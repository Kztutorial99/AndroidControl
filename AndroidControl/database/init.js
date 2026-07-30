/**
 * database/init.js
 * Jalankan sekali untuk membuat semua tabel:
 *   node database/init.js
 *
 * Pastikan DATABASE_URL sudah di-set di environment atau .env
 */

const { Pool } = require('pg')
const fs       = require('fs')
const path     = require('path')

require('dotenv').config({ path: path.join(__dirname, '../.env') })

const url = process.env.DATABASE_URL
if (!url) {
  console.error('❌  DATABASE_URL belum diset!')
  console.error('    Contoh: DATABASE_URL=postgresql://user:pass@host:5432/dbname')
  process.exit(1)
}

const pool = new Pool({
  connectionString: url,
  ssl: url.includes('localhost') || url.includes('127.0.0.1')
    ? false
    : { rejectUnauthorized: false },
})

async function main() {
  console.log('🔌  Menghubungkan ke database...')
  const sql = fs.readFileSync(path.join(__dirname, 'schema.sql'), 'utf8')

  try {
    await pool.query(sql)
    console.log('✅  Semua tabel berhasil dibuat!')

    // Tampilkan daftar tabel
    const { rows } = await pool.query(
      `SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename`
    )
    console.log('\n📋  Tabel yang tersedia:')
    rows.forEach(r => console.log('   •', r.tablename))
  } catch (err) {
    console.error('❌  Error:', err.message)
    process.exit(1)
  } finally {
    await pool.end()
  }
}

main()
