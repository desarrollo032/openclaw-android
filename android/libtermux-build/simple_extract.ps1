# Script simple para extraer librerías de los AARs
# Sin complicaciones - solo extrae y muestra

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Extraer librerias de AARs" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$ProjectDir = Split-Path -Parent $PSScriptRoot
$AarDir = Join-Path $ProjectDir "app\libs"
$OutputDir = Join-Path $PSScriptRoot "extracted"

Write-Host "AARs en: $AarDir" -ForegroundColor White
Write-Host "Extraer en: $OutputDir" -ForegroundColor White
Write-Host ""

# Limpiar salida anterior
if (Test-Path $OutputDir) {
    Remove-Item $OutputDir -Recurse -Force
}
New-Item -ItemType Directory -Path $OutputDir | Out-Null

# Encontrar AARs
$Aars = Get-ChildItem $AarDir -Filter "*.aar"

if ($Aars.Count -eq 0) {
    Write-Host "ERROR: No hay AARs en $AarDir" -ForegroundColor Red
    exit 1
}

Write-Host "Encontrados $($Aars.Count) AAR(s):" -ForegroundColor Green
foreach ($Aar in $Aars) {
    Write-Host "  - $($Aar.Name)" -ForegroundColor Gray
}
Write-Host ""

# Extraer cada AAR
foreach ($Aar in $Aars) {
    $Name = $Aar.Name.Replace(".aar", "")
    Write-Host "Extrayendo $($Aar.Name)..." -ForegroundColor White
    
    $TempDir = Join-Path $env:TEMP "aarextract_$Name"
    if (Test-Path $TempDir) { Remove-Item $TempDir -Recurse -Force }
    New-Item -ItemType Directory -Path $TempDir | Out-Null
    
    # Extraer ZIP (AAR es ZIP)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($Aar.FullName, $TempDir)
    
    # Copiar librerías .so
    $JniDir = Join-Path $TempDir "jni"
    if (Test-Path $JniDir) {
        $Libs = Get-ChildItem $JniDir -Recurse -Filter "*.so"
        if ($Libs.Count -gt 0) {
            Write-Host "  OK: $($Libs.Count) libreria(s) encontrada(s)" -ForegroundColor Green
            Copy-Item $JniDir -Destination $OutputDir -Recurse
        }
    }
    
    # Limpiar
    Remove-Item $TempDir -Recurse -Force
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Listo!" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Librerias extraidas en:" -ForegroundColor White
Write-Host "  $OutputDir" -ForegroundColor Gray
Write-Host ""
Write-Host "Ahora revisa manualmente si necesitas recompilar!" -ForegroundColor Green
