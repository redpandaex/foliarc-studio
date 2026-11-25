"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ForeshadowingDirectorSlot } from "@/components/foreshadowing-director-slot";
import { Button } from "@/components/ui/button";
import {
  getRunStreamUrl,
  runsApi,
  type AgentRunItem,
  type ForeshadowingDirective,
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
  sectionId?: string | null;
  chapterNo?: number | null;
  chapterContent: string;
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
  state: StreamConnectionState;
  activeAgent?: string | null;
  completedStages?: string[];
  criticInfo?: CriticInfo | null;
  outlineApproval?: OutlineApprovalState | null;
}

const ACTION_OPTIONS: { value: RunAction; label: string }[] = [
  { value: "CONTINUE", label: "续写正文" },
  { value: "REWRITE", label: "润色扩写" },
  { value: "OUTLINE", label: "生成大纲" },
];

const MODE_OPTIONS: { value: RunMode; label: string }[] = [
  { value: "MULTI_AGENT", label: "协同精写（推荐）" },
  { value: "AGENT", label: "智能单步" },
  { value: "DIRECT", label: "快速直写" },
];

const MODEL_OPTIONS = [
  { value: "writer-main", label: "默认主力模型" },
  { value: "critic-reasoner", label: "深度推理模型" },
  { value: "fast-router", label: "极速响应模型" },
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
  connecting: { label: "正在连接...", dot: "bg-amber-400 animate-pulse" },
  streaming: { label: "正在创作...", dot: "bg-emerald-500 animate-pulse" },
  reconnecting: { label: "网络波动，自动续接中...", dot: "bg-amber-500 animate-pulse" },
  paused: { label: "等待确认", dot: "bg-sky-500" },
  completed: { label: "创作完成", dot: "bg-emerald-500" },
  cancelled: { label: "已取消", dot: "bg-muted-foreground" },
  error: { label: "生成中断", dot: "bg-rose-500" },
};

const AGENT_HINT: Record<string, string> = {
  router: "正在分析本章创作需求...",
  director: "正在结合上一章剧情构思本章大纲...",
  lorekeeper: "正在核对人物设定与前文线索...",
  writer: "正在紧接前文撰写正文...",
  critic: "正在检查剧情连贯性与设定一致性...",
};

function storageKey(bookId: string, chapterId: string | null): string {
  return `foliarc:sse_run:${bookId}:${chapterId ?? "book"}`;
}

function isActiveStreamState(state: StreamConnectionState): boolean {
  return state === "connecting" || state === "streaming" || state === "reconnecting";
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
  onAppendText,
  onReplaceText,
  onRunStateChange,
}: AiCopilotPanelProps) {
  const [mode, setMode] = useState<RunMode>("MULTI_AGENT");
  const [action, setAction] = useState<RunAction>("CONTINUE");
  const [modelAlias, setModelAlias] = useState("writer-main");
  const [requireOutlineApproval, setRequireOutlineApproval] = useState(true);
  const [showAdvanced, setShowAdvanced] = useState(false);

  const [instruction, setInstruction] = useState("");
  const [povCharacter, setPovCharacter] = useState("");
  const [foreshadowingDirectives, setForeshadowingDirectives] = useState<
    ForeshadowingDirective[]
  >([]);
  const [revisedInstruction, setRevisedInstruction] = useState("");

  const [runId, setRunId] = useState<string | null>(null);
  const [lastEventId, setLastEventId] = useState<string | null>(null);
  const [lastSeq, setLastSeq] = useState(0);
  const [connState, setConnState] = useState<StreamConnectionState>("idle");
  const [statusDetail, setStatusDetail] = useState<string | null>(null);

  const [generatedText, setGeneratedText] = useState("");
  const [thoughtText, setThoughtText] = useState("");
  const [showThought, setShowThought] = useState(false);
  const [busyControl, setBusyControl] = useState(false);

  const [activeAgent, setActiveAgent] = useState<string | null>(null);
  const [completedStages, setCompletedStages] = useState<string[]>([]);
  const [criticInfo, setCriticInfo] = useState<CriticInfo | null>(null);
  const [outlineApproval, setOutlineApproval] = useState<OutlineApprovalState | null>(null);
  const [editedOutline, setEditedOutline] = useState("");
  const [approvalFeedback, setApprovalFeedback] = useState("");
  const [diffReviewOpen, setDiffReviewOpen] = useState(false);
  const [acceptedParagraphs, setAcceptedParagraphs] = useState<Record<number, boolean>>({});

  const sseHandleRef = useRef<ResumableSseHandle | null>(null);
  const seenSeqRef = useRef(0);
  const latestSnapshotRef = useRef<PersistedRunSnapshot | null>(null);
  const persistTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const userToggledThoughtRef = useRef(false);
  const onRunStateChangeRef = useRef(onRunStateChange);

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
        seq: number;
        activeAgent?: string | null;
        completedStages?: string[];
        criticInfo?: CriticInfo | null;
        outlineApproval?: OutlineApprovalState | null;
      },
    ) => {
      sseHandleRef.current?.disconnect();
      seenSeqRef.current = seed.seq;

      let accText = seed.text;
      let accThought = seed.thought;
      let accLastEventId = cursor;
      let accSeq = seed.seq;
      let accAgent = seed.activeAgent ?? null;
      let accCompletedStages = [...(seed.completedStages ?? [])];
      let accCritic = seed.criticInfo ?? null;
      let accOutline = seed.outlineApproval ?? null;
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
        state: nextState,
        activeAgent: accAgent,
        completedStages: accCompletedStages,
        criticInfo: accCritic,
        outlineApproval: accOutline,
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
          setLastEventId(id);
          scheduleSnapshotPersist(buildSnap(accState));
        },
        onStateChange(nextState, meta) {
          accState = nextState;
          updateConnState(nextState);
          if (meta?.detail) {
            setStatusDetail(meta.detail);
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
            setLastSeq(envelope.seq);
          }
          if (envelope.agent) {
            const prevIdx = accAgent ? STAGE_ORDER.indexOf(accAgent) : -1;
            const nextIdx = STAGE_ORDER.indexOf(envelope.agent);
            if (prevIdx >= 0 && nextIdx > prevIdx && accAgent) {
              markStageCompleted(accAgent);
            }
            accAgent = envelope.agent;
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
              setActiveAgent(toA);
              if (AGENT_HINT[toA]) {
                setStatusDetail(AGENT_HINT[toA]);
              }
            }
            scheduleSnapshotPersist(buildSnap("streaming"));
          } else if (envelope.type === "thought_delta") {
            const delta = String(envelope.payload.content ?? "");
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
            setStatusDetail(String(envelope.payload.message ?? "生成过程中断，请稍后重试"));
            flushSnapshotNow(buildSnap("error"));
          } else if (envelope.type === "done") {
            accOutline = null;
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
      setLastEventId(null);
      setLastSeq(0);
      setGeneratedText("");
      setThoughtText("");
      setShowThought(false);
      setActiveAgent(null);
      setCompletedStages([]);
      setCriticInfo(null);
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
        setLastEventId(canIncrementalResume ? (sameSaved?.lastEventId ?? null) : null);
        setLastSeq(seedSeq);
        setGeneratedText(seedText);
        setThoughtText(seedThought);
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
        setLastEventId(pausedRun.lastEventId ?? sameSaved?.lastEventId ?? null);
        setLastSeq(Math.max(pausedRun.lastSeq, sameSaved?.lastSeq ?? 0));
        setGeneratedText(nextText);
        setThoughtText(nextThought);
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
          setLastEventId(saved.lastEventId);
          setLastSeq(saved.lastSeq);
          setGeneratedText(saved.text);
          setThoughtText(saved.thought);
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
          setLastEventId(matchedRun.lastEventId ?? saved.lastEventId);
          setLastSeq(Math.max(matchedRun.lastSeq, saved.lastSeq));
          setGeneratedText(finalText);
          setThoughtText(saved.thought);
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
              state: "completed",
              completedStages: doneStages,
              outlineApproval: null,
            });
            return;
          }
          if (matchedRun.status === "FAILED") {
            updateConnState("error");
            setStatusDetail(matchedRun.errorMessage || "生成过程出现异常，请稍后重试");
            flushSnapshotNow({
              ...saved,
              text: finalText,
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
          setLastEventId(saved.lastEventId);
          setLastSeq(saved.lastSeq);
          setGeneratedText(saved.text);
          setThoughtText(saved.thought);
          setShowThought(false);
          setActiveAgent(saved.activeAgent ?? null);
          setCompletedStages(saved.completedStages ?? ["director", "lorekeeper", "writer", "critic"]);
          setCriticInfo(saved.criticInfo ?? null);
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
    setStatusDetail("正在启动创作...");
    setGeneratedText("");
    setThoughtText("");
    setShowThought(false);
    userToggledThoughtRef.current = false;
    setLastEventId(null);
    setLastSeq(0);
    setRevisedInstruction("");
    setCriticInfo(null);
    setOutlineApproval(null);
    setEditedOutline("");
    setApprovalFeedback("");
    setCompletedStages([]);
    setDiffReviewOpen(false);
    setAcceptedParagraphs({});

    const effectiveMode: RunMode =
      action === "OUTLINE" && mode === "MULTI_AGENT" ? "AGENT" : mode;
    const effectiveApproval =
      effectiveMode === "MULTI_AGENT" && action !== "OUTLINE"
        ? requireOutlineApproval
        : false;
    const initialAgent = effectiveMode === "MULTI_AGENT" ? "director" : "writer";
    setActiveAgent(initialAgent);

    try {
      const created = await runsApi.start({
        bookId,
        chapterId: chapterId ?? undefined,
        sectionId: sectionId ?? undefined,
        mode: effectiveMode,
        action,
        modelAlias,
        instruction,
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
        action,
        modelAlias,
        instruction,
        lastEventId: null,
        lastSeq: 0,
        text: "",
        thought: "",
        state: "connecting",
        activeAgent: initialAgent,
        completedStages: [],
        criticInfo: null,
        outlineApproval: null,
      });
      attachStream(created.id, null, {
        mode: effectiveMode,
        action,
        modelAlias,
        instruction,
        text: "",
        thought: "",
        seq: 0,
        activeAgent: initialAgent,
        completedStages: [],
      });
    } catch (err) {
      updateConnState("error");
      setStatusDetail(err instanceof Error ? err.message : "启动创作失败，请稍后重试");
    } finally {
      setBusyControl(false);
    }
  }

  async function handlePause() {
    if (!runId) return;
    setBusyControl(true);
    try {
      await runsApi.pause(runId);
      setStatusDetail("正在保存当前进度并暂停...");
    } catch (err) {
      setStatusDetail(err instanceof Error ? err.message : "暂停失败");
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
      flushSnapshotNow({
        runId,
        mode,
        action,
        modelAlias,
        instruction: nextInstruction,
        lastEventId,
        lastSeq,
        text: generatedText,
        thought: thoughtText,
        state: "connecting",
        activeAgent,
        completedStages,
        criticInfo,
        outlineApproval: null,
      });
      attachStream(runId, lastEventId, {
        mode,
        action,
        modelAlias,
        instruction: nextInstruction,
        text: generatedText,
        thought: thoughtText,
        seq: lastSeq,
        activeAgent,
        completedStages,
        criticInfo,
      });
    } catch (err) {
      updateConnState("error");
      setStatusDetail(err instanceof Error ? err.message : "继续创作失败");
    } finally {
      setBusyControl(false);
    }
  }

  async function handleApproveOutline(approved: boolean) {
    if (!runId) return;
    setBusyControl(true);
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
      setApprovalFeedback("");
      setActiveAgent(nextAgent);
      flushSnapshotNow({
        runId,
        mode,
        action,
        modelAlias,
        instruction,
        lastEventId,
        lastSeq,
        text: generatedText,
        thought: thoughtText,
        state: "connecting",
        activeAgent: nextAgent,
        completedStages,
        criticInfo,
        outlineApproval: null,
      });
      attachStream(runId, lastEventId, {
        mode,
        action,
        modelAlias,
        instruction,
        text: generatedText,
        thought: thoughtText,
        seq: lastSeq,
        activeAgent: nextAgent,
        completedStages,
        criticInfo,
        outlineApproval: null,
      });
    } catch (err) {
      updateConnState("error");
      setStatusDetail(err instanceof Error ? err.message : "提交确认失败");
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
        setStatusDetail("已取消本次创作");
        flushSnapshotNow(null);
      }
    } catch (err) {
      setStatusDetail(err instanceof Error ? err.message : "取消失败");
    } finally {
      setBusyControl(false);
    }
  }

  function handleClear() {
    sseHandleRef.current?.disconnect();
    flushSnapshotNow(null);
    setRunId(null);
    setLastEventId(null);
    setLastSeq(0);
    setGeneratedText("");
    setThoughtText("");
    setShowThought(false);
    userToggledThoughtRef.current = false;
    setActiveAgent(null);
    setCompletedStages([]);
    setCriticInfo(null);
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
  const badge = STATE_BADGE[connState];
  const showProgressStrip =
    mode === "MULTI_AGENT" &&
    action !== "OUTLINE" &&
    (isRunning || connState === "paused" || connState === "completed");

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden text-xs">
      {/* Control Area */}
      <div className="space-y-2.5 border-b border-border p-3">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-1.5">
            <span className={`inline-block h-2 w-2 rounded-full ${badge.dot}`} />
            <span className="font-medium">{badge.label}</span>
          </div>
          {chapterNo != null && chapterNo > 1 && (
            <span className="rounded-full bg-emerald-500/10 px-2 py-0.5 text-[10px] text-emerald-700 dark:text-emerald-400">
              自动衔接第 {chapterNo - 1} 章
            </span>
          )}
        </div>

        {/* Action Pills */}
        <div className="grid grid-cols-3 gap-1">
          {ACTION_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              disabled={isRunning || connState === "paused"}
              onClick={() => setAction(opt.value)}
              className={`rounded-md border px-2 py-1.5 text-[11px] font-medium transition-colors ${
                action === opt.value
                  ? "border-primary bg-primary text-primary-foreground"
                  : "border-border bg-background text-muted-foreground hover:text-foreground"
              } disabled:opacity-50`}
            >
              {opt.label}
            </button>
          ))}
        </div>

        {/* HITL Outline Confirmation Card vs Normal Pause vs Instruction Input */}
        {connState === "paused" && outlineApproval ? (
          <div className="space-y-2 rounded-lg border border-sky-500/50 bg-sky-500/5 p-2.5">
            <div className="font-semibold text-sky-700 dark:text-sky-400">
              确认本章大纲
            </div>
            <p className="text-[11px] text-muted-foreground">
              可直接修改下方剧情大纲，确认后将紧接前文展开正文写作：
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
              placeholder="如不满意，可写下调整方向后点击「重新构思」..."
              className="w-full rounded-md border border-border bg-background px-2 py-1 text-[11px] outline-none"
            />
            <div className="flex gap-1.5">
              <Button
                size="xs"
                className="flex-1"
                disabled={busyControl}
                onClick={() => void handleApproveOutline(true)}
              >
                确认并开始写作
              </Button>
              <Button
                size="xs"
                variant="outline"
                disabled={busyControl}
                onClick={() => void handleApproveOutline(false)}
              >
                重新构思
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
              placeholder="可选：补充或微调接下来的剧情走向..."
              className="w-full resize-none rounded-md border border-sky-500/50 bg-background px-2.5 py-1.5 text-xs outline-none focus:ring-1 focus:ring-sky-500"
            />
            <div className="flex gap-1.5">
              <Button
                size="xs"
                className="flex-1"
                disabled={busyControl}
                onClick={() => void handleResume()}
              >
                继续生成
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
              rows={3}
              value={instruction}
              disabled={isRunning}
              onChange={(e) => setInstruction(e.target.value)}
              placeholder="写下本章想推进的剧情、人物冲突或场景氛围（留空也会自动紧接上一章续写）..."
              className="w-full resize-none rounded-md border border-input bg-background px-2.5 py-2 text-xs leading-relaxed outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
            />

            <ForeshadowingDirectorSlot
              bookId={bookId}
              chapterNo={chapterNo ?? null}
              disabled={isRunning}
              povCharacter={povCharacter}
              onPovCharacterChange={setPovCharacter}
              directives={foreshadowingDirectives}
              onDirectivesChange={setForeshadowingDirectives}
            />

            <div className="flex items-center justify-between gap-2 text-[11px] text-muted-foreground">
              {mode === "MULTI_AGENT" && action !== "OUTLINE" ? (
                <label className="flex cursor-pointer items-center gap-1.5">
                  <input
                    type="checkbox"
                    disabled={isRunning}
                    checked={requireOutlineApproval}
                    onChange={(e) => setRequireOutlineApproval(e.target.checked)}
                  />
                  先确认大纲再动笔
                </label>
              ) : (
                <span />
              )}
              <button
                type="button"
                onClick={() => setShowAdvanced((v) => !v)}
                className="text-[11px] text-muted-foreground hover:text-foreground"
              >
                {showAdvanced ? "收起设置 ▴" : "写作设置 ▾"}
              </button>
            </div>

            {showAdvanced && (
              <div className="grid grid-cols-2 gap-1.5 rounded-md border border-border bg-muted/25 p-2">
                <div className="space-y-1">
                  <div className="text-[10px] text-muted-foreground">创作模式</div>
                  <select
                    value={mode}
                    disabled={isRunning}
                    onChange={(e) => setMode(e.target.value as RunMode)}
                    className="w-full rounded border border-input bg-background px-2 py-1 text-[11px] outline-none disabled:opacity-60"
                  >
                    {MODE_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="space-y-1">
                  <div className="text-[10px] text-muted-foreground">写作模型</div>
                  <select
                    value={modelAlias}
                    disabled={isRunning}
                    onChange={(e) => setModelAlias(e.target.value)}
                    className="w-full rounded border border-input bg-background px-2 py-1 text-[11px] outline-none disabled:opacity-60"
                  >
                    {MODEL_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
            )}

            {isRunning ? (
              <div className="flex gap-1.5">
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
              </div>
            ) : (
              <div className="flex gap-1.5">
                <Button
                  size="xs"
                  className="flex-1"
                  disabled={busyControl}
                  onClick={() => void handleStart()}
                >
                  {action === "OUTLINE"
                    ? "生成本章大纲"
                    : action === "REWRITE"
                      ? "开始润色扩写"
                      : "开始 AI 创作"}
                </Button>
                {(generatedText || thoughtText || connState !== "idle") && (
                  <Button size="xs" variant="outline" onClick={handleClear}>
                    清空
                  </Button>
                )}
              </div>
            )}
          </div>
        )}

        {/* Compact Progress Strip */}
        {showProgressStrip && (
          <div className="grid grid-cols-4 gap-1 text-center text-[10px]">
            {WRITER_STAGES.map((stage) => {
              const isCurrent = activeAgent === stage.id && (isRunning || connState === "paused");
              const isDone =
                connState === "completed" || completedStages.includes(stage.id);
              return (
                <div
                  key={stage.id}
                  className={`rounded py-1 font-medium transition-colors ${
                    isCurrent
                      ? "bg-primary text-primary-foreground"
                      : isDone
                        ? "bg-emerald-500/15 text-emerald-700 dark:text-emerald-400"
                        : "bg-muted/50 text-muted-foreground"
                  }`}
                >
                  {stage.label}
                </div>
              );
            })}
          </div>
        )}

        {statusDetail && (
          <div
            className={`rounded-md px-2.5 py-1.5 text-[11px] leading-relaxed ${
              connState === "error"
                ? "border border-destructive/40 bg-destructive/10 text-destructive"
                : "bg-muted/60 text-muted-foreground"
            }`}
          >
            {statusDetail}
          </div>
        )}
      </div>

      {/* Output Area */}
      <div className="flex min-h-0 flex-1 flex-col space-y-3 overflow-y-auto p-3">
        {criticInfo && (
          <div
            className={`rounded-lg border px-2.5 py-2 text-[11px] ${
              criticInfo.passed
                ? "border-emerald-500/30 bg-emerald-500/5"
                : "border-amber-500/40 bg-amber-500/10"
            }`}
          >
            <div className="font-medium">
              {criticInfo.passed
                ? "剧情连贯性与设定核查通过"
                : "检测到衔接或设定问题，已自动调整重写"}
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

        {thoughtText && (
          <div className="rounded-lg border border-border bg-muted/35 p-2.5">
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
              <div className="mt-1.5 whitespace-pre-wrap text-[11px] leading-relaxed text-muted-foreground">
                {thoughtText}
              </div>
            )}
          </div>
        )}

        {generatedText ? (
          diffReviewOpen && !isRunning ? (
            <div className="space-y-2 rounded-lg border border-emerald-500/40 bg-background p-2.5">
              <div className="flex items-center justify-between">
                <span className="font-semibold text-emerald-700 dark:text-emerald-400">
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
                      className={`rounded border p-2 text-[11px] leading-relaxed ${
                        accepted
                          ? "border-emerald-500/40 bg-emerald-500/10"
                          : "border-border bg-muted/30 opacity-50 line-through"
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
              <Button size="xs" className="w-full" onClick={handleApplySelectedDiff}>
                将已选段落追加到正文
              </Button>
            </div>
          ) : (
            <div className="rounded-lg border border-border bg-background p-3 leading-relaxed">
              <div className="whitespace-pre-wrap text-xs leading-6 text-foreground">
                {generatedText}
                {connState === "streaming" && (
                  <span className="ml-0.5 inline-block h-3.5 w-1.5 animate-pulse bg-primary align-middle" />
                )}
              </div>
            </div>
          )
        ) : (
          !thoughtText &&
          !outlineApproval && (
            <div className="rounded-lg border border-dashed border-border p-5 text-center text-[11px] leading-relaxed text-muted-foreground">
              <p className="font-medium text-foreground">AI 写作助手</p>
              <p className="mt-1">
                输入本章剧情方向即可开始生成。系统会自动承接上一章剧情与「故事设定」中的人物和伏笔，刷新页面也不会中断生成进度。
              </p>
            </div>
          )
        )}
      </div>

      {/* Adopt Output Actions */}
      {generatedText && !isRunning && (
        <div className="border-t border-border bg-card p-3">
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
              onClick={handleOpenDiffReview}
            >
              {diffReviewOpen ? "全文" : "逐段挑选"}
            </Button>
            <Button
              size="xs"
              variant="outline"
              onClick={() => onReplaceText(generatedText)}
            >
              替换正文
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
