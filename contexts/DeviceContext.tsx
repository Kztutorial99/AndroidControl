'use client'
import { createContext, useContext, useState, useEffect, useCallback, useRef } from 'react'

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

export function DeviceProvider({ children }: { children: React.ReactNode }) {
  const [devices, setDevices] = useState<DeviceItem[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const initializedRef = useRef(false)

  const fetchDevices = useCallback(async () => {
    try {
      const res  = await fetch('/api/devices')
      const data = await res.json()
      const list: DeviceItem[] = data.devices ?? []
      setDevices(list)
      setSelectedId(prev => {
        if (prev && list.find(d => d.deviceId === prev)) return prev
        if (!initializedRef.current && list.length > 0) {
          initializedRef.current = true
          return (list.find(d => d.connected) ?? list[0]).deviceId
        }
        return prev
      })
    } catch {}
  }, [])

  useEffect(() => {
    fetchDevices()
    const t = setInterval(fetchDevices, 3000)
    return () => clearInterval(t)
  }, [fetchDevices])

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
