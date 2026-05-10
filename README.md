# 🌌 OpenClaw Android (Autónomo)

OpenClaw Android es una implementación **completamente autónoma** del asistente de IA OpenClaw. Elimina la necesidad de Termux, Proot o entornos externos, ejecutándose íntegramente dentro del sandbox de Android mediante binarios nativos de Node.js y glibc optimizados.

<img src="docs/images/openclaw_android.jpg" alt="OpenClaw en Android">

[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-brightgreen)](#)
[![Termux Not Required](https://img.shields.io/badge/Termux-Not%20Required-blue)](#)
[![Vite + React](https://img.shields.io/badge/UI-Vite%20%2B%20React-purple)](#)
[![License MIT](https://img.shields.io/github/license/AidanPark/openclaw-android)](#)

---

## 🚀 Características Principales

- **📦 Gestión de Assets Inteligente**:
  - **Payload Integrado**: Soporte para `payload-v2.tar.xz` en los assets del APK para instalación instantánea.
  - **Migración Fluida**: Importación de configuraciones mediante `openclaw-apk-migration.tar.gz`.
- **⚡ Gateway Nativo**: Ejecución del núcleo de OpenClaw como un servicio de primer plano (`Foreground Service`) ultra-estable.
- **🖥️ Dashboard Moderno**: Interfaz de usuario React/Vite integrada mediante un WebView optimizado con soporte para módulos ES nativos.
- **📟 Terminal Premium Integrado**:
  - Basado en librerías oficiales de Termux.
  - Interfaz modernizada con diseño **Deep Dark**.
  - Barra de teclas especiales (TAB, ESC, CTRL, ALT) optimizada para móvil.
- **🔄 Automatización Total**: Integración completa entre Gradle y Vite; el frontend se compila automáticamente al generar el APK.

---

## 📂 Estructura del Núcleo Nativo

- **`OpenClawInstaller.kt`**: Orquestador de la extracción de binarios y generación de wrappers (`node`, `npm`).
- **`AndroidBridge.kt`**: Puente de comunicación bidireccional bajo el espacio de nombres `window.OpenClaw`.
- **`OpenClawTerminalActivity.kt`**: Terminal interactivo con UI premium y soporte para comandos interactivos.
- **`OpenClawGatewayService.kt`**: Gestiona el ciclo de vida del servidor Node.js en segundo plano.

---

## 🛠️ Instalación y Desarrollo

### Requisitos de Assets
Para que la aplicación sea funcional, requiere:
1. **Payload** (`payload-v2.tar.xz`): Node.js, glibc y core de OpenClaw.
2. **Migración** (`openclaw-apk-migration.tar.gz`): Configuración `.openclaw/`.

### Flujo de Compilación
El proyecto está configurado para ser "Zero Config":
1. Abre el proyecto en Android Studio.
2. Pulsa **Run** o ejecuta `./gradlew assembleDebug`.
3. Gradle automáticamente compilará el Frontend en `android/www` y lo integrará en el APK.

---

## 📖 Documentación Detallada
Para más detalles técnicos, consulta la [Documentación Técnica](DOCUMENTACION_TECNICA.md).

## 📄 Licencia
Este proyecto sigue la licencia original de OpenClaw.

