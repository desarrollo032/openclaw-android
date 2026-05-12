import { useState, useRef, useEffect, useCallback } from "react";
import { bridge } from "../lib/bridge";
import { t } from "../i18n";

interface HistoryEntry {
  type: "cmd" | "out" | "err";
  text: string;
}
interface KeyDef {
  label: string;
  flex?: number;
  bg: string;
  fg: string;
  onPress: () => void;
}

const OC_COMMANDS = [
  "openclaw gateway",
  "openclaw status",
  "openclaw health",
  "openclaw logs",
  "openclaw onboard",
  "openclaw setup",
  "openclaw configure",
  "openclaw config",
  "openclaw doctor",
  "openclaw update",
  "openclaw backup",
  "openclaw reset",
  "openclaw uninstall",
  "openclaw models",
  "openclaw infer",
  "openclaw capability",
  "openclaw message",
  "openclaw agent",
  "openclaw agents",
  "openclaw sessions",
  "openclaw memory",
  "openclaw commitments",
  "openclaw wiki",
  "openclaw approvals",
  "openclaw sandbox",
  "openclaw chat",
  "openclaw browser",
  "openclaw cron",
  "openclaw tasks",
  "openclaw hooks",
  "openclaw webhooks",
  "openclaw security",
  "openclaw secrets",
  "openclaw skills",
  "openclaw plugins",
  "openclaw proxy",
  "openclaw dns",
  "openclaw docs",
  "openclaw pairing",
  "openclaw qr",
  "openclaw channels",
  "openclaw system",
  "openclaw --version",
  "openclaw --help",
  "node -v",
  "node --version",
];

const INTERACTIVE_CMDS = [
  "gateway",
  "onboard",
  "configure",
  "config",
  "logs",
  "chat",
  "tui",
  "browser",
  "sandbox",
];

export function Terminal() {
  const [history, setHistory] = useState<HistoryEntry[]>([
    { type: "out", text: "╔══════════════════════════════╗" },
    { type: "out", text: "║   OpenClaw Terminal v2       ║" },
    { type: "out", text: "╚══════════════════════════════╝" },
    { type: "out", text: "↑↓ historial · TAB autocompletar · ^L limpiar" },
    { type: "out", text: "" },
  ]);
  const [input, setInput] = useState("");
  const [ctrlOn, setCtrlOn] = useState(false);
  const [altOn, setAltOn] = useState(false);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [cmdHistory, setCmdHistory] = useState<string[]>([]);
  const [, setHistIdx] = useState(-1);
  const scrollRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (scrollRef.current) {
      const scrollElement = scrollRef.current;
      // Use requestAnimationFrame for smoother scrolling
      const scroll = () => {
        scrollElement.scrollTop = scrollElement.scrollHeight;
        scrollElement.scrollLeft = 0;
        // Force a repaint
        void scrollElement.offsetHeight;
      };
      requestAnimationFrame(scroll);
    }
  }, [history]);

  useEffect(() => {
    const h = (e: Event) => {
      const cmd = (e as CustomEvent<string>).detail;
      if (cmd) runCmd(cmd);
    };
    window.addEventListener("terminal:run", h);
    return () => window.removeEventListener("terminal:run", h);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    try {
      const queued = sessionStorage.getItem("openclaw.pendingTerminalCommand");
      if (queued) {
        sessionStorage.removeItem("openclaw.pendingTerminalCommand");
        runCmd(queued);
      }
    } catch {
      // ignore storage issues
    }
  }, [runCmd]);

  useEffect(() => {
    if (input.length < 2) {
      setSuggestions([]);
      return;
    }
    const q = input.toLowerCase();
    setSuggestions(
      OC_COMMANDS.filter((c) => c.startsWith(q) && c !== input).slice(0, 5),
    );
  }, [input]);

  const append = (e: HistoryEntry) => setHistory((prev) => [...prev, e]);

  const histUp = useCallback(() => {
    setHistIdx((prev) => {
      const next = Math.min(prev + 1, cmdHistory.length - 1);
      if (cmdHistory[next] !== undefined) setInput(cmdHistory[next]);
      return next;
    });
  }, [cmdHistory]);

  const histDown = useCallback(() => {
    setHistIdx((prev) => {
      const next = Math.max(prev - 1, -1);
      setInput(next === -1 ? "" : cmdHistory[next]);
      return next;
    });
  }, [cmdHistory]);

  const runCmd = useCallback(
    (cmd: string) => {
      if (!cmd.trim()) return;

      // Immediate visual feedback
      append({ type: "cmd", text: cmd });
      setSuggestions([]);
      setInput("");
      setHistIdx(-1);
      setCmdHistory((prev) => [cmd, ...prev.slice(0, 49)]);

      // Scroll to bottom immediately
      setTimeout(() => {
        if (scrollRef.current) {
          scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
        }
      }, 10);

      const parts = cmd.trim().split(/\s+/);
      const isOC = parts[0] === "openclaw" || parts[0] === "oa";
      const sub = isOC ? (parts[1] ?? "") : parts[0];

      if (INTERACTIVE_CMDS.includes(sub)) {
        append({
          type: "out",
          text: `↗ Abriendo terminal interactivo: ${cmd}`,
        });
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        bridge.call("launchInteractiveCommand" as any, cmd);
        return;
      }

      // Execute command with immediate feedback
      const result = bridge.callJson<{ stdout?: string; stderr?: string }>(
        "runCommand",
        cmd,
      );

      // Process results immediately
      if (result?.stdout) {
        append({ type: "out", text: result.stdout });
        // Auto-scroll to show new output
        setTimeout(() => {
          if (scrollRef.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
          }
        }, 50);
      }
      if (result?.stderr) {
        append({ type: "err", text: result.stderr });
        setTimeout(() => {
          if (scrollRef.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
          }
        }, 50);
      }
      if (!result) {
        append({ type: "err", text: "Bridge no disponible." });
        setTimeout(() => {
          if (scrollRef.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
          }
        }, 50);
      }
    },
    [cmdHistory],
  ); // eslint-disable-line

  // ── Keyboard ─────────────────────────────────────────────────────────────
  const toggleCtrl = () => {
    setCtrlOn((v) => !v);
    setAltOn(false);
  };
  const toggleAlt = () => {
    setAltOn((v) => !v);
    setCtrlOn(false);
  };
  const typeChar = (ch: string) => {
    setInput((p) => p + ch);
    setCtrlOn(false);
    setAltOn(false);
    inputRef.current?.focus();
  };
  const k = (
    label: string,
    bg: string,
    fg: string,
    flex: number,
    onPress: () => void,
  ): KeyDef => ({ label, bg, fg, flex, onPress });

  const row1: KeyDef[] = [
    k("ESC", "rgba(248,113,113,0.15)", "#fca5a5", 1, () => {
      setInput("");
      setCtrlOn(false);
      setAltOn(false);
    }),
    k("TAB", "rgba(74,222,128,0.15)", "#86efac", 1, () => {
      if (suggestions[0]) {
        setInput(suggestions[0]);
        setSuggestions([]);
      } else typeChar("\t");
    }),
    k(
      ctrlOn ? "CTRL●" : "CTRL",
      ctrlOn ? "rgba(251,191,36,0.3)" : "rgba(251,191,36,0.1)",
      "#fbbf24",
      1.4,
      toggleCtrl,
    ),
    k(
      altOn ? "ALT●" : "ALT",
      altOn ? "rgba(251,191,36,0.3)" : "rgba(251,191,36,0.1)",
      "#fbbf24",
      1.2,
      toggleAlt,
    ),
    k("HOME", "var(--surface2)", "#c4b5fd", 1, () => setInput("")),
    k("END", "var(--surface2)", "#c4b5fd", 1, () => inputRef.current?.focus()),
    k("PGUP", "var(--surface2)", "#c4b5fd", 1, () =>
      scrollRef.current?.scrollBy(0, -200),
    ),
    k("PGDN", "var(--surface2)", "#c4b5fd", 1, () =>
      scrollRef.current?.scrollBy(0, 200),
    ),
  ];
  const row2: KeyDef[] = [
    k("←", "var(--surface2)", "#93c5fd", 1, () =>
      setInput((p) => p.slice(0, -1)),
    ),
    k("↑", "var(--surface2)", "#93c5fd", 1, histUp),
    k("↓", "var(--surface2)", "#93c5fd", 1, histDown),
    k("→", "var(--surface2)", "#93c5fd", 1, () => inputRef.current?.focus()),
    k("SPACE", "var(--surface2)", "#93c5fd", 1.5, () => typeChar(" ")),
    k("BKSP", "rgba(248,113,113,0.15)", "#fca5a5", 1, () =>
      setInput((p) => p.slice(0, -1)),
    ),
    k("DEL", "rgba(248,113,113,0.15)", "#fca5a5", 1, () => setInput("")),
  ];
  const row3: KeyDef[] = [
    k("↵ ENTER", "rgba(74,222,128,0.15)", "#86efac", 2, () => runCmd(input)),
    k("^C", "rgba(248,113,113,0.15)", "#fca5a5", 1, () => {
      setInput("");
      append({ type: "err", text: "^C" });
    }),
    k("^D", "rgba(248,113,113,0.05)", "#fca5a5", 1, () =>
      append({ type: "out", text: "^D" }),
    ),
    k("^Z", "rgba(251,191,36,0.15)", "#fbbf24", 1, () =>
      append({ type: "out", text: "^Z" }),
    ),
    k("^L", "var(--surface2)", "#c4b5fd", 1, () => setHistory([])),
    k("^U", "var(--surface2)", "#c4b5fd", 1, () => setInput("")),
    k("^K", "var(--surface2)", "#c4b5fd", 1, () =>
      setInput((p) => p.slice(0, p.lastIndexOf(" ") + 1)),
    ),
    k("^W", "var(--surface2)", "#c4b5fd", 1, () =>
      setInput((p) => p.replace(/\S+\s*$/, "")),
    ),
  ];

  const renderRow = (keys: KeyDef[]) => (
    <div style={S.kbRow}>
      {keys.map((k, i) => (
        <button
          key={i}
          style={{
            ...S.kbKey,
            flex: k.flex ?? 1,
            background: k.bg,
            color: k.fg,
          }}
          onPointerDown={(e) => {
            e.preventDefault();
            k.onPress();
          }}
          onTouchStart={(e) => {
            e.currentTarget.style.transform = "scale(0.95)";
            e.currentTarget.style.opacity = "0.8";
          }}
          onTouchEnd={(e) => {
            e.currentTarget.style.transform = "scale(1)";
            e.currentTarget.style.opacity = "1";
          }}
          aria-label={`Tecla ${k.label}`}
        >
          {k.label}
        </button>
      ))}
    </div>
  );

  return (
    <div className="terminal-container" style={S.root}>
      {/* Output */}
      <div
        ref={scrollRef}
        className="terminal-output no-scrollbar"
        style={S.output}
      >
        {history.map((h, i) => (
          <div
            key={i}
            style={{
              ...S.line,
              color:
                h.type === "cmd"
                  ? "#4ade80"
                  : h.type === "err"
                    ? "#f87171"
                    : "#e2e8f0",
              fontWeight: h.type === "cmd" ? 600 : 400,
            }}
          >
            {h.type === "cmd" && <span style={{ color: "#6366f1" }}>$ </span>}
            {h.text}
          </div>
        ))}
      </div>

      {/* Autocomplete suggestions */}
      {suggestions.length > 0 && (
        <div className="terminal-suggestions">
          {suggestions.map((s, i) => (
            <button
              key={i}
              className="terminal-suggestion"
              onPointerDown={(e) => {
                e.preventDefault();
                setInput(s);
                setSuggestions([]);
              }}
              onTouchStart={(e) => {
                e.currentTarget.style.transform = "scale(0.95)";
                e.currentTarget.style.opacity = "0.8";
              }}
              onTouchEnd={(e) => {
                e.currentTarget.style.transform = "scale(1)";
                e.currentTarget.style.opacity = "1";
              }}
              aria-label={`Autocompletar con ${s}`}
            >
              {s}
            </button>
          ))}
        </div>
      )}

      {/* Input area & Keyboard */}
      <div className="terminal-keyboard-area" style={S.keyboardArea}>
        {/* Quick Commands (hidden scrollbar) */}
        <div className="terminal-quick-cmds no-scrollbar" style={S.quickCmds}>
          {[
            { label: "gateway", cmd: "openclaw gateway", color: "#60a5fa" },
            { label: "status", cmd: "openclaw status", color: "#4ade80" },
            { label: "health", cmd: "openclaw health", color: "#4ade80" },
            { label: "models", cmd: "openclaw models", color: "#c4b5fd" },
            { label: "doctor", cmd: "openclaw doctor", color: "#fb923c" },
            { label: "update", cmd: "openclaw update", color: "#4ade80" },
            { label: "skills", cmd: "openclaw skills", color: "#facc15" },
            { label: "version", cmd: "openclaw --version", color: "#94a3b8" },
            { label: "tasks", cmd: "openclaw tasks", color: "#22d3ee" },
            { label: "logs", cmd: "openclaw logs", color: "#94a3b8" },
            { label: "node -v", cmd: "node -v", color: "#86efac" },
          ].map((q) => (
            <button
              key={q.cmd}
              style={{
                ...S.quickCmdBtn,
                color: q.color,
                borderColor: q.color + "30",
                background: q.color + "10",
              }}
              onPointerDown={(e) => {
                e.preventDefault();
                runCmd(q.cmd);
              }}
              onTouchStart={(e) => {
                e.currentTarget.style.transform = "scale(0.92)";
              }}
              onTouchEnd={(e) => {
                e.currentTarget.style.transform = "scale(1)";
              }}
              aria-label={`Ejecutar comando ${q.label}`}
            >
              {q.label}
            </button>
          ))}
        </div>

        {/* Input prompt */}
        <div className="terminal-input-row" style={S.inputRow}>
          <span className="terminal-prompt" style={S.prompt}>
            $
          </span>
          <input
            ref={inputRef}
            className="terminal-input"
            style={S.input}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                runCmd(input);
              }
              if (e.key === "Tab") {
                e.preventDefault();
                if (suggestions[0]) {
                  setInput(suggestions[0]);
                  setSuggestions([]);
                }
              }
              if (e.key === "ArrowUp") {
                e.preventDefault();
                histUp();
              }
              if (e.key === "ArrowDown") {
                e.preventDefault();
                histDown();
              }
            }}
            placeholder={t("chat_placeholder")}
            autoComplete="off"
            autoCorrect="off"
            autoCapitalize="off"
            spellCheck={false}
          />
          <button
            className="terminal-send-btn"
            style={S.sendBtn}
            onPointerDown={(e) => {
              e.preventDefault();
              runCmd(input);
            }}
            onTouchStart={(e) => {
              e.currentTarget.style.transform = "scale(0.92)";
            }}
            onTouchEnd={(e) => {
              e.currentTarget.style.transform = "scale(1)";
            }}
            aria-label="Ejecutar comando"
            disabled={!input.trim()}
          >
            ▶
          </button>
        </div>

        {/* Action Row */}
        <div className="terminal-action-row" style={S.actionRow}>
          <button
            className="terminal-action-btn"
            style={S.actionBtn}
            onPointerDown={(e) => {
              e.preventDefault();
              try {
                navigator.clipboard?.readText?.().then((txt) => {
                  if (txt) setInput((p) => p + txt);
                });
              } catch {
                /**/
              }
            }}
            onTouchStart={(e) => {
              e.currentTarget.style.opacity = "0.6";
            }}
            onTouchEnd={(e) => {
              e.currentTarget.style.opacity = "1";
            }}
            aria-label="Pegar desde portapapeles"
          >
            📋 Pegar
          </button>
          <button
            className="terminal-action-btn"
            style={{ ...S.actionBtn, color: "#f87171" }}
            onPointerDown={(e) => {
              e.preventDefault();
              setInput("");
            }}
            onTouchStart={(e) => {
              e.currentTarget.style.opacity = "0.6";
            }}
            onTouchEnd={(e) => {
              e.currentTarget.style.opacity = "1";
            }}
            aria-label="Limpiar entrada"
          >
            ✕ Limpiar
          </button>
        </div>

        {/* Keyboard layout */}
        <div className="terminal-keyboard" style={S.keyboard}>
          {renderRow(row1)}
          {renderRow(row2)}
          {renderRow(row3)}
        </div>
      </div>

      {/* Hide scrollbars globally for classes that use them */}
      <style>{`
        .no-scrollbar::-webkit-scrollbar { display: none; }
        .no-scrollbar { -ms-overflow-style: none; scrollbar-width: none; }
      `}</style>
    </div>
  );
}

const S: Record<string, React.CSSProperties> = {
  root: {
    display: "flex",
    flexDirection: "column",
    height: "100%",
    background: "var(--bg)",
    overflow: "hidden",
    fontSize: "32px",
  },
  output: {
    flex: 1,
    overflowY: "auto",
    padding: "12px 14px",
    fontFamily: "'JetBrains Mono','Courier New',monospace",
    fontSize: "32px",
    lineHeight: 1.7,
    WebkitOverflowScrolling: "touch",
    // Performance optimizations
    willChange: "auto",
    contain: "layout" as const,
    // Better scrolling performance
    scrollBehavior: "smooth",
    scrollPadding: "10px",
  },
  line: { marginBottom: 4, whiteSpace: "pre-wrap", wordBreak: "break-all" },

  suggestions: {
    display: "flex",
    flexWrap: "wrap",
    gap: 6,
    padding: "8px 12px",
    background: "var(--surface)",
    borderTop: "1px solid var(--border)",
  },
  suggestion: {
    background: "rgba(99,102,241,0.15)",
    border: "1px solid rgba(99,102,241,0.3)",
    borderRadius: 8,
    color: "#a5b4fc",
    padding: "6px 10px",
    cursor: "pointer",
    fontWeight: 600,
  },

  keyboardArea: {
    background: "rgba(8,8,16,0.95)",
    borderTop: "1px solid var(--border)",
    backdropFilter: "blur(16px)",
    flexShrink: 0,
    display: "flex",
    flexDirection: "column",
  },

  quickCmds: {
    display: "flex",
    gap: 6,
    overflowX: "auto",
    padding: "10px 12px 6px",
    WebkitOverflowScrolling: "touch",
  },
  quickCmdBtn: {
    flexShrink: 0,
    padding: "6px 12px",
    borderRadius: "var(--r-full)",
    border: "1px solid",
    fontWeight: 700,
    cursor: "pointer",
    whiteSpace: "nowrap",
    letterSpacing: "0.3px",
    transition: "transform 0.1s",
  },

  inputRow: {
    display: "flex",
    alignItems: "center",
    gap: 8,
    background: "var(--surface)",
    borderRadius: 12,
    border: "1px solid var(--border2)",
    padding: "6px 12px",
    margin: "6px 12px",
    boxShadow: "var(--sh-inset)",
  },
  prompt: {
    color: "#6366f1",
    fontWeight: 800,
    fontFamily: "'JetBrains Mono',monospace",
    flexShrink: 0,
  },
  input: {
    flex: 1,
    background: "transparent",
    border: "none",
    outline: "none",
    color: "var(--text)",
    fontFamily: "'JetBrains Mono',monospace",
    fontSize: "32px",
    padding: "4px 0",
    caretColor: "#6366f1",
    // Performance optimizations
    willChange: "auto",
    contain: "layout" as const,
  },
  sendBtn: {
    background: "linear-gradient(135deg,#6366f1,#8b5cf6)",
    border: "none",
    borderRadius: "var(--r-full)",
    color: "#fff",
    width: 34,
    height: 34,
    cursor: "pointer",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    flexShrink: 0,
    boxShadow: "0 4px 12px rgba(99,102,241,0.4)",
  },

  actionRow: { display: "flex", gap: 12, padding: "0 16px 8px" },
  actionBtn: {
    background: "transparent",
    border: "none",
    color: "var(--text3)",
    fontWeight: 700,
    cursor: "pointer",
    padding: "4px",
  },

  keyboard: { padding: "0 6px 10px" },
  kbRow: { display: "flex", gap: 4, marginBottom: 4 },
  kbKey: {
    minHeight: 38,
    borderRadius: 8,
    border: "1px solid rgba(255,255,255,0.05)",
    fontWeight: 700,
    cursor: "pointer",
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    userSelect: "none",
    WebkitUserSelect: "none",
    padding: "0 10px",
    letterSpacing: "0.2px",
  },
};
