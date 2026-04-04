#!/data/data/com.honeybadger.terminal/files/usr/bin/bash
# first-run.sh — Honey Badger first-run setup
# Runs after bootstrap extraction. Installs glibc-runner, sets up
# termux-api CLI dispatcher, and prepares the environment.
#
# This script is non-interactive (no user prompts).
# It is invoked automatically by the app after bootstrap extraction.
set -euo pipefail

# ── Color constants (from lib.sh pattern) ────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

# ── Paths ────────────────────────────────────
: "${PREFIX:?PREFIX not set}"
: "${HOME:?HOME not set}"

HB_DIR="$HOME/.honeybadger"
GLIBC_LDSO="$PREFIX/glibc/lib/ld-linux-aarch64.so.1"
PACMAN_CONF="$PREFIX/etc/pacman.conf"
LOCAL_BIN="$PREFIX/local/bin"
MARKER="$HB_DIR/.glibc-ready"

# ── Pre-checks ───────────────────────────────

if [ -f "$MARKER" ]; then
    echo -e "${GREEN}[SKIP]${NC} First-run setup already completed"
    exit 0
fi

ARCH=$(uname -m)
if [ "$ARCH" != "aarch64" ]; then
    echo -e "${RED}[FAIL]${NC} Honey Badger requires aarch64 (got: $ARCH)"
    exit 1
fi

echo ""
echo -e "${BOLD}══════════════════════════════════════════════${NC}"
echo -e "${BOLD}  Honey Badger — First Run Setup${NC}"
echo -e "${BOLD}══════════════════════════════════════════════${NC}"
echo ""

mkdir -p "$HB_DIR"

# ── Helper: install a package using apt download + manual extraction ──
# Standard `pkg install` fails because deb packages contain hardcoded
# /data/data/com.termux/ paths and dpkg can't access that directory.
# We work around this by downloading packages with apt, extracting with
# dpkg-deb, and rewriting paths (com.termux → our package name).
_pkg_name() {
    local app_data
    app_data=$(dirname "$(dirname "$PREFIX")")
    basename "$app_data"
}

# Binary-patch a file: replace old_str with new_str (null-padded to same length).
# Both strings must have old_str be >= new_str in length.
_binary_patch() {
    local file="$1" old_str="$2" new_str="$3"
    [ -f "$file" ] || return 0
    # Use LC_ALL=C for binary-safe processing
    # Convert strings to hex for binary-safe matching and replacement
    local old_hex new_hex pad_len
    old_hex=$(printf '%s' "$old_str" | xxd -p | tr -d '\n')
    new_hex=$(printf '%s' "$new_str" | xxd -p | tr -d '\n')
    pad_len=$(( ${#old_hex} - ${#new_hex} ))
    if [ $pad_len -lt 0 ]; then
        echo "    [WARN] new string longer than old, skipping patch for $(basename "$file")"
        return 1
    fi
    # Pad new_hex with null bytes
    local padding
    padding=$(printf '%0*d' "$pad_len" 0)
    new_hex="${new_hex}${padding}"
    # Use xxd to convert file to hex, sed to replace, xxd -r to convert back
    if xxd -p "$file" | tr -d '\n' | grep -q "$old_hex" 2>/dev/null; then
        xxd -p "$file" | tr -d '\n' | sed "s|$old_hex|$new_hex|g" | xxd -r -p > "${file}.patched"
        if [ -s "${file}.patched" ]; then
            chmod --reference="$file" "${file}.patched" 2>/dev/null || true
            mv "${file}.patched" "$file"
            echo "    Patched binary: $(basename "$file")"
            return 0
        fi
        rm -f "${file}.patched"
    fi
    return 0
}
_install_pkg() {
    local pkg="$1"
    local pkg_name
    pkg_name=$(_pkg_name)
    local tmp_dir="$PREFIX/tmp/hb-install-$$"
    mkdir -p "$tmp_dir"

    echo "  Downloading $pkg and dependencies..."
    # Download to current directory, then move debs to tmp_dir
    cd "$tmp_dir"
    if ! apt-get download "$pkg" 2>&1; then
        echo -e "${RED}[FAIL]${NC} Failed to download $pkg"
        cd /
        rm -rf "$tmp_dir"
        return 1
    fi

    # Also download dependencies (skip already-installed ones)
    local deps
    deps=$(apt-cache depends --recurse --no-recommends --no-suggests \
           --no-conflicts --no-breaks --no-replaces --no-enhances \
           "$pkg" 2>/dev/null | grep "^\w" | sort -u) || true
    for dep in $deps; do
        # Skip if already installed
        dpkg -s "$dep" &>/dev/null && continue
        apt-get download "$dep" 2>/dev/null || true
    done
    cd /

    echo "  Extracting packages with path rewriting..."
    local extract_dir="$tmp_dir/extract"
    local success=0
    for deb in "$tmp_dir"/*.deb; do
        [ -f "$deb" ] || continue
        mkdir -p "$extract_dir"
        # Use dpkg-deb --fsys-tarfile to get the data tar, then extract
        # with path rewriting via tar --transform
        if dpkg-deb --fsys-tarfile "$deb" 2>/dev/null | \
           tar.real -x --transform="s,com.termux,$pkg_name,g" -C / 2>/dev/null; then
            success=$((success + 1))
        else
            # Fallback: try raw-extract + cp
            rm -rf "$extract_dir"
            mkdir -p "$extract_dir"
            if dpkg-deb --raw-extract "$deb" "$extract_dir" 2>/dev/null; then
                if [ -d "$extract_dir/data/data/com.termux" ]; then
                    cp -a "$extract_dir/data/data/com.termux/files/usr/"* "$PREFIX/" 2>/dev/null && success=$((success + 1))
                elif [ -d "$extract_dir/data/data/$pkg_name" ]; then
                    cp -a "$extract_dir/data/data/$pkg_name/files/usr/"* "$PREFIX/" 2>/dev/null && success=$((success + 1))
                fi
            else
                echo -e "${YELLOW}[WARN]${NC} Failed to extract $(basename "$deb")"
            fi
        fi
        rm -rf "$extract_dir"
    done
    rm -rf "$tmp_dir"
    hash -r
    echo "  Extracted $success packages"
    [ $success -gt 0 ] && return 0 || return 1
}

# ── Step 1: Install pacman ───────────────────

echo -e "${BOLD}[1/4]${NC} Installing pacman..."

if ! command -v pacman &>/dev/null; then
    # Update package lists first
    echo "  Updating package lists..."
    apt-get update -y 2>&1 || true
    if ! _install_pkg pacman; then
        echo -e "${RED}[FAIL]${NC} Failed to install pacman"
        exit 1
    fi
    # Patch pacman text config and scripts: the installed pacman references
    # com.termux paths. Rewrite all text files to use our package name.
    set +e  # Disable exit-on-error for ALL patching operations
    echo "  Patching pacman configuration..."
    pkg_name=$(_pkg_name)
    # Patch key config files directly
    for f in "$PREFIX/etc/pacman.conf" \
             "$PREFIX/etc/makepkg.conf" \
             "$PREFIX/etc/pacman.d/mirrorlist" \
             "$PREFIX/bin/pacman-key" \
             "$PREFIX/bin/makepkg" \
             "$PREFIX/bin/repo-add" \
             "$PREFIX/bin/repo-remove"; do
        if [ -f "$f" ] && grep -q "com\.termux" "$f" 2>/dev/null; then
            sed -i "s|com\.termux|$pkg_name|g" "$f" && echo "    Patched: $(basename "$f")"
        fi
    done
    # Also patch any other text files in etc/pacman.d/
    for f in "$PREFIX/etc/pacman.d/"*; do
        if [ -f "$f" ] && grep -q "com\.termux" "$f" 2>/dev/null; then
            sed -i "s|com\.termux|$pkg_name|g" "$f" && echo "    Patched: $(basename "$f")"
        fi
    done
    # Downgrade pacman mirrors from HTTPS to HTTP (libcurl has hardcoded
    # cert path from com.termux build that we can't fix without recompiling)
    if [ -f "$PREFIX/etc/pacman.d/mirrorlist" ]; then
        sed -i 's|https://|http://|g' "$PREFIX/etc/pacman.d/mirrorlist"
        echo "    Downgraded mirrors to HTTP"
    fi
    # Binary-patch pacman/libalpm ELF binaries: these have hardcoded
    # com.termux paths for hook dirs, dbpath, etc. We use bpatch (a
    # binary search-and-replace tool bundled with the app) to replace
    # these paths with short symlinks pointing to our actual directories.
    echo "  Binary-patching pacman/libalpm (bpatch=$(command -v bpatch 2>/dev/null || echo 'NOT FOUND'))..."
    app_data="/data/data/$pkg_name"
    # Create short symlinks for specific directories that are checked by pacman
    # The old paths are long enough (>38 chars) for the replacement to fit
    [ -e "$app_data/H" ] || ln -sf "files/usr/share/libalpm/hooks" "$app_data/H" 2>/dev/null || true
    [ -e "$app_data/E" ] || ln -sf "files/usr/etc/pacman.d/hooks" "$app_data/E" 2>/dev/null || true
    [ -e "$app_data/D" ] || ln -sf "files/usr/var/lib/pacman" "$app_data/D" 2>/dev/null || true
    [ -e "$app_data/C" ] || ln -sf "files/usr/var/cache/pacman/pkg" "$app_data/C" 2>/dev/null || true
    [ -e "$app_data/G" ] || ln -sf "files/usr/etc/pacman.d/gnupg" "$app_data/G" 2>/dev/null || true
    [ -e "$app_data/T" ] || ln -sf "files/usr/etc/tls" "$app_data/T" 2>/dev/null || true
    echo "  bpatch location: $(which bpatch 2>/dev/null || echo NOT_FOUND)"
    echo "  Looking for binaries to patch in $PREFIX/lib/ and $PREFIX/bin/..."
    if command -v bpatch &>/dev/null; then
        for bin in "$PREFIX/lib/libalpm.so"* "$PREFIX/bin/pacman" \
                   "$PREFIX/bin/pacman-conf" "$PREFIX/bin/vercmp" \
                   "$PREFIX/lib/libcurl.so"*; do
            [ -f "$bin" ] || { echo "    Skip (not found): $bin"; continue; }
            if strings "$bin" 2>/dev/null | grep -q "com\.termux"; then
                echo "    Patching $(basename "$bin")..."
                # Patch paths WITH trailing slashes first (more specific)
                bpatch "$bin" "/data/data/com.termux/files/usr/share/libalpm/hooks/" "$app_data/H/"
                bpatch "$bin" "/data/data/com.termux/files/usr/etc/pacman.d/hooks/" "$app_data/E/"
                bpatch "$bin" "/data/data/com.termux/files/usr/var/lib/pacman/" "$app_data/D/"
                bpatch "$bin" "/data/data/com.termux/files/usr/var/cache/pacman/pkg/" "$app_data/C/"
                bpatch "$bin" "/data/data/com.termux/files/usr/etc/pacman.d/gnupg/" "$app_data/G/"
                # pacman.log: not critical for glibc-runner install, skip
                # Paths without trailing slashes (less common, some may not be in binary)
                bpatch "$bin" "/data/data/com.termux/files/usr/var/lib/pacman" "$app_data/D"
                bpatch "$bin" "/data/data/com.termux/files/usr/etc/pacman.d/gnupg" "$app_data/G"
                # pacman.conf: handled by --config flag (path too long for bpatch)
                bpatch "$bin" "/data/data/com.termux/files/usr/etc/tls" "$app_data/T"
                # tmp paths: too short to fit, but not critical for operation
            fi
        done
    else
        echo -e "${YELLOW}[WARN]${NC} bpatch not available, skipping binary patching"
    fi
    set -e  # Re-enable exit-on-error
    echo -e "${GREEN}[OK]${NC}   pacman configuration patched"
fi
echo -e "${GREEN}[OK]${NC}   pacman available"

# Always ensure pacman binaries are patched (may be needed even if pacman
# was installed in a previous run that failed before reaching bpatch)
set +e
pkg_name=$(_pkg_name)
app_data="/data/data/$pkg_name"
touch "$HB_DIR/.bpatch-check-reached"
if command -v bpatch &>/dev/null && grep -qa "com\.termux" "$PREFIX/lib/libalpm.so" 2>/dev/null; then
    touch "$HB_DIR/.bpatch-running"
    echo "  Ensuring pacman binaries are patched..."
    # Create short symlinks
    [ -e "$app_data/H" ] || ln -sf "files/usr/share/libalpm/hooks" "$app_data/H" 2>/dev/null
    [ -e "$app_data/E" ] || ln -sf "files/usr/etc/pacman.d/hooks" "$app_data/E" 2>/dev/null
    [ -e "$app_data/D" ] || ln -sf "files/usr/var/lib/pacman" "$app_data/D" 2>/dev/null
    [ -e "$app_data/C" ] || ln -sf "files/usr/var/cache/pacman/pkg" "$app_data/C" 2>/dev/null
    [ -e "$app_data/G" ] || ln -sf "files/usr/etc/pacman.d/gnupg" "$app_data/G" 2>/dev/null
    [ -e "$app_data/T" ] || ln -sf "files/usr/etc/tls" "$app_data/T" 2>/dev/null
    for bin in "$PREFIX/lib/libalpm.so"* "$PREFIX/bin/pacman" \
               "$PREFIX/bin/pacman-conf" "$PREFIX/bin/vercmp" \
               "$PREFIX/lib/libcurl.so"*; do
        [ -f "$bin" ] || continue
        if grep -qa "com\.termux" "$bin" 2>/dev/null; then
            echo "    Patching $(basename "$bin")..."
            bpatch "$bin" "/data/data/com.termux/files/usr/share/libalpm/hooks/" "$app_data/H/"
            bpatch "$bin" "/data/data/com.termux/files/usr/etc/pacman.d/hooks/" "$app_data/E/"
            bpatch "$bin" "/data/data/com.termux/files/usr/var/lib/pacman/" "$app_data/D/"
            bpatch "$bin" "/data/data/com.termux/files/usr/var/cache/pacman/pkg/" "$app_data/C/"
            bpatch "$bin" "/data/data/com.termux/files/usr/etc/pacman.d/gnupg/" "$app_data/G/"
            bpatch "$bin" "/data/data/com.termux/files/usr/var/lib/pacman" "$app_data/D"
            bpatch "$bin" "/data/data/com.termux/files/usr/etc/pacman.d/gnupg" "$app_data/G"
            bpatch "$bin" "/data/data/com.termux/files/usr/etc/tls" "$app_data/T"
        fi
    done
    echo -e "${GREEN}[OK]${NC}   pacman binaries patched"
    # Also ensure text configs are patched
    if grep -q "com\.termux" "$PREFIX/etc/pacman.conf" 2>/dev/null; then
        sed -i "s|com\.termux|$pkg_name|g" "$PREFIX/etc/pacman.conf"
        sed -i "s|com\.termux|$pkg_name|g" "$PREFIX/etc/pacman.d/mirrorlist" 2>/dev/null
        sed -i 's|https://|http://|g' "$PREFIX/etc/pacman.d/mirrorlist" 2>/dev/null
        echo -e "${GREEN}[OK]${NC}   pacman configs re-patched"
    fi
fi
set -e

# ── Step 2: Install glibc-runner ─────────────

echo ""
echo -e "${BOLD}[2/4]${NC} Installing glibc-runner..."

if [ -x "$GLIBC_LDSO" ]; then
    echo -e "${GREEN}[SKIP]${NC} glibc-runner already installed"
else
    # SigLevel workaround: Some devices have a GPGME crypto engine bug
    # that prevents signature verification. Temporarily set SigLevel = Never.
    SIGLEVEL_PATCHED=false
    if [ -f "$PACMAN_CONF" ]; then
        if ! grep -q "^SigLevel = Never" "$PACMAN_CONF"; then
            cp "$PACMAN_CONF" "${PACMAN_CONF}.bak"
            sed -i 's/^SigLevel\s*=.*/SigLevel = Never/' "$PACMAN_CONF"
            SIGLEVEL_PATCHED=true
            echo -e "${YELLOW}[INFO]${NC} Applied SigLevel = Never workaround (GPGME bug)"
        fi
    fi

    # Create required directories for pacman
    mkdir -p "$PREFIX/var/lib/pacman"
    mkdir -p "$PREFIX/var/cache/pacman/pkg"
    mkdir -p "$PREFIX/etc/pacman.d/gnupg"
    mkdir -p "$PREFIX/etc/pacman.d/hooks"
    mkdir -p "$PREFIX/share/libalpm/hooks"

    # Set SSL cert path for pacman downloads (curl uses this)
    export CURL_CA_BUNDLE="$PREFIX/etc/tls/cert.pem"
    export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"

    # Initialize pacman keyring (may hang on low-entropy devices)
    echo "  Initializing pacman keyring..."
    pacman-key --config "$PACMAN_CONF" --gpgdir "$PREFIX/etc/pacman.d/gnupg" --init 2>/dev/null || true
    pacman-key --config "$PACMAN_CONF" --gpgdir "$PREFIX/etc/pacman.d/gnupg" --populate 2>/dev/null || true

    # Install glibc-runner
    # --assume-installed: these packages are provided by Termux's apt but pacman
    # doesn't know about them, causing dependency resolution failures
    echo "  Installing glibc-runner (this may take a few minutes)..."
    # Try pacman first, then fall back to manual download+extract
    pkg_name=$(_pkg_name)
    PACMAN_OPTS="--config $PACMAN_CONF --dbpath $PREFIX/var/lib/pacman --cachedir $PREFIX/var/cache/pacman/pkg --gpgdir $PREFIX/etc/pacman.d/gnupg"
    pacman $PACMAN_OPTS -Sy glibc-runner --noconfirm --assume-installed bash,patchelf,resolv-conf --noscriptlet 2>&1 || true

    # If pacman failed (e.g., due to remaining com.termux path issues),
    # fall back to manual download and extraction with path rewriting
    if [ ! -x "$GLIBC_LDSO" ]; then
        echo -e "${YELLOW}[INFO]${NC} pacman extraction failed, trying manual download..."
        # Sync pacman database first
        pacman $PACMAN_OPTS -Sy 2>&1 || true
        # Download glibc-runner and dependencies
        mkdir -p "$PREFIX/var/cache/pacman/pkg"
        pacman $PACMAN_OPTS -Sw glibc-runner --noconfirm --assume-installed bash,patchelf,resolv-conf 2>&1 || true
        # Manually extract all downloaded packages with path rewriting
        for pkg_file in "$PREFIX/var/cache/pacman/pkg/"*.pkg.tar.*; do
            [ -f "$pkg_file" ] || continue
            echo "    Extracting $(basename "$pkg_file")..."
            # Extract to a temp dir, then copy to prefix
            # Can't use -C / because Android denies chdir to root
            extract_tmp="$PREFIX/tmp/pacman-extract-$$"
            mkdir -p "$extract_tmp"
            tar.real -xf "$pkg_file" --transform="s,com.termux,$pkg_name,g" -C "$extract_tmp" 2>&1 || true
            # Copy extracted usr/ files to our prefix
            if [ -d "$extract_tmp/data/data/$pkg_name/files/usr" ]; then
                cp -a "$extract_tmp/data/data/$pkg_name/files/usr/"* "$PREFIX/" 2>/dev/null || true
            fi
            rm -rf "$extract_tmp"
        done
    fi

    if [ ! -x "$GLIBC_LDSO" ]; then
        echo -e "${RED}[FAIL]${NC} Failed to install glibc-runner"
        # Restore SigLevel on failure
        if [ "$SIGLEVEL_PATCHED" = true ] && [ -f "${PACMAN_CONF}.bak" ]; then
            mv "${PACMAN_CONF}.bak" "$PACMAN_CONF"
        fi
        exit 1
    fi

    # Restore SigLevel after successful install
    if [ "$SIGLEVEL_PATCHED" = true ] && [ -f "${PACMAN_CONF}.bak" ]; then
        mv "${PACMAN_CONF}.bak" "$PACMAN_CONF"
        echo -e "${GREEN}[OK]${NC}   Restored pacman SigLevel"
    fi
fi

# Verify glibc dynamic linker
if [ ! -x "$GLIBC_LDSO" ]; then
    echo -e "${RED}[FAIL]${NC} glibc dynamic linker not found at $GLIBC_LDSO"
    exit 1
fi
echo -e "${GREEN}[OK]${NC}   glibc dynamic linker available"

if command -v grun &>/dev/null; then
    echo -e "${GREEN}[OK]${NC}   grun command available"
else
    echo -e "${YELLOW}[WARN]${NC} grun command not found (will use ld.so directly)"
fi

# ── Step 3: termux-api CLI dispatcher + symlinks ─

echo ""
echo -e "${BOLD}[3/4]${NC} Setting up termux-api CLI dispatcher..."

mkdir -p "$LOCAL_BIN"

# Single dispatcher script: reads the original termux-* script from
# $PREFIX/bin/, replaces the hardcoded package name, and executes it.
# This avoids modifying package-owned files — overlay principle.
cat > "$LOCAL_BIN/termux-dispatch" << 'DISPATCH'
#!/data/data/com.honeybadger.terminal/files/usr/bin/bash
cmd=$(basename "$0")
original="$PREFIX/bin/$cmd"
if [ ! -f "$original" ]; then
    echo "Error: $original not found" >&2
    echo "Install termux-api CLI tools: pkg install termux-api" >&2
    exit 1
fi
# Read the original script, replace the hardcoded Termux API package name
# with Honey Badger's package name, then execute it.
# shellcheck disable=SC2016
exec bash -c "$(sed 's/com\.termux\.api/com.honeybadger.terminal/g' "$original")" "$cmd" "$@"
DISPATCH
chmod +x "$LOCAL_BIN/termux-dispatch"
echo -e "${GREEN}[OK]${NC}   Dispatcher script created"

# Create symlinks for all existing termux-* commands
link_count=0
for cmd in "$PREFIX/bin"/termux-*; do
    [ -f "$cmd" ] || continue
    name=$(basename "$cmd")
    ln -sf termux-dispatch "$LOCAL_BIN/$name"
    link_count=$((link_count + 1))
done
echo -e "${GREEN}[OK]${NC}   Created $link_count symlinks in $LOCAL_BIN/"

# Ensure $PREFIX/local/bin is in PATH (prepended before $PREFIX/bin)
# This is set by the app's environment builder (Phase 6), but we also
# add it to .bashrc as a safety net.
BASHRC="$HOME/.bashrc"
PATH_LINE="export PATH=\"\$PREFIX/local/bin:\$PATH\""
if [ -f "$BASHRC" ]; then
    # shellcheck disable=SC2016 # Intentional: search for literal $PREFIX in file
    if ! grep -qF '$PREFIX/local/bin' "$BASHRC"; then
        {
            echo ""
            echo "# Honey Badger: overlay bin directory (termux-api dispatcher)"
            echo "$PATH_LINE"
        } >> "$BASHRC"
        echo -e "${GREEN}[OK]${NC}   Added \$PREFIX/local/bin to PATH in .bashrc"
    else
        echo -e "${GREEN}[SKIP]${NC} \$PREFIX/local/bin already in .bashrc PATH"
    fi
else
    {
        echo "# Honey Badger environment"
        echo "$PATH_LINE"
    } > "$BASHRC"
    echo -e "${GREEN}[OK]${NC}   Created .bashrc with \$PREFIX/local/bin in PATH"
fi

# ── Step 4: Completion marker ────────────────

echo ""
echo -e "${BOLD}[4/4]${NC} Finalizing..."

mkdir -p "$HB_DIR"
touch "$MARKER"
echo -e "${GREEN}[OK]${NC}   Completion marker created"

echo ""
echo -e "${BOLD}══════════════════════════════════════════════${NC}"
echo -e "${GREEN}${BOLD}  First-run setup complete!${NC}"
echo -e "${BOLD}══════════════════════════════════════════════${NC}"
echo ""
echo "  glibc linker: $GLIBC_LDSO"
echo "  Dispatcher:   $LOCAL_BIN/termux-dispatch"
echo "  Symlinks:     $link_count commands in $LOCAL_BIN/"
echo "  Marker:       $MARKER"
echo ""
