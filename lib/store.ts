import { v4 as uuidv4 } from 'uuid'

export interface CommandResult {
  id: string
  command: string
  result: string
  timestamp: string
  exitCode?: number
}

export interface DeviceInfo {
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

interface Store {
  device: DeviceInfo
  pendingCommands: PendingCommand[]
  commandHistory: CommandResult[]
  fileListing: FileListing | null
  token: string
  sessionId: string
}

declare global {
  var __androidStore: Store | undefined
}

function createDefaultStore(): Store {
  return {
    device: {
      connected: false,
      lastSeen: null,
      battery: '--',
      batteryStatus: 'unknown',
      model: 'Unknown Device',
      androidVersion: '--',
      ip: '--',
      storage: '--',
      storageFree: '--',
      networkType: '--',
      cpuUsage: '--',
      memTotal: '--',
      memFree: '--',
      uptime: '--',
      hostname: '--',
      kernel: '--',
      screenState: '--',
    },
    pendingCommands: [],
    commandHistory: [],
    fileListing: null,
    token: process.env.DEVICE_TOKEN || 'change-this-secret-token',
    sessionId: uuidv4(),
  }
}

if (!global.__androidStore) {
  global.__androidStore = createDefaultStore()
}

export const store = global.__androidStore!

export function isDeviceOnline(): boolean {
  if (!store.device.lastSeen) return false
  const lastSeen = new Date(store.device.lastSeen).getTime()
  const now = Date.now()
  return now - lastSeen < 10000
}

export function addCommandToHistory(cmd: CommandResult) {
  store.commandHistory.unshift(cmd)
  if (store.commandHistory.length > 100) {
    store.commandHistory = store.commandHistory.slice(0, 100)
  }
}

export function enqueuCommand(command: string): PendingCommand {
  const pending: PendingCommand = {
    id: uuidv4(),
    command,
    createdAt: new Date().toISOString(),
  }
  store.pendingCommands.push(pending)
  return pending
}

export function popPendingCommand(): PendingCommand | null {
  if (store.pendingCommands.length === 0) return null
  return store.pendingCommands.shift()!
}
