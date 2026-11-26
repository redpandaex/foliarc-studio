"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { AdminShell } from "@/components/admin-shell";
import {
  AdminApiError,
  adminKnowledgeApi,
  adminUsersApi,
  type AdminLibraryDocument,
  type AdminUserBook,
  type AdminUserRow,
  type DebugRagSearchResponse,
  type DocumentGrantRow,
} from "@/lib/admin-api";

const GENRE_OPTIONS = [
  { code: "XIANXIA_FANTASY", label: "仙侠玄幻" },
  { code: "HORROR_SUPERNATURAL", label: "恐怖灵异" },
  { code: "CLASSICAL_HISTORICAL", label: "古典历史" },
  { code: "MYSTERY_THRILLER", label: "悬疑推理" },
  { code: "SCIFI_APOCALYPSE", label: "科幻末世" },
  { code: "URBAN_REALISM", label: "都市现实" },
];

export default function AdminKnowledgePage() {
  const [docs, setDocs] = useState<AdminLibraryDocument[]>([]);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [file, setFile] = useState<File | null>(null);
  const [docType, setDocType] = useState("WORLDBOOK");
  const [title, setTitle] = useState("");
  const [selectedGenres, setSelectedGenres] = useState<string[]>([]);
  const [genreTags, setGenreTags] = useState("");
  const [publicToAll, setPublicToAll] = useState(true);
  const [uploading, setUploading] = useState(false);

  const [grantDocId, setGrantDocId] = useState("");
  const [grantUserId, setGrantUserId] = useState("");
  const [grantNote, setGrantNote] = useState("");
  const [activeGrantsDoc, setActiveGrantsDoc] = useState<AdminLibraryDocument | null>(null);
  const [activeGrants, setActiveGrants] = useState<DocumentGrantRow[]>([]);
  const [users, setUsers] = useState<AdminUserRow[]>([]);
  const [ownerBooks, setOwnerBooks] = useState<AdminUserBook[]>([]);

  const [searchQuery, setSearchQuery] = useState("");
  const [searchOwnerId, setSearchOwnerId] = useState("");
  const [searchBookId, setSearchBookId] = useState("");
  const [searchMaxCh, setSearchMaxCh] = useState("");
  const [searchResult, setSearchResult] = useState<DebugRagSearchResponse | null>(null);
  const [searching, setSearching] = useState(false);

  const loadDocs = useCallback(async () => {
    try {
      const list = await adminKnowledgeApi.list();
      setDocs(list);
      setError(null);
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载公共资料失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let active = true;
    Promise.all([
      adminKnowledgeApi.list(),
      adminUsersApi.list(undefined, undefined, 100).catch(() => [] as AdminUserRow[]),
    ])
      .then(([list, userList]) => {
        if (!active) return;
        setDocs(list);
        setUsers(userList);
        setError(null);
      })
      .catch((err: unknown) => {
        if (!active) return;
        setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载公共资料失败");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  function toggleGenre(code: string) {
    setSelectedGenres((prev) =>
      prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code],
    );
  }

  async function handleUpload(e: FormEvent) {
    e.preventDefault();
    if (!file) return;
    setUploading(true);
    setError(null);
    setNotice(null);
    try {
      await adminKnowledgeApi.upload({
        file,
        docType,
        title: title.trim() || undefined,
        genreCodes: selectedGenres.join(","),
        genreTags: genreTags.trim() || undefined,
        publicToAll,
      });
      setFile(null);
      setTitle("");
      setSelectedGenres([]);
      setGenreTags("");
      setNotice("公共资料已入库并触发异步向量化管道，已记录审计日志。");
      await loadDocs();
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "上传失败");
    } finally {
      setUploading(false);
    }
  }

  async function handleDelete(doc: AdminLibraryDocument) {
    if (!window.confirm(`确认删除平台资料「${doc.title}」及其向量索引？`)) return;
    setError(null);
    setNotice(null);
    try {
      await adminKnowledgeApi.remove(doc.id);
      setDocs((prev) => prev.filter((d) => d.id !== doc.id));
      setNotice(`已删除平台资料「${doc.title}」`);
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "删除失败");
    }
  }

  async function openGrants(doc: AdminLibraryDocument) {
    setActiveGrantsDoc(doc);
    setGrantDocId(doc.id);
    setError(null);
    try {
      const list = await adminKnowledgeApi.listGrants(doc.id);
      setActiveGrants(list);
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载授权记录失败");
    }
  }

  async function handleRevokeGrant(docId: string, userId: string) {
    setError(null);
    setNotice(null);
    try {
      await adminKnowledgeApi.revoke(docId, userId);
      setActiveGrants((prev) => prev.filter((g) => g.userId !== userId));
      setNotice("已撤销该作者对该资料的定向访问授权，并写入审计日志。");
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "撤销授权失败");
    }
  }

  async function handleSelectSearchOwner(ownerId: string) {
    setSearchOwnerId(ownerId);
    setSearchBookId("");
    setOwnerBooks([]);
    if (!ownerId.trim()) return;
    try {
      const list = await adminUsersApi.listBooks(ownerId.trim());
      setOwnerBooks(list);
      if (list.length > 0) {
        setSearchBookId(list[0].id);
      }
    } catch {
      setOwnerBooks([]);
    }
  }

  async function handleGrant(e: FormEvent) {
    e.preventDefault();
    if (!grantDocId || !grantUserId.trim()) return;
    setError(null);
    setNotice(null);
    try {
      await adminKnowledgeApi.grant(grantDocId, grantUserId.trim(), grantNote.trim());
      setNotice("已完成定向资料授权，并写入审计日志。");
      setGrantUserId("");
      setGrantNote("");
      if (activeGrantsDoc && activeGrantsDoc.id === grantDocId) {
        const list = await adminKnowledgeApi.listGrants(grantDocId);
        setActiveGrants(list);
      }
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "授权失败");
    }
  }

  async function handleDebugSearch(e: FormEvent) {
    e.preventDefault();
    if (!searchQuery.trim() || !searchOwnerId.trim() || !searchBookId.trim()) {
      setError("请填写目标作者 ownerId、作品 bookId 与查询词。");
      return;
    }
    setSearching(true);
    setError(null);
    try {
      const res = await adminKnowledgeApi.debugSearch({
        query: searchQuery.trim(),
        ownerId: searchOwnerId.trim(),
        bookId: searchBookId.trim(),
        topK: 8,
        maxChapterNo: searchMaxCh ? Number.parseInt(searchMaxCh, 10) : undefined,
      });
      setSearchResult(res);
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "RAG 调试检索失败");
    } finally {
      setSearching(false);
    }
  }

  return (
    <AdminShell
      title="平台公共资料库 & RAG 调试台"
      subtitle="管理 GLOBAL / ASSIGNED 参考包、题材编码路由、作者定向授权与多作用域召回调试"
      actions={
        <button
          type="button"
          onClick={() => void loadDocs()}
          className="rounded-md border border-border bg-card px-3 py-1.5 text-xs hover:bg-muted"
        >
          刷新列表
        </button>
      }
    >
      {notice && (
        <div className="rounded-lg border border-emerald-500/40 bg-emerald-500/10 px-4 py-2 text-xs text-emerald-300">
          {notice}
        </div>
      )}
      {error && (
        <div className="rounded-lg border border-rose-500/40 bg-rose-500/10 px-4 py-2 text-xs text-rose-300">
          {error}
        </div>
      )}

      {/* Upload Platform Doc */}
      <form
        onSubmit={handleUpload}
        className="grid gap-3 rounded-xl border border-border bg-card p-5 sm:grid-cols-2"
      >
        <div className="sm:col-span-2">
          <h2 className="text-sm font-semibold">上传平台公共 / 专属资料包</h2>
        </div>
        <div className="space-y-1 sm:col-span-2">
          <label className="text-xs text-muted-foreground">文件（PDF / DOCX / MD / TXT）</label>
          <input
            type="file"
            accept=".pdf,.docx,.md,.txt"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            className="w-full text-xs file:mr-3 file:rounded-md file:border file:border-border file:bg-muted file:px-3 file:py-1.5 file:text-xs"
          />
        </div>
        <div className="space-y-1">
          <label className="text-xs text-muted-foreground">资料类型</label>
          <select
            value={docType}
            onChange={(e) => setDocType(e.target.value)}
            className="w-full rounded-md border border-border bg-background px-3 py-1.5 text-xs"
          >
            <option value="WORLDBOOK">WORLDBOOK（世界观/历史考据）</option>
            <option value="STYLE_REF">STYLE_REF（文风参考/范文）</option>
            <option value="OUTLINE">OUTLINE（结构大纲模板）</option>
          </select>
        </div>
        <div className="space-y-1">
          <label className="text-xs text-muted-foreground">标题（选填）</label>
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="例如：明清官制与盐政白描辞典"
            className="w-full rounded-md border border-border bg-background px-3 py-1.5 text-xs"
          />
        </div>
        <div className="space-y-1.5 sm:col-span-2">
          <label className="text-xs text-muted-foreground">
            适用主分类题材（genreCodes，留空表示全题材通用）
          </label>
          <div className="flex flex-wrap gap-1.5">
            {GENRE_OPTIONS.map((g) => {
              const active = selectedGenres.includes(g.code);
              return (
                <button
                  key={g.code}
                  type="button"
                  onClick={() => toggleGenre(g.code)}
                  className={`rounded-full px-2.5 py-1 text-[11px] ${
                    active
                      ? "bg-blue-600 text-white"
                      : "border border-border bg-background text-muted-foreground hover:bg-muted"
                  }`}
                >
                  {active ? `✓ ${g.label}` : `+ ${g.label}`}
                </button>
              );
            })}
          </div>
        </div>
        <div className="space-y-1">
          <label className="text-xs text-muted-foreground">细分标签（genreTags）</label>
          <input
            value={genreTags}
            onChange={(e) => setGenreTags(e.target.value)}
            placeholder="明清世情, 民俗怪谈"
            className="w-full rounded-md border border-border bg-background px-3 py-1.5 text-xs"
          />
        </div>
        <div className="flex items-end justify-between gap-3">
          <label className="flex items-center gap-2 text-xs">
            <input
              type="checkbox"
              checked={publicToAll}
              onChange={(e) => setPublicToAll(e.target.checked)}
            />
            <span>全平台公开（取消勾选则仅对授权作者可见）</span>
          </label>
          <button
            type="submit"
            disabled={!file || uploading}
            className="rounded-md bg-blue-600 px-4 py-1.5 text-xs font-medium text-white hover:bg-blue-500 disabled:opacity-50"
          >
            {uploading ? "上传中..." : "上传入库"}
          </button>
        </div>
      </form>

      {/* Docs Table + Grant */}
      <section className="grid gap-6 lg:grid-cols-3">
        <div className="rounded-xl border border-border bg-card p-4 lg:col-span-2">
          <h2 className="mb-3 text-sm font-semibold">平台资料清单 ({docs.length})</h2>
          {loading ? (
            <p className="py-6 text-center text-xs text-muted-foreground">加载中...</p>
          ) : docs.length === 0 ? (
            <p className="py-6 text-center text-xs text-muted-foreground">暂无平台资料</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="border-b border-border text-[11px] text-muted-foreground">
                  <tr>
                    <th className="py-2">标题 / 文件</th>
                    <th className="py-2">类型 / 题材</th>
                    <th className="py-2">状态</th>
                    <th className="py-2 text-right">操作</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {docs.map((d) => (
                    <tr key={d.id}>
                      <td className="py-2">
                        <div className="font-medium">{d.title}</div>
                        <div className="font-mono text-[10px] text-muted-foreground">
                          {d.id.slice(0, 12)} · {d.chunkCount} chunks
                        </div>
                      </td>
                      <td className="py-2">
                        <span className="rounded bg-muted px-1.5 py-0.5 font-mono text-[10px]">
                          {d.docType}
                        </span>
                        <div className="mt-1 text-[10px] text-muted-foreground">
                          {d.genreCodes || "全题材通用"} {d.genreTags ? `· ${d.genreTags}` : ""}
                        </div>
                      </td>
                      <td className="py-2 font-mono text-[11px]">{d.status}</td>
                      <td className="py-2 text-right">
                        <div className="flex justify-end gap-1.5">
                          <button
                            type="button"
                            onClick={() => void openGrants(d)}
                            className="rounded border border-border px-2 py-0.5 text-[11px] hover:bg-muted"
                          >
                            授权/明细
                          </button>
                          <button
                            type="button"
                            onClick={() => void handleDelete(d)}
                            className="rounded border border-rose-500/40 px-2 py-0.5 text-[11px] text-rose-300 hover:bg-rose-500/10"
                          >
                            删除
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <form onSubmit={handleGrant} className="space-y-3 rounded-xl border border-border bg-card p-4">
          <h2 className="text-sm font-semibold">定向资料授权 (ASSIGNED)</h2>
          <div className="space-y-1">
            <label className="text-xs text-muted-foreground">文档 ID</label>
            <input
              required
              value={grantDocId}
              onChange={(e) => setGrantDocId(e.target.value)}
              placeholder="点击左侧“授权”自动填充"
              className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 font-mono text-xs"
            />
          </div>
          <div className="space-y-1">
            <label className="text-xs text-muted-foreground">目标作者</label>
            {users.length > 0 && (
              <select
                value={grantUserId}
                onChange={(e) => setGrantUserId(e.target.value)}
                className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-xs"
              >
                <option value="">-- 从作者列表选择 --</option>
                {users.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.username} ({u.email})
                  </option>
                ))}
              </select>
            )}
            <input
              required
              value={grantUserId}
              onChange={(e) => setGrantUserId(e.target.value)}
              placeholder="或直接输入作者 User UUID"
              className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 font-mono text-xs"
            />
          </div>
          <div className="space-y-1">
            <label className="text-xs text-muted-foreground">备注</label>
            <input
              value={grantNote}
              onChange={(e) => setGrantNote(e.target.value)}
              placeholder="例如：签约古风专项资料包"
              className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-xs"
            />
          </div>
          <button
            type="submit"
            className="w-full rounded-md bg-blue-600 py-1.5 text-xs font-medium text-white hover:bg-blue-500"
          >
            确认授权
          </button>
        </form>
      </section>

      {/* Active Grants Drawer/Section */}
      {activeGrantsDoc && (
        <section className="rounded-xl border border-blue-500/40 bg-card p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold">
              「{activeGrantsDoc.title}」定向授权明细 ({activeGrants.length})
            </h2>
            <button
              type="button"
              onClick={() => setActiveGrantsDoc(null)}
              className="text-xs text-muted-foreground hover:text-foreground"
            >
              关闭
            </button>
          </div>
          {activeGrants.length === 0 ? (
            <p className="py-3 text-xs text-muted-foreground">当前暂无定向授权记录</p>
          ) : (
            <div className="space-y-1.5">
              {activeGrants.map((g) => (
                <div
                  key={g.grantId}
                  className="flex items-center justify-between gap-3 rounded-lg border border-border/70 bg-background/50 px-3 py-2 text-xs"
                >
                  <div>
                    <span className="font-medium">{g.username}</span>
                    <span className="ml-2 font-mono text-[11px] text-muted-foreground">
                      {g.email} · {g.permission}
                    </span>
                  </div>
                  <button
                    type="button"
                    onClick={() => void handleRevokeGrant(g.documentId, g.userId)}
                    className="rounded border border-rose-500/40 px-2.5 py-0.5 text-[11px] text-rose-300 hover:bg-rose-500/10"
                  >
                    撤销授权
                  </button>
                </div>
              ))}
            </div>
          )}
        </section>
      )}

      {/* RAG Debug Search */}
      <section className="space-y-3 rounded-xl border border-border bg-card p-5">
        <h2 className="text-sm font-semibold">三层作用域 Hybrid RAG 调试检索</h2>
        <form onSubmit={handleDebugSearch} className="grid gap-3 sm:grid-cols-4">
          <input
            required
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="输入测试查询词（如：明清盐引勘合）"
            className="rounded-md border border-border bg-background px-3 py-1.5 text-xs sm:col-span-2"
          />
          <select
            value={searchOwnerId}
            onChange={(e) => void handleSelectSearchOwner(e.target.value)}
            className="rounded-md border border-border bg-background px-3 py-1.5 text-xs"
          >
            <option value="">-- 选择作者 (ownerId) --</option>
            {users.map((u) => (
              <option key={u.id} value={u.id}>
                {u.username} ({u.email})
              </option>
            ))}
          </select>
          <div className="flex gap-2">
            {ownerBooks.length > 0 ? (
              <select
                required
                value={searchBookId}
                onChange={(e) => setSearchBookId(e.target.value)}
                className="min-w-0 flex-1 rounded-md border border-border bg-background px-2.5 py-1.5 text-xs"
              >
                {ownerBooks.map((b) => (
                  <option key={b.id} value={b.id}>
                    《{b.title}》({b.chapterCount}章)
                  </option>
                ))}
              </select>
            ) : (
              <input
                required
                value={searchBookId}
                onChange={(e) => setSearchBookId(e.target.value)}
                placeholder="bookId (UUID)"
                className="min-w-0 flex-1 rounded-md border border-border bg-background px-2.5 py-1.5 font-mono text-xs"
              />
            )}
            <input
              type="number"
              value={searchMaxCh}
              onChange={(e) => setSearchMaxCh(e.target.value)}
              placeholder="maxCh"
              className="w-16 rounded-md border border-border bg-background px-2 py-1.5 font-mono text-xs"
            />
            <button
              type="submit"
              disabled={searching}
              className="rounded-md bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-500"
            >
              {searching ? "..." : "检索"}
            </button>
          </div>
        </form>
        {searchResult && (
          <div className="space-y-2 rounded-lg border border-border bg-background p-3">
            <div className="font-mono text-[11px] text-muted-foreground">
              命中 {searchResult.hits?.length ?? 0} 条结果
            </div>
            {(searchResult.hits ?? []).map((h, idx) => (
              <div key={`${h.chunk_id ?? idx}`} className="rounded border border-border/70 p-2.5 text-xs">
                <div className="flex items-center justify-between font-mono text-[10px] text-blue-300">
                  <span>
                    [{h.scope ?? "GLOBAL"}] {h.doc_type} · Ch.{h.chapter_no ?? 0} · {h.scene_title}
                  </span>
                  <span>score={typeof h.score === "number" ? h.score.toFixed(4) : "-"}</span>
                </div>
                <p className="mt-1 whitespace-pre-wrap text-[11px] text-muted-foreground">
                  {(h.content ?? "").slice(0, 320)}
                </p>
              </div>
            ))}
          </div>
        )}
      </section>
    </AdminShell>
  );
}
