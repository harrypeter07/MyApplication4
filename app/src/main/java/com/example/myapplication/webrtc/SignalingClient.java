package com.example.myapplication.webrtc;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class SignalingClient {
    private static final String TAG = "SignalingClient";
    private final WebSocket webSocket;
    private final SignalingClientListener listener;
    private final String roomName;
    private final Handler handler;

    public interface SignalingClientListener {
        void onConnectionEstablished();
        void onOfferReceived(JSONObject data);
        void onAnswerReceived(JSONObject data);
        void onIceCandidateReceived(JSONObject data);
        void onCallEnded();
    }

    public SignalingClient(String websocketUrl, String roomName, SignalingClientListener listener) {
        this.roomName = roomName;
        this.listener = listener;
        this.handler = new Handler(Looper.getMainLooper());

        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        Request request = new Request.Builder()
                .url(websocketUrl)
                .build();

        WebSocketListener webSocketListener = new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                joinRoom();
                handler.post(() -> listener.onConnectionEstablished());
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                try {
                    JSONObject data = new JSONObject(text);
                    String type = data.getString("type");
                    
                    handler.post(() -> {
                        try {
                            switch (type) {
                                case "offer":
                                    listener.onOfferReceived(data);
                                    break;
                                case "answer":
                                    listener.onAnswerReceived(data);
                                    break;
                                case "candidate":
                                    listener.onIceCandidateReceived(data);
                                    break;
                                case "leave":
                                    listener.onCallEnded();
                                    break;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error handling message: " + e.getMessage());
                        }
                    });
                } catch (JSONException e) {
                    Log.e(TAG, "Error parsing message: " + e.getMessage());
                }
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                handler.post(() -> listener.onCallEnded());
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
                Log.e(TAG, "WebSocket failure: " + t.getMessage());
            }
        };

        webSocket = client.newWebSocket(request, webSocketListener);
    }

    private void joinRoom() {
        JSONObject message = new JSONObject();
        try {
            message.put("type", "join");
            message.put("room", roomName);
            sendMessage(message);
        } catch (JSONException e) {
            Log.e(TAG, "Error joining room: " + e.getMessage());
        }
    }

    public void sendIceCandidate(IceCandidate iceCandidate, String to) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "candidate");
            message.put("candidate", iceCandidate.sdp);
            message.put("sdpMid", iceCandidate.sdpMid);
            message.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
            message.put("to", to);
            sendMessage(message);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending ICE candidate: " + e.getMessage());
        }
    }

    public void sendSessionDescription(SessionDescription sessionDescription, String to) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", sessionDescription.type.canonicalForm());
            message.put("sdp", sessionDescription.description);
            message.put("to", to);
            sendMessage(message);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending session description: " + e.getMessage());
        }
    }

    private void sendMessage(JSONObject message) {
        webSocket.send(message.toString());
    }

    public void disconnect() {
        if (webSocket != null) {
            webSocket.close(1000, "Bye");
        }
    }
} 