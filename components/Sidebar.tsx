'use client'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import {
  LayoutDashboard,
  Terminal,
  FolderOpen,
  Settings,
  Smartphone,
  Wifi,
  WifiOff,
} from 'lucide-react'

interface SidebarProps {
  connected: boolean
}

const navItems = [
  { href: '/', label: 'Dashboard', icon: LayoutDashboard },
  { href: '/terminal', label: 'Terminal', icon: Terminal },
  { href: '/files', label: 'File Manager', icon: FolderOpen },
  { href: '/setup', label: 'Setup', icon: Settings },
]

export default function Sidebar({ connected }: SidebarProps) {
  const pathname = usePathname()

  return (
    <aside className="w-60 min-h-screen bg-android-surface border-r border-android-border flex flex-col">
      <div className="px-5 py-5 border-b border-android-border">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl bg-android-green flex items-center justify-center">
            <Smartphone size={18} className="text-android-bg" />
          </div>
          <div>
            <h1 className="text-sm font-bold text-white leading-tight">AndroidConnector</h1>
            <p className="text-xs text-android-muted leading-tight">Remote Device Access</p>
          </div>
        </div>
      </div>

      <div className="px-3 py-4 border-b border-android-border">
        <div
          className={`flex items-center gap-2 px-3 py-2 rounded-lg text-xs font-medium ${
            connected
              ? 'bg-android-green/10 text-android-green border border-android-green/20'
              : 'bg-android-red/10 text-android-red border border-android-red/20'
          }`}
        >
          {connected ? (
            <><Wifi size={13} /><span>Device Connected</span><div className="w-2 h-2 rounded-full bg-android-green ml-auto status-dot-online" /></>
          ) : (
            <><WifiOff size={13} /><span>No Device</span><div className="w-2 h-2 rounded-full bg-android-red ml-auto" /></>
          )}
        </div>
      </div>

      <nav className="flex-1 px-3 py-4 space-y-1">
        {navItems.map(({ href, label, icon: Icon }) => {
          const active = pathname === href
          return (
            <Link
              key={href}
              href={href}
              className={`flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                active
                  ? 'bg-android-green/10 text-android-green border border-android-green/20'
                  : 'text-android-muted hover:text-android-text hover:bg-white/5'
              }`}
            >
              <Icon size={16} />
              {label}
            </Link>
          )
        })}
      </nav>

      <div className="px-5 py-4 border-t border-android-border">
        <p className="text-xs text-android-muted">AndroidConnector v0.1.0</p>
        <p className="text-xs text-android-muted/60 mt-0.5">by Kztutorial99</p>
      </div>
    </aside>
  )
}
