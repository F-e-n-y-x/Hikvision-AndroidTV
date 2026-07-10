# LibVLC uses JNI; keep its classes intact.
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }
-dontwarn org.videolan.**

# OkHttp / digest
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.burgstaller.okhttp.** { *; }

# EncryptedSharedPreferences (androidx.security-crypto) + its Tink backend register key managers
# via reflection. Under R8 full mode (AGP default) stripping them can crash NvrStore at launch on
# release builds only. Keep them defensively.
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.crypto.tink.**
