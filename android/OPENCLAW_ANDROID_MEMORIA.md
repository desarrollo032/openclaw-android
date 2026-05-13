# 🦀 OpenClaw Android — Documento de Memoria del Proyecto

**Proyecto:** `com.openclaw.android`
**Plataforma:** Android 12-13 (API 31-33) · Arquitectura: aarch64
**Fecha:** Mayo 2026
**Autor:** Adrian (Adán Trinidad)
**GitHub:** github.com/desarrollo032/openclaw-android

---

## Índice

1. [Resumen del Proyecto](#1-resumen-del-proyecto)
2. [Stack Técnico](#2-stack-técnico)
3. [Estructura del Proyecto](#3-estructura-del-proyecto)
4. [Reglas Críticas — NUNCA Violar](#4-reglas-críticas--nunca-violar)
5. [Cadena de Ejecución del Gateway](#5-cadena-de-ejecución-del-gateway)
6. [Terminal Nativa PTY](#6-terminal-nativa-pty)
7. [Bridge Android ↔ React](#7-bridge-android--react)
8. [Flujo de la App](#8-flujo-de-la-app)
9. [Instalación del Payload](#9-instalación-del-payload)
10. [Problemas Resueltos](#10-problemas-resueltos)
11. [Errores Conocidos y Sus Fixes](#11-errores-conocidos-y-sus-fixes)
12. [Optimizaciones Pendientes](#12-optimizaciones-pendientes)
13. [Dependencias del Proyecto](#13-dependencias-del-proyecto)
14. [Constantes del Proyecto](#14-constantes-del-proyecto)

---

## 1. Resumen del Proyecto

OpenClaw Android es una app Android nativa que integra OpenClaw
como asistente de IA completamente autónomo, sin depender de
Termux, Proot ni ninguna dependencia externa en runtime.

### Logros alcanzados

- ✅ Gateway Node.js corriendo dentro de la app nativa
- ✅ Sin Termux, sin Proot, sin dependencias externas
- ✅ Node.js ejecutando via glibc embebido en ARM64
- ✅ Dashboard accesible via WebView (React frontend)
- ✅ Gateway con auto-reinicio via ForegroundService
- ✅ Terminal PTY nativa con Toybox/sh del sistema
- ✅ Bridge Android ↔ React completamente funcional
- ✅ Instalación desde assets del APK

---

## 2. Stack Técnico

| Componente | Detalle |
|------------|---------|
| Lenguaje Android | Kotlin |
| API Target | 31-33 (Android 12-13) |
| Arquitectura | aarch64 (ARM64) |
| Node.js | `libnode.so` — ELF ARM64 (~120MB) |
| glibc | `libldlinux.so` + libs en `glibc/lib/` |
| Shell | `/system/bin/sh` + Toybox nativo |
| Gateway | `openclaw.mjs` en puerto 18789 |
| Frontend | React + TypeScript en `www/` |
| WebView | Carga `assets/www/index.html` |
| Terminal PTY | Termux terminal-emulator + terminal-view (.aar) |
| Extracción | commons-compress 1.26.0 + xz 1.9 |

---

## 3. Estructura del Proyecto

```
android/
├── app/src/main/
│   ├── assets/
│   │   ├── www/                          ← React compilado
│   │   ├── payload-v2.tar.xz             ← 186MB (Node.js + glibc + OpenClaw)
│   │   └── openclaw-apk-migration.tar.gz ← ~5MB (configuración opcional)
│   ├── jniLibs/arm64-v8a/
│   │   ├── libldlinux.so                 ← ld-linux-aarch64.so.1 (241KB)
│   │   ├── libnode.so                    ← node.real ELF ARM64 (120MB)
│   │   └── libbusybox.so                 ← BusyBox estático (fallback)
│   ├── libs/
│   │   ├── terminal-emulator.aar         ← Termux PTY engine
│   │   └── terminal-view.aar             ← Termux PTY view
│   └── java/com/openclaw/android/
│       ├── MainActivity.kt
│       ├── OpenClawDashboardActivity.kt
│       ├── OpenClawGatewayService.kt
│       ├── OpenClawInstaller.kt
│       ├── OpenClawTerminalActivity.kt
│       ├── OpenClawTerminalManager.kt
│       ├── AndroidBridge.kt
│       ├── OpenClawLogger.kt
│       ├── AssetDetector.kt
│       └── OpenClawConstants.kt
└── www/                                  ← React desarrollo
    └── src/
        ├── types/global.d.ts
        ├── utils/androidBridge.ts
        ├── hooks/
        │   ├── useInstallation.ts
        │   ├── useGatewayStatus.ts
        │   └── useLogs.ts
        └── components/
            ├── InstallationCard.tsx
            └── GatewayStatus.tsx
```

### Contenido del payload-v2.tar.xz

```
./bin/node.real              ← Node.js ELF (DUPLICADO — candidato a eliminar)
./glibc/lib/libc.so.6
./glibc/lib/libm.so.6
./glibc/lib/libpthread.so.0
./glibc/lib/libdl.so.2
./glibc/lib/librt.so.1
./glibc/lib/libresolv.so.2
./glibc/lib/libnss_dns.so.2
./glibc/lib/libnss_files.so.2
./glibc/lib/libstdc++.so.6
./glibc/lib/libgcc_s.so.1
./glibc/lib/libz.so.1
./glibc/lib/libcrypto.so.3
./glibc/lib/libssl.so.3
./lib/node_modules/openclaw/ ← Entry point del gateway
./lib/node_modules/clawdhub/
./etc/tls/cert.pem
```

---

## 4. Reglas Críticas — NUNCA Violar

### ❌ NUNCA hacer esto

```kotlin
// ❌ NUNCA — usar Runtime.exec()
Runtime.getRuntime().exec("node --version")

// ❌ NUNCA — setExecutable en Android 12+
file.setExecutable(true)

// ❌ NUNCA — hardcodear rutas absolutas
val path = "/data/data/com.openclaw.android/..."

// ❌ NUNCA — extraer ELFs a directorios no ejecutables
context.filesDir      // bloqueado por W^X
context.cacheDir      // bloqueado por W^X
context.getDir()      // bloqueado por W^X

// ❌ NUNCA — ejecutar bin/node directamente (es wrapper bash)
ProcessBuilder("${payloadDir}/bin/node", "--version")

// ❌ NUNCA — incluir rutas .mjs en LD_LIBRARY_PATH
put("LD_LIBRARY_PATH", "${libs}:${openclaw.absolutePath}")

// ❌ NUNCA — dejar LD_PRELOAD en el environment
// (rompe la ejecución con glibc embebido)
```

### ✅ SIEMPRE hacer esto

```kotlin
// ✅ SIEMPRE — nativeLibraryDir para binarios ELF
val nativeDir = File(context.applicationInfo.nativeLibraryDir)
val loader   = File(nativeDir, "libldlinux.so")
val nodeReal = File(nativeDir, "libnode.so")

// ✅ SIEMPRE — remover LD_PRELOAD
environment().remove("LD_PRELOAD")

// ✅ SIEMPRE — ProcessBuilder (nunca Runtime.exec)
ProcessBuilder(loader.absolutePath, ...)

// ✅ SIEMPRE — capturar stdout/stderr
redirectErrorStream(true)
process.inputStream.bufferedReader().forEachLine { line ->
    Log.d("OpenClawGW", line)
}

// ✅ SIEMPRE — Dispatchers.IO para operaciones de archivo
lifecycleScope.launch(Dispatchers.IO) { ... }
```

---

## 5. Cadena de Ejecución del Gateway

### Orden OBLIGATORIO e INMUTABLE

```kotlin
val base      = context.getDir("payload", Context.MODE_PRIVATE)
val nativeDir = File(context.applicationInfo.nativeLibraryDir)
val loader    = File(nativeDir, "libldlinux.so")
val nodeReal  = File(nativeDir, "libnode.so")
val glibcLibs = File(base, "glibc/lib").absolutePath
val libs      = "${nativeDir.absolutePath}:${glibcLibs}"
val openclaw  = File(base, "lib/node_modules/openclaw/openclaw.mjs")

ProcessBuilder(
    loader.absolutePath,                      // 1. ld-linux loader
    "--library-path", libs,                   // 2. rutas de .so (solo dirs)
    nodeReal.absolutePath,                    // 3. Node.js binario
    "--disable-warning=ExperimentalWarning",  // 4. flags de Node (DESPUÉS del binario)
    openclaw.absolutePath,                    // 5. script .mjs
    "gateway"                                 // 6. argumento del script
).apply {
    directory(base)
    redirectErrorStream(true)
    environment().apply {
        remove("LD_PRELOAD")                  // CRÍTICO
        put("LD_LIBRARY_PATH", libs)          // solo dirs de .so
        put("OA_GLIBC", "1")
        put("CONTAINER", "1")
        put("TMPDIR", context.cacheDir.absolutePath)
        put("HOME", base.absolutePath)
        put("NODE_PATH", "${base.absolutePath}/lib/node_modules")
        put("OPENCLAW_HOME", "${context.filesDir.absolutePath}/.openclaw")
        put("SSL_CERT_FILE", "${base.absolutePath}/etc/tls/cert.pem")
        put("PATH", "${base.absolutePath}/bin:${nativeDir.absolutePath}:/system/bin")
    }
}
```

### Regla de argumentos

```
Todo argumento ANTES de libnode.so  → va al loader (ldlinux)
Todo argumento DESPUÉS de libnode.so → va a Node.js
```

---

## 6. Terminal Nativa PTY

### Stack

- **Motor PTY:** Termux terminal-emulator.aar + terminal-view.aar
- **Shell principal:** `/system/bin/sh` (Toybox nativo — sin seccomp issues)
- **Fallback:** `libbusybox.so` (solo si /system/bin/sh no existe)

### Por qué Toybox en vez de BusyBox

| | Toybox nativo | BusyBox (.so) |
|---|---|---|
| Requiere root | ❌ No | ❌ No |
| Seccomp Android 12+ | ✅ Sin problemas | ❌ "Bad system call" |
| Peso al APK | ✅ 0MB | ⚠️ +1.5MB |
| Disponibilidad | ✅ Siempre en Android 6+ | Depende del build |
| Comandos disponibles | ✅ 200+ | ✅ 200+ |

### Environment del terminal

```kotlin
arrayOf(
    "HOME=${payloadDir.absolutePath}",
    "TERM=xterm-256color",
    "COLORTERM=truecolor",
    "PATH=${payloadDir}/bin:${nativeDir}:/system/bin:/system/xbin",
    "LD_LIBRARY_PATH=${nativeDir}:${glibcLibs}",
    "TMPDIR=${context.cacheDir.absolutePath}",
    "OPENCLAW_HOME=${context.filesDir.absolutePath}/.openclaw",
    "NODE_PATH=${payloadDir}/lib/node_modules",
    "SSL_CERT_FILE=${payloadDir}/etc/tls/cert.pem",
    "PS1=$ ",                    // prompt corto
    "LANG=en_US.UTF-8"
    // SIN LD_PRELOAD
)
```

### Configuración UI móvil

```kotlin
// Fuente
private var currentFontSizeSp = 18f
private val MIN_FONT_SP = 14f
private val MAX_FONT_SP = 32f

// Barra de teclas
val barHeight = (56 * dp).toInt()   // 56dp altura
val textSize  = 15f                  // 15sp texto
val minWidth  = (56 * dp).toInt()   // 56dp touch target

// Padding terminal
terminalView.setPadding(16, 8, 16, (64 * dp).toInt()) // 64dp bottom

// AndroidManifest
android:windowSoftInputMode="adjustResize"
```

### Orden de teclas en barra

```
[TAB][ESC][CTRL][ALT][↑][↓][←][→][/][~][-][|][_][#][@][KBD]
```

---

## 7. Bridge Android ↔ React

### Tabla completa de métodos

| Android `@JavascriptInterface` | React `androidBridge.ts` | Descripción |
|-------------------------------|--------------------------|-------------|
| `checkInstallation()` | `AndroidBridge.checkInstallation()` | Estado del payload |
| `startInstallation()` | `AndroidBridge.startInstallation()` | Iniciar extracción |
| `pickMigrationFile()` | `AndroidBridge.pickMigrationFile()` | File picker |
| `startGateway()` | `AndroidBridge.startGateway()` | Iniciar gateway |
| `stopGateway()` | `AndroidBridge.stopGateway()` | Detener gateway |
| `getGatewayState()` | `AndroidBridge.getGatewayState()` | Estado actual |
| `getAuthToken()` | `AndroidBridge.getAuthToken()` | Token JWT |
| `openTerminal()` | `AndroidBridge.openTerminal()` | Abrir terminal |
| `getLogs(lines)` | `AndroidBridge.getLogs(lines)` | Últimas N líneas |
| `clearLogs()` | `AndroidBridge.clearLogs()` | Limpiar logs |
| `getSystemInfo()` | `AndroidBridge.getSystemInfo()` | Info del sistema |

### Tabla de eventos Android → React

| Evento | Payload JSON | Disparado en | Escuchado en |
|--------|-------------|-------------|-------------|
| `android:onInstallProgress` | `{step, totalSteps, extractedMB, totalMB, percent, currentFile}` | Installer | `useInstallation` |
| `android:onInstallComplete` | `{success: true}` | Installer | `useInstallation` |
| `android:onInstallError` | `{error: string}` | Installer | `useInstallation` |
| `android:onMigrationFilePicked` | `{filename, sizeMB}` | DashboardActivity | `useInstallation` |
| `android:onGatewayState` | `{running, uptime, port, pid}` | GatewayService | `useGatewayStatus` |
| `android:onTokenRefresh` | `{token: string}` | GatewayService | `api/client.ts` |

### Cómo emitir eventos desde Android

```kotlin
fun emit(event: String, dataJson: String) {
    activity.runOnUiThread {
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent(" +
            "'android:$event',{detail:$dataJson}));", null
        )
    }
}
```

### Cómo escuchar eventos en React

```typescript
AndroidBridge.on('onInstallProgress', (data: InstallProgress) => {
    setProgress(data)
})
```

### getSystemInfo() — IMPORTANTE

```kotlin
// ✅ CORRECTO — leer desde package.json
val pkgJson = File(payloadDir,
    "lib/node_modules/openclaw/package.json")
val version = JSONObject(pkgJson.readText())
    .optString("version", "unknown")

// ❌ INCORRECTO — ejecutar bin/node directamente
// Bloqueado por W^X policy de Android 12+
ProcessBuilder("${payloadDir}/bin/node", "--version")
```

---

## 8. Flujo de la App

```
Abrir app
    ↓
MainActivity.kt
    → Solo routing, sin lógica
    → startActivity(OpenClawDashboardActivity)
    → finish()
    ↓
OpenClawDashboardActivity.kt
    → setupWebView() ← PRIMERO, sin condiciones
    → Carga: file:///android_asset/www/index.html
    → setupAndroidBridge()
    → verifica isPayloadReady() en background
    ↓
    ├─ Payload NO instalado
    │      → React muestra <InstallationCard />
    │        dentro del dashboard
    │      → Usuario toca "Instalar"
    │      → AndroidBridge.startInstallation()
    │      → Extracción en Dispatchers.IO
    │      → Progreso via eventos CustomEvent
    │      → Al completar: card desaparece
    │
    └─ Payload SÍ instalado
           → Dashboard normal
           → Usuario toca "Iniciar Gateway"
           → startForegroundService(GatewayService)
           → Polling /health cada 2s hasta 60s
           → Al responder 200: WebUI activo
```

---

## 9. Instalación del Payload

### Assets en el APK

| Archivo | Tamaño | Estado |
|---------|--------|--------|
| `payload-v2.tar.xz` | ~186MB | **OBLIGATORIO** |
| `openclaw-apk-migration.tar.gz` | ~5MB | **OPCIONAL** |

### CRÍTICO — build.gradle.kts

```kotlin
// Sin esto, los archivos .gz no se detectan en assets
android {
    aaptOptions {
        noCompress += listOf("xz", "gz", "tar", "tar.xz", "tar.gz")
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}
```

### Verificar instalación exitosa

```kotlin
fun isPayloadReady(context: Context): Boolean {
    val payloadDir = context.getDir("payload", Context.MODE_PRIVATE)
    return File(payloadDir,
        "lib/node_modules/openclaw/openclaw.mjs").exists()
        && File(context.applicationInfo.nativeLibraryDir,
            "libnode.so").exists()
}
```

### openclaw-apk-migration.tar.gz es OPCIONAL

```kotlin
// Si no está en assets → NO es error
// Ofrecer file picker al usuario
// Si falla → warning, NO bloquear la app
```

---

## 10. Problemas Resueltos

### Problema 1 — bin/node era wrapper bash

**Error:** `bin/node` contiene `#!/data/data/com.termux/files/usr/bin/bash`

**Solución:** Usar `libnode.so` desde `nativeLibraryDir` (el ELF real)

### Problema 2 — Permission denied en libldlinux.so

**Error:** `Cannot run program "ld-linux-aarch64.so.1": error=13`

**Solución:** Los ELFs deben estar en `jniLibs/arm64-v8a/` como `.so`
Android los instala en `nativeLibraryDir` con permisos de ejecución garantizados.

### Problema 3 — W^X Policy Android 12+

**Error:** `execve()` bloqueado en directorios escritos por la app

**Directorios que NO funcionan para ELFs:**
- `filesDir` ❌
- `cacheDir` ❌
- `getDir()` ❌

**Único directorio que SÍ funciona:**
- `nativeLibraryDir` ✅ (instalado por el sistema)

### Problema 4 — TerminalView APIs distintas según versión .aar

**Error:** `Unresolved reference: textSize`, `setTerminalColors`, `stopSelectionMode`

**Fix:**
```kotlin
// textSize → setTextSize()
// setTerminalColors() → eliminar (no existe)
// stopSelectionMode() → stopTextSelectionMode()
// startSelectionMode() → startTextSelectionMode()
```

### Problema 5 — Bad system call con BusyBox

**Error:** `ls: Bad system call` (SIGSYS — seccomp bloquea BusyBox)

**Solución:** Usar `/system/bin/sh` + Toybox nativo
```kotlin
fun getShellPath() = "/system/bin/sh" // siempre disponible, sin seccomp issues
```

### Problema 6 — invalid ELF header en openclaw.mjs

**Error:** `openclaw.mjs: error while loading shared libraries: invalid ELF header`

**Causa:** El loader (`libldlinux.so`) recibe `openclaw.mjs` antes que `libnode.so`,
o `openclaw.mjs` está en `LD_LIBRARY_PATH`.

**Fix:** Orden correcto en ProcessBuilder:
```
ldlinux → --library-path → libnode.so → openclaw.mjs → gateway
```

### Problema 7 — unrecognized option en libldlinux.so

**Error:** `libldlinux.so: unrecognized option '--disable-warning=ExperimentalWarning'`

**Causa:** Flag de Node.js pasado ANTES de `libnode.so` → lo recibe el loader.

**Fix:** Mover flags de Node.js DESPUÉS de `libnode.so`:
```kotlin
ProcessBuilder(
    loader.absolutePath,
    "--library-path", libs,
    nodeReal.absolutePath,
    "--disable-warning=ExperimentalWarning", // ← DESPUÉS del binario Node
    openclaw.absolutePath,
    "gateway"
)
```

### Problema 8 — openclaw-apk-migration no detectado

**Error:** AssetDetector reporta migration como "no encontrado" aunque existe.

**Causa:** aapt comprime el `.gz` durante el build.

**Fix:** `noCompress += listOf("gz")` en build.gradle.kts

### Problema 9 — Versiones ENTORNO muestran "Permission denied"

**Error:** Dashboard muestra error al intentar obtener versión de Node.js y openclaw.

**Causa:** React ejecuta `bin/node --version` directamente → bloqueado por W^X.

**Fix:** Leer versiones desde `package.json`, no ejecutar binarios.

---

## 11. Errores Conocidos y Sus Fixes

### Warnings de compilación (no bloquean build)

```kotlin
// WebView deprecated → usar @Suppress o WebViewAssetLoader
@Suppress("DEPRECATION")
settings.allowFileAccessFromFileURLs = true

// toggleSoftInput deprecated → usar windowInsetsController en API 31+
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    terminalView.windowInsetsController
        ?.show(android.view.WindowInsets.Type.ime())
} else {
    @Suppress("DEPRECATION")
    imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
}
```

### Funciones duplicadas (error de compilación)

Causado por IA que genera el mismo archivo múltiples veces.
**Fix:** Cada función debe existir exactamente UNA vez en su archivo.

Funciones afectadas históricamente:
- `isBusyboxValid()` en `OpenClawTerminalManager.kt`
- `createBusyboxSymlinks()` en `OpenClawTerminalManager.kt`
- `uninstall()` en `OpenClawInstaller.kt`

---

## 12. Optimizaciones Pendientes

### Alta prioridad

- [ ] **Eliminar `./bin/node.real` del tar** — ya está como `libnode.so` en jniLibs.
  Ahorro estimado: ~120MB en APK descomprimido.
  
  ```python
  # Verificar si existe en el tar
  python analyze_payload.py
  ```

- [ ] **SHA-256 verificación del payload** — detectar instalaciones corruptas antes de extraer.

- [ ] **Logs persistentes rotativos** — escribir a `filesDir/logs/openclaw.log` (máx 2MB).

### Media prioridad

- [ ] **Autenticación dashboard** — token JWT para proteger puerto 18789.

- [ ] **Watchdog con WorkManager** — relanzar gateway si ROMs agresivos matan el servicio.

- [ ] **Update incremental del payload** — solo distribuir cambios (~5MB) en vez de 186MB.

### Baja prioridad

- [ ] **Múltiples sesiones PTY** — tabs en la terminal.

- [ ] **Backup/restore de configuración** — exportar `.openclaw/` a Downloads.

- [ ] **Port forwarding configurable** — el puerto 18789 está hardcodeado.

---

## 13. Dependencias del Proyecto

### build.gradle.kts

```kotlin
dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("org.tukaani:xz:1.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("com.google.android.material:material:1.11.0")
    // Termux PTY — desde libs locales (no JitPack — archivos incompletos)
    implementation(files("libs/terminal-emulator.aar"))
    implementation(files("libs/terminal-view.aar"))
}
```

### settings.gradle.kts

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### AndroidManifest.xml — permisos

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### Nota sobre terminal-emulator.aar y terminal-view.aar

Los archivos descargados de JitPack resultaron incompletos (~76KB y ~43KB).
Se deben obtener compilando desde el repositorio oficial de Termux
o extrayendo del APK oficial de Termux (no Play Store).

---

## 14. Constantes del Proyecto

```kotlin
object OpenClawConstants {
    // Assets
    const val PAYLOAD_ASSET      = "payload-v2.tar.xz"
    const val MIGRATION_ASSET    = "openclaw-apk-migration.tar.gz"

    // Directorios
    const val PAYLOAD_DIR_NAME   = "payload"
    const val OPENCLAW_DIR_NAME  = ".openclaw"
    const val LOGS_DIR_NAME      = "logs"

    // Gateway
    const val GATEWAY_PORT       = 18789
    const val GATEWAY_HOST       = "127.0.0.1"
    const val HEALTH_ENDPOINT    = "/health"
    const val GATEWAY_TIMEOUT_MS = 60_000L
    const val HEALTH_POLL_MS     = 2_000L

    // Binarios en nativeLibraryDir
    const val LIB_LDLINUX        = "libldlinux.so"
    const val LIB_NODE           = "libnode.so"
    const val LIB_BUSYBOX        = "libbusybox.so"

    // Terminal
    const val TERMINAL_FONT_DEFAULT = 18f
    const val TERMINAL_FONT_MIN     = 14f
    const val TERMINAL_FONT_MAX     = 32f
    const val TERMINAL_SCROLLBACK   = 4000

    // Logs
    const val LOG_MAX_BYTES      = 2 * 1024 * 1024 // 2MB
    const val LOG_TAG_GATEWAY    = "OpenClawGW"
    const val LOG_TAG_INSTALLER  = "OpenClawInstaller"
    const val LOG_TAG_BRIDGE     = "OpenClawBridge"
    const val LOG_TAG_TERMINAL   = "OpenClawTerminal"
}
```

---

## Notas finales

### Toybox vs BusyBox

Toybox es el reemplazo moderno de BusyBox en Android.
**No requiere root.** Viene preinstalado desde Android 6.0.
Es la shell recomendada para el terminal de esta app
porque no tiene problemas de seccomp en Android 12+.

```bash
# Verificar disponibilidad (en terminal de la app)
/system/bin/toybox --version
ls /system/bin/toybox
```

### Sobre los .aar de Termux

Los `.aar` descargados de JitPack resultaron incompletos.
La solución fue usarlos igual (funcionan para compilar)
porque las APIs que necesitamos están presentes,
aunque el tamaño sea menor al esperado.

### Sobre el frontend React

- Desarrollo: `android/www/`
- Compilado: `android/app/src/main/assets/www/`
- NO usar BrowserRouter — usar HashRouter o MemoryRouter
- `InstallationCard` retorna `null` si `AndroidBridge` no está disponible
  (permite desarrollar en browser sin Android)

---

*Documento generado: Mayo 2026*
*Proyecto: com.openclaw.android — Adrian (Adán Trinidad)*
