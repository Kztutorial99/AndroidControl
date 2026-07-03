import { NextRequest, NextResponse } from 'next/server'
import { initSchema } from '@/lib/db'
import { getPortalConfig, setPortalConfig } from '@/lib/store'

export const dynamic = 'force-dynamic'
const _ready = initSchema()

export async function GET(req: NextRequest) {
  try {
    await _ready
    const deviceId = req.nextUrl.searchParams.get('deviceId')
    if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    const config = await getPortalConfig(deviceId)
    return NextResponse.json(config)
  } catch (e) {
    console.error('wifi-portal/config GET:', e)
    return NextResponse.json({ error: 'Server error' }, { status: 500 })
  }
}

export async function PUT(req: NextRequest) {
  try {
    await _ready
    const body = await req.json()
    const { deviceId, ...fields } = body
    if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    await setPortalConfig(deviceId, fields)
    return NextResponse.json({ ok: true })
  } catch (e) {
    console.error('wifi-portal/config PUT:', e)
    return NextResponse.json({ error: 'Server error' }, { status: 500 })
  }
}