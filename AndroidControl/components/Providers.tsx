'use client'
import { DeviceProvider } from '@/contexts/DeviceContext'
import { BadgeProvider } from '@/contexts/BadgeContext'

export default function Providers({ children }: { children: React.ReactNode }) {
  return (
    <DeviceProvider>
      <BadgeProvider>{children}</BadgeProvider>
    </DeviceProvider>
  )
}
