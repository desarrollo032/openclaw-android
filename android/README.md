# Módulo Android

App nativa Kotlin que ejecuta el **gateway Node.js de OpenClaw** dentro del sandbox de Android, sin depender de Termux ni Proot.

---

## Índice

- [Arquitectura general](#arquitectura-general)
- [Requisitos](#requisitos)
- [Build](#build)
- [Instalación en dispositivo](#instalación-en-dispositivo)
- [Estructura de carpetas](#estructura-de-carpetas)
- [Documentación detallada](#documentación-detallada)

---

## Arquitectura general

```text
┌──────────────────────────────────────────────────────────┐
│                   UI (React + Vite)                      │
│      Ejecutándose en WebView con WebViewAssetLoader      │
└──────────────┬─────────────────────────────▲─────────────┘
               │ window.OpenClaw              │ CustomEvents
┌──────────────▼─────────────────────────────┴─────────────┐
│                  App Android (Kotlin)                    │
│       Ciclo de vida · Bridge · GatewayService            │
└──────────────┬─────────────────────────────▲─────────────┘
               │ ProcessBuilder + PTY         │ stdout/stderr
┌──────────────▼─────────────────────────────┴─────────────┐
│              Runtime (binarios nativos)                  │
│   libldlinux.so → libnode.so → openclaw.mjs (Gateway)    │
└──────────────────────────────────────────────────────────┘
```

---

## Requisitos

- **Android Studio**: Ladybug o superior.
- **JDK**: versión **17**.
- **Node.js**: v20+ (para compilar el frontend React).
- **Git**: necesario para el versionado dinámico que usa Gradle.

Configuración del módulo Android (definida en `app/build.gradle.kts`):

| Clave | Valor |
| --- | --- |
| `compileSdk` | `35` |
| `minSdk` | `31` (Android 12) |
| `targetSdk` | `35` (Android 14) |
| `jvmTarget` | `17` |
| ABI | `arm64-v8a` |

---

## Build

1. **Clonar** el repositorio.
2. **Compilar el frontend** (opcional — Gradle lo hace automáticamente):

   ```bash
   cd www
   npm install
   npm run build
   ```

3. **Compilar el APK** desde Android Studio (`Build > Assemble Debug`) o desde terminal:

   ```bash
   ./gradlew assembleDebug
   ```

   Para una build de release:

   ```bash
   ./gradlew assembleRelease
   ```

---

## Instalación en dispositivo

El APK generado queda en:

```
app/build/outputs/apk/debug/app-debug.apk
```

Instálalo con:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Estructura de carpetas

| Ruta | Contenido |
| --- | --- |
| `app/` | Código fuente Kotlin: Activities, servicios, Bridge, instalador. |
| `app/src/main/assets/` | Payload, migración y bundle web. |
| `www/` | Frontend React 19 + Vite + TypeScript + Tailwind. |
| `libs/` | AARs nativos (`terminal-emulator.aar`, `terminal-view.aar`). |
| `docs/` | Documentación técnica del módulo. |

---

## Documentación detallada

- [Arquitectura](docs/ARQUITECTURA.md)
- [Bridge Android ↔ React](docs/BRIDGE.md)
- [Proceso de instalación](docs/INSTALACION.md)
- [Gestión del Gateway](docs/GATEWAY.md)
- [Terminal PTY](docs/TERMINAL.md)
- [Frontend React](docs/REACT_FRONTEND.md)
- [Reglas críticas de desarrollo](docs/REGLAS_CRITICAS.md)
