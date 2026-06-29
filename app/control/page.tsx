'use client'
import { Suspense, useState, useCallback, useRef, useEffect } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import {
  Gamepad2, Play, Square, RefreshCw, Circle,
  ChevronLeft, ChevronUp, ChevronDown, Home, LayoutGrid,
  Volume2, VolumeX, Power, Send, Camera, Zap, Info,
} from 'lucide-react'

function sleep(ms: number) { return new Promise(r => setTimeout(r, ms)) }

async function sendCmd(deviceId: string, command: string, timeoutMs = 12000): Promise<string> {
  const sentAt = Date.now()
  await fetch('/api/device/command', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId, command }),
  })
  await sleep(500)
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const r = await fetch(`/api/device/result?deviceId=${deviceId}`)
      const d = await r.json()
      const match = (d.history ?? [])
        .filter((h: { command: string; result: string; timestamp: string }) =>
          h.command === command && new Date(h.timestamp).getTime() > sentAt - 200)
        .sort((a: { timestamp: string }, b: { timestamp: string }) =>
          new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())[0]
      if (match?.result) return match.result as string
    } catch {}
    await sleep(400)
  }
  return ''
}

async function captureScreen(deviceId: string, maxW: number, qual: number): Promise<{ b64: string; ms: number }> {
  const cmd = `screenshot:${maxW}:${qual}`
  const sentAt = Date.now()
  await fetch('/api/device/command', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId, command: cmd }),
  })
  await sleep(600)
  const deadline = Date.now() + 14000
  while (Date.now() < deadline) {
    try {
      const r = await fetch(`/api/device/result?deviceId=${deviceId}`)
      const d = await r.json()
      const match = (d.history ?? [])
        .filter((h: { command: string; result: string; timestamp: string }) =>
          h.command === cmd && new Date(h.timestamp).getTime() > sentAt - 200)
        .sort((a: { timestamp: string }, b: { timestamp: string }) =>
          new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())[0]
      if (match?.result) return { b64: match.result as string, ms: Date.now() - sentAt }
    } catch {}
    await sleep(400)
  }
  return { b64: '', ms: Date.now() - sentAt }
}

const QUALITY_OPTS = [
  { label: 'Fast 480p', maxW: 480, qual: 60 },
  { label: 'HD 720p',   maxW: 720, qual: 70 },
  { label: 'Full 1080p', maxW: 1080, qual: 80 },
]

const HW_KEYS = [
  { label: 'Back',    key: 'KEYCODE_BACK',       icon: ChevronLeft },
  { label: 'Home',    key: 'KEYCODE_HOME',        icon: Home },
  { label: 'Recent',  key: 'KEYCODE_APP_SWITCH',  icon: LayoutGrid },
  { label: 'Vol +',   key: 'KEYCODE_VOLUME_UP',   icon: Volume2 },
  { label: 'Vol −',   key: 'KEYCODE_VOLUME_DOWN', icon: VolumeX },
  { label: 'Power',   key: 'KEYCODE_POWER',       icon: Power },
]

function ControlContent() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()

  const [frame, setFrame]           = useState('')
  const [live, setLive]             = useState(false)
  const [capturing, setCapturing]   = useState(false)
  const [acting, setActing]         = useState(false)
  const [qualIdx, setQualIdx]       = useState(1)
  const [frameMs, setFrameMs]       = useState(0)
  const [frameN, setFrameN]         = useState(0)
  const [lastAction, setLastAction] = useState('')
  const [textInput, setTextInput]   = useState('')
  const [error, setError]           = useState('')

  const liveRef      = useRef(false)
  const capRef       = useRef(false)
  const imgRef       = useRef<HTMLImageElement>(null)
  const dragStart    = useRef<{ x: number; y: number } | null>(null)
  const isDragging   = useRef(false)

  const q = QUALITY_OPTS[qualIdx]

  const doCapture = useCallback(async (): Promise<string> => {
    if (!selectedId || capRef.current) return ''
    capRef.current = true
    setCapturing(true)
    setError('')
    try {
      const { b64, ms } = await captureScreen(selectedId, q.maxW, q.qual)
      if (b64 && !b64.startsWith('ERROR')) {
        const url = `data:image/jpeg;base64,${b64.trim()}`
        setFrame(url)
        setFrameMs(ms)
        setFrameN(n => n + 1)
        setError('')
        return url
      } else if (b64.startsWith('ERROR')) {
        setError(b64)
      } else {
        setError('Timeout — tidak ada respons dari device')
      }
    } catch (e) { setError(`${e}`) }
    finally { capRef.current = false; setCapturing(false) }
    return ''
  }, [selectedId, q.maxW, q.qual])

  const startLive = useCallback(async () => {
    liveRef.current = true
    setLive(true)
    setFrameN(0)
    while (liveRef.current) {
      await doCapture()
      if (!liveRef.current) break
      await sleep(500)
    }
    setLive(false)
  }, [doCapture])

  const stopLive = () => { liveRef.current = false }

  useEffect(() => { liveRef.current = false }, [selectedId])

  /* ── Coordinate helper ── */
  const getPct = (clientX: number, clientY: number): { xPct: number; yPct: number } | null => {
    const el = imgRef.current
    if (!el) return null
    const rect = el.getBoundingClientRect()
    const xPct = (clientX - rect.left) / rect.width
    const yPct = (clientY - rect.top)  / rect.height
    return {
      xPct: Math.max(0, Math.min(1, xPct)),
      yPct: Math.max(0, Math.min(1, yPct)),
    }
  }

  const doAction = useCallback(async (cmd: string, label: string) => {
    if (!selectedId) return
    setActing(true)
    setLastAction(label)
    try {
      const res = await sendCmd(selectedId, cmd, 6000)
      if (res.startsWith('ERROR')) setError(res)
      else setError('')
      // Refresh screen after action
      if (!liveRef.current) doCapture()
    } catch {}
    finally { setActing(false) }
  }, [selectedId, doCapture])

  /* ── Mouse / Touch handlers for tap & swipe ── */
  const onPointerDown = (e: React.PointerEvent<HTMLImageElement>) => {
    const pct = getPct(e.clientX, e.clientY)
    if (!pct) return
    dragStart.current = { x: pct.xPct, y: pct.yPct }
    isDragging.current = false
    ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  }

  const onPointerMove = (e: React.PointerEvent<HTMLImageElement>) => {
    if (!dragStart.current) return
    const pct = getPct(e.clientX, e.clientY)
    if (!pct) return
    const dx = Math.abs(pct.xPct - dragStart.current.x)
    const dy = Math.abs(pct.yPct - dragStart.current.y)
    if (dx > 0.01 || dy > 0.01) isDragging.current = true
  }

  const onPointerUp = (e: React.PointerEvent<HTMLImageElement>) => {
    if (!dragStart.current) return
    const pct = getPct(e.clientX, e.clientY)
    if (!pct) return

    if (isDragging.current) {
      // Swipe
      const { x: x1, y: y1 } = dragStart.current
      const dx = Math.abs(pct.xPct - x1); const dy = Math.abs(pct.yPct - y1)
      const dur = Math.round(Math.max(dx, dy) * 800 + 150)
      const cmd = `input_swipe_pct:${x1.toFixed(4)}:${y1.toFixed(4)}:${pct.xPct.toFixed(4)}:${pct.yPct.toFixed(4)}:${dur}`
      doAction(cmd, `Swipe`)
    } else {
      // Tap
      const cmd = `input_tap_pct:${pct.xPct.toFixed(4)}:${pct.yPct.toFixed(4)}`
      doAction(cmd, `Tap (${(pct.xPct * 100).toFixed(0)}%, ${(pct.yPct * 100).toFixed(0)}%)`)
    }
    dragStart.current = null
    isDragging.current = false
  }

  const sendText = () => {
    if (!textInput.trim() || !selectedId) return
    doAction(`input_text:${textInput}`, `Type "${textInput}"`)
    setTextInput('')
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />
      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-5xl mx-auto px-3 md:px-6 py-3 md:py-6">

          {/* Header */}
          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-base md:text-xl font-bold text-white flex items-center gap-2">
                <Gamepad2 size={19} className="text-android-blue" /> Remote Control Screen
              </h2>
              <p className="text-android-muted text-xs hidden sm:block">Lihat & kontrol layar Android secara realtime via Shizuku</p>
            </div>
            <div className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-full border ${connected ? 'text-android-green border-android-green/30 bg-android-green/10' : 'text-android-red border-android-red/30 bg-android-red/10'}`}>
              <Circle size={7} className={connected ? 'fill-android-green' : 'fill-android-red'} />
              {connected ? 'Online' : 'Offline'}
            </div>
          </div>

          <div className="flex flex-col lg:flex-row gap-4">

            {/* ── Screen viewport ── */}
            <div className="flex-1 min-w-0">
              {/* Toolbar */}
              <div className="flex items-center gap-2 mb-2 flex-wrap">
                {QUALITY_OPTS.map((opt, i) => (
                  <button key={i} onClick={() => setQualIdx(i)} disabled={live}
                    className={`px-2 py-1 text-xs rounded-lg border transition-colors disabled:opacity-40 ${qualIdx === i ? 'bg-android-blue/20 border-android-blue/50 text-android-blue' : 'border-android-border text-android-muted hover:border-android-blue/30'}`}>
                    {i === 0 ? 'Fast' : i === 1 ? 'HD' : 'Full'}
                  </button>
                ))}
                <div className="flex-1" />
                <button onClick={doCapture} disabled={!connected || live || capturing}
                  className="flex items-center gap-1 px-3 py-1.5 text-xs rounded-lg border border-android-green/40 text-android-green bg-android-green/10 hover:bg-android-green/20 disabled:opacity-30">
                  {capturing ? <RefreshCw size={12} className="animate-spin" /> : <Camera size={12} />}
                  Capture
                </button>
                {!live ? (
                  <button onClick={startLive} disabled={!connected}
                    className="flex items-center gap-1 px-3 py-1.5 text-xs rounded-lg bg-android-blue text-white font-semibold hover:bg-android-blue/80 disabled:opacity-30">
                    <Play size={12} className="fill-white" /> Live
                  </button>
                ) : (
                  <button onClick={stopLive}
                    className="flex items-center gap-1 px-3 py-1.5 text-xs rounded-lg bg-android-red text-white font-semibold animate-pulse">
                    <Square size={12} className="fill-white" /> Stop
                  </button>
                )}
              </div>

              {/* Screen */}
              <div className="bg-black rounded-xl overflow-hidden border border-android-border relative select-none"
                style={{ minHeight: 300 }}>
                {!connected ? (
                  <div className="flex flex-col items-center justify-center h-72 gap-3">
                    <Gamepad2 size={44} className="text-android-border" />
                    <p className="text-android-muted text-sm">Connect device terlebih dahulu</p>
                  </div>
                ) : !frame ? (
                  <div className="flex flex-col items-center justify-center h-72 gap-3">
                    <Camera size={44} className="text-android-border" />
                    <p className="text-android-muted text-sm">Tekan <span className="text-white font-semibold">Capture</span> atau <span className="text-android-blue font-semibold">Live</span></p>
                    <p className="text-android-muted text-xs text-center max-w-xs">Klik gambar untuk tap · Drag untuk swipe · Butuh Shizuku aktif</p>
                  </div>
                ) : (
                  <img
                    ref={imgRef}
                    src={frame}
                    alt="Remote screen"
                    className={`w-full h-auto cursor-crosshair select-none transition-opacity duration-100 ${(capturing || acting) ? 'opacity-80' : 'opacity-100'}`}
                    draggable={false}
                    onPointerDown={onPointerDown}
                    onPointerMove={onPointerMove}
                    onPointerUp={onPointerUp}
                    style={{ touchAction: 'none', userSelect: 'none' }}
                  />
                )}

                {/* Overlay indicators */}
                {(live || capturing) && (
                  <div className="absolute top-2 right-2 flex items-center gap-1.5 bg-black/70 rounded-full px-2.5 py-1 pointer-events-none">
                    <span className="w-2 h-2 rounded-full bg-android-red animate-pulse" />
                    <span className="text-white text-xs font-mono">LIVE</span>
                  </div>
                )}
                {acting && (
                  <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
                    <div className="bg-black/60 rounded-full px-4 py-2 flex items-center gap-2">
                      <RefreshCw size={14} className="text-android-blue animate-spin" />
                      <span className="text-white text-xs">{lastAction}</span>
                    </div>
                  </div>
                )}
              </div>

              {/* Status bar */}
              <div className="flex items-center gap-3 mt-1.5 px-1">
                {frameN > 0 && <span className="text-android-muted text-xs font-mono">#{frameN}</span>}
                {frameMs > 0 && <span className="text-android-muted text-xs">{frameMs}ms</span>}
                {lastAction && !acting && (
                  <span className="text-android-muted text-xs flex items-center gap-1">
                    <Zap size={10} className="text-android-green" /> {lastAction}
                  </span>
                )}
                {error && <span className="text-android-red text-xs truncate">{error}</span>}
              </div>

              {/* Text input */}
              <div className="mt-3 flex gap-2">
                <input
                  value={textInput}
                  onChange={e => setTextInput(e.target.value)}
                  onKeyDown={e => e.key === 'Enter' && sendText()}
                  placeholder="Ketik teks → kirim ke device…"
                  disabled={!connected}
                  className="flex-1 bg-android-surface border border-android-border text-white text-xs rounded-xl px-3 py-2 placeholder:text-android-muted focus:outline-none focus:border-android-blue/50 disabled:opacity-40"
                />
                <button onClick={sendText} disabled={!connected || !textInput.trim()}
                  className="flex items-center gap-1.5 px-3 py-2 text-xs rounded-xl bg-android-blue text-white disabled:opacity-30 hover:bg-android-blue/80 transition-colors">
                  <Send size={12} /> Kirim
                </button>
              </div>
            </div>

            {/* ── Control pad ── */}
            <div className="lg:w-48 flex flex-col gap-3">

              {/* Hardware keys */}
              <div className="bg-android-surface border border-android-border rounded-xl p-3">
                <p className="text-xs text-android-muted font-semibold mb-2.5">Hardware Keys</p>
                <div className="grid grid-cols-3 gap-1.5">
                  {HW_KEYS.map(({ label, key, icon: Icon }) => (
                    <button key={key}
                      onClick={() => doAction(`input_key:${key}`, label)}
                      disabled={!connected || acting}
                      className="flex flex-col items-center gap-1 py-2.5 px-1 bg-android-bg border border-android-border rounded-xl text-android-muted hover:text-white hover:border-android-blue/40 hover:bg-android-blue/10 disabled:opacity-30 transition-all active:scale-95">
                      <Icon size={16} />
                      <span className="text-[9px] font-medium">{label}</span>
                    </button>
                  ))}
                </div>
              </div>

              {/* Directional pad */}
              <div className="bg-android-surface border border-android-border rounded-xl p-3">
                <p className="text-xs text-android-muted font-semibold mb-2.5">D-Pad / Scroll</p>
                <div className="grid grid-cols-3 gap-1">
                  <div />
                  <button onClick={() => doAction('input_key:KEYCODE_DPAD_UP', 'D-Up')} disabled={!connected || acting}
                    className="flex items-center justify-center p-2.5 bg-android-bg border border-android-border rounded-lg text-android-muted hover:text-white hover:border-android-blue/40 disabled:opacity-30 active:scale-95 transition-all">
                    <ChevronUp size={16} />
                  </button>
                  <div />
                  <button onClick={() => doAction('input_key:KEYCODE_DPAD_LEFT', 'D-Left')} disabled={!connected || acting}
                    className="flex items-center justify-center p-2.5 bg-android-bg border border-android-border rounded-lg text-android-muted hover:text-white hover:border-android-blue/40 disabled:opacity-30 active:scale-95 transition-all">
                    <ChevronLeft size={16} />
                  </button>
                  <button onClick={() => doAction('input_key:KEYCODE_DPAD_CENTER', 'OK')} disabled={!connected || acting}
                    className="flex items-center justify-center p-2.5 bg-android-blue/20 border border-android-blue/30 rounded-lg text-android-blue hover:bg-android-blue/30 disabled:opacity-30 active:scale-95 transition-all text-xs font-bold">
                    OK
                  </button>
                  <button onClick={() => {
                    const r = imgRef.current
                    if (!r) return
                    // simulate right arrow tap on right side
                    doAction('input_key:KEYCODE_DPAD_RIGHT', 'D-Right')
                  }} disabled={!connected || acting}
                    className="flex items-center justify-center p-2.5 bg-android-bg border border-android-border rounded-lg text-android-muted hover:text-white hover:border-android-blue/40 disabled:opacity-30 active:scale-95 transition-all">
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="9 18 15 12 9 6" /></svg>
                  </button>
                  <div />
                  <button onClick={() => doAction('input_key:KEYCODE_DPAD_DOWN', 'D-Down')} disabled={!connected || acting}
                    className="flex items-center justify-center p-2.5 bg-android-bg border border-android-border rounded-lg text-android-muted hover:text-white hover:border-android-blue/40 disabled:opacity-30 active:scale-95 transition-all">
                    <ChevronDown size={16} />
                  </button>
                  <div />
                </div>
              </div>

              {/* Quick swipes */}
              <div className="bg-android-surface border border-android-border rounded-xl p-3">
                <p className="text-xs text-android-muted font-semibold mb-2.5">Quick Swipe</p>
                <div className="grid grid-cols-2 gap-1.5">
                  {[
                    { label: '↑ Scroll Up',   cmd: 'input_swipe_pct:0.5:0.7:0.5:0.3:250' },
                    { label: '↓ Scroll Down', cmd: 'input_swipe_pct:0.5:0.3:0.5:0.7:250' },
                    { label: '← Swipe L',     cmd: 'input_swipe_pct:0.8:0.5:0.2:0.5:250' },
                    { label: '→ Swipe R',     cmd: 'input_swipe_pct:0.2:0.5:0.8:0.5:250' },
                  ].map(({ label, cmd }) => (
                    <button key={cmd} onClick={() => doAction(cmd, label)} disabled={!connected || acting}
                      className="px-2 py-2 text-[10px] bg-android-bg border border-android-border rounded-lg text-android-muted hover:text-white hover:border-android-blue/40 disabled:opacity-30 active:scale-95 transition-all">
                      {label}
                    </button>
                  ))}
                </div>
              </div>

              {/* Info */}
              <div className="bg-android-surface border border-android-border rounded-xl p-3">
                <p className="text-xs text-android-muted font-semibold mb-1.5 flex items-center gap-1"><Info size={10} /> Info</p>
                <ul className="text-[10px] text-android-muted space-y-1 leading-relaxed">
                  <li>• <span className="text-white">Klik</span> gambar = Tap</li>
                  <li>• <span className="text-white">Drag</span> gambar = Swipe</li>
                  <li>• Koordinat dihitung otomatis (persentase layar)</li>
                  <li>• Shizuku wajib aktif &amp; granted</li>
                  <li>• ~1–3s latency per aksi (HTTP polling)</li>
                </ul>
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
  )
}

export default function ControlPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center text-android-muted text-sm">Loading…</div>}>
      <ControlContent />
    </Suspense>
  )
}
