'use client'
import { useEffect, useState, useCallback } from 'react'
import Sidebar from '@/components/Sidebar'
import StatCard from '@/components/StatCard'
import {
  Battery, BatteryCharging, Cpu, HardDrive, Wifi,
  Monitor, Server, Clock, RefreshCw, Smartphone
} from 'lucide-react'

interface DeviceInfo {
  connected: boolean
  lastSeen: string | null
  battery: string
  batteryStatus: string
  model: string
  androidVersion: string
  ip: string
  storage: string
  storageFree: string
  networkType: string
  cpuUsage: string
  memTotal: string
  memFree: string
  uptime: string
  hostname: string
  kernel: string
  screenState: string
}

export default function Dashboard() {
  const [device, setDevice] = useState<DeviceInfo | null>(null)
  const [loading, setLoading] = useState(true)

  const fetchStatus = useCallback(async () => {
    try {
      const res = await fetch('/api/device/heartbeat')
      const data = await res.json()
      setDevice(data.device)
    } catch {}
    setLoading(false)
  }, [])

  useEffect(() => {
    fetchStatus()
    const interval = setInterval(fetchStatus, 3000)
    return () => clearInterval(interval)
  }, [fetchStatus])

  const connected = device?.connected ?? false
  const battPct = parseInt(device?.battery ?? '0') || 0
  const battColor = battPct > 50 ? 'green' : battPct > 20 ? 'yellow' : 'red'
  const isCharging = device?.batteryStatus === 'Charging'

  const memFreeNum = parseFloat(device?.memFree ?? '0') || 0
  const memTotalNum = parseFloat(device?.memTotal ?? '1') || 1
  const memUsedPct = Math.round((1 - memFreeNum / memTotalNum) * 100)

  const storageUsed = parseFloat(device?.storage ?? '0') || 0
  const storageFree = parseFloat(device?.storageFree ?? '0') || 0
  const storageTotal = storageUsed + storageFree
  const storageUsedPct = storageTotal ? Math.round((storageUsed / storageTotal) * 100) : 0

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} />

      <main className="flex-1 p-6 overflow-y-auto">
        <div className="max-w-5xl mx-auto">

          <div className="flex items-center justify-between mb-6">
            <div>
              <h2 className="text-xl font-bold text-white">Device Dashboard</h2>
              <p className="text-android-muted text-sm mt-0.5">
                {connected
                  ? `Connected · Last seen ${device?.lastSeen ? new Date(device.lastSeen).toLocaleTimeString() : '--'}`
                  : 'Waiting for device to connect…'}
              </p>
            </div>
            <button
              onClick={fetchStatus}
              className="flex items-center gap-2 px-4 py-2 bg-android-surface border border-android-border rounded-lg text-sm text-android-muted hover:text-android-text transition-colors"
            >
              <RefreshCw size={14} />
              Refresh
            </button>
          </div>

          {!connected && !loading && (
            <div className="mb-6 p-5 bg-android-yellow/10 border border-android-yellow/30 rounded-xl flex items-start gap-3">
              <Smartphone size={18} className="text-android-yellow mt-0.5 shrink-0" />
              <div>
                <p className="text-android-yellow text-sm font-medium">No device connected</p>
                <p className="text-android-muted text-xs mt-1">
                  Go to <a href="/setup" className="text-android-blue underline">Setup</a> to get the Termux script and connect your Android device.
                </p>
              </div>
            </div>
          )}

          <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-6">
            <StatCard
              label="Battery"
              value={connected ? `${device?.battery ?? '--'}%` : '--'}
              sub={isCharging ? 'Charging' : connected ? 'Discharging' : 'No device'}
              icon={isCharging ? <BatteryCharging size={18} /> : <Battery size={18} />}
              color={connected ? battColor : 'default'}
              bar={connected ? battPct : undefined}
            />
            <StatCard
              label="CPU Usage"
              value={connected ? (device?.cpuUsage ?? '--') : '--'}
              sub="Processor load"
              icon={<Cpu size={18} />}
              color="blue"
              bar={connected ? parseInt(device?.cpuUsage ?? '0') : undefined}
            />
            <StatCard
              label="RAM Free"
              value={connected ? (device?.memFree ?? '--') : '--'}
              sub={connected ? `of ${device?.memTotal ?? '--'}` : 'No device'}
              icon={<Server size={18} />}
              color="yellow"
              bar={connected ? memUsedPct : undefined}
            />
            <StatCard
              label="Storage"
              value={connected ? (device?.storageFree ?? '--') : '--'}
              sub={connected ? `free of ${storageTotal ? storageTotal.toFixed(1) + ' GB' : '--'}` : 'No device'}
              icon={<HardDrive size={18} />}
              color="green"
              bar={connected ? storageUsedPct : undefined}
            />
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="bg-android-surface border border-android-border rounded-xl p-5">
              <h3 className="text-sm font-semibold text-android-muted uppercase tracking-wider mb-4 flex items-center gap-2">
                <Smartphone size={14} /> Device Info
              </h3>
              <div className="space-y-3">
                {[
                  { label: 'Model', value: device?.model },
                  { label: 'Android', value: device?.androidVersion },
                  { label: 'Hostname', value: device?.hostname },
                  { label: 'Kernel', value: device?.kernel },
                  { label: 'Screen', value: device?.screenState },
                ].map(({ label, value }) => (
                  <div key={label} className="flex justify-between items-center py-1 border-b border-android-border/50 last:border-0">
                    <span className="text-android-muted text-sm">{label}</span>
                    <span className="text-android-text text-sm font-medium font-mono truncate max-w-[200px]">
                      {connected ? (value || '--') : '--'}
                    </span>
                  </div>
                ))}
              </div>
            </div>

            <div className="bg-android-surface border border-android-border rounded-xl p-5">
              <h3 className="text-sm font-semibold text-android-muted uppercase tracking-wider mb-4 flex items-center gap-2">
                <Wifi size={14} /> Network & System
              </h3>
              <div className="space-y-3">
                {[
                  { label: 'IP Address', value: device?.ip },
                  { label: 'Network', value: device?.networkType },
                  { label: 'Uptime', value: device?.uptime },
                  { label: 'Storage Used', value: connected ? `${storageUsedPct}%` : '--' },
                  { label: 'RAM Used', value: connected ? `${memUsedPct}%` : '--' },
                ].map(({ label, value }) => (
                  <div key={label} className="flex justify-between items-center py-1 border-b border-android-border/50 last:border-0">
                    <span className="text-android-muted text-sm">{label}</span>
                    <span className="text-android-text text-sm font-medium font-mono truncate max-w-[200px]">
                      {connected ? (value || '--') : '--'}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </div>

          <div className="mt-4 bg-android-surface border border-android-border rounded-xl p-5">
            <h3 className="text-sm font-semibold text-android-muted uppercase tracking-wider mb-4 flex items-center gap-2">
              <Clock size={14} /> Quick Actions
            </h3>
            <div className="flex flex-wrap gap-3">
              {[
                { label: '📟 Open Terminal', href: '/terminal' },
                { label: '📂 File Manager', href: '/files' },
                { label: '⚙️ Setup Guide', href: '/setup' },
              ].map(({ label, href }) => (
                <a
                  key={href}
                  href={href}
                  className="px-4 py-2 bg-android-bg border border-android-border rounded-lg text-sm text-android-text hover:border-android-green hover:text-android-green transition-colors"
                >
                  {label}
                </a>
              ))}
            </div>
          </div>
        </div>
      </main>
    </div>
  )
}
