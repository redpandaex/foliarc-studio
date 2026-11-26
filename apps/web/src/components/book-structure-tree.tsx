"use client";

import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
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
  PRECISE: "精密长篇模式",
  EXPEDITION: "千章远征模式",
  EPIC: "万章史诗模式",
};

interface BookStructureTreeProps {
  tree: BookTreeResponse | null;
  activeChapterId: string | null;
  activeSectionId: string | null;
  runningChapterIds: Record<string, boolean>;
  creatingChapter: boolean;
  newChapterTitle: string;
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
    <aside className="flex w-64 shrink-0 flex-col border-r border-border bg-sidebar">
      <div className="space-y-2 border-b border-border p-3">
        <form onSubmit={onCreateChapter} className="flex gap-1.5">
          <input
            value={newChapterTitle}
            onChange={(e) => onNewChapterTitleChange(e.target.value)}
            placeholder="新章节标题..."
            className="min-w-0 flex-1 rounded-md border border-input bg-background px-2.5 py-1 text-xs outline-none focus:ring-1 focus:ring-ring"
          />
          <Button type="submit" size="xs" disabled={creatingChapter}>
            + 章
          </Button>
        </form>
        <div className="flex items-center justify-between text-[11px] text-muted-foreground">
          <span>
            {volumes.length} 卷 · {totalChapters} 章
          </span>
          <button
            type="button"
            onClick={() => setShowNewVolume((v) => !v)}
            className="font-medium text-foreground/80 hover:text-foreground"
          >
            {showNewVolume ? "收起分卷" : "+ 新建分卷"}
          </button>
        </div>

        {showNewVolume && (
          <form
            onSubmit={handleCreateVolumeSubmit}
            className="space-y-1.5 rounded-lg border border-border bg-background p-2 text-xs"
          >
            <input
              required
              value={newVolTitle}
              onChange={(e) => setNewVolTitle(e.target.value)}
              placeholder="分卷名称（如：第二卷·北境风雪）"
              className="w-full rounded border border-input bg-background px-2 py-1 text-xs outline-none"
            />
            <input
              value={newVolRealm}
              onChange={(e) => setNewVolRealm(e.target.value)}
              placeholder="舞台/地图域（可选）"
              className="w-full rounded border border-input bg-background px-2 py-1 text-xs outline-none"
            />
            <textarea
              rows={2}
              value={newVolGoal}
              onChange={(e) => setNewVolGoal(e.target.value)}
              placeholder="本卷核心目标 / 卷纲（可选，供 AI 把握主线走向）"
              className="w-full resize-none rounded border border-input bg-background px-2 py-1 text-xs outline-none"
            />
            <div className="flex justify-end gap-1">
              <Button type="button" size="xs" variant="ghost" onClick={() => setShowNewVolume(false)}>
                取消
              </Button>
              <Button type="submit" size="xs" disabled={savingVolume}>
                创建分卷
              </Button>
            </div>
          </form>
        )}
      </div>

      <div className="flex-1 overflow-y-auto p-2">
        {totalChapters === 0 ? (
          <p className="p-4 text-center text-xs text-muted-foreground">
            暂无章节，在上方输入标题创建第一章
          </p>
        ) : (
          <div className="space-y-2.5">
            {volumes.map((vol) => {
              const volKey = vol.id ?? `vol-${vol.volumeNo}`;
              const isCollapsed = Boolean(collapsedVolumes[volKey]);
              const isEditing = Boolean(vol.id && editingVolId === vol.id);
              return (
                <div key={volKey} className="space-y-1">
                  <div className="group flex items-center justify-between rounded px-1.5 py-1 text-[11px] font-semibold text-muted-foreground hover:bg-muted/60">
                    <button
                      type="button"
                      onClick={() => toggleVolume(volKey)}
                      className="flex min-w-0 flex-1 items-center gap-1 text-left"
                    >
                      <span className="text-[10px]">{isCollapsed ? "▸" : "▾"}</span>
                      <span className="truncate">{vol.title || `第 ${vol.volumeNo} 卷`}</span>
                    </button>
                    {vol.id && (
                      <button
                        type="button"
                        title="编辑卷纲"
                        onClick={() => startEditVolume(vol)}
                        className="rounded px-1 text-[10px] opacity-0 transition-opacity hover:bg-background group-hover:opacity-100"
                      >
                        卷纲
                      </button>
                    )}
                  </div>

                  {vol.arcGoal && !isCollapsed && !isEditing && (
                    <p className="line-clamp-2 px-2 text-[10px] text-muted-foreground/80">
                      目标：{vol.arcGoal}
                    </p>
                  )}

                  {isEditing && (
                    <form
                      onSubmit={handleEditVolumeSubmit}
                      className="space-y-1.5 rounded-md border border-primary/40 bg-background p-2 text-xs"
                    >
                      <input
                        required
                        value={editVolTitle}
                        onChange={(e) => setEditVolTitle(e.target.value)}
                        className="w-full rounded border border-input bg-background px-2 py-1 text-xs"
                      />
                      <input
                        value={editVolRealm}
                        onChange={(e) => setEditVolRealm(e.target.value)}
                        placeholder="舞台/地图域"
                        className="w-full rounded border border-input bg-background px-2 py-1 text-xs"
                      />
                      <textarea
                        rows={2}
                        value={editVolGoal}
                        onChange={(e) => setEditVolGoal(e.target.value)}
                        placeholder="本卷核心目标 / 卷纲"
                        className="w-full resize-none rounded border border-input bg-background px-2 py-1 text-xs"
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
                    <ul className="space-y-1">
                      {vol.chapters.map((ch) => {
                        const isMulti = ch.structureMode === "MULTI_SECTION" || ch.sections.length > 1;
                        const isChapterActive = ch.id === activeChapterId && !isMulti;
                        const isChapterSelected = ch.id === activeChapterId;
                        const isGenerating = Boolean(runningChapterIds[ch.id]);
                        return (
                          <li key={ch.id} className="space-y-0.5">
                            <div
                              className={`group flex items-center justify-between rounded-md px-2 py-1.5 text-xs transition-colors ${
                                isChapterActive
                                  ? "bg-primary text-primary-foreground"
                                  : isChapterSelected
                                    ? "bg-muted font-medium"
                                    : "hover:bg-muted"
                              }`}
                            >
                              <button
                                type="button"
                                onClick={() => onSelectChapter(ch)}
                                className="min-w-0 flex-1 text-left"
                              >
                                <div className="flex items-center gap-1.5">
                                  <span className="truncate font-medium">
                                    第 {ch.chapterNo} 章 {ch.title}
                                  </span>
                                  {isGenerating && (
                                    <span
                                      title="AI 创作进行中"
                                      className={`inline-block h-1.5 w-1.5 shrink-0 rounded-full animate-pulse ${
                                        isChapterActive ? "bg-primary-foreground" : "bg-status-success"
                                      }`}
                                    />
                                  )}
                                </div>
                                <div
                                  className={`mt-0.5 font-mono text-[10px] ${
                                    isChapterActive
                                      ? "text-primary-foreground/80"
                                      : "text-muted-foreground"
                                  }`}
                                >
                                  {ch.wordCount.toLocaleString()} 字 ·{" "}
                                  {isMulti
                                    ? `${ch.sections.length} 小节`
                                    : CHAPTER_STATUS_LABELS[ch.status] ?? ch.status}
                                </div>
                              </button>

                              <div className="ml-1 flex items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100">
                                <button
                                  type="button"
                                  title={isMulti ? "新增小节" : "拆分为多小节（大章模式）"}
                                  onClick={() => onCreateSection(ch)}
                                  className={`rounded px-1 py-0.5 text-[10px] ${
                                    isChapterActive
                                      ? "hover:bg-primary-foreground/20"
                                      : "hover:bg-background"
                                  }`}
                                >
                                  +节
                                </button>
                                {isMulti && (
                                  <button
                                    type="button"
                                    title="整章连贯预览"
                                    onClick={() => onOpenFullPreview(ch)}
                                    className="rounded px-1 py-0.5 text-[10px] hover:bg-background"
                                  >
                                    览
                                  </button>
                                )}
                                <button
                                  type="button"
                                  title="删除章节"
                                  onClick={() => onDeleteChapter(ch)}
                                  className={`rounded px-1 py-0.5 text-[11px] ${
                                    isChapterActive
                                      ? "hover:bg-primary-foreground/20"
                                      : "text-destructive hover:bg-destructive/10"
                                  }`}
                                >
                                  ×
                                </button>
                              </div>
                            </div>

                            {isMulti && (
                              <ul className="ml-3 space-y-0.5 border-l border-border pl-2">
                                {ch.sections.map((sec) => {
                                  const isSecActive = sec.id === activeSectionId;
                                  return (
                                    <li
                                      key={sec.id}
                                      className={`group/sec flex items-center justify-between rounded px-2 py-1 text-[11px] transition-colors ${
                                        isSecActive
                                          ? "bg-primary text-primary-foreground"
                                          : "hover:bg-muted/80"
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
                                              ? "text-primary-foreground/80"
                                              : "text-muted-foreground"
                                          }`}
                                        >
                                          {sec.wordCount.toLocaleString()} 字
                                        </div>
                                      </button>
                                      {ch.sections.length > 1 && (
                                        <button
                                          type="button"
                                          title="删除小节"
                                          onClick={() => onDeleteSection(ch, sec)}
                                          className={`ml-1 rounded px-1 text-[10px] opacity-0 transition-opacity group-hover/sec:opacity-100 ${
                                            isSecActive
                                              ? "hover:bg-primary-foreground/20"
                                              : "text-destructive hover:bg-destructive/10"
                                          }`}
                                        >
                                          ×
                                        </button>
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
        <div className="flex items-center justify-between border-t border-border px-3 py-2 text-[10px] text-muted-foreground">
          <span>{PRECISION_LABELS[tree.effectivePrecisionMode] ?? "长篇创作模式"}</span>
          <span className="font-mono">{tree.totalUnits} 单元</span>
        </div>
      )}
    </aside>
  );
}
