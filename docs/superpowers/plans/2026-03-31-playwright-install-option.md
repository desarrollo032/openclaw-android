# Plan de implementación: Instalación opcional de Playwright

> **Para agentes autónomos:** HABILIDAD SECUNDARIA REQUERIDA: Usa superpowers:subagent-driven-development (recomendado) o superpowers:executing-plans para implementar este plan tarea por tarea. Los pasos usan sintaxis de casilla de verificación (`- [ ]`).

**Objetivo:** Agregar Playwright como herramienta opcional en `oa --install`, con resolución automática de dependencia de Chromium, configuración de variables de entorno y guía de uso.

**Arquitectura:** El nuevo `scripts/install-playwright.sh` sigue el patrón de scripts delegados existente (como `install-chromium.sh`). `install-tools.sh` recibe una nueva entrada de menú justo después de Chromium. El script de instalación verifica la dependencia de Chromium, instala `playwright-core` vía npm, establece las variables de entorno de Playwright en `.bashrc` e imprime una guía de uso.

**Stack técnico:** Bash, npm (playwright-core), entorno Termux

---

## Tarea 1: Crear `scripts/install-playwright.sh`

**Archivos:**
- Crear: `scripts/install-playwright.sh`

- [ ] **Paso 1: Crear el script de instalación**

```bash
#!/usr/bin/env bash
# install-playwright.sh - Instalar Playwright para automatización de navegador
# Uso: bash install-playwright.sh [install|update]
#
# Lo que hace:
#   1. Asegura que Chromium esté instalado (dependencia)
#   2. Instala playwright-core vía npm global
#   3. Establece variables de entorno de Playwright en .bashrc
#   4. Imprime guía de uso
#
# Este script es de nivel WARN: el fallo no aborta el instalador padre.
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

MODE="${1:-install}"

# ── Ayudante ────────────────────────────────────

fail_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
    exit 0
}

# ── Detectar ruta del binario Chromium ───────────────

detect_chromium_bin() {
    for bin in "$PREFIX/bin/chromium-browser" "$PREFIX/bin/chromium"; do
        if [ -x "$bin" ]; then
            echo "$bin"
            return 0
        fi
    done
    return 1
}

# ── Pre-verificaciones ────────────────────────────────

if [ -z "${PREFIX:-}" ]; then
    fail_warn "No se está ejecutando en Termux (\$PREFIX no está definido)"
fi

if ! command -v npm &>/dev/null; then
    fail_warn "npm no encontrado — Node.js es necesario para Playwright"
fi

# ── Paso 1: Asegurar que Chromium esté instalado ──────

if ! CHROMIUM_BIN=$(detect_chromium_bin); then
    echo "Chromium es necesario para Playwright. Instalando..."
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
    if [ -f "$SCRIPT_DIR/install-chromium.sh" ]; then
        if ! bash "$SCRIPT_DIR/install-chromium.sh" install; then
            fail_warn "La instalación de Chromium falló — no se puede continuar con Playwright"
        fi
    else
        fail_warn "install-chromium.sh no encontrado — instala Chromium primero"
    fi

    if ! CHROMIUM_BIN=$(detect_chromium_bin); then
        fail_warn "Binario de Chromium no encontrado después de la instalación"
    fi
fi

echo -e "${GREEN}[OK]${NC}   Chromium encontrado: $CHROMIUM_BIN"

# ── Paso 2: Instalar playwright-core ───────────

if [ "$MODE" = "install" ]; then
    # Verificar si ya está instalado
    if npm list -g playwright-core &>/dev/null; then
        echo -e "${GREEN}[SKIP]${NC} playwright-core ya está instalado"
    else
        echo "Instalando playwright-core..."
        if ! npm install -g playwright-core; then
            fail_warn "Error al instalar playwright-core"
        fi
        echo -e "${GREEN}[OK]${NC}   playwright-core instalado"
    fi
elif [ "$MODE" = "update" ]; then
    echo "Actualizando playwright-core..."
    if ! npm install -g playwright-core@latest; then
        fail_warn "Error al actualizar playwright-core"
    fi
    echo -e "${GREEN}[OK]${NC}   playwright-core actualizado"
fi

# ── Paso 3: Establecer variables de entorno ─────────

BASHRC="$HOME/.bashrc"
PW_MARKER_START="# >>> Playwright >>>"
PW_MARKER_END="# <<< Playwright <<<"

PW_BLOCK="${PW_MARKER_START}
export PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=\"$CHROMIUM_BIN\"
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
${PW_MARKER_END}"

touch "$BASHRC"
if grep -qF "$PW_MARKER_START" "$BASHRC"; then
    sed -i "/${PW_MARKER_START//\//\\/}/,/${PW_MARKER_END//\//\\/}/d" "$BASHRC"
fi
echo "" >> "$BASHRC"
echo "$PW_BLOCK" >> "$BASHRC"

echo -e "${GREEN}[OK]${NC}   Variables de entorno establecidas en .bashrc"
echo "       PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=$CHROMIUM_BIN"
echo "       PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1"

# ── Paso 4: Guía de uso ──────────────────────

echo ""
echo -e "${BOLD}  ¡Playwright está listo!${NC}"
echo ""
echo "  Para usarlo en tu proyecto:"
echo ""
echo "    npm install playwright-core    # agregar a tu proyecto"
echo ""
echo "  Código de ejemplo:"
echo ""
echo "    const { chromium } = require('playwright-core');"
echo ""
echo "    const browser = await chromium.launch();"
echo "    const page = await browser.newPage();"
echo "    await page.goto('https://example.com');"
echo "    await page.screenshot({ path: 'screenshot.png' });"
echo "    await browser.close();"
echo ""
echo -e "  ${YELLOW}[NOTA]${NC} Las variables de entorno están configuradas. No es necesario"
echo "         especificar executablePath ni --no-sandbox manualmente."
echo ""
echo "  Para aplicar las variables de entorno en la sesión actual:"
echo "    source ~/.bashrc"
echo ""
```

Escribe esto en `scripts/install-playwright.sh`.

- [ ] **Paso 2: Hacer el script ejecutable**

Ejecutar: `chmod +x scripts/install-playwright.sh`

---

## Tarea 2: Agregar Playwright a `install-tools.sh`

**Archivos:**
- Modificar: `install-tools.sh:79` (detección de herramientas)
- Modificar: `install-tools.sh:118` (declaración de bandera)
- Modificar: `install-tools.sh:124` (mensaje al usuario — después de Chromium)
- Modificar: `install-tools.sh:135-137` (verificación de selección)
- Modificar: `install-tools.sh:152` (condición NEEDS_TARBALL)
- Modificar: `install-tools.sh:202-208` (fase de instalación — después de Chromium)

- [ ] **Paso 1: Agregar Playwright a la detección de herramientas**

Después de la línea 79 (`check_tool "Chromium" "chromium-browser"`), detectar playwright-core vía npm:

```bash
# Detección de Playwright — verificar vía npm ya que es un paquete npm global
if npm list -g playwright-core &>/dev/null 2>&1; then
    TOOL_STATUS["Playwright"]="installed"
    echo -e "  ${GREEN}[INSTALADO]${NC} Playwright"
else
    TOOL_STATUS["Playwright"]="not_installed"
    echo -e "  ${YELLOW}[NO INSTALADO]${NC} Playwright"
fi
```

- [ ] **Paso 2: Agregar declaración de bandera**

Después de `INSTALL_CHROMIUM=false` (línea 118), agregar:

```bash
INSTALL_PLAYWRIGHT=false
```

- [ ] **Paso 3: Agregar mensaje al usuario después del mensaje de Chromium**

Después de la línea del mensaje de Chromium (línea 124), agregar:

```bash
if [ "${TOOL_STATUS[Playwright]}" = "not_installed" ] && ask_yn "  ¿Instalar Playwright (biblioteca de automatización de navegador, requiere Chromium)?"; then INSTALL_PLAYWRIGHT=true; fi
```

- [ ] **Paso 4: Agregar a la verificación de selección**

Agregar `INSTALL_PLAYWRIGHT` al bucle for en las líneas 135-137:

```bash
for var in INSTALL_TMUX INSTALL_TTYD INSTALL_DUFS INSTALL_ANDROID_TOOLS \
           INSTALL_CHROMIUM INSTALL_PLAYWRIGHT INSTALL_CODE_SERVER INSTALL_OPENCODE INSTALL_CLAUDE_CODE \
           INSTALL_GEMINI_CLI INSTALL_CODEX_CLI; do
```

- [ ] **Paso 5: Agregar condición NEEDS_TARBALL**

En la línea 152, agregar `INSTALL_PLAYWRIGHT` a la condición:

```bash
if [ "$INSTALL_CODE_SERVER" = true ] || [ "$INSTALL_OPENCODE" = true ] || [ "$INSTALL_CHROMIUM" = true ] || [ "$INSTALL_PLAYWRIGHT" = true ]; then
```

- [ ] **Paso 6: Agregar bloque de instalación después de Chromium**

Después del bloque de instalación de Chromium (después de la línea 208), agregar:

```bash
if [ "$INSTALL_PLAYWRIGHT" = true ]; then
    if bash "$RELEASE_TMP/scripts/install-playwright.sh" install; then
        echo -e "${GREEN}[OK]${NC}   Playwright instalado"
    else
        echo -e "${YELLOW}[WARN]${NC} Falló la instalación de Playwright (no crítico)"
    fi
fi
```

---

## Tarea 3: Verificar

- [ ] **Paso 1: Verificar sintaxis de install-playwright.sh**

Ejecutar: `bash -n scripts/install-playwright.sh`
Esperado: sin salida (sin errores de sintaxis)

- [ ] **Paso 2: Verificar sintaxis de install-tools.sh**

Ejecutar: `bash -n install-tools.sh`
Esperado: sin salida (sin errores de sintaxis)

- [ ] **Paso 3: Verificar que la lógica de detección de Playwright maneje npm ausente correctamente**

Ejecutar: `grep -n "npm list -g playwright-core" install-tools.sh`
Esperado: la línea de detección aparece en la sección de detección de herramientas
