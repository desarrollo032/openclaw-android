# Guía de instalación

Cómo preparar los archivos del runtime y dejar la app **OpenClaw Android** lista para usar.

---

## Índice

- [Archivos necesarios](#archivos-necesarios)
- [Métodos de instalación](#métodos-de-instalación)
- [Reemplazar archivos locales](#reemplazar-archivos-locales)
- [Estados del gateway](#estados-del-gateway)
- [Solución de problemas](#solución-de-problemas)

---

## Archivos necesarios

Necesitas dos archivos comprimidos generados previamente (típicamente desde una instalación Termux funcional):

### 1. `payload-v2.tar.xz` (~170 MB)

Debe incluir el runtime Node.js + glibc:

- `bin/node` — binario o wrapper.
- `glibc/lib/ld-linux-aarch64.so.1` y librerías asociadas.
- `lib/node_modules/openclaw/` — código fuente de OpenClaw.
- `lib/node_modules/clawdhub/` (opcional).

### 2. `openclaw-apk-migration.tar.gz` (~10 MB)

Configuración personal del usuario:

- Carpeta `.openclaw/` con `openclaw.json`, `skills/`, `memory/`, etc.

---

## Métodos de instalación

### Método 1 — APK con archivos integrados

Recomendado si **no te importa** el tamaño del APK final.

1. Copia los dos archivos a `android/app/src/main/assets/`:

   ```
   android/app/src/main/assets/payload-v2.tar.xz
   android/app/src/main/assets/openclaw-apk-migration.tar.gz
   ```

2. Compila e instala la app:

   ```bash
   cd android
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. Abre la app. En la pantalla **Bootstrap** pulsa **"Iniciar instalación"** — la app extraerá los archivos automáticamente.

### Método 2 — APK liviano + carga manual

Recomendado para distribuir una app pequeña.

1. Compila el APK **sin incluir** los archivos en `assets/`.
2. Instala y abre la app.
3. En la pantalla **Bootstrap** verás dos tarjetas:
   - **Payload** → pulsa **"Cargar"** y selecciona tu archivo `.tar.xz`.
   - **Migración** → pulsa **"Cargar"** y selecciona tu archivo `.tar.gz`.
4. Cuando ambos estén cargados, pulsa **"Iniciar instalación"**.

---

## Reemplazar archivos locales

Aunque el APK ya traiga payload/migración embebidos, puedes **sobrescribirlos**:

- En la pantalla Bootstrap, cada tarjeta muestra la fuente actual: `Incluido en APK`, `Archivo local`, `Descarga remota` o `No disponible`.
- El botón **"Reemplazar"** abre el selector de archivos del sistema; el archivo seleccionado se guarda como _override_ y se usa en la siguiente instalación.
- El botón **"Cambiar"** sustituye un override local anterior por otro.

> Los overrides se almacenan en el `cacheDir` privado de la app:
> - `openclaw_payload_override.tar.xz`
> - `openclaw_migration_override.tar.gz`

---

## Estados del gateway

La app usa un **Foreground Service** para que Android no mate el proceso al salir:

| Estado | Significado |
| --- | --- |
| **Notificación activa** | Servidor en marcha. |
| **Health Check** | Cada 10 s la app verifica `http://127.0.0.1:18789/health`. |
| **Auto-reinicio** | Si Node.js cae, el servicio intenta reiniciarlo. |

---

## Solución de problemas

| Síntoma | Causa probable / solución |
| --- | --- |
| **El dashboard no carga.** | Confirma que la notificación de OpenClaw está presente. Si dice "Health check failed", espera unos segundos al arranque de Node.js. |
| **Error de extracción.** | Verifica al menos **500 MB libres** en el almacenamiento interno. |
| **No se solicita permiso de notificaciones.** | Concede manualmente en *Ajustes → Apps → OpenClaw → Notificaciones*. |
| **Bootstrap atorado en "Verificando".** | Pulsa **"Revisar de nuevo"**. Si persiste, usa el botón **"Cargar"** para proporcionar archivos manualmente. |

Más detalles en [docs/troubleshooting.md](docs/troubleshooting.md).
