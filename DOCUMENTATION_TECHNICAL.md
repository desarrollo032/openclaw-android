# Technical Documentation — OpenClaw Android

> Last reviewed: 2026-05-12

## 1) Purpose

This document explains how OpenClaw runs natively on Android: payload installation, Kotlin↔Web bridge, gateway service lifecycle, and build automation.

## 2) Core building blocks

### 2.1 Native runtime

- `libnode.so`: Node.js runtime.
- `libbusybox.so`: baseline shell utilities.
- `libldlinux.so` + bundled glibc: Linux binary compatibility inside Android app sandbox.

### 2.2 Payload installation

- Runtime ships as app asset (`payload-v2.tar.xz`).
- Extracted into app-private storage.
- Wrappers (`node`, `npm`, `openclaw`) are generated for consistent command execution.

### 2.3 `window.OpenClaw` bridge

Interop layer between React and Kotlin for:

- installation status,
- gateway control,
- command execution,
- file picking,
- system/app diagnostics,
- platform/tool management.

## 3) Gateway lifecycle

- Gateway runs as a **Foreground Service**.
- Process health supervision with auto-restart.
- Centralized logs with sensitive-token redaction.
- Uptime tracking exposed to UI/support flows.

## 4) Frontend and WebView

- React/Vite frontend in `android/www`.
- WebView loads local assets and consumes bridge events.
- Startup wait/polling on `/health` avoids early load race conditions.

## 5) Security model

- Data/config remain in app-private storage.
- No mandatory wide external storage permissions for primary flows.
- Ephemeral tokens and log sanitization reduce leakage risk.

## 6) Build pipeline

During Gradle build:

1. Build web app (`npm run build` in `android/www`).
2. Sync built assets into app module assets.
3. Copy runtime helper scripts.
4. Produce APK containing aligned runtime + UI.

## 7) Recommended engineering practices

- Keep Kotlin bridge and TypeScript bridge contracts synchronized.
- Record runtime-impacting changes in `CHANGELOG.md`.
- Run gateway smoke tests after installer/runtime changes.

## 8) Internal references

- Overview: `README.md`
- Testing guide: `TESTING.md`
- Contribution guide: `CONTRIBUTING.md`
- Security policy: `SECURITY.md`
