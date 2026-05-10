# Testing Guide - OpenClaw Android

Guía completa para ejecutar tests en el proyecto OpenClaw Android.

## 📋 Índice

- [Resumen](#resumen)
- [Estructura de Tests](#estructura-de-tests)
- [Tests Kotlin (Android)](#tests-kotlin-android)
- [Tests React (Frontend)](#tests-react-frontend)
- [Tests E2E (Playwright)](#tests-e2e-playwright)
- [Scripts de Ejecución](#scripts-de-ejecución)
- [Cobertura](#cobertura)

---

## 📝 Resumen

El proyecto tiene una suite de testing completa que cubre:

| Capa | Framework | Ubicación |
|------|-----------|-----------|
| **Unit Tests Kotlin** | JUnit5 + Kotest + MockK | `android/app/src/test/` |
| **Integration Tests** | Robolectric | `android/app/src/test/` |
| **UI Tests Android** | Espresso | `android/app/src/androidTest/` |
| **Unit Tests React** | Vitest + RTL | `android/www/src/test/` |
| **E2E Tests** | Playwright | `android/www/e2e/` |

---

## 🏗️ Estructura de Tests

```
openclaw-android/
├── android/
│   └── app/
│       ├── src/
│       │   ├── test/                    # Tests unitarios Kotlin
│       │   │   └── java/com/openclaw/
│       │   │       ├── OpenClawInstallerTest.kt
│       │   │       ├── AssetDetectorTest.kt
│       │   │       └── OpenClawExtensionsTest.kt
│       │   └── androidTest/               # Tests instrumentados
│       │       └── java/com/openclaw/
│       │           ├── MainActivityInstrumentedTest.kt
│       │           ├── AndroidBridgeInstrumentedTest.kt
│       │           └── GatewayServiceInstrumentedTest.kt
│       └── build.gradle.kts             # Configuración testing
└── android/www/
    ├── src/test/                        # Tests React
    │   ├── setup.ts                     # Setup de Vitest
    │   ├── bridge.test.ts
    │   ├── router.test.tsx
    │   ├── useGatewayStatus.test.tsx
    │   └── App.test.tsx
    ├── e2e/                             # Tests E2E Playwright
    │   └── app.spec.ts
    ├── vitest.config.ts
    └── playwright.config.ts
```

---

## 🧪 Tests Kotlin (Android)

### Dependencias

- **JUnit5**: Framework base de testing
- **Kotest**: Estilo BDD para tests descriptivos
- **MockK**: Mocking para Kotlin
- **Robolectric**: Tests unitarios con Android framework
- **Coroutines Test**: Testing de coroutines

### Ejecutar Tests

```bash
# Todos los tests unitarios
./gradlew :app:test

# Con reporte detallado
./gradlew :app:test --info

# Tests instrumentados (requiere dispositivo/emulador)
./gradlew :app:connectedAndroidTest
```

### Tests Incluidos

| Test | Descripción |
|------|-------------|
| `OpenClawInstallerTest` | Tests de instalación, verificación de payload, directorios |
| `AssetDetectorTest` | Tests de detección de assets, espacio libre |
| `OpenClawExtensionsTest` | Tests de extensiones de archivos, extracción tar |

---

## ⚛️ Tests React (Frontend)

### Dependencias

- **Vitest**: Framework de testing rápido
- **React Testing Library**: Testing de componentes React
- **MSW** (Mock Service Worker): Mock de API calls
- **jsdom**: Entorno DOM para tests

### Ejecutar Tests

```bash
cd android/www

# Instalar dependencias
npm install

# Ejecutar tests en modo watch
npm test

# Ejecutar tests una sola vez
npm test -- --run

# Con UI interactiva
npm run test:ui

# Generar cobertura
npm run test:coverage
```

### Tests Incluidos

| Test | Descripción |
|------|-------------|
| `bridge.test.ts` | Tests del puente Android-React |
| `router.test.tsx` | Tests del router hash-based |
| `useGatewayStatus.test.tsx` | Tests del hook de estado del gateway |
| `App.test.tsx` | Tests de componente principal |

---

## 🎭 Tests E2E (Playwright)

### Configuración

Los tests E2E usan Playwright para probar la aplicación en navegadores reales.

### Ejecutar Tests E2E

```bash
cd android/www

# Instalar Playwright y navegadores
npx playwright install

# Ejecutar tests E2E
npm run test:e2e

# Ejecutar en modo debug
npx playwright test --debug

# Ejecutar solo en Chromium
npx playwright test --project=chromium

# Generar reporte HTML
npx playwright show-report
```

### Tests E2E Incluidos

| Test | Descripción |
|------|-------------|
| `app.spec.ts` | Tests de carga, navegación, responsive |

### Configuración de Navegadores

```typescript
// playwright.config.ts
projects: [
  { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
  { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  { name: 'Mobile Chrome', use: { ...devices['Pixel 5'] } },
  { name: 'Mobile Safari', use: { ...devices['iPhone 12'] } },
]
```

---

## 🚀 Scripts de Ejecución

### Unix/Linux/macOS

```bash
# Todos los tests
./scripts/run-tests.sh

# Solo Kotlin
./scripts/run-tests.sh -k

# Solo React
./scripts/run-tests.sh -r

# Solo E2E
./scripts/run-tests.sh -e

# Cobertura
./scripts/run-tests.sh -c
```

### Windows

```powershell
# Todos los tests
.\scripts\run-tests.bat

# Solo Kotlin
.\scripts\run-tests.bat -k

# Solo React
.\scripts\run-tests.bat -r

# Solo E2E
.\scripts\run-tests.bat -e
```

---

## 📊 Cobertura

### React/Vitest

```bash
cd android/www
npm run test:coverage
```

Reporte generado en: `android/www/coverage/`

### Kotlin

```bash
cd android
./gradlew :app:jacocoTestReport
```

Reporte generado en: `android/app/build/reports/jacoco/`

---

## 🔧 Configuración CI/CD

### GitHub Actions (Ejemplo)

```yaml
name: Tests

on: [push, pull_request]

jobs:
  kotlin-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Run Kotlin Tests
        run: ./gradlew :app:test

  react-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-node@v3
        with:
          node-version: '20'
      - name: Install dependencies
        run: cd android/www && npm ci
      - name: Run React Tests
        run: cd android/www && npm test -- --run
      - name: Run E2E Tests
        run: cd android/www && npx playwright test --project=chromium
```

---

## 💡 Mejores Prácticas

1. **Tests Unitarios**: Cubrir lógica de negocio, validaciones, edge cases
2. **Tests de Integración**: Verificar interacción entre componentes
3. **Tests E2E**: Flujos críticos de usuario
4. **Mocking**: Usar MockK (Kotlin) y MSW (React) para aislar tests
5. **Cobertura**: Mantener cobertura > 70% para código crítico

---

## 🐛 Debugging

### Kotlin
```bash
# Debug con logs detallados
./gradlew :app:test --info --debug
```

### React
```bash
# Debug en modo UI
npm run test:ui

# Debug con console
npm test -- --reporter=verbose
```

### Playwright
```bash
# Modo debug
npx playwright test --debug

# Solo un test con navegador visible
npx playwright test --headed
```

---

## 📚 Recursos

- [JUnit5 Documentation](https://junit.org/junit5/docs/current/user-guide/)
- [Kotest Documentation](https://kotest.io/docs/)
- [MockK Guide](https://mockk.io/)
- [Vitest Documentation](https://vitest.dev/)
- [Playwright Documentation](https://playwright.dev/)
- [React Testing Library](https://testing-library.com/docs/react-testing-library/intro/)
