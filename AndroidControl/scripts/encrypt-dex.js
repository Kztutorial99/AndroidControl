#!/usr/bin/env node
/**
 * encrypt-dex.js — AES-256-GCM encrypt a .dex file for server storage.
 * Output: [12 bytes IV][ciphertext + 16 bytes auth tag]
 *
 * Usage: node scripts/encrypt-dex.js <input.dex> <output.dex.enc>
 * Key must match DexModuleLoader.aesKey() in the Android app.
 */
const crypto = require('crypto');
const fs     = require('fs');

// Must match DexModuleLoader ENC XOR MSK
const ENC = [0x13,0x3D,0x22,0x1E,0x3B,0x36,0x35,0x27,0x25,0x5B,0x7E,0x4A,0x5B,0x7E,0x4F,0x5A,
             0x69,0x1E,0x38,0x3C,0x6F,0x3C,0x28,0x38,0x29,0x3C,0x22,0x38,0x7B,0x15,0x55,0x25];
const MSK = Array(32).fill(0x5A);
const KEY  = Buffer.from(ENC.map((b, i) => b ^ MSK[i]));

const [,, input, output] = process.argv;
if (!input || !output) { console.error('Usage: node encrypt-dex.js <input.dex> <output.dex.enc>'); process.exit(1); }

const plain  = fs.readFileSync(input);
const iv     = crypto.randomBytes(12);
const cipher = crypto.createCipheriv('aes-256-gcm', KEY, iv);
const enc    = Buffer.concat([cipher.update(plain), cipher.final()]);
const tag    = cipher.getAuthTag();
fs.writeFileSync(output, Buffer.concat([iv, enc, tag]));
console.log(`✅ ${input} → ${output} (${plain.length} → ${iv.length + enc.length + tag.length} bytes)`);
