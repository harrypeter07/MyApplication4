package com.example.myapplication.signaling;

import android.util.Log;

import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class SignalingClient {
    private static final String TAG = "SignalingClient";
    private static final String SIGNALING_SERVER_URL = "http://your-server-url:3000"; // TODO: Replace with your server URL
    
    private final Socket socket;
    private final SignalingClientListener listener;
    private String roomId;
    
    public interface SignalingClientListener {
        void onConnectionEstablished();
        void onOfferReceived(SessionDescription sessionDescription);
        void onAnswerReceived(SessionDescription sessionDescription);
        void onRemoteIceCandidateReceived(IceCandidate iceCandidate);
    }
    
    public SignalingClient(SignalingClientListener listener) {
        this.listener = listener;
        try {
            socket = IO.socket(SIGNALING_SERVER_URL);
            setupSocketEvents();
        } catch (Exception e) {
            throw new RuntimeException("Socket connection error: " + e.getMessage());
        }
    }
    
    private void setupSocketEvents() {
        socket.on(Socket.EVENT_CONNECT, args -> {
            Log.d(TAG, "Socket connected");
            listener.onConnectionEstablished();
        });
        
        socket.on("offer", args -> {
            if (args[0] instanceof JSONObject) {
                JSONObject data = (JSONObject) args[0];
                try {
                    String type = data.getString("type");
                    String sdp = data.getString("sdp");
                    SessionDescription sessionDescription = 
                            new SessionDescription(SessionDescription.Type.OFFER, sdp);
                    listener.onOfferReceived(sessionDescription);
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing offer: " + e.getMessage());
                }
            }
        });
        
        socket.on("answer", args -> {
            if (args[0] instanceof JSONObject) {
                JSONObject data = (JSONObject) args[0];
                try {
                    String type = data.getString("type");
                    String sdp = data.getString("sdp");
                    SessionDescription sessionDescription = 
                            new SessionDescription(SessionDescription.Type.ANSWER, sdp);
                    listener.onAnswerReceived(sessionDescription);
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing answer: " + e.getMessage());
                }
            }
        });
        
        socket.on("ice-candidate", args -> {
            if (args[0] instanceof JSONObject) {
                JSONObject data = (JSONObject) args[0];
                try {
                    IceCandidate iceCandidate = new IceCandidate(
                            data.getString("sdpMid"),
                            data.getInt("sdpMLineIndex"),
                            data.getString("candidate")
                    );
                    listener.onRemoteIceCandidateReceived(iceCandidate);
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing ICE candidate: " + e.getMessage());
                }
            }
        });
    }
    
    public void connect() {
        socket.connect();
    }
    
    public void joinRoom(String roomId) {
        this.roomId = roomId;
        JSONObject message = new JSONObject();
        try {
            message.put("roomId", roomId);
            socket.emit("join", message);
        } catch (Exception e) {
            Log.e(TAG, "Error joining room: " + e.getMessage());
        }
    }
    
    public void sendOffer(SessionDescription sessionDescription) {
        JSONObject message = new JSONObject();
        try {
            message.put("type", sessionDescription.type.canonicalForm());
            message.put("sdp", sessionDescription.description);
            message.put("roomId", roomId);
            socket.emit("offer", message);
        } catch (Exception e) {
            Log.e(TAG, "Error sending offer: " + e.getMessage());
        }
    }
    
    public void sendAnswer(SessionDescription sessionDescription) {
        JSONObject message = new JSONObject();
        try {
            message.put("type", sessionDescription.type.canonicalForm());
            message.put("sdp", sessionDescription.description);
            message.put("roomId", roomId);
            socket.emit("answer", message);
        } catch (Exception e) {
            Log.e(TAG, "Error sending answer: " + e.getMessage());
        }
    }
    
    public void sendIceCandidate(IceCandidate iceCandidate) {
        JSONObject message = new JSONObject();
        try {
            message.put("candidate", iceCandidate.sdp);
            message.put("sdpMid", iceCandidate.sdpMid);
            message.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
            message.put("roomId", roomId);
            socket.emit("ice-candidate", message);
        } catch (Exception e) {
            Log.e(TAG, "Error sending ICE candidate: " + e.getMessage());
        }
    }
    
    public void disconnect() {
        if (socket != null) {
            socket.disconnect();
        }
    }
} 