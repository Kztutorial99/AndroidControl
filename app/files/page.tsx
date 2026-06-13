'use client'
import { useEffect, useState, useCallback } from 'react'
import Sidebar from '@/components/Sidebar'
import {
  Folder, File, ArrowLeft, RefreshCw,
  HardDrive, Circle, ChevronRight
} from 'lucide-react'

interface FileEntry {
  name: string
  type: 'file' | 'dir'
  size: string
  permissions: string
  modified: string
}

interface FileListing {
  path: string
  entries: FileEntry[]
}

const QUICK_PATHS = [
  { label: 'Internal', path: '/storage/emulated/0' },
  { label: 'DCIM', path: '/storage/emulated/0/DCIM' },
  { label: 'Downloads', path: '/storage/emulated/0/Download' },
  { label: 'Pictures', path: '/storage/emulated/0/Pictures' },
  { label: 'Documents', path: '/storage/emulated/0/Documents' },
  { label: '/sdcard', path: '/sdcard' },
  { label: '/tmp', path: '/data/local/tmp' },
  { label: '/proc', path: '/proc' },
]

export default function FilesPage() {
  const [connected, setConnected] = useState(false)
  const [listing, setListing] = useState<FileListing | null>(null)
  const [path, setPath] = useState('/storage/emulated/0')
  const [loading, setLoading] = useState(false)

  const fetchStatus = useCallback(async () => {
    try {
      const res = await fetch('/api/device/heartbeat')
      const data = await res.json()
      setConnected(data.connected ?? false)
    } catch {}
  }, [])

  const fetchListing = useCallback(async () => {
    try {
      const res = await fetch('/api/device/files')
      const data = await res.json()
      if (data.listing) setListing(data.listing)
    } catch {}
  }, [])

  useEffect(() => {
    fetchStatus()
    fetchListing()
    const interval = setInterval(() => { fetchStatus(); fetchListing() }, 3000)
    return () => clearInterval(interval)
  }, [fetchStatus, fetchListing])

  const navigate = async (targetPath: string) => {
    setLoading(true)
    setListing(null)
    setPath(targetPath)
    try {
      await fetch('/api/device/files', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: targetPath }),
      })
    } finally {
      setLoading(false)
    }
  }

  const goUp = () => {
    const parts = path.split('/').filter(Boolean)
    if (parts.length <= 1) return
    navigate('/' + parts.slice(0, -1).join('/'))
  }

  const viewFile = async (filePath: string) => {
    await fetch('/api/device/command', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ command: `read_text:${filePath}` }),
    })
  }

  const breadcrumbs = path.split('/').filter(Boolean)
  // On mobile, only show last 2 breadcrumbs
  const visibleCrumbs = breadcrumbs.slice(-2)
  const hiddenCount = breadcrumbs.length - visibleCrumbs.length

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} />

      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-5xl mx-auto px-3 md:px-6 py-3 md:py-6">

          {/* Header */}
          <div className="flex items-center justify-between mb-3">
            <div>
              <h2 className="text-base md:text-xl font-bold text-white">File Manager</h2>
              <p className="text-android-muted text-xs hidden sm:block">Browse your Android device storage</p>
            </div>
            <div className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-full border ${connected ? 'text-android-green border-android-green/30 bg-android-green/10' : 'text-android-red border-android-red/30 bg-android-red/10'}`}>
              <Circle size={7} className={connected ? 'fill-android-green' : 'fill-android-red'} />
              {connected ? 'Online' : 'Offline'}
            </div>
          </div>

          {/* Quick path shortcuts — horizontal scroll on mobile */}
          <div className="flex gap-1.5 mb-3 overflow-x-auto pb-1">
            {QUICK_PATHS.map(({ label, path: p }) => (
              <button
                key={p}
                onClick={() => navigate(p)}
                disabled={!connected}
                className="shrink-0 px-2.5 py-1.5 bg-android-surface border border-android-border rounded-lg text-xs text-android-muted hover:text-android-blue hover:border-android-blue/50 transition-colors disabled:opacity-40 font-mono whitespace-nowrap"
              >
                {label}
              </button>
            ))}
          </div>

          {/* File browser */}
          <div className="bg-android-surface border border-android-border rounded-xl overflow-hidden">

            {/* Path bar */}
            <div className="px-3 py-2.5 border-b border-android-border flex items-center gap-2">
              <button
                onClick={goUp}
                disabled={!connected || path === '/'}
                className="p-1.5 rounded-lg hover:bg-android-border/50 disabled:opacity-30 transition-colors shrink-0 active:bg-android-border/70"
              >
                <ArrowLeft size={15} className="text-android-muted" />
              </button>

              <div className="flex items-center gap-1 flex-1 overflow-hidden text-xs">
                <HardDrive size={12} className="text-android-muted shrink-0" />
                {hiddenCount > 0 && (
                  <span className="text-android-muted shrink-0">…</span>
                )}
                {visibleCrumbs.map((part, i) => {
                  const actualIdx = hiddenCount + i
                  return (
                    <span key={actualIdx} className="flex items-center gap-1 shrink-0 min-w-0">
                      <ChevronRight size={12} className="text-android-border shrink-0" />
                      <button
                        onClick={() => navigate('/' + breadcrumbs.slice(0, actualIdx + 1).join('/'))}
                        className="text-android-text hover:text-android-blue font-mono transition-colors truncate max-w-[80px] md:max-w-none"
                      >
                        {part}
                      </button>
                    </span>
                  )
                })}
              </div>

              <button
                onClick={() => navigate(path)}
                disabled={!connected}
                className="p-1.5 rounded-lg hover:bg-android-border/50 disabled:opacity-30 transition-colors shrink-0"
              >
                <RefreshCw size={13} className="text-android-muted" />
              </button>
            </div>

            {/* Content */}
            {!connected ? (
              <div className="p-10 text-center">
                <HardDrive size={32} className="text-android-border mx-auto mb-3" />
                <p className="text-android-muted text-sm">Connect your device to browse files</p>
              </div>
            ) : loading ? (
              <div className="p-10 text-center">
                <RefreshCw size={22} className="text-android-green mx-auto mb-3 animate-spin" />
                <p className="text-android-muted text-sm">Loading…</p>
              </div>
            ) : listing && listing.entries.length > 0 ? (
              <div className="divide-y divide-android-border/50">
                {listing.entries
                  .sort((a, b) => {
                    if (a.type === 'dir' && b.type !== 'dir') return -1
                    if (a.type !== 'dir' && b.type === 'dir') return 1
                    return a.name.localeCompare(b.name)
                  })
                  .map(entry => (
                    <div
                      key={entry.name}
                      className="flex items-center gap-3 px-4 py-3 hover:bg-white/5 active:bg-white/10 cursor-pointer group transition-colors"
                      onClick={() =>
                        entry.type === 'dir'
                          ? navigate(`${path}/${entry.name}`)
                          : viewFile(`${path}/${entry.name}`)
                      }
                    >
                      {entry.type === 'dir' ? (
                        <Folder size={17} className="text-android-yellow shrink-0" />
                      ) : (
                        <File size={17} className="text-android-muted shrink-0" />
                      )}
                      <span className="flex-1 text-sm text-android-text font-medium truncate">
                        {entry.name}
                      </span>
                      <span className="text-xs text-android-muted font-mono shrink-0">
                        {entry.size}
                      </span>
                      {entry.type === 'dir' && (
                        <ChevronRight size={14} className="text-android-border shrink-0" />
                      )}
                    </div>
                  ))}
              </div>
            ) : (
              <div className="p-10 text-center">
                <Folder size={32} className="text-android-border mx-auto mb-3" />
                <p className="text-android-muted text-sm">
                  {listing ? 'Empty folder or access denied' : 'Navigate to a folder to see its contents'}
                </p>
                {connected && !listing && (
                  <button
                    onClick={() => navigate(path)}
                    className="mt-4 px-4 py-2 bg-android-green text-android-bg rounded-lg text-sm font-medium"
                  >
                    Browse {path.split('/').pop()}
                  </button>
                )}
              </div>
            )}
          </div>

          <p className="text-xs text-android-muted mt-2 hidden md:block">
            Tap folder to navigate · Tap file to view in Terminal
          </p>
        </div>
      </main>
    </div>
  )
}
