"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { useAuth } from "@/components/auth-provider";
import { Button, buttonVariants } from "@/components/ui/button";
import {
  ApiClientError,
  booksApi,
  genresApi,
  type BookItem,
  type GenreProfileItem,
} from "@/lib/api";

const STATUS_LABELS: Record<string, string> = {
  DRAFT: "草稿",
  ONGOING: "连载中",
  COMPLETED: "已完结",
  ARCHIVED: "已归档",
};

const FALLBACK_GENRES: GenreProfileItem[] = [
  {
    code: "XIANXIA_FANTASY",
    displayName: "仙侠 / 玄幻",
    shortName: "仙侠玄幻",
    description: "修真境界、宗门博弈与力量体系",
    recommendedSubTags: ["古典仙侠", "凡人流", "宗门权谋", "剑修"],
    characterFieldSchema: [],
    stylePrinciples: "",
    antiClicheRules: [],
    defaultBeatPattern: "",
    criticRubric: [],
    summaryFocus: "",
  },
  {
    code: "HORROR_SUPERNATURAL",
    displayName: "恐怖 / 灵异 / 规则怪谈",
    shortName: "恐怖灵异",
    description: "中式民俗惊悚、规则怪谈与心理压迫",
    recommendedSubTags: ["中式民俗", "规则怪谈", "克苏鲁", "心理惊悚"],
    characterFieldSchema: [],
    stylePrinciples: "",
    antiClicheRules: [],
    defaultBeatPattern: "",
    criticRubric: [],
    summaryFocus: "",
  },
  {
    code: "CLASSICAL_HISTORICAL",
    displayName: "古典 / 历史 / 世情志怪",
    shortName: "古典历史",
    description: "半文白韵律、世情白描、志怪与历史权谋",
    recommendedSubTags: ["明清世情", "历史权谋", "志怪笔记", "民俗恐怖"],
    characterFieldSchema: [],
    stylePrinciples: "",
    antiClicheRules: [],
    defaultBeatPattern: "",
    criticRubric: [],
    summaryFocus: "",
  },
  {
    code: "MYSTERY_THRILLER",
    displayName: "悬疑 / 推理 / 刑侦",
    shortName: "悬疑推理",
    description: "公平线索、不在场证明与反转推演",
    recommendedSubTags: ["本格推理", "刑侦破案", "社会派悬疑", "密室诡计"],
    characterFieldSchema: [],
    stylePrinciples: "",
    antiClicheRules: [],
    defaultBeatPattern: "",
    criticRubric: [],
    summaryFocus: "",
  },
  {
    code: "SCIFI_APOCALYPSE",
    displayName: "科幻 / 赛博 / 末世",
    shortName: "科幻末世",
    description: "科技硬设定、资源危机与赛博阵营",
    recommendedSubTags: ["硬科幻", "赛博朋克", "废土末世", "星际探索"],
    characterFieldSchema: [],
    stylePrinciples: "",
    antiClicheRules: [],
    defaultBeatPattern: "",
    criticRubric: [],
    summaryFocus: "",
  },
  {
    code: "URBAN_REALISM",
    displayName: "都市 / 现实 / 职场情感",
    shortName: "都市现实",
    description: "现实质感、职场商战与细腻情感拉扯",
    recommendedSubTags: ["现实职场", "都市商战", "情感成长", "行业群像"],
    characterFieldSchema: [],
    stylePrinciples: "",
    antiClicheRules: [],
    defaultBeatPattern: "",
    criticRubric: [],
    summaryFocus: "",
  },
];

function toggleTagInCsv(current: string, tag: string): string {
  const parts = current
    .split(/[,，、;]+/)
    .map((s) => s.trim())
    .filter(Boolean);
  if (parts.includes(tag)) {
    return parts.filter((s) => s !== tag).join(",");
  }
  return [...parts, tag].join(",");
}

function splitTags(csv?: string | null): string[] {
  if (!csv) return [];
  return csv
    .split(/[,，、;]+/)
    .map((s) => s.trim())
    .filter(Boolean);
}

export default function BookshelfPage() {
  const router = useRouter();
  const { user, loading: authLoading, logout } = useAuth();
  const [books, setBooks] = useState<BookItem[]>([]);
  const [genres, setGenres] = useState<GenreProfileItem[]>(FALLBACK_GENRES);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [showCreate, setShowCreate] = useState(false);
  const [title, setTitle] = useState("");
  const [genreCode, setGenreCode] = useState("XIANXIA_FANTASY");
  const [subGenreTags, setSubGenreTags] = useState("");
  const [synopsis, setSynopsis] = useState("");
  const [creating, setCreating] = useState(false);

  const [editing, setEditing] = useState<BookItem | null>(null);
  const [editTitle, setEditTitle] = useState("");
  const [editGenreCode, setEditGenreCode] = useState("XIANXIA_FANTASY");
  const [editSubGenreTags, setEditSubGenreTags] = useState("");
  const [editSynopsis, setEditSynopsis] = useState("");
  const [editStatus, setEditStatus] = useState("DRAFT");

  useEffect(() => {
    if (authLoading) {
      return;
    }
    if (!user) {
      router.replace("/login");
      return;
    }
    let active = true;
    void Promise.all([
      booksApi.list(),
      genresApi.list().catch(() => FALLBACK_GENRES),
    ])
      .then(([list, genreList]) => {
        if (!active) return;
        setBooks(list);
        if (genreList.length > 0) {
          setGenres(genreList);
        }
        setError(null);
      })
      .catch((err) => {
        if (!active) return;
        if (err instanceof ApiClientError && err.status === 401) {
          router.replace("/login");
          return;
        }
        setError(err instanceof Error ? err.message : "加载书架失败");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [authLoading, user, router]);

  const selectedCreateGenre =
    genres.find((g) => g.code === genreCode) ?? genres[0] ?? FALLBACK_GENRES[0];
  const selectedEditGenre =
    genres.find((g) => g.code === editGenreCode) ?? genres[0] ?? FALLBACK_GENRES[0];

  async function handleCreate(e: FormEvent) {
    e.preventDefault();
    if (!title.trim()) return;
    setCreating(true);
    setError(null);
    try {
      const created = await booksApi.create({
        title: title.trim(),
        genre: selectedCreateGenre.displayName,
        genreCode,
        subGenreTags: subGenreTags.trim() || undefined,
        synopsis: synopsis.trim() || undefined,
      });
      setBooks((prev) => [created, ...prev]);
      setTitle("");
      setGenreCode("XIANXIA_FANTASY");
      setSubGenreTags("");
      setSynopsis("");
      setShowCreate(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "新建小说失败");
    } finally {
      setCreating(false);
    }
  }

  function startEdit(book: BookItem) {
    setEditing(book);
    setEditTitle(book.title);
    setEditGenreCode(book.genreCode || "XIANXIA_FANTASY");
    setEditSubGenreTags(book.subGenreTags ?? "");
    setEditSynopsis(book.synopsis ?? "");
    setEditStatus(book.status);
  }

  async function handleSaveEdit(e: FormEvent) {
    e.preventDefault();
    if (!editing) return;
    setError(null);
    try {
      const updated = await booksApi.update(editing.id, {
        title: editTitle.trim(),
        genre: selectedEditGenre.displayName,
        genreCode: editGenreCode,
        subGenreTags: editSubGenreTags.trim(),
        synopsis: editSynopsis.trim() || null,
        status: editStatus,
        version: editing.version,
      });
      setBooks((prev) => prev.map((b) => (b.id === updated.id ? updated : b)));
      setEditing(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "更新小说失败");
    }
  }

  async function handleDelete(book: BookItem) {
    if (!window.confirm(`确认删除《${book.title}》及其全部章节和历史版本？`)) {
      return;
    }
    setError(null);
    try {
      await booksApi.remove(book.id);
      setBooks((prev) => prev.filter((b) => b.id !== book.id));
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除失败");
    }
  }

  async function handleLogout() {
    await logout();
    router.push("/login");
  }

  if (authLoading || (!user && loading)) {
    return (
      <main className="mx-auto flex max-w-5xl flex-1 items-center justify-center px-6 py-16 text-sm text-muted-foreground">
        正在恢复作者会话...
      </main>
    );
  }

  return (
    <div className="flex min-h-screen flex-col">
      <header className="border-b border-border bg-card">
        <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-6">
          <div className="flex items-center gap-4">
            <Link href="/" className="font-semibold tracking-tight">
              卷弧工坊 · FoliarcStudio
            </Link>
            <span className="rounded-full border border-border px-2 py-0.5 font-mono text-[11px] text-muted-foreground">
              我的书架
            </span>
          </div>
          <div className="flex items-center gap-3">
            {user && (
              <span className="text-xs text-muted-foreground">
                {user.username} ({user.email})
              </span>
            )}
            <Link
              href="/library"
              className="rounded-md border border-border px-2.5 py-1.5 text-xs font-medium hover:bg-muted"
            >
              我的资料库
            </Link>
            <Link
              href="/vip"
              className="rounded-md border border-amber-500/40 bg-amber-500/10 px-2.5 py-1.5 text-xs font-medium text-amber-700 hover:bg-amber-500/20 dark:text-amber-400"
            >
              会员权益
            </Link>
            <Button variant="outline" size="sm" onClick={() => void handleLogout()}>
              退出登录
            </Button>
          </div>
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-6 py-8">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold tracking-tight">作品列表</h1>
            <p className="text-sm text-muted-foreground">
              管理你的长篇小说项目，选定题材档案即可自动匹配文风纪律、人物属性与专属写作技巧
            </p>
          </div>
          <Button onClick={() => setShowCreate((v) => !v)}>
            {showCreate ? "取消新建" : "+ 新建小说"}
          </Button>
        </div>

        {error && (
          <div className="rounded-lg border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive">
            {error}
          </div>
        )}

        {showCreate && (
          <form
            onSubmit={handleCreate}
            className="space-y-4 rounded-xl border border-border bg-card p-5 shadow-xs"
          >
            <div className="space-y-1.5">
              <label className="text-sm font-medium">书名 *</label>
              <input
                required
                maxLength={200}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="例如：星渊剑录 / 四号楼值班守则 / 姑苏夜雨录"
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
              />
            </div>

            <div className="space-y-2">
              <label className="text-sm font-medium">选择主分类题材 *</label>
              <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
                {genres.map((g) => {
                  const selected = g.code === genreCode;
                  return (
                    <button
                      key={g.code}
                      type="button"
                      onClick={() => setGenreCode(g.code)}
                      className={`rounded-lg border p-2.5 text-left transition-colors ${
                        selected
                          ? "border-primary bg-primary/5 ring-1 ring-primary"
                          : "border-border bg-background hover:bg-muted/40"
                      }`}
                    >
                      <div className="flex items-center justify-between gap-1">
                        <span className="text-xs font-semibold">{g.shortName}</span>
                        <span className="text-[10px] text-muted-foreground">{g.displayName}</span>
                      </div>
                      <p className="mt-1 line-clamp-2 text-[11px] text-muted-foreground">
                        {g.description}
                      </p>
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="space-y-1.5">
              <label className="text-sm font-medium">
                细分副标签（支持跨界混搭，如古典 + 民俗恐怖）
              </label>
              <div className="flex flex-wrap gap-1.5">
                {selectedCreateGenre.recommendedSubTags.map((tag) => {
                  const active = splitTags(subGenreTags).includes(tag);
                  return (
                    <button
                      key={tag}
                      type="button"
                      onClick={() => setSubGenreTags((cur) => toggleTagInCsv(cur, tag))}
                      className={`rounded-full px-2.5 py-0.5 text-[11px] transition-colors ${
                        active
                          ? "bg-primary text-primary-foreground"
                          : "border border-border bg-muted/40 text-muted-foreground hover:bg-muted"
                      }`}
                    >
                      {active ? `✓ ${tag}` : `+ ${tag}`}
                    </button>
                  );
                })}
              </div>
              <input
                maxLength={255}
                value={subGenreTags}
                onChange={(e) => setSubGenreTags(e.target.value)}
                placeholder="可继续输入自定义标签，用逗号分隔（如：中式民俗,规则怪谈）"
                className="w-full rounded-md border border-input bg-background px-3 py-1.5 text-xs outline-none focus:ring-2 focus:ring-ring"
              />
            </div>

            <div className="space-y-1.5">
              <label className="text-sm font-medium">故事简介（选填，开书也可留空由系统从前几章自动整理）</label>
              <textarea
                rows={3}
                maxLength={4000}
                value={synopsis}
                onChange={(e) => setSynopsis(e.target.value)}
                placeholder="简述核心世界观、主角目标与主线冲突..."
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
              />
            </div>

            <div className="flex justify-end gap-2">
              <Button
                type="button"
                variant="outline"
                onClick={() => setShowCreate(false)}
              >
                取消
              </Button>
              <Button type="submit" disabled={creating}>
                {creating ? "创建中..." : "创建作品"}
              </Button>
            </div>
          </form>
        )}

        {editing && (
          <form
            onSubmit={handleSaveEdit}
            className="space-y-4 rounded-xl border border-primary/40 bg-card p-5 shadow-xs"
          >
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-semibold">编辑作品信息</h2>
              <Button
                type="button"
                variant="ghost"
                size="xs"
                onClick={() => setEditing(null)}
              >
                关闭
              </Button>
            </div>
            <div className="grid gap-4 sm:grid-cols-3">
              <div className="space-y-1">
                <label className="text-xs font-medium">书名 *</label>
                <input
                  required
                  value={editTitle}
                  onChange={(e) => setEditTitle(e.target.value)}
                  className="w-full rounded-md border border-input bg-background px-3 py-1.5 text-sm"
                />
              </div>
              <div className="space-y-1">
                <label className="text-xs font-medium">主分类题材</label>
                <select
                  value={editGenreCode}
                  onChange={(e) => setEditGenreCode(e.target.value)}
                  className="w-full rounded-md border border-input bg-background px-3 py-1.5 text-sm"
                >
                  {genres.map((g) => (
                    <option key={g.code} value={g.code}>
                      {g.displayName}
                    </option>
                  ))}
                </select>
              </div>
              <div className="space-y-1">
                <label className="text-xs font-medium">连载状态</label>
                <select
                  value={editStatus}
                  onChange={(e) => setEditStatus(e.target.value)}
                  className="w-full rounded-md border border-input bg-background px-3 py-1.5 text-sm"
                >
                  {Object.entries(STATUS_LABELS).map(([k, label]) => (
                    <option key={k} value={k}>
                      {label}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-medium">细分副标签</label>
              <div className="flex flex-wrap gap-1.5">
                {selectedEditGenre.recommendedSubTags.map((tag) => {
                  const active = splitTags(editSubGenreTags).includes(tag);
                  return (
                    <button
                      key={tag}
                      type="button"
                      onClick={() => setEditSubGenreTags((cur) => toggleTagInCsv(cur, tag))}
                      className={`rounded-full px-2 py-0.5 text-[11px] ${
                        active
                          ? "bg-primary text-primary-foreground"
                          : "border border-border bg-muted/40 text-muted-foreground hover:bg-muted"
                      }`}
                    >
                      {active ? `✓ ${tag}` : `+ ${tag}`}
                    </button>
                  );
                })}
              </div>
              <input
                value={editSubGenreTags}
                onChange={(e) => setEditSubGenreTags(e.target.value)}
                placeholder="逗号分隔副标签"
                className="w-full rounded-md border border-input bg-background px-3 py-1.5 text-xs"
              />
            </div>

            <div className="space-y-1">
              <label className="text-xs font-medium">简介</label>
              <textarea
                rows={2}
                value={editSynopsis}
                onChange={(e) => setEditSynopsis(e.target.value)}
                className="w-full rounded-md border border-input bg-background px-3 py-1.5 text-sm"
              />
            </div>
            <div className="flex justify-end gap-2">
              <Button type="button" variant="outline" size="sm" onClick={() => setEditing(null)}>
                取消
              </Button>
              <Button type="submit" size="sm">
                保存修改
              </Button>
            </div>
          </form>
        )}

        {loading ? (
          <div className="py-12 text-center text-sm text-muted-foreground">
            正在加载书架...
          </div>
        ) : books.length === 0 ? (
          <div className="rounded-xl border border-dashed border-border p-12 text-center">
            <p className="text-sm font-medium">书架还是空的</p>
            <p className="mt-1 text-xs text-muted-foreground">
              点击右上角「新建小说」开启你的第一部长篇创作
            </p>
          </div>
        ) : (
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
            {books.map((book) => {
              const genreMeta = genres.find((g) => g.code === book.genreCode);
              const subTags = splitTags(book.subGenreTags);
              return (
                <article
                  key={book.id}
                  className="flex flex-col justify-between rounded-xl border border-border bg-card p-5 shadow-xs"
                >
                  <div className="space-y-2">
                    <div className="flex items-start justify-between gap-2">
                      <h2 className="text-base font-semibold leading-snug">
                        {book.title}
                      </h2>
                      <span className="shrink-0 rounded-full border border-border px-2 py-0.5 text-[11px] text-muted-foreground">
                        {STATUS_LABELS[book.status] ?? book.status}
                      </span>
                    </div>
                    <div className="flex flex-wrap items-center gap-1.5">
                      {(genreMeta?.shortName || book.genre) && (
                        <span className="rounded bg-primary/10 px-1.5 py-0.5 text-[11px] font-medium text-primary">
                          {genreMeta?.shortName || book.genre}
                        </span>
                      )}
                      {subTags.slice(0, 3).map((tag) => (
                        <span
                          key={tag}
                          className="rounded bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground"
                        >
                          {tag}
                        </span>
                      ))}
                    </div>
                    <p className="line-clamp-3 text-sm text-muted-foreground">
                      {book.synopsis || "暂无简介"}
                    </p>
                  </div>

                  <div className="mt-5 space-y-3 border-t border-border pt-3">
                    <div className="flex items-center justify-between font-mono text-xs text-muted-foreground">
                      <span>{book.chapterCount} 章</span>
                      <span>{book.totalWordCount.toLocaleString()} 字</span>
                      <span>v{book.version}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <Link
                        href={`/books/${book.id}`}
                        className={buttonVariants({ size: "sm", className: "flex-1" })}
                      >
                        进入创作台
                      </Link>
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => startEdit(book)}
                      >
                        编辑
                      </Button>
                      <Button
                        variant="destructive"
                        size="sm"
                        onClick={() => void handleDelete(book)}
                      >
                        删除
                      </Button>
                    </div>
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </main>
    </div>
  );
}
