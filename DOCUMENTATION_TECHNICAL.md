# Documentación Técnica - Arquitectura Autónoma

Este documento describe la arquitectura interna de la implementación autónoma de OpenClaw en Android.

## 🏗 Arquitectura de Procesos

A diferencia de la implementación estándar que depende de un entorno Linux completo (Termux/Proot), esta versión utiliza una ejecución directa de procesos mediante `ProcessBuilder`.

### 1. El Proceso Gateway
El núcleo de la aplicación es un proceso de Node.js que se lanza con las siguientes características:
- **Binario**: `context.filesDir/home/payload/bin/node`
- **Script**: `context.filesDir/home/payload/lib/node_modules/openclaw/openclaw.mjs`
- **Directorio de Trabajo**: `context.filesDir/home/payload`

### 2. Aislamiento de Librerías (glibc)
Para evitar conflictos con las librerías nativas de Android (Bionic), se utiliza un entorno de glibc pre-compilado:
- Se inyecta la variable `LD_LIBRARY_PATH` apuntando a `payload/glibc/lib`.
- Esto permite que el binario de Node.js (compilado para Linux estándar) encuentre sus dependencias de sistema.

## 🛠 Componentes de Software

### Extracción Robusta (`OpenClawExtensions.kt`)
Se utilizan librerías de `org.apache.commons:commons-compress` y `org.tukaani:xz` para manejar la descompresión en streaming:
- Evita cargar archivos grandes en memoria.
- Preserva los bits de ejecución (`chmod`) necesarios para los binarios.

### Gestión de Ciclo de Vida (`OpenClawGatewayService.kt`)
El servicio gestiona la persistencia:
- **Foreground Service**: Previene que Android mate el proceso para ahorrar batería.
- **Supervisor Job**: Mantiene una corrutina dedicada a monitorear la salud del proceso hijo.
- **Environment Management**: Configura dinámicamente el `PATH` y el `TMPDIR` para que Node.js funcione correctamente en el almacenamiento interno.

## 🌐 Comunicación (WebUI)

- **Localhost Only**: El servidor escucha únicamente en `127.0.0.1:18789`.
- **WebView Bridge**: Se utiliza `WebView` con `JavaScriptEnabled` y `DomStorageEnabled` para renderizar el Dashboard de React.
- **Wait Mechanism**: `OpenClawDashboardActivity` implementa un bucle de polling sobre el endpoint `/health` antes de intentar cargar la página, evitando errores de "Connection Refused".

## 🔒 Seguridad y Almacenamiento

- **Sandbox Total**: No se requieren permisos de `READ_EXTERNAL_STORAGE` o `WRITE_EXTERNAL_STORAGE`. Todo ocurre en `context.filesDir`.
- **Privacidad**: Los datos de configuración, memoria y plugins de la IA están protegidos por el sistema de archivos de Android y no son accesibles por otras apps.
