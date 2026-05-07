# Guía de Instalación - OpenClaw Android

Este documento explica cómo preparar y usar la aplicación OpenClaw de forma autónoma.

## 📦 Preparación de Archivos

Necesitas dos archivos comprimidos generados previamente (usualmente desde una instalación de Termux funcional):

### 1. `payload.tar.xz` (~170MB)
Debe contener la estructura de directorios necesaria para ejecutar Node.js:
- `bin/node`
- `glibc/lib/ld-linux-aarch64.so.1` (y librerías asociadas)
- `lib/node_modules/openclaw/`
- `lib/node_modules/clawdhub/`

### 2. `openclaw-apk-migration.tar.gz` (~10MB)
Debe contener tu configuración personal:
- Carpeta `.openclaw/` con `openclaw.json`, `skills/`, `memory/`, etc.

---

## 📲 Métodos de Instalación

### Método 1: Instalación Rápida (Todo Incluido)
Ideal si no te importa el tamaño del archivo APK final.
1. Copia `payload.tar.xz` y `openclaw-apk-migration.tar.gz` a la carpeta `android/app/src/main/assets/`.
2. Compila e instala la aplicación.
3. Abre la app y selecciona **"Install from Assets"**.
4. La app extraerá todo automáticamente y abrirá el Dashboard.

### Método 2: App Liviana (Carga Manual)
Ideal para distribuir una app pequeña y cargar los datos pesados después.
1. Compila la aplicación sin incluir los archivos en la carpeta `assets`.
2. Instala y abre la aplicación.
3. Verás dos botones de selección:
   - Pulsa **"Select Payload"** y elige tu archivo `.tar.xz`.
   - Pulsa **"Select Config"** y elige tu archivo `.tar.gz`.
4. Cuando ambos estén seleccionados, pulsa **"Start Installation"**.

---

## 🚦 Estados del Gateway

La aplicación utiliza un servicio en primer plano para asegurar que OpenClaw no sea cerrado por el sistema Android:
- **Notificación Activa**: Indica que el servidor está corriendo.
- **Health Check**: La app verifica cada 10 segundos que la URL `http://127.0.0.1:18789/health` responda correctamente.
- **Auto-reinicio**: Si el proceso de Node.js se detiene inesperadamente, el servicio intentará reiniciarlo automáticamente.

---

## ❓ Solución de Problemas

- **El Dashboard no carga**: Verifica que la notificación de OpenClaw esté presente. Si dice "Health check failed", espera unos segundos a que Node.js termine de arrancar.
- **Error de extracción**: Asegúrate de tener al menos 500MB de espacio libre en la memoria interna del teléfono.
- **Permisos**: La app solicitará permiso de notificaciones para mostrar el estado del servicio en segundo plano.
