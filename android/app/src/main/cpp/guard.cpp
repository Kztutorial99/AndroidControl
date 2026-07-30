/**
 * guard.cpp — IWX APK Guard (Native Layer, 2026)
 *
 * Protection Layers:
 *   L1  Certificate SHA-256 fingerprint pinning  → anti-repack / anti-resign
 *   L2  Package name verification                → anti-clone
 *   L3  Anti-debug   /proc/self/status TracerPid  (warn only)
 *   L4  Anti-Frida   port scan + /proc/self/maps  (warn only)
 *   L5  Root detect  su binary paths
 *   L6  Emulator     ro.kernel.qemu / hardware
 *
 * Failure response: busy_loop() → raise(SIGKILL)
 *   → screen freezes/blank, then process dies. No crash dialog.
 *
 * Cert check is skipped when cert_hash.h has all-zero placeholder (dev mode).
 * Run android/scripts/setup_guard.sh to activate production enforcement.
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <pthread.h>
#include <signal.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>
#include "sha256.h"
#include "cert_hash.h"

#define TAG "IWX"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

// ─── CodeDev Protection Brand ─────────────────────────────────────────────────
// volatile + __attribute__((used)) = tidak di-strip linker, muncul di strings .so
static const volatile char _brand[]  __attribute__((used)) =
    "\x43\x6F\x64\x65\x44\x65\x76"   // CodeDev
    "\x20\x53\x65\x63\x75\x72\x69\x74\x79"  //  Security
    "\x20\x76\x31\x2E\x30";             //  v1.0
static const volatile char _brand2[] __attribute__((used)) =
    "Protected by CodeDev | com.codedev.protection";
// ───────────────────────────────────────────────────────────────────────────────


// ─── XOR decode (same scheme as ObfStr.kt) ───────────────────────────────────
static void xdec(const int *enc, int n, char *out) {
    for (int i = 0; i < n; i++) out[i] = (char)(enc[i] ^ _xk[i % 12]);
    out[n] = '\0';
}

// ─── Termination: freeze screen → kill ───────────────────────────────────────
static void __attribute__((noreturn)) guard_die(const char *reason) {
    LOGW("GUARD FAIL: %s — terminating", reason);
    // Spin long enough for the screen to go blank/freeze
    volatile int x = 1;
    for (volatile long i = 0; i < 500000000L; i++) { if (!x) break; }
    raise(SIGKILL);
    __builtin_unreachable();
}

// ─────────────────────────────────────────────────────────────────────────────
//  Helpers to call Android Java from JNI
// ─────────────────────────────────────────────────────────────────────────────

static jobjectArray get_signatures(JNIEnv *env, jobject ctx) {
    // Get PackageManager
    jclass ctx_cls = env->GetObjectClass(ctx);
    jmethodID mid_pm = env->GetMethodID(ctx_cls, "getPackageManager",
        "()Landroid/content/pm/PackageManager;");
    if (!mid_pm) return nullptr;
    jobject pm = env->CallObjectMethod(ctx, mid_pm);
    if (!pm) return nullptr;

    // Package name
    jmethodID mid_pkg = env->GetMethodID(ctx_cls, "getPackageName", "()Ljava/lang/String;");
    jstring pkg_js = (jstring)env->CallObjectMethod(ctx, mid_pkg);

    // API level
    jclass bv = env->FindClass("android/os/Build$VERSION");
    jint api  = env->GetStaticIntField(bv, env->GetStaticFieldID(bv, "SDK_INT", "I"));

    jint flags = (api >= 28) ? 0x8000000 : 0x40; // GET_SIGNING_CERTIFICATES : GET_SIGNATURES
    jclass pm_cls = env->GetObjectClass(pm);
    jmethodID mid_gpi = env->GetMethodID(pm_cls, "getPackageInfo",
        "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    if (!mid_gpi) return nullptr;

    jobject pi = env->CallObjectMethod(pm, mid_gpi, pkg_js, flags);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return nullptr; }
    if (!pi) return nullptr;

    jclass pi_cls = env->GetObjectClass(pi);

    if (api >= 28) {
        jfieldID fi = env->GetFieldID(pi_cls, "signingInfo", "Landroid/content/pm/SigningInfo;");
        if (fi) {
            jobject si = env->GetObjectField(pi, fi);
            if (si) {
                jclass si_cls = env->GetObjectClass(si);
                jmethodID gacs = env->GetMethodID(si_cls, "getApkContentsSigners",
                    "()[Landroid/content/pm/Signature;");
                if (gacs) {
                    jobjectArray arr = (jobjectArray)env->CallObjectMethod(si, gacs);
                    if (arr && env->GetArrayLength(arr) > 0) return arr;
                }
            }
        }
    }

    // Fallback: legacy signatures field
    jfieldID fi2 = env->GetFieldID(pi_cls, "signatures", "[Landroid/content/pm/Signature;");
    if (!fi2) return nullptr;
    return (jobjectArray)env->GetObjectField(pi, fi2);
}

static jbyteArray sig_to_bytes(JNIEnv *env, jobject sig) {
    jclass sc = env->GetObjectClass(sig);
    jmethodID tb = env->GetMethodID(sc, "toByteArray", "()[B");
    return tb ? (jbyteArray)env->CallObjectMethod(sig, tb) : nullptr;
}

// ─────────────────────────────────────────────────────────────────────────────
//  L1: Certificate fingerprint check
// ─────────────────────────────────────────────────────────────────────────────

static int l1_cert(JNIEnv *env, jobject ctx) {
    // Dev mode: all-zero placeholder → skip
    int allz = 1;
    for (int i = 0; i < 32 && allz; i++) if (_ce[i] != 0) allz = 0;
    if (allz) { LOGI("L1: dev mode, cert check skipped"); return 1; }

    jobjectArray sigs = get_signatures(env, ctx);
    if (!sigs || env->GetArrayLength(sigs) == 0) return 1; // can't read, skip

    jobject sig0 = env->GetObjectArrayElement(sigs, 0);
    jbyteArray raw_ba = sig_to_bytes(env, sig0);
    if (!raw_ba) return 1;

    jsize len = env->GetArrayLength(raw_ba);
    jbyte *raw = env->GetByteArrayElements(raw_ba, nullptr);

    SHA256_CTX sh; sha256_init(&sh);
    sha256_update(&sh, (const uint8_t *)raw, (size_t)len);
    uint8_t got[32]; sha256_final(&sh, got);
    env->ReleaseByteArrayElements(raw_ba, raw, JNI_ABORT);

    // Decode expected
    uint8_t exp[32];
    for (int i = 0; i < 32; i++) exp[i] = (uint8_t)(_ce[i] ^ _xk[i % 12]);

    int ok = (memcmp(got, exp, 32) == 0);
    if (!ok) LOGW("L1: cert MISMATCH — repack detected");
    return ok;
}

// ─────────────────────────────────────────────────────────────────────────────
//  L2: Package name
// ─────────────────────────────────────────────────────────────────────────────

static int l2_pkg(JNIEnv *env, jobject ctx) {
    jclass c = env->GetObjectClass(ctx);
    jmethodID m = env->GetMethodID(c, "getPackageName", "()Ljava/lang/String;");
    if (!m) return 1;
    jstring js = (jstring)env->CallObjectMethod(ctx, m);
    if (!js) return 1;
    const char *pkg = env->GetStringUTFChars(js, nullptr);

    char exp[64]; xdec(_pe, _pe_len, exp);
    int ok = (strcmp(pkg, exp) == 0);
    if (!ok) LOGW("L2: pkg mismatch [%s]", pkg);
    env->ReleaseStringUTFChars(js, pkg);
    return ok;
}

// ─────────────────────────────────────────────────────────────────────────────
//  L3: Anti-debug — TracerPid
// ─────────────────────────────────────────────────────────────────────────────

static int l3_nodebug() {
    int fd = open("/proc/self/status", O_RDONLY);
    if (fd < 0) return 1;
    char buf[512] = {};
    read(fd, buf, sizeof(buf)-1);
    close(fd);
    const char *p = strstr(buf, "TracerPid:");
    if (!p) return 1;
    int tid = 0; sscanf(p+10, "%d", &tid);
    if (tid != 0) { LOGW("L3: debugger pid=%d", tid); return 0; }
    return 1;
}

// ─────────────────────────────────────────────────────────────────────────────
//  L4: Anti-Frida
// ─────────────────────────────────────────────────────────────────────────────

static int tcp_open(int port) {
    int s = socket(AF_INET, SOCK_STREAM, 0);
    if (s < 0) return 0;
    struct timeval tv = {0, 50000};
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    struct sockaddr_in a = {};
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = inet_addr("127.0.0.1");
    a.sin_port = htons((uint16_t)port);
    int r = connect(s, (struct sockaddr *)&a, sizeof(a));
    close(s);
    return (r == 0);
}

static int l4_nofrida() {
    // Port scan: frida-server default ports
    static const int ports[] = {27042, 27043, 27044, 27045, 0};
    for (int i = 0; ports[i]; i++) {
        if (tcp_open(ports[i])) { LOGW("L4: frida port %d", ports[i]); return 0; }
    }
    // /proc/self/maps scan for gadget/agent libraries
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 1;
    char line[512];
    static const char * const marks[] = {
        "frida-agent", "frida-gadget", "gum-js-loop", "linjector", nullptr
    };
    int hit = 0;
    while (!hit && fgets(line, sizeof(line), f)) {
        for (int i = 0; marks[i] && !hit; i++)
            if (strstr(line, marks[i])) { LOGW("L4: frida map: %s", marks[i]); hit = 1; }
    }
    fclose(f);
    return !hit;
}

// ─────────────────────────────────────────────────────────────────────────────
//  L5: Root detection (warn only — not fatal by default)
// ─────────────────────────────────────────────────────────────────────────────

static int l5_root_warn() {
    static const char * const paths[] = {
        "/system/bin/su","/system/xbin/su","/sbin/su",
        "/system/su","/vendor/bin/su","/data/local/su",
        "/data/local/xbin/su", nullptr
    };
    for (int i = 0; paths[i]; i++)
        if (access(paths[i], F_OK) == 0) { LOGW("L5: root su at %s", paths[i]); return 0; }
    return 1;
}

// ─────────────────────────────────────────────────────────────────────────────
//  L6: Emulator detection (warn only — not fatal by default)
// ─────────────────────────────────────────────────────────────────────────────

static jstring sysprop(JNIEnv *env, const char *key) {
    jclass cls = env->FindClass("android/os/SystemProperties");
    if (!cls) return nullptr;
    jmethodID m = env->GetStaticMethodID(cls, "get", "(Ljava/lang/String;)Ljava/lang/String;");
    if (!m) return nullptr;
    return (jstring)env->CallStaticObjectMethod(cls, m, env->NewStringUTF(key));
}

static int l6_emu_warn(JNIEnv *env) {
    jstring qemu_js = sysprop(env, "ro.kernel.qemu");
    if (qemu_js) {
        const char *q = env->GetStringUTFChars(qemu_js, nullptr);
        int is_qemu = (strcmp(q,"1")==0);
        env->ReleaseStringUTFChars(qemu_js, q);
        if (is_qemu) { LOGW("L6: emulator qemu=1"); return 0; }
    }
    jstring hw_js = sysprop(env, "ro.hardware");
    if (hw_js) {
        const char *hw = env->GetStringUTFChars(hw_js, nullptr);
        int emu = (strstr(hw,"goldfish")||strstr(hw,"ranchu")) ? 1 : 0;
        env->ReleaseStringUTFChars(hw_js, hw);
        if (emu) { LOGW("L6: emulator hw=%s", hw_js); return 0; }
    }
    return 1;
}

// ─────────────────────────────────────────────────────────────────────────────
//  Continuous watcher thread — re-checks L3+L4 every 5 seconds
// ─────────────────────────────────────────────────────────────────────────────

static volatile int g_watch = 0;

static void *watcher(void *) {
    while (g_watch) {
        sleep(5);
        l3_nodebug(); // warn only
        l4_nofrida();  // warn only
    }
    return nullptr;
}

// ─────────────────────────────────────────────────────────────────────────────
//  JNI: main guard entry — com.android.services.Guard.nativeInit()
// ─────────────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_android_services_Guard_nativeInit(JNIEnv *env, jclass, jobject ctx) {
    if (!l1_cert(env, ctx)) guard_die("cert mismatch");
    if (!l2_pkg(env, ctx))  guard_die("package mismatch");
    l3_nodebug(); // warn only — false positive on MIUI/Xiaomi
    l4_nofrida();   // warn only — false positive on some devices
    l5_root_warn();
    l6_emu_warn(env);

    // Start background watcher
    g_watch = 1;
    pthread_t tid;
    pthread_create(&tid, nullptr, watcher, nullptr);
    pthread_detach(tid);

    LOGI("Guard init OK");
    return JNI_TRUE;
}

// ─────────────────────────────────────────────────────────────────────────────
//  JNI: return cert SHA-256 hex — com.android.services.Guard.nativeGetCertHex()
// ─────────────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jstring JNICALL
Java_com_android_services_Guard_nativeGetCertHex(JNIEnv *env, jclass, jobject ctx) {
    jobjectArray sigs = get_signatures(env, ctx);
    if (!sigs || env->GetArrayLength(sigs) == 0)
        return env->NewStringUTF("error:no_sig");

    jbyteArray ba = sig_to_bytes(env, env->GetObjectArrayElement(sigs, 0));
    if (!ba) return env->NewStringUTF("error:no_bytes");

    jsize len = env->GetArrayLength(ba);
    jbyte *raw = env->GetByteArrayElements(ba, nullptr);
    SHA256_CTX sh; sha256_init(&sh);
    sha256_update(&sh, (const uint8_t *)raw, (size_t)len);
    uint8_t digest[32]; sha256_final(&sh, digest);
    env->ReleaseByteArrayElements(ba, raw, JNI_ABORT);

    char hex[65]; hex[64] = '\0';
    for (int i = 0; i < 32; i++) sprintf(hex+i*2, "%02x", digest[i]);
    return env->NewStringUTF(hex);
}
