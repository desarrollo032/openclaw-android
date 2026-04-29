#!/bin/sh
# =============================================================================
# post-setup.sh — OpenClaw Android Offline Post-Setup
# =============================================================================
# Runs ONCE after payload extraction to configure the runtime environment.
#
# Design constraints:
#   - NO internet access required
#   - NO Termux dependency at runtime
#   - Idempotent: safe to run multiple times
#   - Works in Android app sandbox: /data/user/0/<pkg>/files/
#
# Called by: PayloadManager.runPostSetup() via CommandRunner
#            OR interactively from a terminal session
#
# Required env vars (set by EnvironmentBuilder or auto-detected below):
#   APP_FILES_DIR  — context.filesDir
#   APP_PACKAGE    — context.packageName
#   PREFIX         — $APP_FILES_DIR/usr
#   HOME           — $APP_FILES_DIR/home
#   TMPDIR         — $APP_FILES_DIR/tmp
#   PAYLOAD_DIR    — $APP_FILES_DIR/payload (extracted payload location)
# =============================================================================

set -eu

# ── Auto-detect APP_FILES_DIR if not set ──────────────────────────────────────
# When invoked interactively from a terminal session, HOME is set to
# filesDir/home by EnvironmentBuilder. Derive APP_FILES_DIR from it.
if [ -z "${APP_FILES_DIR:-}" ]; then
    if [ -n "${HOME:-}" ] && [ "${HOME}" != "${HOME%/home}" ]; then
        # HOME ends with /home — parent is filesDir
        APP_FILES_DIR="${HOME%/home}"
    elif [ -n "${PREFIX:-}" ] && [ "${PREFIX}" != "${PREFIX%/usr}" ]; then
        # PREFIX ends with /usr — parent is filesDir
        APP_FILES_DIR="${PREFIX%/usr}"
    fi
fi

# ── Auto-set derived vars if missing ─────────────────────────────────────────
if [ -n "${APP_FILES_DIR:-}" ]; then
    : "${PREFIX:=${APP_FILES_DIR}/usr}"
    : "${HOME:=${APP_FILES_DIR}/home}"
    : "${TMPDIR:=${APP_FILES_DIR}/tmp}"
    : "${PAYLOAD_DIR:=${APP_FILES_DIR}/payload}"
    : "${APP_PACKAGE:=com.openclaw.android}"
fi

# ── Logging ───────────────────────────────────────────────────────────────────
LOG_DIR=""
LOG_FILE=""

_init_log() {
    LOG_DIR="${HOME}/.openclaw-android/logs"
    mkdir -p "$LOG_DIR" 2>/dev/null || true
    LOG_FILE="${LOG_DIR}/post-setup-$(date '+%Y%m%d-%H%M%S').log"
    touch "$LOG_FILE" 2>/dev/null || LOG_FILE="/dev/null"
}

log() {
    local ts
    ts="$(date '+%H:%M:%S' 2>/dev/null || echo '??:??:??')"
    local msg="[${ts}] $*"
    echo "$msg"
    echo "$msg" >> "$LOG_FILE" 2>/dev/null || true
}

log_ok()   { log "[OK]   $*"; }
log_err()  { log "[ERR]  $*" >&2; }
log_warn() { log "[WARN] $*"; }
log_step() { log ""; log "=== $* ==="; }

# ── Validate required environment ─────────────────────────────────────────────
validate_env() {
    log_step "Validating environment"

    : "${APP_FILES_DIR:?APP_FILES_DIR not set — run via PayloadManager}"
    : "${APP_PACKAGE:?APP_PACKAGE not set}"
    : "${PREFIX:?PREFIX not set}"
    : "${HOME:?HOME not set}"

    TMPDIR="${TMPDIR:-${APP_FILES_DIR}/tmp}"
    PAYLOAD_DIR="${PAYLOAD_DIR:-${APP_FILES_DIR}/payload}"

    log "  APP_FILES_DIR = $APP_FILES_DIR"
    log "  APP_PACKAGE   = $APP_PACKAGE"
    log "  PREFIX        = $PREFIX"
    log "  HOME          = $HOME"
    log "  PAYLOAD_DIR   = $PAYLOAD_DIR"

    # Verify payload directory exists
    if [ ! -d "$PAYLOAD_DIR" ]; then
        log_err "Payload directory not found: $PAYLOAD_DIR"
        log_err "Ensure PayloadManager.extractPayload() ran successfully first."
        exit 1
    fi

    # Create required directories
    mkdir -p \
        "$PREFIX/bin" \
        "$PREFIX/lib" \
        "$PREFIX/etc/tls/certs" \
        "$PREFIX/glibc/lib" \
        "$PREFIX/glibc/etc" \
        "$HOME/.openclaw-android/bin" \
        "$HOME/.openclaw-android/patches" \
        "$HOME/.openclaw-android/logs" \
        "$HOME/.openclaw-android/node/bin" \
        "$HOME/.openclaw-android/node/lib" \
        "$TMPDIR"

    log_ok "Environment validated"
}

# ── Step 1: Extract glibc ─────────────────────────────────────────────────────
setup_glibc() {
    log_step "Step 1/5: Setting up glibc"

    local GLIBC_ARCHIVE="${PAYLOAD_DIR}/glibc-aarch64.tar.xz"
    local GLIBC_LDSO="${PREFIX}/glibc/lib/ld-linux-aarch64.so.1"
    local MARKER="${PREFIX}/glibc/.glibc-extracted"

    if [ -f "$MARKER" ] && [ -x "$GLIBC_LDSO" ]; then
        log_ok "glibc already extracted (marker present)"
        return 0
    fi

    if [ ! -f "$GLIBC_ARCHIVE" ]; then
        log_err "glibc archive not found: $GLIBC_ARCHIVE"
        log_err "Re-run build-payload.sh and re-bundle the APK."
        exit 1
    fi

    # Verify archive integrity
    log "  Verifying glibc archive..."
    if ! tar -tJf "$GLIBC_ARCHIVE" >/dev/null 2>&1; then
        log_err "glibc archive is corrupt: $GLIBC_ARCHIVE"
        exit 1
    fi

    # Verify linker is inside archive
    if ! tar -tJf "$GLIBC_ARCHIVE" 2>/dev/null | grep -q "ld-linux-aarch64"; then
        log_err "ld-linux-aarch64.so.1 not found in glibc archive"
        exit 1
    fi

    log "  Extracting glibc..."
    # Archive structure: glibc/lib/*, glibc/etc/*
    # Extract to PREFIX so glibc/ lands at PREFIX/glibc/
    tar -xJf "$GLIBC_ARCHIVE" -C "$PREFIX" 2>/dev/null

    if [ ! -f "$GLIBC_LDSO" ]; then
        log_err "Extraction failed — ld-linux-aarch64.so.1 not found at $GLIBC_LDSO"
        exit 1
    fi

    chmod +x "$GLIBC_LDSO"
    touch "$MARKER"
    log_ok "glibc extracted: $GLIBC_LDSO"
}

# ── Step 2: Install CA certificates ──────────────────────────────────────────
setup_certs() {
    log_step "Step 2/5: Setting up CA certificates"

    local CERT_BUNDLE="${PREFIX}/etc/tls/cert.pem"
    local CERT_DIR="${PREFIX}/etc/tls/certs"
    local MARKER="${PREFIX}/etc/tls/.certs-installed"

    if [ -f "$MARKER" ] && [ -s "$CERT_BUNDLE" ]; then
        log_ok "CA certificates already installed"
        export SSL_CERT_FILE="$CERT_BUNDLE"
        export CURL_CA_BUNDLE="$CERT_BUNDLE"
        export GIT_SSL_CAINFO="$CERT_BUNDLE"
        return 0
    fi

    mkdir -p "$CERT_DIR"

    # Source 1: Bundled cert.pem from payload
    local PAYLOAD_CERT="${PAYLOAD_DIR}/certs/cert.pem"
    if [ -f "$PAYLOAD_CERT" ] && [ -s "$PAYLOAD_CERT" ]; then
        cp "$PAYLOAD_CERT" "$CERT_BUNDLE"
        log_ok "CA certs installed from payload bundle"
    fi

    # Source 2: Android system certs (always available, no internet needed)
    if [ ! -s "$CERT_BUNDLE" ]; then
        log "  Payload cert bundle empty — using Android system certs..."
        local ANDROID_CERTS="/system/etc/security/cacerts"
        if [ -d "$ANDROID_CERTS" ]; then
            # Android certs are DER-encoded .0 files — concatenate all
            local count=0
            for cert_file in "$ANDROID_CERTS"/*.0; do
                [ -f "$cert_file" ] || continue
                # Convert DER to PEM if needed (check for PEM header)
                if head -c 27 "$cert_file" 2>/dev/null | grep -q "BEGIN CERTIFICATE"; then
                    cat "$cert_file" >> "$CERT_BUNDLE"
                else
                    # DER format — openssl may not be available, skip
                    # Android .0 files are actually PEM format despite the extension
                    cat "$cert_file" >> "$CERT_BUNDLE" 2>/dev/null || true
                fi
                count=$((count + 1))
            done
            if [ "$count" -gt 0 ]; then
                log_ok "CA certs installed from Android system: $count files"
            fi
        fi
    fi

    # Source 3: User certs directory (if payload included individual certs)
    if [ -d "${PAYLOAD_DIR}/certs" ]; then
        for pem in "${PAYLOAD_DIR}/certs"/*.pem; do
            [ -f "$pem" ] || continue
            [ "$(basename "$pem")" = "cert.pem" ] && continue
            cat "$pem" >> "$CERT_BUNDLE" 2>/dev/null || true
        done
    fi

    if [ -s "$CERT_BUNDLE" ]; then
        local cert_count
        cert_count=$(grep -c "BEGIN CERTIFICATE" "$CERT_BUNDLE" 2>/dev/null || echo "unknown")
        touch "$MARKER"
        log_ok "CA certificate bundle ready: $cert_count certificates"
    else
        log_warn "No CA certificates available — HTTPS may fail"
        # Create empty bundle so env vars point to a valid file
        touch "$CERT_BUNDLE"
    fi

    export SSL_CERT_FILE="$CERT_BUNDLE"
    export CURL_CA_BUNDLE="$CERT_BUNDLE"
    export GIT_SSL_CAINFO="$CERT_BUNDLE"
}

# ── Step 3: Install Node.js ───────────────────────────────────────────────────
setup_nodejs() {
    log_step "Step 3/5: Setting up Node.js"

    local OCA_DIR="${HOME}/.openclaw-android"
    local NODE_DIR="${OCA_DIR}/node"
    local BIN_DIR="${OCA_DIR}/bin"
    local NODE_REAL="${NODE_DIR}/bin/node.real"
    local GLIBC_LDSO="${PREFIX}/glibc/lib/ld-linux-aarch64.so.1"
    local GLIBC_LIB="${PREFIX}/glibc/lib"
    local MARKER="${NODE_DIR}/.node-installed"

    if [ -f "$MARKER" ] && [ -x "$NODE_REAL" ] && [ -x "$BIN_DIR/node" ]; then
        log_ok "Node.js already installed"
        return 0
    fi

    # Copy node.real from payload
    local PAYLOAD_NODE="${PAYLOAD_DIR}/lib/node/bin/node.real"
    if [ ! -f "$PAYLOAD_NODE" ]; then
        log_err "node.real not found in payload: $PAYLOAD_NODE"
        exit 1
    fi

    mkdir -p "${NODE_DIR}/bin" "${NODE_DIR}/lib"
    cp "$PAYLOAD_NODE" "$NODE_REAL"
    chmod +x "$NODE_REAL"

    # Verify node.real is a valid ELF binary
    local elf_magic
    elf_magic=$(head -c 4 "$NODE_REAL" 2>/dev/null | od -An -tx1 | tr -d ' \n' 2>/dev/null || echo "")
    if [ "$elf_magic" != "7f454c46" ]; then
        log_err "node.real is not a valid ELF binary"
        exit 1
    fi

    # Copy node_modules (npm, npx)
    local PAYLOAD_MODULES="${PAYLOAD_DIR}/lib/node/lib/node_modules"
    if [ -d "$PAYLOAD_MODULES" ]; then
        cp -a "$PAYLOAD_MODULES" "${NODE_DIR}/lib/"
        log_ok "node_modules installed"
    fi

    # Verify glibc linker is ready
    if [ ! -x "$GLIBC_LDSO" ]; then
        log_err "glibc linker not found: $GLIBC_LDSO"
        log_err "Run setup_glibc() first"
        exit 1
    fi

    # Create glibc-wrapped node wrapper in BIN_DIR
    # This is the canonical node entry point — never call node.real directly
    mkdir -p "$BIN_DIR"
    cat > "${BIN_DIR}/node" << WRAPPER
#!/system/bin/sh
# OpenClaw glibc-wrapped Node.js launcher
# DO NOT EDIT — regenerated by post-setup.sh

# Unset LD_PRELOAD to prevent bionic libtermux-exec.so from loading
# into the glibc process (causes "Could not find a PHDR" crash)
[ -n "\$LD_PRELOAD" ] && export _OA_ORIG_LD_PRELOAD="\$LD_PRELOAD"
unset LD_PRELOAD

# Point process.execPath to this wrapper (not ld.so)
export _OA_WRAPPER_PATH="${BIN_DIR}/node"

# Load glibc-compat.js shim via NODE_OPTIONS
_OA_COMPAT="${OCA_DIR}/patches/glibc-compat.js"
if [ -f "\$_OA_COMPAT" ]; then
    case "\${NODE_OPTIONS:-}" in
        *"\$_OA_COMPAT"*) ;;
        *) export NODE_OPTIONS="\${NODE_OPTIONS:+\$NODE_OPTIONS }-r \$_OA_COMPAT" ;;
    esac
fi

# Launch node.real via glibc dynamic linker
exec "${GLIBC_LDSO}" --library-path "${GLIBC_LIB}" "${NODE_REAL}" "\$@"
WRAPPER
    chmod +x "${BIN_DIR}/node"

    # Create npm wrapper
    local NPM_CLI="${NODE_DIR}/lib/node_modules/npm/bin/npm-cli.js"
    if [ -f "$NPM_CLI" ]; then
        cat > "${BIN_DIR}/npm" << NPMWRAP
#!/system/bin/sh
exec "${BIN_DIR}/node" "${NPM_CLI}" "\$@"
NPMWRAP
        chmod +x "${BIN_DIR}/npm"
        log_ok "npm wrapper created"
    fi

    # Create npx wrapper
    local NPX_CLI="${NODE_DIR}/lib/node_modules/npm/bin/npx-cli.js"
    if [ -f "$NPX_CLI" ]; then
        cat > "${BIN_DIR}/npx" << NPXWRAP
#!/system/bin/sh
exec "${BIN_DIR}/node" "${NPX_CLI}" "\$@"
NPXWRAP
        chmod +x "${BIN_DIR}/npx"
        log_ok "npx wrapper created"
    fi

    # Verify node works
    local node_ver
    node_ver=$("${BIN_DIR}/node" --version 2>/dev/null) || {
        log_err "Node.js verification failed — glibc launch failed"
        log_err "  node.real: $NODE_REAL"
        log_err "  glibc:     $GLIBC_LDSO"
        exit 1
    }

    touch "$MARKER"
    log_ok "Node.js $node_ver ready (glibc-wrapped)"
}

# ── Step 4: Install OpenClaw ──────────────────────────────────────────────────
setup_openclaw() {
    log_step "Step 4/5: Setting up OpenClaw"

    local OCA_DIR="${HOME}/.openclaw-android"
    local BIN_DIR="${OCA_DIR}/bin"
    local MARKER="${OCA_DIR}/installed.json"

    # Destination for openclaw package
    local OC_DEST="${PREFIX}/lib/node_modules/openclaw"
    local OC_BIN="${PREFIX}/bin/openclaw"

    if [ -f "$MARKER" ] && [ -f "${OC_DEST}/openclaw.mjs" ]; then
        log_ok "OpenClaw already installed"
        return 0
    fi

    # Copy from payload if bundled
    local PAYLOAD_OC="${PAYLOAD_DIR}/lib/openclaw"
    if [ -d "$PAYLOAD_OC" ] && [ -f "${PAYLOAD_OC}/openclaw.mjs" ]; then
        mkdir -p "${PREFIX}/lib/node_modules"
        cp -a "$PAYLOAD_OC" "${PREFIX}/lib/node_modules/"
        log_ok "OpenClaw installed from payload"
    else
        log_warn "OpenClaw not in payload — will need npm install at runtime"
        # Create a placeholder marker so the app knows to install it
        mkdir -p "$OCA_DIR"
        echo '{"status":"pending","source":"npm"}' > "${OCA_DIR}/openclaw-pending.json"
        return 0
    fi

    # Create openclaw launcher wrapper
    local OC_MJS="${OC_DEST}/openclaw.mjs"
    if [ -f "$OC_MJS" ]; then
        mkdir -p "${PREFIX}/bin"
        cat > "$OC_BIN" << OCWRAP
#!/system/bin/sh
exec "${BIN_DIR}/node" "${OC_MJS}" "\$@"
OCWRAP
        chmod +x "$OC_BIN"
        log_ok "openclaw wrapper created: $OC_BIN"
    fi

    # Write installed.json marker
    mkdir -p "$OCA_DIR"
    cat > "$MARKER" << MARKER_EOF
{
  "version": "payload",
  "installedAt": "$(date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || echo 'unknown')",
  "source": "payload",
  "prefix": "${PREFIX}",
  "home": "${HOME}"
}
MARKER_EOF

    log_ok "OpenClaw installation marker written"
}

# ── Step 5: Configure environment ────────────────────────────────────────────
setup_environment() {
    log_step "Step 5/5: Configuring environment"

    local OCA_DIR="${HOME}/.openclaw-android"

    # Copy glibc-compat.js from payload
    local COMPAT_SRC="${PAYLOAD_DIR}/patches/glibc-compat.js"
    local COMPAT_DEST="${OCA_DIR}/patches/glibc-compat.js"
    if [ -f "$COMPAT_SRC" ]; then
        mkdir -p "${OCA_DIR}/patches"
        cp "$COMPAT_SRC" "$COMPAT_DEST"
        log_ok "glibc-compat.js installed"
    fi

    # Configure DNS — resolv.conf for both bionic and glibc resolvers
    local DNS_CONTENT="nameserver 8.8.8.8
nameserver 1.1.1.1
nameserver 8.8.4.4"

    for resolv_path in \
        "${PREFIX}/etc/resolv.conf" \
        "${PREFIX}/glibc/etc/resolv.conf"; do
        local dir
        dir="$(dirname "$resolv_path")"
        mkdir -p "$dir"
        if [ ! -s "$resolv_path" ] || ! grep -q "nameserver" "$resolv_path" 2>/dev/null; then
            printf '%s\n' "$DNS_CONTENT" > "$resolv_path"
            log_ok "resolv.conf written: $resolv_path"
        fi
    done

    # Configure nsswitch.conf for glibc DNS resolution
    local NSSWITCH="${PREFIX}/glibc/etc/nsswitch.conf"
    if [ ! -f "$NSSWITCH" ]; then
        cat > "$NSSWITCH" << 'NSSWITCH_EOF'
passwd:     files
group:      files
hosts:      files dns
networks:   files
protocols:  files
services:   files
NSSWITCH_EOF
        log_ok "nsswitch.conf created"
    fi

    # Configure /etc/hosts for glibc
    local HOSTS="${PREFIX}/glibc/etc/hosts"
    if [ ! -f "$HOSTS" ]; then
        cat > "$HOSTS" << 'HOSTS_EOF'
127.0.0.1 localhost localhost.localdomain
::1       localhost ip6-localhost ip6-loopback
HOSTS_EOF
        log_ok "glibc hosts file created"
    fi

    # Set correct permissions on all executables
    log "  Setting permissions..."
    local GLIBC_LDSO="${PREFIX}/glibc/lib/ld-linux-aarch64.so.1"
    [ -f "$GLIBC_LDSO" ] && chmod +x "$GLIBC_LDSO"

    for bin_dir in "${OCA_DIR}/bin" "${PREFIX}/bin"; do
        [ -d "$bin_dir" ] || continue
        for f in "$bin_dir"/*; do
            [ -f "$f" ] || continue
            chmod +x "$f" 2>/dev/null || true
        done
    done

    # Make all .so files in glibc/lib readable
    if [ -d "${PREFIX}/glibc/lib" ]; then
        chmod 644 "${PREFIX}/glibc/lib"/*.so* 2>/dev/null || true
        chmod +x "${PREFIX}/glibc/lib/ld-linux-aarch64.so.1" 2>/dev/null || true
    fi

    log_ok "Environment configured"
}

# ── Validate runtime ──────────────────────────────────────────────────────────
validate_runtime() {
    log_step "Validating runtime"

    local OCA_DIR="${HOME}/.openclaw-android"
    local BIN_DIR="${OCA_DIR}/bin"
    local GLIBC_LDSO="${PREFIX}/glibc/lib/ld-linux-aarch64.so.1"
    local NODE_REAL="${OCA_DIR}/node/bin/node.real"
    local CERT_BUNDLE="${PREFIX}/etc/tls/cert.pem"

    local errors=0

    # Check glibc linker
    if [ -x "$GLIBC_LDSO" ]; then
        log_ok "glibc linker: $GLIBC_LDSO"
    else
        log_err "glibc linker missing or not executable: $GLIBC_LDSO"
        errors=$((errors + 1))
    fi

    # Check node.real
    if [ -x "$NODE_REAL" ]; then
        log_ok "node.real: $NODE_REAL"
    else
        log_err "node.real missing or not executable: $NODE_REAL"
        errors=$((errors + 1))
    fi

    # Check node wrapper
    if [ -x "${BIN_DIR}/node" ]; then
        log_ok "node wrapper: ${BIN_DIR}/node"
    else
        log_err "node wrapper missing: ${BIN_DIR}/node"
        errors=$((errors + 1))
    fi

    # Check cert bundle
    if [ -f "$CERT_BUNDLE" ]; then
        local cert_count
        cert_count=$(grep -c "BEGIN CERTIFICATE" "$CERT_BUNDLE" 2>/dev/null || echo "0")
        log_ok "CA certs: $cert_count certificates"
    else
        log_warn "CA cert bundle missing: $CERT_BUNDLE"
    fi

    # Test node execution
    log "  Testing Node.js execution..."
    local node_ver
    node_ver=$("${BIN_DIR}/node" --version 2>/dev/null) || {
        log_err "Node.js execution test FAILED"
        errors=$((errors + 1))
    }
    if [ -n "${node_ver:-}" ]; then
        log_ok "Node.js test passed: $node_ver"
    fi

    if [ "$errors" -gt 0 ]; then
        log_err "Runtime validation failed with $errors error(s)"
        exit 1
    fi

    log_ok "Runtime validation passed"
}

# ── Write completion marker ───────────────────────────────────────────────────
write_marker() {
    local MARKER="${HOME}/.openclaw-android/.post-setup-done"
    touch "$MARKER"
    log_ok "Post-setup complete. Marker: $MARKER"
}

# ── Main ──────────────────────────────────────────────────────────────────────
main() {
    # Check idempotency marker
    local MARKER="${HOME}/.openclaw-android/.post-setup-done"
    if [ -f "$MARKER" ]; then
        echo "[post-setup] Already completed (marker: $MARKER)"
        exit 0
    fi

    _init_log

    log "==================================================="
    log "  OpenClaw Android — Offline Post-Setup"
    log "==================================================="

    validate_env
    setup_glibc
    setup_certs
    setup_nodejs
    setup_openclaw
    setup_environment
    validate_runtime
    write_marker

    log ""
    log "==================================================="
    log "  Post-setup completed successfully!"
    log "  Log: $LOG_FILE"
    log "==================================================="
}

main "$@"
