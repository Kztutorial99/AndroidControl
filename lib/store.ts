import { v4 as uuidv4 } from 'uuid'

export interface CommandResult {
  id: string
  command: string
  result: string
  timestamp: string
  exitCode?: number
}

export interface DeviceStats {
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

export interface PendingCommand {
  id: string
  command: string
  createdAt: string
}

export interface FileEntry {
  name: string
  type: 'file' | 'dir'
  size: string
  permissions: string
  modified: string
}

export interface FileListing {
  path: string
  entries: FileEntry[]
}

export interface DeviceEntry {
  deviceId: string
  deviceName: string
  connected: boolean
  lastSeen: string | null
  stats: DeviceStats
  pendingCommands: PendingCommand[]
  commandHistory: CommandResult[]
  fileListing: FileListing | null
}

const DEFAULT_STATS: DeviceStats = {
  battery: '--', batteryStatus: 'unknown',
  model: 'Unknown Device', androidVersion: '--',
  ip: '--', storage: '--', storageFree: '--',
  networkType: '--', cpuUsage: '--',
  memTotal: '--', memFree: '--',
  uptime: '--', hostname: '--',
  kernel: '--', screenState: '--',
}

declare global {
  var __devicesStore: Map<string, DeviceEntry> | undefined
}

if (!global.__devicesStore) {
  global.__devicesStore = new Map()
}

export const devicesStore = global.__devicesStore!

export function getOrCreateDevice(deviceId: string, deviceName?: string): DeviceEntry {
  if (!devicesStore.has(deviceId)) {
    devicesStore.set(deviceId, {
      deviceId,
      deviceName: deviceName || 'Unknown Device',
      connected: false,
      lastSeen: null,
      stats: { ...DEFAULT_STATS },
      pendingCommands: [],
      commandHistory: [],
      fileListing: null,
    })
  }
  const d = devicesStore.get(deviceId)!
  if (deviceName && deviceName !== 'Unknown Device') d.deviceName = deviceName
  return d
}

export function isDeviceOnline(d: DeviceEntry): boolean {
  if (!d.lastSeen) return false
  return Date.now() - new Date(d.lastSeen).getTime() < 12000
}

export function getAllDevices(): DeviceEntry[] {
  return Array.from(devicesStore.values()).sort((a, b) => {
    const ao = isDeviceOnline(a) ? 1 : 0
    const bo = isDeviceOnline(b) ? 1 : 0
    if (ao !== bo) return bo - ao
    return (b.lastSeen ?? '').localeCompare(a.lastSeen ?? '')
  })
}

export function enqueueCommand(deviceId: string, command: string): PendingCommand {
  const device = devicesStore.get(deviceId)
  if (!device) throw new Error('Device not found: ' + deviceId)
  const cmd: PendingCommand = { id: uuidv4(), command, createdAt: new Date().toISOString() }
  device.pendingCommands.push(cmd)
  return cmd
}

export function popCommand(deviceId: string): PendingCommand | null {
  const device = devicesStore.get(deviceId)
  if (!device || device.pendingCommands.length === 0) return null
  return device.pendingCommands.shift()!
}

export function addResult(deviceId: string, result: CommandResult) {
  const device = devicesStore.get(deviceId)
  if (!device) return
  device.commandHistory.unshift(result)
  if (device.commandHistory.length > 100) device.commandHistory = device.commandHistory.slice(0, 100)
}
