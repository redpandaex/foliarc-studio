"use client";

import { useEffect, useState } from "react";
import {
  loreApi,
  type CharacterItem,
  type DueForeshadowingsResponse,
  type ForeshadowingDirective,
  type ForeshadowingItem,
} from "@/lib/api";

interface ForeshadowingDirectorSlotProps {
  bookId: string;
  chapterNo: number | null;
  disabled: boolean;
  povCharacter: string;
  onPovCharacterChange: (pov: string) => void;
  directives: ForeshadowingDirective[];
  onDirectivesChange: (next: ForeshadowingDirective[]) => void;
}

export function ForeshadowingDirectorSlot({
  bookId,
  chapterNo,
  disabled,
  povCharacter,
  onPovCharacterChange,
  directives,
  onDirectivesChange,
}: ForeshadowingDirectorSlotProps) {
  const [open, setOpen] = useState(false);
  const [dueData, setDueData] = useState<DueForeshadowingsResponse | null>(null);
  const [characters, setCharacters] = useState<CharacterItem[]>([]);

  useEffect(() => {
    let active = true;
    const ch = chapterNo && chapterNo > 0 ? chapterNo : 1;
    void Promise.all([
      loreApi.listDueForeshadowings(bookId, ch).catch(() => null),
      loreApi.listCharacters(bookId).catch(() => []),
    ]).then(([due, chars]) => {
      if (!active) return;
      setDueData(due);
      setCharacters(chars.filter((c) => c.lifecycleStatus !== "DEAD"));
    });
    return () => {
      active = false;
    };
  }, [bookId, chapterNo]);

  const dueCount = dueData?.dueNow.length ?? 0;

  function findDirective(id: string): ForeshadowingDirective | undefined {
    return directives.find((d) => d.foreshadowingId === id);
  }

  function toggleDirective(
    item: ForeshadowingItem,
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

  const allPlanted: ForeshadowingItem[] = [];
  const seen = new Set<string>();
  for (const item of [...(dueData?.dueNow ?? []), ...(dueData?.activePlanted ?? [])]) {
    if (!seen.has(item.id)) {
      seen.add(item.id);
      allPlanted.push(item);
    }
  }
  const planned = dueData?.readyToPlant ?? [];

  return (
    <div className="rounded-md border border-border bg-muted/20">
      <button
        type="button"
        disabled={disabled}
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center justify-between px-2.5 py-1.5 text-[11px] font-medium hover:bg-muted/40 disabled:opacity-60"
      >
        <span className="flex items-center gap-1.5">
          <span>伏笔导演与视角</span>
          {directives.length > 0 && (
            <span className="rounded bg-primary/15 px-1.5 py-0.5 font-mono text-[10px] text-primary">
              已选 {directives.length}/3
            </span>
          )}
          {povCharacter && (
            <span className="rounded bg-sky-500/15 px-1.5 py-0.5 text-[10px] text-sky-700 dark:text-sky-400">
              视角:{povCharacter}
            </span>
          )}
        </span>
        <span className="flex items-center gap-1.5">
          {dueCount > 0 && (
            <span className="rounded-full bg-amber-500/20 px-1.5 py-0.5 text-[10px] text-amber-700 dark:text-amber-400">
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

          {allPlanted.length > 0 && (
            <div className="space-y-1">
              <div className="text-[10px] font-medium text-muted-foreground">
                已埋伏笔（轻点照应 / 启用引爆，上限共 3 条）
              </div>
              <ul className="space-y-1">
                {allPlanted.slice(0, 8).map((item) => {
                  const cur = findDirective(item.id);
                  const isDue = dueData?.dueNow.some((d) => d.id === item.id);
                  return (
                    <li key={item.id} className="flex items-center justify-between gap-1.5">
                      <span className="min-w-0 flex-1 truncate">
                        {isDue && (
                          <span className="mr-1 rounded bg-amber-500/20 px-1 text-[9px] text-amber-700 dark:text-amber-400">
                            到期
                          </span>
                        )}
                        {item.code ? `${item.code} ` : ""}
                        {item.title}
                      </span>
                      <div className="flex shrink-0 items-center gap-1">
                        <button
                          type="button"
                          disabled={disabled}
                          onClick={() => toggleDirective(item, "HINT")}
                          className={`rounded px-1.5 py-0.5 text-[10px] ${
                            cur?.mode === "HINT"
                              ? "bg-sky-600 text-white"
                              : "border border-border hover:bg-muted"
                          }`}
                        >
                          照应
                        </button>
                        <button
                          type="button"
                          disabled={disabled}
                          onClick={() => toggleDirective(item, "PAYOFF", "FULL_RESOLVE")}
                          className={`rounded px-1.5 py-0.5 text-[10px] ${
                            cur?.mode === "PAYOFF"
                              ? "bg-amber-600 text-white"
                              : "border border-border hover:bg-muted"
                          }`}
                        >
                          启用
                        </button>
                      </div>
                    </li>
                  );
                })}
              </ul>
            </div>
          )}

          {planned.length === 0 && allPlanted.length === 0 && (
            <p className="text-[10px] text-muted-foreground">
              暂无伏笔记录，可在「故事设定 → 伏笔」登记待埋线索或隐藏底牌。
            </p>
          )}
        </div>
      )}
    </div>
  );
}
