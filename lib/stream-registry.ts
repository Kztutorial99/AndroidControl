/**
 * Stream Registry — in-memory registry untuk Android Push Streaming mode.
 *
 * Cara kerja:
 * 1. Browser panggil POST /api/device/stream-mode { action:'start', deviceId, cmd }
 * 2. Server catat di sini + enqueue command pertama ke DB
 * 3. Android poll → dapat command → capture → POST result
 * 4. result/route.ts: terima frame → broadcast SSE → re-enqueue command berikutnya (server loop)
 * 5. Ulangi tanpa browser ikut kirim command
 *
 * Catatan Vercel: state ini per-instance (in-memory).
 * Selama satu sesi live berjalan, request masuk ke instance yang sama (warm).
 * Jika instance recycled, streaming berhenti — browser punya fallback watchdog.
 */

interface StreamInfo {
  cmd: string       // e.g. 'screenshot:480:55'
  startedAt: number
}

const registry = new Map<string, StreamInfo>()

export function startStreaming(deviceId: string, cmd: string): void {
  registry.set(deviceId, { cmd, startedAt: Date.now() })
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
