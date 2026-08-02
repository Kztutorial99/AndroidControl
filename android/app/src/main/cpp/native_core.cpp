/**
 * native_core.cpp — JNI facade untuk NativeCore.kt
 * ==================================================
 * Migrasi dari Java-layer:
 *   - ObfStr (25 string) → getString(id)
 *   - SecureConfig.serverUrl() → getServerUrl()
 *   - AntiAnalysis.{isDebuggerActive,isEmulator,isFridaPresent,
 *                   isRooted,verifySignature,runChecks}
 *
 * Semua string di-obfuscate via string_obf.h (XOR rolling key).
 * Anti-analysis checks di-native — jauh lebih susah di-hook via Xposed/Frida
 * dibanding equivalent Kotlin.
 */
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <android/log.h>
#include <string>

#include "string_obf.h"
#include "string_table.h"

#define TAG "NCore"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── String decode ─────────────────────────────────────────────────────────────
static std::string decode_id(int id) {
    if (id < 0 || id >= SID_COUNT) return {};
    const ObfEntry &e = g_obf_table[id];
    return obfs_to_string(e.data, e.len);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nexlink_bridge_NativeCore_getString(JNIEnv *env, jclass, jint id) {
    std::string s = decode_id((int)id);
    jstring result = env->NewStringUTF(s.c_str());
    // Zero plaintext buffer sebelum std::string destructor
    if (!s.empty()) memset(&s[0], 0, s.size());
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nexlink_bridge_NativeCore_getServerUrl(JNIEnv *env, jclass) {
    std::string s = decode_id(SID___serverUrl);
    jstring result = env->NewStringUTF(s.c_str());
    if (!s.empty()) memset(&s[0], 0, s.size());
    return result;
}

// ── Anti-Analysis ─────────────────────────────────────────────────────────────

// Baca file kecil ke buffer (dipakai untuk /proc/self/status & /proc/self/maps).
// Return true kalau baris apapun match salah satu needles.
static bool file_contains_any(const char *path, const char *const *needles, size_t n) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return false;
    char buf[4096];
    std::string all;
    ssize_t r;
    while ((r = read(fd, buf, sizeof(buf))) > 0) {
        all.append(buf, (size_t)r);
        if (all.size() > 256 * 1024) break; // cap 256KB
    }
    close(fd);
    for (size_t i = 0; i < n; i++) {
        if (all.find(needles[i]) != std::string::npos) return true;
    }
    return false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nexlink_bridge_NativeCore_isDebuggerActive(JNIEnv *, jclass) {
    // /proc/self/status → TracerPid: <n>  (0 = tidak di-trace)
    int fd = open("/proc/self/status", O_RDONLY);
    if (fd < 0) return JNI_FALSE;
    char buf[2048]; ssize_t r = read(fd, buf, sizeof(buf) - 1); close(fd);
    if (r <= 0) return JNI_FALSE;
    buf[r] = 0;
    const char *p = strstr(buf, "TracerPid:");
    if (!p) return JNI_FALSE;
    p += 10; while (*p == ' ' || *p == '\t') p++;
    return (*p != '0') ? JNI_TRUE : JNI_FALSE;
}

// Baca system_property (ganti dependency pada android.os.Build.*)
static std::string sys_prop(const char *name) {
    char v[PROP_VALUE_MAX] = {0};
    __system_property_get(name, v);
    return std::string(v);
}
static bool sp_contains(const char *name, const char *needle) {
    return sys_prop(name).find(needle) != std::string::npos;
}
static bool sp_startswith(const char *name, const char *prefix) {
    std::string v = sys_prop(name);
    return v.compare(0, strlen(prefix), prefix) == 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nexlink_bridge_NativeCore_isEmulator(JNIEnv *, jclass) {
    int hits = 0;
    if (sp_startswith("ro.build.fingerprint", "generic")) hits++;
    if (sp_startswith("ro.build.fingerprint", "unknown")) hits++;
    if (sp_contains  ("ro.product.model",     "google_sdk")) hits++;
    if (sp_contains  ("ro.product.model",     "Emulator")) hits++;
    if (sp_contains  ("ro.product.model",     "Android SDK built for x86")) hits++;
    if (sys_prop("ro.product.manufacturer") == "Genymotion") hits++;
    if (sp_startswith("ro.product.brand",     "generic")) hits++;
    if (sp_startswith("ro.product.device",    "generic")) hits++;
    if (sp_contains  ("ro.product.name",      "sdk")) hits++;
    if (sp_contains  ("ro.product.name",      "vbox")) hits++;
    if (sp_contains  ("ro.product.name",      "emulator")) hits++;
    if (sp_contains  ("ro.hardware",          "goldfish")) hits++;
    if (sp_contains  ("ro.hardware",          "ranchu")) hits++;
    return (hits >= 3) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nexlink_bridge_NativeCore_isFridaPresent(JNIEnv *, jclass) {
    static const char *needles[] = {
        "frida", "gum-js-loop", "gmain", "linjector", "frida-agent", "re.frida.server"
    };
    if (file_contains_any("/proc/self/maps", needles, sizeof(needles)/sizeof(*needles)))
        return JNI_TRUE;
    // Cek frida-server default port (27042) — hanya sekilas via /proc/net/tcp
    static const char *port_needle[] = { ":69A2" /* 27042 hex */ };
    if (file_contains_any("/proc/net/tcp", port_needle, 1)) return JNI_TRUE;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nexlink_bridge_NativeCore_isRooted(JNIEnv *, jclass) {
    static const char *su_paths[] = {
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/system/sd/xbin/su", "/system/bin/failsafe/su",
        "/data/local/xbin/su", "/data/local/bin/su",
        "/data/local/su", "/su/bin/su",
        "/magisk/.core/bin/su", "/system/app/Superuser.apk"
    };
    struct stat st;
    for (size_t i = 0; i < sizeof(su_paths)/sizeof(*su_paths); i++) {
        if (stat(su_paths[i], &st) == 0) return JNI_TRUE;
    }
    return JNI_FALSE;
}

/**
 * verifySignature — dipanggil dari Kotlin dengan hex-string SHA256 signature.
 * Native compare terhadap expected pinned hash (opsional) atau simpan-first-use
 * pattern via callback ke Java (di sini kita expose comparator saja).
 *
 * Karena signature harus di-fetch via PackageManager (Java-only API), Kotlin
 * side yang ambil signature bytes lalu native hash + compare + persist.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_nexlink_bridge_NativeCore_verifySignatureHash(
    JNIEnv *env, jclass,
    jstring currentHex, jstring storedHex)
{
    if (!currentHex) return JNI_FALSE;
    const char *cur = env->GetStringUTFChars(currentHex, nullptr);
    if (!storedHex) { env->ReleaseStringUTFChars(currentHex, cur); return JNI_TRUE; }
    const char *stored = env->GetStringUTFChars(storedHex, nullptr);
    // Constant-time-ish compare
    size_t lc = strlen(cur), ls = strlen(stored);
    jboolean ok = (lc == ls) ? JNI_TRUE : JNI_FALSE;
    unsigned char diff = (unsigned char)(lc ^ ls);
    size_t n = lc < ls ? lc : ls;
    for (size_t i = 0; i < n; i++) diff |= (unsigned char)(cur[i] ^ stored[i]);
    if (diff != 0) ok = JNI_FALSE;
    env->ReleaseStringUTFChars(currentHex, cur);
    env->ReleaseStringUTFChars(storedHex, stored);
    return ok;
}
