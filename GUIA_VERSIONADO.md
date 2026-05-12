# Guía de Versionado Profesional para OpenClaw Android

## 📋 Formato: Semantic Versioning (SemVer)

`MAJOR.MINOR.PATCH`

- **MAJOR**: Cambios incompatibles (breaking changes)
- **MINOR**: Nuevas funcionalidades compatibles
- **PATCH**: Correcciones de errores

Ejemplos:
- `1.0.0` - Primera versión estable
- `1.1.0` - Nueva funcionalidad
- `1.1.1` - Corrección de error

---

## 🚀 Flujo de Trabajo

### Paso 1: Crear un Git Tag

```bash
# Tag anotado (recomendado)
git tag -a v1.0.0 -m "Versión 1.0.0 - Primera versión estable"

# Push del tag al repositorio
git push origin v1.0.0
```

### Paso 2: Actualizar `version.properties`

```bash
cd android
./gradlew.bat updateVersionFromGit
```

Esto:
- Obtiene el número de commits como `VERSION_CODE`
- Obtiene el último tag como `VERSION_NAME`
- Guarda en `version.properties`

### Paso 3: Compilar la Versión

```bash
# Build debug (pruebas)
./gradlew.bat assembleDebug

# Build release (publicación)
./gradlew.bat assembleRelease
```

---

## 📁 Archivos Importantes

| Archivo | Propósito |
|---------|-----------|
| `version.properties` | Almacena VERSION_CODE y VERSION_NAME |
| `android/app/build.gradle.kts` | Configuración del versionado |

---

## ⚡ Comandos Rápidos

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

## 🔍 Verificar la Versión

En la app se muestra en el Dashboard:
- `packageName` · `versionName` · build `versionCode`

Ejemplo:
```
com.openclaw.android · v1.0.0 · build 42
```
