import { NextResponse } from 'next/server'
import { initSchema } from '@/lib/db'
import pool from '@/lib/db'
import { deleteOldDevices } from '@/lib/store'

export const dynamic = 'force-dynamic'

const _ready = initSchema()

export async function GET() {
  try {
    await _ready
    const { deletedCount, devices } = await deleteOldDevices(7)
    console.log(`[cron/cleanup] Deleted ${deletedCount} devices offline > 7 hari`)
    return NextResponse.json({
      ok: true,
      deleted: deletedCount,
      devices,
      timestamp: new Date().toISOString(),
    })
  } catch (e) {
    console.error('[cron/cleanup] Error:', e)
    return NextResponse.json({ error: 'Cleanup failed' }, { status: 500 })
  }
}

