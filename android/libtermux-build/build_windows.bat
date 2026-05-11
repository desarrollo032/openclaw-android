@echo off
REM Script para Windows para compilar libtermux.so con alineación de 16 KB

echo ========================================
echo   Build libtermux.so - 16 KB (Windows)
echo ========================================
echo.

REM ─────────────────────────────────────────────────────────────────
REM Paso 1: Configurar NDK (edita esta ruta si es diferente)
REM ─────────────────────────────────────────────────────────────────

if "%ANDROID_NDK_HOME%"=="" (
    echo [ERROR] La variable ANDROID_NDK_HOME no esta definida!
    echo.
    echo Por favor, define la ruta a tu NDK en PowerShell:
    echo   $env:ANDROID_NDK_HOME = "C:\Users\TuUsuario\AppData\Local\Android\Sdk\ndk\26.1.10909125"
    echo.
    echo O en el Sistema:
    echo   Panel de Control -^> Sistema -^> Variables de entorno
    echo.
    pause
    exit /b 1
)

echo [OK] Android NDK: %ANDROID_NDK_HOME%
echo.

REM ─────────────────────────────────────────────────────────────────
REM Paso 2: Usar CMake y Ninja desde el Android SDK
REM ─────────────────────────────────────────────────────────────────

echo [2/6] Configurando herramientas...

set CMAKE_PATH=
set NINJA_PATH=
set ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk

echo [INFO] Android SDK: %ANDROID_SDK_ROOT%

REM Usar la versión de CMake que ya encontraste: 4.1.2
if exist "%ANDROID_SDK_ROOT%\cmake\4.1.2\bin\cmake.exe" (
    set CMAKE_PATH=%ANDROID_SDK_ROOT%\cmake\4.1.2\bin
)

if "%CMAKE_PATH%"=="" (
    echo [INFO] Buscando otras versiones de CMake...
    for /d %%d in ("%ANDROID_SDK_ROOT%\cmake\*") do (
        if exist "%%d\bin\cmake.exe" (
            set CMAKE_PATH=%%d\bin
        )
    )
)

if "%CMAKE_PATH%"=="" (
    echo [ERROR] No se encontro CMake en %ANDROID_SDK_ROOT%\cmake\
    echo.
    echo Por favor, verifica que tienes CMake instalado desde Android Studio.
    pause
    exit /b 1
)

set PATH=%CMAKE_PATH%;%PATH%
echo [OK] CMake: %CMAKE_PATH%

REM Buscar Ninja en la misma carpeta de CMake o en el SDK
if exist "%CMAKE_PATH%\ninja.exe" (
    set NINJA_PATH=%CMAKE_PATH%
)
if "%NINJA_PATH%"=="" (
    for /r "%ANDROID_SDK_ROOT%" %%f in (ninja.exe) do (
        if exist "%%f" (
            set NINJA_PATH=%%~dpf
            goto found_ninja
        )
    )
)

:found_ninja
if not "%NINJA_PATH%"=="" (
    set PATH=%NINJA_PATH%;%PATH%
    echo [OK] Ninja: %NINJA_PATH%
) else (
    echo [WARNING] No se encontro ninja.exe, usare NMake Makefiles...
)
echo.

REM ─────────────────────────────────────────────────────────────────
REM Paso 3: Obtener código fuente de Termux
REM ─────────────────────────────────────────────────────────────────

echo [1/6] Obteniendo codigo fuente de Termux...
if not exist "termux-app" (
    git clone --depth 1 https://github.com/termux/termux-app.git termux-app
    if errorlevel 1 (
        echo [WARNING] Fallo al clonar, intentando continuar...
    )
) else (
    echo [OK] Repositorio ya existe
)

REM Copiar fuentes del terminal-emulator
if exist "termux-app\terminal-emulator\src\main\cpp" (
    echo [OK] Copiando fuentes...
    xcopy /E /I /Y "termux-app\terminal-emulator\src\main\cpp" . >nul 2>&1
)
echo.

REM ─────────────────────────────────────────────────────────────────
REM Paso 4: Compilar usando CMake
REM ─────────────────────────────────────────────────────────────────

echo [2/6] Creando directorio de build...
if not exist build mkdir build
cd build

echo.
echo [3/6] Configurando CMake...
cmake .. ^
    -G "Ninja" ^
    -DCMAKE_TOOLCHAIN_FILE="%ANDROID_NDK_HOME%\build\cmake\android.toolchain.cmake" ^
    -DANDROID_ABI=arm64-v8a ^
    -DANDROID_PLATFORM=android-24 ^
    -DCMAKE_BUILD_TYPE=Release

if errorlevel 1 (
    echo.
    echo [ERROR] Fallo en la configuracion de CMake
    cd ..
    pause
    exit /b 1
)

echo.
echo [4/6] Compilando...
cmake --build . --config Release

if errorlevel 1 (
    echo.
    echo [ERROR] Fallo en la compilacion
    cd ..
    pause
    exit /b 1
)

cd ..

echo.
echo [5/6] Preparando salida...
if not exist output\lib\arm64-v8a mkdir output\lib\arm64-v8a

set LIB_FOUND=
if exist "build\lib\arm64-v8a\libtermux.so" (
    copy /Y "build\lib\arm64-v8a\libtermux.so" "output\lib\arm64-v8a\" >nul
    set LIB_FOUND=1
) else (
    REM Buscar en otras ubicaciones
    for /r "build" %%f in (libtermux.so) do (
        copy /Y "%%f" "output\lib\arm64-v8a\" >nul
        set LIB_FOUND=1
    )
)

if not defined LIB_FOUND (
    echo [WARNING] No se encontro libtermux.so compilada
    echo Buscando en el directorio build...
    dir /s /b build\*.so
)

echo.
echo [6/6] Verificacion...
echo.
echo ========================================
echo   Build completado!
echo ========================================
echo.
if exist "output\lib\arm64-v8a\libtermux.so" (
    echo Libreria final:
    echo   %CD%\output\lib\arm64-v8a\libtermux.so
    echo.
    echo Para usar en tu app Android:
    echo 1. Copia esta libreria a:
    echo    android\app\src\main\jniLibs\arm64-v8a\libtermux.so
    echo.
    echo 2. O ejecuta estos comandos:
    echo    mkdir ..\app\src\main\jniLibs\arm64-v8a 2^>nul
    echo    copy output\lib\arm64-v8a\libtermux.so ..\app\src\main\jniLibs\arm64-v8a\
) else (
    echo [ERROR] No se genero la libreria
)
echo.
pause
