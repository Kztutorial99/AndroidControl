#!/bin/bash
# ============================================================
#  AndroidConnector — Setup Database Sendiri (Self-Hosted)
#  Jalankan sekali di server Anda:
#    chmod +x database/setup.sh
#    ./database/setup.sh
# ============================================================

set -e

echo "======================================"
echo "  AndroidConnector — Database Setup"
echo "======================================"

# Cek apakah Docker terinstall
if ! command -v docker &> /dev/null; then
    echo ""
    echo "❌  Docker belum terinstall."
    echo "    Install Docker dulu:"
    echo "    curl -fsSL https://get.docker.com | sh"
    exit 1
fi

if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null 2>&1; then
    echo "❌  Docker Compose belum terinstall."
    exit 1
fi

# Buat file .env jika belum ada
if [ ! -f ".env" ]; then
    echo ""
    echo "📝  Membuat file .env..."
    cp .env.example .env

    # Generate password random
    PASS=$(openssl rand -base64 16 | tr -d '=/+' | head -c 20)
    sed -i "s/ganti_password_ini/$PASS/g" .env
    echo "✅  Password database dibuat otomatis: $PASS"
    echo "    (tersimpan di file .env)"
fi

echo ""
echo "🐳  Menjalankan database PostgreSQL..."
docker compose up -d db

echo ""
echo "⏳  Menunggu database siap..."
sleep 5

# Ambil DATABASE_URL dari .env
export $(grep -v '^#' .env | xargs)
DB_URL="postgresql://android_user:${DB_PASSWORD}@localhost:5432/androidconnector"

echo ""
echo "📋  Membuat tabel..."
docker compose exec db psql -U android_user -d androidconnector -f /docker-entrypoint-initdb.d/schema.sql 2>/dev/null || true

echo ""
echo "======================================"
echo "✅  Database berhasil dibuat!"
echo ""
echo "📡  Connection String:"
echo "    $DB_URL"
echo ""
echo "🌐  Pasang di Vercel:"
echo "    Settings → Environment Variables"
echo "    Key: DATABASE_URL"
echo "    Value: $DB_URL"
echo "    (ganti localhost dengan IP server Anda)"
echo "======================================"
