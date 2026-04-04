# Honey Badger

Honey Badger is an Android terminal emulator and Linux environment app built on [Termux](https://github.com/termux/termux-app). It provides a complete Linux terminal environment with **glibc-runner pre-configured**, enabling standard Linux (glibc-linked) binaries to run directly on Android without proot or chroot.

## What It Does

- **Complete Linux terminal** on Android (bash, coreutils, apt, pacman, and more)
- **glibc-runner auto-configured** on first launch -- run standard Linux binaries out of the box
- **Android API access** built-in (camera, SMS, sensors, location, clipboard, notifications)
- **Boot-time auto-start** built-in (run scripts automatically when the device boots)
- **Agent runtime ready** -- install and run agent runtimes like OpenClaw directly

## What It Does Not Do

- It is not a custom launcher or WebView wrapper
- It does not install Node.js, OpenClaw, or any specific runtime -- you install what you need
- It does not replace the package manager -- it uses Termux's existing package ecosystem

## Installation

1. Download the latest APK from [GitHub Releases](https://github.com/AidanPark/openclaw-android/releases)
2. Install the APK on your Android device (arm64 only)
3. Open the app
4. Wait for first-run setup to complete (bootstrap extraction + glibc-runner installation)
5. You now have a complete Linux terminal with glibc support

### Requirements

- Android 7.0 (API 24) or higher
- arm64-v8a (aarch64) device
- Internet connection for first-run setup

## Usage

After first-run setup completes, you have a full Linux terminal. Some examples:

```bash
# Check glibc-runner is installed
ls $PREFIX/glibc/lib/ld-linux-aarch64.so.1

# Install packages
pkg install python
pkg install git

# Install an agent runtime (e.g., OpenClaw)
# Follow the runtime's own installation instructions
```

### Android API Access

Built-in Termux:API commands are available for accessing Android device features:

```bash
termux-camera-photo  # Take a photo
termux-sms-send      # Send SMS
termux-location      # Get device location
termux-clipboard-get # Read clipboard
termux-notification  # Show notification
```

### Boot Auto-Start

Place scripts in `~/.termux/boot/` to run them automatically when the device boots. This is useful for starting agent services that should always be running.

## Architecture

Honey Badger is a fork of Termux with minimal modifications:

- **Termux Core**: Terminal emulator, session management, package management (unchanged)
- **Termux:API**: Android API access (integrated into the main app)
- **Termux:Boot**: Boot-time script execution (integrated into the main app)
- **Honey Badger Layer**: Branding, first-run glibc-runner setup, About screen

The glibc-runner is installed via pacman on first launch, providing a glibc dynamic linker and libraries. This allows standard Linux binaries compiled against glibc to run on Android's bionic-based environment.

## Building from Source

### Prerequisites

- Android SDK (API 28)
- Java 21 (Temurin recommended)

### Build

```bash
cd v2/termux-app
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Credits

Honey Badger is built on [Termux](https://github.com/termux/termux-app), an Android terminal emulator and Linux environment app. We are grateful to the Termux project and its contributors for creating and maintaining the foundation that makes this project possible.

## License

This project is licensed under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html) (GPLv3), the same license as Termux.

Termux is Copyright (c) 2015-present Fredrik Fornwall and contributors, licensed under GPLv3.

## Links

- **Honey Badger**: https://github.com/AidanPark/openclaw-android
- **Termux**: https://github.com/termux/termux-app
