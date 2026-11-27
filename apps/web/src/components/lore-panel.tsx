"use client";

import Link from "next/link";
import { useEffect, useMemo, useState, type FormEvent } from "react";
import {
  BookOpen,
  Bookmark,
  Eye,
  EyeOff,
  Globe,
  Lock,
  Pencil,
  Plus,
  Search,
  Sparkles,
  Trash2,
  Unlock,
  Users,
  X,
} from "lucide-react";
import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { useToast } from "@/components/ui/toast";
import {
  ApiClientError,
  booksApi,
  genresApi,
  libraryApi,
  loreApi,
  memoryProposalsApi,
  styleAnchorsApi,
  type BookCharterItem,
  type CharacterItem,
  type ForeshadowingItem,
  type GenreCharacterField,
  type GenreProfileItem,
  type LibraryDocumentItem,
  type MemoryProposalItem,
  type StyleAnchorItem,
  type WorldEntryItem,
} from "@/lib/api";

const ROLE_OPTIONS = [
  { value: "PROTAGONIST", label: "主角" },
  { value: "SUPPORTING", label: "配角" },
  { value: "ANTAGONIST", label: "反派" },
] as const;

const ROLE_META: Record<string, { label: string; badge: string; avatar: string }> = {
  PROTAGONIST: {
    label: "主角",
    badge: "border-primary/35 bg-primary-soft text-primary-soft-foreground",
    avatar: "bg-primary-soft text-primary-soft-foreground border-primary/35",
  },
  SUPPORTING: {
    label: "配角",
    badge: "border-status-info-border bg-status-info-bg text-status-info",
    avatar: "bg-status-info-bg text-status-info border-status-info-border",
  },
  ANTAGONIST: {
    label: "反派",
    badge: "border-status-danger-border bg-status-danger-bg text-status-danger",
    avatar: "bg-status-danger-bg text-status-danger border-status-danger-border",
  },
};

const LIFECYCLE_LABELS: Record<string, { label: string; className: string }> = {
  CORE_PINNED: {
    label: "常驻核心",
    className: "border border-status-success-border bg-status-success-bg text-status-success",
  },
  ACTIVE: {
    label: "活跃",
    className: "border border-border bg-surface-subtle text-muted-foreground",
  },
  DORMANT: {
    label: "休眠",
    className: "border border-status-warning-border bg-status-warning-bg text-status-warning",
  },
  DEAD: {
    label: "已退场",
    className: "border border-status-danger-border bg-status-danger-bg text-status-danger",
  },
};

const WORLD_CATEGORIES = [
  { value: "WORLD", label: "世界观" },
  { value: "POWER_SYSTEM", label: "力量体系" },
  { value: "FACTION", label: "宗门势力" },
  { value: "ITEM", label: "器物法宝" },
  { value: "LOCATION", label: "地标秘境" },
  { value: "RULE", label: "铁律禁忌" },
] as const;

const WORLD_CATEGORY_LABELS: Record<string, string> = Object.fromEntries(
  WORLD_CATEGORIES.map((c) => [c.value, c.label]),
);

const FORESHADOW_STATUS_META: Record<string, { label: string; className: string }> = {
  PLANNED: { label: "待埋设", className: "border border-border bg-muted text-muted-foreground" },
  PLANTED: { label: "已埋设", className: "border border-status-info-border bg-status-info-bg text-status-info" },
  OPEN: { label: "已埋设", className: "border border-status-info-border bg-status-info-bg text-status-info" },
  HINTED: { label: "已照应", className: "border border-status-ai-border bg-status-ai-bg text-status-ai" },
  TRIGGERED: { label: "待引爆", className: "border border-status-warning-border bg-status-warning-bg text-status-warning" },
  RESOLVED: { label: "已回收", className: "border border-status-success-border bg-status-success-bg text-status-success" },
  DORMANT_CLUE: { label: "沉睡", className: "border border-status-warning-border bg-status-warning-bg text-status-warning opacity-80" },
  ABANDONED: { label: "已弃用", className: "border border-border bg-muted/70 text-muted-foreground line-through" },
};

const IMPORTANCE_OPTIONS = [
  { value: "MAIN_ARC", label: "主线" },
  { value: "SUBPLOT", label: "支线" },
  { value: "DETAIL", label: "细节" },
] as const;

const IMPORTANCE_LABELS: Record<string, string> = Object.fromEntries(
  IMPORTANCE_OPTIONS.map((i) => [i.value, i.label]),
);

const REVEAL_POLICY_OPTIONS = [
  { value: "HIDE_TRUTH", label: "严守底牌" },
  { value: "HINT_ONLY", label: "只许暗示" },
  { value: "REVEAL_NOW", label: "允许揭晓" },
] as const;

const REVEAL_POLICY_LABELS: Record<string, string> = Object.fromEntries(
  REVEAL_POLICY_OPTIONS.map((r) => [r.value, r.label]),
);

const PROPOSAL_TYPE_LABELS: Record<string, string> = {
  CHARACTER_DEATH: "人物退场确认",
  FORESHADOWING_RESOLVE: "伏笔回收确认",
  CORE_IDENTITY_SHIFT: "核心设定变更",
};

const DEFAULT_LOCKED_CHARACTER_FIELDS = ["personality", "realmOrLevel", "roleType"];

const DEFAULT_CHAR_FIELDS: GenreCharacterField[] = [
  { key: "realm", label: "境界 / 实力", placeholder: "如：筑基后期" },
  { key: "faction", label: "所属势力", placeholder: "如：青云宗" },
];

type SubTab = "charter" | "characters" | "world" | "foreshadowing";

interface LorePanelProps {
  bookId: string;
  bookGenreCode?: string;
  anchorRefreshKey?: number;
  refreshSignal?: number;
  onDataMutated?: () => void;
}

export function LorePanel({
  bookId,
  bookGenreCode,
  anchorRefreshKey = 0,
  refreshSignal = 0,
  onDataMutated,
}: LorePanelProps) {
  const confirm = useConfirm();
  const toast = useToast();

  const [subTab, setSubTab] = useState<SubTab>("characters");
  const [genreProfile, setGenreProfile] = useState<GenreProfileItem | null>(null);
  const [charter, setCharter] = useState<BookCharterItem | null>(null);
  const [charterDraft, setCharterDraft] = useState("");
  const [savingCharter, setSavingCharter] = useState(false);

  const [styleAnchors, setStyleAnchors] = useState<StyleAnchorItem[]>([]);
  const [anchorComposerOpen, setAnchorComposerOpen] = useState(false);
  const [anchorTitle, setAnchorTitle] = useState("");
  const [anchorText, setAnchorText] = useState("");
  const [savingAnchor, setSavingAnchor] = useState(false);
  const [anchorQuotaError, setAnchorQuotaError] = useState<string | null>(null);
  const [libraryDocs, setLibraryDocs] = useState<LibraryDocumentItem[]>([]);

  const [characters, setCharacters] = useState<CharacterItem[]>([]);
  const [worldEntries, setWorldEntries] = useState<WorldEntryItem[]>([]);
  const [foreshadowings, setForeshadowings] = useState<ForeshadowingItem[]>([]);
  const [proposals, setProposals] = useState<MemoryProposalItem[]>([]);
  const [decidingProposalId, setDecidingProposalId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Search & filters per tab
  const [charQuery, setCharQuery] = useState("");
  const [charRoleFilter, setCharRoleFilter] = useState<string>("ALL");
  const [worldQuery, setWorldQuery] = useState("");
  const [worldCatFilter, setWorldCatFilter] = useState<string>("ALL");
  const [clueQuery, setClueQuery] = useState("");
  const [clueFilter, setClueFilter] = useState<"ALL" | "ACTIVE" | "PLANNED" | "RESOLVED">("ALL");

  // Collapsible composer drawers
  const [charComposerOpen, setCharComposerOpen] = useState(false);
  const [editingCharName, setEditingCharName] = useState<string | null>(null);
  const [cName, setCName] = useState("");
  const [cRole, setCRole] = useState("PROTAGONIST");
  const [cAliases, setCAliases] = useState("");
  const [cLocation, setCLocation] = useState("");
  const [cNotes, setCNotes] = useState("");
  const [cInventory, setCInventory] = useState("");
  const [cCustomAttrs, setCCustomAttrs] = useState<Record<string, string>>({});
  const [savingChar, setSavingChar] = useState(false);

  const [worldComposerOpen, setWorldComposerOpen] = useState(false);
  const [wCat, setWCat] = useState("WORLD");
  const [wTitle, setWTitle] = useState("");
  const [wContent, setWContent] = useState("");
  const [savingWorld, setSavingWorld] = useState(false);

  const [clueComposerOpen, setClueComposerOpen] = useState(false);
  const [editingClueId, setEditingClueId] = useState<string | null>(null);
  const [fTitle, setFTitle] = useState("");
  const [fImportance, setFImportance] = useState("SUBPLOT");
  const [fRevealPolicy, setFRevealPolicy] = useState("HIDE_TRUTH");
  const [fStatus, setFStatus] = useState("PLANNED");
  const [fChapter, setFChapter] = useState("");
  const [fTargetMin, setFTargetMin] = useState("");
  const [fTargetMax, setFTargetMax] = useState("");
  const [fSurfaceClue, setFSurfaceClue] = useState("");
  const [fHiddenTruth, setFHiddenTruth] = useState("");
  const [fAuthorLocked, setFAuthorLocked] = useState(true);
  const [savingClue, setSavingClue] = useState(false);

  // Per-card truth reveal toggles (anti-spoiler by default)
  const [revealedTruthIds, setRevealedTruthIds] = useState<Record<string, boolean>>({});

  const effectiveGenreCode = bookGenreCode || "XIANXIA_FANTASY";

  useEffect(() => {
    let active = true;
    void Promise.all([
      loreApi.listCharacters(bookId),
      loreApi.listWorldbook(bookId),
      loreApi.listForeshadowings(bookId),
      booksApi.getCharter(bookId).catch(() => null),
      libraryApi.list(bookId).catch(() => []),
      memoryProposalsApi.list(bookId, "PENDING").catch(() => []),
      genresApi.get(effectiveGenreCode).catch(() => null),
      styleAnchorsApi.list(bookId).catch(() => []),
    ])
      .then(([chars, worlds, clues, charterData, libDocs, pendingProposals, profile, anchors]) => {
        if (!active) return;
        setCharacters(chars);
        setWorldEntries(worlds);
        setForeshadowings(clues);
        setCharter(charterData);
        setCharterDraft(charterData?.effectiveCharter ?? "");
        setLibraryDocs(libDocs);
        setProposals(pendingProposals);
        setGenreProfile(profile);
        setStyleAnchors(anchors);
        setError(null);
      })
      .catch((err) => {
        if (!active) return;
        setError(err instanceof Error ? err.message : "加载设定库失败");
      });
    return () => {
      active = false;
    };
  }, [bookId, effectiveGenreCode, anchorRefreshKey, refreshSignal]);

  const charFields =
    genreProfile && genreProfile.characterFieldSchema.length > 0
      ? genreProfile.characterFieldSchema
      : DEFAULT_CHAR_FIELDS;

  const charFieldLabels: Record<string, string> = Object.fromEntries(
    charFields.map((f) => [f.key, f.label]),
  );

  const enabledAnchorChars = styleAnchors
    .filter((a) => a.enabled)
    .reduce((sum, a) => sum + a.excerptText.trim().length, 0);

  const filteredCharacters = useMemo(() => {
    const q = charQuery.trim().toLowerCase();
    return characters.filter((ch) => {
      if (charRoleFilter !== "ALL" && ch.roleType !== charRoleFilter) return false;
      if (!q) return true;
      return (
        ch.name.toLowerCase().includes(q) ||
        (ch.aliases ?? "").toLowerCase().includes(q) ||
        (ch.currentLocation ?? "").toLowerCase().includes(q) ||
        (ch.realmOrLevel ?? "").toLowerCase().includes(q) ||
        Object.values(ch.customAttributes ?? {}).some((v) => v.toLowerCase().includes(q))
      );
    });
  }, [characters, charQuery, charRoleFilter]);

  const filteredWorldEntries = useMemo(() => {
    const q = worldQuery.trim().toLowerCase();
    return worldEntries.filter((w) => {
      if (worldCatFilter !== "ALL" && w.category !== worldCatFilter) return false;
      if (!q) return true;
      return (
        w.title.toLowerCase().includes(q) ||
        (w.content ?? "").toLowerCase().includes(q)
      );
    });
  }, [worldEntries, worldQuery, worldCatFilter]);

  const filteredForeshadowings = useMemo(() => {
    const q = clueQuery.trim().toLowerCase();
    return foreshadowings.filter((f) => {
      if (clueFilter === "ACTIVE") {
        if (!["PLANTED", "OPEN", "HINTED", "TRIGGERED", "DORMANT_CLUE"].includes(f.status)) {
          return false;
        }
      } else if (clueFilter === "PLANNED") {
        if (f.status !== "PLANNED") return false;
      } else if (clueFilter === "RESOLVED") {
        if (f.status !== "RESOLVED" && f.status !== "ABANDONED") return false;
      }
      if (!q) return true;
      return (
        f.title.toLowerCase().includes(q) ||
        (f.code ?? "").toLowerCase().includes(q) ||
        (f.surfaceClue ?? "").toLowerCase().includes(q) ||
        (f.description ?? "").toLowerCase().includes(q)
      );
    });
  }, [foreshadowings, clueQuery, clueFilter]);

  function openNewCharForm() {
    setEditingCharName(null);
    setCName("");
    setCRole("SUPPORTING");
    setCAliases("");
    setCLocation("");
    setCNotes("");
    setCInventory("");
    setCCustomAttrs({});
    setCharComposerOpen(true);
  }

  function openEditCharForm(ch: CharacterItem) {
    setEditingCharName(ch.name);
    setCName(ch.name);
    setCRole(ch.roleType || "SUPPORTING");
    setCAliases(ch.aliases ?? "");
    setCLocation(ch.currentLocation ?? "");
    setCNotes(ch.statusNotes ?? "");
    setCInventory(ch.inventory ?? "");
    const nextAttrs: Record<string, string> = { ...(ch.customAttributes ?? {}) };
    if (ch.realmOrLevel && !nextAttrs.realm) {
      nextAttrs.realm = ch.realmOrLevel;
    }
    setCCustomAttrs(nextAttrs);
    setCharComposerOpen(true);
  }

  function openNewClueForm() {
    setEditingClueId(null);
    setFTitle("");
    setFImportance("SUBPLOT");
    setFRevealPolicy("HIDE_TRUTH");
    setFStatus("PLANNED");
    setFChapter("");
    setFTargetMin("");
    setFTargetMax("");
    setFSurfaceClue("");
    setFHiddenTruth("");
    setFAuthorLocked(true);
    setClueComposerOpen(true);
  }

  function openEditClueForm(item: ForeshadowingItem) {
    setEditingClueId(item.id);
    setFTitle(item.title);
    setFImportance(item.importance || "SUBPLOT");
    setFRevealPolicy(item.revealPolicy || "HIDE_TRUTH");
    setFStatus(item.status === "OPEN" ? "PLANTED" : item.status || "PLANNED");
    setFChapter(item.plantedChapterNo != null ? String(item.plantedChapterNo) : "");
    setFTargetMin(item.targetChapterMin != null ? String(item.targetChapterMin) : "");
    setFTargetMax(item.targetChapterMax != null ? String(item.targetChapterMax) : "");
    setFSurfaceClue(item.surfaceClue || item.description || "");
    setFHiddenTruth(item.hiddenTruth || "");
    setFAuthorLocked(Boolean(item.authorLocked));
    setClueComposerOpen(true);
  }

  async function handleDecideProposal(proposal: MemoryProposalItem, decision: "ACCEPT" | "REJECT") {
    setDecidingProposalId(proposal.id);
    setError(null);
    try {
      await memoryProposalsApi.decide(bookId, proposal.id, decision);
      setProposals((prev) => prev.filter((item) => item.id !== proposal.id));
      if (decision === "ACCEPT") {
        const [chars, clues] = await Promise.all([
          loreApi.listCharacters(bookId),
          loreApi.listForeshadowings(bookId),
        ]);
        setCharacters(chars);
        setForeshadowings(clues);
        onDataMutated?.();
        toast.success("已采纳设定变更");
      } else {
        toast.info("已驳回该条变更提议");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "处理设定变更确认失败");
    } finally {
      setDecidingProposalId(null);
    }
  }

  async function handleSaveCharter(e: FormEvent) {
    e.preventDefault();
    setSavingCharter(true);
    setError(null);
    try {
      const updated = await booksApi.updateCharter(bookId, charterDraft);
      setCharter(updated);
      setCharterDraft(updated.effectiveCharter);
      toast.success(
        updated.charterSource === "AUTHOR_DEFINED"
          ? "已锁定故事档案，AI 后续创作将严格遵守"
          : "已清空手填档案，将按自动归纳兜底",
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存故事档案失败");
    } finally {
      setSavingCharter(false);
    }
  }

  async function handleAddStyleAnchor(e: FormEvent) {
    e.preventDefault();
    if (!anchorText.trim()) return;
    setSavingAnchor(true);
    setError(null);
    setAnchorQuotaError(null);
    try {
      const saved = await styleAnchorsApi.upsert(bookId, {
        title: anchorTitle.trim() || `文风参考 #${styleAnchors.length + 1}`,
        excerptText: anchorText.trim(),
        sortOrder: styleAnchors.length + 1,
        enabled: true,
      });
      setStyleAnchors((prev) => [...prev, saved]);
      setAnchorTitle("");
      setAnchorText("");
      setAnchorComposerOpen(false);
      toast.success("已添加文风锚点");
    } catch (err) {
      if (err instanceof ApiClientError && err.code === "VIP_QUOTA_EXCEEDED") {
        setAnchorQuotaError(err.message);
      } else {
        setError(err instanceof Error ? err.message : "保存文风锚点失败");
      }
    } finally {
      setSavingAnchor(false);
    }
  }

  async function handleToggleStyleAnchor(anchor: StyleAnchorItem) {
    setError(null);
    setAnchorQuotaError(null);
    try {
      const updated = await styleAnchorsApi.upsert(bookId, {
        id: anchor.id,
        title: anchor.title,
        excerptText: anchor.excerptText,
        sortOrder: anchor.sortOrder,
        enabled: !anchor.enabled,
      });
      setStyleAnchors((prev) => prev.map((a) => (a.id === updated.id ? updated : a)));
    } catch (err) {
      if (err instanceof ApiClientError && err.code === "VIP_QUOTA_EXCEEDED") {
        setAnchorQuotaError(err.message);
      } else {
        setError(err instanceof Error ? err.message : "切换文风锚点失败");
      }
    }
  }

  async function handleDeleteStyleAnchor(anchor: StyleAnchorItem) {
    await confirm({
      title: `删除文风锚点「${anchor.title || "文风参考"}」？`,
      description: "删除后 AI 将不再参考该段句式风格。",
      confirmLabel: "确认删除",
      variant: "destructive",
      onConfirm: async () => {
        await styleAnchorsApi.remove(bookId, anchor.id);
        setStyleAnchors((prev) => prev.filter((x) => x.id !== anchor.id));
        toast.success("已删除文风锚点");
      },
    });
  }

  async function handleToggleBookDoc(doc: LibraryDocumentItem) {
    setError(null);
    try {
      const updated = await libraryApi.setBookBinding(bookId, doc.id, !doc.boundToBook);
      setLibraryDocs((prev) => prev.map((d) => (d.id === updated.id ? updated : d)));
    } catch (err) {
      setError(err instanceof Error ? err.message : "更新参考资料开关失败");
    }
  }

  async function handleSaveCharacter(e: FormEvent) {
    e.preventDefault();
    if (!cName.trim()) return;
    setSavingChar(true);
    setError(null);
    const cleanedCustom: Record<string, string> = {};
    for (const [k, v] of Object.entries(cCustomAttrs)) {
      if (v.trim()) cleanedCustom[k] = v.trim();
    }
    try {
      const saved = await loreApi.upsertCharacter(bookId, {
        name: cName.trim(),
        roleType: cRole,
        aliases: cAliases.trim(),
        realmOrLevel: cleanedCustom.realm ?? "",
        currentLocation: cLocation.trim(),
        statusNotes: cNotes.trim(),
        inventory: cInventory.trim(),
        customAttributes: cleanedCustom,
      });
      setCharacters((prev) => {
        const rest = prev.filter((item) => item.id !== saved.id);
        return [saved, ...rest];
      });
      setCharComposerOpen(false);
      setEditingCharName(null);
      onDataMutated?.();
      toast.success(editingCharName ? `已更新角色「${saved.name}」` : `已登记角色「${saved.name}」`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存人物失败");
    } finally {
      setSavingChar(false);
    }
  }

  async function handleDeleteCharacter(ch: CharacterItem) {
    await confirm({
      title: `删除人物「${ch.name}」？`,
      description: "删除后将从人物档案与关系图谱角色索引中移除。",
      confirmLabel: "确认删除",
      variant: "destructive",
      onConfirm: async () => {
        await loreApi.deleteCharacter(bookId, ch.id);
        setCharacters((prev) => prev.filter((x) => x.id !== ch.id));
        onDataMutated?.();
        toast.success(`已删除人物「${ch.name}」`);
      },
    });
  }

  async function handleWakeCharacter(ch: CharacterItem) {
    setError(null);
    try {
      const updated = await loreApi.wakeCharacter(bookId, ch.id);
      setCharacters((prev) => prev.map((item) => (item.id === updated.id ? updated : item)));
      onDataMutated?.();
      toast.success(`已唤醒人物「${ch.name}」`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "唤醒人物失败");
    }
  }

  async function handleToggleCharacterLock(ch: CharacterItem) {
    setError(null);
    const hasLocks = (ch.lockedFields?.length ?? 0) > 0;
    const nextLocks = hasLocks ? [] : DEFAULT_LOCKED_CHARACTER_FIELDS;
    try {
      const updated = await loreApi.updateCharacterLocks(bookId, ch.id, nextLocks);
      setCharacters((prev) => prev.map((item) => (item.id === updated.id ? updated : item)));
      onDataMutated?.();
      toast.info(hasLocks ? `已解锁「${ch.name}」的核心设定` : `已锁定「${ch.name}」，AI 整理不会覆盖`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "更新人物锁定失败");
    }
  }

  async function handleAddWorld(e: FormEvent) {
    e.preventDefault();
    if (!wTitle.trim()) return;
    setSavingWorld(true);
    setError(null);
    try {
      const saved = await loreApi.createWorldEntry(bookId, {
        category: wCat,
        title: wTitle.trim(),
        content: wContent.trim(),
      });
      setWorldEntries((prev) => [saved, ...prev]);
      setWTitle("");
      setWContent("");
      setWorldComposerOpen(false);
      onDataMutated?.();
      toast.success(`已添加词条「${saved.title}」`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存世界观失败");
    } finally {
      setSavingWorld(false);
    }
  }

  async function handleDeleteWorld(w: WorldEntryItem) {
    await confirm({
      title: `删除世界观词条「${w.title}」？`,
      description: "删除后 AI 将不再检索该词条。",
      confirmLabel: "确认删除",
      variant: "destructive",
      onConfirm: async () => {
        await loreApi.deleteWorldEntry(bookId, w.id);
        setWorldEntries((prev) => prev.filter((x) => x.id !== w.id));
        onDataMutated?.();
        toast.success(`已删除词条「${w.title}」`);
      },
    });
  }

  async function handleSaveForeshadowing(e: FormEvent) {
    e.preventDefault();
    if (!fTitle.trim()) return;
    setSavingClue(true);
    setError(null);
    try {
      const plantedNo = Number.parseInt(fChapter, 10);
      const minNo = Number.parseInt(fTargetMin, 10);
      const maxNo = Number.parseInt(fTargetMax, 10);
      const surface = fSurfaceClue.trim();
      const saved = await loreApi.upsertForeshadowing(bookId, {
        id: editingClueId ?? undefined,
        title: fTitle.trim(),
        description: surface,
        surfaceClue: surface,
        hiddenTruth: fHiddenTruth.trim(),
        importance: fImportance,
        revealPolicy: fRevealPolicy,
        status: fStatus,
        plantedChapterNo: Number.isNaN(plantedNo) ? undefined : plantedNo,
        targetChapterMin: Number.isNaN(minNo) ? undefined : minNo,
        targetChapterMax: Number.isNaN(maxNo) ? undefined : maxNo,
        authorLocked: fAuthorLocked,
      });
      setForeshadowings((prev) => {
        const rest = prev.filter((item) => item.id !== saved.id);
        return [saved, ...rest];
      });
      setClueComposerOpen(false);
      setEditingClueId(null);
      onDataMutated?.();
      toast.success(editingClueId ? `已更新伏笔「${saved.title}」` : `已登记伏笔「${saved.title}」`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存伏笔失败");
    } finally {
      setSavingClue(false);
    }
  }

  async function handleDeleteForeshadowing(f: ForeshadowingItem) {
    await confirm({
      title: `删除伏笔「${f.title}」？`,
      description: "删除后将同步从剧情泳道与 AI 导演槽中移除。",
      confirmLabel: "确认删除",
      variant: "destructive",
      onConfirm: async () => {
        await loreApi.deleteForeshadowing(bookId, f.id);
        setForeshadowings((prev) => prev.filter((x) => x.id !== f.id));
        onDataMutated?.();
        toast.success(`已删除伏笔「${f.title}」`);
      },
    });
  }

  async function handleTransitionForeshadowing(item: ForeshadowingItem, targetStatus: string) {
    setError(null);
    try {
      const saved = await loreApi.transitionForeshadowing(bookId, item.id, {
        targetStatus,
      });
      setForeshadowings((prev) => prev.map((f) => (f.id === saved.id ? saved : f)));
      onDataMutated?.();
      toast.success(`伏笔「${item.title}」状态已更新`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "更新伏笔状态失败");
    }
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden text-xs">
      {/* Compact Top Segmented Navigation + Genre Pill */}
      <div className="shrink-0 space-y-2 border-b border-border/70 bg-card/70 px-3 py-2">
        <div className="flex items-center gap-1.5">
          <div className="grid flex-1 grid-cols-4 gap-0.5 rounded-xl bg-muted p-0.5 text-[11px] font-medium">
            {(
              [
                ["charter", "档案", null, BookOpen],
                ["characters", "人物", characters.length, Users],
                ["world", "世界", worldEntries.length, Globe],
                ["foreshadowing", "伏笔", foreshadowings.length, Bookmark],
              ] as const
            ).map(([key, label, count, Icon]) => {
              const active = subTab === key;
              return (
                <button
                  key={key}
                  type="button"
                  onClick={() => setSubTab(key)}
                  className={`inline-flex items-center justify-center gap-1 rounded-lg px-1.5 py-1.5 transition-all ${
                    active
                      ? "bg-card font-semibold text-foreground shadow-2xs"
                      : "text-muted-foreground hover:text-foreground"
                  }`}
                >
                  <Icon className={`size-3 shrink-0 ${active ? "text-primary" : ""}`} />
                  <span className="truncate">{label}</span>
                  {count !== null && count > 0 && (
                    <span
                      className={`font-mono text-[9.5px] ${
                        active ? "text-primary" : "text-muted-foreground/80"
                      }`}
                    >
                      {count}
                    </span>
                  )}
                </button>
              );
            })}
          </div>
          {genreProfile && (
            <span
              title={`题材模板：${genreProfile.shortName}`}
              className="shrink-0 rounded-lg border border-primary/25 bg-primary-soft px-2 py-1 text-[10px] font-medium text-primary-soft-foreground"
            >
              {genreProfile.shortName}
            </span>
          )}
        </div>

        {/* Pending Memory Proposals Banner (compact, only when present) */}
        {proposals.length > 0 && (
          <div className="space-y-1.5 rounded-xl border border-status-warning-border bg-status-warning-bg p-2.5">
            <div className="flex items-center justify-between text-[11px] font-semibold text-status-warning">
              <span>待确认的设定变更（{proposals.length}）</span>
              <span className="text-[10px] font-normal">确认后生效</span>
            </div>
            <ul className="space-y-1.5">
              {proposals.slice(0, 2).map((proposal) => {
                const busy = decidingProposalId === proposal.id;
                return (
                  <li
                    key={proposal.id}
                    className="space-y-1 rounded-lg border border-status-warning-border/70 bg-card p-2"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate font-medium text-foreground">
                        {proposal.targetName || "设定变更"}
                      </span>
                      <span className="shrink-0 rounded bg-status-warning-bg px-1.5 py-0.5 text-[9.5px] text-status-warning">
                        {PROPOSAL_TYPE_LABELS[proposal.proposalType] ?? proposal.proposalType}
                      </span>
                    </div>
                    <p className="line-clamp-2 text-[10.5px] leading-snug text-muted-foreground">
                      {proposal.summary}
                    </p>
                    <div className="flex justify-end gap-1 pt-0.5">
                      <Button
                        size="xs"
                        variant="ghost"
                        disabled={busy}
                        onClick={() => void handleDecideProposal(proposal, "REJECT")}
                      >
                        驳回
                      </Button>
                      <Button
                        size="xs"
                        disabled={busy}
                        onClick={() => void handleDecideProposal(proposal, "ACCEPT")}
                      >
                        {busy ? "处理中..." : "采纳"}
                      </Button>
                    </div>
                  </li>
                );
              })}
            </ul>
          </div>
        )}
      </div>

      {error && (
        <div className="px-3 pt-2">
          <Alert status="danger" announce="assertive">
            {error}
          </Alert>
        </div>
      )}

      {/* Main Scrollable Content Area */}
      <div className="flex-1 space-y-2.5 overflow-y-auto p-3">
        {/* ======================== TAB 1: CHARTER / ANCHORS / DOCS ======================== */}
        {subTab === "charter" && (
          <>
            <form
              onSubmit={handleSaveCharter}
              className="overflow-hidden rounded-2xl border border-border bg-card shadow-2xs"
            >
              <div className="flex items-center justify-between gap-2 border-b border-border/60 px-3 py-2">
                <span className="font-semibold text-foreground">核心故事档案</span>
                <span
                  className={`rounded-full px-2 py-0.5 text-[10px] font-medium ${
                    charter?.charterSource === "AUTHOR_DEFINED"
                      ? "border border-status-success-border bg-status-success-bg text-status-success"
                      : charter?.charterSource === "AUTO_INFERRED"
                        ? "border border-status-info-border bg-status-info-bg text-status-info"
                        : "bg-muted text-muted-foreground"
                  }`}
                >
                  {charter?.charterSource === "AUTHOR_DEFINED"
                    ? "作者已锁定"
                    : charter?.charterSource === "AUTO_INFERRED"
                      ? "AI 自动归纳"
                      : "未填写"}
                </span>
              </div>
              <div className="p-2.5">
                <textarea
                  rows={5}
                  value={charterDraft}
                  onChange={(e) => setCharterDraft(e.target.value)}
                  placeholder="可留空由 AI 随前几章自动总结主线、人称与基调；也可手动写定后锁定，锁定后 AI 不会覆盖..."
                  className="block w-full resize-none rounded-xl border border-border/70 bg-surface-subtle/60 p-2.5 text-xs leading-relaxed text-foreground placeholder:text-muted-foreground/70 outline-none transition-colors focus:border-ring"
                />
                <div className="mt-2 flex items-center justify-between gap-2">
                  <span className="text-[10px] text-muted-foreground">
                    锁定后作为全局创作铁律注入
                  </span>
                  <Button type="submit" size="xs" disabled={savingCharter}>
                    {savingCharter
                      ? "保存中..."
                      : charter?.charterSource === "AUTO_INFERRED"
                        ? "确认并锁定"
                        : "保存档案"}
                  </Button>
                </div>
              </div>
            </form>

            {/* Style Anchors Card (List-First, Collapsible Add Form) */}
            <div className="overflow-hidden rounded-2xl border border-border bg-card shadow-2xs">
              <div className="flex items-center justify-between gap-2 border-b border-border/60 px-3 py-2">
                <div className="flex items-center gap-1.5">
                  <Sparkles className="size-3.5 text-primary" />
                  <span className="font-semibold text-foreground">文风锚点</span>
                  <span className="rounded-full bg-surface-subtle px-1.5 py-0.5 font-mono text-[9.5px] text-muted-foreground">
                    {enabledAnchorChars}/900字
                  </span>
                </div>
                <Button
                  size="xs"
                  variant={anchorComposerOpen ? "secondary" : "ghost"}
                  onClick={() => setAnchorComposerOpen((v) => !v)}
                >
                  {anchorComposerOpen ? <X className="size-3" /> : <Plus className="size-3" />}
                  {anchorComposerOpen ? "收起" : "新增"}
                </Button>
              </div>

              <div className="space-y-2 p-2.5">
                {anchorQuotaError && (
                  <div className="flex items-center justify-between gap-2 rounded-lg border border-status-vip-border bg-status-vip-bg px-2.5 py-1.5 text-[11px] text-status-vip">
                    <span>{anchorQuotaError}</span>
                    <Link href="/vip" className="shrink-0 font-semibold underline">
                      升级权益 →
                    </Link>
                  </div>
                )}

                {anchorComposerOpen && (
                  <form
                    onSubmit={handleAddStyleAnchor}
                    className="space-y-1.5 rounded-xl border border-border bg-surface-subtle/70 p-2.5"
                  >
                    <input
                      value={anchorTitle}
                      onChange={(e) => setAnchorTitle(e.target.value)}
                      placeholder="锚点名称（如：雪夜白描参考）"
                      className="h-7 w-full rounded-lg border border-border bg-background px-2.5 text-xs outline-none focus:border-ring"
                    />
                    <textarea
                      rows={3}
                      value={anchorText}
                      onChange={(e) => setAnchorText(e.target.value)}
                      placeholder="粘贴 100~300 字亲笔样段（或直接在中栏正文划词设为锚点）..."
                      className="w-full resize-none rounded-lg border border-border bg-background p-2 text-xs leading-relaxed outline-none focus:border-ring"
                    />
                    <div className="flex justify-end gap-1.5">
                      <Button
                        type="button"
                        size="xs"
                        variant="ghost"
                        onClick={() => setAnchorComposerOpen(false)}
                      >
                        取消
                      </Button>
                      <Button type="submit" size="xs" disabled={savingAnchor || !anchorText.trim()}>
                        {savingAnchor ? "保存中..." : "保存锚点"}
                      </Button>
                    </div>
                  </form>
                )}

                {styleAnchors.length === 0 ? (
                  <p className="rounded-xl border border-dashed border-border/80 bg-surface-subtle/40 px-3 py-3 text-center text-[11px] leading-relaxed text-muted-foreground">
                    暂无文风锚点。在正文划选满意段落可一键收藏，让 AI 贴合你的句式与用词节奏。
                  </p>
                ) : (
                  <ul className="space-y-1.5">
                    {styleAnchors.map((anchor) => (
                      <li
                        key={anchor.id}
                        className={`rounded-xl border p-2.5 transition-colors ${
                          anchor.enabled
                            ? "border-primary/30 bg-primary-soft/25"
                            : "border-border/70 bg-surface-subtle/50 opacity-70"
                        }`}
                      >
                        <div className="flex items-center justify-between gap-2">
                          <span className="truncate font-medium text-foreground">
                            {anchor.title || "文风锚点"}
                          </span>
                          <div className="flex shrink-0 items-center gap-1.5">
                            <button
                              type="button"
                              role="switch"
                              aria-checked={anchor.enabled}
                              title={anchor.enabled ? "已启用（点击停用）" : "已停用（点击启用）"}
                              onClick={() => void handleToggleStyleAnchor(anchor)}
                              className={`relative inline-flex h-4 w-7 items-center rounded-full transition-colors ${
                                anchor.enabled ? "bg-primary" : "bg-muted"
                              }`}
                            >
                              <span
                                className={`inline-block size-3 rounded-full bg-card shadow-2xs transition-transform ${
                                  anchor.enabled ? "translate-x-3.5" : "translate-x-0.5"
                                }`}
                              />
                            </button>
                            <button
                              type="button"
                              title="删除文风锚点"
                              onClick={() => void handleDeleteStyleAnchor(anchor)}
                              className="rounded p-0.5 text-muted-foreground transition-colors hover:bg-status-danger-bg hover:text-status-danger"
                            >
                              <Trash2 className="size-3" />
                            </button>
                          </div>
                        </div>
                        <p className="mt-1 line-clamp-3 whitespace-pre-wrap text-[11px] leading-relaxed text-muted-foreground">
                          {anchor.excerptText}
                        </p>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>

            {/* Book Reference Library Card */}
            <div className="overflow-hidden rounded-2xl border border-border bg-card shadow-2xs">
              <div className="flex items-center justify-between gap-2 border-b border-border/60 px-3 py-2">
                <span className="font-semibold text-foreground">
                  参考资料 ({libraryDocs.length})
                </span>
                <Link href="/library" className="text-[11px] font-medium text-primary hover:underline">
                  管理资料库 →
                </Link>
              </div>
              <div className="p-2.5">
                {libraryDocs.length === 0 ? (
                  <p className="rounded-xl border border-dashed border-border/80 bg-surface-subtle/40 px-3 py-3 text-center text-[11px] text-muted-foreground">
                    暂无绑定资料，可前往「资料库」上传设定集或行业参考。
                  </p>
                ) : (
                  <ul className="space-y-1.5">
                    {libraryDocs.map((doc) => (
                      <li
                        key={doc.id}
                        className="flex items-center justify-between gap-2 rounded-xl border border-border/70 bg-surface-subtle/50 px-2.5 py-2"
                      >
                        <div className="flex min-w-0 items-center gap-2">
                          <button
                            type="button"
                            role="switch"
                            aria-checked={doc.boundToBook}
                            onClick={() => void handleToggleBookDoc(doc)}
                            className={`relative inline-flex h-4 w-7 shrink-0 items-center rounded-full transition-colors ${
                              doc.boundToBook ? "bg-primary" : "bg-muted"
                            }`}
                          >
                            <span
                              className={`inline-block size-3 rounded-full bg-card shadow-2xs transition-transform ${
                                doc.boundToBook ? "translate-x-3.5" : "translate-x-0.5"
                              }`}
                            />
                          </button>
                          <span className="truncate text-[11px] font-medium text-foreground">
                            {doc.title}
                          </span>
                        </div>
                        <span className="shrink-0 rounded-md bg-muted px-1.5 py-0.5 text-[9.5px] text-muted-foreground">
                          {doc.accessType === "USER"
                            ? "个人"
                            : doc.accessType === "ASSIGNED"
                              ? "专属"
                              : "公共"}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
          </>
        )}

        {/* ======================== TAB 2: CHARACTERS (LIST-FIRST) ======================== */}
        {subTab === "characters" && (
          <>
            {/* Search + Quick Filter + New CTA */}
            <div className="space-y-2">
              <div className="flex items-center gap-1.5">
                <div className="relative flex-1">
                  <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
                  <input
                    value={charQuery}
                    onChange={(e) => setCharQuery(e.target.value)}
                    placeholder="搜索姓名、别名、势力..."
                    className="h-7.5 w-full rounded-xl border border-border bg-card pr-2.5 pl-7.5 text-[11.5px] text-foreground placeholder:text-muted-foreground/70 outline-none focus:border-ring"
                  />
                </div>
                <Button
                  size="xs"
                  variant={charComposerOpen ? "secondary" : "default"}
                  onClick={() => (charComposerOpen ? setCharComposerOpen(false) : openNewCharForm())}
                  className="h-7.5 shrink-0 px-2.5"
                >
                  {charComposerOpen ? <X className="size-3.5" /> : <Plus className="size-3.5" />}
                  {charComposerOpen ? "收起" : "新建"}
                </Button>
              </div>

              {characters.length > 0 && (
                <div className="flex items-center gap-1 overflow-x-auto pb-0.5 text-[10.5px]">
                  {[
                    ["ALL", `全部 (${characters.length})`],
                    ["PROTAGONIST", "主角"],
                    ["SUPPORTING", "配角"],
                    ["ANTAGONIST", "反派"],
                  ].map(([val, lbl]) => (
                    <button
                      key={val}
                      type="button"
                      onClick={() => setCharRoleFilter(val)}
                      className={`shrink-0 rounded-full px-2.5 py-0.5 font-medium transition-colors ${
                        charRoleFilter === val
                          ? "bg-primary-soft text-primary-soft-foreground"
                          : "text-muted-foreground hover:bg-muted hover:text-foreground"
                      }`}
                    >
                      {lbl}
                    </button>
                  ))}
                </div>
              )}
            </div>

            {/* Collapsible Character Create / Edit Card */}
            {charComposerOpen && (
              <form
                onSubmit={handleSaveCharacter}
                className="space-y-2 rounded-2xl border border-primary/35 bg-card p-3 shadow-sm"
              >
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-foreground">
                    {editingCharName ? `编辑人物 · ${editingCharName}` : "登记新人物"}
                  </span>
                  <button
                    type="button"
                    onClick={() => setCharComposerOpen(false)}
                    className="rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                  >
                    <X className="size-3.5" />
                  </button>
                </div>

                <input
                  required
                  value={cName}
                  onChange={(e) => setCName(e.target.value)}
                  placeholder="角色姓名"
                  className="h-8 w-full rounded-lg border border-border bg-surface-subtle/60 px-2.5 text-xs text-foreground outline-none focus:border-ring"
                />

                <div className="grid grid-cols-3 gap-1 rounded-lg bg-muted p-0.5">
                  {ROLE_OPTIONS.map((opt) => (
                    <button
                      key={opt.value}
                      type="button"
                      onClick={() => setCRole(opt.value)}
                      className={`rounded-md py-1 text-[11px] font-medium transition-all ${
                        cRole === opt.value
                          ? "bg-card text-foreground shadow-2xs"
                          : "text-muted-foreground hover:text-foreground"
                      }`}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>

                <input
                  value={cAliases}
                  onChange={(e) => setCAliases(e.target.value)}
                  placeholder="别名 / 化名 / 称谓（逗号分隔）"
                  className="h-7.5 w-full rounded-lg border border-border bg-surface-subtle/60 px-2.5 text-[11.5px] outline-none focus:border-ring"
                />

                <div className="grid grid-cols-2 gap-1.5">
                  {charFields.map((field) => (
                    <input
                      key={field.key}
                      value={cCustomAttrs[field.key] ?? ""}
                      onChange={(e) =>
                        setCCustomAttrs((prev) => ({ ...prev, [field.key]: e.target.value }))
                      }
                      placeholder={`${field.label}（${field.placeholder}）`}
                      title={field.label}
                      className="h-7.5 rounded-lg border border-border bg-surface-subtle/60 px-2 text-[11px] outline-none focus:border-ring"
                    />
                  ))}
                  <input
                    value={cLocation}
                    onChange={(e) => setCLocation(e.target.value)}
                    placeholder="当前位置"
                    className="h-7.5 rounded-lg border border-border bg-surface-subtle/60 px-2 text-[11px] outline-none focus:border-ring"
                  />
                  <input
                    value={cInventory}
                    onChange={(e) => setCInventory(e.target.value)}
                    placeholder="随身关键物品"
                    className="h-7.5 rounded-lg border border-border bg-surface-subtle/60 px-2 text-[11px] outline-none focus:border-ring"
                  />
                </div>

                <input
                  value={cNotes}
                  onChange={(e) => setCNotes(e.target.value)}
                  placeholder="当前状态 / 伤势 / 性格备注"
                  className="h-7.5 w-full rounded-lg border border-border bg-surface-subtle/60 px-2.5 text-[11.5px] outline-none focus:border-ring"
                />

                <div className="flex justify-end gap-1.5 pt-0.5">
                  <Button
                    type="button"
                    size="xs"
                    variant="ghost"
                    onClick={() => setCharComposerOpen(false)}
                  >
                    取消
                  </Button>
                  <Button type="submit" size="xs" loading={savingChar}>
                    {editingCharName ? "保存修改" : "保存人物"}
                  </Button>
                </div>
              </form>
            )}

            {/* Character Cards List */}
            {filteredCharacters.length === 0 ? (
              <div className="rounded-2xl border border-dashed border-border bg-card/60 px-4 py-8 text-center">
                <p className="text-xs font-medium text-foreground">
                  {characters.length === 0 ? "尚未登记人物档案" : "没有匹配的人物"}
                </p>
                <p className="mt-1 text-[11px] text-muted-foreground">
                  {characters.length === 0
                    ? "点击右上角「新建」录入核心角色，或在关系图谱一键抽取正文人物。"
                    : "换个关键词或切换定位筛选试试。"}
                </p>
                {characters.length === 0 && !charComposerOpen && (
                  <Button size="xs" className="mt-3" onClick={openNewCharForm}>
                    <Plus className="size-3.5" />
                    新建第一位角色
                  </Button>
                )}
              </div>
            ) : (
              <ul className="space-y-2">
                {filteredCharacters.map((ch) => {
                  const roleMeta = ROLE_META[ch.roleType] ?? ROLE_META.SUPPORTING;
                  const lifeMeta =
                    LIFECYCLE_LABELS[ch.lifecycleStatus ?? "ACTIVE"] ?? LIFECYCLE_LABELS.ACTIVE;
                  const isLocked = (ch.lockedFields?.length ?? 0) > 0;
                  const customEntries = Object.entries(ch.customAttributes ?? {}).filter(
                    ([, val]) => Boolean(val && val.trim()),
                  );
                  const initial = ch.name.trim().slice(0, 1) || "角";

                  return (
                    <li
                      key={ch.id}
                      className="group rounded-2xl border border-border bg-card p-3 shadow-2xs transition-colors hover:border-primary/40"
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div className="flex min-w-0 items-center gap-2">
                          <span
                            className={`flex size-7 shrink-0 items-center justify-center rounded-full border text-[11px] font-semibold ${roleMeta.avatar}`}
                          >
                            {initial}
                          </span>
                          <div className="min-w-0">
                            <div className="flex flex-wrap items-center gap-1">
                              <span
                                className={`truncate text-xs font-semibold text-foreground ${
                                  ch.lifecycleStatus === "DEAD" ? "line-through opacity-70" : ""
                                }`}
                              >
                                {ch.name}
                              </span>
                              <span
                                className={`rounded-full border px-1.5 py-0 text-[9.5px] font-medium ${roleMeta.badge}`}
                              >
                                {roleMeta.label}
                              </span>
                              {ch.lifecycleStatus && ch.lifecycleStatus !== "ACTIVE" && (
                                <span
                                  className={`rounded-full px-1.5 py-0 text-[9.5px] ${lifeMeta.className}`}
                                >
                                  {lifeMeta.label}
                                </span>
                              )}
                            </div>
                            {ch.aliases && (
                              <div className="mt-0.5 truncate text-[10.5px] text-muted-foreground">
                                别名：{ch.aliases}
                              </div>
                            )}
                          </div>
                        </div>

                        <div className="flex shrink-0 items-center gap-0.5">
                          <button
                            type="button"
                            title="编辑人物档案"
                            onClick={() => openEditCharForm(ch)}
                            className="rounded-md p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                          >
                            <Pencil className="size-3.5" />
                          </button>
                          <button
                            type="button"
                            title={
                              isLocked
                                ? "已锁定核心属性（点击解锁）"
                                : "锁定核心属性，防止 AI 自动覆盖"
                            }
                            onClick={() => void handleToggleCharacterLock(ch)}
                            className={`rounded-md p-1 transition-colors ${
                              isLocked
                                ? "bg-primary-soft text-primary"
                                : "text-muted-foreground hover:bg-muted hover:text-foreground"
                            }`}
                          >
                            {isLocked ? (
                              <Lock className="size-3.5" />
                            ) : (
                              <Unlock className="size-3.5" />
                            )}
                          </button>
                          {ch.lifecycleStatus === "DORMANT" && (
                            <button
                              type="button"
                              onClick={() => void handleWakeCharacter(ch)}
                              className="rounded-md border border-status-warning-border bg-status-warning-bg px-1.5 py-0.5 text-[10px] font-medium text-status-warning hover:opacity-85"
                            >
                              唤醒
                            </button>
                          )}
                          <button
                            type="button"
                            title="删除人物"
                            onClick={() => void handleDeleteCharacter(ch)}
                            className="rounded-md p-1 text-muted-foreground transition-colors hover:bg-status-danger-bg hover:text-status-danger"
                          >
                            <Trash2 className="size-3.5" />
                          </button>
                        </div>
                      </div>

                      {(customEntries.length > 0 ||
                        ch.realmOrLevel ||
                        ch.currentLocation ||
                        ch.inventory) && (
                        <div className="mt-2 flex flex-wrap gap-1">
                          {customEntries.length > 0 ? (
                            customEntries.map(([k, v]) => (
                              <span
                                key={k}
                                className="rounded-md bg-surface-subtle px-1.5 py-0.5 text-[10px] text-foreground"
                              >
                                <span className="text-muted-foreground">
                                  {charFieldLabels[k] ?? k}：
                                </span>
                                {v}
                              </span>
                            ))
                          ) : ch.realmOrLevel ? (
                            <span className="rounded-md bg-surface-subtle px-1.5 py-0.5 text-[10px] text-foreground">
                              <span className="text-muted-foreground">实力：</span>
                              {ch.realmOrLevel}
                            </span>
                          ) : null}
                          {ch.currentLocation && (
                            <span className="rounded-md bg-surface-subtle px-1.5 py-0.5 text-[10px] text-foreground">
                              <span className="text-muted-foreground">位置：</span>
                              {ch.currentLocation}
                            </span>
                          )}
                          {ch.inventory && (
                            <span className="rounded-md bg-surface-subtle px-1.5 py-0.5 text-[10px] text-foreground">
                              <span className="text-muted-foreground">物品：</span>
                              {ch.inventory}
                            </span>
                          )}
                        </div>
                      )}

                      {ch.statusNotes && (
                        <p className="mt-1.5 line-clamp-2 text-[11px] leading-relaxed text-muted-foreground">
                          {ch.statusNotes}
                        </p>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </>
        )}

        {/* ======================== TAB 3: WORLDBOOK (LIST-FIRST) ======================== */}
        {subTab === "world" && (
          <>
            <div className="space-y-2">
              <div className="flex items-center gap-1.5">
                <div className="relative flex-1">
                  <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
                  <input
                    value={worldQuery}
                    onChange={(e) => setWorldQuery(e.target.value)}
                    placeholder="搜索势力、法宝、地标、铁律..."
                    className="h-7.5 w-full rounded-xl border border-border bg-card pr-2.5 pl-7.5 text-[11.5px] text-foreground placeholder:text-muted-foreground/70 outline-none focus:border-ring"
                  />
                </div>
                <Button
                  size="xs"
                  variant={worldComposerOpen ? "secondary" : "default"}
                  onClick={() => setWorldComposerOpen((v) => !v)}
                  className="h-7.5 shrink-0 px-2.5"
                >
                  {worldComposerOpen ? <X className="size-3.5" /> : <Plus className="size-3.5" />}
                  {worldComposerOpen ? "收起" : "新建"}
                </Button>
              </div>

              {worldEntries.length > 0 && (
                <div className="flex items-center gap-1 overflow-x-auto pb-0.5 text-[10.5px]">
                  <button
                    type="button"
                    onClick={() => setWorldCatFilter("ALL")}
                    className={`shrink-0 rounded-full px-2.5 py-0.5 font-medium transition-colors ${
                      worldCatFilter === "ALL"
                        ? "bg-primary-soft text-primary-soft-foreground"
                        : "text-muted-foreground hover:bg-muted hover:text-foreground"
                    }`}
                  >
                    全部 ({worldEntries.length})
                  </button>
                  {WORLD_CATEGORIES.map((c) => (
                    <button
                      key={c.value}
                      type="button"
                      onClick={() => setWorldCatFilter(c.value)}
                      className={`shrink-0 rounded-full px-2.5 py-0.5 font-medium transition-colors ${
                        worldCatFilter === c.value
                          ? "bg-primary-soft text-primary-soft-foreground"
                          : "text-muted-foreground hover:bg-muted hover:text-foreground"
                      }`}
                    >
                      {c.label}
                    </button>
                  ))}
                </div>
              )}
            </div>

            {worldComposerOpen && (
              <form
                onSubmit={handleAddWorld}
                className="space-y-2 rounded-2xl border border-primary/35 bg-card p-3 shadow-sm"
              >
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-foreground">新增世界观词条</span>
                  <button
                    type="button"
                    onClick={() => setWorldComposerOpen(false)}
                    className="rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                  >
                    <X className="size-3.5" />
                  </button>
                </div>

                <div className="flex flex-wrap gap-1">
                  {WORLD_CATEGORIES.map((c) => (
                    <button
                      key={c.value}
                      type="button"
                      onClick={() => setWCat(c.value)}
                      className={`rounded-full px-2.5 py-0.5 text-[10.5px] font-medium transition-colors ${
                        wCat === c.value
                          ? "bg-primary text-primary-foreground"
                          : "bg-muted text-muted-foreground hover:text-foreground"
                      }`}
                    >
                      {c.label}
                    </button>
                  ))}
                </div>

                <input
                  required
                  value={wTitle}
                  onChange={(e) => setWTitle(e.target.value)}
                  placeholder="词条标题（如：大傩祭仪 / 青云宗）"
                  className="h-8 w-full rounded-lg border border-border bg-surface-subtle/60 px-2.5 text-xs outline-none focus:border-ring"
                />
                <textarea
                  rows={3}
                  value={wContent}
                  onChange={(e) => setWContent(e.target.value)}
                  placeholder="详细规则、禁忌、边界与背景描述..."
                  className="w-full resize-none rounded-lg border border-border bg-surface-subtle/60 p-2.5 text-xs leading-relaxed outline-none focus:border-ring"
                />
                <div className="flex justify-end gap-1.5">
                  <Button
                    type="button"
                    size="xs"
                    variant="ghost"
                    onClick={() => setWorldComposerOpen(false)}
                  >
                    取消
                  </Button>
                  <Button type="submit" size="xs" loading={savingWorld}>
                    保存词条
                  </Button>
                </div>
              </form>
            )}

            {filteredWorldEntries.length === 0 ? (
              <div className="rounded-2xl border border-dashed border-border bg-card/60 px-4 py-8 text-center">
                <p className="text-xs font-medium text-foreground">
                  {worldEntries.length === 0 ? "尚未录入世界观设定" : "没有匹配的词条"}
                </p>
                <p className="mt-1 text-[11px] text-muted-foreground">
                  记录力量体系、宗门势力、器物法宝与世界铁律，AI 动笔前会自动检索核验。
                </p>
                {worldEntries.length === 0 && !worldComposerOpen && (
                  <Button
                    size="xs"
                    className="mt-3"
                    onClick={() => setWorldComposerOpen(true)}
                  >
                    <Plus className="size-3.5" />
                    添加首条设定
                  </Button>
                )}
              </div>
            ) : (
              <ul className="space-y-2">
                {filteredWorldEntries.map((w) => (
                  <li
                    key={w.id}
                    className="rounded-2xl border border-border bg-card p-3 shadow-2xs transition-colors hover:border-primary/40"
                  >
                    <div className="flex items-start justify-between gap-2">
                      <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                        <span className="rounded-md bg-primary-soft px-1.5 py-0.5 text-[9.5px] font-medium text-primary-soft-foreground">
                          {WORLD_CATEGORY_LABELS[w.category] || w.category}
                        </span>
                        <span className="truncate text-xs font-semibold text-foreground">
                          {w.title}
                        </span>
                      </div>
                      <button
                        type="button"
                        title="删除词条"
                        onClick={() => void handleDeleteWorld(w)}
                        className="shrink-0 rounded-md p-1 text-muted-foreground transition-colors hover:bg-status-danger-bg hover:text-status-danger"
                      >
                        <Trash2 className="size-3.5" />
                      </button>
                    </div>
                    {w.content && (
                      <p className="mt-1.5 whitespace-pre-wrap text-[11px] leading-relaxed text-muted-foreground">
                        {w.content}
                      </p>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </>
        )}

        {/* ======================== TAB 4: FORESHADOWINGS (LIST-FIRST) ======================== */}
        {subTab === "foreshadowing" && (
          <>
            <div className="space-y-2">
              <div className="flex items-center gap-1.5">
                <div className="relative flex-1">
                  <Search className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
                  <input
                    value={clueQuery}
                    onChange={(e) => setClueQuery(e.target.value)}
                    placeholder="搜索伏笔编号、标题或表层线索..."
                    className="h-7.5 w-full rounded-xl border border-border bg-card pr-2.5 pl-7.5 text-[11.5px] text-foreground placeholder:text-muted-foreground/70 outline-none focus:border-ring"
                  />
                </div>
                <Button
                  size="xs"
                  variant={clueComposerOpen ? "secondary" : "default"}
                  onClick={() => (clueComposerOpen ? setClueComposerOpen(false) : openNewClueForm())}
                  className="h-7.5 shrink-0 px-2.5"
                >
                  {clueComposerOpen ? <X className="size-3.5" /> : <Plus className="size-3.5" />}
                  {clueComposerOpen ? "收起" : "新建"}
                </Button>
              </div>

              {foreshadowings.length > 0 && (
                <div className="flex items-center gap-1 overflow-x-auto pb-0.5 text-[10.5px]">
                  {(
                    [
                      ["ALL", `全部 (${foreshadowings.length})`],
                      ["ACTIVE", "进行中"],
                      ["PLANNED", "待埋设"],
                      ["RESOLVED", "已回收"],
                    ] as const
                  ).map(([val, lbl]) => (
                    <button
                      key={val}
                      type="button"
                      onClick={() => setClueFilter(val)}
                      className={`shrink-0 rounded-full px-2.5 py-0.5 font-medium transition-colors ${
                        clueFilter === val
                          ? "bg-primary-soft text-primary-soft-foreground"
                          : "text-muted-foreground hover:bg-muted hover:text-foreground"
                      }`}
                    >
                      {lbl}
                    </button>
                  ))}
                </div>
              )}
            </div>

            {/* Collapsible Foreshadowing Create / Edit Card */}
            {clueComposerOpen && (
              <form
                onSubmit={handleSaveForeshadowing}
                className="space-y-2.5 rounded-2xl border border-primary/35 bg-card p-3 shadow-sm"
              >
                <div className="flex items-center justify-between">
                  <span className="font-semibold text-foreground">
                    {editingClueId ? "编辑伏笔线索" : "登记新伏笔"}
                  </span>
                  <button
                    type="button"
                    onClick={() => setClueComposerOpen(false)}
                    className="rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
                  >
                    <X className="size-3.5" />
                  </button>
                </div>

                <input
                  required
                  value={fTitle}
                  onChange={(e) => setFTitle(e.target.value)}
                  placeholder="伏笔标题（如：残缺青铜玉佩）"
                  className="h-8 w-full rounded-lg border border-border bg-surface-subtle/60 px-2.5 text-xs outline-none focus:border-ring"
                />

                <div className="grid grid-cols-2 gap-2">
                  <div className="space-y-1">
                    <div className="text-[10px] text-muted-foreground">重要度</div>
                    <div className="grid grid-cols-3 gap-0.5 rounded-lg bg-muted p-0.5">
                      {IMPORTANCE_OPTIONS.map((opt) => (
                        <button
                          key={opt.value}
                          type="button"
                          onClick={() => setFImportance(opt.value)}
                          className={`rounded-md py-1 text-[10px] font-medium transition-all ${
                            fImportance === opt.value
                              ? "bg-card text-foreground shadow-2xs"
                              : "text-muted-foreground hover:text-foreground"
                          }`}
                        >
                          {opt.label}
                        </button>
                      ))}
                    </div>
                  </div>
                  <div className="space-y-1">
                    <div className="text-[10px] text-muted-foreground">揭示策略</div>
                    <div className="grid grid-cols-3 gap-0.5 rounded-lg bg-muted p-0.5">
                      {REVEAL_POLICY_OPTIONS.map((opt) => (
                        <button
                          key={opt.value}
                          type="button"
                          onClick={() => setFRevealPolicy(opt.value)}
                          className={`rounded-md py-1 text-[10px] font-medium transition-all ${
                            fRevealPolicy === opt.value
                              ? "bg-card text-foreground shadow-2xs"
                              : "text-muted-foreground hover:text-foreground"
                          }`}
                        >
                          {opt.label.slice(0, 2)}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>

                <div className="space-y-1">
                  <div className="text-[10px] text-muted-foreground">当前状态</div>
                  <div className="grid grid-cols-4 gap-0.5 rounded-lg bg-muted p-0.5">
                    {(
                      [
                        ["PLANNED", "待埋设"],
                        ["PLANTED", "已埋设"],
                        ["HINTED", "已照应"],
                        ["TRIGGERED", "待引爆"],
                      ] as const
                    ).map(([val, lbl]) => (
                      <button
                        key={val}
                        type="button"
                        onClick={() => setFStatus(val)}
                        className={`rounded-md py-1 text-[10px] font-medium transition-all ${
                          fStatus === val
                            ? "bg-card text-foreground shadow-2xs"
                            : "text-muted-foreground hover:text-foreground"
                        }`}
                      >
                        {lbl}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="grid grid-cols-3 gap-1.5">
                  <input
                    type="number"
                    min={1}
                    value={fChapter}
                    onChange={(e) => setFChapter(e.target.value)}
                    placeholder="埋设章"
                    className="h-7.5 rounded-lg border border-border bg-surface-subtle/60 px-2 text-[11px] outline-none focus:border-ring"
                  />
                  <input
                    type="number"
                    min={1}
                    value={fTargetMin}
                    onChange={(e) => setFTargetMin(e.target.value)}
                    placeholder="起收章"
                    className="h-7.5 rounded-lg border border-border bg-surface-subtle/60 px-2 text-[11px] outline-none focus:border-ring"
                  />
                  <input
                    type="number"
                    min={1}
                    value={fTargetMax}
                    onChange={(e) => setFTargetMax(e.target.value)}
                    placeholder="最晚回收"
                    className="h-7.5 rounded-lg border border-border bg-surface-subtle/60 px-2 text-[11px] outline-none focus:border-ring"
                  />
                </div>

                <textarea
                  rows={2}
                  value={fSurfaceClue}
                  onChange={(e) => setFSurfaceClue(e.target.value)}
                  placeholder="表层线索（读者与主角可见：玉佩遇血发烫、刻有半截残纹……）"
                  className="w-full resize-none rounded-lg border border-border bg-surface-subtle/60 p-2 text-xs leading-relaxed outline-none focus:border-ring"
                />
                <textarea
                  rows={2}
                  value={fHiddenTruth}
                  onChange={(e) => setFHiddenTruth(e.target.value)}
                  placeholder="幕后底牌（选填：未在导演槽启用引爆前，AI 写手绝不可提前剧透）"
                  className="w-full resize-none rounded-lg border border-status-warning-border/70 bg-status-warning-bg/40 p-2 text-xs leading-relaxed outline-none focus:border-ring"
                />

                <div className="flex items-center justify-between gap-2 rounded-lg border border-border/70 bg-surface-subtle/60 px-2.5 py-1.5">
                  <span className="text-[10.5px] text-muted-foreground">
                    作者锁定（AI 整理需经确认才可标为已回收）
                  </span>
                  <button
                    type="button"
                    role="switch"
                    aria-checked={fAuthorLocked}
                    onClick={() => setFAuthorLocked((v) => !v)}
                    className={`relative inline-flex h-4 w-7 shrink-0 items-center rounded-full transition-colors ${
                      fAuthorLocked ? "bg-primary" : "bg-muted"
                    }`}
                  >
                    <span
                      className={`inline-block size-3 rounded-full bg-card shadow-2xs transition-transform ${
                        fAuthorLocked ? "translate-x-3.5" : "translate-x-0.5"
                      }`}
                    />
                  </button>
                </div>

                <div className="flex justify-end gap-1.5">
                  <Button
                    type="button"
                    size="xs"
                    variant="ghost"
                    onClick={() => setClueComposerOpen(false)}
                  >
                    取消
                  </Button>
                  <Button type="submit" size="xs" loading={savingClue}>
                    {editingClueId ? "保存修改" : "登记伏笔"}
                  </Button>
                </div>
              </form>
            )}

            {/* Foreshadowing Cards List */}
            {filteredForeshadowings.length === 0 ? (
              <div className="rounded-2xl border border-dashed border-border bg-card/60 px-4 py-8 text-center">
                <p className="text-xs font-medium text-foreground">
                  {foreshadowings.length === 0 ? "尚未登记伏笔线索" : "没有匹配的伏笔"}
                </p>
                <p className="mt-1 text-[11px] text-muted-foreground">
                  将表面线索与幕后底牌分开记录，防剧透机制可自动护航长篇伏笔回收。
                </p>
                {foreshadowings.length === 0 && !clueComposerOpen && (
                  <Button size="xs" className="mt-3" onClick={openNewClueForm}>
                    <Plus className="size-3.5" />
                    登记首条伏笔
                  </Button>
                )}
              </div>
            ) : (
              <ul className="space-y-2">
                {filteredForeshadowings.map((f) => {
                  const statusMeta = FORESHADOW_STATUS_META[f.status] ?? {
                    label: f.status,
                    className: "bg-muted text-muted-foreground",
                  };
                  const surfaceText = f.surfaceClue || f.description;
                  const isActive =
                    f.status === "PLANTED" ||
                    f.status === "OPEN" ||
                    f.status === "HINTED" ||
                    f.status === "TRIGGERED";
                  const truthOpen = Boolean(revealedTruthIds[f.id]);

                  return (
                    <li
                      key={f.id}
                      className="rounded-2xl border border-border bg-card p-3 shadow-2xs transition-colors hover:border-primary/40"
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0 flex-1">
                          <div className="flex flex-wrap items-center gap-1">
                            {f.code && (
                              <span className="font-mono text-[10px] font-semibold text-primary">
                                {f.code}
                              </span>
                            )}
                            <span className="text-xs font-semibold text-foreground">{f.title}</span>
                            {f.importance && (
                              <span className="rounded-md bg-surface-subtle px-1.5 py-0.5 text-[9.5px] text-muted-foreground">
                                {IMPORTANCE_LABELS[f.importance] ?? f.importance}
                              </span>
                            )}
                            {f.authorLocked && (
                              <Lock className="size-3 text-primary" aria-label="作者已锁定" />
                            )}
                          </div>
                        </div>

                        <div className="flex shrink-0 items-center gap-0.5">
                          <span className={`rounded-full px-1.5 py-0.5 text-[9.5px] font-medium ${statusMeta.className}`}>
                            {statusMeta.label}
                          </span>
                          <button
                            type="button"
                            title="编辑伏笔"
                            onClick={() => openEditClueForm(f)}
                            className="rounded-md p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                          >
                            <Pencil className="size-3.5" />
                          </button>
                          <button
                            type="button"
                            title="删除伏笔"
                            onClick={() => void handleDeleteForeshadowing(f)}
                            className="rounded-md p-1 text-muted-foreground transition-colors hover:bg-status-danger-bg hover:text-status-danger"
                          >
                            <Trash2 className="size-3.5" />
                          </button>
                        </div>
                      </div>

                      {(f.plantedChapterNo ||
                        f.targetChapterMin ||
                        f.targetChapterMax ||
                        f.resolvedChapterNo) && (
                        <div className="mt-1.5 flex flex-wrap gap-1.5 font-mono text-[10px] text-muted-foreground">
                          {f.plantedChapterNo && (
                            <span className="rounded bg-surface-subtle px-1.5 py-0.5">
                              埋设 Ch.{f.plantedChapterNo}
                            </span>
                          )}
                          {(f.targetChapterMin || f.targetChapterMax) && (
                            <span className="rounded bg-surface-subtle px-1.5 py-0.5">
                              预计 Ch.{f.targetChapterMin ?? "?"}–{f.targetChapterMax ?? "?"}
                            </span>
                          )}
                          {f.resolvedChapterNo && (
                            <span className="rounded bg-status-success-bg px-1.5 py-0.5 text-status-success">
                              回收 Ch.{f.resolvedChapterNo}
                            </span>
                          )}
                        </div>
                      )}

                      {surfaceText && (
                        <p className="mt-1.5 text-[11px] leading-relaxed text-muted-foreground">
                          {surfaceText}
                        </p>
                      )}

                      {f.hiddenTruth && (
                        <div className="mt-2 rounded-xl border border-dashed border-status-warning-border bg-status-warning-bg/70 px-2.5 py-1.5 text-[11px]">
                          <button
                            type="button"
                            onClick={() =>
                              setRevealedTruthIds((prev) => ({
                                ...prev,
                                [f.id]: !prev[f.id],
                              }))
                            }
                            className="flex w-full items-center justify-between gap-2 text-[10px] font-medium text-status-warning"
                          >
                            <span className="inline-flex items-center gap-1">
                              {truthOpen ? <EyeOff className="size-3" /> : <Eye className="size-3" />}
                              <span>
                                幕后底牌 ·{" "}
                                {REVEAL_POLICY_LABELS[f.revealPolicy ?? "HIDE_TRUTH"] ?? f.revealPolicy}
                              </span>
                            </span>
                            <span>{truthOpen ? "隐藏" : "查看"}</span>
                          </button>
                          {truthOpen && (
                            <p className="mt-1 text-[11px] leading-relaxed text-foreground">
                              {f.hiddenTruth}
                            </p>
                          )}
                        </div>
                      )}

                      {f.plantedQuote && (
                        <p className="mt-1.5 border-l-2 border-primary/40 pl-2 text-[10.5px] italic text-muted-foreground">
                          「{f.plantedQuote}」
                        </p>
                      )}

                      <div className="mt-2 flex flex-wrap items-center gap-1 border-t border-border/50 pt-2">
                        {f.status === "PLANNED" && (
                          <button
                            type="button"
                            onClick={() => void handleTransitionForeshadowing(f, "PLANTED")}
                            className="rounded-md border border-border bg-surface-subtle px-2 py-0.5 text-[10px] font-medium text-foreground transition-colors hover:border-primary/50"
                          >
                            标为已埋
                          </button>
                        )}
                        {f.status === "DORMANT_CLUE" && (
                          <button
                            type="button"
                            onClick={() => void handleTransitionForeshadowing(f, "PLANTED")}
                            className="rounded-md border border-status-warning-border bg-status-warning-bg px-2 py-0.5 text-[10px] font-medium text-status-warning hover:opacity-85"
                          >
                            唤醒线索
                          </button>
                        )}
                        {isActive && (
                          <button
                            type="button"
                            onClick={() => void handleTransitionForeshadowing(f, "RESOLVED")}
                            className="rounded-md border border-status-success-border bg-status-success-bg px-2 py-0.5 text-[10px] font-medium text-status-success hover:opacity-85"
                          >
                            标为已回收
                          </button>
                        )}
                        {(isActive || f.status === "PLANNED" || f.status === "DORMANT_CLUE") && (
                          <button
                            type="button"
                            onClick={() => void handleTransitionForeshadowing(f, "ABANDONED")}
                            className="rounded-md px-2 py-0.5 text-[10px] text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                          >
                            弃用
                          </button>
                        )}
                        {(f.status === "RESOLVED" || f.status === "ABANDONED") && (
                          <button
                            type="button"
                            onClick={() => void handleTransitionForeshadowing(f, "PLANTED")}
                            className="rounded-md border border-border bg-surface-subtle px-2 py-0.5 text-[10px] text-muted-foreground transition-colors hover:text-foreground"
                          >
                            重新激活
                          </button>
                        )}
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
          </>
        )}
      </div>
    </div>
  );
}
