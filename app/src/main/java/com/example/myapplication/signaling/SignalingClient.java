package com.example.myapplication.signaling;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.example.myapplication.MainActivity;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class SignalingClient {
    private static final String TAG = "SignalingClient";
    private static final String SERVER_URL = "https://myapplication4.onrender.com";
    private Socket socket;
    private Context context;
    private SignalingClientListener listener;
    private CameraCommandListener cameraCommandListener;
    private boolean isConnected = false;
    private String currentRoomId;

    public interface SignalingClientListener {
        void onConnectionEstablished();
        void onOfferReceived(SessionDescription sessionDescription);
        void onAnswerReceived(SessionDescription sessionDescription);
        void onRemoteIceCandidateReceived(IceCandidate iceCandidate);
    }

    public interface CameraCommandListener {
        void onCameraCommand(String command, String cameraType);
    }

    public interface ScreenCaptureCommandListener {
        void onScreenCaptureCommand();
    }

    public SignalingClient(Context context, SignalingClientListener listener) {
        this.context = context;
        this.listener = listener;
        initializeSocket();
    }

    private void initializeSocket() {
        try {
            IO.Options options = new IO.Options();
            options.reconnection = true;
            options.reconnectionAttempts = Integer.MAX_VALUE;
            options.reconnectionDelay = 1000;
            options.reconnectionDelayMax = 5000;
            options.timeout = 20000;
            options.forceNew = true;
            options.transports = new String[]{"websocket"};

            socket = IO.socket(SERVER_URL, options);

            setupSocketListeners();
        } catch (URISyntaxException e) {
            Log.e(TAG, "Error initializing socket: " + e.getMessage());
        }
    }

    private void setupSocketListeners() {
        socket.on(Socket.EVENT_CONNECT, args -> {
            Log.d(TAG, "Socket connected");
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, "Socket connected", Toast.LENGTH_SHORT).show());
            isConnected = true;
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(context, "Connected to server", Toast.LENGTH_SHORT).show();
                listener.onConnectionEstablished();
            });
        });

        socket.on(Socket.EVENT_DISCONNECT, args -> {
            Log.d(TAG, "Socket disconnected");
            isConnected = false;
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(context, "Disconnected from server", Toast.LENGTH_SHORT).show();
            });
            // Attempt to reconnect
            reconnect();
        });

        socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
            Log.e(TAG, "Socket connection error: " + args[0]);
            isConnected = false;
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(context, "Connection error. Retrying...", Toast.LENGTH_SHORT).show();
            });
            // Attempt to reconnect
            reconnect();
        });

        socket.on("cameraCommand", args -> {
            Log.d(TAG, "cameraCommand event args[0] type: " + args[0].getClass().getName());
            Log.d(TAG, "cameraCommand event args[0] value: " + args[0].toString());
            Log.d(TAG, "cameraCommand event received: " + args[0]);
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, "cameraCommand event received", Toast.LENGTH_SHORT).show());
            try {
                JSONObject data = (JSONObject) args[0];
                String command = data.getString("command");
                String cameraType = data.optString("cameraType", "back");
                if (cameraCommandListener != null) {
                    cameraCommandListener.onCameraCommand(command, cameraType);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing camera command: " + e.getMessage());
            }
        });

        socket.on("offer", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                String sdp = data.getString("sdp");
                SessionDescription offer = new SessionDescription(
                    SessionDescription.Type.OFFER, sdp);
                listener.onOfferReceived(offer);
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing offer: " + e.getMessage());
            }
        });

        socket.on("answer", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                String sdp = data.getString("sdp");
                SessionDescription answer = new SessionDescription(
                    SessionDescription.Type.ANSWER, sdp);
                listener.onAnswerReceived(answer);
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing answer: " + e.getMessage());
            }
        });

        socket.on("ice-candidate", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                String candidate = data.getString("candidate");
                String sdpMid = data.getString("sdpMid");
                int sdpMLineIndex = data.getInt("sdpMLineIndex");
                IceCandidate iceCandidate = new IceCandidate(
                    sdpMid, sdpMLineIndex, candidate);
                listener.onRemoteIceCandidateReceived(iceCandidate);
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing ice candidate: " + e.getMessage());
            }
        });

        socket.on("screenCaptureCommand", args -> {
            new Handler(Looper.getMainLooper()).post(() -> {
                Toast.makeText(context, "Screen capture command received", Toast.LENGTH_SHORT).show();
                if (listener instanceof ScreenCaptureCommandListener) {
                    ((ScreenCaptureCommandListener) listener).onScreenCaptureCommand();
                }
            });
        });
    }

    private void reconnect() {
        if (!isConnected) {
            try {
                Log.d(TAG, "Attempting to reconnect...");
                socket.connect();
                // Add a delay before checking connection status
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!isConnected) {
                        Log.w(TAG, "Reconnection failed, retrying in 5 seconds...");
                        new Handler(Looper.getMainLooper()).postDelayed(this::reconnect, 5000);
                    }
                }, 1000);
            } catch (Exception e) {
                Log.e(TAG, "Error reconnecting: " + e.getMessage());
                // Retry after delay
                new Handler(Looper.getMainLooper()).postDelayed(this::reconnect, 5000);
            }
        }
    }

    public void connect(Runnable onConnected) {
        if (!isConnected) {
            try {
                Log.d(TAG, "Connecting to signaling server...");
                socket.connect();
                if (onConnected != null) {
                    onConnected.run();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error connecting: " + e.getMessage());
                // Retry connection
                new Handler(Looper.getMainLooper()).postDelayed(() -> connect(onConnected), 5000);
            }
        }
    }

    public void disconnect() {
        if (isConnected) {
            try {
                Log.d(TAG, "Disconnecting from signaling server...");
                socket.disconnect();
                isConnected = false;
            } catch (Exception e) {
                Log.e(TAG, "Error disconnecting: " + e.getMessage());
            }
        }
    }

    public void joinRoom(String roomId) {
        currentRoomId = roomId;
        socket.emit("join", roomId);
    }

    public void sendOffer(SessionDescription offer) {
        try {
            JSONObject data = new JSONObject();
            data.put("sdp", offer.description);
            socket.emit("offer", data);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending offer: " + e.getMessage());
        }
    }

    public void sendAnswer(SessionDescription answer) {
        try {
            JSONObject data = new JSONObject();
            data.put("sdp", answer.description);
            socket.emit("answer", data);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending answer: " + e.getMessage());
        }
    }

    public void sendIceCandidate(IceCandidate candidate) {
        try {
            JSONObject data = new JSONObject();
            data.put("candidate", candidate.sdp);
            data.put("sdpMid", candidate.sdpMid);
            data.put("sdpMLineIndex", candidate.sdpMLineIndex);
            socket.emit("ice-candidate", data);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending ice candidate: " + e.getMessage());
        }
    }

    public void setCameraCommandListener(CameraCommandListener listener) {
        this.cameraCommandListener = listener;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public String getCurrentRoomId() {
        return currentRoomId;
    }
}