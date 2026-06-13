import { NextRequest, NextResponse } from 'next/server'
import { updateDeviceHeartbeat, getAllDevices, isDeviceOnline, getOrCreateDevice } from '@/lib/store'
import { initSchema } from '@/lib/db'

export const dynamic = 'force-dynamic'

const _ready = initSchema()

export async function POST(req: NextRequest) {
  try {
    await _ready
    const body = await req.json()
    const { deviceId, deviceName, device } = body

    if (!deviceId || typeof deviceId !== 'string') {
      return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    }

    const name = deviceName || 'Unknown Device'
    const stats = device ? {
      // Core
      battery:        device.battery        ?? '--',
      batteryStatus:  device.batteryStatus  ?? 'unknown',
      model:          device.model          ?? 'Unknown Device',
      androidVersion: device.androidVersion ?? '--',
      ip:             device.ip             ?? '--',
      storage:        device.storage        ?? '--',
      storageFree:    device.storageFree    ?? '--',
      networkType:    device.networkType    ?? '--',
      cpuUsage:       device.cpuUsage       ?? '--',
      memTotal:       device.memTotal       ?? '--',
      memFree:        device.memFree        ?? '--',
      uptime:         device.uptime         ?? '--',
      hostname:       device.hostname       ?? '--',
      kernel:         device.kernel         ?? '--',
      screenState:    device.screenState    ?? '--',
      // Identitas perangkat
      brand:          device.brand          ?? '--',
      device:         device.device         ?? '--',
      product:        device.product        ?? '--',
      fingerprint:    device.fingerprint    ?? '--',
      // SIM & Telepon
      imei:               device.imei               ?? '--',
      phoneNumber:        device.phoneNumber         ?? '--',
      simOperator:        device.simOperator         ?? '--',
      simCountry:         device.simCountry          ?? '--',
      simSerial:          device.simSerial           ?? '--',
      simSlots:           device.simSlots            ?? '--',
      simState:           device.simState            ?? '--',
      networkOperator:    device.networkOperator     ?? '--',
      networkGeneration:  device.networkGeneration   ?? '--',
      roaming:            device.roaming             ?? '--',
      mccMnc:             device.mccMnc              ?? '--',
      // Dual SIM array
      sims:               Array.isArray(device.sims) ? device.sims : [],
    } : null

    if (stats) {
      await updateDeviceHeartbeat(deviceId, name, stats)
    } else {
      await getOrCreateDevice(deviceId, name)
    }

    return NextResponse.json({ ok: true, serverTime: new Date().toISOString() })
  } catch (e) {
    console.error('heartbeat POST error:', e)
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}

export async function GET() {
  try {
    await _ready
    const devices = await getAllDevices()
    return NextResponse.json({
      devices: devices.map(d => ({ ...d, connected: isDeviceOnline(d) }))
    })
  } catch (e) {
    console.error('heartbeat GET error:', e)
    return NextResponse.json({ devices: [] })
  }
}
