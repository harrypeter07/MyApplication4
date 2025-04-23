package com.example.myapplication.webrtc;

import android.content.Context;
import android.view.Surface;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.List;

public class WebRTCClient {
    private static final String TAG = "WebRTCClient";
    
    private final Context context;
    private final WebRTCListener webRTCListener;
    private final EglBase eglBase;
    
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private SurfaceTextureHelper surfaceTextureHelper;
    private SurfaceViewRenderer localVideoRenderer;
    private Surface captureSurface;
    
    public interface WebRTCListener {
        void onIceCandidateReceived(IceCandidate iceCandidate);
        void onConnectionStateChanged(PeerConnection.PeerConnectionState state);
    }
    
    public WebRTCClient(Context context, WebRTCListener listener) {
        this.context = context;
        this.webRTCListener = listener;
        this.eglBase = EglBase.create();
    }
    
    public void initialize() {
        Log.d(TAG, "Initializing WebRTC client");
        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);
        Log.d(TAG, "PeerConnectionFactory initialized");
        
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        DefaultVideoEncoderFactory videoEncoderFactory =
                new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory videoDecoderFactory =
                new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());
                
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(videoEncoderFactory)
                .setVideoDecoderFactory(videoDecoderFactory)
                .createPeerConnectionFactory();
                
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
        videoSource = peerConnectionFactory.createVideoSource(false);
        localVideoTrack = peerConnectionFactory.createVideoTrack("local_track", videoSource);
        
        audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        localAudioTrack = peerConnectionFactory.createAudioTrack("local_audio_track", audioSource);
    }
    
    public void attachLocalVideoRenderer(SurfaceViewRenderer videoRenderer) {
        if (localVideoTrack != null && videoRenderer != null) {
            videoRenderer.init(eglBase.getEglBaseContext(), null);
            localVideoTrack.addSink(videoRenderer);
        }
    }
    
    public Surface createSurface() {
        if (videoSource != null && surfaceTextureHelper != null) {
            return new Surface(surfaceTextureHelper.getSurfaceTexture());
        }
        return null;
    }
    
    public PeerConnectionFactory getPeerConnectionFactory() {
        return peerConnectionFactory;
    }

    public void release() {
        try {
            if (localVideoRenderer != null) {
                localVideoRenderer.release();
                localVideoRenderer = null;
            }
            
            if (surfaceTextureHelper != null) {
                surfaceTextureHelper.dispose();
                surfaceTextureHelper = null;
            }
            
            if (videoSource != null) {
                videoSource.dispose();
                videoSource = null;
            }
            
            if (peerConnection != null) {
                peerConnection.close();
                peerConnection = null;
            }
            
            if (peerConnectionFactory != null) {
                peerConnectionFactory.dispose();
                peerConnectionFactory = null;
            }
            
            if (eglBase != null) {
                eglBase.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing resources: " + e.getMessage());
        }
    }
    
    public void createPeerConnection(boolean isBroadcaster) {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        
        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(iceServers);
        configuration.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        configuration.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        
        PeerConnection.Observer observer = new PeerConnection.Observer() {
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(TAG, "Generated ICE candidate: " + iceCandidate);
                webRTCListener.onIceCandidateReceived(iceCandidate);
            }
            
            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState state) {
                Log.d(TAG, "Connection state changed to: " + state);
                webRTCListener.onConnectionStateChanged(state);
            }
            
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {}
            
            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {}
            
            @Override
            public void onIceConnectionReceivingChange(boolean b) {}
            
            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}
            
            @Override
            public void onAddStream(MediaStream mediaStream) {}
            
            @Override
            public void onRemoveStream(MediaStream mediaStream) {}
            
            @Override
            public void onDataChannel(DataChannel dataChannel) {}
            
            @Override
            public void onRenegotiationNeeded() {}
            
            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {}
            
            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}
        };
        
        Log.d(TAG, "Creating peer connection with configuration: " + configuration);
        peerConnection = peerConnectionFactory.createPeerConnection(configuration, observer);
        if (peerConnection != null) {
            Log.d(TAG, "Peer connection created successfully");
        } else {
            Log.e(TAG, "Failed to create peer connection");
        }
        
        if (peerConnection != null) {
            // Only add local tracks for broadcasters
            if (isBroadcaster) {
                Log.d(TAG, "Adding local video and audio tracks");
                peerConnection.addTrack(localVideoTrack);
                peerConnection.addTrack(localAudioTrack);
                Log.d(TAG, "Local tracks added successfully");
            }
        }
    }
    
    public void setRemoteDescription(SessionDescription sessionDescription) {
        Log.d(TAG, "Setting remote description: " + sessionDescription);
        if (peerConnection != null) {
            peerConnection.setRemoteDescription(new SimpleSdpObserver(), sessionDescription);
            Log.d(TAG, "Remote description set successfully");
        } else {
            Log.e(TAG, "Cannot set remote description - peer connection is null");
        }
    }
    
    public void addIceCandidate(IceCandidate iceCandidate) {
        if (peerConnection != null) {
            peerConnection.addIceCandidate(iceCandidate);
        }
    }
    
    public void createOffer() {
        if (peerConnection != null) {
            MediaConstraints constraints = new MediaConstraints();
            Log.d(TAG, "Creating offer with constraints: " + constraints);
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
            
            peerConnection.createOffer(new SimpleSdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {
                    Log.d(TAG, "Offer created successfully");
                    peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                }
            }, constraints);
        }
    }
    
    public void createAnswer() {
        if (peerConnection != null) {
            MediaConstraints constraints = new MediaConstraints();
            Log.d(TAG, "Creating answer with constraints: " + constraints);
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
            
            peerConnection.createAnswer(new SimpleSdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {
                    Log.d(TAG, "Offer created successfully");
                    peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                }
            }, constraints);
        }
    }
    
    public EglBase.Context getEglBaseContext() {
        return eglBase.getEglBaseContext();
    }
    
    public VideoSource getVideoSource() {
        return videoSource;
    }

    public VideoSource createVideoSource() {
        if (videoSource != null) {
            videoSource.dispose();
        }
        videoSource = peerConnectionFactory.createVideoSource(false);
        localVideoTrack = peerConnectionFactory.createVideoTrack("local_track", videoSource);
        return videoSource;
    }

    public void disposeVideoSource() {
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (localVideoTrack != null) {
            localVideoTrack.dispose();
            localVideoTrack = null;
        }
    }

    public void onDestroy() {
        if (captureSurface != null) {
            captureSurface.release();
            captureSurface = null;
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (localVideoTrack != null) {
            localVideoTrack.dispose();
            localVideoTrack = null;
        }
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }
    }
}