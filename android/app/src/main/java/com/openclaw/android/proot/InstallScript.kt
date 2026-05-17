package com.openclaw.android.proot

/**
 * InstallScript — Shell script embebido para la instalación de OpenClaw.
 *
 * Este objeto genera el script de shell de 12 fases que se ejecuta dentro
 * del proot/Alpine para instalar Node.js, npm, pnpm y openclaw.
 *
 * Cada fase es resumible: escribe un marker file en
 * /root/.openclaw-install/<key>.done y los reintentos saltan las fases
 * ya completadas.
 *
 * Fix de proot/Android: al terminar nodejs/pnpm/openclaw aplicamos
 * fix_node_shebang para reescribir '#!/usr/bin/env node' →
 * '#!/usr/bin/node' y evitar el error
 *   env: can't execute 'node': Function not implemented
 */
object InstallScript {

    /** Versión de branch de Alpine para los repositorios apk. */
    private fun apkVersionBranch(alpineVersion: String): String =
        "v" + alpineVersion.substringBeforeLast('.')

    /**
     * Genera el script de instalación completo de 12 fases.
     *
     * @param alpineVersion versión de Alpine (e.g. "3.22.0")
     * @param openclawChannel versión a instalar: "estable" o "beta"
     * @return script shell listo para ejecutar con /bin/sh -c
     */
    fun generate(
        alpineVersion: String = AlpineDownloader.ALPINE_VERSION,
        openclawChannel: String = "estable" // Puede ser "estable" o "beta"
    ): String {
        val ocPackage = if (openclawChannel == "beta") "openclaw@beta" else "openclaw"
        val channelLabel = if (openclawChannel == "beta") "beta" else "estable"
        
        return """
            set +e
            MARKER_DIR=/root/.openclaw-install
            mkdir -p "${'$'}MARKER_DIR"

            phase_start() { echo "PHASE:${'$'}1:start:${'$'}2"; }
            phase_step()  { echo "PHASE:${'$'}1:step:${'$'}2"; }
            phase_ok()    { touch "${'$'}MARKER_DIR/${'$'}1.done"; echo "PHASE:${'$'}1:ok:${'$'}2"; }
            phase_skip()  { echo "PHASE:${'$'}1:skip:${'$'}2"; }
            phase_err()   { echo "PHASE:${'$'}1:error:${'$'}2"; echo "FALLO:${'$'}1 ${'$'}2"; }
            phase_done()  { [ -f "${'$'}MARKER_DIR/${'$'}1.done" ]; }

            # ────────────────────────────────────────────────────────────────
            # Fase 1: arch — detectar arquitectura
            # ────────────────────────────────────────────────────────────────
            if phase_done arch; then
                phase_skip arch "Arquitectura ya verificada"
            else
                phase_start arch "Detectando arquitectura"
                arch=${'$'}(uname -m)
                case "${'$'}arch" in
                    aarch64|arm64)
                        phase_ok arch "ARM64 confirmado (${'$'}arch)" ;;
                    *)
                        phase_ok arch "Arquitectura: ${'$'}arch (no estándar pero continuando)" ;;
                esac
            fi

            # ────────────────────────────────────────────────────────────────
            # Fase 2: apk_repos — verificar repositorios
            # ────────────────────────────────────────────────────────────────
            if phase_done apk_repos; then
                phase_skip apk_repos "Repositorios ya configurados"
            else
                phase_start apk_repos "Verificando repositorios apk"
                if [ ! -s /etc/apk/repositories ]; then
                    phase_err apk_repos "/etc/apk/repositories vacío o ausente"
                    exit 1
                fi
                phase_ok apk_repos "Repositorios listos"
            fi

            # ────────────────────────────────────────────────────────────────
            # Fase 3: apk_update — refresco de índice (con retry)
            # ────────────────────────────────────────────────────────────────
            if phase_done apk_update; then
                phase_skip apk_update "Índice apk ya actualizado"
            else
                phase_start apk_update "Refrescando índice de paquetes"
                apk_log=/tmp/apk-update.log
                rm -f "${'$'}apk_log"
                update_ok=0
                for attempt in 1 2 3; do
                    if apk update > "${'$'}apk_log" 2>&1; then
                        update_ok=1
                        break
                    fi
                    phase_step apk_update "Reintento ${'$'}attempt/3"
                    sleep 2
                done
                if [ "${'$'}update_ok" -ne 1 ]; then
                    tail -20 "${'$'}apk_log"
                    apk_err=${'$'}(tail -1 "${'$'}apk_log" | tr -d '\n' | cut -c1-200)
                    phase_err apk_update "apk update falló: ${'$'}apk_err"
                    exit 1
                fi
                phase_ok apk_update "Índice apk actualizado"
            fi

            # Helper: instalar un paquete apk individual
            install_pkg() {
                local key="${'$'}1"
                local pkg="${'$'}2"
                local label="${'$'}{3:-${'$'}2}"
                if apk info -e "${'$'}pkg" >/dev/null 2>&1; then
                    phase_step "${'$'}key" "✓ ${'$'}label (ya instalado)"
                    return 0
                fi
                phase_step "${'$'}key" "↓ Instalando ${'$'}label..."
                local log=/tmp/apk-${'$'}{pkg}.log
                if ! apk add --no-progress "${'$'}pkg" > "${'$'}log" 2>&1; then
                    if ! apk add --no-progress --no-cache "${'$'}pkg" > "${'$'}log" 2>&1; then
                        tail -10 "${'$'}log"
                        return 1
                    fi
                fi
                phase_step "${'$'}key" "✓ ${'$'}label instalado"
                return 0
            }

            # ────────────────────────────────────────────────────────────────
            # Fase 4: nodejs — instalar Node.js
            # ────────────────────────────────────────────────────────────────
            if phase_done nodejs && command -v node >/dev/null 2>&1; then
                phase_skip nodejs "Node.js ya instalado (${'$'}(node --version 2>/dev/null))"
            else
                phase_start nodejs "Instalando Node.js"
                if ! install_pkg nodejs nodejs "Node.js"; then
                    phase_err nodejs "apk add nodejs falló"
                    exit 1
                fi
                node_ver=${'$'}(node --version 2>/dev/null || echo "?")
                phase_ok nodejs "Node.js ${'$'}node_ver instalado"
            fi

            # ────────────────────────────────────────────────────────────────
            # Fase 5: npm — instalar npm
            # ────────────────────────────────────────────────────────────────
            if phase_done npm && command -v npm >/dev/null 2>&1; then
                phase_skip npm "npm ya instalado (${'$'}(npm --version 2>/dev/null))"
            else
                phase_start npm "Instalando npm"
                if ! install_pkg npm npm "npm"; then
                    phase_err npm "apk add npm falló"
                    exit 1
                fi
                npm_ver=${'$'}(npm --version 2>/dev/null || echo "?")
                phase_ok npm "npm ${'$'}npm_ver instalado"
            fi

            # ────────────────────────────────────────────────────────────────
            # Fase 6: sys_deps — dependencias mínimas de runtime
            # ────────────────────────────────────────────────────────────────
            if phase_done sys_deps; then
                phase_skip sys_deps "Dependencias del sistema ya instaladas"
            else
                phase_start sys_deps "Instalando dependencias mínimas de runtime"
                dep_failures=""
                for pkg in libstdc++ ca-certificates bash; do
                    if ! install_pkg sys_deps "${'$'}pkg" "${'$'}pkg"; then
                        dep_failures="${'$'}dep_failures ${'$'}pkg"
                    fi
                done
                if [ -n "${'$'}dep_failures" ]; then
                    phase_err sys_deps "Paquetes fallidos:${'$'}dep_failures"
                    exit 1
                fi
                phase_ok sys_deps "Dependencias de runtime instaladas"
            fi

            # ────────────────────────────────────────────────────────────────
            # Helper: reescribe shebangs '#!/usr/bin/env node' → '#!/usr/bin/node'
            # ────────────────────────────────────────────────────────────────
            fix_node_shebang() {
                local f="${'$'}1"
                [ -e "${'$'}f" ] || return 0
                local real
                real=${'$'}(readlink -f "${'$'}f" 2>/dev/null || echo "${'$'}f")
                [ -f "${'$'}real" ] || return 0
                local first
                first=${'$'}(head -n 1 "${'$'}real" 2>/dev/null)
                case "${'$'}first" in
                    "#!/usr/bin/env node"*|"#!/usr/bin/env -S node"*)
                        sed -i '1s|^#!/usr/bin/env .*node.*|#!/usr/bin/node|' "${'$'}real" 2>/dev/null || true
                        ;;
                esac
            }

            # Aplicar fix a npm/npx
            for bin in /usr/bin/npm /usr/bin/npx \
                       /usr/local/bin/npm /usr/local/bin/npx; do
                fix_node_shebang "${'$'}bin"
            done

            # ────────────────────────────────────────────────────────────────
            # Fase 7: pnpm — instalar pnpm (NO FATAL — se puede saltar)
            # ────────────────────────────────────────────────────────────────
            # pnpm es preferible pero no indispensable. Si falla, openclaw
            # se instalará con npm directamente.
            if phase_done pnpm; then
                if command -v pnpm >/dev/null 2>&1; then
                    phase_skip pnpm "pnpm ya instalado (${'$'}(pnpm --version 2>/dev/null))"
                else
                    phase_skip pnpm "pnpm saltado (se usará npm)"
                fi
            else
                phase_start pnpm "Instalando pnpm"
                pnpm_ok=0

                # Intento 1: apk
                pnpm_log=/tmp/apk-pnpm.log
                if apk add --no-progress pnpm > "${'$'}pnpm_log" 2>&1; then
                    phase_step pnpm "pnpm instalado vía apk"
                    pnpm_ok=1
                else
                    phase_step pnpm "apk add pnpm falló — intentando corepack"
                fi

                # Intento 2: corepack
                if [ "${'$'}pnpm_ok" -ne 1 ]; then
                    if command -v corepack >/dev/null 2>&1; then
                        phase_step pnpm "Activando pnpm vía corepack"
                        cd "${'$'}MARKER_DIR" 2>/dev/null || true
                        if corepack enable 2>&1 && corepack prepare pnpm@latest --activate 2>&1; then
                            phase_step pnpm "pnpm activado vía corepack"
                            pnpm_ok=1
                        else
                            phase_step pnpm "corepack falló — intentando npm directo"
                        fi
                    fi
                fi

                # Intento 3: node + npm-cli.js
                if [ "${'$'}pnpm_ok" -ne 1 ]; then
                    NPM_CLI=""
                    for p in /usr/lib/node_modules/npm/bin/npm-cli.js \
                             /usr/local/lib/node_modules/npm/bin/npm-cli.js; do
                        [ -f "${'$'}p" ] && NPM_CLI="${'$'}p" && break
                    done
                    if [ -n "${'$'}NPM_CLI" ]; then
                        phase_step pnpm "Ejecutando node ${'$'}NPM_CLI install -g pnpm"
                        cd "${'$'}MARKER_DIR" 2>/dev/null || true
                        if node "${'$'}NPM_CLI" install -g pnpm 2>&1; then
                            pnpm_ok=1
                        else
                            phase_step pnpm "npm install -g pnpm falló"
                        fi
                    else
                        phase_step pnpm "npm-cli.js no encontrado"
                    fi
                fi

                if [ "${'$'}pnpm_ok" -eq 1 ]; then
                    for bin in /usr/bin/pnpm /usr/bin/pnpx \
                               /usr/local/bin/pnpm /usr/local/bin/pnpx \
                               /root/.local/share/pnpm/pnpm; do
                        fix_node_shebang "${'$'}bin"
                    done
                    if pnpm_cmd=${'$'}(command -v pnpm 2>/dev/null); then
                        fix_node_shebang "${'$'}pnpm_cmd"
                    fi
                    if corepack_cmd=${'$'}(command -v corepack 2>/dev/null); then
                        fix_node_shebang "${'$'}corepack_cmd"
                    fi
                    pnpm_ver=${'$'}(pnpm --version 2>/dev/null || echo "?")
                    phase_ok pnpm "pnpm ${'$'}pnpm_ver instalado"
                else
                    phase_step pnpm "pnpm no disponible"
                    phase_ok pnpm "Saltado"
                fi
            fi

            # ────────────────────────────────────────────────────────────────
            # Fase 8: pnpm_env — configurar PNPM_HOME persistente
            # ────────────────────────────────────────────────────────────────
            if phase_done pnpm_env; then
                phase_skip pnpm_env "PNPM_HOME ya configurado"
            else
                phase_start pnpm_env "Configurando PNPM_HOME"
                mkdir -p /root/.local/share/pnpm
                export PNPM_HOME="/root/.local/share/pnpm"
                export PATH="${'$'}PNPM_HOME:${'$'}PATH"
                if ! grep -q "PNPM_HOME" /root/.bashrc 2>/dev/null; then
                    cat >> /root/.bashrc << 'ENVEOF'
export PNPM_HOME="/root/.local/share/pnpm"
export PATH="${'$'}PNPM_HOME:${'$'}PATH"
ENVEOF
                fi
                if ! grep -q "PNPM_HOME" /root/.profile 2>/dev/null; then
                    cat >> /root/.profile << 'ENVEOF'
export PNPM_HOME="/root/.local/share/pnpm"
export PATH="${'$'}PNPM_HOME:${'$'}PATH"
ENVEOF
                fi
                phase_ok pnpm_env "PNPM_HOME configurado en .bashrc y .profile"
            fi

            # Reexportar PNPM_HOME para fases posteriores
            export PNPM_HOME="/root/.local/share/pnpm"
            export PATH="${'$'}PNPM_HOME:${'$'}PATH"

            # ────────────────────────────────────────────────────────────────
            # Fase 9: versions — verificar versiones
            # ────────────────────────────────────────────────────────────────
            phase_start versions "Verificando versiones instaladas"
            node_v=${'$'}(node --version 2>/dev/null || echo "?")
            npm_v=${'$'}(npm --version 2>/dev/null || echo "?")
            pnpm_v=${'$'}(pnpm --version 2>/dev/null || echo "?")
            phase_step versions "node ${'$'}node_v · npm ${'$'}npm_v · pnpm ${'$'}pnpm_v"
            phase_ok versions "Versiones: node ${'$'}node_v / npm ${'$'}npm_v / pnpm ${'$'}pnpm_v"

            # ────────────────────────────────────────────────────────────────
            # Fase 10: openclaw — instalar OpenClaw (pnpm)
            # ────────────────────────────────────────────────────────────────
            if [ -f /usr/local/lib/node_modules/openclaw/openclaw.mjs ] \
               || [ -f /usr/lib/node_modules/openclaw/openclaw.mjs ] \
               || [ -f /root/.local/share/pnpm/global/5/node_modules/openclaw/openclaw.mjs ]; then
                phase_skip openclaw "OpenClaw ya instalado"
            else
                phase_start openclaw "Instalando OpenClaw ($channelLabel)"
                cd "${'$'}MARKER_DIR" 2>/dev/null || true
                oc_ok=0

                # Intento Único: pnpm
                if command -v pnpm >/dev/null 2>&1; then
                    phase_step openclaw "Instalando con pnpm..."
                    if pnpm_cmd=${'$'}(command -v pnpm 2>/dev/null); then
                        fix_node_shebang "${'$'}pnpm_cmd"
                    fi
                    if pnpm add -g $ocPackage 2>&1; then
                        oc_ok=1
                    else
                        phase_step openclaw "pnpm falló."
                    fi
                else
                    phase_step openclaw "pnpm no encontrado, no se puede instalar."
                fi

                if [ "${'$'}oc_ok" -ne 1 ]; then
                    phase_err openclaw "No se pudo instalar OpenClaw (pnpm falló)"
                    exit 1
                fi

                for bin in /root/.local/share/pnpm/openclaw \
                           /usr/local/bin/openclaw \
                           /usr/bin/openclaw; do
                    fix_node_shebang "${'$'}bin"
                done
                phase_ok openclaw "OpenClaw $channelLabel instalado"
            fi

            # ────────────────────────────────────────────────────────────────
            # Fase 11: onboard — ejecutar openclaw onboard
            # ────────────────────────────────────────────────────────────────
            if phase_done onboard; then
                phase_skip onboard "openclaw onboard ya ejecutado"
            else
                phase_start onboard "Configurando OpenClaw (onboard)"
                if ! echo "" | openclaw onboard 2>&1; then
                    phase_err onboard "openclaw onboard falló"
                    exit 1
                fi
                phase_ok onboard "openclaw onboard completado"
            fi

            # ────────────────────────────────────────────────────────────────
            # Fase 12: verify — verificación final
            # ────────────────────────────────────────────────────────────────
            phase_start verify "Verificación final"
            if ! openclaw --version 2>&1; then
                phase_err verify "openclaw --version falló"
                exit 1
            fi
            phase_ok verify "OpenClaw operativo"

            echo "DONE"
        """.trimIndent()
    }
}
