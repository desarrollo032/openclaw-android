# Android Bridge

El bridge permite que la interfaz React interactúe con el hardware y las APIs nativas de Android a través del objeto `window.OpenClaw`.

## 🌉 Funcionamiento

El bridge utiliza `@JavascriptInterface` para exponer métodos de Kotlin a Javascript. Para la comunicación de Android hacia React, se utilizan `CustomEvents` disparados en el `window`.

## 📑 Métodos del Bridge (Kotlin → React)

| Método | Parámetros | Retorno | Descripción |
| :--- | :--- | :--- | :--- |
| `getSetupStatus()` | - | `JSON String` | Estado de instalación y onboard. |
| `checkInstallation()` | - | `JSON String` | Alias de `getSetupStatus()` para compatibilidad. |
| `getBootstrapStatus()` | - | `JSON String` | Alias de `getSetupStatus()` para compatibilidad. |
| `startSetup()` | - | - | Inicia la extracción del payload. |
| `startInstallation()` | - | - | Alias de `startSetup()` para compatibilidad. |
| `pickFile(callbackId)` | `String` | - | Abre selector de archivos y envía URI via `native:file_picked_{callbackId}`. |
| `installFromUri(payloadUri, configUri)` | `String, String` | - | Instala desde URIs específicas. |
| `pickMigrationFile()` | - | - | Selecciona archivo de migración local. |
| `pickPayloadFile()` | - | - | Selecciona archivo de payload local. |
| `startGateway()` | - | - | Inicia el servicio de OpenClaw. |
| `stopGateway()` | - | - | Detiene el servicio de OpenClaw. |
| `getGatewayState()` | - | `String` | `READY`, `STARTING`, `FAILED`, etc. |
| `getAuthToken()` | - | `String` | Token de acceso para el dashboard web. |
| `getGatewayToken()` | - | `String` | Alias de `getAuthToken()`. |
| `getGatewayUrl()` | - | `String` | URL del gateway (http://127.0.0.1:18789). |
| `getGatewayLogs()` | - | `JSON String` | Obtiene logs del gateway con niveles y timestamps. |
| `clearGatewayLogs()` | - | - | Limpia los logs del gateway. |
| `getGatewayUptime()` | - | `JSON String` | Tiempo de actividad del gateway en segundos. |
| `openTerminal()` | - | - | Abre la actividad de terminal nativa. |
| `showTerminal()` | - | - | Alias de `openTerminal()`. |
| `showWebView()` | - | - | Mantido para compatibilidad (no hace nada). |
| `createSession()` | - | `JSON String` | Crea sesión de terminal. |
| `switchSession(id)` | `String` | - | Mantido para compatibilidad. |
| `closeSession(id)` | `String` | - | Mantido para compatibilidad. |
| `getTerminalSessions()` | - | `JSON String` | Obtiene lista de sesiones (vacía en nativo). |
| `writeToTerminal(id, data)` | `String, String` | - | Escribe datos a terminal o abre con comando. |
| `launchInteractiveCommand(command)` | `String` | - | Abre terminal con comando pre-cargado. |
| `runCommand(command)` | `String` | `JSON String` | Ejecuta comando (no interactivo). |
| `runCommandAsync(callbackId, command)` | `String, String` | - | Ejecuta comando asíncronamente. |
| `getSystemInfo()` | - | `JSON String` | Versiones de Node, npm, OpenClaw y rutas. |
| `getAppInfo()` | - | `JSON String` | Versión y paquete de la app. |
| `getApkUpdateInfo()` | - | `JSON String` | Información de actualizaciones. |
| `getBatteryOptimizationStatus()` | - | `JSON String` | Estado de exclusión de optimización de batería. |
| `requestBatteryOptimizationExclusion()` | - | - | Solicita exclusión de optimización de batería. |
| `openSystemSettings(page)` | `String` | - | Abre configuraciones del sistema. |
| `copyToClipboard(t)`| `String` | - | Copia texto al portapapeles. |
| `getStorageInfo()` | - | `JSON String` | Espacio libre y tamaño de carpetas. |
| `clearCache()` | - | - | Limpia caché de WebView y app. |
| `openUrl(url)` | `String` | - | Abre URL en navegador externo. |
| `getInstalledTools()` | - | `JSON String` | Lista de herramientas instaladas. |
| `saveToolSelections(json)` | `String` | - | Guarda selecciones de herramientas. |
| `isToolInstalled(id)` | `String` | `JSON String` | Verifica si una herramienta está instalada. |
| `getAvailablePlatforms()` | - | `JSON String` | Plataformas disponibles. |
| `getInstalledPlatforms()` | - | `JSON String` | Plataformas instaladas. |
| `getActivePlatform()` | - | `JSON String` | Plataforma activa. |
| `installPlatform(id)` | `String` | - | Instala una plataforma. |
| `uninstallPlatform(id)` | `String` | - | Desinstala una plataforma. |
| `switchPlatform(id)` | `String` | - | Cambia de plataforma. |
| `checkForUpdates()` | - | `JSON String` | Verifica actualizaciones. |
| `applyUpdate(component)` | `String` | - | Aplica actualización. |
| `installTool(id)` | `String` | - | Instala una herramienta. |
| `uninstallTool(id)` | `String` | - | Desinstala una herramienta. |

### Ejemplo de Uso en React (TypeScript)

```typescript
// androidBridge.ts
const status = JSON.parse(window.OpenClaw.getSetupStatus());
if (!status.bootstrapInstalled) {
    window.OpenClaw.startSetup();
}
```

## 🔔 Eventos (Android → React)

Los eventos se escuchan mediante `window.addEventListener('android:NOMBRE_EVENTO', ...)` o `window.addEventListener('native:NOMBRE_EVENTO', ...)` (ambos soportados).

| Nombre del Evento | Payload JSON | Origen en Android |
| :--- | :--- | :--- |
| `onInstallProgress`| `{ step, percent, currentFile }` | `OpenClawInstaller` |
| `onInstallComplete`| `{ success: true }` | `AndroidBridge` |
| `onInstallError` | `{ error: "mensaje" }` | `AndroidBridge` |
| `onLocalAssetPicked` | `{ type, filename, sizeMB, source }` | `AndroidBridge` |
| `onMigrationFilePicked` | `{ filename, sizeMB }` | `AndroidBridge` |
| `install_progress` | `{ target, progress, message }` | `AndroidBridge` |
| `native:file_picked_{callbackId}` | `{ uri, success }` | `AndroidBridge` |
| `native:command_result_{callbackId}` | Resultado del comando | `AndroidBridge` |

### Cómo Escuchar un Evento en React

```typescript
useEffect(() => {
    const handleProgress = (e: any) => {
        console.log("Progreso:", e.detail.percent);
    };
    window.addEventListener('android:onInstallProgress', handleProgress);
    return () => window.removeEventListener('android:onInstallProgress', handleProgress);
}, []);
```

## ⚠️ Limitaciones
1. Solo se pueden pasar tipos primitivos (`String`, `Int`, `Boolean`) o Strings en formato JSON.
2. Los métodos del bridge se ejecutan en un hilo de background; si necesitas tocar la UI de Android, usa `activity.runOnUiThread`.
3. Evita llamadas síncronas pesadas que puedan bloquear el renderizado del WebView.
