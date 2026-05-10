# Terminal PTY

OpenClaw Android incluye una terminal interactiva que permite ejecutar comandos directamente en el entorno de OpenClaw.

## 🛠️ Componentes

1. **TerminalView**: Widget de Android que renderiza la rejilla de caracteres.
2. **TerminalSession**: Gestiona el proceso shell y el buffer de texto.
3. **libbusybox.so**: Proporciona las herramientas estándar de Linux (`ls`, `grep`, `sh`, etc.) en un solo binario estático.

## 🐚 Selección del Shell

El sistema intenta usar BusyBox por defecto porque ofrece un entorno más predecible que el `/system/bin/sh` de Android, que suele estar muy limitado o variar entre fabricantes.

1. Se verifica si `libbusybox.so` es ejecutable.
2. Si falla, se realiza un fallback automático a `/system/bin/sh`.
3. Se inyectan las mismas variables de entorno que usa el Gateway para asegurar que `node` y `openclaw` funcionen igual.

## 🎹 Teclas Especiales

La terminal en Android es difícil de usar con el teclado virtual estándar. Por eso, hemos añadido una barra de teclas rápidas:
* **ESC**: Envía el código de escape.
* **TAB**: Autocompletado de comandos.
* **CTRL / ALT**: Modificadores persistentes (se quedan "pulsados" para la siguiente tecla).
* **Flechas**: Navegación por el historial de comandos y edición de línea.

## 📋 Copiar y Pegar

La terminal soporta copiar y pegar texto con el portapapeles del sistema:
- **Copiar**: Selecciona texto y el sistema lo copia automáticamente al portapapeles. Muestra un Toast de confirmación.
- **Pegar**: El texto del portapapeles se inserta directamente en la terminal.
- **Callbacks**: Implementa `onCopyTextToClipboard` y `onPasteTextFromClipboard` de la librería de Termux.

## 📂 Simlinks de Busybox

Para que comandos como `ls` funcionen simplemente escribiendo `ls` (y no `busybox ls`), el `OpenClawTerminalManager` crea symlinks en la carpeta `bin` del payload durante la instalación:

```kotlin
// Ejemplo de creación en Kotlin
Os.symlink(busyboxPath, linkPath)
```

## ⚠️ Limitaciones Conocidas

* **Fuentes**: La terminal requiere una fuente monoespaciada para que las celdas se alineen correctamente.
* **Encoding**: Se asume `UTF-8`. Caracteres especiales de otras codificaciones podrían no renderizarse bien.
* **Permisos**: Aunque es una terminal, sigues estando bajo los permisos del usuario de la app. No puedes ejecutar comandos `root` (su) a menos que el dispositivo esté rooteado.
