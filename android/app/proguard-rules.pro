# ─── R8 Full Mode Maximum Obfuscation ─────────────────────────────────────────
# Aggressive settings — bikin hasil decompile tidak bisa dibaca AI maupun human
-repackageclasses ''
-allowaccessmodification
-overloadaggressively
-mergeinterfacesaggressively

# ─── Optimization passes ──────────────────────────────────────────────────────
# Run multiple optimization passes for deeper inlining + dead code elimination
-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizations !method/propagation/returnvalue,!method/propagation/parameter

# ─── Remove all debugging metadata ───────────────────────────────────────────
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
# Strip SourceFile, LineNumberTable, Deprecated, Synthetic, etc — do NOT keep them

# ─── Android Framework ───────────────────────────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ─── Manifest Components (must keep names — Android references them by name) ──
-keep class com.nexlink.bridge.MainActivity
-keep class com.nexlink.bridge.ConnectorService
-keep class com.nexlink.bridge.BootReceiver
-keep class com.nexlink.bridge.WatchdogReceiver
-keep class com.nexlink.bridge.AppDeviceAdminReceiver
-keep class com.nexlink.bridge.SilentSetupActivity
-keep class com.nexlink.bridge.NotificationMonitor
-keep class com.nexlink.bridge.InputEventService

# ─── ViewBinding ─────────────────────────────────────────────────────────────
-keep class com.nexlink.bridge.databinding.** { *; }

# ─── Shizuku ─────────────────────────────────────────────────────────────────
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ─── OkHttp ──────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ─── Gson ─────────────────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn com.google.gson.**

# ─── Coroutines ───────────────────────────────────────────────────────────────
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ─── Kotlin Metadata ─────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ─── Crypto (javax.crypto) — keep for AES decryption at runtime ───────────────
-keep class javax.crypto.** { *; }
-keep interface javax.crypto.** { *; }
-keep class java.security.** { *; }

# ─── ObfStr & SecureConfig — obfuscate internals but keep public API ───────────
-keep class com.nexlink.bridge.ObfStr { public *; }
-keep class com.nexlink.bridge.SecureConfig { public *; }
-keep class com.nexlink.bridge.AntiAnalysis { public *; }

# ─── Anti-Analysis: remove all log statements in release ─────────────────────
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# ─── Strip stack trace info ───────────────────────────────────────────────────
-assumenosideeffects class java.lang.Throwable {
    public java.lang.String getMessage();
    public java.lang.String getLocalizedMessage();
}

# ─── String constant obfuscation hint ─────────────────────────────────────────
# R8 tidak encrypt string secara native, tapi -repackageclasses + -overloadaggressively
# + full mode akan inline dan menghapus sebanyak mungkin metadata.
# String encryption dihandle oleh ObfStr.kt (AES-256-CTR) di source level.
