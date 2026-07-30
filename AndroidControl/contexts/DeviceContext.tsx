'use client'
import { createContext, useContext, useState, useEffect, useCallback, useRef } from 'react'
import useSWR from 'swr'

export interface DeviceItem {
  deviceId: string
  deviceName: string
  connected: boolean
  model?: string
}

interface DeviceContextValue {
  devices: DeviceItem[]
  selectedId: string | null
  setSelectedId: (id: string) => void
  connected: boolean
  selectedDevice: DeviceItem | undefined
}

const DeviceContext = createContext<DeviceContextValue>({
  devices: [],
  selectedId: null,
  setSelectedId: () => {},
  connected: false,
  selectedDevice: undefined,
})

const fetcher = (url: string) => fetch(url).then(r => r.json())

export function DeviceProvider({ children }: { children: React.ReactNode }) {
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const initializedRef = useRef(false)

  const { data, mutate } = useSWR<{ devices: DeviceItem[] }>(
    '/api/devices',
    fetcher,
    {
      refreshInterval: 5000,
      keepPreviousData: true,
      revalidateOnFocus: true,
      dedupingInterval: 2000,
    }
  )

  const devices: DeviceItem[] = data?.devices ?? []

  useEffect(() => {
    if (devices.length === 0) return
    setSelectedId(prev => {
      if (prev && devices.find(d => d.deviceId === prev)) return prev
      if (!initializedRef.current) {
        initializedRef.current = true
        return (devices.find(d => d.connected) ?? devices[0]).deviceId
      }
      return prev
    })
  }, [devices])

  useEffect(() => {
    if (!selectedId) return
    const es = new EventSource(`/api/device/stream?deviceId=${encodeURIComponent(selectedId)}&heartbeatOnly=true`)
    es.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data)
        if (msg.type === 'heartbeat') {
          mutate(prev => {
            if (!prev) return prev
            const updated = prev.devices.map(d =>
              d.deviceId === msg.device.deviceId
                ? { ...d, connected: msg.device.connected, model: msg.device.stats?.model }
                : d
            )
            return { devices: updated }
          }, { revalidate: false })
        }
      } catch {}
    }
    es.onerror = () => {
      es.close()
    }
    return () => es.close()
  }, [selectedId, mutate])

  const selectedDevice = devices.find(d => d.deviceId === selectedId)
  const connected = selectedDevice?.connected ?? false

  return (
    <DeviceContext.Provider value={{ devices, selectedId, setSelectedId, connected, selectedDevice }}>
      {children}
    </DeviceContext.Provider>
  )
}

export function useDevice() {
  return useContext(DeviceContext)
}
