# Gestión del Gateway

El Gateway es el proceso de Node.js que ejecuta OpenClaw y expone la API en el puerto `18789`.

## 🔋 Foreground Service

Para evitar que Android mate el proceso cuando el usuario sale de la app, el Gateway corre dentro de un `ForegroundService`. Esto garantiza:
1. Prioridad alta en la gestión de memoria.
2. Notificación persistente para que el usuario sepa que está activo.
3. Capacidad de auto-reinicio si el sistema llega a cerrar el servicio.
4. Acciones directas desde la notificación: "Restart" y "Ver logs".

## 🌍 Variables de Entorno

El proceso se inicia con un entorno controlado:

| Variable | Valor / Función |
| :--- | :--- |
| `PATH` | Incluye los binarios del payload y `/system/bin`. |
| `HOME` | Apunta al directorio del payload. |
| `LD_LIBRARY_PATH` | Rutas a `nativeLibraryDir` y `glibc/lib`. |
| `TMPDIR` | Carpeta de cache de la app. |
| `OPENCLAW_HOME` | Donde se guarda la configuración (`.openclaw`). |
| `OPENCLAW_DASHBOARD_TOKEN` | Token dinámico para seguridad. |
| `SSL_CERT_FILE` | Ruta a certificados SSL. |
| `NODE_PATH` | Ruta a módulos Node.js. |
| `NODE_NO_WARNINGS` | Desactiva advertencias de Node.js. |
| `NODE_DISABLE_COMPILE_CACHE` | Desactiva caché de compilación. |
| `OA_GLIBC` | Indica que estamos usando entorno glibc. |
| `CONTAINER` | Indica ejecución en contenedor. |

## 🛡️ Regla de LD_PRELOAD

**REGLA**: SIEMPRE se debe eliminar `LD_PRELOAD` del entorno antes de arrancar.
**POR QUÉ**: Algunas capas de Android inyectan librerías que entran en conflicto con la `glibc` personalizada del payload, provocando un *Crash* inmediato del proceso.

## 💓 Health Check y Auto-reinicio

El servicio realiza un monitoreo constante:
1. **Polling**: Verifica si el proceso de Node.js sigue vivo (`process.isAlive`).
2. **Backoff**: Si el proceso muere, el servicio espera 3 segundos antes de intentar reiniciarlo.
3. **Contador**: Si falla 3 veces seguidas en poco tiempo, se marca como `FAILED` y se notifica al usuario.
4. **Uptime Tracking**: Registra el tiempo de actividad del proceso en segundos.

## 📊 Logs del Gateway

El servicio captura y gestiona los logs del gateway:
- **Redacción de Tokens**: Los tokens sensibles se eliminan automáticamente de los logs.
- **Almacenamiento**: Los logs se guardan de forma persistente en el almacenamiento interno.
- **Acceso desde Bridge**: 
  - `getGatewayLogs()`: Obtiene los últimos 200 líneas con niveles (info, warn, error, debug) y timestamps.
  - `clearGatewayLogs()`: Limpia todos los logs almacenados.
- **Niveles de Log**: Se infieren automáticamente del contenido del mensaje.

## ⏱️ Uptime

El servicio calcula y expone el tiempo de actividad:
- `getGatewayUptime()`: Devuelve el tiempo en segundos desde que el gateway se inició.
- Si el gateway no está activo, devuelve 0.
- La notificación muestra el uptime formateado (ej: "1h 30m" o "25m 10s").

## 🔐 Token de Autenticación

En cada inicio, el servicio genera un `dashboardToken` único (UUID + Timestamp). 
* Se inyecta al proceso de Node.js vía variable de entorno.
* Se expone al frontend React vía Bridge.
* **Nunca** se guarda en disco para evitar robos de sesión.
* Los logs automáticos redactan este token para que no aparezca en capturas de pantalla.
