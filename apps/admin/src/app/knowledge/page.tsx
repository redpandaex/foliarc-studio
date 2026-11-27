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
  adminKnowledgeApi,
  adminUsersApi,
  type AdminLibraryDocument,
  type AdminUserBook,
  type AdminUserRow,
  type DebugRagSearchResponse,
  type DocumentGrantRow,
} from "@/lib/admin-api";
import {
  badgeForStatus,
  labelDocType,
  labelGenreCodes,
  labelScope,
  labelStatus,
} from "@/lib/admin-labels";
import { formatAdminError } from "@/lib/error-messages";

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

  const [grantUserId, setGrantUserId] = useState("");
  const [grantNote, setGrantNote] = useState("");
  const [manualGrantId, setManualGrantId] = useState(false);
  const [activeGrantsDoc, setActiveGrantsDoc] = useState<AdminLibraryDocument | null>(null);
  const [activeGrants, setActiveGrants] = useState<DocumentGrantRow[]>([]);
  const [users, setUsers] = useState<AdminUserRow[]>([]);
  const [ownerBooks, setOwnerBooks] = useState<AdminUserBook[]>([]);

  const [searchQuery, setSearchQuery] = useState("");
  const [searchOwnerId, setSearchOwnerId] = useState("");
  const [searchBookId, setSearchBookId] = useState("");
  const [searchMaxCh, setSearchMaxCh] = useState("");
  const [manualSearchIds, setManualSearchIds] = useState(false);
  const [searchResult, setSearchResult] = useState<DebugRagSearchResponse | null>(null);
  const [searching, setSearching] = useState(false);

  const loadDocs = useCallback(async () => {
    try {
      const list = await adminKnowledgeApi.list();
      setDocs(list);
      setError(null);
    } catch (err) {
      setError(formatAdminError(err, "加载资料列表失败，请稍后重试"));
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
        setError(formatAdminError(err, "加载资料列表失败，请稍后重试"));
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
      toast.success("资料已上传，正在后台解析");
      await loadDocs();
    } catch (err) {
      setError(formatAdminError(err, "上传资料失败，请稍后重试", { action: "upload" }));
    } finally {
      setUploading(false);
    }
  }

  async function handleDelete(doc: AdminLibraryDocument) {
    await confirm({
      title: "删除平台资料",
      description: `删除「${doc.title}」后，作者将无法在创作中继续引用该资料。`,
      confirmLabel: "删除",
      variant: "destructive",
      onConfirm: async () => {
        try {
          await adminKnowledgeApi.remove(doc.id);
        } catch (err) {
          throw new Error(formatAdminError(err, "删除资料失败，请稍后重试", { action: "delete" }));
        }
        setDocs((prev) => prev.filter((d) => d.id !== doc.id));
        if (activeGrantsDoc?.id === doc.id) {
          setActiveGrantsDoc(null);
          setActiveGrants([]);
        }
        toast.success(`已删除「${doc.title}」`);
      },
    });
  }

  async function openGrants(doc: AdminLibraryDocument) {
    setActiveGrantsDoc(doc);
    setError(null);
    try {
      const list = await adminKnowledgeApi.listGrants(doc.id);
      setActiveGrants(list);
    } catch (err) {
      setError(formatAdminError(err, "加载授权记录失败，请稍后重试"));
    }
  }

  async function handleRevokeGrant(docId: string, userId: string) {
    setError(null);
    try {
      await adminKnowledgeApi.revoke(docId, userId);
      setActiveGrants((prev) => prev.filter((g) => g.userId !== userId));
      toast.success("已撤销授权");
    } catch (err) {
      setError(formatAdminError(err, "撤销授权失败，请稍后重试"));
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
    if (!activeGrantsDoc || !grantUserId.trim()) return;
    setError(null);
    try {
      await adminKnowledgeApi.grant(activeGrantsDoc.id, grantUserId.trim(), grantNote.trim());
      toast.success("已添加授权");
      setGrantUserId("");
      setGrantNote("");
      const list = await adminKnowledgeApi.listGrants(activeGrantsDoc.id);
      setActiveGrants(list);
    } catch (err) {
      setError(formatAdminError(err, "添加授权失败，请稍后重试"));
    }
  }

  async function handleDebugSearch(e: FormEvent) {
    e.preventDefault();
    if (!searchQuery.trim() || !searchOwnerId.trim() || !searchBookId.trim()) {
      setError("请选择目标作者、作品并输入查询词");
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
      setError(formatAdminError(err, "检索测试失败，请稍后重试", { action: "search" }));
    } finally {
      setSearching(false);
    }
  }

  return (
    <AdminShell
      title="公共资料"
      actions={
        <Button type="button" onClick={() => void loadDocs()} variant="outline" size="sm">
          刷新
        </Button>
      }
    >
      {error && (
        <Alert status="danger" announce="assertive">
          {error}
        </Alert>
      )}

      {/* Upload */}
      <Card className="p-5">
        <form onSubmit={handleUpload} className="grid gap-3.5 sm:grid-cols-2">
          <div className="sm:col-span-2">
            <h2 className="text-sm font-semibold">上传资料</h2>
          </div>

          <div className="space-y-1 sm:col-span-2">
            <div className="flex items-center justify-between text-xs text-muted-foreground">
              <label htmlFor="upload-file">文件</label>
              <span>PDF / DOCX / MD / TXT</span>
            </div>
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
                { value: "WORLDBOOK", label: "世界观资料" },
                { value: "STYLE_REF", label: "文风参考" },
                { value: "OUTLINE", label: "大纲模板" },
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
              placeholder="明清官制与盐政白描辞典"
            />
          </div>

          <div className="space-y-1.5 sm:col-span-2">
            <div className="text-xs text-muted-foreground">适用题材</div>
            <div className="flex flex-wrap gap-1.5">
              <Button
                type="button"
                onClick={() => setSelectedGenres([])}
                aria-pressed={selectedGenres.length === 0}
                size="sm"
                variant={selectedGenres.length === 0 ? "default" : "outline"}
              >
                全题材通用
              </Button>
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
                    {g.label}
                  </Button>
                );
              })}
            </div>
          </div>

          <div className="space-y-1">
            <label htmlFor="genreTags" className="text-xs text-muted-foreground">
              细分标签（选填）
            </label>
            <Input
              id="genreTags"
              value={genreTags}
              onChange={(e) => setGenreTags(e.target.value)}
              placeholder="明清世情、民俗怪谈"
            />
          </div>

          <div className="flex flex-wrap items-end justify-between gap-3">
            <div className="space-y-1">
              <div className="text-xs text-muted-foreground">可见范围</div>
              <div
                role="group"
                aria-label="可见范围"
                className="inline-flex rounded-lg border border-border bg-muted/40 p-0.5"
              >
                <Button
                  type="button"
                  size="sm"
                  variant={publicToAll ? "default" : "ghost"}
                  aria-pressed={publicToAll}
                  onClick={() => setPublicToAll(true)}
                >
                  全部作者
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant={!publicToAll ? "default" : "ghost"}
                  aria-pressed={!publicToAll}
                  onClick={() => setPublicToAll(false)}
                >
                  指定作者
                </Button>
              </div>
            </div>

            <Button type="submit" disabled={!file} loading={uploading} variant="default" size="sm">
              {uploading ? "上传中…" : "上传"}
            </Button>
          </div>
        </form>
      </Card>

      {/* Docs Table + Selected Doc Grants */}
      <section className="grid gap-6 lg:grid-cols-3">
        <Card className={`p-4 ${activeGrantsDoc ? "lg:col-span-2" : "lg:col-span-3"}`}>
          <h2 className="mb-3 text-sm font-semibold">资料列表 ({docs.length})</h2>
          <DataTable
            caption="资料列表"
            rows={docs}
            rowKey={(d) => d.id}
            loading={loading}
            empty={<EmptyState title="暂无平台资料" />}
            columns={[
              {
                id: "title",
                header: "资料",
                cell: (d) => (
                  <>
                    <div className="font-medium">{d.title}</div>
                    <div className="text-[11px] text-muted-foreground">{d.chunkCount} 段</div>
                  </>
                ),
              },
              {
                id: "type",
                header: "类型与题材",
                cell: (d) => (
                  <>
                    <Badge status="neutral">{labelDocType(d.docType)}</Badge>
                    <div className="mt-1 text-[11px] text-muted-foreground">
                      {labelGenreCodes(d.genreCodes)}
                      {d.genreTags ? ` · ${d.genreTags}` : ""}
                    </div>
                  </>
                ),
              },
              {
                id: "scope",
                header: "可见范围",
                cell: (d) => (
                  <Badge status={d.accessType === "ASSIGNED" ? "info" : "neutral"}>
                    {labelScope(d.scope, d.accessType)}
                  </Badge>
                ),
              },
              {
                id: "status",
                header: "状态",
                cell: (d) => <Badge status={badgeForStatus(d.status)}>{labelStatus(d.status)}</Badge>,
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
                      variant={activeGrantsDoc?.id === d.id ? "default" : "outline"}
                      size="sm"
                    >
                      授权
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

        {activeGrantsDoc && (
          <Card className="space-y-4 p-4">
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0">
                <h2 className="truncate text-sm font-semibold">授权管理</h2>
                <div className="mt-0.5 truncate text-xs text-muted-foreground">
                  {activeGrantsDoc.title}
                </div>
              </div>
              <Button
                type="button"
                onClick={() => setActiveGrantsDoc(null)}
                variant="ghost"
                size="sm"
              >
                关闭
              </Button>
            </div>

            <form onSubmit={handleGrant} className="space-y-3 border-b border-border pb-4">
              <div className="space-y-1">
                <div className="flex items-center justify-between text-xs text-muted-foreground">
                  <label htmlFor="target-author-id">作者</label>
                  {users.length > 0 && (
                    <button
                      type="button"
                      onClick={() => setManualGrantId((v) => !v)}
                      className="text-[11px] text-primary hover:underline"
                    >
                      {manualGrantId ? "从列表选择" : "按编号输入"}
                    </button>
                  )}
                </div>
                {users.length > 0 && !manualGrantId ? (
                  <Select
                    id="grantUserId"
                    label="作者"
                    hideLabel
                    value={grantUserId}
                    onValueChange={(value) => value !== null && setGrantUserId(value)}
                    options={[
                      { value: "", label: "选择作者" },
                      ...users.map((u) => ({ value: u.id, label: `${u.username}（${u.email}）` })),
                    ]}
                  />
                ) : (
                  <Input
                    id="target-author-id"
                    required
                    value={grantUserId}
                    onChange={(e) => setGrantUserId(e.target.value)}
                    placeholder="输入作者编号"
                    className="font-mono"
                  />
                )}
              </div>

              <div className="space-y-1">
                <label htmlFor="grantNote" className="text-xs text-muted-foreground">
                  备注（选填）
                </label>
                <Input
                  id="grantNote"
                  value={grantNote}
                  onChange={(e) => setGrantNote(e.target.value)}
                  placeholder="签约古风专项资料包"
                />
              </div>

              <Button
                type="submit"
                disabled={!grantUserId.trim()}
                variant="default"
                size="sm"
                className="w-full"
              >
                添加授权
              </Button>
            </form>

            <div className="space-y-2">
              <div className="text-xs font-medium text-muted-foreground">
                已授权作者 ({activeGrants.length})
              </div>
              {activeGrants.length === 0 ? (
                <EmptyState title="暂无指定授权" className="py-6" />
              ) : (
                <div className="space-y-1.5">
                  {activeGrants.map((g) => (
                    <div
                      key={g.grantId}
                      className="flex items-center justify-between gap-2 rounded-lg border border-border px-3 py-2 text-xs"
                    >
                      <div className="min-w-0">
                        <div className="truncate font-medium">{g.username}</div>
                        <div className="truncate text-[11px] text-muted-foreground">{g.email}</div>
                      </div>
                      <Button
                        type="button"
                        onClick={() => void handleRevokeGrant(g.documentId, g.userId)}
                        variant="destructive"
                        size="sm"
                      >
                        撤销
                      </Button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </Card>
        )}
      </section>

      {/* Retrieval Test */}
      <Card className="space-y-3 p-5">
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-sm font-semibold">检索测试</h2>
          <button
            type="button"
            onClick={() => setManualSearchIds((v) => !v)}
            className="text-xs text-primary hover:underline"
          >
            {manualSearchIds ? "按列表选择" : "按编号输入"}
          </button>
        </div>

        <form onSubmit={handleDebugSearch} className="grid gap-3 sm:grid-cols-12">
          <div className="space-y-1 sm:col-span-4">
            <label htmlFor="searchQuery" className="text-xs text-muted-foreground">
              查询词
            </label>
            <Input
              id="searchQuery"
              required
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="明清盐引勘合"
            />
          </div>

          <div className="space-y-1 sm:col-span-3">
            <label htmlFor="searchOwnerInput" className="text-xs text-muted-foreground">
              作者
            </label>
            {!manualSearchIds && users.length > 0 ? (
              <Select
                id="searchOwnerId"
                label="作者"
                hideLabel
                value={searchOwnerId}
                onValueChange={(value) => {
                  if (value !== null) void handleSelectSearchOwner(value);
                }}
                options={[
                  { value: "", label: "选择作者" },
                  ...users.map((u) => ({ value: u.id, label: `${u.username}（${u.email}）` })),
                ]}
              />
            ) : (
              <Input
                id="searchOwnerInput"
                required
                value={searchOwnerId}
                onChange={(e) => setSearchOwnerId(e.target.value)}
                placeholder="作者编号"
                className="font-mono"
              />
            )}
          </div>

          <div className="space-y-1 sm:col-span-3">
            <label htmlFor="searchBookInput" className="text-xs text-muted-foreground">
              作品
            </label>
            {!manualSearchIds && ownerBooks.length > 0 ? (
              <Select
                id="searchBookId"
                label="作品"
                hideLabel
                required
                value={searchBookId}
                onValueChange={(value) => value !== null && setSearchBookId(value)}
                options={ownerBooks.map((b) => ({
                  value: b.id,
                  label: `《${b.title}》（${b.chapterCount} 章）`,
                }))}
              />
            ) : (
              <Input
                id="searchBookInput"
                required
                value={searchBookId}
                onChange={(e) => setSearchBookId(e.target.value)}
                placeholder="作品编号"
                className="font-mono"
              />
            )}
          </div>

          <div className="flex items-end gap-2 sm:col-span-2">
            <div className="min-w-0 flex-1 space-y-1">
              <label htmlFor="searchMaxCh" className="text-xs text-muted-foreground">
                截至章
              </label>
              <Input
                id="searchMaxCh"
                type="number"
                min={1}
                value={searchMaxCh}
                onChange={(e) => setSearchMaxCh(e.target.value)}
                placeholder="全部"
                className="font-mono"
              />
            </div>
            <Button type="submit" loading={searching} variant="default" size="sm">
              检索
            </Button>
          </div>
        </form>

        {searchResult && (
          <div className="space-y-2 pt-1">
            <div className="text-xs text-muted-foreground">
              命中 {searchResult.hits?.length ?? 0} 条
            </div>
            {!searchResult.hits?.length ? (
              <EmptyState title="暂无匹配结果" className="py-6" />
            ) : (
              <div className="space-y-2">
                {(searchResult.hits ?? []).map((h, idx) => (
                  <div
                    key={`${h.chunk_id ?? idx}`}
                    className="rounded-lg border border-border/80 bg-background/50 p-3 text-xs"
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <Badge status="info">{labelScope(h.scope)}</Badge>
                        {h.doc_type && <Badge status="neutral">{labelDocType(h.doc_type)}</Badge>}
                        {typeof h.chapter_no === "number" && h.chapter_no > 0 && (
                          <span className="text-[11px] text-muted-foreground">
                            第 {h.chapter_no} 章
                          </span>
                        )}
                        {h.scene_title && (
                          <span className="font-medium text-foreground">{h.scene_title}</span>
                        )}
                      </div>
                      {typeof h.score === "number" && (
                        <details className="text-[11px] text-muted-foreground">
                          <summary className="cursor-pointer select-none">详情</summary>
                          <span className="ml-2 font-mono">相关度 {h.score.toFixed(3)}</span>
                        </details>
                      )}
                    </div>
                    <p className="mt-1.5 whitespace-pre-wrap text-xs leading-relaxed text-muted-foreground">
                      {(h.content ?? "").slice(0, 320)}
                    </p>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </Card>
    </AdminShell>
  );
}
