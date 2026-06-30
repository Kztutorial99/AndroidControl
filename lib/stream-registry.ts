/**
 * Stream Registry — in-memory registry untuk Android Push Streaming.
 *
 * EXTREME OPTIMIZATION:
 * - Zero DB per frame (in-memory pending flag)
 * - Long-polling: server holds connection until command ready → eliminasi
 *   skenario Android poll→null→sleep(500ms)→poll lagi
 * - Pre-pipeline: pending di-set SEBELUM SSE broadcast sehingga Android
 *   bisa langsung ambil command sementara browser masih render frame
 *
 * Flow optimal:
 * 1. Browser POST stream-mode → startStreaming() (pending=true)
 * 2. Android GET /poll → waitForStreamCommand() → dapat command INSTANT
 * 3. Android capture → POST result
 * 4. Server: setStreamPending() [BEFORE SSE] → wakes waiter immediately
 * 5. Android GET /poll (sudah dari step 3) → dapat command INSTANT
 * 6. Parallel: SSE broadcast frame ke browser
 * 7. Ulangi — tanpa sleep, tanpa DB, tanpa null round-trip
 */

interface StreamInfo {
  cmd:       string
  delayMs:   number
  startedAt: number
  pending:   boolean
}

interface PollWaiter {
  resolve: (cmd: string | null) => void
  timer:   ReturnType<typeof setTimeout>
}

const registry    = new Map<string, StreamInfo>()
const pollWaiters = new Map<string, PollWaiter>()

export function startStreaming(deviceId: string, cmd: string, delayMs = 0): void {
  // Bersihkan waiter lama jika ada (reconnect scenario)
  const old = pollWaiters.get(deviceId)
  if (old) { clearTimeout(old.timer); pollWaiters.delete(deviceId); old.resolve(null) }

  registry.set(deviceId, {
    cmd,
    delayMs,   // preserve -1 for ACK mode (do NOT clamp — Math.max would break ACK)
    startedAt: Date.now(),
    pending: true,   // command pertama langsung siap
  })
}

export function stopStreaming(deviceId: string): void {
  const waiter = pollWaiters.get(deviceId)
  if (waiter) { clearTimeout(waiter.timer); pollWaiters.delete(deviceId); waiter.resolve(null) }
  registry.delete(deviceId)
}

export function isStreaming(deviceId: string): boolean {
  return registry.has(deviceId)
}

export function getStreamCmd(deviceId: string): string | null {
  return registry.get(deviceId)?.cmd ?? null
}

export function getStreamDelay(deviceId: string): number {
  return registry.get(deviceId)?.delayMs ?? 0
}

/**
 * Long-poll: blocks until command is ready or timeout.
 * Eliminates Android sleep(500ms) + re-poll cycle when command not yet ready.
 * timeoutMs: how long to wait before returning null (Android will retry).
 */
export function waitForStreamCommand(deviceId: string, timeoutMs = 12000): Promise<string | null> {
  return new Promise(resolve => {
    const info = registry.get(deviceId)
    if (!info) { resolve(null); return }

    // Already pending — serve instantly
    if (info.pending) {
      info.pending = false
      resolve(info.cmd)
      return
    }

    // Cancel existing waiter (e.g. two concurrent polls from same device)
    const existing = pollWaiters.get(deviceId)
    if (existing) {
      clearTimeout(existing.timer)
      existing.resolve(null)
    }

    // Register waiter — woken up by setStreamPending()
    const timer = setTimeout(() => {
      pollWaiters.delete(deviceId)
      resolve(null)   // timeout: Android akan retry
    }, timeoutMs)

    pollWaiters.set(deviceId, { resolve, timer })
  })
}

/**
 * Set command as pending.
 * If there's a waiting poll request, wake it up IMMEDIATELY (long-poll path).
 * Otherwise set flag for next poll.
 *
 * CALL THIS BEFORE broadcastFrame() so Android can start next capture
 * while browser is still receiving the SSE frame (pre-pipeline).
 */
export function setStreamPending(deviceId: string): void {
  const info = registry.get(deviceId)
  if (!info) return

  const waiter = pollWaiters.get(deviceId)
  if (waiter) {
    // Android is already waiting → wake it up immediately (0ms extra latency)
    clearTimeout(waiter.timer)
    pollWaiters.delete(deviceId)
    waiter.resolve(info.cmd)
  } else {
    // Android hasn't polled yet → set flag
    info.pending = true
  }
}
