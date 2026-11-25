"use client";

import Link from "next/link";
import { useEffect, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
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

const ROLE_LABELS: Record<string, string> = {
  PROTAGONIST: "主角",
  SUPPORTING: "配角",
  ANTAGONIST: "反派",
};

const LIFECYCLE_LABELS: Record<string, { label: string; className: string }> = {
  CORE_PINNED: {
    label: "常驻核心",
    className: "bg-emerald-500/15 text-emerald-700 dark:text-emerald-400",
  },
  ACTIVE: {
    label: "活跃",
    className: "bg-muted text-muted-foreground",
  },
  DORMANT: {
    label: "休眠",
    className: "bg-amber-500/15 text-amber-700 dark:text-amber-400",
  },
  DEAD: {
    label: "已退场",
    className: "bg-rose-500/15 text-rose-700 dark:text-rose-400",
  },
};

const WORLD_CATEGORY_LABELS: Record<string, string> = {
  WORLD: "世界观",
  POWER_SYSTEM: "力量体系",
  FACTION: "宗门势力",
  ITEM: "器物法宝",
  LOCATION: "地标秘境",
  RULE: "铁律禁忌",
};

const FORESHADOW_STATUS_META: Record<string, { label: string; className: string }> = {
  PLANNED: { label: "待埋设", className: "bg-muted text-muted-foreground" },
  PLANTED: { label: "已埋设", className: "bg-sky-500/15 text-sky-700 dark:text-sky-400" },
  OPEN: { label: "已埋设", className: "bg-sky-500/15 text-sky-700 dark:text-sky-400" },
  HINTED: { label: "已照应", className: "bg-indigo-500/15 text-indigo-700 dark:text-indigo-400" },
  TRIGGERED: { label: "待引爆", className: "bg-amber-500/20 text-amber-700 dark:text-amber-400" },
  RESOLVED: { label: "已回收", className: "bg-emerald-500/15 text-emerald-700 dark:text-emerald-400" },
  DORMANT_CLUE: { label: "沉睡", className: "bg-amber-500/10 text-amber-600 dark:text-amber-400" },
  ABANDONED: { label: "已弃用", className: "bg-muted/70 text-muted-foreground line-through" },
};

const IMPORTANCE_LABELS: Record<string, string> = {
  MAIN_ARC: "主线",
  SUBPLOT: "支线",
  DETAIL: "细节",
};

const REVEAL_POLICY_LABELS: Record<string, string> = {
  HIDE_TRUTH: "严守底牌",
  HINT_ONLY: "只许暗示",
  REVEAL_NOW: "允许揭晓",
};

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

interface LorePanelProps {
  bookId: string;
  bookGenreCode?: string;
  anchorRefreshKey?: number;
}

export function LorePanel({ bookId, bookGenreCode, anchorRefreshKey = 0 }: LorePanelProps) {
  const [subTab, setSubTab] = useState<"charter" | "characters" | "world" | "foreshadowing">(
    "characters",
  );
  const [genreProfile, setGenreProfile] = useState<GenreProfileItem | null>(null);
  const [charter, setCharter] = useState<BookCharterItem | null>(null);
  const [charterDraft, setCharterDraft] = useState("");
  const [savingCharter, setSavingCharter] = useState(false);
  const [charterNotice, setCharterNotice] = useState<string | null>(null);
  const [styleAnchors, setStyleAnchors] = useState<StyleAnchorItem[]>([]);
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

  // Character form
  const [cName, setCName] = useState("");
  const [cRole, setCRole] = useState("PROTAGONIST");
  const [cAliases, setCAliases] = useState("");
  const [cLocation, setCLocation] = useState("");
  const [cNotes, setCNotes] = useState("");
  const [cInventory, setCInventory] = useState("");
  const [cCustomAttrs, setCCustomAttrs] = useState<Record<string, string>>({});

  // World entry form
  const [wCat, setWCat] = useState("WORLD");
  const [wTitle, setWTitle] = useState("");
  const [wContent, setWContent] = useState("");

  // Foreshadowing form
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
  }, [bookId, effectiveGenreCode, anchorRefreshKey]);

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
    setCharterNotice(null);
    try {
      const updated = await booksApi.updateCharter(bookId, charterDraft);
      setCharter(updated);
      setCharterDraft(updated.effectiveCharter);
      setCharterNotice(
        updated.charterSource === "AUTHOR_DEFINED"
          ? "已锁定故事档案，AI 后续创作将严格遵守且不会擅自覆盖。"
          : "已清空手填档案，将按自动归纳或简介兜底。",
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

  async function handleToggleBookDoc(doc: LibraryDocumentItem) {
    setError(null);
    try {
      const updated = await libraryApi.setBookBinding(bookId, doc.id, !doc.boundToBook);
      setLibraryDocs((prev) => prev.map((d) => (d.id === updated.id ? updated : d)));
    } catch (err) {
      setError(err instanceof Error ? err.message : "更新参考资料开关失败");
    }
  }

  async function handleAddCharacter(e: FormEvent) {
    e.preventDefault();
    if (!cName.trim()) return;
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
      setCName("");
      setCAliases("");
      setCLocation("");
      setCNotes("");
      setCInventory("");
      setCCustomAttrs({});
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存人物失败");
    }
  }

  async function handleWakeCharacter(ch: CharacterItem) {
    setError(null);
    try {
      const updated = await loreApi.wakeCharacter(bookId, ch.id);
      setCharacters((prev) => prev.map((item) => (item.id === updated.id ? updated : item)));
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
    } catch (err) {
      setError(err instanceof Error ? err.message : "更新人物锁定失败");
    }
  }

  async function handleAddWorld(e: FormEvent) {
    e.preventDefault();
    if (!wTitle.trim()) return;
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
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存世界观失败");
    }
  }

  async function handleAddForeshadowing(e: FormEvent) {
    e.preventDefault();
    if (!fTitle.trim()) return;
    setError(null);
    try {
      const plantedNo = Number.parseInt(fChapter, 10);
      const minNo = Number.parseInt(fTargetMin, 10);
      const maxNo = Number.parseInt(fTargetMax, 10);
      const surface = fSurfaceClue.trim();
      const saved = await loreApi.upsertForeshadowing(bookId, {
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
      setFTitle("");
      setFChapter("");
      setFTargetMin("");
      setFTargetMax("");
      setFSurfaceClue("");
      setFHiddenTruth("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存伏笔失败");
    }
  }

  async function handleTransitionForeshadowing(item: ForeshadowingItem, targetStatus: string) {
    setError(null);
    try {
      const saved = await loreApi.transitionForeshadowing(bookId, item.id, {
        targetStatus,
      });
      setForeshadowings((prev) => prev.map((f) => (f.id === saved.id ? saved : f)));
    } catch (err) {
      setError(err instanceof Error ? err.message : "更新伏笔状态失败");
    }
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden text-xs">
      <div className="flex items-center justify-between gap-2 border-b border-border bg-muted/30 px-3 py-2 text-[11px] text-muted-foreground">
        <span className="truncate">维护故事档案、文风锚点、人物与伏笔底牌</span>
        {genreProfile && (
          <span className="shrink-0 rounded bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary">
            {genreProfile.shortName}
          </span>
        )}
      </div>

      {proposals.length > 0 && (
        <div className="border-b border-amber-500/30 bg-amber-500/10 px-3 py-2 space-y-1.5">
          <div className="flex items-center justify-between text-[11px] font-semibold text-amber-800 dark:text-amber-300">
            <span>待确认的重要设定变更（{proposals.length}）</span>
            <span className="text-[10px] font-normal">需你确认后才会生效</span>
          </div>
          <ul className="space-y-1.5">
            {proposals.slice(0, 3).map((proposal) => {
              const busy = decidingProposalId === proposal.id;
              return (
                <li
                  key={proposal.id}
                  className="rounded border border-amber-500/30 bg-background/90 p-2 space-y-1"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-medium text-foreground truncate">
                      {proposal.targetName || "设定变更"}
                    </span>
                    <span className="shrink-0 rounded bg-amber-500/15 px-1.5 py-0.5 text-[10px] text-amber-700 dark:text-amber-400">
                      {PROPOSAL_TYPE_LABELS[proposal.proposalType] ?? proposal.proposalType}
                    </span>
                  </div>
                  <p className="text-[11px] text-muted-foreground leading-snug">
                    {proposal.summary}
                  </p>
                  <div className="flex justify-end gap-1.5 pt-0.5">
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => void handleDecideProposal(proposal, "REJECT")}
                      className="rounded border border-border px-2 py-0.5 text-[10px] text-muted-foreground hover:bg-muted disabled:opacity-50"
                    >
                      驳回
                    </button>
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => void handleDecideProposal(proposal, "ACCEPT")}
                      className="rounded bg-amber-600 px-2 py-0.5 text-[10px] text-white hover:bg-amber-700 disabled:opacity-50"
                    >
                      {busy ? "处理中..." : "采纳生效"}
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>
        </div>
      )}

      <div className="grid grid-cols-4 border-b border-border text-[11px] font-medium">
        <button
          type="button"
          onClick={() => setSubTab("charter")}
          className={`py-2 ${
            subTab === "charter"
              ? "border-b-2 border-primary text-foreground"
              : "text-muted-foreground"
          }`}
        >
          档案
        </button>
        <button
          type="button"
          onClick={() => setSubTab("characters")}
          className={`py-2 ${
            subTab === "characters"
              ? "border-b-2 border-primary text-foreground"
              : "text-muted-foreground"
          }`}
        >
          人物 ({characters.length})
        </button>
        <button
          type="button"
          onClick={() => setSubTab("world")}
          className={`py-2 ${
            subTab === "world"
              ? "border-b-2 border-primary text-foreground"
              : "text-muted-foreground"
          }`}
        >
          世界观 ({worldEntries.length})
        </button>
        <button
          type="button"
          onClick={() => setSubTab("foreshadowing")}
          className={`py-2 ${
            subTab === "foreshadowing"
              ? "border-b-2 border-primary text-foreground"
              : "text-muted-foreground"
          }`}
        >
          伏笔 ({foreshadowings.length})
        </button>
      </div>

      {error && (
        <div className="border-b border-destructive/40 bg-destructive/10 px-3 py-1.5 text-[11px] text-destructive">
          {error}
        </div>
      )}

      <div className="flex-1 overflow-y-auto p-3 space-y-3">
        {subTab === "charter" && (
          <>
            <form
              onSubmit={handleSaveCharter}
              className="space-y-2 rounded-lg border border-border bg-background p-2.5"
            >
              <div className="flex items-center justify-between gap-2">
                <span className="font-semibold">核心故事档案</span>
                <span
                  className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${
                    charter?.charterSource === "AUTHOR_DEFINED"
                      ? "bg-emerald-500/15 text-emerald-700 dark:text-emerald-400"
                      : charter?.charterSource === "AUTO_INFERRED"
                        ? "bg-sky-500/15 text-sky-700 dark:text-sky-400"
                        : "bg-muted text-muted-foreground"
                  }`}
                >
                  {charter?.charterSource === "AUTHOR_DEFINED"
                    ? "作者已锁定"
                    : charter?.charterSource === "AUTO_INFERRED"
                      ? "AI 已从前文整理"
                      : "未填写"}
                </span>
              </div>
              <p className="text-[11px] leading-relaxed text-muted-foreground">
                开书时可不填，写完前几章后系统会自动整理主线、人称与基调；你随时可修改并锁定，锁定后 AI 不会覆盖。
              </p>
              <textarea
                rows={5}
                value={charterDraft}
                onChange={(e) => setCharterDraft(e.target.value)}
                placeholder="例如：第三人称限知，冷峻写实基调。主角为查清旧案真相踏入迷雾……"
                className="w-full resize-none rounded border border-input bg-background p-2 text-xs leading-relaxed outline-none focus:ring-1 focus:ring-ring"
              />
              {charterNotice && (
                <div className="rounded bg-emerald-500/10 px-2 py-1 text-[11px] text-emerald-700 dark:text-emerald-400">
                  {charterNotice}
                </div>
              )}
              <Button type="submit" size="xs" className="w-full" disabled={savingCharter}>
                {savingCharter
                  ? "保存中..."
                  : charter?.charterSource === "AUTO_INFERRED"
                    ? "确认并锁定故事档案"
                    : "保存故事档案"}
              </Button>
            </form>

            {/* Phase 5.8C: Style Anchors Card */}
            <div className="space-y-2 rounded-lg border border-border bg-background p-2.5">
              <div className="flex items-center justify-between gap-2">
                <span className="font-semibold">文风锚点（去 AI 味参考）</span>
                <span className="font-mono text-[10px] text-muted-foreground">
                  {enabledAnchorChars}/900 字
                </span>
              </div>
              <p className="text-[11px] leading-relaxed text-muted-foreground">
                收藏 1~3 段你最满意的亲笔正文（或直接在中栏划词一键设为锚点），AI 每次动笔都会对齐你的句式节奏与用词克制度。
              </p>

              {anchorQuotaError && (
                <div className="flex items-center justify-between gap-2 rounded bg-amber-500/15 px-2 py-1.5 text-[11px] text-amber-800 dark:text-amber-300">
                  <span>{anchorQuotaError}</span>
                  <Link href="/vip" className="shrink-0 font-semibold underline">
                    升级权益 →
                  </Link>
                </div>
              )}

              <form onSubmit={handleAddStyleAnchor} className="space-y-1.5">
                <input
                  value={anchorTitle}
                  onChange={(e) => setAnchorTitle(e.target.value)}
                  placeholder="锚点名称（如：雪夜白描参考）"
                  className="w-full rounded border border-input bg-background px-2 py-1 text-xs"
                />
                <textarea
                  rows={3}
                  value={anchorText}
                  onChange={(e) => setAnchorText(e.target.value)}
                  placeholder="粘贴 100~300 字最具代表性的文风段落（总启用字数上限 900 字）..."
                  className="w-full resize-none rounded border border-input bg-background p-2 text-xs leading-relaxed outline-none focus:ring-1 focus:ring-ring"
                />
                <Button type="submit" size="xs" variant="outline" className="w-full" disabled={savingAnchor}>
                  {savingAnchor ? "保存中..." : "+ 添加文风锚点"}
                </Button>
              </form>

              {styleAnchors.length > 0 && (
                <ul className="space-y-1.5 pt-1">
                  {styleAnchors.map((anchor) => (
                    <li
                      key={anchor.id}
                      className={`rounded border p-2 ${
                        anchor.enabled
                          ? "border-primary/30 bg-primary/5"
                          : "border-border/70 bg-muted/20 opacity-70"
                      }`}
                    >
                      <div className="flex items-center justify-between gap-1.5">
                        <span className="truncate font-medium">{anchor.title || "文风锚点"}</span>
                        <div className="flex shrink-0 items-center gap-1.5">
                          <button
                            type="button"
                            onClick={() => void handleToggleStyleAnchor(anchor)}
                            className="rounded border border-border px-1.5 py-0.5 text-[10px] hover:bg-muted"
                          >
                            {anchor.enabled ? "已启用" : "已停用"}
                          </button>
                          <button
                            type="button"
                            onClick={() =>
                              void styleAnchorsApi
                                .remove(bookId, anchor.id)
                                .then(() =>
                                  setStyleAnchors((prev) => prev.filter((x) => x.id !== anchor.id)),
                                )
                            }
                            className="text-destructive hover:underline"
                          >
                            删
                          </button>
                        </div>
                      </div>
                      <p className="mt-1 line-clamp-3 whitespace-pre-wrap text-[11px] text-muted-foreground">
                        {anchor.excerptText}
                      </p>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div className="space-y-2 rounded-lg border border-border bg-background p-2.5">
              <div className="flex items-center justify-between">
                <span className="font-semibold">本书参考资料 ({libraryDocs.length})</span>
                <Link href="/library" className="text-[11px] text-primary hover:underline">
                  管理资料库 →
                </Link>
              </div>
              <p className="text-[11px] text-muted-foreground">
                勾选允许 AI 在本书中参考的个人或平台资料（若与本书设定冲突，始终以本书为准）。
              </p>
              {libraryDocs.length === 0 ? (
                <p className="py-2 text-center text-[11px] text-muted-foreground">
                  暂无可用参考资料，可前往「我的资料库」上传。
                </p>
              ) : (
                <ul className="space-y-1.5">
                  {libraryDocs.map((doc) => (
                    <li
                      key={doc.id}
                      className="flex items-center justify-between gap-2 rounded border border-border/70 px-2 py-1.5"
                    >
                      <label className="flex min-w-0 flex-1 cursor-pointer items-center gap-2">
                        <input
                          type="checkbox"
                          checked={doc.boundToBook}
                          onChange={() => void handleToggleBookDoc(doc)}
                        />
                        <span className="truncate text-[11px] font-medium">{doc.title}</span>
                      </label>
                      <span className="shrink-0 rounded bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
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
          </>
        )}

        {subTab === "characters" && (
          <>
            <form
              onSubmit={handleAddCharacter}
              className="space-y-1.5 rounded-lg border border-border bg-background p-2.5"
            >
              <div className="flex gap-1.5">
                <input
                  required
                  value={cName}
                  onChange={(e) => setCName(e.target.value)}
                  placeholder="角色姓名"
                  className="min-w-0 flex-1 rounded border border-input bg-background px-2 py-1 text-xs"
                />
                <select
                  value={cRole}
                  onChange={(e) => setCRole(e.target.value)}
                  className="rounded border border-input bg-background px-1.5 py-1 text-[11px]"
                >
                  <option value="PROTAGONIST">主角</option>
                  <option value="SUPPORTING">配角</option>
                  <option value="ANTAGONIST">反派</option>
                </select>
              </div>
              <input
                value={cAliases}
                onChange={(e) => setCAliases(e.target.value)}
                placeholder="别名/化名/称谓（逗号分隔）"
                className="w-full rounded border border-input bg-background px-2 py-1 text-xs"
              />
              {/* Dynamic Genre-Specific Character Fields */}
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
                    className="rounded border border-input bg-background px-2 py-1 text-xs"
                  />
                ))}
              </div>
              <div className="grid grid-cols-2 gap-1.5">
                <input
                  value={cLocation}
                  onChange={(e) => setCLocation(e.target.value)}
                  placeholder="当前位置"
                  className="rounded border border-input bg-background px-2 py-1 text-xs"
                />
                <input
                  value={cInventory}
                  onChange={(e) => setCInventory(e.target.value)}
                  placeholder="随身关键物品"
                  className="rounded border border-input bg-background px-2 py-1 text-xs"
                />
              </div>
              <input
                value={cNotes}
                onChange={(e) => setCNotes(e.target.value)}
                placeholder="当前状态/伤势/性格备注"
                className="w-full rounded border border-input bg-background px-2 py-1 text-xs"
              />
              <Button type="submit" size="xs" className="w-full">
                保存 / 更新人物设定
              </Button>
            </form>

            <ul className="space-y-2">
              {characters.map((ch) => {
                const lifeMeta =
                  LIFECYCLE_LABELS[ch.lifecycleStatus ?? "ACTIVE"] ?? LIFECYCLE_LABELS.ACTIVE;
                const isLocked = (ch.lockedFields?.length ?? 0) > 0;
                const customEntries = Object.entries(ch.customAttributes ?? {}).filter(
                  ([, val]) => Boolean(val && val.trim()),
                );
                return (
                  <li key={ch.id} className="rounded-lg border border-border bg-background p-2.5">
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex min-w-0 items-center gap-1.5">
                        <span className="truncate font-semibold">{ch.name}</span>
                        <span className={`shrink-0 rounded px-1.5 py-0.5 text-[10px] ${lifeMeta.className}`}>
                          {lifeMeta.label}
                        </span>
                      </div>
                      <div className="flex shrink-0 items-center gap-1.5">
                        <span className="rounded bg-muted px-1.5 py-0.5 text-[10px]">
                          {ROLE_LABELS[ch.roleType] || ch.roleType}
                        </span>
                        <button
                          type="button"
                          title="锁定后 AI 自动整理不会覆盖该人物的核心定位与属性"
                          onClick={() => void handleToggleCharacterLock(ch)}
                          className={`rounded px-1.5 py-0.5 text-[10px] ${
                            isLocked
                              ? "bg-primary/15 text-primary"
                              : "border border-border text-muted-foreground hover:bg-muted"
                          }`}
                        >
                          {isLocked ? "已锁" : "锁定"}
                        </button>
                        {ch.lifecycleStatus === "DORMANT" && (
                          <button
                            type="button"
                            onClick={() => void handleWakeCharacter(ch)}
                            className="rounded bg-amber-600 px-1.5 py-0.5 text-[10px] text-white hover:bg-amber-700"
                          >
                            唤醒
                          </button>
                        )}
                        <button
                          type="button"
                          onClick={() =>
                            void loreApi
                              .deleteCharacter(bookId, ch.id)
                              .then(() =>
                                setCharacters((prev) => prev.filter((x) => x.id !== ch.id)),
                              )
                          }
                          className="text-destructive hover:underline"
                        >
                          删
                        </button>
                      </div>
                    </div>
                    {ch.aliases && (
                      <div className="mt-1 text-[11px] text-muted-foreground">
                        别名：{ch.aliases}
                      </div>
                    )}
                    {customEntries.length > 0 ? (
                      <div className="mt-1 flex flex-wrap gap-1.5">
                        {customEntries.map(([k, v]) => (
                          <span
                            key={k}
                            className="rounded bg-muted/70 px-1.5 py-0.5 text-[10px] text-foreground"
                          >
                            {charFieldLabels[k] ?? k}：{v}
                          </span>
                        ))}
                      </div>
                    ) : (
                      ch.realmOrLevel && (
                        <div className="mt-1 text-[11px] text-muted-foreground">
                          实力：{ch.realmOrLevel}
                        </div>
                      )
                    )}
                    {ch.currentLocation && (
                      <div className="mt-0.5 text-[11px] text-muted-foreground">
                        位置：{ch.currentLocation}
                      </div>
                    )}
                    {ch.inventory && (
                      <div className="text-[11px] text-muted-foreground">
                        物品：{ch.inventory}
                      </div>
                    )}
                    {ch.statusNotes && (
                      <div className="mt-0.5 text-[11px] text-muted-foreground">
                        状态：{ch.statusNotes}
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          </>
        )}

        {subTab === "world" && (
          <>
            <form
              onSubmit={handleAddWorld}
              className="space-y-1.5 rounded-lg border border-border bg-background p-2.5"
            >
              <div className="flex gap-1.5">
                <select
                  value={wCat}
                  onChange={(e) => setWCat(e.target.value)}
                  className="rounded border border-input bg-background px-1.5 py-1 text-[11px]"
                >
                  <option value="WORLD">世界观</option>
                  <option value="POWER_SYSTEM">力量体系</option>
                  <option value="FACTION">宗门势力</option>
                  <option value="ITEM">器物法宝</option>
                  <option value="LOCATION">地标秘境</option>
                  <option value="RULE">铁律禁忌</option>
                </select>
                <input
                  required
                  value={wTitle}
                  onChange={(e) => setWTitle(e.target.value)}
                  placeholder="词条标题"
                  className="min-w-0 flex-1 rounded border border-input bg-background px-2 py-1 text-xs"
                />
              </div>
              <textarea
                rows={3}
                value={wContent}
                onChange={(e) => setWContent(e.target.value)}
                placeholder="详细规则与背景描述..."
                className="w-full resize-none rounded border border-input bg-background px-2 py-1 text-xs"
              />
              <Button type="submit" size="xs" className="w-full">
                添加世界观词条
              </Button>
            </form>

            <ul className="space-y-2">
              {worldEntries.map((w) => (
                <li key={w.id} className="rounded-lg border border-border bg-background p-2.5">
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-semibold">{w.title}</span>
                    <div className="flex items-center gap-1.5">
                      <span className="rounded bg-muted px-1.5 py-0.5 text-[10px]">
                        {WORLD_CATEGORY_LABELS[w.category] || w.category}
                      </span>
                      <button
                        type="button"
                        onClick={() =>
                          void loreApi
                            .deleteWorldEntry(bookId, w.id)
                            .then(() =>
                              setWorldEntries((prev) => prev.filter((x) => x.id !== w.id)),
                            )
                        }
                        className="text-destructive hover:underline"
                      >
                        删
                      </button>
                    </div>
                  </div>
                  <p className="mt-1 whitespace-pre-wrap text-[11px] text-muted-foreground">
                    {w.content}
                  </p>
                </li>
              ))}
            </ul>
          </>
        )}

        {subTab === "foreshadowing" && (
          <>
            <form
              onSubmit={handleAddForeshadowing}
              className="space-y-1.5 rounded-lg border border-border bg-background p-2.5"
            >
              <input
                required
                value={fTitle}
                onChange={(e) => setFTitle(e.target.value)}
                placeholder="伏笔标题（如：残缺青铜玉佩）"
                className="w-full rounded border border-input bg-background px-2 py-1 text-xs"
              />
              <div className="grid grid-cols-3 gap-1">
                <select
                  value={fImportance}
                  onChange={(e) => setFImportance(e.target.value)}
                  className="rounded border border-input bg-background px-1.5 py-1 text-[11px]"
                >
                  <option value="MAIN_ARC">主线伏笔</option>
                  <option value="SUBPLOT">支线伏笔</option>
                  <option value="DETAIL">细节呼应</option>
                </select>
                <select
                  value={fRevealPolicy}
                  onChange={(e) => setFRevealPolicy(e.target.value)}
                  className="rounded border border-input bg-background px-1.5 py-1 text-[11px]"
                >
                  <option value="HIDE_TRUTH">严守底牌</option>
                  <option value="HINT_ONLY">只许暗示</option>
                  <option value="REVEAL_NOW">允许揭晓</option>
                </select>
                <select
                  value={fStatus}
                  onChange={(e) => setFStatus(e.target.value)}
                  className="rounded border border-input bg-background px-1.5 py-1 text-[11px]"
                >
                  <option value="PLANNED">待埋设</option>
                  <option value="PLANTED">已埋设</option>
                  <option value="HINTED">已照应</option>
                  <option value="TRIGGERED">待引爆</option>
                </select>
              </div>
              <div className="grid grid-cols-3 gap-1">
                <input
                  type="number"
                  min={1}
                  value={fChapter}
                  onChange={(e) => setFChapter(e.target.value)}
                  placeholder="埋设章(选填)"
                  className="rounded border border-input bg-background px-2 py-1 text-[11px]"
                />
                <input
                  type="number"
                  min={1}
                  value={fTargetMin}
                  onChange={(e) => setFTargetMin(e.target.value)}
                  placeholder="预计起收章"
                  className="rounded border border-input bg-background px-2 py-1 text-[11px]"
                />
                <input
                  type="number"
                  min={1}
                  value={fTargetMax}
                  onChange={(e) => setFTargetMax(e.target.value)}
                  placeholder="最晚回收章"
                  className="rounded border border-input bg-background px-2 py-1 text-[11px]"
                />
              </div>
              <textarea
                rows={2}
                value={fSurfaceClue}
                onChange={(e) => setFSurfaceClue(e.target.value)}
                placeholder="表面线索（读者与主角可见：玉佩遇血发烫、刻有半截残纹……）"
                className="w-full resize-none rounded border border-input bg-background px-2 py-1 text-xs"
              />
              <textarea
                rows={2}
                value={fHiddenTruth}
                onChange={(e) => setFHiddenTruth(e.target.value)}
                placeholder="幕后真相/底牌（选填：未启用引爆前，AI 写手绝不可提前剧透）"
                className="w-full resize-none rounded border border-input bg-background px-2 py-1 text-xs"
              />
              <label className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
                <input
                  type="checkbox"
                  checked={fAuthorLocked}
                  onChange={(e) => setFAuthorLocked(e.target.checked)}
                />
                <span>作者锁定（AI 整理记忆时需你确认才可标为已回收）</span>
              </label>
              <Button type="submit" size="xs" className="w-full">
                登记伏笔
              </Button>
            </form>

            <ul className="space-y-2">
              {foreshadowings.map((f) => {
                const statusMeta =
                  FORESHADOW_STATUS_META[f.status] ?? {
                    label: f.status,
                    className: "bg-muted text-muted-foreground",
                  };
                const surfaceText = f.surfaceClue || f.description;
                const isActive =
                  f.status === "PLANTED" ||
                  f.status === "OPEN" ||
                  f.status === "HINTED" ||
                  f.status === "TRIGGERED";
                return (
                  <li key={f.id} className="rounded-lg border border-border bg-background p-2.5">
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-1">
                          {f.code && (
                            <span className="font-mono text-[10px] font-semibold text-primary">
                              {f.code}
                            </span>
                          )}
                          <span className="font-semibold">{f.title}</span>
                          {f.importance && (
                            <span className="rounded bg-muted px-1 py-0.5 text-[9px] text-muted-foreground">
                              {IMPORTANCE_LABELS[f.importance] ?? f.importance}
                            </span>
                          )}
                          {f.authorLocked && (
                            <span className="rounded bg-primary/10 px-1 py-0.5 text-[9px] text-primary">
                              锁
                            </span>
                          )}
                        </div>
                      </div>
                      <div className="flex shrink-0 items-center gap-1">
                        <span className={`rounded px-1.5 py-0.5 text-[10px] ${statusMeta.className}`}>
                          {statusMeta.label}
                        </span>
                        <button
                          type="button"
                          onClick={() =>
                            void loreApi
                              .deleteForeshadowing(bookId, f.id)
                              .then(() =>
                                setForeshadowings((prev) => prev.filter((x) => x.id !== f.id)),
                              )
                          }
                          className="text-destructive hover:underline"
                        >
                          删
                        </button>
                      </div>
                    </div>

                    <div className="mt-1 flex flex-wrap gap-x-2 gap-y-0.5 font-mono text-[10px] text-muted-foreground">
                      {f.plantedChapterNo && <span>埋设:第{f.plantedChapterNo}章</span>}
                      {(f.targetChapterMin || f.targetChapterMax) && (
                        <span>
                          预计回收:第{f.targetChapterMin ?? "?"}~{f.targetChapterMax ?? "?"}章
                        </span>
                      )}
                      {f.resolvedChapterNo && <span>回收:第{f.resolvedChapterNo}章</span>}
                    </div>

                    {surfaceText && (
                      <p className="mt-1 text-[11px] text-muted-foreground">{surfaceText}</p>
                    )}

                    {f.hiddenTruth && (
                      <div className="mt-1.5 rounded border border-dashed border-amber-500/40 bg-amber-500/5 px-2 py-1 text-[11px]">
                        <div className="flex items-center justify-between text-[10px] text-amber-700 dark:text-amber-400">
                          <span className="font-medium">幕后底牌</span>
                          <span>
                            {REVEAL_POLICY_LABELS[f.revealPolicy ?? "HIDE_TRUTH"] ?? f.revealPolicy}
                          </span>
                        </div>
                        <p className="mt-0.5 text-muted-foreground">{f.hiddenTruth}</p>
                      </div>
                    )}

                    {f.plantedQuote && (
                      <p className="mt-1 border-l-2 border-primary/40 pl-2 text-[10px] italic text-muted-foreground">
                        「{f.plantedQuote}」
                      </p>
                    )}

                    <div className="mt-2 flex flex-wrap items-center gap-1.5">
                      {f.status === "PLANNED" && (
                        <button
                          type="button"
                          onClick={() => void handleTransitionForeshadowing(f, "PLANTED")}
                          className="rounded border border-border px-1.5 py-0.5 text-[10px] hover:bg-muted"
                        >
                          标为已埋
                        </button>
                      )}
                      {f.status === "DORMANT_CLUE" && (
                        <button
                          type="button"
                          onClick={() => void handleTransitionForeshadowing(f, "PLANTED")}
                          className="rounded bg-amber-600 px-1.5 py-0.5 text-[10px] text-white hover:bg-amber-700"
                        >
                          唤醒线索
                        </button>
                      )}
                      {isActive && (
                        <button
                          type="button"
                          onClick={() => void handleTransitionForeshadowing(f, "RESOLVED")}
                          className="rounded bg-emerald-600/90 px-1.5 py-0.5 text-[10px] text-white hover:bg-emerald-600"
                        >
                          标为已回收
                        </button>
                      )}
                      {(isActive || f.status === "PLANNED" || f.status === "DORMANT_CLUE") && (
                        <button
                          type="button"
                          onClick={() => void handleTransitionForeshadowing(f, "ABANDONED")}
                          className="rounded border border-border px-1.5 py-0.5 text-[10px] text-muted-foreground hover:bg-muted"
                        >
                          弃用
                        </button>
                      )}
                      {(f.status === "RESOLVED" || f.status === "ABANDONED") && (
                        <button
                          type="button"
                          onClick={() => void handleTransitionForeshadowing(f, "PLANTED")}
                          className="rounded border border-border px-1.5 py-0.5 text-[10px] text-muted-foreground hover:bg-muted"
                        >
                          重新激活
                        </button>
                      )}
                    </div>
                  </li>
                );
              })}
            </ul>
          </>
        )}
      </div>
    </div>
  );
}
