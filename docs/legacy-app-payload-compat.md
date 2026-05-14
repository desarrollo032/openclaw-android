# Compatibilidad: `app_payload/bin/node` (Android)

## Problema

En instalaciones antiguas, algunos wrappers ejecutan esta ruta legacy:

`/data/user/0/<package>/files/app_payload/bin/node`

Si ese archivo no existe o no tiene permiso de ejecución, aparece:

`Permission denied`

## Ajuste adicional sobre Node nativo (`jniLibs`)

Si `node.real` no está disponible en el payload, el instalador ahora reutiliza `libnode.so`
que ya esté presente en `nativeLibraryDir` (copiado desde `jniLibs` por Android en runtime),
evita depender exclusivamente de `payload/bin/node.real`.

## Solución implementada

Durante `deployScripts()` se generan wrappers de compatibilidad en:

- `files/app_payload/bin/node`
- `files/app_payload/bin/openclaw`

Ambos delegan a los wrappers actuales en `payload/bin/*` y se marcan ejecutables.

## Impacto

- No cambia el flujo principal (se sigue usando `payload/bin`).
- Evita errores en rutas legacy persistidas por instalaciones previas.
- Mejora tolerancia cuando el origen efectivo de Node proviene de librerías nativas empaquetadas.
