import { NextRequest, NextResponse } from 'next/server'
import { getOrCreateDevice, popCommand } from '@/lib/store'
import { initSchema } from '@/lib/db'
import { isStreaming, waitForStreamCommand } from '@/lib/stream-registry'

export const dynamic = 'force-dynamic'

const _ready = initSchema()

export async function GET(req: NextRequest) {
  try {
    await _ready
    const deviceId = req.nextUrl.searchParams.get('deviceId')
    if (!deviceId) {
      return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    }

    // ── FAST PATH: streaming → LONG-POLL (zero DB) ───────────────────────────
    // Server holds connection until command ready (max 12s).
    // Eliminates Android sleep(500ms)+retry cycle when command not yet available.
    // Android OkHttp timeout = 25s, server timeout = 12s → safe margin.
    if (isStreaming(deviceId)) {
      const cmd = await waitForStreamCommand(deviceId, 12000)
      return NextResponse.json({
        command:    cmd,
        commandId:  cmd ? `s-${Date.now()}` : null,
        extra:      null,
        serverTime: new Date().toISOString(),
      })
    }

    // ── NORMAL PATH: regular DB commands ─────────────────────────────────────
    await getOrCreateDevice(deviceId)
    const pending = await popCommand(deviceId)
    return NextResponse.json({
      command:    pending?.command   ?? null,
      commandId:  pending?.id        ?? null,
      extra:      pending?.extra     ?? null,
      serverTime: new Date().toISOString(),
    })
  } catch (e) {
    console.error('poll error:', e)
    return NextResponse.json({ command: null, commandId: null, extra: null, serverTime: new Date().toISOString() })
  }
}
