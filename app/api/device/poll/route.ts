import { NextRequest, NextResponse } from 'next/server'
import { store, popPendingCommand } from '@/lib/store'

export async function GET(req: NextRequest) {
  const token = req.nextUrl.searchParams.get('token')

  if (token !== store.token) {
    return NextResponse.json({ error: 'Invalid token' }, { status: 401 })
  }

  store.device.lastSeen = new Date().toISOString()
  store.device.connected = true

  const pending = popPendingCommand()

  return NextResponse.json({
    command: pending?.command ?? null,
    commandId: pending?.id ?? null,
    serverTime: new Date().toISOString(),
  })
}
