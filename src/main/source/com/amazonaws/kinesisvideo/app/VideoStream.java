
package com.amazonaws.kinesisvideo.app;

import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.kinesisvideo.client.KinesisVideoClient;
import com.amazonaws.kinesisvideo.common.exception.KinesisVideoException;
import com.amazonaws.kinesisvideo.internal.client.mediasource.MediaSource;
import com.amazonaws.kinesisvideo.java.client.KinesisVideoJavaClientFactory;
import com.amazonaws.kinesisvideo.java.mediasource.file.ImageFileMediaSource;
import com.amazonaws.kinesisvideo.java.mediasource.file.ImageFileMediaSourceConfiguration;
import com.amazonaws.regions.Regions;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

import java.io.FileOutputStream;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avformat.av_register_all;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR;
import static org.bytedeco.ffmpeg.global.swscale.sws_getContext;

public class VideoStream {
    private static final String STREAM_NAME = "eriksVstream";
    private static final int FPS_25 = 25;
    private static final int RETENTION_ONE_HOUR = 1;
    private static final String IMAGE_DIR = "src/main/resources/data/h264/";
    private static final String FRAME_DIR = "src/main/resources/data/audio-video-frames";
    // CHECKSTYLE:SUPPRESS:LineLength
    // Need to get key frame configured properly so the output can be decoded. h264 files can be decoded using gstreamer plugin
    // gst-launch-1.0 rtspsrc location="YourRtspUri" short-header=TRUE protocols=tcp ! rtph264depay ! decodebin ! videorate ! videoscale ! vtenc_h264_hw allow-frame-reordering=FALSE max-keyframe-interval=25 bitrate=1024 realtime=TRUE ! video/x-h264,stream-format=avc,alignment=au,profile=baseline,width=640,height=480,framerate=1/25 ! multifilesink location=./frame-%03d.h264 index=1
    private static final String IMAGE_FILENAME_FORMAT = "frame-%04d.h264";
    private static final int START_FILE_INDEX = 23;
    private static final int END_FILE_INDEX = 9000;
    private static final String DEFAULT_REGION = "us-east-1";

    // FFMPEG
    private static int dst_w, dst_h, ret;
    private static int src_w = 640, src_h = 360;
    private static AVCodec codec;
    private static AVCodecContext c;
    private static AVPacket pkt;
    private static AVFrame picture;
    private static SwsContext sws_ctx;
    private static int src_pix_fmt = AV_PIX_FMT_ARGB, dst_pix_fmt = AV_PIX_FMT_YUV420P;

    private static PointerPointer<BytePointer> dst_data = new PointerPointer<>(4);
    private static IntPointer dst_linesize = new IntPointer(4);
    private static AVFrame encodeFrame;
    private static int encodeIndex = 0;

    public static FileOutputStream fos = null;

    public static void initFFMPEG() {
        av_register_all();

        codec = avcodec_find_encoder(AV_CODEC_ID_H264);
        if (codec.isNull()) {
            System.out.println("Codec h264 not found.");
            System.exit(-1);
        }
        c = avcodec_alloc_context3(codec);
        if (c.isNull()) {
            System.out.println("Could not allocate video codec context\n");
            System.exit(-1);
        }

        pkt = av_packet_alloc();
        if (pkt.isNull()){
            System.out.println("Not alloc packet\n");
            System.exit(-1);
        }

        dst_w = 640;
        dst_h = 360;

        /* put sample parameters */
        c.bit_rate(400000);
        /* resolution must be a multiple of two */
        c.width(640);
        c.height(360);
        /* frames per second */
        AVRational frame_rate = av_d2q(25, 1001000);
        c.time_base(av_inv_q(frame_rate));

        /* emit one intra frame every ten frames
         * check frame pict_type before passing frame
         * to encoder, if frame->pict_type is AV_PICTURE_TYPE_I
         * then gop_size is ignored and the output of encoder
         * will always be I frame irrespective to gop_size
         */
        c.gop_size(10);
        c.max_b_frames(1);
        c.pix_fmt(AV_PIX_FMT_YUV420P);
        if (codec.id() == AV_CODEC_ID_H264)
            av_opt_set(c.priv_data(), "preset", "fast", 0);

        /* open it */
        ret = avcodec_open2(c, codec, (PointerPointer) null);
        if (ret < 0) {
            System.out.println("Could not open codec: " + ret);
            System.exit(-1);
        }

        sws_ctx = sws_getContext(src_w, src_h, src_pix_fmt,
                dst_w, dst_h, dst_pix_fmt,
                SWS_BILINEAR, null, null, (double[]) null);
        if (sws_ctx.isNull()) {
            System.out.println("Impossible to create scale context for the conversion " + "fmt:%s s:%dx%d -> fmt:%s s:%dx%d\n" + av_get_pix_fmt_name(src_pix_fmt) + src_w + src_h + av_get_pix_fmt_name(dst_pix_fmt) + dst_w + dst_h);
            System.exit(-1);
        }

        if ((ret = av_image_alloc(dst_data, dst_linesize, c.width(), c.height(), dst_pix_fmt, 1)) < 0) {}

        picture = av_frame_alloc();
    }

    public static void startKinesisVideo() {
        try {
            final KinesisVideoClient kinesisVideoClient = KinesisVideoJavaClientFactory
                    .createKinesisVideoClient(
                            Regions.US_EAST_1,
                            new ProfileCredentialsProvider());

            final MediaSource mediaSource = createImageFileMediaSource();
            kinesisVideoClient.registerMediaSource(mediaSource);
            mediaSource.start();

        } catch (final KinesisVideoException e) {
            throw new RuntimeException(e);
        }
    }

    public static MediaSource createImageFileMediaSource() {
        final ImageFileMediaSourceConfiguration configuration =
                new ImageFileMediaSourceConfiguration.Builder()
                        .fps(FPS_25)
                        .dir(IMAGE_DIR)
                        .filenameFormat(IMAGE_FILENAME_FORMAT)
                        .startFileIndex(START_FILE_INDEX)
                        .endFileIndex(END_FILE_INDEX)
                        //.contentType("video/hevc") // for h265
                        .build();
        final ImageFileMediaSource mediaSource = new ImageFileMediaSource(STREAM_NAME);
        mediaSource.configure(configuration);

        return mediaSource;
    }
}
