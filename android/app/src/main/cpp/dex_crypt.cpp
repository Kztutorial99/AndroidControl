/**
 * dex_crypt.cpp — CodeDev Cipher v2 (CDC v2) — Native DEX Decryptor
 * ===================================================================
 * Algorithm: SHA256-chain stream cipher
 *   Key derivation : HKDF-SHA256(cert_sha256 + pkg_hash + runtime_key)
 *   Keystream      : double-pass SHA256 with counter twist
 *   Integrity      : HMAC-SHA256 over ciphertext
 *
 * runtime_key: fetched at runtime, not stored in APK
 *              TIDAK ada di APK / binary — zero dari memory setelah pakai
 *
 * JNI entry: DexCrypt.nativeDecrypt(enc, cert, pkg, runtimeKey)
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <android/log.h>
#include "sha256.h"
#include "hmac_sha256.h"

#define TAG  "CDC"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MAGIC    "CDC2"
#define HDR_SIZE 56       // 4+16+32+4
#define BLOCK    32

// ── Key derivation ────────────────────────────────────────────────────────────
static void cdc_derive_seed(
    const uint8_t *cert32,   // 32-byte cert SHA256
    const char    *pkg,
    const uint8_t *salt16,   // 16-byte dari header
    const uint8_t *rk,       // runtime key (dari server, bukan APK)
    size_t         rk_len,
    uint8_t       *seed_out) // 32 bytes out
{
    // pkg_hash = SHA256(pkg_name)
    uint8_t pkg_h[32];
    SHA256_CTX c; sha256_init(&c);
    sha256_update(&c, (const uint8_t *)pkg, strlen(pkg));
    sha256_final(&c, pkg_h);

    // ikm = cert32 || pkg_h || runtime_key
    size_t  ikm_len = 32 + 32 + rk_len;
    uint8_t *ikm    = (uint8_t *)alloca(ikm_len);
    memcpy(ikm,      cert32, 32);
    memcpy(ikm + 32, pkg_h,  32);
    memcpy(ikm + 64, rk,     rk_len);

    static const uint8_t info[] = {'C','D','C','-','E','N','C',0x01};
    hkdf_32(salt16, 16, ikm, ikm_len, info, sizeof(info), seed_out);

    // Zero ikm dari stack
    memset(ikm, 0, ikm_len);
}

static void cdc_derive_mac_key(const uint8_t *seed, uint8_t *mac_key_out) {
    static const uint8_t info[] = {'C','D','C','-','M','A','C',0x01};
    hkdf_32(seed, 32, seed, 32, info, sizeof(info), mac_key_out);
}

// ── Keystream block ───────────────────────────────────────────────────────────
static void cdc_ks(const uint8_t *seed, uint64_t n, uint8_t *ks_out) {
    uint8_t ctr[8];
    for (int i = 0; i < 8; i++) ctr[i] = (uint8_t)(n >> (i*8));

    uint8_t h1[32];
    SHA256_CTX c; sha256_init(&c);
    sha256_update(&c, seed, 32);
    sha256_update(&c, ctr, 8);
    sha256_final(&c, h1);

    // Twist: XOR counter ke h1[0..7]
    uint8_t tw[32];
    memcpy(tw, h1, 32);
    for (int i = 0; i < 8; i++) tw[i] ^= ctr[i];

    sha256_init(&c);
    sha256_update(&c, seed, 32);
    sha256_update(&c, tw, 32);
    sha256_final(&c, ks_out);
}

// ── Core decrypt ─────────────────────────────────────────────────────────────
static uint8_t* cdc_decrypt(
    const uint8_t *enc, size_t enc_len,
    const uint8_t *cert32,
    const char    *pkg,
    const uint8_t *rk, size_t rk_len,
    size_t        *out_len)
{
    if (enc_len < (size_t)HDR_SIZE) { LOGE("too small"); return nullptr; }
    if (memcmp(enc, MAGIC, 4) != 0)  { LOGE("bad magic"); return nullptr; }

    const uint8_t *salt    = enc + 4;
    const uint8_t *mac_exp = enc + 20;
    uint32_t orig_sz; memcpy(&orig_sz, enc + 52, 4);
    const uint8_t *cipher  = enc + HDR_SIZE;
    size_t clen = enc_len - HDR_SIZE;

    LOGI("orig=%u cipher=%zu", orig_sz, clen);

    uint8_t seed[32];
    cdc_derive_seed(cert32, pkg, salt, rk, rk_len, seed);

    // Verify MAC
    uint8_t mk[32]; cdc_derive_mac_key(seed, mk);
    uint8_t mac[32]; hmac_sha256_fn(mk, 32, cipher, clen, mac);
    if (memcmp(mac, mac_exp, 32) != 0) {
        LOGE("MAC FAIL — wrong key / cert / pkg / tampered");
        memset(seed, 0, 32);
        return nullptr;
    }

    uint8_t *plain = (uint8_t *)malloc(orig_sz);
    if (!plain) { LOGE("malloc fail"); return nullptr; }

    uint64_t nb = (clen + BLOCK - 1) / BLOCK;
    for (uint64_t i = 0; i < nb; i++) {
        uint8_t ks[BLOCK]; cdc_ks(seed, i, ks);
        size_t off = i * BLOCK;
        size_t bl  = (clen - off < BLOCK) ? (clen - off) : BLOCK;
        for (size_t j = 0; j < bl; j++)
            plain[off + j] = cipher[off + j] ^ ks[j];
    }

    // Zero seed dari stack
    memset(seed, 0, 32);
    memset(mk,   0, 32);

    *out_len = orig_sz;
    LOGI("decrypt OK %u bytes", orig_sz);
    return plain;
}

// ── JNI ──────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_android_services_DexCrypt_nativeDecrypt(
    JNIEnv *env, jclass,
    jbyteArray enc_j,
    jbyteArray cert_j,
    jstring    pkg_j,
    jbyteArray rk_j)   // runtime key dari server (KeyFetcher.kt)
{
    jsize  enc_len  = env->GetArrayLength(enc_j);
    jbyte *enc_raw  = env->GetByteArrayElements(enc_j,  nullptr);
    jbyte *cert_raw = env->GetByteArrayElements(cert_j, nullptr);
    jsize  cert_len = env->GetArrayLength(cert_j);
    jbyte *rk_raw   = env->GetByteArrayElements(rk_j,   nullptr);
    jsize  rk_len   = env->GetArrayLength(rk_j);
    const char *pkg = env->GetStringUTFChars(pkg_j, nullptr);

    jbyteArray result = nullptr;

    if (cert_len == 32 && rk_len > 0) {
        size_t plen = 0;
        uint8_t *plain = cdc_decrypt(
            (uint8_t *)enc_raw, (size_t)enc_len,
            (uint8_t *)cert_raw, pkg,
            (uint8_t *)rk_raw,  (size_t)rk_len,
            &plen);

        if (plain) {
            result = env->NewByteArray((jsize)plen);
            env->SetByteArrayRegion(result, 0, (jsize)plen, (jbyte *)plain);
            memset(plain, 0, plen);   // zero plaintext DEX sebelum free
            free(plain);
        }
    } else {
        LOGE("bad params: cert=%d rk=%d", cert_len, rk_len);
    }

    // Zero runtime key dari JNI buffer sebelum release
    if (rk_raw) memset(rk_raw, 0, rk_len);

    env->ReleaseByteArrayElements(enc_j,  enc_raw,  JNI_ABORT);
    env->ReleaseByteArrayElements(cert_j, cert_raw, JNI_ABORT);
    env->ReleaseByteArrayElements(rk_j,   rk_raw,   JNI_ABORT);
    env->ReleaseStringUTFChars(pkg_j, pkg);
    return result;
}
