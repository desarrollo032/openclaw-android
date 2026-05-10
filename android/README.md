# OpenClaw Android

OpenClaw Android es una aplicación que permite ejecutar el entorno de OpenClaw (Node.js AI Gateway) de forma nativa en dispositivos Android, superando las restricciones de ejecución de binarios de las versiones modernas del sistema operativo (W^X).

## 🧩 Arquitectura General

```text
┌──────────────────────────────────────────────────────────┐
│                   UI (React + Vite)                      │
│      Ejecutándose en WebView con WebViewAssetLoader      │
└──────────────┬─────────────────────────────▲─────────────┘
               │ (Bridge: window.Android)     │ (CustomEvents)
┌──────────────▼─────────────────────────────┴─────────────┐
│                 Android App (Kotlin)                     │
│    Maneja el Ciclo de Vida, Bridge y GatewayService      │
└──────────────┬─────────────────────────────▲─────────────┘
               │ (ProcessBuilder + PTY)       │ (Stdout/Stderr)
┌──────────────▼─────────────────────────────┴─────────────┐
│                 Runtime (Native Binaries)                │
│   ldlinux.so → libnode.so → openclaw.mjs (Gateway)       │
└──────────────────────────────────────────────────────────┘
```

## 🛠️ Requisitos para Compilar

* **Android Studio**: Ladybug (o superior).
* **JDK**: Versión 17.
* **Node.js**: v18+ (para compilar el frontend React).
* **Git**: Para el versionado dinámico en Gradle.

## 🚀 Instrucciones de Build

1. **Clonar el repositorio** y sus submódulos si aplica.
2. **Compilar el Frontend**:
   ```bash
   cd www
   npm install
   npm run build
   ```
   *Nota: Gradle ejecutará esto automáticamente al compilar el APK.*
3. **Compilar el APK**:
   En Android Studio, pulsa `Build > Assemble Debug` o ejecuta:
   ```bash
   ./gradlew assembleDebug
   ```

## 📲 Instalación en Dispositivo

* El APK generado se encuentra en: `app/build/outputs/apk/debug/app-debug.apk`.
* Puedes instalarlo mediante `adb install app-debug.apk`.

## 📂 Estructura de Carpetas

* `/app`: Código fuente de la aplicación Android (Kotlin).
* `/www`: Código fuente de la interfaz web (React + TypeScript).
* `/libs`: Librerías AAR nativas (Terminal Emulator).
* `/docs`: Documentación técnica detallada.

## 📚 Documentación Detallada

* [Arquitectura](docs/ARQUITECTURA.md)
* [Bridge Android-React](docs/BRIDGE.md)
* [Proceso de Instalación](docs/INSTALACION.md)
* [Gestión del Gateway](docs/GATEWAY.md)
* [Terminal PTY](docs/TERMINAL.md)
* [Frontend React](docs/REACT_FRONTEND.md)
* [Reglas Críticas de Desarrollo](docs/REGLAS_CRITICAS.md)
