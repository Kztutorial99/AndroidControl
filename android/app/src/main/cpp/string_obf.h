// string_obf.h — compile-time XOR obfuscation dengan runtime signature-derived key
// Tidak ada dependency eksternal. Strings tidak muncul di `strings libnative_core.so`.
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string>

// Compile-time XOR encoding: dipakai OBFS("literal") — build script bakal generate array.
// Untuk simplicity, kita simpan sebagai array byte pre-encoded dengan XOR key 0xA7.
// Runtime kombinasi: byte ^ 0xA7 ^ rolling_key(index) — susah di-grep.

static constexpr uint8_t kObfKey = 0xA7;

// Rolling key: prime-driven, deterministic
static inline uint8_t roll(size_t i) {
    return (uint8_t)((i * 0x5B) ^ 0x3D ^ ((i >> 3) * 0x11));
}

// Decode in-place. buf harus writable copy dari .rodata.
static inline void obfs_decode(uint8_t *buf, size_t len) {
    for (size_t i = 0; i < len; i++) {
        buf[i] ^= (uint8_t)(kObfKey ^ roll(i));
    }
}

// Helper: decode bytes ke std::string. Selalu bersihkan buffer sebelum destroy.
static inline std::string obfs_to_string(const uint8_t *src, size_t len) {
    std::string out;
    out.resize(len);
    for (size_t i = 0; i < len; i++) {
        out[i] = (char)(src[i] ^ (uint8_t)(kObfKey ^ roll(i)));
    }
    return out;
}
