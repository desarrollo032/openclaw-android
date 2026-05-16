# Guía de contribución

Gracias por tu interés en contribuir a **OpenClaw Android**. Esta guía resume cómo configurar el entorno, qué convenciones seguir y cómo proponer cambios.

---

## Índice

- [Primeros pasos](#primeros-pasos)
- [Setup de desarrollo](#setup-de-desarrollo)
- [Flujo de contribución](#flujo-de-contribución)
- [Estructura del proyecto](#estructura-del-proyecto)
- [Estilo de código](#estilo-de-código)
- [Consideraciones clave](#consideraciones-clave)
- [Reportar issues](#reportar-issues)
- [Licencia](#licencia)

---

## Primeros pasos

Las contribuciones de cualquier tamaño son bienvenidas. Si es tu primera vez:

1. **Busca un issue.** Filtra por la etiqueta [`good first issue`](https://github.com/desarrollo032/openclaw-android/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22).
2. **Elige un alcance pequeño.** Buenas opciones:
   - Correcciones de tipografía o documentación.
   - Mejoras de scripts shell.
   - Bugs con pasos claros de reproducción.
3. **Sigue el flujo fork → PR** descrito más abajo.

---

## Setup de desarrollo

### Scripts shell (instalador, actualizador)

```bash
git clone https://github.com/desarrollo032/openclaw-android.git
cd openclaw-android

# Validar sintaxis de los scripts
bash -n install.sh
bash -n update-core.sh
bash -n oa.sh
```

Los scripts usan **estilo POSIX-compatible** con **4 espacios** de indentación. Las convenciones compartidas viven en `scripts/lib.sh`.

### App Android

```bash
cd android

# Compilar APK debug
./gradlew assembleDebug

# Linters de Kotlin
./gradlew ktlintCheck
./gradlew detekt

# Formateo automático
./gradlew ktlintFormat
```

**Requisitos:** **JDK 17**, Android SDK (API 35), NDK arm64-v8a, Node.js 20+.

### WebView UI

```bash
cd android/www
pnpm install     # o: npm install
pnpm build       # o: npm run build
```

Stack: **React 19 · Vite 7 · TypeScript 5.9 · Tailwind 4**.

### Activar git hooks

```bash
git config core.hooksPath .githooks
```

El hook `pre-commit` ejecuta automáticamente:

- **Kotlin**: `ktlint` (formato) + `detekt` (análisis estático).
- **Shell**: `shellcheck` (requiere instalar `shellcheck`).
- **Markdown**: `markdownlint` (requiere `markdownlint-cli2`).
- **WebView**: `ESLint` sobre TypeScript/React de `android/www/`.
- **Sync check**: verifica que `post-setup.sh` esté sincronizado entre raíz y assets.

---

## Flujo de contribución

### 1. Fork y clone

```bash
git clone https://github.com/<tu-usuario>/openclaw-android.git
cd openclaw-android
```

### 2. Realiza tus cambios

Todo el trabajo se hace sobre **`main`** — flujo de rama única, sin prefijos especiales.

### 3. Verifica antes de commitear

| Cambio | Verificación |
| --- | --- |
| Scripts shell | `bash -n <script>` |
| App Android | `./gradlew assembleDebug` |
| Código Kotlin | `./gradlew ktlintCheck && ./gradlew detekt` |
| Frontend | `pnpm build` desde `android/www` |

### 4. Commit

Los mensajes van en **inglés**, modo imperativo, sin prefijo:

```
Fix update-core.sh syntax error
Add multi-session terminal tab bar
Migrate runtime to proot + Alpine Linux
```

- Empieza con mayúscula, sin punto final.
- Asunto bajo los **50 caracteres**.
- Tiempo presente imperativo (`Fix`, no `Fixed`/`Fixes`).

### 5. Abre el Pull Request

PR contra `main`. En la descripción incluye:

- **Qué** hace el cambio.
- **Por qué** es necesario.
- **Cómo probarlo**.

---

## Estructura del proyecto

| Capa | Ruta | Descripción |
| --- | --- | --- |
| Shell scripts | raíz | Instalador, updater, CLI. Corren en Termux. |
| App Android | `android/` | Kotlin + WebView UI + terminal nativa. |
| Frontend | `android/www/` | React 19 + Vite 7 + Tailwind 4. |
| Docs | `docs/`, `android/docs/` | Guías técnicas y operativas. |

Consulta el [README principal](README.md) para la arquitectura completa.

---

## Estilo de código

| Lenguaje | Estilo | Indentación |
| --- | --- | --- |
| Shell (bash) | POSIX-compatible, convenciones de `scripts/lib.sh` | 4 espacios |
| Kotlin | [Convenciones oficiales](https://kotlinlang.org/docs/coding-conventions.html) | 4 espacios |
| XML | Convenciones estándar de Android | 2 espacios |
| TypeScript/React | Config ESLint de `android/www/` | 2 espacios |

---

## Consideraciones clave

Al contribuir, ten presente:

- **Compatibilidad Termux** — los scripts deben funcionar con `$PREFIX` y sin root.
- **proot + Alpine** — el runtime de OpenClaw corre dentro de un contenedor proot + Alpine Linux, no como binarios nativos de Android.
- **Conversión de rutas** — las rutas estándar de Linux (`/tmp`, `/bin/sh`) se mapean a equivalentes de Termux o del Alpine.
- **Rango Android** — la app objetivo es **`minSdk 31` → `targetSdk 35`** (Android 12 a 14).
- **Idempotencia** — los scripts de instalación/actualización deben poder ejecutarse múltiples veces sin efectos secundarios.

> 🔥 Antes de tocar el runtime lee [android/docs/REGLAS_CRITICAS.md](android/docs/REGLAS_CRITICAS.md).

---

## Reportar issues

- **Bugs**: incluye versión de Android, modelo del dispositivo, versión de Termux y pasos de reproducción.
- **Features**: describe el caso de uso y la propuesta de implementación.
- **Seguridad**: consulta [SECURITY.md](SECURITY.md) para divulgación responsable.

---

## Licencia

Al contribuir aceptas que tus cambios se distribuirán bajo la licencia **MIT** del proyecto (ver [LICENSE](LICENSE)).
