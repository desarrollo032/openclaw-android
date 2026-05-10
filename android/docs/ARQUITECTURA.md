# Arquitectura del Sistema

OpenClaw Android utiliza una arquitectura híbrida para combinar la flexibilidad de una interfaz web con la potencia de un runtime Node.js nativo en un entorno restringido.

## 🏗️ Capas del Sistema

1. **Capa Nativa (Kotlin)**: Gestiona los permisos, el sistema de archivos, el servicio en primer plano (Foreground Service) y la ejecución de procesos.
2. **Bridge (JavascriptInterface)**: Canal de comunicación bidireccional entre la lógica nativa y el WebView.
3. **Interfaz (React)**: Proporciona el Dashboard, la Terminal y la configuración al usuario.
4. **Runtime (Node.js)**: El motor que ejecuta OpenClaw, operando como un proceso hijo del sistema Android.

## 🚀 Flujo de Inicio de la App

```text
Usuario abre App
       │
  MainActivity
       │
Check Payload? ────── NO ────► Pantalla de Instalación
       │                         (Extraer .tar.xz)
      SI
       │
Cargar React UI
       │
Check Gateway? ────── NO ────► Usuario pulsa "Start"
       │                         (Start GatewayService)
      SI
       │
Gateway Ready! ──────────────► Conexión a 127.0.0.1:18789
```

## 📦 Flujo de Instalación del Payload

1. Se verifica la integridad del archivo `payload-v2.tar.xz` en los assets.
2. Se extrae el contenido en el directorio privado `getDir("payload", ...)`.
3. Se copian los scripts de mantenimiento a la carpeta `bin`.
4. **Punto Crítico**: Los binarios ELF (como `libnode.so`) NO se pueden ejecutar desde el directorio de datos en Android 12+.

## 🛡️ Política W^X y Resolución en Android 12+

Desde Android 10, y reforzado en Android 12, el sistema impide la ejecución de archivos en directorios de datos de la app (`/data/data/...`) por seguridad.

**Solución OpenClaw**: 
Solo el directorio `nativeLibraryDir` (donde el sistema instala las `.so`) tiene permisos de ejecución nativos. 
1. Los binarios necesarios (`libnode.so`, `libldlinux.so`, `libbusybox.so`) se empaquetan como librerías nativas de Android.
2. Android los extrae automáticamente al instalar el APK.
3. Usamos el enlazador dinámico nativo (`libldlinux.so`) para cargar y ejecutar `libnode.so`.

### Cadena de Ejecución Obligatoria
`ldlinux.so (Linker)` → `libnode.so (Node.js)` → `openclaw.mjs (Script)`

Este flujo permite que Node.js herede los permisos de ejecución del linker, evitando el bloqueo de seguridad del sistema operativo.
