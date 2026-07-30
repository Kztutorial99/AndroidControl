import { NextRequest, NextResponse } from 'next/server'
import { initSchema } from '@/lib/db'
import { getDeviceSettings, setDeviceSettings, enqueueCommand, getOrCreateDevice } from '@/lib/store'

export const dynamic = 'force-dynamic'

const _ready = initSchema()

export async function GET(req: NextRequest) {
  try {
    await _ready
    const { searchParams } = new URL(req.url)
    const deviceId = searchParams.get('deviceId')
    if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    const settings = await getDeviceSettings(deviceId)
    return NextResponse.json({ settings })
  } catch (e) {
    console.error('settings GET error:', e)
    return NextResponse.json({ error: 'Internal error' }, { status: 500 })
  }
}

export async function POST(req: NextRequest) {
  try {
    await _ready
    const body = await req.json()
    const { deviceId, antiUninstall } = body

    if (!deviceId || typeof deviceId !== 'string') {
      return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    }

    // Pastikan device ada di DB dulu
    await getOrCreateDevice(deviceId)

    // Simpan setting ke database
    await setDeviceSettings(deviceId, { antiUninstall })

    // Kirim command ke device secara realtime via command queue
    if (typeof antiUninstall === 'boolean') {
      await enqueueCommand(deviceId, `anti_uninstall:${antiUninstall}`)
    }

    return NextResponse.json({ ok: true })
  } catch (e) {
    console.error('settings POST error:', e)
    return NextResponse.json({ error: 'Internal error' }, { status: 500 })
  }
}
