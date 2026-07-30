#!/usr/bin/env python3
"""
enc_dex.py — CodeDev Cipher v2 (CDC v2) DEX Encryptor
=======================================================
Algorithm: SHA256-chain stream cipher (NOT XOR/AES/ChaCha — custom)

Key derivation:
  ikm  = cert_sha256 || SHA256(pkg_name) || static_key
  prk  = HMAC-SHA256(salt, ikm)         -- HKDF extract
  seed = HMAC-SHA256(prk, "CDC-ENC\x01") -- HKDF expand

Keystream per 32-byte block:
  h1 = SHA256(seed || counter_LE8)
  twist = h1[:8] XOR counter_LE8 || h1[8:]
  K_n = SHA256(seed || twist)            -- double-pass

Cipher: C[n] = P[n] XOR K_n             -- keystream cipher (NOT static XOR)
MAC   : HMAC-SHA256(mac_key, ciphertext) -- integrity

File format:
  [0..3]   "CDC2" magic
  [4..19]  16-byte random build salt
  [20..51] 32-byte HMAC-SHA256 MAC
  [52..55] 4-byte original DEX size (LE)
  [56+]    encrypted DEX

Usage:
  python3 enc_dex.py <dex> <cert_sha256_hex> <pkg_name> <output.enc>
"""

import sys, hashlib, hmac as hmaclib, struct, secrets, os

MAGIC      = b'CDC2'
STATIC_KEY = b'CodeDev-CDC-v2-2026-IWX'
BLOCK      = 32
HDR_SIZE   = 56  # 4+16+32+4

def _hmac(key: bytes, data: bytes) -> bytes:
    return hmaclib.new(key, data, hashlib.sha256).digest()

def derive_seed(cert_hex: str, pkg_name: str, salt: bytes) -> bytes:
    cert  = bytes.fromhex(cert_hex)
    pkg_h = hashlib.sha256(pkg_name.encode()).digest()
    ikm   = cert + pkg_h + STATIC_KEY
    prk   = _hmac(salt, ikm)
    okm   = _hmac(prk, b'CDC-ENC\x01')
    return okm

def derive_mac_key(seed: bytes) -> bytes:
    prk = _hmac(seed, seed)
    return _hmac(prk, b'CDC-MAC\x01')

def ks_block(seed: bytes, n: int) -> bytes:
    ctr  = struct.pack('<Q', n)
    h1   = hashlib.sha256(seed + ctr).digest()
    twst = bytes(h1[i] ^ ctr[i] for i in range(8)) + h1[8:]
    return hashlib.sha256(seed + twst).digest()

def encrypt(plain: bytes, seed: bytes) -> bytes:
    out = bytearray()
    blocks = (len(plain) + BLOCK - 1) // BLOCK
    for i in range(blocks):
        ks  = ks_block(seed, i)
        blk = plain[i*BLOCK:(i+1)*BLOCK]
        out.extend(b ^ k for b, k in zip(blk, ks))
    return bytes(out)

def main():
    if len(sys.argv) != 5:
        print(f"Usage: {sys.argv[0]} <dex> <cert_sha256_hex> <pkg_name> <output>")
        sys.exit(1)

    dex_path, cert_hex, pkg_name, out_path = sys.argv[1:]

    if len(cert_hex) != 64:
        print(f"[!] cert_sha256_hex must be 64 hex chars (got {len(cert_hex)})")
        sys.exit(1)

    with open(dex_path, 'rb') as f:
        plain = f.read()
    print(f"[*] Input  : {os.path.basename(dex_path)} ({len(plain):,} bytes)")

    salt   = secrets.token_bytes(16)
    seed   = derive_seed(cert_hex, pkg_name, salt)
    cipher = encrypt(plain, seed)
    mac_k  = derive_mac_key(seed)
    mac    = _hmac(mac_k, cipher)
    size   = struct.pack('<I', len(plain))
    output = MAGIC + salt + mac + size + cipher

    with open(out_path, 'wb') as f:
        f.write(output)

    print(f"[*] Salt   : {salt.hex()}")
    print(f"[*] MAC    : {mac.hex()[:16]}...")
    print(f"[✓] Output : {os.path.basename(out_path)} ({len(output):,} bytes)")
    print(f"[✓] Algo   : CDC v2 | SHA256-chain stream | cert+pkg bound")

if __name__ == '__main__':
    main()
