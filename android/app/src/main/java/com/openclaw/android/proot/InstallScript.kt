package com.openclaw.android.proot

/**
 * InstallScript - shell installer embedded in the Android app.
 *
 * The generated script runs inside the Alpine rootfs through proot. It keeps
 * all runtime assets inside Alpine, installs Node.js manually under
 * /usr/local, skips pnpm entirely, and installs OpenClaw with npm.
 */
object InstallScript {

    private const val NODE_VERSION = "22.22.0"

    private fun apkVersionBranch(alpineVersion: String): String =
        "v" + alpineVersion.substringBeforeLast('.')

    fun generate(
        alpineVersion: String = AlpineDownloader.ALPINE_VERSION,
        openclawChannel: String = "estable"
    ): String {
        val ocPackage = if (openclawChannel == "beta") "openclaw@beta" else "openclaw"
        val channelLabel = if (openclawChannel == "beta") "beta" else "estable"
        val apkBranch = apkVersionBranch(alpineVersion)

        return """
            set +e
            cd /root 2>/dev/null || cd / 2>/dev/null || true

            # Alpine Version: $alpineVersion ($apkBranch)
            NODE_VERSION="$NODE_VERSION"
            ocPackage="$ocPackage"
            channelLabel="$channelLabel"
            apkBranch="$apkBranch"
            MARKER_DIR=/root/.openclaw-install
            NODE_LINK=/usr/local/node
            LOG_FILE=/root/.openclaw/install.log

            mkdir -p /root/tmp /root/.openclaw /usr/local /usr/local/bin /usr/local/lib /usr/local/etc "@@MARKER_DIR"
            touch "@@LOG_FILE" 2>/dev/null || true

            phase_start() { echo "PHASE:@@1:start:@@2"; }
            phase_step()  { echo "PHASE:@@1:step:@@2"; }
            phase_ok()    { touch "@@MARKER_DIR/@@1.done"; echo "PHASE:@@1:ok:@@2"; }
            phase_skip()  { echo "PHASE:@@1:skip:@@2"; }
            phase_err()   { echo "PHASE:@@1:error:@@2"; echo "FALLO:@@1 @@2"; }
            phase_done()  { [ -f "@@MARKER_DIR/@@1.done" ]; }

            run_log() {
                tmp="/root/tmp/openclaw-run-@@@@.log"
                "@@@" > "@@tmp" 2>&1
                code="@@?"
                cat "@@tmp" 2>/dev/null || true
                cat "@@tmp" >> "@@LOG_FILE" 2>/dev/null || true
                rm -f "@@tmp"
                return "@@code"
            }

            # 1. Prepare Alpine directories, DNS, and APK repository helpers.
            configure_dns() {
                mkdir -p /etc /etc/apk /etc/ssl/certs /var/cache/apk /tmp /var/tmp /run
                cat > /etc/resolv.conf << 'DNSEOF'
            nameserver 1.1.1.1
            nameserver 8.8.8.8
            nameserver 9.9.9.9
            options timeout:2 attempts:3 rotate
            DNSEOF
                cat > /etc/hosts << 'HOSTSEOF'
            127.0.0.1 localhost localhost.localdomain
            ::1 localhost ip6-localhost ip6-loopback
            HOSTSEOF
                chmod 1777 /tmp /var/tmp /root/tmp 2>/dev/null || true
            }

            configure_repos() {
                scheme="@@1"
                mirror="@@2"
                mkdir -p /etc/apk
                {
                    echo "@@{scheme}://@@{mirror}/alpine/@@{apkBranch}/main"
                    echo "@@{scheme}://@@{mirror}/alpine/@@{apkBranch}/community"
                } > /etc/apk/repositories
            }

            safe_apk_update() {
                rm -rf /var/cache/apk/* /etc/apk/cache/* 2>/dev/null || true
                apk cache clean >/dev/null 2>&1 || true
                if run_log apk update; then
                    return 0
                fi

                for mirror in dl-cdn.alpinelinux.org uk.alpinelinux.org dl-2.alpinelinux.org; do
                    for scheme in http https; do
                        phase_step apk_update "Reintentando apk update con @@{scheme}://@@{mirror}"
                        configure_repos "@@scheme" "@@mirror"
                        if run_log apk update; then
                            return 0
                        fi
                    done
                done
                return 1
            }

            install_pkg() {
                key="@@1"
                pkg="@@2"
                label="@@{3:-@@2}"
                if apk info -e "@@pkg" >/dev/null 2>&1; then
                    phase_step "@@key" "ok @@label (ya instalado)"
                    return 0
                fi
                phase_step "@@key" "Instalando @@label"
                run_log apk add --no-progress "@@pkg"
                code="@@?"
                if [ "@@code" -ne 0 ]; then
                    run_log apk add --no-progress --no-cache "@@pkg"
                    code="@@?"
                fi
                return "@@code"
            }

            deps_ready() {
                for pkg in bash ca-certificates libstdc++ libgcc curl tar xz; do
                    apk info -e "@@pkg" >/dev/null 2>&1 || return 1
                done
                return 0
            }

            write_cwd_fix() {
                mkdir -p /root/.openclaw
                cat > /root/.openclaw/proot-cwd-fix.cjs << 'JSEOF'
            'use strict';

            const rawCwd = process.cwd.bind(process);
            const rawChdir = process.chdir.bind(process);

            process.cwd = function openclawPatchedCwd() {
              try {
                return rawCwd();
              } catch (error) {
                if (error && (error.code === 'ENOSYS' || error.errno === -38)) {
                  return process.env.PWD || process.env.INIT_CWD || process.env.HOME || '/root';
                }
                throw error;
              }
            };

            process.chdir = function openclawPatchedChdir(dir) {
              rawChdir(dir);
              try {
                process.env.PWD = rawCwd();
              } catch (_) {
                process.env.PWD = dir || process.env.PWD || '/root';
              }
            };
            JSEOF
            }

            write_node_env() {
                mkdir -p /etc/profile.d /usr/local/etc /root/tmp/npm-cache
                cat > /etc/profile.d/openclaw-node.sh << 'ENVEOF'
            export PATH="/usr/local/bin:/usr/local/node/bin:@@{PATH}"
            export PWD="@@{PWD:-/root}"
            export NPM_CONFIG_PREFIX="/usr/local"
            export npm_config_prefix="/usr/local"
            export NPM_CONFIG_GLOBALCONFIG="/usr/local/etc/npmrc"
            export npm_config_cache="/root/tmp/npm-cache"
            export npm_config_fund="false"
            export npm_config_audit="false"
            export SSL_CERT_FILE="/etc/ssl/certs/ca-certificates.crt"
            export OPENCLAW_HOME="/data/home/.openclaw"
            export TMPDIR="/root/tmp"
            if [ -d /data/home/.openclaw/tmp ]; then
                export TMPDIR="/data/home/.openclaw/tmp"
            fi
            if [ -f /root/.openclaw/proot-cwd-fix.cjs ]; then
                case " @@{NODE_OPTIONS:-} " in
                    *" --require /root/.openclaw/proot-cwd-fix.cjs "*) ;;
                    *) export NODE_OPTIONS="@@{NODE_OPTIONS:-} --require /root/.openclaw/proot-cwd-fix.cjs" ;;
                esac
            fi
            ENVEOF
                cat > /usr/local/etc/npmrc << 'NPMEOF'
            prefix=/usr/local
            cache=/root/tmp/npm-cache
            fund=false
            audit=false
            update-notifier=false
            registry=https://registry.npmjs.org/
            NPMEOF
                touch /root/.profile /root/.bashrc
                if ! grep -q "openclaw-node.sh" /root/.profile 2>/dev/null; then
                    echo ". /etc/profile.d/openclaw-node.sh" >> /root/.profile
                fi
                if ! grep -q "openclaw-node.sh" /root/.bashrc 2>/dev/null; then
                    echo ". /etc/profile.d/openclaw-node.sh" >> /root/.bashrc
                fi
                . /etc/profile.d/openclaw-node.sh
            }

            fix_node_shebang() {
                f="@@1"
                [ -e "@@f" ] || return 0
                real="@@(readlink -f "@@f" 2>/dev/null || echo "@@f")"
                [ -f "@@real" ] || return 0
                first="@@(head -n 1 "@@real" 2>/dev/null)"
                case "@@first" in
                    "#!/usr/bin/env node"*|"#!/usr/bin/env -S node"*)
                        sed -i '1s|^#!.*env .*node.*|#!/usr/local/bin/node|' "@@real" 2>/dev/null || true
                        ;;
                esac
            }

            write_node_wrappers() {
                rm -f /usr/local/bin/node /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack
                ln -sfn /usr/local/node/bin/node /usr/local/bin/node || return 1

                if [ -f /usr/local/node/lib/node_modules/npm/bin/npm-cli.js ]; then
                    cat > /usr/local/bin/npm << 'NPMWRAP'
            #!/bin/sh
            export PWD="@@{PWD:-/root}"
            export NPM_CONFIG_PREFIX="@@{NPM_CONFIG_PREFIX:-/usr/local}"
            export npm_config_prefix="@@{npm_config_prefix:-/usr/local}"
            export npm_config_cache="@@{npm_config_cache:-/root/tmp/npm-cache}"
            if [ -f /root/.openclaw/proot-cwd-fix.cjs ]; then
                case " @@{NODE_OPTIONS:-} " in
                    *" --require /root/.openclaw/proot-cwd-fix.cjs "*) ;;
                    *) export NODE_OPTIONS="@@{NODE_OPTIONS:-} --require /root/.openclaw/proot-cwd-fix.cjs" ;;
                esac
            fi
            exec /usr/local/bin/node /usr/local/node/lib/node_modules/npm/bin/npm-cli.js "@@@"
            NPMWRAP
                    chmod +x /usr/local/bin/npm
                fi

                if [ -f /usr/local/node/lib/node_modules/npm/bin/npx-cli.js ]; then
                    cat > /usr/local/bin/npx << 'NPXWRAP'
            #!/bin/sh
            export PWD="@@{PWD:-/root}"
            if [ -f /root/.openclaw/proot-cwd-fix.cjs ]; then
                case " @@{NODE_OPTIONS:-} " in
                    *" --require /root/.openclaw/proot-cwd-fix.cjs "*) ;;
                    *) export NODE_OPTIONS="@@{NODE_OPTIONS:-} --require /root/.openclaw/proot-cwd-fix.cjs" ;;
                esac
            fi
            exec /usr/local/bin/node /usr/local/node/lib/node_modules/npm/bin/npx-cli.js "@@@"
            NPXWRAP
                    chmod +x /usr/local/bin/npx
                fi

                if [ -f /usr/local/node/bin/corepack ]; then
                    ln -sfn /usr/local/node/bin/corepack /usr/local/bin/corepack
                    fix_node_shebang /usr/local/bin/corepack
                fi
                return 0
            }

            test_node_runtime() {
                export PWD="@@{PWD:-/root}"
                export NODE_OPTIONS="--require /root/.openclaw/proot-cwd-fix.cjs"
                /usr/local/bin/node --version >/dev/null 2>&1 || return 1
                /usr/local/bin/node -e "process.stdout.write(process.cwd())" >/dev/null 2>&1 || return 1
                /usr/local/bin/npm --version >/dev/null 2>&1 || return 1
                return 0
            }

            manual_node_ready() {
                [ -x /usr/local/bin/node ] || return 1
                [ -x /usr/local/bin/npm ] || return 1
                case "@@(readlink -f /usr/local/bin/node 2>/dev/null)" in
                    /usr/local/node/*|/usr/local/node-v*) ;;
                    *) return 1 ;;
                esac
                test_node_runtime
            }

            download_file() {
                url="@@1"
                out="@@2"
                rm -f "@@out"
                if command -v curl >/dev/null 2>&1; then
                    run_log curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 --max-time 600 "@@url" -o "@@out" && return 0
                fi
                if command -v wget >/dev/null 2>&1; then
                    run_log wget -O "@@out" "@@url" && return 0
                fi
                return 1
            }

            verify_sha() {
                sums="@@1"
                file="@@2"
                name="@@3"
                expected="@@(grep -F " @@name" "@@sums" 2>/dev/null | awk '{print @@1}' | head -n 1)"
                actual="@@(sha256sum "@@file" 2>/dev/null | awk '{print @@1}')"
                [ -n "@@expected" ] && [ "@@expected" = "@@actual" ]
            }

            install_node_candidate() {
                dist="@@1"
                base="@@2"
                prefix="@@3"
                label="@@4"
                tarball="/root/tmp/@@{dist}.tar.xz"
                sums="/root/tmp/SHASUMS256-@@{dist}.txt"

                phase_step nodejs "Descargando Node.js @@NODE_VERSION ARM64 (@@label)"
                download_file "@@base/SHASUMS256.txt" "@@sums" || return 1
                download_file "@@base/@@{dist}.tar.xz" "@@tarball" || return 1
                verify_sha "@@sums" "@@tarball" "@@{dist}.tar.xz" || return 1

                rm -rf "@@prefix.tmp" "@@prefix"
                mkdir -p "@@prefix.tmp" || return 1
                run_log tar -xJf "@@tarball" -C "@@prefix.tmp" --strip-components=1 || return 1

                mv "@@prefix.tmp" "@@prefix" || return 1
                ln -sfn "@@prefix" "@@NODE_LINK" || return 1
                write_node_wrappers || return 1
                test_node_runtime
            }

            write_openclaw_wrapper() {
                if [ -f /usr/local/lib/node_modules/openclaw/openclaw.mjs ]; then
                    rm -f /usr/local/bin/openclaw
                    cat > /usr/local/bin/openclaw << 'OCWRAP'
            #!/bin/sh
            export PWD="@@{PWD:-/root}"
            export OPENCLAW_HOME="@@{OPENCLAW_HOME:-/data/home/.openclaw}"
            export TMPDIR="@@{TMPDIR:-/root/tmp}"
            if [ -f /root/.openclaw/proot-cwd-fix.cjs ]; then
                case " @@{NODE_OPTIONS:-} " in
                    *" --require /root/.openclaw/proot-cwd-fix.cjs "*) ;;
                    *) export NODE_OPTIONS="@@{NODE_OPTIONS:-} --require /root/.openclaw/proot-cwd-fix.cjs" ;;
                esac
            fi
            exec /usr/local/bin/node /usr/local/lib/node_modules/openclaw/openclaw.mjs "@@@"
            OCWRAP
                    chmod +x /usr/local/bin/openclaw
                fi
            }

            # Phase 1: architecture.
            if phase_done arch; then
                phase_skip arch "Arquitectura ya verificada"
            else
                phase_start arch "Detectando arquitectura"
                arch="@@(uname -m 2>/dev/null || echo unknown)"
                case "@@arch" in
                    aarch64|arm64) phase_ok arch "ARM64 confirmado (@@arch)" ;;
                    *) phase_err arch "Arquitectura no soportada: @@arch"; exit 1 ;;
                esac
            fi

            # Phase 2: DNS and APK repositories.
            if phase_done apk_repos; then
                phase_skip apk_repos "Repositorios ya configurados"
            else
                phase_start apk_repos "Configurando DNS y repositorios apk"
                configure_dns
                configure_repos http dl-cdn.alpinelinux.org
                [ -s /etc/apk/repositories ] || { phase_err apk_repos "/etc/apk/repositories vacio"; exit 1; }
                phase_ok apk_repos "Repositorios apk listos"
            fi

            # Phase 3: safe APK index refresh.
            if phase_done apk_update; then
                phase_skip apk_update "Indice apk ya actualizado"
            else
                phase_start apk_update "Limpiando cache APK y refrescando indice"
                if ! safe_apk_update; then
                    phase_err apk_update "apk update fallo"
                    exit 1
                fi
                phase_ok apk_update "Indice apk actualizado"
            fi

            # Phase 4: runtime dependencies.
            if phase_done sys_deps && deps_ready; then
                phase_skip sys_deps "Dependencias del sistema ya instaladas"
            else
                phase_start sys_deps "Instalando dependencias minimas de runtime"
                dep_failures=""
                for pkg in bash ca-certificates libstdc++ libgcc curl tar xz; do
                    install_pkg sys_deps "@@pkg" "@@pkg" || dep_failures="@@dep_failures @@pkg"
                done
                if [ -n "@@dep_failures" ]; then
                    phase_err sys_deps "Paquetes fallidos:@@dep_failures"
                    exit 1
                fi
                apk add --no-progress gcompat >/dev/null 2>&1 || apk add --no-progress libc6-compat >/dev/null 2>&1 || true
                update-ca-certificates >/dev/null 2>&1 || true
                configure_repos https dl-cdn.alpinelinux.org
                safe_apk_update || { configure_repos http dl-cdn.alpinelinux.org; safe_apk_update || exit 1; }
                phase_ok sys_deps "Dependencias de runtime instaladas"
            fi

            write_cwd_fix
            write_node_env

            # Phase 5: manual Node.js from nodejs.org under /usr/local.
            if phase_done nodejs && manual_node_ready; then
                phase_skip nodejs "Node.js manual ya instalado (@@(/usr/local/bin/node --version 2>/dev/null))"
            else
                phase_start nodejs "Instalando Node.js manual dentro de Alpine/proot"
                official_dist="node-v@@{NODE_VERSION}-linux-arm64"
                official_base="https://nodejs.org/dist/v@@{NODE_VERSION}"
                official_prefix="/usr/local/@@official_dist"
                if ! install_node_candidate "@@official_dist" "@@official_base" "@@official_prefix" "nodejs.org"; then
                    phase_step nodejs "Node oficial no arranco en Alpine/proot; intentando build musl compatible"
                    musl_dist="node-v@@{NODE_VERSION}-linux-arm64-musl"
                    musl_base="https://unofficial-builds.nodejs.org/download/release/v@@{NODE_VERSION}"
                    musl_prefix="/usr/local/@@musl_dist"
                    install_node_candidate "@@musl_dist" "@@musl_base" "@@musl_prefix" "musl fallback" || {
                        phase_err nodejs "No se pudo instalar Node.js manual"
                        exit 1
                    }
                fi
                node_ver="@@(/usr/local/bin/node --version 2>/dev/null || echo "?")"
                phase_ok nodejs "Node.js @@node_ver instalado en /usr/local"
            fi

            # Phase 6: npm bundled with manual Node.js.
            if phase_done npm && /usr/local/bin/npm --version >/dev/null 2>&1; then
                phase_skip npm "npm ya instalado (@@(/usr/local/bin/npm --version 2>/dev/null))"
            else
                phase_start npm "Verificando npm incluido con Node.js manual"
                write_node_wrappers || { phase_err npm "No se pudieron crear wrappers npm"; exit 1; }
                npm_ver="@@(/usr/local/bin/npm --version 2>/dev/null || echo "?")"
                [ "@@npm_ver" != "?" ] || { phase_err npm "npm no responde"; exit 1; }
                phase_ok npm "npm @@npm_ver instalado"
            fi

            # Phase 7: pnpm is deliberately skipped in Android/proot.
            apk del pnpm >/dev/null 2>&1 || true
            rm -f /usr/local/bin/pnpm /usr/local/bin/pnpx /root/.local/share/pnpm/pnpm 2>/dev/null || true
            if phase_done pnpm; then
                phase_skip pnpm "pnpm ya fue omitido"
            else
                phase_start pnpm "Saltando pnpm"
                phase_step pnpm "pnpm se omite para evitar ENOSYS: uv_cwd en proot"
                phase_ok pnpm "Saltado; se usara npm"
            fi

            # Phase 8: keep old marker key but configure npm instead of PNPM_HOME.
            if phase_done pnpm_env; then
                phase_skip pnpm_env "Entorno npm ya configurado"
            else
                phase_start pnpm_env "Configurando entorno npm"
                write_node_env
                phase_ok pnpm_env "Entorno npm configurado"
            fi

            # Phase 9: versions.
            phase_start versions "Verificando versiones instaladas"
            node_v="@@(/usr/local/bin/node --version 2>/dev/null || echo "?")"
            npm_v="@@(/usr/local/bin/npm --version 2>/dev/null || echo "?")"
            phase_step versions "node @@node_v / npm @@npm_v / pnpm omitido"
            [ "@@node_v" != "?" ] && [ "@@npm_v" != "?" ] || { phase_err versions "Versiones invalidas"; exit 1; }
            phase_ok versions "Versiones: node @@node_v / npm @@npm_v / pnpm omitido"

            # Phase 10: OpenClaw install with npm only.
            if [ -f /usr/local/lib/node_modules/openclaw/openclaw.mjs ] \
               || [ -f /usr/lib/node_modules/openclaw/openclaw.mjs ]; then
                write_openclaw_wrapper
                phase_skip openclaw "OpenClaw ya instalado"
            else
                phase_start openclaw "Instalando OpenClaw (@@channelLabel) con npm"
                cd /root 2>/dev/null || cd /
                export PWD="/root"
                run_log /usr/local/bin/npm install -g @@ocPackage --no-audit --no-fund
                code="@@?"
                if [ "@@code" -ne 0 ]; then
                    phase_step openclaw "npm fallo; limpiando cache y reintentando"
                    /usr/local/bin/npm cache clean --force >/dev/null 2>&1 || true
                    run_log /usr/local/bin/npm install -g @@ocPackage --no-audit --no-fund --prefer-online
                    code="@@?"
                fi
                if [ "@@code" -ne 0 ]; then
                    phase_err openclaw "No se pudo instalar OpenClaw con npm"
                    exit 1
                fi
                write_openclaw_wrapper
                /usr/local/bin/openclaw --version >/dev/null 2>&1 || { phase_err openclaw "openclaw --version fallo tras npm"; exit 1; }
                phase_ok openclaw "OpenClaw @@channelLabel instalado con npm"
            fi

            # Phase 11: onboard must run in the interactive terminal, not in this non-PTY installer.
            if phase_done onboard; then
                phase_skip onboard "openclaw onboard ya preparado"
            else
                phase_start onboard "Preparando onboard interactivo"
                phase_step onboard "openclaw onboard se abrira en el terminal interactivo de la app"
                phase_ok onboard "Onboard listo para terminal"
            fi

            # Phase 12: final verification and cleanup.
            phase_start verify "Verificacion final"
            /usr/local/bin/npm cache clean --force >/dev/null 2>&1 || true
            rm -rf /root/tmp/node-v*.tar.xz /root/tmp/SHASUMS256-* /root/tmp/npm-cache /tmp/npm-* /var/cache/apk/* /etc/apk/cache/* 2>/dev/null || true
            mkdir -p /root/tmp /root/.openclaw/tmp
            chmod 1777 /root/tmp /tmp /var/tmp 2>/dev/null || true
            apk cache clean >/dev/null 2>&1 || true
            if ! /usr/local/bin/openclaw --version 2>&1; then
                phase_err verify "openclaw --version fallo"
                exit 1
            fi
            phase_ok verify "OpenClaw operativo"

            echo "DONE"
        """.trimIndent().replace("@@", "$")
    }
}
