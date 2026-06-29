import { NextRequest, NextResponse } from 'next/server'
import { enqueueCommand } from '@/lib/store'
import { initSchema } from '@/lib/db'
import { startStreaming, stopStreaming } from '@/lib/stream-registry'

export const dynamic = 'force-dynamic'

const _ready = initSchema()

/**
 * POST /api/device/stream-mode
 * body: { deviceId, action: 'start'|'stop', cmd?: 'screenshot:480:55' }
 *
 * start → catat di registry + enqueue command pertama ke DB
 * stop  → hapus dari registry (server loop berhenti otomatis)
 */
export async function POST(req: NextRequest) {
  try {
    await _ready
    const { deviceId, action, cmd } = await req.json()

    if (!deviceId || typeof deviceId !== 'string') {
      return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    }
    if (action !== 'start' && action !== 'stop') {
      return NextResponse.json({ error: 'action must be start|stop' }, { status: 400 })
    }

    if (action === 'stop') {
      stopStreaming(deviceId)
      return NextResponse.json({ ok: true, streaming: false })
    }

    // action === 'start'
    const command = typeof cmd === 'string' && cmd.startsWith('screenshot:') ? cmd : 'screenshot:480:55'
    startStreaming(deviceId, command)

    // Enqueue command pertama — Android akan langsung ambil saat poll berikutnya
    await enqueueCommand(deviceId, command)

    return NextResponse.json({ ok: true, streaming: true, cmd: command })
  } catch (e) {
    console.error('stream-mode error:', e)
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}
