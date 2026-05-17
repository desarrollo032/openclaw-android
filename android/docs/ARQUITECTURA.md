# Arquitectura del sistema

**OpenClaw Android** combina una UI web (React) con el runtime de **OpenClaw ejecutándose dentro de un contenedor proot + Alpine Linux**, todo dentro del sandbox de Android.

---

## Índice

- [Capas del sistema](#capas-del-sistema)
- [Flujo de inicio](#flujo-de-inicio)
- [Flujo de instalación (Alpine bootstrap)](#flujo-de-instalación-alpine-bootstrap)
- [Terminal embebido con proot](#terminal-embebido-con-proot)

---

## Capas del sistema

| Capa | Tecnología | Función |
| --- | --- | --- |
| **Nativa** | Kotlin | Permisos, filesystem, Foreground Service, ejecución de procesos. |
| **Bridge** | `@JavascriptInterface` + CustomEvents | Comunicación bidireccional Kotlin ↔ WebView. |
| **Interfaz** | React 19 + Vite + Tailwind | Dashboard, terminal, ajustes. |
| **Contenedor** | **proot** + **Alpine Linux** | Entorno Linux completo con `sh`, Node.js, npm y OpenClaw. |

---

## Flujo de inicio

```text
Usuario abre la app
         │
   MainActivity
         │
 ¿Alpine instalado? ──── NO ────► Pantalla de instalación
         │                          (bootstrap Alpine rootfs)
        SÍ
         │
  Cargar UI React
         │
 ¿Gateway activo? ───── NO ────► Usuario pulsa "Start"
         │                       (lanza GatewayService dentro de proot)
        SÍ
         │
 Gateway Ready! ──────────────► Conexión a 127.0.0.1:18789
```

---

## Flujo de instalación (Alpine bootstrap)

1. Se descarga la imagen Alpine mínima desde los servidores oficiales o se usa un rootfs local.
2. Se extrae el rootfs a `getDir("alpine", MODE_PRIVATE)`.
3. Se instala Node.js + npm dentro del Alpine mediante `apk add`.
4. Se instala OpenClaw dentro del Alpine mediante `npm install -g openclaw`.
5. Se configura el entorno: `PATH`, `OPENCLAW_HOME`, `TERM`.
6. Se verifica la instalación ejecutando `openclaw --version` dentro del proot.

**No se necesita glibc, ni payload-v2.tar.xz, ni libnode.so.** Todo corre dentro del Alpine vía proot.

---

## Terminal embebido con proot

El terminal interactivo corre **dentro del contenedor proot + Alpine**:

```
Usuario escribe comando
       ↓
OpenClawTerminalManager.createSession()
       ↓
proot --link2symlink -0 --rootfs=... --bind=/proc ...
    → /bin/sh -i (Alpine shell)
       ↓
Node.js, npm, openclaw disponibles directamente
```

Flags clave:
- `--link2symlink` — necesario para que Alpine funcione en filesystems Android restrictivos (Samsung Knox).
- `-0` — fake root sin activar restricciones SELinux extra (reemplaza a `--change-id=0:0`).

Ver [TERMINAL.md](./TERMINAL.md) para más detalles.
