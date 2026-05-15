# Terminal PTY

OpenClaw Android incluye una terminal interactiva que permite ejecutar comandos directamente sobre el entorno del runtime.

---

## Índice

- [Componentes](#componentes)
- [Selección del shell](#selección-del-shell)
- [Teclas especiales](#teclas-especiales)
- [Copiar y pegar](#copiar-y-pegar)
- [Symlinks de BusyBox](#symlinks-de-busybox)
- [Limitaciones conocidas](#limitaciones-conocidas)

---

## Componentes

| Componente | Función |
| --- | --- |
| **`TerminalView`** | Widget Android que renderiza la rejilla de caracteres. |
| **`TerminalSession`** | Gestiona el proceso shell y el buffer de texto. |
| **`libbusybox.so`** | Provee las herramientas estándar de Linux (`ls`, `grep`, `sh`, etc.) en un binario único. |

Las librerías provienen de los AAR oficiales de **Termux** (`terminal-emulator` y `terminal-view`) ubicados en `android/app/libs/`.

---

## Selección del shell

Por defecto se intenta usar **BusyBox** porque ofrece un entorno más predecible que `/system/bin/sh`, que varía entre fabricantes.

1. Se verifica si `libbusybox.so` es ejecutable.
2. Si falla, **fallback** automático a `/system/bin/sh`.
3. Se inyectan las mismas variables de entorno que el Gateway para que `node` y `openclaw` funcionen igual.

---

## Teclas especiales

La terminal en Android es difícil de usar con el teclado virtual estándar. Por eso se incluye una **barra de teclas rápidas**:

- **ESC** — código de escape.
- **TAB** — autocompletado de comandos.
- **CTRL / ALT** — modificadores persistentes (quedan "pulsados" para la siguiente tecla).
- **Flechas** — navegación por el historial y edición de línea.

---

## Copiar y pegar

- **Copiar:** selecciona texto y el sistema lo copia automáticamente al portapapeles (con Toast de confirmación).
- **Pegar:** el texto del portapapeles se inserta directamente en la terminal.
- **Callbacks:** implementa `onCopyTextToClipboard` y `onPasteTextFromClipboard` de la librería Termux.

---

## Symlinks de BusyBox

Para que comandos como `ls` funcionen escribiendo solo `ls` (y no `busybox ls`), `OpenClawTerminalManager` crea symlinks en `payload/bin` durante la instalación:

```kotlin
// Ejemplo de creación
Os.symlink(busyboxPath, linkPath)
```

---

## Limitaciones conocidas

- **Fuentes** — la terminal requiere una fuente monoespaciada para alinear las celdas correctamente.
- **Encoding** — se asume `UTF-8`; caracteres en otras codificaciones podrían no renderizarse bien.
- **Permisos** — pese a ser una terminal, sigues bajo los permisos del usuario de la app. No hay `su` salvo dispositivos rooteados.
