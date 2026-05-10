@echo off
REM Script para ejecutar todos los tests del proyecto OpenClaw Android en Windows

setlocal EnableDelayedExpansion

echo ========================================
echo   OpenClaw Android - Test Suite
echo ========================================
echo.

set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%.."

REM Verificar argumentos
if "%~1"=="-h" goto :help
if "%~1"=="--help" goto :help
if "%~1"=="-k" goto :kotlin_only
if "%~1"=="--kotlin" goto :kotlin_only
if "%~1"=="-r" goto :react_only
if "%~1"=="--react" goto :react_only
if "%~1"=="-e" goto :e2e_only
if "%~1"=="--e2e" goto :e2e_only
if "%~1"=="-c" goto :coverage_only
if "%~1"=="--coverage" goto :coverage_only

goto :run_all

:help
echo Uso: %0 [opciones]
echo.
echo Opciones:
echo   -h, --help          Mostrar esta ayuda
echo   -k, --kotlin        Solo tests de Kotlin
echo   -r, --react         Solo tests de React
echo   -e, --e2e           Solo tests E2E
echo   -c, --coverage      Generar reporte de cobertura
echo.
echo Ejemplos:
echo   %0                  Ejecutar todos los tests
echo   %0 -k               Solo tests de Kotlin
echo   %0 -r               Solo tests de React
exit /b 0

:kotlin_only
call :test_kotlin
exit /b %errorlevel%

:react_only
call :test_react
exit /b %errorlevel%

:e2e_only
call :test_e2e
exit /b %errorlevel%

:coverage_only
call :coverage_report
exit /b %errorlevel%

:run_all
call :test_kotlin
if errorlevel 1 exit /b 1
call :test_react
if errorlevel 1 exit /b 1
call :test_e2e
exit /b %errorlevel%

:test_kotlin
echo [1/3] Ejecutando Tests Unitarios Kotlin...
cd /d "%PROJECT_ROOT%\android"

REM Ejecutar tests unitarios con Gradle
if exist "gradlew.bat" (
    call gradlew.bat test --console=plain
) else (
    echo ERROR: No se encuentra Gradle wrapper
    exit /b 1
)

if errorlevel 1 (
    echo Tests unitarios Kotlin fallaron
    exit /b 1
)
echo Tests unitarios Kotlin completados ^
echo.
exit /b 0

:test_react
echo [2/3] Ejecutando Tests React/Vitest...
cd /d "%PROJECT_ROOT%\android\www"

REM Instalar dependencias si es necesario
if not exist "node_modules" (
    echo Instalando dependencias npm...
    call npm ci
)

REM Ejecutar tests
if exist "package.json" (
    call npm test -- --run
) else (
    echo ERROR: No se encuentra package.json
    exit /b 1
)

if errorlevel 1 (
    echo Tests React fallaron
    exit /b 1
)
echo Tests React completados ^
echo.
exit /b 0

:test_e2e
echo [3/3] Ejecutando Tests E2E Playwright...
cd /d "%PROJECT_ROOT%\android\www"

REM Verificar si Playwright está instalado
npx playwright --version >nul 2>&1
if errorlevel 1 (
    echo Playwright no esta instalado. Instalando...
    call npm install -D @playwright/test
    call npx playwright install chromium
)

REM Ejecutar tests E2E
if exist "package.json" (
    call npm run test:e2e -- --project=chromium
) else (
    echo WARNING: No se pudo ejecutar tests E2E
    exit /b 0
)

echo Tests E2E completados ^
echo.
exit /b 0

:coverage_report
echo Generando Reporte de Cobertura...
cd /d "%PROJECT_ROOT%\android\www"

if exist "package.json" (
    call npm run test:coverage
) else (
    echo WARNING: No se pudo generar cobertura
)

echo Reporte de cobertura generado en: coverage\nexit /b 0
