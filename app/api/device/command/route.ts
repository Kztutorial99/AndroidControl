import { NextRequest, NextResponse } from 'next/server'
import { getDevice, enqueueCommand, getPendingCommands } from '@/lib/store'
import { initSchema } from '@/lib/db'

let schemaInit = false
async function ensureSchema() {
  if (!schemaInit) { await initSchema(); schemaInit = true }
}

export async function POST(req: NextRequest) {
  try {
    await ensureSchema()
    const body = await req.json()
    const { deviceId, command } = body

    if (!deviceId || typeof deviceId !== 'string') {
      return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    }
    if (!command || typeof command !== 'string') {
      return NextResponse.json({ error: 'command required' }, { status: 400 })
    }

    const device = await getDevice(deviceId)
    if (!device) return NextResponse.json({ error: 'Device not found' }, { status: 404 })

    const pending = await enqueueCommand(deviceId, command.trim())
    return NextResponse.json({ ok: true, commandId: pending.id, command: pending.command })
  } catch (e) {
    console.error('command POST error:', e)
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}

export async function GET(req: NextRequest) {
  try {
    await ensureSchema()
    const deviceId = req.nextUrl.searchParams.get('deviceId')
    if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    const pending = await getPendingCommands(deviceId)
    return NextResponse.json({ pending, count: pending.length })
  } catch (e) {
    console.error('command GET error:', e)
    return NextResponse.json({ pending: [], count: 0 })
  }
}
