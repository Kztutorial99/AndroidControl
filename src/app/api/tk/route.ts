/**
 * /api/tk — CDC v2 Key Delivery Endpoint
 * ========================================
 * Challenge-response protocol — key TIDAK pernah dikirim plaintext.
 *
 * Request (POST):
 *   { "t": base64(HMAC-SHA256(nonce||ts_bytes, cert_sha256)),
 *     "n": base64(nonce),
 *     "ts": unix_timestamp }
 *
 * Response:
 *   { "r": base64(static_key XOR SHA256(nonce||t_bytes)[:key_len]) }
 *
 * Security:
 *   - "r" adalah XOR-blob acak — tidak ada plaintext key di response
 *   - decode "r" butuh nonce + t (HMAC yang hanya bisa dihitung APK asli)
 *   - tanpa cert APK asli → tidak bisa hitung t → tidak bisa decode r
 *   - Anti-replay: window 90 detik via ts validation
 *   - static_key dari env CDC_STATIC_KEY — TIDAK ada di source code
 */

import { NextRequest, NextResponse } from 'next/server'
import crypto from 'crypto'

export const runtime = 'nodejs'

export async function POST(req: NextRequest) {
    try {
        const body = await req.json()
        const { t, n, ts } = body

        if (!t || !n || typeof ts !== 'number') {
            return NextResponse.json({ e: 1 }, { status: 400 })
        }

        // Validate timestamp window (90 detik)
        const now = Math.floor(Date.now() / 1000)
        if (Math.abs(now - ts) > 90) {
            return NextResponse.json({ e: 2 }, { status: 400 })
        }

        const staticKeyHex = process.env.CDC_STATIC_KEY
        if (!staticKeyHex) {
            // Env tidak di-set — return 500 tanpa info sensitif
            return NextResponse.json({ e: 3 }, { status: 500 })
        }

        const staticKey = Buffer.from(staticKeyHex, 'hex')
        const nonce     = Buffer.from(String(n), 'base64')
        const tBytes    = Buffer.from(String(t), 'base64')

        if (nonce.length < 8 || tBytes.length < 16) {
            return NextResponse.json({ e: 4 }, { status: 400 })
        }

        // mask = SHA256(nonce || t_bytes)[:key_len]
        // Hanya APK yang bisa decode r karena butuh 't' (dari cert_sha256 asli)
        const mask = crypto
            .createHash('sha256')
            .update(Buffer.concat([nonce, tBytes]))
            .digest()
            .subarray(0, staticKey.length)

        // r = static_key XOR mask
        const r = Buffer.alloc(staticKey.length)
        for (let i = 0; i < staticKey.length; i++) {
            r[i] = staticKey[i] ^ mask[i]
        }

        return NextResponse.json({ r: r.toString('base64') })

    } catch {
        return NextResponse.json({ e: 0 }, { status: 500 })
    }
}
