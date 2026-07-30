import { NextRequest, NextResponse } from 'next/server'
import { initSchema } from '@/lib/db'
import { getPortalSessions, clearPortalSessions } from '@/lib/store'

export const dynamic = 'force-dynamic'
const _ready = initSchema()

export async function GET(req: NextRequest) {
  try {
    await _ready
    const deviceId = req.nextUrl.searchParams.get('deviceId')
    if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    const sessions = await getPortalSessions(deviceId)
    return NextResponse.json({ sessions })
  } catch (e) {
    return NextResponse.json({ error: 'Server error' }, { status: 500 })
  }
}

export async function DELETE(req: NextRequest) {
  try {
    await _ready
    const deviceId = req.nextUrl.searchParams.get('deviceId')
    if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    await clearPortalSessions(deviceId)
    return NextResponse.json({ ok: true })
  } catch (e) {
    return NextResponse.json({ error: 'Server error' }, { status: 500 })
  }
}