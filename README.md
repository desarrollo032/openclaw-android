# OpenClaw Android

> Ejecuta **OpenClaw** de forma nativa dentro de Android — sin Termux ni Proot — usando un runtime Node.js + glibc empaquetado en el APK.

![OpenClaw en Android](docs/images/openclaw_android.jpg)

---

## Índice

- [Resumen](#resumen)
- [Arquitectura](#arquitectura)
- [Estructura del repositorio](#estructura-del-repositorio)
- [Requisitos](#requisitos)
- [Build local](#build-local)
- [Empaquetar `npm` en el payload](#empaquetar-npm-en-el-payload)
- [Documentación](#documentación)
- [Estado del proyecto](#estado-del-proyecto)
- [Licencia](#licencia)

---

## Resumen

- **Android 12+** (`minSdk 31`, `targetSdk 35`, `compileSdk 35`).
- ABI única: **`arm64-v8a`**.
- Runtime **Node.js + glibc** empaquetado en `android/app/src/main/assets/payload-v2.tar.xz`.
- UI moderna en **React 19 + Vite 7 + Tailwind 4** cargada dentro de un `WebView`.
- **Foreground Service** para el gateway (estable en segundo plano).
- Terminal integrada basada en las librerías oficiales de Termux (`terminal-emulator`, `terminal-view`).

---

## Arquitectura

```text
┌──────────────────────────────────────────────────────────┐
│            UI (React 19 + Vite + Tailwind)               │
│         dentro de WebView con WebViewAssetLoader         │
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

**Flujo resumido:**

1. La app extrae `payload-v2.tar.xz` en almacenamiento privado interno.
2. Kotlin expone `window.OpenClaw` a React mediante `@JavascriptInterface`.
3. Un proceso Node.js corre como **Foreground Service** en el puerto `127.0.0.1:18789`.
4. El WebView consume los assets locales y controla acciones/estado vía Bridge.

---

## Estructura del repositorio

| Ruta | Descripción |
| --- | --- |
| `android/app/` | Código Kotlin: Activities, servicios, Bridge e instalador. |
| `android/www/` | Frontend React + Vite + TypeScript. |
| `android/docs/` | Guías técnicas de la app Android. |
| `docs/` | Guías operativas, troubleshooting y planes. |
| `scripts/` | Utilidades de instalación y mantenimiento. |
| `platforms/` | Plugins por plataforma (estructura `platforms/<nombre>/`). |
| `tests/` | Verificadores y smoke tests. |

---

## Requisitos

- **Android Studio** (Ladybug o superior, recomendado).
- **JDK 17**.
- **Node.js 20+** y **pnpm** o **npm** (para construir `android/www`).
- **Git** (necesario para `updateVersionFromGit`).

---

## Build local

```bash
cd android
./gradlew assembleDebug
```

El build de Gradle ejecuta automáticamente:

1. `npm run build` dentro de `android/www`.
2. Copia el `dist/` resultante a `app/src/main/assets/www`.
3. Empaqueta los scripts auxiliares en `app/src/main/assets/scripts`.

El APK queda en:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

Instálalo con:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

---

## Empaquetar `npm` en el payload

Si necesitas inyectar `npm` dentro del payload preinstalado:

```bash
python scripts/add_npm_to_payload.py \
  --payload android/app/src/main/assets/payload-v2.tar.xz \
  --output  android/app/src/main/assets/payload-v2-with-npm.tar.xz
```

Esto descarga el `npm` configurado y produce un payload con `lib/node_modules/npm/bin/npm-cli.js` listo para Android.

---

## Documentación

### Núcleo técnico
- [DOCUMENTACION_TECNICA.md](DOCUMENTACION_TECNICA.md) — referencia técnica principal (es).
- [DOCUMENTATION_TECHNICAL.md](DOCUMENTATION_TECHNICAL.md) — versión espejo (es, completa).

### Guías del proyecto
- [INSTALLATION_GUIDE.md](INSTALLATION_GUIDE.md) — preparación de archivos e instalación.
- [GUIA_VERSIONADO.md](GUIA_VERSIONADO.md) — flujo de versionado y tags.
- [TESTING.md](TESTING.md) — suite de pruebas (Kotlin · React · E2E).
- [CONTRIBUTING.md](CONTRIBUTING.md) — guía de contribución.
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) — código de conducta.
- [SECURITY.md](SECURITY.md) — política de seguridad.
- [CHANGELOG.md](CHANGELOG.md) — historial de cambios.

### Documentación interna del módulo Android
- [android/README.md](android/README.md) — punto de entrada del módulo.
- [android/docs/ARQUITECTURA.md](android/docs/ARQUITECTURA.md)
- [android/docs/BRIDGE.md](android/docs/BRIDGE.md)
- [android/docs/GATEWAY.md](android/docs/GATEWAY.md)
- [android/docs/INSTALACION.md](android/docs/INSTALACION.md)
- [android/docs/TERMINAL.md](android/docs/TERMINAL.md)
- [android/docs/REACT_FRONTEND.md](android/docs/REACT_FRONTEND.md)
- [android/docs/REGLAS_CRITICAS.md](android/docs/REGLAS_CRITICAS.md)

### Operación y troubleshooting
- [docs/troubleshooting.md](docs/troubleshooting.md)
- [docs/termux-ssh-guide.md](docs/termux-ssh-guide.md)
- [docs/disable-phantom-process-killer.md](docs/disable-phantom-process-killer.md)
- [docs/legacy-app-payload-compat.md](docs/legacy-app-payload-compat.md)

---

## Estado del proyecto

Prioridades activas:

- **Estabilidad** del runtime Node.js + glibc en Android 12+.
- **Observabilidad** del gateway (logs, health-check, uptime).
- **Mantenibilidad** del bridge Kotlin ↔ TypeScript.

---

## Licencia

Distribuido bajo licencia **MIT**. Consulta [LICENSE](LICENSE) para más detalles.
