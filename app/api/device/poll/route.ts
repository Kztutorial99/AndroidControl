import { NextRequest, NextResponse } from 'next/server'
import { getOrCreateDevice, popCommand } from '@/lib/store'
import { initSchema } from '@/lib/db'

export const dynamic = 'force-dynamic'

const _ready = initSchema()

export async function GET(req: NextRequest) {
  try {
    await _ready
    const deviceId = req.nextUrl.searchParams.get('deviceId')
    if (!deviceId) {
      return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    }
    await getOrCreateDevice(deviceId)
    const pending = await popCommand(deviceId)
    return NextResponse.json({
      command:   pending?.command ?? null,
      commandId: pending?.id ?? null,
      extra:     pending?.extra ?? null,
      serverTime: new Date().toISOString(),
    })
  } catch (e) {
    console.error('poll error:', e)
    return NextResponse.json({ command: null, commandId: null, extra: null, serverTime: new Date().toISOString() })
  }
}
