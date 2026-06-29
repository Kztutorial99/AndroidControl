import { NextRequest } from 'next/server'
import { getDevice, isDeviceOnline } from '@/lib/store'
import { initSchema } from '@/lib/db'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

const _ready = initSchema()

const clients = new Map<string, Set<(data: string) => void>>()

export function notifyDeviceUpdate(deviceId: string) {
  const subs = clients.get(deviceId)
  if (subs && subs.size > 0) {
    subs.forEach(fn => fn(deviceId))
  }
}

export async function GET(req: NextRequest) {
  await _ready
  const { searchParams } = new URL(req.url)
  const deviceId = searchParams.get('deviceId')
  if (!deviceId) {
    return new Response('deviceId required', { status: 400 })
  }

  const encoder = new TextEncoder()

  const stream = new ReadableStream({
    start(controller) {
      const send = (data: object) => {
        try {
          controller.enqueue(encoder.encode(`data: ${JSON.stringify(data)}\n\n`))
        } catch {}
      }

      send({ type: 'connected', deviceId })

      const push = async () => {
        try {
          const device = await getDevice(deviceId)
          if (device) {
            send({
              type: 'heartbeat',
              device: { ...device, connected: isDeviceOnline(device) },
            })
          }
        } catch {}
      }

      push()

      const notify = () => push()
      if (!clients.has(deviceId)) clients.set(deviceId, new Set())
      clients.get(deviceId)!.add(notify)

      const keepalive = setInterval(() => {
        try {
          controller.enqueue(encoder.encode(': ping\n\n'))
        } catch {
          clearInterval(keepalive)
        }
      }, 20000)

      req.signal.addEventListener('abort', () => {
        clearInterval(keepalive)
        clients.get(deviceId)?.delete(notify)
        try { controller.close() } catch {}
      })
    },
  })

  return new Response(stream, {
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      'Connection': 'keep-alive',
      'X-Accel-Buffering': 'no',
    },
  })
}
