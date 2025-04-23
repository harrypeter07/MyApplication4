package com.example.myapplication;

import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.example.myapplication.databinding.ActivityViewerBinding;
import com.example.myapplication.signaling.SignalingClient;
import com.example.myapplication.webrtc.WebRTCClient;

import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;

public class ViewerActivity extends AppCompatActivity implements WebRTCClient.WebRTCListener, SignalingClient.SignalingClientListener {
    private static final String TAG = "ViewerActivity";
    
    private ActivityViewerBinding binding;
    private WebRTCClient webRTCClient;
    private SignalingClient signalingClient;
    private String roomId;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        
        roomId = getIntent().getStringExtra("roomId");
        if (roomId == null || roomId.isEmpty()) {
            Log.e(TAG, "Room ID is null or empty");
            finish();
            return;
        }
        Log.d(TAG, "Room ID received: " + roomId);
        
        Log.d(TAG, "WebRTCClient initialized");
        setupWebRTC();
        Log.d(TAG, "Setting up click listeners");
        setupClickListeners();
        Log.d(TAG, "Connecting to room: " + roomId);
        connectToRoom();
    }
    
    private void setupWebRTC() {
        Log.d(TAG, "Creating WebRTC client");
        webRTCClient = new WebRTCClient(this, this);
        Log.d(TAG, "Initializing WebRTC client");
        webRTCClient.initialize();
        Log.d(TAG, "Creating signaling client");
        signalingClient = new SignalingClient(this , this);
        
        SurfaceViewRenderer remoteVideoView = binding.remoteVideoView;
        remoteVideoView.init(webRTCClient.getEglBaseContext(), null);
        remoteVideoView.setMirror(false);
        webRTCClient.attachLocalVideoRenderer(remoteVideoView);
    }
    
    private void setupClickListeners() {
        binding.disconnectButton.setOnClickListener(v -> {
            webRTCClient.release();
            signalingClient.disconnect();
            finish();
        });
    }
    
    private void connectToRoom() {
        Log.d(TAG, "Connecting to signaling server");
        signalingClient.connect(() -> {
            Log.d(TAG, "Joining room: " + roomId);
            signalingClient.joinRoom(roomId);
        });
        Log.d(TAG, "Creating peer connection");
        webRTCClient.createPeerConnection(false);
    }
    
    // WebRTCListener Implementation
    @Override
    public void onIceCandidateReceived(IceCandidate iceCandidate) {
        // This is called when a local ICE candidate is generated
        signalingClient.sendIceCandidate(iceCandidate);
        Log.d(TAG, "ICE candidate received: " + iceCandidate.toString());
    }
    
    @Override
    public void onConnectionStateChanged(PeerConnection.PeerConnectionState state) {
        runOnUiThread(() -> {
            if (state == PeerConnection.PeerConnectionState.DISCONNECTED ||
                    state == PeerConnection.PeerConnectionState.FAILED) {
                finish();
            }
        });
        Log.d(TAG, "Connection state changed: " + state.toString());
    }
    
    // SignalingClientListener Implementation
    @Override
    public void onConnectionEstablished() {
        Log.d(TAG, "Signaling server connected");
        Log.d(TAG, "SignalingClient initialized");
    }
    
    @Override
    public void onOfferReceived(SessionDescription sessionDescription) {
        webRTCClient.setRemoteDescription(sessionDescription);
        webRTCClient.createAnswer();
        Log.d(TAG, "Offer received: " + sessionDescription.description);
    }
    
    @Override
    public void onAnswerReceived(SessionDescription sessionDescription) {
        webRTCClient.setRemoteDescription(sessionDescription);
        Log.d(TAG, "Answer received: " + sessionDescription.description);
    }
    
    @Override
    public void onRemoteIceCandidateReceived(IceCandidate iceCandidate) {
        // This is called when a remote ICE candidate is received
        webRTCClient.addIceCandidate(iceCandidate);
        Log.d(TAG, "Remote ICE candidate received: " + iceCandidate.toString());
    }
    
    @Override
    protected void onDestroy() {
        webRTCClient.release();
        signalingClient.disconnect();
        super.onDestroy();
    }
}