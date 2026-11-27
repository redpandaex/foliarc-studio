"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Bookmark, SlidersHorizontal, Sparkles, X } from "lucide-react";
import {
  ForeshadowingDirectorTray,
  useDirectorOptions,
} from "@/components/foreshadowing-director-slot";
import { ShinyText } from "@/components/motion/shiny-text";
import { Switch } from "@/components/ui/switch";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/toast";
import {
  getRunStreamUrl,
  runsApi,
  type AgentRunItem,
  type ForeshadowingDirective,
  type RunAction,
  type RunMode,
  type VisualVisibility,
} from "@/lib/api";
import {
  connectResumableSse,
  type ResumableSseHandle,
  type SseEnvelope,
  type StreamConnectionState,
} from "@/lib/sse-client";
import { formatStreamError, formatWebError } from "@/lib/error-messages";

export interface SelectionEditTask {
  mode: "REWRITE" | "EXPAND";
  text: string;
  start: number;
  end: number;
}

interface AiCopilotPanelProps {
  bookId: string;
  chapterId: string | null;
  sectionId?: string | null;
  chapterNo?: number | null;
  chapterContent: string;
  compact?: boolean;
  visibility?: VisualVisibility;
  visualRefreshSignal?: number;
  selectionTask?: SelectionEditTask | null;
  onClearSelectionTask?: () => void;
  onHighlightRelationsInGraph?: (relationIds: string[]) => void;
  injectedDirective?: {
    nonce: number;
    directive: ForeshadowingDirective;
    label?: string;
  } | null;
  injectedInstruction?: {
    nonce: number;
    text: string;
  } | null;
  onReviewDiff?: (payload: {
    originalText: string;
    proposedText: string;
    mode: "FULL" | "SELECTION";
    selectionRange?: { start: number; end: number };
  }) => void;
  onAppendText: (text: string) => void;
  onReplaceText: (text: string) => void;
  onRunStateChange?: (chapterId: string | null, state: StreamConnectionState) => void;
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
  liveOutline?: string;
  state: StreamConnectionState;
  activeAgent?: string | null;
  completedStages?: string[];
  criticInfo?: CriticInfo | null;
  outlineApproval?: OutlineApprovalState | null;
  hitRelationIds?: string[];
}

const ACTION_OPTIONS: { value: RunAction; label: string }[] = [
  { value: "CONTINUE", label: "续写正文" },
  { value: "REWRITE", label: "润色扩写" },
  { value: "OUTLINE", label: "生成大纲" },
];

const MODE_OPTIONS: { value: RunMode; label: string; short: string; desc: string }[] = [
  { value: "MULTI_AGENT", label: "协同精写", short: "精写", desc: "大纲 → 设定核验 → 执笔 → 审稿" },
  { value: "AGENT", label: "智能单步", short: "单步", desc: "结合设定库直接成稿" },
  { value: "DIRECT", label: "快速直写", short: "直写", desc: "极速流式续写" },
];

const MODEL_OPTIONS = [
  { value: "writer-main", label: "主力模型" },
  { value: "critic-reasoner", label: "深度推理" },
  { value: "fast-router", label: "极速响应" },
];

const WRITER_STAGES = [
  { id: "director", label: "构思大纲" },
  { id: "lorekeeper", label: "核对前文" },
  { id: "writer", label: "撰写正文" },
  { id: "critic", label: "连贯校对" },
];

const STAGE_ORDER = ["router", "director", "lorekeeper", "writer", "critic"];

const STATE_BADGE: Record<StreamConnectionState, { label: string; dot: string }> = {
  idle: { label: "就绪", dot: "bg-muted-foreground/40" },
  connecting: { label: "正在连接...", dot: "bg-status-warning animate-pulse" },
  streaming: { label: "正在创作...", dot: "bg-status-ai animate-pulse" },
  reconnecting: { label: "网络波动，自动续接中...", dot: "bg-status-warning animate-pulse" },
  paused: { label: "等待确认", dot: "bg-status-info" },
  completed: { label: "创作完成", dot: "bg-status-success" },
  cancelled: { label: "已取消", dot: "bg-muted-foreground" },
  error: { label: "生成中断", dot: "bg-status-danger" },
};

const AGENT_HINT: Record<string, string> = {
  router: "正在分析本章创作需求...",
  director: "正在结合上一章剧情构思本章大纲...",
  lorekeeper: "正在核对人物设定与前文线索...",
  writer: "正在紧接前文撰写正文...",
  critic: "正在检查剧情连贯性与设定一致性...",
};

const THOUGHT_AGENT_LABEL: Record<string, string> = {
  director: "构思大纲",
  writer: "写作思考",
  critic: "连贯性审稿",
};

function storageKey(bookId: string, chapterId: string | null): string {
  return `foliarc:sse_run:${bookId}:${chapterId ?? "book"}`;
}

function isActiveStreamState(state: StreamConnectionState): boolean {
  return state === "connecting" || state === "streaming" || state === "reconnecting";
}

function useSmoothTypewriter(targetText: string, active: boolean): string {
  const [shownLen, setShownLen] = useState(targetText.length);
  const shownLenRef = useRef(targetText.length);
  const targetLenRef = useRef(targetText.length);

  useEffect(() => {
    targetLenRef.current = targetText.length;
    if (!active) {
      shownLenRef.current = targetText.length;
      return;
    }
    if (targetText.length < shownLenRef.current) {
      shownLenRef.current = targetText.length;
    }

    let rafId = 0;
    let lastTick = 0;

    const tick = (now: number) => {
      const targetLen = targetLenRef.current;
      const cur = shownLenRef.current;
      if (cur > targetLen) {
        shownLenRef.current = targetLen;
        setShownLen(targetLen);
        return;
      }
      if (cur === targetLen) {
        setShownLen(cur);
        return;
      }

      const backlog = targetLen - cur;
      const intervalMs = backlog > 60 ? 16 : backlog > 16 ? 20 : 26;
      if (now - lastTick >= intervalMs) {
        lastTick = now;
        const step =
          backlog > 300
            ? 20
            : backlog > 150
              ? 10
              : backlog > 60
                ? 4
                : backlog > 16
                  ? 2
                  : 1;
        const next = Math.min(targetLen, cur + step);
        shownLenRef.current = next;
        setShownLen(next);
      }

      if (shownLenRef.current < targetLen) {
        rafId = window.requestAnimationFrame(tick);
      }
    };

    rafId = window.requestAnimationFrame(tick);
    return () => window.cancelAnimationFrame(rafId);
  }, [targetText, active]);

  if (!active) {
    return targetText;
  }
  return targetText.slice(0, Math.min(shownLen, targetText.length));
}

function parseOutlineApprovalFromRun(run: AgentRunItem): OutlineApprovalState | null {
  if (run.interruptType !== "OUTLINE_APPROVAL" || !run.interruptPayloadJson) {
    return null;
  }
  try {
    const parsed = JSON.parse(run.interruptPayloadJson) as Record<string, unknown>;
    const outlineText = String(parsed.outline_text ?? "");
    return {
      outlineText,
      beats: Array.isArray(parsed.beats) ? parsed.beats.map(String) : [],
      message: String(parsed.message ?? "请确认或微调本章剧情大纲"),
    };
  } catch {
    return null;
  }
}

export function AiCopilotPanel({
  bookId,
  chapterId,
  sectionId,
  chapterNo,
  chapterContent,
  compact = false,
  visibility = "AS_OF",
  visualRefreshSignal = 0,
  selectionTask,
  onClearSelectionTask,
  onHighlightRelationsInGraph,
  injectedDirective,
  injectedInstruction,
  onReviewDiff,
  onAppendText,
  onReplaceText,
  onRunStateChange,
}: AiCopilotPanelProps) {
  const toast = useToast();
  const [mode, setMode] = useState<RunMode>("MULTI_AGENT");
  const [action, setAction] = useState<RunAction>("CONTINUE");
  const [modelAlias, setModelAlias] = useState("writer-main");
  const [requireOutlineApproval, setRequireOutlineApproval] = useState(true);
  const [activeTray, setActiveTray] = useState<"none" | "director" | "settings">("none");

  const directorData = useDirectorOptions({
    bookId,
    chapterId,
    sectionId: sectionId ?? null,
    chapterNo: chapterNo ?? null,
    visibility,
    refreshSignal: visualRefreshSignal,
  });

  const [instruction, setInstruction] = useState("");
  const [povCharacter, setPovCharacter] = useState("");
  const [foreshadowingDirectives, setForeshadowingDirectives] = useState<
    ForeshadowingDirective[]
  >([]);
  const [revisedInstruction, setRevisedInstruction] = useState("");

  const [runId, setRunId] = useState<string | null>(null);
  const [connState, setConnState] = useState<StreamConnectionState>("idle");
  const [statusMessage, setStatusDetail] = useState<string | { error: string } | null>(null);
  const statusDetail = typeof statusMessage === "string" ? statusMessage : statusMessage?.error;
  const statusIsError = connState === "error" || Boolean(statusMessage && typeof statusMessage === "object");
  const [controlsExpanded, setControlsExpanded] = useState(false);

  const [generatedText, setGeneratedText] = useState("");
  const [thoughtText, setThoughtText] = useState("");
  const [liveOutlineText, setLiveOutlineText] = useState("");
  const [showThought, setShowThought] = useState(false);
  const [busyControl, setBusyControl] = useState(false);

  const [activeAgent, setActiveAgent] = useState<string | null>(null);
  const [completedStages, setCompletedStages] = useState<string[]>([]);
  const [criticInfo, setCriticInfo] = useState<CriticInfo | null>(null);
  const [hitRelationIds, setHitRelationIds] = useState<string[]>([]);
  const [outlineApproval, setOutlineApproval] = useState<OutlineApprovalState | null>(null);
  const [editedOutline, setEditedOutline] = useState("");
  const [approvalFeedback, setApprovalFeedback] = useState("");
  const [diffReviewOpen, setDiffReviewOpen] = useState(false);
  const [acceptedParagraphs, setAcceptedParagraphs] = useState<Record<number, boolean>>({});
  const [isAtBottom, setIsAtBottom] = useState(true);

  const sseHandleRef = useRef<ResumableSseHandle | null>(null);
  const lastEventIdRef = useRef<string | null>(null);
  const lastSeqRef = useRef(0);
  const seenSeqRef = useRef(0);
  const latestSnapshotRef = useRef<PersistedRunSnapshot | null>(null);
  const persistTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const appliedDirectiveNonceRef = useRef(0);
  const appliedInstructionNonceRef = useRef(0);

  useEffect(() => {
    if (!injectedDirective || injectedDirective.nonce === appliedDirectiveNonceRef.current) {
      return;
    }
    const targetNonce = injectedDirective.nonce;
    const incoming = injectedDirective.directive;
    const label = injectedDirective.label;
    let active = true;
    void Promise.resolve().then(() => {
      if (!active || appliedDirectiveNonceRef.current === targetNonce) return;
      appliedDirectiveNonceRef.current = targetNonce;
      setForeshadowingDirectives((prev) => {
        const without = prev.filter((d) => d.foreshadowingId !== incoming.foreshadowingId);
        if (without.length >= 3) {
          toast.warning("导演槽已满 3 条伏笔指令", {
            description: "请先在「伏笔导演与视角」中移除 1 条后再添加",
          });
          return prev;
        }
        const modeText =
          incoming.mode === "PLANT" ? "埋入" : incoming.mode === "HINT" ? "照应" : "回收";
        toast.success(`已送入导演槽：${modeText}「${label ?? "伏笔"}」`);
        return [...without, incoming];
      });
      setControlsExpanded(true);
    });
    return () => {
      active = false;
    };
  }, [injectedDirective, toast]);

  useEffect(() => {
    if (!injectedInstruction || injectedInstruction.nonce === appliedInstructionNonceRef.current) {
      return;
    }
    const targetNonce = injectedInstruction.nonce;
    const addition = injectedInstruction.text.trim();
    if (!addition) return;
    let active = true;
    void Promise.resolve().then(() => {
      if (!active || appliedInstructionNonceRef.current === targetNonce) return;
      appliedInstructionNonceRef.current = targetNonce;
      setInstruction((prev) => (prev.trim() ? `${prev.trim()}\n${addition}` : addition));
      setControlsExpanded(true);
      toast.success("已填入 AI 写作指令草稿");
    });
    return () => {
      active = false;
    };
  }, [injectedInstruction, toast]);
  const userToggledThoughtRef = useRef(false);
  const onRunStateChangeRef = useRef(onRunStateChange);
  const outputScrollRef = useRef<HTMLDivElement | null>(null);
  const isAtBottomRef = useRef(true);

  useEffect(() => {
    onRunStateChangeRef.current = onRunStateChange;
  }, [onRunStateChange]);

  const generatedParagraphs = useMemo(
    () =>
      generatedText
        .split(/\n{2,}/)
        .map((p) => p.trim())
        .filter(Boolean),
    [generatedText],
  );

  const writeSnapshotToStorage = useCallback(
    (snapshot: PersistedRunSnapshot | null) => {
      if (typeof window === "undefined") return;
      const key = storageKey(bookId, chapterId);
      if (!snapshot) {
        window.sessionStorage.removeItem(key);
        return;
      }
      try {
        window.sessionStorage.setItem(key, JSON.stringify(snapshot));
      } catch {
        // Ignore storage quota errors
      }
    },
    [bookId, chapterId],
  );

  const flushSnapshotNow = useCallback(
    (snapshot: PersistedRunSnapshot | null) => {
      if (persistTimerRef.current) {
        clearTimeout(persistTimerRef.current);
        persistTimerRef.current = null;
      }
      latestSnapshotRef.current = snapshot;
      writeSnapshotToStorage(snapshot);
    },
    [writeSnapshotToStorage],
  );

  const scheduleSnapshotPersist = useCallback(
    (snapshot: PersistedRunSnapshot) => {
      latestSnapshotRef.current = snapshot;
      if (persistTimerRef.current) return;
      persistTimerRef.current = setTimeout(() => {
        persistTimerRef.current = null;
        if (latestSnapshotRef.current) {
          writeSnapshotToStorage(latestSnapshotRef.current);
        }
      }, 220);
    },
    [writeSnapshotToStorage],
  );

  // Flush the very latest cursor + content synchronously when the user refreshes or leaves the page
  useEffect(() => {
    const handlePageHide = () => {
      if (latestSnapshotRef.current) {
        writeSnapshotToStorage(latestSnapshotRef.current);
      }
    };
    window.addEventListener("pagehide", handlePageHide);
    window.addEventListener("beforeunload", handlePageHide);
    return () => {
      window.removeEventListener("pagehide", handlePageHide);
      window.removeEventListener("beforeunload", handlePageHide);
    };
  }, [writeSnapshotToStorage]);

  const updateConnState = useCallback(
    (nextState: StreamConnectionState) => {
      setConnState(nextState);
      onRunStateChangeRef.current?.(chapterId, nextState);
    },
    [chapterId],
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
        liveOutline?: string;
        seq: number;
        activeAgent?: string | null;
        completedStages?: string[];
        criticInfo?: CriticInfo | null;
        outlineApproval?: OutlineApprovalState | null;
        hitRelationIds?: string[];
      },
    ) => {
      sseHandleRef.current?.disconnect();
      seenSeqRef.current = seed.seq;
      lastEventIdRef.current = cursor;
      lastSeqRef.current = seed.seq;

      let accText = seed.text;
      let accThought = seed.thought;
      let accLiveOutline = seed.liveOutline ?? "";
      let lastThoughtAgent: string | null = null;
      let lastHintAgent: string | null = seed.activeAgent ?? null;
      let accLastEventId = cursor;
      let accSeq = seed.seq;
      let accAgent = seed.activeAgent ?? null;
      let accCompletedStages = [...(seed.completedStages ?? [])];
      let accCritic = seed.criticInfo ?? null;
      let accOutline = seed.outlineApproval ?? null;
      let accHitRelations = [...(seed.hitRelationIds ?? [])];
      let accState: StreamConnectionState = "connecting";

      const buildSnap = (nextState: StreamConnectionState): PersistedRunSnapshot => ({
        runId: targetRunId,
        mode: seed.mode,
        action: seed.action,
        modelAlias: seed.modelAlias,
        instruction: seed.instruction,
        lastEventId: accLastEventId,
        lastSeq: accSeq,
        text: accText,
        thought: accThought,
        liveOutline: accLiveOutline,
        state: nextState,
        activeAgent: accAgent,
        completedStages: accCompletedStages,
        criticInfo: accCritic,
        outlineApproval: accOutline,
        hitRelationIds: accHitRelations,
      });

      const markStageCompleted = (stageId: string) => {
        if (!stageId || accCompletedStages.includes(stageId)) return;
        accCompletedStages = [...accCompletedStages, stageId];
        setCompletedStages(accCompletedStages);
      };

      sseHandleRef.current = connectResumableSse({
        url: getRunStreamUrl(targetRunId),
        initialLastEventId: cursor,
        onLastEventId(id) {
          accLastEventId = id;
          lastEventIdRef.current = id;
          scheduleSnapshotPersist(buildSnap(accState));
        },
        onStateChange(nextState, meta) {
          accState = nextState;
          updateConnState(nextState);
          if (meta?.detail) {
            setStatusDetail(nextState === "error" ? { error: meta.detail } : meta.detail);
          } else if (nextState === "streaming" && accAgent && AGENT_HINT[accAgent]) {
            setStatusDetail(AGENT_HINT[accAgent]);
          } else if (nextState === "completed") {
            setStatusDetail(null);
          }
          flushSnapshotNow(buildSnap(nextState));
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
            lastSeqRef.current = envelope.seq;
          }
          if (envelope.agent && envelope.agent !== lastHintAgent) {
            const prevIdx = accAgent ? STAGE_ORDER.indexOf(accAgent) : -1;
            const nextIdx = STAGE_ORDER.indexOf(envelope.agent);
            if (prevIdx >= 0 && nextIdx > prevIdx && accAgent) {
              markStageCompleted(accAgent);
            }
            accAgent = envelope.agent;
            lastHintAgent = envelope.agent;
            setActiveAgent(envelope.agent);
            if (AGENT_HINT[envelope.agent]) {
              setStatusDetail(AGENT_HINT[envelope.agent]);
            }
          }

          if (envelope.type === "agent_handoff") {
            const fromA = String(envelope.payload.from_agent ?? "");
            const toA = String(envelope.payload.to_agent ?? "");
            if (fromA && fromA !== "human") {
              markStageCompleted(fromA);
            }
            if (toA) {
              accAgent = toA;
              lastHintAgent = toA;
              setActiveAgent(toA);
              if (AGENT_HINT[toA]) {
                setStatusDetail(AGENT_HINT[toA]);
              }
            }
            scheduleSnapshotPersist(buildSnap("streaming"));
          } else if (envelope.type === "thought_delta") {
            const delta = String(envelope.payload.content ?? "");
            const thoughtAgent = envelope.agent ?? null;
            if (seed.mode === "MULTI_AGENT" && thoughtAgent === "director") {
              accLiveOutline += delta;
              setLiveOutlineText(accLiveOutline);
              scheduleSnapshotPersist(buildSnap("streaming"));
              return;
            }
            if (thoughtAgent && thoughtAgent !== lastThoughtAgent) {
              const label = THOUGHT_AGENT_LABEL[thoughtAgent];
              if (label && accThought.trim().length > 0) {
                accThought += `\n\n【${label}】\n`;
              }
              lastThoughtAgent = thoughtAgent;
            }
            accThought += delta;
            setThoughtText(accThought);
            if (!accText && !userToggledThoughtRef.current) {
              setShowThought(true);
            }
            scheduleSnapshotPersist(buildSnap("streaming"));
          } else if (envelope.type === "draft_reset") {
            accText = "";
            setGeneratedText("");
            setStatusDetail("连贯性校对发现可优化之处，正在自动调整重写...");
            flushSnapshotNow(buildSnap("streaming"));
          } else if (envelope.type === "text_delta") {
            const delta = String(envelope.payload.content ?? "");
            if (!accText && !userToggledThoughtRef.current) {
              setShowThought(false);
            }
            accText += delta;
            setGeneratedText(accText);
            scheduleSnapshotPersist(buildSnap("streaming"));
          } else if (envelope.type === "tool_result") {
            const rawIds = envelope.payload.hit_relation_ids;
            if (Array.isArray(rawIds) && rawIds.length > 0) {
              accHitRelations = Array.from(
                new Set(rawIds.map((id) => String(id).trim()).filter(Boolean)),
              );
              setHitRelationIds(accHitRelations);
              scheduleSnapshotPersist(buildSnap("streaming"));
            }
          } else if (envelope.type === "critic_review") {
            markStageCompleted("writer");
            if (Boolean(envelope.payload.passed)) {
              markStageCompleted("critic");
            }
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
            flushSnapshotNow(buildSnap("streaming"));
          } else if (envelope.type === "interrupt") {
            const intType = String(
              envelope.payload.interrupt_type ?? envelope.payload.reason ?? "USER_PAUSED",
            );
            const proposed = (envelope.payload.proposed_data ?? {}) as Record<string, unknown>;
            if (intType === "OUTLINE_APPROVAL") {
              markStageCompleted("director");
              const outlineText = String(proposed.outline_text ?? "");
              const beats = Array.isArray(proposed.beats)
                ? proposed.beats.map(String)
                : [];
              accOutline = {
                outlineText,
                beats,
                message: String(proposed.message ?? "请确认或微调本章剧情大纲"),
              };
              accLiveOutline = "";
              setLiveOutlineText("");
              setOutlineApproval(accOutline);
              setEditedOutline(outlineText);
              setShowThought(false);
              setStatusDetail("大纲已拟定，确认或修改后即可开始撰写正文");
            } else {
              accOutline = null;
              setOutlineApproval(null);
              setStatusDetail("已暂停创作，你可以补充剧情想法后继续");
            }
            accState = "paused";
            flushSnapshotNow(buildSnap("paused"));
          } else if (envelope.type === "error") {
            accState = "error";
            setStatusDetail({
              error: formatStreamError(
                typeof envelope.payload.message === "string"
                  ? envelope.payload.message
                  : null,
              ),
            });
            flushSnapshotNow(buildSnap("error"));
          } else if (envelope.type === "done") {
            accOutline = null;
            accLiveOutline = "";
            setLiveOutlineText("");
            setOutlineApproval(null);
            const doneStatus =
              envelope.payload.status === "cancelled" ? "cancelled" : "completed";
            if (doneStatus === "completed") {
              accCompletedStages = ["director", "lorekeeper", "writer", "critic"];
              setCompletedStages(accCompletedStages);
              setStatusDetail(null);
            } else {
              setStatusDetail("已取消本次创作");
            }
            accState = doneStatus;
            flushSnapshotNow(buildSnap(doneStatus));
          }
        },
      });
    },
    [flushSnapshotNow, scheduleSnapshotPersist, updateConnState],
  );

  // Authoritative recovery on mount / page refresh / chapter switch:
  // Always reconcile against backend run state so refreshing never leaves a running job stranded.
  useEffect(() => {
    sseHandleRef.current?.disconnect();
    sseHandleRef.current = null;
    if (persistTimerRef.current) {
      clearTimeout(persistTimerRef.current);
      persistTimerRef.current = null;
    }
    latestSnapshotRef.current = null;
    userToggledThoughtRef.current = false;

    let active = true;

    const resetIdle = () => {
      setRunId(null);
      lastEventIdRef.current = null;
      lastSeqRef.current = 0;
      setGeneratedText("");
      setThoughtText("");
      setLiveOutlineText("");
      setShowThought(false);
      setControlsExpanded(false);
      setActiveAgent(null);
      setCompletedStages([]);
      setCriticInfo(null);
      setHitRelationIds([]);
      setOutlineApproval(null);
      setEditedOutline("");
      setApprovalFeedback("");
      setDiffReviewOpen(false);
      updateConnState("idle");
      setStatusDetail(null);
    };

    const restore = async () => {
      const key = storageKey(bookId, chapterId);
      let saved: PersistedRunSnapshot | null = null;
      if (typeof window !== "undefined") {
        const raw = window.sessionStorage.getItem(key);
        if (raw) {
          try {
            saved = JSON.parse(raw) as PersistedRunSnapshot;
          } catch {
            window.sessionStorage.removeItem(key);
          }
        }
      }

      let recent: AgentRunItem[] = [];
      let backendReachable = true;
      try {
        recent = await runsApi.list(bookId, chapterId ?? undefined);
      } catch {
        backendReachable = false;
      }
      if (!active) return;

      // 1. If backend has an in-flight run (RUNNING / QUEUED), ALWAYS reconnect SSE!
      const liveRun = recent.find(
        (r) => r.status === "RUNNING" || r.status === "QUEUED",
      );
      if (liveRun) {
        const sameSaved = saved && saved.runId === liveRun.id ? saved : null;
        const canIncrementalResume = Boolean(sameSaved?.lastEventId);
        const seedMode = (liveRun.mode ?? sameSaved?.mode ?? "MULTI_AGENT") as RunMode;
        const seedAction = liveRun.action ?? sameSaved?.action ?? "CONTINUE";
        const seedModel = liveRun.modelAlias || sameSaved?.modelAlias || "writer-main";
        const seedInstruction = liveRun.instruction || sameSaved?.instruction || "";
        const seedText = canIncrementalResume ? (sameSaved?.text ?? "") : "";
        const seedThought = canIncrementalResume ? (sameSaved?.thought ?? "") : "";
        const seedLiveOutline = canIncrementalResume ? (sameSaved?.liveOutline ?? "") : "";
        const seedSeq = canIncrementalResume ? (sameSaved?.lastSeq ?? 0) : 0;
        const seedAgent =
          (canIncrementalResume ? sameSaved?.activeAgent : null) ??
          liveRun.currentNode?.toLowerCase() ??
          (seedMode === "MULTI_AGENT" ? "director" : "writer");
        const seedStages = canIncrementalResume ? (sameSaved?.completedStages ?? []) : [];
        const seedCritic = canIncrementalResume ? (sameSaved?.criticInfo ?? null) : null;

        setRunId(liveRun.id);
        setMode(seedMode);
        setAction(seedAction);
        setModelAlias(seedModel);
        setInstruction(seedInstruction);
        lastEventIdRef.current = canIncrementalResume ? (sameSaved?.lastEventId ?? null) : null;
        lastSeqRef.current = seedSeq;
        setGeneratedText(seedText);
        setThoughtText(seedThought);
        setLiveOutlineText(seedLiveOutline);
        setControlsExpanded(false);
        setActiveAgent(seedAgent);
        setCompletedStages(seedStages);
        setCriticInfo(seedCritic);
        setOutlineApproval(null);
        setStatusDetail("已自动恢复进行中的创作任务...");

        attachStream(liveRun.id, canIncrementalResume ? (sameSaved?.lastEventId ?? null) : null, {
          mode: seedMode,
          action: seedAction,
          modelAlias: seedModel,
          instruction: seedInstruction,
          text: seedText,
          thought: seedThought,
          liveOutline: seedLiveOutline,
          seq: seedSeq,
          activeAgent: seedAgent,
          completedStages: seedStages,
          criticInfo: seedCritic,
          outlineApproval: null,
        });
        return;
      }

      // 2. If backend has a PAUSED run (HITL outline approval or user pause), restore it!
      const pausedRun = recent.find((r) => r.status === "PAUSED");
      if (pausedRun) {
        const sameSaved = saved && saved.runId === pausedRun.id ? saved : null;
        const parsedOutline = parseOutlineApprovalFromRun(pausedRun) ?? sameSaved?.outlineApproval ?? null;
        const nextMode = (pausedRun.mode ?? sameSaved?.mode ?? "MULTI_AGENT") as RunMode;
        const nextText = pausedRun.accumulatedText || sameSaved?.text || "";
        const nextThought = sameSaved?.thought ?? "";
        const nextStages = parsedOutline
          ? Array.from(new Set([...(sameSaved?.completedStages ?? []), "director"]))
          : (sameSaved?.completedStages ?? []);

        setRunId(pausedRun.id);
        setMode(nextMode);
        setAction(pausedRun.action);
        setModelAlias(pausedRun.modelAlias);
        setInstruction(pausedRun.instruction);
        lastEventIdRef.current = pausedRun.lastEventId ?? sameSaved?.lastEventId ?? null;
        lastSeqRef.current = Math.max(pausedRun.lastSeq, sameSaved?.lastSeq ?? 0);
        setGeneratedText(nextText);
        setThoughtText(nextThought);
        setLiveOutlineText("");
        setControlsExpanded(false);
        setActiveAgent(pausedRun.currentNode?.toLowerCase() ?? sameSaved?.activeAgent ?? "director");
        setCompletedStages(nextStages);
        setCriticInfo(sameSaved?.criticInfo ?? null);
        setOutlineApproval(parsedOutline);
        setEditedOutline(parsedOutline?.outlineText ?? "");
        setShowThought(false);
        updateConnState("paused");
        setStatusDetail(
          parsedOutline
            ? "大纲已拟定，确认或修改后即可开始撰写正文"
            : "已恢复上次暂停的创作进度，可补充想法后继续",
        );

        flushSnapshotNow({
          runId: pausedRun.id,
          mode: nextMode,
          action: pausedRun.action,
          modelAlias: pausedRun.modelAlias,
          instruction: pausedRun.instruction,
          lastEventId: pausedRun.lastEventId ?? sameSaved?.lastEventId ?? null,
          lastSeq: Math.max(pausedRun.lastSeq, sameSaved?.lastSeq ?? 0),
          text: nextText,
          thought: nextThought,
          liveOutline: "",
          state: "paused",
          activeAgent: pausedRun.currentNode?.toLowerCase() ?? sameSaved?.activeAgent ?? "director",
          completedStages: nextStages,
          criticInfo: sameSaved?.criticInfo ?? null,
          outlineApproval: parsedOutline,
        });
        return;
      }

      // 3. No active/paused run on backend: reconcile saved snapshot (handles run completing while page refreshed)
      if (saved) {
        let matchedRun = recent.find((r) => r.id === saved.runId) ?? null;
        if (!matchedRun && backendReachable && isActiveStreamState(saved.state)) {
          try {
            matchedRun = await runsApi.get(saved.runId);
          } catch {
            matchedRun = null;
          }
          if (!active) return;
        }

        if (!backendReachable && isActiveStreamState(saved.state)) {
          // Temporary network hiccup while loading run list: still try resuming saved SSE
          setRunId(saved.runId);
          setMode(saved.mode ?? "MULTI_AGENT");
          setAction(saved.action);
          setModelAlias(saved.modelAlias);
          setInstruction(saved.instruction);
          lastEventIdRef.current = saved.lastEventId;
          lastSeqRef.current = saved.lastSeq;
          setGeneratedText(saved.text);
          setThoughtText(saved.thought);
          setLiveOutlineText(saved.liveOutline ?? "");
          setControlsExpanded(false);
          setActiveAgent(saved.activeAgent ?? null);
          setCompletedStages(saved.completedStages ?? []);
          setCriticInfo(saved.criticInfo ?? null);
          setOutlineApproval(null);
          setStatusDetail("正在自动续接上次创作任务...");
          attachStream(saved.runId, saved.lastEventId, {
            mode: saved.mode ?? "MULTI_AGENT",
            action: saved.action,
            modelAlias: saved.modelAlias,
            instruction: saved.instruction,
            text: saved.text,
            thought: saved.thought,
            liveOutline: saved.liveOutline ?? "",
            seq: saved.lastSeq,
            activeAgent: saved.activeAgent,
            completedStages: saved.completedStages,
            criticInfo: saved.criticInfo,
          });
          return;
        }

        if (matchedRun) {
          const finalText =
            matchedRun.accumulatedText && matchedRun.accumulatedText.length >= saved.text.length
              ? matchedRun.accumulatedText
              : saved.text;
          const wasInFlight = isActiveStreamState(saved.state);

          setRunId(matchedRun.id);
          setMode((matchedRun.mode ?? saved.mode ?? "MULTI_AGENT") as RunMode);
          setAction(matchedRun.action);
          setModelAlias(matchedRun.modelAlias);
          setInstruction(matchedRun.instruction);
          lastEventIdRef.current = matchedRun.lastEventId ?? saved.lastEventId;
          lastSeqRef.current = Math.max(matchedRun.lastSeq, saved.lastSeq);
          setGeneratedText(finalText);
          setThoughtText(saved.thought);
          setLiveOutlineText("");
          setControlsExpanded(false);
          setShowThought(false);
          setCriticInfo(saved.criticInfo ?? null);
          setOutlineApproval(null);

          if (matchedRun.status === "COMPLETED") {
            const doneStages = ["director", "lorekeeper", "writer", "critic"];
            setCompletedStages(doneStages);
            updateConnState("completed");
            setStatusDetail(wasInFlight ? "上次创作已在后台完成，可直接采纳正文" : null);
            flushSnapshotNow({
              ...saved,
              lastEventId: matchedRun.lastEventId ?? saved.lastEventId,
              lastSeq: Math.max(matchedRun.lastSeq, saved.lastSeq),
              text: finalText,
              liveOutline: "",
              state: "completed",
              completedStages: doneStages,
              outlineApproval: null,
            });
            return;
          }
          if (matchedRun.status === "FAILED") {
            updateConnState("error");
            setStatusDetail({ error: formatStreamError(matchedRun.errorMessage) });
            flushSnapshotNow({
              ...saved,
              text: finalText,
              liveOutline: "",
              state: "error",
              outlineApproval: null,
            });
            return;
          }
          if (matchedRun.status === "CANCELLED") {
            updateConnState("cancelled");
            setStatusDetail("该次创作已取消");
            flushSnapshotNow({
              ...saved,
              text: finalText,
              liveOutline: "",
              state: "cancelled",
              outlineApproval: null,
            });
            return;
          }
        }

        // Keep previously completed output visible if still in sessionStorage
        if (saved.state === "completed" && saved.text) {
          setRunId(saved.runId);
          setMode(saved.mode ?? "MULTI_AGENT");
          setAction(saved.action);
          setModelAlias(saved.modelAlias);
          setInstruction(saved.instruction);
          lastEventIdRef.current = saved.lastEventId;
          lastSeqRef.current = saved.lastSeq;
          setGeneratedText(saved.text);
          setThoughtText(saved.thought);
          setLiveOutlineText("");
          setControlsExpanded(false);
          setShowThought(false);
          setActiveAgent(saved.activeAgent ?? null);
          setCompletedStages(saved.completedStages ?? ["director", "lorekeeper", "writer", "critic"]);
          setCriticInfo(saved.criticInfo ?? null);
          setHitRelationIds(saved.hitRelationIds ?? []);
          setOutlineApproval(null);
          updateConnState("completed");
          setStatusDetail(null);
          latestSnapshotRef.current = saved;
          return;
        }
      }

      resetIdle();
    };

    void restore();

    return () => {
      active = false;
      if (latestSnapshotRef.current) {
        writeSnapshotToStorage(latestSnapshotRef.current);
      }
      if (persistTimerRef.current) {
        clearTimeout(persistTimerRef.current);
        persistTimerRef.current = null;
      }
      sseHandleRef.current?.disconnect();
      sseHandleRef.current = null;
    };
  }, [bookId, chapterId, attachStream, flushSnapshotNow, updateConnState, writeSnapshotToStorage]);

  async function handleStart() {
    setBusyControl(true);
    setActiveTray("none");
    setStatusDetail("正在启动创作...");
    setControlsExpanded(false);
    setGeneratedText("");
    setThoughtText("");
    setLiveOutlineText("");
    setShowThought(false);
    userToggledThoughtRef.current = false;
    lastEventIdRef.current = null;
    lastSeqRef.current = 0;
    isAtBottomRef.current = true;
    setIsAtBottom(true);
    setRevisedInstruction("");
    setCriticInfo(null);
    setHitRelationIds([]);
    setOutlineApproval(null);
    setEditedOutline("");
    setApprovalFeedback("");
    setCompletedStages([]);
    setDiffReviewOpen(false);
    setAcceptedParagraphs({});

    const effectiveAction: RunAction = selectionTask ? "REWRITE" : action;
    const effectiveMode: RunMode = selectionTask
      ? mode === "MULTI_AGENT"
        ? "AGENT"
        : mode
      : effectiveAction === "OUTLINE" && mode === "MULTI_AGENT"
        ? "AGENT"
        : mode;
    const effectiveApproval =
      !selectionTask && effectiveMode === "MULTI_AGENT" && effectiveAction !== "OUTLINE"
        ? requireOutlineApproval
        : false;
    const effectiveInstruction = selectionTask
      ? [
          selectionTask.mode === "EXPAND"
            ? "请仅针对以下【划选片段】进行细节扩写（丰富动作、感官氛围与情绪张力，保持与上下文无缝衔接），只输出改写扩写后的该段正文，不要复述其他未选中段落："
            : "请仅针对以下【划选片段】进行精细改写润色（提升文笔节奏与人物神态，严格保持既有事实设定与上下句衔接），只输出改写后的该段正文，不要复述其他未选中段落：",
          instruction.trim() ? `作者补充要求：${instruction.trim()}` : "",
          `【划选片段】\n${selectionTask.text}`,
        ]
          .filter(Boolean)
          .join("\n\n")
      : instruction;
    const initialAgent = effectiveMode === "MULTI_AGENT" ? "director" : "writer";
    setActiveAgent(initialAgent);

    try {
      const created = await runsApi.start({
        bookId,
        chapterId: chapterId ?? undefined,
        sectionId: sectionId ?? undefined,
        mode: effectiveMode,
        action: effectiveAction,
        modelAlias,
        instruction: effectiveInstruction,
        chapterContext: chapterContent,
        requireOutlineApproval: effectiveApproval,
        povCharacter: povCharacter.trim() || undefined,
        foreshadowingDirectives:
          foreshadowingDirectives.length > 0 ? foreshadowingDirectives : undefined,
      });
      setRunId(created.id);
      flushSnapshotNow({
        runId: created.id,
        mode: effectiveMode,
        action: effectiveAction,
        modelAlias,
        instruction: effectiveInstruction,
        lastEventId: null,
        lastSeq: 0,
        text: "",
        thought: "",
        liveOutline: "",
        state: "connecting",
        activeAgent: initialAgent,
        completedStages: [],
        criticInfo: null,
        outlineApproval: null,
      });
      attachStream(created.id, null, {
        mode: effectiveMode,
        action: effectiveAction,
        modelAlias,
        instruction: effectiveInstruction,
        text: "",
        thought: "",
        liveOutline: "",
        seq: 0,
        activeAgent: initialAgent,
        completedStages: [],
      });
    } catch (err) {
      updateConnState("error");
      setStatusDetail({ error: formatWebError(err, "启动创作失败，请稍后重试", { action: "generate" }) });
    } finally {
      setBusyControl(false);
    }
  }

  async function handlePause() {
    if (!runId) return;
    setBusyControl(true);
    try {
      await runsApi.pause(runId);
      setStatusDetail("正在保存进度并暂停…");
    } catch (err) {
      setStatusDetail({ error: formatWebError(err, "暂停失败，请稍后重试", { action: "generate" }) });
    } finally {
      setBusyControl(false);
    }
  }

  async function handleResume() {
    if (!runId) return;
    setBusyControl(true);
    setStatusDetail("正在继续创作...");
    try {
      const nextInstruction = revisedInstruction.trim() || instruction;
      const resumed = await runsApi.resume(runId, revisedInstruction.trim() || undefined);
      setInstruction(resumed.instruction);
      setRevisedInstruction("");
      const cursor = lastEventIdRef.current;
      const curSeq = lastSeqRef.current;
      flushSnapshotNow({
        runId,
        mode,
        action,
        modelAlias,
        instruction: nextInstruction,
        lastEventId: cursor,
        lastSeq: curSeq,
        text: generatedText,
        thought: thoughtText,
        liveOutline: liveOutlineText,
        state: "connecting",
        activeAgent,
        completedStages,
        criticInfo,
        outlineApproval: null,
      });
      attachStream(runId, cursor, {
        mode,
        action,
        modelAlias,
        instruction: nextInstruction,
        text: generatedText,
        thought: thoughtText,
        liveOutline: liveOutlineText,
        seq: curSeq,
        activeAgent,
        completedStages,
        criticInfo,
      });
    } catch (err) {
      updateConnState("error");
      setStatusDetail({ error: formatWebError(err, "继续创作失败，请稍后重试", { action: "generate" }) });
    } finally {
      setBusyControl(false);
    }
  }

  async function handleApproveOutline(approved: boolean) {
    if (!runId) return;
    setBusyControl(true);
    setControlsExpanded(false);
    const nextAgent = approved ? "lorekeeper" : "director";
    setStatusDetail(
      approved
        ? "已确认大纲，正在核对前文并撰写正文..."
        : "正在根据你的意见重新构思大纲...",
    );
    try {
      await runsApi.approve(runId, {
        approved,
        editedOutline: editedOutline.trim() || outlineApproval?.outlineText || "",
        feedback: approvalFeedback.trim() || undefined,
      });
      setOutlineApproval(null);
      setLiveOutlineText("");
      setApprovalFeedback("");
      setActiveAgent(nextAgent);
      isAtBottomRef.current = true;
      setIsAtBottom(true);
      const cursor = lastEventIdRef.current;
      const curSeq = lastSeqRef.current;
      flushSnapshotNow({
        runId,
        mode,
        action,
        modelAlias,
        instruction,
        lastEventId: cursor,
        lastSeq: curSeq,
        text: generatedText,
        thought: thoughtText,
        liveOutline: "",
        state: "connecting",
        activeAgent: nextAgent,
        completedStages,
        criticInfo,
        outlineApproval: null,
      });
      attachStream(runId, cursor, {
        mode,
        action,
        modelAlias,
        instruction,
        text: generatedText,
        thought: thoughtText,
        liveOutline: "",
        seq: curSeq,
        activeAgent: nextAgent,
        completedStages,
        criticInfo,
        outlineApproval: null,
      });
    } catch (err) {
      updateConnState("error");
      setStatusDetail({ error: formatWebError(err, "提交确认失败，请稍后重试", { action: "generate" }) });
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
        updateConnState("cancelled");
        setOutlineApproval(null);
        setLiveOutlineText("");
        setStatusDetail("已取消本次创作");
        flushSnapshotNow(null);
      }
    } catch (err) {
      setStatusDetail({ error: formatWebError(err, "取消失败，请稍后重试", { action: "generate" }) });
    } finally {
      setBusyControl(false);
    }
  }

  function handleClear() {
    sseHandleRef.current?.disconnect();
    flushSnapshotNow(null);
    setRunId(null);
    lastEventIdRef.current = null;
    lastSeqRef.current = 0;
    setGeneratedText("");
    setThoughtText("");
    setLiveOutlineText("");
    setShowThought(false);
    setControlsExpanded(false);
    userToggledThoughtRef.current = false;
    setActiveAgent(null);
    setCompletedStages([]);
    setCriticInfo(null);
    setHitRelationIds([]);
    setOutlineApproval(null);
    setDiffReviewOpen(false);
    updateConnState("idle");
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

  const isRunning = isActiveStreamState(connState);
  const isOutlineApprovalMode = connState === "paused" && Boolean(outlineApproval);
  const isCompactCompleted =
    connState === "completed" && Boolean(generatedText) && !controlsExpanded;
  const badge = STATE_BADGE[connState];
  const showProgressStrip =
    mode === "MULTI_AGENT" &&
    action !== "OUTLINE" &&
    (isRunning || connState === "paused" || connState === "completed");

  const displayedOutline = useSmoothTypewriter(
    liveOutlineText,
    isRunning && activeAgent === "director" && !generatedText,
  );
  const displayedText = useSmoothTypewriter(
    generatedText,
    isRunning && Boolean(generatedText),
  );
  const displayedThought = useSmoothTypewriter(
    thoughtText,
    isRunning && showThought && !generatedText,
  );

  const handleOutputScroll = useCallback(() => {
    const el = outputScrollRef.current;
    if (!el) return;
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
    const atBottom = distance <= 48;
    isAtBottomRef.current = atBottom;
    setIsAtBottom((prev) => (prev === atBottom ? prev : atBottom));
  }, []);

  useEffect(() => {
    if (!isRunning || !isAtBottomRef.current) return;
    const el = outputScrollRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }, [isRunning, displayedOutline, displayedText, displayedThought]);

  const scrollToBottom = useCallback(() => {
    const el = outputScrollRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
    isAtBottomRef.current = true;
    setIsAtBottom(true);
  }, []);

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden text-xs">
      {/* Control Area (Auto-compacts during streaming, outline approval, and completed states) */}
      <div
        className={`shrink-0 border-b border-border/70 ${
          compact ? "space-y-2 p-2.5" : "space-y-2.5 p-3"
        }`}
      >
        <div className="flex items-center justify-between gap-2">
          <div className="flex min-w-0 items-center gap-2">
            <span className={`inline-block size-2 shrink-0 rounded-full ${badge.dot}`} />
            <span className="font-medium text-foreground">{badge.label}</span>
            {chapterNo != null && chapterNo > 1 && (
              <span className="truncate rounded-full border border-status-success-border bg-status-success-bg px-2 py-0.5 text-[10px] font-medium text-status-success">
                衔接第 {chapterNo - 1} 章
              </span>
            )}
          </div>

          {isRunning && (
            <div className="flex shrink-0 items-center gap-1">
              <Button
                size="xs"
                variant="secondary"
                disabled={busyControl}
                onClick={() => void handlePause()}
              >
                暂停
              </Button>
              <Button
                size="xs"
                variant="ghost"
                disabled={busyControl}
                onClick={() => void handleCancel()}
                className="text-status-danger hover:bg-status-danger-bg hover:text-status-danger"
              >
                取消
              </Button>
            </div>
          )}

          {isCompactCompleted && (
            <div className="flex shrink-0 items-center gap-1">
              <Button
                size="xs"
                variant="outline"
                onClick={() => setControlsExpanded(true)}
              >
                调整指令
              </Button>
              <Button size="xs" variant="ghost" onClick={handleClear}>
                清空
              </Button>
            </div>
          )}
        </div>

        {/* Full Form vs Compact Running/Completed/Approval Header */}
        {isRunning ? (
          instruction.trim() ? (
            <p
              title={instruction}
              className="truncate rounded-lg bg-surface-subtle px-2.5 py-1.5 text-[11px] text-muted-foreground"
            >
              指令：{instruction.trim()}
            </p>
          ) : null
        ) : isOutlineApprovalMode ? null : isCompactCompleted ? (
          instruction.trim() ? (
            <p
              title={instruction}
              className="truncate rounded-lg bg-surface-subtle px-2.5 py-1.5 text-[11px] text-muted-foreground"
            >
              本次指令：{instruction.trim()}
            </p>
          ) : null
        ) : (
          <>
            {/* Inline Selection Task Banner */}
            {selectionTask && (
              <div className="space-y-1.5 rounded-xl border border-status-ai-border bg-card p-3 shadow-2xs">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-semibold text-status-ai">
                    {selectionTask.mode === "EXPAND"
                      ? `划词扩写模式（已选 ${selectionTask.text.length} 字）`
                      : `划词改写模式（已选 ${selectionTask.text.length} 字）`}
                  </span>
                  {onClearSelectionTask && (
                    <button
                      type="button"
                      onClick={onClearSelectionTask}
                      className="text-[11px] text-muted-foreground hover:text-foreground"
                    >
                      取消划词
                    </button>
                  )}
                </div>
                <p className="line-clamp-2 whitespace-pre-wrap rounded-lg bg-surface-subtle px-2.5 py-1.5 text-[11px] text-muted-foreground">
                  {selectionTask.text}
                </p>
              </div>
            )}

            {/* Segmented Action Pills */}
            <div className="grid grid-cols-3 gap-1 rounded-xl bg-muted p-1">
              {ACTION_OPTIONS.map((opt) => (
                <button
                  key={opt.value}
                  type="button"
                  disabled={connState === "paused"}
                  onClick={() => setAction(opt.value)}
                  className={`rounded-lg px-2 py-1.5 text-[11.5px] font-medium transition-all ${
                    action === opt.value
                      ? "bg-card font-semibold text-foreground shadow-2xs"
                      : "text-muted-foreground hover:text-foreground"
                  } disabled:opacity-50`}
                >
                  {opt.label}
                </button>
              ))}
            </div>

            {connState === "paused" ? (
              <div className="space-y-2.5 rounded-2xl border border-border bg-card p-3.5 shadow-2xs">
                <textarea
                  rows={2}
                  value={revisedInstruction}
                  onChange={(e) => setRevisedInstruction(e.target.value)}
                  placeholder="可选：补充或微调接下来的剧情走向..."
                  className="w-full resize-none rounded-xl border border-border bg-surface-subtle px-3 py-2 text-xs leading-relaxed outline-none focus:border-ring"
                />
                <div className="flex gap-1.5">
                  <Button
                    size="sm"
                    className="flex-1"
                    disabled={busyControl}
                    onClick={() => void handleResume()}
                  >
                    继续生成
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={busyControl}
                    onClick={() => void handleCancel()}
                    className="text-status-danger hover:bg-status-danger-bg hover:text-status-danger"
                  >
                    取消
                  </Button>
                </div>
              </div>
            ) : (
              <div className="overflow-hidden rounded-2xl border border-border bg-card shadow-2xs transition-colors focus-within:border-ring/80">
                <textarea
                  rows={compact ? 2 : 3}
                  value={instruction}
                  onChange={(e) => setInstruction(e.target.value)}
                  onKeyDown={(e) => {
                    if ((e.metaKey || e.ctrlKey) && e.key === "Enter" && !busyControl) {
                      e.preventDefault();
                      void handleStart();
                    } else if (e.key === "Escape" && activeTray !== "none") {
                      setActiveTray("none");
                    }
                  }}
                  placeholder="本章剧情方向（选填）"
                  className="block w-full resize-none bg-transparent px-3 pt-2.5 pb-1.5 text-xs leading-relaxed text-foreground placeholder:text-muted-foreground/70 outline-none"
                />

                {/* Active POV & Foreshadowing Chips (0px height when empty) */}
                {(povCharacter.trim() || foreshadowingDirectives.length > 0) && (
                  <div className="flex flex-wrap items-center gap-1 px-2.5 pb-2">
                    {povCharacter.trim() && (
                      <span className="inline-flex items-center gap-1 rounded-full border border-status-info-border bg-status-info-bg px-2 py-0.5 text-[10px] font-medium text-status-info">
                        <span>视角：{povCharacter.trim()}</span>
                        <button
                          type="button"
                          onClick={() => setPovCharacter("")}
                          aria-label="清除叙事视角"
                          className="rounded-full hover:opacity-75"
                        >
                          <X className="size-2.5" />
                        </button>
                      </span>
                    )}
                    {foreshadowingDirectives.map((dir) => {
                      const matched = directorData.arcById.get(dir.foreshadowingId);
                      const modeTag =
                        dir.mode === "PLANT" ? "埋入" : dir.mode === "HINT" ? "照应" : "引爆";
                      const title = matched
                        ? `${matched.code ? `${matched.code} ` : ""}${matched.title}`
                        : "已选伏笔";
                      return (
                        <span
                          key={dir.foreshadowingId}
                          className={`inline-flex max-w-48 items-center gap-1 rounded-full border px-2 py-0.5 text-[10px] font-medium ${
                            dir.mode === "PAYOFF"
                              ? "border-status-warning-border bg-status-warning-bg text-status-warning"
                              : "border-primary/35 bg-primary-soft text-primary-soft-foreground"
                          }`}
                        >
                          <span className="truncate">
                            {modeTag} · {title}
                          </span>
                          <button
                            type="button"
                            onClick={() =>
                              setForeshadowingDirectives((prev) =>
                                prev.filter((d) => d.foreshadowingId !== dir.foreshadowingId),
                              )
                            }
                            aria-label={`移除伏笔指令 ${title}`}
                            className="shrink-0 rounded-full hover:opacity-75"
                          >
                            <X className="size-2.5" />
                          </button>
                        </span>
                      );
                    })}
                  </div>
                )}

                {/* Unified Bottom Composer Toolbar */}
                <div className="flex items-center justify-between gap-1.5 border-t border-border/60 bg-surface-subtle/45 px-2 py-1.5">
                  <div className="flex min-w-0 items-center gap-1">
                    <button
                      type="button"
                      onClick={() =>
                        setActiveTray((cur) => (cur === "director" ? "none" : "director"))
                      }
                      title="配置本章叙事视角（POV）与伏笔埋设/照应/回收"
                      className={`inline-flex h-7 items-center gap-1 rounded-lg px-2 text-[11px] font-medium transition-colors ${
                        activeTray === "director"
                          ? "bg-primary-soft text-primary-soft-foreground"
                          : foreshadowingDirectives.length > 0 || povCharacter.trim()
                            ? "bg-primary-soft/65 text-primary-soft-foreground hover:bg-primary-soft"
                            : "text-muted-foreground hover:bg-muted hover:text-foreground"
                      }`}
                    >
                      <Bookmark className="size-3.5 shrink-0" />
                      <span className="truncate">伏笔·视角</span>
                      {foreshadowingDirectives.length > 0 ? (
                        <span className="rounded-full bg-primary px-1.5 py-0 font-mono text-[9.5px] leading-4 text-primary-foreground">
                          {foreshadowingDirectives.length}
                        </span>
                      ) : directorData.dueCount > 0 ? (
                        <span className="rounded-full border border-status-warning-border bg-status-warning-bg px-1.5 py-0 font-mono text-[9.5px] leading-4 text-status-warning">
                          {directorData.dueCount}到期
                        </span>
                      ) : null}
                    </button>

                    <button
                      type="button"
                      onClick={() =>
                        setActiveTray((cur) => (cur === "settings" ? "none" : "settings"))
                      }
                      title="切换创作模式、模型与大纲确认开关"
                      className={`inline-flex h-7 items-center gap-1 rounded-lg px-2 text-[11px] font-medium transition-colors ${
                        activeTray === "settings"
                          ? "bg-primary-soft text-primary-soft-foreground"
                          : "text-muted-foreground hover:bg-muted hover:text-foreground"
                      }`}
                    >
                      <SlidersHorizontal className="size-3.5 shrink-0" />
                      <span className="truncate">
                        {MODE_OPTIONS.find((m) => m.value === mode)?.short ?? "精写"}
                        {mode === "MULTI_AGENT" && action !== "OUTLINE" && requireOutlineApproval
                          ? "·审纲"
                          : ""}
                      </span>
                    </button>
                  </div>

                  <div className="flex shrink-0 items-center gap-1">
                    {connState === "completed" && generatedText && controlsExpanded && (
                      <Button
                        size="xs"
                        variant="ghost"
                        onClick={() => setControlsExpanded(false)}
                      >
                        收起
                      </Button>
                    )}
                    {(generatedText || thoughtText || liveOutlineText || connState !== "idle") && (
                      <Button size="xs" variant="ghost" onClick={handleClear}>
                        清空
                      </Button>
                    )}
                    <Button
                      size="xs"
                      title="开始 (Ctrl+Enter)"
                      disabled={busyControl}
                      onClick={() => void handleStart()}
                      className="h-7 px-2.5"
                    >
                      <Sparkles className="size-3" />
                      {selectionTask
                        ? selectionTask.mode === "EXPAND"
                          ? "扩写选段"
                          : "改写选段"
                        : action === "OUTLINE"
                          ? "生成大纲"
                          : action === "REWRITE"
                            ? "润色扩写"
                            : "开始创作"}
                    </Button>
                  </div>
                </div>

                {/* Mutually Exclusive Pop-Down Tray inside Composer Card */}
                {activeTray === "director" && (
                  <div className="border-t border-border/70 bg-surface-subtle/60 p-2.5">
                    <ForeshadowingDirectorTray
                      visibility={visibility}
                      characters={directorData.characters}
                      planned={directorData.planned}
                      activePlanted={directorData.activePlanted}
                      disabled={false}
                      povCharacter={povCharacter}
                      onPovCharacterChange={setPovCharacter}
                      directives={foreshadowingDirectives}
                      onDirectivesChange={setForeshadowingDirectives}
                      onClose={() => setActiveTray("none")}
                    />
                  </div>
                )}

                {activeTray === "settings" && (
                  <div className="space-y-2.5 border-t border-border/70 bg-surface-subtle/60 p-2.5 text-[11px]">
                    <div className="flex items-center justify-between">
                      <span className="font-semibold text-foreground">写作设置</span>
                      <button
                        type="button"
                        onClick={() => setActiveTray("none")}
                        aria-label="收起写作设置"
                        className="rounded-md p-0.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                      >
                        <X className="size-3.5" />
                      </button>
                    </div>

                    <div className="space-y-1">
                      <div className="flex items-center justify-between text-[10px] text-muted-foreground">
                        <span>创作模式</span>
                        <span>{MODE_OPTIONS.find((m) => m.value === mode)?.desc}</span>
                      </div>
                      <div className="grid grid-cols-3 gap-1 rounded-lg bg-muted p-0.5">
                        {MODE_OPTIONS.map((opt) => (
                          <button
                            key={opt.value}
                            type="button"
                            onClick={() => setMode(opt.value)}
                            className={`rounded-md px-2 py-1 text-[11px] font-medium transition-all ${
                              mode === opt.value
                                ? "bg-card text-foreground shadow-2xs"
                                : "text-muted-foreground hover:text-foreground"
                            }`}
                          >
                            {opt.label}
                          </button>
                        ))}
                      </div>
                    </div>

                    <div className="space-y-1">
                      <div className="text-[10px] text-muted-foreground">写作模型</div>
                      <div className="grid grid-cols-3 gap-1 rounded-lg bg-muted p-0.5">
                        {MODEL_OPTIONS.map((opt) => (
                          <button
                            key={opt.value}
                            type="button"
                            onClick={() => setModelAlias(opt.value)}
                            className={`rounded-md px-2 py-1 text-[11px] font-medium transition-all ${
                              modelAlias === opt.value
                                ? "bg-card text-foreground shadow-2xs"
                                : "text-muted-foreground hover:text-foreground"
                            }`}
                          >
                            {opt.label}
                          </button>
                        ))}
                      </div>
                    </div>

                    {mode === "MULTI_AGENT" && action !== "OUTLINE" && (
                      <div className="flex items-center justify-between gap-2 rounded-lg border border-border/70 bg-card px-2.5 py-2">
                        <span className="text-[11px] font-medium text-foreground">
                          先确认大纲再动笔
                        </span>
                        <Switch
                          aria-label="先确认大纲再动笔"
                          checked={requireOutlineApproval}
                          onCheckedChange={setRequireOutlineApproval}
                        />
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}
          </>
        )}

        {/* Minimal Connected Stepper + Fixed-Height Status Note (prevents layout jitter) */}
        {(showProgressStrip || statusDetail) && (
          <div className="space-y-2 rounded-xl border border-border bg-card p-2.5">
            {showProgressStrip && (
              <div className="relative grid grid-cols-4 items-start text-center">
                <span
                  aria-hidden
                  className="pointer-events-none absolute top-[5px] right-[12.5%] left-[12.5%] h-[1.5px] bg-border"
                />
                {WRITER_STAGES.map((stage) => {
                  const isCurrent =
                    activeAgent === stage.id && (isRunning || connState === "paused");
                  const isDone =
                    connState === "completed" || completedStages.includes(stage.id);
                  return (
                    <div
                      key={stage.id}
                      className="relative z-10 flex flex-col items-center gap-1"
                    >
                      <span
                        className={`size-[11px] rounded-full border-2 transition-all ${
                          isCurrent
                            ? "border-primary bg-primary shadow-[0_0_0_3px_var(--primary-soft)]"
                            : isDone
                              ? "border-primary bg-primary"
                              : "border-border bg-card"
                        }`}
                      />
                      <span
                        className={`text-[10.5px] transition-colors ${
                          isCurrent || isDone
                            ? "font-semibold text-primary-soft-foreground"
                            : "text-muted-foreground"
                        }`}
                      >
                        {stage.label}
                      </span>
                    </div>
                  );
                })}
              </div>
            )}

            {statusDetail && (
              <div
                title={statusDetail}
                className={`flex h-8 items-center rounded-lg px-2.5 text-[11px] ${
                  statusIsError
                    ? "border border-status-danger-border bg-status-danger-bg text-status-danger"
                    : "bg-surface-subtle text-muted-foreground"
                }`}
              >
                {isRunning && !statusIsError && connState !== "reconnecting" ? (
                  <ShinyText>{statusDetail}</ShinyText>
                ) : (
                  <span className="truncate">{statusDetail}</span>
                )}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Main Output / Outline Approval Area */}
      {isOutlineApprovalMode && outlineApproval ? (
        <div
          className={`flex min-h-0 flex-1 flex-col ${
            compact ? "p-2.5" : "p-3.5"
          }`}
        >
          <div className="flex min-h-0 flex-1 flex-col gap-2.5 rounded-2xl border border-border bg-card p-3.5 shadow-[0_1px_2px_oklch(0.2_0.02_200/0.04),0_8px_24px_oklch(0.2_0.02_200/0.05)]">
            <div className="flex shrink-0 items-center justify-between gap-2">
              <div className="text-[13px] font-semibold text-foreground">确认本章大纲</div>
              <span className="rounded-full bg-primary-soft px-2 py-0.5 text-[10px] font-medium text-primary-soft-foreground">
                待确认
              </span>
            </div>
            <textarea
              value={editedOutline}
              onChange={(e) => setEditedOutline(e.target.value)}
              className="min-h-[220px] w-full flex-1 resize-none rounded-xl border border-border bg-surface-subtle px-3 py-2.5 text-xs leading-6 text-foreground outline-none transition-colors focus:border-ring [scrollbar-gutter:stable]"
            />
            <input
              value={approvalFeedback}
              onChange={(e) => setApprovalFeedback(e.target.value)}
              placeholder="调整意见（选填）"
              className="h-8 w-full shrink-0 rounded-lg border border-border bg-background px-2.5 text-[11.5px] text-foreground placeholder:text-muted-foreground/75 outline-none transition-colors focus:border-ring"
            />
            <div className="flex shrink-0 items-center gap-1.5 pt-0.5">
              <Button
                size="sm"
                className="flex-1"
                disabled={busyControl}
                onClick={() => void handleApproveOutline(true)}
              >
                确认并开始写作
              </Button>
              <Button
                size="sm"
                variant="secondary"
                disabled={busyControl}
                onClick={() => void handleApproveOutline(false)}
              >
                重新构思
              </Button>
              <Button
                size="sm"
                variant="ghost"
                disabled={busyControl}
                onClick={() => void handleCancel()}
                className="px-2 text-status-danger hover:bg-status-danger-bg hover:text-status-danger"
              >
                取消
              </Button>
            </div>
          </div>
        </div>
      ) : (
        <div className="relative flex min-h-0 flex-1 flex-col">
          <div
            ref={outputScrollRef}
            onScroll={handleOutputScroll}
            className={`flex min-h-0 flex-1 flex-col overflow-y-auto [scrollbar-gutter:stable] [overflow-anchor:none] ${
              compact ? "space-y-2 p-2.5" : "space-y-3 p-3.5"
            }`}
          >
            {criticInfo && (
              <div
                className={`rounded-xl border px-3 py-2.5 text-[11px] ${
                  criticInfo.passed
                    ? "border-status-success-border bg-status-success-bg text-status-success"
                    : "border-status-warning-border bg-status-warning-bg text-status-warning"
                }`}
              >
                <div className="font-medium">
                  {criticInfo.passed
                    ? "剧情连贯性与设定核查通过"
                    : "检测到衔接或设定问题，已自动调整重写"}
                </div>
                {criticInfo.issues.length > 0 && (
                  <ul className="mt-1 list-disc space-y-0.5 pl-4 text-[10px] text-foreground/85">
                    {criticInfo.issues.map((iss, idx) => (
                      <li key={idx}>{iss}</li>
                    ))}
                  </ul>
                )}
              </div>
            )}

            {hitRelationIds.length > 0 && onHighlightRelationsInGraph && (
              <div className="flex items-center justify-between gap-2 rounded-xl border border-status-ai-border bg-status-ai-bg px-3 py-2 text-[11px] text-status-ai">
                <span className="truncate font-medium">
                  已参考 {hitRelationIds.length} 条人物关系长记忆
                </span>
                <button
                  type="button"
                  onClick={() => onHighlightRelationsInGraph(hitRelationIds)}
                  className="shrink-0 rounded-md border border-status-ai-border bg-card px-2 py-0.5 text-[10.5px] font-semibold text-status-ai transition-colors hover:bg-primary hover:text-primary-foreground"
                >
                  在图谱高亮 →
                </button>
              </div>
            )}

            {!generatedText && liveOutlineText && (
              <div className="rounded-xl border border-border bg-card p-3.5 shadow-2xs">
                <div className="mb-2 flex items-center justify-between gap-2">
                  <span className="text-[11.5px] font-semibold text-foreground">
                    正在构思本章大纲
                  </span>
                  {isRunning && activeAgent === "director" && (
                    <span className="rounded-full bg-primary-soft px-2 py-0.5 text-[10px] font-medium text-primary-soft-foreground">
                      生成中
                    </span>
                  )}
                </div>
                <div className="whitespace-pre-wrap text-xs leading-6 text-foreground">
                  {displayedOutline}
                  {isRunning && activeAgent === "director" && (
                    <span className="ml-0.5 inline-block h-3.5 w-1.5 animate-pulse rounded-xs bg-primary align-middle" />
                  )}
                </div>
              </div>
            )}

            {thoughtText && (
              <div className="rounded-xl border border-border bg-card p-3">
                <button
                  type="button"
                  onClick={() => {
                    userToggledThoughtRef.current = true;
                    setShowThought((v) => !v);
                  }}
                  className="flex w-full items-center justify-between text-[11px] font-medium text-muted-foreground hover:text-foreground"
                >
                  <span>构思大纲与校对记录</span>
                  <span>{showThought ? "收起" : "展开"}</span>
                </button>
                {showThought && (
                  <div className="mt-2 whitespace-pre-wrap text-[11px] leading-relaxed text-muted-foreground">
                    {displayedThought}
                  </div>
                )}
              </div>
            )}

            {generatedText ? (
              diffReviewOpen && !isRunning ? (
                <div className="space-y-2 rounded-xl border border-status-success-border bg-card p-3">
                  <div className="flex items-center justify-between">
                    <span className="font-semibold text-status-success">
                      逐段挑选采纳
                    </span>
                    <button
                      type="button"
                      onClick={() => setDiffReviewOpen(false)}
                      className="text-[11px] text-muted-foreground hover:text-foreground"
                    >
                      返回全文
                    </button>
                  </div>
                  <div className="space-y-1.5">
                    {generatedParagraphs.map((para, idx) => {
                      const accepted = acceptedParagraphs[idx] !== false;
                      return (
                        <div
                          key={idx}
                          className={`rounded-lg border p-2.5 text-[11px] leading-relaxed ${
                            accepted
                              ? "border-status-success-border bg-status-success-bg"
                              : "border-border bg-muted/50 opacity-55 line-through"
                          }`}
                        >
                          <div className="mb-1 flex items-center justify-between text-[10px]">
                            <span>{accepted ? `采纳第 ${idx + 1} 段` : `跳过第 ${idx + 1} 段`}</span>
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
                              {accepted ? "跳过" : "恢复"}
                            </button>
                          </div>
                          <p className="whitespace-pre-wrap">{para}</p>
                        </div>
                      );
                    })}
                  </div>
                  <Button size="sm" className="w-full" onClick={handleApplySelectedDiff}>
                    将已选段落追加到正文
                  </Button>
                </div>
              ) : (
                <div className="rounded-xl border border-border bg-card p-3.5 leading-relaxed shadow-2xs">
                  <div className="whitespace-pre-wrap font-editor text-[13.5px] leading-7 text-foreground">
                    {displayedText}
                    {isRunning && (
                      <span className="ml-0.5 inline-block h-3.5 w-1.5 animate-pulse rounded-xs bg-primary align-middle" />
                    )}
                  </div>
                </div>
              )
            ) : connState === "completed" && !outlineApproval ? (
              <div className="rounded-xl border border-status-warning-border bg-status-warning-bg p-3.5 text-xs text-status-warning">
                本次未返回正文内容，请切换模型或重试。
              </div>
            ) : (
              !thoughtText &&
              !liveOutlineText && (
                <div className="flex flex-1 items-center justify-center p-6 text-center text-xs text-muted-foreground">
                  准备就绪
                </div>
              )
            )}
          </div>

          {!isAtBottom && isRunning && (
            <button
              type="button"
              onClick={scrollToBottom}
              className="absolute right-4 bottom-3 z-10 rounded-full border border-border bg-card/95 px-2.5 py-1 text-[10.5px] font-medium text-foreground shadow-md backdrop-blur-xs transition-colors hover:bg-primary-soft hover:text-primary-soft-foreground"
            >
              回到底部 ↓
            </button>
          )}
        </div>
      )}

      {/* Adopt Output Actions */}
      {generatedText && !isRunning && (
        <div className="space-y-1.5 border-t border-border bg-card p-3">
          {selectionTask && onReviewDiff ? (
            <div className="flex gap-1.5">
              <Button
                size="sm"
                className="flex-1"
                onClick={() =>
                  onReviewDiff({
                    originalText: selectionTask.text,
                    proposedText: generatedText,
                    mode: "SELECTION",
                    selectionRange: {
                      start: selectionTask.start,
                      end: selectionTask.end,
                    },
                  })
                }
              >
                审阅后替换选段
              </Button>
              <Button
                size="sm"
                variant="secondary"
                onClick={() => onAppendText(generatedText)}
              >
                追加末尾
              </Button>
            </div>
          ) : (
            <div className="flex flex-wrap gap-1.5">
              <Button
                size="sm"
                className="flex-1"
                onClick={() => onAppendText(generatedText)}
              >
                追加末尾
              </Button>
              <Button
                size="sm"
                variant="secondary"
                onClick={handleOpenDiffReview}
              >
                {diffReviewOpen ? "全文" : "逐段挑选"}
              </Button>
              {onReviewDiff ? (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() =>
                    onReviewDiff({
                      originalText: chapterContent,
                      proposedText: generatedText,
                      mode: "FULL",
                    })
                  }
                >
                  审阅后替换
                </Button>
              ) : (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => onReplaceText(generatedText)}
                >
                  替换正文
                </Button>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
