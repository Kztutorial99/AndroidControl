import { NextRequest, NextResponse } from 'next/server'
import { devicesStore, addResult } from '@/lib/store'
import { v4 as uuidv4 } from 'uuid'

export async function POST(req: NextRequest) {
  try {
    const body = await req.json()
    const { deviceId, commandId, command, result, exitCode, type, data } = body

    if (!deviceId || typeof deviceId !== 'string') {
      return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    }

    const device = devicesStore.get(deviceId)
    if (!device) return NextResponse.json({ error: 'Device not found' }, { status: 404 })

    if (type === 'file_listing') {
      device.fileListing = {
        path: data?.path ?? '/',
        entries: data?.entries ?? [],
      }
      return NextResponse.json({ ok: true })
    }

    addResult(deviceId, {
      id: commandId ?? uuidv4(),
      command: command ?? '',
      result: result ?? '',
      timestamp: new Date().toISOString(),
      exitCode: exitCode ?? 0,
    })

    return NextResponse.json({ ok: true })
  } catch {
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}

export async function GET(req: NextRequest) {
  const deviceId = req.nextUrl.searchParams.get('deviceId')
  if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
  const device = devicesStore.get(deviceId)
  if (!device) return NextResponse.json({ history: [] })
  return NextResponse.json({ history: device.commandHistory })
}
