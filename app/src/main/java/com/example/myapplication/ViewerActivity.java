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
            finish();
            return;
        }
        
        setupWebRTC();
        setupClickListeners();
        connectToRoom();
    }
    
    private void setupWebRTC() {
        webRTCClient = new WebRTCClient(this, this);
        webRTCClient.initialize();
        signalingClient = new SignalingClient(this);
        
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
        signalingClient.connect();
        signalingClient.joinRoom(roomId);
        webRTCClient.createPeerConnection();
    }
    
    // WebRTCListener Implementation
    @Override
    public void onIceCandidateReceived(IceCandidate iceCandidate) {
        // This is called when a local ICE candidate is generated
        signalingClient.sendIceCandidate(iceCandidate);
    }
    
    @Override
    public void onConnectionStateChanged(PeerConnection.PeerConnectionState state) {
        runOnUiThread(() -> {
            if (state == PeerConnection.PeerConnectionState.DISCONNECTED ||
                    state == PeerConnection.PeerConnectionState.FAILED) {
                finish();
            }
        });
    }
    
    // SignalingClientListener Implementation
    @Override
    public void onConnectionEstablished() {
        Log.d(TAG, "Signaling server connected");
    }
    
    @Override
    public void onOfferReceived(SessionDescription sessionDescription) {
        webRTCClient.setRemoteDescription(sessionDescription);
        webRTCClient.createAnswer();
    }
    
    @Override
    public void onAnswerReceived(SessionDescription sessionDescription) {
        webRTCClient.setRemoteDescription(sessionDescription);
    }
    
    @Override
    public void onRemoteIceCandidateReceived(IceCandidate iceCandidate) {
        // This is called when a remote ICE candidate is received
        webRTCClient.addIceCandidate(iceCandidate);
    }
    
    @Override
    protected void onDestroy() {
        webRTCClient.release();
        signalingClient.disconnect();
        super.onDestroy();
    }
} 