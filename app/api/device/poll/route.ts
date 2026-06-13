import { NextRequest, NextResponse } from 'next/server'
import { getOrCreateDevice, popCommand } from '@/lib/store'

export async function GET(req: NextRequest) {
  const deviceId = req.nextUrl.searchParams.get('deviceId')

  if (!deviceId) {
    return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
  }

  const entry = getOrCreateDevice(deviceId)
  entry.lastSeen = new Date().toISOString()
  entry.connected = true

  const pending = popCommand(deviceId)

  return NextResponse.json({
    command: pending?.command ?? null,
    commandId: pending?.id ?? null,
    serverTime: new Date().toISOString(),
  })
}
