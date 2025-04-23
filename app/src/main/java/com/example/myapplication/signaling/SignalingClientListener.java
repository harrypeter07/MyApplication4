package com.example.myapplication.signaling;

import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

public interface SignalingClientListener {
    void onConnectionEstablished();
    void onOfferReceived(SessionDescription sessionDescription);
    void onAnswerReceived(SessionDescription sessionDescription);
    void onRemoteIceCandidateReceived(IceCandidate iceCandidate);
}