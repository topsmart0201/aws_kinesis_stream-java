package com.amazonaws.kinesisvideo.app;

import com.amazonaws.kinesisvideo.app.auth.AuthHelper;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class SignalMain {
    private static final String DEFAULT_REGION = System.getProperty("aws.region");
    private static final String AWS_SQS_URL = System.getProperty("aws.sqs");

    // FFMPEG
    public static boolean isInit = false;

    public static AmazonSQS sqs = null;
    public static ReceiveMessageRequest receiveMessageRequest = null;
    public static String standardQueueUrl = AWS_SQS_URL;
    public static List<Message> sqsMessages = new ArrayList<Message>();

    private static final String exeCmd = "./new-java-app.sh %s %s %s %s %s %s %s %s %s";
    private static final String cmdPath = "../php/decrypt.php";

    private SignalMain() {
        throw new UnsupportedOperationException();
    }

    public static String createProcess(String command) {

        String logMsg = String.format("Creating process. command = {%s}", command);

        Process proc = null;
        String s;
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);

        try {
            proc = pb.start();

            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            while ((s = br.readLine()) != null)
                stdout.append(s);

            BufferedReader bre = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
            while ((s = bre.readLine()) != null)
                stderr.append(s);
            if (stderr.length() > 0) {
                System.out.println("stderr: " + stderr.toString());
            }
            proc.waitFor();

        } catch (Exception e) {
            System.out.println("Exception while executing process command: " + command);
            System.out.println(e.getStackTrace().toString());
        }

        return stdout.toString();
    }

    public static void main(final String[] args) throws IOException, InterruptedException {
        sqs = AmazonSQSClientBuilder.standard()
                .withCredentials(AuthHelper.getSystemPropertiesCredentialsProvider())
                .withRegion(DEFAULT_REGION)
                .build();

        receiveMessageRequest = new ReceiveMessageRequest(standardQueueUrl)
                .withWaitTimeSeconds(0)
                .withMaxNumberOfMessages(10);

        while(true) {
            sqsMessages = sqs.receiveMessage(receiveMessageRequest).getMessages();
            for (int i = 0; i < sqsMessages.size(); i ++) {
                String strMessage = sqsMessages.get(i).getBody();
                JsonObject jsonObject = new JsonParser().parse(strMessage).getAsJsonObject();
                String channel = jsonObject.get("signalChannelName").getAsString();
                String stream = jsonObject.get("streamName").getAsString();
                String session = jsonObject.get("sessionId").getAsString();
                String email = jsonObject.get("email").getAsString();

                DeleteMessageRequest dmr = new DeleteMessageRequest();
                dmr.withQueueUrl(standardQueueUrl).withReceiptHandle(sqsMessages.get(i).getReceiptHandle());
                sqs.deleteMessage(dmr);

                String chkUser = String.format(exeCmd, "AKIAY6TQDS7NUDMVKY4L", "DugcilM5xifwj/vPx0ycCAOp8R6FpHcM55fGDzXk", DEFAULT_REGION, AWS_SQS_URL, channel, stream, session, email, "&");
                System.out.println(chkUser);
//                String retVal = createProcess(chkUser);
            }
        }
    }
}
