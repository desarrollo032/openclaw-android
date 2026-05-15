# Gestión del Gateway

El **Gateway** es el proceso Node.js que ejecuta OpenClaw y expone la API en `http://127.0.0.1:18789`.

---

## Índice

- [Foreground Service](#foreground-service)
- [Variables de entorno](#variables-de-entorno)
- [Regla sobre `LD_PRELOAD`](#regla-sobre-ld_preload)
- [Health-check y auto-reinicio](#health-check-y-auto-reinicio)
- [Logs del gateway](#logs-del-gateway)
- [Uptime](#uptime)
- [Token de autenticación](#token-de-autenticación)

---

## Foreground Service

Para evitar que Android mate el proceso cuando el usuario sale de la app, el Gateway corre dentro de un **`ForegroundService`**. Esto garantiza:

1. **Prioridad alta** en la gestión de memoria.
2. **Notificación persistente** indicando que está activo.
3. **Auto-reinicio** si el sistema llega a cerrar el servicio.
4. **Acciones directas** desde la notificación: *Restart* y *Ver logs*.

---

## Variables de entorno

El proceso se inicia con un entorno controlado:

| Variable | Valor / Función |
| --- | --- |
| `PATH` | Binarios del payload + `/system/bin`. |
| `HOME` | Directorio del payload. |
| `LD_LIBRARY_PATH` | `nativeLibraryDir` + `glibc/lib`. |
| `TMPDIR` | Cache de la app. |
| `OPENCLAW_HOME` | Configuración del usuario (`.openclaw`). |
| `OPENCLAW_DASHBOARD_TOKEN` | Token dinámico para autenticar el dashboard. |
| `SSL_CERT_FILE` | Ruta a certificados SSL. |
| `NODE_PATH` | Ruta a módulos Node.js. |
| `NODE_NO_WARNINGS` | Desactiva advertencias de Node.js. |
| `NODE_DISABLE_COMPILE_CACHE` | Desactiva caché de compilación. |
| `OA_GLIBC` | Indica que se está usando entorno glibc. |
| `CONTAINER` | Indica ejecución en contenedor. |

---

## Regla sobre `LD_PRELOAD`

**REGLA:** **siempre** eliminar `LD_PRELOAD` del entorno antes de arrancar.

**Por qué:** algunas capas de Android inyectan librerías que entran en conflicto con la `glibc` personalizada del payload, provocando un **crash inmediato** del proceso.

---

## Health-check y auto-reinicio

El servicio monitorea constantemente:

1. **Polling**: verifica si el proceso de Node.js sigue vivo (`process.isAlive`).
2. **Backoff**: si el proceso muere, espera 3 s antes de reintentar.
3. **Contador**: si falla 3 veces seguidas en poco tiempo, se marca como `FAILED` y se notifica al usuario.
4. **Uptime tracking**: registra el tiempo de actividad en segundos.

---

## Logs del gateway

El servicio captura y gestiona los logs del gateway:

- **Redacción de tokens**: los tokens sensibles se eliminan automáticamente.
- **Persistencia**: se guardan en almacenamiento interno.
- **Acceso vía bridge**:
  - `getGatewayLogs()` — últimas 200 líneas con nivel (`info`, `warn`, `error`, `debug`) y timestamp.
  - `clearGatewayLogs()` — limpia todos los logs.
- **Niveles**: se infieren del contenido del mensaje.

---

## Uptime

- `getGatewayUptime()` devuelve el tiempo en segundos desde el arranque.
- Si el gateway no está activo, devuelve `0`.
- La notificación muestra el uptime formateado (`1h 30m`, `25m 10s`).

---

## Token de autenticación

En cada inicio, el servicio genera un `dashboardToken` único (**UUID + timestamp**):

- Se inyecta al proceso Node.js vía variable de entorno.
- Se expone al frontend React vía bridge.
- **Nunca** se persiste a disco — evita robos de sesión.
- Los logs lo **redactan automáticamente** para que no aparezca en capturas.
