"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  getRunStreamUrl,
  runsApi,
  type RunAction,
  type RunMode,
} from "@/lib/api";
import {
  connectResumableSse,
  type ResumableSseHandle,
  type SseEnvelope,
  type StreamConnectionState,
} from "@/lib/sse-client";

interface AiCopilotPanelProps {
  bookId: string;
  chapterId: string | null;
  chapterContent: string;
  onAppendText: (text: string) => void;
  onReplaceText: (text: string) => void;
}

interface ToolStepItem {
  callId: string;
  toolName: string;
  source: string;
  argumentsPreview: string;
  status: "running" | "ok" | "error";
  summary?: string;
  latencyMs?: number;
}

interface PersistedRunSnapshot {
  runId: string;
  mode: RunMode;
  action: RunAction;
  modelAlias: string;
  instruction: string;
  lastEventId: string | null;
  lastSeq: number;
  text: string;
  thought: string;
  toolSteps: ToolStepItem[];
  state: StreamConnectionState;
}

interface TokenUsage {
  inputTokens: number;
  cachedTokens: number;
  outputTokens: number;
}

const ACTION_OPTIONS: { value: RunAction; label: string }[] = [
  { value: "CONTINUE", label: "续写正文" },
  { value: "REWRITE", label: "扩写润色" },
  { value: "OUTLINE", label: "章节大纲" },
];

const MODEL_OPTIONS = [
  { value: "writer-main", label: "主力写作 (writer-main)" },
  { value: "critic-reasoner", label: "深度推理 (critic-reasoner)" },
  { value: "fast-router", label: "快速模型 (fast-router)" },
];

const STATE_BADGE: Record<StreamConnectionState, { label: string; dot: string }> = {
  idle: { label: "就绪", dot: "bg-muted-foreground/50" },
  connecting: { label: "连接中", dot: "bg-amber-400 animate-pulse" },
  streaming: { label: "流式执行中", dot: "bg-emerald-500 animate-pulse" },
  reconnecting: { label: "断线续连中", dot: "bg-amber-500 animate-pulse" },
  paused: { label: "已暂停（可改指令）", dot: "bg-sky-500" },
  completed: { label: "生成完成", dot: "bg-emerald-500" },
  cancelled: { label: "已取消", dot: "bg-muted-foreground" },
  error: { label: "异常中断", dot: "bg-rose-500" },
};

const SOURCE_STYLE: Record<string, string> = {
  mcp: "bg-emerald-500/15 text-emerald-600",
  skill: "bg-violet-500/15 text-violet-600",
  sandbox: "bg-amber-500/15 text-amber-600",
  builtin: "bg-sky-500/15 text-sky-600",
};

function storageKey(bookId: string, chapterId: string | null): string {
  return `inkflow:sse_run:${bookId}:${chapterId ?? "book"}`;
}

export function AiCopilotPanel({
  bookId,
  chapterId,
  chapterContent,
  onAppendText,
  onReplaceText,
}: AiCopilotPanelProps) {
  const [mode, setMode] = useState<RunMode>("AGENT");
  const [action, setAction] = useState<RunAction>("CONTINUE");
  const [modelAlias, setModelAlias] = useState("writer-main");
  const [instruction, setInstruction] = useState("");
  const [revisedInstruction, setRevisedInstruction] = useState("");

  const [runId, setRunId] = useState<string | null>(null);
  const [lastEventId, setLastEventId] = useState<string | null>(null);
  const [lastSeq, setLastSeq] = useState(0);
  const [connState, setConnState] = useState<StreamConnectionState>("idle");
  const [statusDetail, setStatusDetail] = useState<string | null>(null);

  const [generatedText, setGeneratedText] = useState("");
  const [thoughtText, setThoughtText] = useState("");
  const [toolSteps, setToolSteps] = useState<ToolStepItem[]>([]);
  const [discoveredTools, setDiscoveredTools] = useState<string[]>([]);
  const [showThought, setShowThought] = useState(true);
  const [usage, setUsage] = useState<TokenUsage | null>(null);
  const [busyControl, setBusyControl] = useState(false);

  const sseHandleRef = useRef<ResumableSseHandle | null>(null);
  const seenSeqRef = useRef(0);

  const persistSnapshot = useCallback(
    (snapshot: PersistedRunSnapshot | null) => {
      if (typeof window === "undefined") return;
      const key = storageKey(bookId, chapterId);
      if (!snapshot) {
        window.sessionStorage.removeItem(key);
        return;
      }
      window.sessionStorage.setItem(key, JSON.stringify(snapshot));
    },
    [bookId, chapterId],
  );

  const attachStream = useCallback(
    (
      targetRunId: string,
      cursor: string | null,
      seed: {
        mode: RunMode;
        action: RunAction;
        modelAlias: string;
        instruction: string;
        text: string;
        thought: string;
        toolSteps: ToolStepItem[];
        seq: number;
      },
    ) => {
      sseHandleRef.current?.disconnect();
      seenSeqRef.current = seed.seq;

      let accText = seed.text;
      let accThought = seed.thought;
      let accTools = [...seed.toolSteps];
      let accLastEventId = cursor;
      let accSeq = seed.seq;

      const saveCurrent = (nextState: StreamConnectionState) => {
        persistSnapshot({
          runId: targetRunId,
          mode: seed.mode,
          action: seed.action,
          modelAlias: seed.modelAlias,
          instruction: seed.instruction,
          lastEventId: accLastEventId,
          lastSeq: accSeq,
          text: accText,
          thought: accThought,
          toolSteps: accTools,
          state: nextState,
        });
      };

      sseHandleRef.current = connectResumableSse({
        url: getRunStreamUrl(targetRunId),
        initialLastEventId: cursor,
        onLastEventId(id) {
          accLastEventId = id;
          setLastEventId(id);
        },
        onStateChange(nextState, meta) {
          setConnState(nextState);
          setStatusDetail(meta?.detail ?? null);
          saveCurrent(nextState);
        },
        onEvent(envelope: SseEnvelope) {
          if (envelope.type === "ping") {
            return;
          }
          if (envelope.seq > 0 && envelope.seq <= seenSeqRef.current) {
            return;
          }
          if (envelope.seq > 0) {
            seenSeqRef.current = envelope.seq;
            accSeq = envelope.seq;
            setLastSeq(envelope.seq);
          }

          if (envelope.type === "run_started") {
            const tools = envelope.payload.tools;
            if (Array.isArray(tools)) {
              setDiscoveredTools(tools.map(String));
            }
          } else if (envelope.type === "thought_delta") {
            const delta = String(envelope.payload.content ?? "");
            accThought += delta;
            setThoughtText(accThought);
          } else if (envelope.type === "tool_call") {
            const callId = String(envelope.payload.call_id ?? `step-${accSeq}`);
            const toolName = String(envelope.payload.tool_name ?? "tool");
            const source = String(envelope.payload.source ?? "builtin");
            const argsPreview = JSON.stringify(envelope.payload.arguments ?? {});
            accTools = [
              ...accTools,
              {
                callId,
                toolName,
                source,
                argumentsPreview: argsPreview,
                status: "running",
              },
            ];
            setToolSteps(accTools);
            saveCurrent("streaming");
          } else if (envelope.type === "tool_result") {
            const callId = String(envelope.payload.call_id ?? "");
            const ok = Boolean(envelope.payload.success);
            const summary = String(envelope.payload.summary ?? "");
            const latencyMs = Number(envelope.payload.latency_ms ?? 0);
            accTools = accTools.map((step) =>
              step.callId === callId
                ? { ...step, status: ok ? "ok" : "error", summary, latencyMs }
                : step,
            );
            setToolSteps(accTools);
            saveCurrent("streaming");
          } else if (envelope.type === "text_delta") {
            const delta = String(envelope.payload.content ?? "");
            accText += delta;
            setGeneratedText(accText);
            saveCurrent("streaming");
          } else if (envelope.type === "usage") {
            setUsage({
              inputTokens: Number(envelope.payload.input_tokens ?? 0),
              cachedTokens: Number(envelope.payload.cached_tokens ?? 0),
              outputTokens: Number(envelope.payload.output_tokens ?? 0),
            });
          } else if (envelope.type === "interrupt") {
            setStatusDetail("已在安全检查点暂停（上游 LLM 已停流省 Token），可修改指令后继续");
            saveCurrent("paused");
          } else if (envelope.type === "error") {
            setStatusDetail(String(envelope.payload.message ?? "生成异常"));
            saveCurrent("error");
          } else if (envelope.type === "done") {
            const doneStatus = envelope.payload.status === "cancelled" ? "cancelled" : "completed";
            saveCurrent(doneStatus);
          }
        },
      });
    },
    [persistSnapshot],
  );

  useEffect(() => {
    sseHandleRef.current?.disconnect();
    sseHandleRef.current = null;
    let active = true;

    const restore = async () => {
      const key = storageKey(bookId, chapterId);
      const raw = typeof window !== "undefined" ? window.sessionStorage.getItem(key) : null;

      if (raw) {
        try {
          const saved = JSON.parse(raw) as PersistedRunSnapshot;
          if (!active) return;
          setRunId(saved.runId);
          setMode(saved.mode ?? "AGENT");
          setAction(saved.action);
          setModelAlias(saved.modelAlias);
          setInstruction(saved.instruction);
          setLastEventId(saved.lastEventId);
          setLastSeq(saved.lastSeq);
          setGeneratedText(saved.text);
          setThoughtText(saved.thought);
          setToolSteps(saved.toolSteps ?? []);
          setConnState(saved.state);
          setStatusDetail(null);
          setUsage(null);

          if (
            saved.state === "streaming" ||
            saved.state === "connecting" ||
            saved.state === "reconnecting"
          ) {
            setStatusDetail("检测到页面刷新，正在携带 Last-Event-ID 自动断点续连...");
            attachStream(saved.runId, saved.lastEventId, {
              mode: saved.mode ?? "AGENT",
              action: saved.action,
              modelAlias: saved.modelAlias,
              instruction: saved.instruction,
              text: saved.text,
              thought: saved.thought,
              toolSteps: saved.toolSteps ?? [],
              seq: saved.lastSeq,
            });
          }
          return;
        } catch {
          window.sessionStorage.removeItem(key);
        }
      }

      try {
        const recent = await runsApi.list(bookId, chapterId ?? undefined);
        if (!active) return;
        const resumable = recent.find(
          (r) => r.status === "RUNNING" || r.status === "QUEUED" || r.status === "PAUSED",
        );
        if (resumable) {
          setRunId(resumable.id);
          setMode(resumable.mode ?? "AGENT");
          setAction(resumable.action);
          setModelAlias(resumable.modelAlias);
          setInstruction(resumable.instruction);
          setLastEventId(resumable.lastEventId);
          setLastSeq(resumable.lastSeq);
          setGeneratedText(resumable.accumulatedText);
          setThoughtText("");
          setToolSteps([]);
          if (resumable.status === "PAUSED") {
            setConnState("paused");
            setStatusDetail("已恢复上次暂停的检查点，可修改指令后继续生成");
          } else {
            attachStream(resumable.id, null, {
              mode: resumable.mode ?? "AGENT",
              action: resumable.action,
              modelAlias: resumable.modelAlias,
              instruction: resumable.instruction,
              text: "",
              thought: "",
              toolSteps: [],
              seq: 0,
            });
          }
          return;
        }
      } catch {
        // Ignore silent recovery errors
      }

      if (!active) return;
      setRunId(null);
      setLastEventId(null);
      setLastSeq(0);
      setGeneratedText("");
      setThoughtText("");
      setToolSteps([]);
      setDiscoveredTools([]);
      setUsage(null);
      setConnState("idle");
      setStatusDetail(null);
    };

    void restore();

    return () => {
      active = false;
      sseHandleRef.current?.disconnect();
      sseHandleRef.current = null;
    };
  }, [bookId, chapterId, attachStream]);

  async function handleStart() {
    setBusyControl(true);
    setStatusDetail(null);
    setGeneratedText("");
    setThoughtText("");
    setToolSteps([]);
    setDiscoveredTools([]);
    setUsage(null);
    setLastEventId(null);
    setLastSeq(0);
    setRevisedInstruction("");

    try {
      const created = await runsApi.start({
        bookId,
        chapterId: chapterId ?? undefined,
        mode,
        action,
        modelAlias,
        instruction,
        chapterContext: chapterContent,
      });
      setRunId(created.id);
      attachStream(created.id, null, {
        mode,
        action,
        modelAlias,
        instruction,
        text: "",
        thought: "",
        toolSteps: [],
        seq: 0,
      });
    } catch (err) {
      setConnState("error");
      setStatusDetail(err instanceof Error ? err.message : "启动流式任务失败");
    } finally {
      setBusyControl(false);
    }
  }

  async function handlePause() {
    if (!runId) return;
    setBusyControl(true);
    try {
      await runsApi.pause(runId);
      setStatusDetail("已发送暂停信号，等待检查点落盘...");
    } catch (err) {
      setStatusDetail(err instanceof Error ? err.message : "暂停失败");
    } finally {
      setBusyControl(false);
    }
  }

  async function handleResume() {
    if (!runId) return;
    setBusyControl(true);
    setStatusDetail("正在从断点继续生成...");
    try {
      const nextInstruction = revisedInstruction.trim() || instruction;
      const resumed = await runsApi.resume(runId, revisedInstruction.trim() || undefined);
      setInstruction(resumed.instruction);
      setRevisedInstruction("");
      attachStream(runId, lastEventId, {
        mode,
        action,
        modelAlias,
        instruction: nextInstruction,
        text: generatedText,
        thought: thoughtText,
        toolSteps,
        seq: lastSeq,
      });
    } catch (err) {
      setConnState("error");
      setStatusDetail(err instanceof Error ? err.message : "恢复生成失败");
    } finally {
      setBusyControl(false);
    }
  }

  async function handleCancel() {
    if (!runId) return;
    setBusyControl(true);
    try {
      await runsApi.cancel(runId);
      if (connState === "paused") {
        setConnState("cancelled");
        setStatusDetail("已取消暂停中的任务");
        persistSnapshot(null);
      }
    } catch (err) {
      setStatusDetail(err instanceof Error ? err.message : "取消失败");
    } finally {
      setBusyControl(false);
    }
  }

  function handleClear() {
    sseHandleRef.current?.disconnect();
    persistSnapshot(null);
    setRunId(null);
    setLastEventId(null);
    setLastSeq(0);
    setGeneratedText("");
    setThoughtText("");
    setToolSteps([]);
    setDiscoveredTools([]);
    setUsage(null);
    setConnState("idle");
    setStatusDetail(null);
  }

  const isRunning =
    connState === "connecting" ||
    connState === "streaming" ||
    connState === "reconnecting";
  const badge = STATE_BADGE[connState];

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden text-xs">
      <div className="space-y-2.5 border-b border-border p-3">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-1.5">
            <span className={`inline-block h-2 w-2 rounded-full ${badge.dot}`} />
            <span className="font-medium">{badge.label}</span>
          </div>
          {runId && (
            <span className="font-mono text-[10px] text-muted-foreground" title={runId}>
              seq:{lastSeq}
            </span>
          )}
        </div>

        {/* Agent vs Direct Mode Toggle */}
        <div className="grid grid-cols-2 gap-1 rounded-md bg-muted/60 p-0.5">
          <button
            type="button"
            disabled={isRunning || connState === "paused"}
            onClick={() => setMode("AGENT")}
            className={`rounded py-1 text-[11px] font-medium transition-colors ${
              mode === "AGENT"
                ? "bg-background text-foreground shadow-xs"
                : "text-muted-foreground hover:text-foreground"
            }`}
          >
            Agent 模式 (MCP+Skill)
          </button>
          <button
            type="button"
            disabled={isRunning || connState === "paused"}
            onClick={() => setMode("DIRECT")}
            className={`rounded py-1 text-[11px] font-medium transition-colors ${
              mode === "DIRECT"
                ? "bg-background text-foreground shadow-xs"
                : "text-muted-foreground hover:text-foreground"
            }`}
          >
            直连流式 (Direct)
          </button>
        </div>

        <div className="grid grid-cols-3 gap-1">
          {ACTION_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              disabled={isRunning || connState === "paused"}
              onClick={() => setAction(opt.value)}
              className={`rounded-md border px-2 py-1 text-[11px] font-medium transition-colors ${
                action === opt.value
                  ? "border-primary bg-primary text-primary-foreground"
                  : "border-border bg-background text-muted-foreground hover:text-foreground"
              } disabled:opacity-50`}
            >
              {opt.label}
            </button>
          ))}
        </div>

        <select
          value={modelAlias}
          disabled={isRunning || connState === "paused"}
          onChange={(e) => setModelAlias(e.target.value)}
          className="w-full rounded-md border border-input bg-background px-2.5 py-1.5 text-xs outline-none disabled:opacity-60"
        >
          {MODEL_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>

        {connState === "paused" ? (
          <div className="space-y-2">
            <textarea
              rows={2}
              value={revisedInstruction}
              onChange={(e) => setRevisedInstruction(e.target.value)}
              placeholder="可选：暂停期间补充或修改后续剧情指令..."
              className="w-full resize-none rounded-md border border-sky-500/50 bg-background px-2.5 py-1.5 text-xs outline-none focus:ring-1 focus:ring-sky-500"
            />
            <div className="flex gap-1.5">
              <Button
                size="xs"
                className="flex-1"
                disabled={busyControl}
                onClick={() => void handleResume()}
              >
                从断点继续生成
              </Button>
              <Button
                size="xs"
                variant="destructive"
                disabled={busyControl}
                onClick={() => void handleCancel()}
              >
                取消
              </Button>
            </div>
          </div>
        ) : (
          <div className="space-y-2">
            <textarea
              rows={2}
              value={instruction}
              disabled={isRunning}
              onChange={(e) => setInstruction(e.target.value)}
              placeholder="输入写作要求，如：先查林青的伤势与伏笔，再写一场雨夜剑斗..."
              className="w-full resize-none rounded-md border border-input bg-background px-2.5 py-1.5 text-xs outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
            />

            {isRunning ? (
              <div className="flex flex-wrap gap-1.5">
                <Button
                  size="xs"
                  variant="secondary"
                  className="flex-1"
                  disabled={busyControl}
                  onClick={() => void handlePause()}
                >
                  暂停
                </Button>
                <Button
                  size="xs"
                  variant="destructive"
                  disabled={busyControl}
                  onClick={() => void handleCancel()}
                >
                  取消
                </Button>
                <Button
                  size="xs"
                  variant="outline"
                  title="故意断开本地连接并携带 Last-Event-ID 自动重连补帧"
                  onClick={() => sseHandleRef.current?.simulateDrop()}
                >
                  模拟断线续连
                </Button>
              </div>
            ) : (
              <div className="flex gap-1.5">
                <Button
                  size="xs"
                  className="flex-1"
                  disabled={busyControl}
                  onClick={() => void handleStart()}
                >
                  {mode === "AGENT" ? "启动 Agent 创作" : "开始流式生成"}
                </Button>
                {(generatedText || toolSteps.length > 0 || connState !== "idle") && (
                  <Button size="xs" variant="outline" onClick={handleClear}>
                    清空
                  </Button>
                )}
              </div>
            )}
          </div>
        )}

        {statusDetail && (
          <div
            className={`rounded-md px-2.5 py-1.5 text-[11px] leading-relaxed ${
              connState === "error"
                ? "border border-destructive/40 bg-destructive/10 text-destructive"
                : "bg-muted text-muted-foreground"
            }`}
          >
            {statusDetail}
          </div>
        )}
      </div>

      {/* Trajectory Timeline + Thought + Streaming Prose */}
      <div className="flex min-h-0 flex-1 flex-col space-y-3 overflow-y-auto p-3">
        {discoveredTools.length > 0 && (
          <div className="rounded-md bg-muted/40 px-2.5 py-1.5 font-mono text-[10px] text-muted-foreground">
            已挂载 {discoveredTools.length} 个工具：{discoveredTools.join(", ")}
          </div>
        )}

        {toolSteps.length > 0 && (
          <div className="space-y-1.5 rounded-lg border border-border bg-card p-2.5">
            <div className="font-mono text-[10px] font-semibold text-muted-foreground">
              Agent 执行轨迹 ({toolSteps.length} 步)
            </div>
            <ol className="space-y-1.5">
              {toolSteps.map((step) => (
                <li
                  key={step.callId}
                  className="rounded border border-border/70 bg-background p-2 text-[11px]"
                >
                  <div className="flex items-center justify-between gap-1.5">
                    <div className="flex min-w-0 items-center gap-1.5">
                      <span
                        className={`rounded px-1 py-0.5 font-mono text-[9px] uppercase ${
                          SOURCE_STYLE[step.source] ?? SOURCE_STYLE.builtin
                        }`}
                      >
                        {step.source}
                      </span>
                      <span className="truncate font-mono font-medium">
                        {step.toolName}
                      </span>
                    </div>
                    <span className="shrink-0 font-mono text-[10px] text-muted-foreground">
                      {step.status === "running"
                        ? "执行中..."
                        : step.status === "ok"
                          ? `✓ ${step.latencyMs ?? 0}ms`
                          : "✕ 失败"}
                    </span>
                  </div>
                  <div className="mt-1 truncate font-mono text-[10px] text-muted-foreground">
                    args: {step.argumentsPreview}
                  </div>
                  {step.summary && (
                    <div className="mt-1 line-clamp-2 text-[10px] text-muted-foreground">
                      {step.summary}
                    </div>
                  )}
                </li>
              ))}
            </ol>
          </div>
        )}

        {thoughtText && (
          <div className="rounded-lg border border-border bg-muted/40 p-2.5">
            <button
              type="button"
              onClick={() => setShowThought((v) => !v)}
              className="flex w-full items-center justify-between font-mono text-[10px] font-semibold text-muted-foreground"
            >
              <span>思考过程 (thought_delta)</span>
              <span>{showThought ? "收起" : "展开"}</span>
            </button>
            {showThought && (
              <div className="mt-1.5 whitespace-pre-wrap text-[11px] leading-relaxed text-muted-foreground">
                {thoughtText}
              </div>
            )}
          </div>
        )}

        {generatedText ? (
          <div className="rounded-lg border border-border bg-background p-3 leading-relaxed">
            <div className="whitespace-pre-wrap text-xs text-foreground">
              {generatedText}
              {connState === "streaming" && (
                <span className="ml-0.5 inline-block h-3.5 w-1.5 animate-pulse bg-primary align-middle" />
              )}
            </div>
          </div>
        ) : (
          toolSteps.length === 0 && (
            <div className="rounded-lg border border-dashed border-border p-4 text-center text-[11px] leading-relaxed text-muted-foreground">
              <p className="font-medium text-foreground">Phase 2 手写 ReAct Agent Core</p>
              <p className="mt-1">
                在「设定库」录入人物/世界观/伏笔后，启动 Agent 模式即可观察实时 MCP 查设定、渐进式加载写作 Skill 与流式正文输出。
              </p>
            </div>
          )
        )}
      </div>

      {/* Footer Actions & Telemetry */}
      <div className="space-y-2 border-t border-border bg-card p-3">
        {generatedText && !isRunning && (
          <div className="flex gap-1.5">
            <Button
              size="xs"
              className="flex-1"
              onClick={() => onAppendText(generatedText)}
            >
              追加到正文
            </Button>
            <Button
              size="xs"
              variant="outline"
              onClick={() => onReplaceText(generatedText)}
            >
              替换正文
            </Button>
          </div>
        )}

        <div className="space-y-0.5 font-mono text-[10px] text-muted-foreground">
          {lastEventId && (
            <div className="truncate" title={lastEventId}>
              Last-Event-ID: {lastEventId}
            </div>
          )}
          {usage && (
            <div>
              Tokens: in={usage.inputTokens} (cache={usage.cachedTokens}) / out={usage.outputTokens}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
