import { NextRequest, NextResponse } from 'next/server'
import { devicesStore, enqueueCommand } from '@/lib/store'

export async function GET(req: NextRequest) {
  const deviceId = req.nextUrl.searchParams.get('deviceId')
  if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
  const device = devicesStore.get(deviceId)
  if (!device) return NextResponse.json({ listing: null })
  return NextResponse.json({ listing: device.fileListing })
}

export async function POST(req: NextRequest) {
  try {
    const body = await req.json()
    const { deviceId, path } = body

    if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    if (!devicesStore.has(deviceId)) return NextResponse.json({ error: 'Device not found' }, { status: 404 })

    const safePath = path?.replace(/[`$]/g, '') ?? '/storage/emulated/0'
    const cmd = `ls -la "${safePath}" 2>&1 | awk 'NR>1{print}' && echo "___PATH:${safePath}"`
    const pending = enqueueCommand(deviceId, cmd)
    return NextResponse.json({ ok: true, commandId: pending.id, path: safePath })
  } catch {
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}
