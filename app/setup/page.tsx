'use client'
import { useState } from 'react'
import Sidebar from '@/components/Sidebar'
import { useDevice } from '@/contexts/DeviceContext'
import {
  Smartphone, Globe, Shield, Zap,
  ChevronDown, ChevronRight, Copy, CheckCheck, ExternalLink
} from 'lucide-react'

function Step({ n, title, children }: { n: number; title: string; children: React.ReactNode }) {
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
              Hubungkan Android kamu ke dashboard ini dalam 5 langkah.
            </p>
          </div>

          {/* Overview cards */}
          <div className="grid grid-cols-3 gap-2 mb-5">
            {[
              { icon: <Globe size={16} />, label: 'Web Server', sub: 'Vercel', color: 'text-android-blue' },
              { icon: <Smartphone size={16} />, label: 'APK Client', sub: 'Android', color: 'text-android-green' },
              { icon: <Shield size={16} />, label: 'Shizuku', sub: 'Elevated', color: 'text-android-yellow' },
            ].map(({ icon, label, sub, color }) => (
              <div key={label} className="bg-android-surface border border-android-border rounded-xl p-3 text-center">
                <div className={`${color} mx-auto mb-1.5 flex justify-center`}>{icon}</div>
                <p className="text-white text-xs font-semibold">{label}</p>
                <p className="text-android-muted text-[10px]">{sub}</p>
              </div>
            ))}
          </div>

          <Step n={1} title="Deploy ke Vercel">
            <p>Push repo ke GitHub, lalu deploy di Vercel:</p>
            <ol className="list-decimal list-inside space-y-1 text-android-text text-xs">
              <li>Buka <a href="https://vercel.com/new" target="_blank" className="text-android-blue underline inline-flex items-center gap-1">vercel.com/new <ExternalLink size={10} /></a></li>
              <li>Import repo <code className="bg-android-border px-1 rounded text-[10px]">Kztutorial99/AndroidControl</code></li>
              <li>Klik <strong>Deploy</strong> — Vercel auto-detect Next.js</li>
            </ol>
            <div className="p-3 bg-android-yellow/10 border border-android-yellow/30 rounded-lg text-android-yellow text-xs">
              ⚠️ Tambahkan env var <code className="font-mono">DEVICE_TOKEN</code> di Vercel → Settings → Env Variables
            </div>
          </Step>

          <Step n={2} title="Set Secret Token">
            <p>Di <strong>Vercel → Project → Settings → Environment Variables</strong>:</p>
            <div className="grid grid-cols-2 gap-2 mt-1">
              <div>
                <p className="text-[10px] mb-1">Key</p>
                <Code>DEVICE_TOKEN</Code>
              </div>
              <div>
                <p className="text-[10px] mb-1">Value (unik!)</p>
                <Code>vivo-secret-2025</Code>
              </div>
            </div>
            <p className="text-xs">Token ini diisikan juga di APK.</p>
          </Step>

          <Step n={3} title="Download & Install APK">
            <p className="font-medium text-android-text text-xs">Cara A — GitHub Actions <Tag color="green" label="Direkomendasikan" /></p>
            <ol className="list-decimal list-inside space-y-1 mt-1 text-xs text-android-text">
              <li>Push code ke GitHub → tab Actions auto-jalan</li>
              <li>Klik workflow run terbaru → <strong>Artifacts</strong></li>
              <li>Download <code className="bg-android-border px-1 rounded text-[10px]">AndroidConnector-debug-xxx.zip</code></li>
              <li>Extract APK → transfer ke HP</li>
              <li>Di HP: aktifkan <strong>Install Unknown Apps</strong> untuk file manager</li>
              <li>Tap APK untuk install</li>
            </ol>

            <div className="mt-2 p-3 bg-android-surface border border-android-border rounded-lg text-xs">
              <p className="text-android-text font-medium mb-1">Aktifkan Unknown Sources di Vivo:</p>
              <p className="text-android-muted">Settings → More Settings → Install apps from external sources → aktifkan untuk file manager</p>
            </div>
          </Step>

          <Step n={4} title="Konfigurasi APK di HP">
            <ol className="list-decimal list-inside space-y-2 text-android-text text-xs">
              <li>Buka app <strong>AndroidConnector</strong></li>
              <li>Izinkan <strong>All Files Access</strong> (terbuka otomatis)</li>
              <li>Masukkan <strong>Server URL</strong>:
                <Code copy={false}>https://nama-app.vercel.app</Code>
              </li>
              <li>Masukkan <strong>Device Token</strong>:
                <Code copy={false}>vivo-secret-2025</Code>
              </li>
              <li>Tap <strong>CONNECT</strong> — status jadi hijau</li>
            </ol>
            <div className="p-3 bg-android-green/10 border border-android-green/30 rounded-lg text-android-green text-xs">
              ✅ Buka tab Dashboard — info device muncul dalam 5 detik!
            </div>
          </Step>

          <Step n={5} title="Aktifkan Shizuku (Opsional tapi Powerful)">
            <div className="flex items-start gap-2">
              <Zap size={14} className="text-android-yellow shrink-0 mt-0.5" />
              <p className="text-xs">Shizuku memberi akses level ADB shell tanpa PC/root — grant izin, baca system info, kontrol app.</p>
            </div>

            <p className="font-medium text-android-text text-xs">5a — Install Shizuku</p>
            <p className="text-xs">Download di <a href="https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api" target="_blank" className="text-android-blue underline inline-flex items-center gap-1">Play Store <ExternalLink size={10} /></a></p>

            <p className="font-medium text-android-text text-xs mt-2">5b — Aktifkan (tanpa PC!)</p>
            <ol className="list-decimal list-inside space-y-1 mt-1 text-xs text-android-text">
              <li>Settings → <strong>About Phone</strong> → tap <strong>Build Number</strong> 7x</li>
              <li>Settings → <strong>Developer Options</strong> → aktifkan <strong>Wireless Debugging</strong></li>
              <li>Buka <strong>Shizuku</strong> → tap <strong>Pairing by wireless debugging</strong></li>
              <li>Dev Options → Wireless Debugging → <strong>Pair device with pairing code</strong></li>
              <li>Masukkan kode 6 digit ke Shizuku</li>
              <li>Tap <strong>Start</strong> di Shizuku → status "Running"</li>
            </ol>

            <p className="font-medium text-android-text text-xs mt-2">5c — Grant ke AndroidConnector</p>
            <ol className="list-decimal list-inside space-y-1 mt-1 text-xs text-android-text">
              <li>Buka <strong>AndroidConnector</strong></li>
              <li>Tap card kuning <strong>"Shizuku running but not granted"</strong></li>
              <li>Izinkan di dialog Shizuku</li>
            </ol>

            <div className="grid grid-cols-2 gap-2 text-xs">
              <div className="p-3 bg-android-surface border border-android-border rounded-lg">
                <p className="text-android-green font-medium mb-1">Dengan Shizuku ✅</p>
                <ul className="space-y-0.5 text-android-muted text-[11px]">
                  <li>• Grant izin diam-diam</li>
                  <li>• Baca /data/system/</li>
                  <li>• Install app silent</li>
                  <li>• Nonaktifkan bloatware</li>
                </ul>
              </div>
              <div className="p-3 bg-android-surface border border-android-border rounded-lg">
                <p className="text-android-yellow font-medium mb-1">Tanpa Shizuku ⚠️</p>
                <ul className="space-y-0.5 text-android-muted text-[11px]">
                  <li>• /storage/** full R/W</li>
                  <li>• /proc, /sys read</li>
                  <li>• Info device lengkap</li>
                  <li>• Tetap powerful!</li>
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
                { cmd: 'read_text:/storage/emulated/0/readme.txt', desc: 'Baca file teks' },
                { cmd: 'shizuku:pm list packages -3', desc: 'List app terinstall (Shizuku)' },
                { cmd: 'shell:df -h', desc: 'Disk usage' },
                { cmd: 'device_info', desc: 'Full device JSON info' },
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
