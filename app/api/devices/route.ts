import { NextResponse } from 'next/server'
import { getAllDevices, isDeviceOnline } from '@/lib/store'
import { initSchema } from '@/lib/db'

let schemaInit = false
async function ensureSchema() {
  if (!schemaInit) { await initSchema(); schemaInit = true }
}

export async function GET() {
  try {
    await ensureSchema()
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
