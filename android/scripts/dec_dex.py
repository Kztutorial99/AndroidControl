#!/usr/bin/env python3
"""
dec_dex.py — CDC v2 DEX Decryptor (testing tool)
Usage: python3 dec_dex.py <enc_file> <cert_sha256_hex> <pkg_name> <output.dex>
"""

import sys, hashlib, hmac as hmaclib, struct, os

MAGIC      = b'CDC2'
STATIC_KEY = b'CodeDev-CDC-v2-2026-IWX'
BLOCK      = 32
HDR_SIZE   = 56

def _hmac(key: bytes, data: bytes) -> bytes:
    return hmaclib.new(key, data, hashlib.sha256).digest()

def derive_seed(cert_hex: str, pkg_name: str, salt: bytes) -> bytes:
    cert  = bytes.fromhex(cert_hex)
    pkg_h = hashlib.sha256(pkg_name.encode()).digest()
    ikm   = cert + pkg_h + STATIC_KEY
    prk   = _hmac(salt, ikm)
    return _hmac(prk, b'CDC-ENC\x01')

def derive_mac_key(seed: bytes) -> bytes:
    return _hmac(_hmac(seed, seed), b'CDC-MAC\x01')

def ks_block(seed: bytes, n: int) -> bytes:
    ctr  = struct.pack('<Q', n)
    h1   = hashlib.sha256(seed + ctr).digest()
    twst = bytes(h1[i] ^ ctr[i] for i in range(8)) + h1[8:]
    return hashlib.sha256(seed + twst).digest()

def main():
    if len(sys.argv) != 5:
        print(f"Usage: {sys.argv[0]} <enc_file> <cert_sha256_hex> <pkg_name> <output.dex>")
        sys.exit(1)

    enc_path, cert_hex, pkg_name, out_path = sys.argv[1:]

    with open(enc_path, 'rb') as f:
        data = f.read()

    if len(data) < HDR_SIZE:
        print("[!] File too small"); sys.exit(1)
    if data[:4] != MAGIC:
        print(f"[!] Bad magic: {data[:4]}"); sys.exit(1)

    salt     = data[4:20]
    mac_exp  = data[20:52]
    orig_sz  = struct.unpack('<I', data[52:56])[0]
    cipher   = data[HDR_SIZE:]

    print(f"[*] Enc size : {len(data):,} bytes")
    print(f"[*] Orig size: {orig_sz:,} bytes")
    print(f"[*] Salt     : {salt.hex()}")

    seed  = derive_seed(cert_hex, pkg_name, salt)
    mac_k = derive_mac_key(seed)
    mac   = _hmac(mac_k, cipher)

    if mac != mac_exp:
        print(f"[!] MAC MISMATCH — wrong key or tampered file")
        print(f"    Expected : {mac_exp.hex()[:16]}...")
        print(f"    Got      : {mac.hex()[:16]}...")
        sys.exit(1)
    print(f"[✓] MAC OK")

    blocks = (len(cipher) + BLOCK - 1) // BLOCK
    plain  = bytearray()
    for i in range(blocks):
        ks  = ks_block(seed, i)
        blk = cipher[i*BLOCK:(i+1)*BLOCK]
        plain.extend(b ^ k for b, k in zip(blk, ks))

    plain = bytes(plain[:orig_sz])

    with open(out_path, 'wb') as f:
        f.write(plain)
    print(f"[✓] Decrypted: {out_path} ({len(plain):,} bytes)")

if __name__ == '__main__':
    main()
