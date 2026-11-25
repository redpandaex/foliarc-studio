"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { useAuth } from "@/components/auth-provider";
import { Button, buttonVariants } from "@/components/ui/button";
import { ApiClientError, booksApi, type BookItem } from "@/lib/api";

const STATUS_LABELS: Record<string, string> = {
  DRAFT: "草稿",
  ONGOING: "连载中",
  COMPLETED: "已完结",
  ARCHIVED: "已归档",
};

export default function BookshelfPage() {
  const router = useRouter();
  const { user, loading: authLoading, logout } = useAuth();
  const [books, setBooks] = useState<BookItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [showCreate, setShowCreate] = useState(false);
  const [title, setTitle] = useState("");
  const [genre, setGenre] = useState("");
  const [synopsis, setSynopsis] = useState("");
  const [creating, setCreating] = useState(false);

  const [editing, setEditing] = useState<BookItem | null>(null);
  const [editTitle, setEditTitle] = useState("");
  const [editGenre, setEditGenre] = useState("");
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
    void booksApi
      .list()
      .then((list) => {
        if (!active) return;
        setBooks(list);
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

  async function handleCreate(e: FormEvent) {
    e.preventDefault();
    if (!title.trim()) return;
    setCreating(true);
    setError(null);
    try {
      const created = await booksApi.create({
        title: title.trim(),
        genre: genre.trim() || undefined,
        synopsis: synopsis.trim() || undefined,
      });
      setBooks((prev) => [created, ...prev]);
      setTitle("");
      setGenre("");
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
    setEditGenre(book.genre ?? "");
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
        genre: editGenre.trim() || null,
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
            {user?.role === "ADMIN" && (
              <Link
                href="/billing"
                className="rounded-md border border-border px-2.5 py-1.5 text-xs font-medium hover:bg-muted"
              >
                后台管理
              </Link>
            )}
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
              管理你的长篇小说项目，点击作品进入三栏写作工作台
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
            className="grid gap-4 rounded-xl border border-border bg-card p-5 shadow-xs sm:grid-cols-2"
          >
            <div className="space-y-1.5">
              <label className="text-sm font-medium">书名 *</label>
              <input
                required
                maxLength={200}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="例如：星渊剑录"
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-sm font-medium">题材分类</label>
              <input
                maxLength={64}
                value={genre}
                onChange={(e) => setGenre(e.target.value)}
                placeholder="例如：东方玄幻 / 赛博悬疑 / 科幻"
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
              />
            </div>
            <div className="space-y-1.5 sm:col-span-2">
              <label className="text-sm font-medium">故事简介</label>
              <textarea
                rows={3}
                maxLength={4000}
                value={synopsis}
                onChange={(e) => setSynopsis(e.target.value)}
                placeholder="简述核心世界观、主角目标与主线冲突..."
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring"
              />
            </div>
            <div className="flex justify-end gap-2 sm:col-span-2">
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
            className="grid gap-4 rounded-xl border border-primary/40 bg-card p-5 shadow-xs sm:grid-cols-3"
          >
            <div className="sm:col-span-3 flex items-center justify-between">
              <h2 className="text-sm font-semibold">
                编辑作品信息
              </h2>
              <Button
                type="button"
                variant="ghost"
                size="xs"
                onClick={() => setEditing(null)}
              >
                关闭
              </Button>
            </div>
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
              <label className="text-xs font-medium">题材</label>
              <input
                value={editGenre}
                onChange={(e) => setEditGenre(e.target.value)}
                className="w-full rounded-md border border-input bg-background px-3 py-1.5 text-sm"
              />
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
            <div className="space-y-1 sm:col-span-3">
              <label className="text-xs font-medium">简介</label>
              <textarea
                rows={2}
                value={editSynopsis}
                onChange={(e) => setEditSynopsis(e.target.value)}
                className="w-full rounded-md border border-input bg-background px-3 py-1.5 text-sm"
              />
            </div>
            <div className="flex justify-end gap-2 sm:col-span-3">
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
            {books.map((book) => (
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
                  {book.genre && (
                    <p className="text-xs font-medium text-muted-foreground">
                      题材：{book.genre}
                    </p>
                  )}
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
            ))}
          </div>
        )}
      </main>
    </div>
  );
}
