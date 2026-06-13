import { NextRequest, NextResponse } from 'next/server'
import pool, { initSchema } from '@/lib/db'

export const dynamic = 'force-dynamic'

export async function GET() {
  try {
    await initSchema()
    const { rows } = await pool.query(
      `SELECT device_id, device_name, last_seen FROM devices ORDER BY last_seen DESC NULLS LAST`
    )
    return NextResponse.json({ ok: true, count: rows.length, devices: rows })
  } catch (e: unknown) {
    return NextResponse.json({ ok: false, error: e instanceof Error ? e.message : String(e) })
  }
}

export async function POST(req: NextRequest) {
  try {
    await initSchema()
    const body = await req.json().catch(() => ({}))
    if (body.action === 'cleanup') {
      await pool.query(
        `DELETE FROM devices WHERE device_id IN (
          'test-device-123','test-apk-001','test-diag-001',
          'debug-1781336618','25e5275e-9c57-4364-9f32-05688f5529c2',
          '683c801e-b52d-4894-a792-bad0d9172224'
        )`
      )
      const { rows } = await pool.query(`SELECT device_id, device_name, last_seen FROM devices ORDER BY last_seen DESC NULLS LAST`)
      return NextResponse.json({ ok: true, message: 'Cleaned up test devices', remaining: rows })
    }
    return NextResponse.json({ ok: false, error: 'Unknown action' })
  } catch (e: unknown) {
    return NextResponse.json({ ok: false, error: e instanceof Error ? e.message : String(e) })
  }
}
