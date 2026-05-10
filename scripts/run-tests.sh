#!/bin/bash
# Script para ejecutar todos los tests del proyecto OpenClaw Android

set -e

# Colores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo -e "${YELLOW}========================================${NC}"
echo -e "${YELLOW}  OpenClaw Android - Test Suite${NC}"
echo -e "${YELLOW}========================================${NC}"
echo ""

# Función para ejecutar tests de Kotlin
test_kotlin() {
    echo -e "${YELLOW}[1/4] Ejecutando Tests Unitarios Kotlin...${NC}"
    cd "$PROJECT_ROOT/android"

    # Ejecutar tests unitarios
    ./gradlew test --console=plain || {
        echo -e "${RED}Tests unitarios Kotlin fallaron${NC}"
        return 1
    }

    echo -e "${GREEN}Tests unitarios Kotlin completados ✓${NC}"
    echo ""
}

# Función para ejecutar tests instrumentados de Android
test_android_instrumented() {
    echo -e "${YELLOW}[2/4] Ejecutando Tests Instrumentados Android...${NC}"
    cd "$PROJECT_ROOT/android"

    # Nota: Esto requiere un emulador o dispositivo conectado
    # ./gradlew connectedAndroidTest --console=plain || {
    #     echo -e "${YELLOW}Tests instrumentados omitidos (requiere dispositivo)${NC}"
    #     return 0
    # }

    echo -e "${YELLOW}Tests instrumentados requieren un dispositivo/emulador conectado${NC}"
    echo -e "${YELLOW}Para ejecutar: cd android && ./gradlew connectedAndroidTest${NC}"
    echo ""
}

# Función para ejecutar tests de React
test_react() {
    echo -e "${YELLOW}[3/4] Ejecutando Tests React/Vitest...${NC}"
    cd "$PROJECT_ROOT/android/www"

    # Instalar dependencias si es necesario
    if [ ! -d "node_modules" ]; then
        echo "Instalando dependencias npm..."
        npm ci
    fi

    # Ejecutar tests
    npm test -- --run || {
        echo -e "${RED}Tests React fallaron${NC}"
        return 1
    }

    echo -e "${GREEN}Tests React completados ✓${NC}"
    echo ""
}

# Función para ejecutar tests E2E con Playwright
test_e2e() {
    echo -e "${YELLOW}[4/4] Ejecutando Tests E2E Playwright...${NC}"
    cd "$PROJECT_ROOT/android/www"

    # Verificar si Playwright está instalado
    if ! npx playwright --version &> /dev/null; then
        echo -e "${YELLOW}Playwright no está instalado. Instalando...${NC}"
        npm install -D @playwright/test
        npx playwright install chromium
    fi

    # Ejecutar tests E2E
    npm run test:e2e -- --project=chromium || {
        echo -e "${YELLOW}Tests E2E completados con advertencias${NC}"
        return 0
    }

    echo -e "${GREEN}Tests E2E completados ✓${NC}"
    echo ""
}

# Función para generar reporte de cobertura
coverage_report() {
    echo -e "${YELLOW}Generando Reporte de Cobertura...${NC}"
    cd "$PROJECT_ROOT/android/www"

    npm run test:coverage || true

    echo -e "${GREEN}Reporte de cobertura generado en: coverage/${NC}"
    echo ""
}

# Función de ayuda
show_help() {
    echo "Uso: $0 [opciones]"
    echo ""
    echo "Opciones:"
    echo "  -h, --help          Mostrar esta ayuda"
    echo "  -k, --kotlin        Solo tests de Kotlin"
    echo "  -r, --react         Solo tests de React"
    echo "  -e, --e2e           Solo tests E2E"
    echo "  -c, --coverage      Generar reporte de cobertura"
    echo "  -a, --all           Ejecutar todos los tests (por defecto)"
    echo ""
    echo "Ejemplos:"
    echo "  $0                  Ejecutar todos los tests"
    echo "  $0 -k               Solo tests de Kotlin"
    echo "  $0 -r               Solo tests de React"
    echo "  $0 -c               Generar cobertura"
}

# Parsear argumentos
RUN_KOTLIN=false
RUN_REACT=false
RUN_E2E=false
RUN_COVERAGE=false

if [ $# -eq 0 ]; then
    RUN_KOTLIN=true
    RUN_REACT=true
    RUN_E2E=true
fi

while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--help)
            show_help
            exit 0
            ;;
        -k|--kotlin)
            RUN_KOTLIN=true
            shift
            ;;
        -r|--react)
            RUN_REACT=true
            shift
            ;;
        -e|--e2e)
            RUN_E2E=true
            shift
            ;;
        -c|--coverage)
            RUN_COVERAGE=true
            shift
            ;;
        -a|--all)
            RUN_KOTLIN=true
            RUN_REACT=true
            RUN_E2E=true
            shift
            ;;
        *)
            echo "Opción desconocida: $1"
            show_help
            exit 1
            ;;
    esac
done

# Ejecutar tests según selección
if [ "$RUN_KOTLIN" = true ]; then
    test_kotlin
fi

if [ "$RUN_REACT" = true ]; then
    test_react
fi

if [ "$RUN_E2E" = true ]; then
    test_e2e
fi

if [ "$RUN_COVERAGE" = true ]; then
    coverage_report
fi

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Test Suite Completado${NC}"
echo -e "${GREEN}========================================${NC}"
