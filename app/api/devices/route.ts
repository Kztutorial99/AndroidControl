import { NextResponse } from 'next/server'
import { getAllDevices, isDeviceOnline } from '@/lib/store'

export async function GET() {
  const devices = getAllDevices()
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
}
