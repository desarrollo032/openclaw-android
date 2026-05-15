# Proceso de instalación

Instalar OpenClaw en Android no es solo copiar archivos: hay que preparar un entorno de ejecución compatible con Linux (**glibc**) dentro del sandbox de Android.

---

## Índice

- [Contenido de los paquetes](#contenido-de-los-paquetes)
- [Configuración de Gradle](#configuración-de-gradle)
- [Fuentes del payload](#fuentes-del-payload)
- [Etapas de instalación](#etapas-de-instalación)
- [Override local de archivos](#override-local-de-archivos)
- [Verificación post-instalación](#verificación-post-instalación)
- [Errores comunes](#errores-comunes)

---

## Contenido de los paquetes

### 1. `payload-v2.tar.xz`

**Corazón del sistema.** Contiene:

- `/bin` — wrappers para `node`, `npm` y `openclaw`.
- `/lib/node_modules` — código fuente de OpenClaw y dependencias.
- `/glibc` — librerías glibc necesarias para Node.js en Android.
- `/etc` — configuración básica y certificados SSL.

### 2. `openclaw-apk-migration.tar.gz`

Configuración opcional del usuario (carpeta `.openclaw`) si se detecta una instalación previa de otra versión.

---

## Configuración de Gradle

Para que Android **no** intente comprimir los archivos `.xz` ni `.gz` (lo que impediría su lectura aleatoria o los dañaría), es obligatorio configurar `noCompress` en `app/build.gradle.kts`:

```kotlin
androidResources {
    noCompress += listOf("xz", "gz", "tar", "tar.xz", "tar.gz")
}
```

---

## Fuentes del payload

El instalador acepta tres fuentes (en orden de prioridad):

| Fuente | Origen | Cuándo se usa |
| --- | --- | --- |
| **Local (override)** | Archivo seleccionado por el usuario | Cuando existe un override en `cacheDir`. |
| **APK** | Asset incluido en `assets/payload-v2.tar.xz` | Cuando hay assets empaquetados. |
| **Remoto** | Descarga desde GitHub Releases | Cuando no hay assets ni override y hay red. |

> El campo `payloadSource` de `getSetupStatus()` devuelve `local`, `apk`, `remote` o `missing`.

---

## Etapas de instalación

1. **Selección de fuente** — el instalador decide qué archivo usar.
2. **Limpieza** — se borra cualquier instalación previa para evitar conflictos.
3. **Extracción** — se usa `commons-compress` y `xz-java` para descomprimir el payload a `getDir("payload", MODE_PRIVATE)`.
4. **Bootstrap de `npm`** — si falta, se descarga el paquete oficial desde el registry.
5. **Deploy de librerías nativas** — copia `libnode.so`, `libldlinux.so`, `libbusybox.so` desde el payload a `nativeLibraryDir`.
6. **Generación de wrappers** — crea `bin/node`, `bin/openclaw`, `bin/npm`, `bin/pnpm` con rutas absolutas.
7. **Permisos** — aplica `chmod` recursivo según reglas estrictas.
8. **Migración** — si está disponible, extrae `openclaw-apk-migration.tar.gz` a `home/.openclaw`.
9. **Layout final** — crea symlinks `usr/lib`, `usr/glibc`, `usr/etc`, `usr/tmp`.
10. **BusyBox symlinks** — para que comandos como `ls` funcionen sin prefijo.

---

## Override local de archivos

El usuario puede **reemplazar** los archivos integrados desde la UI:

- `pickPayloadFile()` → guarda en `cacheDir/openclaw_payload_override.tar.xz`.
- `pickMigrationFile()` → guarda en `cacheDir/openclaw_migration_override.tar.gz`.

En la siguiente ejecución de `startSetup()` el instalador detecta los overrides y los usa en lugar de los assets del APK.

---

## Verificación post-instalación

`isPayloadReady(context)` verifica:

- `payload/lib/node_modules/openclaw/` (directorio con contenido).
- `nativeLibraryDir/libnode.so` (archivo regular).
- `payload/lib/node_modules/npm/bin/npm-cli.js`.

Si todo está en orden, se actualiza la flag `KEY_PAYLOAD_INSTALLED` en `SharedPreferences`.

---

## Errores comunes

| Error | Causa | Solución |
| --- | --- | --- |
| `No hay espacio suficiente` | Menos de 500 MB libres. | Liberar espacio y reintentar. |
| `Permission denied` | Intento de ejecución en `/sdcard`. | La instalación debe vivir en el almacenamiento interno privado. |
| `File not found: libnode.so` | La arquitectura del APK no coincide (`arm64-v8a` requerido). | Usar el APK correcto para el procesador del dispositivo. |
| `Bootstrap atorado en "Verificando…"` | No hay payload ni override, y no hay red. | Pulsar **"Cargar"** y proporcionar el archivo localmente. |
