# AndroidConnector

> Remote Android device management — control files, run shell commands, and monitor stats from any browser.

[![Build Android APK](https://github.com/Kztutorial99/AndroidControl/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Kztutorial99/AndroidControl/actions/workflows/build-apk.yml)

---

## How It Works

```
Browser  ──►  Vercel (Next.js)  ◄──  APK polls every 2s  ──  Vivo Y35
                  │                         │
              REST API                 Executes cmd
              /api/command             Returns result
```

The APK runs as a foreground service, polls the server for commands, executes them, and posts results back. No open ports needed on the device — everything goes through the Vercel server.

---

## Features

| Feature | Description |
|---------|-------------|
| 📊 **Live Dashboard** | Battery, RAM, CPU, storage, network — refreshes every 5s |
| 📂 **File Manager** | Browse, read, write, delete, move files on device storage |
| 📟 **Terminal** | Run shell commands from the browser, see output live |
| 🔑 **Shizuku** | ADB-level access without root — grant perms, read system files |
| 🔄 **Auto-reconnect** | Service restarts on boot via `BootReceiver` |
| 🔒 **Token Auth** | All requests authenticated with shared secret token |
| 📱 **Mobile-ready** | Web dashboard fully responsive for phone/tablet |

---

## Stack

| Layer | Tech |
|-------|------|
| Web frontend | Next.js 14 App Router, Tailwind CSS, TypeScript |
| Web deploy | Vercel (region: `sin1`) |
| Android | Kotlin, OkHttp 4.12, Gson, Shizuku API 13.1.5 |
| Android min SDK | 26 (Android 8.0) |
| Android target | 34 (Android 14) |
| Auth | Shared secret `DEVICE_TOKEN` env var |

---

## Setup

### 1 — Deploy to Vercel

1. Fork / clone this repo
2. Import project into [vercel.com](https://vercel.com)
3. Add environment variable: `DEVICE_TOKEN` = any random secret string (e.g. `openssl rand -hex 20`)
4. Deploy → copy your `.vercel.app` URL

### 2 — Build the APK

APK is built automatically via **GitHub Actions** on every push.

1. Go to **Actions** tab in this repo
2. Click the latest **Build Android APK** run
3. Scroll to **Artifacts** → download `app-release-unsigned.apk`

Or trigger manually: **Actions → Build Android APK → Run workflow**

### 3 — Install & Connect

1. Install the APK on your Android device (enable *Unknown Sources*)
2. Open **AndroidConnector**
3. Enter your Vercel URL and the token you set in step 1
4. Tap **CONNECT**
5. (Optional) Enable **Shizuku** for ADB-level access — see [shizuku.rikka.app](https://shizuku.rikka.app)

### 4 — Open the Dashboard

Visit your Vercel URL in any browser — dashboard, terminal, and file manager are all there.

---

## Command Reference

Commands are sent from the web terminal or file manager to the device.

### Shell & Shizuku

| Command | Description |
|---------|-------------|
| `shell:<cmd>` | Run shell command as app UID |
| `shizuku:<cmd>` | Run shell command as ADB UID (requires Shizuku) |
| `shizuku_status` | Check if Shizuku is active and granted |
| `ping` | Check device is online |
| `device_info` | Return full device JSON |

### File Operations

| Command | Description |
|---------|-------------|
| `ls_json:/path` | List directory contents as JSON |
| `read_text:/path` | Read text file (up to 500 lines) |
| `read_b64:/path` | Read file as base64 (any file type) |
| `write_text:/path` + body | Write text file |
| `write_b64:/path` + body | Write binary file (base64 body) |
| `mkdir:/path` | Create directory |
| `delete:/path` | Delete file or directory |
| `move:/src:/dst` | Move or rename file |
| `file_info:/path` | Size, permissions, modified time |

### Package Manager (Shizuku)

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
| `/system/` | ❌ Read-only system partition |

---

## Android Source Files

```
android/app/src/main/kotlin/com/kztutorial99/androidconnector/
├── MainActivity.kt        — UI, Shizuku permission flow, connect/disconnect
├── ConnectorService.kt    — Foreground service, poll loop, command dispatch
├── DeviceInfo.kt          — Battery, RAM, CPU, storage, network stats
├── FileOperations.kt      — File R/W, directory listing, move/delete
└── BootReceiver.kt        — Auto-start service on device boot
```

---

## Web App Routes

```
app/
├── page.tsx               — Dashboard (live stats)
├── terminal/page.tsx      — Shell terminal
├── files/page.tsx         — File manager
├── setup/page.tsx         — Setup guide
└── api/
    ├── heartbeat/         — APK posts device stats here
    ├── poll/              — APK polls for pending commands
    ├── command/           — Web sends commands here
    ├── result/            — APK posts command output here
    └── files/             — File download endpoint
```

---

## Target Device

Built and tested on:

| Field | Value |
|-------|-------|
| Model | Vivo Y35 (V2205) |
| OS | Funtouch OS 14 / Android 14 |
| SoC | Snapdragon 680 |
| RAM | 8 GB |
| Storage | 128 GB |
| Kernel | 4.19.157-perf+ |
| Root | ❌ Not rooted — Shizuku used for elevated access |

---

## License

MIT — [Kztutorial99](https://github.com/Kztutorial99)
