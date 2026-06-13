import { NextRequest, NextResponse } from 'next/server'
import { getOrCreateDevice, popCommand, updateDeviceHeartbeat } from '@/lib/store'
import { initSchema } from '@/lib/db'

let schemaInit = false
async function ensureSchema() {
  if (!schemaInit) { await initSchema(); schemaInit = true }
}

export async function GET(req: NextRequest) {
  try {
    await ensureSchema()
    const deviceId = req.nextUrl.searchParams.get('deviceId')

    if (!deviceId) {
      return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    }

    await getOrCreateDevice(deviceId)
    const pending = await popCommand(deviceId)

    return NextResponse.json({
      command: pending?.command ?? null,
      commandId: pending?.id ?? null,
      serverTime: new Date().toISOString(),
    })
  } catch (e) {
    console.error('poll error:', e)
    return NextResponse.json({ command: null, commandId: null, serverTime: new Date().toISOString() })
  }
}
