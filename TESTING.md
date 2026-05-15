# Guía de testing

Suite de pruebas del proyecto **OpenClaw Android**: tests Kotlin, React y E2E.

---

## Índice

- [Resumen](#resumen)
- [Estructura de tests](#estructura-de-tests)
- [Tests Kotlin (Android)](#tests-kotlin-android)
- [Tests React (frontend)](#tests-react-frontend)
- [Tests E2E (Playwright)](#tests-e2e-playwright)
- [Scripts de ejecución](#scripts-de-ejecución)
- [Cobertura](#cobertura)
- [CI/CD](#cicd)
- [Buenas prácticas](#buenas-prácticas)
- [Debugging](#debugging)
- [Recursos](#recursos)

---

## Resumen

| Capa | Framework | Ubicación |
| --- | --- | --- |
| **Unit Tests Kotlin** | JUnit5 + Kotest + MockK | `android/app/src/test/` |
| **Integration Tests** | Robolectric | `android/app/src/test/` |
| **UI Tests Android** | Espresso | `android/app/src/androidTest/` |
| **Unit Tests React** | Vitest + React Testing Library | `android/www/src/test/` |
| **E2E Tests** | Playwright | `android/www/e2e/` |

---

## Estructura de tests

```text
openclaw-android/
├── android/
│   └── app/
│       └── src/
│           ├── test/                # Tests unitarios Kotlin
│           │   └── java/com/openclaw/
│           │       ├── OpenClawInstallerTest.kt
│           │       ├── AssetDetectorTest.kt
│           │       └── OpenClawExtensionsTest.kt
│           └── androidTest/         # Tests instrumentados
│               └── java/com/openclaw/
│                   ├── MainActivityInstrumentedTest.kt
│                   ├── AndroidBridgeInstrumentedTest.kt
│                   └── GatewayServiceInstrumentedTest.kt
└── android/www/
    ├── src/test/                    # Tests React
    │   ├── setup.ts
    │   ├── bridge.test.ts
    │   ├── router.test.tsx
    │   ├── useGatewayStatus.test.tsx
    │   └── App.test.tsx
    ├── e2e/                         # Tests E2E
    │   └── app.spec.ts
    ├── vitest.config.ts
    └── playwright.config.ts
```

---

## Tests Kotlin (Android)

### Stack

- **JUnit5** — framework base.
- **Kotest** — DSL BDD para tests descriptivos.
- **MockK** — mocking para Kotlin.
- **Robolectric** — tests unitarios con el framework de Android.
- **kotlinx-coroutines-test** — testing de corrutinas.

### Ejecutar

```bash
# Tests unitarios
./gradlew :app:test

# Con reporte detallado
./gradlew :app:test --info

# Tests instrumentados (requiere dispositivo/emulador)
./gradlew :app:connectedAndroidTest
```

### Tests incluidos

| Test | Descripción |
| --- | --- |
| `OpenClawInstallerTest` | Instalación, verificación de payload, directorios. |
| `AssetDetectorTest` | Detección de assets y espacio libre. |
| `OpenClawExtensionsTest` | Extensiones de archivos y extracción de tar. |

---

## Tests React (frontend)

### Stack

- **Vitest** — framework de testing rápido.
- **React Testing Library** — testing de componentes.
- **MSW (Mock Service Worker)** — mock de llamadas a API.
- **jsdom** — entorno DOM.

### Ejecutar

```bash
cd android/www

# Instalar dependencias
pnpm install     # o: npm install

# Modo watch
pnpm test

# Ejecutar una vez
pnpm test -- --run

# UI interactiva
pnpm test:ui

# Cobertura
pnpm test:coverage
```

### Tests incluidos

| Test | Descripción |
| --- | --- |
| `bridge.test.ts` | Puente Android ↔ React. |
| `router.test.tsx` | Router hash-based. |
| `useGatewayStatus.test.tsx` | Hook de estado del gateway. |
| `App.test.tsx` | Componente principal. |

---

## Tests E2E (Playwright)

Los E2E ejercitan la UI en navegadores reales.

### Ejecutar

```bash
cd android/www

# Instalar Playwright y navegadores
npx playwright install

# Ejecutar
pnpm test:e2e

# Modo debug
npx playwright test --debug

# Solo Chromium
npx playwright test --project=chromium

# Reporte HTML
npx playwright show-report
```

### Tests incluidos

| Test | Descripción |
| --- | --- |
| `app.spec.ts` | Carga, navegación, responsive. |

### Configuración de navegadores

```typescript
// playwright.config.ts
projects: [
  { name: 'chromium',      use: { ...devices['Desktop Chrome'] } },
  { name: 'firefox',       use: { ...devices['Desktop Firefox'] } },
  { name: 'webkit',        use: { ...devices['Desktop Safari'] } },
  { name: 'Mobile Chrome', use: { ...devices['Pixel 5'] } },
  { name: 'Mobile Safari', use: { ...devices['iPhone 12'] } },
]
```

---

## Scripts de ejecución

### Unix / Linux / macOS

```bash
./scripts/run-tests.sh        # todos
./scripts/run-tests.sh -k     # solo Kotlin
./scripts/run-tests.sh -r     # solo React
./scripts/run-tests.sh -e     # solo E2E
./scripts/run-tests.sh -c     # cobertura
```

### Windows

```powershell
.\scripts\run-tests.bat       # todos
.\scripts\run-tests.bat -k    # solo Kotlin
.\scripts\run-tests.bat -r    # solo React
.\scripts\run-tests.bat -e    # solo E2E
```

---

## Cobertura

### React / Vitest

```bash
cd android/www
pnpm test:coverage
```

Reporte en: `android/www/coverage/`

### Kotlin

```bash
cd android
./gradlew :app:jacocoTestReport
```

Reporte en: `android/app/build/reports/jacoco/`

---

## CI/CD

Ejemplo de workflow para **GitHub Actions**:

```yaml
name: Tests

on: [push, pull_request]

jobs:
  kotlin-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Run Kotlin Tests
        run: ./gradlew :app:test

  react-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
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

## Buenas prácticas

1. **Tests unitarios** — cubren lógica de negocio, validaciones y edge cases.
2. **Tests de integración** — verifican la interacción entre componentes.
3. **Tests E2E** — flujos críticos de usuario.
4. **Mocking** — `MockK` (Kotlin) y `MSW` (React) para aislar tests.
5. **Cobertura** — mantener `> 70 %` para código crítico.

---

## Debugging

### Kotlin

```bash
./gradlew :app:test --info --debug
```

### React

```bash
pnpm test:ui                        # modo UI
pnpm test -- --reporter=verbose     # verbose
```

### Playwright

```bash
npx playwright test --debug         # modo debug
npx playwright test --headed        # navegador visible
```

---

## Recursos

- [JUnit5](https://junit.org/junit5/docs/current/user-guide/)
- [Kotest](https://kotest.io/docs/)
- [MockK](https://mockk.io/)
- [Vitest](https://vitest.dev/)
- [Playwright](https://playwright.dev/)
- [React Testing Library](https://testing-library.com/docs/react-testing-library/intro/)
