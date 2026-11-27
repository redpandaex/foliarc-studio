"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useAuth } from "@/components/auth-provider";
import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { SpotlightCard } from "@/components/motion/spotlight-card";
import { Card } from "@/components/ui/card";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { useToast } from "@/components/ui/toast";
import {
  ApiClientError,
  booksApi,
  genresApi,
  type BookItem,
  type GenreProfileItem,
} from "@/lib/api";
import { formatWebError } from "@/lib/error-messages";

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
    description: "",
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
    description: "",
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
    description: "",
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
    description: "",
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
    description: "",
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
    description: "",
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
  const confirm = useConfirm();
  const toast = useToast();
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

  const loadBooks = useCallback(async () => {
    setError(null);
    try {
      const [list, genreList] = await Promise.all([
        booksApi.list(),
        genresApi.list().catch(() => FALLBACK_GENRES),
      ]);
      setBooks(list);
      if (genreList.length > 0) setGenres(genreList);
    } catch (err) {
      if (err instanceof ApiClientError && err.status === 401) {
        router.replace("/login");
        return;
      }
      setError(formatWebError(err, "加载书架失败，请稍后重试"));
    } finally {
      setLoading(false);
    }
  }, [router]);

  useEffect(() => {
    if (authLoading) return;
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
        if (genreList.length > 0) setGenres(genreList);
        setError(null);
      })
      .catch((err: unknown) => {
        if (!active) return;
        if (err instanceof ApiClientError && err.status === 401) {
          router.replace("/login");
          return;
        }
        setError(formatWebError(err, "加载书架失败，请稍后重试"));
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
      toast.success(`已创建《${created.title}》`);
    } catch (err) {
      toast.error(formatWebError(err, "创建作品失败，请稍后重试", { action: "save" }));
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
      toast.success(`已保存《${updated.title}》`);
    } catch (err) {
      toast.error(formatWebError(err, "保存作品信息失败，请稍后重试", { action: "save" }));
    }
  }

  async function handleDelete(book: BookItem) {
    const result = await confirm({
      title: `删除《${book.title}》？`,
      description: `删除后，《${book.title}》的全部章节和历史版本都将无法恢复。`,
      confirmLabel: "删除",
      variant: "destructive",
      onConfirm: async () => {
        await booksApi.remove(book.id);
        setBooks((prev) => prev.filter((b) => b.id !== book.id));
      },
    });
    if (result === "succeeded") {
      toast.success(`已删除《${book.title}》`);
    }
  }

  async function handleLogout() {
    await logout();
    router.push("/login");
  }

  if (authLoading || (!user && loading)) {
    return (
      <main className="mx-auto flex max-w-5xl flex-1 items-center justify-center px-6 py-16 text-xs text-muted-foreground">
        加载中…
      </main>
    );
  }

  return (
    <div className="flex min-h-screen flex-col">
      <header className="border-b border-border bg-card">
        <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-6">
          <div className="flex items-center gap-3">
            <Link href="/" className="font-semibold tracking-tight">
              卷弧工坊
            </Link>
            <span className="text-muted-foreground">/</span>
            <span className="text-sm font-medium">书架</span>
          </div>
          <div className="flex items-center gap-2">
            {user && (
              <span className="hidden text-xs text-muted-foreground sm:inline">
                {user.username}
              </span>
            )}
            <ThemeToggle />
            <Link
              href="/library"
              className={buttonVariants({ variant: "outline", size: "sm" })}
            >
              资料库
            </Link>
            <Link
              href="/billing"
              className={buttonVariants({ variant: "outline", size: "sm" })}
            >
              积分
            </Link>
            <Link
              href="/vip"
              className="inline-flex h-7 items-center rounded-md border border-status-vip-border bg-status-vip-bg px-2.5 text-xs font-medium text-status-vip transition-colors hover:brightness-95"
            >
              会员
            </Link>
            <Button variant="outline" size="sm" onClick={() => void handleLogout()}>
              退出
            </Button>
          </div>
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-6 py-8">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <h1 className="text-xl font-bold tracking-tight">作品列表</h1>
          <Button size="sm" onClick={() => setShowCreate((v) => !v)}>
            {showCreate ? "取消" : "+ 新建小说"}
          </Button>
        </div>

        {error && (
          <Alert
            status="danger"
            announce="assertive"
            action={
              <Button type="button" variant="outline" size="sm" onClick={() => void loadBooks()}>
                重新加载
              </Button>
            }
          >
            {error}
          </Alert>
        )}

        {showCreate && (
          <Card className="p-5">
            <form onSubmit={handleCreate} className="space-y-4">
              <div className="space-y-1.5">
                <label htmlFor="create-book-title" className="text-xs font-medium text-muted-foreground">
                  书名
                </label>
                <Input
                  id="create-book-title"
                  required
                  maxLength={200}
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  placeholder="星渊剑录"
                  className="h-9 text-sm"
                />
              </div>

              <div className="space-y-2">
                <div className="text-xs font-medium text-muted-foreground">题材</div>
                <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
                  {genres.map((g) => {
                    const selected = g.code === genreCode;
                    return (
                      <button
                        key={g.code}
                        type="button"
                        aria-pressed={selected}
                        onClick={() => setGenreCode(g.code)}
                        className={`rounded-lg border px-3 py-2 text-center text-xs font-medium transition-colors ${
                          selected
                            ? "border-primary bg-accent text-accent-foreground"
                            : "border-border bg-background text-muted-foreground hover:bg-muted hover:text-foreground"
                        }`}
                      >
                        {g.shortName}
                      </button>
                    );
                  })}
                </div>
              </div>

              <div className="space-y-1.5">
                <div className="text-xs font-medium text-muted-foreground">副标签（选填）</div>
                <div className="flex flex-wrap gap-1.5">
                  {selectedCreateGenre.recommendedSubTags.map((tag) => {
                    const active = splitTags(subGenreTags).includes(tag);
                    return (
                      <button
                        key={tag}
                        type="button"
                        aria-pressed={active}
                        onClick={() => setSubGenreTags((cur) => toggleTagInCsv(cur, tag))}
                        className={`rounded-full px-2.5 py-0.5 text-xs transition-colors ${
                          active
                            ? "bg-primary text-primary-foreground"
                            : "border border-border bg-muted text-muted-foreground hover:text-foreground"
                        }`}
                      >
                        {tag}
                      </button>
                    );
                  })}
                </div>
                <Input
                  maxLength={255}
                  value={subGenreTags}
                  onChange={(e) => setSubGenreTags(e.target.value)}
                  placeholder="自定义标签，逗号分隔"
                />
              </div>

              <div className="space-y-1.5">
                <label htmlFor="create-book-synopsis" className="text-xs font-medium text-muted-foreground">
                  简介（选填）
                </label>
                <Textarea
                  id="create-book-synopsis"
                  rows={3}
                  maxLength={4000}
                  value={synopsis}
                  onChange={(e) => setSynopsis(e.target.value)}
                  placeholder="核心设定或主线冲突"
                />
              </div>

              <div className="flex justify-end gap-2">
                <Button type="button" variant="outline" size="sm" onClick={() => setShowCreate(false)}>
                  取消
                </Button>
                <Button type="submit" size="sm" loading={creating}>
                  {creating ? "创建中…" : "创建"}
                </Button>
              </div>
            </form>
          </Card>
        )}

        {editing && (
          <Card surface="elevated" className="border-primary p-5">
            <form onSubmit={handleSaveEdit} className="space-y-4">
              <div className="flex items-center justify-between">
                <h2 className="text-sm font-semibold">编辑作品</h2>
                <Button type="button" variant="ghost" size="xs" onClick={() => setEditing(null)}>
                  关闭
                </Button>
              </div>
              <div className="grid gap-4 sm:grid-cols-3">
                <div className="space-y-1">
                  <label htmlFor="edit-book-title" className="text-xs font-medium text-muted-foreground">
                    书名
                  </label>
                  <Input
                    id="edit-book-title"
                    required
                    value={editTitle}
                    onChange={(e) => setEditTitle(e.target.value)}
                  />
                </div>
                <Select
                  id="edit-genre-code"
                  label="题材"
                  value={editGenreCode}
                  options={genres.map((g) => ({ value: g.code, label: g.shortName }))}
                  onValueChange={(v) => {
                    if (v) setEditGenreCode(v);
                  }}
                />
                <Select
                  id="edit-book-status"
                  label="状态"
                  value={editStatus}
                  options={Object.entries(STATUS_LABELS).map(([k, label]) => ({
                    value: k,
                    label,
                  }))}
                  onValueChange={(v) => {
                    if (v) setEditStatus(v);
                  }}
                />
              </div>

              <div className="space-y-1.5">
                <div className="text-xs font-medium text-muted-foreground">副标签（选填）</div>
                <div className="flex flex-wrap gap-1.5">
                  {selectedEditGenre.recommendedSubTags.map((tag) => {
                    const active = splitTags(editSubGenreTags).includes(tag);
                    return (
                      <button
                        key={tag}
                        type="button"
                        aria-pressed={active}
                        onClick={() => setEditSubGenreTags((cur) => toggleTagInCsv(cur, tag))}
                        className={`rounded-full px-2.5 py-0.5 text-xs ${
                          active
                            ? "bg-primary text-primary-foreground"
                            : "border border-border bg-muted text-muted-foreground hover:text-foreground"
                        }`}
                      >
                        {tag}
                      </button>
                    );
                  })}
                </div>
                <Input
                  value={editSubGenreTags}
                  onChange={(e) => setEditSubGenreTags(e.target.value)}
                  placeholder="逗号分隔"
                />
              </div>

              <div className="space-y-1">
                <label htmlFor="edit-book-synopsis" className="text-xs font-medium text-muted-foreground">
                  简介（选填）
                </label>
                <Textarea
                  id="edit-book-synopsis"
                  rows={2}
                  value={editSynopsis}
                  onChange={(e) => setEditSynopsis(e.target.value)}
                />
              </div>
              <div className="flex justify-end gap-2">
                <Button type="button" variant="outline" size="sm" onClick={() => setEditing(null)}>
                  取消
                </Button>
                <Button type="submit" size="sm">
                  保存
                </Button>
              </div>
            </form>
          </Card>
        )}

        {loading ? (
          <div className="py-12 text-center text-xs text-muted-foreground">加载中…</div>
        ) : books.length === 0 ? (
          <EmptyState
            title="还没有作品"
            action={
              <Button size="sm" onClick={() => setShowCreate(true)}>
                + 新建小说
              </Button>
            }
          />
        ) : (
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
            {books.map((book) => {
              const genreMeta = genres.find((g) => g.code === book.genreCode);
              const subTags = splitTags(book.subGenreTags);
              return (
                <SpotlightCard key={book.id} className="flex flex-col justify-between p-5">
                  <div className="space-y-2.5">
                    <div className="flex items-start justify-between gap-2">
                      <h2 className="text-base font-semibold leading-snug">{book.title}</h2>
                      <Badge status={book.status === "COMPLETED" ? "success" : "neutral"}>
                        {STATUS_LABELS[book.status] ?? book.status}
                      </Badge>
                    </div>
                    <div className="flex flex-wrap items-center gap-1.5">
                      {(genreMeta?.shortName || book.genre) && (
                        <Badge status="info">{genreMeta?.shortName || book.genre}</Badge>
                      )}
                      {subTags.slice(0, 3).map((tag) => (
                        <Badge key={tag} status="neutral">
                          {tag}
                        </Badge>
                      ))}
                    </div>
                    {book.synopsis && (
                      <p className="line-clamp-3 text-xs leading-relaxed text-muted-foreground">
                        {book.synopsis}
                      </p>
                    )}
                  </div>

                  <div className="mt-5 space-y-3 border-t border-border pt-3">
                    <div className="flex items-center justify-between font-mono text-xs text-muted-foreground tabular-nums">
                      <span>{book.chapterCount} 章</span>
                      <span>{book.totalWordCount.toLocaleString()} 字</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <Link
                        href={`/books/${book.id}`}
                        className={buttonVariants({ size: "sm", className: "flex-1" })}
                      >
                        进入创作台
                      </Link>
                      <Button variant="outline" size="sm" onClick={() => startEdit(book)}>
                        编辑
                      </Button>
                      <Button variant="destructive" size="sm" onClick={() => void handleDelete(book)}>
                        删除
                      </Button>
                    </div>
                  </div>
                </SpotlightCard>
              );
            })}
          </div>
        )}
      </main>
    </div>
  );
}
