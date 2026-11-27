"use client";

import { useEffect, useMemo, useState } from "react";
import { X } from "lucide-react";
import {
  visualApi,
  type ForeshadowingDirective,
  type VisualDirectorOptionsResponse,
  type VisualForeshadowingArc,
  type VisualVisibility,
} from "@/lib/api";

export interface UseDirectorOptionsParams {
  bookId: string;
  chapterId?: string | null;
  sectionId?: string | null;
  chapterNo: number | null;
  visibility?: VisualVisibility;
  refreshSignal?: number;
}

export function useDirectorOptions({
  bookId,
  chapterId = null,
  sectionId = null,
  chapterNo,
  visibility = "AS_OF",
  refreshSignal = 0,
}: UseDirectorOptionsParams) {
  const [options, setOptions] = useState<VisualDirectorOptionsResponse | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    void visualApi
      .getDirectorOptions(
        bookId,
        {
          visibility,
          asOfChapterId: visibility === "AS_OF" ? chapterId : undefined,
          asOfSectionId: visibility === "AS_OF" ? sectionId : undefined,
        },
        { signal: controller.signal },
      )
      .then((res) => {
        if (!active) return;
        setOptions(res);
      })
      .catch(() => {
        if (!active || controller.signal.aborted) return;
        setOptions(null);
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [bookId, chapterId, sectionId, chapterNo, visibility, refreshSignal]);

  const characters = useMemo(() => options?.characters ?? [], [options]);
  const allArcs = useMemo(() => options?.foreshadowings ?? [], [options]);

  const planned = useMemo(
    () => allArcs.filter((a) => a.allowedDirectorModes.includes("PLANT")),
    [allArcs],
  );
  const activePlanted = useMemo(
    () =>
      allArcs.filter(
        (a) => a.allowedDirectorModes.includes("HINT") || a.allowedDirectorModes.includes("PAYOFF"),
      ),
    [allArcs],
  );
  const dueCount = useMemo(
    () =>
      activePlanted.filter((a) => a.alertState === "DUE" || a.alertState === "OVERDUE").length,
    [activePlanted],
  );
  const arcById = useMemo(() => {
    const map = new Map<string, VisualForeshadowingArc>();
    for (const arc of allArcs) {
      map.set(arc.id, arc);
    }
    return map;
  }, [allArcs]);

  return {
    options,
    characters,
    planned,
    activePlanted,
    dueCount,
    arcById,
  };
}

export interface ForeshadowingDirectorTrayProps {
  visibility: VisualVisibility;
  characters: { id: string; name: string; roleType: string }[];
  planned: VisualForeshadowingArc[];
  activePlanted: VisualForeshadowingArc[];
  disabled: boolean;
  povCharacter: string;
  onPovCharacterChange: (pov: string) => void;
  directives: ForeshadowingDirective[];
  onDirectivesChange: (next: ForeshadowingDirective[]) => void;
  onClose?: () => void;
}

export function ForeshadowingDirectorTray({
  visibility,
  characters,
  planned,
  activePlanted,
  disabled,
  povCharacter,
  onPovCharacterChange,
  directives,
  onDirectivesChange,
  onClose,
}: ForeshadowingDirectorTrayProps) {
  function findDirective(id: string): ForeshadowingDirective | undefined {
    return directives.find((d) => d.foreshadowingId === id);
  }

  function toggleDirective(
    item: VisualForeshadowingArc,
    mode: "PLANT" | "HINT" | "PAYOFF",
    resolveType?: "FULL_RESOLVE" | "PARTIAL_REVEAL",
  ) {
    const existing = findDirective(item.id);
    if (existing && existing.mode === mode) {
      onDirectivesChange(directives.filter((d) => d.foreshadowingId !== item.id));
      return;
    }
    const without = directives.filter((d) => d.foreshadowingId !== item.id);
    if (without.length >= 3) {
      return;
    }
    onDirectivesChange([
      ...without,
      {
        foreshadowingId: item.id,
        mode,
        resolveType: mode === "PAYOFF" ? (resolveType ?? "FULL_RESOLVE") : undefined,
      },
    ]);
  }

  return (
    <div className="space-y-3 text-[11px]">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-1.5">
          <span className="font-semibold text-foreground">伏笔与视角</span>
          <span className="rounded-md bg-muted px-1.5 py-0.5 text-[9.5px] text-muted-foreground">
            {visibility === "AS_OF" ? "截至当前章" : "全书规划"}
          </span>
        </div>
        <div className="flex items-center gap-1.5">
          <span className="font-mono text-[10px] text-muted-foreground tabular-nums">
            已选 {directives.length}/3
          </span>
          {onClose && (
            <button
              type="button"
              onClick={onClose}
              aria-label="收起面板"
              className="rounded-md p-0.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              <X className="size-3.5" />
            </button>
          )}
        </div>
      </div>

      {/* POV Quick Chips + Custom Input */}
      <div className="space-y-1.5">
        <div className="flex items-center justify-between">
          <span className="text-[10px] font-medium text-muted-foreground">视角人物</span>
          {povCharacter && (
            <button
              type="button"
              disabled={disabled}
              onClick={() => onPovCharacterChange("")}
              className="text-[10px] text-muted-foreground hover:text-foreground"
            >
              恢复默认
            </button>
          )}
        </div>
        {characters.length > 0 && (
          <div className="flex flex-wrap gap-1">
            <button
              type="button"
              disabled={disabled}
              onClick={() => onPovCharacterChange("")}
              className={`rounded-full border px-2 py-0.5 text-[10.5px] transition-colors ${
                !povCharacter
                  ? "border-primary/40 bg-primary-soft font-medium text-primary-soft-foreground"
                  : "border-border bg-card text-muted-foreground hover:text-foreground"
              }`}
            >
              默认视角
            </button>
            {characters.slice(0, 8).map((c) => {
              const active = povCharacter === c.name;
              return (
                <button
                  key={c.id}
                  type="button"
                  disabled={disabled}
                  onClick={() => onPovCharacterChange(active ? "" : c.name)}
                  className={`max-w-28 truncate rounded-full border px-2 py-0.5 text-[10.5px] transition-colors ${
                    active
                      ? "border-status-info-border bg-status-info-bg font-medium text-status-info"
                      : "border-border bg-card text-foreground hover:border-primary/40"
                  }`}
                >
                  {c.name}
                </button>
              );
            })}
          </div>
        )}
        <input
          disabled={disabled}
          value={povCharacter}
          onChange={(e) => onPovCharacterChange(e.target.value)}
          placeholder="自定义视角人物"
          className="h-7 w-full rounded-lg border border-border bg-background px-2.5 text-[11px] text-foreground placeholder:text-muted-foreground/70 outline-none transition-colors focus:border-ring"
        />
      </div>

      {/* Foreshadowing Clue Controls */}
      <div className="max-h-44 space-y-2.5 overflow-y-auto pr-0.5">
        {planned.length > 0 && (
          <div className="space-y-1">
            <div className="text-[10px] font-medium text-muted-foreground">待埋伏笔</div>
            <ul className="space-y-1">
              {planned.slice(0, 6).map((item) => {
                const chosen = findDirective(item.id)?.mode === "PLANT";
                return (
                  <li
                    key={item.id}
                    className="flex items-center justify-between gap-2 rounded-lg border border-border/70 bg-card px-2 py-1.5"
                  >
                    <span className="min-w-0 flex-1 truncate text-foreground">
                      {item.code ? (
                        <span className="mr-1 font-mono text-[10px] text-primary">{item.code}</span>
                      ) : null}
                      {item.title}
                    </span>
                    <button
                      type="button"
                      disabled={disabled}
                      onClick={() => toggleDirective(item, "PLANT")}
                      className={`shrink-0 rounded-md px-2 py-0.5 text-[10px] font-medium transition-colors ${
                        chosen
                          ? "bg-primary text-primary-foreground"
                          : "border border-border bg-surface-subtle text-foreground hover:border-primary/50"
                      }`}
                    >
                      {chosen ? "✓ 埋入" : "埋入"}
                    </button>
                  </li>
                );
              })}
            </ul>
          </div>
        )}

        {activePlanted.length > 0 && (
          <div className="space-y-1">
            <div className="text-[10px] font-medium text-muted-foreground">已埋伏笔</div>
            <ul className="space-y-1">
              {activePlanted.slice(0, 8).map((item) => {
                const cur = findDirective(item.id);
                const isOverdue = item.alertState === "OVERDUE";
                const isDue = item.alertState === "DUE";
                return (
                  <li
                    key={item.id}
                    className="flex items-center justify-between gap-1.5 rounded-lg border border-border/70 bg-card px-2 py-1.5"
                  >
                    <span className="min-w-0 flex-1 truncate text-foreground">
                      {isOverdue && (
                        <span className="mr-1 rounded border border-status-danger-border bg-status-danger-bg px-1 text-[9px] text-status-danger">
                          逾期
                        </span>
                      )}
                      {isDue && (
                        <span className="mr-1 rounded border border-status-warning-border bg-status-warning-bg px-1 text-[9px] text-status-warning">
                          到期
                        </span>
                      )}
                      {item.code ? (
                        <span className="mr-1 font-mono text-[10px] text-primary">{item.code}</span>
                      ) : null}
                      {item.title}
                    </span>
                    <div className="flex shrink-0 items-center gap-1">
                      {item.allowedDirectorModes.includes("HINT") && (
                        <button
                          type="button"
                          disabled={disabled}
                          onClick={() => toggleDirective(item, "HINT")}
                          className={`rounded-md px-1.5 py-0.5 text-[10px] transition-colors ${
                            cur?.mode === "HINT"
                              ? "border border-status-info-border bg-status-info-bg font-semibold text-status-info"
                              : "border border-border bg-surface-subtle text-muted-foreground hover:text-foreground"
                          }`}
                        >
                          照应
                        </button>
                      )}
                      {item.allowedDirectorModes.includes("PAYOFF") && (
                        <button
                          type="button"
                          disabled={disabled}
                          onClick={() => toggleDirective(item, "PAYOFF", "FULL_RESOLVE")}
                          className={`rounded-md px-1.5 py-0.5 text-[10px] transition-colors ${
                            cur?.mode === "PAYOFF"
                              ? "border border-status-warning-border bg-status-warning-bg font-semibold text-status-warning"
                              : "border border-border bg-surface-subtle text-muted-foreground hover:text-foreground"
                          }`}
                        >
                          引爆
                        </button>
                      )}
                    </div>
                  </li>
                );
              })}
            </ul>
          </div>
        )}

        {planned.length === 0 && activePlanted.length === 0 && (
          <p className="rounded-lg border border-dashed border-border/80 bg-card/60 px-2.5 py-2 text-center text-[10.5px] text-muted-foreground">
            {visibility === "AS_OF" ? "截至当前章暂无已埋伏笔" : "暂无伏笔记录"}
          </p>
        )}
      </div>
    </div>
  );
}
