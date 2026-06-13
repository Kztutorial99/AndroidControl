import { neon } from '@neondatabase/serverless'

const sql = neon(process.env.NEON_DATABASE_URL || process.env.DATABASE_URL!)

export default sql

export async function initSchema() {
  await sql`
    CREATE TABLE IF NOT EXISTS devices (
      device_id TEXT PRIMARY KEY,
      device_name TEXT NOT NULL DEFAULT 'Unknown Device',
      last_seen TIMESTAMPTZ,
      stats JSONB NOT NULL DEFAULT '{}'
    )
  `
  await sql`
    CREATE TABLE IF NOT EXISTS pending_commands (
      id TEXT PRIMARY KEY,
      device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
      command TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `
  await sql`
    CREATE TABLE IF NOT EXISTS command_history (
      id TEXT PRIMARY KEY,
      device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
      command TEXT NOT NULL,
      result TEXT NOT NULL DEFAULT '',
      timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      exit_code INTEGER DEFAULT 0
    )
  `
  await sql`
    CREATE TABLE IF NOT EXISTS file_listings (
      device_id TEXT PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
      path TEXT NOT NULL DEFAULT '/',
      entries JSONB NOT NULL DEFAULT '[]',
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `
}
