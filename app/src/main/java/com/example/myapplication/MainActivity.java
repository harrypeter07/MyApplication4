package com.example.myapplication;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Surface;
import android.widget.Button;
import android.widget.Toast;

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

public class MainActivity extends AppCompatActivity implements WebRTCClient.WebRTCListener, SignalingClient.SignalingClientListener {
    private static final String TAG = "MainActivity";
    private static final int SCREEN_CAPTURE_REQUEST = 1001;
    
    private ActivityMainBinding binding;
    private WebRTCClient webRTCClient;
    private SignalingClient signalingClient;
    private ScreenCaptureService screenCaptureService;
    private boolean isScreenSharing = false;
    private VideoSource videoSource;
    
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ScreenCaptureService.LocalBinder binder = (ScreenCaptureService.LocalBinder) service;
            screenCaptureService = binder.getService();
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            screenCaptureService = null;
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        
        // Initialize SignalingClient
        signalingClient = new SignalingClient(this , this);

        setupWebRTC();
        setupClickListeners();
        checkPermissions();
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

            if (!isScreenSharing) {
                signalingClient.connect(() -> {
                    // Connection established callback
                    signalingClient.joinRoom(roomId);
                    Toast.makeText(this, "Connecting to room: " + roomId + "...", Toast.LENGTH_SHORT).show();
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
vrb            if (!roomId.isEmpty()) {
                signalingClient.connect(() -> {});
                signalingClient.joinRoom(roomId);
                Toast.makeText(this, "Joining room: " + roomId + "...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Please enter a room ID", Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    private void checkPermissions() {
        Dexter.withContext(this)
                .withPermissions(
                        android.Manifest.permission.RECORD_AUDIO,
                        android.Manifest.permission.INTERNET
                )
                .withListener(new MultiplePermissionsListener() {
                    @Override
                    public void onPermissionsChecked(MultiplePermissionsReport report) {
                        if (report.areAllPermissionsGranted()) {
                            // Permissions granted, ready to proceed
                            Toast.makeText(MainActivity.this, 
                                    "Enter a room ID and click Start Screen Share", Toast.LENGTH_LONG).show();
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        try {
            if (requestCode == SCREEN_CAPTURE_REQUEST && resultCode == RESULT_OK) {
                Log.d(TAG, "Screen capture permission granted");
                Toast.makeText(this, "Screen capture permission granted", Toast.LENGTH_SHORT).show();
                
                // Start the service first
                Intent intent = new Intent(this, ScreenCaptureService.class);
                intent.putExtra("resultCode", resultCode);
                intent.putExtra("data", data);
                startService(intent);
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

                // Wait briefly for service to bind
                new android.os.Handler().postDelayed(() -> {
                    // Initialize video components
                    if (webRTCClient != null) {
                        if (videoSource == null && webRTCClient != null) {
                            videoSource = webRTCClient.createVideoSource();
                            if (videoSource == null) {
                                Log.e(TAG, "Failed to create video source after retry");
                                Toast.makeText(MainActivity.this, "Video source creation failed", Toast.LENGTH_SHORT).show();
                                stopScreenShare();
                                return;
                            }
                        }
                        
                        // Check components and create surface
                        if (videoSource != null && screenCaptureService != null) {
                            Log.d(TAG, "Creating surface for screen capture");
                            Surface surface = webRTCClient.createSurface();
                            if (surface != null && webRTCClient != null) {
                                try {
                                    screenCaptureService.startVirtualDisplay(surface);
                                    Log.d(TAG, "Virtual display started successfully");
                                    Toast.makeText(MainActivity.this, "Screen capture started", Toast.LENGTH_SHORT).show();
                                    isScreenSharing = true;
                                    updateButtonState(true);
                                    // Create peer connection after surface validation
                                    webRTCClient.createPeerConnection(true);
                                } catch (IllegalStateException e) {
                                    Log.e(TAG, "Failed to start virtual display: " + e.getMessage());
                                    Toast.makeText(MainActivity.this, "Screen capture failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                    stopScreenShare();
                                }
                            } else {
                                Log.e(TAG, "Failed to create surface");
                                Toast.makeText(MainActivity.this, "Failed to create capture surface", Toast.LENGTH_SHORT).show();
                                stopScreenShare();
                            }
                        } else {
                            String errorMsg = "Failed to initialize: " + 
                                (videoSource == null ? "videoSource is null. " : "") +
                                (screenCaptureService == null ? "screenCaptureService is null. " : "");
                            Log.e(TAG, errorMsg);
                            Toast.makeText(MainActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                            stopScreenShare();
                        }
                    } else {
                        Log.d(TAG, "WebRTCClient initialized");
                        Toast.makeText(MainActivity.this, "WebRTCClient initialization failed", Toast.LENGTH_SHORT).show();
                        stopScreenShare();
                    }
                }, 500); // Give 500ms for service to bind

            } else {
                Log.w(TAG, "Screen capture permission denied or cancelled");
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show();
                isScreenSharing = false;
                updateButtonState(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onActivityResult: " + e.getMessage(), e);
            Toast.makeText(this, "Error starting screen capture: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            isScreenSharing = false;
            updateButtonState(false);
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
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (screenCaptureService != null) {
            screenCaptureService.stopProjection();
            screenCaptureService = null;
        }
        if (webRTCClient != null) {
            webRTCClient.disposeVideoSource();
            webRTCClient.release();
            webRTCClient = null;
        }
        signalingClient.disconnect();
    }

    private void updateButtonState(boolean isSharing) {
        binding.startShareButton.setText(isSharing ? "Stop Sharing" : "Start Sharing");
    }

    private void stopScreenShare() {
        try {
            if (screenCaptureService != null) {
                screenCaptureService.stopProjection();
                unbindService(serviceConnection);
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
}
