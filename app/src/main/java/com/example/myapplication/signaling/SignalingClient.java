package com.example.myapplication.signaling;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class SignalingClient {
    private static final String TAG = "SignalingClient";
    private final Context context;
    private static final String SIGNALING_SERVER_URL = "http://192.168.51.36:3000"; // Updated server URL with actual IP
    
    private final Socket socket;
    private final SignalingClientListener listener;
    private String roomId;
    
    public interface SignalingClientListener {
        void onConnectionEstablished();
        void onOfferReceived(SessionDescription sessionDescription);
        void onAnswerReceived(SessionDescription sessionDescription);
        void onRemoteIceCandidateReceived(IceCandidate iceCandidate);
    }
    
    public SignalingClient(Context context, SignalingClientListener listener) {
        this.context = context;
        this.listener = listener;
        try {
            socket = IO.socket(SIGNALING_SERVER_URL);
            socket.on(Socket.EVENT_DISCONNECT, args -> {
                Log.e(TAG, "Socket error: " + args[0].toString());
                Toast.makeText(context, "Server connection error", Toast.LENGTH_LONG).show();
            });
            setupSocketEvents();
        } catch (Exception e) {
            Toast.makeText(context, "Socket init error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            throw new RuntimeException("Socket connection error: " + e.getMessage());
        }
    }

    private void setupSocketEvents() {
        socket.on(Socket.EVENT_CONNECT, args -> {
            Log.d(TAG, "Socket connected successfully to signaling server");
            if (context != null) {
            new android.os.Handler(context.getMainLooper()).post(() -> {
                Toast.makeText(context, "Connected to signaling server", Toast.LENGTH_SHORT).show();
            });
        }
            listener.onConnectionEstablished();
        });

        socket.on(Socket.EVENT_DISCONNECT, args -> {
            Log.d(TAG, "Disconnected from signaling server");
            if (context != null) {
            new android.os.Handler(context.getMainLooper()).post(() -> {
                Toast.makeText(context, "Server connection lost", Toast.LENGTH_LONG).show();
            });
        }
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
                    Log.e(TAG, "Error parsing offer from remote peer: " + e.getMessage());
                }
            }
        });
        
        socket.on("screen-answer", args -> {
            if (args[0] instanceof JSONObject) {
                JSONObject data = (JSONObject) args[0];
                try {
                    String type = data.getString("type");
                    String sdp = data.getString("sdp");
                    SessionDescription sessionDescription = 
                            new SessionDescription(SessionDescription.Type.ANSWER, sdp);
                    listener.onAnswerReceived(sessionDescription);
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing screen answer: " + e.getMessage());
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
                    Log.e(TAG, "Error parsing answer from remote peer: " + e.getMessage());
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
                    Log.e(TAG, "Error parsing ICE candidate from remote peer: " + e.getMessage());
                }
            }
        });
    }
    
    public void connect(Runnable onConnected) {
        Log.d(TAG, "Attempting to connect to signaling server...");
        if (socket == null) {
            Log.e(TAG, "Socket not initialized");
            Toast.makeText(context, "Connection failed: Socket error", Toast.LENGTH_LONG).show();
            return;
        }
        socket.connect();
        socket.once(Socket.EVENT_CONNECT, args -> {
            if (onConnected != null) {
                onConnected.run();
            }
        });
        Log.d(TAG, "Socket connection attempt finished.");
    }
    
    public void joinRoom(String roomId) {
        if (socket == null || !socket.connected()) {
            if (socket == null) {
                Log.e(TAG, "Join room failed: Socket not initialized");
                return;
            }
            Log.e(TAG, "Join room failed: Socket not connected");
            if (context != null) {
                new android.os.Handler(context.getMainLooper()).post(() -> {
                    Toast.makeText(context, "Not connected to server", Toast.LENGTH_LONG).show();
                });
            }
            return;
        }
        this.roomId = roomId;
        try {
            JSONObject message = new JSONObject();
            message.put("roomId", roomId);
            socket.emit("join", message);
            Toast.makeText(context, "Joined room " + roomId, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error joining room " + roomId + ": " + e.getMessage());
            Toast.makeText(context, "Join room failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    public void sendScreenOffer(SessionDescription sessionDescription) {
        if (socket == null || !socket.connected()) {
            if (socket == null) {
                Log.e(TAG, "Send screen offer failed: Socket not initialized");
                return;
            }
            Log.e(TAG, "Send screen offer failed: Socket not connected");
            if (context != null) {
                new android.os.Handler(context.getMainLooper()).post(() -> {
                    Toast.makeText(context, "Not connected to server", Toast.LENGTH_LONG).show();
                });
            }
            return;
        }
        try {
            JSONObject message = new JSONObject();
            message.put("type", sessionDescription.type.canonicalForm());
            message.put("sdp", sessionDescription.description);
            message.put("roomId", roomId);
            socket.emit("screen-offer", message);
        } catch (Exception e) {
            Log.e(TAG, "Error sending screen offer: " + e.getMessage());
            Toast.makeText(context, "Screen offer failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public void sendOffer(SessionDescription sessionDescription) {
        if (socket == null || !socket.connected()) {
            if (socket == null) {
                Log.e(TAG, "Send offer failed: Socket not initialized");
                return;
            }
            Log.e(TAG, "Send offer failed: Socket not connected");
            if (context != null) {
                new android.os.Handler(context.getMainLooper()).post(() -> {
                    Toast.makeText(context, "Not connected to server", Toast.LENGTH_LONG).show();
                });
            }
            return;
        }
        try {
            JSONObject message = new JSONObject();
            message.put("type", sessionDescription.type.canonicalForm());
            message.put("sdp", sessionDescription.description);
            message.put("roomId", roomId);
            socket.emit("offer", message);
        } catch (Exception e) {
            Log.e(TAG, "Error sending offer to remote peer: " + e.getMessage());
            Toast.makeText(context, "Offer failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    public void sendAnswer(SessionDescription sessionDescription) {
        if (socket == null || !socket.connected()) {
            if (socket == null) {
                Log.e(TAG, "Send answer failed: Socket not initialized");
                return;
            }
            Log.e(TAG, "Send answer failed: Socket not connected");
            if (context != null) {
                new android.os.Handler(context.getMainLooper()).post(() -> {
                    Toast.makeText(context, "Not connected to server", Toast.LENGTH_LONG).show();
                });
            }
            return;
        }
        try {
            JSONObject message = new JSONObject();
            message.put("type", sessionDescription.type.canonicalForm());
            message.put("sdp", sessionDescription.description);
            message.put("roomId", roomId);
            socket.emit("answer", message);
        } catch (Exception e) {
            Log.e(TAG, "Error sending answer to remote peer: " + e.getMessage());
            Toast.makeText(context, "Answer failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    public void sendScreenIceCandidate(IceCandidate iceCandidate) {
        if (socket == null || !socket.connected()) {
            if (socket == null) {
                Log.e(TAG, "Send screen ICE candidate failed: Socket not initialized");
                return;
            }
            Log.e(TAG, "Send screen ICE candidate failed: Socket not connected");
            if (context != null) {
                new android.os.Handler(context.getMainLooper()).post(() -> {
                    Toast.makeText(context, "Not connected to server", Toast.LENGTH_LONG).show();
                });
            }
            return;
        }
        try {
            JSONObject message = new JSONObject();
            message.put("candidate", iceCandidate.sdp);
            message.put("sdpMid", iceCandidate.sdpMid);
            message.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
            message.put("roomId", roomId);
            socket.emit("screen-ice-candidate", message);
        } catch (Exception e) {
            Log.e(TAG, "Error sending screen ICE candidate: " + e.getMessage());
            Toast.makeText(context, "Screen ICE candidate failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public void sendIceCandidate(IceCandidate iceCandidate) {
        if (socket == null || !socket.connected()) {
            if (socket == null) {
                Log.e(TAG, "Send ICE candidate failed: Socket not initialized");
                return;
            }
            Log.e(TAG, "Send ICE candidate failed: Socket not connected");
            if (context != null) {
                new android.os.Handler(context.getMainLooper()).post(() -> {
                    Toast.makeText(context, "Not connected to server", Toast.LENGTH_LONG).show();
                });
            }
            if (context != null) {
                new android.os.Handler(context.getMainLooper()).post(() -> {
                    Toast.makeText(context, "Not connected to server", Toast.LENGTH_LONG).show();
                });
            }
            return;
        }
        try {
            JSONObject message = new JSONObject();
            message.put("candidate", iceCandidate.sdp);
            message.put("sdpMid", iceCandidate.sdpMid);
            message.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
            message.put("roomId", roomId);
            socket.emit("ice-candidate", message);
        } catch (Exception e) {
            Log.e(TAG, "Error sending ICE candidate to remote peer: " + e.getMessage());
            if (context != null) {
                new android.os.Handler(context.getMainLooper()).post(() -> {
                    Toast.makeText(context, "ICE candidate failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }
    }
    
    public void disconnect() {
        if (socket != null && socket.connected()) {
            try {
                socket.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Error disconnecting socket: " + e.getMessage());
            }
            socket.disconnect();
        }
    }
}