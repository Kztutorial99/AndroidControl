import { NextRequest, NextResponse } from 'next/server'
import { initSchema } from '@/lib/db'
import pool from '@/lib/db'

export const dynamic = 'force-dynamic'
const _ready = initSchema()

export async function POST(req: NextRequest) {
  try {
    await _ready
    const deviceId = req.nextUrl.searchParams.get('deviceId')
    if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    const { active, port, ip } = await req.json()
    await pool.query(
      `UPDATE wifi_portal_config
       SET portal_active=$1, portal_ip=$2, portal_port=$3, updated_at=NOW()
       WHERE device_id=$4`,
      [!!active, ip ?? '', port ?? 0, deviceId]
    )
    return NextResponse.json({ ok: true })
  } catch (e) {
    console.error('wifi-portal/status:', e)
    return NextResponse.json({ error: 'Server error' }, { status: 500 })
  }
}