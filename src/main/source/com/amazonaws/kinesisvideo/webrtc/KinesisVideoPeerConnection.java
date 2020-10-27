//package com.amazonaws.kinesisvideo.webrtc;
//
////import android.util.Log;
////import org.webrtc.DataChannel;
////import org.webrtc.IceCandidate;
////import org.webrtc.MediaStream;
////import org.webrtc.PeerConnection;
////import org.webrtc.RtpReceiver;
//
//import dev.onvoid.webrtc.RTCPeerConnection;
//import dev.onvoid.webrtc.RTCDataChannel;
//import dev.onvoid.webrtc.RTCIceCandidate;
//import dev.onvoid.webrtc.RTCIceConnectionState;
//import dev.onvoid.webrtc.RTCIceGatheringState;
//import dev.onvoid.webrtc.RTCSignalingState;
//import dev.onvoid.webrtc.RTCRtpReceiver;
//import dev.onvoid.webrtc.media.MediaStream;
//
//public class KinesisVideoPeerConnection implements RTCPeerConnection.Observer {
//
//    public KinesisVideoPeerConnection() {
//
//    }
//
//    @Override
////    public void onSignalingChange(RTCPeerConnection.SignalingState signalingState) {
//    public void onSignalingChange(RTCSignalingState signalingState) {
//
//        System.out.println("onSignalingChange(): signalingState = [" + signalingState + "]");
//
//    }
//
//    @Override
////    public void onIceConnectionChange(RTCPeerConnection.IceConnectionState iceConnectionState) {
//    public void onIceConnectionChange(RTCIceConnectionState iceConnectionState) {
//
//        System.out.println("onIceConnectionChange(): iceConnectionState = [" + iceConnectionState + "]");
//
//    }
//
//    @Override
//    public void onIceConnectionReceivingChange(boolean connectionChange) {
//
//        System.out.println("onIceConnectionReceivingChange(): connectionChange = [" + connectionChange + "]");
//
//    }
//
//    @Override
////    public void onIceGatheringChange(RTCPeerConnection.IceGatheringState iceGatheringState) {
//    public void onIceGatheringChange(RTCIceGatheringState iceGatheringState) {
//
//        System.out.println("onIceGatheringChange(): iceGatheringState = [" + iceGatheringState + "]");
//
//    }
//
//    @Override
//    public void onIceCandidate(RTCIceCandidate iceCandidate) {
//
//        System.out.println("onIceCandidate(): iceCandidate = [" + iceCandidate + "]");
//
//    }
//
//    @Override
//    public void onIceCandidatesRemoved(RTCIceCandidate[] iceCandidates) {
//
//        System.out.println("onIceCandidatesRemoved(): iceCandidates Length = [" + iceCandidates.length + "]");
//
//    }
//
//    @Override
//    public void onAddStream(MediaStream mediaStream) {
//
//        System.out.println("onAddStream(): mediaStream = [" + mediaStream + "]");
//
//    }
//
//    @Override
//    public void onRemoveStream(MediaStream mediaStream) {
//
//        System.out.println("onRemoveStream(): mediaStream = [" + mediaStream + "]");
//
//    }
//
//    @Override
//    public void onDataChannel(RTCDataChannel dataChannel) {
//
//        System.out.println("onDataChannel(): dataChannel = [" + dataChannel + "]");
//
//    }
//
//    @Override
//    public void onRenegotiationNeeded() {
//
//        System.out.println("onRenegotiationNeeded():");
//
//    }
//
//    @Override
//    public void onAddTrack(RTCRtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
//
//        System.out.println("onAddTrack(): rtpReceiver = [" + rtpReceiver + "], " +
//                "mediaStreams Length = [" + mediaStreams.length + "]");
//
//    }
//}
