/**
 * src/lib/api.ts
 * Re-exports desde la nueva capa de API tipada.
 * Mantiene compatibilidad con código existente que importa desde aquí.
 */

export * from '../api/gateway'
export * from '../api/client'

// ── Legacy compat: objeto api.* usado en código antiguo ──────────────────────
import { getHealth, getLogs, getSkills, toggleSkill, sendChat, getMemory, deleteMemory, getConfig, updateConfig } from '../api/gateway'

export const api = {
  getHealth,
  getLogs,
  getSkills,
  toggleSkill,
  chat: sendChat,
  getMemory,
  deleteMemory,
  getConfig,
  updateConfig,
}
