/**
 * src/api/gateway.ts
 * Endpoints del gateway OpenClaw (HTTP REST).
 * Usa el cliente base con auth automática.
 */

import { apiGet, apiPost, apiDelete } from './client'

// ── Types ─────────────────────────────────────────────────────────────────────

export interface GatewayHealth {
  status: 'ok' | 'error'
  uptime: number       // segundos
  pid: number
  version: string
}

export interface GatewayStatus {
  running: boolean
  uptime: number       // segundos
  memoryMB: number
  port: number
  restartCount?: number
}

export interface LogEntry {
  timestamp: string
  tag: string
  message: string
  level?: 'info' | 'warn' | 'error' | 'debug'
}

export interface GatewayConfig {
  agents?: {
    defaults?: {
      model?: { primary?: string }
      temperature?: number
      contextTokens?: number
    }
  }
  models?: {
    providers?: Record<string, unknown>
  }
}

export interface ModelEntry {
  id: string
  name: string
  provider: string
}

// ── Health & Status ───────────────────────────────────────────────────────────

/**
 * GET /health
 * Comprueba que el gateway responde. Prueba varias rutas por compatibilidad.
 */
export async function getHealth(): Promise<GatewayHealth> {
  // El gateway puede responder en /health o /api/health según versión
  try {
    return await apiGet<GatewayHealth>('/health')
  } catch {
    const fallback = await apiGet<GatewayHealth>('/api/health')
    return fallback
  }
}

/**
 * GET /api/status
 * Estado detallado del proceso gateway.
 */
export async function getStatus(): Promise<GatewayStatus> {
  return apiGet<GatewayStatus>('/api/status')
}

// ── Control ───────────────────────────────────────────────────────────────────

/**
 * POST /api/restart
 * Solicita al gateway que se reinicie. El ForegroundService lo relanza.
 */
export async function restartGateway(): Promise<void> {
  await apiPost('/api/restart', {})
}

// ── Logs ──────────────────────────────────────────────────────────────────────

/**
 * GET /api/logs?lines=N
 * Obtiene las últimas N líneas del log del gateway.
 */
export async function getLogs(lines = 100): Promise<LogEntry[]> {
  const data = await apiGet<LogEntry[] | { logs?: LogEntry[] }>(`/api/logs?lines=${lines}`)
  // El gateway puede retornar array directo o { logs: [] }
  if (Array.isArray(data)) return data
  return (data as { logs?: LogEntry[] }).logs ?? []
}

/**
 * DELETE /api/logs
 * Limpia el log del gateway.
 */
export async function clearLogs(): Promise<void> {
  await apiDelete('/api/logs')
}

// ── Config ────────────────────────────────────────────────────────────────────

export async function getConfig(): Promise<GatewayConfig | null> {
  try {
    return await apiGet<GatewayConfig>('/api/config')
  } catch {
    return null
  }
}

export async function updateConfig(patch: Partial<GatewayConfig>): Promise<GatewayConfig> {
  return apiPost<GatewayConfig>('/api/config', patch)
}

// ── Models ────────────────────────────────────────────────────────────────────

export async function getModels(): Promise<ModelEntry[]> {
  try {
    const data = await apiGet<ModelEntry[] | { models?: ModelEntry[] }>('/api/models')
    if (Array.isArray(data)) return data
    return (data as { models?: ModelEntry[] }).models ?? []
  } catch {
    return []
  }
}

// ── Skills ────────────────────────────────────────────────────────────────────

export async function getSkills(): Promise<unknown[]> {
  try {
    return await apiGet<unknown[]>('/api/skills')
  } catch {
    return []
  }
}

export async function toggleSkill(id: string): Promise<unknown> {
  return apiPost(`/api/skill/${id}/toggle`)
}

// ── Chat ──────────────────────────────────────────────────────────────────────

export interface ChatRequest { message: string; context?: unknown }
export interface ChatResponse { reply: string; [key: string]: unknown }

export async function sendChat(message: string, context?: unknown): Promise<ChatResponse> {
  return apiPost<ChatResponse>('/api/chat', { message, context })
}

// ── Memory ────────────────────────────────────────────────────────────────────

export async function getMemory(): Promise<unknown[]> {
  try { return await apiGet<unknown[]>('/api/memory') }
  catch { return [] }
}

export async function deleteMemory(id: string): Promise<void> {
  await apiDelete(`/api/memory/${id}`)
}
