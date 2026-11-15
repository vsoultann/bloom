-keep class app.bloom.core.Native { native <methods>; }
# WebRTC is called from native code; keep it intact.
-keep class org.webrtc.** { *; }
-keep class livekit.org.webrtc.** { *; }
