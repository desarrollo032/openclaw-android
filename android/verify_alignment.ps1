# Script para verificar alineación de 16 KB en librerías .so
# Para Windows con Android NDK (readelf.exe)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Verificador de alineación 16 KB" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Buscar el Android NDK
$ndkPath = ""
if (Test-Path "$env:LOCALAPPDATA\Android\Sdk\ndk") {
    $ndkVersions = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\ndk" -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($ndkVersions) {
        $ndkPath = $ndkVersions.FullName
    }
}

if (-not $ndkPath) {
    Write-Host "⚠️ No se encontró Android NDK. Por favor, configura la ruta manualmente." -ForegroundColor Yellow
    Write-Host ""
    $ndkPath = Read-Host "Ingresa la ruta del Android NDK (ej: C:\Users\TuUser\AppData\Local\Android\Sdk\ndk\26.1.10909125)"
}

$readelfPath = Join-Path $ndkPath "toolchains\llvm\prebuilt\windows-x86_64\bin\readelf.exe"

if (-not (Test-Path $readelfPath)) {
    Write-Host "❌ No se encontró readelf.exe en $readelfPath" -ForegroundColor Red
    exit 1
}

Write-Host "✓ Usando NDK: $ndkPath" -ForegroundColor Green
Write-Host "✓ readelf: $readelfPath" -ForegroundColor Green
Write-Host ""

# Buscar todas las librerías .so en el proyecto
$libsToCheck = @()

# 1. Buscar en el payload (si está extraído)
$payloadDir = Join-Path $PSScriptRoot "app\src\main\assets\payload-v2.tar.xz"
if (Test-Path $payloadDir) {
    Write-Host "📦 Encontrado payload-v2.tar.xz" -ForegroundColor Cyan
    Write-Host "   (Nota: Para verificar librerías dentro del payload, extrae el archivo primero)" -ForegroundColor Yellow
    Write-Host ""
}

# 2. Verificar AARs (terminal-emulator.aar y terminal-view.aar)
$aars = Get-ChildItem (Join-Path $PSScriptRoot "app\libs") -Filter "*.aar"
if ($aars.Count -gt 0) {
    Write-Host "📦 AARs encontrados:" -ForegroundColor Cyan
    foreach ($aar in $aars) {
        Write-Host "   - $($aar.Name)" -ForegroundColor Gray
        
        # Extraer temporalmente el AAR para verificar las librerías
        $tempDir = Join-Path $env:TEMP "openclaw_aar_check_$([Guid]::NewGuid().ToString('N'))"
        New-Item -ItemType Directory -Path $tempDir | Out-Null
        
        try {
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            [System.IO.Compression.ZipFile]::ExtractToDirectory($aar.FullName, $tempDir)
            
            $jniLibs = Get-ChildItem (Join-Path $tempDir "jni") -Recurse -Filter "*.so" -ErrorAction SilentlyContinue
            if ($jniLibs) {
                Write-Host "     → $($jniLibs.Count) librerías encontradas" -ForegroundColor Gray
                $libsToCheck += $jniLibs
            }
        } catch {
            Write-Host "     ⚠️ No se pudo extraer: $_" -ForegroundColor Yellow
        } finally {
            Remove-Item -Path $tempDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    Write-Host ""
}

# 3. Buscar en el directorio de build (si existe)
$buildDir = Join-Path $PSScriptRoot "app\build"
if (Test-Path $buildDir) {
    $buildLibs = Get-ChildItem $buildDir -Recurse -Filter "*.so" -ErrorAction SilentlyContinue
    if ($buildLibs) {
        Write-Host "🔨 Librerías en build:" -ForegroundColor Cyan
        $libsToCheck += $buildLibs
    }
}

if ($libsToCheck.Count -eq 0) {
    Write-Host "⚠️ No se encontraron librerías .so para verificar" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "💡 Consejo:" -ForegroundColor Cyan
    Write-Host "   1. Compila la app primero (./gradlew assembleDebug)" -ForegroundColor Gray
    Write-Host "   2. Extrae el payload-v2.tar.xz para verificar librerías internas" -ForegroundColor Gray
    Write-Host ""
    exit 0
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Verificando $($libsToCheck.Count) librerías..." -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$okCount = 0
$failCount = 0

foreach ($lib in $libsToCheck) {
    $libName = Split-Path $lib.FullName -Leaf
    Write-Host "🔍 $libName" -ForegroundColor White
    
    try {
        $output = & $readelfPath -l $lib.FullName 2>&1
        $aligned = $true
        $issues = @()
        
        foreach ($line in $output) {
            if ($line -match "LOAD\s+(\w+)\s+(\w+)\s+(\w+)\s+(\w+)") {
                $offset = [Convert]::ToInt64($matches[1], 16)
                $vaddr = [Convert]::ToInt64($matches[2], 16)
                
                # Verificar alineación de 16 KB (16384 bytes)
                if (($offset % 16384) -ne 0) {
                    $aligned = $false
                    $issues += "Offset: 0x$($matches[1]) ($offset bytes) - NO alineado"
                }
                if (($vaddr % 16384) -ne 0) {
                    $aligned = $false
                    $issues += "Vaddr: 0x$($matches[2]) ($vaddr bytes) - NO alineado"
                }
            }
        }
        
        if ($aligned) {
            Write-Host "   ✓ OK - Alineado a 16 KB" -ForegroundColor Green
            $okCount++
        } else {
            Write-Host "   ❌ NO alineado!" -ForegroundColor Red
            foreach ($issue in $issues) {
                Write-Host "      - $issue" -ForegroundColor Red
            }
            $failCount++
        }
    } catch {
        Write-Host "   ⚠️ Error al verificar: $_" -ForegroundColor Yellow
    }
    Write-Host ""
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Resumen:" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "✓ OK: $okCount librerías" -ForegroundColor Green
Write-Host "❌ Fallidas: $failCount librerías" -ForegroundColor $(if ($failCount -eq 0) { 'Gray' } else { 'Red' })
Write-Host ""

if ($failCount -gt 0) {
    Write-Host "========================================" -ForegroundColor Red
    Write-Host "  Acciones necesarias:" -ForegroundColor Red
    Write-Host "========================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "1. Recompilar las librerías con flags:" -ForegroundColor White
    Write-Host "   -Wl,-z,max-page-size=16384" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "2. Usar NDK moderno (r25 o superior)" -ForegroundColor White
    Write-Host ""
    Write-Host "3. Para AARs precompilados:" -ForegroundColor White
    Write-Host "   - Recompilar las librerías del terminal-emulator" -ForegroundColor Yellow
    Write-Host "   - O usar versiones actualizadas del terminal-emulator" -ForegroundColor Yellow
    Write-Host ""
} else {
    Write-Host "🎉 Todas las librerías están correctamente alineadas!" -ForegroundColor Green
    Write-Host ""
}
