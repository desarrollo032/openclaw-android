/**
 * i18n Validation Script
 * Verifica que todos los archivos JSON de idioma en src/i18n/locales/
 * tengan exactamente las mismas claves de traduccion.
 *
 * Uso: node scripts/i18n-check.mjs
 */

import { readFileSync, readdirSync, existsSync } from 'fs'
import { join, dirname } from 'path'
import { fileURLToPath } from 'url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const LOCALES_DIR = join(__dirname, '..', 'src', 'i18n', 'locales')

if (!existsSync(LOCALES_DIR)) {
  console.error('No se encontro el directorio de locales:', LOCALES_DIR)
  process.exit(1)
}

const files = readdirSync(LOCALES_DIR).filter(f => f.endsWith('.json'))

if (files.length === 0) {
  console.error('No se encontraron archivos JSON en:', LOCALES_DIR)
  process.exit(1)
}

console.log('Idiomas detectados:', files.join(', '), '\n')

// Cargar todos los archivos
const locales = {}
for (const file of files) {
  const code = file.replace('.json', '')
  const content = readFileSync(join(LOCALES_DIR, file), 'utf-8')
  try {
    locales[code] = JSON.parse(content)
    const keys = Object.keys(locales[code])
    console.log('  [' + code + '] ' + keys.length + ' claves')
  } catch (err) {
    console.error('  [' + code + '] Error al parsear JSON:', err.message)
    process.exit(1)
  }
}

console.log('')

// Usar el primer idioma como referencia
const codes = Object.keys(locales)
const refCode = codes[0]
const refKeys = new Set(Object.keys(locales[refCode]))

let hasErrors = false

// Verificar que todos tengan las mismas claves
for (const code of codes) {
  const keys = new Set(Object.keys(locales[code]))

  // Claves que faltan en este idioma
  const missing = []
  for (const key of refKeys) {
    if (!keys.has(key)) missing.push(key)
  }

  // Claves extra en este idioma
  const extra = []
  for (const key of keys) {
    if (!refKeys.has(key)) extra.push(key)
  }

  if (missing.length > 0) {
    hasErrors = true
    console.log('  [FALTAN en ' + code + '] ' + missing.length + ' clave(s):')
    missing.forEach(k => console.log('       - ' + k))
  }

  if (extra.length > 0) {
    hasErrors = true
    console.log('  [EXTRAS en ' + code + '] ' + extra.length + ' clave(s):')
    extra.forEach(k => console.log('       - ' + k))
  }

  if (missing.length === 0 && extra.length === 0) {
    console.log('  [' + code + '] todas las claves coinciden')
  }
}

console.log('')

if (hasErrors) {
  console.error('Se encontraron diferencias entre los archivos de idioma.')
  process.exit(1)
} else {
  console.log('Todos los idiomas (' + codes.length + ') tienen las mismas ' + refKeys.size + ' claves de traduccion.')
}
