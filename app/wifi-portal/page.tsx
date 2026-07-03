'use client'
import { useState, useEffect, useCallback } from 'react'
import { Wifi, WifiOff, Shield, Users, Copy, RefreshCw, Trash2, Power, Eye, EyeOff, QrCode, Info } from 'lucide-react'

interface Device { deviceId: string; deviceName: string; connected: boolean }
interface PortalConfig {
  enabled: boolean; password: string; title: string; message: string
  portalActive: boolean; portalIp: string; portalPort: number
}
interface Session { id: number; clientIp: string; authorizedAt: string }

export default function WifiPortalPage() {
  const [devices, setDevices]           = useState<Device[]>([])
  const [selectedId, setSelectedId]     = useState('')
  const [config, setConfig]             = useState<PortalConfig>({
    enabled: false, password: '', title: 'WiFi Login',
    message: 'Masukkan password untuk terhubung ke internet.',
    portalActive: false, portalIp: '', portalPort: 0
  })
  const [sessions, setSessions]         = useState<Session[]>([])
  const [saving, setSaving]             = useState(false)
  const [saved, setSaved]               = useState(false)
  const [showPass, setShowPass]         = useState(false)
  const [copied, setCopied]             = useState(false)
  const [loadingConfig, setLoadingConfig] = useState(false)
  const [sendingCmd, setSendingCmd]     = useState<'start' | 'stop' | null>(null)
  const [cmdFeedback, setCmdFeedback]   = useState<{ ok: boolean; msg: string } | null>(null)
  const [showQr, setShowQr]             = useState(false)

  useEffect(() => {
    fetch('/api/devices').then(r => r.json()).then(d => {
      setDevices(d.devices || [])
      if (d.devices?.length > 0) setSelectedId(d.devices[0].deviceId)
    })
  }, [])

  const loadConfig = useCallback(async (id: string) => {
    if (!id) return
    setLoadingConfig(true)
    try {
      const [cfg, sess] = await Promise.all([
        fetch(`/api/wifi-portal/config?deviceId=${id}`).then(r => r.json()),
        fetch(`/api/wifi-portal/sessions?deviceId=${id}`).then(r => r.json())
      ])
      setConfig({
        enabled: cfg.enabled ?? false, password: cfg.password ?? '',
        title: cfg.title ?? 'WiFi Login',
        message: cfg.message ?? 'Masukkan password untuk terhubung ke internet.',
        portalActive: cfg.portalActive ?? false,
        portalIp: cfg.portalIp ?? '', portalPort: cfg.portalPort ?? 0
      })
      setSessions(sess.sessions || [])
    } finally { setLoadingConfig(false) }
  }, [])

  useEffect(() => { if (selectedId) loadConfig(selectedId) }, [selectedId, loadConfig])

  // Auto-refresh status setiap 10 detik saat portal aktif
  useEffect(() => {
    if (!config.portalActive || !selectedId) return
    const t = setInterval(() => loadConfig(selectedId), 10000)
    return () => clearInterval(t)
  }, [config.portalActive, selectedId, loadConfig])

  async function saveConfig() {
    setSaving(true)
    try {
      await fetch('/api/wifi-portal/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          deviceId: selectedId, enabled: config.enabled,
          password: config.password, title: config.title, message: config.message
        })
      })
      setSaved(true); setTimeout(() => setSaved(false), 2000)
    } finally { setSaving(false) }
  }

  async function sendPortalCommand(action: 'start' | 'stop') {
    if (!selectedId) return
    setSendingCmd(action)
    setCmdFeedback(null)
    try {
      const res = await fetch('/api/device/command', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, command: `wifi_portal_${action}` })
      })
      const data = await res.json()
      if (res.ok) {
        setCmdFeedback({ ok: true, msg: action === 'start'
          ? '✅ Perintah START dikirim ke device. Tunggu 3–5 detik...'
          : '✅ Perintah STOP dikirim ke device.' })
        setTimeout(() => { loadConfig(selectedId); setCmdFeedback(null) }, 4000)
      } else {
        setCmdFeedback({ ok: false, msg: `❌ Gagal: ${data.error ?? 'Device offline?'}` })
      }
    } catch (e: any) {
      setCmdFeedback({ ok: false, msg: `❌ Error: ${e.message}` })
    } finally { setSendingCmd(null) }
  }

  async function clearSessions() {
    await fetch(`/api/wifi-portal/sessions?deviceId=${selectedId}`, { method: 'DELETE' })
    setSessions([])
  }

  const vercelPortalUrl = selectedId ? `https://android-control.vercel.app/portal/${selectedId}` : ''
  const localUrl = config.portalIp && config.portalPort
    ? `http://${config.portalIp}${config.portalPort !== 80 ? `:${config.portalPort}` : ''}/`
    : null

  // Deteksi mode berdasarkan port yang aktif
  const portalMode = config.portalActive
    ? config.portalPort === 80
      ? { label: 'No-Root Mode ✅', color: 'text-green-400', desc: 'Port 80 — client otomatis diarahkan saat buka browser' }
      : config.portalPort === 8080
        ? { label: 'Root/Fallback Mode', color: 'text-yellow-400', desc: 'Port 8080 — client buka URL manual atau scan QR code' }
        : { label: 'Aktif', color: 'text-green-400', desc: `Port ${config.portalPort}` }
    : null

  const qrUrl = `https://api.qrserver.com/v1/create-qr-code/?size=200x200&bgcolor=1a1a1a&color=4ade80&data=${encodeURIComponent(localUrl ?? vercelPortalUrl)}`

  function copyUrl(url: string) {
    navigator.clipboard.writeText(url)
    setCopied(true); setTimeout(() => setCopied(false), 2000)
  }

  return (
    <div className="p-4 md:p-6 max-w-3xl mx-auto">
      {/* Header */}
      <div className="flex items-center gap-3 mb-6">
        <div className="w-10 h-10 rounded-xl bg-indigo-500/20 border border-indigo-500/30 flex items-center justify-center">
          <Wifi size={20} className="text-indigo-400" />
        </div>
        <div>
          <h1 className="text-lg font-bold text-android-text">WiFi Portal</h1>
          <p className="text-xs text-android-muted">Captive portal — redirect client hotspot ke halaman login</p>
        </div>
      </div>

      {/* Device selector */}
      <div className="bg-android-surface border border-android-border rounded-xl p-4 mb-4">
        <label className="text-xs font-semibold text-android-muted uppercase tracking-wider block mb-2">Pilih Device</label>
        {devices.length === 0 ? (
          <p className="text-android-muted text-sm">Tidak ada device terhubung.</p>
        ) : (
          <select value={selectedId} onChange={e => setSelectedId(e.target.value)}
            className="w-full bg-android-bg border border-android-border rounded-lg px-3 py-2 text-android-text text-sm outline-none focus:border-indigo-500/60">
            {devices.map(d => (
              <option key={d.deviceId} value={d.deviceId}>{d.deviceName} {d.connected ? '🟢' : '⚫'}</option>
            ))}
          </select>
        )}
      </div>

      {selectedId && !loadingConfig && (
        <>
          {/* Status bar */}
          <div className="flex items-center gap-3 mb-4 p-3 bg-android-surface border border-android-border rounded-xl">
            <div className={`w-2.5 h-2.5 rounded-full shrink-0 ${config.portalActive ? 'bg-green-400 shadow-[0_0_8px_rgba(74,222,128,0.6)] animate-pulse' : 'bg-gray-600'}`} />
            <div className="flex-1 min-w-0">
              <p className="text-sm text-android-text font-medium">
                {config.portalActive ? 'Portal Aktif di Device' : 'Portal Tidak Aktif'}
              </p>
              {portalMode && (
                <p className={`text-xs ${portalMode.color}`}>{portalMode.label} — {portalMode.desc}</p>
              )}
            </div>
            {config.portalActive && (
              <button onClick={() => loadConfig(selectedId)} className="p-1.5 text-android-muted hover:text-white">
                <RefreshCw size={13} />
              </button>
            )}
          </div>

          {/* ── INFO: Cara Kerja No-Root ── */}
          <div className="bg-android-surface border border-android-border rounded-xl p-4 mb-4">
            <div className="flex items-center gap-2 mb-3">
              <Info size={13} className="text-indigo-400 shrink-0" />
              <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider">Cara Kerja (No-Root & Root)</h3>
            </div>
            <div className="space-y-2 text-[11px] text-android-muted">
              <div className="flex gap-2"><span className="text-green-400 shrink-0">✅</span><span><span className="text-green-400 font-medium">No-Root (Port 80):</span> Beberapa ROM (MIUI, One UI) — saat device jadi hotspot, app bisa bind port 80. Semua HTTP client otomatis diarahkan ke portal. <span className="text-android-text">Tidak perlu root.</span></span></div>
              <div className="flex gap-2"><span className="text-blue-400 shrink-0">🔐</span><span><span className="text-blue-400 font-medium">Root (iptables):</span> Jika port 80 gagal, app coba redirect via iptables. Butuh akses su/root.</span></div>
              <div className="flex gap-2"><span className="text-yellow-400 shrink-0">⚠️</span><span><span className="text-yellow-400 font-medium">Fallback (Port 8080):</span> Jika keduanya gagal, portal jalan di port 8080. Client harus buka URL/scan QR manual.</span></div>
            </div>
          </div>

          {/* ── START / STOP COMMAND ── */}
          <div className="bg-android-surface border border-android-border rounded-xl p-4 mb-4">
            <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider mb-3 flex items-center gap-2">
              <Power size={13} /> Kontrol Portal di Device
            </h3>
            <div className="flex gap-3 mb-2">
              <button
                onClick={() => sendPortalCommand('start')}
                disabled={sendingCmd !== null || config.portalActive}
                className={`flex-1 flex items-center justify-center gap-2 py-2.5 rounded-lg text-sm font-semibold border transition-all disabled:opacity-50 disabled:cursor-not-allowed
                  ${config.portalActive ? 'bg-green-500/10 border-green-500/30 text-green-400' : 'bg-indigo-500/20 border-indigo-500/40 text-indigo-400 hover:bg-indigo-500/30'}`}>
                <Wifi size={15} />
                {sendingCmd === 'start' ? 'Mengirim...' : config.portalActive ? 'Portal Berjalan' : 'Start Portal'}
              </button>
              <button
                onClick={() => sendPortalCommand('stop')}
                disabled={sendingCmd !== null || !config.portalActive}
                className="flex-1 flex items-center justify-center gap-2 py-2.5 rounded-lg text-sm font-semibold border border-red-500/40 bg-red-500/10 text-red-400 hover:bg-red-500/20 transition-all disabled:opacity-50 disabled:cursor-not-allowed">
                <WifiOff size={15} />
                {sendingCmd === 'stop' ? 'Mengirim...' : 'Stop Portal'}
              </button>
            </div>
            {cmdFeedback && (
              <p className={`text-xs font-medium text-center py-1 ${cmdFeedback.ok ? 'text-green-400' : 'text-red-400'}`}>{cmdFeedback.msg}</p>
            )}
            <p className="text-[11px] text-android-muted">⚠️ Aktifkan hotspot di device terlebih dahulu sebelum Start Portal.</p>
          </div>

          {/* ── URL PORTAL + QR CODE ── */}
          <div className="bg-android-surface border border-android-border rounded-xl p-4 mb-4">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider flex items-center gap-2">
                <QrCode size={13} /> URL Portal untuk Client
              </h3>
              <button onClick={() => setShowQr(v => !v)}
                className={`text-xs px-2.5 py-1 rounded-lg border transition-colors ${showQr ? 'bg-indigo-500/20 border-indigo-500/40 text-indigo-400' : 'bg-android-bg border-android-border text-android-muted'}`}>
                {showQr ? 'Sembunyikan QR' : 'Tampilkan QR'}
              </button>
            </div>

            {/* Local URL (saat portal aktif) */}
            {localUrl && (
              <div className="mb-3">
                <label className="text-[11px] text-android-muted block mb-1">
                  🌐 URL Lokal (untuk client di hotspot yang sama) — {config.portalPort === 80 ? 'auto-redirect aktif' : 'share manual'}
                </label>
                <div className="flex gap-2">
                  <input readOnly value={localUrl}
                    className="flex-1 bg-android-bg border border-android-border rounded-lg px-3 py-2 text-green-400 text-xs font-mono outline-none" />
                  <button onClick={() => copyUrl(localUrl)}
                    className={`px-3 py-2 rounded-lg border text-xs font-medium transition-all ${copied ? 'bg-green-500/20 border-green-500/40 text-green-400' : 'bg-android-bg border-android-border text-android-muted hover:border-indigo-500/40 hover:text-indigo-400'}`}>
                    {copied ? '✓' : <Copy size={14} />}
                  </button>
                </div>
              </div>
            )}

            {/* Vercel URL */}
            <div>
              <label className="text-[11px] text-android-muted block mb-1">
                🔗 URL Vercel (bisa diakses dari mana saja via internet)
              </label>
              <div className="flex gap-2">
                <input readOnly value={vercelPortalUrl}
                  className="flex-1 bg-android-bg border border-android-border rounded-lg px-3 py-2 text-android-muted text-xs font-mono outline-none" />
                <button onClick={() => copyUrl(vercelPortalUrl)}
                  className={`px-3 py-2 rounded-lg border text-xs font-medium transition-all ${copied ? 'bg-green-500/20 border-green-500/40 text-green-400' : 'bg-android-bg border-android-border text-android-muted hover:border-indigo-500/40 hover:text-indigo-400'}`}>
                  {copied ? '✓' : <Copy size={14} />}
                </button>
              </div>
            </div>

            {/* QR Code */}
            {showQr && (
              <div className="mt-4 flex flex-col items-center gap-2">
                <p className="text-[11px] text-android-muted">
                  Client scan QR ini untuk buka portal ({localUrl ? 'URL lokal' : 'URL Vercel'})
                </p>
                <div className="p-3 bg-[#1a1a1a] rounded-xl border border-android-border">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={qrUrl} alt="QR Portal" width={180} height={180}
                    className="rounded-lg" />
                </div>
                <p className="text-[10px] text-android-muted text-center max-w-[200px]">
                  {localUrl
                    ? `Hanya bekerja saat client terhubung ke hotspot`
                    : `Bisa diakses dari mana saja via internet`}
                </p>
              </div>
            )}
          </div>

          {/* Config form */}
          <div className="bg-android-surface border border-android-border rounded-xl p-4 mb-4 space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider flex items-center gap-2"><Shield size={13}/> Konfigurasi Portal</h3>
              <div className="flex items-center gap-2">
                <span className="text-xs text-android-muted">Password protection</span>
                <button onClick={() => setConfig(c => ({ ...c, enabled: !c.enabled }))}
                  className={`relative w-10 h-5 rounded-full transition-colors ${config.enabled ? 'bg-indigo-500' : 'bg-gray-700'}`}>
                  <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform ${config.enabled ? 'translate-x-5' : 'translate-x-0.5'}`} />
                </button>
              </div>
            </div>

            {config.enabled && (
              <div>
                <label className="text-xs text-android-muted block mb-1.5">Password WiFi</label>
                <div className="relative">
                  <input type={showPass ? 'text' : 'password'} value={config.password}
                    onChange={e => setConfig(c => ({ ...c, password: e.target.value }))}
                    placeholder="Set password untuk client..."
                    className="w-full bg-android-bg border border-android-border rounded-lg px-3 py-2 pr-10 text-android-text text-sm outline-none focus:border-indigo-500/60" />
                  <button type="button" onClick={() => setShowPass(s => !s)}
                    className="absolute right-2 top-1/2 -translate-y-1/2 text-android-muted hover:text-android-text">
                    {showPass ? <EyeOff size={15}/> : <Eye size={15}/>}
                  </button>
                </div>
              </div>
            )}

            <div>
              <label className="text-xs text-android-muted block mb-1.5">Judul Halaman</label>
              <input value={config.title} onChange={e => setConfig(c => ({ ...c, title: e.target.value }))}
                placeholder="WiFi Login"
                className="w-full bg-android-bg border border-android-border rounded-lg px-3 py-2 text-android-text text-sm outline-none focus:border-indigo-500/60" />
            </div>

            <div>
              <label className="text-xs text-android-muted block mb-1.5">Pesan untuk Client</label>
              <textarea value={config.message} onChange={e => setConfig(c => ({ ...c, message: e.target.value }))}
                rows={2} placeholder="Masukkan password untuk terhubung ke internet."
                className="w-full bg-android-bg border border-android-border rounded-lg px-3 py-2 text-android-text text-sm outline-none focus:border-indigo-500/60 resize-none" />
            </div>

            <button onClick={saveConfig} disabled={saving}
              className={`w-full py-2.5 rounded-lg text-sm font-semibold transition-all ${saved ? 'bg-green-500/20 border border-green-500/40 text-green-400' : 'bg-indigo-500/20 border border-indigo-500/40 text-indigo-400 hover:bg-indigo-500/30'}`}>
              {saved ? '✓ Tersimpan' : saving ? 'Menyimpan...' : 'Simpan Konfigurasi'}
            </button>
          </div>

          {/* Sessions */}
          <div className="bg-android-surface border border-android-border rounded-xl p-4">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider flex items-center gap-2">
                <Users size={13}/> Log Client Terhubung ({sessions.length})
              </h3>
              <div className="flex gap-2">
                <button onClick={() => loadConfig(selectedId)} className="text-android-muted hover:text-android-text p-1"><RefreshCw size={13}/></button>
                {sessions.length > 0 && (
                  <button onClick={clearSessions} className="text-android-muted hover:text-red-400 p-1"><Trash2 size={13}/></button>
                )}
              </div>
            </div>
            {sessions.length === 0 ? (
              <p className="text-android-muted text-xs text-center py-6">Belum ada client yang login melalui portal.</p>
            ) : (
              <div className="space-y-1.5 max-h-64 overflow-y-auto">
                {sessions.map(s => (
                  <div key={s.id} className="flex items-center justify-between py-2 px-3 bg-android-bg rounded-lg border border-android-border/50">
                    <span className="text-sm font-mono text-android-text">{s.clientIp}</span>
                    <span className="text-xs text-android-muted">{new Date(s.authorizedAt).toLocaleString('id-ID')}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </>
      )}

      {loadingConfig && (
        <div className="flex items-center justify-center py-12">
          <div className="w-6 h-6 border-2 border-indigo-500/30 border-t-indigo-500 rounded-full animate-spin" />
        </div>
      )}
    </div>
  )
}
