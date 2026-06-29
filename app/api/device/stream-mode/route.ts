import { NextRequest, NextResponse } from 'next/server'
import { initSchema } from '@/lib/db'
import { startStreaming, stopStreaming } from '@/lib/stream-registry'

export const dynamic = 'force-dynamic'

const _ready = initSchema()

/**
 * POST /api/device/stream-mode
 * { deviceId, action: 'start'|'stop', cmd?: string, targetFps?: number }
 *
 * targetFps = 0 / undefined → Max speed
 * targetFps = 30 → ~30fps (33ms delay)
 * targetFps = 15 → ~15fps (67ms delay)
 * targetFps = 10 → ~10fps (100ms delay)
 * targetFps = 5  → ~5fps  (200ms delay)
 *
 * OPTIMIZED: First command set via in-memory flag (startStreaming sets pending=true).
 * No DB write needed — Android picks it up on next poll via popStreamCommand().
 */
export async function POST(req: NextRequest) {
  try {
    await _ready
    const { deviceId, action, cmd, targetFps } = await req.json()

    if (!deviceId || typeof deviceId !== 'string')
      return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    if (action !== 'start' && action !== 'stop')
      return NextResponse.json({ error: 'action must be start|stop' }, { status: 400 })

    if (action === 'stop') {
      stopStreaming(deviceId)
      return NextResponse.json({ ok: true, streaming: false })
    }

    // action === 'start'
    const command = typeof cmd === 'string' && cmd.startsWith('screenshot:') ? cmd : 'screenshot:480:55'
    const fps     = typeof targetFps === 'number' && targetFps > 0 ? targetFps : 0
    const delayMs = fps > 0 ? Math.round(1000 / fps) : 0

    // startStreaming sets pending=true so Android picks up on next poll (no DB needed)
    startStreaming(deviceId, command, delayMs)

    return NextResponse.json({ ok: true, streaming: true, cmd: command, delayMs })
  } catch (e) {
    console.error('stream-mode error:', e)
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}
