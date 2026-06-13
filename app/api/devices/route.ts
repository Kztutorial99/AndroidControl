import { NextRequest, NextResponse } from 'next/server'
import { getAllDevices, isDeviceOnline, deleteDevice } from '@/lib/store'
import { initSchema } from '@/lib/db'

export const dynamic = 'force-dynamic'

const _ready = initSchema()

export async function GET() {
  try {
    await _ready
    const devices = await getAllDevices()
    return NextResponse.json({
      devices: devices.map(d => ({
        deviceId: d.deviceId,
        deviceName: d.deviceName,
        connected: isDeviceOnline(d),
        lastSeen: d.lastSeen,
        model: d.stats.model,
        androidVersion: d.stats.androidVersion,
        battery: d.stats.battery,
        batteryStatus: d.stats.batteryStatus,
        ip: d.stats.ip,
      }))
    })
  } catch (e) {
    console.error('devices error:', e)
    return NextResponse.json({ devices: [] })
  }
}

export async function DELETE(req: NextRequest) {
  try {
    await _ready
    const { searchParams } = new URL(req.url)
    const deviceId = searchParams.get('deviceId')
    if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    await deleteDevice(deviceId)
    return NextResponse.json({ ok: true })
  } catch (e) {
    console.error('delete device error:', e)
    return NextResponse.json({ error: 'Failed' }, { status: 500 })
  }
}
