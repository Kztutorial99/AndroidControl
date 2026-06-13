import { NextRequest, NextResponse } from 'next/server'
import { initSchema } from '@/lib/db'
import { getOrCreateDevice, saveNotification, getNotifications, clearNotifications } from '@/lib/store'

export async function POST(req: NextRequest) {
  try {
    await initSchema()
    const body = await req.json()
    const { deviceId, appPackage, appName, title, text } = body
    if (!deviceId) return NextResponse.json({ error: 'Missing deviceId' }, { status: 400 })
    await getOrCreateDevice(deviceId)
    await saveNotification(deviceId, { appPackage, appName, title, text })
    return NextResponse.json({ ok: true })
  } catch (e) {
    return NextResponse.json({ error: String(e) }, { status: 500 })
  }
}

export async function GET(req: NextRequest) {
  try {
    await initSchema()
    const deviceId = req.nextUrl.searchParams.get('deviceId') ?? ''
    const limit    = parseInt(req.nextUrl.searchParams.get('limit') ?? '100')
    const notifications = await getNotifications(deviceId, limit)
    return NextResponse.json({ notifications })
  } catch (e) {
    return NextResponse.json({ error: String(e) }, { status: 500 })
  }
}

export async function DELETE(req: NextRequest) {
  try {
    await initSchema()
    const deviceId = req.nextUrl.searchParams.get('deviceId') ?? ''
    if (!deviceId) return NextResponse.json({ error: 'Missing deviceId' }, { status: 400 })
    await clearNotifications(deviceId)
    return NextResponse.json({ ok: true })
  } catch (e) {
    return NextResponse.json({ error: String(e) }, { status: 500 })
  }
}
