import { useState, useEffect, useCallback, useRef } from "react";
import { bridge, on, off } from "../lib/bridge";
import {
  Check,
  Terminal as TerminalIcon,
  Shield,
  Play,
  Package,
  AlertCircle,
  RefreshCw,
  Loader2,
  Circle,
  XCircle,
  SkipForward,
} from "lucide-react";

interface Props {
  onComplete: () => void;
}

const STEPS = [
  { key: "setup", label: "Instalación", icon: Shield },
  { key: "platform", label: "Plataforma", icon: TerminalIcon },
  { key: "done", label: "Listo", icon: Check },
];

/**
 * Lista de fases de instalación visibles para el usuario. Las claves deben
 * coincidir con las que emite el script de OpenClawProot.installOpenClaw
 * y con las que reporta AndroidBridge.getInstallPhases().
 */
const INSTALL_PHASES: { key: string; label: string; skippable?: boolean }[] = [
  { key: "dirs", label: "Preparar directorios del entorno" },
  { key: "alpine", label: "Descargar y extraer Alpine Linux" },
  { key: "arch", label: "Detectar arquitectura" },
  { key: "apk_repos", label: "Configurar repositorios apk" },
  { key: "apk_update", label: "Refrescar índice de paquetes" },
  {
    key: "sys_deps",
    label: "Dependencias mínimas (libstdc++, ca-certs, bash)",
    skippable: true,
  },
  { key: "nodejs", label: "Instalar Node.js manual", skippable: true },
  { key: "npm", label: "Configurar npm", skippable: true },
  { key: "pnpm", label: "Instalar pnpm", skippable: true },
  { key: "pnpm_env", label: "Configurar entorno pnpm", skippable: true },
  { key: "versions", label: "Verificar versiones", skippable: true },
  { key: "openclaw", label: "Instalar OpenClaw con pnpm", skippable: true },
  {
    key: "onboard",
    label: "Abrir OpenClaw onboard en terminal",
    skippable: true,
  },
  { key: "verify", label: "Verificación final", skippable: true },
];

interface SetupStatus {
  bootstrapInstalled?: boolean;
  platformInstalled?: string;
  alpineReady?: boolean;
  alpineAvailable?: boolean;
  alpineSizeBytes?: number;
  alpineSource?: string;
  canDownloadRemotely?: boolean;
  onboardComplete?: boolean;
  freeSpaceMB?: number;
  requiredSpaceMB?: number;
  hasEnoughSpace?: boolean;
}

type PhaseState = "pending" | "running" | "skipped" | "done" | "error";

interface PhaseStatus {
  state: PhaseState;
  message: string;
}

interface InstallProgressEvent {
  phase: string;
  phaseStatus: "start" | "step" | "ok" | "skip" | "error" | "log";
  message: string;
  logLine: string;
}

const MAX_LOG_LINES = 60;

const DEFAULT_PLATFORMS = [
  { id: "openclaw", name: "OpenClaw Core", installed: false },
  { id: "code", name: "Code Server", installed: false },
  { id: "browser", name: "Chromium Browser", installed: false },
];

export function Setup({ onComplete }: Props) {
  // ── Initial state from bridge (synchronous, runs once at mount) ─────
  const [setupStatus, setSetupStatus] = useState<SetupStatus | null>(() => {
    if (!bridge.isAvailable()) return null;
    return bridge.callJson<SetupStatus>("getSetupStatus") ?? null;
  });

  const [phaseStatus, setPhaseStatus] = useState<Record<string, PhaseStatus>>(
    () => {
      if (!bridge.isAvailable()) return {};
      const r = bridge.callJson<{ phases: string[] }>("getInstallPhases");
      if (!r?.phases) return {};
      const map: Record<string, PhaseStatus> = {};
      for (const k of r.phases) {
        map[k] = { state: "done", message: "Completado" };
      }
      return map;
    },
  );

  const [stepIdx, setStepIdx] = useState(() => {
    if (!bridge.isAvailable()) return 0;
    const s = bridge.callJson<SetupStatus>("getSetupStatus");
    return s?.bootstrapInstalled && s?.alpineReady ? 1 : 0;
  });

  const [error, setError] = useState("");
  const [errorPhase, setErrorPhase] = useState<string | null>(null);
  const [platforms, setPlatforms] = useState(DEFAULT_PLATFORMS);
  const [animKey, setAnimKey] = useState(0);
  const [installing, setInstalling] = useState(false);
  const [activePhase, setActivePhase] = useState<string | null>(null);
  const [logLines, setLogLines] = useState<string[]>([]);
  const [channel, setChannel] = useState<"estable" | "beta">("estable");

  const installingRef = useRef(false);
  const logEndRef = useRef<HTMLDivElement | null>(null);

  // Sync ref with state inside an effect (React compiler forbids ref updates during render)
  useEffect(() => {
    installingRef.current = installing;
  }, [installing]);

  // Derived: loading until setupStatus is populated
  const loading = bridge.isAvailable() && setupStatus === null && stepIdx === 0;
  const isAlreadyInstalled =
    setupStatus?.bootstrapInstalled && setupStatus?.alpineReady;

  const nextAnim = () => setAnimKey((k) => k + 1);
  const current = STEPS[stepIdx];

  /** Refresh setup status — used by polling interval and UI refresh button. */
  const refreshStatus = useCallback((): SetupStatus | null => {
    if (!bridge.isAvailable()) return null;
    const s = bridge.callJson<SetupStatus>("getSetupStatus");
    if (s) setSetupStatus(s);
    return s;
  }, []);

  /** Refresh completed phases — used by polling interval and UI refresh button. */
  const refreshCompletedPhases = useCallback(() => {
    if (!bridge.isAvailable()) return;
    const r = bridge.callJson<{ phases: string[] }>("getInstallPhases");
    if (!r?.phases) return;
    setPhaseStatus((prev) => {
      const next = { ...prev };
      for (const k of r.phases) {
        if (next[k]?.state === "error") continue;
        next[k] = { state: "done", message: "Completado" };
      }
      return next;
    });
  }, []);

  const appendLog = useCallback((line: string) => {
    if (!line) return;
    setLogLines((prev) => {
      const next = [...prev, line];
      return next.length > MAX_LOG_LINES
        ? next.slice(next.length - MAX_LOG_LINES)
        : next;
    });
  }, []);

  // ── Step 0 poll while installing ───────────────────────────────────────
  // setState is called inside the setInterval callback (async), not
  // synchronously in the effect body — this satisfies the React compiler.
  useEffect(() => {
    if (stepIdx !== 0) return;
    if (!installing) return;
    const id = setInterval(() => {
      const s = refreshStatus();
      refreshCompletedPhases();
      if (s?.bootstrapInstalled && s?.alpineReady) {
        setInstalling(false);
        setStepIdx(1);
        nextAnim();
        clearInterval(id);
      }
    }, 1500);
    return () => clearInterval(id);
  }, [stepIdx, installing, refreshStatus, refreshCompletedPhases]);

  // ── Auto-scroll log feed ───────────────────────────────────────────────
  useEffect(() => {
    logEndRef.current?.scrollIntoView({ block: "end", behavior: "smooth" });
  }, [logLines]);

  // ── Listen to native install events ────────────────────────────────────
  useEffect(() => {
    if (!bridge.isAvailable()) return;

    const hProgress = (data: unknown) => {
      const e = data as Partial<InstallProgressEvent>;
      const line = e.logLine || e.message || "";
      if (line) appendLog(line);

      if (e.phase) {
        setPhaseStatus((prev) => {
          const next = { ...prev };
          const existing = next[e.phase!] ?? { state: "pending", message: "" };
          let state: PhaseState = existing.state;
          const message = e.message || existing.message;
          switch (e.phaseStatus) {
            case "start":
            case "step":
              // No degradar de done a running si el script reporta una fase
              // que ya completamos en un intento previo
              state =
                existing.state === "done" || existing.state === "skipped"
                  ? existing.state
                  : "running";
              setActivePhase(e.phase!);
              break;
            case "skip":
              // Si previamente la marcamos como done, mantener done (visual coherente)
              state = existing.state === "done" ? "done" : "skipped";
              break;
            case "ok":
              state = "done";
              if (activePhase === e.phase) setActivePhase(null);
              break;
            case "error":
              state = "error";
              setErrorPhase(e.phase!);
              break;
            default:
              break;
          }
          next[e.phase!] = { state, message: message || existing.message };
          return next;
        });
        if (!installing) setInstalling(true);
      } else {
        // Línea de log sin fase asociada → mostrarla pero no cambiar status
        if (!installing) setInstalling(true);
      }
    };

    const hComplete = () => {
      setInstalling(false);
      setActivePhase(null);
      setError("");
      setErrorPhase(null);
      refreshStatus();
      refreshCompletedPhases();
      setStepIdx(1);
      nextAnim();
    };
    const hError = (data: unknown) => {
      const d = data as { error?: string };
      setInstalling(false);
      setActivePhase(null);
      setError(d?.error ?? "Error desconocido durante la instalación");
    };

    const hBypassed = () => {
      setInstalling(false);
      setActivePhase(null);
      setError("");
      setErrorPhase(null);
      setStepIdx(2);
      nextAnim();
      setTimeout(() => onComplete(), 1200);
    };

    const r1 = on("onInstallProgress", hProgress);
    const r2 = on("onInstallComplete", hComplete);
    const r3 = on("onInstallError", hError);
    const r4 = on("onInstallBypassed", hBypassed);

    return () => {
      off("onInstallProgress", r1);
      off("onInstallComplete", r2);
      off("onInstallError", r3);
      off("onInstallBypassed", r4);
    };
  }, [
    refreshStatus,
    refreshCompletedPhases,
    appendLog,
    installing,
    activePhase,
    onComplete,
  ]);

  // ── Install actions ────────────────────────────────────────────────────
  const startInstall = useCallback(() => {
    if (!bridge.isAvailable() || installing) return;
    setError("");
    setErrorPhase(null);
    // Mantener fases ya completadas — solo limpiamos las que estaban en error
    setPhaseStatus((prev) => {
      const next: Record<string, PhaseStatus> = {};
      for (const [k, v] of Object.entries(prev)) {
        if (v.state === "done" || v.state === "skipped") next[k] = v;
      }
      return next;
    });
    setLogLines([]);
    setInstalling(true);
    if (!bridge.isAvailable()) {
      setInstalling(false);
      setError("Bridge Android no disponible. Recarga la app.");
      return;
    }
    const result = bridge.call("startSetupWithChannel", channel);
    if (result === null) {
      setInstalling(false);
      setError(
        "No se pudo iniciar la instalación: método nativo no disponible.",
      );
      return;
    }
  }, [installing, channel]);

  const installPlatform = useCallback((id: string) => {
    bridge.call("installPlatform", id);
    setPlatforms((prev) =>
      prev.map((p) => (p.id === id ? { ...p, installed: true } : p)),
    );
  }, []);

  const installAllPlatforms = () => {
    platforms.forEach((p) => bridge.call("installPlatform", p.id));
    setPlatforms((prev) => prev.map((p) => ({ ...p, installed: true })));
    setStepIdx(2);
    nextAnim();
    setTimeout(() => onComplete(), 1500);
  };

  /** Saltar una fase fallida (solo fases marcadas como skippable). */
  const handleSkipPhase = useCallback(
    (key: string) => {
      if (!bridge.isAvailable()) return;
      bridge.call("skipPhase", key);
      // Actualizar el estado local inmediatamente para feedback visual
      setPhaseStatus((prev) => ({
        ...prev,
        [key]: { state: "skipped", message: "Saltado por el usuario" },
      }));
      // Si era la fase con error activo, limpiar el error
      if (errorPhase === key) {
        setErrorPhase(null);
        setError("");
      }
    },
    [errorPhase],
  );

  /**
   * Saltar toda la instalación. El usuario quedará en el dashboard,
   * donde puede instalar OpenClaw desde la card de instalación o
   * directamente desde el terminal.
   */
  const handleBypassInstall = useCallback(() => {
    if (!bridge.isAvailable()) {
      // Modo navegador (sin bridge): solo avanzamos al dashboard
      setInstalling(false);
      setError("");
      setErrorPhase(null);
      setStepIdx(2);
      nextAnim();
      setTimeout(() => onComplete(), 1000);
      return;
    }
    const ok = window.confirm(
      "¿Saltar la instalación?\n\n" +
        "OpenClaw NO quedará instalado dentro de Alpine. Podrás " +
        "instalarlo más tarde desde el dashboard o desde el terminal.",
    );
    if (!ok) return;
    setInstalling(false);
    setError("");
    setErrorPhase(null);
    bridge.call("bypassInstall");
  }, [onComplete]);

  // ── Phase list summary ─────────────────────────────────────────────────
  const phaseSummary = (() => {
    let done = 0,
      running = 0,
      errored = 0;
    for (const p of INSTALL_PHASES) {
      const s = phaseStatus[p.key]?.state;
      if (s === "done" || s === "skipped") done++;
      if (s === "running") running++;
      if (s === "error") errored++;
    }
    return { done, running, errored, total: INSTALL_PHASES.length };
  })();

  const overallPercent = Math.round(
    (phaseSummary.done / phaseSummary.total) * 100,
  );
  const hasPartialProgress = phaseSummary.done > 0 || phaseSummary.errored > 0;
  const showChannelSelection =
    !installing &&
    !isAlreadyInstalled &&
    (phaseStatus.alpine?.state === "done" ||
      phaseStatus.openclaw !== undefined);

  // ── Setup step renderer ────────────────────────────────────────────────
  const renderPhaseRow = (key: string, label: string, skippable?: boolean) => {
    const status = phaseStatus[key]?.state ?? "pending";
    const message = phaseStatus[key]?.message ?? "";

    let icon: React.ReactNode;
    let titleClass = "text-text-secondary";
    let badgeClass = "bg-glass-bg border-glass-border";
    switch (status) {
      case "running":
        icon = <Loader2 size={14} className="text-accent animate-spin" />;
        titleClass = "text-text-primary";
        badgeClass = "bg-accent-soft border-accent/20";
        break;
      case "done":
        icon = <Check size={14} className="text-green" />;
        titleClass = "text-text-primary";
        badgeClass = "bg-green-soft border-green/20";
        break;
      case "skipped":
        icon = <Check size={14} className="text-text-muted" />;
        titleClass = "text-text-secondary";
        badgeClass = "bg-glass-bg border-glass-border";
        break;
      case "error":
        icon = <XCircle size={14} className="text-red" />;
        titleClass = "text-red";
        badgeClass = "bg-red-soft border-red/20";
        break;
      default:
        icon = <Circle size={14} className="text-text-dim" />;
        break;
    }

    return (
      <div
        key={key}
        className={`flex items-start gap-2.5 px-2.5 py-1.5 rounded-lg border ${badgeClass}`}
      >
        <div className="mt-[2px]">{icon}</div>
        <div className="flex-1 min-w-0">
          <div className={`text-[11px] font-medium ${titleClass}`}>{label}</div>
          {message && (
            <div className="text-[10px] text-text-muted truncate mt-0.5">
              {message}
            </div>
          )}
        </div>
        {status === "error" && skippable && !installing && (
          <button
            onClick={() => handleSkipPhase(key)}
            className="shrink-0 flex items-center gap-1 px-2 py-0.5 rounded-md bg-amber-500/20 border border-amber-500/30 text-amber-400 text-[10px] font-medium hover:bg-amber-500/30 transition-colors"
            title="Saltar esta fase y continuar"
          >
            <SkipForward size={10} />
            Saltar
          </button>
        )}
      </div>
    );
  };

  const renderSetupStep = () => {
    const needsSpace = setupStatus && setupStatus.hasEnoughSpace === false;
    const isAlreadyInstalled =
      setupStatus?.bootstrapInstalled && setupStatus?.alpineReady;

    if (loading && !setupStatus) {
      return (
        <div className="flex items-center justify-center py-4">
          <div className="flex items-center gap-3">
            <div className="w-5 h-5 rounded-full border-2 border-accent/30 border-t-accent animate-spin" />
            <span className="text-xs text-text-muted">
              Verificando instalación...
            </span>
          </div>
        </div>
      );
    }

    return (
      <div className="space-y-3">
        {/* Alpine status card */}
        <div className="rounded-xl bg-glass-bg border border-glass-border p-3">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg bg-accent-soft flex items-center justify-center shrink-0">
              <Package size={15} className="text-accent" />
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-xs font-semibold text-text-primary">
                Alpine Linux + Node.js
              </div>
              <div className="text-[10px] text-text-muted mt-0.5">
                {setupStatus?.bootstrapInstalled
                  ? setupStatus?.alpineReady
                    ? "Instalado ✓"
                    : "Alpine listo, instalando OpenClaw..."
                  : hasPartialProgress
                    ? `Reanudable: ${phaseSummary.done}/${phaseSummary.total} fases completadas`
                    : "Requiere descarga (~10 MB)"}
              </div>
            </div>
            {setupStatus?.bootstrapInstalled && setupStatus?.alpineReady && (
              <div className="w-6 h-6 rounded-full bg-green-soft flex items-center justify-center">
                <Check size={12} className="text-green" />
              </div>
            )}
          </div>
        </div>

        {/* Free-space warning */}
        {needsSpace && (
          <div className="flex items-center gap-2 px-3 py-2 rounded-xl bg-red-soft border border-red/10 text-[11px] text-red">
            <AlertCircle size={12} />
            Espacio insuficiente: {setupStatus?.freeSpaceMB} MB libres, se
            requieren {setupStatus?.requiredSpaceMB} MB.
          </div>
        )}

        {/* Error banner — vinculado a la fase */}
        {error && (
          <div className="px-3 py-2.5 rounded-xl bg-red-soft border border-red/10 text-[11px] text-red space-y-2">
            <div className="flex items-start gap-2">
              <AlertCircle size={12} className="mt-[2px] shrink-0" />
              <div className="flex-1 min-w-0">
                <div className="font-semibold">
                  {errorPhase
                    ? `Falló en: ${INSTALL_PHASES.find((p) => p.key === errorPhase)?.label ?? errorPhase}`
                    : "Error durante la instalación"}
                </div>
                <div className="opacity-80 mt-0.5 wrap-break-word">{error}</div>
                {hasPartialProgress && (
                  <div className="opacity-70 mt-1">
                    Se conservarán las {phaseSummary.done} fases ya completadas
                    al reintentar.
                  </div>
                )}
              </div>
            </div>
            {!installing && (
              <div className="flex flex-wrap gap-2 pt-1">
                {errorPhase &&
                  INSTALL_PHASES.find((p) => p.key === errorPhase)?.skippable && (
                    <button
                      onClick={() => handleSkipPhase(errorPhase)}
                      className="flex items-center gap-1 px-2.5 py-1 rounded-md bg-amber-500/20 border border-amber-500/30 text-amber-300 text-[10px] font-medium hover:bg-amber-500/30 transition-colors"
                    >
                      <SkipForward size={10} />
                      Saltar esta fase
                    </button>
                  )}
                <button
                  onClick={handleBypassInstall}
                  className="flex items-center gap-1 px-2.5 py-1 rounded-md bg-glass-bg border border-glass-border text-text-secondary text-[10px] font-medium hover:bg-bg transition-colors"
                  title="Cancelar instalación y continuar al dashboard"
                >
                  <SkipForward size={10} />
                  Saltar instalación e ir al dashboard
                </button>
              </div>
            )}
          </div>
        )}

        {/* Overall progress bar */}
        {(installing || hasPartialProgress) && (
          <div className="space-y-1.5">
            <div className="flex items-center justify-between text-[10px] text-text-muted">
              <span>
                {installing ? "Instalando…" : "Progreso parcial"}
                {" · "}
                {phaseSummary.done}/{phaseSummary.total} fases
              </span>
              <span>{overallPercent}%</span>
            </div>
            <div className="w-full h-2 rounded-full bg-glass-bg overflow-hidden">
              <div
                className={`h-full rounded-full transition-all duration-300 ${
                  phaseSummary.errored > 0 ? "bg-red" : "bg-accent"
                }`}
                style={{ width: `${Math.max(2, overallPercent)}%` }}
              />
            </div>
          </div>
        )}

        {/* Phase checklist */}
        {(installing || hasPartialProgress || error) && (
          <div className="rounded-xl bg-glass-bg border border-glass-border p-2 space-y-1.5 max-h-[280px] overflow-y-auto">
            {INSTALL_PHASES.map((p) =>
              renderPhaseRow(p.key, p.label, p.skippable),
            )}
          </div>
        )}

        {/* Live log feed (last lines) */}
        {(installing || logLines.length > 0) && (
          <div className="rounded-xl bg-black/40 border border-glass-border p-2 max-h-[140px] overflow-y-auto font-mono text-[10px] text-text-muted leading-tight">
            {logLines.length === 0 ? (
              <div className="text-text-dim italic">
                Esperando salida del instalador…
              </div>
            ) : (
              logLines.map((line, i) => (
                <div key={i} className="break-all whitespace-pre-wrap">
                  {line}
                </div>
              ))
            )}
            <div ref={logEndRef} />
          </div>
        )}

        {/* Actions */}
        {!installing && (
          <div className="space-y-2">
            {!isAlreadyInstalled && (
              <>
                {showChannelSelection && (
                  <div className="flex items-center justify-between rounded-xl bg-glass-bg border border-glass-border p-3">
                    <div>
                      <div className="text-xs font-semibold text-text-primary">
                        Canal de instalación
                      </div>
                      <div className="text-[10px] text-text-muted mt-0.5">
                        Versión a descargar e instalar
                      </div>
                    </div>
                    <select
                      value={channel}
                      onChange={(e) =>
                        setChannel(e.target.value as "estable" | "beta")
                      }
                      className="bg-bg border border-glass-border rounded-lg text-xs px-2 py-1 outline-none text-text-primary"
                    >
                      <option value="estable">Estable</option>
                      <option value="beta">Beta</option>
                    </select>
                  </div>
                )}
                <button
                  onClick={startInstall}
                  disabled={installing || needsSpace === true}
                  className={`w-full btn text-xs ${needsSpace ? "bg-glass-bg text-text-dim cursor-not-allowed" : "btn-primary"}`}
                >
                  <Play size={13} />
                  {hasPartialProgress
                    ? "Continuar instalación"
                    : "Iniciar instalación"}
                </button>
              </>
            )}
            <button
              onClick={() => {
                refreshStatus();
                refreshCompletedPhases();
              }}
              className="w-full btn btn-ghost text-[11px] px-3 py-1.5"
            >
              <RefreshCw size={11} />
              Revisar de nuevo
            </button>

            {/* Skip-install option: visible cuando el usuario no ha completado el
                setup, para que pueda continuar al dashboard e instalar manualmente. */}
            {!isAlreadyInstalled && (
              <div className="mt-3 pt-3 border-t border-glass-border space-y-2">
                <div className="text-[10px] text-text-muted leading-snug">
                  ¿No quieres esperar la instalación automática? Puedes
                  saltarla y configurar OpenClaw más tarde desde el
                  dashboard o directamente desde el terminal de la app.
                </div>
                <button
                  onClick={handleBypassInstall}
                  className="w-full flex items-center justify-center gap-2 px-3 py-2 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-300 text-[11px] font-medium hover:bg-amber-500/20 transition-colors"
                  title="Saltar la instalación e ir directamente al dashboard"
                >
                  <SkipForward size={12} />
                  Saltar instalación e ir al dashboard
                </button>
              </div>
            )}
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="min-h-screen bg-bg flex flex-col items-center justify-center p-4">
      <div className="w-full max-w-md mx-auto animate-fade-in" key={animKey}>
        {/* ── Logo / Header ── */}
        <div className="text-center mb-8">
          <div className="relative w-16 h-16 mx-auto mb-4">
            <div className="absolute inset-0 rounded-2xl bg-accent-soft animate-pulse" />
            <div className="absolute inset-0 rounded-2xl flex items-center justify-center">
              <Shield size={32} className="text-accent" />
            </div>
          </div>
          <h1 className="text-xl font-bold text-text-primary tracking-tight">
            OpenClaw
          </h1>
          <p className="text-[13px] text-text-muted mt-1">
            Configuración inicial
          </p>
        </div>

        {/* ── Step progress ── */}
        <div className="flex items-center justify-center gap-3 mb-8">
          {STEPS.map((s, i) => (
            <div key={s.key} className="flex items-center gap-3">
              <div
                className={`step-dot ${i <= stepIdx ? (i < stepIdx ? "done" : "active") : ""}`}
              />
              {i < STEPS.length - 1 && (
                <div
                  className={`w-8 h-[2px] rounded-full ${i < stepIdx ? "bg-accent-light" : "bg-glass-bg"}`}
                />
              )}
            </div>
          ))}
        </div>

        {/* ── Step content ── */}
        <div className="card p-6">
          <div className="text-center mb-6">
            <div className="w-12 h-12 rounded-xl bg-accent-soft flex items-center justify-center mx-auto mb-3">
              <current.icon size={24} className="text-accent" />
            </div>
            <h3 className="text-base font-bold text-text-primary">
              {current.label}
            </h3>
            <p className="text-xs text-text-muted mt-1">
              {stepIdx === 0 &&
                (installing
                  ? "Instalando Alpine + Node.js + OpenClaw..."
                  : "Descarga Alpine Linux e instala OpenClaw")}
              {stepIdx === 1 && "Selecciona las plataformas a instalar"}
              {stepIdx === 2 && "¡Todo listo! Redirigiendo..."}
            </p>
          </div>

          {stepIdx === 0 && renderSetupStep()}

          {/* Step: Platforms */}
          {stepIdx === 1 && (
            <div className="space-y-2">
              {platforms.map((p) => (
                <div
                  key={p.id}
                  className="flex items-center justify-between px-4 py-3 rounded-xl bg-glass-bg border border-glass-border"
                >
                  <div>
                    <div className="text-sm font-semibold text-text-primary">
                      {p.name}
                    </div>
                    <div className="text-[11px] text-text-muted">{p.id}</div>
                  </div>
                  {p.installed ? (
                    <Check size={18} className="text-green" />
                  ) : (
                    <button
                      onClick={() => installPlatform(p.id)}
                      className="btn btn-primary text-[11px] px-3 py-1.5"
                    >
                      Instalar
                    </button>
                  )}
                </div>
              ))}
              {platforms.every((p) => p.installed) && (
                <button
                  onClick={installAllPlatforms}
                  className="w-full btn btn-primary text-xs mt-4"
                >
                  Finalizar
                </button>
              )}
            </div>
          )}

          {/* Step: Done */}
          {stepIdx === 2 && (
            <div className="text-center py-6">
              <div className="w-16 h-16 rounded-2xl bg-green-soft flex items-center justify-center mx-auto mb-4 animate-scale-in">
                <Check size={32} className="text-green" />
              </div>
              <p className="text-sm text-text-secondary">
                OpenClaw está listo para usar
              </p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
