'use client'
import { useState, useMemo } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import {
  Smartphone, Globe, Shield, Zap, QrCode,
  ChevronDown, ChevronRight, Copy, CheckCheck, ExternalLink,
  ShieldCheck, Wifi, Download, RefreshCw, Lock
} from 'lucide-react'

function Step({ n, title, badge, children }: { n: number; title: string; badge?: React.ReactNode; children: React.ReactNode }) {
  const [open, setOpen] = useState(n <= 2)
  return (
    <div className="bg-android-surface border border-android-border rounded-xl overflow-hidden mb-2.5">
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center gap-3 px-4 py-3.5 text-left hover:bg-white/5 transition-colors"
      >
        <div className="w-6 h-6 rounded-full bg-android-green/20 text-android-green text-xs font-bold flex items-center justify-center shrink-0">
          {n}
        </div>
        <span className="flex-1 font-semibold text-white text-sm">{title}</span>
        {badge && <span className="mr-1">{badge}</span>}
        {open
          ? <ChevronDown size={15} className="text-android-muted shrink-0" />
          : <ChevronRight size={15} className="text-android-muted shrink-0" />}
      </button>
      {open && <div className="px-4 pb-4 text-sm text-android-muted space-y-2.5">{children}</div>}
    </div>
  )
}

function Code({ children, copy = true }: { children: string; copy?: boolean }) {
  const [copied, setCopied] = useState(false)
  const doCopy = () => {
    navigator.clipboard.writeText(children)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }
  return (
    <div className="group relative bg-[#0a0c10] border border-android-border rounded-lg p-3 font-mono text-xs text-android-green overflow-x-auto">
      <pre className="pr-8 whitespace-pre-wrap break-all">{children}</pre>
      {copy && (
        <button
          onClick={doCopy}
          className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 p-1.5 rounded hover:bg-android-border transition-all"
        >
          {copied ? <CheckCheck size={12} className="text-android-green" /> : <Copy size={12} className="text-android-muted" />}
        </button>
      )}
    </div>
  )
}

function Tag({ color, label }: { color: 'green' | 'yellow' | 'red' | 'blue'; label: string }) {
  const map = {
    green: 'bg-android-green/15 text-android-green border-android-green/30',
    yellow: 'bg-android-yellow/15 text-android-yellow border-android-yellow/30',
    red: 'bg-android-red/15 text-android-red border-android-red/30',
    blue: 'bg-android-blue/15 text-android-blue border-android-border',
  }
  return <span className={`text-xs px-2 py-0.5 rounded-full border font-medium ${map[color]}`}>{label}</span>
}

function QrGenerator() {
  const [apkUrl, setApkUrl] = useState('')
  const [wifiSsid, setWifiSsid] = useState('')
  const [wifiPass, setWifiPass] = useState('')
  const [wifiSec, setWifiSec] = useState<'WPA' | 'WEP' | 'NONE'>('WPA')
  const [showQr, setShowQr] = useState(false)

  const provisioning = useMemo(() => {
    const obj: Record<string, unknown> = {
      'android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME':
        'com.android.services/.AppDeviceAdminReceiver',
      'android.app.extra.PROVISIONING_SKIP_ENCRYPTION': true,
      'android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED': true,
    }
    if (apkUrl.trim()) {
      obj['android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION'] = apkUrl.trim()
    }
    if (wifiSsid.trim()) {
      obj['android.app.extra.PROVISIONING_WIFI_SSID'] = wifiSsid.trim()
      obj['android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE'] = wifiSec
      if (wifiPass.trim()) obj['android.app.extra.PROVISIONING_WIFI_PASSWORD'] = wifiPass.trim()
    }
    return JSON.stringify(obj, null, 2)
  }, [apkUrl, wifiSsid, wifiPass, wifiSec])

  const qrUrl = useMemo(() => {
    const encoded = encodeURIComponent(JSON.stringify(JSON.parse(provisioning)))
    return `https://api.qrserver.com/v1/create-qr-code/?size=280x280&data=${encoded}&bgcolor=0d1117&color=00c853&margin=10`
  }, [provisioning])

  return (
    <div className="space-y-3">
      <p className="text-xs text-android-muted">Isi form → generate QR → scan saat setup HP baru → app jadi Device Owner otomatis.</p>

      {/* Form */}
      <div className="grid grid-cols-1 gap-2">
        <div>
          <label className="text-[10px] font-mono text-android-green/70 uppercase tracking-wider">APK Download URL</label>
          <input
            value={apkUrl}
            onChange={e => setApkUrl(e.target.value)}
            placeholder="https://your-server.com/app.apk"
            className="mt-1 w-full bg-[#0a0c10] border border-android-border rounded-lg px-3 py-2 text-xs text-android-text font-mono placeholder:text-android-muted/40 focus:outline-none focus:border-android-green/50"
          />
          <p className="text-[10px] text-android-muted mt-0.5">Link langsung ke APK kamu (GitHub Release, Drive, server sendiri)</p>
        </div>

        <div className="grid grid-cols-2 gap-2">
          <div>
            <label className="text-[10px] font-mono text-android-green/70 uppercase tracking-wider">WiFi SSID</label>
            <input
              value={wifiSsid}
              onChange={e => setWifiSsid(e.target.value)}
              placeholder="NamaWiFi"
              className="mt-1 w-full bg-[#0a0c10] border border-android-border rounded-lg px-3 py-2 text-xs text-android-text font-mono placeholder:text-android-muted/40 focus:outline-none focus:border-android-green/50"
            />
          </div>
          <div>
            <label className="text-[10px] font-mono text-android-green/70 uppercase tracking-wider">WiFi Password</label>
            <input
              value={wifiPass}
              onChange={e => setWifiPass(e.target.value)}
              placeholder="password123"
              type="password"
              className="mt-1 w-full bg-[#0a0c10] border border-android-border rounded-lg px-3 py-2 text-xs text-android-text font-mono placeholder:text-android-muted/40 focus:outline-none focus:border-android-green/50"
            />
          </div>
        </div>

        <div>
          <label className="text-[10px] font-mono text-android-green/70 uppercase tracking-wider">Tipe Keamanan WiFi</label>
          <div className="flex gap-2 mt-1">
            {(['WPA', 'WEP', 'NONE'] as const).map(t => (
              <button
                key={t}
                onClick={() => setWifiSec(t)}
                className={`px-3 py-1.5 rounded text-xs font-mono font-bold border transition-colors ${
                  wifiSec === t
                    ? 'bg-android-green/20 border-android-green/50 text-android-green'
                    : 'bg-android-bg border-android-border text-android-muted hover:text-android-text'
                }`}
              >{t}</button>
            ))}
          </div>
        </div>
      </div>

      {/* Generate button */}
      <button
        onClick={() => setShowQr(true)}
        className="w-full py-2.5 rounded-lg text-sm font-semibold bg-android-green/10 border border-android-green/40 text-android-green hover:bg-android-green/20 transition-colors flex items-center justify-center gap-2"
      >
        <QrCode size={15} />
        Generate QR Code
      </button>

      {/* QR Display */}
      {showQr && (
        <div className="flex flex-col items-center gap-3 p-4 bg-[#0d1117] border border-android-green/30 rounded-xl">
          <div className="text-xs text-android-green font-mono font-bold tracking-widest">SCAN UNTUK PROVISIONING</div>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={qrUrl}
            alt="QR Provisioning"
            width={200}
            height={200}
            className="rounded-lg border border-android-green/20"
          />
          <div className="text-[10px] text-android-muted text-center space-y-1">
            <p>1. Factory reset HP target</p>
            <p>2. Tap layar welcome <strong className="text-android-text">6x</strong></p>
            <p>3. Scan QR ini → HP download APK → <span className="text-android-green">jadi Device Owner!</span></p>
          </div>
          <button
            onClick={() => setShowQr(false)}
            className="text-[10px] text-android-muted hover:text-android-text flex items-center gap-1"
          >
            <RefreshCw size={10} /> Ubah & Generate Ulang
          </button>
        </div>
      )}

      {/* JSON Preview */}
      <details className="group">
        <summary className="text-[10px] font-mono text-android-muted cursor-pointer hover:text-android-text select-none">
          ▸ Lihat JSON provisioning
        </summary>
        <div className="mt-2">
          <Code copy={true}>{provisioning}</Code>
        </div>
      </details>
    </div>
  )
}

export default function SetupPage() {
  const { devices, selectedId, setSelectedId, connected } = useDevice()

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />
      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-2xl mx-auto px-4 py-4 md:py-6">

          <div className="mb-5">
            <h2 className="text-lg md:text-xl font-bold text-white">Setup Guide</h2>
            <p className="text-android-muted text-xs mt-1">
              Hubungkan Android ke dashboard — termasuk Device Owner provisioning.
            </p>
          </div>

          {/* Overview cards */}
          <div className="grid grid-cols-4 gap-2 mb-5">
            {[
              { icon: <Globe size={15} />, label: 'Web Server', sub: 'Vercel', color: 'text-android-blue' },
              { icon: <Smartphone size={15} />, label: 'APK Client', sub: 'Android', color: 'text-android-green' },
              { icon: <Shield size={15} />, label: 'Device Owner', sub: 'QR / ADB', color: 'text-android-yellow' },
              { icon: <Lock size={15} />, label: 'Block Uninstall', sub: 'Dashboard', color: 'text-android-red' },
            ].map(({ icon, label, sub, color }) => (
              <div key={label} className="bg-android-surface border border-android-border rounded-xl p-2.5 text-center">
                <div className={`${color} mx-auto mb-1 flex justify-center`}>{icon}</div>
                <p className="text-white text-[11px] font-semibold">{label}</p>
                <p className="text-android-muted text-[9px]">{sub}</p>
              </div>
            ))}
          </div>

          {/* ── DEVICE OWNER — QR PROVISIONING ── */}
          <div className="bg-android-surface border border-android-green/30 rounded-xl overflow-hidden mb-3">
            <div className="flex items-center gap-3 px-4 py-3.5 bg-android-green/5 border-b border-android-green/20">
              <div className="p-1.5 rounded-lg bg-android-green/20">
                <ShieldCheck size={15} className="text-android-green" />
              </div>
              <div className="flex-1">
                <p className="text-sm font-bold text-android-green">Device Owner via QR Code</p>
                <p className="text-[10px] text-android-muted mt-0.5">Cara TERKUAT — Block Uninstall permanen tanpa root, tanpa ADB</p>
              </div>
              <Tag color="green" label="DIREKOMENDASIKAN" />
            </div>
            <div className="px-4 py-4 space-y-3">

              {/* How it works */}
              <div className="grid grid-cols-3 gap-2 text-center">
                {[
                  { icon: <RefreshCw size={14} />, label: 'Factory Reset', sub: 'HP target', c: 'text-android-yellow' },
                  { icon: <QrCode size={14} />, label: 'Scan QR', sub: 'Di setup wizard', c: 'text-android-blue' },
                  { icon: <ShieldCheck size={14} />, label: 'Device Owner', sub: 'Aktif permanen', c: 'text-android-green' },
                ].map(({ icon, label, sub, c }) => (
                  <div key={label} className="bg-android-bg border border-android-border rounded-lg p-2.5">
                    <div className={`${c} flex justify-center mb-1`}>{icon}</div>
                    <p className={`text-[11px] font-semibold ${c}`}>{label}</p>
                    <p className="text-[9px] text-android-muted">{sub}</p>
                  </div>
                ))}
              </div>

              {/* QR Generator */}
              <QrGenerator />

              {/* What Device Owner gives */}
              <div className="p-3 bg-android-green/5 border border-android-green/20 rounded-lg">
                <p className="text-[10px] font-bold text-android-green mb-2 flex items-center gap-1.5">
                  <ShieldCheck size={11} /> Setelah Device Owner aktif, di dashboard kamu bisa:
                </p>
                <div className="grid grid-cols-2 gap-1 text-[10px] text-android-muted">
                  {[
                    '✅ Block Uninstall semua app',
                    '✅ Disable tombol Safe Mode',
                    '✅ Block Factory Reset',
                    '✅ Force Stop grayed out',
                    '✅ Block install app lain',
                    '✅ Kiosk mode (lock task)',
                  ].map(t => <span key={t}>{t}</span>)}
                </div>
              </div>
            </div>
          </div>

          {/* ── ADB Method ── */}
          <div className="bg-android-surface border border-android-border rounded-xl overflow-hidden mb-3">
            <div className="flex items-center gap-3 px-4 py-3.5 border-b border-android-border">
              <div className="p-1.5 rounded-lg bg-android-yellow/10">
                <Zap size={15} className="text-android-yellow" />
              </div>
              <div className="flex-1">
                <p className="text-sm font-bold text-white">Device Owner via ADB</p>
                <p className="text-[10px] text-android-muted mt-0.5">One-time setup, tanpa factory reset — butuh PC + kabel</p>
              </div>
              <Tag color="yellow" label="PC REQUIRED" />
            </div>
            <div className="px-4 py-4 space-y-2.5 text-xs text-android-muted">
              <ol className="list-decimal list-inside space-y-2 text-android-text">
                <li>Pastikan <strong>tidak ada Google Account</strong> di HP (atau baru factory reset)</li>
                <li>Enable <strong>USB Debugging</strong> di Developer Options</li>
                <li>Sambungkan HP ke PC via kabel USB</li>
                <li>Jalankan di terminal PC:</li>
              </ol>
              <Code>adb shell dpm set-device-owner com.android.services/.AppDeviceAdminReceiver</Code>
              <div className="p-2.5 bg-android-green/10 border border-android-green/30 rounded-lg text-android-green text-[11px]">
                ✅ Output: <code>Active admin component set</code> → Device Owner aktif!
              </div>
              <p className="text-[10px]">Setelah aktif, buka dashboard → Kontrol Jarak Jauh → <strong>Block Uninstall</strong> → toggle ON.</p>
            </div>
          </div>

          {/* ── INSTALL APK ── */}
          <Step n={1} title="Download & Install APK">
            <p className="font-medium text-android-text text-xs">Via GitHub Actions <Tag color="green" label="Direkomendasikan" /></p>
            <ol className="list-decimal list-inside space-y-1 mt-1 text-xs text-android-text">
              <li>Push code ke GitHub → tab <strong>Actions</strong> auto-jalan</li>
              <li>Klik workflow run terbaru → <strong>Artifacts</strong></li>
              <li>Download <code className="bg-android-border px-1 rounded text-[10px]">AndroidConnector-debug-xxx.zip</code></li>
              <li>Extract APK → transfer ke HP target</li>
              <li>HP: aktifkan <strong>Install Unknown Apps</strong> untuk file manager</li>
              <li>Tap APK untuk install</li>
            </ol>
            <div className="p-3 bg-android-surface border border-android-border rounded-lg text-xs">
              <p className="text-android-text font-medium mb-1 flex items-center gap-1.5">
                <Download size={11} /> Aktifkan Unknown Sources di Vivo:
              </p>
              <p className="text-android-muted">Settings → More Settings → Install apps from external sources → aktifkan untuk file manager</p>
            </div>
          </Step>

          {/* ── CONNECT ── */}
          <Step n={2} title="Hubungkan APK ke Dashboard">
            <ol className="list-decimal list-inside space-y-2 text-android-text text-xs">
              <li>Buka app <strong>AndroidConnector</strong></li>
              <li>Izinkan <strong>All Files Access</strong></li>
              <li>Masukkan <strong>Server URL</strong>:<Code copy={false}>https://nama-app.vercel.app</Code></li>
              <li>Masukkan <strong>Device Token</strong>:<Code copy={false}>vivo-secret-2025</Code></li>
              <li>Tap <strong>CONNECT</strong> → status hijau</li>
            </ol>
            <div className="p-3 bg-android-green/10 border border-android-green/30 rounded-lg text-android-green text-xs">
              ✅ Buka tab Dashboard — info device muncul dalam 5 detik!
            </div>
          </Step>

          {/* ── SHIZUKU ── */}
          <Step n={3} title="Aktifkan Shizuku (Opsional)" badge={<Tag color="blue" label="Android 11+" />}>
            <div className="flex items-start gap-2">
              <Wifi size={14} className="text-android-blue shrink-0 mt-0.5" />
              <p className="text-xs">Wireless Debugging Android 11+ — ADB level tanpa PC/root. Cukup sekali pairing.</p>
            </div>
            <ol className="list-decimal list-inside space-y-1 mt-1 text-xs text-android-text">
              <li>Settings → About Phone → tap <strong>Build Number</strong> 7x</li>
              <li>Developer Options → aktifkan <strong>Wireless Debugging</strong></li>
              <li>Buka <strong>Shizuku</strong> → Pairing by wireless debugging</li>
              <li>Dev Options → Wireless Debugging → <strong>Pair device with pairing code</strong></li>
              <li>Masukkan kode 6 digit → Start</li>
            </ol>
            <div className="grid grid-cols-2 gap-2 text-xs">
              <div className="p-2.5 bg-android-surface border border-android-border rounded-lg">
                <p className="text-android-green font-medium mb-1">Dengan Shizuku ✅</p>
                <ul className="space-y-0.5 text-android-muted text-[11px]">
                  <li>• Grant izin diam-diam</li>
                  <li>• Baca /data/system/</li>
                  <li>• Install app silent</li>
                </ul>
              </div>
              <div className="p-2.5 bg-android-surface border border-android-border rounded-lg">
                <p className="text-android-yellow font-medium mb-1">Tanpa Shizuku ⚠️</p>
                <ul className="space-y-0.5 text-android-muted text-[11px]">
                  <li>• /storage/** full R/W</li>
                  <li>• Info device lengkap</li>
                  <li>• Semua fitur remote!</li>
                </ul>
              </div>
            </div>
          </Step>

          {/* Quick Commands */}
          <div className="mt-4 bg-android-surface border border-android-border rounded-xl p-4">
            <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider mb-3 flex items-center gap-2">
              <Zap size={13} /> Quick Terminal Commands
            </h3>
            <div className="space-y-2 text-xs">
              {[
                { cmd: 'ls_json:/storage/emulated/0', desc: 'Browse internal storage' },
                { cmd: 'shell:df -h', desc: 'Disk usage' },
                { cmd: 'device_info', desc: 'Full device JSON info' },
                { cmd: 'block_uninstall:true', desc: 'Block semua uninstall' },
                { cmd: 'block_uninstall:false', desc: 'Lepas proteksi uninstall' },
              ].map(({ cmd, desc }) => (
                <div key={cmd} className="bg-android-bg rounded-lg p-2.5 border border-android-border/50 flex items-center gap-3">
                  <code className="text-android-green font-mono text-[11px] min-w-0 flex-1 break-all">{cmd}</code>
                  <p className="text-android-muted text-[11px] shrink-0">{desc}</p>
                </div>
              ))}
            </div>
          </div>

        </div>
      </main>
    </div>
  )
}
