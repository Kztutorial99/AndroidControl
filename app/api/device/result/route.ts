import { NextRequest, NextResponse } from 'next/server'
import { addResult, getCommandHistory, setFileListing, getDevice } from '@/lib/store'
import { initSchema } from '@/lib/db'
import { notifyDeviceUpdate, broadcastFrame } from '@/lib/sse'
import { isStreaming, getStreamDelay, setStreamPending } from '@/lib/stream-registry'
import { v4 as uuidv4 } from 'uuid'

export const dynamic = 'force-dynamic'

const _ready = initSchema()

export async function POST(req: NextRequest) {
  try {
    await _ready
    const body = await req.json()
    const { deviceId, commandId, command, result, exitCode, type, data } = body

    if (!deviceId || typeof deviceId !== 'string') {
      return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    }

    if (
      typeof command === 'string' && command.startsWith('screenshot:') &&
      typeof result  === 'string' && result.length > 0 && !result.startsWith('ERROR')
    ) {
      if (isStreaming(deviceId)) {
        const delayMs = getStreamDelay(deviceId)
        if (delayMs <= 0) {
          setStreamPending(deviceId)
          broadcastFrame(deviceId, result.trim())
        } else {
          broadcastFrame(deviceId, result.trim())
          setTimeout(() => setStreamPending(deviceId), delayMs)
        }
      } else {
        broadcastFrame(deviceId, result.trim())
      }
      return NextResponse.json({ ok: true })
    }

    const device = await getDevice(deviceId)
    if (!device) return NextResponse.json({ error: 'Device not found' }, { status: 404 })

    if (type === 'file_listing') {
      await setFileListing(deviceId, data?.path ?? '/', data?.entries ?? [])
      return NextResponse.json({ ok: true })
    }

    await addResult(deviceId, {
      id: commandId ?? uuidv4(),
      command: command ?? '',
      result: result ?? '',
      timestamp: new Date().toISOString(),
      exitCode: exitCode ?? 0,
    })

    notifyDeviceUpdate(deviceId)
    return NextResponse.json({ ok: true })
  } catch (e) {
    console.error('result POST error:', e)
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}

export async function GET(req: NextRequest) {
  try {
    await _ready
    const deviceId = req.nextUrl.searchParams.get('deviceId')
    if (!deviceId) return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    const history = await getCommandHistory(deviceId)
    return NextResponse.json({ history })
  } catch (e) {
    console.error('result GET error:', e)
    return NextResponse.json({ history: [] })
  }
}
