const GATEWAY_URL = 'http://127.0.0.1:18789';

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: number;
  actions?: { label: string; command: string }[];
  suggestions?: string[];
}

export const api = {
  async getHealth() {
    // Try multiple endpoints — openclaw gateway may respond on different paths
    const endpoints = [
      `${GATEWAY_URL}/health`,
      `${GATEWAY_URL}/api/health`,
      `${GATEWAY_URL}/`,
    ]
    for (const url of endpoints) {
      try {
        const res = await fetch(url, {
          signal: AbortSignal.timeout(3000),
        })
        if (res.ok || res.status === 401 || res.status === 403) {
          // 401/403 means gateway is running but needs auth — still "online"
          return { status: 'ok' }
        }
      } catch {
        // try next
      }
    }
    // Also check via Kotlin bridge — GatewayService state
    try {
      const bridge = (window as unknown as { OpenClaw?: { getGatewayState?: () => string } }).OpenClaw
      if (bridge?.getGatewayState) {
        const state = bridge.getGatewayState()
        if (state === 'READY') return { status: 'ok' }
        if (state === 'STARTING' || state === 'RESTARTING') return { status: 'starting' }
      }
    } catch { /* ignore */ }
    return { status: 'offline' }
  },

  async getSkills() {
    try {
      const res = await fetch(`${GATEWAY_URL}/api/skills`, { signal: AbortSignal.timeout(5000) })
      return await res.json()
    } catch { return [] }
  },

  async toggleSkill(id: string) {
    try {
      const res = await fetch(`${GATEWAY_URL}/api/skill/${id}/toggle`, { method: 'POST', signal: AbortSignal.timeout(5000) })
      return await res.json()
    } catch { return {} }
  },

  async chat(message: string, context?: unknown) {
    const res = await fetch(`${GATEWAY_URL}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message, context }),
    })
    return await res.json()
  },

  async getMemory() {
    try {
      const res = await fetch(`${GATEWAY_URL}/api/memory`, { signal: AbortSignal.timeout(5000) })
      return await res.json()
    } catch { return [] }
  },

  async deleteMemory(id: string) {
    try {
      const res = await fetch(`${GATEWAY_URL}/api/memory/${id}`, { method: 'DELETE', signal: AbortSignal.timeout(5000) })
      return await res.json()
    } catch { return {} }
  },

  async getConfig() {
    try {
      const res = await fetch(`${GATEWAY_URL}/api/config`, { signal: AbortSignal.timeout(5000) })
      return await res.json()
    } catch { return null }
  },

  async updateConfig(config: unknown) {
    const res = await fetch(`${GATEWAY_URL}/api/config`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(config),
    })
    return await res.json()
  },

  async getLogs() {
    try {
      const res = await fetch(`${GATEWAY_URL}/api/logs`, { signal: AbortSignal.timeout(5000) })
      return await res.json()
    } catch { return [] }
  }
}
