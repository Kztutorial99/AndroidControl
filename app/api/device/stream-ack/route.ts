import { NextRequest, NextResponse } from 'next/server'
import { setStreamPending, isStreaming } from '@/lib/stream-registry'

export const dynamic = 'force-dynamic'

/**
 * POST /api/device/stream-ack
 * { deviceId }
 *
 * Called by the browser after it has successfully rendered a frame.
 * Signals stream-registry to wake Android for the next capture.
 *
 * This implements true backpressure:
 * Android only captures when browser is ready — zero queue buildup
 * regardless of network speed or device performance.
 */
export async function POST(req: NextRequest) {
  try {
    const { deviceId } = await req.json()
    if (!deviceId || typeof deviceId !== 'string') {
      return NextResponse.json({ error: 'deviceId required' }, { status: 400 })
    }
    if (isStreaming(deviceId)) {
      setStreamPending(deviceId)
    }
    return NextResponse.json({ ok: true })
  } catch (e) {
    console.error('stream-ack error:', e)
    return NextResponse.json({ error: 'Bad request' }, { status: 400 })
  }
}
