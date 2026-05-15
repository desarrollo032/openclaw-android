# Guía de versionado

Cómo gestionar versiones y tags en **OpenClaw Android**, alineado con [Semantic Versioning](https://semver.org/).

---

## Índice

- [Formato SemVer](#formato-semver)
- [Flujo de versionado](#flujo-de-versionado)
- [Archivos relevantes](#archivos-relevantes)
- [Comandos rápidos](#comandos-rápidos)
- [Verificar la versión en la app](#verificar-la-versión-en-la-app)

---

## Formato SemVer

```
MAJOR.MINOR.PATCH
```

| Componente | Cuándo aumentarlo |
| --- | --- |
| **MAJOR** | Cambios incompatibles (breaking changes). |
| **MINOR** | Nuevas funcionalidades compatibles. |
| **PATCH** | Correcciones de errores. |

**Ejemplos:**

- `1.0.0` — primera versión estable.
- `1.1.0` — nueva funcionalidad.
- `1.1.1` — corrección de error.

---

## Flujo de versionado

### Paso 1 — Crear un Git tag

```bash
# Tag anotado (recomendado)
git tag -a v1.0.0 -m "Versión 1.0.0 - Primera versión estable"

# Subir el tag al repositorio
git push origin v1.0.0
```

> El workflow de releases automáticos se dispara con cualquier tag `v*`.

### Paso 2 — Actualizar `version.properties`

```bash
cd android
./gradlew updateVersionFromGit
```

Esto:

- Toma el número de commits como `VERSION_CODE`.
- Toma el último tag como `VERSION_NAME`.
- Escribe el resultado en `version.properties`.

> En Windows usa `gradlew.bat`.

### Paso 3 — Compilar

```bash
# Debug
./gradlew assembleDebug

# Release
./gradlew assembleRelease
```

---

## Archivos relevantes

| Archivo | Propósito |
| --- | --- |
| `version.properties` | Guarda `VERSION_CODE` y `VERSION_NAME`. |
| `android/app/build.gradle.kts` | Lógica de versionado (lee `version.properties`). |

---

## Comandos rápidos

```bash
# Ver tags locales
git tag

# Ver tags remotos
git ls-remote --tags origin

# Eliminar tag local
git tag -d v1.0.0

# Eliminar tag remoto
git push origin --delete v1.0.0
```

---

## Verificar la versión en la app

El Dashboard muestra (en *Settings → About*):

```
com.openclaw.android · v1.0.0 · build 42
```

Formato: `packageName · versionName · build versionCode`.
