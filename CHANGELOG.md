# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Script v1.0.24] - 2026-04-11

### Fixed

- Stop permanently polluting user's `~/.npmrc` during install — previously `post-setup.sh` would detect slow `registry.npmjs.org` access and write `registry=https://registry.npmmirror.com` to `~/.npmrc`, affecting all of the user's npm projects forever with no self-recovery. Now the installer uses session-scoped `NPM_CONFIG_REGISTRY` env var and caches the chosen registry at `~/.openclaw-android/.npm-registry`, re-exporting from `~/.bashrc` on each login. Users bitten by v1.0.22/v1.0.23 are auto-rescued on next `oa --update` because env vars override `~/.npmrc`, and their personal npmrc is left untouched (preserving auth tokens, scope registries, etc.) ([#107](https://github.com/AidanPark/openclaw-android/issues/107))
- Cover all three install paths for the npm registry detection — App Install (`post-setup.sh`), Termux Install (`install.sh`), and Update (`update-core.sh`). `scripts/setup-env.sh` now injects the `NPM_CONFIG_REGISTRY` re-export line inside the `# >>> OpenClaw on Android >>>` marker block of `~/.bashrc` so Termux-install and update paths get the same session-to-session re-evaluation as App Install.

## [Script v1.0.23] - 2026-04-11

### Fixed

- Preserve user's existing `~/.gitconfig` during post-setup — previously `cat > ~/.gitconfig` overwrote all user settings (name, email, aliases). Now uses `git config --global` to set only `http.sslCAInfo` and `url.https://github.com/.insteadOf` keys while keeping user entries intact ([#107](https://github.com/AidanPark/openclaw-android/issues/107))

## [Script v1.0.22] - 2026-04-10

### Added

- ELF binary auto-wrapping: detect glibc binaries via PT_INTERP and route through ld.so, enabling npx-installed native binaries like codex-acp to run on Android ([#103](https://github.com/AidanPark/openclaw-android/issues/103))
- Shebang resolution: handle `#!/usr/bin/env` scripts without libtermux-exec.so by resolving interpreters from PATH in JavaScript
- Shell invocation interception: detect `spawn('sh', ['-c', 'cmd'])` pattern used by npm/npx and resolve commands directly
- Supplementary glibc library deployment: bundle libcap.so.2 for third-party native binary support
- Localhost DNS shortcut: return 127.0.0.1 immediately for localhost lookups without querying external DNS ([#105](https://github.com/AidanPark/openclaw-android/issues/105))
- Create `$PREFIX/glibc/etc/hosts` if missing, ensuring getaddrinfo can resolve localhost ([#105](https://github.com/AidanPark/openclaw-android/issues/105))

### Changed

- Stop restoring LD_PRELOAD in glibc-compat.js — libtermux-exec.so re-injects it via execve hook, crashing glibc child processes with "Could not find a PHDR" errors
- Always use Termux shell for exec/execSync on all Android versions (previously only Android 7-8)

## [Script v1.0.21] - 2026-04-07

### Fixed

- Fix `oa --backup` exiting with error code 1 due to `tmpdir: unbound variable` — trap used local variable that went out of scope
- Fix `oa --backup` / `oa --restore` and `ask_yn` failing in tty-less environments (SSH pipe, non-interactive) — fallback to stdin when `/dev/tty` is unavailable

## [Script v1.0.20] - 2026-04-06

### Fixed

- Fix dep restore blocked by sharp build failure — `npm install` inside openclaw dir triggers sharp's native build which fails on Termux, blocking all other deps. Now runs `postinstall-bundled-plugins.mjs` directly with `npm_config_ignore_scripts=true` to skip sharp while installing channel deps ([#92](https://github.com/AidanPark/openclaw-android/issues/92))
- Fix dep restore skipped when openclaw already at latest version — check `@buape/carbon` presence instead of `OPENCLAW_UPDATED` flag

## [Script v1.0.19] - 2026-04-06

### Fixed

- Fix missing channel dependencies after `--ignore-scripts` install — reinstall deps inside openclaw package dir to restore optional modules like `@buape/carbon`, `grammy` ([#92](https://github.com/AidanPark/openclaw-android/issues/92))

## [Script v1.0.18] - 2026-04-04

### Fixed

- Fix `process.execPath` pointing to `ld-linux-aarch64.so.1` instead of node wrapper — glibc-compat.js had wrong path (`node/bin/node` instead of `bin/node`), causing OpenClaw 4.2 child process spawns with `--disable-warning=ExperimentalWarning` to fail ([#88](https://github.com/AidanPark/openclaw-android/issues/88))
- Add `_OA_WRAPPER_PATH` env var to node wrapper — eliminates path guessing in glibc-compat.js
- Fix verify-compat.sh checking wrong wrapper paths — tests now verify behavior (executable script, not ELF) instead of hardcoded paths
- Fix `install.sh` session PATH missing `$BIN_DIR` — node/npm commands could fail to resolve after Step 5
- Fix README (en/ko/zh) documenting wrong wrapper path (`node/bin/node` → `bin/node`)
- Fix npm wrapper writing through symlink and corrupting `openclaw.mjs` — npm creates symlink `$PREFIX/bin/openclaw` → `openclaw.mjs`, our shim writer followed it and destroyed the original file ([#89](https://github.com/AidanPark/openclaw-android/issues/89))

## [Script v1.0.17] - 2026-04-03

### Fixed

- Fix false-positive "glibc node wrapper not found" in install verification — verify-install.sh and status.sh referenced old `node/bin/` path instead of new `bin/` path ([#87](https://github.com/AidanPark/openclaw-android/issues/87))
- Add `BIN_DIR` constant to lib.sh to prevent path hardcoding drift across verification scripts

## [Script v1.0.16] - 2026-04-02

### Fixed

- Auto-repatch openclaw CLI wrapper after `npm install/update -g openclaw` — prevents `/usr/bin/env` shebang breakage on Termux ([#86](https://github.com/AidanPark/openclaw-android/issues/86))
- Move node/npm/npx wrappers to dedicated `bin/` directory safe from npm overwrites
- Fix missing `bin/node` wrapper creation in already-installed repair path

## [Script v1.0.15] - 2026-04-01

### Fixed

- Fix `dns.promises.lookup` not patched in glibc-compat.js — OpenClaw's web_search SSRF guard uses `node:dns/promises` which bypassed the c-ares DNS fix, causing `getaddrinfo EAI_AGAIN` on hosts without `resolv.conf` ([#83](https://github.com/AidanPark/openclaw-android/issues/83))

## [Script v1.0.14] - 2026-04-01

### Fixed

- Auto-disable Bonjour/mDNS at runtime when only loopback interface is visible — Android/Termux cannot send multicast, causing repeated "Announcement failed as of socket errors!" gateway logs ([#84](https://github.com/AidanPark/openclaw-android/issues/84))

## [Script v1.0.13] - 2026-03-31

### Added

- Playwright as optional install tool (`oa --install`) — installs `playwright-core`, auto-configures Chromium path and environment variables
- Auto-repatch openclaw CLI wrapper after `npm install/update -g openclaw` — prevents shebang breakage on Termux (#86)

### Changed

- Bump Gson 2.12.1 → 2.13.2
- Bump androidx.core:core-ktx 1.17.0 → 1.18.0
- Bump ktlint gradle plugin 14.1.0 → 14.2.0
- Bump Gradle wrapper 9.3.1 → 9.4.1
- Bump eslint 9.39.4 → 10.0.3
- Bump globals 16.5.0 → 17.4.0
- Bump eslint-plugin-react-refresh 0.4.24 → 0.5.2
- Bump GitHub Actions: checkout v4→v6, setup-node v4→v6, setup-java v4→v5, upload-artifact v4→v7, download-artifact v4→v8

## [App v0.4.0 / Script v1.0.12] - 2026-03-30

### Added

- App: i18n support — English, Korean (한국어), Chinese (中文) with auto-detection
- App: Language selector in Settings
- Add Chinese README (README.zh.md) with China mirror download link
- Add language switcher links to README.md, README.ko.md, README.zh.md
- GitHub mirror fallback for China/restricted networks (ghfast.top, ghproxy.net)
- npm registry auto-switch to npmmirror.com when npmjs.org is unreachable
- Add AppLogger centralized logging wrapper, replace all android.util.Log calls
- Add unit test infrastructure (JUnit5 + MockK, 22 tests)
- Add CI code-quality workflow (shellcheck, sync check, markdownlint, doc freshness, kotlin lint, unit tests)
- Add shellcheck, markdownlint to pre-commit hook
- Add post-setup.sh sync verification to pre-commit hook
- Add Claude Code hooks (push warning, document freshness, shellcheck auto-run)

### Changed

- Resolve all 48 detekt violations — no baseline needed
- Resolve all 43 shellcheck violations across all scripts
- Resolve all 125 markdownlint violations across all documents
- Refactor BootstrapManager, JsBridge, MainActivity for reduced complexity
- Convert A&&B||C patterns to if/then/else in install.sh, install-tools.sh
- Bump app version to v0.4.0 (versionCode 9)
- Bump script version to v1.0.12

## [1.0.6] - 2026-03-10

### Changed

- Clean up existing installation on reinstall

## [1.0.5] - 2026-03-06

### Added

- Standalone Android APK with WebView UI, native terminal, and extra keys bar
- Multi-session terminal tab bar with swipe navigation
- Boot auto-start via BootReceiver
- Chromium browser automation support (`scripts/install-chromium.sh`)
- `oa --install` command for installing optional tools independently

### Fixed

- `update-core.sh` syntax error (extra `fi` on line 237)
- sharp image processing with WASM fallback for glibc/bionic boundary

### Changed

- Switch terminal input mode to `TYPE_NULL` for strict terminal behavior

## [1.0.4] - 2025-12-15

### Changed

- Upgrade Node.js to v22.22.0 for FTS5 support (`node:sqlite` static bundle)
- Show version in all update skip and completion messages

### Removed

- oh-my-opencode support (OpenCode uses internal Bun, PATH-based plugins not detected)

### Fixed

- Update version glob picks oldest instead of latest
- Native module build failures during update

## [1.0.3] - 2025-11-20

### Added

- `.gitattributes` for LF line ending enforcement

### Changed

- Bump version to v1.0.3

## [1.0.2] - 2025-10-15

### Added

- Platform-plugin architecture (`platforms/<name>/` structure)
- Shared script library (`scripts/lib.sh`)
- Verification system (`tests/verify-install.sh`)

### Changed

- Refactor install flow into modular scripts
- Separate platform-specific code from infrastructure

## [1.0.1] - 2025-09-01

### Fixed

- Initial bug fixes and stability improvements

## [1.0.0] - 2025-08-15

### Added

- Initial release
- glibc-runner based execution (no proot-distro required)
- One-command installer (`curl | bash`)
- Node.js glibc wrapper for standard Linux binaries on Android
- Path conversion for Termux compatibility
- Optional tools: tmux, code-server, OpenCode, AI CLIs
- Post-install verification
