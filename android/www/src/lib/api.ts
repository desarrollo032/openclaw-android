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
    try {
      const res = await fetch(`${GATEWAY_URL}/api/health`);
      return await res.json();
    } catch (e) {
      console.error('Gateway health check failed', e);
      return { status: 'offline' };
    }
  },

  async getSkills() {
    const res = await fetch(`${GATEWAY_URL}/api/skills`);
    return await res.json();
  },

  async toggleSkill(id: string) {
    const res = await fetch(`${GATEWAY_URL}/api/skill/${id}/toggle`, { method: 'POST' });
    return await res.json();
  },

  async chat(message: string, context?: any) {
    const res = await fetch(`${GATEWAY_URL}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message, context }),
    });
    return await res.json();
  },

  async getMemory() {
    const res = await fetch(`${GATEWAY_URL}/api/memory`);
    return await res.json();
  },

  async deleteMemory(id: string) {
    const res = await fetch(`${GATEWAY_URL}/api/memory/${id}`, { method: 'DELETE' });
    return await res.json();
  },

  async getConfig() {
    const res = await fetch(`${GATEWAY_URL}/api/config`);
    return await res.json();
  },

  async updateConfig(config: any) {
    const res = await fetch(`${GATEWAY_URL}/api/config`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(config),
    });
    return await res.json();
  },

  async getLogs() {
    const res = await fetch(`${GATEWAY_URL}/api/logs`);
    return await res.json();
  }
};
