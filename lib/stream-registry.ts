/**
 * Stream Registry — in-memory registry untuk Android Push Streaming mode.
 *
 * OPTIMIZED: Screenshot commands tidak pernah lewat DB.
 * In-memory pending flag digunakan agar poll/result hot path zero-DB.
 *
 * Flow:
 * 1. Browser POST /api/device/stream-mode → startStreaming() + setStreamPending()
 * 2. Android GET /api/device/poll → popStreamCommand() (in-memory, no DB)
 * 3. Android capture → POST /api/device/result
 * 4. Server broadcast SSE → setTimeout(delayMs) → setStreamPending()
 * 5. Ulangi dari #2
 *
 * delayMs = 0    → Max speed
 * delayMs = 33   → ~30fps cap
 * delayMs = 67   → ~15fps cap
 * delayMs = 100  → ~10fps cap
 * delayMs = 200  → ~5fps cap
 */

interface StreamInfo {
  cmd:       string
  delayMs:   number
  startedAt: number
  pending:   boolean   // in-memory "command ready" flag — replaces DB enqueue
}

const registry = new Map<string, StreamInfo>()

export function startStreaming(deviceId: string, cmd: string, delayMs = 0): void {
  registry.set(deviceId, {
    cmd,
    delayMs: Math.max(0, delayMs),
    startedAt: Date.now(),
    pending: true,   // command pertama langsung siap
  })
}

export function stopStreaming(deviceId: string): void {
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
 * Atomically take the pending command.
 * Returns command string jika ada pending command, null jika belum siap.
 * Android harus poll lagi kalau null.
 */
export function popStreamCommand(deviceId: string): string | null {
  const info = registry.get(deviceId)
  if (!info || !info.pending) return null
  info.pending = false
  return info.cmd
}

/**
 * Tandai bahwa command berikutnya siap diambil Android.
 * Dipanggil setelah delayMs dari result handler.
 */
export function setStreamPending(deviceId: string): void {
  const info = registry.get(deviceId)
  if (info) info.pending = true
}
