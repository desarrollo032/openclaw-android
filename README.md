# OpenClaw Android

> Ejecuta **OpenClaw** de forma nativa dentro de Android — usando un contenedor **proot + Alpine Linux**, sin Termux ni root.

![OpenClaw en Android](docs/images/openclaw_android.jpg)

---

## Índice

- [Resumen](#resumen)
- [Arquitectura](#arquitectura)
- [Estructura del repositorio](#estructura-del-repositorio)
- [Requisitos](#requisitos)
- [Build local](#build-local)
- [Documentación](#documentación)
- [Estado del proyecto](#estado-del-proyecto)
- [Licencia](#licencia)

---

## Resumen

- **Android 12+** (`minSdk 31`, `targetSdk 35`, `compileSdk 35`).
- ABI única: **`arm64-v8a`**.
- Runtime **Node.js + npm** instalado dentro de **Alpine Linux** vía **proot**.
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
               │ proot --rootfs=... --bind=... │ stdout/stderr
┌──────────────▼─────────────────────────────┴─────────────┐
│         Contenedor proot + Alpine Linux                  │
│   sh · node · npm · openclaw (todo dentro del Alpine)    │
└──────────────────────────────────────────────────────────┘
```

**Flujo resumido:**

1. La app bootstrapa Alpine Linux en almacenamiento privado interno.
2. Instala Node.js + npm + OpenClaw dentro del Alpine vía `apk` y `npm`.
3. Kotlin expone `window.OpenClaw` a React mediante `@JavascriptInterface`.
4. Un proceso Node.js corre como **Foreground Service** dentro de proot en el puerto `127.0.0.1:18789`.
5. El WebView consume los assets locales y controla acciones/estado vía Bridge.

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

## Documentación

### Núcleo técnico
- [DOCUMENTACION_TECNICA.md](DOCUMENTACION_TECNICA.md) — referencia técnica principal (es).

### Guías del proyecto
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

---

## Estado del proyecto

Prioridades activas:

- **Estabilidad** del runtime proot + Alpine en Android 12+.
- **Observabilidad** del gateway (logs, health-check, uptime).
- **Mantenibilidad** del bridge Kotlin ↔ TypeScript.

---

## Licencia

Distribuido bajo licencia **MIT**. Consulta [LICENSE](LICENSE) para más detalles.
