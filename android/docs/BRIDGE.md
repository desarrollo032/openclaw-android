# Android Bridge

El bridge permite que la interfaz React interactúe con el hardware y las APIs nativas de Android a través del objeto `window.OpenClaw`.

## 🌉 Funcionamiento

El bridge utiliza `@JavascriptInterface` para exponer métodos de Kotlin a Javascript. Para la comunicación de Android hacia React, se utilizan `CustomEvents` disparados en el `window`.

## 📑 Métodos del Bridge (Kotlin → React)

| Método | Parámetros | Retorno | Descripción |
| :--- | :--- | :--- | :--- |
| `getSetupStatus()` | - | `JSON String` | Estado de instalación y onboard. |
| `startSetup()` | - | - | Inicia la extracción del payload. |
| `startGateway()` | - | - | Inicia el servicio de OpenClaw. |
| `stopGateway()` | - | - | Detiene el servicio de OpenClaw. |
| `getGatewayState()` | - | `String` | `READY`, `STARTING`, `FAILED`, etc. |
| `getGatewayToken()` | - | `String` | Token de acceso para el dashboard web. |
| `openTerminal()` | - | - | Abre la actividad de terminal nativa. |
| `runCommand(cmd)` | `String` | `JSON String` | Ejecuta un comando (no interactivo). |
| `copyToClipboard(t)`| `String` | - | Copia texto al portapapeles. |
| `getAppInfo()` | - | `JSON String` | Versión y paquete de la app. |
| `getStorageInfo()` | - | `JSON String` | Espacio libre y tamaño de carpetas. |

### Ejemplo de Uso en React (TypeScript)

```typescript
// androidBridge.ts
const status = JSON.parse(window.OpenClaw.getSetupStatus());
if (!status.bootstrapInstalled) {
    window.OpenClaw.startSetup();
}
```

## 🔔 Eventos (Android → React)

Los eventos se escuchan mediante `window.addEventListener('android:NOMBRE_EVENTO', ...)`.

| Nombre del Evento | Payload JSON | Origen en Android |
| :--- | :--- | :--- |
| `onInstallProgress`| `{ step, percent, currentFile }` | `OpenClawInstaller` |
| `onInstallComplete`| `{ success: true }` | `AndroidBridge` |
| `onInstallError` | `{ error: "mensaje" }` | `AndroidBridge` |

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
