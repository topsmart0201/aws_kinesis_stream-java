package com.amazonaws.kinesisvideo.java.mediasource.file;

import com.amazonaws.kinesisvideo.app.H264Packet;
import com.amazonaws.kinesisvideo.common.exception.KinesisVideoException;
import com.amazonaws.kinesisvideo.common.preconditions.Preconditions;
import com.amazonaws.kinesisvideo.app.AppMain;
import com.amazonaws.kinesisvideo.app.KVSStream;
import com.amazonaws.kinesisvideo.internal.mediasource.OnStreamDataAvailable;

import com.amazonaws.kinesisvideo.producer.KinesisVideoFrame;
import com.amazonaws.kinesisvideo.signaling.model.Message;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.amazonaws.kinesisvideo.producer.FrameFlags.FRAME_FLAG_KEY_FRAME;
import static com.amazonaws.kinesisvideo.producer.FrameFlags.FRAME_FLAG_NONE;
import static com.amazonaws.kinesisvideo.producer.Time.HUNDREDS_OF_NANOS_IN_A_MILLISECOND;

/**
 * Frame source backed by local image files.
 */
@NotThreadSafe
public class ImageFrameSource {
    public static final int METADATA_INTERVAL = 8;
    private static final long FRAME_DURATION_20_MS = 20L;
    private static long FRAME_DURATION = 0;
    private final ExecutorService executor = Executors.newFixedThreadPool(1);
    private final int fps;
    private final ImageFileMediaSourceConfiguration configuration;

    private final int totalFiles;
    private OnStreamDataAvailable mkvDataAvailableCallback;
    private boolean isRunning = false;
    private int frameCounter;
    private final Log log = LogFactory.getLog(ImageFrameSource.class);
    private final String metadataName = "ImageLoop";
    private int metadataCount = 0;
    private static int firstPacket = 0;
//    private static boolean output = true;

    public ImageFrameSource(final ImageFileMediaSourceConfiguration configuration) {
        this.configuration = configuration;
        this.totalFiles = getTotalFiles(configuration.getStartFileIndex(), configuration.getEndFileIndex());
        this.fps = configuration.getFps();
        FRAME_DURATION = 1000L / this.fps / 2;
    }

    private int getTotalFiles(final int startIndex, final int endIndex) {
        Preconditions.checkState(endIndex >= startIndex);
        return endIndex - startIndex + 1;
    }

    public void start() {
        if (isRunning) {
            throw new IllegalStateException("Frame source is already running");
        }

        isRunning = true;
        startFrameGenerator();
    }

    public void stop() {
        isRunning = false;
        stopFrameGenerator();
    }

    public void onStreamDataAvailable(final OnStreamDataAvailable onMkvDataAvailable) {
        this.mkvDataAvailableCallback = onMkvDataAvailable;
    }

    private void startFrameGenerator() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    generateFrameAndNotifyListener();
                } catch (final KinesisVideoException e) {
                    log.error("Failed to keep generating frames with Exception", e);
                }
            }
        });
    }

    private void generateFrameAndNotifyListener() throws KinesisVideoException {
        while (isRunning) {
            if (mkvDataAvailableCallback != null) {
                KinesisVideoFrame frame = createKinesisVideoFrameFromImage(frameCounter);
                if (frame != null) {
                    mkvDataAvailableCallback.onFrameDataAvailable(frame);
//                    if (isMetadataReady()) {
//                        mkvDataAvailableCallback.onFragmentMetadataAvailable(metadataName + metadataCount,
//                                Integer.toString(metadataCount++), false);
//                    }
                    frameCounter++;
                }
            }

            try {
                Thread.sleep(10);
            } catch (final InterruptedException e) {
                log.error("Frame interval wait interrupted by Exception ", e);
            }
        }
    }

    private boolean isMetadataReady() {
        return frameCounter % METADATA_INTERVAL == 0;
    }

    private KinesisVideoFrame createKinesisVideoFrameFromImage(final long index) {
        final long currentTimeMs = System.currentTimeMillis();

        final int flags = isKeyFrame() ? FRAME_FLAG_KEY_FRAME : FRAME_FLAG_NONE;

        H264Packet pkt = null;
        try {
            KVSStream.mutex.acquire();
            if (KVSStream.videoList.size() > 0) {
                pkt = KVSStream.videoList.firstElement();
                KVSStream.videoList.remove(0);
            }
            else {
                KVSStream.mutex.release();
                return null;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        KVSStream.mutex.release();

        if (firstPacket == 0) {
            String messagePayload =
                    "{\"type\":\""
                            + "streamStart"
                            + "\"}";
            Message message = new Message("SDP_OFFER", KVSStream.recipientClientId, KVSStream.mClientId, new String(Base64.getEncoder().encode(messagePayload.getBytes())));
            KVSStream.client.sendSdpOffer(message);
            firstPacket = 1;
        }


        return new KinesisVideoFrame(
                frameCounter,
                pkt.getFlags(),
                pkt.getDts() * HUNDREDS_OF_NANOS_IN_A_MILLISECOND,
                pkt.getPts() * HUNDREDS_OF_NANOS_IN_A_MILLISECOND,
//                currentTimeMs * HUNDREDS_OF_NANOS_IN_A_MILLISECOND,
//                currentTimeMs * HUNDREDS_OF_NANOS_IN_A_MILLISECOND,
                FRAME_DURATION_20_MS * HUNDREDS_OF_NANOS_IN_A_MILLISECOND * 2,
                ByteBuffer.wrap(pkt.bytes));
    }

    private boolean isKeyFrame() {
        return frameCounter % this.fps == 0;
    }

    private void stopFrameGenerator() {
        executor.shutdown();
    }
}
