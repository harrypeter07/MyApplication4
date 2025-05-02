package com.example.myapplication;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import com.example.myapplication.service.ImageCaptureService;

public class WebAppInterface {
    private static final String TAG = "WebAppInterface";
    private Context context;

    public WebAppInterface(Context context) {
        this.context = context;
        Log.d(TAG, "WebAppInterface initialized");
    }

    @JavascriptInterface
    public boolean isInterfaceAvailable() {
        Log.d(TAG, "Checking interface availability");
        return true;
    }

    @JavascriptInterface
    public void selectCamera(String cameraType) {
        Log.d(TAG, "Selecting camera: " + cameraType);
        try {
            Intent intent = new Intent(context, ImageCaptureService.class);
            intent.setAction(ImageCaptureService.ACTION_SWITCH_CAMERA);
            intent.putExtra(ImageCaptureService.EXTRA_USE_FRONT_CAMERA, 
                "front".equalsIgnoreCase(cameraType));
            context.startService(intent);
            showToast("Switching to " + cameraType + " camera");
        } catch (Exception e) {
            Log.e(TAG, "Error switching camera", e);
            showToast("Error switching camera: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void startCapture() {
        Log.d(TAG, "Starting capture");
        try {
            Intent intent = new Intent(context, ImageCaptureService.class);
            intent.setAction(ImageCaptureService.ACTION_START_CAPTURE);
            context.startService(intent);
            showToast("Starting image capture");
        } catch (Exception e) {
            Log.e(TAG, "Error starting capture", e);
            showToast("Error starting capture: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void stopCapture() {
        Log.d(TAG, "Stopping capture");
        try {
            Intent intent = new Intent(context, ImageCaptureService.class);
            intent.setAction(ImageCaptureService.ACTION_STOP_CAPTURE);
            context.startService(intent);
            showToast("Stopping image capture");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping capture", e);
            showToast("Error stopping capture: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public String getInterfaceVersion() {
        return "1.0";
    }

    @JavascriptInterface
    public void startScreenCapture() {
        Log.d(TAG, "Requesting screen capture from JS");
        if (context instanceof MainActivity) {
            ((MainActivity) context).requestScreenCapturePermission();
        }
    }

    private void showToast(final String message) {
        android.os.Handler mainHandler = new android.os.Handler(context.getMainLooper());
        mainHandler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
} 