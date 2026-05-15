import { useState, useCallback, useEffect } from "react"
import { bridge } from "../lib/bridge"
import { GatewayStatus } from "../components/GatewayStatus"
import { InstallationCard } from "../components/InstallationCard"
import { navigate } from "../lib/router"
import { Play, Box, Terminal as TerminalIcon, Zap, Cpu, Activity, Star, RefreshCw, AlertTriangle, ArrowRight } from "lucide-react"

interface AppInfo {
  versionName: string
  versionCode: number
  packageName: string
}

interface SystemInfo {
  nodeVersion?: string
  npmVersion?: string
  openclawVersion?: string
  gitVersion?: string
  payloadReady?: boolean
  diagnostics?: string
}

interface CmdGroup {
  title: string
  icon: React.ElementType
  items: { icon: React.ElementType; label: string; cmd: string; desc: string; color: string; path?: string }[]
}

const CMD_GROUPS: CmdGroup[] = [
  {
    title: "Quick Actions",
    icon: Zap,
    items: [
      { icon: Play, label: "Gateway", cmd: "openclaw gateway", desc: "Iniciar el gateway", color: "#60a5fa" },
      { icon: Activity, label: "Status", cmd: "openclaw status", desc: "Estado del sistema", color: "#4ade80" },
      { icon: Star, label: "Onboard", cmd: "openclaw onboard", desc: "Configuración inicial", color: "#fbbf24" },
      { icon: TerminalIcon, label: "Logs", cmd: "openclaw logs --follow", desc: "Logs en vivo", color: "#a78bfa" },
    ],
  },
  {
    title: "Administración",
    icon: Cpu,
    items: [
      { icon: RefreshCw, label: "Update", cmd: "openclaw update", desc: "Actualizar componentes", color: "#4ade80", path: "/settings/updates" },
      { icon: Zap, label: "Skills", cmd: "openclaw skills", desc: "Gestionar capacidades", color: "#facc15", path: "/skills" },
      { icon: Activity, label: "Doctor", cmd: "openclaw doctor", desc: "Diagnóstico del sistema", color: "#fb923c" },
      { icon: Box, label: "Config", cmd: "openclaw configure", desc: "Configurar entorno", color: "#22d3ee" },
    ],
  },
]

export function Dashboard() {
  const [nodeVer, setNodeVer] = useState<string | null>(null)
  const [npmVer, setNpmVer] = useState<string | null>(null)
  const [ocVer, setOcVer] = useState<string | null>(null)
  const [gitVer, setGitVer] = useState<string | null>(null)
  const [lastFile, setLastFile] = useState<string | null>(null)
  const [greeting] = useState(() => {
    const h = new Date().getHours()
    if (h < 12) return "Buenos días"
    if (h < 18) return "Buenas tardes"
    return "Buenas noches"
  })

  const getBridgeSystemInfo = useCallback((): SystemInfo => {
    if (!bridge.isAvailable()) return {}
    return bridge.callJson<SystemInfo>("getSystemInfo") ?? {}
  }, [])

  const refreshVersions = useCallback(async () => {
    if (!bridge.isAvailable()) return
    let info: SystemInfo | null = null
    try {
      const controller = new AbortController()
      const timeout = window.setTimeout(() => controller.abort(), 2000)
      const res = await fetch("http://127.0.0.1:18789/api/status", {
        headers: { Authorization: `Bearer ${window.__OPENCLAW_TOKEN ?? ""}` },
        signal: controller.signal,
      })
      window.clearTimeout(timeout)
      if (res.ok) {
        const data = await res.json()
        info = { nodeVersion: data.nodeVersion, npmVersion: data.npmVersion, openclawVersion: data.version, gitVersion: "no incluido", payloadReady: true }
      }
    } catch { info = null }
    info ??= getBridgeSystemInfo()
    const nodeDisplay = info.nodeVersion && info.nodeVersion !== "unknown" ? info.nodeVersion : info.payloadReady ? "reintentando..." : "instalando..."
    const ocDisplay = info.openclawVersion && info.openclawVersion !== "unknown" ? info.openclawVersion : info.payloadReady ? "desconocido" : "instalando..."
    setNodeVer(nodeDisplay)
    setNpmVer(info.npmVersion && info.npmVersion !== "unknown" ? info.npmVersion : "no incluido")
    setOcVer(ocDisplay)
    setGitVer(info.gitVersion || "no incluido")
  }, [getBridgeSystemInfo])

  useEffect(() => {
    const onFilePicked = (e: Event) => {
      const detail = (e as CustomEvent).detail
      if (typeof detail === "string") { try { const data = JSON.parse(detail); setLastFile(data.filename) } catch { /* */ } }
    }
    window.addEventListener("onMigrationFilePicked", onFilePicked)
    const onGatewayReady = () => { void refreshVersions() }
    window.addEventListener("android:onGatewayReady", onGatewayReady)
    window.addEventListener("onGatewayReady", onGatewayReady)
    void refreshVersions()
    const t1 = setTimeout(() => void refreshVersions(), 2000)
    const t2 = setTimeout(() => void refreshVersions(), 5000)
    return () => {
      window.removeEventListener("onMigrationFilePicked", onFilePicked)
      window.removeEventListener("android:onGatewayReady", onGatewayReady)
      window.removeEventListener("onGatewayReady", onGatewayReady)
      clearTimeout(t1); clearTimeout(t2)
    }
  }, [refreshVersions])

  const appInfo = bridge.isAvailable() ? bridge.callJson<AppInfo>("getAppInfo") : null

  const envTools = [
    { label: "Node.js",  value: nodeVer,          color: "#6c5ce7" },
    { label: "npm",      value: npmVer === "no incluido" ? "—" : npmVer, color: "#ff4757" },
    { label: "git",      value: gitVer === "no incluido" ? "—" : gitVer, color: "#00cec9" },
    { label: "openclaw", value: ocVer,            color: "#ffa502" },
  ]

  const needsInstall = !appInfo || !ocVer || ocVer === 'instalando...' || ocVer === 'desconocido' || ocVer === 'reintentando...'

  const runCommand = (cmd: string, path?: string) => {
    if (cmd === "openclaw gateway") { bridge.call("startGateway"); return }
    if (path) { navigate(path); return }
    try { sessionStorage.setItem("openclaw.pendingTerminalCommand", cmd) } catch { /* */ }
    window.dispatchEvent(new CustomEvent("terminal:run", { detail: cmd }))
  }

  return (
    <div className="page-container flex flex-col gap-4 pt-6 pb-4 animate-fade-in">
      {/* ── Greeting ── */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-text-primary tracking-tight">{greeting}</h1>
          <p className="text-[12px] sm:text-[13px] text-text-muted mt-0.5">Panel de control</p>
        </div>
        {lastFile && (
          <span className="badge badge-accent text-[10px] truncate max-w-[120px]">{lastFile}</span>
        )}
      </div>

      {/* ── Banner de instalación pendiente ── */}
      {needsInstall && (
        <div className="card p-4 border-l-2 border-l-accent/30">
          <div className="flex items-start gap-3">
            <div className="w-9 h-9 rounded-xl bg-accent-soft flex items-center justify-center shrink-0">
              <AlertTriangle size={17} className="text-accent" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-sm font-semibold text-text-primary">Instalación pendiente</div>
              <div className="text-[11px] text-text-muted mt-1 leading-relaxed">
                Los componentes del sistema aún no están instalados. Los archivos necesarios ya están incluidos
                en la aplicación — solo necesitas iniciar la instalación.
              </div>
              <div className="flex gap-2 mt-3">
                <button
                  onClick={() => { bridge.call('startSetup'); setOcVer('instalando...') }}
                  className="btn btn-primary text-xs px-3 py-1.5"
                >
                  <Play size={11} />
                  Iniciar instalación
                </button>
                <button
                  onClick={() => navigate('/settings/updates')}
                  className="btn btn-ghost text-xs px-3 py-1.5"
                >
                  <ArrowRight size={11} />
                  Ir a Updates
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── Environment badges ── */}
      <div className="flex flex-wrap gap-2">
        {envTools.map(tool => (
          <div key={tool.label}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-full glass text-[11px]">
            <span className="w-1.5 h-1.5 rounded-full" style={{ background: tool.color }} />
            <span className="font-medium text-text-secondary">{tool.label}</span>
            <span className="font-semibold text-text-primary">{tool.value ?? "—"}</span>
          </div>
        ))}
      </div>

      {/* ── Gateway + Installation ── */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <GatewayStatus />
        <InstallationCard />
      </div>

      {/* ── Command Groups ── */}
      {CMD_GROUPS.map((group, gi) => (
        <div key={group.title} style={{ animationDelay: `${gi * 0.1}s` }} className="animate-slide-up">
          <div className="flex items-center gap-1.5 mb-3 px-0.5">
            <group.icon size={12} className="text-text-muted" />
            <span className="text-[10px] font-semibold text-text-muted tracking-widest uppercase">{group.title}</span>
          </div>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
            {group.items.map((item, ii) => {
              const Icon = item.icon
              return (
                <button key={item.cmd}
                  onClick={() => runCommand(item.cmd, item.path)}
                  className="card card-hover p-3.5 text-left flex flex-col gap-2.5 group"
                  style={{ animationDelay: `${gi * 0.1 + ii * 0.05}s` }}>
                  <div className="w-9 h-9 rounded-xl flex items-center justify-center shrink-0 transition-all duration-200 group-hover:scale-110 group-hover:shadow-lg"
                    style={{ background: `${item.color}15` }}>
                    <Icon size={16} style={{ color: item.color }} strokeWidth={2} />
                  </div>
                  <div>
                    <div className="text-sm font-semibold text-text-primary leading-tight">{item.label}</div>
                    <div className="text-[11px] text-text-muted leading-tight mt-1">{item.desc}</div>
                  </div>
                </button>
              )
            })}
          </div>
        </div>
      ))}

      {/* ── App info footer ── */}
      {appInfo && (
        <div className="flex items-center justify-center gap-3 py-3 mt-2">
          <div className="gradient-divider flex-1" />
          <span className="text-[10px] text-text-muted font-medium tracking-tight">
            {appInfo.packageName} · v{appInfo.versionName}
          </span>
          <div className="gradient-divider flex-1" />
        </div>
      )}
    </div>
  )
}
