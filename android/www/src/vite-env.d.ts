/// <reference types="vite/client" />

/**
 * Variables de entorno expuestas a través de Vite (import.meta.env).
 * Sólo las que empiezan con VITE_ se inyectan al bundle.
 */
interface ImportMetaEnv {
  /** Host del gateway OpenClaw. Default: 127.0.0.1 */
  readonly VITE_GATEWAY_HOST?: string
  /** Puerto del gateway OpenClaw. Default: 18789 */
  readonly VITE_GATEWAY_PORT?: string
  /** Si está presente, sobrescribe el path WS del PTY (default: /terminal). */
  readonly VITE_TERMINAL_WS_PATH?: string
  /** Activa logs de bridge calls en consola (true/false). Default: false. */
  readonly VITE_DEBUG_BRIDGE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
