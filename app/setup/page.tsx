'use client'
import { useState, useEffect } from 'react'
import Sidebar from '@/components/Sidebar'
import {
  Smartphone, Globe, Shield, Zap, ChevronDown,
  ChevronRight, Copy, CheckCheck, ExternalLink, Download
} from 'lucide-react'

function Step({ n, title, children }: { n: number; title: string; children: React.ReactNode }) {
  const [open, setOpen] = useState(n <= 2)
  return (
    <div className="bg-android-surface border border-android-border rounded-xl overflow-hidden mb-3">
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center gap-3 px-5 py-4 text-left hover:bg-white/5 transition-colors"
      >
        <div className="w-7 h-7 rounded-full bg-android-green/20 text-android-green text-sm font-bold flex items-center justify-center shrink-0">
          {n}
        </div>
        <span className="flex-1 font-semibold text-white text-sm">{title}</span>
        {open ? <ChevronDown size={16} className="text-android-muted" /> : <ChevronRight size={16} className="text-android-muted" />}
      </button>
      {open && <div className="px-5 pb-5 text-sm text-android-muted space-y-3">{children}</div>}
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
      <pre className="pr-8">{children}</pre>
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
    blue: 'bg-android-blue/15 text-android-blue border-android-blue/30',
  }
  return <span className={`text-xs px-2 py-0.5 rounded-full border font-medium ${map[color]}`}>{label}</span>
}

export default function SetupPage() {
  const [devices, setDevices] = useState<{ deviceId: string; deviceName: string; connected: boolean }[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)

  useEffect(() => {
    const load = async () => {
      try {
        const res = await fetch('/api/devices')
        const data = await res.json()
        const list = data.devices ?? []
        setDevices(list)
        if (list.length > 0) {
          const online = list.find((d: { connected: boolean }) => d.connected) ?? list[0]
          setSelectedId(online.deviceId)
        }
      } catch {}
    }
    load()
    const t = setInterval(load, 5000)
    return () => clearInterval(t)
  }, [])

  const connected = devices.find(d => d.deviceId === selectedId)?.connected ?? false

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />
      <main className="flex-1 p-6 overflow-y-auto">
        <div className="max-w-3xl mx-auto">

          <div className="mb-7">
            <h2 className="text-xl font-bold text-white">Setup Guide</h2>
            <p className="text-android-muted text-sm mt-1">
              Connect your <strong className="text-android-text">Vivo Y35</strong> to this web dashboard in 5 steps.
            </p>
          </div>

          {/* Overview */}
          <div className="grid grid-cols-3 gap-3 mb-6">
            {[
              { icon: <Globe size={18} />, label: 'Web Server', sub: 'Vercel', color: 'text-android-blue' },
              { icon: <Smartphone size={18} />, label: 'APK Client', sub: 'Vivo Y35', color: 'text-android-green' },
              { icon: <Shield size={18} />, label: 'Shizuku', sub: 'Elevated Access', color: 'text-android-yellow' },
            ].map(({ icon, label, sub, color }) => (
              <div key={label} className="bg-android-surface border border-android-border rounded-xl p-4 text-center">
                <div className={`${color} mx-auto mb-2 flex justify-center`}>{icon}</div>
                <p className="text-white text-sm font-medium">{label}</p>
                <p className="text-android-muted text-xs">{sub}</p>
              </div>
            ))}
          </div>

          {/* Step 1 — Deploy */}
          <Step n={1} title="Deploy to Vercel">
            <p>Push this repo to GitHub, then deploy on Vercel:</p>
            <ol className="list-decimal list-inside space-y-1 text-android-text">
              <li>Go to <a href="https://vercel.com/new" target="_blank" className="text-android-blue underline inline-flex items-center gap-1">vercel.com/new <ExternalLink size={11} /></a></li>
              <li>Import repo <code className="bg-android-border px-1 rounded">Kztutorial99/AndroidConncetor</code></li>
              <li>Click <strong>Deploy</strong> — Vercel auto-detects Next.js</li>
            </ol>
            <div className="mt-3 p-3 bg-android-yellow/10 border border-android-yellow/30 rounded-lg text-android-yellow text-xs">
              ⚠️ Add environment variable <code>DEVICE_TOKEN</code> in Vercel project settings → Environment Variables
            </div>
          </Step>

          {/* Step 2 — Set token */}
          <Step n={2} title="Set Your Secret Token">
            <p>In <strong>Vercel → Project → Settings → Environment Variables</strong>, add:</p>
            <div className="grid grid-cols-2 gap-2 mt-2">
              <div>
                <p className="text-xs mb-1">Key</p>
                <Code copy>DEVICE_TOKEN</Code>
              </div>
              <div>
                <p className="text-xs mb-1">Value (make it unique!)</p>
                <Code copy>vivo-y35-my-secret-2025</Code>
              </div>
            </div>
            <p className="text-xs mt-2">You will enter this same token in the APK on your phone.</p>
          </Step>

          {/* Step 3 — Download & Install APK */}
          <Step n={3} title="Download & Install APK on Vivo Y35">
            <p className="font-medium text-android-text">Option A — Auto-build via GitHub Actions <Tag color="green" label="Recommended" /></p>
            <ol className="list-decimal list-inside space-y-1 mt-2">
              <li>Push code to GitHub → Actions tab auto-runs</li>
              <li>Click the latest workflow run → <strong>Artifacts</strong></li>
              <li>Download <code className="bg-android-border px-1 rounded">AndroidConnector-debug-xxx.zip</code></li>
              <li>Extract APK → transfer to Vivo Y35</li>
              <li>On Vivo Y35: enable <strong>Install Unknown Apps</strong> for your file manager</li>
              <li>Tap APK to install</li>
            </ol>

            <p className="font-medium text-android-text mt-4">Option B — Build locally with Android Studio</p>
            <Code copy={false}>{`File → Open → select android/ folder
Build → Generate Signed Bundle/APK → APK → Debug`}</Code>

            <div className="mt-3 p-3 bg-android-surface border border-android-border rounded-lg text-xs">
              <p className="text-android-text font-medium mb-1">Vivo Y35 Enable Unknown Sources:</p>
              <p>Settings → More Settings → Install apps from external sources → enable for your file manager</p>
            </div>
          </Step>

          {/* Step 4 — Configure APK */}
          <Step n={4} title="Configure APK on Vivo Y35">
            <ol className="list-decimal list-inside space-y-2 text-android-text">
              <li>Open <strong>AndroidConnector</strong> app</li>
              <li>Grant <strong>All Files Access</strong> when prompted (opens automatically)</li>
              <li>
                Enter <strong>Server URL</strong>:
                <Code copy={false}>https://your-app-name.vercel.app</Code>
              </li>
              <li>
                Enter <strong>Device Token</strong>:
                <Code copy={false}>vivo-y35-my-secret-2025</Code>
              </li>
              <li>Tap <strong>CONNECT</strong> — status turns green</li>
            </ol>
            <div className="mt-3 p-3 bg-android-green/10 border border-android-green/30 rounded-lg text-android-green text-xs">
              ✅ Open the Dashboard tab — device info should appear within 3 seconds!
            </div>
          </Step>

          {/* Step 5 — Shizuku (optional but powerful) */}
          <Step n={5} title="Enable Shizuku — Elevated Access (Optional but Powerful)">
            <div className="flex items-start gap-2 mb-3">
              <Zap size={16} className="text-android-yellow shrink-0 mt-0.5" />
              <p>Shizuku gives <strong className="text-android-text">ADB shell-level access</strong> without PC or root — grants/revokes permissions, reads system info, controls apps.</p>
            </div>

            <p className="font-medium text-android-text">Step 5a — Install Shizuku</p>
            <p className="mt-1">Download from <a href="https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api" target="_blank" className="text-android-blue underline inline-flex items-center gap-1">Play Store <ExternalLink size={11} /></a> or <a href="https://github.com/RikkaApps/Shizuku/releases" target="_blank" className="text-android-blue underline inline-flex items-center gap-1">GitHub <ExternalLink size={11} /></a></p>

            <p className="font-medium text-android-text mt-4">Step 5b — Enable on Vivo Y35 (no PC needed!)</p>
            <ol className="list-decimal list-inside space-y-1 mt-1 text-android-text">
              <li>Settings → <strong>About Phone</strong> → tap <strong>Build Number</strong> 7 times</li>
              <li>Settings → <strong>Developer Options</strong> → enable <strong>Wireless Debugging</strong></li>
              <li>Open <strong>Shizuku</strong> app → tap <strong>Pairing by wireless debugging</strong></li>
              <li>Go to Developer Options → Wireless Debugging → <strong>Pair device with pairing code</strong></li>
              <li>Enter the 6-digit pairing code in Shizuku</li>
              <li>Tap <strong>Start</strong> in Shizuku → status shows "Running"</li>
            </ol>

            <p className="font-medium text-android-text mt-4">Step 5c — Grant to AndroidConnector</p>
            <ol className="list-decimal list-inside space-y-1 mt-1 text-android-text">
              <li>Open <strong>AndroidConnector</strong> app</li>
              <li>Tap the yellow status card <strong>"Shizuku running but not granted"</strong></li>
              <li>Allow permission in Shizuku dialog</li>
            </ol>

            <div className="mt-3 grid grid-cols-2 gap-2 text-xs">
              <div className="p-3 bg-android-surface border border-android-border rounded-lg">
                <p className="text-android-green font-medium mb-1">With Shizuku ✅</p>
                <ul className="space-y-0.5 text-android-muted">
                  <li>• Grant permissions silent</li>
                  <li>• Read /data/system/</li>
                  <li>• Install apps silent</li>
                  <li>• Disable bloatware</li>
                  <li>• Edit system settings</li>
                </ul>
              </div>
              <div className="p-3 bg-android-surface border border-android-border rounded-lg">
                <p className="text-android-yellow font-medium mb-1">Without Shizuku ⚠️</p>
                <ul className="space-y-0.5 text-android-muted">
                  <li>• /storage/** full R/W</li>
                  <li>• /proc, /sys read</li>
                  <li>• Device info</li>
                  <li>• Shell commands</li>
                  <li>• Still very powerful!</li>
                </ul>
              </div>
            </div>
          </Step>

          {/* Quick Commands */}
          <div className="mt-6 bg-android-surface border border-android-border rounded-xl p-5">
            <h3 className="text-sm font-semibold text-android-muted uppercase tracking-wider mb-4 flex items-center gap-2">
              <Zap size={14} /> Quick Terminal Commands
            </h3>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs">
              {[
                { cmd: 'ls_json:/storage/emulated/0', desc: 'Browse internal storage (JSON)' },
                { cmd: 'read_text:/storage/emulated/0/readme.txt', desc: 'Read a text file' },
                { cmd: 'mkdir:/storage/emulated/0/MyFolder', desc: 'Create directory' },
                { cmd: 'shizuku:pm list packages -3', desc: 'List installed apps (Shizuku)' },
                { cmd: 'shizuku:settings get global airplane_mode_on', desc: 'Read system setting' },
                { cmd: 'shizuku_status', desc: 'Check Shizuku availability' },
                { cmd: 'shell:df -h', desc: 'Disk usage' },
                { cmd: 'device_info', desc: 'Full device JSON info' },
              ].map(({ cmd, desc }) => (
                <div key={cmd} className="bg-android-bg rounded-lg p-3 border border-android-border/50">
                  <code className="text-android-green block font-mono mb-1">{cmd}</code>
                  <p className="text-android-muted">{desc}</p>
                </div>
              ))}
            </div>
          </div>

        </div>
      </main>
    </div>
  )
}
