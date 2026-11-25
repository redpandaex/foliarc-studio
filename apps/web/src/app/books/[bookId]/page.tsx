"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import {
  Suspense,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from "react";
import { useAuth } from "@/components/auth-provider";
import { AiCopilotPanel } from "@/components/ai-copilot-panel";
import { BookStructureTree } from "@/components/book-structure-tree";
import { LorePanel } from "@/components/lore-panel";
import { Button } from "@/components/ui/button";
import {
  ApiClientError,
  booksApi,
  chaptersApi,
  runsApi,
  structureApi,
  type BookItem,
  type BookTreeResponse,
  type ChapterDetail,
  type ChapterTreeItem,
  type ChapterVersion,
  type FullChapterPreview,
  type SectionDetail,
  type SectionSummaryItem,
} from "@/lib/api";
import type { StreamConnectionState } from "@/lib/sse-client";

const CHAPTER_STATUS_LABELS: Record<string, string> = {
  DRAFT: "草稿",
  REVIEWING: "审校中",
  PUBLISHED: "已定稿",
};

function countWords(text: string): number {
  return text.replace(/\s+/g, "").length;
}

function activeChapterStorageKey(bookId: string): string {
  return `foliarc:active_chapter:${bookId}`;
}

function syncActiveChapterLocation(bookId: string, chapterId: string | null) {
  if (typeof window === "undefined") return;
  const key = activeChapterStorageKey(bookId);
  if (chapterId) {
    window.sessionStorage.setItem(key, chapterId);
  } else {
    window.sessionStorage.removeItem(key);
  }
  const url = new URL(window.location.href);
  if (chapterId) {
    url.searchParams.set("chapterId", chapterId);
  } else {
    url.searchParams.delete("chapterId");
  }
  window.history.replaceState(null, "", url.pathname + url.search);
}

function flattenTreeChapters(tree: BookTreeResponse | null): ChapterTreeItem[] {
  if (!tree) return [];
  return tree.volumes.flatMap((v) => v.chapters);
}

function BookWorkbenchContent() {
  const params = useParams<{ bookId: string }>();
  const bookId = params.bookId;
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();

  const [book, setBook] = useState<BookItem | null>(null);
  const [tree, setTree] = useState<BookTreeResponse | null>(null);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [activeChapter, setActiveChapter] = useState<ChapterDetail | null>(null);
  const [activeSection, setActiveSection] = useState<SectionDetail | null>(null);

  const [draftTitle, setDraftTitle] = useState("");
  const [draftContent, setDraftContent] = useState("");
  const [draftStatus, setDraftStatus] = useState("DRAFT");

  const [saveState, setSaveState] = useState<
    "idle" | "dirty" | "saving" | "saved" | "conflict" | "error"
  >("idle");
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [vipLimitError, setVipLimitError] = useState<string | null>(null);

  const [newChapterTitle, setNewChapterTitle] = useState("");
  const [creatingChapter, setCreatingChapter] = useState(false);

  const [rightTab, setRightTab] = useState<"ai" | "lore">("ai");
  const [rightPanelOpen, setRightPanelOpen] = useState(true);

  const [versionsOpen, setVersionsOpen] = useState(false);
  const [versions, setVersions] = useState<ChapterVersion[]>([]);
  const [previewVersion, setPreviewVersion] = useState<ChapterVersion | null>(null);
  const [snapshotSummary, setSnapshotSummary] = useState("");
  const [fullPreview, setFullPreview] = useState<FullChapterPreview | null>(null);
  const [loadingPage, setLoadingPage] = useState(true);

  const [runningChapterIds, setRunningChapterIds] = useState<Record<string, boolean>>({});
  const autoSaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const liveWordCount = useMemo(() => countWords(draftContent), [draftContent]);

  const activeTreeChapter = useMemo(() => {
    if (!activeId) return null;
    return flattenTreeChapters(tree).find((c) => c.id === activeId) ?? null;
  }, [tree, activeId]);

  const isMultiSectionEditing = Boolean(
    activeSection &&
      activeTreeChapter &&
      (activeTreeChapter.structureMode === "MULTI_SECTION" ||
        activeTreeChapter.sections.length > 1),
  );

  const effectiveSectionId = useMemo(() => {
    if (activeSection) return activeSection.id;
    return activeTreeChapter?.sections[0]?.id ?? null;
  }, [activeSection, activeTreeChapter]);

  const refreshTree = useCallback(async (): Promise<BookTreeResponse | null> => {
    try {
      const next = await structureApi.getTree(bookId);
      setTree(next);
      return next;
    } catch {
      return null;
    }
  }, [bookId]);

  const loadChapterDetail = useCallback(
    async (targetChapterId: string, preferredSectionId?: string | null, treeSnapshot?: BookTreeResponse | null) => {
      setErrorMsg(null);
      setVipLimitError(null);
      try {
        const [detail, versionList] = await Promise.all([
          chaptersApi.get(bookId, targetChapterId),
          chaptersApi.listVersions(bookId, targetChapterId),
        ]);
        setActiveId(detail.id);
        setActiveChapter(detail);
        setVersions(versionList);
        setPreviewVersion(null);
        syncActiveChapterLocation(bookId, detail.id);

        const targetTree = treeSnapshot ?? tree;
        const treeChap = flattenTreeChapters(targetTree).find((c) => c.id === targetChapterId);
        const isMulti =
          treeChap &&
          (treeChap.structureMode === "MULTI_SECTION" || treeChap.sections.length > 1);

        if (isMulti && treeChap.sections.length > 0) {
          const secId =
            preferredSectionId && treeChap.sections.some((s) => s.id === preferredSectionId)
              ? preferredSectionId
              : treeChap.sections[0].id;
          const secDetail = await structureApi.getSection(bookId, targetChapterId, secId);
          setActiveSection(secDetail);
          setDraftTitle(secDetail.title);
          setDraftContent(secDetail.content);
          setDraftStatus(detail.status);
        } else {
          setActiveSection(null);
          setDraftTitle(detail.title);
          setDraftContent(detail.content);
          setDraftStatus(detail.status);
        }
        setSaveState("idle");
      } catch (err) {
        setErrorMsg(err instanceof Error ? err.message : "加载章节失败");
      }
    },
    [bookId, tree],
  );

  useEffect(() => {
    if (authLoading) return;
    if (!user) {
      router.replace("/login");
      return;
    }
    let active = true;
    void Promise.all([
      booksApi.get(bookId),
      structureApi.getTree(bookId),
      runsApi.list(bookId).catch(() => []),
    ])
      .then(async ([bookData, treeData, bookRuns]) => {
        if (!active) return;
        setBook(bookData);
        setTree(treeData);
        setErrorMsg(null);

        const activeRunMap: Record<string, boolean> = {};
        let chapterWithLiveRun: string | null = null;
        for (const r of bookRuns) {
          if (
            r.chapterId &&
            (r.status === "RUNNING" || r.status === "QUEUED" || r.status === "PAUSED")
          ) {
            activeRunMap[r.chapterId] = true;
            if (!chapterWithLiveRun) {
              chapterWithLiveRun = r.chapterId;
            }
          }
        }
        setRunningChapterIds(activeRunMap);

        const allChapters = flattenTreeChapters(treeData);
        if (allChapters.length > 0) {
          const validIds = new Set(allChapters.map((c) => c.id));
          const urlChapterId =
            typeof window !== "undefined"
              ? new URLSearchParams(window.location.search).get("chapterId")
              : null;
          const storedChapterId =
            typeof window !== "undefined"
              ? window.sessionStorage.getItem(activeChapterStorageKey(bookId))
              : null;

          const targetChapterId =
            (urlChapterId && validIds.has(urlChapterId) ? urlChapterId : null) ??
            (storedChapterId && validIds.has(storedChapterId) ? storedChapterId : null) ??
            (chapterWithLiveRun && validIds.has(chapterWithLiveRun) ? chapterWithLiveRun : null) ??
            allChapters[0].id;

          await loadChapterDetail(targetChapterId, null, treeData);
        } else {
          setActiveId(null);
          setActiveChapter(null);
          setActiveSection(null);
          setVersions([]);
          syncActiveChapterLocation(bookId, null);
        }
      })
      .catch((err) => {
        if (!active) return;
        if (err instanceof ApiClientError && err.status === 401) {
          router.replace("/login");
          return;
        }
        setErrorMsg(err instanceof Error ? err.message : "加载作品工作台失败");
      })
      .finally(() => {
        if (active) setLoadingPage(false);
      });
    return () => {
      active = false;
    };
  }, [authLoading, user, router, bookId, loadChapterDetail]);

  const saveCurrentUnit = useCallback(
    async (options?: { createSnapshot?: boolean; changeSummary?: string }) => {
      if (!activeChapter) return;
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
        autoSaveTimerRef.current = null;
      }
      setSaveState("saving");
      setErrorMsg(null);
      setVipLimitError(null);
      try {
        if (isMultiSectionEditing && activeSection) {
          const updatedSec = await structureApi.updateSection(
            bookId,
            activeChapter.id,
            activeSection.id,
            {
              title: draftTitle.trim() || activeSection.title,
              content: draftContent,
              version: activeSection.version,
              createSnapshot: options?.createSnapshot ?? false,
              changeSummary: options?.changeSummary,
            },
          );
          setActiveSection(updatedSec);
          setDraftTitle(updatedSec.title);
          setDraftContent(updatedSec.content);
          await refreshTree();
          setSaveState("saved");
          return;
        }

        const updated = await chaptersApi.update(bookId, activeChapter.id, {
          title: draftTitle.trim() || activeChapter.title,
          content: draftContent,
          status: draftStatus,
          version: activeChapter.version,
          createSnapshot: options?.createSnapshot ?? false,
          changeSummary: options?.changeSummary,
        });
        setActiveChapter(updated);
        setDraftTitle(updated.title);
        setDraftContent(updated.content);
        setDraftStatus(updated.status);
        await refreshTree();
        if (options?.createSnapshot) {
          const versionList = await chaptersApi.listVersions(bookId, updated.id);
          setVersions(versionList);
          setSnapshotSummary("");
        }
        setSaveState("saved");
      } catch (err) {
        if (err instanceof ApiClientError && err.code === "VIP_QUOTA_EXCEEDED") {
          setSaveState("error");
          setVipLimitError(err.message);
        } else if (
          err instanceof ApiClientError &&
          (err.status === 409 || err.code === "VERSION_CONFLICT")
        ) {
          setSaveState("conflict");
          setErrorMsg(err.message);
        } else {
          setSaveState("error");
          setErrorMsg(err instanceof Error ? err.message : "保存失败");
        }
      }
    },
    [
      activeChapter,
      activeSection,
      isMultiSectionEditing,
      bookId,
      draftTitle,
      draftContent,
      draftStatus,
      refreshTree,
    ],
  );

  // Debounced auto-save after 2.5s idle when dirty
  useEffect(() => {
    if (saveState !== "dirty" || !activeChapter) {
      return;
    }
    autoSaveTimerRef.current = setTimeout(() => {
      void saveCurrentUnit();
    }, 2500);
    return () => {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
      }
    };
  }, [saveState, activeChapter, saveCurrentUnit]);

  // Ctrl/Cmd + S manual save shortcut
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "s") {
        e.preventDefault();
        if (activeChapter && saveState !== "conflict") {
          void saveCurrentUnit();
        }
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [activeChapter, saveState, saveCurrentUnit]);

  const handleRunStateChange = useCallback(
    (targetChapterId: string | null, state: StreamConnectionState) => {
      if (!targetChapterId) return;
      const isLive =
        state === "connecting" ||
        state === "streaming" ||
        state === "reconnecting" ||
        state === "paused";
      setRunningChapterIds((prev) => {
        if (Boolean(prev[targetChapterId]) === isLive) return prev;
        return { ...prev, [targetChapterId]: isLive };
      });
    },
    [],
  );

  async function handleCreateChapter(e: FormEvent) {
    e.preventDefault();
    if (!newChapterTitle.trim()) return;
    setCreatingChapter(true);
    setErrorMsg(null);
    try {
      if (activeChapter && saveState === "dirty") {
        await saveCurrentUnit();
      }
      const created = await chaptersApi.create(bookId, {
        title: newChapterTitle.trim(),
        content: "",
      });
      setNewChapterTitle("");
      const latestTree = await refreshTree();
      await loadChapterDetail(created.id, null, latestTree);
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "新建章节失败");
    } finally {
      setCreatingChapter(false);
    }
  }

  async function handleSelectChapter(ch: ChapterTreeItem) {
    if (ch.id === activeId && !activeSection) return;
    if (activeChapter && saveState === "dirty") {
      await saveCurrentUnit();
    }
    await loadChapterDetail(ch.id);
  }

  async function handleSelectSection(ch: ChapterTreeItem, sec: SectionSummaryItem) {
    if (ch.id === activeId && activeSection?.id === sec.id) return;
    if (activeChapter && saveState === "dirty") {
      await saveCurrentUnit();
    }
    await loadChapterDetail(ch.id, sec.id);
  }

  async function handleDeleteChapter(ch: ChapterTreeItem) {
    if (!window.confirm(`确认删除「第 ${ch.chapterNo} 章 ${ch.title}」？`)) {
      return;
    }
    try {
      await chaptersApi.remove(bookId, ch.id);
      const latestTree = await refreshTree();
      const remaining = flattenTreeChapters(latestTree);
      if (activeId === ch.id) {
        if (remaining.length > 0) {
          await loadChapterDetail(remaining[0].id, null, latestTree);
        } else {
          setActiveId(null);
          setActiveChapter(null);
          setActiveSection(null);
          setVersions([]);
          syncActiveChapterLocation(bookId, null);
        }
      }
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "删除章节失败");
    }
  }

  async function handleCreateSection(ch: ChapterTreeItem) {
    const suggested = `第 ${ch.sections.length + 1} 节`;
    const title = window.prompt(`为「第 ${ch.chapterNo} 章 ${ch.title}」新增小节标题：`, suggested);
    if (title === null) return;
    setErrorMsg(null);
    try {
      if (activeChapter && saveState === "dirty") {
        await saveCurrentUnit();
      }
      const created = await structureApi.createSection(bookId, ch.id, {
        title: title.trim() || suggested,
        content: "",
      });
      const latestTree = await refreshTree();
      await loadChapterDetail(ch.id, created.id, latestTree);
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "新增小节失败");
    }
  }

  async function handleDeleteSection(ch: ChapterTreeItem, sec: SectionSummaryItem) {
    if (!window.confirm(`确认删除小节「§${sec.sectionNo} ${sec.title}」？`)) {
      return;
    }
    setErrorMsg(null);
    try {
      await structureApi.deleteSection(bookId, ch.id, sec.id);
      const latestTree = await refreshTree();
      if (activeSection?.id === sec.id) {
        await loadChapterDetail(ch.id, null, latestTree);
      }
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "删除小节失败");
    }
  }

  async function handleOpenFullPreview(ch: ChapterTreeItem) {
    setErrorMsg(null);
    try {
      const preview = await structureApi.getFullPreview(bookId, ch.id);
      setFullPreview(preview);
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "加载整章预览失败");
    }
  }

  async function handleCreateVolume(payload: { title: string; arcGoal: string; realmScope: string }) {
    setErrorMsg(null);
    try {
      await structureApi.createVolume(bookId, payload);
      await refreshTree();
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "创建分卷失败");
    }
  }

  async function handleUpdateVolume(
    volumeId: string,
    payload: { title: string; arcGoal: string; realmScope: string },
  ) {
    setErrorMsg(null);
    try {
      await structureApi.updateVolume(bookId, volumeId, payload);
      await refreshTree();
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "更新分卷失败");
    }
  }

  async function handleManualSnapshot(e: FormEvent) {
    e.preventDefault();
    if (!activeChapter) return;
    await saveCurrentUnit({
      createSnapshot: true,
      changeSummary: snapshotSummary.trim() || "手动版本快照",
    });
  }

  async function handleRollback(ver: ChapterVersion) {
    if (!activeChapter) return;
    if (
      !window.confirm(
        `确认将当前章节回滚到 v${ver.versionNo}（${ver.changeSummary ?? "历史快照"}）？系统会自动保留一条回滚快照记录。`,
      )
    ) {
      return;
    }
    setErrorMsg(null);
    try {
      const updated = await chaptersApi.rollback(
        bookId,
        activeChapter.id,
        ver.id,
        activeChapter.version,
      );
      setActiveChapter(updated);
      setActiveSection(null);
      setDraftTitle(updated.title);
      setDraftContent(updated.content);
      setDraftStatus(updated.status);
      setSaveState("saved");
      setPreviewVersion(null);
      const versionList = await chaptersApi.listVersions(bookId, updated.id);
      setVersions(versionList);
      await refreshTree();
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "回滚失败");
    }
  }

  if (authLoading || loadingPage) {
    return (
      <main className="flex flex-1 items-center justify-center p-12 text-sm text-muted-foreground">
        正在加载创作工作台...
      </main>
    );
  }

  return (
    <div className="relative flex h-screen flex-col overflow-hidden bg-background">
      {/* Top Toolbar */}
      <header className="flex h-14 shrink-0 items-center justify-between gap-4 border-b border-border bg-card px-4">
        <div className="flex min-w-0 items-center gap-3">
          <Link
            href="/books"
            className="text-xs font-medium text-muted-foreground hover:text-foreground"
          >
            ← 书架
          </Link>
          <span className="text-muted-foreground">/</span>
          <h1 className="truncate text-sm font-semibold">
            {book?.title ?? "未命名小说"}
          </h1>
        </div>

        <div className="flex items-center gap-2">
          {activeChapter && (
            <>
              <span className="hidden font-mono text-xs text-muted-foreground md:inline">
                {liveWordCount.toLocaleString()} 字 ·{" "}
                {saveState === "saving"
                  ? "保存中..."
                  : saveState === "dirty"
                    ? "未保存"
                    : saveState === "conflict"
                      ? "版本冲突"
                      : saveState === "saved"
                        ? "已保存"
                        : "就绪"}
              </span>
              {!isMultiSectionEditing && (
                <select
                  value={draftStatus}
                  onChange={(e) => {
                    setDraftStatus(e.target.value);
                    setSaveState("dirty");
                  }}
                  className="rounded-md border border-input bg-background px-2 py-1 text-xs"
                >
                  {Object.entries(CHAPTER_STATUS_LABELS).map(([k, label]) => (
                    <option key={k} value={k}>
                      {label}
                    </option>
                  ))}
                </select>
              )}
              {isMultiSectionEditing && activeTreeChapter && (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => void handleOpenFullPreview(activeTreeChapter)}
                >
                  整章预览
                </Button>
              )}
              {!isMultiSectionEditing && (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => setVersionsOpen(true)}
                >
                  历史版本 ({versions.length})
                </Button>
              )}
              <Button
                size="sm"
                variant="outline"
                disabled={saveState === "saving" || saveState === "conflict"}
                onClick={() => void saveCurrentUnit()}
              >
                保存
              </Button>
            </>
          )}
          <Button
            size="sm"
            variant="ghost"
            onClick={() => setRightPanelOpen((v) => !v)}
          >
            {rightPanelOpen ? "收起助手" : "展开助手"}
          </Button>
        </div>
      </header>

      {/* Main Three-Pane Body */}
      <div className="flex min-h-0 flex-1 overflow-hidden">
        <BookStructureTree
          tree={tree}
          activeChapterId={activeId}
          activeSectionId={activeSection?.id ?? null}
          runningChapterIds={runningChapterIds}
          creatingChapter={creatingChapter}
          newChapterTitle={newChapterTitle}
          onNewChapterTitleChange={setNewChapterTitle}
          onCreateChapter={handleCreateChapter}
          onSelectChapter={(ch) => void handleSelectChapter(ch)}
          onSelectSection={(ch, sec) => void handleSelectSection(ch, sec)}
          onDeleteChapter={(ch) => void handleDeleteChapter(ch)}
          onCreateSection={(ch) => void handleCreateSection(ch)}
          onDeleteSection={(ch, sec) => void handleDeleteSection(ch, sec)}
          onOpenFullPreview={(ch) => void handleOpenFullPreview(ch)}
          onCreateVolume={handleCreateVolume}
          onUpdateVolume={handleUpdateVolume}
        />

        {/* Center Pane: Manuscript Editor */}
        <section className="flex min-w-0 flex-1 flex-col overflow-hidden">
          {vipLimitError && (
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-amber-500/40 bg-amber-500/10 px-4 py-2 text-xs text-amber-800 dark:text-amber-300">
              <span>{vipLimitError}</span>
              <Link
                href="/vip"
                className="rounded bg-amber-600 px-2.5 py-1 font-medium text-white hover:bg-amber-700"
              >
                升级解锁更大单节字数 →
              </Link>
            </div>
          )}

          {errorMsg && (
            <div className="flex items-center justify-between gap-3 border-b border-destructive/40 bg-destructive/10 px-4 py-2 text-xs text-destructive">
              <span>{errorMsg}</span>
              {saveState === "conflict" && activeChapter && (
                <Button
                  size="xs"
                  variant="outline"
                  onClick={() => void loadChapterDetail(activeChapter.id, activeSection?.id)}
                >
                  拉取最新版本
                </Button>
              )}
            </div>
          )}

          {activeChapter && liveWordCount > 8000 && activeTreeChapter && (
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-amber-500/30 bg-amber-500/5 px-4 py-1.5 text-[11px] text-amber-800 dark:text-amber-300">
              <span>
                当前正文已超过 8,000 字（支持最高 10 万字），按场景拆分小节可让 AI 记忆与连贯校对更精准。
              </span>
              <button
                type="button"
                onClick={() => void handleCreateSection(activeTreeChapter)}
                className="font-medium underline hover:opacity-80"
              >
                + 新增下一小节
              </button>
            </div>
          )}

          {activeChapter ? (
            <div className="flex min-h-0 flex-1 flex-col p-6">
              <div className="mb-3 flex items-center gap-3 border-b border-border pb-3">
                <span className="shrink-0 font-mono text-xs text-muted-foreground">
                  {isMultiSectionEditing && activeSection
                    ? `第 ${activeChapter.chapterNo} 章 · 第 ${activeSection.sectionNo} 节`
                    : `第 ${activeChapter.chapterNo} 章`}
                </span>
                <input
                  value={draftTitle}
                  onChange={(e) => {
                    setDraftTitle(e.target.value);
                    if (saveState !== "conflict") setSaveState("dirty");
                  }}
                  placeholder={isMultiSectionEditing ? "请输入小节标题" : "请输入章节标题"}
                  className="min-w-0 flex-1 bg-transparent text-xl font-bold outline-none"
                />
              </div>

              <textarea
                value={draftContent}
                onChange={(e) => {
                  setDraftContent(e.target.value);
                  if (saveState !== "conflict") setSaveState("dirty");
                }}
                placeholder="在此开始撰写正文...（支持自动保存与 Ctrl+S 手动保存）"
                className="min-h-0 flex-1 resize-none bg-transparent text-base leading-8 outline-none"
              />
            </div>
          ) : (
            <div className="flex flex-1 flex-col items-center justify-center p-8 text-center text-sm text-muted-foreground">
              <p>选择左侧章节开始创作，或新建第一章</p>
            </div>
          )}
        </section>

        {/* Right Pane */}
        <aside
          className={
            rightPanelOpen
              ? "flex w-96 shrink-0 flex-col border-l border-border bg-card/40"
              : "hidden"
          }
        >
          <div className="grid grid-cols-2 border-b border-border text-xs font-medium">
            <button
              type="button"
              onClick={() => setRightTab("ai")}
              className={`py-2.5 transition-colors ${
                rightTab === "ai"
                  ? "border-b-2 border-primary text-foreground"
                  : "text-muted-foreground hover:text-foreground"
              }`}
            >
              AI 写作
            </button>
            <button
              type="button"
              onClick={() => setRightTab("lore")}
              className={`py-2.5 transition-colors ${
                rightTab === "lore"
                  ? "border-b-2 border-primary text-foreground"
                  : "text-muted-foreground hover:text-foreground"
              }`}
            >
              故事设定
            </button>
          </div>

          <div className={rightTab === "ai" ? "flex min-h-0 flex-1 flex-col" : "hidden"}>
            <AiCopilotPanel
              bookId={bookId}
              chapterId={activeId}
              sectionId={effectiveSectionId}
              chapterNo={activeChapter?.chapterNo ?? null}
              chapterContent={draftContent}
              onRunStateChange={handleRunStateChange}
              onAppendText={(text) => {
                setDraftContent((prev) =>
                  prev.trim() ? `${prev.replace(/\s+$/, "")}\n\n${text}` : text,
                );
                if (saveState !== "conflict") setSaveState("dirty");
              }}
              onReplaceText={(text) => {
                setDraftContent(text);
                if (saveState !== "conflict") setSaveState("dirty");
              }}
            />
          </div>

          {rightTab === "lore" && <LorePanel bookId={bookId} />}
        </aside>
      </div>

      {/* Full Chapter Preview Modal (for Multi-Section Big Chapters) */}
      {fullPreview && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-6"
          onClick={() => setFullPreview(null)}
        >
          <div
            className="flex max-h-[85vh] w-full max-w-3xl flex-col rounded-xl border border-border bg-card shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between border-b border-border px-5 py-3">
              <div>
                <h2 className="text-sm font-semibold">
                  第 {fullPreview.chapterNo} 章 · {fullPreview.title}（整章预览）
                </h2>
                <p className="text-[11px] text-muted-foreground">
                  共 {fullPreview.sectionCount} 个小节 · {fullPreview.totalWordCount.toLocaleString()} 字
                </p>
              </div>
              <Button size="xs" variant="ghost" onClick={() => setFullPreview(null)}>
                关闭
              </Button>
            </div>
            <div className="flex-1 overflow-y-auto whitespace-pre-wrap p-6 text-sm leading-8">
              {fullPreview.combinedContent || "（本章暂无正文）"}
            </div>
          </div>
        </div>
      )}

      {/* On-Demand Version History Slide-Over Drawer */}
      {versionsOpen && activeChapter && (
        <div
          className="fixed inset-0 z-50 flex justify-end bg-black/30"
          onClick={() => setVersionsOpen(false)}
        >
          <div
            className="flex h-full w-96 max-w-full flex-col border-l border-border bg-card shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between border-b border-border px-4 py-3">
              <div>
                <h2 className="text-sm font-semibold">历史版本</h2>
                <p className="text-[11px] text-muted-foreground">
                  第 {activeChapter.chapterNo} 章 · {activeChapter.title}
                </p>
              </div>
              <Button
                size="xs"
                variant="ghost"
                onClick={() => setVersionsOpen(false)}
              >
                关闭
              </Button>
            </div>

            <form
              onSubmit={handleManualSnapshot}
              className="space-y-2 border-b border-border p-3"
            >
              <input
                value={snapshotSummary}
                onChange={(e) => setSnapshotSummary(e.target.value)}
                placeholder="快照备注，例如：完成初稿 / 改写打斗戏"
                className="w-full rounded-md border border-input bg-background px-2.5 py-1.5 text-xs outline-none focus:ring-1 focus:ring-ring"
              />
              <Button
                type="submit"
                size="xs"
                className="w-full"
                disabled={saveState === "saving" || saveState === "conflict"}
              >
                保存当前正文为历史快照
              </Button>
            </form>

            <div className="flex-1 overflow-y-auto p-3">
              {versions.length === 0 ? (
                <p className="py-8 text-center text-xs text-muted-foreground">
                  暂无历史快照。点击上方按钮保存版本节点，随时支持一键回滚。
                </p>
              ) : (
                <ul className="space-y-2">
                  {versions.map((ver) => (
                    <li
                      key={ver.id}
                      className="rounded-lg border border-border bg-background p-3 text-xs"
                    >
                      <div className="flex items-center justify-between">
                        <span className="font-mono font-semibold">
                          v{ver.versionNo}
                        </span>
                        <span className="font-mono text-[10px] text-muted-foreground">
                          {countWords(ver.content)} 字
                        </span>
                      </div>
                      <p className="mt-1 text-muted-foreground">
                        {ver.changeSummary || "版本快照"}
                      </p>
                      <div className="mt-2 flex items-center gap-1.5">
                        <Button
                          size="xs"
                          variant="outline"
                          onClick={() =>
                            setPreviewVersion(
                              previewVersion?.id === ver.id ? null : ver,
                            )
                          }
                        >
                          {previewVersion?.id === ver.id ? "收起" : "预览"}
                        </Button>
                        <Button
                          size="xs"
                          variant="secondary"
                          onClick={() => void handleRollback(ver)}
                        >
                          回滚到此版本
                        </Button>
                      </div>
                      {previewVersion?.id === ver.id && (
                        <pre className="mt-2 max-h-48 overflow-y-auto whitespace-pre-wrap rounded bg-muted p-2 font-sans text-[11px] leading-relaxed">
                          {ver.content || "（空内容）"}
                        </pre>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default function BookWorkbenchPage() {
  return (
    <Suspense
      fallback={
        <div className="flex min-h-screen items-center justify-center text-sm text-muted-foreground">
          加载创作工作台中...
        </div>
      }
    >
      <BookWorkbenchContent />
    </Suspense>
  );
}
