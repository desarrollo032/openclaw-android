# Arquitectura del sistema

**OpenClaw Android** combina la flexibilidad de una UI web con la potencia de un runtime **Node.js + glibc** nativo, todo dentro del sandbox de Android.

---

## Índice

- [Capas del sistema](#capas-del-sistema)
- [Flujo de inicio](#flujo-de-inicio)
- [Flujo de instalación del payload](#flujo-de-instalación-del-payload)
- [Política W^X en Android 12+](#política-wx-en-android-12)
- [Cadena de ejecución obligatoria](#cadena-de-ejecución-obligatoria)

---

## Capas del sistema

| Capa | Tecnología | Función |
| --- | --- | --- |
| **Nativa** | Kotlin | Permisos, filesystem, Foreground Service, ejecución de procesos. |
| **Bridge** | `@JavascriptInterface` + CustomEvents | Comunicación bidireccional Kotlin ↔ WebView. |
| **Interfaz** | React 19 + Vite + Tailwind | Dashboard, terminal, ajustes. |
| **Runtime** | Node.js + glibc | Motor que ejecuta `openclaw.mjs`. |

---

## Flujo de inicio

```text
Usuario abre la app
         │
   MainActivity
         │
 ¿Payload instalado? ──── NO ────► Pantalla de instalación
         │                          (extraer .tar.xz)
        SÍ
         │
  Cargar UI React
         │
 ¿Gateway activo? ───── NO ────► Usuario pulsa "Start"
         │                       (lanza GatewayService)
        SÍ
         │
 Gateway Ready! ──────────────► Conexión a 127.0.0.1:18789
```

---

## Flujo de instalación del payload

1. Se verifica la integridad del asset `payload-v2.tar.xz` (o del override local provisto por el usuario).
2. Se extrae el contenido al directorio privado `getDir("payload", ...)`.
3. Se copian los scripts de mantenimiento a la carpeta `bin`.
4. **Punto crítico:** los binarios ELF (como `libnode.so`) **NO** se pueden ejecutar desde el directorio de datos en Android 12+.

---

## Política W^X en Android 12+

Desde Android 10, y reforzado en Android 12, el sistema impide la ejecución de archivos en directorios de datos de la app (`/data/data/...`) por seguridad.

**Solución OpenClaw:**

Solo el directorio `nativeLibraryDir` (donde el sistema instala las `.so`) tiene permisos de ejecución nativos.

1. Los binarios necesarios (`libnode.so`, `libldlinux.so`, `libbusybox.so`) se empaquetan como librerías nativas de Android.
2. Android los extrae automáticamente al instalar el APK.
3. Se usa el enlazador dinámico nativo (`libldlinux.so`) para cargar y ejecutar `libnode.so`.

---

## Cadena de ejecución obligatoria

```text
libldlinux.so (Linker) → libnode.so (Node.js) → openclaw.mjs (Script)
```

Este flujo permite que Node.js **herede** los permisos de ejecución del linker, evitando el bloqueo de seguridad del sistema operativo.
