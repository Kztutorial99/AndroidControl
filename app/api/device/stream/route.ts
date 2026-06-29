import { NextRequest } from 'next/server'
import { getDevice, isDeviceOnline } from '@/lib/store'
import { initSchema } from '@/lib/db'
import { subscribeDevice, subscribeFrame } from '@/lib/sse'

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
      let closed = false

      const send = (data: object) => {
        if (closed) return
        try {
          controller.enqueue(encoder.encode(`data: ${JSON.stringify(data)}\n\n`))
        } catch { closed = true }
      }

      send({ type: 'connected', deviceId })

      const pushStatus = async () => {
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

      pushStatus()

      const unsubscribeDevice = subscribeDevice(deviceId, () => pushStatus())

      // Push frame langsung ke browser tanpa polling
      const unsubscribeFrame = subscribeFrame(deviceId, (b64: string) => {
        send({ type: 'frame', b64 })
      })

      const keepalive = setInterval(() => {
        if (closed) { clearInterval(keepalive); return }
        try {
          controller.enqueue(encoder.encode(': ping\n\n'))
        } catch {
          closed = true
          clearInterval(keepalive)
        }
      }, 20000)

      req.signal.addEventListener('abort', () => {
        closed = true
        clearInterval(keepalive)
        unsubscribeDevice()
        unsubscribeFrame()
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
