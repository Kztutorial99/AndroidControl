'use client'
import { Suspense, useState, useCallback, useRef, useEffect } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import {
  Monitor, Play, Square, Download, RefreshCw, Circle,
  Zap, Clock, Camera, AlertTriangle, ChevronLeft, Home,
  LayoutGrid, ChevronUp, ChevronDown, MousePointer2, Hand,
  Keyboard, Send, Delete, X, Radio,
} from 'lucide-react'

const QUALITY_OPTIONS = [
  { label: 'Fast',     maxW: 480,  quality: 55 },
  { label: 'Balanced', maxW: 720,  quality: 65 },
  { label: 'HD',       maxW: 1080, quality: 75 },
]

const INTERVAL_OPTIONS = [
  { label: 'Max',  ms: 0    },
  { label: '0.5s', ms: 500  },
  { label: '1s',   ms: 1000 },
  { label: '2s',   ms: 2000 },
]

const QUICK_BTNS = [
  { label: 'Back',   icon: ChevronLeft, cmd: 'input_key:KEYCODE_BACK' },
  { label: 'Home',   icon: Home,        cmd: 'input_key:KEYCODE_HOME' },
  { label: 'Recent', icon: LayoutGrid,  cmd: 'input_key:KEYCODE_APP_SWITCH' },
  { label: '↑',      icon: ChevronUp,   cmd: 'input_swipe_pct:0.5:0.7:0.5:0.25:220' },
  { label: '↓',      icon: ChevronDown, cmd: 'input_swipe_pct:0.5:0.3:0.5:0.75:220' },
]

function sleep(ms: number) { return new Promise(r => setTimeout(r, ms)) }

/** Kirim ke /api/device/stream-mode */
async function apiStreamMode(deviceId: string, action: 'start' | 'stop', cmd?: string) {
  await fetch('/api/device/stream-mode', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId, action, cmd }),
  })
}

function ScreenshotContent() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()

  // ── UI state ──
  const [live,        setLive]        = useState(false)
  const [pushMode,    setPushMode]    = useState(false)   // push streaming aktif?
  const [hasFrame,    setHasFrame]    = useState(false)
  const [frameCount,  setFrameCount]  = useState(0)
  const [lastTs,      setLastTs]      = useState<Date | null>(null)
  const [lastElapsed, setLastElapsed] = useState(0)
  const [capturing,   setCapturing]   = useState(false)
  const [error,       setError]       = useState('')
  const [qualityIdx,  setQualityIdx]  = useState(0)
  const [intervalIdx, setIntervalIdx] = useState(0)
  const [fps,         setFps]         = useState(0)
  const [touchMode,   setTouchMode]   = useState(true)
  const [lastAct,     setLastAct]     = useState('')
  const [ripple,      setRipple]      = useState<{ x: number; y: number } | null>(null)
  const [text,        setText]        = useState('')
  const [showKbd,     setShowKbd]     = useState(false)
  const textRef = useRef<HTMLInputElement>(null)

  // ── Refs (tidak trigger re-render) ──
  const liveRef        = useRef(false)
  const pushModeRef    = useRef(false)
  const sseRef         = useRef<EventSource | null>(null)
  const fpsCountRef    = useRef(0)
  const fpsTimerRef    = useRef<ReturnType<typeof setInterval> | null>(null)
  const watchdogRef    = useRef<ReturnType<typeof setTimeout> | null>(null)
  const imgRef         = useRef<HTMLImageElement | null>(null)
  const frameRef       = useRef('')
  const pendingRef     = useRef(false)
  const dragRef        = useRef<{ x: number; y: number; px: number; py: number } | null>(null)
  const dragging       = useRef(false)
  const frameRecvRef   = useRef(0)

  const q  = QUALITY_OPTIONS[qualityIdx]
  const iv = INTERVAL_OPTIONS[intervalIdx]

  // ── Kirim screenshot command (legacy mode) ──
  const sendCmd = useCallback(async (devId: string) => {
    await fetch('/api/device/command', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: devId, command: `screenshot:${q.maxW}:${q.quality}` }),
    })
  }, [q.maxW, q.quality])

  // ── Kirim touch/key command ──
  const sendTouch = useCallback((cmd: string, label: string) => {
    if (!selectedId || !connected) return
    setLastAct(label)
    fetch('/api/device/command', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: selectedId, command: cmd }),
    }).catch(() => {})
  }, [selectedId, connected])

  // ── Koordinat pct dari pointer event ──
  const getPct = (cx: number, cy: number) => {
    const el = imgRef.current; if (!el) return null
    const r = el.getBoundingClientRect()
    const containerW = r.width; const containerH = r.height
    const naturalRatio = el.naturalWidth / el.naturalHeight
    const containerRatio = containerW / containerH
    let imgW: number, imgH: number, imgX: number, imgY: number
    if (naturalRatio > containerRatio) {
      imgW = containerW; imgH = containerW / naturalRatio; imgX = 0; imgY = (containerH - imgH) / 2
    } else {
      imgH = containerH; imgW = containerH * naturalRatio; imgX = (containerW - imgW) / 2; imgY = 0
    }
    const relX = cx - r.left - imgX; const relY = cy - r.top - imgY
    if (relX < 0 || relY < 0 || relX > imgW || relY > imgH) return null
    return {
      x: Math.max(0, Math.min(1, relX / imgW)),
      y: Math.max(0, Math.min(1, relY / imgH)),
      px: cx - r.left, py: cy - r.top,
    }
  }

  // ── Pointer handlers ──
  const onDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!touchMode || !hasFrame) return
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
        sendTouch(`input_swipe_pct:${x1.toFixed(4)}:${y1.toFixed(4)}:${p.x.toFixed(4)}:${p.y.toFixed(4)}:${Math.round(dist * 700 + 150)}`, 'Swipe')
      } else {
        setRipple({ x: p.px, y: p.py }); setTimeout(() => setRipple(null), 600)
        sendTouch(`input_tap_pct:${p.x.toFixed(4)}:${p.y.toFixed(4)}`, `Tap ${Math.round(p.x * 100)}%,${Math.round(p.y * 100)}%`)
      }
    }
    dragRef.current = null; dragging.current = false
  }

  // ── Capture tunggal ──
  const captureSingle = useCallback(async () => {
    if (!selectedId || capturing) return
    setCapturing(true); setError('')
    const cmdName = `screenshot:${q.maxW}:${q.quality}`
    const sentAt  = Date.now()
    try {
      await fetch('/api/device/command', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, command: cmdName }),
      })
      await sleep(500)
      for (let i = 0; i < 30; i++) {
        if (i > 0) await sleep(300)
        const d = await (await fetch(`/api/device/result?deviceId=${selectedId}`)).json()
        const match = (d.history ?? [])
          .filter((h: { command: string; result: string; timestamp: string }) =>
            h.command === cmdName && new Date(h.timestamp).getTime() > sentAt - 200)
          .sort((a: { timestamp: string }, b: { timestamp: string }) =>
            new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())[0]
        if (match?.result && !match.result.startsWith('ERROR')) {
          const dataUrl = `data:image/jpeg;base64,${match.result.trim()}`
          frameRef.current = dataUrl
          if (imgRef.current) imgRef.current.src = dataUrl
          setHasFrame(true); setLastTs(new Date()); setLastElapsed(Date.now() - sentAt)
          setFrameCount(n => n + 1); setError(''); break
        } else if (match?.result?.startsWith('ERROR')) { setError(match.result); break }
      }
    } catch (e) { setError(`Error: ${e}`) }
    finally { setCapturing(false) }
  }, [selectedId, capturing, q.maxW, q.quality])

  // ── Watchdog: restart server loop jika push mode tapi frame berhenti >3s ──
  const resetWatchdog = useCallback((devId: string, cmdStr: string) => {
    if (watchdogRef.current) clearTimeout(watchdogRef.current)
    watchdogRef.current = setTimeout(async () => {
      if (!liveRef.current || !pushModeRef.current) return
      // Re-prime server loop
      await apiStreamMode(devId, 'start', cmdStr).catch(() => {})
      resetWatchdog(devId, cmdStr)
    }, 3000)
  }, [])

  // ── Buka SSE dan setup onmessage handler ──
  const openSSE = useCallback((devId: string, isPush: boolean, cmdStr: string) => {
    const es = new EventSource(`/api/device/stream?deviceId=${devId}`)
    sseRef.current = es

    es.onmessage = (evt) => {
      try {
        const msg = JSON.parse(evt.data)
        if (msg.type === 'frame' && liveRef.current) {
          const now = Date.now()
          const elapsed = frameRecvRef.current > 0 ? now - frameRecvRef.current : 0
          frameRecvRef.current = now

          const dataUrl = `data:image/jpeg;base64,${msg.b64}`
          if (imgRef.current) imgRef.current.src = dataUrl
          frameRef.current = dataUrl
          setHasFrame(true)
          setLastTs(new Date())
          if (elapsed > 0) setLastElapsed(elapsed)
          setFrameCount(n => n + 1)
          fpsCountRef.current++
          pendingRef.current = false

          if (pushModeRef.current) {
            // Push mode: server sudah auto re-enqueue, reset watchdog saja
            resetWatchdog(devId, cmdStr)
          } else {
            // Legacy mode: browser kirim command berikutnya
            const doNext = async () => {
              if (iv.ms > 0) await sleep(iv.ms)
              if (liveRef.current && !pendingRef.current && !pushModeRef.current) {
                pendingRef.current = true
                sendCmd(devId)
              }
            }
            doNext()
          }
        }
      } catch {}
    }

    es.onerror = () => { if (liveRef.current) setError('SSE terputus…') }

    if (isPush) {
      es.onopen = () => {
        setError('')
        // Server sudah enqueue command pertama via apiStreamMode, jangan dobel
      }
    } else {
      es.onopen = () => {
        setError(''); pendingRef.current = true; sendCmd(devId)
      }
    }
  }, [iv.ms, sendCmd, resetWatchdog])

  // ── Start Push Streaming (mode baru, server loop) ──
  const startPush = useCallback(async () => {
    if (!selectedId || liveRef.current) return
    liveRef.current = true; pushModeRef.current = true; pendingRef.current = false
    setLive(true); setPushMode(true); setFrameCount(0); setFps(0)
    fpsCountRef.current = 0; setError(''); frameRecvRef.current = 0

    fpsTimerRef.current = setInterval(() => {
      setFps(fpsCountRef.current); fpsCountRef.current = 0
    }, 1000)

    const cmdStr = `screenshot:${q.maxW}:${q.quality}`
    // Daftarkan server loop + enqueue command pertama
    await apiStreamMode(selectedId, 'start', cmdStr).catch(() => {})

    openSSE(selectedId, true, cmdStr)
    resetWatchdog(selectedId, cmdStr)
  }, [selectedId, q.maxW, q.quality, openSSE, resetWatchdog])

  // ── Start Legacy Live (browser kirim command tiap frame) ──
  const startLive = useCallback(() => {
    if (!selectedId || liveRef.current) return
    liveRef.current = true; pushModeRef.current = false; pendingRef.current = false
    setLive(true); setPushMode(false); setFrameCount(0); setFps(0)
    fpsCountRef.current = 0; setError(''); frameRecvRef.current = 0

    fpsTimerRef.current = setInterval(() => {
      setFps(fpsCountRef.current); fpsCountRef.current = 0
    }, 1000)

    openSSE(selectedId, false, `screenshot:${q.maxW}:${q.quality}`)
  }, [selectedId, q.maxW, q.quality, openSSE])

  // ── Stop semua ──
  const stopLive = useCallback(() => {
    const devId = selectedId
    liveRef.current = false; pushModeRef.current = false; pendingRef.current = false
    setLive(false); setPushMode(false)
    sseRef.current?.close(); sseRef.current = null
    if (fpsTimerRef.current) { clearInterval(fpsTimerRef.current); fpsTimerRef.current = null }
    if (watchdogRef.current) { clearTimeout(watchdogRef.current); watchdogRef.current = null }
    setFps(0)
    // Bersihkan server loop
    if (devId) apiStreamMode(devId, 'stop').catch(() => {})
  }, [selectedId])

  useEffect(() => { stopLive() }, [selectedId])       // eslint-disable-line
  useEffect(() => () => { stopLive() }, [])            // eslint-disable-line

  // Race condition fix: set src dari frameRef jika imgRef null saat frame pertama
  useEffect(() => {
    if (hasFrame && imgRef.current && frameRef.current && !imgRef.current.src) {
      imgRef.current.src = frameRef.current
    }
  }, [hasFrame])

  // ── Kirim teks ke Android ──
  const sendText = useCallback(() => {
    const t = text.trim()
    if (!t || !selectedId || !connected) return
    sendTouch(`input_text:${t.replace(/ /g, '%s')}`, `Type "${t.length > 20 ? t.slice(0, 20) + '…' : t}"`)
    setText(''); textRef.current?.focus()
  }, [text, selectedId, connected, sendTouch])

  const downloadFrame = () => {
    if (!frameRef.current) return
    const a = document.createElement('a')
    a.href = frameRef.current; a.download = `screenshot_${Date.now()}.jpg`; a.click()
  }

  const formatTs = (d: Date) =>
    d.toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit', second: '2-digit' })

  const latencyColor = lastElapsed < 300 ? 'text-android-green'
    : lastElapsed < 700 ? 'text-android-yellow' : 'text-android-red'

  return (
    <div className="flex h-[100dvh] overflow-hidden bg-android-bg">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />

      <main className="flex-1 page-fixed min-w-0">

        {/* ── Controls bar ── */}
        <div className="flex-shrink-0 bg-android-surface border-b border-android-border px-3 py-2 space-y-1.5">

          {/* Row 1: title + status + touch toggle */}
          <div className="flex items-center gap-2">
            <h2 className="text-sm font-bold text-white flex items-center gap-1.5 flex-1">
              <Monitor size={15} className="text-android-blue" />
              Live Screen
            </h2>
            <button
              onClick={() => setTouchMode(v => !v)} disabled={!hasFrame}
              className={`flex items-center gap-1 px-2 py-0.5 text-xs rounded border transition-colors disabled:opacity-30 ${touchMode ? 'bg-android-blue/20 border-android-blue/50 text-android-blue' : 'border-android-border text-android-muted'}`}
            >
              {touchMode ? <Hand size={11} /> : <MousePointer2 size={11} />}
              {touchMode ? 'Touch' : 'View'}
            </button>
            <div className={`flex items-center gap-1 text-xs font-medium px-2 py-0.5 rounded-full border ${connected ? 'text-android-green border-android-green/30 bg-android-green/10' : 'text-android-red border-android-red/30 bg-android-red/10'}`}>
              <Circle size={6} className={connected ? 'fill-android-green' : 'fill-android-red'} />
              {connected ? 'Online' : 'Offline'}
            </div>
          </div>

          {/* Row 2: quality + interval + buttons */}
          <div className="flex flex-wrap items-center gap-1.5">
            <div className="flex items-center gap-1">
              <span className="text-xs text-android-muted">Q:</span>
              {QUALITY_OPTIONS.map((opt, i) => (
                <button key={i} onClick={() => setQualityIdx(i)} disabled={live}
                  className={`px-1.5 py-0.5 text-xs rounded border transition-colors disabled:opacity-40 ${qualityIdx === i ? 'bg-android-blue/20 border-android-blue/50 text-android-blue' : 'border-android-border text-android-muted'}`}>
                  {opt.label}
                </button>
              ))}
            </div>
            {/* IV hanya untuk legacy mode */}
            {!pushMode && (
              <div className="flex items-center gap-1">
                <span className="text-xs text-android-muted">IV:</span>
                {INTERVAL_OPTIONS.map((opt, i) => (
                  <button key={i} onClick={() => setIntervalIdx(i)} disabled={live}
                    className={`px-1.5 py-0.5 text-xs rounded border transition-colors disabled:opacity-40 ${intervalIdx === i ? 'bg-android-blue/20 border-android-blue/50 text-android-blue' : 'border-android-border text-android-muted'}`}>
                    {opt.label}
                  </button>
                ))}
              </div>
            )}
            <div className="flex-1" />
            <button onClick={downloadFrame} disabled={!hasFrame}
              className="flex items-center gap-1 px-2 py-0.5 text-xs rounded border border-android-border text-android-muted hover:text-android-blue disabled:opacity-30 transition-colors">
              <Download size={11} /> Save
            </button>
            <button onClick={captureSingle} disabled={!connected || live || capturing}
              className="flex items-center gap-1 px-2 py-0.5 text-xs rounded border border-android-green/40 text-android-green bg-android-green/10 hover:bg-android-green/20 disabled:opacity-30 transition-colors">
              {capturing ? <RefreshCw size={11} className="animate-spin" /> : <Camera size={11} />}
              Snap
            </button>

            {/* ── Tombol Push / Live / Stop ── */}
            {!live ? (
              <>
                {/* Push Streaming — server loop, tercepat */}
                <button onClick={startPush} disabled={!connected}
                  className="flex items-center gap-1 px-3 py-0.5 text-xs rounded bg-android-green text-black font-bold hover:opacity-90 disabled:opacity-30 transition-colors">
                  <Radio size={11} /> Push
                </button>
                {/* Legacy Live — browser kirim command tiap frame */}
                <button onClick={startLive} disabled={!connected}
                  className="flex items-center gap-1 px-2 py-0.5 text-xs rounded border border-android-blue/50 text-android-blue hover:bg-android-blue/10 disabled:opacity-30 transition-colors">
                  <Play size={11} className="fill-current" /> Live
                </button>
              </>
            ) : (
              <button onClick={stopLive}
                className="flex items-center gap-1 px-3 py-0.5 text-xs rounded bg-android-red text-white font-semibold hover:bg-android-red/80 transition-colors animate-pulse">
                <Square size={11} className="fill-white" /> Stop
              </button>
            )}
          </div>

          {/* Row 3: quick buttons + stats */}
          <div className="flex items-center gap-2">
            <div className="flex items-center gap-1">
              {QUICK_BTNS.map(({ label, icon: Icon, cmd }) => (
                <button key={cmd} onClick={() => sendTouch(cmd, label)} disabled={!connected} title={label}
                  className="flex items-center justify-center w-7 h-6 text-xs rounded border border-android-border text-android-muted hover:text-white hover:border-android-blue/40 hover:bg-android-blue/10 disabled:opacity-30 transition-colors">
                  <Icon size={12} />
                </button>
              ))}
              <button onClick={() => sendTouch('input_key:KEYCODE_DEL', 'Backspace')} disabled={!connected} title="Backspace"
                className="flex items-center justify-center w-7 h-6 text-xs rounded border border-android-border text-android-muted hover:text-android-red hover:border-android-red/40 hover:bg-android-red/10 disabled:opacity-30 transition-colors">
                <Delete size={12} />
              </button>
            </div>

            <div className="w-px h-4 bg-android-border" />

            <button onClick={() => { setShowKbd(v => !v); setTimeout(() => textRef.current?.focus(), 50) }}
              disabled={!connected}
              className={`flex items-center gap-1 px-1.5 py-0.5 text-xs rounded border transition-colors disabled:opacity-30 ${showKbd ? 'bg-android-blue/20 border-android-blue/50 text-android-blue' : 'border-android-border text-android-muted hover:border-android-blue/30'}`}>
              <Keyboard size={11} />
              <span className="hidden sm:inline">Ketik</span>
            </button>

            <div className="w-px h-4 bg-android-border" />

            {/* Stats */}
            <div className="flex items-center gap-2 text-xs flex-1 min-w-0">
              {live && pushMode && (
                <span className="flex items-center gap-1 text-android-green animate-pulse shrink-0">
                  <Radio size={10} />
                  PUSH{fps > 0 && <span className="font-mono ml-0.5">{fps}fps</span>}
                </span>
              )}
              {live && !pushMode && (
                <span className="flex items-center gap-1 text-android-blue shrink-0">
                  <Zap size={10} className="fill-current" />
                  LIVE{fps > 0 && <span className="font-mono ml-0.5">{fps}fps</span>}
                </span>
              )}
              {capturing && !live && (
                <span className="flex items-center gap-1 text-android-blue shrink-0">
                  <RefreshCw size={10} className="animate-spin" /> Snap…
                </span>
              )}
              {lastTs && (
                <span className="flex items-center gap-1 text-android-muted shrink-0">
                  <Clock size={10} /> {formatTs(lastTs)}
                </span>
              )}
              {lastElapsed > 0 && (
                <span className={`font-mono shrink-0 ${latencyColor}`}>{lastElapsed}ms</span>
              )}
              {lastAct && !error && (
                <span className="text-android-muted truncate text-[10px]">⬤ {lastAct}</span>
              )}
              {error && (
                <span className="flex items-center gap-1 text-android-red truncate">
                  <AlertTriangle size={10} /> {error}
                </span>
              )}
            </div>
          </div>

          {/* Row 4: keyboard input */}
          {showKbd && (
            <div className="flex items-center gap-1.5 pt-1 border-t border-android-border/60">
              <Keyboard size={12} className="text-android-blue shrink-0" />
              <input ref={textRef} type="text" value={text}
                onChange={e => setText(e.target.value)}
                onKeyDown={e => {
                  if (e.key === 'Enter') { e.preventDefault(); sendText() }
                  if (e.key === 'Escape') { setShowKbd(false); setText('') }
                }}
                placeholder="Ketik teks → Enter untuk kirim ke Android…"
                disabled={!connected}
                className="flex-1 bg-android-bg border border-android-border rounded-lg px-2.5 py-1 text-xs text-white placeholder-android-muted/60 focus:outline-none focus:border-android-blue/60 focus:ring-1 focus:ring-android-blue/30 disabled:opacity-40 transition-colors"
              />
              {text && (
                <button onClick={() => { setText(''); textRef.current?.focus() }}
                  className="p-1 rounded text-android-muted hover:text-white transition-colors">
                  <X size={12} />
                </button>
              )}
              <button onClick={sendText} disabled={!text.trim() || !connected}
                className="flex items-center gap-1 px-2.5 py-1 text-xs rounded-lg bg-android-blue text-white font-semibold hover:bg-android-blue/80 disabled:opacity-30 transition-colors">
                <Send size={11} /> Kirim
              </button>
            </div>
          )}
        </div>

        {/* ── Viewport ── */}
        <div
          className={`flex-1 bg-black relative overflow-hidden ${touchMode && hasFrame ? 'cursor-crosshair' : ''}`}
          onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={onUp}
        >
          {!connected ? (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-3">
              <Monitor size={44} className="text-android-border" />
              <p className="text-android-muted text-sm">Connect device terlebih dahulu</p>
            </div>
          ) : (
            <>
              {/* img selalu di DOM saat connected — imgRef valid sejak frame pertama */}
              <img
                ref={imgRef}
                alt="Live Screen"
                draggable={false}
                className="absolute inset-0 w-full h-full object-contain"
                style={{
                  display: hasFrame ? 'block' : 'none',
                  imageRendering: 'auto', touchAction: 'none', userSelect: 'none', pointerEvents: 'none',
                }}
              />

              {/* Placeholder */}
              {!hasFrame && (
                <div className="absolute inset-0 flex flex-col items-center justify-center gap-3">
                  <Camera size={44} className="text-android-border" />
                  <p className="text-android-muted text-sm text-center px-4">
                    Tekan <span className="text-android-green font-bold">Push</span> untuk streaming tercepat
                    <br />
                    atau <span className="text-android-blue font-semibold">Live</span> untuk mode manual
                    <br />
                    atau <span className="text-android-green font-semibold">Snap</span> untuk satu frame
                  </p>
                  <p className="text-android-muted text-xs opacity-60">Push mode = server loop, latency lebih rendah</p>
                </div>
              )}

              {/* Ripple */}
              {ripple && (
                <div className="absolute pointer-events-none" style={{ left: ripple.x - 20, top: ripple.y - 20, width: 40, height: 40 }}>
                  <div className="w-full h-full rounded-full border-2 border-android-blue animate-ping opacity-75" />
                  <div className="absolute inset-0 m-auto w-3 h-3 rounded-full bg-android-blue/60" style={{ left: '50%', top: '50%', transform: 'translate(-50%,-50%)' }} />
                </div>
              )}

              {/* Touch hint */}
              {touchMode && hasFrame && (
                <div className="absolute top-2 left-2 flex items-center gap-1 bg-black/60 backdrop-blur-sm rounded-full px-2 py-0.5">
                  <Hand size={9} className="text-android-blue" />
                  <span className="text-[9px] text-android-blue font-medium">Touch</span>
                </div>
              )}

              {/* Live/Push badge */}
              {live && (
                <div className={`absolute top-2 right-2 flex items-center gap-1.5 bg-black/70 backdrop-blur-sm rounded-full px-2.5 py-1`}>
                  {pushMode
                    ? <><Radio size={10} className="text-android-green animate-pulse" /><span className="text-android-green text-xs font-bold">PUSH</span></>
                    : <><span className="w-2 h-2 rounded-full bg-android-red animate-pulse" /><span className="text-white text-xs font-mono">LIVE</span></>
                  }
                  {fps > 0 && <span className="text-android-muted text-xs font-mono">{fps}fps</span>}
                </div>
              )}

              {/* Frame counter */}
              <div className="absolute bottom-2 right-2 text-[10px] font-mono text-white/40">
                #{frameCount}
              </div>
            </>
          )}
        </div>
      </main>
    </div>
  )
}

export default function ScreenshotPage() {
  return (
    <Suspense>
      <ScreenshotContent />
    </Suspense>
  )
}
