# Proceso de Instalación

La instalación de OpenClaw en Android no es solo copiar archivos, sino preparar un entorno de ejecución compatible con Linux (glibc).

## 📦 Contenido de los Paquetes

### 1. `payload-v2.tar.xz`
Este es el corazón del sistema. Contiene:
* `/bin`: Wrappers para `node`, `npm` y `openclaw`.
* `/lib/node_modules`: El código fuente de OpenClaw y sus dependencias.
* `/glibc`: Librerías base necesarias para que Node.js funcione en Android.
* `/etc`: Configuración básica y certificados SSL.

### 2. `openclaw-apk-migration.tar.gz`
Contiene la configuración de migraciones opcionales del usuario (.openclaw) si se detecta una instalación previa de otra versión.

## 🛠️ Configuración de Gradle (Importante)

Para que Android no intente comprimir los archivos `.xz` o `.gz` (lo que impediría su lectura aleatoria o los dañaría), es obligatorio configurar `noCompress` en `app/build.gradle.kts`:

```kotlin
androidResources {
    noCompress += listOf("xz", "gz", "tar", "tar.xz", "tar.gz")
}
```

## ⚙️ Proceso de Extracción Paso a Paso

1. **Borrado de Seguridad**: Se elimina cualquier instalación previa en el directorio de la app para evitar conflictos.
2. **Extracción**: Se usa `commons-compress` y `xz-java` para descomprimir el payload directamente en el almacenamiento interno privado.
3. **Despliegue de Scripts**: Se generan wrappers dinámicos que inyectan las rutas correctas (especialmente `nativeLibraryDir`) en las variables de entorno.
4. **Fix de Permisos**: Se aplican permisos de lectura y ejecución a los directorios y scripts shell.

## ✅ Verificación de Éxito

Para confirmar que la instalación es correcta, la app verifica:
1. Existencia de `lib/node_modules/openclaw/openclaw.mjs`.
2. Existencia de `libnode.so` en el directorio de librerías nativas.

## 🆘 Errores Comunes

| Error | Causa | Solución |
| :--- | :--- | :--- |
| "No hay espacio suficiente" | El dispositivo tiene menos de 500MB libres. | Liberar espacio y reintentar. |
| "Permission denied" | Intento de ejecución en `/sdcard`. | La instalación debe ser en el almacenamiento interno privado. |
| "File not found: libnode.so" | La arquitectura del APK (arm64/v7) no coincide. | Asegúrate de usar el APK correcto para el procesador. |
