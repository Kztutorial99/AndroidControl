# AndroidConnector

Remote Android device management from the web. Access files, run commands, monitor device stats — all from your browser.

## Architecture

```
Web Browser ←→ Vercel (Next.js API) ←→ APK on Android (HTTP polling)
```

## Features

- 📊 **Dashboard** — Battery, RAM, CPU, storage, network stats live
- 📂 **File Manager** — Browse, read, write, delete files on device storage
- 📟 **Terminal** — Run shell commands on device from browser
- 🔑 **Shizuku Support** — ADB-level access: grant permissions, read system files
- 🔄 **Auto-reconnect** — APK reconnects automatically on reboot

## Stack

| Layer | Tech |
|-------|------|
| Web | Next.js 14, Tailwind CSS |
| Deploy | Vercel |
| Android | Kotlin, OkHttp, Shizuku API |
| Auth | Shared secret token |

## Setup

See the **[Setup Guide](https://your-app.vercel.app/setup)** after deploying.

### Quick Start

1. Deploy to Vercel, set `DEVICE_TOKEN` env var
2. Build APK via GitHub Actions (Actions tab → download artifact)
3. Install APK on Android device
4. Enter server URL + token → tap Connect
5. (Optional) Enable Shizuku for elevated access

## Commands

| Command | Description |
|---------|-------------|
| `ls_json:/path` | List directory as JSON |
| `read_text:/path` | Read text file (first 500 lines) |
| `read_b64:/path` | Read file as base64 |
| `write_text:/path` + extra | Write text file |
| `write_b64:/path` + extra | Write binary file |
| `mkdir:/path` | Create directory |
| `delete:/path` | Delete file or directory |
| `move:/src:/dst` | Move or rename |
| `shell:command` | Run shell command (app UID) |
| `shizuku:command` | Run via Shizuku (ADB shell UID) |
| `shizuku:pm grant pkg perm` | Grant permission silently |
| `shizuku_status` | Check Shizuku availability |
| `device_info` | Full device JSON |
| `ping` | Health check |

## File Access

| Path | Access |
|------|--------|
| `/storage/emulated/0/**` | ✅ Full R/W (MANAGE_EXTERNAL_STORAGE) |
| `/sdcard/**` | ✅ Full R/W |
| `/proc`, `/sys` | ✅ Read (system info) |
| `/data/local/tmp` | ✅ R/W via Shizuku |
| `/data/system/` | ✅ Read via Shizuku |
| `/data/data/<other apps>` | ❌ SELinux blocked |
| `/system/` | ❌ Read-only partition |

## Device

Tested on: **Vivo Y35 (V2205)** — Funtouch OS 14 / Android 14 / Snapdragon 680

## License

MIT — by [Kztutorial99](https://github.com/Kztutorial99)
