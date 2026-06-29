import { NextRequest, NextResponse } from 'next/server'
import { initSchema } from '@/lib/db'
import { startStreaming, stopStreaming } from '@/lib/stream-registry'

export const dynamic = 'force-dynamic'

const _ready = initSchema()

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

    const command = typeof cmd === 'string' && cmd.startsWith('screenshot:') ? cmd : 'screenshot:480:55'
    const fps = typeof targetFps === 'number' ? targetFps : 0

    // fps = -1  → ACK mode: server waits for browser ACK before signalling Android
    // fps = 0   → max speed (no delay)
    // fps > 0   → fixed fps (delayMs = 1000/fps)
    let delayMs: number
    if (fps < 0)      delayMs = -1
    else if (fps > 0) delayMs = Math.round(1000 / fps)
    else              delayMs = 0

    startStreaming(deviceId, command, delayMs)

    return NextResponse.json({ ok: true, streaming: true, cmd: command, delayMs })
  } catch (e) {
    console.error('stream-mode error:', e)
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}
