# LibVLC uses JNI; keep its classes intact.
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }
-dontwarn org.videolan.**

# OkHttp / digest
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.burgstaller.okhttp.** { *; }
