import { NextRequest, NextResponse } from 'next/server'
import { store, isDeviceOnline } from '@/lib/store'

export async function POST(req: NextRequest) {
  try {
    const body = await req.json()
    const { token, device } = body

    if (token !== store.token) {
      return NextResponse.json({ error: 'Invalid token' }, { status: 401 })
    }

    store.device = {
      ...store.device,
      connected: true,
      lastSeen: new Date().toISOString(),
      battery: device?.battery ?? store.device.battery,
      batteryStatus: device?.batteryStatus ?? store.device.batteryStatus,
      model: device?.model ?? store.device.model,
      androidVersion: device?.androidVersion ?? store.device.androidVersion,
      ip: device?.ip ?? store.device.ip,
      storage: device?.storage ?? store.device.storage,
      storageFree: device?.storageFree ?? store.device.storageFree,
      networkType: device?.networkType ?? store.device.networkType,
      cpuUsage: device?.cpuUsage ?? store.device.cpuUsage,
      memTotal: device?.memTotal ?? store.device.memTotal,
      memFree: device?.memFree ?? store.device.memFree,
      uptime: device?.uptime ?? store.device.uptime,
      hostname: device?.hostname ?? store.device.hostname,
      kernel: device?.kernel ?? store.device.kernel,
      screenState: device?.screenState ?? store.device.screenState,
    }

    return NextResponse.json({ ok: true, serverTime: new Date().toISOString() })
  } catch {
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}

export async function GET(req: NextRequest) {
  const online = isDeviceOnline()
  if (!online && store.device.connected) {
    store.device.connected = false
  }
  return NextResponse.json({
    connected: online,
    device: store.device,
  })
}
