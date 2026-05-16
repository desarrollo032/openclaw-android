# Terminal PTY

OpenClaw Android incluye una terminal interactiva que ejecuta comandos **dentro del contenedor proot + Alpine Linux**.

---

## Índice

- [Componentes](#componentes)
- [Selección del shell](#selección-del-shell)
- [Teclas especiales](#teclas-especiales)
- [Copiar y pegar](#copiar-y-pegar)
- [Limitaciones conocidas](#limitaciones-conocidas)

---

## Componentes

| Componente | Función |
| --- | --- |
| **`TerminalView`** | Widget Android que renderiza la rejilla de caracteres. |
| **`TerminalSession`** | Gestiona el proceso shell y el buffer de texto. |
| **`OpenClawProot`** | Construye los argumentos de `proot` para ejecutar el shell dentro del Alpine. |

Las librerías provienen de los AAR oficiales de **Termux** (`terminal-emulator` y `terminal-view`) ubicados en `android/app/libs/`.

---

## Selección del shell

El terminal usa **Alpine Linux shell** (`/bin/sh`) dentro de proot.

1. `OpenClawTerminalManager.createSession()` construye el comando proot:
   ```
   proot --rootfs=<alpine_dir> --bind=/proc --bind=/dev --change-id=0:0 /bin/sh -i
   ```
2. Se inyectan las variables de entorno de Alpine (`PATH`, `TERM`, `OPENCLAW_HOME`).
3. Node.js, npm y openclaw están disponibles directamente desde el shell.

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

## Limitaciones conocidas

- **Fuentes** — la terminal requiere una fuente monoespaciada para alinear las celdas correctamente.
- **Encoding** — se asume `UTF-8`; caracteres en otras codificaciones podrían no renderizarse bien.
- **Permisos** — pese a ser una terminal, sigues bajo los permisos de la app. No hay `su` salvo dispositivos rooteados.
- **Proot overhead** — proot añade latencia en llamadas al sistema, pero es negligible para uso interactivo.
