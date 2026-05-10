# Technical Documentation - Autonomous Architecture

This document describes the internal architecture of the autonomous OpenClaw implementation on Android.

## 🏗 Process Architecture

Unlike the standard implementation that depends on a full Linux environment (Termux/Proot), this version uses direct process execution via `ProcessBuilder`.

### 1. The Gateway Process
The core of the application is a Node.js process launched with the following characteristics:
- **Binary**: `context.filesDir/home/payload/bin/node`
- **Script**: `context.filesDir/home/payload/lib/node_modules/openclaw/openclaw.mjs`
- **Working Directory**: `context.filesDir/home/payload`

### 2. Library Isolation (glibc)
To avoid conflicts with Android's native libraries (Bionic), a pre-compiled glibc environment is used:
- The `LD_LIBRARY_PATH` variable is injected pointing to `payload/glibc/lib`.
- This allows the Node.js binary (compiled for standard Linux) to find its system dependencies.

## 🛠 Software Components

### Interactive Terminal (`TerminalActivity.kt`)
Complete terminal implementation using official Termux libraries:
- **Libraries**: Local AAR files: `terminal-emulator.aar` and `terminal-view.aar`
- **Shell**: `/system/bin/sh` (native Android shell, no external dependencies)
- **Features**:
  - Full VT100/ANSI terminal emulation
  - Support for physical and virtual keyboard input
  - Touch scroll and text selection
  - Color configuration (16 ANSI colors) and font size
  - Lifecycle management (session creation/destruction)
  - **Copy and Paste**: Full clipboard support with Toast confirmation
- **Implementation**:
  - `TerminalSession`: Manages the shell process and emulator
  - `TerminalView`: Visual widget to display and interact with the terminal
  - `TerminalSessionClient`: Callbacks for session events (including copy/paste)
  - `TerminalViewClient`: Callbacks for view events (gestures, keys)

### Robust Extraction (`OpenClawExtensions.kt`)
Libraries from `org.apache.commons:commons-compress` and `org.tukaani:xz` are used to handle streaming decompression:
- Avoids loading large files into memory.
- Preserves the execution bits (`chmod`) required for binaries.

### Lifecycle Management (`OpenClawGatewayService.kt`)
The service manages persistence:
- **Foreground Service**: Prevents Android from killing the process to save battery.
- **Persistent Notification**: Shows status, uptime, and direct actions (Restart, View logs).
- **Supervisor Job**: Maintains a dedicated coroutine to monitor the health of the child process.
- **Environment Management**: Dynamically configures `PATH` and `TMPDIR` for Node.js to work correctly on internal storage.
- **Centralized Logging**: Captures stdout/stderr with automatic redaction of sensitive tokens.
- **Uptime Tracking**: Records and exposes process uptime in seconds.
- **Health Check & Auto-restart**: Monitors the process and automatically restarts if it fails.

### Android Bridge (`AndroidBridge.kt`)
Bidirectional communication bridge between React and Android:
- **Standard `window.OpenClaw`**: Unified native access under a single global object
- **Comprehensive Methods**: Setup, gateway control, terminal access, file picking, system info, battery optimization, storage management, and more
- **Event System**: Supports both `android:` and `native:` event prefixes for flexibility
- **Async Operations**: File picking and command execution with callback mechanisms
- **Platform/Tool Management**: Methods for installing/uninstalling platforms and tools

## 🌐 Communication (WebUI)

- **Localhost Only**: The server listens only on `127.0.0.1:18789`.
- **WebView Bridge**: `WebView` with `JavaScriptEnabled` and `DomStorageEnabled` is used to render the React Dashboard.
- **Wait Mechanism**: `OpenClawDashboardActivity` implements a polling loop on the `/health` endpoint before attempting to load the page, avoiding "Connection Refused" errors.

## 🔒 Security and Storage

- **Total Sandbox**: No `READ_EXTERNAL_STORAGE` or `WRITE_EXTERNAL_STORAGE` permissions required. Everything happens in `context.filesDir`.
- **Privacy**: Configuration, memory, and AI plugin data are protected by Android's filesystem and not accessible by other apps.
- **Token Security**: Dashboard tokens are generated per launch, never persisted to disk, and automatically redacted from logs.

## 🛠 Build Automation

**Vite lifecycle is integrated within Gradle**. No need to compile the frontend separately:

1. When executing `./gradlew assembleDebug`, the system:
   - Enters `android/www/`.
   - Runs `npm run build`.
   - Cleans and syncs assets to `src/main/assets/www/`.
   - Copies maintenance scripts to `src/main/assets/scripts/`.
2. The result is an APK that always contains the latest UI version and scripts.

## 📱 Android Compatibility Notes

### Android 11+ (API 30+) - W^X Restrictions
Android 11 introduced Write-Xor-Execute restrictions that can affect binary execution:
- The terminal uses `/system/bin/sh` which is part of the system and works correctly
- If there are issues, verify that the working directory is accessible (`/data/local/tmp`)
- Termux libraries properly handle these restrictions
