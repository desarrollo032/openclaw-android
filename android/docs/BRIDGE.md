# Bridge Android ↔ React

El bridge permite que la UI React interactúe con las APIs nativas de Android a través del objeto global `window.OpenClaw`.

---

## Índice

- [Funcionamiento](#funcionamiento)
- [Métodos del bridge (Kotlin → React)](#métodos-del-bridge-kotlin--react)
- [Eventos (Android → React)](#eventos-android--react)
- [Ejemplo de uso en React](#ejemplo-de-uso-en-react)
- [Limitaciones](#limitaciones)

---

## Funcionamiento

- Kotlin expone métodos a JavaScript mediante `@JavascriptInterface`.
- Android publica eventos al frontend disparando `CustomEvent` sobre `window`. El nombre del evento sigue el patrón `android:<EVENTO>` (y por compatibilidad también `native:<EVENTO>`).
- Todos los métodos retornan tipos primitivos (`String`, `Int`, `Boolean`) — los objetos complejos se serializan a JSON.

---

## Métodos del bridge (Kotlin → React)

### Setup / instalación

| Método | Parámetros | Retorno | Descripción |
| --- | --- | --- | --- |
| `getSetupStatus()` | — | `JSON` | Estado completo (bootstrap, payload, fuentes, espacio). |
| `getBootstrapStatus()` | — | `JSON` | Estado simplificado del bootstrap (legacy). |
| `getPayloadStatus()` | — | `JSON` | Estado de payload listo (legacy). |
| `getAssetStatus()` | — | `JSON` | Estado por componente (bootstrap, payload, platform, tools). |
| `checkBootstrap()` | — | — | Dispara verificación del bootstrap. |
| `checkPayload()` | — | — | Dispara verificación del payload. |
| `startSetup()` | — | — | Inicia la extracción del payload + migración. |
| `pickFile(callbackId)` | `String` | — | Abre selector; emite `native:file_picked_{id}`. |
| `pickPayloadFile()` | — | — | Selecciona archivo `.tar.xz` para override de payload. |
| `pickMigrationFile()` | — | — | Selecciona archivo `.tar.gz` para override de migración. |
| `installFromUri(payloadUri, configUri)` | `String, String` | — | Instala desde URIs específicas. |

### Gateway

| Método | Parámetros | Retorno | Descripción |
| --- | --- | --- | --- |
| `startGateway()` | — | — | Inicia el servicio gateway. |
| `stopGateway()` | — | — | Detiene el gateway. |
| `getGatewayState()` | — | `String` | `READY`, `STARTING`, `FAILED`, etc. |
| `getGatewayUrl()` | — | `String` | URL del gateway (`http://127.0.0.1:18789`). |
| `getGatewayToken()` / `getAuthToken()` | — | `String` | Token de autenticación. |
| `getGatewayLogs()` | — | `JSON` | Logs recientes con niveles y timestamps. |
| `getLogs(lines)` | `Int` | `JSON` | Últimos N logs. |
| `clearGatewayLogs()` / `clearLogs()` | — | — | Limpia el log persistente. |
| `getGatewayUptime()` | — | `JSON` | Tiempo de actividad en segundos. |

### Terminal

| Método | Parámetros | Retorno | Descripción |
| --- | --- | --- | --- |
| `openTerminal()` / `showTerminal()` | — | — | Abre la actividad de terminal nativa. |
| `launchInteractiveCommand(cmd)` | `String` | — | Abre la terminal con un comando precargado. |
| `runCommand(cmd)` | `String` | `JSON` | Ejecuta un comando no interactivo. |
| `runOpenClawCommand(cmd)` | `String` | `JSON` | Alias específico para comandos OpenClaw. |
| `runCommandAsync(callbackId, cmd)` | `String, String` | — | Comando asíncrono; emite `native:command_result_{id}`. |
| `createSession()` / `getTerminalSessions()` | — | `JSON` | Compatibilidad con el modelo de sesiones. |

### Sistema y app

| Método | Parámetros | Retorno | Descripción |
| --- | --- | --- | --- |
| `getSystemInfo()` | — | `JSON` | Versiones de Node, npm, OpenClaw y rutas. |
| `getAppInfo()` | — | `JSON` | `versionName`, `versionCode`, `packageName`. |
| `getApkUpdateInfo()` | — | `JSON` | Info de actualizaciones APK. |
| `getStorageInfo()` | — | `JSON` | Espacio libre y tamaño de carpetas. |
| `getBatteryOptimizationStatus()` | — | `JSON` | Estado de exclusión de optimización de batería. |
| `requestBatteryOptimizationExclusion()` | — | — | Solicita exclusión. |
| `openSystemSettings(page)` | `String` | — | Abre una página de Ajustes Android. |
| `copyToClipboard(text)` | `String` | — | Copia texto al portapapeles. |
| `openUrl(url)` | `String` | — | Abre URL en navegador externo. |
| `clearCache()` | — | — | Limpia caché del WebView y de la app. |
| `getLocale()` / `getSystemTheme()` | — | `String` | Locale y tema del sistema. |

### Plataformas y herramientas

| Método | Parámetros | Retorno | Descripción |
| --- | --- | --- | --- |
| `getAvailablePlatforms()` | — | `JSON` | Plataformas disponibles. |
| `getInstalledPlatforms()` | — | `JSON` | Plataformas instaladas. |
| `getActivePlatform()` | — | `JSON` | Plataforma activa. |
| `installPlatform(id)` / `uninstallPlatform(id)` / `switchPlatform(id)` | `String` | — | Operaciones de plataforma. |
| `getInstalledTools()` | — | `JSON` | Lista de herramientas instaladas. |
| `installTool(id)` / `uninstallTool(id)` | `String` | — | Operaciones de herramientas. |
| `isToolInstalled(id)` | `String` | `JSON` | Verifica instalación. |
| `saveToolSelections(json)` | `String` | — | Persiste selección de herramientas. |

### Configuración y updates

| Método | Parámetros | Retorno | Descripción |
| --- | --- | --- | --- |
| `readOpenclawJson()` | — | `JSON` | Lee `home/.openclaw/openclaw.json`. |
| `writeOpenclawJson(content)` | `String` | `JSON` | Persiste configuración (valida JSON). |
| `checkForUpdates()` | — | `JSON` | Verifica updates. |
| `applyUpdate(component)` | `String` | — | Aplica una actualización. |
| `isBackgroundExecutionEnabled()` | — | `JSON` | Estado de ejecución en background. |
| `setBackgroundExecutionEnabled(enabled)` | `Boolean` | — | Activa/desactiva ejecución en background. |

---

## Eventos (Android → React)

Los eventos se escuchan vía `window.addEventListener('android:NOMBRE', ...)` o `native:NOMBRE` (ambos soportados).

| Evento | Payload | Emitido por |
| --- | --- | --- |
| `onInstallProgress` | `{ step, totalSteps, percent, extractedMB, totalMB, currentFile, stepName }` | `OpenClawInstaller` |
| `onInstallComplete` | `{ success: true }` | `AndroidBridge` |
| `onInstallError` | `{ error }` | `AndroidBridge` |
| `onLocalAssetPicked` | `{ type, filename, sizeMB, source }` | `AndroidBridge` |
| `onMigrationFilePicked` | `{ filename, sizeMB }` | `AndroidBridge` |
| `install_progress` | `{ target, progress, message }` | `AndroidBridge` |
| `onGatewayStateChanged` | `{ state }` | `OpenClawGatewayService` |
| `onGatewayReady` | — | `OpenClawGatewayService` |
| `onBackgroundExecutionChanged` | `{ enabled }` | `AndroidBridge` |
| `native:file_picked_{callbackId}` | `{ uri, success }` | `AndroidBridge` |
| `native:command_result_{callbackId}` | resultado del comando | `AndroidBridge` |

---

## Ejemplo de uso en React

```typescript
// Llamada directa
const raw = window.OpenClaw?.getSetupStatus()
const status = raw ? JSON.parse(raw) : null
if (status && !status.payloadReady) {
  window.OpenClaw?.startSetup()
}

// Escuchar progreso
useEffect(() => {
  const handler = (e: Event) => {
    const detail = (e as CustomEvent).detail
    console.log('Progreso:', detail.percent)
  }
  window.addEventListener('android:onInstallProgress', handler)
  return () => window.removeEventListener('android:onInstallProgress', handler)
}, [])
```

> En el proyecto existe un wrapper tipado en `android/www/src/lib/bridge.ts` (`bridge.call`, `bridge.callJson`, `bridge.on`, `bridge.off`).

---

## Limitaciones

1. Solo se pueden pasar tipos primitivos (`String`, `Int`, `Boolean`) — para datos complejos se usa **JSON String**.
2. Los métodos del bridge se ejecutan en un **hilo de background**; para tocar la UI nativa hay que usar `activity.runOnUiThread`.
3. Evita llamadas **síncronas pesadas** que puedan bloquear el renderizado del WebView (usa `runCommandAsync`).
