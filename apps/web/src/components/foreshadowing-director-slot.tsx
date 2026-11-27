"use client";

import { useEffect, useState } from "react";
import {
  visualApi,
  type ForeshadowingDirective,
  type VisualDirectorOptionsResponse,
  type VisualForeshadowingArc,
  type VisualVisibility,
} from "@/lib/api";

interface ForeshadowingDirectorSlotProps {
  bookId: string;
  chapterId?: string | null;
  sectionId?: string | null;
  chapterNo: number | null;
  visibility?: VisualVisibility;
  refreshSignal?: number;
  disabled: boolean;
  povCharacter: string;
  onPovCharacterChange: (pov: string) => void;
  directives: ForeshadowingDirective[];
  onDirectivesChange: (next: ForeshadowingDirective[]) => void;
}

export function ForeshadowingDirectorSlot({
  bookId,
  chapterId = null,
  sectionId = null,
  chapterNo,
  visibility = "AS_OF",
  refreshSignal = 0,
  disabled,
  povCharacter,
  onPovCharacterChange,
  directives,
  onDirectivesChange,
}: ForeshadowingDirectorSlotProps) {
  const [open, setOpen] = useState(false);
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

  const characters = options?.characters ?? [];
  const allArcs = options?.foreshadowings ?? [];

  const planned = allArcs.filter((a) => a.allowedDirectorModes.includes("PLANT"));
  const activePlanted = allArcs.filter(
    (a) => a.allowedDirectorModes.includes("HINT") || a.allowedDirectorModes.includes("PAYOFF"),
  );
  const dueCount = activePlanted.filter(
    (a) => a.alertState === "DUE" || a.alertState === "OVERDUE",
  ).length;

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
    <div className="rounded-xl border border-border bg-card">
      <button
        type="button"
        disabled={disabled}
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between rounded-xl px-3 py-2 text-[11px] font-medium text-foreground transition-colors hover:bg-surface-subtle disabled:opacity-60"
      >
        <span className="flex min-w-0 items-center gap-1.5">
          <span className="truncate">伏笔导演与视角</span>
          <span
            title={
              visibility === "AS_OF"
                ? "与可视化视角同步：仅显示截至当前章节已出现的人物与已埋伏笔（防剧透）"
                : "与可视化视角同步：全书规划模式，含待埋伏笔与未登场角色"
            }
            className="shrink-0 rounded bg-muted px-1.5 py-0.5 text-[9.5px] font-normal text-muted-foreground"
          >
            {visibility === "AS_OF" ? "防剧透" : "全书规划"}
          </span>
          {directives.length > 0 && (
            <span className="shrink-0 rounded-full bg-primary-soft px-2 py-0.5 font-mono text-[10px] text-primary-soft-foreground">
              已选 {directives.length}/3
            </span>
          )}
          {povCharacter && (
            <span className="max-w-20 truncate rounded border border-status-info-border bg-status-info-bg px-1.5 py-0.5 text-[10px] text-status-info">
              视角:{povCharacter}
            </span>
          )}
        </span>
        <span className="flex shrink-0 items-center gap-1.5">
          {dueCount > 0 && (
            <span className="rounded-full border border-status-warning-border bg-status-warning-bg px-1.5 py-0.5 text-[10px] text-status-warning">
              {dueCount} 条到期
            </span>
          )}
          <span className="text-muted-foreground">{open ? "▴" : "▾"}</span>
        </span>
      </button>

      {open && (
        <div className="space-y-2.5 border-t border-border p-2.5 text-[11px]">
          <div className="space-y-1">
            <label className="text-[10px] text-muted-foreground">
              本章叙事视角（POV，防止角色凭空读心）
            </label>
            <div className="flex gap-1.5">
              <select
                disabled={disabled}
                value={characters.some((c) => c.name === povCharacter) ? povCharacter : ""}
                onChange={(e) => onPovCharacterChange(e.target.value)}
                className="w-28 rounded border border-input bg-background px-1.5 py-1 text-[11px]"
              >
                <option value="">默认视角</option>
                {characters.map((c) => (
                  <option key={c.id} value={c.name}>
                    {c.name}
                  </option>
                ))}
              </select>
              <input
                disabled={disabled}
                value={povCharacter}
                onChange={(e) => onPovCharacterChange(e.target.value)}
                placeholder="或手动填视角人物"
                className="min-w-0 flex-1 rounded border border-input bg-background px-2 py-1 text-[11px] outline-none"
              />
            </div>
          </div>

          {planned.length > 0 && (
            <div className="space-y-1">
              <div className="text-[10px] font-medium text-muted-foreground">
                待埋伏笔（勾选本章埋入）
              </div>
              <ul className="space-y-1">
                {planned.slice(0, 6).map((item) => {
                  const chosen = findDirective(item.id)?.mode === "PLANT";
                  return (
                    <li key={item.id} className="flex items-center justify-between gap-2">
                      <span className="truncate">
                        {item.code ? `${item.code} ` : ""}
                        {item.title}
                      </span>
                      <button
                        type="button"
                        disabled={disabled}
                        onClick={() => toggleDirective(item, "PLANT")}
                        className={`shrink-0 rounded px-2 py-0.5 text-[10px] ${
                          chosen
                            ? "bg-primary text-primary-foreground"
                            : "border border-border hover:bg-muted"
                        }`}
                      >
                        {chosen ? "✓ 本章埋入" : "埋入"}
                      </button>
                    </li>
                  );
                })}
              </ul>
            </div>
          )}

          {activePlanted.length > 0 && (
            <div className="space-y-1">
              <div className="text-[10px] font-medium text-muted-foreground">
                已埋伏笔（轻点照应 / 启用引爆，上限共 3 条）
              </div>
              <ul className="space-y-1">
                {activePlanted.slice(0, 8).map((item) => {
                  const cur = findDirective(item.id);
                  const isOverdue = item.alertState === "OVERDUE";
                  const isDue = item.alertState === "DUE";
                  return (
                    <li key={item.id} className="flex items-center justify-between gap-1.5">
                      <span className="min-w-0 flex-1 truncate">
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
                        {item.code ? `${item.code} ` : ""}
                        {item.title}
                      </span>
                      <div className="flex shrink-0 items-center gap-1">
                        {item.allowedDirectorModes.includes("HINT") && (
                          <button
                            type="button"
                            disabled={disabled}
                            onClick={() => toggleDirective(item, "HINT")}
                            className={`rounded px-1.5 py-0.5 text-[10px] ${
                              cur?.mode === "HINT"
                                ? "border border-status-info-border bg-status-info-bg font-semibold text-status-info"
                                : "border border-border hover:bg-muted"
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
                            className={`rounded px-1.5 py-0.5 text-[10px] ${
                              cur?.mode === "PAYOFF"
                                ? "border border-status-warning-border bg-status-warning-bg font-semibold text-status-warning"
                                : "border border-border hover:bg-muted"
                            }`}
                          >
                            启用
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
            <p className="text-[10px] text-muted-foreground">
              {visibility === "AS_OF"
                ? "截至当前章节暂无已埋伏笔；切至「全书规划」可选择待埋设线索，或在「故事设定 → 伏笔」新建。"
                : "暂无伏笔记录，可在「故事设定 → 伏笔」登记待埋线索或隐藏底牌。"}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
