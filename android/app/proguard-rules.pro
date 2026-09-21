# Java AFK release keep rules.
# native-lib.cpp registers its JNI entry points BY NAME
# (Java_dev_mstheesha_afk_NodeRuntime_*), so R8 must not rename or drop the
# Kotlin methods they bind to. Everything else minifies normally.
-keep class dev.mstheesha.afk.NodeRuntime {
    *;
}
