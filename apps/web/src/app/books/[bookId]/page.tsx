"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import {
  Suspense,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type CSSProperties,
  type FormEvent,
  type PointerEvent as ReactPointerEvent,
} from "react";
import {
  Check,
  ChevronsLeft,
  ChevronsRight,
  Download,
  FileText,
  GitCommitHorizontal,
  History,
  Maximize2,
  Network,
  PanelLeftClose,
  PanelLeftOpen,
  PanelRightClose,
  PanelRightOpen,
  RefreshCw,
} from "lucide-react";
import { useAuth } from "@/components/auth-provider";
import { AiCopilotPanel, type SelectionEditTask } from "@/components/ai-copilot-panel";
import { AiDiffReviewModal } from "@/components/ai-diff-review-modal";
import { BookStructureTree } from "@/components/book-structure-tree";
import { LorePanel } from "@/components/lore-panel";
import { RippleEditDrawer } from "@/components/ripple-edit-drawer";
import { Alert } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { Input } from "@/components/ui/input";
import { usePrompt } from "@/components/ui/prompt-dialog";
import { Select, type SelectOption } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { useToast } from "@/components/ui/toast";
import {
  ApiClientError,
  booksApi,
  chaptersApi,
  runsApi,
  structureApi,
  styleAnchorsApi,
  type BookItem,
  type BookTreeResponse,
  type ChapterDetail,
  type ChapterTreeItem,
  type ChapterVersion,
  type ForeshadowingDirective,
  type FullChapterPreview,
  type SectionDetail,
  type SectionSummaryItem,
  type VisualVisibility,
} from "@/lib/api";
import type { StreamConnectionState } from "@/lib/sse-client";

type WorkbenchView = "editor" | "graph" | "lanes";

const CharacterRelationGraph = dynamic(
  () =>
    import("@/components/visual/character-relation-graph").then(
      (mod) => mod.CharacterRelationGraph,
    ),
  {
    ssr: false,
    loading: () => (
      <div className="flex flex-1 items-center justify-center bg-surface-subtle text-xs text-muted-foreground">
        正在加载人物关系动态图谱...
      </div>
    ),
  },
);

const PlotlineSwimlanes = dynamic(
  () =>
    import("@/components/visual/plotline-swimlanes").then(
      (mod) => mod.PlotlineSwimlanes,
    ),
  {
    ssr: false,
    loading: () => (
      <div className="flex flex-1 items-center justify-center bg-background text-xs text-muted-foreground">
        正在加载剧情支线与伏笔弧线泳道...
      </div>
    ),
  },
);

const CHAPTER_STATUS_LABELS: Record<string, string> = {
  DRAFT: "草稿",
  REVIEWING: "审校中",
  PUBLISHED: "已定稿",
};

const CHAPTER_STATUS_OPTIONS: SelectOption[] = Object.entries(CHAPTER_STATUS_LABELS).map(
  ([value, label]) => ({ value, label }),
);

type WorkbenchDensity = "compact" | "balanced" | "relaxed";

const DENSITY_STORAGE_KEY = "foliarc:workbench_density";
const DENSITY_CHANGE_EVENT = "foliarc-density-change";
const DENSITY_ORDER: readonly WorkbenchDensity[] = ["compact", "balanced", "relaxed"];

const DENSITY_META: Record<
  WorkbenchDensity,
  { label: string; short: string; desc: string; fontSize: string; lineHeight: string }
> = {
  compact: {
    label: "紧凑全宽",
    short: "紧凑",
    desc: "全宽高效排布 · 最大化视野",
    fontSize: "0.96875rem",
    lineHeight: "1.78",
  },
  balanced: {
    label: "标准宽幅",
    short: "标准",
    desc: "居中宽纸面 · 均衡留白",
    fontSize: "1.03125rem",
    lineHeight: "1.88",
  },
  relaxed: {
    label: "舒展沉浸",
    short: "舒展",
    desc: "经典书卷版心 · 宽松行距",
    fontSize: "1.09375rem",
    lineHeight: "1.98",
  },
};

function isValidDensity(value: string | null | undefined): value is WorkbenchDensity {
  return value === "compact" || value === "balanced" || value === "relaxed";
}

function readStoredDensity(): WorkbenchDensity {
  if (typeof window === "undefined") return "compact";
  try {
    const saved = window.localStorage.getItem(DENSITY_STORAGE_KEY);
    if (isValidDensity(saved)) return saved;
  } catch {
    // ignore storage restrictions
  }
  return "compact";
}

function saveDensity(next: WorkbenchDensity) {
  try {
    window.localStorage.setItem(DENSITY_STORAGE_KEY, next);
  } catch {
    // ignore storage restrictions
  }
  window.dispatchEvent(new Event(DENSITY_CHANGE_EVENT));
}

function subscribeDensity(onStoreChange: () => void): () => void {
  const onStorage = (e: StorageEvent) => {
    if (e.key === DENSITY_STORAGE_KEY) onStoreChange();
  };
  window.addEventListener(DENSITY_CHANGE_EVENT, onStoreChange);
  window.addEventListener("storage", onStorage);
  return () => {
    window.removeEventListener(DENSITY_CHANGE_EVENT, onStoreChange);
    window.removeEventListener("storage", onStorage);
  };
}

type EditorFontPreset = "serif" | "kai" | "sans";

interface EditorTypographyPrefs {
  font: EditorFontPreset;
  fontSizePx: number | null;
  lineHeight: number | null;
}

const TYPO_STORAGE_KEY = "foliarc:editor_typography:v1";
const TYPO_CHANGE_EVENT = "foliarc-typography-change";
const DEFAULT_TYPO_PREFS: EditorTypographyPrefs = {
  font: "serif",
  fontSizePx: null,
  lineHeight: null,
};
let cachedTypoRaw: string | null = null;
let cachedTypoVal: EditorTypographyPrefs = DEFAULT_TYPO_PREFS;

const FONT_PRESETS: Record<EditorFontPreset, { label: string; sample: string; stack: string }> = {
  serif: {
    label: "思源宋体",
    sample: "典雅书卷",
    stack: '"Noto Serif SC", "Source Han Serif SC", "Songti SC", "STSong", SimSun, serif',
  },
  kai: {
    label: "霞鹜文楷",
    sample: "温润纸感",
    stack: '"LXGW WenKai", "LXGW WenKai Screen", "Kaiti SC", "STKaiti", KaiTi, serif',
  },
  sans: {
    label: "清爽无衬线",
    sample: "现代利落",
    stack: 'system-ui, -apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans SC", "Microsoft YaHei", sans-serif',
  },
};

function readStoredTypography(): EditorTypographyPrefs {
  if (typeof window === "undefined") return DEFAULT_TYPO_PREFS;
  try {
    const raw = window.localStorage.getItem(TYPO_STORAGE_KEY);
    if (raw === cachedTypoRaw) return cachedTypoVal;
    cachedTypoRaw = raw;
    if (!raw) {
      cachedTypoVal = DEFAULT_TYPO_PREFS;
      return cachedTypoVal;
    }
    const parsed = JSON.parse(raw) as Partial<EditorTypographyPrefs>;
    const font: EditorFontPreset =
      parsed.font === "kai" || parsed.font === "sans" || parsed.font === "serif"
        ? parsed.font
        : "serif";
    const fontSizePx =
      typeof parsed.fontSizePx === "number" && parsed.fontSizePx >= 14 && parsed.fontSizePx <= 22
        ? Math.round(parsed.fontSizePx)
        : null;
    const lineHeight =
      typeof parsed.lineHeight === "number" && parsed.lineHeight >= 1.5 && parsed.lineHeight <= 2.3
        ? Number(parsed.lineHeight.toFixed(2))
        : null;
    cachedTypoVal = { font, fontSizePx, lineHeight };
    return cachedTypoVal;
  } catch {
    return DEFAULT_TYPO_PREFS;
  }
}

function saveTypography(next: EditorTypographyPrefs) {
  try {
    const serialized = JSON.stringify(next);
    window.localStorage.setItem(TYPO_STORAGE_KEY, serialized);
    cachedTypoRaw = serialized;
    cachedTypoVal = next;
  } catch {
    // ignore storage restrictions
  }
  window.dispatchEvent(new Event(TYPO_CHANGE_EVENT));
}

function subscribeTypography(onStoreChange: () => void): () => void {
  const onStorage = (e: StorageEvent) => {
    if (e.key === TYPO_STORAGE_KEY) onStoreChange();
  };
  window.addEventListener(TYPO_CHANGE_EVENT, onStoreChange);
  window.addEventListener("storage", onStorage);
  return () => {
    window.removeEventListener(TYPO_CHANGE_EVENT, onStoreChange);
    window.removeEventListener("storage", onStorage);
  };
}

const RIGHT_WIDTH_STORAGE_KEY = "foliarc:right_panel_width";
const RIGHT_WIDTH_CHANGE_EVENT = "foliarc-right-width-change";
const RIGHT_PANEL_DEFAULT_WIDTH = 440;
const RIGHT_PANEL_WIDE_WIDTH = 580;
const RIGHT_PANEL_MIN_WIDTH = 360;
const RIGHT_PANEL_MAX_WIDTH = 680;
const MIN_CENTER_WIDTH = 440;

function clampRightWidth(value: number): number {
  return Math.max(RIGHT_PANEL_MIN_WIDTH, Math.min(RIGHT_PANEL_MAX_WIDTH, Math.round(value)));
}

function readStoredRightWidth(): number {
  if (typeof window === "undefined") return RIGHT_PANEL_DEFAULT_WIDTH;
  try {
    const raw = window.localStorage.getItem(RIGHT_WIDTH_STORAGE_KEY);
    if (raw) {
      const parsed = Number(raw);
      if (Number.isFinite(parsed) && parsed >= RIGHT_PANEL_MIN_WIDTH && parsed <= RIGHT_PANEL_MAX_WIDTH) {
        return Math.round(parsed);
      }
    }
  } catch {
    // ignore storage restrictions
  }
  return RIGHT_PANEL_DEFAULT_WIDTH;
}

function saveRightWidth(next: number) {
  const clamped = clampRightWidth(next);
  try {
    window.localStorage.setItem(RIGHT_WIDTH_STORAGE_KEY, String(clamped));
  } catch {
    // ignore storage restrictions
  }
  window.dispatchEvent(new Event(RIGHT_WIDTH_CHANGE_EVENT));
}

function subscribeRightWidth(onStoreChange: () => void): () => void {
  const onStorage = (e: StorageEvent) => {
    if (e.key === RIGHT_WIDTH_STORAGE_KEY) onStoreChange();
  };
  window.addEventListener(RIGHT_WIDTH_CHANGE_EVENT, onStoreChange);
  window.addEventListener("storage", onStorage);
  return () => {
    window.removeEventListener(RIGHT_WIDTH_CHANGE_EVENT, onStoreChange);
    window.removeEventListener("storage", onStorage);
  };
}

function readViewportWidth(): number | null {
  return typeof window === "undefined" ? null : window.innerWidth;
}

function subscribeViewportWidth(onStoreChange: () => void): () => void {
  window.addEventListener("resize", onStoreChange);
  return () => window.removeEventListener("resize", onStoreChange);
}

function countWords(text: string): number {
  return text.replace(/\s+/g, "").length;
}

function countParagraphs(text: string): number {
  if (!text.trim()) return 0;
  return text
    .split(/\n+/)
    .map((line) => line.trim())
    .filter(Boolean).length;
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
  const searchParams = useSearchParams();
  const { user, loading: authLoading } = useAuth();
  const confirm = useConfirm();
  const prompt = usePrompt();
  const toast = useToast();

  const [activeView, setActiveView] = useState<WorkbenchView>(() => {
    const v = searchParams.get("view");
    return v === "graph" || v === "lanes" ? v : "editor";
  });
  const [visualVisibility, setVisualVisibility] = useState<VisualVisibility>("AS_OF");
  const [asOfChapterId, setAsOfChapterId] = useState<string | null>(null);
  const [visualRefreshSignal, setVisualRefreshSignal] = useState(0);
  const [highlightedRelationIds, setHighlightedRelationIds] = useState<string[]>([]);
  const [injectedDirective, setInjectedDirective] = useState<{
    nonce: number;
    directive: ForeshadowingDirective;
    label?: string;
  } | null>(null);
  const [injectedInstruction, setInjectedInstruction] = useState<{
    nonce: number;
    text: string;
  } | null>(null);

  const bumpCrossPanelRefresh = useCallback(() => {
    setVisualRefreshSignal((n) => n + 1);
  }, []);

  const switchWorkbenchView = useCallback((nextView: WorkbenchView) => {
    setActiveView(nextView);
    if (typeof window !== "undefined") {
      const url = new URL(window.location.href);
      if (nextView === "editor") {
        url.searchParams.delete("view");
      } else {
        url.searchParams.set("view", nextView);
      }
      window.history.replaceState(null, "", url.pathname + url.search);
    }
  }, []);

  const handleHighlightRelationsInGraph = useCallback(
    (relationIds: string[]) => {
      setHighlightedRelationIds(relationIds);
      switchWorkbenchView("graph");
      toast.info(`已在关系图谱高亮本次 AI 引用的 ${relationIds.length} 条人物关系`);
    },
    [switchWorkbenchView, toast],
  );

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
  const [leftPanelOpen, setLeftPanelOpen] = useState(true);
  const [rightPanelOpen, setRightPanelOpen] = useState(true);
  const [densityMenuOpen, setDensityMenuOpen] = useState(false);

  const density = useSyncExternalStore<WorkbenchDensity>(
    subscribeDensity,
    readStoredDensity,
    () => "compact",
  );
  const typoPrefs = useSyncExternalStore<EditorTypographyPrefs>(
    subscribeTypography,
    readStoredTypography,
    () => DEFAULT_TYPO_PREFS,
  );
  const isCompact = density === "compact";
  const densityMeta = DENSITY_META[density];
  const densityMenuRef = useRef<HTMLDivElement | null>(null);
  const manuscriptRef = useRef<HTMLTextAreaElement | null>(null);

  const storedRightWidth = useSyncExternalStore<number>(
    subscribeRightWidth,
    readStoredRightWidth,
    () => RIGHT_PANEL_DEFAULT_WIDTH,
  );
  const viewportWidth = useSyncExternalStore<number | null>(
    subscribeViewportWidth,
    readViewportWidth,
    () => null,
  );
  const [dragRightWidth, setDragRightWidth] = useState<number | null>(null);
  const [isDraggingRight, setIsDraggingRight] = useState(false);

  const leftSidebarWidth = leftPanelOpen ? (isCompact ? 224 : 240) : 0;
  const maxAllowedRightWidth =
    viewportWidth != null
      ? Math.max(
          RIGHT_PANEL_MIN_WIDTH,
          Math.min(RIGHT_PANEL_MAX_WIDTH, viewportWidth - leftSidebarWidth - MIN_CENTER_WIDTH),
        )
      : RIGHT_PANEL_MAX_WIDTH;
  const effectiveRightWidth = Math.max(
    RIGHT_PANEL_MIN_WIDTH,
    Math.min(maxAllowedRightWidth, dragRightWidth ?? storedRightWidth),
  );
  const isRightWide = effectiveRightWidth >= 530;

  const handleRightResizePointerDown = useCallback(
    (e: ReactPointerEvent<HTMLDivElement>) => {
      if (e.button !== 0) return;
      e.preventDefault();
      const startX = e.clientX;
      const startWidth = effectiveRightWidth;
      let latestWidth = startWidth;

      setIsDraggingRight(true);
      setDragRightWidth(startWidth);

      const prevUserSelect = document.body.style.userSelect;
      const prevCursor = document.body.style.cursor;
      document.body.style.userSelect = "none";
      document.body.style.cursor = "col-resize";

      const onPointerMove = (ev: PointerEvent) => {
        const delta = startX - ev.clientX;
        latestWidth = Math.max(
          RIGHT_PANEL_MIN_WIDTH,
          Math.min(maxAllowedRightWidth, Math.round(startWidth + delta)),
        );
        setDragRightWidth(latestWidth);
      };

      const finish = () => {
        window.removeEventListener("pointermove", onPointerMove);
        window.removeEventListener("pointerup", finish);
        window.removeEventListener("pointercancel", finish);
        document.body.style.userSelect = prevUserSelect;
        document.body.style.cursor = prevCursor;
        setIsDraggingRight(false);
        setDragRightWidth(null);
        saveRightWidth(latestWidth);
      };

      window.addEventListener("pointermove", onPointerMove);
      window.addEventListener("pointerup", finish);
      window.addEventListener("pointercancel", finish);
    },
    [effectiveRightWidth, maxAllowedRightWidth],
  );

  const handleToggleWideRight = useCallback(() => {
    if (isRightWide) {
      saveRightWidth(RIGHT_PANEL_DEFAULT_WIDTH);
      return;
    }
    if (typeof window !== "undefined" && window.innerWidth < 1380 && leftPanelOpen) {
      setLeftPanelOpen(false);
    }
    saveRightWidth(RIGHT_PANEL_WIDE_WIDTH);
  }, [isRightWide, leftPanelOpen]);

  useEffect(() => {
    if (!densityMenuOpen) return;
    function onPointerDown(e: MouseEvent) {
      if (densityMenuRef.current && !densityMenuRef.current.contains(e.target as Node)) {
        setDensityMenuOpen(false);
      }
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") setDensityMenuOpen(false);
    }
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [densityMenuOpen]);

  const [versionsOpen, setVersionsOpen] = useState(false);
  const [rippleOpen, setRippleOpen] = useState(false);
  const [exportMenuOpen, setExportMenuOpen] = useState(false);
  const [exportingFormat, setExportingFormat] = useState<string | null>(null);
  const [versions, setVersions] = useState<ChapterVersion[]>([]);
  const [previewVersion, setPreviewVersion] = useState<ChapterVersion | null>(null);
  const [snapshotSummary, setSnapshotSummary] = useState("");
  const [fullPreview, setFullPreview] = useState<FullChapterPreview | null>(null);
  const [loadingPage, setLoadingPage] = useState(true);
  const [selectedExcerpt, setSelectedExcerpt] = useState("");
  const [selectedRange, setSelectedRange] = useState<{ start: number; end: number } | null>(null);
  const [selectionTask, setSelectionTask] = useState<SelectionEditTask | null>(null);
  const [diffModalState, setDiffModalState] = useState<{
    originalText: string;
    proposedText: string;
    mode: "FULL" | "SELECTION";
    selectionRange?: { start: number; end: number };
  } | null>(null);
  const [anchorRefreshKey, setAnchorRefreshKey] = useState(0);

  const [runningChapterIds, setRunningChapterIds] = useState<Record<string, boolean>>({});
  const autoSaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const treeRef = useRef<BookTreeResponse | null>(null);

  useEffect(() => {
    treeRef.current = tree;
  }, [tree]);

  const liveWordCount = useMemo(() => countWords(draftContent), [draftContent]);
  const liveParagraphCount = useMemo(() => countParagraphs(draftContent), [draftContent]);
  const estimatedReadMinutes = useMemo(
    () => (liveWordCount === 0 ? 0 : Math.max(1, Math.ceil(liveWordCount / 450))),
    [liveWordCount],
  );
  const editorTypographyStyle = useMemo(
    () =>
      ({
        "--editor-font-size":
          typoPrefs.fontSizePx != null ? `${typoPrefs.fontSizePx}px` : densityMeta.fontSize,
        "--editor-line-height":
          typoPrefs.lineHeight != null ? String(typoPrefs.lineHeight) : densityMeta.lineHeight,
        "--type-literary": FONT_PRESETS[typoPrefs.font].stack,
        "--font-editor": FONT_PRESETS[typoPrefs.font].stack,
      }) as CSSProperties,
    [densityMeta.fontSize, densityMeta.lineHeight, typoPrefs],
  );

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
      treeRef.current = next;
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

        const targetTree = treeSnapshot ?? treeRef.current;
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
      structureApi.getTree(bookId),
      runsApi.list(bookId).catch(() => []),
    ])
      .then(async ([bookData, treeData, bookRuns]) => {
        if (!active) return;
        setBook(bookData);
        treeRef.current = treeData;
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
    async (options?: {
      createSnapshot?: boolean;
      changeSummary?: string;
    }): Promise<"saved" | "conflict" | "failed"> => {
      if (!activeChapter) return "saved";
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
          setVisualRefreshSignal((n) => n + 1);
          return "saved";
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
          toast.success("已保存当前正文为历史快照");
        }
        setSaveState("saved");
        setVisualRefreshSignal((n) => n + 1);
        return "saved";
      } catch (err) {
        if (err instanceof ApiClientError && err.code === "VIP_QUOTA_EXCEEDED") {
          setSaveState("error");
          setVipLimitError(err.message);
          return "failed";
        } else if (
          err instanceof ApiClientError &&
          (err.status === 409 || err.code === "VERSION_CONFLICT")
        ) {
          setSaveState("conflict");
          setErrorMsg(err.message);
          return "conflict";
        } else {
          setSaveState("error");
          setErrorMsg(err instanceof Error ? err.message : "保存失败");
          return "failed";
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
      toast,
    ],
  );

  const handleJumpFromVisual = useCallback(
    async (targetChapterId: string, targetSectionId?: string | null) => {
      if (activeChapter && saveState === "dirty") {
        const res = await saveCurrentUnit();
        if (res !== "saved") {
          toast.error("当前章节尚未保存成功，请先处理保存或版本冲突后再跳转");
          switchWorkbenchView("editor");
          return;
        }
      }
      await loadChapterDetail(targetChapterId, targetSectionId ?? null);
      switchWorkbenchView("editor");
    },
    [activeChapter, saveState, saveCurrentUnit, loadChapterDetail, switchWorkbenchView, toast],
  );

  const handleSendDirectiveFromVisual = useCallback(
    (directive: ForeshadowingDirective, label: string) => {
      setInjectedDirective({ nonce: Date.now(), directive, label });
      setRightTab("ai");
      setRightPanelOpen(true);
    },
    [],
  );

  const handleSendPromptFromVisual = useCallback(
    async (promptText: string, targetChapterId?: string | null) => {
      if (targetChapterId && targetChapterId !== activeId) {
        if (activeChapter && saveState === "dirty") {
          const res = await saveCurrentUnit();
          if (res !== "saved") {
            toast.error("当前章节保存失败，已留在正文视图");
            switchWorkbenchView("editor");
            return;
          }
        }
        await loadChapterDetail(targetChapterId);
      }
      setInjectedInstruction({ nonce: Date.now(), text: promptText });
      setRightTab("ai");
      setRightPanelOpen(true);
      switchWorkbenchView("editor");
    },
    [activeId, activeChapter, saveState, saveCurrentUnit, loadChapterDetail, switchWorkbenchView, toast],
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

  // Global Workbench Shortcuts: Ctrl/Cmd+S save, Alt+1/2/3 view switch, Ctrl/Cmd+\ left pane, Ctrl/Cmd+. right pane, Esc close overlays
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "s") {
        e.preventDefault();
        if (activeChapter && saveState !== "conflict") {
          void saveCurrentUnit();
        }
        return;
      }
      if ((e.metaKey || e.ctrlKey) && e.key === "\\") {
        e.preventDefault();
        setLeftPanelOpen((v) => !v);
        return;
      }
      if ((e.metaKey || e.ctrlKey) && e.key === ".") {
        e.preventDefault();
        setRightPanelOpen((v) => !v);
        return;
      }
      if (e.altKey && !e.ctrlKey && !e.metaKey) {
        if (e.key === "1") {
          e.preventDefault();
          switchWorkbenchView("editor");
          return;
        }
        if (e.key === "2") {
          e.preventDefault();
          switchWorkbenchView("graph");
          return;
        }
        if (e.key === "3") {
          e.preventDefault();
          switchWorkbenchView("lanes");
          return;
        }
      }
      if (e.key === "Escape") {
        if (exportMenuOpen) setExportMenuOpen(false);
        if (versionsOpen) setVersionsOpen(false);
        if (fullPreview) setFullPreview(null);
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [
    activeChapter,
    saveState,
    saveCurrentUnit,
    switchWorkbenchView,
    exportMenuOpen,
    versionsOpen,
    fullPreview,
  ]);

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
    const result = await confirm({
      title: `删除「第 ${ch.chapterNo} 章 ${ch.title}」`,
      description: "该章节及其历史快照将被永久删除且无法恢复，确认继续？",
      confirmLabel: "确认删除",
      variant: "destructive",
      onConfirm: async () => {
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
      },
    });
    if (result === "succeeded") {
      toast.success(`已删除「第 ${ch.chapterNo} 章 ${ch.title}」`);
    }
  }

  async function handleCreateSection(ch: ChapterTreeItem) {
    const suggested = `第 ${ch.sections.length + 1} 节`;
    const title = await prompt({
      title: "新增小节",
      description: `为「第 ${ch.chapterNo} 章 ${ch.title}」新增场景小节，便于大章拆分与精准校对。`,
      label: "小节标题",
      defaultValue: suggested,
      submitLabel: "创建小节",
    });
    if (title === null) return;
    setErrorMsg(null);
    try {
      if (activeChapter && saveState === "dirty") {
        await saveCurrentUnit();
      }
      const finalTitle = title.trim() || suggested;
      const created = await structureApi.createSection(bookId, ch.id, {
        title: finalTitle,
        content: "",
      });
      const latestTree = await refreshTree();
      await loadChapterDetail(ch.id, created.id, latestTree);
      toast.success(`已新增小节「${finalTitle}」`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : "新增小节失败";
      setErrorMsg(msg);
      toast.error(msg);
    }
  }

  async function handleDeleteSection(ch: ChapterTreeItem, sec: SectionSummaryItem) {
    const result = await confirm({
      title: `删除小节「§${sec.sectionNo} ${sec.title}」`,
      description: "该小节的正文内容将被删除且无法恢复，确认继续？",
      confirmLabel: "确认删除",
      variant: "destructive",
      onConfirm: async () => {
        await structureApi.deleteSection(bookId, ch.id, sec.id);
        const latestTree = await refreshTree();
        if (activeSection?.id === sec.id) {
          await loadChapterDetail(ch.id, null, latestTree);
        }
      },
    });
    if (result === "succeeded") {
      toast.success(`已删除小节「§${sec.sectionNo} ${sec.title}」`);
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
    const chapterId = activeChapter.id;
    const chapterVersion = activeChapter.version;
    const result = await confirm({
      title: `回滚到 v${ver.versionNo}`,
      description: `确认将当前章节回滚到 v${ver.versionNo}（${ver.changeSummary ?? "历史快照"}）？系统会自动保留一条回滚快照记录。`,
      confirmLabel: "确认回滚",
      variant: "default",
      onConfirm: async () => {
        const updated = await chaptersApi.rollback(
          bookId,
          chapterId,
          ver.id,
          chapterVersion,
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
      },
    });
    if (result === "succeeded") {
      toast.success(`已回滚至历史版本 v${ver.versionNo}`);
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
    <div
      style={editorTypographyStyle}
      className="relative flex h-screen flex-col overflow-hidden bg-background"
    >
      {/* Top Toolbar — airy, hairline, single focal CTA */}
      <header
        className={`flex shrink-0 items-center justify-between gap-3 border-b border-border bg-card px-3 ${
          isCompact ? "h-11" : "h-12"
        }`}
      >
        <div className="flex min-w-0 items-center gap-2">
          <Button
            size="icon-sm"
            variant="ghost"
            title={leftPanelOpen ? "收起目录（沉浸写作）" : "展开目录"}
            aria-label={leftPanelOpen ? "收起目录" : "展开目录"}
            onClick={() => setLeftPanelOpen((v) => !v)}
          >
            {leftPanelOpen ? (
              <PanelLeftClose className="size-4" />
            ) : (
              <PanelLeftOpen className="size-4" />
            )}
          </Button>
          <Link
            href="/books"
            className="text-xs font-medium text-muted-foreground transition-colors hover:text-foreground"
          >
            ← 书架
          </Link>
          <span className="text-border">/</span>
          <h1 className="max-w-44 truncate text-sm font-semibold tracking-tight text-foreground">
            {book?.title ?? "未命名小说"}
          </h1>

          {/* Phase 7A View Switcher Pill: 正文 / 关系图谱 / 剧情泳道 */}
          <div
            role="tablist"
            aria-label="工作台视图切换"
            className="ml-2 inline-flex shrink-0 items-center rounded-lg bg-muted p-0.5"
          >
            {(
              [
                ["editor", "正文", FileText],
                ["graph", "关系图谱", Network],
                ["lanes", "剧情泳道", GitCommitHorizontal],
              ] as const
            ).map(([viewId, label, Icon]) => {
              const active = activeView === viewId;
              return (
                <button
                  key={viewId}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  onClick={() => switchWorkbenchView(viewId)}
                  className={`inline-flex items-center gap-1 rounded-md px-2.5 py-1 text-[11.5px] font-medium transition-all ${
                    active
                      ? "bg-card text-foreground shadow-2xs"
                      : "text-muted-foreground hover:text-foreground"
                  }`}
                >
                  <Icon className={`size-3.5 ${active ? "text-primary" : ""}`} />
                  <span className="hidden sm:inline">{label}</span>
                </button>
              );
            })}
          </div>
        </div>

        <div className="flex items-center gap-1">
          {activeChapter && (
            <>
              {!isMultiSectionEditing && (
                <Select
                  id="workbench-chapter-status"
                  label="章节状态"
                  hideLabel
                  value={draftStatus}
                  options={CHAPTER_STATUS_OPTIONS}
                  onValueChange={(next) => {
                    if (!next) return;
                    setDraftStatus(next);
                    setSaveState("dirty");
                  }}
                  className="w-22 [&_button]:h-7 [&_button]:border-transparent [&_button]:bg-muted [&_button]:px-2.5 [&_button]:hover:bg-muted/80"
                />
              )}
              <span className="mx-1 hidden h-4 w-px bg-border sm:inline-block" />
              {isMultiSectionEditing && activeTreeChapter && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => void handleOpenFullPreview(activeTreeChapter)}
                >
                  整章预览
                </Button>
              )}
              {effectiveSectionId && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => setRippleOpen(true)}
                >
                  <RefreshCw className="size-3.5" />
                  <span className="hidden lg:inline">同步前后文</span>
                </Button>
              )}
              {!isMultiSectionEditing && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => setVersionsOpen(true)}
                >
                  <History className="size-3.5" />
                  <span className="hidden lg:inline">历史版本 ({versions.length})</span>
                </Button>
              )}
            </>
          )}
          {book && (
            <div className="relative">
              <Button
                size="sm"
                variant="ghost"
                disabled={exportingFormat !== null}
                onClick={() => setExportMenuOpen((v) => !v)}
              >
                <Download className="size-3.5" />
                <span className="hidden md:inline">
                  {exportingFormat ? `导出 ${exportingFormat.toUpperCase()}...` : "导出"}
                </span>
              </Button>
              {exportMenuOpen && (
                <div className="absolute right-0 top-9 z-40 w-48 rounded-xl border border-border bg-popover p-1 text-popover-foreground shadow-lg">
                  {(
                    [
                      ["epub", "EPUB 3.0 电子书 (.epub)"],
                      ["md", "Markdown 文稿 (.md)"],
                      ["txt", "纯文本全本 (.txt)"],
                    ] as const
                  ).map(([fmt, label]) => (
                    <button
                      key={fmt}
                      type="button"
                      onClick={() => {
                        setExportMenuOpen(false);
                        setExportingFormat(fmt);
                        setErrorMsg(null);
                        void booksApi
                          .exportBook(book.id, fmt, book.title)
                          .then(() => {
                            toast.success(`已导出 ${fmt.toUpperCase()} 成品`);
                          })
                          .catch((err) => {
                            const msg =
                              err instanceof Error ? err.message : `导出 ${fmt.toUpperCase()} 失败`;
                            setErrorMsg(msg);
                            toast.error(msg);
                          })
                          .finally(() => setExportingFormat(null));
                      }}
                      className="block w-full rounded-lg px-2.5 py-1.5 text-left text-xs transition-colors hover:bg-primary-soft hover:text-primary-soft-foreground"
                    >
                      {label}
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Density & Typography Switcher */}
          <div ref={densityMenuRef} className="relative">
            <Button
              size="sm"
              variant="ghost"
              aria-haspopup="menu"
              aria-expanded={densityMenuOpen}
              title="切换版心密度与正文排版"
              onClick={() => setDensityMenuOpen((v) => !v)}
            >
              <Maximize2 className="size-3.5 text-primary" />
              <span className="hidden sm:inline">{densityMeta.short}</span>
            </Button>
            {densityMenuOpen && (
              <div
                role="menu"
                aria-label="切换版心密度与正文排版"
                className="absolute right-0 top-9 z-50 w-64 space-y-2 rounded-xl border border-border bg-popover p-2 text-popover-foreground shadow-lg"
              >
                <div className="space-y-0.5">
                  <div className="px-2 py-0.5 text-[10px] font-medium text-muted-foreground">
                    版心与密度
                  </div>
                  {DENSITY_ORDER.map((mode) => {
                    const meta = DENSITY_META[mode];
                    const active = density === mode;
                    return (
                      <button
                        key={mode}
                        type="button"
                        role="menuitemradio"
                        aria-checked={active}
                        onClick={() => saveDensity(mode)}
                        className={`flex w-full items-start justify-between gap-2 rounded-lg px-2 py-1.5 text-left transition-colors ${
                          active
                            ? "bg-primary-soft text-primary-soft-foreground"
                            : "text-foreground hover:bg-muted"
                        }`}
                      >
                        <div>
                          <div className="text-xs font-medium">{meta.label}</div>
                          <div className="mt-0.5 text-[10px] opacity-75">{meta.desc}</div>
                        </div>
                        {active && <Check className="mt-0.5 size-3.5 shrink-0 text-primary" />}
                      </button>
                    );
                  })}
                </div>

                <div className="space-y-1.5 border-t border-border pt-2">
                  <div className="px-2 text-[10px] font-medium text-muted-foreground">
                    正文字体（免费商用字体栈）
                  </div>
                  <div className="grid grid-cols-3 gap-1 px-1">
                    {(["serif", "kai", "sans"] as const).map((preset) => {
                      const active = typoPrefs.font === preset;
                      return (
                        <button
                          key={preset}
                          type="button"
                          onClick={() => saveTypography({ ...typoPrefs, font: preset })}
                          className={`rounded-lg border px-1.5 py-1 text-center text-[11px] font-medium transition-colors ${
                            active
                              ? "border-primary bg-primary-soft text-primary-soft-foreground"
                              : "border-border bg-background text-foreground hover:bg-muted"
                          }`}
                        >
                          {FONT_PRESETS[preset].label}
                        </button>
                      );
                    })}
                  </div>
                </div>

                <div className="space-y-1.5 border-t border-border pt-2 px-1">
                  <div className="flex items-center justify-between text-[10.5px]">
                    <span className="text-muted-foreground">字号</span>
                    <div className="flex items-center gap-1">
                      <button
                        type="button"
                        onClick={() =>
                          saveTypography({
                            ...typoPrefs,
                            fontSizePx: Math.max(14, (typoPrefs.fontSizePx ?? 16) - 1),
                          })
                        }
                        className="flex size-6 items-center justify-center rounded border border-border bg-background text-xs hover:bg-muted"
                      >
                        −
                      </button>
                      <span className="w-10 text-center font-mono text-[11px] text-foreground">
                        {typoPrefs.fontSizePx != null ? `${typoPrefs.fontSizePx}px` : "默认"}
                      </span>
                      <button
                        type="button"
                        onClick={() =>
                          saveTypography({
                            ...typoPrefs,
                            fontSizePx: Math.min(22, (typoPrefs.fontSizePx ?? 16) + 1),
                          })
                        }
                        className="flex size-6 items-center justify-center rounded border border-border bg-background text-xs hover:bg-muted"
                      >
                        +
                      </button>
                    </div>
                  </div>
                  <div className="flex items-center justify-between text-[10.5px]">
                    <span className="text-muted-foreground">行距</span>
                    <div className="flex items-center gap-1">
                      {(
                        [
                          [null, "默认"],
                          [1.75, "紧"],
                          [1.92, "适"],
                          [2.1, "宽"],
                        ] as const
                      ).map(([val, lbl]) => {
                        const active = typoPrefs.lineHeight === val;
                        return (
                          <button
                            key={lbl}
                            type="button"
                            onClick={() => saveTypography({ ...typoPrefs, lineHeight: val })}
                            className={`rounded px-1.5 py-0.5 text-[10.5px] ${
                              active
                                ? "bg-primary-soft font-semibold text-primary-soft-foreground"
                                : "border border-border bg-background text-muted-foreground hover:text-foreground"
                            }`}
                          >
                            {lbl}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                  {(typoPrefs.font !== "serif" ||
                    typoPrefs.fontSizePx != null ||
                    typoPrefs.lineHeight != null) && (
                    <button
                      type="button"
                      onClick={() => saveTypography(DEFAULT_TYPO_PREFS)}
                      className="w-full rounded-md py-1 text-center text-[10.5px] text-muted-foreground hover:bg-muted hover:text-foreground"
                    >
                      恢复默认排版
                    </button>
                  )}
                </div>
              </div>
            )}
          </div>

          <ThemeToggle />
          {activeChapter && (
            <Button
              size="sm"
              variant="default"
              disabled={saveState === "saving" || saveState === "conflict"}
              onClick={() => void saveCurrentUnit()}
              className="ml-0.5 px-3"
            >
              保存
            </Button>
          )}
          <span className="mx-1 h-4 w-px bg-border" />
          <Button
            size="sm"
            variant="ghost"
            title={rightPanelOpen ? "收起右侧助手" : "展开右侧助手"}
            onClick={() => setRightPanelOpen((v) => !v)}
          >
            {rightPanelOpen ? (
              <PanelRightClose className="size-4" />
            ) : (
              <PanelRightOpen className="size-4" />
            )}
            <span className="hidden sm:inline">
              {rightPanelOpen ? "收起助手" : "展开助手"}
            </span>
          </Button>
        </div>
      </header>

      {/* Main Three-Pane Body */}
      <div className="flex min-h-0 flex-1 overflow-hidden">
        {leftPanelOpen && (
          <BookStructureTree
            tree={tree}
            activeChapterId={activeId}
            activeSectionId={activeSection?.id ?? null}
            runningChapterIds={runningChapterIds}
            creatingChapter={creatingChapter}
            newChapterTitle={newChapterTitle}
            compact={isCompact}
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
        )}

        {/* Center Pane: Editor / Character Relation Graph / Plotline Swimlanes */}
        <section
          className={`flex min-w-0 flex-1 flex-col overflow-hidden ${
            activeView !== "editor" || isCompact ? "bg-background" : "bg-surface-subtle/55"
          }`}
        >
          {activeView === "graph" ? (
            <CharacterRelationGraph
              bookId={bookId}
              tree={tree}
              activeChapterId={activeId}
              activeSectionId={effectiveSectionId}
              visibility={visualVisibility}
              onVisibilityChange={setVisualVisibility}
              asOfChapterId={asOfChapterId}
              onAsOfChapterIdChange={setAsOfChapterId}
              refreshSignal={visualRefreshSignal}
              highlightedRelationIds={highlightedRelationIds}
              onClearHighlightedRelations={() => setHighlightedRelationIds([])}
              onDataMutated={bumpCrossPanelRefresh}
              onJumpToChapter={(chId, secId) => void handleJumpFromVisual(chId, secId)}
              onSendToDirector={handleSendDirectiveFromVisual}
              onOpenLorePanel={() => {
                setRightTab("lore");
                setRightPanelOpen(true);
              }}
            />
          ) : activeView === "lanes" ? (
            <PlotlineSwimlanes
              bookId={bookId}
              tree={tree}
              activeChapterId={activeId}
              activeSectionId={effectiveSectionId}
              visibility={visualVisibility}
              onVisibilityChange={setVisualVisibility}
              asOfChapterId={asOfChapterId}
              onAsOfChapterIdChange={setAsOfChapterId}
              refreshSignal={visualRefreshSignal}
              onDataMutated={bumpCrossPanelRefresh}
              onJumpToChapter={(chId, secId) => void handleJumpFromVisual(chId, secId)}
              onSendToDirector={handleSendDirectiveFromVisual}
              onSendPromptToAi={(text, chId) => void handleSendPromptFromVisual(text, chId)}
            />
          ) : (
            <>
          {vipLimitError && (
            <Alert
              status="vip"
              announce="assertive"
              className="rounded-none border-x-0 border-t-0"
              action={
                <Link href="/vip" className={buttonVariants({ size: "xs" })}>
                  升级解锁更大单节字数 →
                </Link>
              }
            >
              {vipLimitError}
            </Alert>
          )}

          {errorMsg && (
            <Alert
              status="danger"
              announce="assertive"
              className="rounded-none border-x-0 border-t-0"
              action={
                saveState === "conflict" && activeChapter ? (
                  <Button
                    size="xs"
                    variant="outline"
                    onClick={() => void loadChapterDetail(activeChapter.id, activeSection?.id)}
                  >
                    拉取最新版本
                  </Button>
                ) : undefined
              }
            >
              {errorMsg}
            </Alert>
          )}

          {activeChapter && liveWordCount > 8000 && activeTreeChapter && (
            <Alert
              status="warning"
              className="rounded-none border-x-0 border-t-0 py-1.5 text-[11px]"
              action={
                <Button
                  size="xs"
                  variant="ghost"
                  onClick={() => void handleCreateSection(activeTreeChapter)}
                >
                  + 新增下一小节
                </Button>
              }
            >
              当前正文已超过 8,000 字（支持最高 10 万字），按场景拆分小节可让 AI 记忆与连贯校对更精准。
            </Alert>
          )}

          {activeChapter ? (
            <>
              <div
                onClick={(e) => {
                  if (e.target === e.currentTarget) {
                    manuscriptRef.current?.focus();
                  }
                }}
                className={`flex-1 cursor-text overflow-y-auto ${
                  isCompact
                    ? "px-5 py-3.5 sm:px-7"
                    : density === "balanced"
                      ? "px-5 py-5 sm:px-8"
                      : "px-5 py-6 sm:px-10"
                }`}
              >
                <div
                  onClick={(e) => {
                    if (e.target === e.currentTarget) {
                      manuscriptRef.current?.focus();
                    }
                  }}
                  className={
                    isCompact
                      ? "w-full pb-16"
                      : density === "balanced"
                        ? "mx-auto min-h-full w-full max-w-[940px] rounded-xl border border-border/80 bg-background px-7 py-6 pb-20 shadow-2xs sm:px-10"
                        : "mx-auto min-h-full w-full max-w-[740px] rounded-2xl border border-border/80 bg-background px-8 py-8 pb-28 shadow-2xs sm:px-12"
                  }
                >
                  {isCompact ? (
                    <div className="mb-3 flex items-center gap-2.5 border-b border-border/80 pb-2.5">
                      <span className="shrink-0 rounded-md bg-primary-soft px-2 py-0.5 font-mono text-[11px] font-semibold text-primary-soft-foreground">
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
                        className="min-w-0 flex-1 bg-transparent font-editor text-lg font-bold tracking-tight text-foreground placeholder:text-muted-foreground/50 outline-none"
                      />
                    </div>
                  ) : (
                    <div className="mb-6 border-b border-border/80 pb-3.5">
                      <div className="mb-1 font-mono text-xs font-semibold tracking-wider text-primary">
                        {isMultiSectionEditing && activeSection
                          ? `第 ${activeChapter.chapterNo} 章 · 第 ${activeSection.sectionNo} 节`
                          : `第 ${activeChapter.chapterNo} 章`}
                      </div>
                      <input
                        value={draftTitle}
                        onChange={(e) => {
                          setDraftTitle(e.target.value);
                          if (saveState !== "conflict") setSaveState("dirty");
                        }}
                        placeholder={isMultiSectionEditing ? "请输入小节标题" : "请输入章节标题"}
                        className={`w-full bg-transparent font-editor font-bold tracking-tight text-foreground placeholder:text-muted-foreground/50 outline-none ${
                          density === "balanced" ? "text-xl sm:text-2xl" : "text-2xl sm:text-[26px]"
                        }`}
                      />
                    </div>
                  )}

                  {selectedExcerpt.length >= 10 && selectedRange && (
                    <div className="sticky top-2 z-20 mb-4 flex flex-wrap items-center justify-between gap-2 rounded-xl border border-border bg-card/95 px-3 py-1.5 text-xs text-foreground shadow-sm backdrop-blur-md">
                      <span className="truncate font-medium text-primary">
                        已划选 {selectedExcerpt.length} 字片段
                      </span>
                      <div className="flex flex-wrap items-center gap-1.5">
                        <Button
                          size="xs"
                          onClick={() => {
                            setSelectionTask({
                              mode: "REWRITE",
                              text: selectedExcerpt,
                              start: selectedRange.start,
                              end: selectedRange.end,
                            });
                            setRightTab("ai");
                            setRightPanelOpen(true);
                          }}
                        >
                          AI 划词改写
                        </Button>
                        <Button
                          size="xs"
                          variant="secondary"
                          onClick={() => {
                            setSelectionTask({
                              mode: "EXPAND",
                              text: selectedExcerpt,
                              start: selectedRange.start,
                              end: selectedRange.end,
                            });
                            setRightTab("ai");
                            setRightPanelOpen(true);
                          }}
                        >
                          AI 划词扩写
                        </Button>
                        {selectedExcerpt.length >= 20 && selectedExcerpt.length <= 900 && (
                          <Button
                            size="xs"
                            variant="ghost"
                            onClick={() => {
                              const titleLabel = activeChapter
                                ? `第${activeChapter.chapterNo}章片段`
                                : "正文选段";
                              void styleAnchorsApi
                                .upsert(bookId, {
                                  title: titleLabel,
                                  excerptText: selectedExcerpt,
                                  enabled: true,
                                })
                                .then(() => {
                                  setSelectedExcerpt("");
                                  setSelectedRange(null);
                                  setAnchorRefreshKey((k) => k + 1);
                                  toast.success("已收藏为本书文风锚点，AI 后续创作将对齐该段句式与质感。");
                                })
                                .catch((err) => {
                                  const msg =
                                    err instanceof Error ? err.message : "设为文风锚点失败";
                                  setErrorMsg(msg);
                                  toast.error(msg);
                                });
                            }}
                          >
                            设为文风锚点
                          </Button>
                        )}
                        <Button
                          size="xs"
                          variant="ghost"
                          onClick={() => {
                            setSelectedExcerpt("");
                            setSelectedRange(null);
                          }}
                          title="清除选中条"
                        >
                          ×
                        </Button>
                      </div>
                    </div>
                  )}

                  <Textarea
                    ref={manuscriptRef}
                    variant="manuscript"
                    value={draftContent}
                    onSelect={(e) => {
                      const el = e.currentTarget;
                      const start = el.selectionStart;
                      const end = el.selectionEnd;
                      const chunk = el.value.slice(start, end).trim();
                      if (chunk.length >= 10) {
                        setSelectedExcerpt(chunk);
                        setSelectedRange({ start, end });
                      } else {
                        setSelectedExcerpt("");
                        setSelectedRange(null);
                      }
                    }}
                    onChange={(e) => {
                      setDraftContent(e.target.value);
                      if (saveState !== "conflict") setSaveState("dirty");
                    }}
                    placeholder={
                      isMultiSectionEditing
                        ? "从这里写下本节的场景与对白……"
                        : "从这里落笔本章正文，划选任意段落可唤起 AI 润色与扩写……"
                    }
                  />
                </div>
              </div>

              {/* Docked Antigravity-Style Editor Status Bar */}
              <div className="flex h-7 shrink-0 items-center justify-between gap-3 border-t border-border/80 bg-surface-subtle/80 px-4 text-[11px] text-muted-foreground">
                <div className="flex min-w-0 items-center gap-2.5 font-mono">
                  <span className="inline-flex items-center gap-1.5">
                    <span
                      className={`inline-block size-1.5 rounded-full ${
                        saveState === "saving"
                          ? "animate-pulse bg-status-warning"
                          : saveState === "dirty"
                            ? "bg-status-warning"
                            : saveState === "conflict" || saveState === "error"
                              ? "bg-status-danger"
                              : "bg-primary"
                      }`}
                    />
                    <span>
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
                  </span>
                  <span className="text-border">|</span>
                  <span>{liveWordCount.toLocaleString()} 字</span>
                  <span className="hidden text-border sm:inline">·</span>
                  <span className="hidden sm:inline">{liveParagraphCount} 段</span>
                  {estimatedReadMinutes > 0 && (
                    <>
                      <span className="hidden text-border md:inline">·</span>
                      <span className="hidden md:inline">约 {estimatedReadMinutes} 分钟阅读</span>
                    </>
                  )}
                  {selectedExcerpt.length > 0 && (
                    <>
                      <span className="text-border">|</span>
                      <span className="text-primary">已选 {selectedExcerpt.length} 字</span>
                    </>
                  )}
                </div>

                <div className="flex shrink-0 items-center gap-2.5">
                  <button
                    type="button"
                    onClick={() => {
                      const nextIdx = (DENSITY_ORDER.indexOf(density) + 1) % DENSITY_ORDER.length;
                      saveDensity(DENSITY_ORDER[nextIdx]);
                    }}
                    title="点击切换布局密度（紧凑全宽 / 标准宽幅 / 舒展沉浸）"
                    className="rounded px-1.5 py-0.5 font-medium text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                  >
                    {densityMeta.label}
                  </button>
                  <span className="hidden text-border sm:inline">·</span>
                  <span className="hidden font-mono text-[10.5px] sm:inline">
                    Ctrl+S 保存 · Alt+1/2/3 切视图
                  </span>
                </div>
              </div>
            </>
          ) : (
            <div className="flex flex-1 flex-col items-center justify-center p-8 text-center text-sm text-muted-foreground">
              <p>选择左侧章节开始创作，或新建第一章</p>
            </div>
          )}
            </>
          )}
        </section>

        {/* Right Pane Resize Handle */}
        {rightPanelOpen && (
          <div
            role="separator"
            aria-orientation="vertical"
            aria-label="拖拽调节右侧助手宽度（双击恢复默认）"
            title="拖拽调节宽度 · 双击恢复默认"
            onPointerDown={handleRightResizePointerDown}
            onDoubleClick={() => saveRightWidth(RIGHT_PANEL_DEFAULT_WIDTH)}
            className="group relative z-20 -ml-[3px] flex w-1.5 shrink-0 cursor-col-resize items-stretch justify-center select-none"
          >
            <span
              className={`w-px transition-colors ${
                isDraggingRight
                  ? "bg-primary"
                  : "bg-transparent group-hover:bg-primary/60"
              }`}
            />
          </div>
        )}

        {/* Right Pane */}
        <aside
          style={rightPanelOpen ? { width: `${effectiveRightWidth}px` } : undefined}
          className={
            rightPanelOpen
              ? `flex shrink-0 flex-col border-l border-border bg-surface-subtle ${
                  isDraggingRight ? "" : "transition-[width] duration-150"
                }`
              : "hidden"
          }
        >
          <div className="flex items-center border-b border-border bg-card pl-3 pr-2 text-xs font-medium">
            <div className="grid flex-1 grid-cols-2">
              <button
                type="button"
                onClick={() => setRightTab("ai")}
                className={`relative transition-colors ${isCompact ? "py-2.5" : "py-3"} ${
                  rightTab === "ai"
                    ? "font-semibold text-foreground after:absolute after:inset-x-6 after:bottom-0 after:h-0.5 after:rounded-full after:bg-primary"
                    : "text-muted-foreground hover:text-foreground"
                }`}
              >
                AI 写作
              </button>
              <button
                type="button"
                onClick={() => setRightTab("lore")}
                className={`relative transition-colors ${isCompact ? "py-2.5" : "py-3"} ${
                  rightTab === "lore"
                    ? "font-semibold text-foreground after:absolute after:inset-x-6 after:bottom-0 after:h-0.5 after:rounded-full after:bg-primary"
                    : "text-muted-foreground hover:text-foreground"
                }`}
              >
                故事设定
              </button>
            </div>
            <button
              type="button"
              onClick={handleToggleWideRight}
              title={isRightWide ? "还原标准宽度 (440px)" : "加宽右侧面板 (580px)"}
              aria-label={isRightWide ? "还原标准宽度" : "加宽右侧面板"}
              className="ml-1 flex size-7 shrink-0 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            >
              {isRightWide ? (
                <ChevronsRight className="size-3.5" />
              ) : (
                <ChevronsLeft className="size-3.5" />
              )}
            </button>
          </div>

          <div className={rightTab === "ai" ? "flex min-h-0 flex-1 flex-col" : "hidden"}>
            <AiCopilotPanel
              bookId={bookId}
              chapterId={activeId}
              sectionId={effectiveSectionId}
              chapterNo={activeChapter?.chapterNo ?? null}
              chapterContent={draftContent}
              compact={isCompact}
              visibility={visualVisibility}
              visualRefreshSignal={visualRefreshSignal}
              selectionTask={selectionTask}
              onClearSelectionTask={() => setSelectionTask(null)}
              onHighlightRelationsInGraph={handleHighlightRelationsInGraph}
              injectedDirective={injectedDirective}
              injectedInstruction={injectedInstruction}
              onReviewDiff={(payload) => setDiffModalState(payload)}
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

          {rightTab === "lore" && (
            <LorePanel
              bookId={bookId}
              bookGenreCode={book?.genreCode}
              anchorRefreshKey={anchorRefreshKey}
              refreshSignal={visualRefreshSignal}
              onDataMutated={bumpCrossPanelRefresh}
            />
          )}
        </aside>
      </div>

      {diffModalState && (
        <AiDiffReviewModal
          open={Boolean(diffModalState)}
          title={
            diffModalState.mode === "SELECTION"
              ? "划词改写 / 扩写 · Git 式逐段 Diff 审阅"
              : "AI 正文替换 · Git 式逐段 Diff 审阅"
          }
          subtitle={
            diffModalState.mode === "SELECTION"
              ? "仅替换你在编辑器中划选的局部片段，未选中正文保持原封不动"
              : "按段对比当前正文与 AI 建议稿，可逐段 Accept 采纳或 Reject 保留原文"
          }
          originalText={diffModalState.originalText}
          proposedText={diffModalState.proposedText}
          onClose={() => setDiffModalState(null)}
          onApply={(mergedText) => {
            if (diffModalState.mode === "SELECTION" && diffModalState.selectionRange) {
              const { start, end } = diffModalState.selectionRange;
              setDraftContent(
                (prev) => prev.slice(0, start) + mergedText + prev.slice(end),
              );
              setSelectionTask(null);
              setSelectedExcerpt("");
              setSelectedRange(null);
            } else {
              setDraftContent(mergedText);
            }
            if (saveState !== "conflict") setSaveState("dirty");
          }}
        />
      )}

      {/* Full Chapter Preview Modal (for Multi-Section Big Chapters) */}
      {fullPreview && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-6 backdrop-blur-[1px]"
          onClick={() => setFullPreview(null)}
        >
          <div
            className="flex max-h-[85vh] w-full max-w-3xl flex-col rounded-xl border border-border bg-surface-elevated shadow-2xl"
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
            <div className="flex-1 overflow-y-auto whitespace-pre-wrap p-6 font-editor text-editor text-foreground">
              {fullPreview.combinedContent || "（本章暂无正文）"}
            </div>
          </div>
        </div>
      )}

      {activeChapter && effectiveSectionId && (
        <RippleEditDrawer
          open={rippleOpen}
          bookId={bookId}
          sectionId={effectiveSectionId}
          chapterNo={activeChapter.chapterNo}
          chapterTitle={activeChapter.title}
          currentContent={draftContent}
          tree={tree}
          versions={versions}
          onClose={() => setRippleOpen(false)}
          onAppliedSections={(appliedIds) => {
            void refreshTree();
            bumpCrossPanelRefresh();
            if (effectiveSectionId && appliedIds.includes(effectiveSectionId)) {
              void loadChapterDetail(activeChapter.id, activeSection?.id);
            }
          }}
        />
      )}

      {/* On-Demand Version History Slide-Over Drawer */}
      {versionsOpen && activeChapter && (
        <div
          className="fixed inset-0 z-50 flex justify-end bg-black/40 backdrop-blur-[1px]"
          onClick={() => setVersionsOpen(false)}
        >
          <div
            className="flex h-full w-96 max-w-full flex-col border-l border-border bg-surface-elevated shadow-2xl"
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
              <Input
                value={snapshotSummary}
                onChange={(e) => setSnapshotSummary(e.target.value)}
                placeholder="快照备注，例如：完成初稿 / 改写打斗戏"
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
