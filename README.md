# AndroidConnector
 
> Remote Android device management — control files, run shell commands, read SMS/calls/contacts, track location, and monitor live stats from any browser.

[![Build Android APK](https://github.com/Kztutorial99/AndroidControl/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Kztutorial99/AndroidControl/actions/workflows/build-apk.yml)
[![Vercel](https://img.shields.io/badge/deployed-Vercel-black?logo=vercel)](https://android-control-juldevelopers-projects.vercel.app)

---

## How It Works

```
Browser  ──►  Vercel (Next.js)  ◄──  APK polls every 2s  ──  Android Device
                  │                         │
            REST API + Neon DB         Executes cmd
            /api/device/*              Returns result
```

The APK runs as a persistent foreground service, polls the server every 2 seconds for commands, executes them, and posts results back. No port forwarding or open ports needed on the device — everything goes through the Vercel HTTPS server backed by a Neon PostgreSQL database.

---

## Features

| Feature | Description |
|---------|-------------|
| 📊 **Live Dashboard** | Battery, RAM, CPU, storage, network — auto-refreshes every 5s |
| 📂 **File Manager** | Browse, read, write, delete, move files on device storage |
| 📟 **Terminal** | Run any shell command from browser, see output live with history |
| 💬 **SMS Reader** | Read incoming & outgoing messages with sender & date |
| 📞 **Call Log** | View call history — incoming, outgoing, missed with duration |
| 👥 **Contacts** | Browse full contacts list, search by name or number |
| 📍 **Location** | Get live GPS coordinates with direct Google Maps link |
| 📦 **App Manager** | List all installed apps, search by name or package name |
| 🔑 **Shizuku** | ADB-level access without root — grant perms, read system files |
| 🔄 **Auto-reconnect** | Service restarts on device boot via `BootReceiver` |
| 📱 **Mobile-ready** | Web dashboard fully responsive for phone/tablet |
| 🔋 **Wake Lock** | Service stays alive even with screen off |

---

## Dashboard Pages

| Route | Page | Description |
|-------|------|-------------|
| `/` | Dashboard | Live device stats — battery, RAM, CPU, storage, IP |
| `/terminal` | Terminal | Interactive shell with command history & quick-buttons |
| `/files` | Files | File browser with navigation breadcrumbs |
| `/sms` | SMS | Read SMS messages (select count: 20–200) |
| `/calls` | Calls | Call log with type badges & duration |
| `/contacts` | Contacts | Full contacts list with search |
| `/location` | Location | GPS fix + Google Maps link |
| `/apps` | Apps | Installed apps list with search |
| `/setup` | Setup | Step-by-step setup guide |

---

## Stack

| Layer | Tech |
|-------|------|
| Web frontend | Next.js 14 App Router, Tailwind CSS, TypeScript |
| Web deploy | Vercel |
| Database | Neon PostgreSQL via `pg` Pool |
| Android | Kotlin, OkHttp 4.12, Gson, Shizuku API 13.1.5 |
| Android min SDK | 26 (Android 8.0) |
| Android target | 34 (Android 14) |
| Build CI | GitHub Actions (debug APK on every push) |

---

## Setup

### 1 — Deploy to Vercel

1. Fork / clone this repo
2. Import project at [vercel.com/new](https://vercel.com/new)
3. Add these environment variables in Vercel project settings:

| Key | Value |
|-----|-------|
| `NEON_DATABASE_URL` | Your Neon PostgreSQL connection string |
| `DATABASE_URL` | Same Neon connection string (fallback) |

4. Click **Deploy** — Vercel auto-detects Next.js

> **Get a free Neon DB:** [console.neon.tech](https://console.neon.tech) → Create project → copy the connection string

### 2 — Build the APK

APK is built automatically via **GitHub Actions** on every push to `main`.

1. Go to the **Actions** tab in this repo
2. Click the latest **Build Android APK** run
3. Scroll to **Artifacts** → download the APK zip
4. Extract and install `app-debug.apk` on your Android device

Or trigger manually: **Actions → Build Android APK → Run workflow**

### 3 — Install & Connect

1. Install the APK (enable *Install Unknown Apps* in device Settings)
2. Open **AndroidConnector**
3. The app connects automatically to the hardcoded server URL
4. Status turns green → open the web dashboard in any browser

### 4 — Enable Shizuku (Optional but Powerful)

Shizuku gives ADB shell-level access without a PC or root.

1. Install **Shizuku** from [Play Store](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) or [GitHub](https://github.com/RikkaApps/Shizuku/releases)
2. On device: Settings → About Phone → tap **Build Number** 7× → enable **Wireless Debugging**
3. Open Shizuku → pair via Wireless Debugging → enter pairing code
4. Open AndroidConnector → tap the Shizuku status card → grant permission

---

## Command Reference

Commands are sent from the web terminal. The APK executes them and returns results.

### Shell & System

| Command | Description |
|---------|-------------|
| `shell:<cmd>` | Run shell command (e.g. `shell:df -h`) |
| `shizuku:<cmd>` | Run as ADB UID (requires Shizuku) |
| `shizuku_status` | Check Shizuku availability |
| `ping` | Check device is online |
| `device_info` | Full device JSON dump |
| `get_processes` | Running process list |
| `scan_wifi` | Nearby WiFi networks |
| `ring_device` | Play loud alarm on device |
| `stop_ring` | Stop the alarm |

### Data & Communication

| Command | Description |
|---------|-------------|
| `get_sms:<count>` | Read SMS messages (e.g. `get_sms:50`) |
| `get_calls:<count>` | Read call log (e.g. `get_calls:20`) |
| `get_contacts:<count>` | Read contacts (e.g. `get_contacts:200`) |
| `get_location` | Get GPS coordinates |
| `get_apps` | List installed apps |

### File Operations

| Command | Description |
|---------|-------------|
| `ls_json:/path` | List directory as JSON |
| `read_text:/path` | Read text file (up to 500 lines) |
| `read_b64:/path` | Read file as base64 |
| `write_text:/path` | Write text file |
| `write_b64:/path` | Write binary file (base64 body) |
| `mkdir:/path` | Create directory |
| `delete:/path` | Delete file or directory |
| `move:/src:/dst` | Move or rename file |
| `file_info:/path` | Size, permissions, modified time |

### Package Manager (requires Shizuku)

| Command | Description |
|---------|-------------|
| `pm_grant:<pkg> <perm>` | Grant permission silently |
| `pm_revoke:<pkg> <perm>` | Revoke permission |
| `pm_list_packages` | List all installed packages |
| `settings_put:<ns> <key> <val>` | Write system setting |
| `settings_get:<ns> <key>` | Read system setting |

---

## File Access

| Path | Access |
|------|--------|
| `/storage/emulated/0/**` | ✅ Full R/W (MANAGE_EXTERNAL_STORAGE) |
| `/sdcard/**` | ✅ Full R/W |
| `/proc`, `/sys` | ✅ Read (system/kernel info) |
| `/data/local/tmp` | ✅ R/W via Shizuku |
| `/data/system/` | ✅ Read via Shizuku |
| `/data/data/<other apps>` | ❌ SELinux blocked |
| `/system/` | ❌ Read-only partition |

---

## Database Schema

Neon PostgreSQL — 4 tables, auto-created on first API call:

```sql
devices             -- device_id, device_name, last_seen, stats (JSONB)
pending_commands    -- id, device_id, command, created_at
command_history     -- id, device_id, command, result, timestamp, exit_code
file_listings       -- device_id, path, entries (JSONB), updated_at
```

---

## Android Source Files

```
android/app/src/main/kotlin/com/kztutorial99/androidconnector/
├── MainActivity.kt        — UI, connect/disconnect, Shizuku flow
├── ConnectorService.kt    — Foreground service, poll loop, command dispatch
├── DeviceInfo.kt          — Battery, RAM, CPU, storage, network stats
├── FileOperations.kt      — File R/W, directory listing, move/delete
└── BootReceiver.kt        — Auto-start on boot
```

---

## Web App Routes

```
app/
├── page.tsx               — Dashboard (live stats)
├── terminal/page.tsx      — Shell terminal
├── files/page.tsx         — File manager
├── sms/page.tsx           — SMS reader
├── calls/page.tsx         — Call log
├── contacts/page.tsx      — Contacts list
├── location/page.tsx      — GPS location
├── apps/page.tsx          — App manager
├── setup/page.tsx         — Setup guide
└── api/device/
    ├── heartbeat/         — APK posts stats here every 2s
    ├── poll/              — APK polls for next pending command
    ├── command/           — Web sends commands to device
    ├── result/            — APK posts command output here
    └── files/             — Cached file listing endpoint
```

---

## Target Device

Built and tested on:

| Field | Value |
|-------|-------|
| Model | Vivo Y35 (V2205) |
| OS | Funtouch OS 14 / Android 14 (API 34) |
| SoC | Snapdragon 680 |
| RAM | 8 GB |
| Storage | 128 GB |
| Root | ❌ Not rooted — Shizuku for elevated access |

---

## License

MIT — [Kztutorial99](https://github.com/Kztutorial99)
