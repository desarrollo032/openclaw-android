# Script para extraer y verificar librerías de los AARs
# Verifica alineación de 16 KB en las librerías existentes

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Extraer y verificar AARs" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# ─────────────────────────────────────────────────────────────────
# Paso 1: Configurar rutas
# ─────────────────────────────────────────────────────────────────

$ProjectDir = Split-Path -Parent $PSScriptRoot
$AarDir = Join-Path $ProjectDir "app\libs"
$TempDir = Join-Path $env:TEMP "openclaw_aar_extract_$([Guid]::NewGuid().ToString('N'))"
$OutputDir = Join-Path $PSScriptRoot "extracted_libs"

Write-Host "📦 AARs directory: $AarDir" -ForegroundColor White
Write-Host "📂 Temp dir: $TempDir" -ForegroundColor White
Write-Host ""

# ─────────────────────────────────────────────────────────────────
# Paso 2: Verificar que existan los AARs
# ─────────────────────────────────────────────────────────────────

$Aars = Get-ChildItem $AarDir -Filter "*.aar"

if ($Aars.Count -eq 0) {
    Write-Host "❌ No se encontraron AARs en $AarDir" -ForegroundColor Red
    Write-Host ""
    Write-Host "Buscando en: $AarDir" -ForegroundColor Yellow
    exit 1
}

Write-Host "✅ Encontrados $($Aars.Count) AAR(s):" -ForegroundColor Green
foreach ($Aar in $Aars) {
    Write-Host "   - $($Aar.Name)" -ForegroundColor Gray
}
Write-Host ""

# ─────────────────────────────────────────────────────────────────
# Paso 3: Encontrar readelf en el Android SDK
# ─────────────────────────────────────────────────────────────────

Write-Host "🔍 Buscando readelf..." -ForegroundColor White

$ReadElf = $null
$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk"

# Buscar en NDK
if (Test-Path $AndroidSdk) {
    $ReadElf = Get-ChildItem -Path $AndroidSdk -Filter "readelf.exe" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
}

if (-not $ReadElf) {
    Write-Host "⚠️ No se encontró readelf.exe" -ForegroundColor Yellow
    Write-Host "   No se podrá verificar la alineación, pero se extraerán las librerías." -ForegroundColor Yellow
    Write-Host ""
} else {
    Write-Host "✅ readelf: $ReadElf" -ForegroundColor Green
    Write-Host ""
}

# ─────────────────────────────────────────────────────────────────
# Paso 4: Extraer cada AAR
# ─────────────────────────────────────────────────────────────────

New-Item -ItemType Directory -Path $TempDir -Force | Out-Null
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$AllLibs = @()

foreach ($Aar in $Aars) {
    $AarName = $Aar.Name.Replace(".aar", "")
    $ExtractDir = Join-Path $TempDir $AarName
    
    Write-Host "────────────────────────────────────────────────" -ForegroundColor Cyan
    Write-Host "📦 Extrayendo: $($Aar.Name)" -ForegroundColor White
    Write-Host "────────────────────────────────────────────────" -ForegroundColor Cyan
    
    New-Item -ItemType Directory -Path $ExtractDir -Force | Out-Null
    
    # Extraer el AAR (es un ZIP)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($Aar.FullName, $ExtractDir)
    
    # Buscar librerías .so
    $JniLibsDir = Join-Path $ExtractDir "jni"
    if (Test-Path $JniLibsDir) {
        $Libs = Get-ChildItem $JniLibsDir -Recurse -Filter "*.so"
        
        if ($Libs.Count -gt 0) {
            Write-Host "✅ Encontradas $($Libs.Count) librería(s):" -ForegroundColor Green
            
            foreach ($Lib in $Libs) {
                $RelPath = $Lib.FullName.Substring($ExtractDir.Length + 1)
                Write-Host "   - $RelPath" -ForegroundColor Gray
                
                # Copiar al directorio de salida
                $DestDir = Join-Path $OutputDir (Split-Path -Parent $RelPath)
                New-Item -ItemType Directory -Path $DestDir -Force | Out-Null
                Copy-Item $Lib.FullName -Destination (Join-Path $DestDir $Lib.Name) -Force
                
                $AllLibs += $Lib
            }
        }
    }
    Write-Host ""
}

# ─────────────────────────────────────────────────────────────────
# Paso 5: Verificar alineación de cada librería (si tenemos readelf)
# ─────────────────────────────────────────────────────────────────

if ($ReadElf -and $AllLibs.Count -gt 0) {
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "  Verificación de alineación (16 KB)" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host ""
    
    $OkCount = 0
    $FailCount = 0
    
    foreach ($Lib in $AllLibs) {
        $LibName = $Lib.Name
        Write-Host "🔍 $LibName" -ForegroundColor White
        
        try {
            $Output = & $ReadElf -l $Lib.FullName 2>&1
            $Aligned = $true
            $Issues = @()
            
            foreach ($Line in $Output) {
                if ($Line -match "LOAD\s+(\w+)\s+(\w+)\s+(\w+)\s+(\w+)") {
                    $OffsetHex = $matches[1]
                    $VaddrHex = $matches[2]
                    
                    $Offset = [Convert]::ToInt64($OffsetHex, 16)
                    $Vaddr = [Convert]::ToInt64($VaddrHex, 16)
                    
                    if (($Offset % 16384) -ne 0) {
                        $Aligned = $false
                        $Issues += "Offset: 0x$OffsetHex ($Offset bytes) - NO alineado"
                    }
                    if (($Vaddr % 16384) -ne 0) {
                        $Aligned = $false
                        $Issues += "Vaddr: 0x$VaddrHex ($Vaddr bytes) - NO alineado"
                    }
                }
            }
            
            if ($Aligned) {
                Write-Host "   ✅ OK - Alineado a 16 KB" -ForegroundColor Green
                $OkCount++
            } else {
                Write-Host "   ❌ NO alineado!" -ForegroundColor Red
                foreach ($Issue in $Issues) {
                    Write-Host "      - $Issue" -ForegroundColor Red
                }
                $FailCount++
            }
        } catch {
            Write-Host "   ⚠️ Error al verificar: $_" -ForegroundColor Yellow
        }
        Write-Host ""
    }
    
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "  Resumen:" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "✅ OK: $OkCount librerías" -ForegroundColor Green
    Write-Host "❌ Fallidas: $FailCount librerías" -ForegroundColor $(if ($FailCount -eq 0) { 'Gray' } else { 'Red' })
    Write-Host ""
}

# ─────────────────────────────────────────────────────────────────
# Paso 6: Instrucciones finales
# ─────────────────────────────────────────────────────────────────

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Extracción completada!" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "📂 Librerías extraídas en:" -ForegroundColor White
Write-Host "   $OutputDir" -ForegroundColor Gray
Write-Host ""
Write-Host "💡 Para usar en tu app:" -ForegroundColor White
Write-Host "   Copia las librerías de:" -ForegroundColor Gray
Write-Host "   $OutputDir\jni\arm64-v8a\" -ForegroundColor Gray
Write-Host ""
Write-Host "   A:" -ForegroundColor Gray
Write-Host "   $ProjectDir\app\src\main\jniLibs\arm64-v8a\" -ForegroundColor Gray
Write-Host ""
Write-Host "Y vuelve a compilar tu app!" -ForegroundColor Green
Write-Host ""

# Limpiar directorio temporal
Remove-Item -Path $TempDir -Recurse -Force -ErrorAction SilentlyContinue
