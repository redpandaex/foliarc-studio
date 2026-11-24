"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
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

const DOC_STATUS_LABEL: Record<string, { text: string; tone: string }> = {
  QUEUED: { text: "排队中", tone: "bg-amber-500/15 text-amber-600" },
  PARSING: { text: "解析中", tone: "bg-sky-500/15 text-sky-600" },
  CHUNKING: { text: "父子切片", tone: "bg-sky-500/15 text-sky-600" },
  EMBEDDING: { text: "向量入库", tone: "bg-violet-500/15 text-violet-600" },
  READY: { text: "已就绪", tone: "bg-emerald-500/15 text-emerald-600" },
  FAILED: { text: "解析失败", tone: "bg-rose-500/15 text-rose-600" },
};

const DOC_TYPE_LABEL: Record<string, string> = {
  WORLDBOOK: "世界观设定",
  CHAPTER: "已同步正文",
  STYLE_REF: "文风参考",
  OUTLINE: "大纲资料",
};

export function KnowledgeMemoryPanel({
  bookId,
  activeChapterId,
  activeChapterNo,
  activeChapterTitle,
}: KnowledgeMemoryPanelProps) {
  const [subTab, setSubTab] = useState<"docs" | "search" | "memory">("docs");
  const [docs, setDocs] = useState<KnowledgeDocumentItem[]>([]);
  const [memory, setMemory] = useState<StructuredMemorySnapshot | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
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
    setNotice(null);
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
      setNotice("文档已上传至 MinIO 并进入 RabbitMQ 异步解析切片队列");
      await refreshData();
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : "上传失败");
    } finally {
      setUploading(false);
    }
  }

  async function handleSyncCurrentChapter() {
    if (!activeChapterId) return;
    setSyncingChapter(true);
    setError(null);
    setNotice(null);
    try {
      await knowledgeApi.syncChapter(bookId, activeChapterId, true);
      setNotice("已通过 PG Outbox → RabbitMQ 触发本章向量同步 + 结构化记忆抽取");
      await refreshData();
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : "同步章节失败");
    } finally {
      setSyncingChapter(false);
    }
  }

  async function handleDeleteDoc(docId: string) {
    setError(null);
    setNotice(null);
    try {
      await knowledgeApi.deleteDocument(bookId, docId);
      setNotice("已软删除文档并派发 Milvus 双写清理事件");
      await refreshData();
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : "删除文档失败");
    }
  }

  async function handleReprocessDoc(docId: string) {
    setError(null);
    try {
      await knowledgeApi.reprocess(bookId, docId);
      setNotice("已递增 doc_version 并重新入队解析");
      await refreshData();
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : "重建索引失败");
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
      setError(err instanceof ApiClientError ? err.message : "混合检索失败");
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
      await refreshData();
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : "保存人物关系失败");
    }
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col text-xs">
      {/* Sub-navigation */}
      <div className="grid grid-cols-3 border-b border-border bg-muted/30 text-[11px]">
        <button
          type="button"
          onClick={() => setSubTab("docs")}
          className={`py-2 font-medium ${
            subTab === "docs" ? "bg-background text-foreground" : "text-muted-foreground"
          }`}
        >
          文档 ({docs.length})
        </button>
        <button
          type="button"
          onClick={() => setSubTab("search")}
          className={`py-2 font-medium ${
            subTab === "search" ? "bg-background text-foreground" : "text-muted-foreground"
          }`}
        >
          混合检索
        </button>
        <button
          type="button"
          onClick={() => setSubTab("memory")}
          className={`py-2 font-medium ${
            subTab === "memory" ? "bg-background text-foreground" : "text-muted-foreground"
          }`}
        >
          长记忆 ({(memory?.relations.length ?? 0) + (memory?.timelineEvents.length ?? 0)})
        </button>
      </div>

      {notice && (
        <div className="border-b border-emerald-500/30 bg-emerald-500/10 px-3 py-1.5 text-[11px] text-emerald-700 dark:text-emerald-400">
          {notice}
        </div>
      )}
      {error && (
        <div className="border-b border-rose-500/30 bg-rose-500/10 px-3 py-1.5 text-[11px] text-rose-600">
          {error}
        </div>
      )}

      {/* TAB 1: Documents & Chapter Sync */}
      {subTab === "docs" && (
        <div className="flex min-h-0 flex-1 flex-col">
          {/* Quick Chapter Sync Card */}
          <div className="border-b border-border bg-muted/20 p-3">
            <div className="flex items-center justify-between gap-2">
              <div className="min-w-0">
                <div className="font-semibold">当前章节入库 & 记忆抽取</div>
                <div className="truncate text-[11px] text-muted-foreground">
                  {activeChapterId
                    ? `第 ${activeChapterNo ?? "?"} 章 · ${activeChapterTitle ?? ""}`
                    : "请先选中一个章节"}
                </div>
              </div>
              <Button
                size="sm"
                variant="outline"
                disabled={!activeChapterId || syncingChapter}
                onClick={() => void handleSyncCurrentChapter()}
                className="h-7 shrink-0 px-2 text-[11px]"
              >
                {syncingChapter ? "入队中..." : "同步 & 抽记忆"}
              </Button>
            </div>
            <p className="mt-1 text-[10px] text-muted-foreground">
              提示：章节状态切换为「已定稿」保存时也会自动触发同步与记忆抽取。
            </p>
          </div>

          {/* Upload Form */}
          <form onSubmit={handleUpload} className="space-y-2 border-b border-border p-3">
            <div className="font-semibold">上传设定集 / 参考资料</div>
            <input
              type="file"
              accept=".pdf,.docx,.md,.txt"
              onChange={(e) => setSelectedFile(e.target.files?.[0] ?? null)}
              className="w-full text-[11px] file:mr-2 file:rounded file:border file:border-border file:bg-muted file:px-2 file:py-1 file:text-[11px]"
            />
            <div className="grid grid-cols-2 gap-1.5">
              <select
                value={uploadDocType}
                onChange={(e) => setUploadDocType(e.target.value as KnowledgeDocType)}
                className="rounded border border-border bg-background px-2 py-1 text-[11px]"
              >
                <option value="WORLDBOOK">世界观设定</option>
                <option value="STYLE_REF">文风参考</option>
                <option value="OUTLINE">大纲资料</option>
                <option value="CHAPTER">历史正文</option>
              </select>
              <input
                type="number"
                min={0}
                value={uploadChapterNo}
                onChange={(e) => setUploadChapterNo(e.target.value)}
                placeholder="生效章节 (0=全局)"
                title="防剧透章节号：0 表示全局设定，N 表示仅在 >= 第N章可见"
                className="rounded border border-border bg-background px-2 py-1 text-[11px]"
              />
            </div>
            <div className="flex gap-1.5">
              <input
                value={uploadTitle}
                onChange={(e) => setUploadTitle(e.target.value)}
                placeholder="文档标题（可选，默认文件名）"
                className="min-w-0 flex-1 rounded border border-border bg-background px-2 py-1 text-[11px]"
              />
              <Button
                type="submit"
                size="sm"
                disabled={!selectedFile || uploading}
                className="h-7 px-2.5 text-[11px]"
              >
                {uploading ? "上传..." : "上传入库"}
              </Button>
            </div>
          </form>

          {/* Document List */}
          <div className="flex-1 overflow-y-auto p-3">
            {docs.length === 0 ? (
              <p className="text-center text-muted-foreground">
                暂无知识库文档。上传 PDF / Word / Markdown / TXT 设定集，或点击上方同步当前章节。
              </p>
            ) : (
              <ul className="space-y-2">
                {docs.map((doc) => {
                  const st = DOC_STATUS_LABEL[doc.status] ?? {
                    text: doc.status,
                    tone: "bg-muted text-muted-foreground",
                  };
                  return (
                    <li
                      key={doc.id}
                      className="rounded-lg border border-border bg-background p-2.5"
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          <div className="truncate font-medium">{doc.title}</div>
                          <div className="mt-0.5 flex flex-wrap items-center gap-1.5 text-[10px] text-muted-foreground">
                            <span>{DOC_TYPE_LABEL[doc.docType] ?? doc.docType}</span>
                            <span>·</span>
                            <span>{doc.chapterNo === 0 ? "全局生效" : `第${doc.chapterNo}章`}</span>
                            <span>·</span>
                            <span className="font-mono">v{doc.docVersion}</span>
                            {doc.chunkCount > 0 && (
                              <>
                                <span>·</span>
                                <span>{doc.chunkCount} 块</span>
                              </>
                            )}
                          </div>
                        </div>
                        <span className={`shrink-0 rounded px-1.5 py-0.5 text-[10px] font-medium ${st.tone}`}>
                          {st.text}
                        </span>
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
                        <p className="mt-1.5 rounded bg-rose-500/10 p-1.5 text-[10px] text-rose-600">
                          {doc.errorMessage}
                        </p>
                      )}

                      <div className="mt-2 flex items-center justify-end gap-2 text-[10px]">
                        <button
                          type="button"
                          onClick={() => void handleReprocessDoc(doc.id)}
                          className="text-muted-foreground hover:text-foreground"
                        >
                          重建索引
                        </button>
                        <button
                          type="button"
                          onClick={() => void handleDeleteDoc(doc.id)}
                          className="text-rose-500 hover:underline"
                        >
                          删除(双写清理)
                        </button>
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
            <input
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="输入设定、专有名词或剧情问题（如：玄铁重剑规则）"
              className="w-full rounded border border-border bg-background px-2.5 py-1.5 text-xs"
            />
            <div className="flex items-center gap-2">
              <label className="flex items-center gap-1 text-[11px] text-muted-foreground">
                防剧透上限 ≤ 第
                <input
                  type="number"
                  min={0}
                  value={maxChapterFilter}
                  onChange={(e) => setMaxChapterFilter(e.target.value)}
                  placeholder="不限"
                  className="w-14 rounded border border-border bg-background px-1.5 py-0.5 text-center font-mono text-[11px]"
                />
                章
              </label>
              <label className="ml-auto flex items-center gap-1 text-[11px] text-muted-foreground">
                <input
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
              disabled={searching || !searchQuery.trim()}
              className="h-7 w-full text-xs"
            >
              {searching ? "Milvus Dense + BM25 + RRF 检索中..." : "执行混合检索"}
            </Button>
          </form>

          <div className="flex-1 overflow-y-auto p-3">
            {searchResult ? (
              <div className="space-y-2.5">
                <div className="flex flex-wrap items-center justify-between gap-1 rounded bg-muted/50 px-2 py-1 font-mono text-[10px] text-muted-foreground">
                  <span>命中 {searchResult.hits.length} 条</span>
                  <span>
                    embed={searchResult.embedding_mode} · rerank={searchResult.rerank_mode}
                  </span>
                </div>
                {searchResult.hits.length === 0 ? (
                  <p className="py-4 text-center text-muted-foreground">
                    未检索到符合当前防剧透条件的片段。
                  </p>
                ) : (
                  searchResult.hits.map((hit) => {
                    const expanded = expandedHitId === hit.parent_chunk_id;
                    return (
                      <div
                        key={hit.parent_chunk_id}
                        className="rounded-lg border border-border bg-background p-2.5"
                      >
                        <div className="flex items-center justify-between gap-2">
                          <span className="truncate font-semibold">{hit.document_title}</span>
                          <span className="shrink-0 rounded bg-emerald-500/15 px-1.5 py-0.5 font-mono text-[10px] text-emerald-600">
                            {hit.score.toFixed(3)}
                          </span>
                        </div>
                        <div className="mt-0.5 text-[10px] text-muted-foreground">
                          {hit.scene_title} ·{" "}
                          {hit.chapter_no === 0 ? "全局设定" : `第 ${hit.chapter_no} 章`}
                        </div>
                        <p className="mt-1.5 whitespace-pre-wrap text-[11px] leading-relaxed text-foreground/90">
                          {expanded ? hit.parent_context : hit.matched_snippet}
                        </p>
                        {hit.parent_context.length > hit.matched_snippet.length && (
                          <button
                            type="button"
                            onClick={() =>
                              setExpandedHitId(expanded ? null : hit.parent_chunk_id)
                            }
                            className="mt-1 text-[10px] text-primary hover:underline"
                          >
                            {expanded ? "收起父块上下文" : "展开完整父块上下文 (Small-to-Big)"}
                          </button>
                        )}
                      </div>
                    );
                  })
                )}
              </div>
            ) : (
              <p className="text-center text-muted-foreground">
                输入关键词测试 Milvus Dense + BM25 混合召回、RRF 融合与防剧透章节过滤。
              </p>
            )}
          </div>
        </div>
      )}

      {/* TAB 3: Structured Long-Term Memory (Relations + Timeline) */}
      {subTab === "memory" && (
        <div className="flex min-h-0 flex-1 flex-col overflow-y-auto p-3">
          <div className="mb-3 flex items-center justify-between">
            <span className="font-semibold">人物关系网 ({memory?.relations.length ?? 0})</span>
            <button
              type="button"
              onClick={() => void refreshData()}
              className="text-[11px] text-primary hover:underline"
            >
              刷新
            </button>
          </div>

          <form onSubmit={handleAddRelation} className="mb-3 space-y-1.5 rounded-lg border border-border bg-muted/20 p-2">
            <div className="grid grid-cols-3 gap-1">
              <input
                value={relSource}
                onChange={(e) => setRelSource(e.target.value)}
                placeholder="角色A"
                className="rounded border border-border bg-background px-1.5 py-1 text-[11px]"
              />
              <input
                value={relType}
                onChange={(e) => setRelType(e.target.value)}
                placeholder="关系(师徒/仇敌)"
                className="rounded border border-border bg-background px-1.5 py-1 text-[11px]"
              />
              <input
                value={relTarget}
                onChange={(e) => setRelTarget(e.target.value)}
                placeholder="角色B"
                className="rounded border border-border bg-background px-1.5 py-1 text-[11px]"
              />
            </div>
            <div className="flex gap-1">
              <input
                value={relDesc}
                onChange={(e) => setRelDesc(e.target.value)}
                placeholder="关系说明（可选）"
                className="min-w-0 flex-1 rounded border border-border bg-background px-1.5 py-1 text-[11px]"
              />
              <Button type="submit" size="sm" className="h-6 px-2 text-[10px]">
                添加
              </Button>
            </div>
          </form>

          {memory && memory.relations.length > 0 ? (
            <ul className="mb-4 space-y-1.5">
              {memory.relations.map((r) => (
                <li
                  key={r.id}
                  className="rounded border border-border bg-background p-2"
                >
                  <div className="flex items-center justify-between gap-1">
                    <span className="font-medium">
                      {r.sourceCharacter}{" "}
                      <span className="rounded bg-violet-500/15 px-1 py-0.5 text-[10px] text-violet-600">
                        {r.relationType}
                      </span>{" "}
                      {r.targetCharacter}
                    </span>
                    <button
                      type="button"
                      onClick={() =>
                        void memoryApi.deleteRelation(bookId, r.id).then(refreshData)
                      }
                      className="text-[10px] text-rose-500 hover:underline"
                    >
                      删除
                    </button>
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

          <div className="mb-2 font-semibold">
            事件时间线 ({memory?.timelineEvents.length ?? 0})
          </div>
          {memory && memory.timelineEvents.length > 0 ? (
            <ol className="space-y-2 border-l-2 border-border pl-3">
              {memory.timelineEvents.map((ev) => (
                <li key={ev.id} className="rounded border border-border bg-background p-2">
                  <div className="flex items-center justify-between gap-1">
                    <span className="font-medium">
                      <span className="mr-1 font-mono text-[10px] text-primary">
                        Ch.{ev.chapterNo}
                      </span>
                      {ev.eventTitle}
                    </span>
                    <button
                      type="button"
                      onClick={() =>
                        void memoryApi.deleteTimeline(bookId, ev.id).then(refreshData)
                      }
                      className="text-[10px] text-rose-500 hover:underline"
                    >
                      删除
                    </button>
                  </div>
                  {ev.summary && (
                    <p className="mt-1 text-[11px] text-muted-foreground">{ev.summary}</p>
                  )}
                  {(ev.involvedCharacters || ev.location) && (
                    <div className="mt-1 text-[10px] text-muted-foreground/80">
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
