# AGENTS.md

Guía rápida para agentes que trabajan sobre **OpenClaw Android**. Resume los
workflows, comandos y reglas críticas extraídos de [README.md](README.md),
[CONTRIBUTING.md](CONTRIBUTING.md), [TESTING.md](TESTING.md) y los workflows en
`.github/workflows/`.

---

## Layout

| Ruta | Descripción |
| --- | --- |
| `android/` | Módulo Android (Kotlin + Gradle). |
| `android/www/` | Frontend React 19 + Vite 7 + Tailwind 4. |
| `android/docs/` | Documentación del módulo Android. |
| `docs/` | Guías operativas y troubleshooting. |
| `scripts/` | Utilidades de instalación, build y test. |
| `platforms/` | Plugins por plataforma. |
| `tests/` | Verificadores y smoke tests. |
| Raíz (`install.sh`, `update.sh`, `oa.sh`, `bootstrap.sh`, `post-setup.sh`, ...) | Scripts shell del instalador/updater. |

---

## Requisitos de toolchain

- **JDK 17** (CI usa Temurin 21; ambos compilan).
- **Android SDK** con `platforms;android-35` instalado.
- **Node.js 20+** (CI usa Node 22).
- **pnpm** o **npm** para `android/www`.
- **Git** — requerido por la tarea Gradle `updateVersionFromGit`.

---

## Workflows habituales

### Build APK debug

```bash
cd android
./gradlew assembleDebug
```

El build encadena: `npm run build` en `android/www` → copia `dist/` a
`app/src/main/assets/www` → empaqueta scripts en `app/src/main/assets/scripts`.

Artefacto: `android/app/build/outputs/apk/debug/app-debug.apk`.

Instalación en dispositivo:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### Versionado desde git (usado en CI)

```bash
cd android
./gradlew updateVersionFromGit
```

### Frontend (`android/www`)

```bash
cd android/www
pnpm install            # o: npm ci
pnpm dev                # vite dev server
pnpm build              # tsc -b && vite build
pnpm build:zip          # build + genera www.zip
pnpm lint               # eslint .
pnpm test               # vitest (watch)
pnpm test -- --run      # una sola pasada
pnpm test:ui            # vitest UI
pnpm test:coverage      # cobertura v8
pnpm test:e2e           # Playwright
pnpm i18n:check         # node scripts/i18n-check.mjs
```

### Lint y análisis Kotlin

```bash
cd android
./gradlew ktlintCheck
./gradlew ktlintFormat
./gradlew detekt
./gradlew :app:lintDebug   # fallback si no existe ktlintCheck
```

### Tests Kotlin

```bash
cd android
./gradlew test                          # todos los unit tests
./gradlew :app:test                     # tests del módulo app
./gradlew :app:test --info              # reporte detallado
./gradlew :app:connectedAndroidTest     # instrumentados (requiere device/emulator)
./gradlew :app:jacocoTestReport         # cobertura → app/build/reports/jacoco/
```

### Suite completa via scripts

```bash
./scripts/run-tests.sh        # todos
./scripts/run-tests.sh -k     # solo Kotlin
./scripts/run-tests.sh -r     # solo React
./scripts/run-tests.sh -e     # solo E2E
./scripts/run-tests.sh -c     # con cobertura
```

En Windows: `.\scripts\run-tests.bat` con los mismos flags.

### Validación de scripts shell

```bash
bash -n install.sh
bash -n update-core.sh
bash -n oa.sh
shellcheck -e SC2015,SC2016 <script>.sh
```

CI ejecuta `shellcheck` sobre `**/*.sh` excluyendo `android/app/build/`,
`node_modules/` y `.agent/`.

### Markdown lint

```bash
npm install -g markdownlint-cli2
markdownlint-cli2 '.github/**/*.md' 'docs/**/*.md' \
  '!node_modules' '!android/www/node_modules' \
  '!android/www/dist' '!android/app/build'
```

### Sync check `post-setup.sh`

`post-setup.sh` (raíz) y `android/app/src/main/assets/scripts/post-setup.sh`
deben ser **idénticos**. CI lo verifica con `diff -q`. Si modificas uno, copia
el cambio al otro antes de commitear (el script de assets también se regenera
durante `:app:bundleScripts`).

### Activar pre-commit hooks

```bash
git config core.hooksPath .githooks
```

Ejecutan automáticamente: `ktlint` + `detekt` (Kotlin), `shellcheck` (shell),
`markdownlint` (md), `eslint` (frontend) y el sync check de `post-setup.sh`.

---

## CI (`.github/workflows/`)

| Workflow | Jobs |
| --- | --- |
| `android-build.yml` | `build-www` (npm ci → npm run build → www.zip), `build-apk` (gradle assembleDebug + updateVersionFromGit), `release` (en tags `v*`). |
| `code-quality.yml` | `shellcheck`, `script-sync` (post-setup.sh), `markdownlint`, `doc-freshness`, `kotlin-lint` (ktlintCheck/detekt, fallback `:app:lintDebug`), `unit-tests` (`./gradlew test`). |

`doc-freshness` advierte si cambias scripts/docs principales sin actualizar
README/CHANGELOG, y exige sincronizar las variantes `.ko.md` / `.zh.md` cuando
existen.

Las releases automáticas se disparan al hacer push de tags `v*` y empaquetan el
APK debug junto con `www.zip`.

---

## Convenciones de commit

- Mensajes en **inglés**, modo imperativo, sin prefijo (`Fix ...`, `Add ...`,
  `Migrate ...`).
- Asunto bajo 50 caracteres, empieza con mayúscula, sin punto final.
- Todo el trabajo se hace sobre `main` (flujo de rama única).

---

## Reglas críticas

- **Runtime proot + Alpine**: el gateway Node.js corre dentro de un contenedor
  proot + Alpine instalado en almacenamiento privado, no como binarios nativos
  de Android. Antes de tocar el runtime lee
  [android/docs/REGLAS_CRITICAS.md](android/docs/REGLAS_CRITICAS.md).
- **Compatibilidad Termux**: los scripts shell deben funcionar con `$PREFIX` y
  sin root (estilo POSIX-compatible, 4 espacios de indentación; convenciones en
  `scripts/lib.sh`).
- **Idempotencia**: los scripts de instalación/actualización deben poder
  ejecutarse múltiples veces sin efectos secundarios.
- **Rango Android**: `minSdk 31`, `targetSdk 35`, `compileSdk 35`, ABI única
  `arm64-v8a`.

---

## Documentación de referencia

- [DOCUMENTACION_TECNICA.md](DOCUMENTACION_TECNICA.md) — referencia técnica.
- [GUIA_VERSIONADO.md](GUIA_VERSIONADO.md) — flujo de versionado y tags.
- [TESTING.md](TESTING.md) — suite de pruebas completa.
- [CONTRIBUTING.md](CONTRIBUTING.md) — guía de contribución.
- [android/docs/](android/docs/) — arquitectura, bridge, gateway, instalación,
  terminal, frontend, reglas críticas.
- [docs/troubleshooting.md](docs/troubleshooting.md) — operación y debugging.
