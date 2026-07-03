'use client'
import { useState, useEffect, useCallback } from 'react'
import { Wifi, WifiOff, Shield, Users, Copy, RefreshCw, Trash2, Power, Eye, EyeOff } from 'lucide-react'

interface Device { deviceId: string; deviceName: string; connected: boolean }
interface PortalConfig {
  enabled: boolean; password: string; title: string; message: string
  portalActive: boolean; portalIp: string; portalPort: number
}
interface Session { id: number; clientIp: string; authorizedAt: string }

export default function WifiPortalPage() {
  const [devices, setDevices] = useState<Device[]>([])
  const [selectedId, setSelectedId] = useState('')
  const [config, setConfig] = useState<PortalConfig>({ enabled: false, password: '', title: 'WiFi Login', message: 'Masukkan password untuk terhubung ke internet.', portalActive: false, portalIp: '', portalPort: 0 })
  const [sessions, setSessions] = useState<Session[]>([])
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [showPass, setShowPass] = useState(false)
  const [copied, setCopied] = useState(false)
  const [loadingConfig, setLoadingConfig] = useState(false)

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
      setConfig({ enabled: cfg.enabled ?? false, password: cfg.password ?? '', title: cfg.title ?? 'WiFi Login', message: cfg.message ?? 'Masukkan password untuk terhubung ke internet.', portalActive: cfg.portalActive ?? false, portalIp: cfg.portalIp ?? '', portalPort: cfg.portalPort ?? 0 })
      setSessions(sess.sessions || [])
    } finally { setLoadingConfig(false) }
  }, [])

  useEffect(() => { if (selectedId) loadConfig(selectedId) }, [selectedId, loadConfig])

  async function saveConfig() {
    setSaving(true)
    try {
      await fetch('/api/wifi-portal/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, enabled: config.enabled, password: config.password, title: config.title, message: config.message })
      })
      setSaved(true); setTimeout(() => setSaved(false), 2000)
    } finally { setSaving(false) }
  }

  async function clearSessions() {
    await fetch(`/api/wifi-portal/sessions?deviceId=${selectedId}`, { method: 'DELETE' })
    setSessions([])
  }

  const portalUrl = selectedId ? `https://android-control.vercel.app/portal/${selectedId}` : ''
  const localUrl = config.portalIp && config.portalPort ? `http://${config.portalIp}:${config.portalPort}` : null

  function copyUrl() {
    navigator.clipboard.writeText(portalUrl)
    setCopied(true); setTimeout(() => setCopied(false), 2000)
  }

  const selectedDevice = devices.find(d => d.deviceId === selectedId)

  return (
    <div className="p-4 md:p-6 max-w-3xl mx-auto">
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
            <div className={`w-2.5 h-2.5 rounded-full ${config.portalActive ? 'bg-green-400 shadow-[0_0_8px_rgba(74,222,128,0.6)]' : 'bg-gray-600'}`} />
            <span className="text-sm text-android-text font-medium">
              {config.portalActive ? `Portal Aktif — ${config.portalIp}:${config.portalPort}` : 'Portal Tidak Aktif di Device'}
            </span>
            {config.portalActive && localUrl && (
              <span className="ml-auto text-xs text-android-muted font-mono">{localUrl}</span>
            )}
          </div>

          {/* Toggle + Portal URL */}
          <div className="bg-android-surface border border-android-border rounded-xl p-4 mb-4">
            <div className="flex items-center justify-between mb-4">
              <div>
                <p className="text-sm font-semibold text-android-text">Aktifkan Portal</p>
                <p className="text-xs text-android-muted mt-0.5">Client hotspot diarahkan ke halaman login</p>
              </div>
              <button onClick={() => setConfig(c => ({ ...c, enabled: !c.enabled }))}
                className={`relative w-12 h-6 rounded-full transition-colors ${config.enabled ? 'bg-indigo-500' : 'bg-gray-700'}`}>
                <div className={`absolute top-0.5 w-5 h-5 rounded-full bg-white shadow transition-transform ${config.enabled ? 'translate-x-6' : 'translate-x-0.5'}`} />
              </button>
            </div>

            <label className="text-xs font-semibold text-android-muted uppercase tracking-wider block mb-1.5">URL Portal (share ke client)</label>
            <div className="flex gap-2">
              <input readOnly value={portalUrl} className="flex-1 bg-android-bg border border-android-border rounded-lg px-3 py-2 text-android-muted text-xs font-mono outline-none" />
              <button onClick={copyUrl} className={`px-3 py-2 rounded-lg border text-xs font-medium transition-all ${copied ? 'bg-green-500/20 border-green-500/40 text-green-400' : 'bg-android-bg border-android-border text-android-muted hover:border-indigo-500/40 hover:text-indigo-400'}`}>
                {copied ? '✓' : <Copy size={14} />}
              </button>
            </div>
          </div>

          {/* Config form */}
          <div className="bg-android-surface border border-android-border rounded-xl p-4 mb-4 space-y-4">
            <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider flex items-center gap-2"><Shield size={13}/> Konfigurasi Portal</h3>

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
              <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider flex items-center gap-2"><Users size={13}/> Log Client Terhubung ({sessions.length})</h3>
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