/**
 * OpenClaw Gateway WebSocket client (Protocol v3).
 * Handles connect/handshake, RPC calls, and event subscriptions.
 * Falls back gracefully when the gateway is offline.
 */

const GATEWAY_WS = 'ws://127.0.0.1:18789'
const GATEWAY_HTTP = 'http://127.0.0.1:18789'
const REQUEST_TIMEOUT = 15_000

/* ── Types ─────────────────────────────────────────────── */

export interface GatewayModel {
    id: string          // e.g. "openai/gpt-5.5"
    name?: string       // display name
    provider?: string   // e.g. "openai"
    contextWindow?: number
    reasoning?: boolean
    input?: string[]
}

export interface GatewayConfig {
    agents?: {
        defaults?: {
            model?: { primary?: string }
            agentRuntime?: { id?: string }
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
    providerLabel: string
    contextWindow?: number
    reasoning?: boolean
}

/* ── Provider display metadata ─────────────────────────── */

const PROVIDER_META: Record<string, { label: string; icon: string; color: string }> = {
    openai: { label: 'OpenAI', icon: '🟢', color: '#10a37f' },
    'openai-codex': { label: 'OpenAI Codex', icon: '🟢', color: '#10a37f' },
    anthropic: { label: 'Anthropic', icon: '🟠', color: '#d97706' },
    google: { label: 'Google Gemini', icon: '🔵', color: '#4285f4' },
    'google-vertex': { label: 'Google Vertex', icon: '🔵', color: '#4285f4' },
    'google-gemini-cli': { label: 'Gemini CLI', icon: '🔵', color: '#4285f4' },
    opencode: { label: 'OpenCode', icon: '🟣', color: '#7c3aed' },
    'opencode-go': { label: 'OpenCode Go', icon: '🟣', color: '#7c3aed' },
    ollama: { label: 'Ollama (local)', icon: '🦙', color: '#6b7280' },
    lmstudio: { label: 'LM Studio', icon: '🖥️', color: '#6b7280' },
    vllm: { label: 'vLLM', icon: '⚡', color: '#6b7280' },
    sglang: { label: 'SGLang', icon: '⚡', color: '#6b7280' },
    openrouter: { label: 'OpenRouter', icon: '🔀', color: '#6366f1' },
    groq: { label: 'Groq', icon: '⚡', color: '#f59e0b' },
    mistral: { label: 'Mistral', icon: '🌊', color: '#0ea5e9' },
    deepseek: { label: 'DeepSeek', icon: '🔍', color: '#3b82f6' },
    xai: { label: 'xAI (Grok)', icon: '✖️', color: '#e5e7eb' },
    moonshot: { label: 'Moonshot (Kimi)', icon: '🌙', color: '#8b5cf6' },
    kimi: { label: 'Kimi Coding', icon: '🌙', color: '#8b5cf6' },
    zai: { label: 'Z.AI (GLM)', icon: '🇨🇳', color: '#ef4444' },
    qwen: { label: 'Qwen', icon: '🇨🇳', color: '#ef4444' },
    minimax: { label: 'MiniMax', icon: '🔮', color: '#a855f7' },
    'minimax-portal': { label: 'MiniMax Portal', icon: '🔮', color: '#a855f7' },
    cerebras: { label: 'Cerebras', icon: '🧠', color: '#f97316' },
    nvidia: { label: 'NVIDIA', icon: '🟩', color: '#76b900' },
    together: { label: 'Together AI', icon: '🤝', color: '#6366f1' },
    huggingface: { label: 'Hugging Face', icon: '🤗', color: '#fbbf24' },
    perplexity: { label: 'Perplexity', icon: '🔎', color: '#20b2aa' },
    'vercel-ai-gateway': { label: 'Vercel AI', icon: '▲', color: '#000000' },
    kilocode: { label: 'Kilo Gateway', icon: '🔑', color: '#6366f1' },
    'github-copilot': { label: 'GitHub Copilot', icon: '🐙', color: '#24292e' },
    volcengine: { label: 'Volcano Engine', icon: '🌋', color: '#ef4444' },
    'volcengine-plan': { label: 'Doubao (Coding)', icon: '🌋', color: '#ef4444' },
    byteplus: { label: 'BytePlus', icon: '🔷', color: '#1d4ed8' },
    'byteplus-plan': { label: 'BytePlus Coding', icon: '🔷', color: '#1d4ed8' },
    deepinfra: { label: 'DeepInfra', icon: '🏗️', color: '#6b7280' },
    synthetic: { label: 'Synthetic', icon: '🧪', color: '#8b5cf6' },
    stepfun: { label: 'StepFun', icon: '🪜', color: '#6366f1' },
    venice: { label: 'Venice AI', icon: '🎭', color: '#7c3aed' },
    xiaomi: { label: 'Xiaomi MiMo', icon: '📱', color: '#ff6900' },
}

export function getProviderMeta(providerId: string) {
    return PROVIDER_META[providerId] ?? { label: providerId, icon: '🤖', color: '#6b7280' }
}

/* ── HTTP fallback for config ──────────────────────────── */

async function httpGet<T>(path: string): Promise<T | null> {
    try {
        const headers: Record<string, string> = { 'Content-Type': 'application/json' }
        const token = (window as unknown as { __OPENCLAW_TOKEN?: string }).__OPENCLAW_TOKEN
        if (token) {
            headers['Authorization'] = `Bearer ${token}`
        }
        
        const r = await fetch(`${GATEWAY_HTTP}${path}`, { 
            headers,
            signal: AbortSignal.timeout(5000) 
        })
        if (!r.ok) return null
        return await r.json() as T
    } catch {
        return null
    }
}

async function httpPost<T>(path: string, body: unknown): Promise<T | null> {
    try {
        const headers: Record<string, string> = { 'Content-Type': 'application/json' }
        const token = (window as unknown as { __OPENCLAW_TOKEN?: string }).__OPENCLAW_TOKEN
        if (token) {
            headers['Authorization'] = `Bearer ${token}`
        }
        
        const r = await fetch(`${GATEWAY_HTTP}${path}`, {
            method: 'POST',
            headers,
            body: JSON.stringify(body),
            signal: AbortSignal.timeout(5000),
        })
        if (!r.ok) return null
        return await r.json() as T
    } catch {
        return null
    }
}

/* ── Android bridge token helper ──────────────────────── */

function getAndroidToken(): string {
    try {
        // Primero intentar la variable global (más nueva)
        const globalToken = (window as unknown as { __OPENCLAW_TOKEN?: string }).__OPENCLAW_TOKEN
        if (globalToken) {
            return globalToken
        }
        
        // Fallback al bridge Android
        const bridge = (window as unknown as { OpenClaw?: { getGatewayToken?: () => string, getAuthToken?: () => string } }).OpenClaw
        if (bridge?.getAuthToken) {
            return bridge.getAuthToken() ?? ''
        }
        if (bridge?.getGatewayToken) {
            return bridge.getGatewayToken() ?? ''
        }
    } catch { /* not in Android WebView */ }
    return ''
}

/* ── WebSocket RPC client ──────────────────────────────── */

class GatewayClient {
    private ws: WebSocket | null = null
    private pending = new Map<string, { resolve: (v: unknown) => void; reject: (e: unknown) => void; timer: ReturnType<typeof setTimeout> }>()
    private connectPromise: Promise<void> | null = null
    private seq = 0

    private nextId() { return `oc-${++this.seq}-${Date.now()}` }

    async connect(): Promise<void> {
        if (this.ws?.readyState === WebSocket.OPEN) return
        if (this.connectPromise) return this.connectPromise

        this.connectPromise = new Promise((resolve, reject) => {
            const ws = new WebSocket(GATEWAY_WS)
            const timeout = setTimeout(() => {
                ws.close()
                reject(new Error('WS connect timeout'))
            }, 8000)

            ws.onopen = () => {
                // Wait for connect.challenge then send connect request
            }

            ws.onmessage = (ev) => {
                try {
                    const frame = JSON.parse(ev.data as string)

                    // Pre-auth challenge
                    if (frame.type === 'event' && frame.event === 'connect.challenge') {
                        const token = getAndroidToken()
                        const req = {
                            type: 'req',
                            id: this.nextId(),
                            method: 'connect',
                            params: {
                                minProtocol: 3, maxProtocol: 3,
                                client: { id: 'android-node', version: '1.0.0', platform: 'android', mode: 'operator' },
                                role: 'operator',
                                scopes: ['operator.read', 'operator.write'],
                                caps: [], commands: [], permissions: {},
                                auth: { token },
                                locale: 'es',
                                userAgent: 'openclaw-android/1.0.0',
                            }
                        }
                        ws.send(JSON.stringify(req))
                        return
                    }

                    // Connect response
                    if (frame.type === 'res' && frame.ok && frame.payload?.type === 'hello-ok') {
                        clearTimeout(timeout)
                        this.ws = ws
                        this.connectPromise = null
                        resolve()
                        return
                    }

                    // RPC responses
                    if (frame.type === 'res' && frame.id) {
                        const p = this.pending.get(frame.id)
                        if (p) {
                            clearTimeout(p.timer)
                            this.pending.delete(frame.id)
                            if (frame.ok) p.resolve(frame.payload)
                            else p.reject(new Error(frame.error?.message ?? 'RPC error'))
                        }
                    }
                } catch { /* ignore parse errors */ }
            }

            ws.onerror = () => { clearTimeout(timeout); reject(new Error('WS error')) }
            ws.onclose = () => {
                clearTimeout(timeout)
                this.ws = null
                this.connectPromise = null
                // Reject all pending
                for (const [, p] of this.pending) p.reject(new Error('WS closed'))
                this.pending.clear()
            }
        })

        return this.connectPromise
    }

    async rpc<T>(method: string, params?: unknown): Promise<T> {
        await this.connect()
        if (!this.ws) throw new Error('Not connected')

        return new Promise<T>((resolve, reject) => {
            const id = this.nextId()
            const timer = setTimeout(() => {
                this.pending.delete(id)
                reject(new Error(`RPC timeout: ${method}`))
            }, REQUEST_TIMEOUT)

            this.pending.set(id, {
                resolve: (v) => resolve(v as T),
                reject,
                timer,
            })

            this.ws!.send(JSON.stringify({ type: 'req', id, method, params }))
        })
    }

    close() {
        this.ws?.close()
        this.ws = null
    }
}

const client = new GatewayClient()

/* ── Public API ────────────────────────────────────────── */

/**
 * Fetch all available models from the gateway.
 * Uses WS RPC models.list with view:"all", falls back to HTTP /api/models.
 */
export async function fetchModels(): Promise<ModelEntry[]> {
    try {
        // Try WebSocket RPC first
        const res = await client.rpc<{ models?: GatewayModel[]; entries?: GatewayModel[] }>('models.list', { view: 'all' })
        const raw: GatewayModel[] = res?.models ?? res?.entries ?? []
        return normalizeModels(raw)
    } catch {
        // Fallback: HTTP endpoint (some gateway versions expose this)
        const res = await httpGet<{ models?: GatewayModel[] }>('/api/models')
        if (res?.models) return normalizeModels(res.models)
        return []
    }
}

/**
 * Fetch configured models only (what's in agents.defaults.models or providers).
 */
export async function fetchConfiguredModels(): Promise<ModelEntry[]> {
    try {
        const res = await client.rpc<{ models?: GatewayModel[] }>('models.list', { view: 'configured' })
        const raw: GatewayModel[] = res?.models ?? []
        return normalizeModels(raw)
    } catch {
        return []
    }
}

/**
 * Fetch the full gateway config.
 */
export async function fetchGatewayConfig(): Promise<GatewayConfig | null> {
    try {
        const res = await client.rpc<{ config?: GatewayConfig; payload?: GatewayConfig }>('config.get')
        return res?.config ?? res?.payload ?? (res as GatewayConfig) ?? null
    } catch {
        return httpGet<GatewayConfig>('/api/config')
    }
}

/**
 * Patch the gateway config (partial update).
 */
export async function patchGatewayConfig(patch: GatewayConfig): Promise<boolean> {
    try {
        await client.rpc('config.patch', { config: patch })
        return true
    } catch {
        const r = await httpPost('/api/config', patch)
        return r !== null
    }
}

/**
 * Set the active model via config.patch.
 */
export async function setActiveModel(modelId: string): Promise<boolean> {
    return patchGatewayConfig({
        agents: { defaults: { model: { primary: modelId } } }
    })
}

/* ── Helpers ───────────────────────────────────────────── */

function normalizeModels(raw: GatewayModel[]): ModelEntry[] {
    return raw
        .filter(m => m?.id)
        .map(m => {
            const [provider, ...rest] = m.id.split('/')
            const modelName = rest.join('/') || m.id
            const meta = getProviderMeta(provider)
            return {
                id: m.id,
                name: m.name || formatModelName(modelName),
                provider,
                providerLabel: meta.label,
                contextWindow: m.contextWindow,
                reasoning: m.reasoning,
            }
        })
        .sort((a, b) => a.provider.localeCompare(b.provider) || a.name.localeCompare(b.name))
}

function formatModelName(id: string): string {
    return id
        .replace(/-/g, ' ')
        .replace(/\b\w/g, c => c.toUpperCase())
        .replace(/\bGpt\b/, 'GPT')
        .replace(/\bGemini\b/, 'Gemini')
        .replace(/\bClaude\b/, 'Claude')
}
