import { NextRequest, NextResponse } from 'next/server'
import { initSchema } from '@/lib/db'
import { saveNotification, getNotifications, getSmsNotifications, clearNotifications, getOrCreateDevice } from '@/lib/store'

export async function POST(req: NextRequest) {
  try {
    await initSchema()
    const body = await req.json()
    const { deviceId, appPackage, appName, title, text } = body
    if (!deviceId || (!title && !text)) {
      return NextResponse.json({ error: 'Missing fields' }, { status: 400 })
    }
    await getOrCreateDevice(deviceId)
    await saveNotification(deviceId, {
      appPackage: appPackage ?? '',
      appName:    appName    ?? '',
      title:      title      ?? '',
      text:       text       ?? '',
    })
    return NextResponse.json({ ok: true })
  } catch (e) {
    return NextResponse.json({ error: String(e) }, { status: 500 })
  }
}

export async function GET(req: NextRequest) {
  try {
    await initSchema()
    const deviceId   = req.nextUrl.searchParams.get('deviceId')
    const limit      = parseInt(req.nextUrl.searchParams.get('limit') ?? '200')
    const type       = req.nextUrl.searchParams.get('type')       // 'sms' | null
    const appPackage = req.nextUrl.searchParams.get('app') ?? undefined
    if (!deviceId) return NextResponse.json({ error: 'Missing deviceId' }, { status: 400 })
    const entries = type === 'sms'
      ? await getSmsNotifications(deviceId, limit)
      : await getNotifications(deviceId, limit, appPackage)
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
    await clearNotifications(deviceId)
    return NextResponse.json({ ok: true })
  } catch (e) {
    return NextResponse.json({ error: String(e) }, { status: 500 })
  }
}
