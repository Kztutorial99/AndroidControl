'use client'
import { Suspense, useState, useCallback, useRef, useEffect } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import {
  Gamepad2, Play, Square, RefreshCw, Circle,
  ChevronLeft, ChevronUp, ChevronDown, Home, LayoutGrid,
  Volume2, VolumeX, Power, Send, Camera, Zap,
} from 'lucide-react'

function sleep(ms: number) { return new Promise(r => setTimeout(r, ms)) }

/* ── Single generic command sender (no overlap risk — caller awaits) ── */
async function apiCmd(deviceId: string, command: string, timeoutMs = 10000): Promise<string> {
  const sentAt = Date.now()
  await fetch('/api/device/command', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId, command }),
  })
  await sleep(400)
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

/* ── Screenshot — returns {dataUrl, ms} ── */
async function grabFrame(deviceId: string, maxW: number, qual: number): Promise<{ url: string; ms: number }> {
  const cmd = `screenshot:${maxW}:${qual}`
  const sentAt = Date.now()
  await fetch('/api/device/command', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId, command: cmd }),
  })
  await sleep(500)
  const deadline = Date.now() + 15000
  while (Date.now() < deadline) {
    try {
      const d = await (await fetch(`/api/device/result?deviceId=${deviceId}`)).json()
      const hit = (d.history ?? [])
        .filter((h: { command: string; result: string; timestamp: string }) =>
          h.command === cmd && new Date(h.timestamp).getTime() > sentAt - 100)
        .sort((a: { timestamp: string }, b: { timestamp: string }) =>
          new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())[0]
      if (hit?.result && !hit.result.startsWith('ERROR')) {
        return { url: `data:image/jpeg;base64,${(hit.result as string).trim()}`, ms: Date.now() - sentAt }
      }
      if (hit?.result?.startsWith('ERROR')) return { url: '', ms: Date.now() - sentAt }
    } catch {}
    await sleep(350)
  }
  return { url: '', ms: Date.now() - sentAt }
}

const Q = [
  { label: 'Fast', maxW: 480, qual: 60 },
  { label: 'HD',   maxW: 720, qual: 70 },
  { label: 'Full', maxW: 1080, qual: 80 },
]

const HW = [
  { l: 'Back',   k: 'KEYCODE_BACK',       I: ChevronLeft },
  { l: 'Home',   k: 'KEYCODE_HOME',        I: Home },
  { l: 'Recent', k: 'KEYCODE_APP_SWITCH',  I: LayoutGrid },
  { l: 'Vol+',   k: 'KEYCODE_VOLUME_UP',   I: Volume2 },
  { l: 'Vol−',   k: 'KEYCODE_VOLUME_DOWN', I: VolumeX },
  { l: 'Power',  k: 'KEYCODE_POWER',       I: Power },
]

const SWIPES = [
  { l: '↑ Scroll Up',   c: 'input_swipe_pct:0.5:0.7:0.5:0.25:220' },
  { l: '↓ Scroll Down', c: 'input_swipe_pct:0.5:0.3:0.5:0.75:220' },
  { l: '← Swipe Left',  c: 'input_swipe_pct:0.8:0.5:0.2:0.5:200' },
  { l: '→ Swipe Right', c: 'input_swipe_pct:0.2:0.5:0.8:0.5:200' },
]

function ControlContent() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()

  const [frame, setFrame]     = useState('')
  const [live, setLive]       = useState(false)
  const [busy, setBusy]       = useState(false)   // any pending op
  const [qi, setQi]           = useState(1)
  const [frameMs, setFrameMs] = useState(0)
  const [frameN, setFrameN]   = useState(0)
  const [lastAct, setLastAct] = useState('')
  const [text, setText]       = useState('')
  const [err, setErr]         = useState('')

  const liveRef  = useRef(false)
  const busyRef  = useRef(false)      // true while screencap in-flight
  const imgRef   = useRef<HTMLImageElement>(null)
  const dragRef  = useRef<{ x: number; y: number } | null>(null)
  const dragging = useRef(false)

  const q = Q[qi]

  /* ── Grab one frame — lock prevents overlap ── */
  const oneFrame = useCallback(async () => {
    if (!selectedId || busyRef.current) return
    busyRef.current = true
    setBusy(true)
    setErr('')
    try {
      const { url, ms } = await grabFrame(selectedId, q.maxW, q.qual)
      if (url) { setFrame(url); setFrameMs(ms); setFrameN(n => n + 1) }
      else if (!url && ms > 0) setErr('Timeout — cek Shizuku')
    } catch { setErr('Network error') }
    finally { busyRef.current = false; setBusy(false) }
  }, [selectedId, q.maxW, q.qual])

  /* ── Pipeline loop — NO overlap possible (await blocks) ── */
  const startLive = useCallback(async () => {
    liveRef.current = true
    setLive(true)
    setFrameN(0)
    while (liveRef.current) {
      await oneFrame()                // waits for result → naturally sequential
      if (!liveRef.current) break
      // micro-pause only for React re-render, not artificial delay
      await sleep(50)
    }
    setLive(false)
  }, [oneFrame])

  const stopLive = () => { liveRef.current = false }

  useEffect(() => { liveRef.current = false }, [selectedId])

  /* ── Coordinate pct helper ── */
  const getPct = (cx: number, cy: number) => {
    const el = imgRef.current; if (!el) return null
    const r = el.getBoundingClientRect()
    return {
      x: Math.max(0, Math.min(1, (cx - r.left) / r.width)),
      y: Math.max(0, Math.min(1, (cy - r.top) / r.height)),
    }
  }

  /* ── Action sender — fire & refresh screen ── */
  const act = useCallback(async (cmd: string, label: string) => {
    if (!selectedId) return
    setBusy(true); setLastAct(label); setErr('')
    try {
      const res = await apiCmd(selectedId, cmd, 6000)
      if (res.startsWith?.('ERROR')) setErr(res)
      if (!liveRef.current) await oneFrame()   // refresh after action
    } catch {}
    finally { setBusy(false) }
  }, [selectedId, oneFrame])

  /* ── Pointer events for tap / swipe on screen image ── */
  const onDown = (e: React.PointerEvent<HTMLImageElement>) => {
    const p = getPct(e.clientX, e.clientY); if (!p) return
    dragRef.current = { x: p.x, y: p.y }; dragging.current = false
    ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
    e.preventDefault()
  }
  const onMove = (e: React.PointerEvent<HTMLImageElement>) => {
    if (!dragRef.current) return
    const p = getPct(e.clientX, e.clientY); if (!p) return
    if (Math.abs(p.x - dragRef.current.x) > 0.012 || Math.abs(p.y - dragRef.current.y) > 0.012)
      dragging.current = true
  }
  const onUp = (e: React.PointerEvent<HTMLImageElement>) => {
    if (!dragRef.current) return
    const p = getPct(e.clientX, e.clientY)
    if (p) {
      if (dragging.current) {
        const { x: x1, y: y1 } = dragRef.current
        const dur = Math.round((Math.hypot(p.x - x1, p.y - y1)) * 700 + 150)
        act(`input_swipe_pct:${x1.toFixed(4)}:${y1.toFixed(4)}:${p.x.toFixed(4)}:${p.y.toFixed(4)}:${dur}`, 'Swipe')
      } else {
        act(`input_tap_pct:${p.x.toFixed(4)}:${p.y.toFixed(4)}`, `Tap ${Math.round(p.x*100)}%,${Math.round(p.y*100)}%`)
      }
    }
    dragRef.current = null; dragging.current = false
  }

  const sendText = () => {
    if (!text.trim() || !selectedId) return
    act(`input_text:${text}`, `Type "${text}"`); setText('')
  }

  return (
    <div className="flex h-[100dvh] overflow-hidden">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />

      {/* Main — page-fixed handles mobile top/bottom padding */}
      <main className="flex-1 page-fixed min-w-0">

        {/* ── Header ── */}
        <div className="shrink-0 flex items-center gap-2 px-3 py-2 border-b border-android-border bg-android-surface">
          <Gamepad2 size={15} className="text-android-blue shrink-0" />
          <span className="text-sm font-bold text-white flex-1 truncate">Remote Control</span>

          {/* fps / ms */}
          {frameMs > 0 && <span className="text-[10px] text-android-muted font-mono">{frameMs}ms</span>}
          {frameN > 0 && <span className="text-[10px] text-android-muted font-mono">#{frameN}</span>}

          {/* status dot */}
          <div className={`w-2 h-2 rounded-full shrink-0 ${connected ? 'bg-android-green' : 'bg-android-red'}`} />

          {/* quality */}
          <div className="flex gap-0.5">
            {Q.map((o, i) => (
              <button key={i} onClick={() => setQi(i)} disabled={live}
                className={`px-1.5 py-0.5 text-[10px] rounded border transition-colors disabled:opacity-40 ${qi===i?'bg-android-blue/20 border-android-blue/50 text-android-blue':'border-android-border text-android-muted'}`}>
                {o.label}
              </button>
            ))}
          </div>

          {/* capture / live */}
          <button onClick={oneFrame} disabled={!connected || live || busy}
            className="p-1.5 rounded-lg border border-android-green/40 text-android-green disabled:opacity-30 hover:bg-android-green/10">
            {busy && !live ? <RefreshCw size={13} className="animate-spin" /> : <Camera size={13} />}
          </button>
          {!live ? (
            <button onClick={startLive} disabled={!connected}
              className="flex items-center gap-1 px-2.5 py-1 text-[11px] rounded-lg bg-android-blue text-white font-semibold disabled:opacity-30">
              <Play size={11} className="fill-white" /> Live
            </button>
          ) : (
            <button onClick={stopLive}
              className="flex items-center gap-1 px-2.5 py-1 text-[11px] rounded-lg bg-android-red text-white font-semibold animate-pulse">
              <Square size={11} className="fill-white" /> Stop
            </button>
          )}
        </div>

        {/* ── Screen viewport — fills all remaining height ── */}
        <div className="flex-1 min-h-0 bg-black overflow-hidden relative select-none">
          {!connected && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-2">
              <Gamepad2 size={40} className="text-android-border" />
              <p className="text-android-muted text-sm">Connect device terlebih dahulu</p>
            </div>
          )}
          {connected && !frame && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-2">
              <Camera size={40} className="text-android-border" />
              <p className="text-android-muted text-sm">Capture atau Live untuk mulai</p>
              <p className="text-android-muted text-xs">Klik = Tap · Drag = Swipe</p>
            </div>
          )}
          {frame && (
            <img
              ref={imgRef} src={frame} alt="screen"
              draggable={false}
              className={`w-full h-full object-contain cursor-crosshair transition-opacity duration-75 ${busy?'opacity-80':'opacity-100'}`}
              style={{ touchAction: 'none', userSelect: 'none' }}
              onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp}
            />
          )}

          {/* LIVE badge */}
          {live && (
            <div className="absolute top-2 right-2 flex items-center gap-1 bg-black/70 rounded-full px-2 py-0.5 pointer-events-none">
              <span className="w-1.5 h-1.5 rounded-full bg-android-red animate-pulse" />
              <span className="text-white text-[10px] font-mono">LIVE</span>
            </div>
          )}
          {/* action toast */}
          {busy && lastAct && (
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

        {/* ── Bottom controls — fixed height, no scroll ── */}
        <div className="shrink-0 bg-android-surface border-t border-android-border">

          {/* Hardware keys row — horizontal scroll if tight */}
          <div className="flex items-center gap-1 px-2 py-1.5 overflow-x-auto scrollbar-hide">
            {HW.map(({ l, k, I: Icon }) => (
              <button key={k} onClick={() => act(`input_key:${k}`, l)} disabled={!connected || busy}
                className="shrink-0 flex flex-col items-center gap-0.5 px-2.5 py-1.5 bg-android-bg border border-android-border rounded-lg text-android-muted hover:text-white hover:border-android-blue/40 disabled:opacity-30 active:scale-95 transition-all min-w-[48px]">
                <Icon size={14} />
                <span className="text-[9px] font-medium whitespace-nowrap">{l}</span>
              </button>
            ))}
            <div className="w-px h-6 bg-android-border shrink-0 mx-0.5" />
            {/* D-pad inline */}
            {[
              { l: '↑', k: 'KEYCODE_DPAD_UP' }, { l: '↓', k: 'KEYCODE_DPAD_DOWN' },
              { l: '←', k: 'KEYCODE_DPAD_LEFT' }, { l: '→', k: 'KEYCODE_DPAD_RIGHT' },
            ].map(({ l, k }) => (
              <button key={k} onClick={() => act(`input_key:${k}`, l)} disabled={!connected || busy}
                className="shrink-0 flex items-center justify-center w-10 h-10 bg-android-bg border border-android-border rounded-lg text-android-muted hover:text-white disabled:opacity-30 active:scale-95 transition-all text-sm">
                {l}
              </button>
            ))}
          </div>

          {/* Quick swipes row */}
          <div className="flex items-center gap-1 px-2 pb-1.5 overflow-x-auto scrollbar-hide">
            {SWIPES.map(({ l, c }) => (
              <button key={c} onClick={() => act(c, l)} disabled={!connected || busy}
                className="shrink-0 px-2.5 py-1 text-[10px] bg-android-bg border border-android-border rounded-lg text-android-muted hover:text-white hover:border-android-blue/30 disabled:opacity-30 active:scale-95 transition-all whitespace-nowrap">
                {l}
              </button>
            ))}
          </div>

          {/* Text input */}
          <div className="flex gap-1.5 px-2 pb-2">
            <input value={text} onChange={e => setText(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && sendText()}
              placeholder="Ketik teks → kirim ke device…"
              disabled={!connected}
              className="flex-1 bg-android-bg border border-android-border text-white text-xs rounded-xl px-3 py-2 placeholder:text-android-muted focus:outline-none focus:border-android-blue/50 disabled:opacity-40 min-w-0"
            />
            <button onClick={sendText} disabled={!connected || !text.trim()}
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
    <Suspense fallback={<div className="flex h-[100dvh] items-center justify-center text-android-muted text-sm">Loading…</div>}>
      <ControlContent />
    </Suspense>
  )
}
