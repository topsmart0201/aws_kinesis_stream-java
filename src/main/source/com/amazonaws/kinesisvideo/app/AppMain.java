
package com.amazonaws.kinesisvideo.app;

import com.amazonaws.kinesisvideo.app.auth.AuthHelper;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileOutputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Java Producer.
 */
public final class AppMain {
    private static final String DEFAULT_REGION = System.getProperty("aws.region");
    private static final String AWS_SQS_URL = System.getProperty("aws.sqs");

    // FFMPEG
    public static KVSStream kvsstream = null;

    public static FileOutputStream fos = null;
    public static ExecutorService executor = Executors.newFixedThreadPool(1);
    public static boolean isInit = false;

    public static AmazonSQS sqs = null;
    public static ReceiveMessageRequest receiveMessageRequest = null;
    public static String standardQueueUrl = AWS_SQS_URL;
    public static List<com.amazonaws.services.sqs.model.Message> sqsMessages = new ArrayList<com.amazonaws.services.sqs.model.Message>();
    public static boolean isInitialize = false;
    private AppMain() {
        throw new UnsupportedOperationException();
    }

    private static final String channel = System.getProperty("aws.channel");
    private static final String stream = System.getProperty("aws.stream");
    private static final String session = System.getProperty("aws.session");
    private static final String email = System.getProperty("aws.email");

    public static void main(final String[] args) {
        kvsstream = new KVSStream(channel, stream, session, email);
        kvsstream.init();
    }
}




