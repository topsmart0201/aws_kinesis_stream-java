package com.amazonaws.kinesisvideo.signaling.tyrus;

//import android.util.Base64;
//import android.util.Log;
import com.amazonaws.kinesisvideo.signaling.SignalingListener;

import com.amazonaws.kinesisvideo.signaling.model.Message;
import com.google.gson.Gson;
import org.glassfish.tyrus.client.ClientManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Signaling service client based on websocket.
 */

public class SignalingServiceWebSocketClient {

    private static final String TAG = "SignalingServiceWebSocketClient";

    private final WebSocketClient websocketClient;

    private final ExecutorService executorService;

    private final Gson gson = new Gson();

    public SignalingServiceWebSocketClient(final String uri, final SignalingListener signalingListener,
                                           final ExecutorService executorService) {
//        Log.d(TAG, "Connecting to URI " + uri + " as master");
        websocketClient = new WebSocketClient(uri, new ClientManager(), signalingListener, executorService);
        this.executorService = executorService;
    }

    public boolean isOpen() {
        return websocketClient.isOpen();
    }

    public void sendStreamStart(final Message offer) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                if (offer.getAction().equalsIgnoreCase("STREAM_START")) {
                    send(offer);
                }
            }
        });
    }

    public void sendSdpOffer(final Message offer) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                if (offer.getAction().equalsIgnoreCase("SDP_OFFER")) {
//                    Log.d(TAG, "Sending Offer");
                    send(offer);
                }
            }
        });
    }

    public void sendSdpAnswer(final Message answer) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                if (answer.getAction().equalsIgnoreCase("SDP_ANSWER")) {
//                    Log.d(TAG, "Answer sent " + new String(Base64.decode(answer.getMessagePayload().getBytes(),
//                            Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE)));
                    send(answer);
                }
            }
        });
    }

    public void sendIceCandidate(final Message candidate) {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                if (candidate.getAction().equalsIgnoreCase("ICE_CANDIDATE")) {

                    send(candidate);
                }

//                Log.d(TAG, "Sent Ice candidate message");
            }
        });
    }

    public void disconnect() {
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                websocketClient.disconnect();
            }
        });
        try {
            executorService.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
//            Log.e(TAG, "Error in disconnect");
        }
    }

    private void send(final Message message) {
        String jsonMessage = gson.toJson(message);
//        Log.d(TAG, "Sending JSON Message= " + jsonMessage);
//        if (jsonMessage.contains("SDP_OFFER"))
//            websocketClient.send("{\"action\":\"SDP_OFFER\",\"messagePayload\":\"eyJ0eXBlIjoib2ZmZXIiLCJzZHAiOiJ2PTBcclxubz0tIDM1MzI3NDcwODcyOTU4MjI1OTggMiBJTiBJUDQgMTI3LjAuMC4xXHJcbnM9LVxyXG50PTAgMFxyXG5hPWdyb3VwOkJVTkRMRSAwIDFcclxuYT1tc2lkLXNlbWFudGljOiBXTVMgamZkTTdsZE5sUTRVU1R3WUR0ajRGNU5vMjRWS3QzR1ZMcVhEXHJcbm09dmlkZW8gOSBVRFAvVExTL1JUUC9TQVZQRiA5NiA5NyA5OCA5OSAxMDAgMTAxIDEwMiAxMjEgMTI3IDEyMCAxMjUgMTA3IDEwOCAxMDkgMTI0IDExOSAxMjNcclxuYz1JTiBJUDQgMC4wLjAuMFxyXG5hPXJ0Y3A6OSBJTiBJUDQgMC4wLjAuMFxyXG5hPWljZS11ZnJhZzpqN1d3XHJcbmE9aWNlLXB3ZDp3QSs4NU96dVlNQlNlQjZocDV6dnhNcXpcclxuYT1pY2Utb3B0aW9uczp0cmlja2xlXHJcbmE9ZmluZ2VycHJpbnQ6c2hhLTI1NiBFRTpENTpGMzowRjo1NDpCRTo5MDo4Qzo3RDo4MToxMTo2MzoyRjo4MjowMzoyNTpGMDpEQzo2RDowQjowMTo2RjozMToyMDpDNjpDNjo4NDpBNTpFNjpGQzpBMTo0QlxyXG5hPXNldHVwOmFjdHBhc3NcclxuYT1taWQ6MFxyXG5hPWV4dG1hcDoxIHVybjppZXRmOnBhcmFtczpydHAtaGRyZXh0OnRvZmZzZXRcclxuYT1leHRtYXA6MiBodHRwOi8vd3d3LndlYnJ0Yy5vcmcvZXhwZXJpbWVudHMvcnRwLWhkcmV4dC9hYnMtc2VuZC10aW1lXHJcbmE9ZXh0bWFwOjMgdXJuOjNncHA6dmlkZW8tb3JpZW50YXRpb25cclxuYT1leHRtYXA6NCBodHRwOi8vd3d3LmlldGYub3JnL2lkL2RyYWZ0LWhvbG1lci1ybWNhdC10cmFuc3BvcnQtd2lkZS1jYy1leHRlbnNpb25zLTAxXHJcbmE9ZXh0bWFwOjUgaHR0cDovL3d3dy53ZWJydGMub3JnL2V4cGVyaW1lbnRzL3J0cC1oZHJleHQvcGxheW91dC1kZWxheVxyXG5hPWV4dG1hcDo2IGh0dHA6Ly93d3cud2VicnRjLm9yZy9leHBlcmltZW50cy9ydHAtaGRyZXh0L3ZpZGVvLWNvbnRlbnQtdHlwZVxyXG5hPWV4dG1hcDo3IGh0dHA6Ly93d3cud2VicnRjLm9yZy9leHBlcmltZW50cy9ydHAtaGRyZXh0L3ZpZGVvLXRpbWluZ1xyXG5hPWV4dG1hcDo4IGh0dHA6Ly93d3cud2VicnRjLm9yZy9leHBlcmltZW50cy9ydHAtaGRyZXh0L2NvbG9yLXNwYWNlXHJcbmE9ZXh0bWFwOjkgdXJuOmlldGY6cGFyYW1zOnJ0cC1oZHJleHQ6c2RlczptaWRcclxuYT1leHRtYXA6MTAgdXJuOmlldGY6cGFyYW1zOnJ0cC1oZHJleHQ6c2RlczpydHAtc3RyZWFtLWlkXHJcbmE9ZXh0bWFwOjExIHVybjppZXRmOnBhcmFtczpydHAtaGRyZXh0OnNkZXM6cmVwYWlyZWQtcnRwLXN0cmVhbS1pZFxyXG5hPXNlbmRyZWN2XHJcbmE9bXNpZDpqZmRNN2xkTmxRNFVTVHdZRHRqNEY1Tm8yNFZLdDNHVkxxWEQgOTFhNzAxNDMtMTNmNy00M2RkLTkzYzItOGJkN2EyNzE3NzBkXHJcbmE9cnRjcC1tdXhcclxuYT1ydGNwLXJzaXplXHJcbmE9cnRwbWFwOjk2IFZQOC85MDAwMFxyXG5hPXJ0Y3AtZmI6OTYgZ29vZy1yZW1iXHJcbmE9cnRjcC1mYjo5NiB0cmFuc3BvcnQtY2NcclxuYT1ydGNwLWZiOjk2IGNjbSBmaXJcclxuYT1ydGNwLWZiOjk2IG5hY2tcclxuYT1ydGNwLWZiOjk2IG5hY2sgcGxpXHJcbmE9cnRwbWFwOjk3IHJ0eC85MDAwMFxyXG5hPWZtdHA6OTcgYXB0PTk2XHJcbmE9cnRwbWFwOjk4IFZQOS85MDAwMFxyXG5hPXJ0Y3AtZmI6OTggZ29vZy1yZW1iXHJcbmE9cnRjcC1mYjo5OCB0cmFuc3BvcnQtY2NcclxuYT1ydGNwLWZiOjk4IGNjbSBmaXJcclxuYT1ydGNwLWZiOjk4IG5hY2tcclxuYT1ydGNwLWZiOjk4IG5hY2sgcGxpXHJcbmE9Zm10cDo5OCBwcm9maWxlLWlkPTBcclxuYT1ydHBtYXA6OTkgcnR4LzkwMDAwXHJcbmE9Zm10cDo5OSBhcHQ9OThcclxuYT1ydHBtYXA6MTAwIFZQOS85MDAwMFxyXG5hPXJ0Y3AtZmI6MTAwIGdvb2ctcmVtYlxyXG5hPXJ0Y3AtZmI6MTAwIHRyYW5zcG9ydC1jY1xyXG5hPXJ0Y3AtZmI6MTAwIGNjbSBmaXJcclxuYT1ydGNwLWZiOjEwMCBuYWNrXHJcbmE9cnRjcC1mYjoxMDAgbmFjayBwbGlcclxuYT1mbXRwOjEwMCBwcm9maWxlLWlkPTJcclxuYT1ydHBtYXA6MTAxIHJ0eC85MDAwMFxyXG5hPWZtdHA6MTAxIGFwdD0xMDBcclxuYT1ydHBtYXA6MTAyIEgyNjQvOTAwMDBcclxuYT1ydGNwLWZiOjEwMiBnb29nLXJlbWJcclxuYT1ydGNwLWZiOjEwMiB0cmFuc3BvcnQtY2NcclxuYT1ydGNwLWZiOjEwMiBjY20gZmlyXHJcbmE9cnRjcC1mYjoxMDIgbmFja1xyXG5hPXJ0Y3AtZmI6MTAyIG5hY2sgcGxpXHJcbmE9Zm10cDoxMDIgbGV2ZWwtYXN5bW1ldHJ5LWFsbG93ZWQ9MTtwYWNrZXRpemF0aW9uLW1vZGU9MTtwcm9maWxlLWxldmVsLWlkPTQyMDAxZlxyXG5hPXJ0cG1hcDoxMjEgcnR4LzkwMDAwXHJcbmE9Zm10cDoxMjEgYXB0PTEwMlxyXG5hPXJ0cG1hcDoxMjcgSDI2NC85MDAwMFxyXG5hPXJ0Y3AtZmI6MTI3IGdvb2ctcmVtYlxyXG5hPXJ0Y3AtZmI6MTI3IHRyYW5zcG9ydC1jY1xyXG5hPXJ0Y3AtZmI6MTI3IGNjbSBmaXJcclxuYT1ydGNwLWZiOjEyNyBuYWNrXHJcbmE9cnRjcC1mYjoxMjcgbmFjayBwbGlcclxuYT1mbXRwOjEyNyBsZXZlbC1hc3ltbWV0cnktYWxsb3dlZD0xO3BhY2tldGl6YXRpb24tbW9kZT0wO3Byb2ZpbGUtbGV2ZWwtaWQ9NDIwMDFmXHJcbmE9cnRwbWFwOjEyMCBydHgvOTAwMDBcclxuYT1mbXRwOjEyMCBhcHQ9MTI3XHJcbmE9cnRwbWFwOjEyNSBIMjY0LzkwMDAwXHJcbmE9cnRjcC1mYjoxMjUgZ29vZy1yZW1iXHJcbmE9cnRjcC1mYjoxMjUgdHJhbnNwb3J0LWNjXHJcbmE9cnRjcC1mYjoxMjUgY2NtIGZpclxyXG5hPXJ0Y3AtZmI6MTI1IG5hY2tcclxuYT1ydGNwLWZiOjEyNSBuYWNrIHBsaVxyXG5hPWZtdHA6MTI1IGxldmVsLWFzeW1tZXRyeS1hbGxvd2VkPTE7cGFja2V0aXphdGlvbi1tb2RlPTE7cHJvZmlsZS1sZXZlbC1pZD00MmUwMWZcclxuYT1ydHBtYXA6MTA3IHJ0eC85MDAwMFxyXG5hPWZtdHA6MTA3IGFwdD0xMjVcclxuYT1ydHBtYXA6MTA4IEgyNjQvOTAwMDBcclxuYT1ydGNwLWZiOjEwOCBnb29nLXJlbWJcclxuYT1ydGNwLWZiOjEwOCB0cmFuc3BvcnQtY2NcclxuYT1ydGNwLWZiOjEwOCBjY20gZmlyXHJcbmE9cnRjcC1mYjoxMDggbmFja1xyXG5hPXJ0Y3AtZmI6MTA4IG5hY2sgcGxpXHJcbmE9Zm10cDoxMDggbGV2ZWwtYXN5bW1ldHJ5LWFsbG93ZWQ9MTtwYWNrZXRpemF0aW9uLW1vZGU9MDtwcm9maWxlLWxldmVsLWlkPTQyZTAxZlxyXG5hPXJ0cG1hcDoxMDkgcnR4LzkwMDAwXHJcbmE9Zm10cDoxMDkgYXB0PTEwOFxyXG5hPXJ0cG1hcDoxMjQgcmVkLzkwMDAwXHJcbmE9cnRwbWFwOjExOSBydHgvOTAwMDBcclxuYT1mbXRwOjExOSBhcHQ9MTI0XHJcbmE9cnRwbWFwOjEyMyB1bHBmZWMvOTAwMDBcclxuYT1zc3JjLWdyb3VwOkZJRCAzMjQ5ODY2ODE4IDIwNTQ1OTMyODRcclxuYT1zc3JjOjMyNDk4NjY4MTggY25hbWU6YVlNOU1qNFdSVGI5TEpLaVxyXG5hPXNzcmM6MzI0OTg2NjgxOCBtc2lkOmpmZE03bGRObFE0VVNUd1lEdGo0RjVObzI0Vkt0M0dWTHFYRCA5MWE3MDE0My0xM2Y3LTQzZGQtOTNjMi04YmQ3YTI3MTc3MGRcclxuYT1zc3JjOjMyNDk4NjY4MTggbXNsYWJlbDpqZmRNN2xkTmxRNFVTVHdZRHRqNEY1Tm8yNFZLdDNHVkxxWERcclxuYT1zc3JjOjMyNDk4NjY4MTggbGFiZWw6OTFhNzAxNDMtMTNmNy00M2RkLTkzYzItOGJkN2EyNzE3NzBkXHJcbmE9c3NyYzoyMDU0NTkzMjg0IGNuYW1lOmFZTTlNajRXUlRiOUxKS2lcclxuYT1zc3JjOjIwNTQ1OTMyODQgbXNpZDpqZmRNN2xkTmxRNFVTVHdZRHRqNEY1Tm8yNFZLdDNHVkxxWEQgOTFhNzAxNDMtMTNmNy00M2RkLTkzYzItOGJkN2EyNzE3NzBkXHJcbmE9c3NyYzoyMDU0NTkzMjg0IG1zbGFiZWw6amZkTTdsZE5sUTRVU1R3WUR0ajRGNU5vMjRWS3QzR1ZMcVhEXHJcbmE9c3NyYzoyMDU0NTkzMjg0IGxhYmVsOjkxYTcwMTQzLTEzZjctNDNkZC05M2MyLThiZDdhMjcxNzcwZFxyXG5tPWF1ZGlvIDkgVURQL1RMUy9SVFAvU0FWUEYgMTExIDEwMyAxMDQgOSAwIDggMTA2IDEwNSAxMyAxMTAgMTEyIDExMyAxMjZcclxuYz1JTiBJUDQgMC4wLjAuMFxyXG5hPXJ0Y3A6OSBJTiBJUDQgMC4wLjAuMFxyXG5hPWljZS11ZnJhZzpqN1d3XHJcbmE9aWNlLXB3ZDp3QSs4NU96dVlNQlNlQjZocDV6dnhNcXpcclxuYT1pY2Utb3B0aW9uczp0cmlja2xlXHJcbmE9ZmluZ2VycHJpbnQ6c2hhLTI1NiBFRTpENTpGMzowRjo1NDpCRTo5MDo4Qzo3RDo4MToxMTo2MzoyRjo4MjowMzoyNTpGMDpEQzo2RDowQjowMTo2RjozMToyMDpDNjpDNjo4NDpBNTpFNjpGQzpBMTo0QlxyXG5hPXNldHVwOmFjdHBhc3NcclxuYT1taWQ6MVxyXG5hPWV4dG1hcDoxNCB1cm46aWV0ZjpwYXJhbXM6cnRwLWhkcmV4dDpzc3JjLWF1ZGlvLWxldmVsXHJcbmE9ZXh0bWFwOjIgaHR0cDovL3d3dy53ZWJydGMub3JnL2V4cGVyaW1lbnRzL3J0cC1oZHJleHQvYWJzLXNlbmQtdGltZVxyXG5hPWV4dG1hcDo0IGh0dHA6Ly93d3cuaWV0Zi5vcmcvaWQvZHJhZnQtaG9sbWVyLXJtY2F0LXRyYW5zcG9ydC13aWRlLWNjLWV4dGVuc2lvbnMtMDFcclxuYT1leHRtYXA6OSB1cm46aWV0ZjpwYXJhbXM6cnRwLWhkcmV4dDpzZGVzOm1pZFxyXG5hPWV4dG1hcDoxMCB1cm46aWV0ZjpwYXJhbXM6cnRwLWhkcmV4dDpzZGVzOnJ0cC1zdHJlYW0taWRcclxuYT1leHRtYXA6MTEgdXJuOmlldGY6cGFyYW1zOnJ0cC1oZHJleHQ6c2RlczpyZXBhaXJlZC1ydHAtc3RyZWFtLWlkXHJcbmE9cmVjdm9ubHlcclxuYT1ydGNwLW11eFxyXG5hPXJ0cG1hcDoxMTEgb3B1cy80ODAwMC8yXHJcbmE9cnRjcC1mYjoxMTEgdHJhbnNwb3J0LWNjXHJcbmE9Zm10cDoxMTEgbWlucHRpbWU9MTA7dXNlaW5iYW5kZmVjPTFcclxuYT1ydHBtYXA6MTAzIElTQUMvMTYwMDBcclxuYT1ydHBtYXA6MTA0IElTQUMvMzIwMDBcclxuYT1ydHBtYXA6OSBHNzIyLzgwMDBcclxuYT1ydHBtYXA6MCBQQ01VLzgwMDBcclxuYT1ydHBtYXA6OCBQQ01BLzgwMDBcclxuYT1ydHBtYXA6MTA2IENOLzMyMDAwXHJcbmE9cnRwbWFwOjEwNSBDTi8xNjAwMFxyXG5hPXJ0cG1hcDoxMyBDTi84MDAwXHJcbmE9cnRwbWFwOjExMCB0ZWxlcGhvbmUtZXZlbnQvNDgwMDBcclxuYT1ydHBtYXA6MTEyIHRlbGVwaG9uZS1ldmVudC8zMjAwMFxyXG5hPXJ0cG1hcDoxMTMgdGVsZXBob25lLWV2ZW50LzE2MDAwXHJcbmE9cnRwbWFwOjEyNiB0ZWxlcGhvbmUtZXZlbnQvODAwMFxyXG4ifQ==\"}");
//        else
        websocketClient.send(jsonMessage);
//        Log.d(TAG, "Sent JSON Message= " + jsonMessage);
    }

}
