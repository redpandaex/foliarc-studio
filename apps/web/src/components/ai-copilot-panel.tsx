"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  getRunStreamUrl,
  runsApi,
  type RunAction,
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

interface PersistedRunSnapshot {
  runId: string;
  action: RunAction;
  modelAlias: string;
  instruction: string;
  lastEventId: string | null;
  lastSeq: number;
  text: string;
  thought: string;
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
  streaming: { label: "流式生成中", dot: "bg-emerald-500 animate-pulse" },
  reconnecting: { label: "断线续连中", dot: "bg-amber-500 animate-pulse" },
  paused: { label: "已暂停（可改指令）", dot: "bg-sky-500" },
  completed: { label: "生成完成", dot: "bg-emerald-500" },
  cancelled: { label: "已取消", dot: "bg-muted-foreground" },
  error: { label: "异常中断", dot: "bg-rose-500" },
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
  const [showThought, setShowThought] = useState(true);
  const [usage, setUsage] = useState<TokenUsage | null>(null);
  const [busyControl, setBusyControl] = useState(false);

  const sseHandleRef = useRef<ResumableSseHandle | null>(null);
  const seenSeqRef = useRef(0);
  const stateRef = useRef<PersistedRunSnapshot | null>(null);

  const persistSnapshot = useCallback(
    (snapshot: PersistedRunSnapshot | null) => {
      stateRef.current = snapshot;
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
        action: RunAction;
        modelAlias: string;
        instruction: string;
        text: string;
        thought: string;
        seq: number;
      },
    ) => {
      sseHandleRef.current?.disconnect();
      seenSeqRef.current = seed.seq;

      let accText = seed.text;
      let accThought = seed.thought;
      let accLastEventId = cursor;
      let accSeq = seed.seq;

      const saveCurrent = (nextState: StreamConnectionState) => {
        persistSnapshot({
          runId: targetRunId,
          action: seed.action,
          modelAlias: seed.modelAlias,
          instruction: seed.instruction,
          lastEventId: accLastEventId,
          lastSeq: accSeq,
          text: accText,
          thought: accThought,
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

          if (envelope.type === "thought_delta") {
            const delta = String(envelope.payload.content ?? "");
            accThought += delta;
            setThoughtText(accThought);
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

  // Restore active or paused run after page refresh or chapter switch
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
          setAction(saved.action);
          setModelAlias(saved.modelAlias);
          setInstruction(saved.instruction);
          setLastEventId(saved.lastEventId);
          setLastSeq(saved.lastSeq);
          setGeneratedText(saved.text);
          setThoughtText(saved.thought);
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
              action: saved.action,
              modelAlias: saved.modelAlias,
              instruction: saved.instruction,
              text: saved.text,
              thought: saved.thought,
              seq: saved.lastSeq,
            });
          }
          return;
        } catch {
          window.sessionStorage.removeItem(key);
        }
      }

      // Fallback: inspect latest server run for this chapter
      try {
        const recent = await runsApi.list(bookId, chapterId ?? undefined);
        if (!active) return;
        const resumable = recent.find(
          (r) => r.status === "RUNNING" || r.status === "QUEUED" || r.status === "PAUSED",
        );
        if (resumable) {
          setRunId(resumable.id);
          setAction(resumable.action);
          setModelAlias(resumable.modelAlias);
          setInstruction(resumable.instruction);
          setLastEventId(resumable.lastEventId);
          setLastSeq(resumable.lastSeq);
          setGeneratedText(resumable.accumulatedText);
          setThoughtText("");
          if (resumable.status === "PAUSED") {
            setConnState("paused");
            setStatusDetail("已恢复上次暂停的检查点，可修改指令后继续生成");
          } else {
            attachStream(resumable.id, null, {
              action: resumable.action,
              modelAlias: resumable.modelAlias,
              instruction: resumable.instruction,
              text: "",
              thought: "",
              seq: 0,
            });
          }
          return;
        }
      } catch {
        // Ignore silent recovery errors when backend is unavailable
      }

      if (!active) return;
      setRunId(null);
      setLastEventId(null);
      setLastSeq(0);
      setGeneratedText("");
      setThoughtText("");
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
    setUsage(null);
    setLastEventId(null);
    setLastSeq(0);
    setRevisedInstruction("");

    try {
      const created = await runsApi.start({
        bookId,
        chapterId: chapterId ?? undefined,
        action,
        modelAlias,
        instruction,
        chapterContext: chapterContent,
      });
      setRunId(created.id);
      attachStream(created.id, null, {
        action,
        modelAlias,
        instruction,
        text: "",
        thought: "",
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
        action,
        modelAlias,
        instruction: nextInstruction,
        text: generatedText,
        thought: thoughtText,
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
      {/* Control Form */}
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
              placeholder="输入写作要求，如：描写雨夜神秘剑客现身，制造悬念..."
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
                  开始流式生成
                </Button>
                {(generatedText || connState !== "idle") && (
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

      {/* Streaming Output Area */}
      <div className="flex min-h-0 flex-1 flex-col overflow-y-auto p-3 space-y-3">
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
          <div className="rounded-lg border border-dashed border-border p-4 text-center text-[11px] leading-relaxed text-muted-foreground">
            <p className="font-medium text-foreground">Phase 1 企业级 SSE 流式引擎</p>
            <p className="mt-1">
              链路：Next.js (fetch Stream) → Java WebFlux (Redis Streams XREAD) → Python FastAPI → OpenAI 兼容大模型
            </p>
            <p className="mt-1">
              生成过程中可随时点击「模拟断线续连」、直接刷新页面（F5）或「暂停改指令后继续」。
            </p>
          </div>
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
