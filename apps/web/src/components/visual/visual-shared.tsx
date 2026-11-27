"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Compass, ShieldCheck } from "lucide-react";
import {
  ApiClientError,
  visualApi,
  type BookTreeResponse,
  type ChapterTreeItem,
  type VisualProjectionResponse,
  type VisualVisibility,
} from "@/lib/api";

export interface UseVisualProjectionParams {
  bookId: string;
  view: "graph" | "lanes";
  tree: BookTreeResponse | null;
  activeChapterId: string | null;
  activeSectionId: string | null;
  visibility: VisualVisibility;
  asOfChapterId: string | null;
  volumeId?: string | null;
  fromChapter?: number | null;
  toChapter?: number | null;
  refreshSignal?: number;
  errorFallback: string;
}

export interface UseVisualProjectionResult {
  allChapters: ChapterTreeItem[];
  effectiveChapterId: string | null;
  effectiveChapterIdx: number;
  scrubIdx: number | null;
  setScrubIdx: (next: number | null) => void;
  displayedIdx: number;
  displayedChapter: ChapterTreeItem | null;
  projection: VisualProjectionResponse | null;
  loading: boolean;
  refreshing: boolean;
  error: string | null;
  refetch: () => Promise<void>;
}

export function useVisualProjection({
  bookId,
  view,
  tree,
  activeChapterId,
  activeSectionId,
  visibility,
  asOfChapterId,
  volumeId = null,
  fromChapter = null,
  toChapter = null,
  refreshSignal = 0,
  errorFallback,
}: UseVisualProjectionParams): UseVisualProjectionResult {
  const allChapters = useMemo(
    () => (tree ? tree.volumes.flatMap((v) => v.chapters) : []),
    [tree],
  );

  const effectiveChapterId =
    asOfChapterId ?? activeChapterId ?? allChapters[allChapters.length - 1]?.id ?? null;

  const effectiveChapterIdx = useMemo(() => {
    if (allChapters.length === 0) return 0;
    const idx = allChapters.findIndex((c) => c.id === effectiveChapterId);
    return idx >= 0 ? idx : allChapters.length - 1;
  }, [allChapters, effectiveChapterId]);

  const [scrubIdx, setScrubIdx] = useState<number | null>(null);
  const displayedIdx = scrubIdx ?? effectiveChapterIdx;
  const displayedChapter = allChapters[displayedIdx] ?? null;

  const [projection, setProjection] = useState<VisualProjectionResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reqVersionRef = useRef(0);
  const abortRef = useRef<AbortController | null>(null);
  const hasLoadedRef = useRef(false);

  const buildParams = useCallback(() => {
    const useSection =
      visibility === "AS_OF" &&
      effectiveChapterId &&
      effectiveChapterId === activeChapterId &&
      activeSectionId
        ? activeSectionId
        : null;
    return {
      view,
      visibility,
      asOfChapterId: visibility === "AS_OF" ? effectiveChapterId : undefined,
      asOfSectionId: useSection,
      volumeId: volumeId || undefined,
      fromChapter: fromChapter ?? undefined,
      toChapter: toChapter ?? undefined,
    };
  }, [
    view,
    visibility,
    effectiveChapterId,
    activeChapterId,
    activeSectionId,
    volumeId,
    fromChapter,
    toChapter,
  ]);

  const refetch = useCallback(async () => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const reqId = ++reqVersionRef.current;
    if (hasLoadedRef.current) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    setError(null);
    try {
      const data = await visualApi.getProjection(bookId, buildParams(), {
        signal: controller.signal,
      });
      if (reqId !== reqVersionRef.current) return;
      hasLoadedRef.current = true;
      setProjection(data);
    } catch (err) {
      if (controller.signal.aborted || reqId !== reqVersionRef.current) return;
      setError(err instanceof ApiClientError ? err.message : errorFallback);
    } finally {
      if (reqId === reqVersionRef.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, [bookId, buildParams, errorFallback]);

  useEffect(() => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const reqId = ++reqVersionRef.current;
    let active = true;

    void Promise.resolve().then(() => {
      if (!active || reqId !== reqVersionRef.current) return;
      if (hasLoadedRef.current) {
        setRefreshing(true);
      }
    });

    void visualApi
      .getProjection(bookId, buildParams(), { signal: controller.signal })
      .then((data) => {
        if (!active || reqId !== reqVersionRef.current) return;
        hasLoadedRef.current = true;
        setProjection(data);
        setError(null);
      })
      .catch((err: unknown) => {
        if (!active || controller.signal.aborted || reqId !== reqVersionRef.current) return;
        setError(err instanceof ApiClientError ? err.message : errorFallback);
      })
      .finally(() => {
        if (active && reqId === reqVersionRef.current) {
          setLoading(false);
          setRefreshing(false);
        }
      });

    return () => {
      active = false;
      controller.abort();
    };
  }, [bookId, buildParams, errorFallback, refreshSignal]);

  return {
    allChapters,
    effectiveChapterId,
    effectiveChapterIdx,
    scrubIdx,
    setScrubIdx,
    displayedIdx,
    displayedChapter,
    projection,
    loading,
    refreshing,
    error,
    refetch,
  };
}

export function useEscapeKey(enabled: boolean, onEscape: () => void) {
  useEffect(() => {
    if (!enabled) return;
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") {
        e.preventDefault();
        onEscape();
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [enabled, onEscape]);
}

interface VisualControlRibbonProps {
  visibility: VisualVisibility;
  onVisibilityChange: (next: VisualVisibility) => void;
  allChapters: ChapterTreeItem[];
  displayedIdx: number;
  displayedChapter: ChapterTreeItem | null;
  scrubIdx: number | null;
  onScrubChange: (idx: number) => void;
  onScrubCommit: (chapterId: string) => void;
  sliderAriaLabel: string;
  showChapterTitle?: boolean;
  refreshing?: boolean;
  leftExtra?: ReactNode;
  rightActions?: ReactNode;
}

export function VisualControlRibbon({
  visibility,
  onVisibilityChange,
  allChapters,
  displayedIdx,
  displayedChapter,
  scrubIdx,
  onScrubChange,
  onScrubCommit,
  sliderAriaLabel,
  showChapterTitle = false,
  refreshing = false,
  leftExtra,
  rightActions,
}: VisualControlRibbonProps) {
  function commitScrub() {
    if (scrubIdx !== null && allChapters[scrubIdx]) {
      onScrubCommit(allChapters[scrubIdx].id);
    }
  }

  return (
    <div className="relative flex flex-wrap items-center justify-between gap-2 border-b border-border bg-card px-3.5 py-2 text-xs">
      <div className="flex flex-wrap items-center gap-2">
        <div className="inline-flex rounded-lg bg-muted p-0.5">
          <button
            type="button"
            onClick={() => onVisibilityChange("AS_OF")}
            className={`inline-flex items-center gap-1 rounded-md px-2.5 py-1 text-[11px] font-medium transition-all ${
              visibility === "AS_OF"
                ? "bg-card text-foreground shadow-2xs"
                : "text-muted-foreground hover:text-foreground"
            }`}
          >
            <ShieldCheck className="size-3.5 text-primary" />
            读到这里（防剧透）
          </button>
          <button
            type="button"
            onClick={() => onVisibilityChange("PLAN")}
            className={`inline-flex items-center gap-1 rounded-md px-2.5 py-1 text-[11px] font-medium transition-all ${
              visibility === "PLAN"
                ? "bg-card text-foreground shadow-2xs"
                : "text-muted-foreground hover:text-foreground"
            }`}
          >
            <Compass className="size-3.5 text-primary" />
            全书规划
          </button>
        </div>

        {visibility === "AS_OF" && allChapters.length > 0 && (
          <div className="flex items-center gap-2 rounded-lg border border-border bg-surface-subtle px-2.5 py-1">
            <span className="shrink-0 font-mono text-[11px] font-medium text-primary">
              ≤ 第 {displayedChapter?.chapterNo ?? 0} 章
            </span>
            <input
              type="range"
              min={0}
              max={Math.max(0, allChapters.length - 1)}
              value={displayedIdx}
              aria-label={sliderAriaLabel}
              onChange={(e) => onScrubChange(Number(e.target.value))}
              onMouseUp={commitScrub}
              onTouchEnd={commitScrub}
              onKeyUp={commitScrub}
              className="h-1.5 w-28 cursor-pointer accent-primary sm:w-36"
            />
            {showChapterTitle && (
              <span className="hidden max-w-28 truncate text-[11px] text-muted-foreground lg:inline">
                {displayedChapter?.title ?? ""}
              </span>
            )}
          </div>
        )}

        {leftExtra}
      </div>

      <div className="flex flex-wrap items-center gap-1.5">{rightActions}</div>

      {refreshing && (
        <div
          aria-hidden
          className="pointer-events-none absolute right-0 bottom-0 left-0 h-0.5 overflow-hidden bg-primary/15"
        >
          <div className="h-full w-1/3 animate-pulse rounded-full bg-primary" />
        </div>
      )}
    </div>
  );
}
