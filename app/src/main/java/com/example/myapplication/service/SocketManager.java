package com.example.myapplication.service;

import android.util.Base64;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URISyntaxException;
import io.socket.client.IO;
import io.socket.client.Socket;

public class SocketManager {
    private Socket socket;
    private String serverUrl;

    public SocketManager(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public void connect() {
        try {
            socket = IO.socket(serverUrl);
            socket.connect();
            socket.on(Socket.EVENT_CONNECT, args -> {
                Log.d("SocketIO", "Connected to server");
            });
            socket.on(Socket.EVENT_DISCONNECT, args -> {
                Log.d("SocketIO", "Disconnected from server");
                reconnect();
            });
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    private void reconnect() {
        if (socket != null && !socket.connected()) {
            socket.connect();
        }
    }

    public void disconnect() {
        if (socket != null) {
            socket.disconnect();
        }
    }

    public boolean isConnected() {
        return socket != null && socket.connected();
    }

    public void sendScreenCapture(byte[] imageData) {
        if (socket != null && socket.connected()) {
            JSONObject data = new JSONObject();
            try {
                data.put("image", Base64.encodeToString(imageData, Base64.DEFAULT));
                data.put("timestamp", System.currentTimeMillis());
                socket.emit("screen_capture", data);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    public void sendAccessibilityData(JSONObject accessibilityData) {
        if (socket != null && socket.connected()) {
            socket.emit("accessibility_data", accessibilityData);
        }
    }

    public void sendAccessibilityScreenshot(byte[] imageData, int eventType, String packageName, long timestamp) {
        if (socket != null && socket.connected()) {
            JSONObject data = new JSONObject();
            try {
                data.put("image", Base64.encodeToString(imageData, Base64.DEFAULT));
                data.put("eventType", eventType);
                data.put("packageName", packageName);
                data.put("timestamp", timestamp);
                socket.emit("accessibility_screenshot", data);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    public void sendAccessibilityError(JSONObject errorData) {
        if (socket != null && socket.connected()) {
            socket.emit("accessibility_screenshot", errorData);
        }
    }

    public void sendToastMessage(String message) {
        if (socket != null && socket.connected()) {
            JSONObject data = new JSONObject();
            try {
                data.put("message", message);
                data.put("timestamp", System.currentTimeMillis());
            } catch (JSONException e) {
                e.printStackTrace();
            }
            socket.emit("toast_message", data);
        }
    }
} 