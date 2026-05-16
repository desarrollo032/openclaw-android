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
| **proot** | Traductor de llamadas al sistema que permite ejecutar Linux sin root (`chroot` simulado). |
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
2. **Descarga del rootfs Alpine** — se descarga la imagen Alpine mínima (`apk --root`) o se usa un rootfs incluido en assets.
3. **Bootstrap de Alpine** — se ejecuta `apk add --initdb` y se instalan paquetes base (`busybox`, `alpine-base`).
4. **Instalación de Node.js** — `apk add nodejs npm`.
5. **Instalación de OpenClaw** — `npm install -g openclaw` dentro del Alpine.
6. **Configuración del entorno** — se crean scripts wrapper para `openclaw`, `node`, `npm`.
7. **Verificación** — se ejecuta `openclaw --version` para confirmar que todo funciona.
8. **Marcar instalación completa** — se persiste la flag `KEY_ALPINE_INSTALLED`.

---

## Verificación post-instalación

`isAlpineSetupComplete(context)` verifica:

- El directorio Alpine existe y contiene rootfs (`alpine/etc/alpine-release`).
- `proot` es ejecutable.
- Node.js responde dentro del proot.
- OpenClaw responde dentro del proot.

Si todo está en orden, se actualiza la flag `KEY_ALPINE_INSTALLED` en `SharedPreferences`.

---

## Errores comunes

| Error | Causa | Solución |
| --- | --- | --- |
| `No hay espacio suficiente` | Menos de 500 MB libres. | Liberar espacio y reintentar. |
| `proot: execve: Permission denied` | Android bloquea la ejecución en datos de app. | Verificar que proot esté en `nativeLibraryDir`. |
| `apk: not found` | Alpine no se bootstrapó correctamente. | Reintentar instalación. |
| `Connection refused (port 18789)` | Gateway no iniciado o Node.js no instalado. | Verificar instalación de Alpine + Node.js. |
