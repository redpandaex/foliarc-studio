"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  getRunStreamUrl,
  runsApi,
  type AgentCheckpointItem,
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
  agent?: string;
  argumentsPreview: string;
  status: "running" | "ok" | "error";
  summary?: string;
  latencyMs?: number;
}

interface RouteInfo {
  intent: string;
  layer: string;
  reason: string;
  recommendedSkill?: string | null;
}

interface CriticInfo {
  passed: boolean;
  score: number;
  issues: string[];
  revisionCount: number;
  maxRevisions: number;
}

interface OutlineApprovalState {
  outlineText: string;
  beats: string[];
  checkpointStep?: number;
  message?: string;
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
  activeAgent?: string | null;
  routeInfo?: RouteInfo | null;
  criticInfo?: CriticInfo | null;
  outlineApproval?: OutlineApprovalState | null;
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

const PIPELINE_NODES = [
  { id: "router", label: "路由" },
  { id: "director", label: "总编" },
  { id: "lorekeeper", label: "设定" },
  { id: "writer", label: "执笔" },
  { id: "critic", label: "审稿" },
];

const STATE_BADGE: Record<StreamConnectionState, { label: string; dot: string }> = {
  idle: { label: "就绪", dot: "bg-muted-foreground/50" },
  connecting: { label: "连接中", dot: "bg-amber-400 animate-pulse" },
  streaming: { label: "流式执行中", dot: "bg-emerald-500 animate-pulse" },
  reconnecting: { label: "断线续连中", dot: "bg-amber-500 animate-pulse" },
  paused: { label: "等待确认 / 已暂停", dot: "bg-sky-500" },
  completed: { label: "生成完成", dot: "bg-emerald-500" },
  cancelled: { label: "已取消", dot: "bg-muted-foreground" },
  error: { label: "异常中断", dot: "bg-rose-500" },
};

const SOURCE_STYLE: Record<string, string> = {
  mcp: "bg-emerald-500/15 text-emerald-600",
  skill: "bg-violet-500/15 text-violet-600",
  rag: "bg-indigo-500/15 text-indigo-600",
  memory: "bg-teal-500/15 text-teal-600",
  sandbox: "bg-amber-500/15 text-amber-600",
  handoff: "bg-fuchsia-500/15 text-fuchsia-600",
  builtin: "bg-sky-500/15 text-sky-600",
};

function storageKey(bookId: string, chapterId: string | null): string {
  return `foliarc:sse_run:${bookId}:${chapterId ?? "book"}`;
}

export function AiCopilotPanel({
  bookId,
  chapterId,
  chapterContent,
  onAppendText,
  onReplaceText,
}: AiCopilotPanelProps) {
  const [mode, setMode] = useState<RunMode>("MULTI_AGENT");
  const [action, setAction] = useState<RunAction>("CONTINUE");
  const [modelAlias, setModelAlias] = useState("writer-main");
  const [requireOutlineApproval, setRequireOutlineApproval] = useState(true);
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

  // Phase 4 Multi-Agent + HITL + Checkpoints + Diff review state
  const [activeAgent, setActiveAgent] = useState<string | null>(null);
  const [routeInfo, setRouteInfo] = useState<RouteInfo | null>(null);
  const [criticInfo, setCriticInfo] = useState<CriticInfo | null>(null);
  const [outlineApproval, setOutlineApproval] = useState<OutlineApprovalState | null>(null);
  const [editedOutline, setEditedOutline] = useState("");
  const [approvalFeedback, setApprovalFeedback] = useState("");
  const [checkpoints, setCheckpoints] = useState<AgentCheckpointItem[]>([]);
  const [showCheckpoints, setShowCheckpoints] = useState(false);
  const [diffReviewOpen, setDiffReviewOpen] = useState(false);
  const [acceptedParagraphs, setAcceptedParagraphs] = useState<Record<number, boolean>>({});

  const sseHandleRef = useRef<ResumableSseHandle | null>(null);
  const seenSeqRef = useRef(0);

  const generatedParagraphs = useMemo(
    () =>
      generatedText
        .split(/\n{2,}/)
        .map((p) => p.trim())
        .filter(Boolean),
    [generatedText],
  );

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

  const loadCheckpointsForRun = useCallback(async (targetRunId: string) => {
    try {
      const items = await runsApi.listCheckpoints(targetRunId);
      setCheckpoints(items);
    } catch {
      // Ignore optional checkpoint fetch failures
    }
  }, []);

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
        activeAgent?: string | null;
        routeInfo?: RouteInfo | null;
        criticInfo?: CriticInfo | null;
        outlineApproval?: OutlineApprovalState | null;
      },
    ) => {
      sseHandleRef.current?.disconnect();
      seenSeqRef.current = seed.seq;

      let accText = seed.text;
      let accThought = seed.thought;
      let accTools = [...seed.toolSteps];
      let accLastEventId = cursor;
      let accSeq = seed.seq;
      let accAgent = seed.activeAgent ?? null;
      let accRoute = seed.routeInfo ?? null;
      let accCritic = seed.criticInfo ?? null;
      let accOutline = seed.outlineApproval ?? null;

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
          activeAgent: accAgent,
          routeInfo: accRoute,
          criticInfo: accCritic,
          outlineApproval: accOutline,
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
          if (meta?.detail) {
            setStatusDetail(meta.detail);
          }
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
          if (envelope.agent) {
            accAgent = envelope.agent;
            setActiveAgent(envelope.agent);
          }

          if (envelope.type === "run_started") {
            const tools = envelope.payload.tools;
            if (Array.isArray(tools)) {
              setDiscoveredTools(tools.map(String));
            }
          } else if (envelope.type === "route_decision") {
            accRoute = {
              intent: String(envelope.payload.intent ?? "CHAPTER_COMPOSE"),
              layer: String(envelope.payload.layer ?? "DEFAULT"),
              reason: String(envelope.payload.reason ?? ""),
              recommendedSkill:
                envelope.payload.recommended_skill != null
                  ? String(envelope.payload.recommended_skill)
                  : null,
            };
            setRouteInfo(accRoute);
            saveCurrent("streaming");
          } else if (envelope.type === "agent_handoff") {
            const fromA = String(envelope.payload.from_agent ?? "agent");
            const toA = String(envelope.payload.to_agent ?? "agent");
            const reason = String(envelope.payload.reason ?? "");
            accAgent = toA;
            setActiveAgent(toA);
            accTools = [
              ...accTools,
              {
                callId: `handoff-${accSeq}`,
                toolName: `${fromA} → ${toA}`,
                source: "handoff",
                agent: fromA,
                argumentsPreview: reason,
                status: "ok",
                summary: reason,
              },
            ];
            setToolSteps(accTools);
            saveCurrent("streaming");
          } else if (envelope.type === "thought_delta") {
            const delta = String(envelope.payload.content ?? "");
            accThought += delta;
            setThoughtText(accThought);
          } else if (envelope.type === "tool_call") {
            const callId = String(envelope.payload.call_id ?? `step-${accSeq}`);
            const toolName = String(
              envelope.payload.tool_name ?? envelope.payload.tool ?? "tool",
            );
            const source = String(envelope.payload.source ?? "builtin");
            const argsPreview = JSON.stringify(envelope.payload.arguments ?? {});
            accTools = [
              ...accTools,
              {
                callId,
                toolName,
                source,
                agent: envelope.agent,
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
          } else if (envelope.type === "draft_reset") {
            accText = "";
            setGeneratedText("");
            setStatusDetail(String(envelope.payload.reason ?? "审稿打回，正在重新执笔..."));
            saveCurrent("streaming");
          } else if (envelope.type === "text_delta") {
            const delta = String(envelope.payload.content ?? "");
            accText += delta;
            setGeneratedText(accText);
            saveCurrent("streaming");
          } else if (envelope.type === "critic_review") {
            accCritic = {
              passed: Boolean(envelope.payload.passed),
              score: Number(envelope.payload.score ?? 0),
              issues: Array.isArray(envelope.payload.issues)
                ? envelope.payload.issues.map(String)
                : [],
              revisionCount: Number(envelope.payload.revision_count ?? 0),
              maxRevisions: Number(envelope.payload.max_revisions ?? 2),
            };
            setCriticInfo(accCritic);
            saveCurrent("streaming");
          } else if (envelope.type === "usage") {
            setUsage({
              inputTokens: Number(envelope.payload.input_tokens ?? 0),
              cachedTokens: Number(envelope.payload.cached_tokens ?? 0),
              outputTokens: Number(envelope.payload.output_tokens ?? 0),
            });
          } else if (envelope.type === "interrupt") {
            const intType = String(
              envelope.payload.interrupt_type ?? envelope.payload.reason ?? "USER_PAUSED",
            );
            const proposed = (envelope.payload.proposed_data ?? {}) as Record<string, unknown>;
            if (intType === "OUTLINE_APPROVAL") {
              const outlineText = String(proposed.outline_text ?? "");
              const beats = Array.isArray(proposed.beats)
                ? proposed.beats.map(String)
                : [];
              accOutline = {
                outlineText,
                beats,
                checkpointStep: Number(proposed.checkpoint_step ?? 0),
                message: String(proposed.message ?? "请审阅或微调总编拟定的章节大纲"),
              };
              setOutlineApproval(accOutline);
              setEditedOutline(outlineText);
              setStatusDetail("总编大纲已落盘 PostgreSQL Checkpoint，请审阅批准后开始执笔");
              void loadCheckpointsForRun(targetRunId);
            } else {
              accOutline = null;
              setOutlineApproval(null);
              setStatusDetail("已在安全检查点暂停（上游 LLM 已停流省 Token），可修改指令后继续");
            }
            saveCurrent("paused");
          } else if (envelope.type === "error") {
            setStatusDetail(String(envelope.payload.message ?? "生成异常"));
            saveCurrent("error");
          } else if (envelope.type === "done") {
            accOutline = null;
            setOutlineApproval(null);
            const doneStatus =
              envelope.payload.status === "cancelled" ? "cancelled" : "completed";
            saveCurrent(doneStatus);
            if (seed.mode === "MULTI_AGENT") {
              void loadCheckpointsForRun(targetRunId);
            }
          }
        },
      });
    },
    [persistSnapshot, loadCheckpointsForRun],
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
          setMode(saved.mode ?? "MULTI_AGENT");
          setAction(saved.action);
          setModelAlias(saved.modelAlias);
          setInstruction(saved.instruction);
          setLastEventId(saved.lastEventId);
          setLastSeq(saved.lastSeq);
          setGeneratedText(saved.text);
          setThoughtText(saved.thought);
          setToolSteps(saved.toolSteps ?? []);
          setActiveAgent(saved.activeAgent ?? null);
          setRouteInfo(saved.routeInfo ?? null);
          setCriticInfo(saved.criticInfo ?? null);
          setOutlineApproval(saved.outlineApproval ?? null);
          setEditedOutline(saved.outlineApproval?.outlineText ?? "");
          setConnState(saved.state);
          setStatusDetail(null);
          setUsage(null);

          if (saved.mode === "MULTI_AGENT") {
            void loadCheckpointsForRun(saved.runId);
          }

          if (
            saved.state === "streaming" ||
            saved.state === "connecting" ||
            saved.state === "reconnecting"
          ) {
            setStatusDetail("检测到页面刷新，正在携带 Last-Event-ID 自动断点续连...");
            attachStream(saved.runId, saved.lastEventId, {
              mode: saved.mode ?? "MULTI_AGENT",
              action: saved.action,
              modelAlias: saved.modelAlias,
              instruction: saved.instruction,
              text: saved.text,
              thought: saved.thought,
              toolSteps: saved.toolSteps ?? [],
              seq: saved.lastSeq,
              activeAgent: saved.activeAgent,
              routeInfo: saved.routeInfo,
              criticInfo: saved.criticInfo,
              outlineApproval: saved.outlineApproval,
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
          setActiveAgent(resumable.currentNode?.toLowerCase() ?? null);
          if (resumable.mode === "MULTI_AGENT") {
            void loadCheckpointsForRun(resumable.id);
          }
          if (resumable.status === "PAUSED") {
            setConnState("paused");
            if (
              resumable.interruptType === "OUTLINE_APPROVAL" &&
              resumable.interruptPayloadJson
            ) {
              try {
                const parsed = JSON.parse(resumable.interruptPayloadJson) as Record<string, unknown>;
                const outlineText = String(parsed.outline_text ?? "");
                setOutlineApproval({
                  outlineText,
                  beats: Array.isArray(parsed.beats) ? parsed.beats.map(String) : [],
                  checkpointStep: Number(parsed.checkpoint_step ?? 0),
                  message: String(parsed.message ?? "请审批总编拟定的章节大纲"),
                });
                setEditedOutline(outlineText);
                setStatusDetail("已从 PostgreSQL Checkpoint 恢复待审批的章节大纲");
              } catch {
                setStatusDetail("已恢复上次暂停的检查点");
              }
            } else {
              setStatusDetail("已恢复上次暂停的检查点，可修改指令后继续生成");
            }
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
      setActiveAgent(null);
      setRouteInfo(null);
      setCriticInfo(null);
      setOutlineApproval(null);
      setCheckpoints([]);
      setConnState("idle");
      setStatusDetail(null);
    };

    void restore();

    return () => {
      active = false;
      sseHandleRef.current?.disconnect();
      sseHandleRef.current = null;
    };
  }, [bookId, chapterId, attachStream, loadCheckpointsForRun]);

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
    setActiveAgent(mode === "MULTI_AGENT" ? "router" : "writer");
    setRouteInfo(null);
    setCriticInfo(null);
    setOutlineApproval(null);
    setEditedOutline("");
    setApprovalFeedback("");
    setCheckpoints([]);
    setDiffReviewOpen(false);
    setAcceptedParagraphs({});

    try {
      const created = await runsApi.start({
        bookId,
        chapterId: chapterId ?? undefined,
        mode,
        action,
        modelAlias,
        instruction,
        chapterContext: chapterContent,
        requireOutlineApproval: mode === "MULTI_AGENT" ? requireOutlineApproval : false,
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
        activeAgent: mode === "MULTI_AGENT" ? "router" : "writer",
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
        activeAgent,
        routeInfo,
        criticInfo,
      });
    } catch (err) {
      setConnState("error");
      setStatusDetail(err instanceof Error ? err.message : "恢复生成失败");
    } finally {
      setBusyControl(false);
    }
  }

  async function handleApproveOutline(approved: boolean) {
    if (!runId) return;
    setBusyControl(true);
    setStatusDetail(
      approved
        ? "已批准大纲，从 PostgreSQL Checkpoint 唤醒设定管家与执笔作家..."
        : "已打回大纲，正在通知总编按你的修改意见重拟...",
    );
    try {
      await runsApi.approve(runId, {
        approved,
        editedOutline: editedOutline.trim() || outlineApproval?.outlineText || "",
        feedback: approvalFeedback.trim() || undefined,
      });
      setOutlineApproval(null);
      setApprovalFeedback("");
      attachStream(runId, lastEventId, {
        mode,
        action,
        modelAlias,
        instruction,
        text: generatedText,
        thought: thoughtText,
        toolSteps,
        seq: lastSeq,
        activeAgent: approved ? "lorekeeper" : "director",
        routeInfo,
        criticInfo,
        outlineApproval: null,
      });
    } catch (err) {
      setConnState("error");
      setStatusDetail(err instanceof Error ? err.message : "提交大纲审批失败");
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
        setOutlineApproval(null);
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
    setActiveAgent(null);
    setRouteInfo(null);
    setCriticInfo(null);
    setOutlineApproval(null);
    setCheckpoints([]);
    setDiffReviewOpen(false);
    setConnState("idle");
    setStatusDetail(null);
  }

  function handleOpenDiffReview() {
    const initial: Record<number, boolean> = {};
    generatedParagraphs.forEach((_, idx) => {
      initial[idx] = true;
    });
    setAcceptedParagraphs(initial);
    setDiffReviewOpen((v) => !v);
  }

  function handleApplySelectedDiff() {
    const chosen = generatedParagraphs.filter((_, idx) => acceptedParagraphs[idx] !== false);
    if (chosen.length === 0) return;
    onAppendText(chosen.join("\n\n"));
    setDiffReviewOpen(false);
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

        {/* 3-Mode Selector: Multi-Agent Editorial Team / Single Agent / Direct */}
        <div className="grid grid-cols-3 gap-1 rounded-md bg-muted/60 p-0.5">
          <button
            type="button"
            disabled={isRunning || connState === "paused"}
            onClick={() => setMode("MULTI_AGENT")}
            className={`rounded py-1 text-[11px] font-medium transition-colors ${
              mode === "MULTI_AGENT"
                ? "bg-background text-foreground shadow-xs"
                : "text-muted-foreground hover:text-foreground"
            }`}
          >
            编辑部协同
          </button>
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
            单 Agent
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
            直连流式
          </button>
        </div>

        {/* Multi-Agent Pipeline Stepper */}
        {mode === "MULTI_AGENT" && (
          <div className="space-y-1.5 rounded-lg border border-border bg-muted/25 p-2">
            <div className="grid grid-cols-5 gap-1 text-center text-[10px]">
              {PIPELINE_NODES.map((node) => {
                const isCurrent = activeAgent === node.id && (isRunning || connState === "paused");
                const isDone =
                  connState === "completed" ||
                  checkpoints.some((c) => c.nodeName.toLowerCase() === node.id);
                return (
                  <div
                    key={node.id}
                    className={`rounded py-1 font-medium transition-colors ${
                      isCurrent
                        ? "bg-primary text-primary-foreground"
                        : isDone
                          ? "bg-emerald-500/15 text-emerald-700 dark:text-emerald-400"
                          : "bg-background text-muted-foreground"
                    }`}
                  >
                    {node.label}
                  </div>
                );
              })}
            </div>
            <label className="flex items-center gap-1.5 text-[10px] text-muted-foreground">
              <input
                type="checkbox"
                disabled={isRunning || connState === "paused"}
                checked={requireOutlineApproval}
                onChange={(e) => setRequireOutlineApproval(e.target.checked)}
              />
              总编出大纲后暂停审批（HITL Interrupt）
            </label>
          </div>
        )}

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

        {/* HITL Outline Approval Card vs Normal Pause Controls */}
        {connState === "paused" && outlineApproval ? (
          <div className="space-y-2 rounded-lg border border-sky-500/50 bg-sky-500/5 p-2.5">
            <div className="flex items-center justify-between">
              <span className="font-semibold text-sky-700 dark:text-sky-400">
                HITL 大纲审批（Checkpoint #{outlineApproval.checkpointStep ?? 2}）
              </span>
              <span className="font-mono text-[10px] text-muted-foreground">DIRECTOR</span>
            </div>
            <p className="text-[11px] text-muted-foreground">
              你可以直接修改下方节拍大纲，批准后设定管家与执笔作家将严格按你的定稿创作：
            </p>
            <textarea
              rows={6}
              value={editedOutline}
              onChange={(e) => setEditedOutline(e.target.value)}
              className="w-full rounded-md border border-border bg-background p-2 text-[11px] leading-relaxed outline-none focus:ring-1 focus:ring-sky-500"
            />
            <input
              value={approvalFeedback}
              onChange={(e) => setApprovalFeedback(e.target.value)}
              placeholder="可选：若要打回重拟，在此填写给总编的修改意见..."
              className="w-full rounded-md border border-border bg-background px-2 py-1 text-[11px] outline-none"
            />
            <div className="flex gap-1.5">
              <Button
                size="xs"
                className="flex-1"
                disabled={busyControl}
                onClick={() => void handleApproveOutline(true)}
              >
                批准大纲并开始执笔
              </Button>
              <Button
                size="xs"
                variant="outline"
                disabled={busyControl}
                onClick={() => void handleApproveOutline(false)}
              >
                打回重拟
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
        ) : connState === "paused" ? (
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
              placeholder="输入写作要求，如：安排寒潭雨夜剑斗，严格核对林青修为与青铜令伏笔..."
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
                  {mode === "MULTI_AGENT"
                    ? "启动编辑部协同创作"
                    : mode === "AGENT"
                      ? "启动单 Agent 创作"
                      : "开始流式生成"}
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

      {/* Trajectory Timeline + Route/Critic Badges + Thought + Streaming Prose / Diff Review */}
      <div className="flex min-h-0 flex-1 flex-col space-y-3 overflow-y-auto p-3">
        {/* Route Decision Banner */}
        {routeInfo && (
          <div className="rounded-lg border border-indigo-500/30 bg-indigo-500/5 px-2.5 py-2 text-[11px]">
            <div className="flex items-center justify-between gap-2">
              <span className="font-semibold text-indigo-700 dark:text-indigo-400">
                路由决策：{routeInfo.intent}
              </span>
              <span className="rounded bg-indigo-500/15 px-1.5 py-0.5 font-mono text-[9px] text-indigo-600">
                {routeInfo.layer}
              </span>
            </div>
            <div className="mt-0.5 text-[10px] text-muted-foreground">
              {routeInfo.reason}
              {routeInfo.recommendedSkill ? ` · 自动挂载 Skill: ${routeInfo.recommendedSkill}` : ""}
            </div>
          </div>
        )}

        {/* ContinuityCritic Review Banner */}
        {criticInfo && (
          <div
            className={`rounded-lg border px-2.5 py-2 text-[11px] ${
              criticInfo.passed
                ? "border-emerald-500/30 bg-emerald-500/5"
                : "border-amber-500/40 bg-amber-500/10"
            }`}
          >
            <div className="flex items-center justify-between">
              <span className="font-semibold">
                审稿编辑评分：{criticInfo.score}/100（{criticInfo.passed ? "通过" : "触发返工"}）
              </span>
              <span className="font-mono text-[10px] text-muted-foreground">
                返工 {criticInfo.revisionCount}/{criticInfo.maxRevisions}
              </span>
            </div>
            {criticInfo.issues.length > 0 && (
              <ul className="mt-1 list-disc space-y-0.5 pl-4 text-[10px] text-muted-foreground">
                {criticInfo.issues.map((iss, idx) => (
                  <li key={idx}>{iss}</li>
                ))}
              </ul>
            )}
          </div>
        )}

        {discoveredTools.length > 0 && (
          <div className="rounded-md bg-muted/40 px-2.5 py-1.5 font-mono text-[10px] text-muted-foreground">
            已挂载 {discoveredTools.length} 个工具：{discoveredTools.join(", ")}
          </div>
        )}

        {toolSteps.length > 0 && (
          <div className="space-y-1.5 rounded-lg border border-border bg-card p-2.5">
            <div className="font-mono text-[10px] font-semibold text-muted-foreground">
              多 Agent / 工具执行轨迹 ({toolSteps.length} 步)
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
                          ? step.latencyMs !== undefined
                            ? `✓ ${step.latencyMs}ms`
                            : "✓"
                          : "✕ 失败"}
                    </span>
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

        {/* PG Checkpoint History Accordion */}
        {checkpoints.length > 0 && (
          <div className="rounded-lg border border-border bg-muted/30 p-2">
            <button
              type="button"
              onClick={() => setShowCheckpoints((v) => !v)}
              className="flex w-full items-center justify-between font-mono text-[10px] font-semibold text-muted-foreground"
            >
              <span>PostgreSQL Checkpoints ({checkpoints.length} 个检查点)</span>
              <span>{showCheckpoints ? "收起" : "展开"}</span>
            </button>
            {showCheckpoints && (
              <ul className="mt-1.5 space-y-1 font-mono text-[10px]">
                {checkpoints.map((cp) => (
                  <li
                    key={cp.id}
                    className="flex items-center justify-between rounded bg-background px-2 py-1"
                  >
                    <span>
                      #{cp.stepNo} {cp.nodeName}
                    </span>
                    <span className="text-muted-foreground">{cp.status}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        {thoughtText && (
          <div className="rounded-lg border border-border bg-muted/40 p-2.5">
            <button
              type="button"
              onClick={() => setShowThought((v) => !v)}
              className="flex w-full items-center justify-between font-mono text-[10px] font-semibold text-muted-foreground"
            >
              <span>大纲 / 思考与审稿过程</span>
              <span>{showThought ? "收起" : "展开"}</span>
            </button>
            {showThought && (
              <div className="mt-1.5 whitespace-pre-wrap text-[11px] leading-relaxed text-muted-foreground">
                {thoughtText}
              </div>
            )}
          </div>
        )}

        {/* Generated Prose OR Paragraph-by-Paragraph Diff Review */}
        {generatedText ? (
          diffReviewOpen && !isRunning ? (
            <div className="space-y-2 rounded-lg border border-emerald-500/40 bg-background p-2.5">
              <div className="flex items-center justify-between">
                <span className="font-semibold text-emerald-700 dark:text-emerald-400">
                  逐段 Diff 审阅（勾选采纳段落）
                </span>
                <button
                  type="button"
                  onClick={() => setDiffReviewOpen(false)}
                  className="text-[10px] text-muted-foreground hover:text-foreground"
                >
                  退出 Diff
                </button>
              </div>
              <div className="space-y-1.5">
                {generatedParagraphs.map((para, idx) => {
                  const accepted = acceptedParagraphs[idx] !== false;
                  return (
                    <div
                      key={idx}
                      className={`rounded border p-2 text-[11px] leading-relaxed ${
                        accepted
                          ? "border-emerald-500/40 bg-emerald-500/10"
                          : "border-border bg-muted/30 opacity-50 line-through"
                      }`}
                    >
                      <div className="mb-1 flex items-center justify-between font-mono text-[10px]">
                        <span>{accepted ? "+ 采纳段落" : "− 跳过段落"} #{idx + 1}</span>
                        <button
                          type="button"
                          onClick={() =>
                            setAcceptedParagraphs((prev) => ({
                              ...prev,
                              [idx]: !accepted,
                            }))
                          }
                          className="text-primary hover:underline"
                        >
                          {accepted ? "跳过" : "恢复采纳"}
                        </button>
                      </div>
                      <p className="whitespace-pre-wrap">{para}</p>
                    </div>
                  );
                })}
              </div>
              <Button size="xs" className="w-full" onClick={handleApplySelectedDiff}>
                合并已采纳段落到编辑器正文
              </Button>
            </div>
          ) : (
            <div className="rounded-lg border border-border bg-background p-3 leading-relaxed">
              <div className="whitespace-pre-wrap text-xs text-foreground">
                {generatedText}
                {connState === "streaming" && (
                  <span className="ml-0.5 inline-block h-3.5 w-1.5 animate-pulse bg-primary align-middle" />
                )}
              </div>
            </div>
          )
        ) : (
          toolSteps.length === 0 && (
            <div className="rounded-lg border border-dashed border-border p-4 text-center text-[11px] leading-relaxed text-muted-foreground">
              <p className="font-medium text-foreground">Phase 4 多 Agent 编辑部协同</p>
              <p className="mt-1">
                一键启动「总编出大纲 → 人工审批 → 设定管家查 MCP/RAG/长记忆 → 执笔作家写正文 → 审稿编辑打分返工」完整流水线。
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
              追加正文
            </Button>
            <Button
              size="xs"
              variant="outline"
              onClick={handleOpenDiffReview}
            >
              {diffReviewOpen ? "普通视图" : "Diff 审阅"}
            </Button>
            <Button
              size="xs"
              variant="outline"
              onClick={() => onReplaceText(generatedText)}
            >
              替换
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
