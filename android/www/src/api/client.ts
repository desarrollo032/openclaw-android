/**
 * src/api/client.ts
 * HTTP client base con autenticación automática via window.__OPENCLAW_TOKEN.
 * REGLA: el token nunca aparece en logs, errores ni console.log.
 */

import { getToken } from '../utils/androidBridge'

export const BASE_URL = 'http://127.0.0.1:18789'
const DEFAULT_TIMEOUT = 5000

// ── Custom errors ─────────────────────────────────────────────────────────────

export class OpenClawNetworkError extends Error {
  constructor(message: string, public readonly endpoint: string) {
    super(message)
    this.name = 'OpenClawNetworkError'
  }
}

export class OpenClawAuthError extends Error {
  constructor(public readonly endpoint: string) {
    super('Authentication failed — token may be invalid or expired')
    this.name = 'OpenClawAuthError'
  }
}

export class OpenClawGatewayError extends Error {
  constructor(message: string, public readonly statusCode: number, public readonly endpoint: string) {
    super(message)
    this.name = 'OpenClawGatewayError'
  }
}

// ── Core fetch ────────────────────────────────────────────────────────────────

export interface FetchOptions extends Omit<RequestInit, 'signal'> {
  timeoutMs?: number
}

/**
 * Función base para todas las llamadas HTTP al gateway.
 * - Añade Authorization: Bearer <token> automáticamente
 * - Gestiona timeout con AbortController
 * - Lanza errores tipados según el status HTTP
 */
export async function apiFetch<T = unknown>(
  endpoint: string,
  options: FetchOptions = {}
): Promise<T> {
  const { timeoutMs = DEFAULT_TIMEOUT, headers: extraHeaders, ...rest } = options

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)

  const token = getToken()
  const url = `${BASE_URL}${endpoint}`

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(extraHeaders as Record<string, string> ?? {}),
  }
  // Solo añadir Authorization si hay token (evita header vacío)
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }

  try {
    const res = await fetch(url, {
      ...rest,
      headers,
      signal: controller.signal,
    })

    if (res.status === 401 || res.status === 403) {
      throw new OpenClawAuthError(endpoint)
    }

    if (!res.ok) {
      let msg = `Gateway error ${res.status}`
      try {
        const body = await res.json() as { message?: string; error?: string }
        msg = body.message ?? body.error ?? msg
      } catch { /* body no es JSON */ }
      throw new OpenClawGatewayError(msg, res.status, endpoint)
    }

    // 204 No Content
    if (res.status === 204) return undefined as unknown as T

    return await res.json() as T
  } catch (err) {
    if (err instanceof OpenClawAuthError || err instanceof OpenClawGatewayError) throw err
    if ((err as Error).name === 'AbortError') {
      throw new OpenClawNetworkError(`Request timeout after ${timeoutMs}ms`, endpoint)
    }
    throw new OpenClawNetworkError(
      `Network error: ${(err as Error).message ?? 'unknown'}`,
      endpoint
    )
  } finally {
    clearTimeout(timer)
  }
}

/** Helper para GET */
export const apiGet  = <T>(ep: string, opts?: FetchOptions) => apiFetch<T>(ep, { method: 'GET', ...opts })

/** Helper para POST */
export const apiPost = <T>(ep: string, body?: unknown, opts?: FetchOptions) =>
  apiFetch<T>(ep, { method: 'POST', body: body !== undefined ? JSON.stringify(body) : undefined, ...opts })

/** Helper para DELETE */
export const apiDelete = <T>(ep: string, opts?: FetchOptions) => apiFetch<T>(ep, { method: 'DELETE', ...opts })

/** Devuelve la URL base (para construir WS URL en terminal) */
export function getBaseUrl(): string { return BASE_URL }
