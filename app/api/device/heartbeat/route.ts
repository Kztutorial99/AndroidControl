import { NextRequest, NextResponse } from 'next/server'
import { getOrCreateDevice, isDeviceOnline, getAllDevices } from '@/lib/store'

export async function POST(req: NextRequest) {
  try {
    const body = await req.json()
    const { deviceId, deviceName, device } = body

    if (!deviceId || typeof deviceId !== 'string') {
      return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    }

    const entry = getOrCreateDevice(deviceId, deviceName)
    entry.lastSeen = new Date().toISOString()
    entry.connected = true

    if (device) {
      entry.stats = {
        battery: device.battery ?? entry.stats.battery,
        batteryStatus: device.batteryStatus ?? entry.stats.batteryStatus,
        model: device.model ?? entry.stats.model,
        androidVersion: device.androidVersion ?? entry.stats.androidVersion,
        ip: device.ip ?? entry.stats.ip,
        storage: device.storage ?? entry.stats.storage,
        storageFree: device.storageFree ?? entry.stats.storageFree,
        networkType: device.networkType ?? entry.stats.networkType,
        cpuUsage: device.cpuUsage ?? entry.stats.cpuUsage,
        memTotal: device.memTotal ?? entry.stats.memTotal,
        memFree: device.memFree ?? entry.stats.memFree,
        uptime: device.uptime ?? entry.stats.uptime,
        hostname: device.hostname ?? entry.stats.hostname,
        kernel: device.kernel ?? entry.stats.kernel,
        screenState: device.screenState ?? entry.stats.screenState,
      }
    }

    return NextResponse.json({ ok: true, serverTime: new Date().toISOString() })
  } catch {
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}

export async function GET() {
  const devices = getAllDevices()
  devices.forEach(d => { d.connected = isDeviceOnline(d) })
  return NextResponse.json({ devices })
}
