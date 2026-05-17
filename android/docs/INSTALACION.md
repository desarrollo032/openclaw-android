# Proceso de instalación

Instalar OpenClaw en Android implica preparar un entorno **Alpine Linux completo** usando **proot**, que permite ejecutar Linux sin permisos de root.

---

## Índice

- [Arquitectura: proot + Alpine](#arquitectura-proot--alpine)
- [Configuración de Gradle](#configuración-de-gradle)
- [Etapas de instalación](#etapas-de-instalación)
- [Verificación post-instalación](#verificación-post-instalación)
- [Errores comunes](#errores-comunes)

---

## Arquitectura: proot + Alpine

| Componente | Propósito |
| --- | --- |
| **proot** | Traductor de llamadas al sistema que permite ejecutar Linux sin root. Usa `--link2symlink` (symlinks en filesystem Android) y `-0` (fake root compatible Samsung Knox). |
| **Alpine Linux** | Distribución Linux mínima (`~5 MB` base) que contiene `sh`, `apk`, y puede instalar Node.js. |
| **Node.js + npm** | Instalados dentro de Alpine mediante `apk add nodejs npm`. |
| **OpenClaw** | Instalado globalmente dentro de Alpine mediante `npm install -g openclaw`. |

**No se necesita glibc, ni binarios ELF precompilados, ni payload-v2.tar.xz.** Todo el ecosistema corre dentro del Alpine vía proot.

---

## Configuración de Gradle

Para assets comprimidos dentro del APK, es recomendable configurar `noCompress` en `app/build.gradle.kts`:

```kotlin
androidResources {
    noCompress += listOf("xz", "gz", "tar", "tar.xz", "tar.gz")
}
```

---

## Etapas de instalación

1. **Verificación de espacio** — se comprueba que haya al menos 500 MB libres.
2. **Verificación de red y proot** — se verifica que `libproot.so` esté presente y ejecutable, y que haya conexión a Internet.
3. **Descarga del rootfs Alpine** — se descarga Alpine minirootfs ARM64 desde dl-cdn.alpinelinux.org (con fallback HTTP).
4. **Extracción con symlinks** — se extrae el tar.gz preservando enlaces simbólicos (crítico: `bin/sh` → `busybox`). Se aplican permisos `+x` a todos los binarios en `bin/`, `sbin/`, `usr/bin/`, etc.
5. **Creación de directorios base** — se crean `/root`, `/tmp` y `/.l2s` (requerido por `--link2symlink`) dentro del rootfs.
6. **Sanity check** — se ejecuta `/bin/sh -c 'echo ok'` dentro de proot para verificar que el contenedor funciona.
7. **Instalación de Node.js** — `apk add nodejs npm` dentro del Alpine.
8. **Instalación de OpenClaw** — `pnpm add -g openclaw@beta` dentro del Alpine.
9. **Ejecución de onboard** — `openclaw onboard` para completar la configuración inicial.
10. **Marcar instalación completa** — se persiste la flag `KEY_ALPINE_INSTALLED`.

---

## Verificación post-instalación

`isAlpineSetupComplete(context)` verifica:

- El rootfs Alpine existe y contiene `/bin/sh` ejecutable.
- `busybox` es ejecutable (target de `/bin/sh`).
- `libproot.so` está presente y es ejecutable en `nativeLibraryDir`.
- OpenClaw está instalado como módulo global de npm.

Si todo está en orden, se actualiza la flag `KEY_ALPINE_INSTALLED` en `SharedPreferences`.

---

## Errores comunes

| Error | Causa | Solución |
| --- | --- | --- |
| `No hay espacio suficiente` | Menos de 500 MB libres. | Liberar espacio y reintentar. |
| `proot error: execve: Permission denied` | `libproot.so` incorrecto o permisos insuficientes. | Verificar que proot esté en `nativeLibraryDir` y sea ejecutable. |
| `proot error: Function not implemented` | Flag `--change-id=0:0` en lugar de `-0`. | Usar `-0` (compatible Samsung Knox) en lugar de `--change-id=0:0`. |
| `apk: not found` | Alpine no se bootstrapó correctamente. | Reintentar instalación o reinstalar Alpine desde Ajustes. |
| `Connection refused (port 18789)` | Gateway no iniciado o Node.js no instalado. | Verificar instalación de Alpine + Node.js. |
