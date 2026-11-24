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
import { LorePanel } from "@/components/lore-panel";
import { Button } from "@/components/ui/button";
import {
  ApiClientError,
  booksApi,
  chaptersApi,
  runsApi,
  type BookItem,
  type ChapterDetail,
  type ChapterSummary,
  type ChapterVersion,
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

function BookWorkbenchContent() {
  const params = useParams<{ bookId: string }>();
  const bookId = params.bookId;
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();

  const [book, setBook] = useState<BookItem | null>(null);
  const [chapters, setChapters] = useState<ChapterSummary[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [activeChapter, setActiveChapter] = useState<ChapterDetail | null>(null);

  const [draftTitle, setDraftTitle] = useState("");
  const [draftContent, setDraftContent] = useState("");
  const [draftStatus, setDraftStatus] = useState("DRAFT");

  const [saveState, setSaveState] = useState<
    "idle" | "dirty" | "saving" | "saved" | "conflict" | "error"
  >("idle");
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const [newChapterTitle, setNewChapterTitle] = useState("");
  const [creatingChapter, setCreatingChapter] = useState(false);

  // Streamlined right pane: ONLY 2 focused writer tabs (AI 写作 / 故事设定)
  const [rightTab, setRightTab] = useState<"ai" | "lore">("ai");
  const [rightPanelOpen, setRightPanelOpen] = useState(true);

  // Version history drawer (opened on demand from top toolbar instead of occupying a permanent sidebar tab)
  const [versionsOpen, setVersionsOpen] = useState(false);
  const [versions, setVersions] = useState<ChapterVersion[]>([]);
  const [previewVersion, setPreviewVersion] = useState<ChapterVersion | null>(null);
  const [snapshotSummary, setSnapshotSummary] = useState("");
  const [loadingPage, setLoadingPage] = useState(true);

  // Track which chapters currently have an active AI generation
  const [runningChapterIds, setRunningChapterIds] = useState<Record<string, boolean>>({});

  const autoSaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const liveWordCount = useMemo(() => countWords(draftContent), [draftContent]);

  const loadChapterDetail = useCallback(
    async (targetChapterId: string) => {
      setErrorMsg(null);
      try {
        const [detail, versionList] = await Promise.all([
          chaptersApi.get(bookId, targetChapterId),
          chaptersApi.listVersions(bookId, targetChapterId),
        ]);
        setActiveId(detail.id);
        setActiveChapter(detail);
        setDraftTitle(detail.title);
        setDraftContent(detail.content);
        setDraftStatus(detail.status);
        setVersions(versionList);
        setPreviewVersion(null);
        setSaveState("idle");
        syncActiveChapterLocation(bookId, detail.id);
      } catch (err) {
        setErrorMsg(err instanceof Error ? err.message : "加载章节失败");
      }
    },
    [bookId],
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
      chaptersApi.list(bookId),
      runsApi.list(bookId).catch(() => []),
    ])
      .then(async ([bookData, chapterList, bookRuns]) => {
        if (!active) return;
        setBook(bookData);
        setChapters(chapterList);
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

        if (chapterList.length > 0) {
          // Restore the chapter the author was working on across page refresh:
          // 1) URL ?chapterId=  2) sessionStorage active chapter  3) chapter with active AI run  4) first chapter
          const validIds = new Set(chapterList.map((c) => c.id));
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
            chapterList[0].id;

          await loadChapterDetail(targetChapterId);
        } else {
          setActiveId(null);
          setActiveChapter(null);
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

  const saveCurrentChapter = useCallback(
    async (options?: { createSnapshot?: boolean; changeSummary?: string }) => {
      if (!activeChapter) return;
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
        autoSaveTimerRef.current = null;
      }
      setSaveState("saving");
      setErrorMsg(null);
      try {
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
        setChapters((prev) =>
          prev.map((c) =>
            c.id === updated.id
              ? {
                  ...c,
                  title: updated.title,
                  wordCount: updated.wordCount,
                  status: updated.status,
                  version: updated.version,
                  updatedAt: updated.updatedAt,
                }
              : c,
          ),
        );
        if (options?.createSnapshot) {
          const versionList = await chaptersApi.listVersions(bookId, updated.id);
          setVersions(versionList);
          setSnapshotSummary("");
        }
        setSaveState("saved");
      } catch (err) {
        if (
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
    [activeChapter, bookId, draftTitle, draftContent, draftStatus],
  );

  // Debounced auto-save after 2.5s idle when dirty
  useEffect(() => {
    if (saveState !== "dirty" || !activeChapter) {
      return;
    }
    autoSaveTimerRef.current = setTimeout(() => {
      void saveCurrentChapter();
    }, 2500);
    return () => {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
      }
    };
  }, [saveState, activeChapter, saveCurrentChapter]);

  // Ctrl/Cmd + S manual save shortcut
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "s") {
        e.preventDefault();
        if (activeChapter && saveState !== "conflict") {
          void saveCurrentChapter();
        }
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [activeChapter, saveState, saveCurrentChapter]);

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
      // Save any pending edits on the current chapter first so Chapter N+1 immediately sees its latest ending!
      if (activeChapter && saveState === "dirty") {
        await saveCurrentChapter();
      }
      const created = await chaptersApi.create(bookId, {
        title: newChapterTitle.trim(),
        content: "",
      });
      setChapters((prev) => [
        ...prev,
        {
          id: created.id,
          bookId: created.bookId,
          chapterNo: created.chapterNo,
          title: created.title,
          wordCount: created.wordCount,
          status: created.status,
          version: created.version,
          createdAt: created.createdAt,
          updatedAt: created.updatedAt,
        },
      ]);
      setNewChapterTitle("");
      await loadChapterDetail(created.id);
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "新建章节失败");
    } finally {
      setCreatingChapter(false);
    }
  }

  async function handleSelectChapter(targetChapterId: string) {
    if (targetChapterId === activeId) return;
    if (activeChapter && saveState === "dirty") {
      await saveCurrentChapter();
    }
    await loadChapterDetail(targetChapterId);
  }

  async function handleDeleteChapter(chapter: ChapterSummary) {
    if (!window.confirm(`确认删除「第 ${chapter.chapterNo} 章 ${chapter.title}」？`)) {
      return;
    }
    try {
      await chaptersApi.remove(bookId, chapter.id);
      const remaining = chapters.filter((c) => c.id !== chapter.id);
      setChapters(remaining);
      if (activeId === chapter.id) {
        if (remaining.length > 0) {
          await loadChapterDetail(remaining[0].id);
        } else {
          setActiveId(null);
          setActiveChapter(null);
          setVersions([]);
          syncActiveChapterLocation(bookId, null);
        }
      }
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : "删除章节失败");
    }
  }

  async function handleManualSnapshot(e: FormEvent) {
    e.preventDefault();
    if (!activeChapter) return;
    await saveCurrentChapter({
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
      setDraftTitle(updated.title);
      setDraftContent(updated.content);
      setDraftStatus(updated.status);
      setSaveState("saved");
      setPreviewVersion(null);
      const versionList = await chaptersApi.listVersions(bookId, updated.id);
      setVersions(versionList);
      setChapters((prev) =>
        prev.map((c) =>
          c.id === updated.id
            ? { ...c, wordCount: updated.wordCount, version: updated.version }
            : c,
        ),
      );
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
              <Button
                size="sm"
                variant="outline"
                onClick={() => setVersionsOpen(true)}
              >
                历史版本 ({versions.length})
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={saveState === "saving" || saveState === "conflict"}
                onClick={() => void saveCurrentChapter()}
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
        {/* Left Pane: Chapter Tree */}
        <aside className="flex w-60 shrink-0 flex-col border-r border-border bg-card/50">
          <div className="border-b border-border p-3">
            <form onSubmit={handleCreateChapter} className="flex gap-1.5">
              <input
                value={newChapterTitle}
                onChange={(e) => setNewChapterTitle(e.target.value)}
                placeholder="新章节标题..."
                className="min-w-0 flex-1 rounded-md border border-input bg-background px-2.5 py-1 text-xs outline-none focus:ring-1 focus:ring-ring"
              />
              <Button type="submit" size="xs" disabled={creatingChapter}>
                + 新增
              </Button>
            </form>
          </div>

          <div className="flex-1 overflow-y-auto p-2">
            {chapters.length === 0 ? (
              <p className="p-4 text-center text-xs text-muted-foreground">
                暂无章节，在上方输入标题创建第一章
              </p>
            ) : (
              <ul className="space-y-1">
                {chapters.map((ch) => {
                  const isActive = ch.id === activeId;
                  const isGenerating = Boolean(runningChapterIds[ch.id]);
                  return (
                    <li
                      key={ch.id}
                      className={`group flex items-center justify-between rounded-md px-2.5 py-2 text-xs transition-colors ${
                        isActive
                          ? "bg-primary text-primary-foreground"
                          : "hover:bg-muted"
                      }`}
                    >
                      <button
                        type="button"
                        onClick={() => void handleSelectChapter(ch.id)}
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
                                isActive ? "bg-primary-foreground" : "bg-emerald-500"
                              }`}
                            />
                          )}
                        </div>
                        <div
                          className={`mt-0.5 font-mono text-[10px] ${
                            isActive
                              ? "text-primary-foreground/80"
                              : "text-muted-foreground"
                          }`}
                        >
                          {ch.wordCount} 字 · {CHAPTER_STATUS_LABELS[ch.status] ?? ch.status}
                        </div>
                      </button>
                      <button
                        type="button"
                        title="删除章节"
                        onClick={() => void handleDeleteChapter(ch)}
                        className={`ml-1 rounded px-1 py-0.5 text-[11px] opacity-0 transition-opacity group-hover:opacity-100 ${
                          isActive
                            ? "hover:bg-primary-foreground/20"
                            : "text-destructive hover:bg-destructive/10"
                        }`}
                      >
                        ×
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </aside>

        {/* Center Pane: Manuscript Editor */}
        <section className="flex min-w-0 flex-1 flex-col overflow-hidden">
          {errorMsg && (
            <div className="flex items-center justify-between gap-3 border-b border-destructive/40 bg-destructive/10 px-4 py-2 text-xs text-destructive">
              <span>{errorMsg}</span>
              {saveState === "conflict" && activeChapter && (
                <Button
                  size="xs"
                  variant="outline"
                  onClick={() => void loadChapterDetail(activeChapter.id)}
                >
                  拉取最新版本
                </Button>
              )}
            </div>
          )}

          {activeChapter ? (
            <div className="flex min-h-0 flex-1 flex-col p-6">
              <div className="mb-3 flex items-center gap-3 border-b border-border pb-3">
                <span className="shrink-0 font-mono text-xs text-muted-foreground">
                  第 {activeChapter.chapterNo} 章
                </span>
                <input
                  value={draftTitle}
                  onChange={(e) => {
                    setDraftTitle(e.target.value);
                    if (saveState !== "conflict") setSaveState("dirty");
                  }}
                  placeholder="请输入章节标题"
                  className="min-w-0 flex-1 bg-transparent text-xl font-bold outline-none"
                />
              </div>

              <textarea
                value={draftContent}
                onChange={(e) => {
                  setDraftContent(e.target.value);
                  if (saveState !== "conflict") setSaveState("dirty");
                }}
                placeholder="在此开始撰写章节正文...（支持自动保存与 Ctrl+S 手动保存）"
                className="min-h-0 flex-1 resize-none bg-transparent text-base leading-8 outline-none"
              />
            </div>
          ) : (
            <div className="flex flex-1 flex-col items-center justify-center p-8 text-center text-sm text-muted-foreground">
              <p>选择左侧章节开始创作，或新建第一章</p>
            </div>
          )}
        </section>

        {/* Right Pane: Streamlined to 2 Writer-Focused Tabs (AI 写作 / 故事设定); kept mounted when collapsed */}
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

          {/* Keep AiCopilotPanel mounted across tab switches so active streaming never disconnects */}
          <div className={rightTab === "ai" ? "flex min-h-0 flex-1 flex-col" : "hidden"}>
            <AiCopilotPanel
              bookId={bookId}
              chapterId={activeId}
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
