#!/usr/bin/env bash
# bootstrap.sh — OpenClaw on Android installer entry point
# Served at: curl -sL myopenclawhub.com/install | bash
# Also available at: curl -sL https://raw.githubusercontent.com/AidanPark/openclaw-android/main/bootstrap.sh | bash
set -euo pipefail

REPO_TARBALL_ORIGIN="https://github.com/AidanPark/openclaw-android/archive/refs/heads/main.tar.gz"
REPO_BASE_ORIGIN="https://raw.githubusercontent.com/AidanPark/openclaw-android/main"
INSTALL_DIR="$HOME/.openclaw-android/installer"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

echo ""
echo -e "${BOLD}OpenClaw on Android — Installer${NC}"
echo ""

# ── Pre-flight checks ──────────────────────────────────────────────────────────

if [ -z "${PREFIX:-}" ]; then
    echo -e "${RED}[FAIL]${NC} Not running in Termux (\$PREFIX not set)"
    echo "       Install Termux from F-Droid: https://f-droid.org/packages/com.termux/"
    exit 1
fi

if ! command -v curl &>/dev/null; then
    echo -e "${RED}[FAIL]${NC} curl not found. Install it with: pkg install curl"
    exit 1
fi

if ! command -v tar &>/dev/null; then
    echo "Installing tar..."
    pkg install -y tar
fi

# ── Mirror fallback for restricted networks ────────────────────────────────────

REPO_TARBALL="$REPO_TARBALL_ORIGIN"

resolve_tarball_url() {
    # Test origin first
    if curl -sI --connect-timeout 5 "$REPO_BASE_ORIGIN/bootstrap.sh" >/dev/null 2>&1; then
        REPO_TARBALL="$REPO_TARBALL_ORIGIN"
        return 0
    fi
    # Try mirrors (ghfast/ghproxy accept the full GitHub URL as path)
    local mirrors=(
        "https://ghfast.top/$REPO_TARBALL_ORIGIN"
        "https://ghproxy.net/$REPO_TARBALL_ORIGIN"
        "https://mirror.ghproxy.com/$REPO_TARBALL_ORIGIN"
    )
    for m in "${mirrors[@]}"; do
        if curl -sI --connect-timeout 5 "$m" >/dev/null 2>&1; then
            echo -e "  ${YELLOW}[MIRROR]${NC} Using mirror for GitHub downloads"
            REPO_TARBALL="$m"
            return 0
        fi
    done
    # Fallback to origin even if unreachable — let curl report the real error
    REPO_TARBALL="$REPO_TARBALL_ORIGIN"
    return 1
}

echo "Checking network connectivity..."
resolve_tarball_url || echo -e "  ${YELLOW}[WARN]${NC} GitHub may be slow — proceeding anyway"

# ── Bootstrap SSL certificates before any download ────────────────────────────
# Use the bundled cacert.pem from the repo if already extracted,
# otherwise fall back to Android system certs so curl can verify HTTPS
# even before ca-certificates is installed via pkg.
_CERT_BUNDLE="$PREFIX/etc/tls/cert.pem"
if [ ! -s "$_CERT_BUNDLE" ] || ! grep -q "BEGIN CERTIFICATE" "$_CERT_BUNDLE" 2>/dev/null; then
    mkdir -p "$PREFIX/etc/tls"
    # Try Android system certs first (always available on Android)
    if [ -d "/system/etc/security/cacerts" ]; then
        cat /system/etc/security/cacerts/*.0 > "$_CERT_BUNDLE" 2>/dev/null || true
    fi
fi
export CURL_CA_BUNDLE="$_CERT_BUNDLE"
export SSL_CERT_FILE="$_CERT_BUNDLE"
unset _CERT_BUNDLE

# ── Clean up any previous failed download ─────────────────────────────────────

if [ -d "$INSTALL_DIR" ]; then
    echo "Cleaning up previous installer cache..."
    rm -rf "$INSTALL_DIR"
fi
mkdir -p "$INSTALL_DIR"

# ── Download installer ────────────────────────────────────────────────────────

echo "Downloading installer (~5MB)..."

# Download to a temp file first, then extract — avoids partial extraction on failure
TMP_TARBALL=$(mktemp "$PREFIX/tmp/openclaw-bootstrap.XXXXXX") || {
    echo -e "${RED}[FAIL]${NC} Failed to create temp file"
    exit 1
}
trap 'rm -f "$TMP_TARBALL"' EXIT

if ! curl -fL --max-time 120 --retry 2 --retry-delay 3 \
        -# "$REPO_TARBALL" -o "$TMP_TARBALL"; then
    echo -e "${RED}[FAIL]${NC} Download failed"
    echo "       Check your internet connection and try again."
    exit 1
fi

echo "Extracting..."
if ! tar xz -C "$INSTALL_DIR" --strip-components=1 -f "$TMP_TARBALL"; then
    echo -e "${RED}[FAIL]${NC} Extraction failed — archive may be corrupt"
    rm -rf "$INSTALL_DIR"
    exit 1
fi
rm -f "$TMP_TARBALL"
trap - EXIT

echo -e "${GREEN}[OK]${NC}   Installer ready"
echo ""

# ── Install bundled CA certificates before running installer ─────────────────
# The repo includes scripts/cacert.pem (Mozilla bundle) — install it now so
# pkg/curl/apt work without needing ca-certificates pre-installed.
_REPO_CERT="$INSTALL_DIR/scripts/cacert.pem"
_CERT_BUNDLE="$PREFIX/etc/tls/cert.pem"
if [ -f "$_REPO_CERT" ]; then
    mkdir -p "$PREFIX/etc/tls"
    cp "$_REPO_CERT" "$_CERT_BUNDLE"
    export CURL_CA_BUNDLE="$_CERT_BUNDLE"
    export SSL_CERT_FILE="$_CERT_BUNDLE"
    echo -e "${GREEN}[OK]${NC}   CA certificates installed from bundled cacert.pem"
fi
unset _REPO_CERT _CERT_BUNDLE

# ── Run installer ─────────────────────────────────────────────────────────────

bash "$INSTALL_DIR/install.sh"

# ── Post-install cleanup ──────────────────────────────────────────────────────

cp "$INSTALL_DIR/uninstall.sh" "$HOME/.openclaw-android/uninstall.sh"
chmod +x "$HOME/.openclaw-android/uninstall.sh"
rm -rf "$INSTALL_DIR"

# ── Final message ─────────────────────────────────────────────────────────────

echo ""
echo -e "${BOLD}To activate the new environment in this session:${NC}"
echo ""
echo "  source ~/.bashrc"
echo ""
