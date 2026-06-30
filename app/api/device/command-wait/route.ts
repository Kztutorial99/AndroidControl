import { NextRequest, NextResponse } from 'next/server'
import { getDevice, enqueueCommand } from '@/lib/store'
import { initSchema } from '@/lib/db'
import pool from '@/lib/db'

export const dynamic = 'force-dynamic'

const _ready = initSchema()

export async function POST(req: NextRequest) {
  try {
    await _ready
    const body = await req.json()
    const { deviceId, command, extra, timeoutMs = 8000 } = body

    if (!deviceId || typeof deviceId !== 'string') {
      return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    }
    if (!command || typeof command !== 'string') {
      return NextResponse.json({ error: 'command required' }, { status: 400 })
    }

    const device = await getDevice(deviceId)
    if (!device) return NextResponse.json({ error: 'Device not found' }, { status: 404 })

    const pending = await enqueueCommand(deviceId, command.trim(), extra ?? undefined)
    const commandId = pending.id

    const deadline = Date.now() + Math.min(timeoutMs, 15000)
    while (Date.now() < deadline) {
      await new Promise(r => setTimeout(r, 400))
      const { rows } = await pool.query(
        `SELECT result FROM command_history WHERE id = $1 LIMIT 1`,
        [commandId]
      )
      if (rows[0]) {
        return NextResponse.json({ ok: true, commandId, result: rows[0].result })
      }
    }

    return NextResponse.json({ ok: false, commandId, result: null, error: 'timeout' }, { status: 408 })
  } catch (e) {
    console.error('command-wait POST error:', e)
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}
