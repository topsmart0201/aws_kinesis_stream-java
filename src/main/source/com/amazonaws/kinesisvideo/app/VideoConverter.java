package com.amazonaws.kinesisvideo.app;

import com.amazonaws.kinesisvideo.signaling.model.Message;
import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.entity.EntityBuilder;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ContentType;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avcodec.AVPicture;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.amazonaws.kinesisvideo.producer.Time.HUNDREDS_OF_NANOS_IN_A_MILLISECOND;
import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avformat.av_register_all;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.swscale.*;
import java.util.concurrent.*;

public class VideoConverter {
    private static int default_width = 0;
    private static int default_height = 0;
    private int dst_w;
    private int dst_h;
    private int src_w;
    private int src_h;
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
    private int fps;

    public static int MAX_LIST_SIZE = 30;

    public VideoConverter(Semaphore mutex, int width, int height, int fps, int src_pix_fmt, int dst_pix_fmt) {
        this.mutex = mutex;
        this.fps = fps;

        default_width = width;
        default_height = height;
        this.src_w = width;
        this.src_h = height;
        this.dst_w = width;
        this.dst_h = height;

        this.src_pix_fmt = src_pix_fmt;
        this.dst_pix_fmt = dst_pix_fmt;
    }

    public VideoConverter(Semaphore mutex, int width, int height, int fps, int src_pix_fmt) {
        this(mutex, width, height, fps, src_pix_fmt, AV_PIX_FMT_YUV420P);
    }

    public VideoConverter(Semaphore mutex, int width, int height, int fps) {
        this(mutex, width, height, fps, AV_PIX_FMT_ARGB, AV_PIX_FMT_YUV420P);
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
//        this.c.bit_rate(1000000);
        /* resolution must be a multiple of two */
        this.c.width(this.dst_w);
        this.c.height(this.dst_h);
        /* frames per second */
//        AVRational frame_rate = av_d2q(25, 1001000);
        AVRational frame_rate = av_d2q(fps, 1000);
        this.c.time_base(av_inv_q(frame_rate));
        this.c.framerate(frame_rate);

        /* emit one intra frame every ten frames
         * check frame pict_type before passing frame
         * to encoder, if frame->pict_type is AV_PICTURE_TYPE_I
         * then gop_size is ignored and the output of encoder
         * will always be I frame irrespective to gop_size
         */
        this.c.gop_size(fps);
	this.c.qmin(10);
	this.c.qmax(51);
        this.c.keyint_min(fps);
        this.c.max_b_frames(20);
//        this.c.scenechange_threshold(0);
        this.c.pix_fmt(AV_PIX_FMT_YUV420P);
        if (this.codec.id() == AV_CODEC_ID_H264) {
//        av_opt_set(this.c.priv_data(), "x264opts", "bitrate=2M:vbv-maxrate=2M", 0);
            av_opt_set(this.c.priv_data(), "preset", "medium", 0);
//            av_opt_set(this.c.priv_data(), "x264opts", "no-scenecut", 0);
        }

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

        avcodec_free_context(c);
        av_frame_free(encodeFrame);
        av_packet_free(pkt);
        sws_freeContext(sws_ctx);
    }

    public void convert() {
        //Replace AppMain to Test
        if (KVSStream.videoList.size() >= MAX_LIST_SIZE) {
//            System.out.println("The count of frame list is more than " + MAX_LIST_SIZE);
            return;
        } else if (KVSStream.videoFrames.size() == 0){
            try {
                Thread.sleep(10); // sleep in 10ms
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return;
        }

        FrameBuffer frame = null;
        try {
            KVSStream.frame_mutex.acquire();
            if (KVSStream.videoFrames.size() > 0) {
                FrameBuffer temp = KVSStream.videoFrames.firstElement();
                frame = new FrameBuffer(temp.bytes, temp.getWidth(), temp.getHeight());
                frame.setPts(temp.getPts());
                KVSStream.videoFrames.remove(0);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            KVSStream.frame_mutex.release();
        }

        if (frame == null) {
            return;
        }

        if (default_width != frame.getWidth() || default_height != frame.getHeight()) {
            default_width = src_w = frame.getWidth();
            default_height = src_h = frame.getHeight();
            sws_ctx = sws_getContext(src_w, src_h, src_pix_fmt,
                    dst_w, dst_h, dst_pix_fmt,
                    SWS_BILINEAR, null, null, (double[]) null);
            System.out.println("Image changed");

            if (sws_ctx.isNull()) {
                System.out.println("Impossible to create scale context for the conversion " + "fmt:%s s:%dx%d -> fmt:%s s:%dx%d\n" + av_get_pix_fmt_name(this.src_pix_fmt) + this.src_w + this.src_h + av_get_pix_fmt_name(this.dst_pix_fmt) + this.dst_w + this.dst_h);
                return ;
            }
        }

        try {
            // Part-1
            avpicture_fill(new AVPicture(picture), frame.bytes, src_pix_fmt, default_width, default_height);
            sws_scale(sws_ctx, picture.data(), picture.linesize(), 0, default_height, dst_data, dst_linesize);

            encodeFrame = av_frame_alloc();

            if (encodeFrame.isNull()) {
                System.out.println("Could not allocate video frame");
                return ;
            }
            encodeFrame.format(c.pix_fmt());
            encodeFrame.width(c.width());
            encodeFrame.height(c.height());

            avpicture_fill(new AVPicture(encodeFrame), new BytePointer(dst_data.get(0)), dst_pix_fmt, c.width(), c.height());
//            System.out.println("Send Frame pts: " + frame.getPts());
            encodeFrame.pts(frame.getPts());
//            encodeFrame.pts(encodeIndex);
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

                    Timestamp timestamp = new Timestamp(System.currentTimeMillis());
                    long current = timestamp.getTime();
//                    System.out.println("Convert:  pts :"+pkt.pts() + " dts:"+pkt.dts() + " flags:"+pkt.flags() + " duration:"+pkt.duration());

/*                    try {
                        FileOutputStream fos = null;
                        String filename = String.format("frame-%04d.h264", encodeIndex);
                        fos = new FileOutputStream("/home/ubuntu/h264/" + filename);
                        fos.write(writeData, 0, pkt.size());
                        fos.close();
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                        System.exit(-1);
                    } catch (IOException e) {
                        e.printStackTrace();
                        System.exit(-1);
                    }*/

                    try {
                        this.mutex.acquire();
                        KVSStream.videoList.add(new H264Packet(writeData, pkt.pts(), pkt.dts(), pkt.duration(), pkt.flags()));
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        this.mutex.release();
                    }
                }
                av_packet_unref(pkt);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
