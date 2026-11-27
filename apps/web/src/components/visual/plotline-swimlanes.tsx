"use client";

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type PointerEvent as ReactPointerEvent,
} from "react";
import {
  AlertTriangle,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Clock,
  Crosshair,
  Eye,
  EyeOff,
  MapPin,
  Pencil,
  Plus,
  RefreshCw,
  Sparkles,
  Users,
  X,
} from "lucide-react";
import { Alert } from "@/components/ui/alert";
import { Badge, type UiStatus } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { useToast } from "@/components/ui/toast";
import {
  VisualControlRibbon,
  useEscapeKey,
  useVisualProjection,
} from "@/components/visual/visual-shared";
import {
  memoryApi,
  visualApi,
  type BookTreeResponse,
  type ForeshadowingDirective,
  type VisualChapterAxisItem,
  type VisualEntityLane,
  type VisualForeshadowingArc,
  type VisualTimelineLaneEvent,
  type VisualVisibility,
} from "@/lib/api";
import { formatWebError } from "@/lib/error-messages";

const COL_WIDTH = 138;
const LABEL_WIDTH = 208;
const ARC_ROW_HEIGHT = 46;
const CHAPTER_WINDOW_SIZE = 24;

const ALERT_META: Record<
  string,
  { label: string; status: UiStatus; stroke: string }
> = {
  RESOLVED: { label: "已回收", status: "success", stroke: "var(--status-success)" },
  OVERDUE: { label: "逾期未收", status: "danger", stroke: "var(--status-danger)" },
  DUE: { label: "即将到期", status: "warning", stroke: "var(--status-warning)" },
  OPEN: { label: "进行中", status: "info", stroke: "var(--status-info)" },
  PLANNED: { label: "待埋设", status: "neutral", stroke: "var(--muted-foreground)" },
};

export interface PlotlineSwimlanesProps {
  bookId: string;
  tree: BookTreeResponse | null;
  activeChapterId: string | null;
  activeSectionId: string | null;
  visibility: VisualVisibility;
  onVisibilityChange: (next: VisualVisibility) => void;
  asOfChapterId: string | null;
  onAsOfChapterIdChange: (chapterId: string | null) => void;
  refreshSignal?: number;
  onDataMutated?: () => void;
  onJumpToChapter: (chapterId: string, sectionId?: string | null) => void;
  onSendToDirector: (directive: ForeshadowingDirective, label: string) => void;
  onSendPromptToAi: (promptText: string, targetChapterId?: string | null) => void;
}

export function PlotlineSwimlanes({
  bookId,
  tree,
  activeChapterId,
  activeSectionId,
  visibility,
  onVisibilityChange,
  asOfChapterId,
  onAsOfChapterIdChange,
  refreshSignal = 0,
  onDataMutated,
  onJumpToChapter,
  onSendToDirector,
  onSendPromptToAi,
}: PlotlineSwimlanesProps) {
  const toast = useToast();
  const confirm = useConfirm();

  const volumes = useMemo(() => tree?.volumes ?? [], [tree]);
  const allTreeChapters = useMemo(
    () => (tree ? tree.volumes.flatMap((v) => v.chapters) : []),
    [tree],
  );

  const [selectedVolumeId, setSelectedVolumeId] = useState<string>("ALL");
  const [windowOffset, setWindowOffset] = useState<number | null>(null);
  const [showAllWindow, setShowAllWindow] = useState(false);

  const activeTreeChapterNo = useMemo(() => {
    const match = allTreeChapters.find((c) => c.id === activeChapterId);
    return match?.chapterNo ?? null;
  }, [allTreeChapters, activeChapterId]);

  // Sliding chapter window when book grows beyond CHAPTER_WINDOW_SIZE chapters
  const windowRange = useMemo(() => {
    if (
      showAllWindow ||
      selectedVolumeId !== "ALL" ||
      allTreeChapters.length <= CHAPTER_WINDOW_SIZE
    ) {
      return { active: false, fromChapter: null as number | null, toChapter: null as number | null, startIdx: 0 };
    }
    const maxStart = Math.max(0, allTreeChapters.length - CHAPTER_WINDOW_SIZE);
    const defaultStart = (() => {
      const idx = allTreeChapters.findIndex((c) => c.id === (asOfChapterId ?? activeChapterId));
      if (idx < 0) return maxStart;
      return Math.max(0, Math.min(maxStart, idx - Math.floor(CHAPTER_WINDOW_SIZE / 2)));
    })();
    const startIdx = Math.max(0, Math.min(maxStart, windowOffset ?? defaultStart));
    const slice = allTreeChapters.slice(startIdx, startIdx + CHAPTER_WINDOW_SIZE);
    return {
      active: true,
      fromChapter: slice[0]?.chapterNo ?? null,
      toChapter: slice[slice.length - 1]?.chapterNo ?? null,
      startIdx,
    };
  }, [showAllWindow, selectedVolumeId, allTreeChapters, asOfChapterId, activeChapterId, windowOffset]);

  const {
    allChapters,
    scrubIdx,
    setScrubIdx,
    displayedIdx,
    displayedChapter,
    projection,
    loading,
    refreshing,
    error,
    refetch,
  } = useVisualProjection({
    bookId,
    view: "lanes",
    tree,
    activeChapterId,
    activeSectionId,
    visibility,
    asOfChapterId,
    volumeId: selectedVolumeId === "ALL" ? null : selectedVolumeId,
    fromChapter: windowRange.fromChapter,
    toChapter: windowRange.toChapter,
    refreshSignal,
    errorFallback: "加载剧情与伏笔泳道失败",
  });

  const [showEvents, setShowEvents] = useState(true);
  const [showArcs, setShowArcs] = useState(true);
  const [showCharacterLanes, setShowCharacterLanes] = useState(true);
  const [showLocationLanes, setShowLocationLanes] = useState(true);

  const [selectedArcId, setSelectedArcId] = useState<string | null>(null);
  const [selectedEventId, setSelectedEventId] = useState<string | null>(null);
  const [revealTruth, setRevealTruth] = useState(false);

  // Add / Edit timeline event modals
  const [eventDraft, setEventDraft] = useState<{
    chapterNo: string;
    eventTitle: string;
    summary: string;
    involvedCharacters: string;
    location: string;
  } | null>(null);
  const [editingEvent, setEditingEvent] = useState<{
    id: string;
    chapterNo: string;
    eventTitle: string;
    summary: string;
    involvedCharacters: string;
    location: string;
  } | null>(null);
  const [savingEvent, setSavingEvent] = useState(false);

  // Horizontal scroll + drag-to-pan refs
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const autoScrolledRef = useRef(false);
  const dragPanRef = useRef<{ active: boolean; startX: number; scrollLeft: number } | null>(null);
  const [isPanning, setIsPanning] = useState(false);

  const hasAnyOverlay = Boolean(selectedArcId || selectedEventId || eventDraft);
  useEscapeKey(hasAnyOverlay, () => {
    if (eventDraft) {
      setEventDraft(null);
      return;
    }
    setSelectedArcId(null);
    setSelectedEventId(null);
    setEditingEvent(null);
  });

  const chapters: VisualChapterAxisItem[] = useMemo(
    () => projection?.chapters ?? [],
    [projection],
  );

  const chapterIndexByNo = useMemo(() => {
    const map = new Map<number, number>();
    chapters.forEach((c, idx) => map.set(c.chapterNo, idx));
    return map;
  }, [chapters]);

  const maxWordCount = useMemo(
    () => Math.max(1000, ...chapters.map((c) => c.wordCount || 0)),
    [chapters],
  );

  const eventsByChapter = useMemo(() => {
    const map = new Map<number, VisualTimelineLaneEvent[]>();
    for (const ev of projection?.events ?? []) {
      const list = map.get(ev.chapterNo) ?? [];
      list.push(ev);
      map.set(ev.chapterNo, list);
    }
    return map;
  }, [projection]);

  const characterLanes = useMemo(
    () => (projection?.entityLanes ?? []).filter((l) => l.kind === "CHARACTER"),
    [projection],
  );
  const locationLanes = useMemo(
    () => (projection?.entityLanes ?? []).filter((l) => l.kind === "LOCATION"),
    [projection],
  );

  const selectedArc: VisualForeshadowingArc | null = useMemo(
    () => projection?.arcs.find((a) => a.id === selectedArcId) ?? null,
    [projection, selectedArcId],
  );
  const selectedEvent: VisualTimelineLaneEvent | null = useMemo(
    () => projection?.events.find((e) => e.id === selectedEventId) ?? null,
    [projection, selectedEventId],
  );

  const trackWidth = Math.max(1, chapters.length) * COL_WIDTH;

  function xForChapter(chapterNo: number): number | null {
    const idx = chapterIndexByNo.get(chapterNo);
    if (idx === undefined) return null;
    return idx * COL_WIDTH + COL_WIDTH / 2;
  }

  const scrollToActiveChapter = useCallback(
    (smooth = true) => {
      const container = scrollRef.current;
      if (!container || chapters.length === 0) return;
      const targetIdx = chapters.findIndex((c) => c.id === activeChapterId);
      if (targetIdx < 0) return;
      const colCenter = LABEL_WIDTH + targetIdx * COL_WIDTH + COL_WIDTH / 2;
      const desiredLeft = Math.max(0, colCenter - container.clientWidth / 2);
      container.scrollTo({ left: desiredLeft, behavior: smooth ? "smooth" : "auto" });
    },
    [chapters, activeChapterId],
  );

  // Auto-scroll to current chapter once on initial load
  useEffect(() => {
    if (autoScrolledRef.current || chapters.length === 0) return;
    autoScrolledRef.current = true;
    const raf = window.requestAnimationFrame(() => scrollToActiveChapter(false));
    return () => window.cancelAnimationFrame(raf);
  }, [chapters.length, scrollToActiveChapter]);

  function handleCanvasPointerDown(e: ReactPointerEvent<HTMLDivElement>) {
    if (e.button !== 0) return;
    const target = e.target as HTMLElement | null;
    if (target?.closest("button, input, select, textarea, a")) return;
    const el = scrollRef.current;
    if (!el) return;
    dragPanRef.current = { active: true, startX: e.clientX, scrollLeft: el.scrollLeft };
    setIsPanning(true);
  }

  function handleCanvasPointerMove(e: ReactPointerEvent<HTMLDivElement>) {
    const pan = dragPanRef.current;
    const el = scrollRef.current;
    if (!pan?.active || !el) return;
    const dx = e.clientX - pan.startX;
    el.scrollLeft = pan.scrollLeft - dx;
  }

  function handleCanvasPointerUp() {
    if (!dragPanRef.current) return;
    dragPanRef.current = null;
    setIsPanning(false);
  }

  async function handleSaveNewEvent(e: FormEvent) {
    e.preventDefault();
    if (!eventDraft || !eventDraft.eventTitle.trim()) return;
    setSavingEvent(true);
    try {
      const chNo = Number.parseInt(eventDraft.chapterNo, 10);
      await memoryApi.upsertTimeline(bookId, {
        chapterNo: Number.isNaN(chNo) ? 1 : Math.max(1, chNo),
        eventTitle: eventDraft.eventTitle.trim(),
        summary: eventDraft.summary.trim(),
        involvedCharacters: eventDraft.involvedCharacters.trim(),
        location: eventDraft.location.trim(),
      });
      setEventDraft(null);
      toast.success("已记录剧情时间线事件");
      onDataMutated?.();
      await refetch();
    } catch (err) {
      toast.error(formatWebError(err, "保存事件失败，请稍后重试"));
    } finally {
      setSavingEvent(false);
    }
  }

  async function handleSaveEventEdit(e: FormEvent) {
    e.preventDefault();
    if (!editingEvent || !editingEvent.eventTitle.trim()) return;
    setSavingEvent(true);
    try {
      const chNo = Number.parseInt(editingEvent.chapterNo, 10);
      await visualApi.updateEvent(bookId, editingEvent.id, {
        chapterNo: Number.isNaN(chNo) ? undefined : Math.max(1, chNo),
        eventTitle: editingEvent.eventTitle.trim(),
        summary: editingEvent.summary.trim(),
        involvedCharacters: editingEvent.involvedCharacters.trim(),
        location: editingEvent.location.trim(),
      });
      setEditingEvent(null);
      toast.success("剧情事件已更新");
      onDataMutated?.();
      await refetch();
    } catch (err) {
      toast.error(formatWebError(err, "更新事件失败，请稍后重试"));
    } finally {
      setSavingEvent(false);
    }
  }

  async function handleDeleteEvent(ev: VisualTimelineLaneEvent) {
    await confirm({
      title: `删除时间线事件「${ev.eventTitle}」？`,
      description: "删除后将从主线事件轨与结构化记忆中移除。",
      confirmLabel: "确认删除",
      variant: "destructive",
      onConfirm: async () => {
        await memoryApi.deleteTimeline(bookId, ev.id);
        setSelectedEventId(null);
        setEditingEvent(null);
        toast.success("已删除事件");
        onDataMutated?.();
        await refetch();
      },
    });
  }

  function renderEntityLaneGroup(title: string, lanes: VisualEntityLane[], isLocation: boolean) {
    if (lanes.length === 0) return null;
    return (
      <div className="border-b border-border">
        <div className="flex items-center border-b border-border/60 bg-surface-subtle/80">
          <div
            style={{ width: LABEL_WIDTH }}
            className="sticky left-0 z-10 flex shrink-0 items-center gap-1.5 border-r border-border bg-surface-subtle px-3 py-1.5 text-[11px] font-semibold text-muted-foreground"
          >
            {isLocation ? <MapPin className="size-3.5 text-primary" /> : <Users className="size-3.5 text-primary" />}
            <span>
              {title} ({lanes.length})
            </span>
          </div>
          <div style={{ width: trackWidth }} className="shrink-0" />
        </div>

        {lanes.map((lane) => {
          const activePoints = lane.activeChapters
            .map((ch) => ({ ch, x: xForChapter(ch) }))
            .filter((p): p is { ch: number; x: number } => p.x !== null);

          return (
            <div key={lane.id} className="group flex items-stretch border-b border-border/45 last:border-b-0">
              <div
                style={{ width: LABEL_WIDTH }}
                className="sticky left-0 z-10 flex shrink-0 items-center justify-between gap-1.5 border-r border-border bg-card px-3 py-2"
              >
                <div className="min-w-0">
                  <div className="truncate text-xs font-medium text-foreground">{lane.label}</div>
                  <div className="mt-0.5 flex items-center gap-1.5 text-[10px] text-muted-foreground">
                    <span>{lane.activeChapters.length} 章活跃</span>
                    {lane.gapFromCutoff >= 3 && (
                      <span className="rounded bg-status-warning-bg px-1 py-0.2 text-[9.5px] font-medium text-status-warning">
                        离线 {lane.gapFromCutoff} 章
                      </span>
                    )}
                  </div>
                </div>
                <Button
                  size="xs"
                  variant="ghost"
                  title="填入 AI 指令草稿"
                  onClick={() =>
                    onSendPromptToAi(
                      isLocation
                        ? `请在本章推进「${lane.label}」地点的剧情线，呼应此前该地发生的事件。`
                        : `请在本章安排「${lane.label}」出场并推进其人物支线（已间隔 ${lane.gapFromCutoff} 章未登场）。`,
                      activeChapterId,
                    )
                  }
                  className="h-6 shrink-0 px-1.5 text-[10px] text-muted-foreground hover:text-foreground"
                >
                  <Sparkles className="size-3" />
                  带入 AI
                </Button>
              </div>

              <div style={{ width: trackWidth, height: 42 }} className="relative shrink-0 bg-background">
                <div className="pointer-events-none absolute inset-0 flex">
                  {chapters.map((ch) => (
                    <div
                      key={ch.id}
                      style={{ width: COL_WIDTH }}
                      className={`h-full shrink-0 border-r border-border/35 ${
                        ch.id === activeChapterId ? "bg-primary-soft/20" : ""
                      }`}
                    />
                  ))}
                </div>

                {activePoints.length >= 2 && (
                  <svg
                    width={trackWidth}
                    height={42}
                    className="pointer-events-none absolute inset-0"
                  >
                    <line
                      x1={activePoints[0].x}
                      y1={21}
                      x2={activePoints[activePoints.length - 1].x}
                      y2={21}
                      stroke={isLocation ? "var(--status-info)" : "var(--primary)"}
                      strokeWidth={1.6}
                      strokeOpacity={0.35}
                      strokeDasharray="3 3"
                    />
                  </svg>
                )}

                {activePoints.map((pt) => {
                  const targetCh = chapters.find((c) => c.chapterNo === pt.ch);
                  const evCount = lane.eventIdsByChapter[String(pt.ch)]?.length ?? 1;
                  return (
                    <button
                      key={pt.ch}
                      type="button"
                      style={{ left: pt.x }}
                      title={`${lane.label} · 第 ${pt.ch} 章${targetCh ? ` ${targetCh.title}` : ""}（点击跳写）`}
                      onClick={() => {
                        if (targetCh) onJumpToChapter(targetCh.id, targetCh.firstSectionId);
                      }}
                      className="absolute top-1/2 -translate-x-1/2 -translate-y-1/2 flex h-5 min-w-5 items-center justify-center rounded-full border border-primary/50 bg-primary-soft px-1.5 font-mono text-[9.5px] font-semibold text-primary-soft-foreground transition-transform hover:scale-110 hover:bg-primary hover:text-primary-foreground"
                    >
                      {evCount > 1 ? evCount : "●"}
                    </button>
                  );
                })}
              </div>
            </div>
          );
        })}
      </div>
    );
  }

  const canPagePrev = windowRange.active && windowRange.startIdx > 0;
  const canPageNext =
    windowRange.active && windowRange.startIdx + CHAPTER_WINDOW_SIZE < allTreeChapters.length;

  return (
    <div className="relative flex min-h-0 flex-1 flex-col overflow-hidden bg-background">
      {/* Shared Control Ribbon */}
      <VisualControlRibbon
        visibility={visibility}
        onVisibilityChange={(next) => {
          onVisibilityChange(next);
          if (next === "AS_OF") setRevealTruth(false);
        }}
        allChapters={allChapters}
        displayedIdx={displayedIdx}
        displayedChapter={displayedChapter}
        scrubIdx={scrubIdx}
        onScrubChange={setScrubIdx}
        onScrubCommit={(chapterId) => {
          onAsOfChapterIdChange(chapterId);
          setScrubIdx(null);
        }}
        sliderAriaLabel="剧情泳道章节时间轴滑块"
        refreshing={refreshing}
        leftExtra={
          <>
            {volumes.length > 1 && (
              <select
                value={selectedVolumeId}
                onChange={(e) => {
                  setSelectedVolumeId(e.target.value);
                  setWindowOffset(null);
                }}
                aria-label="按分卷筛选"
                className="h-7 rounded-lg border border-border bg-background px-2 text-[11px] text-foreground outline-none focus:border-ring"
              >
                <option value="ALL">全部卷 ({volumes.length})</option>
                {volumes
                  .filter((v) => v.id)
                  .map((v) => (
                    <option key={v.id!} value={v.id!}>
                      {v.title || `第 ${v.volumeNo} 卷`}
                    </option>
                  ))}
              </select>
            )}

            {allTreeChapters.length > CHAPTER_WINDOW_SIZE && selectedVolumeId === "ALL" && (
              <div className="inline-flex items-center gap-0.5 rounded-lg border border-border bg-surface-subtle px-1 py-0.5 text-[10.5px]">
                {windowRange.active ? (
                  <>
                    <button
                      type="button"
                      disabled={!canPagePrev}
                      onClick={() =>
                        setWindowOffset(Math.max(0, windowRange.startIdx - CHAPTER_WINDOW_SIZE))
                      }
                      className="rounded p-0.5 text-muted-foreground hover:bg-card hover:text-foreground disabled:opacity-35"
                      title={`前 ${CHAPTER_WINDOW_SIZE} 章`}
                    >
                      <ChevronLeft className="size-3.5" />
                    </button>
                    <span className="px-1 font-mono text-foreground">
                      Ch.{windowRange.fromChapter}–{windowRange.toChapter}
                    </span>
                    <button
                      type="button"
                      disabled={!canPageNext}
                      onClick={() =>
                        setWindowOffset(
                          Math.min(
                            allTreeChapters.length - CHAPTER_WINDOW_SIZE,
                            windowRange.startIdx + CHAPTER_WINDOW_SIZE,
                          ),
                        )
                      }
                      className="rounded p-0.5 text-muted-foreground hover:bg-card hover:text-foreground disabled:opacity-35"
                      title={`后 ${CHAPTER_WINDOW_SIZE} 章`}
                    >
                      <ChevronRight className="size-3.5" />
                    </button>
                    <button
                      type="button"
                      onClick={() => setShowAllWindow(true)}
                      className="ml-0.5 rounded px-1.5 py-0.5 text-muted-foreground hover:bg-card hover:text-foreground"
                    >
                      全展
                    </button>
                  </>
                ) : (
                  <button
                    type="button"
                    onClick={() => {
                      setShowAllWindow(false);
                      setWindowOffset(null);
                    }}
                    className="rounded px-1.5 py-0.5 text-primary hover:bg-card"
                  >
                    分页窗口 ({CHAPTER_WINDOW_SIZE}章)
                  </button>
                )}
              </div>
            )}
          </>
        }
        rightActions={
          <>
            {(
              [
                ["事件", showEvents, () => setShowEvents((v) => !v)],
                ["伏笔弧线", showArcs, () => setShowArcs((v) => !v)],
                ["角色轨", showCharacterLanes, () => setShowCharacterLanes((v) => !v)],
                ["地点轨", showLocationLanes, () => setShowLocationLanes((v) => !v)],
              ] as const
            ).map(([label, enabled, toggle]) => (
              <button
                key={label}
                type="button"
                onClick={toggle}
                className={`rounded-full px-2.5 py-0.5 text-[11px] font-medium transition-colors ${
                  enabled
                    ? "bg-primary-soft text-primary-soft-foreground"
                    : "bg-muted text-muted-foreground hover:text-foreground"
                }`}
              >
                {label}
              </button>
            ))}
            {activeChapterId && (
              <Button
                size="xs"
                variant="ghost"
                title={
                  activeTreeChapterNo
                    ? `滚动定位到当前章节（第 ${activeTreeChapterNo} 章）`
                    : "定位当前章节"
                }
                onClick={() => {
                  if (windowRange.active && !chapters.some((c) => c.id === activeChapterId)) {
                    setWindowOffset(null);
                    window.requestAnimationFrame(() => scrollToActiveChapter(true));
                  } else {
                    scrollToActiveChapter(true);
                  }
                }}
              >
                <Crosshair className="size-3.5 text-primary" />
                <span className="hidden lg:inline">定位当前章</span>
              </Button>
            )}
            <Button size="xs" variant="ghost" onClick={() => void refetch()} title="刷新泳道">
              <RefreshCw className={`size-3.5 ${loading || refreshing ? "animate-spin" : ""}`} />
            </Button>
            <Button
              size="xs"
              onClick={() =>
                setEventDraft({
                  chapterNo: String(displayedChapter?.chapterNo ?? 1),
                  eventTitle: "",
                  summary: "",
                  involvedCharacters: "",
                  location: "",
                })
              }
            >
              <Plus className="size-3.5" />
              记录事件
            </Button>
          </>
        }
      />

      {error && (
        <div className="p-3">
          <Alert status="danger" announce="assertive">
            {error}
          </Alert>
        </div>
      )}

      {/* Main Scrollable Swimlane Matrix + Floating Detail Drawer */}
      <div className="relative flex min-h-0 flex-1 overflow-hidden">
        {!loading && chapters.length === 0 ? (
          <div className="flex flex-1 items-center justify-center p-8">
            <EmptyState
              title="暂无可展示的章节时间轴"
              description="先在左侧目录创建第一章，或向右移动防剧透章节滑块。"
            />
          </div>
        ) : (
          <div
            ref={scrollRef}
            onPointerDown={handleCanvasPointerDown}
            onPointerMove={handleCanvasPointerMove}
            onPointerUp={handleCanvasPointerUp}
            onPointerLeave={handleCanvasPointerUp}
            className={`min-h-0 flex-1 overflow-auto ${
              isPanning ? "cursor-grabbing select-none" : "cursor-grab"
            }`}
          >
            <div style={{ minWidth: LABEL_WIDTH + trackWidth }}>
              {/* Sticky Chapter Axis Header */}
              <div className="sticky top-0 z-20 flex border-b border-border bg-card shadow-2xs">
                <div
                  style={{ width: LABEL_WIDTH }}
                  className="sticky left-0 z-30 flex shrink-0 flex-col justify-center border-r border-border bg-card px-3 py-2"
                >
                  <span className="text-xs font-semibold text-foreground">剧情与伏笔矩阵</span>
                  <span className="mt-0.5 font-mono text-[10px] text-muted-foreground">
                    {chapters.length} 章 · {projection?.arcs.length ?? 0} 伏笔 ·{" "}
                    {projection?.events.length ?? 0} 事件
                  </span>
                </div>

                <div className="flex">
                  {chapters.map((ch) => {
                    const isActive = ch.id === activeChapterId;
                    const barPct = Math.min(100, Math.round((ch.wordCount / maxWordCount) * 100));
                    return (
                      <button
                        key={ch.id}
                        type="button"
                        style={{ width: COL_WIDTH }}
                        onClick={() => onJumpToChapter(ch.id, ch.firstSectionId)}
                        title={`点击跳转到 第 ${ch.chapterNo} 章 ${ch.title}`}
                        className={`group flex shrink-0 flex-col justify-between border-r border-border/60 px-2.5 py-2 text-left transition-colors ${
                          isActive
                            ? "bg-primary-soft/60"
                            : "hover:bg-surface-subtle"
                        }`}
                      >
                        <div className="flex items-center justify-between gap-1">
                          <span
                            className={`font-mono text-[11px] font-semibold ${
                              isActive ? "text-primary-soft-foreground" : "text-primary"
                            }`}
                          >
                            Ch.{ch.chapterNo}
                          </span>
                          <span className="font-mono text-[9.5px] text-muted-foreground">
                            {ch.wordCount.toLocaleString()}字
                          </span>
                        </div>
                        <div className="mt-0.5 truncate text-[11px] font-medium text-foreground">
                          {ch.title || `第 ${ch.chapterNo} 章`}
                        </div>
                        <div className="mt-1.5 h-1 w-full overflow-hidden rounded-full bg-muted">
                          <div
                            className="h-full rounded-full bg-primary/70 transition-all"
                            style={{ width: `${Math.max(4, barPct)}%` }}
                          />
                        </div>
                      </button>
                    );
                  })}
                </div>
              </div>

              {/* Track 1: Main Timeline Events */}
              {showEvents && (
                <div className="flex items-stretch border-b border-border">
                  <div
                    style={{ width: LABEL_WIDTH }}
                    className="sticky left-0 z-10 flex shrink-0 flex-col justify-center border-r border-border bg-surface-subtle px-3 py-3"
                  >
                    <div className="text-xs font-semibold text-foreground">主线事件轨</div>
                    <div className="mt-0.5 text-[10.5px] text-muted-foreground">
                      共 {projection?.events.length ?? 0} 个节点
                    </div>
                  </div>
                  <div className="flex bg-background">
                    {chapters.map((ch) => {
                      const chEvents = eventsByChapter.get(ch.chapterNo) ?? [];
                      return (
                        <div
                          key={ch.id}
                          style={{ width: COL_WIDTH }}
                          className={`min-h-[84px] shrink-0 space-y-1.5 border-r border-border/45 p-2 ${
                            ch.id === activeChapterId ? "bg-primary-soft/20" : ""
                          }`}
                        >
                          {chEvents.map((ev) => {
                            const selected = selectedEventId === ev.id;
                            return (
                              <button
                                key={ev.id}
                                type="button"
                                onClick={() => {
                                  setSelectedEventId(ev.id);
                                  setSelectedArcId(null);
                                  setEditingEvent(null);
                                }}
                                className={`block w-full rounded-lg border p-2 text-left transition-all ${
                                  selected
                                    ? "border-primary bg-primary-soft/60 shadow-xs"
                                    : "border-border bg-card hover:border-primary/50"
                                }`}
                              >
                                <div className="line-clamp-2 text-[11px] font-medium leading-snug text-foreground">
                                  {ev.eventTitle}
                                </div>
                                {ev.location && (
                                  <div className="mt-1 truncate text-[9.5px] text-muted-foreground">
                                    📍 {ev.location}
                                  </div>
                                )}
                              </button>
                            );
                          })}
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}

              {/* Track 2: Foreshadowing Metro-Arcs (SVG) */}
              {showArcs && (
                <div className="border-b border-border">
                  <div className="flex items-center border-b border-border/60 bg-surface-subtle/80">
                    <div
                      style={{ width: LABEL_WIDTH }}
                      className="sticky left-0 z-10 flex shrink-0 items-center justify-between border-r border-border bg-surface-subtle px-3 py-1.5 text-[11px] font-semibold text-muted-foreground"
                    >
                      <span>伏笔跨章弧线 ({projection?.arcs.length ?? 0})</span>
                    </div>
                    <div
                      style={{ width: trackWidth }}
                      className="flex shrink-0 items-center gap-3 px-3 py-1 text-[10px] text-muted-foreground"
                    >
                      <span>● 埋设</span>
                      <span>◆ 照应</span>
                      <span>✓ 回收</span>
                      {visibility === "PLAN" && <span>┄ 计划回收窗口</span>}
                    </div>
                  </div>

                  {(projection?.arcs.length ?? 0) === 0 ? (
                    <div className="flex">
                      <div
                        style={{ width: LABEL_WIDTH }}
                        className="sticky left-0 z-10 shrink-0 border-r border-border bg-card px-3 py-4 text-[11px] text-muted-foreground"
                      >
                        暂无伏笔
                      </div>
                      <div className="px-4 py-4 text-[11px] text-muted-foreground">
                        {visibility === "AS_OF" ? "截至当前章暂无已埋伏笔" : "暂无伏笔记录"}
                      </div>
                    </div>
                  ) : (
                    projection!.arcs.map((arc) => {
                      const meta = ALERT_META[arc.alertState] ?? ALERT_META.OPEN;
                      const isSelected = selectedArcId === arc.id;
                      const startX =
                        arc.plantedChapterNo != null ? xForChapter(arc.plantedChapterNo) : null;
                      const endX =
                        arc.resolvedChapterNo != null
                          ? xForChapter(arc.resolvedChapterNo)
                          : arc.openEnded
                            ? trackWidth - 18
                            : startX;
                      const lineStartX = startX ?? (arc.continuesLeft ? 10 : null);
                      const lineEndX =
                        endX ??
                        (arc.continuesRight ? trackWidth - 10 : lineStartX);

                      const winStartX =
                        visibility === "PLAN" && arc.targetChapterMin != null
                          ? xForChapter(arc.targetChapterMin)
                          : null;
                      const winEndX =
                        visibility === "PLAN" && arc.targetChapterMax != null
                          ? xForChapter(arc.targetChapterMax)
                          : winStartX;
                      const winRectX =
                        winStartX != null && winEndX != null
                          ? Math.min(winStartX, winEndX) - 22
                          : null;
                      const winRectW =
                        winStartX != null && winEndX != null
                          ? Math.abs(winEndX - winStartX) + 44
                          : null;

                      return (
                        <div
                          key={arc.id}
                          onClick={() => {
                            setSelectedArcId(arc.id);
                            setSelectedEventId(null);
                            setEditingEvent(null);
                            setRevealTruth(false);
                          }}
                          className={`group flex cursor-pointer items-stretch border-b border-border/45 last:border-b-0 transition-colors ${
                            isSelected ? "bg-primary-soft/25" : "hover:bg-surface-subtle/40"
                          }`}
                        >
                          <div
                            style={{ width: LABEL_WIDTH, height: ARC_ROW_HEIGHT }}
                            className={`sticky left-0 z-10 flex shrink-0 items-center justify-between gap-1.5 border-r border-border px-3 ${
                              isSelected ? "bg-primary-soft/45" : "bg-card"
                            }`}
                          >
                            <div className="min-w-0">
                              <div className="truncate text-[11.5px] font-medium text-foreground">
                                {arc.code ? (
                                  <span className="mr-1 font-mono text-[10px] text-primary">
                                    {arc.code}
                                  </span>
                                ) : null}
                                {arc.title}
                              </div>
                              <div className="mt-0.5 flex items-center gap-1 text-[9.5px] text-muted-foreground">
                                {arc.alertState === "RESOLVED" && (
                                  <CheckCircle2 className="size-3 text-status-success" />
                                )}
                                {arc.alertState === "OVERDUE" && (
                                  <AlertTriangle className="size-3 text-status-danger" />
                                )}
                                {arc.alertState === "DUE" && (
                                  <Clock className="size-3 text-status-warning" />
                                )}
                                <span>{meta.label}</span>
                              </div>
                            </div>
                          </div>

                          <div
                            style={{ width: trackWidth, height: ARC_ROW_HEIGHT }}
                            className="relative shrink-0"
                          >
                            <div className="pointer-events-none absolute inset-0 flex">
                              {chapters.map((ch) => (
                                <div
                                  key={ch.id}
                                  style={{ width: COL_WIDTH }}
                                  className={`h-full shrink-0 border-r border-border/35 ${
                                    ch.id === activeChapterId ? "bg-primary-soft/20" : ""
                                  }`}
                                />
                              ))}
                            </div>

                            <svg
                              width={trackWidth}
                              height={ARC_ROW_HEIGHT}
                              className="relative z-10 block"
                            >
                              {winRectX != null && winRectW != null && (
                                <>
                                  <rect
                                    x={winRectX}
                                    y={12}
                                    width={winRectW}
                                    height={22}
                                    rx={11}
                                    fill="var(--status-warning-bg)"
                                    stroke="var(--status-warning-border)"
                                    strokeDasharray="4 3"
                                    strokeWidth={1.2}
                                  />
                                  {startX == null && (
                                    <text
                                      x={winRectX + winRectW / 2}
                                      y={26.5}
                                      textAnchor="middle"
                                      fill="var(--status-warning)"
                                      fontSize={9.5}
                                      fontWeight={600}
                                    >
                                      {arc.targetChapterMax && arc.targetChapterMax !== arc.targetChapterMin
                                        ? `计划 Ch.${arc.targetChapterMin}–${arc.targetChapterMax}`
                                        : `计划 Ch.${arc.targetChapterMin}`}
                                    </text>
                                  )}
                                </>
                              )}

                              {lineStartX != null && lineEndX != null && lineEndX >= lineStartX && (
                                <line
                                  x1={lineStartX}
                                  y1={ARC_ROW_HEIGHT / 2}
                                  x2={lineEndX}
                                  y2={ARC_ROW_HEIGHT / 2}
                                  stroke={meta.stroke}
                                  strokeWidth={isSelected ? 3 : 2.2}
                                  strokeDasharray={arc.openEnded ? "6 4" : undefined}
                                  strokeLinecap="round"
                                />
                              )}

                              {arc.openEnded && lineEndX != null && (
                                <polygon
                                  points={`${lineEndX},${ARC_ROW_HEIGHT / 2} ${lineEndX - 7},${ARC_ROW_HEIGHT / 2 - 4} ${lineEndX - 7},${ARC_ROW_HEIGHT / 2 + 4}`}
                                  fill={meta.stroke}
                                />
                              )}

                              {startX != null && (
                                <circle
                                  cx={startX}
                                  cy={ARC_ROW_HEIGHT / 2}
                                  r={5.5}
                                  fill="var(--card)"
                                  stroke={meta.stroke}
                                  strokeWidth={2.4}
                                />
                              )}

                              {arc.hintChapters.map((hCh) => {
                                const hx = xForChapter(hCh);
                                if (hx == null) return null;
                                const cy = ARC_ROW_HEIGHT / 2;
                                return (
                                  <polygon
                                    key={hCh}
                                    points={`${hx},${cy - 5} ${hx + 5},${cy} ${hx},${cy + 5} ${hx - 5},${cy}`}
                                    fill={meta.stroke}
                                  />
                                );
                              })}

                              {arc.resolvedChapterNo != null && endX != null && (
                                <circle
                                  cx={endX}
                                  cy={ARC_ROW_HEIGHT / 2}
                                  r={6}
                                  fill={meta.stroke}
                                />
                              )}
                            </svg>
                          </div>
                        </div>
                      );
                    })
                  )}
                </div>
              )}

              {/* Track 3: Character Subplot Lanes */}
              {showCharacterLanes && renderEntityLaneGroup("角色支线轨", characterLanes, false)}

              {/* Track 4: Location Lanes */}
              {showLocationLanes && renderEntityLaneGroup("场景 / 地点轨", locationLanes, true)}
            </div>
          </div>
        )}

        {/* Floating Overlay Inspector for Selected Arc or Event */}
        {(selectedArc || selectedEvent) && (
          <div className="absolute top-3 right-3 bottom-3 z-30 flex w-80 max-w-[calc(100%-1.5rem)] flex-col overflow-hidden rounded-2xl border border-border bg-card/95 shadow-xl backdrop-blur-md">
            <div className="flex items-center justify-between border-b border-border px-3.5 py-2.5">
              <div className="min-w-0">
                <div className="truncate text-xs font-semibold text-foreground">
                  {selectedArc ? selectedArc.title : selectedEvent?.eventTitle}
                </div>
                <div className="text-[10.5px] text-muted-foreground">
                  {selectedArc
                    ? "伏笔详情"
                    : `第 ${selectedEvent?.chapterNo ?? "?"} 章事件`}
                </div>
              </div>
              <Button
                size="icon-xs"
                variant="ghost"
                title="关闭详情 (Esc)"
                onClick={() => {
                  setSelectedArcId(null);
                  setSelectedEventId(null);
                  setEditingEvent(null);
                }}
              >
                <X className="size-3.5" />
              </Button>
            </div>

            <div className="flex-1 space-y-3.5 overflow-y-auto p-3.5 text-xs">
              {selectedArc && (
                <>
                  <div className="flex flex-wrap items-center gap-1.5">
                    <Badge status={(ALERT_META[selectedArc.alertState] ?? ALERT_META.OPEN).status}>
                      {(ALERT_META[selectedArc.alertState] ?? ALERT_META.OPEN).label}
                    </Badge>
                    {selectedArc.code && (
                      <span className="font-mono text-[11px] text-muted-foreground">
                        {selectedArc.code}
                      </span>
                    )}
                    {selectedArc.plantedChapterNo != null && (
                      <span className="font-mono text-[11px] text-muted-foreground">
                        埋设：Ch.{selectedArc.plantedChapterNo}
                      </span>
                    )}
                    {selectedArc.resolvedChapterNo != null && (
                      <span className="font-mono text-[11px] text-status-success">
                        回收：Ch.{selectedArc.resolvedChapterNo}
                      </span>
                    )}
                  </div>

                  <div className="space-y-1.5 rounded-xl border border-border bg-surface-subtle p-3">
                    <div className="text-[11px] font-medium text-muted-foreground">表层线索</div>
                    <p className="text-xs leading-relaxed text-foreground">
                      {selectedArc.surfaceClue || "（暂无表层线索描述）"}
                    </p>
                  </div>

                  {visibility === "PLAN" && selectedArc.hiddenTruth && (
                    <div className="space-y-1.5 rounded-xl border border-status-warning-border bg-status-warning-bg p-3">
                      <div className="flex items-center justify-between">
                        <span className="text-[11px] font-semibold text-status-warning">
                          幕后底牌
                        </span>
                        <button
                          type="button"
                          onClick={() => setRevealTruth((v) => !v)}
                          className="inline-flex items-center gap-1 text-[10.5px] text-status-warning hover:underline"
                        >
                          {revealTruth ? <EyeOff className="size-3" /> : <Eye className="size-3" />}
                          {revealTruth ? "收起" : "展开"}
                        </button>
                      </div>
                      {revealTruth && (
                        <p className="text-xs leading-relaxed text-foreground">
                          {selectedArc.hiddenTruth}
                        </p>
                      )}
                    </div>
                  )}

                  {selectedArc.involvedCharacters.length > 0 && (
                    <div className="space-y-1">
                      <div className="text-[11px] font-medium text-muted-foreground">涉及角色</div>
                      <div className="flex flex-wrap gap-1">
                        {selectedArc.involvedCharacters.map((c) => (
                          <span
                            key={c}
                            className="rounded-full bg-muted px-2 py-0.5 text-[11px] text-foreground"
                          >
                            {c}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}

                  {selectedArc.allowedDirectorModes.length > 0 && (
                    <div className="space-y-2 border-t border-border pt-3">
                      <div className="text-[11px] font-semibold text-foreground">带入 AI 写作</div>
                      <div className="flex flex-wrap gap-1.5">
                        {selectedArc.allowedDirectorModes.map((mode) => (
                          <Button
                            key={mode}
                            size="xs"
                            variant={mode === "PAYOFF" ? "default" : "secondary"}
                            onClick={() =>
                              onSendToDirector(
                                {
                                  foreshadowingId: selectedArc.id,
                                  mode,
                                  resolveType: mode === "PAYOFF" ? "FULL_RESOLVE" : undefined,
                                },
                                selectedArc.title,
                              )
                            }
                          >
                            <Sparkles className="size-3" />
                            {mode === "PLANT"
                              ? "安排本章埋入"
                              : mode === "HINT"
                                ? "安排本章照应"
                                : "安排本章回收"}
                          </Button>
                        ))}
                      </div>
                    </div>
                  )}

                  {selectedArc.plantedChapterNo != null && (
                    <Button
                      size="xs"
                      variant="outline"
                      className="w-full"
                      onClick={() => {
                        const target = allTreeChapters.find(
                          (c) => c.chapterNo === selectedArc.plantedChapterNo,
                        );
                        if (target) onJumpToChapter(target.id, selectedArc.plantedSectionId);
                      }}
                    >
                      跳转到埋设章节（Ch.{selectedArc.plantedChapterNo}）
                    </Button>
                  )}
                </>
              )}

              {selectedEvent && !editingEvent && (
                <>
                  <div className="space-y-1.5 rounded-xl border border-border bg-surface-subtle p-3">
                    <div className="flex items-center justify-between font-mono text-[11px] text-muted-foreground">
                      <span>第 {selectedEvent.chapterNo} 章</span>
                      {selectedEvent.location && <span>📍 {selectedEvent.location}</span>}
                    </div>
                    <p className="text-xs leading-relaxed text-foreground">
                      {selectedEvent.summary || "（暂无事件摘要）"}
                    </p>
                  </div>

                  {selectedEvent.involvedCharacters.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {selectedEvent.involvedCharacters.map((c) => (
                        <span
                          key={c}
                          className="rounded-full bg-muted px-2 py-0.5 text-[11px] text-foreground"
                        >
                          {c}
                        </span>
                      ))}
                    </div>
                  )}

                  <div className="flex flex-wrap gap-1.5">
                    {selectedEvent.chapterId && (
                      <Button
                        size="xs"
                        className="flex-1"
                        onClick={() =>
                          onJumpToChapter(selectedEvent.chapterId!, selectedEvent.sourceSectionId)
                        }
                      >
                        跳转正文
                      </Button>
                    )}
                    <Button
                      size="xs"
                      variant="secondary"
                      onClick={() =>
                        setEditingEvent({
                          id: selectedEvent.id,
                          chapterNo: String(selectedEvent.chapterNo || 1),
                          eventTitle: selectedEvent.eventTitle,
                          summary: selectedEvent.summary || "",
                          involvedCharacters: selectedEvent.involvedCharacters.join(", "),
                          location: selectedEvent.location || "",
                        })
                      }
                    >
                      <Pencil className="size-3" />
                      编辑
                    </Button>
                    <Button
                      size="xs"
                      variant="ghost"
                      onClick={() => void handleDeleteEvent(selectedEvent)}
                      className="text-status-danger hover:bg-status-danger-bg hover:text-status-danger"
                    >
                      删除
                    </Button>
                  </div>
                </>
              )}

              {selectedEvent && editingEvent && (
                <form onSubmit={handleSaveEventEdit} className="space-y-2.5">
                  <div className="grid grid-cols-3 gap-1.5">
                    <div className="space-y-1">
                      <label className="text-[11px] text-muted-foreground">章节号</label>
                      <Input
                        type="number"
                        min={1}
                        required
                        value={editingEvent.chapterNo}
                        onChange={(e) =>
                          setEditingEvent({ ...editingEvent, chapterNo: e.target.value })
                        }
                      />
                    </div>
                    <div className="col-span-2 space-y-1">
                      <label className="text-[11px] text-muted-foreground">事件标题</label>
                      <Input
                        required
                        value={editingEvent.eventTitle}
                        onChange={(e) =>
                          setEditingEvent({ ...editingEvent, eventTitle: e.target.value })
                        }
                      />
                    </div>
                  </div>
                  <div className="space-y-1">
                    <label className="text-[11px] text-muted-foreground">涉及角色（逗号分隔）</label>
                    <Input
                      value={editingEvent.involvedCharacters}
                      onChange={(e) =>
                        setEditingEvent({ ...editingEvent, involvedCharacters: e.target.value })
                      }
                    />
                  </div>
                  <div className="space-y-1">
                    <label className="text-[11px] text-muted-foreground">发生地点</label>
                    <Input
                      value={editingEvent.location}
                      onChange={(e) =>
                        setEditingEvent({ ...editingEvent, location: e.target.value })
                      }
                    />
                  </div>
                  <div className="space-y-1">
                    <label className="text-[11px] text-muted-foreground">事件摘要</label>
                    <textarea
                      rows={3}
                      value={editingEvent.summary}
                      onChange={(e) =>
                        setEditingEvent({ ...editingEvent, summary: e.target.value })
                      }
                      className="w-full resize-none rounded-lg border border-input bg-background px-2.5 py-1.5 text-xs outline-none focus:border-ring"
                    />
                  </div>
                  <div className="flex gap-1.5">
                    <Button type="submit" size="xs" loading={savingEvent} className="flex-1">
                      保存修改
                    </Button>
                    <Button
                      type="button"
                      size="xs"
                      variant="ghost"
                      onClick={() => setEditingEvent(null)}
                    >
                      取消
                    </Button>
                  </div>
                </form>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Add Timeline Event Dialog */}
      {eventDraft && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4 backdrop-blur-[1px]"
          onClick={() => setEventDraft(null)}
        >
          <form
            onSubmit={handleSaveNewEvent}
            onClick={(e) => e.stopPropagation()}
            className="w-full max-w-md space-y-3 rounded-2xl border border-border bg-card p-5 shadow-2xl"
          >
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-foreground">记录剧情时间线事件</h3>
              <Button size="icon-xs" variant="ghost" onClick={() => setEventDraft(null)}>
                <X className="size-3.5" />
              </Button>
            </div>
            <div className="grid grid-cols-3 gap-2">
              <div className="space-y-1">
                <label className="text-[11px] text-muted-foreground">章节号</label>
                <Input
                  type="number"
                  min={1}
                  required
                  value={eventDraft.chapterNo}
                  onChange={(e) => setEventDraft({ ...eventDraft, chapterNo: e.target.value })}
                />
              </div>
              <div className="col-span-2 space-y-1">
                <label className="text-[11px] text-muted-foreground">事件标题</label>
                <Input
                  required
                  value={eventDraft.eventTitle}
                  onChange={(e) => setEventDraft({ ...eventDraft, eventTitle: e.target.value })}
                  placeholder="例如：地牢夜谈与铜钱异动"
                />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-2">
              <div className="space-y-1">
                <label className="text-[11px] text-muted-foreground">涉及角色（逗号分隔）</label>
                <Input
                  value={eventDraft.involvedCharacters}
                  onChange={(e) =>
                    setEventDraft({ ...eventDraft, involvedCharacters: e.target.value })
                  }
                  placeholder="李火旺, 舟师"
                />
              </div>
              <div className="space-y-1">
                <label className="text-[11px] text-muted-foreground">发生地点</label>
                <Input
                  value={eventDraft.location}
                  onChange={(e) => setEventDraft({ ...eventDraft, location: e.target.value })}
                  placeholder="戒律堂地牢"
                />
              </div>
            </div>
            <div className="space-y-1">
              <label className="text-[11px] text-muted-foreground">事件摘要</label>
              <textarea
                rows={3}
                value={eventDraft.summary}
                onChange={(e) => setEventDraft({ ...eventDraft, summary: e.target.value })}
                placeholder="简述核心转折或对后续支线的影响..."
                className="w-full resize-none rounded-lg border border-input bg-background px-2.5 py-1.5 text-xs outline-none focus:border-ring"
              />
            </div>
            <div className="flex justify-end gap-2">
              <Button type="button" size="sm" variant="ghost" onClick={() => setEventDraft(null)}>
                取消
              </Button>
              <Button type="submit" size="sm" loading={savingEvent}>
                保存事件
              </Button>
            </div>
          </form>
        </div>
      )}
    </div>
  );
}
