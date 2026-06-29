import { NextRequest } from 'next/server'
import { getDevice, isDeviceOnline } from '@/lib/store'
import { initSchema } from '@/lib/db'
import { subscribeDevice } from '@/lib/sse'

export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

const _ready = initSchema()

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

      const unsubscribe = subscribeDevice(deviceId, () => push())

      const keepalive = setInterval(() => {
        try {
          controller.enqueue(encoder.encode(': ping\n\n'))
        } catch {
          clearInterval(keepalive)
        }
      }, 20000)

      req.signal.addEventListener('abort', () => {
        clearInterval(keepalive)
        unsubscribe()
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
