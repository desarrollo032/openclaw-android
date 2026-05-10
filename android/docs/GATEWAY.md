# Gestión del Gateway

El Gateway es el proceso de Node.js que ejecuta OpenClaw y expone la API en el puerto `18789`.

## 🔋 Foreground Service

Para evitar que Android mate el proceso cuando el usuario sale de la app, el Gateway corre dentro de un `ForegroundService`. Esto garantiza:
1. Prioridad alta en la gestión de memoria.
2. Notificación persistente para que el usuario sepa que está activo.
3. Capacidad de auto-reinicio si el sistema llega a cerrar el servicio.

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

## 🛡️ Regla de LD_PRELOAD

**REGLA**: SIEMPRE se debe eliminar `LD_PRELOAD` del entorno antes de arrancar.
**POR QUÉ**: Algunas capas de Android inyectan librerías que entran en conflicto con la `glibc` personalizada del payload, provocando un *Crash* inmediato del proceso.

## 💓 Health Check y Auto-reinicio

El servicio realiza un monitoreo constante:
1. **Polling**: Verifica si el proceso de Node.js sigue vivo (`process.isAlive`).
2. **Backoff**: Si el proceso muere, el servicio espera 3 segundos antes de intentar reiniciarlo.
3. **Contador**: Si falla 3 veces seguidas en poco tiempo, se marca como `FAILED` y se notifica al usuario.

## 🔐 Token de Autenticación

En cada inicio, el servicio genera un `dashboardToken` único (UUID + Timestamp). 
* Se inyecta al proceso de Node.js vía variable de entorno.
* Se expone al frontend React vía Bridge.
* **Nunca** se guarda en disco para evitar robos de sesión.
* Los logs automáticos redactan este token para que no aparezca en capturas de pantalla.
