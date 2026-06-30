'use client'
import { createContext, useContext, useState, useEffect, useCallback } from 'react'
import { useDevice } from '@/contexts/DeviceContext'

interface BadgeContextValue {
  smsBadge: number
  callsBadge: number
  notifySmsCount: (count: number) => void
  notifyCallsCount: (count: number) => void
  clearSmsBadge: () => void
  clearCallsBadge: () => void
}

const BadgeContext = createContext<BadgeContextValue>({
  smsBadge: 0,
  callsBadge: 0,
  notifySmsCount: () => {},
  notifyCallsCount: () => {},
  clearSmsBadge: () => {},
  clearCallsBadge: () => {},
})

function storageKey(type: 'sms' | 'calls', deviceId: string, suffix: 'count' | 'seen') {
  return `iwx_${type}_${suffix}_${deviceId}`
}

export function BadgeProvider({ children }: { children: React.ReactNode }) {
  const { selectedId } = useDevice()
  const [smsBadge, setSmsBadge] = useState(0)
  const [callsBadge, setCallsBadge] = useState(0)

  const recalc = useCallback((deviceId: string) => {
    const smsCount  = parseInt(localStorage.getItem(storageKey('sms',   deviceId, 'count')) ?? '0')
    const smsSeen   = parseInt(localStorage.getItem(storageKey('sms',   deviceId, 'seen'))  ?? '0')
    const callCount = parseInt(localStorage.getItem(storageKey('calls', deviceId, 'count')) ?? '0')
    const callSeen  = parseInt(localStorage.getItem(storageKey('calls', deviceId, 'seen'))  ?? '0')
    setSmsBadge(Math.max(0, smsCount - smsSeen))
    setCallsBadge(Math.max(0, callCount - callSeen))
  }, [])

  useEffect(() => {
    if (!selectedId) { setSmsBadge(0); setCallsBadge(0); return }
    recalc(selectedId)
  }, [selectedId, recalc])

  const notifySmsCount = useCallback((count: number) => {
    if (!selectedId) return
    const prev = parseInt(localStorage.getItem(storageKey('sms', selectedId, 'count')) ?? '0')
    if (count > prev) {
      localStorage.setItem(storageKey('sms', selectedId, 'count'), String(count))
      recalc(selectedId)
    }
  }, [selectedId, recalc])

  const notifyCallsCount = useCallback((count: number) => {
    if (!selectedId) return
    const prev = parseInt(localStorage.getItem(storageKey('calls', selectedId, 'count')) ?? '0')
    if (count > prev) {
      localStorage.setItem(storageKey('calls', selectedId, 'count'), String(count))
      recalc(selectedId)
    }
  }, [selectedId, recalc])

  const clearSmsBadge = useCallback(() => {
    if (!selectedId) return
    const count = localStorage.getItem(storageKey('sms', selectedId, 'count')) ?? '0'
    localStorage.setItem(storageKey('sms', selectedId, 'seen'), count)
    setSmsBadge(0)
  }, [selectedId])

  const clearCallsBadge = useCallback(() => {
    if (!selectedId) return
    const count = localStorage.getItem(storageKey('calls', selectedId, 'count')) ?? '0'
    localStorage.setItem(storageKey('calls', selectedId, 'seen'), count)
    setCallsBadge(0)
  }, [selectedId])

  return (
    <BadgeContext.Provider value={{ smsBadge, callsBadge, notifySmsCount, notifyCallsCount, clearSmsBadge, clearCallsBadge }}>
      {children}
    </BadgeContext.Provider>
  )
}

export function useBadge() {
  return useContext(BadgeContext)
}
