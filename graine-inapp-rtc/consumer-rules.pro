# The SDK finds this module by its entry class's name, so R8 must keep that
# class and its constructor: nothing refers to it in code, and without this
# rule an app's release build would drop the module and quietly call over
# the WebSocket instead.
-keep class ai.graine.inapp.rtc.GraineRtc { public <init>(); }

# The WebRTC library's native code calls back into its Java classes by name.
# Renamed or removed, a call fails inside the native library at run time.
-keep class livekit.org.webrtc.** { *; }
-keep class livekit.org.jni_zero.** { *; }
-dontwarn livekit.org.webrtc.**
-dontwarn livekit.org.jni_zero.**
