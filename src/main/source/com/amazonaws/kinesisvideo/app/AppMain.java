package com.amazonaws.kinesisvideo.app;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.kinesisvideo.client.KinesisVideoClient;
import com.amazonaws.kinesisvideo.common.exception.KinesisVideoException;
import com.amazonaws.kinesisvideo.config.Configuration;
import com.amazonaws.kinesisvideo.config.VideoConfiguration;
import com.amazonaws.kinesisvideo.app.auth.AuthHelper;
import com.amazonaws.kinesisvideo.internal.client.mediasource.MediaSource;
import com.amazonaws.kinesisvideo.java.client.KinesisVideoJavaClientFactory;
import com.amazonaws.kinesisvideo.java.mediasource.file.ImageFileMediaSource;
import com.amazonaws.kinesisvideo.java.mediasource.file.ImageFileMediaSourceConfiguration;
import com.amazonaws.kinesisvideo.signaling.SignalingListener;
import com.amazonaws.kinesisvideo.signaling.model.Event;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.amazonaws.kinesisvideo.signaling.tyrus.SignalingServiceWebSocketClient;
import com.amazonaws.kinesisvideo.utils.AwsV4Signer;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoAsyncClient;
import com.amazonaws.services.kinesisvideo.model.*;
import com.amazonaws.services.kinesisvideosignalingchannels.AmazonKinesisVideoSignalingChannels;
import com.amazonaws.services.kinesisvideosignalingchannels.AmazonKinesisVideoSignalingChannelsClient;
import com.amazonaws.services.kinesisvideosignalingchannels.model.GetIceServerConfigRequest;
import com.amazonaws.services.kinesisvideosignalingchannels.model.GetIceServerConfigResult;
import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioSource;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.*;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avcodec.AVPicture;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.BytePointer;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import static java.util.Objects.nonNull;
import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.av_register_all;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * Demo Java Producer.
 */
public final class AppMain {
    // Use a different stream name when testing audio/video sample
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
    private static final List<ResourceEndpointListItem> mEndpointList = new ArrayList<>();

    private static String mChannelArn = "";
    private static String mWssEndpoint = "";
    private static String mClientId = "";
    private static RTCPeerConnection localPeer;

    private static volatile SignalingServiceWebSocketClient client;
    private static String recipientClientId = "";
    private static PeerConnectionFactory peerConnectionFactory;
    private static List<RTCIceServer> peerIceServers = new ArrayList<>();
    private static GetIceServerConfigResult iceServerConfigResult = null;
    private static Configuration peerConfiguration = new Configuration();

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

    private AppMain() {
        throw new UnsupportedOperationException();
    }

    public static void main(final String[] args) {
        initFFMPEG();

        final AmazonKinesisVideo kinesis_client = AmazonKinesisVideoAsyncClient.builder()
                .withCredentials(AuthHelper.getSystemPropertiesCredentialsProvider())
                .withRegion(DEFAULT_REGION)
                .build();

        DescribeSignalingChannelRequest descRequest = new DescribeSignalingChannelRequest();
        descRequest.setChannelName("eriks");

        DescribeSignalingChannelResult descResult = kinesis_client.describeSignalingChannel(descRequest);

        List<String> protocols = new ArrayList<>();
        protocols.add("WSS");
        protocols.add("HTTPS");

        SingleMasterChannelEndpointConfiguration configuration = new SingleMasterChannelEndpointConfiguration();
        configuration.setProtocols(protocols);
        configuration.setRole("VIEWER");

        GetSignalingChannelEndpointRequest request = new GetSignalingChannelEndpointRequest();
        request.setChannelARN(descResult.getChannelInfo().getChannelARN());
        request.setSingleMasterChannelEndpointConfiguration(configuration);
        System.out.println(descResult.getChannelInfo().getChannelARN());
        try {
            final GetSignalingChannelEndpointResult result = kinesis_client.getSignalingChannelEndpoint(request);
            mEndpointList.addAll(result.getResourceEndpointList());
        } catch (Exception e) {
            System.out.println(e);
        }

        String wsHost = null;
        String httpsHost = null;

        for (ResourceEndpointListItem endpoint : mEndpointList) {
            if (endpoint.getProtocol().equals("WSS")) {
                mWssEndpoint = endpoint.getResourceEndpoint();
            }
            if (endpoint.getProtocol().equals("HTTPS")) {
                httpsHost = endpoint.getResourceEndpoint();
            }
        }

        mClientId = UUID.randomUUID().toString();
        mChannelArn = descResult.getChannelInfo().getChannelARN();
        AWSCredentials mCreds = AuthHelper.getSystemPropertiesCredentialsProvider().getCredentials();
        String viewerEndpoint = mWssEndpoint + "?X-Amz-ChannelARN=" + mChannelArn + "&X-Amz-ClientId=" + mClientId;
        URI signedUri;
        signedUri =  AwsV4Signer.sign(URI.create(viewerEndpoint), mCreds.getAWSAccessKeyId(),
                mCreds.getAWSSecretKey(), mCreds instanceof AWSSessionCredentials ? ((AWSSessionCredentials)mCreds).getSessionToken() : "", URI.create(mWssEndpoint), DEFAULT_REGION);

        wsHost = signedUri.toString();
        System.out.println(httpsHost +" : "+ wsHost);


        AwsClientBuilder.EndpointConfiguration endpointConfiguration = new AwsClientBuilder.EndpointConfiguration(httpsHost, DEFAULT_REGION);

        GetIceServerConfigRequest iceServerConfigRequest = new GetIceServerConfigRequest();
        iceServerConfigRequest.setChannelARN(descResult.getChannelInfo().getChannelARN());

        AmazonKinesisVideoSignalingChannels kinesisVideoSignalingChannels = AmazonKinesisVideoSignalingChannelsClient.builder()
                .withCredentials(AuthHelper.getSystemPropertiesCredentialsProvider())
                .withEndpointConfiguration(endpointConfiguration)
                .build();

        iceServerConfigResult = kinesisVideoSignalingChannels.getIceServerConfig(iceServerConfigRequest);

        final SignalingListener signalingListener = new SignalingListener() {

            @Override
            public void onSdpOffer(final Event offerEvent) {
                System.out.println("Received SDP Offer: Setting Remote Description ");

                final String sdp = Event.parseOfferEvent(offerEvent);

                localPeer.setRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, sdp), new SetSDObserver());

                recipientClientId = offerEvent.getSenderClientId();

                System.out.println("Received SDP offer: Creating answer " + recipientClientId);

                createSdpAnswer();
            }

            @Override
            public void onSdpAnswer(final Event answerEvent) {

                System.out.println("SDP answer received from signaling");

                final String sdp = Event.parseSdpEvent(answerEvent);

                final RTCSessionDescription sdpAnswer = new RTCSessionDescription(RTCSdpType.ANSWER, sdp);

                localPeer.setRemoteDescription(sdpAnswer, new SetSDObserver() {

                    @Override
                    public void onSuccess() { System.out.println("Success Remote Description");
                    }

                });
            }

            @Override
            public void onIceCandidate(Event message) {

                System.out.println("Received IceCandidate from remote ");

                final RTCIceCandidate iceCandidate = Event.parseIceCandidate(message);

                if(iceCandidate != null) {
                    localPeer.addIceCandidate(iceCandidate);
                } else {
                    System.out.println("Invalid Ice candidate");
                }
            }

            @Override
            public void onError(Event errorMessage) {
                System.out.println("Received error message" + errorMessage);
            }

            @Override
            public void onException(Exception e) {
                System.out.println("Signaling client returned exception " + e.getMessage());
            }
        };

        if (wsHost != null) {
            try {
                client = new SignalingServiceWebSocketClient(wsHost, signalingListener, Executors.newFixedThreadPool(10));

                System.out.println("Client connection " + (client.isOpen() ? "Successful" : "Failed"));
            } catch (Exception e) {
                System.out.println(e);
            }

            if (isValidClient()) {
                System.out.println("Client connected to Signaling service " + client.isOpen());

                if (true) {
                    System.out.println("Signaling service is connected: " + "Sending offer as viewer to remote peer"); // Viewer
                    createSdpOffer();
                }
            } else {
                System.out.println("Error in connecting to signaling service");
            }
        }
    }

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

    private static boolean isValidClient() {
        return client != null && client.isOpen();
    }

    private static class CreateSDObserver implements CreateSessionDescriptionObserver {
        @Override
        public void onSuccess(RTCSessionDescription description) {
            System.out.println("CreateSessionDescriptionObserver: onSuccess");
            localPeer.setLocalDescription(description, new SetSDObserver() {
                @Override
                public void onSuccess() {
                super.onSuccess();
                System.out.println("CreateSessionDescriptionObserver: onSuccess");
                try {
                    Message sdpOfferMessage = Message.createOfferMessage(description, mClientId);

                    if (isValidClient()) {
                        client.sendSdpOffer(sdpOfferMessage);
                    } else {
                    }
                } catch (Exception e) {
                    System.out.println("Send RTCSessionDescription failed" + e);
                }
                }
            });
        }

        @Override
        public void onFailure(String error) {
            System.out.println("CreateSessionDescriptionObserver: onFailure");
        }
    }

    private static class SetSDObserver implements SetSessionDescriptionObserver {
        @Override
        public void onSuccess() {
            System.out.println("SetSessionDescriptionObserver: onSuccess");
        }

        @Override
        public void onFailure(String error) {
            System.out.println("SetSessionDescriptionObserver: onFailure");
        }
    }

    private static void createSdpOffer() {
        if (localPeer == null) {
            createLocalPeerConnection();
        }

        RTCOfferOptions options = new RTCOfferOptions();
        localPeer.createOffer(options, new CreateSDObserver());
    }

    private static void createLocalPeerConnection() {
        RTCIceServer server = new RTCIceServer();
        server.urls.add(String.format("stun:stun.kinesisvideo.%s.amazonaws.com:443", DEFAULT_REGION));
        peerIceServers.add(server);
        for (int i = 0; i < iceServerConfigResult.getIceServerList().size(); i ++) {
            RTCIceServer server1 = new RTCIceServer();
            server1.urls = iceServerConfigResult.getIceServerList().get(i).getUris();
            server1.password = iceServerConfigResult.getIceServerList().get(i).getPassword();
            server1.username = iceServerConfigResult.getIceServerList().get(i).getUsername();
            peerIceServers.add(server1);
        }

        peerConnectionFactory = new PeerConnectionFactory();
        peerConfiguration.setIceServers(peerIceServers);

        CompletableFuture.runAsync(() -> {
            localPeer = peerConnectionFactory.createPeerConnection(peerConfiguration.getRTCConfig(), new PeerConnectionObserver() {
                @Override
                public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] mediaStreams) {}

                @Override
                public void onAddStream(MediaStream mediaStream) {}

                @Override
                public void onTrack(RTCRtpTransceiver transceiver) {
                    MediaStreamTrack track = transceiver.getReceiver().getTrack();
                    if (track.getKind().equals(MediaStreamTrack.VIDEO_TRACK_KIND)) {
                        VideoTrack videoTrack = (VideoTrack) track;
                        videoTrack.addSink(frame -> publishFrame(frame));
                    }
                }
                @Override
                public void onIceCandidate(RTCIceCandidate rtcIceCandidate) {
                    Message message = createIceCandidateMessage(rtcIceCandidate);
                    client.sendIceCandidate(message);  /* Send to Peer */
                }
            });
            addVideo(RTCRtpTransceiverDirection.SEND_RECV);
            addAudio(RTCRtpTransceiverDirection.SEND_RECV);
        }).join();
    }

    private static void publishFrame(VideoFrame frame) {
        if (encodeIndex == 50) {
            startKinesisVideo();
        }
        if (encodeIndex > END_FILE_INDEX) {
            System.out.println("EXIT");
            System.exit(-1);
        }
        try {
            String filename = String.format(IMAGE_FILENAME_FORMAT, encodeIndex);
            fos = new FileOutputStream(IMAGE_DIR + filename);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            System.exit(-1);
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
                System.exit(-1);
            }
            encodeFrame.format(c.pix_fmt());
            encodeFrame.width(c.width());
            encodeFrame.height(c.height());

            avpicture_fill(new AVPicture(encodeFrame), new BytePointer(dst_data.get(0)), dst_pix_fmt, c.width(), c.height());
            encodeFrame.pts(encodeIndex);
            encodeIndex++;

            /* encode the image */
            ret = avcodec_send_frame(c, encodeFrame);
            if (ret < 0) {
                System.out.println("Error sending a frame for encoding");
                System.exit(-1);
            }
            while (ret >= 0) {
                ret = avcodec_receive_packet(c, pkt);
                if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF)
                    return;
                else if (ret < 0) {
                    System.out.println("Error during encoding");
                    System.exit(-1);
                }

                if (pkt.size() > 0) {
                    byte[] writeData = new byte[pkt.size()];
                    pkt.data().get(writeData, 0, pkt.size());

                    fos.write(writeData, 0, pkt.size());
                    fos.close();
                }
                av_packet_unref(pkt);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void startKinesisVideo() {
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

    private static void addVideo(RTCRtpTransceiverDirection direction) {
        if (direction == RTCRtpTransceiverDirection.INACTIVE) {
            return;
        }

        VideoConfiguration videoConfig = peerConfiguration.getVideoConfiguration();
        VideoDevice device = videoConfig.getCaptureDevice();
        VideoCaptureCapability capability = videoConfig.getCaptureCapability();

        VideoDeviceSource videoSource = new VideoDeviceSource();

        if (nonNull(device)) {
            videoSource.setVideoCaptureDevice(device);
        }
        if (nonNull(capability)) {
            videoSource.setVideoCaptureCapability(capability);
        }
        VideoTrack videoTrack = peerConnectionFactory.createVideoTrack("videoTrack", videoSource);
        localPeer.addTrack(videoTrack, List.of("stream"));

        for (RTCRtpTransceiver transceiver : localPeer.getTransceivers()) {
            MediaStreamTrack track = transceiver.getSender().getTrack();

            if (nonNull(track) && track.getKind().equals(MediaStreamTrack.VIDEO_TRACK_KIND)) {
                transceiver.setDirection(direction);
                break;
            }
        }
    }

    private static void addAudio(RTCRtpTransceiverDirection direction) {
        if (direction == RTCRtpTransceiverDirection.INACTIVE) {
            return;
        }

        AudioOptions audioOptions = new AudioOptions();

        if (direction != RTCRtpTransceiverDirection.SEND_ONLY) {
            audioOptions.echoCancellation = true;
            audioOptions.noiseSuppression = true;
        }

        AudioSource audioSource = peerConnectionFactory.createAudioSource(audioOptions);
        AudioTrack audioTrack = peerConnectionFactory.createAudioTrack("audioTrack", audioSource);

        localPeer.addTrack(audioTrack, List.of("stream"));

        for (RTCRtpTransceiver transceiver : localPeer.getTransceivers()) {
            MediaStreamTrack track = transceiver.getSender().getTrack();

            if (nonNull(track) && track.getKind().equals(MediaStreamTrack.AUDIO_TRACK_KIND)) {
                transceiver.setDirection(direction);
                break;
            }
        }
    }

    private static void createSdpAnswer() {
        RTCAnswerOptions options = new RTCAnswerOptions();

        localPeer.createAnswer(options, new CreateSDObserver());
    }

    private static Message createIceCandidateMessage(RTCIceCandidate iceCandidate) {
        String sdpMid = iceCandidate.sdpMid;
        int sdpMLineIndex = iceCandidate.sdpMLineIndex;
        String sdp = iceCandidate.sdp;

        String messagePayload =
                "{\"candidate\":\""
                        + sdp
                        + "\",\"sdpMid\":\""
                        + sdpMid
                        + "\",\"sdpMLineIndex\":"
                        + sdpMLineIndex
                        + "}";
        String senderClientId = mClientId;

        Message message = new Message("ICE_CANDIDATE", recipientClientId, senderClientId, new String(Base64.getEncoder().encode(messagePayload.getBytes())));

        return message;
    }

    private static MediaSource createImageFileMediaSource() {
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


