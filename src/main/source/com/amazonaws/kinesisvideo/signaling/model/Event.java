package com.amazonaws.kinesisvideo.signaling.model;

//import android.util.Base64;
//import android.util.Log;
import java.util.Base64;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
//import org.webrtc.IceCandidate;

import dev.onvoid.webrtc.RTCIceCandidate;

public class Event {

    private static final String TAG = "Event";

    private String senderClientId;

    private String messageType;

    private String messagePayload;

    private String statusCode;

    private String body;

    public String getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(String statusCode) {
        this.statusCode = statusCode;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getSenderClientId() {
        return senderClientId;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getMessagePayload() {
        return messagePayload;
    }

    public Event() {}

    public Event(String senderClientId, String messageType, String messagePayload) {
        this.senderClientId = senderClientId;
        this.messageType = messageType;
        this.messagePayload = messagePayload;
    }

    public static RTCIceCandidate parseIceCandidate(Event event) {

        byte[] decode = Base64.getDecoder().decode(event.getMessagePayload());
        String candidateString = new String(decode);
        JsonObject jsonObject = new JsonParser().parse(candidateString).getAsJsonObject();
        String sdpMid = jsonObject.get("sdpMid").toString();
        if (sdpMid.length() > 2) {
            sdpMid = sdpMid.substring(1, sdpMid.length() - 1);
        }

        int sdpMLineIndex = 0;
        try {
            sdpMLineIndex = Integer.parseInt(jsonObject.get("sdpMLineIndex").toString());
        } catch (NumberFormatException e) {
//            Log.e(TAG,  "Invalid sdpMLineIndex");
            return null;
        }

        String candidate = jsonObject.get("candidate").toString();
        if (candidate.length() > 2) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }

        return new RTCIceCandidate(sdpMid, sdpMLineIndex, candidate);
    }

    public static String parseSdpEvent(Event answerEvent) {

        String message = new String(Base64.getDecoder().decode(answerEvent.getMessagePayload().getBytes()));
        JsonObject jsonObject = new JsonParser().parse(message).getAsJsonObject();
        String type = jsonObject.get("type").toString();

        if (!type.equalsIgnoreCase("\"answer\"")) {
//            Log.e(TAG, "Error in answer message");
        }

        String sdp = jsonObject.get("sdp").getAsString();
//        Log.d(TAG, "SDP answer received from master:" + sdp);
        return sdp;
    }

    public static String parseOfferEvent(Event offerEvent) {
        String s = new String(Base64.getDecoder().decode(offerEvent.getMessagePayload()));

        JsonObject jsonObject = new JsonParser().parse(s).getAsJsonObject();
        return jsonObject.get("sdp").getAsString();
    }

}
