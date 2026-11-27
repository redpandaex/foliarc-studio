"use client";

import { useState, type FormEvent } from "react";
import { MoreHorizontal } from "lucide-react";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import type {
  BookTreeResponse,
  ChapterTreeItem,
  SectionSummaryItem,
  VolumeTreeItem,
} from "@/lib/api";

const CHAPTER_STATUS_LABELS: Record<string, string> = {
  DRAFT: "草稿",
  REVIEWING: "审校中",
  PUBLISHED: "已定稿",
};

const PRECISION_LABELS: Record<string, string> = {
  PRECISE: "标准记忆",
  EXPEDITION: "千章记忆",
  EPIC: "万章记忆",
};

interface BookStructureTreeProps {
  tree: BookTreeResponse | null;
  activeChapterId: string | null;
  activeSectionId: string | null;
  runningChapterIds: Record<string, boolean>;
  creatingChapter: boolean;
  newChapterTitle: string;
  compact?: boolean;
  onNewChapterTitleChange: (val: string) => void;
  onCreateChapter: (e: FormEvent) => void;
  onSelectChapter: (chapter: ChapterTreeItem) => void;
  onSelectSection: (chapter: ChapterTreeItem, section: SectionSummaryItem) => void;
  onDeleteChapter: (chapter: ChapterTreeItem) => void;
  onCreateSection: (chapter: ChapterTreeItem) => void;
  onDeleteSection: (chapter: ChapterTreeItem, section: SectionSummaryItem) => void;
  onOpenFullPreview: (chapter: ChapterTreeItem) => void;
  onCreateVolume: (payload: { title: string; arcGoal: string; realmScope: string }) => Promise<void>;
  onUpdateVolume: (
    volumeId: string,
    payload: { title: string; arcGoal: string; realmScope: string },
  ) => Promise<void>;
}

export function BookStructureTree({
  tree,
  activeChapterId,
  activeSectionId,
  runningChapterIds,
  creatingChapter,
  newChapterTitle,
  compact = false,
  onNewChapterTitleChange,
  onCreateChapter,
  onSelectChapter,
  onSelectSection,
  onDeleteChapter,
  onCreateSection,
  onDeleteSection,
  onOpenFullPreview,
  onCreateVolume,
  onUpdateVolume,
}: BookStructureTreeProps) {
  const [collapsedVolumes, setCollapsedVolumes] = useState<Record<string, boolean>>({});
  const [showNewVolume, setShowNewVolume] = useState(false);
  const [newVolTitle, setNewVolTitle] = useState("");
  const [newVolGoal, setNewVolGoal] = useState("");
  const [newVolRealm, setNewVolRealm] = useState("");
  const [savingVolume, setSavingVolume] = useState(false);

  const [editingVolId, setEditingVolId] = useState<string | null>(null);
  const [editVolTitle, setEditVolTitle] = useState("");
  const [editVolGoal, setEditVolGoal] = useState("");
  const [editVolRealm, setEditVolRealm] = useState("");

  const volumes = tree?.volumes ?? [];
  const totalChapters = volumes.reduce((acc, v) => acc + v.chapters.length, 0);

  function toggleVolume(key: string) {
    setCollapsedVolumes((prev) => ({ ...prev, [key]: !prev[key] }));
  }

  function startEditVolume(vol: VolumeTreeItem) {
    if (!vol.id) return;
    setEditingVolId(vol.id);
    setEditVolTitle(vol.title);
    setEditVolGoal(vol.arcGoal || "");
    setEditVolRealm(vol.realmScope || "");
  }

  async function handleCreateVolumeSubmit(e: FormEvent) {
    e.preventDefault();
    if (!newVolTitle.trim()) return;
    setSavingVolume(true);
    try {
      await onCreateVolume({
        title: newVolTitle.trim(),
        arcGoal: newVolGoal.trim(),
        realmScope: newVolRealm.trim(),
      });
      setNewVolTitle("");
      setNewVolGoal("");
      setNewVolRealm("");
      setShowNewVolume(false);
    } finally {
      setSavingVolume(false);
    }
  }

  async function handleEditVolumeSubmit(e: FormEvent) {
    e.preventDefault();
    if (!editingVolId || !editVolTitle.trim()) return;
    setSavingVolume(true);
    try {
      await onUpdateVolume(editingVolId, {
        title: editVolTitle.trim(),
        arcGoal: editVolGoal.trim(),
        realmScope: editVolRealm.trim(),
      });
      setEditingVolId(null);
    } finally {
      setSavingVolume(false);
    }
  }

  return (
    <aside
      className={`flex shrink-0 flex-col border-r border-border bg-sidebar transition-[width] duration-150 ${
        compact ? "w-56" : "w-60"
      }`}
    >
      <div
        className={`border-b border-border ${
          compact ? "space-y-2 px-2.5 py-2.5" : "space-y-2.5 px-3 py-3"
        }`}
      >
        {totalChapters > 0 && (
          <form onSubmit={onCreateChapter} className="flex gap-1.5">
            <input
              aria-label="新章节标题"
              value={newChapterTitle}
              onChange={(e) => onNewChapterTitleChange(e.target.value)}
              placeholder="新章节标题"
              className={`min-w-0 flex-1 rounded-lg border border-border bg-card px-2.5 text-xs text-foreground placeholder:text-muted-foreground/75 outline-none transition-colors focus:border-ring ${
                compact ? "h-7" : "h-8"
              }`}
            />
            <Button
              type="submit"
              size="sm"
              disabled={creatingChapter}
              className={compact ? "h-7 px-2" : "h-8 px-2.5"}
            >
              + 章
            </Button>
          </form>
        )}

        <div className="flex items-center justify-between px-0.5 text-[11px] text-muted-foreground">
          <span>
            {volumes.length} 卷 · {totalChapters} 章
          </span>
          <button
            type="button"
            onClick={() => setShowNewVolume((v) => !v)}
            className="font-medium text-primary transition-opacity hover:opacity-80"
          >
            {showNewVolume ? "收起" : "+ 新建分卷"}
          </button>
        </div>

        {showNewVolume && (
          <form
            onSubmit={handleCreateVolumeSubmit}
            className="space-y-1.5 rounded-xl border border-border bg-card p-2.5 text-xs shadow-2xs"
          >
            <input
              required
              value={newVolTitle}
              onChange={(e) => setNewVolTitle(e.target.value)}
              placeholder="分卷名称"
              className="w-full rounded-lg border border-border bg-background px-2.5 py-1.5 text-xs outline-none focus:border-ring"
            />
            <input
              value={newVolRealm}
              onChange={(e) => setNewVolRealm(e.target.value)}
              placeholder="舞台 / 区域（选填）"
              className="w-full rounded-lg border border-border bg-background px-2.5 py-1.5 text-xs outline-none focus:border-ring"
            />
            <textarea
              rows={2}
              value={newVolGoal}
              onChange={(e) => setNewVolGoal(e.target.value)}
              placeholder="卷纲（选填）"
              className="w-full resize-none rounded-lg border border-border bg-background px-2.5 py-1.5 text-xs outline-none focus:border-ring"
            />
            <div className="flex justify-end gap-1">
              <Button type="button" size="xs" variant="ghost" onClick={() => setShowNewVolume(false)}>
                取消
              </Button>
              <Button type="submit" size="xs" disabled={savingVolume}>
                创建
              </Button>
            </div>
          </form>
        )}
      </div>

      <div className="flex-1 overflow-y-auto px-2 py-2.5">
        {totalChapters === 0 ? (
          <form
            onSubmit={onCreateChapter}
            className="mx-1 my-4 space-y-2.5 rounded-xl border border-dashed border-border bg-card/70 p-3.5"
          >
            <div className="text-xs font-medium text-foreground">第一章</div>
            <input
              aria-label="第一章标题"
              value={newChapterTitle}
              onChange={(e) => onNewChapterTitleChange(e.target.value)}
              placeholder="输入章节标题"
              className="h-8 w-full rounded-lg border border-border bg-background px-2.5 text-xs text-foreground placeholder:text-muted-foreground/75 outline-none focus:border-ring"
            />
            <Button type="submit" size="xs" className="w-full" loading={creatingChapter}>
              创建第一章
            </Button>
          </form>
        ) : (
          <div className="space-y-3">
            {volumes.map((vol) => {
              const volKey = vol.id ?? `vol-${vol.volumeNo}`;
              const isCollapsed = Boolean(collapsedVolumes[volKey]);
              const isEditing = Boolean(vol.id && editingVolId === vol.id);
              return (
                <div key={volKey} className="space-y-1">
                  <div className="flex items-center justify-between rounded-lg px-2 py-1 text-[11px] font-semibold text-muted-foreground transition-colors hover:bg-muted/70">
                    <button
                      type="button"
                      onClick={() => toggleVolume(volKey)}
                      className="flex min-w-0 flex-1 items-center gap-1.5 text-left"
                    >
                      <span className="text-[10px]">{isCollapsed ? "▸" : "▾"}</span>
                      <span className="truncate">{vol.title || `第 ${vol.volumeNo} 卷`}</span>
                    </button>
                    {vol.id && (
                      <button
                        type="button"
                        title="编辑卷纲"
                        onClick={() => startEditVolume(vol)}
                        className="rounded-md px-1.5 py-0.5 text-[10px] text-muted-foreground transition-colors hover:bg-card hover:text-foreground"
                      >
                        卷纲
                      </button>
                    )}
                  </div>

                  {vol.arcGoal && !isCollapsed && !isEditing && (
                    <p className="line-clamp-2 px-2.5 text-[10px] text-muted-foreground/80">
                      {vol.arcGoal}
                    </p>
                  )}

                  {isEditing && (
                    <form
                      onSubmit={handleEditVolumeSubmit}
                      className="space-y-1.5 rounded-xl border border-primary/30 bg-card p-2.5 text-xs shadow-2xs"
                    >
                      <input
                        required
                        value={editVolTitle}
                        onChange={(e) => setEditVolTitle(e.target.value)}
                        className="w-full rounded-lg border border-border bg-background px-2.5 py-1.5 text-xs outline-none focus:border-ring"
                      />
                      <input
                        value={editVolRealm}
                        onChange={(e) => setEditVolRealm(e.target.value)}
                        placeholder="舞台 / 区域（选填）"
                        className="w-full rounded-lg border border-border bg-background px-2.5 py-1.5 text-xs outline-none focus:border-ring"
                      />
                      <textarea
                        rows={2}
                        value={editVolGoal}
                        onChange={(e) => setEditVolGoal(e.target.value)}
                        placeholder="卷纲（选填）"
                        className="w-full resize-none rounded-lg border border-border bg-background px-2.5 py-1.5 text-xs outline-none focus:border-ring"
                      />
                      <div className="flex justify-end gap-1">
                        <Button type="button" size="xs" variant="ghost" onClick={() => setEditingVolId(null)}>
                          取消
                        </Button>
                        <Button type="submit" size="xs" disabled={savingVolume}>
                          保存
                        </Button>
                      </div>
                    </form>
                  )}

                  {!isCollapsed && (
                    <ul className="space-y-0.5">
                      {vol.chapters.map((ch) => {
                        const isMulti = ch.structureMode === "MULTI_SECTION" || ch.sections.length > 1;
                        const isChapterActive = ch.id === activeChapterId && !isMulti;
                        const isChapterSelected = ch.id === activeChapterId;
                        const isGenerating = Boolean(runningChapterIds[ch.id]);
                        return (
                          <li key={ch.id} className="space-y-0.5">
                            <div
                              className={`relative flex items-center justify-between rounded-xl text-xs transition-colors ${
                                compact ? "px-2.5 py-1.5" : "px-3 py-2"
                              } ${
                                isChapterActive
                                  ? "bg-primary-soft text-primary-soft-foreground before:absolute before:left-0 before:top-2 before:bottom-2 before:w-[3px] before:rounded-r-full before:bg-primary"
                                  : isChapterSelected
                                    ? "bg-muted/80 font-medium text-foreground"
                                    : "text-foreground/90 hover:bg-muted/65"
                              }`}
                            >
                              <button
                                type="button"
                                onClick={() => onSelectChapter(ch)}
                                className="min-w-0 flex-1 text-left"
                              >
                                <div className="flex items-center gap-1.5">
                                  <span
                                    className={`truncate ${
                                      isChapterActive ? "font-semibold" : "font-medium"
                                    }`}
                                  >
                                    第 {ch.chapterNo} 章 {ch.title}
                                  </span>
                                  {isGenerating && (
                                    <span
                                      title="AI 生成中"
                                      className="inline-block size-1.5 shrink-0 animate-pulse rounded-full bg-primary"
                                    />
                                  )}
                                </div>
                                <div
                                  className={`mt-0.5 font-mono text-[10px] ${
                                    isChapterActive
                                      ? "text-primary-soft-foreground/75"
                                      : "text-muted-foreground"
                                  }`}
                                >
                                  {ch.wordCount.toLocaleString()} 字 ·{" "}
                                  {isMulti
                                    ? `${ch.sections.length} 小节`
                                    : CHAPTER_STATUS_LABELS[ch.status] ?? ch.status}
                                </div>
                              </button>

                              <DropdownMenu modal={false}>
                                <DropdownMenuTrigger
                                  aria-label={`第 ${ch.chapterNo} 章更多操作`}
                                  render={<Button size="icon-xs" variant="ghost" className="ml-1" />}
                                >
                                  <MoreHorizontal className="size-3.5" />
                                </DropdownMenuTrigger>
                                <DropdownMenuContent align="end" className="w-28">
                                  <DropdownMenuItem onClick={() => onCreateSection(ch)}>
                                    {isMulti ? "新增小节" : "拆为多节"}
                                  </DropdownMenuItem>
                                  {isMulti && (
                                    <DropdownMenuItem onClick={() => onOpenFullPreview(ch)}>整章预览</DropdownMenuItem>
                                  )}
                                  <DropdownMenuItem variant="destructive" onClick={() => onDeleteChapter(ch)}>
                                    删除章节
                                  </DropdownMenuItem>
                                </DropdownMenuContent>
                              </DropdownMenu>
                            </div>

                            {isMulti && (
                              <ul className="ml-3 space-y-0.5 border-l border-border pl-2">
                                {ch.sections.map((sec) => {
                                  const isSecActive = sec.id === activeSectionId;
                                  return (
                                    <li
                                      key={sec.id}
                                      className={`relative flex items-center justify-between rounded-lg px-2.5 py-1.5 text-[11px] transition-colors ${
                                        isSecActive
                                          ? "bg-primary-soft font-medium text-primary-soft-foreground before:absolute before:left-0 before:top-1.5 before:bottom-1.5 before:w-[2.5px] before:rounded-r-full before:bg-primary"
                                          : "hover:bg-muted/70"
                                      }`}
                                    >
                                      <button
                                        type="button"
                                        onClick={() => onSelectSection(ch, sec)}
                                        className="min-w-0 flex-1 text-left"
                                      >
                                        <div className="truncate font-medium">
                                          §{sec.sectionNo} {sec.title || `第 ${sec.sectionNo} 节`}
                                        </div>
                                        <div
                                          className={`font-mono text-[10px] ${
                                            isSecActive
                                              ? "text-primary-soft-foreground/75"
                                              : "text-muted-foreground"
                                          }`}
                                        >
                                          {sec.wordCount.toLocaleString()} 字
                                        </div>
                                      </button>
                                      {ch.sections.length > 1 && (
                                        <DropdownMenu modal={false}>
                                          <DropdownMenuTrigger
                                            aria-label={`第 ${sec.sectionNo} 节更多操作`}
                                            render={<Button size="icon-xs" variant="ghost" className="ml-1 size-5" />}
                                          >
                                            <MoreHorizontal className="size-3" />
                                          </DropdownMenuTrigger>
                                          <DropdownMenuContent align="end" className="w-24">
                                            <DropdownMenuItem variant="destructive" onClick={() => onDeleteSection(ch, sec)}>
                                              删除小节
                                            </DropdownMenuItem>
                                          </DropdownMenuContent>
                                        </DropdownMenu>
                                      )}
                                    </li>
                                  );
                                })}
                              </ul>
                            )}
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {tree && (
        <div className="flex items-center justify-between border-t border-border px-3.5 py-2 text-[11px] text-muted-foreground">
          <span>{PRECISION_LABELS[tree.effectivePrecisionMode] ?? "长篇记忆"}</span>
          <span className="font-mono tabular-nums">{tree.totalUnits} 节</span>
        </div>
      )}
    </aside>
  );
}
