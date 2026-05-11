@echo off
REM Script para compilar libtermux.so con alineación de 16 KB

echo ========================================
echo   Compilar libtermux.so - 16 KB
echo ========================================
echo.

if "%ANDROID_NDK_HOME%"=="" (
    echo [ERROR] ANDROID_NDK_HOME no definida!
    echo.
    echo Define la variable en PowerShell:
    echo   $env:ANDROID_NDK_HOME = "C:\Users\TRINIDAD\AppData\Local\Android\Sdk\ndk\27.0.12077973"
    echo.
    pause
    exit /b 1
)

echo [OK] NDK: %ANDROID_NDK_HOME%
echo.

cd /d "%~dp0termux-app\terminal-emulator\src\main"

echo [1/2] Ejecutando ndk-build...
"%ANDROID_NDK_HOME%\ndk-build.cmd" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./jni/Android.mk APP_ABI=arm64-v8a APP_PLATFORM=android-24

if errorlevel 1 (
    echo.
    echo [ERROR] Fallo en la compilacion!
    pause
    exit /b 1
)

echo.
echo [2/2] Copiando libreria...
set OUT_DIR=%~dp0compiled_libs
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"
if not exist "%OUT_DIR%\arm64-v8a" mkdir "%OUT_DIR%\arm64-v8a"

copy /Y "libs\arm64-v8a\libtermux.so" "%OUT_DIR%\arm64-v8a\"

echo.
echo ========================================
echo   Compilacion completada!
echo ========================================
echo.
echo Libreria final:
echo   %OUT_DIR%\arm64-v8a\libtermux.so
echo.
echo Copia esta libreria a:
echo   ..\app\src\main\jniLibs\arm64-v8a\
echo.
pause
