'use client'
import { useEffect, useState, useCallback } from 'react'
import Sidebar from '@/components/Sidebar'
import {
  Folder, File, ArrowLeft, RefreshCw,
  HardDrive, Circle, ChevronRight, Download
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
  '/storage/emulated/0',
  '/storage/emulated/0/DCIM',
  '/storage/emulated/0/Download',
  '/storage/emulated/0/Pictures',
  '/storage/emulated/0/Documents',
  '/sdcard',
  '/data/local/tmp',
  '/proc',
]

export default function FilesPage() {
  const [connected, setConnected] = useState(false)
  const [listing, setListing] = useState<FileListing | null>(null)
  const [path, setPath] = useState('/storage/emulated/0')
  const [loading, setLoading] = useState(false)
  const [rawOutput, setRawOutput] = useState<string | null>(null)

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
      if (data.listing) {
        setListing(data.listing)
        setRawOutput(null)
      }
    } catch {}
  }, [])

  useEffect(() => {
    fetchStatus()
    fetchListing()
    const interval = setInterval(() => {
      fetchStatus()
      fetchListing()
    }, 3000)
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
    const parent = '/' + parts.slice(0, -1).join('/')
    navigate(parent)
  }

  const viewFile = async (filePath: string) => {
    await fetch('/api/device/command', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ command: `cat "${filePath}" 2>&1 | head -100` }),
    })
  }

  const breadcrumbs = path.split('/').filter(Boolean)

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} />

      <main className="flex-1 p-6 overflow-y-auto">
        <div className="max-w-5xl mx-auto">
          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-xl font-bold text-white">File Manager</h2>
              <p className="text-android-muted text-sm mt-0.5">Browse your Android device storage</p>
            </div>
            <div className={`flex items-center gap-2 text-xs font-medium px-3 py-1.5 rounded-full border ${connected ? 'text-android-green border-android-green/30 bg-android-green/10' : 'text-android-red border-android-red/30 bg-android-red/10'}`}>
              <Circle size={7} className={connected ? 'fill-android-green' : 'fill-android-red'} />
              {connected ? 'Online' : 'Offline'}
            </div>
          </div>

          <div className="flex gap-2 mb-4 flex-wrap">
            {QUICK_PATHS.map(p => (
              <button
                key={p}
                onClick={() => navigate(p)}
                disabled={!connected}
                className="px-3 py-1.5 bg-android-surface border border-android-border rounded-lg text-xs text-android-muted hover:text-android-blue hover:border-android-blue/50 transition-colors disabled:opacity-40 font-mono"
              >
                {p.split('/').pop() || '/'}
              </button>
            ))}
          </div>

          <div className="bg-android-surface border border-android-border rounded-xl overflow-hidden">
            <div className="px-4 py-3 border-b border-android-border flex items-center gap-2">
              <button
                onClick={goUp}
                disabled={!connected || path === '/'}
                className="p-1.5 rounded-lg hover:bg-android-border/50 disabled:opacity-30 transition-colors"
              >
                <ArrowLeft size={15} className="text-android-muted" />
              </button>

              <div className="flex items-center gap-1 flex-1 overflow-x-auto text-sm">
                <HardDrive size={13} className="text-android-muted shrink-0" />
                {breadcrumbs.map((part, i) => (
                  <span key={i} className="flex items-center gap-1 shrink-0">
                    <ChevronRight size={13} className="text-android-border" />
                    <button
                      onClick={() => navigate('/' + breadcrumbs.slice(0, i + 1).join('/'))}
                      className="text-android-text hover:text-android-blue font-mono transition-colors"
                    >
                      {part}
                    </button>
                  </span>
                ))}
              </div>

              <button
                onClick={() => navigate(path)}
                disabled={!connected}
                className="p-1.5 rounded-lg hover:bg-android-border/50 disabled:opacity-30 transition-colors"
              >
                <RefreshCw size={13} className="text-android-muted" />
              </button>
            </div>

            {!connected ? (
              <div className="p-12 text-center">
                <HardDrive size={36} className="text-android-border mx-auto mb-3" />
                <p className="text-android-muted text-sm">Connect your device to browse files</p>
              </div>
            ) : loading ? (
              <div className="p-12 text-center">
                <RefreshCw size={24} className="text-android-green mx-auto mb-3 animate-spin" />
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
                      className="flex items-center gap-3 px-4 py-3 hover:bg-white/5 cursor-pointer group transition-colors"
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
                      <span className="text-xs text-android-muted font-mono hidden md:block">
                        {entry.permissions}
                      </span>
                      <span className="text-xs text-android-muted font-mono w-20 text-right hidden sm:block">
                        {entry.size}
                      </span>
                      {entry.type === 'file' && (
                        <button
                          onClick={e => { e.stopPropagation(); viewFile(`${path}/${entry.name}`) }}
                          className="opacity-0 group-hover:opacity-100 p-1 rounded hover:text-android-blue transition-all"
                          title="View file in terminal"
                        >
                          <Download size={13} />
                        </button>
                      )}
                    </div>
                  ))}
              </div>
            ) : (
              <div className="p-12 text-center">
                <Folder size={36} className="text-android-border mx-auto mb-3" />
                <p className="text-android-muted text-sm">
                  {listing ? 'Empty folder or access denied' : 'Navigate to a folder to see its contents'}
                </p>
                {connected && !listing && (
                  <button
                    onClick={() => navigate(path)}
                    className="mt-4 px-4 py-2 bg-android-green text-android-bg rounded-lg text-sm font-medium"
                  >
                    Browse {path}
                  </button>
                )}
              </div>
            )}
          </div>

          <p className="text-xs text-android-muted mt-3">
            Click a folder to navigate · Click a file to view contents in Terminal · File results appear in the <a href="/terminal" className="text-android-blue">Terminal</a>
          </p>
        </div>
      </main>
    </div>
  )
}
