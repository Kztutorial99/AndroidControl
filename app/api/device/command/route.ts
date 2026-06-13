import { NextRequest, NextResponse } from 'next/server'
import { devicesStore, enqueueCommand } from '@/lib/store'

export async function POST(req: NextRequest) {
  try {
    const body = await req.json()
    const { deviceId, command } = body

    if (!deviceId || typeof deviceId !== 'string') {
      return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    }
    if (!command || typeof command !== 'string') {
      return NextResponse.json({ error: 'command required' }, { status: 400 })
    }
    if (!devicesStore.has(deviceId)) {
      return NextResponse.json({ error: 'Device not found' }, { status: 404 })
    }

    const pending = enqueueCommand(deviceId, command.trim())
    return NextResponse.json({ ok: true, commandId: pending.id, command: pending.command })
  } catch {
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}

export async function GET(req: NextRequest) {
  const deviceId = req.nextUrl.searchParams.get('deviceId')
  if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
  const device = devicesStore.get(deviceId)
  if (!device) return NextResponse.json({ pending: [], count: 0 })
  return NextResponse.json({ pending: device.pendingCommands, count: device.pendingCommands.length })
}
