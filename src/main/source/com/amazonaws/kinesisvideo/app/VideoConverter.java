package com.amazonaws.kinesisvideo.app;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avcodec.AVPicture;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avformat.av_register_all;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.swscale.*;
import java.util.concurrent.*;

public class VideoConverter {
    private final int dst_w;
    private final int dst_h;
    private final int src_w;
    private final int src_h;
    private final int src_pix_fmt;
    private final int dst_pix_fmt;
    private AVCodec codec;
    private AVCodecContext c;
    private AVPacket pkt;
    private AVFrame picture;
    private SwsContext sws_ctx;

    private PointerPointer<BytePointer> dst_data = new PointerPointer<>(4);
    private IntPointer dst_linesize = new IntPointer(4);
    private AVFrame encodeFrame;
    private int encodeIndex = 0;

    private ExecutorService executor = Executors.newFixedThreadPool(1);
    private boolean isRunning = false;
    private final Semaphore mutex;

    public static int MAX_LIST_SIZE = 30;


    public VideoConverter(Semaphore mutex, int width, int height, int src_pix_fmt, int dst_pix_fmt) {
        this.mutex = mutex;

        this.src_w = width;
        this.src_h = height;
        this.dst_w = width;
        this.dst_h = height;

        this.src_pix_fmt = src_pix_fmt;
        this.dst_pix_fmt = dst_pix_fmt;
    }

    public VideoConverter(Semaphore mutex, int width, int height, int src_pix_fmt) {
        this(mutex, width, height, src_pix_fmt, AV_PIX_FMT_YUV420P);
    }

    public VideoConverter(Semaphore mutex, int width, int height) {
        this(mutex, width, height, AV_PIX_FMT_ARGB, AV_PIX_FMT_YUV420P);
    }

    public int init() {
        av_register_all();

        this.codec = avcodec_find_encoder(AV_CODEC_ID_H264);
        if (this.codec.isNull()) {
            System.out.println("Codec h264 not found.");
            return -1;
        }
        this.c = avcodec_alloc_context3(this.codec);
        if (this.c.isNull()) {
            System.out.println("Could not allocate video codec context\n");
            return -1;
        }

        this.pkt = av_packet_alloc();
        if (this.pkt.isNull()){
            System.out.println("Not alloc packet\n");
            return -1;
        }

        /* put sample parameters */
        this.c.bit_rate(400000);
        /* resolution must be a multiple of two */
        this.c.width(this.dst_w);
        this.c.height(this.dst_h);
        /* frames per second */
        AVRational frame_rate = av_d2q(25, 1001000);
        this.c.time_base(av_inv_q(frame_rate));

        /* emit one intra frame every ten frames
         * check frame pict_type before passing frame
         * to encoder, if frame->pict_type is AV_PICTURE_TYPE_I
         * then gop_size is ignored and the output of encoder
         * will always be I frame irrespective to gop_size
         */
        this.c.gop_size(10);
        this.c.max_b_frames(1);
        this.c.pix_fmt(AV_PIX_FMT_YUV420P);
        if (this.codec.id() == AV_CODEC_ID_H264)
            av_opt_set(this.c.priv_data(), "preset", "fast", 0);

        /* open it */
        int ret = avcodec_open2(this.c, this.codec, (PointerPointer) null);
        if (ret < 0) {
            System.out.println("Could not open codec: " + ret);
            return -1;
        }

        this.sws_ctx = sws_getContext(this.src_w, this.src_h, this.src_pix_fmt,
                this.dst_w, this.dst_h, this.dst_pix_fmt,
                SWS_BILINEAR, null, null, (double[]) null);
        if (this.sws_ctx.isNull()) {
            System.out.println("Impossible to create scale context for the conversion " + "fmt:%s s:%dx%d -> fmt:%s s:%dx%d\n" + av_get_pix_fmt_name(this.src_pix_fmt) + this.src_w + this.src_h + av_get_pix_fmt_name(this.dst_pix_fmt) + this.dst_w + this.dst_h);
            return -1;
        }

        if ((ret = av_image_alloc(this.dst_data, this.dst_linesize, this.c.width(), this.c.height(), this.dst_pix_fmt, 1)) < 0) {
            System.out.println("Could not alloc image: " + ret);
            return -1;
        }

        picture = av_frame_alloc();
        return 0;
    }

    public void start() {
        if (isRunning) {
            throw new IllegalStateException("Frame source is already running");
        }

        isRunning = true;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                System.out.println("Video Converter Start");
                while (isRunning) {
                    convert();
                }
            }
        });
    }

    public void stop() {
        isRunning = false;
        executor.shutdown();
    }

    public void convert() {
        if (AppMain.videoList.size() >= MAX_LIST_SIZE) {
//            System.out.println("The count of frame list is more than " + MAX_LIST_SIZE);
            return;
        }

        VideoFrame frame = null;
        try {
            this.mutex.acquire();
            frame = AppMain.videoFrame;
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            this.mutex.release();
        }

        if (frame == null) {
            return;
        }

        byte[] bytes = new byte[frame.buffer.getHeight() * frame.buffer.getWidth() * 4];
        try {
            VideoBufferConverter.convertFromI420(frame.buffer, bytes, FourCC.BGRA);

            // Part-1
            avpicture_fill(new AVPicture(picture), bytes, src_pix_fmt, c.width(), c.height());
            sws_scale(sws_ctx, picture.data(), picture.linesize(), 0, c.height(), dst_data, dst_linesize);

            encodeFrame = av_frame_alloc();

            if (encodeFrame.isNull()) {
                System.out.println("Could not allocate video frame");
                return ;
            }
            encodeFrame.format(c.pix_fmt());
            encodeFrame.width(c.width());
            encodeFrame.height(c.height());

            avpicture_fill(new AVPicture(encodeFrame), new BytePointer(dst_data.get(0)), dst_pix_fmt, c.width(), c.height());
            encodeFrame.pts(encodeIndex);
            encodeIndex++;

            /* encode the image */
            int ret = avcodec_send_frame(c, encodeFrame);
            if (ret < 0) {
                System.out.println("Error sending a frame for encoding");
                return ;
            }
            while (ret >= 0) {
                ret = avcodec_receive_packet(c, pkt);
                if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF)
                    return ;
                else if (ret < 0) {
                    System.out.println("Error during encoding");
                    return ;
                }

                if (pkt.size() > 0) {
                    byte[] writeData = new byte[pkt.size()];
                    pkt.data().get(writeData, 0, pkt.size());

                    try {
                        this.mutex.acquire();
                        AppMain.videoList.add(writeData);
                        System.out.println("VideoConverter " + AppMain.videoList.size());
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        this.mutex.release();
                    }
                }

                try {
                    Thread.sleep(10); // sleep in 10ms
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                av_packet_unref(pkt);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
