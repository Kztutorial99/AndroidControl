'use client'
import { Suspense, useState, useCallback, useRef, useEffect } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import {
  Monitor, Play, Square, Download, RefreshCw, Circle,
  Zap, Clock, Camera, AlertTriangle,
} from 'lucide-react'

function sleep(ms: number) { return new Promise(r => setTimeout(r, ms)) }

async function sendScreenshotCmd(
  deviceId: string,
  maxW: number,
  quality: number,
): Promise<{ b64: string; elapsed: number }> {
  const cmdName = `screenshot:${maxW}:${quality}`
  const sentAt = Date.now()
  await fetch('/api/device/command', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId, command: cmdName }),
  })

  // Poll — first check after 600ms (Android needs time to screencap + encode)
  await sleep(600)
  for (let i = 0; i < 40; i++) {
    if (i > 0) await sleep(400)
    try {
      const r = await fetch(`/api/device/result?deviceId=${deviceId}`)
      const d = await r.json()
      const match = (d.history ?? [])
        .filter((h: { command: string; result: string; timestamp: string }) =>
          h.command === cmdName &&
          new Date(h.timestamp).getTime() > sentAt - 300)
        .sort((a: { timestamp: string }, b: { timestamp: string }) =>
          new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())[0]
      if (match?.result) return { b64: match.result as string, elapsed: Date.now() - sentAt }
    } catch {}
  }
  return { b64: '', elapsed: Date.now() - sentAt }
}

const QUALITY_OPTIONS = [
  { label: 'Fast (480p, q60)', maxW: 480, quality: 60 },
  { label: 'Balanced (720p, q70)', maxW: 720, quality: 70 },
  { label: 'HD (1080p, q80)', maxW: 1080, quality: 80 },
]

const INTERVAL_OPTIONS = [
  { label: '1s', ms: 1000 },
  { label: '2s', ms: 2000 },
  { label: '3s', ms: 3000 },
  { label: '5s', ms: 5000 },
]

function ScreenshotContent() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()

  const [live, setLive]             = useState(false)
  const [frame, setFrame]           = useState<string>('')   // data URL
  const [frameCount, setFrameCount] = useState(0)
  const [lastTs, setLastTs]         = useState<Date | null>(null)
  const [lastElapsed, setLastElapsed] = useState<number>(0)
  const [capturing, setCapturing]   = useState(false)
  const [error, setError]           = useState('')
  const [qualityIdx, setQualityIdx] = useState(1)   // Balanced default
  const [intervalIdx, setIntervalIdx] = useState(1) // 2s default

  const liveRef     = useRef(false)
  const capturingRef = useRef(false)

  const q = QUALITY_OPTIONS[qualityIdx]
  const iv = INTERVAL_OPTIONS[intervalIdx]

  const capture = useCallback(async () => {
    if (!selectedId || capturingRef.current) return
    capturingRef.current = true
    setCapturing(true)
    setError('')
    try {
      const { b64, elapsed } = await sendScreenshotCmd(selectedId, q.maxW, q.quality)
      if (b64 && !b64.startsWith('ERROR')) {
        setFrame(`data:image/jpeg;base64,${b64.trim()}`)
        setLastTs(new Date())
        setLastElapsed(elapsed)
        setFrameCount(n => n + 1)
        setError('')
      } else if (b64.startsWith('ERROR')) {
        setError(b64)
      } else {
        setError('Timeout — tidak ada respons dari device')
      }
    } catch (e) {
      setError(`Error: ${e}`)
    } finally {
      capturingRef.current = false
      setCapturing(false)
    }
  }, [selectedId, q.maxW, q.quality])

  // Live loop
  const startLive = useCallback(async () => {
    liveRef.current = true
    setLive(true)
    setFrameCount(0)
    while (liveRef.current) {
      await capture()
      if (!liveRef.current) break
      // Wait interval between frames (minus capture time)
      await sleep(iv.ms)
    }
    setLive(false)
  }, [capture, iv.ms])

  const stopLive = useCallback(() => {
    liveRef.current = false
  }, [])

  // Stop live when device changes
  useEffect(() => { liveRef.current = false }, [selectedId])

  const downloadFrame = () => {
    if (!frame) return
    const a = document.createElement('a')
    a.href = frame
    a.download = `screenshot_${Date.now()}.jpg`
    a.click()
  }

  const formatTs = (d: Date) =>
    d.toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit', second: '2-digit' })

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />
      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-4xl mx-auto px-3 md:px-6 py-3 md:py-6">

          {/* Header */}
          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-base md:text-xl font-bold text-white flex items-center gap-2">
                <Monitor size={19} className="text-android-blue" /> Screenshot Realtime
              </h2>
              <p className="text-android-muted text-xs hidden sm:block">Live screen capture dari device Android</p>
            </div>
            <div className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-full border ${connected ? 'text-android-green border-android-green/30 bg-android-green/10' : 'text-android-red border-android-red/30 bg-android-red/10'}`}>
              <Circle size={7} className={connected ? 'fill-android-green' : 'fill-android-red'} />
              {connected ? 'Online' : 'Offline'}
            </div>
          </div>

          {/* Controls bar */}
          <div className="bg-android-surface border border-android-border rounded-xl p-3 mb-4">
            <div className="flex flex-wrap items-center gap-2">

              {/* Quality picker */}
              <div className="flex items-center gap-1.5">
                <span className="text-xs text-android-muted whitespace-nowrap">Kualitas:</span>
                <div className="flex gap-1">
                  {QUALITY_OPTIONS.map((opt, i) => (
                    <button key={i} onClick={() => setQualityIdx(i)} disabled={live}
                      className={`px-2 py-1 text-xs rounded-lg border transition-colors disabled:opacity-40 ${qualityIdx === i ? 'bg-android-blue/20 border-android-blue/50 text-android-blue' : 'border-android-border text-android-muted hover:border-android-blue/30'}`}>
                      {i === 0 ? 'Fast' : i === 1 ? 'Balanced' : 'HD'}
                    </button>
                  ))}
                </div>
              </div>

              {/* Interval picker */}
              <div className="flex items-center gap-1.5">
                <span className="text-xs text-android-muted whitespace-nowrap">Interval:</span>
                <div className="flex gap-1">
                  {INTERVAL_OPTIONS.map((opt, i) => (
                    <button key={i} onClick={() => setIntervalIdx(i)} disabled={live}
                      className={`px-2 py-1 text-xs rounded-lg border transition-colors disabled:opacity-40 ${intervalIdx === i ? 'bg-android-blue/20 border-android-blue/50 text-android-blue' : 'border-android-border text-android-muted hover:border-android-blue/30'}`}>
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>

              {/* Spacer */}
              <div className="flex-1" />

              {/* Action buttons */}
              <button onClick={downloadFrame} disabled={!frame}
                className="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-lg border border-android-border text-android-muted hover:text-android-blue hover:border-android-blue/40 disabled:opacity-30 transition-colors">
                <Download size={13} /> Save
              </button>

              <button onClick={capture} disabled={!connected || live || capturing}
                className="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-lg border border-android-green/40 text-android-green bg-android-green/10 hover:bg-android-green/20 disabled:opacity-30 transition-colors">
                {capturing ? <RefreshCw size={13} className="animate-spin" /> : <Camera size={13} />}
                Capture
              </button>

              {!live ? (
                <button onClick={startLive} disabled={!connected}
                  className="flex items-center gap-1.5 px-4 py-1.5 text-xs rounded-lg bg-android-blue text-white font-semibold hover:bg-android-blue/80 disabled:opacity-30 transition-colors">
                  <Play size={13} className="fill-white" /> Live
                </button>
              ) : (
                <button onClick={stopLive}
                  className="flex items-center gap-1.5 px-4 py-1.5 text-xs rounded-lg bg-android-red text-white font-semibold hover:bg-android-red/80 transition-colors animate-pulse">
                  <Square size={13} className="fill-white" /> Stop
                </button>
              )}
            </div>

            {/* Status row */}
            <div className="flex items-center gap-3 mt-2 pt-2 border-t border-android-border/50">
              {live && (
                <div className="flex items-center gap-1.5 text-android-green text-xs animate-pulse">
                  <Zap size={11} className="fill-android-green" />
                  <span>Live · setiap {iv.label}</span>
                </div>
              )}
              {capturing && !live && (
                <span className="text-android-blue text-xs flex items-center gap-1">
                  <RefreshCw size={10} className="animate-spin" /> Mengambil screenshot…
                </span>
              )}
              {lastTs && (
                <span className="text-android-muted text-xs flex items-center gap-1">
                  <Clock size={10} /> {formatTs(lastTs)}
                </span>
              )}
              {lastElapsed > 0 && (
                <span className="text-android-muted text-xs">{lastElapsed}ms</span>
              )}
              {frameCount > 0 && (
                <span className="text-android-muted text-xs font-mono">#{frameCount}</span>
              )}
              {error && (
                <span className="text-android-red text-xs flex items-center gap-1">
                  <AlertTriangle size={10} /> {error}
                </span>
              )}
            </div>
          </div>

          {/* Viewport */}
          <div className="bg-black border border-android-border rounded-xl overflow-hidden" style={{ minHeight: 340 }}>
            {!connected ? (
              <div className="flex flex-col items-center justify-center h-80 gap-3">
                <Monitor size={48} className="text-android-border" />
                <p className="text-android-muted text-sm">Connect device terlebih dahulu</p>
              </div>
            ) : !frame ? (
              <div className="flex flex-col items-center justify-center h-80 gap-3">
                <Camera size={48} className="text-android-border" />
                <p className="text-android-muted text-sm">Tekan <span className="text-white font-semibold">Capture</span> atau <span className="text-android-blue font-semibold">Live</span> untuk mulai</p>
                <p className="text-android-muted text-xs max-w-xs text-center">Memerlukan Shizuku aktif di device Android. Screencap akan di-resize ke {q.maxW}px JPEG q{q.quality}</p>
              </div>
            ) : (
              <div className="relative">
                <img
                  src={frame}
                  alt="Screenshot"
                  className="w-full h-auto object-contain"
                  style={{ imageRendering: 'auto' }}
                />
                {(live || capturing) && (
                  <div className="absolute top-2 right-2 flex items-center gap-1.5 bg-black/70 rounded-full px-2.5 py-1">
                    <span className="w-2 h-2 rounded-full bg-android-red animate-pulse" />
                    <span className="text-white text-xs font-mono">LIVE</span>
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Info box */}
          <div className="mt-3 bg-android-surface border border-android-border rounded-xl p-3">
            <p className="text-xs text-android-muted font-semibold mb-1.5">ℹ️ Cara kerja</p>
            <ul className="text-xs text-android-muted space-y-1">
              <li>• Android menjalankan <code className="text-android-blue bg-android-blue/10 px-1 rounded">screencap</code> via Shizuku (Strategy 1) atau Runtime.exec (Strategy 2/3)</li>
              <li>• File PNG disimpan sementara di <code className="text-android-blue bg-android-blue/10 px-1 rounded">/sdcard</code>, lalu di-resize dengan BitmapFactory, dikirim sebagai JPEG, kemudian dihapus otomatis</li>
              <li>• Kecepatan realtime tergantung polling interval Android (500ms) + waktu screencap (~500-1500ms)</li>
              <li>• <strong className="text-android-yellow">Shizuku wajib aktif</strong> untuk semua Android — tanpa root, tanpa ADB kabel</li>
            </ul>
          </div>

        </div>
      </main>
    </div>
  )
}

export default function ScreenshotPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center text-android-muted text-sm">Loading…</div>}>
      <ScreenshotContent />
    </Suspense>
  )
}
