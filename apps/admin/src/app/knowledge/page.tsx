"use client";

import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { DataTable } from "@/components/ui/data-table";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { useToast } from "@/components/ui/toast";

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
  const toast = useToast();
  const confirm = useConfirm();
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
        setError(
          err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载公共资料失败",
        );
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
      toast.success("公共资料已入库并触发异步向量化管道，已记录审计日志。");
      await loadDocs();
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "上传失败");
    } finally {
      setUploading(false);
    }
  }

  async function handleDelete(doc: AdminLibraryDocument) {
    await confirm({
      title: "删除平台资料",
      description: `确认删除平台资料「${doc.title}」及其向量索引？`,
      confirmLabel: "删除资料",
      variant: "destructive",
      onConfirm: async () => {
        try {
          await adminKnowledgeApi.remove(doc.id);
        } catch (err) {
          throw new Error(
            err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "删除失败",
          );
        }
        setDocs((prev) => prev.filter((d) => d.id !== doc.id));
        toast.success(`已删除平台资料「${doc.title}」`);
      },
    });
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
    try {
      await adminKnowledgeApi.revoke(docId, userId);
      setActiveGrants((prev) => prev.filter((g) => g.userId !== userId));
      toast.success("已撤销该作者对该资料的定向访问授权，并写入审计日志。");
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
    try {
      await adminKnowledgeApi.grant(grantDocId, grantUserId.trim(), grantNote.trim());
      toast.success("已完成定向资料授权，并写入审计日志。");
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
        <Button type="button" onClick={() => void loadDocs()} variant="outline" size="sm">
          刷新列表
        </Button>
      }
    >
      {error && (
        <Alert status="danger" announce="assertive">
          {error}
        </Alert>
      )}

      {/* Upload Platform Doc */}
      <Card className="p-5">
        <form onSubmit={handleUpload} className="grid gap-3 sm:grid-cols-2">
          <div className="sm:col-span-2">
            <h2 className="text-sm font-semibold">上传平台公共 / 专属资料包</h2>
          </div>
          <div className="space-y-1 sm:col-span-2">
            <label htmlFor="upload-file" className="text-xs text-muted-foreground">
              文件（PDF / DOCX / MD / TXT）
            </label>
            <Input
              id="upload-file"
              type="file"
              accept=".pdf,.docx,.md,.txt"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
          </div>
          <div className="space-y-1">
            <Select
              id="docType"
              label="资料类型"
              value={docType}
              onValueChange={(value) => value !== null && setDocType(value)}
              options={[
                { value: "WORLDBOOK", label: "WORLDBOOK（世界观/历史考据）" },
                { value: "STYLE_REF", label: "STYLE_REF（文风参考/范文）" },
                { value: "OUTLINE", label: "OUTLINE（结构大纲模板）" },
              ]}
            />
          </div>
          <div className="space-y-1">
            <label htmlFor="title" className="text-xs text-muted-foreground">
              标题（选填）
            </label>
            <Input
              id="title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="例如：明清官制与盐政白描辞典"
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
                  <Button
                    key={g.code}
                    type="button"
                    onClick={() => toggleGenre(g.code)}
                    aria-pressed={active}
                    size="sm"
                    variant={active ? "default" : "outline"}
                  >
                    {active ? `✓ ${g.label}` : `+ ${g.label}`}
                  </Button>
                );
              })}
            </div>
          </div>
          <div className="space-y-1">
            <label htmlFor="genreTags" className="text-xs text-muted-foreground">
              细分标签（genreTags）
            </label>
            <Input
              id="genreTags"
              value={genreTags}
              onChange={(e) => setGenreTags(e.target.value)}
              placeholder="明清世情, 民俗怪谈"
            />
          </div>
          <div className="flex items-end justify-between gap-3">
            <label className="flex items-center gap-2 text-xs">
              <Input
                type="checkbox"
                checked={publicToAll}
                onChange={(e) => setPublicToAll(e.target.checked)}
              />
              <span>全平台公开（取消勾选则仅对授权作者可见）</span>
            </label>
            <Button type="submit" disabled={!file} loading={uploading} variant="default" size="sm">
              {uploading ? "上传中..." : "上传入库"}
            </Button>
          </div>
        </form>
      </Card>

      {/* Docs Table + Grant */}
      <section className="grid gap-6 lg:grid-cols-3">
        <Card className="p-4 lg:col-span-2">
          <h2 className="mb-3 text-sm font-semibold">平台资料清单 ({docs.length})</h2>
          <DataTable
            caption="平台资料清单"
            rows={docs}
            rowKey={(d) => d.id}
            loading={loading}
            empty={<EmptyState title="暂无平台资料" />}
            columns={[
              {
                id: "title",
                header: "标题 / 文件",
                cell: (d) => (
                  <>
                    <div className="font-medium">{d.title}</div>
                    <div className="font-mono text-[10px] text-muted-foreground">
                      {d.id.slice(0, 12)} · {d.chunkCount} chunks
                    </div>
                  </>
                ),
              },
              {
                id: "type",
                header: "类型 / 题材",
                cell: (d) => (
                  <>
                    <Badge status="neutral">{d.docType}</Badge>
                    <div className="mt-1 text-[10px] text-muted-foreground">
                      {d.genreCodes || "全题材通用"} {d.genreTags ? `· ${d.genreTags}` : ""}
                    </div>
                  </>
                ),
              },
              {
                id: "status",
                header: "状态",
                className: "font-mono text-[11px]",
                cell: (d) => (
                  <Badge
                    status={
                      d.status === "READY"
                        ? "success"
                        : d.status === "FAILED"
                          ? "danger"
                          : "warning"
                    }
                  >
                    {d.status}
                  </Badge>
                ),
              },
              {
                id: "actions",
                header: "操作",
                align: "end",
                cell: (d) => (
                  <div className="flex justify-end gap-1.5">
                    <Button
                      type="button"
                      onClick={() => void openGrants(d)}
                      variant="outline"
                      size="sm"
                    >
                      授权/明细
                    </Button>
                    <Button
                      type="button"
                      onClick={() => void handleDelete(d)}
                      variant="destructive"
                      size="sm"
                    >
                      删除
                    </Button>
                  </div>
                ),
              },
            ]}
          />
        </Card>

        <Card className="p-4">
          <form onSubmit={handleGrant} className="space-y-3">
            <h2 className="text-sm font-semibold">定向资料授权 (ASSIGNED)</h2>
            <div className="space-y-1">
              <label htmlFor="grantDocId" className="text-xs text-muted-foreground">
                文档 ID
              </label>
              <Input
                id="grantDocId"
                required
                value={grantDocId}
                onChange={(e) => setGrantDocId(e.target.value)}
                placeholder="点击左侧“授权”自动填充"
                className="font-mono"
              />
            </div>
            <div className="space-y-1">
              <label htmlFor="target-author-id" className="text-xs text-muted-foreground">
                目标作者
              </label>
              {users.length > 0 && (
                <Select
                  id="grantUserId"
                  label="选择目标作者"
                  hideLabel
                  value={grantUserId}
                  onValueChange={(value) => value !== null && setGrantUserId(value)}
                  options={[
                    { value: "", label: "-- 从作者列表选择 --" },
                    ...users.map((u) => ({ value: u.id, label: `${u.username} (${u.email})` })),
                  ]}
                />
              )}
              <Input
                id="target-author-id"
                required
                value={grantUserId}
                onChange={(e) => setGrantUserId(e.target.value)}
                placeholder="或直接输入作者 User UUID"
                className="font-mono"
              />
            </div>
            <div className="space-y-1">
              <label htmlFor="grantNote" className="text-xs text-muted-foreground">
                备注
              </label>
              <Input
                id="grantNote"
                value={grantNote}
                onChange={(e) => setGrantNote(e.target.value)}
                placeholder="例如：签约古风专项资料包"
              />
            </div>
            <Button type="submit" variant="default" size="sm" className="w-full">
              确认授权
            </Button>
          </form>
        </Card>
      </section>

      {/* Active Grants Drawer/Section */}
      {activeGrantsDoc && (
        <Card className="p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold">
              「{activeGrantsDoc.title}」定向授权明细 ({activeGrants.length})
            </h2>
            <Button
              type="button"
              onClick={() => setActiveGrantsDoc(null)}
              variant="ghost"
              size="sm"
            >
              关闭
            </Button>
          </div>
          {activeGrants.length === 0 ? (
            <EmptyState title="当前暂无定向授权记录" />
          ) : (
            <div className="space-y-1.5">
              {activeGrants.map((g) => (
                <Card
                  key={g.grantId}
                  className="flex items-center justify-between gap-3 px-3 py-2 text-xs"
                >
                  <div>
                    <span className="font-medium">{g.username}</span>
                    <span className="ml-2 font-mono text-[11px] text-muted-foreground">
                      {g.email} · {g.permission}
                    </span>
                  </div>
                  <Button
                    type="button"
                    onClick={() => void handleRevokeGrant(g.documentId, g.userId)}
                    variant="destructive"
                    size="sm"
                  >
                    撤销授权
                  </Button>
                </Card>
              ))}
            </div>
          )}
        </Card>
      )}

      {/* RAG Debug Search */}
      <Card className="space-y-3 p-5">
        <h2 className="text-sm font-semibold">三层作用域 Hybrid RAG 调试检索</h2>
        <form onSubmit={handleDebugSearch} className="grid gap-3 sm:grid-cols-4">
          <Input
            aria-label="测试查询词"
            required
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="输入测试查询词（如：明清盐引勘合）"
            className="sm:col-span-2"
          />
          <Select
            id="searchOwnerId"
            label="检索作者"
            hideLabel
            value={searchOwnerId}
            onValueChange={(value) => {
              if (value !== null) void handleSelectSearchOwner(value);
            }}
            options={[
              { value: "", label: "-- 选择作者 (ownerId) --" },
              ...users.map((u) => ({ value: u.id, label: `${u.username} (${u.email})` })),
            ]}
          />
          <div className="flex gap-2">
            {ownerBooks.length > 0 ? (
              <Select
                id="searchBookId"
                label="检索作品"
                hideLabel
                required
                className="min-w-0 flex-1"
                value={searchBookId}
                onValueChange={(value) => value !== null && setSearchBookId(value)}
                options={ownerBooks.map((b) => ({
                  value: b.id,
                  label: `《${b.title}》(${b.chapterCount}章)`,
                }))}
              />
            ) : (
              <Input
                aria-label="作品 UUID"
                required
                value={searchBookId}
                onChange={(e) => setSearchBookId(e.target.value)}
                placeholder="bookId (UUID)"
                className="min-w-0 flex-1 font-mono"
              />
            )}
            <Input
              aria-label="最大章节号"
              type="number"
              value={searchMaxCh}
              onChange={(e) => setSearchMaxCh(e.target.value)}
              placeholder="maxCh"
              className="w-16 font-mono"
            />
            <Button type="submit" loading={searching} variant="default" size="sm">
              {searching ? "..." : "检索"}
            </Button>
          </div>
        </form>
        {searchResult && (
          <Card className="space-y-2 p-3">
            <div className="font-mono text-[11px] text-muted-foreground">
              命中 {searchResult.hits?.length ?? 0} 条结果
            </div>
            {!searchResult.hits?.length && <EmptyState title="暂无匹配结果" />}
            {(searchResult.hits ?? []).map((h, idx) => (
              <div
                key={`${h.chunk_id ?? idx}`}
                className="rounded border border-border/70 p-2.5 text-xs"
              >
                <div className="flex items-center justify-between font-mono text-[10px] text-primary">
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
          </Card>
        )}
      </Card>
    </AdminShell>
  );
}
