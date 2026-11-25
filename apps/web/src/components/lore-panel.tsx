"use client";

import Link from "next/link";
import { useEffect, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import {
  booksApi,
  libraryApi,
  loreApi,
  type BookCharterItem,
  type CharacterItem,
  type ForeshadowingItem,
  type LibraryDocumentItem,
  type WorldEntryItem,
} from "@/lib/api";

const ROLE_LABELS: Record<string, string> = {
  PROTAGONIST: "主角",
  SUPPORTING: "配角",
  ANTAGONIST: "反派",
};

const WORLD_CATEGORY_LABELS: Record<string, string> = {
  WORLD: "世界观",
  POWER_SYSTEM: "力量体系",
  FACTION: "宗门势力",
  ITEM: "器物法宝",
  LOCATION: "地标秘境",
  RULE: "铁律禁忌",
};

interface LorePanelProps {
  bookId: string;
}

export function LorePanel({ bookId }: LorePanelProps) {
  const [subTab, setSubTab] = useState<"charter" | "characters" | "world" | "foreshadowing">(
    "characters",
  );
  const [charter, setCharter] = useState<BookCharterItem | null>(null);
  const [charterDraft, setCharterDraft] = useState("");
  const [savingCharter, setSavingCharter] = useState(false);
  const [charterNotice, setCharterNotice] = useState<string | null>(null);
  const [libraryDocs, setLibraryDocs] = useState<LibraryDocumentItem[]>([]);

  const [characters, setCharacters] = useState<CharacterItem[]>([]);
  const [worldEntries, setWorldEntries] = useState<WorldEntryItem[]>([]);
  const [foreshadowings, setForeshadowings] = useState<ForeshadowingItem[]>([]);
  const [error, setError] = useState<string | null>(null);

  // Character form
  const [cName, setCName] = useState("");
  const [cRole, setCRole] = useState("PROTAGONIST");
  const [cRealm, setCRealm] = useState("");
  const [cLocation, setCLocation] = useState("");
  const [cNotes, setCNotes] = useState("");
  const [cInventory, setCInventory] = useState("");

  // World entry form
  const [wCat, setWCat] = useState("WORLD");
  const [wTitle, setWTitle] = useState("");
  const [wContent, setWContent] = useState("");

  // Foreshadowing form
  const [fTitle, setFTitle] = useState("");
  const [fDesc, setFDesc] = useState("");
  const [fChapter, setFChapter] = useState("1");

  useEffect(() => {
    let active = true;
    void Promise.all([
      loreApi.listCharacters(bookId),
      loreApi.listWorldbook(bookId),
      loreApi.listForeshadowings(bookId),
      booksApi.getCharter(bookId).catch(() => null),
      libraryApi.list(bookId).catch(() => []),
    ])
      .then(([chars, worlds, clues, charterData, libDocs]) => {
        if (!active) return;
        setCharacters(chars);
        setWorldEntries(worlds);
        setForeshadowings(clues);
        setCharter(charterData);
        setCharterDraft(charterData?.effectiveCharter ?? "");
        setLibraryDocs(libDocs);
        setError(null);
      })
      .catch((err) => {
        if (!active) return;
        setError(err instanceof Error ? err.message : "加载设定库失败");
      });
    return () => {
      active = false;
    };
  }, [bookId]);

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
    try {
      const saved = await loreApi.upsertCharacter(bookId, {
        name: cName.trim(),
        roleType: cRole,
        realmOrLevel: cRealm.trim(),
        currentLocation: cLocation.trim(),
        statusNotes: cNotes.trim(),
        inventory: cInventory.trim(),
      });
      setCharacters((prev) => {
        const rest = prev.filter((item) => item.id !== saved.id);
        return [saved, ...rest];
      });
      setCName("");
      setCRealm("");
      setCLocation("");
      setCNotes("");
      setCInventory("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存人物失败");
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
      const chapterNo = Number.parseInt(fChapter, 10);
      const saved = await loreApi.upsertForeshadowing(bookId, {
        title: fTitle.trim(),
        description: fDesc.trim(),
        plantedChapterNo: Number.isNaN(chapterNo) ? undefined : chapterNo,
        status: "OPEN",
      });
      setForeshadowings((prev) => {
        const rest = prev.filter((item) => item.id !== saved.id);
        return [saved, ...rest];
      });
      setFTitle("");
      setFDesc("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "保存伏笔失败");
    }
  }

  async function handleToggleForeshadowing(item: ForeshadowingItem) {
    const nextStatus = item.status === "OPEN" ? "RESOLVED" : "OPEN";
    const saved = await loreApi.upsertForeshadowing(bookId, {
      title: item.title,
      description: item.description,
      plantedChapterNo: item.plantedChapterNo ?? undefined,
      status: nextStatus,
    });
    setForeshadowings((prev) => prev.map((f) => (f.id === saved.id ? saved : f)));
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-hidden text-xs">
      <div className="border-b border-border bg-muted/30 px-3 py-2 text-[11px] text-muted-foreground">
        维护人物、世界观与伏笔，AI 创作时会自动参考并保持前后一致
      </div>

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
                rows={6}
                value={charterDraft}
                onChange={(e) => setCharterDraft(e.target.value)}
                placeholder="例如：第三人称限知，冷峻武侠写实基调。主角林青为查清师门灭门真相携玄铁剑入世……"
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
            <form onSubmit={handleAddCharacter} className="space-y-1.5 rounded-lg border border-border bg-background p-2.5">
              <div className="flex gap-1.5">
                <input
                  required
                  value={cName}
                  onChange={(e) => setCName(e.target.value)}
                  placeholder="角色姓名（如：林青）"
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
              <div className="grid grid-cols-2 gap-1.5">
                <input
                  value={cRealm}
                  onChange={(e) => setCRealm(e.target.value)}
                  placeholder="境界/实力"
                  className="rounded border border-input bg-background px-2 py-1 text-xs"
                />
                <input
                  value={cLocation}
                  onChange={(e) => setCLocation(e.target.value)}
                  placeholder="当前位置"
                  className="rounded border border-input bg-background px-2 py-1 text-xs"
                />
              </div>
              <input
                value={cInventory}
                onChange={(e) => setCInventory(e.target.value)}
                placeholder="持有法宝/物品（如：玄铁剑）"
                className="w-full rounded border border-input bg-background px-2 py-1 text-xs"
              />
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
              {characters.map((ch) => (
                <li key={ch.id} className="rounded-lg border border-border bg-background p-2.5">
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-semibold">{ch.name}</span>
                    <div className="flex items-center gap-1.5">
                      <span className="rounded bg-muted px-1.5 py-0.5 text-[10px]">
                        {ch.realmOrLevel || ROLE_LABELS[ch.roleType] || ch.roleType}
                      </span>
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
                  {ch.currentLocation && (
                    <div className="mt-1 text-[11px] text-muted-foreground">
                      位置：{ch.currentLocation}
                    </div>
                  )}
                  {ch.inventory && (
                    <div className="text-[11px] text-muted-foreground">
                      物品：{ch.inventory}
                    </div>
                  )}
                  {ch.statusNotes && (
                    <div className="mt-1 text-[11px] text-muted-foreground">
                      状态：{ch.statusNotes}
                    </div>
                  )}
                </li>
              ))}
            </ul>
          </>
        )}

        {subTab === "world" && (
          <>
            <form onSubmit={handleAddWorld} className="space-y-1.5 rounded-lg border border-border bg-background p-2.5">
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
                  placeholder="词条标题（如：星渊禁地）"
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
            <form onSubmit={handleAddForeshadowing} className="space-y-1.5 rounded-lg border border-border bg-background p-2.5">
              <div className="flex gap-1.5">
                <input
                  required
                  value={fTitle}
                  onChange={(e) => setFTitle(e.target.value)}
                  placeholder="伏笔标题（如：残缺玉佩）"
                  className="min-w-0 flex-1 rounded border border-input bg-background px-2 py-1 text-xs"
                />
                <input
                  type="number"
                  min={1}
                  value={fChapter}
                  onChange={(e) => setFChapter(e.target.value)}
                  placeholder="章节"
                  className="w-16 rounded border border-input bg-background px-2 py-1 text-xs"
                />
              </div>
              <textarea
                rows={2}
                value={fDesc}
                onChange={(e) => setFDesc(e.target.value)}
                placeholder="埋设细节与预计回收时机..."
                className="w-full resize-none rounded border border-input bg-background px-2 py-1 text-xs"
              />
              <Button type="submit" size="xs" className="w-full">
                登记伏笔
              </Button>
            </form>

            <ul className="space-y-2">
              {foreshadowings.map((f) => (
                <li key={f.id} className="rounded-lg border border-border bg-background p-2.5">
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-semibold">{f.title}</span>
                    <div className="flex items-center gap-1.5">
                      <button
                        type="button"
                        onClick={() => void handleToggleForeshadowing(f)}
                        className={`rounded px-1.5 py-0.5 font-mono text-[10px] ${
                          f.status === "OPEN"
                            ? "bg-amber-500/15 text-amber-600"
                            : "bg-emerald-500/15 text-emerald-600"
                        }`}
                      >
                        {f.status === "OPEN" ? "未回收" : "已回收"}
                      </button>
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
                  {f.plantedChapterNo && (
                    <div className="mt-0.5 font-mono text-[10px] text-muted-foreground">
                      埋设于第 {f.plantedChapterNo} 章
                    </div>
                  )}
                  <p className="mt-1 text-[11px] text-muted-foreground">
                    {f.description}
                  </p>
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
    </div>
  );
}
