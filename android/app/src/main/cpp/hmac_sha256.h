// hmac_sha256.h — HMAC-SHA256 + HKDF helper (reuses sha256.h)
// CDC v2 — CodeDev Cipher

#pragma once
#include "sha256.h"
#include <string.h>
#include <stddef.h>
#include <stdint.h>

#define _HMAC_BLOCK 64

static void hmac_sha256_fn(
    const uint8_t *key,  size_t key_len,
    const uint8_t *data, size_t data_len,
    uint8_t *out /* 32 bytes */)
{
    uint8_t tk[32], k_ipad[_HMAC_BLOCK], k_opad[_HMAC_BLOCK];

    if (key_len > _HMAC_BLOCK) {
        SHA256_CTX c; sha256_init(&c);
        sha256_update(&c, key, key_len);
        sha256_final(&c, tk);
        key = tk; key_len = 32;
    }

    memset(k_ipad, 0x36, _HMAC_BLOCK);
    memset(k_opad, 0x5c, _HMAC_BLOCK);
    for (size_t i = 0; i < key_len; i++) {
        k_ipad[i] ^= key[i];
        k_opad[i] ^= key[i];
    }

    uint8_t inner[32];
    SHA256_CTX c;
    sha256_init(&c);
    sha256_update(&c, k_ipad, _HMAC_BLOCK);
    sha256_update(&c, data, data_len);
    sha256_final(&c, inner);

    sha256_init(&c);
    sha256_update(&c, k_opad, _HMAC_BLOCK);
    sha256_update(&c, inner, 32);
    sha256_final(&c, out);
}

// HKDF-like single-block expand (32 bytes out)
// okm = HMAC-SHA256(HMAC-SHA256(salt, ikm), info || 0x01)
static void hkdf_32(
    const uint8_t *salt, size_t salt_len,
    const uint8_t *ikm,  size_t ikm_len,
    const uint8_t *info, size_t info_len,
    uint8_t *okm /* 32 bytes */)
{
    uint8_t prk[32];
    hmac_sha256_fn(salt, salt_len, ikm, ikm_len, prk);

    // T = info || 0x01, max info 64 bytes
    uint8_t t[65];
    size_t tl = (info_len > 64) ? 64 : info_len;
    memcpy(t, info, tl);
    t[tl] = 0x01;
    hmac_sha256_fn(prk, 32, t, tl + 1, okm);
}
