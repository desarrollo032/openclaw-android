# Política de seguridad

---

## Índice

- [Versiones con soporte](#versiones-con-soporte)
- [Reporte de vulnerabilidades](#reporte-de-vulnerabilidades)
- [Tiempos de respuesta](#tiempos-de-respuesta)
- [Arquitectura de seguridad](#arquitectura-de-seguridad)
- [Qué protege OpenClaw](#qué-protege-openclaw)
- [Fuera de alcance](#fuera-de-alcance)

---

## Versiones con soporte

| Versión | ¿Soportada? |
| --- | --- |
| `1.0.x` | ✅ |

---

## Reporte de vulnerabilidades

> **No abras un issue público en GitHub** para reportar vulnerabilidades de seguridad.

Reporta de forma responsable a través de **[GitHub Security Advisories](https://github.com/desarrollo032/openclaw-android/security/advisories/new)**.

### Qué incluir

- Descripción clara de la vulnerabilidad.
- Pasos de reproducción.
- Evaluación de impacto.
- Sugerencia de corrección (si la tienes).

---

## Tiempos de respuesta

| Etapa | Plazo |
| --- | --- |
| **Acuse de recibo** | 48 horas |
| **Evaluación** | 1 semana |
| **Corrección** | 2 semanas (para problemas críticos) |

---

## Arquitectura de seguridad

OpenClaw en Android ejecuta binarios Linux dentro de un contenedor **proot + Alpine Linux**. El modelo de seguridad se basa en este entorno:

```
┌───────────────────────────────────────────────────┐
│ Kernel Android (SELinux aplicado)                  │
│ ┌───────────────────────────────────────────────┐ │
│ │ Sandbox de la app (com.openclaw.android)       │ │
│ │ ┌───────────────────────────────────────────┐ │ │
│ │ │ proot (traductor de syscalls, userspace)   │ │ │
│ │ │ ┌───────────────────────────────────────┐ │ │ │
│ │ │ │ Alpine Linux rootfs                    │ │ │ │
│ │ │ │ · /bin/sh (busybox)                    │ │ │ │
│ │ │ │ · Node.js + npm (via apk)              │ │ │ │
│ │ │ │ · openclaw (via npm)                   │ │ │ │
│ │ │ └───────────────────────────────────────┘ │ │ │
│ │ └───────────────────────────────────────────┘ │ │
│ └───────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────┘
```

### Capas de aislamiento

1. **Sandbox de la app Android** — la app corre en su propio namespace Linux bajo `com.openclaw.android`; no accede a datos de otras apps.
2. **SELinux** — el control de acceso obligatorio (MAC) de Android aplica a todos los procesos de la app.
3. **Sin root** — todo el stack corre como usuario sin privilegios. `proot` traduce syscalls para simular root dentro del contenedor, usando `-0` (fake root) para compatibilidad con Samsung Knox.
4. **proot** — capa de traducción de syscalls que permite ejecutar un rootfs Linux completo sin permisos de root reales. Usa `--link2symlink` para manejar symlinks de Alpine sin depender de `symlink()` del kernel.
5. **Filesystem privado** — el rootfs Alpine y los datos de OpenClaw viven en `context.filesDir` y `context.cacheDir`, inaccesibles para otras apps.

---

## Qué protege OpenClaw

- **Acceso no autorizado** al sistema Android o a datos de otras apps (Android sandbox).
- **Ejecución de código** fuera del sandbox de la app (SELinux + app sandbox).
- **Path traversal** desde el contenedor proot hacia rutas del sistema (bind mounts controlados).
- **Tokens efímeros** — el token de dashboard (UUID + timestamp) nunca se persiste a disco.

---

## Fuera de alcance

| Componente | Dónde reportar |
| --- | --- |
| Vulnerabilidades del core OpenClaw | [OpenClaw upstream](https://github.com/openclaw/openclaw) |
| Vulnerabilidades de proot | [proot-rs / proot](https://github.com/proot-me/proot-rs) |
| Vulnerabilidades de Alpine Linux | [Alpine Security](https://alpinelinux.org/security/) |
| Seguridad a nivel del dispositivo (dispositivos rooteados, bootloaders desbloqueados) | No aplica |
