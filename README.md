# OpenClaw en Android (Autónomo)

Esta es una versión de OpenClaw para Android diseñada para ser **completamente autónoma**, eliminando la necesidad de Termux, Proot o scripts shell externos. Todo se ejecuta dentro del sandbox de la aplicación utilizando un binario de Node.js y glibc pre-empaquetados.

<img src="docs/images/openclaw_android.jpg" alt="OpenClaw en Android">

![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-brightgreen)
![No Termux](https://img.shields.io/badge/Termux-Not%20Required-blue)
![No proot](https://img.shields.io/badge/proot--distro-Not%20Required-blue)
![License MIT](https://img.shields.io/github/license/AidanPark/openclaw-android)

## 🚀 Características

- **Modo Dual de Instalación**:
  - **Assets**: Incluye los archivos pesados en el APK para una instalación de un solo clic.
  - **Carga Local (App Liviana)**: Permite seleccionar los archivos `payload.tar.xz` y `openclaw-apk-migration.tar.gz` desde el almacenamiento del teléfono para mantener el APK pequeño.
- **Gateway Nativo**: Ejecuta el núcleo de OpenClaw como un servicio de Android en primer plano (`Foreground Service`).
- **Dashboard Integrado**: Interfaz de usuario accesible mediante un WebView optimizado.
- **Terminal Interactivo Autocontenido**: Terminal completo usando librerías oficiales de Termux (`terminal-emulator` y `terminal-view`) embebidas en la app, sin requerir la app Termux instalada. Soporta comandos shell básicos (ls, cd, pwd, echo, cat, mkdir, etc.) con entrada de teclado, scroll y configuración de colores/fuente.
- **Sin Dependencias Externas**: No requiere instalar Termux ni configurar entornos complejos manualmente.

## 📂 Estructura del Proyecto

- `OpenClawInstaller.kt`: Gestiona la extracción y verificación de los componentes.
- `OpenClawGatewayService.kt`: Servicio que mantiene vivo el servidor Node.js/OpenClaw.
- `OpenClawDashboardActivity.kt`: Actividad para interactuar con la WebUI de OpenClaw.
- `TerminalActivity.kt`: Terminal interactivo usando librerías de Termux (autocontenido, sin app externa).
- `OpenClawExtensions.kt`: Utilidades de bajo nivel para descompresión `.tar.xz` y `.tar.gz`.

## 🛠 Requisitos de Instalación

Para que la app funcione, necesita dos componentes principales:

1. **Payload** (`payload.tar.xz`): Contiene Node.js, glibc y el núcleo de OpenClaw.
2. **Migración** (`openclaw-apk-migration.tar.gz`): Contiene tu configuración personalizada (`.openclaw/`).

### Opción A: Incluir en Assets (Recomendado para desarrollo)
Coloca ambos archivos en:
`android/app/src/main/assets/`

### Opción B: Carga desde el Teléfono (App Liviana)
Simplemente compila e instala la app. Al abrirla, te pedirá que selecciones ambos archivos desde tu almacenamiento interno.

## ⚙️ Configuración Técnica

El gateway se ejecuta con las siguientes variables de entorno:
- `LD_LIBRARY_PATH`: Ruta a las librerías de glibc incluidas en el payload.
- `OPENCLAW_HOME`: Ruta a la carpeta de configuración `.openclaw`.
- `NODE_PATH`: Ruta a los módulos de Node.js.
- `TMPDIR`: Carpeta temporal interna de la app.

## 📄 Licencia
Este proyecto sigue la licencia original de OpenClaw.
