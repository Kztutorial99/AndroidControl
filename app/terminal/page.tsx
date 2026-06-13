'use client'
import { useEffect, useState, useRef, useCallback } from 'react'
import Sidebar from '@/components/Sidebar'
import { Send, Trash2, Circle } from 'lucide-react'

interface HistoryEntry {
  id: string
  command: string
  result: string
  timestamp: string
  exitCode?: number
}

const QUICK_CMDS = [
  'uname -a',
  'whoami',
  'pwd',
  'ls -la',
  'df -h',
  'free -h',
  'top -bn1 | head -20',
  'ip addr',
  'cat /proc/cpuinfo | head -20',
  'ls /storage/emulated/0',
  'ls /sdcard',
  'pm list packages | head -20',
]

export default function TerminalPage() {
  const [connected, setConnected] = useState(false)
  const [input, setInput] = useState('')
  const [history, setHistory] = useState<HistoryEntry[]>([])
  const [sending, setSending] = useState(false)
  const [cmdHistory, setCmdHistory] = useState<string[]>([])
  const [histIdx, setHistIdx] = useState(-1)
  const outputRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  const fetchHistory = useCallback(async () => {
    try {
      const [statusRes, histRes] = await Promise.all([
        fetch('/api/device/heartbeat'),
        fetch('/api/device/result'),
      ])
      const status = await statusRes.json()
      const hist = await histRes.json()
      setConnected(status.connected ?? false)
      setHistory(hist.history ?? [])
    } catch {}
  }, [])

  useEffect(() => {
    fetchHistory()
    const interval = setInterval(fetchHistory, 2500)
    return () => clearInterval(interval)
  }, [fetchHistory])

  useEffect(() => {
    if (outputRef.current) {
      outputRef.current.scrollTop = outputRef.current.scrollHeight
    }
  }, [history])

  const sendCommand = async (cmd?: string) => {
    const command = (cmd ?? input).trim()
    if (!command) return
    setSending(true)
    try {
      await fetch('/api/device/command', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ command }),
      })
      setCmdHistory(prev => [command, ...prev.slice(0, 49)])
      setHistIdx(-1)
      setInput('')
    } finally {
      setSending(false)
      inputRef.current?.focus()
    }
  }

  const clearHistory = async () => {
    await fetch('/api/device/result', { method: 'DELETE' }).catch(() => {})
    setHistory([])
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      sendCommand()
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      const newIdx = Math.min(histIdx + 1, cmdHistory.length - 1)
      setHistIdx(newIdx)
      setInput(cmdHistory[newIdx] ?? '')
    } else if (e.key === 'ArrowDown') {
      e.preventDefault()
      const newIdx = Math.max(histIdx - 1, -1)
      setHistIdx(newIdx)
      setInput(newIdx === -1 ? '' : cmdHistory[newIdx] ?? '')
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} />

      <main className="flex-1 flex flex-col p-6 overflow-hidden">
        <div className="max-w-5xl mx-auto w-full flex flex-col flex-1">
          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-xl font-bold text-white">Terminal</h2>
              <p className="text-android-muted text-sm mt-0.5">Execute shell commands on your Android device</p>
            </div>
            <div className="flex items-center gap-3">
              <div className={`flex items-center gap-2 text-xs font-medium px-3 py-1.5 rounded-full border ${connected ? 'text-android-green border-android-green/30 bg-android-green/10' : 'text-android-red border-android-red/30 bg-android-red/10'}`}>
                <Circle size={7} className={connected ? 'fill-android-green' : 'fill-android-red'} />
                {connected ? 'Online' : 'Offline'}
              </div>
              <button
                onClick={clearHistory}
                className="flex items-center gap-2 px-3 py-1.5 bg-android-surface border border-android-border rounded-lg text-xs text-android-muted hover:text-android-red hover:border-android-red/50 transition-colors"
              >
                <Trash2 size={13} /> Clear
              </button>
            </div>
          </div>

          <div className="flex gap-2 mb-3 flex-wrap">
            {QUICK_CMDS.map(cmd => (
              <button
                key={cmd}
                onClick={() => sendCommand(cmd)}
                disabled={!connected}
                className="px-2.5 py-1 bg-android-surface border border-android-border rounded text-xs text-android-muted hover:text-android-green hover:border-android-green/50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed font-mono"
              >
                {cmd}
              </button>
            ))}
          </div>

          <div
            ref={outputRef}
            className="flex-1 min-h-[300px] max-h-[calc(100vh-320px)] bg-[#0a0c10] border border-android-border rounded-xl p-4 overflow-y-auto terminal-output"
          >
            {history.length === 0 ? (
              <div className="flex flex-col items-center justify-center h-full text-center">
                <p className="text-android-muted text-sm">
                  {connected ? 'Type a command below and press Enter ↵' : 'Connect your Android device to start'}
                </p>
              </div>
            ) : (
              [...history].reverse().map(entry => (
                <div key={entry.id} className="mb-4">
                  <div className="flex items-center gap-2 mb-1">
                    <span className="text-android-green text-xs">$</span>
                    <span className="text-white text-xs font-semibold">{entry.command}</span>
                    <span className="text-android-muted text-xs ml-auto">
                      {new Date(entry.timestamp).toLocaleTimeString()}
                    </span>
                    {entry.exitCode !== undefined && entry.exitCode !== 0 && (
                      <span className="text-android-red text-xs">exit {entry.exitCode}</span>
                    )}
                  </div>
                  <pre className="text-android-text text-xs whitespace-pre-wrap break-all pl-4 border-l border-android-border/50">
                    {entry.result || <span className="text-android-muted italic">(no output)</span>}
                  </pre>
                </div>
              ))
            )}
          </div>

          <div className="mt-3 flex gap-2">
            <div className="flex-1 flex items-center bg-[#0a0c10] border border-android-border rounded-xl px-4 py-3 gap-2 focus-within:border-android-green/50 transition-colors">
              <span className="text-android-green font-mono text-sm select-none">$</span>
              <input
                ref={inputRef}
                type="text"
                value={input}
                onChange={e => setInput(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder={connected ? 'Enter command… (↑↓ for history)' : 'No device connected'}
                disabled={!connected || sending}
                className="flex-1 bg-transparent text-android-text font-mono text-sm outline-none placeholder:text-android-muted/50 disabled:opacity-50"
                autoComplete="off"
                spellCheck={false}
              />
            </div>
            <button
              onClick={() => sendCommand()}
              disabled={!connected || sending || !input.trim()}
              className="px-4 py-3 bg-android-green text-android-bg rounded-xl hover:bg-android-green/80 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            >
              <Send size={16} />
            </button>
          </div>
          <p className="text-xs text-android-muted mt-2">↑↓ arrow keys to navigate command history · Enter to send</p>
        </div>
      </main>
    </div>
  )
}
