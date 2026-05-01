#!/usr/bin/env bash
# install-minimal.sh - Minimal OpenClaw installer (~200MB)
# Stack: curl, git, glibc-runner, Node.js 22, OpenClaw
# Excludes: python, make, cmake, clang, binutils, tmux, ttyd, dufs, code-server, chromium, playwright, clawdhub, PyYAML
#
# Usage: bash install-minimal.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/scripts/lib.sh"

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${BOLD}  OpenClaw on Android - Minimal Installer${NC}"
echo -e "${BOLD}  Version: ${OA_VERSION}${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""
echo "Minimal stack (~200MB) - No optional tools"
echo ""

step() {
    echo ""
    echo -e "${BOLD}[$1/8] $2${NC}"
    echo "----------------------------------------"
}

# Pre-flight: Ensure we are in Termux
if [ -z "${PREFIX:-}" ]; then
    echo -e "${RED}[FAIL]${NC} Not running in Termux"
    exit 1
fi

# Step 1: Install curl and git (required for all operations)
step 1 "Install curl and git"
pkg install -y curl git
echo -e "${GREEN}[OK]${NC}   curl and git installed"

# Step 2: Install glibc-runner via pacman
step 2 "Install glibc-runner"
bash "$SCRIPT_DIR/scripts/install-glibc.sh"
echo -e "${GREEN}[OK]${NC}   glibc installed"

# Step 3: Install Node.js 22 LTS with ld.so wrapper
step 3 "Install Node.js 22 LTS"
bash "$SCRIPT_DIR/scripts/install-nodejs.sh"
echo -e "${GREEN}[OK]${NC}   Node.js installed"

# Source environment for current session
GLIBC_BIN_DIR="$PROJECT_DIR/bin"
GLIBC_NODE_DIR="$PROJECT_DIR/node"
export PATH="$GLIBC_BIN_DIR:$GLIBC_NODE_DIR/bin:$HOME/.local/bin:$PATH"
export TMPDIR="$PREFIX/tmp"
export TMP="$TMPDIR"
export TEMP="$TMPDIR"
export OA_GLIBC=1

# Auto-detect npm registry
command -v resolve_npm_registry >/dev/null 2>&1 && resolve_npm_registry || true

# Step 4: Install OpenClaw npm package
step 4 "Install OpenClaw"
bash "$SCRIPT_DIR/platforms/openclaw/install.sh"
echo -e "${GREEN}[OK]${NC}   OpenClaw installed"

# Step 5: Apply glibc-compat.js patch
step 5 "Apply glibc-compat.js patch"
mkdir -p "$PROJECT_DIR/patches"
cp "$SCRIPT_DIR/patches/glibc-compat.js" "$PROJECT_DIR/patches/glibc-compat.js"
echo -e "${GREEN}[OK]${NC}   glibc-compat.js applied"

# Step 6: Install systemctl stub
step 6 "Install systemctl stub"
cp "$SCRIPT_DIR/patches/systemctl" "$PREFIX/bin/systemctl"
chmod +x "$PREFIX/bin/systemctl"
echo -e "${GREEN}[OK]${NC}   systemctl stub installed"

# Step 7: Configure environment
step 7 "Configure environment"
bash "$SCRIPT_DIR/scripts/setup-env.sh"

# Platform-specific environment
SELECTED_PLATFORM="openclaw"
PLATFORM_ENV_SCRIPT="$SCRIPT_DIR/platforms/$SELECTED_PLATFORM/env.sh"
if [ -f "$PLATFORM_ENV_SCRIPT" ]; then
    eval "$(bash "$PLATFORM_ENV_SCRIPT")"
fi

# Write platform marker
mkdir -p "$PROJECT_DIR"
echo "$SELECTED_PLATFORM" > "$PLATFORM_MARKER"

# Install oa CLI
cp "$SCRIPT_DIR/oa.sh" "$PREFIX/bin/oa"
chmod +x "$PREFIX/bin/oa"

# Install update CLI
cp "$SCRIPT_DIR/update.sh" "$PREFIX/bin/oaupdate"
chmod +x "$PREFIX/bin/oaupdate"

# Copy uninstall script
cp "$SCRIPT_DIR/uninstall.sh" "$PROJECT_DIR/uninstall.sh"
chmod +x "$PROJECT_DIR/uninstall.sh"

# Copy scripts for oa CLI
mkdir -p "$PROJECT_DIR/scripts"
cp "$SCRIPT_DIR/scripts/lib.sh" "$PROJECT_DIR/scripts/lib.sh"
cp "$SCRIPT_DIR/scripts/setup-env.sh" "$PROJECT_DIR/scripts/setup-env.sh"
cp "$SCRIPT_DIR/scripts/setup-paths.sh" "$PROJECT_DIR/scripts/setup-paths.sh"

# Copy patches
mkdir -p "$PROJECT_DIR/platforms"
rm -rf "$PROJECT_DIR/platforms/$SELECTED_PLATFORM"
cp -R "$SCRIPT_DIR/platforms/$SELECTED_PLATFORM" "$PROJECT_DIR/platforms/$SELECTED_PLATFORM"

echo -e "${GREEN}[OK]${NC}   Environment configured"

# Step 8: Verification
step 8 "Verification"

# Verify Node.js version
NODE_VER=$("$GLIBC_BIN_DIR/node" --version 2>/dev/null || echo "NOT FOUND")
echo "  Node.js: $NODE_VER"

# Verify OpenClaw
OPENCLAW_VER=$(openclaw --version 2>/dev/null || echo "NOT FOUND")
echo "  OpenClaw: $OPENCLAW_VER"

# Verify wrappers exist
if [ -x "$GLIBC_BIN_DIR/node" ]; then
    echo -e "${GREEN}[OK]${NC}   node wrapper: $GLIBC_BIN_DIR/node"
fi
if [ -x "$GLIBC_BIN_DIR/npm" ]; then
    echo -e "${GREEN}[OK]${NC}   npm wrapper: $GLIBC_BIN_DIR/npm"
fi
if [ -x "$PREFIX/bin/systemctl" ]; then
    echo -e "${GREEN}[OK]${NC}   systemctl stub"
fi

# Write installed marker
write_installed_marker() {
    local marker="$PROJECT_DIR/installed.json"
    mkdir -p "$PROJECT_DIR"
    cat > "$marker" << EOF
{
  "installed": true,
  "version": "$OPENCLAW_VER",
  "node_version": "$NODE_VER",
  "timestamp": "$(date -Iseconds)"
}
EOF
    echo -e "${GREEN}[OK]${NC}   installed.json written"
}

write_installed_marker

echo ""
echo -e "${BOLD}========================================${NC}"
echo -e "${GREEN}${BOLD}  Installation Complete!${NC}"
echo -e "${BOLD}========================================${NC}"
echo ""
echo "Next step:"
echo "  source ~/.bashrc"
echo ""
echo "Update command:"
echo "  oa --update"
echo ""
