'use client'
import { DeviceProvider } from '@/contexts/DeviceContext'

export default function Providers({ children }: { children: React.ReactNode }) {
  return <DeviceProvider>{children}</DeviceProvider>
}
