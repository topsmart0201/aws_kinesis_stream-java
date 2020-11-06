
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
import com.amazonaws.kinesisvideo.app.contants.TrackInfos;
import com.amazonaws.kinesisvideo.internal.client.mediasource.MediaSource;
import com.amazonaws.kinesisvideo.java.client.KinesisVideoJavaClientFactory;
import com.amazonaws.kinesisvideo.java.mediasource.file.AudioVideoFileMediaSource;
import com.amazonaws.kinesisvideo.java.mediasource.file.AudioVideoFileMediaSourceConfiguration;
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
import dev.onvoid.webrtc.media.MediaStreamTrackState;
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

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import static com.amazonaws.kinesisvideo.util.StreamInfoConstants.ABSOLUTE_TIMECODES;
import static java.util.Objects.nonNull;
import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.av_register_all;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

// FFMPEG

/**
 * Java Producer.
 */
public final class AppMain {
    // Use a different stream name when testing audio/video sample
    private static final int FPS_10 = 10;
    private static final int FPS_15 = 15;
    private static final int FPS_20 = 20;
    private static final int FPS_25 = 25;
    private static final int FPS_30 = 30;
    private static final int FPS_60 = 60;
    private static final long FRAME_DURATION = 40L;
    private static int frameRate = FPS_25;
    private static final int FRAME_WIDTH = 1280;
    private static final int FRAME_HEIGHT = 720;
    private static final int RETENTION_ONE_HOUR = 1;
    private static final String IMAGE_DIR = "src/main/resources/data/h264/";
    private static final String FRAME_DIR = "src/main/resources/data/audio-video-frames";
    // CHECKSTYLE:SUPPRESS:LineLength
    // Need to get key frame configured properly so the output can be decoded. h264 files can be decoded using gstreamer plugin
    // gst-launch-1.0 rtspsrc location="YourRtspUri" short-header=TRUE protocols=tcp ! rtph264depay ! decodebin ! videorate ! videoscale ! vtenc_h264_hw allow-frame-reordering=FALSE max-keyframe-interval=25 bitrate=1024 realtime=TRUE ! video/x-h264,stream-format=avc,alignment=au,profile=baseline,width=640,height=480,framerate=1/25 ! multifilesink location=./frame-%03d.h264 index=1
    private static final String IMAGE_FILENAME_FORMAT = "frame-%04d.h264";
    private static final int START_FILE_INDEX = 23;
    private static final int END_FILE_INDEX = 9000;
    private static final String DEFAULT_REGION = System.getProperty("aws.region");
    private static final List<ResourceEndpointListItem> mEndpointList = new ArrayList<>();

    private static String mChannelArn = "";
    private static String mWssEndpoint = "";
    private static String mClientId = "";
    private static RTCPeerConnection localPeer;

    private static volatile SignalingServiceWebSocketClient client;
    private static String recipientClientId = "";
    private static PeerConnectionFactory peerConnectionFactory;
    private static boolean isAudioSent = false;
    private static AudioTrack localAudioTrack;
    private static VideoTrack localVideoTrack;
    private static List<RTCIceServer> peerIceServers = new ArrayList<>();
    private static GetIceServerConfigResult iceServerConfigResult = null;
    private static Configuration peerConfiguration = new Configuration();

    // FFMPEG
    public static VideoConverter videoConverter = null;

    public static VideoFrame videoFrame = null;
    public static FrameBuffer frameBuffer = null;
    public static Vector<FrameBuffer> videoFrames = new Vector<>();

    public static FileOutputStream fos = null;
    public static Semaphore mutex = new Semaphore(1);
    public static Semaphore frame_mutex = new Semaphore(1);
    public static long firstPickTime = 0;
    public static Vector<H264Packet> videoList = new Vector<>();
    public static Vector<Integer> videoListKeyFlags = new Vector<>();
    public static ExecutorService executor = Executors.newFixedThreadPool(1);
    public static boolean isInit = false;

    public static String kvsStream = System.getProperty("kvs-stream");
    public static String kvsChannel = System.getProperty("kvs-channel");

    public static long encodeIndex = 0;

    private AppMain() {
        throw new UnsupportedOperationException();
    }

    public static void main(final String[] args) {
        System.out.println(DEFAULT_REGION + " " + kvsStream + " " + kvsChannel);

        startKinesisVideo();

        final AmazonKinesisVideo kinesis_client = AmazonKinesisVideoAsyncClient.builder()
                .withCredentials(AuthHelper.getSystemPropertiesCredentialsProvider())
                .withRegion(DEFAULT_REGION)
                .build();

        DescribeSignalingChannelRequest descRequest = new DescribeSignalingChannelRequest();
        descRequest.setChannelName(kvsChannel);

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
                    System.out.println("Signaling service is connected: " +
                            "Sending offer as viewer to remote peer"); // Viewer

                    createSdpOffer();
                }
            } else {
                System.out.println("Error in connecting to signaling service");
            }
        }
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
                    RTCRtpTransceiver receiver = transceiver;
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
        // FFMPEG
        if (!isInit) {
            Timestamp timestamp = new Timestamp(System.currentTimeMillis());
            firstPickTime = timestamp.getTime();
            startVideoFrames();
            videoConverter = new VideoConverter(mutex, FRAME_WIDTH, FRAME_HEIGHT, frameRate);
            int ret = videoConverter.init();
            if (ret == 0) {
                videoConverter.start();
                System.out.println("VideoConverter Init");
            } else {
                System.out.println("Failed the init video converter");
                videoConverter.stop();
                System.exit(-1);
            }
            isInit = true;
        }

        VideoFrameBuffer buf = null;
        int w = 0, h = 0;
        try {
            buf = frame.buffer;
            w = frame.buffer.getWidth();
            h = frame.buffer.getHeight();
            byte[] bytes = new byte[w * h * 4];
            VideoBufferConverter.convertFromI420(buf, bytes, FourCC.BGRA);
            frame_mutex.acquire();
            Timestamp timestamp = new Timestamp(System.currentTimeMillis());
            long current = timestamp.getTime();
            System.out.println("Publish Frame: "+current);
            frameBuffer = new FrameBuffer(bytes, w, h);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            frame_mutex.release();
        }
    }

    private static void startVideoFrames() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    Timestamp timestamp = new Timestamp(System.currentTimeMillis());
                    long current = timestamp.getTime();
                    if (firstPickTime + FRAME_DURATION < current) {
                        firstPickTime = current - (current  % FRAME_DURATION);
                        try {
                            frame_mutex.acquire();
                            if (frameBuffer != null) {
                                FrameBuffer tmpBuffer = new FrameBuffer(frameBuffer.bytes, frameBuffer.getWidth(), frameBuffer.getHeight());
                                tmpBuffer.setPts(firstPickTime);
                                videoFrames.add(tmpBuffer);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            frame_mutex.release();
                        }
                    }
                    try {
                        Thread.sleep(10); // sleep in 10ms
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    private static int getFps(int count, long start, long end) {
        long space = end - start;
        int fps = (int) (count / (space / 1000));
        int result;

        if (fps <= FPS_10) {
            result = FPS_10;
        } else if (fps <= FPS_15) {
            result = FPS_15;
        } else if (fps <= FPS_20) {
            result = FPS_20;
        } else if (fps <= FPS_25) {
            result = FPS_25;
        } else if (fps <= FPS_30 +  5) {
            result = FPS_30;
        } else {
            result = FPS_60;
        }

        return result;
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

    /**
     * Create a MediaSource based on local sample H.264 frames.
     *
     * @return a MediaSource backed by local H264 frame files
     */
    private static MediaSource createImageFileMediaSource() {
        final ImageFileMediaSourceConfiguration configuration =
                new ImageFileMediaSourceConfiguration.Builder()
                        .fps(frameRate)
                        .dir(IMAGE_DIR)
                        .filenameFormat(IMAGE_FILENAME_FORMAT)
                        .startFileIndex(START_FILE_INDEX)
                        .endFileIndex(END_FILE_INDEX)
                        //.contentType("video/hevc") // for h265
                        .build();
        final ImageFileMediaSource mediaSource = new ImageFileMediaSource(kvsStream);
        mediaSource.configure(configuration);

        return mediaSource;
    }

    /**
     * Create a MediaSource based on local sample H.264 frames and AAC frames.
     *
     * @return a MediaSource backed by local H264 and AAC frame files
     */
    private static MediaSource createFileMediaSource() {
        final AudioVideoFileMediaSourceConfiguration configuration =
                new AudioVideoFileMediaSourceConfiguration.AudioVideoBuilder()
                        .withDir(FRAME_DIR)
                        .withRetentionPeriodInHours(RETENTION_ONE_HOUR)
                        .withAbsoluteTimecode(ABSOLUTE_TIMECODES)
                        .withTrackInfoList(TrackInfos.createTrackInfoList())
                        .build();
        final AudioVideoFileMediaSource mediaSource = new AudioVideoFileMediaSource(kvsStream);
        mediaSource.configure(configuration);

        return mediaSource;
    }
}




