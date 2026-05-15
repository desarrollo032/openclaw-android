# Compatibilidad legacy de `app_payload/bin/node`

Notas sobre soporte de instalaciones antiguas que invocan rutas legacy.

---

## Índice

- [Problema](#problema)
- [Ajuste sobre Node nativo (`jniLibs`)](#ajuste-sobre-node-nativo-jnilibs)
- [Solución implementada](#solución-implementada)
- [Impacto](#impacto)

---

## Problema

En instalaciones antiguas, algunos wrappers ejecutan esta ruta legacy:

```
/data/user/0/<package>/files/app_payload/bin/node
```

Si ese archivo no existe o no tiene permiso de ejecución, aparece:

```
Permission denied
```

---

## Ajuste sobre Node nativo (`jniLibs`)

Si `node.real` no está disponible en el payload, el instalador reutiliza `libnode.so` ya presente en `nativeLibraryDir` (copiado desde `jniLibs` por Android en runtime). Esto evita depender exclusivamente de `payload/bin/node.real`.

---

## Solución implementada

Durante `deployScripts()` se generan **wrappers de compatibilidad** en:

- `files/app_payload/bin/node`
- `files/app_payload/bin/openclaw`

Ambos delegan a los wrappers actuales en `payload/bin/*` y se marcan ejecutables.

---

## Impacto

- No cambia el flujo principal (sigue usando `payload/bin`).
- Evita errores en rutas legacy persistidas por instalaciones previas.
- Mejora la tolerancia cuando el origen efectivo de Node proviene de librerías nativas empaquetadas.
