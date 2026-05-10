# 🌌 OpenClaw Android: Documentación Técnica

Esta documentación detalla la arquitectura y las implementaciones realizadas para integrar el ecosistema **OpenClaw** en la plataforma Android de manera nativa y fluida.

---

## 🏗️ Arquitectura de Comunicación (The Bridge)

Se ha implementado un puente de comunicación bidireccional de alto rendimiento entre la interfaz web (React) y el sistema operativo Android (Kotlin).

### Estándar `window.OpenClaw`
Para garantizar la coherencia, se ha unificado el acceso nativo bajo un único objeto global:
- **Localización**: `android/app/src/main/java/com/openclaw/android/AndroidBridge.kt`
- **TypeScript**: `android/www/src/lib/bridge.ts`

**Funciones Clave:**
- `getSetupStatus()` / `checkInstallation()`: Análisis en tiempo real de binarios y espacio.
- `startSetup()` / `startInstallation()`: Inicia la extracción del payload.
- `pickFile()` / `installFromUri()`: Selección e instalación desde archivos locales.
- `pickPayloadFile()` / `pickMigrationFile()`: Selección de payload y migración locales.
- `startGateway()` / `stopGateway()`: Control del servicio de gateway.
- `getGatewayState()` / `getGatewayUrl()` / `getAuthToken()`: Información del gateway.
- `getGatewayLogs()` / `clearGatewayLogs()`: Gestión de logs del gateway.
- `getGatewayUptime()`: Tiempo de actividad del gateway.
- `openTerminal()` / `launchInteractiveCommand()`: Acceso al terminal nativo.
- `runCommand()` / `runCommandAsync()`: Ejecución de comandos.
- `getSystemInfo()`: Versiones de Node.js, npm y OpenClaw.
- `getAppInfo()`: Información de la aplicación.
- `getBatteryOptimizationStatus()` / `requestBatteryOptimizationExclusion()`: Gestión de optimización de batería.
- `openSystemSettings()`: Apertura de configuraciones del sistema.
- `copyToClipboard()`: Copia al portapapeles.
- `getStorageInfo()`: Información de almacenamiento.
- `clearCache()`: Limpieza de caché.
- `openUrl()`: Apertura de URLs en navegador.
- `getAvailablePlatforms()` / `getInstalledPlatforms()` / `switchPlatform()`: Gestión de plataformas.
- `installTool()` / `uninstallTool()`: Gestión de herramientas.

**Eventos Clave (Android → React):**
- `onInstallProgress` / `onInstallComplete` / `onInstallError`: Progreso de instalación.
- `onLocalAssetPicked` / `onMigrationFilePicked`: Selección de archivos locales.
- `install_progress`: Progreso de instalación de herramientas/plataformas.
- `native:file_picked_{callbackId}` / `native:command_result_{callbackId}`: Resultados asíncronos.

---

## 🐚 Entorno de Ejecución Nativo

OpenClaw Android opera sobre un entorno híbrido que combina librerías nativas con un sistema de archivos persistente.

### Gestión de Binarios (`jniLibs`)
- **Motor Node.js**: Utiliza `libnode.so` como núcleo de ejecución.
- **Shell BusyBox**: Proporciona el entorno de comandos Linux estándar mediante `libbusybox.so`.
- **Linker Dinámico**: Se emplea `libldlinux.so` para permitir que los binarios externos se ejecuten correctamente dentro del espacio de usuario de Android.

### Wrappers Automáticos
El sistema genera automáticamente scripts de arranque en `app_payload/bin/` para los comandos:
- `node`
- `npm`
- `openclaw`

Esto permite que estos comandos sean "invisibles" y funcionen como si estuvieran instalados globalmente en el sistema.

---

## 🎨 Terminal Premium

El terminal nativo ha sido rediseñado desde cero para ofrecer una experiencia visual moderna.

- **Tema Visual**: Fondo "Deep Space" (`#0a0a0f`) con tipografía optimizada para código.
- **Barra de Herramientas**: Diseño minimalista con indicadores de estado LED.
- **Teclas Especiales**: Barra de acceso rápido con desplazamiento horizontal para TAB, ESC, CTRL, ALT y flechas de navegación.
- **Respuesta Táctil**: Integración de Ripple Effects y retroalimentación háptica sutil.
- **Copiar y Pegar**: Soporte completo para portapapeles del sistema con confirmación Toast.

---

## 🔋 Gestión del Gateway

El gateway se ejecuta como un Foreground Service para garantizar estabilidad:

- **Notificación Persistente**: Muestra estado, uptime y acciones (Restart, Ver logs).
- **Health Check y Auto-reinicio**: Monitorea el proceso y reinicia automáticamente si falla.
- **Logs Centralizados**: Captura stdout/stderr con redacción automática de tokens sensibles.
- **Uptime Tracking**: Registra y expone el tiempo de actividad en segundos.

---

## 🛠️ Automatización del Build

Se ha integrado el ciclo de vida de **Vite** dentro de **Gradle**. No es necesario compilar el frontend por separado.

1. Al ejecutar `./gradlew assembleDebug`, el sistema:
   - Entra en `android/www/`.
   - Ejecuta `npm run build`.
   - Limpia y sincroniza los assets en `src/main/assets/www/`.
   - Copia scripts de mantenimiento a `src/main/assets/scripts/`.
2. El resultado es un APK que siempre contiene la última versión de la interfaz de usuario y scripts.

---

## 📝 Notas de Versión
- **Caché**: Se ha forzado `LOAD_NO_CACHE` en el WebView para desarrollo rápido.
- **Seguridad**: Políticas de origen cruzado (CORS) configuradas para permitir carga de assets locales mediante módulos ES.
- **Compatibilidad**: Eventos soportan tanto prefijo `android:` como `native:` para mayor flexibilidad.

---
*Documento generado automáticamente por Antigravity AI - 2026*
