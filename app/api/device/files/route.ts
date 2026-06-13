import { NextRequest, NextResponse } from 'next/server'
import { getDevice, enqueueCommand, getFileListing } from '@/lib/store'
import { initSchema } from '@/lib/db'

let schemaInit = false
async function ensureSchema() {
  if (!schemaInit) { await initSchema(); schemaInit = true }
}

export async function GET(req: NextRequest) {
  try {
    await ensureSchema()
    const deviceId = req.nextUrl.searchParams.get('deviceId')
    if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    const listing = await getFileListing(deviceId)
    return NextResponse.json({ listing })
  } catch (e) {
    console.error('files GET error:', e)
    return NextResponse.json({ listing: null })
  }
}

export async function POST(req: NextRequest) {
  try {
    await ensureSchema()
    const body = await req.json()
    const { deviceId, path } = body

    if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })

    const device = await getDevice(deviceId)
    if (!device) return NextResponse.json({ error: 'Device not found' }, { status: 404 })

    const safePath = path?.replace(/[`$]/g, '') ?? '/storage/emulated/0'
    const pending = await enqueueCommand(deviceId, `ls_json:${safePath}`)
    return NextResponse.json({ ok: true, commandId: pending.id, path: safePath })
  } catch (e) {
    console.error('files POST error:', e)
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}
