"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Alert } from "@/components/ui/alert";
import { Badge, type UiStatus } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Select, type SelectOption } from "@/components/ui/select";
import { useToast } from "@/components/ui/toast";
import {
  ApiClientError,
  knowledgeApi,
  memoryApi,
  type KnowledgeDocType,
  type KnowledgeDocumentItem,
  type RagSearchResult,
  type StructuredMemorySnapshot,
} from "@/lib/api";

interface KnowledgeMemoryPanelProps {
  bookId: string;
  activeChapterId: string | null;
  activeChapterNo: number | null;
  activeChapterTitle: string | null;
}

const DOC_STATUS_META: Record<string, { text: string; status: UiStatus }> = {
  QUEUED: { text: "排队中", status: "warning" },
  PARSING: { text: "解析中", status: "info" },
  CHUNKING: { text: "父子切片", status: "info" },
  EMBEDDING: { text: "向量入库", status: "ai" },
  READY: { text: "已就绪", status: "success" },
  FAILED: { text: "解析失败", status: "danger" },
};

const DOC_TYPE_LABEL: Record<string, string> = {
  WORLDBOOK: "世界观设定",
  CHAPTER: "已同步正文",
  STYLE_REF: "文风参考",
  OUTLINE: "大纲资料",
};

const DOC_TYPE_OPTIONS: readonly SelectOption<KnowledgeDocType>[] = [
  { value: "WORLDBOOK", label: "世界观设定" },
  { value: "STYLE_REF", label: "文风参考" },
  { value: "OUTLINE", label: "大纲资料" },
  { value: "CHAPTER", label: "历史正文" },
];

export function KnowledgeMemoryPanel({
  bookId,
  activeChapterId,
  activeChapterNo,
  activeChapterTitle,
}: KnowledgeMemoryPanelProps) {
  const toast = useToast();
  const confirm = useConfirm();

  const [subTab, setSubTab] = useState<"docs" | "search" | "memory">("docs");
  const [docs, setDocs] = useState<KnowledgeDocumentItem[]>([]);
  const [memory, setMemory] = useState<StructuredMemorySnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Upload state
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploadDocType, setUploadDocType] = useState<KnowledgeDocType>("WORLDBOOK");
  const [uploadChapterNo, setUploadChapterNo] = useState("0");
  const [uploadTitle, setUploadTitle] = useState("");
  const [uploading, setUploading] = useState(false);
  const [syncingChapter, setSyncingChapter] = useState(false);

  // Search state
  const [searchQuery, setSearchQuery] = useState("");
  const [maxChapterFilter, setMaxChapterFilter] = useState(
    activeChapterNo !== null ? String(activeChapterNo) : "",
  );
  const [rewriteQuery, setRewriteQuery] = useState(false);
  const [searching, setSearching] = useState(false);
  const [searchResult, setSearchResult] = useState<RagSearchResult | null>(null);
  const [expandedHitId, setExpandedHitId] = useState<string | null>(null);

  // Quick manual relation form
  const [relSource, setRelSource] = useState("");
  const [relTarget, setRelTarget] = useState("");
  const [relType, setRelType] = useState("师徒");
  const [relDesc, setRelDesc] = useState("");

  const refreshData = useCallback(async () => {
    try {
      const [docList, memSnap] = await Promise.all([
        knowledgeApi.listDocuments(bookId),
        memoryApi.get(bookId),
      ]);
      setDocs(docList);
      setMemory(memSnap);
      setError(null);
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : "加载知识库数据失败");
    }
  }, [bookId]);

  useEffect(() => {
    let active = true;
    Promise.all([knowledgeApi.listDocuments(bookId), memoryApi.get(bookId)])
      .then(([docList, memSnap]) => {
        if (!active) return;
        setDocs(docList);
        setMemory(memSnap);
      })
      .catch((err: unknown) => {
        if (!active) return;
        setError(err instanceof ApiClientError ? err.message : "加载知识库数据失败");
      });
    return () => {
      active = false;
    };
  }, [bookId]);

  // Auto-poll while any document is in flight
  const hasInFlight = docs.some((d) =>
    ["QUEUED", "PARSING", "CHUNKING", "EMBEDDING"].includes(d.status),
  );
  useEffect(() => {
    if (!hasInFlight) return;
    const timer = setInterval(() => {
      void refreshData();
    }, 2000);
    return () => clearInterval(timer);
  }, [hasInFlight, refreshData]);

  async function handleUpload(e: FormEvent) {
    e.preventDefault();
    if (!selectedFile) return;
    setUploading(true);
    setError(null);
    try {
      const chapterNoNum = Number.parseInt(uploadChapterNo, 10);
      await knowledgeApi.uploadDocument(bookId, {
        file: selectedFile,
        docType: uploadDocType,
        chapterNo: Number.isNaN(chapterNoNum) ? 0 : Math.max(0, chapterNoNum),
        title: uploadTitle,
      });
      setSelectedFile(null);
      setUploadTitle("");
      toast.success("文档已入队解析", {
        description: "已上传至 MinIO 并进入 RabbitMQ 异步解析切片队列",
      });
      await refreshData();
    } catch (err) {
      const msg = err instanceof ApiClientError ? err.message : "上传失败";
      setError(msg);
      toast.error(msg);
    } finally {
      setUploading(false);
    }
  }

  async function handleSyncCurrentChapter() {
    if (!activeChapterId) return;
    setSyncingChapter(true);
    setError(null);
    try {
      await knowledgeApi.syncChapter(bookId, activeChapterId, true);
      toast.success("已触发章节同步与记忆抽取", {
        description: "通过 PG Outbox → RabbitMQ 异步更新向量库与长记忆",
      });
      await refreshData();
    } catch (err) {
      const msg = err instanceof ApiClientError ? err.message : "同步章节失败";
      setError(msg);
      toast.error(msg);
    } finally {
      setSyncingChapter(false);
    }
  }

  async function handleDeleteDoc(doc: KnowledgeDocumentItem) {
    setError(null);
    await confirm({
      title: `删除知识库文档「${doc.title}」？`,
      description: "将执行软删除并派发 Milvus 双写清理事件，对应切片不再参与 RAG 检索。",
      confirmLabel: "确认删除",
      variant: "destructive",
      onConfirm: async () => {
        await knowledgeApi.deleteDocument(bookId, doc.id);
        toast.success("已软删除文档并派发 Milvus 双写清理事件");
        await refreshData();
      },
    });
  }

  async function handleReprocessDoc(docId: string) {
    setError(null);
    try {
      await knowledgeApi.reprocess(bookId, docId);
      toast.info("已递增 doc_version 并重新入队解析");
      await refreshData();
    } catch (err) {
      const msg = err instanceof ApiClientError ? err.message : "重建索引失败";
      setError(msg);
      toast.error(msg);
    }
  }

  async function handleSearch(e: FormEvent) {
    e.preventDefault();
    if (!searchQuery.trim()) return;
    setSearching(true);
    setError(null);
    try {
      const parsedCap = Number.parseInt(maxChapterFilter, 10);
      const res = await knowledgeApi.search(bookId, {
        query: searchQuery.trim(),
        topK: 5,
        maxChapterNo:
          maxChapterFilter.trim() !== "" && !Number.isNaN(parsedCap)
            ? parsedCap
            : undefined,
        rewriteQuery,
      });
      setSearchResult(res);
    } catch (err) {
      const msg = err instanceof ApiClientError ? err.message : "混合检索失败";
      setError(msg);
      toast.error(msg);
    } finally {
      setSearching(false);
    }
  }

  async function handleAddRelation(e: FormEvent) {
    e.preventDefault();
    if (!relSource.trim() || !relTarget.trim() || !relType.trim()) return;
    setError(null);
    try {
      await memoryApi.upsertRelation(bookId, {
        sourceCharacter: relSource.trim(),
        targetCharacter: relTarget.trim(),
        relationType: relType.trim(),
        description: relDesc.trim(),
        sinceChapterNo: activeChapterNo ?? undefined,
      });
      setRelSource("");
      setRelTarget("");
      setRelDesc("");
      toast.success("人物关系已保存");
      await refreshData();
    } catch (err) {
      const msg = err instanceof ApiClientError ? err.message : "保存人物关系失败";
      setError(msg);
      toast.error(msg);
    }
  }

  async function handleDeleteRelation(id: string, label: string) {
    await confirm({
      title: `删除人物关系「${label}」？`,
      description: "删除后 AI 长记忆将不再自动引用该条关系记录。",
      confirmLabel: "确认删除",
      variant: "destructive",
      onConfirm: async () => {
        await memoryApi.deleteRelation(bookId, id);
        toast.success("已删除人物关系");
        await refreshData();
      },
    });
  }

  async function handleDeleteTimeline(id: string, title: string) {
    await confirm({
      title: `删除时间线事件「${title}」？`,
      description: "删除后将从本书结构化长记忆时间线中移除。",
      confirmLabel: "确认删除",
      variant: "destructive",
      onConfirm: async () => {
        await memoryApi.deleteTimeline(bookId, id);
        toast.success("已删除时间线事件");
        await refreshData();
      },
    });
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col text-xs">
      {/* Sub-navigation */}
      <div
        role="tablist"
        aria-label="知识库与长记忆功能切换"
        className="grid grid-cols-3 border-b border-border bg-surface-subtle text-[11px]"
      >
        <button
          type="button"
          role="tab"
          aria-selected={subTab === "docs"}
          onClick={() => setSubTab("docs")}
          className={`py-2 font-medium transition-colors focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-ring ${
            subTab === "docs"
              ? "bg-card text-foreground shadow-[inset_0_-2px_0_0_var(--primary)]"
              : "text-muted-foreground hover:text-foreground"
          }`}
        >
          文档 ({docs.length})
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={subTab === "search"}
          onClick={() => setSubTab("search")}
          className={`py-2 font-medium transition-colors focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-ring ${
            subTab === "search"
              ? "bg-card text-foreground shadow-[inset_0_-2px_0_0_var(--primary)]"
              : "text-muted-foreground hover:text-foreground"
          }`}
        >
          混合检索
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={subTab === "memory"}
          onClick={() => setSubTab("memory")}
          className={`py-2 font-medium transition-colors focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-ring ${
            subTab === "memory"
              ? "bg-card text-foreground shadow-[inset_0_-2px_0_0_var(--primary)]"
              : "text-muted-foreground hover:text-foreground"
          }`}
        >
          长记忆 ({(memory?.relations.length ?? 0) + (memory?.timelineEvents.length ?? 0)})
        </button>
      </div>

      {error && (
        <div className="border-b border-border p-2.5">
          <Alert status="danger" announce="assertive">
            {error}
          </Alert>
        </div>
      )}

      {/* TAB 1: Documents & Chapter Sync */}
      {subTab === "docs" && (
        <div className="flex min-h-0 flex-1 flex-col">
          {/* Quick Chapter Sync Card */}
          <div className="border-b border-border bg-surface-subtle p-3">
            <div className="flex items-center justify-between gap-2">
              <div className="min-w-0">
                <div className="font-semibold text-foreground">当前章节入库 & 记忆抽取</div>
                <div className="truncate text-[11px] text-muted-foreground">
                  {activeChapterId
                    ? `第 ${activeChapterNo ?? "?"} 章 · ${activeChapterTitle ?? ""}`
                    : "请先选中一个章节"}
                </div>
              </div>
              <Button
                size="xs"
                variant="outline"
                loading={syncingChapter}
                disabled={!activeChapterId}
                onClick={() => void handleSyncCurrentChapter()}
                className="shrink-0"
              >
                {syncingChapter ? "入队中..." : "同步 & 抽记忆"}
              </Button>
            </div>
            <p className="mt-1.5 text-[10px] text-muted-foreground">
              提示：章节状态切换为「已定稿」保存时也会自动触发同步与记忆抽取。
            </p>
          </div>

          {/* Upload Form */}
          <form onSubmit={handleUpload} className="space-y-2 border-b border-border p-3">
            <div className="font-semibold text-foreground">上传设定集 / 参考资料</div>
            <Input
              type="file"
              accept=".pdf,.docx,.md,.txt"
              aria-label="选择要上传的设定集或参考资料"
              onChange={(e) => setSelectedFile(e.target.files?.[0] ?? null)}
              className="text-[11px]"
            />
            <div className="grid grid-cols-2 gap-1.5">
              <Select<KnowledgeDocType>
                id="kb-upload-doc-type"
                label="文档类型"
                hideLabel
                value={uploadDocType}
                options={DOC_TYPE_OPTIONS}
                onValueChange={(next) => {
                  if (next) setUploadDocType(next);
                }}
              />
              <Input
                type="number"
                min={0}
                value={uploadChapterNo}
                onChange={(e) => setUploadChapterNo(e.target.value)}
                placeholder="生效章节 (0=全局)"
                aria-label="防剧透生效章节号"
                title="防剧透章节号：0 表示全局设定，N 表示仅在 >= 第N章可见"
                className="text-[11px]"
              />
            </div>
            <div className="flex gap-1.5">
              <Input
                value={uploadTitle}
                onChange={(e) => setUploadTitle(e.target.value)}
                placeholder="文档标题（可选，默认文件名）"
                aria-label="文档标题"
                className="min-w-0 flex-1 text-[11px]"
              />
              <Button
                type="submit"
                size="sm"
                loading={uploading}
                disabled={!selectedFile}
                className="shrink-0"
              >
                {uploading ? "上传..." : "上传入库"}
              </Button>
            </div>
          </form>

          {/* Document List */}
          <div className="flex-1 overflow-y-auto p-3">
            {docs.length === 0 ? (
              <EmptyState
                title="暂无知识库文档"
                description="上传 PDF / Word / Markdown / TXT 设定集，或点击上方同步当前章节。"
              />
            ) : (
              <ul className="space-y-2">
                {docs.map((doc) => {
                  const st = DOC_STATUS_META[doc.status] ?? {
                    text: doc.status,
                    status: "neutral" as UiStatus,
                  };
                  return (
                    <li
                      key={doc.id}
                      className="rounded-lg border border-border bg-card p-2.5"
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          <div className="truncate font-medium text-foreground">{doc.title}</div>
                          <div className="mt-0.5 flex flex-wrap items-center gap-1.5 text-[10px] text-muted-foreground">
                            <span>{DOC_TYPE_LABEL[doc.docType] ?? doc.docType}</span>
                            <span>·</span>
                            <span>{doc.chapterNo === 0 ? "全局生效" : `第${doc.chapterNo}章`}</span>
                            <span>·</span>
                            <span className="font-mono">v{doc.docVersion}</span>
                            {doc.chunkCount > 0 && (
                              <>
                                <span>·</span>
                                <span className="tabular-nums">{doc.chunkCount} 块</span>
                              </>
                            )}
                          </div>
                        </div>
                        <Badge status={st.status} className="shrink-0 text-[10px]">
                          {st.text}
                        </Badge>
                      </div>

                      {doc.status !== "READY" && doc.status !== "FAILED" && (
                        <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-muted">
                          <div
                            className="h-full bg-primary transition-all"
                            style={{ width: `${Math.max(8, doc.progress)}%` }}
                          />
                        </div>
                      )}

                      {doc.errorMessage && (
                        <Alert status="danger" className="mt-2 px-2 py-1.5 text-[10px]">
                          {doc.errorMessage}
                        </Alert>
                      )}

                      <div className="mt-2 flex items-center justify-end gap-1.5">
                        <Button
                          variant="ghost"
                          size="xs"
                          onClick={() => void handleReprocessDoc(doc.id)}
                          className="h-6 px-2 text-[10px]"
                        >
                          重建索引
                        </Button>
                        <Button
                          variant="ghost"
                          size="xs"
                          onClick={() => void handleDeleteDoc(doc)}
                          className="h-6 px-2 text-[10px] text-status-danger hover:bg-status-danger-bg hover:text-status-danger"
                        >
                          删除(双写清理)
                        </Button>
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </div>
      )}

      {/* TAB 2: Hybrid RAG Search Inspector */}
      {subTab === "search" && (
        <div className="flex min-h-0 flex-1 flex-col">
          <form onSubmit={handleSearch} className="space-y-2 border-b border-border p-3">
            <Input
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="输入设定、专有名词或剧情问题（如：玄铁重剑规则）"
              aria-label="检索关键词"
            />
            <div className="flex items-center gap-2">
              <label className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
                防剧透上限 ≤ 第
                <Input
                  type="number"
                  min={0}
                  value={maxChapterFilter}
                  onChange={(e) => setMaxChapterFilter(e.target.value)}
                  placeholder="不限"
                  className="h-7 w-16 px-1.5 text-center font-mono text-[11px]"
                />
                章
              </label>
              <label className="ml-auto flex cursor-pointer items-center gap-1.5 text-[11px] text-muted-foreground">
                <Input
                  type="checkbox"
                  checked={rewriteQuery}
                  onChange={(e) => setRewriteQuery(e.target.checked)}
                />
                查询改写
              </label>
            </div>
            <Button
              type="submit"
              size="sm"
              loading={searching}
              disabled={!searchQuery.trim()}
              className="w-full"
            >
              {searching ? "Milvus Dense + BM25 + RRF 检索中..." : "执行混合检索"}
            </Button>
          </form>

          <div className="flex-1 overflow-y-auto p-3">
            {searchResult ? (
              <div className="space-y-2.5">
                <div className="flex flex-wrap items-center justify-between gap-1 rounded-lg border border-border bg-surface-subtle px-2.5 py-1.5 font-mono text-[10px] text-muted-foreground">
                  <span className="tabular-nums">命中 {searchResult.hits.length} 条</span>
                  <span>
                    embed={searchResult.embedding_mode} · rerank={searchResult.rerank_mode}
                  </span>
                </div>
                {searchResult.hits.length === 0 ? (
                  <EmptyState
                    title="未命中片段"
                    description="未检索到符合当前防剧透条件的设定或正文片段。"
                  />
                ) : (
                  searchResult.hits.map((hit) => {
                    const expanded = expandedHitId === hit.parent_chunk_id;
                    return (
                      <div
                        key={hit.parent_chunk_id}
                        className="rounded-lg border border-border bg-card p-2.5"
                      >
                        <div className="flex items-center justify-between gap-2">
                          <span className="truncate font-semibold text-foreground">
                            {hit.document_title}
                          </span>
                          <Badge status="success" className="shrink-0 font-mono text-[10px] tabular-nums">
                            {hit.score.toFixed(3)}
                          </Badge>
                        </div>
                        <div className="mt-0.5 text-[10px] text-muted-foreground">
                          {hit.scene_title} ·{" "}
                          {hit.chapter_no === 0 ? "全局设定" : `第 ${hit.chapter_no} 章`}
                        </div>
                        <p className="mt-1.5 whitespace-pre-wrap text-[11px] leading-relaxed text-foreground">
                          {expanded ? hit.parent_context : hit.matched_snippet}
                        </p>
                        {hit.parent_context.length > hit.matched_snippet.length && (
                          <Button
                            variant="link"
                            size="xs"
                            onClick={() =>
                              setExpandedHitId(expanded ? null : hit.parent_chunk_id)
                            }
                            className="mt-1 h-auto p-0 text-[10px]"
                          >
                            {expanded ? "收起父块上下文" : "展开完整父块上下文 (Small-to-Big)"}
                          </Button>
                        )}
                      </div>
                    );
                  })
                )}
              </div>
            ) : (
              <EmptyState
                title="混合检索调试器"
                description="输入关键词测试 Milvus Dense + BM25 混合召回、RRF 融合与防剧透章节过滤。"
              />
            )}
          </div>
        </div>
      )}

      {/* TAB 3: Structured Long-Term Memory (Relations + Timeline) */}
      {subTab === "memory" && (
        <div className="flex min-h-0 flex-1 flex-col overflow-y-auto p-3">
          <div className="mb-3 flex items-center justify-between">
            <span className="font-semibold text-foreground">
              人物关系网 ({memory?.relations.length ?? 0})
            </span>
            <Button
              variant="link"
              size="xs"
              onClick={() => void refreshData()}
              className="h-auto p-0 text-[11px]"
            >
              刷新
            </Button>
          </div>

          <form
            onSubmit={handleAddRelation}
            className="mb-3 space-y-1.5 rounded-lg border border-border bg-surface-subtle p-2.5"
          >
            <div className="grid grid-cols-3 gap-1.5">
              <Input
                value={relSource}
                onChange={(e) => setRelSource(e.target.value)}
                placeholder="角色A"
                aria-label="角色A"
                className="h-7 px-2 text-[11px]"
              />
              <Input
                value={relType}
                onChange={(e) => setRelType(e.target.value)}
                placeholder="关系(师徒/仇敌)"
                aria-label="关系类型"
                className="h-7 px-2 text-[11px]"
              />
              <Input
                value={relTarget}
                onChange={(e) => setRelTarget(e.target.value)}
                placeholder="角色B"
                aria-label="角色B"
                className="h-7 px-2 text-[11px]"
              />
            </div>
            <div className="flex gap-1.5">
              <Input
                value={relDesc}
                onChange={(e) => setRelDesc(e.target.value)}
                placeholder="关系说明（可选）"
                aria-label="关系说明"
                className="h-7 min-w-0 flex-1 px-2 text-[11px]"
              />
              <Button type="submit" size="xs" className="shrink-0">
                添加
              </Button>
            </div>
          </form>

          {memory && memory.relations.length > 0 ? (
            <ul className="mb-4 space-y-1.5">
              {memory.relations.map((r) => (
                <li
                  key={r.id}
                  className="rounded-lg border border-border bg-card p-2.5"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="flex flex-wrap items-center gap-1.5 font-medium text-foreground">
                      <span>{r.sourceCharacter}</span>
                      <Badge status="ai" className="text-[10px]">
                        {r.relationType}
                      </Badge>
                      <span>{r.targetCharacter}</span>
                    </span>
                    <Button
                      variant="ghost"
                      size="xs"
                      onClick={() =>
                        void handleDeleteRelation(
                          r.id,
                          `${r.sourceCharacter} - ${r.relationType} - ${r.targetCharacter}`,
                        )
                      }
                      className="h-6 shrink-0 px-1.5 text-[10px] text-status-danger hover:bg-status-danger-bg hover:text-status-danger"
                    >
                      删除
                    </Button>
                  </div>
                  {r.description && (
                    <p className="mt-1 text-[11px] text-muted-foreground">{r.description}</p>
                  )}
                </li>
              ))}
            </ul>
          ) : (
            <p className="mb-4 text-[11px] text-muted-foreground">
              暂无人物关系记录（章节定稿或手动抽取记忆后自动填充）。
            </p>
          )}

          <div className="mb-2 font-semibold text-foreground">
            事件时间线 ({memory?.timelineEvents.length ?? 0})
          </div>
          {memory && memory.timelineEvents.length > 0 ? (
            <ol className="space-y-2 border-l-2 border-border pl-3">
              {memory.timelineEvents.map((ev) => (
                <li key={ev.id} className="rounded-lg border border-border bg-card p-2.5">
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-medium text-foreground">
                      <span className="mr-1.5 font-mono text-[10px] text-primary">
                        Ch.{ev.chapterNo}
                      </span>
                      {ev.eventTitle}
                    </span>
                    <Button
                      variant="ghost"
                      size="xs"
                      onClick={() => void handleDeleteTimeline(ev.id, ev.eventTitle)}
                      className="h-6 shrink-0 px-1.5 text-[10px] text-status-danger hover:bg-status-danger-bg hover:text-status-danger"
                    >
                      删除
                    </Button>
                  </div>
                  {ev.summary && (
                    <p className="mt-1 text-[11px] text-muted-foreground">{ev.summary}</p>
                  )}
                  {(ev.involvedCharacters || ev.location) && (
                    <div className="mt-1 text-[10px] text-muted-foreground">
                      {ev.involvedCharacters && <span>人物: {ev.involvedCharacters}</span>}
                      {ev.involvedCharacters && ev.location && <span> · </span>}
                      {ev.location && <span>地点: {ev.location}</span>}
                    </div>
                  )}
                </li>
              ))}
            </ol>
          ) : (
            <p className="text-[11px] text-muted-foreground">
              暂无时间线事件（章节定稿或点击「同步 & 抽记忆」自动生成）。
            </p>
          )}
        </div>
      )}
    </div>
  );
}
