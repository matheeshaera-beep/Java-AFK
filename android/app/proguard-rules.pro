# Keep JNI native methods (NodeRuntime) — reflection used by System.loadLibrary.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class dev.mstheesha.afk.NodeRuntime { *; }

# Room entities are reflected via KSP-generated code; keep data classes.
-keep class dev.mstheesha.afk.ServerEntity { *; }

# OkHttp is consumed by the bridge; keep it intact.
-dontwarn okhttp3.**
-dontwarn okio.**

# JSONObject serialization happens via JSONObject builders (string keys), no reflection needed.