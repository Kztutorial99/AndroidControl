'use client'
import { Suspense, useState, useCallback, useRef, useEffect } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import {
  Gamepad2, Play, Square, Camera, Send, Zap,
  ChevronLeft, Home, LayoutGrid, Volume2, VolumeX, Power,
} from 'lucide-react'

async function apiStreamMode(deviceId: string, action: 'start' | 'stop', cmd?: string, targetFps?: number) {
  await fetch('/api/device/stream-mode', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId, action, cmd, targetFps }),
  }).catch(() => {})
}

// Signal browser is ready for next frame — true backpressure
async function apiStreamAck(deviceId: string) {
  await fetch('/api/device/stream-ack', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId }),
  }).catch(() => {})
}

const Q = [
  { label: 'Micro', maxW: 320, qual: 35 },
  { label: 'Fast',  maxW: 480, qual: 50 },
  { label: 'HD',    maxW: 720, qual: 65 },
]

const HW = [
  { l: 'Back',   k: 'KEYCODE_BACK',       I: ChevronLeft },
  { l: 'Home',   k: 'KEYCODE_HOME',        I: Home },
  { l: 'Recent', k: 'KEYCODE_APP_SWITCH',  I: LayoutGrid },
  { l: 'Vol+',   k: 'KEYCODE_VOLUME_UP',   I: Volume2 },
  { l: 'Vol-',   k: 'KEYCODE_VOLUME_DOWN', I: VolumeX },
  { l: 'Power',  k: 'KEYCODE_POWER',       I: Power },
]

const SWIPES = [
  { l: 'Scroll Up',   c: 'input_swipe_pct:0.5:0.7:0.5:0.25:220' },
  { l: 'Scroll Down', c: 'input_swipe_pct:0.5:0.3:0.5:0.75:220' },
  { l: 'Swipe Left',  c: 'input_swipe_pct:0.8:0.5:0.2:0.5:200' },
  { l: 'Swipe Right', c: 'input_swipe_pct:0.2:0.5:0.8:0.5:200' },
]

function ControlContent() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()

  const [hasFrame,    setHasFrame]    = useState(false)
  const [live,        setLive]        = useState(false)
  const [qi,          setQi]          = useState(1)
  const [fps,         setFps]         = useState(0)
  const [frameN,      setFrameN]      = useState(0)
  const [lastElapsed, setLastElapsed] = useState(0)
  const [lastAct,     setLastAct]     = useState('')
  const [text,        setText]        = useState('')
  const [err,         setErr]         = useState('')
  const [capturing,   setCapturing]   = useState(false)
  const [ripple,      setRipple]      = useState<{ x: number; y: number } | null>(null)

  const liveRef         = useRef(false)
  const sseRef          = useRef<EventSource | null>(null)
  const fpsCountRef     = useRef(0)
  const fpsTimerRef     = useRef<ReturnType<typeof setInterval> | null>(null)
  // Watchdog: 15s — must be > worst-case network latency
  const watchdogRef     = useRef<ReturnType<typeof setTimeout> | null>(null)
  const sseReconnectRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const frameRecvRef    = useRef(0)
  const imgRef          = useRef<HTMLImageElement | null>(null)
  const dragRef         = useRef<{ x: number; y: number; px: number; py: number } | null>(null)
  const dragging        = useRef(false)
  const ackingRef       = useRef(false)

  const q = Q[qi]

  const sendCmd = useCallback((cmd: string, label: string) => {
    if (!selectedId || !connected) return
    setLastAct(label)
    fetch('/api/device/command', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: selectedId, command: cmd }),
    }).catch(() => {})
  }, [selectedId, connected])

  const getPct = (cx: number, cy: number) => {
    const el = imgRef.current; if (!el) return null
    const r = el.getBoundingClientRect()
    const naturalRatio = el.naturalWidth / el.naturalHeight
    const containerRatio = r.width / r.height
    let imgW: number, imgH: number, imgX: number, imgY: number
    if (naturalRatio > containerRatio) {
      imgW = r.width; imgH = r.width / naturalRatio; imgX = 0; imgY = (r.height - imgH) / 2
    } else {
      imgH = r.height; imgW = r.height * naturalRatio; imgX = (r.width - imgW) / 2; imgY = 0
    }
    const relX = cx - r.left - imgX; const relY = cy - r.top - imgY
    if (relX < 0 || relY < 0 || relX > imgW || relY > imgH) return null
    return {
      x: Math.max(0, Math.min(1, relX / imgW)),
      y: Math.max(0, Math.min(1, relY / imgH)),
      px: cx - r.left, py: cy - r.top,
    }
  }

  const onDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!hasFrame) return
    const p = getPct(e.clientX, e.clientY); if (!p) return
    dragRef.current = p; dragging.current = false
    ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
    e.preventDefault()
  }
  const onMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragRef.current) return
    const p = getPct(e.clientX, e.clientY); if (!p) return
    if (Math.abs(p.x - dragRef.current.x) > 0.01 || Math.abs(p.y - dragRef.current.y) > 0.01)
      dragging.current = true
    e.preventDefault()
  }
  const onUp = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!dragRef.current) return
    const p = getPct(e.clientX, e.clientY)
    if (p) {
      if (dragging.current) {
        const { x: x1, y: y1 } = dragRef.current
        const dist = Math.hypot(p.x - x1, p.y - y1)
        sendCmd(`input_swipe_pct:${x1.toFixed(4)}:${y1.toFixed(4)}:${p.x.toFixed(4)}:${p.y.toFixed(4)}:${Math.round(dist * 700 + 150)}`, 'Swipe')
      } else {
        setRipple({ x: p.px, y: p.py })
        setTimeout(() => setRipple(null), 600)
        sendCmd(`input_tap_pct:${p.x.toFixed(4)}:${p.y.toFixed(4)}`, `Tap ${Math.round(p.x * 100)}%,${Math.round(p.y * 100)}%`)
      }
    }
    dragRef.current = null; dragging.current = false
  }

  // Watchdog: if no frame for 15s, re-prime Android (don't restart SSE)
  const resetWatchdog = useCallback((devId: string, cmdStr: string) => {
    if (watchdogRef.current) clearTimeout(watchdogRef.current)
    watchdogRef.current = setTimeout(async () => {
      if (!liveRef.current) return
      // Re-prime only: restart the stream-mode (ack mode) without touching SSE
      await apiStreamMode(devId, 'start', cmdStr, -1)
      resetWatchdog(devId, cmdStr)
    }, 15000)
  }, [])

  const openSSE = useCallback((devId: string, cmdStr: string) => {
    if (sseRef.current) { sseRef.current.close(); sseRef.current = null }
    if (sseReconnectRef.current) { clearTimeout(sseReconnectRef.current); sseReconnectRef.current = null }
    if (!liveRef.current) return

    const es = new EventSource(`/api/device/stream?deviceId=${encodeURIComponent(devId)}`)
    sseRef.current = es

    es.onmessage = (evt) => {
      try {
        const msg = JSON.parse(evt.data)
        if (msg.type === 'frame' && liveRef.current) {
          const now = Date.now()
          const elapsed = frameRecvRef.current > 0 ? now - frameRecvRef.current : 0
          frameRecvRef.current = now

          // ── PERF: direct DOM mutation, NO React re-render per frame ──
          if (imgRef.current) imgRef.current.src = `data:image/jpeg;base64,${msg.b64}`
          setHasFrame(true)
          setFrameN(n => n + 1)
          if (elapsed > 0) setLastElapsed(elapsed)
          fpsCountRef.current++
          setErr('')
          resetWatchdog(devId, cmdStr)

          // ── BACKPRESSURE: tell server we are ready for NEXT frame ──
          // This prevents Android from queuing ahead of our render speed.
          if (!ackingRef.current) {
            ackingRef.current = true
            // Use requestAnimationFrame to ensure img is actually painted first
            requestAnimationFrame(() => {
              apiStreamAck(devId).finally(() => { ackingRef.current = false })
            })
          }
        }
      } catch {}
    }

    es.onerror = () => {
      if (!liveRef.current) return
      es.close(); sseRef.current = null
      // On SSE error: reconnect SSE but DON'T restart stream-mode
      // (Android is still running, just our listener broke)
      sseReconnectRef.current = setTimeout(() => {
        if (!liveRef.current) return
        setErr('')
        openSSE(devId, cmdStr)
        // Only re-prime if we haven't received a frame in a while
        // (ack will take care of the normal flow)
      }, 1000)
    }

    es.onopen = () => setErr('')
  }, [resetWatchdog])

  const startLive = useCallback(async () => {
    if (!selectedId || liveRef.current) return
    liveRef.current = true
    setLive(true); setFrameN(0); setFps(0); setErr('')
    fpsCountRef.current = 0; frameRecvRef.current = 0; ackingRef.current = false

    fpsTimerRef.current = setInterval(() => {
      setFps(fpsCountRef.current); fpsCountRef.current = 0
    }, 1000)

    const cmdStr = `screenshot:${q.maxW}:${q.qual}`
    // ACK mode (targetFps=-1): server waits for browser ACK before each capture
    await apiStreamMode(selectedId, 'start', cmdStr, -1)
    openSSE(selectedId, cmdStr)
    resetWatchdog(selectedId, cmdStr)
  }, [selectedId, q.maxW, q.qual, openSSE, resetWatchdog])

  const stopLive = useCallback(() => {
    const devId = selectedId
    liveRef.current = false; ackingRef.current = false
    setLive(false); setFps(0)
    sseRef.current?.close(); sseRef.current = null
    if (fpsTimerRef.current)    { clearInterval(fpsTimerRef.current);    fpsTimerRef.current    = null }
    if (watchdogRef.current)    { clearTimeout(watchdogRef.current);     watchdogRef.current    = null }
    if (sseReconnectRef.current){ clearTimeout(sseReconnectRef.current); sseReconnectRef.current = null }
    if (devId) apiStreamMode(devId, 'stop')
  }, [selectedId])

  // Single capture: one-shot ACK mode — get exactly 1 frame then stop
  const captureSingle = useCallback(async () => {
    if (!selectedId || capturing || live) return
    setCapturing(true); setErr('')
    const cmdStr = `screenshot:${q.maxW}:${q.qual}`
    try {
      await apiStreamMode(selectedId, 'start', cmdStr, -1)
      await new Promise<void>((resolve) => {
        const es = new EventSource(`/api/device/stream?deviceId=${encodeURIComponent(selectedId)}`)
        const t = setTimeout(() => { es.close(); setErr('Timeout — pastikan device terhubung'); resolve() }, 12000)
        es.onmessage = (evt) => {
          try {
            const msg = JSON.parse(evt.data)
            if (msg.type === 'frame') {
              if (imgRef.current) imgRef.current.src = `data:image/jpeg;base64,${msg.b64}`
              setHasFrame(true); setFrameN(n => n + 1); setErr('')
              clearTimeout(t); es.close(); resolve()
            }
          } catch {}
        }
        es.onerror = () => { clearTimeout(t); es.close(); resolve() }
      })
    } finally {
      await apiStreamMode(selectedId, 'stop')
      setCapturing(false)
    }
  }, [selectedId, capturing, live, q.maxW, q.qual])

  useEffect(() => { stopLive() }, [selectedId])  // eslint-disable-line
  useEffect(() => () => { stopLive() }, [])       // eslint-disable-line

  const sendText = () => {
    const t = text.trim()
    if (!t || !selectedId || !connected) return
    sendCmd(`input_text:${t.replace(/ /g, '%s')}`, `Type "${t.length > 20 ? t.slice(0, 20) + '...' : t}"`)
    setText('')
  }

  const latencyColor = lastElapsed < 800  ? 'text-android-green'
    : lastElapsed < 2000 ? 'text-android-yellow' : 'text-android-red'

  return (
    <div className="flex h-[100dvh] overflow-hidden">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />

      <main className="flex-1 page-fixed min-w-0">

        {/* Header */}
        <div className="shrink-0 flex items-center gap-2 px-3 py-2 border-b border-android-border bg-android-surface">
          <Gamepad2 size={15} className="text-android-blue shrink-0" />
          <span className="text-sm font-bold text-white flex-1 truncate">Remote Control</span>

          {live && fps > 0 && (
            <span className="text-[10px] text-android-green font-mono font-semibold">{fps}fps</span>
          )}
          {live && lastElapsed > 0 && (
            <span className={`text-[10px] font-mono ${latencyColor}`}>{lastElapsed}ms</span>
          )}
          {!live && frameN > 0 && (
            <span className="text-[10px] text-android-muted font-mono">#{frameN}</span>
          )}

          <div className={`w-2 h-2 rounded-full shrink-0 ${connected ? 'bg-android-green' : 'bg-android-red'}`} />

          <div className="flex gap-0.5">
            {Q.map((o, i) => (
              <button key={i} onClick={() => setQi(i)} disabled={live}
                className={`px-1.5 py-0.5 text-[10px] rounded border transition-colors disabled:opacity-40 ${
                  qi === i
                    ? 'bg-android-blue/20 border-android-blue/50 text-android-blue'
                    : 'border-android-border text-android-muted'
                }`}>
                {o.label}
              </button>
            ))}
          </div>

          <button onClick={captureSingle} disabled={!connected || live || capturing}
            className="p-1.5 rounded-lg border border-android-green/40 text-android-green disabled:opacity-30 hover:bg-android-green/10 transition-colors">
            <Camera size={13} className={capturing ? 'animate-pulse' : ''} />
          </button>

          {!live ? (
            <button onClick={startLive} disabled={!connected}
              className="flex items-center gap-1 px-2.5 py-1 text-[11px] rounded-lg bg-android-blue text-white font-semibold disabled:opacity-30 hover:bg-android-blue/80 transition-colors">
              <Play size={11} className="fill-white" /> Live
            </button>
          ) : (
            <button onClick={stopLive}
              className="flex items-center gap-1 px-2.5 py-1 text-[11px] rounded-lg bg-android-red text-white font-semibold">
              <Square size={11} className="fill-white" /> Stop
            </button>
          )}
        </div>

        {/* Screen viewport */}
        <div
          className="flex-1 min-h-0 bg-black overflow-hidden relative select-none"
          onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp}
          style={{ touchAction: 'none' }}
        >
          {!connected && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-2">
              <Gamepad2 size={40} className="text-android-border" />
              <p className="text-android-muted text-sm">Connect device terlebih dahulu</p>
            </div>
          )}
          {connected && !hasFrame && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-2">
              <Camera size={40} className="text-android-border" />
              <p className="text-android-muted text-sm">Tap Live atau Capture untuk mulai</p>
              <p className="text-android-muted text-xs">Klik = Tap  ·  Drag = Swipe</p>
            </div>
          )}

          {/* img always in DOM when connected — src updated directly (no re-render) */}
          {connected && (
            <img
              ref={imgRef}
              alt="screen"
              draggable={false}
              className={`w-full h-full object-contain transition-opacity duration-75 ${hasFrame ? 'opacity-100' : 'opacity-0'}`}
              style={{ userSelect: 'none', pointerEvents: 'none' }}
            />
          )}

          {live && (
            <div className="absolute top-2 right-2 flex items-center gap-1 bg-black/70 rounded-full px-2 py-0.5 pointer-events-none">
              <span className="w-1.5 h-1.5 rounded-full bg-android-red animate-pulse" />
              <span className="text-white text-[10px] font-mono">LIVE</span>
            </div>
          )}

          {ripple && (
            <div
              className="absolute w-8 h-8 rounded-full border-2 border-android-blue/70 animate-ping pointer-events-none"
              style={{ left: ripple.x - 16, top: ripple.y - 16 }}
            />
          )}

          {lastAct && live && (
            <div className="absolute bottom-2 left-1/2 -translate-x-1/2 flex items-center gap-1.5 bg-black/70 rounded-full px-3 py-1 pointer-events-none">
              <Zap size={10} className="text-android-blue" />
              <span className="text-white text-[10px]">{lastAct}</span>
            </div>
          )}

          {err && (
            <div className="absolute bottom-2 left-2 right-2 bg-android-red/20 border border-android-red/40 rounded-lg px-2 py-1 text-android-red text-[10px] text-center pointer-events-none">
              {err}
            </div>
          )}
        </div>

        {/* Bottom controls */}
        <div className="shrink-0 bg-android-surface border-t border-android-border">
          <div className="flex items-center gap-1 px-2 py-1.5 overflow-x-auto scrollbar-hide">
            {HW.map(({ l, k, I: Icon }) => (
              <button key={k} onClick={() => sendCmd(`input_key:${k}`, l)} disabled={!connected}
                className="shrink-0 flex flex-col items-center gap-0.5 px-2.5 py-1.5 bg-android-bg border border-android-border rounded-lg text-android-muted hover:text-white hover:border-android-blue/40 disabled:opacity-30 active:scale-95 transition-all min-w-[48px]">
                <Icon size={14} />
                <span className="text-[9px] font-medium whitespace-nowrap">{l}</span>
              </button>
            ))}
            <div className="w-px h-6 bg-android-border shrink-0 mx-0.5" />
            {[
              { l: '↑', k: 'KEYCODE_DPAD_UP' },
              { l: '↓', k: 'KEYCODE_DPAD_DOWN' },
              { l: '←', k: 'KEYCODE_DPAD_LEFT' },
              { l: '→', k: 'KEYCODE_DPAD_RIGHT' },
            ].map(({ l, k }) => (
              <button key={k} onClick={() => sendCmd(`input_key:${k}`, l)} disabled={!connected}
                className="shrink-0 flex items-center justify-center w-10 h-10 bg-android-bg border border-android-border rounded-lg text-android-muted hover:text-white disabled:opacity-30 active:scale-95 transition-all text-sm">
                {l}
              </button>
            ))}
          </div>

          <div className="flex items-center gap-1 px-2 pb-1.5 overflow-x-auto scrollbar-hide">
            {SWIPES.map(({ l, c }) => (
              <button key={c} onClick={() => sendCmd(c, l)} disabled={!connected}
                className="shrink-0 px-2.5 py-1 text-[10px] bg-android-bg border border-android-border rounded-lg text-android-muted hover:text-white hover:border-android-blue/30 disabled:opacity-30 active:scale-95 transition-all whitespace-nowrap">
                {l}
              </button>
            ))}
          </div>

          <div className="flex gap-1.5 px-2 pb-2">
            <input
              value={text}
              onChange={e => setText(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && sendText()}
              placeholder="Ketik teks lalu Enter / Kirim..."
              disabled={!connected}
              className="flex-1 bg-android-bg border border-android-border text-white text-xs rounded-xl px-3 py-2 placeholder:text-android-muted focus:outline-none focus:border-android-blue/50 disabled:opacity-40 min-w-0"
            />
            <button
              onClick={sendText}
              disabled={!connected || !text.trim()}
              className="shrink-0 flex items-center gap-1 px-3 py-2 text-xs rounded-xl bg-android-blue text-white disabled:opacity-30 hover:bg-android-blue/80 active:scale-95 transition-all">
              <Send size={12} /> Kirim
            </button>
          </div>
        </div>
      </main>
    </div>
  )
}

export default function ControlPage() {
  return (
    <Suspense fallback={<div className="flex h-[100dvh] items-center justify-center text-android-muted text-sm">Loading...</div>}>
      <ControlContent />
    </Suspense>
  )
}
