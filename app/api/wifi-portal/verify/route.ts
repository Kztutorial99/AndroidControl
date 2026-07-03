import { NextRequest, NextResponse } from 'next/server'
import { initSchema } from '@/lib/db'
import { getPortalConfig, logPortalSession } from '@/lib/store'

export const dynamic = 'force-dynamic'
const _ready = initSchema()

export async function POST(req: NextRequest) {
  try {
    await _ready
    const { deviceId, password, clientIp } = await req.json()
    if (!deviceId || !password) {
      return NextResponse.json({ error: 'Missing fields' }, { status: 400 })
    }
    const config = await getPortalConfig(deviceId)
    if (!config.enabled) {
      return NextResponse.json({ error: 'Portal tidak aktif' }, { status: 403 })
    }
    if (!config.password || config.password !== password) {
      return NextResponse.json({ error: 'Password salah' }, { status: 401 })
    }
    const ip = clientIp ||
      req.headers.get('x-forwarded-for')?.split(',')[0].trim() ||
      req.headers.get('x-real-ip') ||
      'unknown'
    await logPortalSession(deviceId, ip)
    return NextResponse.json({ ok: true })
  } catch (e) {
    console.error('wifi-portal/verify:', e)
    return NextResponse.json({ error: 'Server error' }, { status: 500 })
  }
}