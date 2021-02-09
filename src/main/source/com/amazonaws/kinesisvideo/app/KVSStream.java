package com.amazonaws.kinesisvideo.app;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.kinesisvideo.app.auth.AuthHelper;
import com.amazonaws.kinesisvideo.client.KinesisVideoClient;
import com.amazonaws.kinesisvideo.common.exception.KinesisVideoException;
import com.amazonaws.kinesisvideo.config.Configuration;
import com.amazonaws.kinesisvideo.config.VideoConfiguration;
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
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioSource;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.*;
import java.net.URI;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.Objects.nonNull;
import java.util.concurrent.*;

public final class KVSStream {
    private static final int FPS_25 = 25;

    private static final String IMAGE_DIR = "src/main/resources/data/h264/";
    private static final String IMAGE_FILENAME_FORMAT = "frame-%04d.h264";
    private static final int START_FILE_INDEX = 23;
    private static final int END_FILE_INDEX = 9000;

    private static String kvsStream = "";
    private static String kvsChannel = "";
    public static String sessionID = "";
    public static String email = "";
    public long examTimes[];

    public static volatile SignalingServiceWebSocketClient client;
    private static final String DEFAULT_REGION = System.getProperty("aws.region");
    private static final List<ResourceEndpointListItem> mEndpointList = new ArrayList<>();

    private static String mChannelArn = "";
    private static String mWssEndpoint = "";
    public static String mClientId = "";

    private static GetIceServerConfigResult iceServerConfigResult = null;

    private static RTCPeerConnection localPeer;
    public static String recipientClientId = "";
    private static List<RTCIceServer> peerIceServers = new ArrayList<>();
    private static PeerConnectionFactory peerConnectionFactory;
    private static Configuration peerConfiguration = new Configuration();
    public static boolean isInit = false, isStop = false;
    public static long firstPickTime = 0;
    public static VideoConverter videoConverter = null;

    public static Semaphore mutex = new Semaphore(1);
    public static Semaphore frame_mutex = new Semaphore(1);

    private static final int FRAME_WIDTH = 1280;
    private static final int FRAME_HEIGHT = 720;
    private static int frameRate = FPS_25;
    public static FrameBuffer frameBuffer = null;

    public static ExecutorService executor = Executors.newFixedThreadPool(1);
    private static final long FRAME_DURATION = 40L;
    public static Vector<FrameBuffer> videoFrames = new Vector<>();

    public static Vector<H264Packet> videoList = new Vector<>();
    public static boolean receiveEndExamSignal = false, receiveStartExamSignal = false, receiveSecondExamSignal = false, receiveThirdExamSignal = false, receiveFourthExamSignal = false, receiveFifthExamSignal = false;

    public static MediaSource mediaSource = null;

    private final Gson gson = new Gson();

    public KVSStream(String channelName, String streamName, String sessionId, String email) {
        this.kvsChannel = channelName;
        this.kvsStream = streamName;
        this.sessionID = sessionId;
        this.email = email;
	this.examTimes = new long[5];

    }

    public void init() {
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
                String s = new String(Base64.getDecoder().decode(offerEvent.getMessagePayload()));
                JsonObject jsonObject = new JsonParser().parse(s).getAsJsonObject();
                if (jsonObject.get("type").getAsString().equals("exam")) {

                    int idx = jsonObject.get("idx").getAsInt();
                    boolean endStatus = jsonObject.get("endStatus").getAsBoolean();

                    Timestamp timestamp = new Timestamp(System.currentTimeMillis());
                    examTimes[idx] = timestamp.getTime();

                    if (endStatus)
                    {
                        String messagePayload =
                                "{"
                                        + "\"type\": \"streamEnd\","
                                        + "\"examTimes\":" + gson.toJson(examTimes).toString()
                                        + "}";
			System.out.println(messagePayload);
                        Message message = new Message("SDP_OFFER", recipientClientId, mClientId, new String(Base64.getEncoder().encode(messagePayload.getBytes())));
                        client.sendSdpOffer(message);
                        stopKinesisVideo();
                    }
                    return;
                }

                final String sdp = Event.parseOfferEvent(offerEvent);

                localPeer.setRemoteDescription(new RTCSessionDescription(RTCSdpType.OFFER, sdp), new KVSStream.SetSDObserver());

                recipientClientId = offerEvent.getSenderClientId();

                createSdpAnswer();
            }

            @Override
            public void onSdpAnswer(final Event answerEvent) {
                final String sdp = Event.parseSdpEvent(answerEvent);

                final RTCSessionDescription sdpAnswer = new RTCSessionDescription(RTCSdpType.ANSWER, sdp);

                localPeer.setRemoteDescription(sdpAnswer, new KVSStream.SetSDObserver() {
                    @Override
                    public void onSuccess() {
                    }
                });
            }

            @Override
            public void onIceCandidate(Event message) {
                final RTCIceCandidate iceCandidate = Event.parseIceCandidate(message);

                if(iceCandidate != null) {
                    localPeer.addIceCandidate(iceCandidate);
                } else {}
            }

            @Override
            public void onError(Event errorMessage) {}

            @Override
            public void onException(Exception e) {}
        };

        if (wsHost != null) {
            try {
                client = new SignalingServiceWebSocketClient(wsHost, signalingListener, Executors.newFixedThreadPool(10));
            } catch (Exception e) {
                System.out.println(e);
            }

            if (isValidClient()) {
                if (true) {
                    createSdpOffer();
                }
            } else {}
        }
    }

    private static boolean isValidClient() {
        return client != null && client.isOpen();
    }

    private static class CreateSDObserver implements CreateSessionDescriptionObserver {
        @Override
        public void onSuccess(RTCSessionDescription description) {
            localPeer.setLocalDescription(description, new SetSDObserver() {
                @Override
                public void onSuccess() {
                    super.onSuccess();
                    try {
                        Message sdpOfferMessage = Message.createOfferMessage(description, mClientId);
                        if (isValidClient()) {
                            client.sendSdpOffer(sdpOfferMessage);
                        } else {}
                    } catch (Exception e) {}
                }
            });
        }

        @Override
        public void onFailure(String error) {}

    }

    private static class SetSDObserver implements SetSessionDescriptionObserver {

        @Override
        public void onSuccess() {}

        @Override
        public void onFailure(String error) {}
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
            } else {
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
                    if (isStop) {
                        System.exit(0);
                        break;
                    }
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

            mediaSource = createImageFileMediaSource();
            kinesisVideoClient.registerMediaSource(mediaSource);
            mediaSource.start();
        } catch (final KinesisVideoException e) {
            throw new RuntimeException(e);
        }
    }

    public static void stopKinesisVideo() {
        System.out.println("stopKinesisVideo");
        System.out.println("stopKinesisVideo");
        System.out.println("stopKinesisVideo");
        System.out.println("stopKinesisVideo");
        System.out.println("stopKinesisVideo");

        // try {
        //     final KinesisVideoClient kinesisVideoClient = KinesisVideoJavaClientFactory
        //             .createKinesisVideoClient(
        //                     Regions.US_EAST_1,
        //                     new ProfileCredentialsProvider());

        //     final MediaSource mediaSource = createImageFileMediaSource();
        //     kinesisVideoClient.registerMediaSource(mediaSource);
        //     mediaSource.stop();
        // } catch (final KinesisVideoException e) {
        //     throw new RuntimeException(e);
        // }
        try {
            mediaSource.stop();
            isStop = true;
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
}
