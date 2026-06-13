import sql from './db'
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

export function isDeviceOnline(d: { lastSeen: string | null }): boolean {
  if (!d.lastSeen) return false
  return Date.now() - new Date(d.lastSeen).getTime() < 30000
}

export async function getOrCreateDevice(deviceId: string, deviceName?: string): Promise<DeviceEntry> {
  const name = deviceName || 'Unknown Device'
  const rows = await sql`
    INSERT INTO devices (device_id, device_name, stats)
    VALUES (${deviceId}, ${name}, ${JSON.stringify(DEFAULT_STATS)})
    ON CONFLICT (device_id) DO UPDATE
      SET device_name = CASE WHEN ${name} != 'Unknown Device' THEN ${name} ELSE devices.device_name END
    RETURNING *
  `
  const row = rows[0]
  return rowToDevice(row)
}

export async function updateDeviceHeartbeat(deviceId: string, deviceName: string, stats: DeviceStats) {
  await sql`
    INSERT INTO devices (device_id, device_name, last_seen, stats)
    VALUES (${deviceId}, ${deviceName}, NOW(), ${JSON.stringify(stats)})
    ON CONFLICT (device_id) DO UPDATE
      SET last_seen = NOW(),
          device_name = CASE WHEN ${deviceName} != 'Unknown Device' THEN ${deviceName} ELSE devices.device_name END,
          stats = ${JSON.stringify(stats)}
  `
}

export async function getAllDevices(): Promise<DeviceEntry[]> {
  const rows = await sql`SELECT * FROM devices ORDER BY last_seen DESC NULLS LAST`
  return rows.map(rowToDevice)
}

export async function getDevice(deviceId: string): Promise<DeviceEntry | null> {
  const rows = await sql`SELECT * FROM devices WHERE device_id = ${deviceId}`
  if (!rows[0]) return null
  return rowToDevice(rows[0])
}

export async function enqueueCommand(deviceId: string, command: string): Promise<PendingCommand> {
  const id = uuidv4()
  await sql`
    INSERT INTO pending_commands (id, device_id, command)
    VALUES (${id}, ${deviceId}, ${command})
  `
  return { id, command, createdAt: new Date().toISOString() }
}

export async function popCommand(deviceId: string): Promise<PendingCommand | null> {
  const rows = await sql`
    DELETE FROM pending_commands
    WHERE id = (
      SELECT id FROM pending_commands
      WHERE device_id = ${deviceId}
      ORDER BY created_at ASC
      LIMIT 1
    )
    RETURNING *
  `
  if (!rows[0]) return null
  return { id: rows[0].id, command: rows[0].command, createdAt: rows[0].created_at }
}

export async function addResult(deviceId: string, result: CommandResult) {
  await sql`
    INSERT INTO command_history (id, device_id, command, result, timestamp, exit_code)
    VALUES (${result.id}, ${deviceId}, ${result.command}, ${result.result}, ${result.timestamp}, ${result.exitCode ?? 0})
    ON CONFLICT (id) DO NOTHING
  `
  await sql`
    DELETE FROM command_history
    WHERE device_id = ${deviceId}
      AND id NOT IN (
        SELECT id FROM command_history
        WHERE device_id = ${deviceId}
        ORDER BY timestamp DESC
        LIMIT 100
      )
  `
}

export async function getCommandHistory(deviceId: string): Promise<CommandResult[]> {
  const rows = await sql`
    SELECT * FROM command_history
    WHERE device_id = ${deviceId}
    ORDER BY timestamp DESC
    LIMIT 100
  `
  return rows.map(r => ({
    id: r.id,
    command: r.command,
    result: r.result,
    timestamp: r.timestamp,
    exitCode: r.exit_code,
  }))
}

export async function getFileListing(deviceId: string): Promise<FileListing | null> {
  const rows = await sql`SELECT * FROM file_listings WHERE device_id = ${deviceId}`
  if (!rows[0]) return null
  return { path: rows[0].path, entries: rows[0].entries }
}

export async function setFileListing(deviceId: string, path: string, entries: FileEntry[]) {
  await sql`
    INSERT INTO file_listings (device_id, path, entries, updated_at)
    VALUES (${deviceId}, ${path}, ${JSON.stringify(entries)}, NOW())
    ON CONFLICT (device_id) DO UPDATE
      SET path = ${path}, entries = ${JSON.stringify(entries)}, updated_at = NOW()
  `
}

export async function getPendingCommands(deviceId: string): Promise<PendingCommand[]> {
  const rows = await sql`
    SELECT * FROM pending_commands WHERE device_id = ${deviceId} ORDER BY created_at ASC
  `
  return rows.map(r => ({ id: r.id, command: r.command, createdAt: r.created_at }))
}

function rowToDevice(row: Record<string, unknown>): DeviceEntry {
  const lastSeen = row.last_seen ? new Date(row.last_seen as string).toISOString() : null
  const stats = (row.stats as DeviceStats) || DEFAULT_STATS
  return {
    deviceId: row.device_id as string,
    deviceName: row.device_name as string,
    connected: isDeviceOnline({ lastSeen }),
    lastSeen,
    stats,
    pendingCommands: [],
    commandHistory: [],
    fileListing: null,
  }
}
