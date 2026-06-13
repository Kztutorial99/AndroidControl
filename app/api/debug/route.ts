import { NextResponse } from 'next/server'
import { neon } from '@neondatabase/serverless'

export const dynamic = 'force-dynamic'

export async function GET() {
  const neonUrl = process.env.NEON_DATABASE_URL
  const dbUrl = process.env.DATABASE_URL

  const usedUrl = neonUrl || dbUrl || ''
  const masked = usedUrl ? usedUrl.replace(/:([^@]+)@/, ':***@').slice(0, 80) : 'NONE'

  const results: Record<string, unknown> = {
    env: {
      NEON_DATABASE_URL: neonUrl ? 'SET (' + neonUrl.replace(/:([^@]+)@/, ':***@').slice(0, 60) + ')' : 'NOT SET',
      DATABASE_URL: dbUrl ? 'SET (' + dbUrl.replace(/:([^@]+)@/, ':***@').slice(0, 60) + ')' : 'NOT SET',
      using: masked,
    }
  }

  try {
    const sql = neon(usedUrl)
    // Try a write
    await sql`CREATE TABLE IF NOT EXISTS _debug_test (id TEXT PRIMARY KEY, ts TIMESTAMPTZ DEFAULT NOW())`
    await sql`INSERT INTO _debug_test (id) VALUES ('probe-' || extract(epoch from now())::text) ON CONFLICT DO NOTHING`
    const rows = await sql`SELECT * FROM _debug_test ORDER BY ts DESC LIMIT 3`
    results.write_test = { ok: true, rows }

    // Check devices table
    const devs = await sql`SELECT device_id, device_name, last_seen FROM devices ORDER BY last_seen DESC NULLS LAST LIMIT 10`
    results.devices = devs
  } catch (e: unknown) {
    results.error = e instanceof Error ? e.message : String(e)
  }

  return NextResponse.json(results)
}
