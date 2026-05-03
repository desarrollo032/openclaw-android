# OpenClaw Android

> Ejecuta OpenClaw directamente en tu dispositivo Android mediante un entorno Termux completo, con terminal nativa PTY, interfaz WebView React y actualizaciones OTA.

---

## ¿Qué es esto?

APK autónoma (~5MB) que instala y ejecuta OpenClaw en Android sin necesidad de root. Incluye:

- **Terminal PTY nativa** — sesiones múltiples con emulador completo
- **Interfaz WebView React** — setup, dashboard y configuración
- **Bootstrap Termux** — entorno Linux completo con apt, bash, glibc
- **grun (glibc-runner)** — ejecutor obligatorio para Node.js en Android
- **OTA** — actualizaciones de UI sin reinstalar el APK

---

## Requisitos del sistema

| Componente | Versión mínima |
|---|---|
| Android | 7.0 (API 24) |
| Arquitectura | arm64-v8a |
| JDK (build) | 21 |
| Android SDK (build) | API 36 |
| NDK (build) | 28+ |
| Node.js (build UI) | 22+ |

---

## Instalación manual validada

La app replica exactamente este flujo de instalación manual en Termux:

```bash
# 1. Configurar almacenamiento
termux-setup-storage

# 2. Instalar dependencias glibc
pkg install glibc-runner

# 3. Instalar OpenClaw
npm install -g openclaw

# 4. Ejecutar gateway (SIEMPRE con grun, nunca node directamente)
grun openclaw gateway --host 0.0.0.0
```

> ⚠️ **Crítico:** Node.js en Android requiere `grun` (glibc-runner). Llamar `node` directamente falla.

---

## Rutas del sistema

```
/data/data/com.termux/files/
├── home/
│   ├── .openclaw-android/
│   │   ├── bin/           ← binarios OpenClaw + grun
│   │   ├── installed.json ← marcador de instalación completa
│   │   └── post-setup.sh
│   └── openclaw-start.sh  ← wrapper script (usa grun)
└── usr/                   ← bootstrap Termux (bash, apt, etc.)
```

---

## Arquitectura

```
APK (~5MB)
├── Native:     TerminalView  — PTY via libtermux.so
├── WebView:    React SPA     — setup, dashboard, settings
├── JsBridge:   34 métodos    — WebView ↔ Kotlin (8 dominios)
├── EventBridge:              — Kotlin → WebView (CustomEvent)
└── OTA:        www.zip       — actualización atómica de UI
```

### Flujo de ejecución

```
App inicia
  └─► requestStoragePermissions()
        └─► termux-setup-storage (auto yes)
              └─► Bootstrap Termux (si no instalado)
                    └─► post-setup.sh
                          └─► installed.json ✓
                                └─► openclaw-start.sh
                                      └─► grun openclaw gateway
```

---

## Estructura del proyecto

```
android/
├── app/src/main/
│   ├── java/com/openclaw/android/
│   │   ├── MainActivity.kt           # Contenedor WebView + TerminalView + permisos
│   │   ├── OpenClawService.kt        # Foreground Service (START_STICKY)
│   │   ├── InstallerManager.kt       # Orquestador de instalación (online/offline)
│   │   ├── PayloadExtractor.kt       # Extracción streaming tar.gz (sin saturar RAM)
│   │   ├── PayloadManager.kt         # Fachada de compatibilidad sobre InstallerManager
│   │   ├── OpenClawManager.kt        # Instalación online vía npm
│   │   ├── RootfsManager.kt          # Instalación desde rootfs pre-construido
│   │   ├── InstallValidator.kt       # Verificación post-instalación
│   │   ├── JsBridge.kt               # 34 métodos @JavascriptInterface
│   │   ├── EventBridge.kt            # Eventos Kotlin → WebView
│   │   ├── CommandRunner.kt          # bash -l -c + grun + rutas Termux + wrapper
│   │   ├── EnvironmentBuilder.kt     # Variables de entorno Termux reales
│   │   ├── UrlResolver.kt            # URLs BuildConfig + config.json remoto
│   │   ├── TerminalManager.kt        # Gestión PTY terminal
│   │   ├── TerminalSessionManager.kt # Gestión multi-sesión terminal
│   │   ├── BootReceiver.kt           # Auto-arranque al iniciar el dispositivo
│   │   └── AppLogger.kt              # Logging centralizado
│   ├── assets/
│   │   ├── www/                      # UI React compilada (fallback)
│   │   ├── post-setup.sh             # Script de configuración post-extracción
│   │   ├── run-openclaw.sh           # Lanzador del gateway OpenClaw
│   │   ├── env-init.sh               # Inicialización de variables de entorno
│   │   └── glibc-compat.js           # Shim Node.js para compatibilidad glibc
│   └── res/                          # Recursos Android
├── app/src/test/java/com/openclaw/android/
│   ├── AppLoggerTest.kt              # 7 tests — delegación de Log
│   ├── CommandRunnerTest.kt          # 22 tests — runSync, constantes, env
│   ├── EnvironmentBuilderTest.kt     # 27 tests — variables de entorno
│   ├── BootstrapManagerTest.kt       # 14 tests — detección, wrapper, ELF
│   └── VersionCompareTest.kt         # 8 tests — lógica semver OTA
├── www/                              # React SPA (UI producción)
│   └── src/
│       ├── lib/bridge.ts             # Wrapper tipado JsBridge (34 métodos)
│       ├── lib/useNativeEvent.ts     # Hook EventBridge para React
│       ├── lib/router.tsx            # Router hash-based (file:// compatible)
│       ├── components/               # Componentes reutilizables (Button, Card)
│       ├── i18n/                     # Internacionalización (EN, ES)
│       └── screens/                  # Pantallas: Dashboard, Setup, Settings*
├── terminal-emulator/                # Emulador PTY (fork ReTerminal)
└── terminal-view/                    # Renderizado terminal (fork ReTerminal)
```

---

## Build

### APK debug

```bash
cd android
./gradlew assembleDebug
# Salida: app/build/outputs/apk/debug/app-debug.apk
```

### APK release

```bash
# Configurar local.properties con keystore
./gradlew assembleRelease
```

### Tests unitarios

```bash
cd android
./gradlew test
# Reporte: app/build/reports/tests/test/index.html
```

### UI WebView

```bash
cd android/www
npm install
npm run build        # Salida: dist/
npm run build:zip    # Salida: www.zip (para OTA)
```

---

## JsBridge API

| Dominio | Métodos | Descripción |
|---|---|---|
| Terminal | 8 | show/hide, crear/cambiar/cerrar sesiones, escribir |
| Setup | 3 | estado bootstrap + openclaw, iniciar setup |
| Platform | 6 | instalar/desinstalar/cambiar plataformas |
| Tools | 5 | instalar/desinstalar herramientas CLI |
| Commands | 4 | sync/async, testGrunNode, launchGateway |
| Updates | 3 | check/apply OTA, info APK |
| System | 7 | info app, batería, permisos, almacenamiento |
| Storage | 1 | termux-setup-storage |

---

## Decisiones de diseño

| Decisión | Motivo |
|---|---|
| `targetSdk 28` | Bypass W^X — permite exec en `/data/data/` |
| `minSdk 24` | Requisito bootstrap apt-android-7 |
| `bash -l -c` siempre | Carga entorno login completo de Termux |
| `grun` obligatorio | Node.js en Android necesita glibc-runner |
| `installed.json` | Detección fiable de instalación completa |
| Scope IO compartido | Un solo `CoroutineScope(Dispatchers.IO)` en JsBridge — evita crear thread pools por operación |
| Hash routing | `file://` no soporta History API |
| Sin CSS framework | Bundle mínimo para entrega OTA |
| Rutas Termux reales | `/data/data/com.termux/files/` — independiente del paquete app |

---

## Permisos Android

| Permiso | Uso |
|---|---|
| `INTERNET` | Descarga bootstrap y actualizaciones |
| `FOREGROUND_SERVICE` | Mantener terminal activa en background |
| `WAKE_LOCK` | Evitar suspensión durante instalación |
| `RECEIVE_BOOT_COMPLETED` | Auto-inicio del gateway al arrancar |
| `READ/WRITE_EXTERNAL_STORAGE` | Android 6–10 |
| `MANAGE_EXTERNAL_STORAGE` | Android 11+ (termux-setup-storage) |

---

## Dependencias clave

| Librería | Versión | Uso |
|---|---|---|
| AGP | 9.1.0 | Build system Android |
| Kotlin | 2.2.21 | Lenguaje principal |
| kotlinx-coroutines | 1.10.2 | Operaciones async |
| gson | 2.13.2 | Serialización JSON |
| JUnit5 | 6.0.3 | Tests unitarios |
| MockK | 1.14.9 | Mocking en tests |
| detekt | 1.23.8 | Análisis estático |
| ktlint | 14.2.0 | Formato de código |

---

## Licencia

GPL v3
