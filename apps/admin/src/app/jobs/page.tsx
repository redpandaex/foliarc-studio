"use client";

import { useCallback, useEffect, useState } from "react";
import { AdminShell } from "@/components/admin-shell";
import {
  AdminApiError,
  adminJobsApi,
  type AdminLibraryDocument,
  type OutboxEventRow,
  type RippleJobRow,
} from "@/lib/admin-api";

type JobTab = "outbox" | "documents" | "ripples";

export default function AdminJobsPage() {
  const [tab, setTab] = useState<JobTab>("outbox");
  const [outbox, setOutbox] = useState<OutboxEventRow[]>([]);
  const [docs, setDocs] = useState<AdminLibraryDocument[]>([]);
  const [ripples, setRipples] = useState<RippleJobRow[]>([]);
  const [statusFilter, setStatusFilter] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const loadJobs = useCallback(async (filter?: string) => {
    setError(null);
    try {
      const [o, d, r] = await Promise.all([
        adminJobsApi.listOutbox(filter || undefined, 60),
        adminJobsApi.listDocuments(filter || undefined, 60),
        adminJobsApi.listRipples(40),
      ]);
      setOutbox(o);
      setDocs(d);
      setRipples(r);
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载异步任务失败");
    }
  }, []);

  useEffect(() => {
    let active = true;
    Promise.all([
      adminJobsApi.listOutbox(statusFilter || undefined, 60),
      adminJobsApi.listDocuments(statusFilter || undefined, 60),
      adminJobsApi.listRipples(40),
    ])
      .then(([o, d, r]) => {
        if (!active) return;
        setOutbox(o);
        setDocs(d);
        setRipples(r);
        setError(null);
      })
      .catch((err: unknown) => {
        if (!active) return;
        setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载异步任务失败");
      });
    return () => {
      active = false;
    };
  }, [statusFilter]);

  async function handleRetryOutbox(eventId: string) {
    setBusyId(eventId);
    setNotice(null);
    setError(null);
    try {
      await adminJobsApi.retryOutbox(eventId);
      setNotice(`Outbox 事件 ${eventId.slice(0, 8)} 已重置为 PENDING 等待中继投递。`);
      await loadJobs(statusFilter);
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "重试失败");
    } finally {
      setBusyId(null);
    }
  }

  async function handleRetryDocument(docId: string) {
    setBusyId(docId);
    setNotice(null);
    setError(null);
    try {
      await adminJobsApi.retryDocument(docId);
      setNotice(`文档 ${docId.slice(0, 8)} 已重新入队解析。`);
      await loadJobs(statusFilter);
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "文档重试失败");
    } finally {
      setBusyId(null);
    }
  }

  return (
    <AdminShell
      title="异步任务中心"
      subtitle="Outbox 事务发件箱重试 · RAG 文档解析流水线 · 蝴蝶效应改稿作业监控"
      actions={
        <div className="flex items-center gap-2">
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="rounded-md border border-border bg-card px-2.5 py-1.5 text-xs"
          >
            <option value="">全部状态</option>
            <option value="PENDING">PENDING</option>
            <option value="FAILED">FAILED</option>
            <option value="PUBLISHED">PUBLISHED</option>
            <option value="READY">READY</option>
          </select>
          <button
            type="button"
            onClick={() => void loadJobs(statusFilter)}
            className="rounded-md border border-border bg-card px-3 py-1.5 text-xs hover:bg-muted"
          >
            刷新
          </button>
        </div>
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

      <div className="flex gap-2">
        {(
          [
            ["outbox", `Outbox 发件箱 (${outbox.length})`],
            ["documents", `文档导入任务 (${docs.length})`],
            ["ripples", `蝴蝶效应作业 (${ripples.length})`],
          ] as [JobTab, string][]
        ).map(([k, label]) => (
          <button
            key={k}
            type="button"
            onClick={() => setTab(k)}
            className={`rounded-lg px-3.5 py-1.5 text-xs font-medium ${
              tab === k
                ? "bg-blue-600 text-white"
                : "border border-border bg-card text-muted-foreground hover:bg-muted"
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      {tab === "outbox" && (
        <div className="overflow-x-auto rounded-xl border border-border bg-card p-4">
          <table className="w-full text-left text-xs">
            <thead className="border-b border-border text-[11px] text-muted-foreground">
              <tr>
                <th className="py-2">事件类型 / RoutingKey</th>
                <th className="py-2">聚合 ID</th>
                <th className="py-2">状态</th>
                <th className="py-2">重试次数 / 错误</th>
                <th className="py-2 text-right">操作</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {outbox.map((ev) => (
                <tr key={ev.id}>
                  <td className="py-2">
                    <div className="font-mono font-medium">{ev.eventType}</div>
                    <div className="font-mono text-[10px] text-muted-foreground">{ev.routingKey}</div>
                  </td>
                  <td className="py-2 font-mono text-[11px]">
                    {ev.aggregateType}:{ev.aggregateId.slice(0, 12)}
                  </td>
                  <td className="py-2 font-mono text-[11px]">
                    <span
                      className={`rounded px-1.5 py-0.5 ${
                        ev.status === "FAILED"
                          ? "bg-rose-500/20 text-rose-300"
                          : ev.status === "PUBLISHED"
                            ? "bg-emerald-500/20 text-emerald-300"
                            : "bg-amber-500/20 text-amber-300"
                      }`}
                    >
                      {ev.status}
                    </span>
                  </td>
                  <td className="py-2 text-[11px] text-muted-foreground">
                    #{ev.retryCount} {ev.lastError ? `· ${ev.lastError.slice(0, 80)}` : ""}
                  </td>
                  <td className="py-2 text-right">
                    <button
                      type="button"
                      disabled={busyId === ev.id}
                      onClick={() => void handleRetryOutbox(ev.id)}
                      className="rounded border border-blue-500/40 px-2.5 py-1 text-[11px] text-blue-300 hover:bg-blue-500/10 disabled:opacity-50"
                    >
                      重试投递
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {tab === "documents" && (
        <div className="overflow-x-auto rounded-xl border border-border bg-card p-4">
          <table className="w-full text-left text-xs">
            <thead className="border-b border-border text-[11px] text-muted-foreground">
              <tr>
                <th className="py-2">文档标题</th>
                <th className="py-2">作用域 / 类型</th>
                <th className="py-2">进度</th>
                <th className="py-2">错误</th>
                <th className="py-2 text-right">操作</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {docs.map((doc) => (
                <tr key={doc.id}>
                  <td className="py-2 font-medium">{doc.title}</td>
                  <td className="py-2 font-mono text-[11px]">
                    {doc.scope} / {doc.docType}
                  </td>
                  <td className="py-2 font-mono text-[11px]">
                    {doc.status} ({doc.progress}%) · {doc.chunkCount} chunks
                  </td>
                  <td className="py-2 text-[11px] text-rose-300">{doc.errorMessage || "-"}</td>
                  <td className="py-2 text-right">
                    <button
                      type="button"
                      disabled={busyId === doc.id}
                      onClick={() => void handleRetryDocument(doc.id)}
                      className="rounded border border-blue-500/40 px-2.5 py-1 text-[11px] text-blue-300 hover:bg-blue-500/10 disabled:opacity-50"
                    >
                      重新解析
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {tab === "ripples" && (
        <div className="overflow-x-auto rounded-xl border border-border bg-card p-4">
          <table className="w-full text-left text-xs">
            <thead className="border-b border-border text-[11px] text-muted-foreground">
              <tr>
                <th className="py-2">Job ID</th>
                <th className="py-2">源章节 / 扫描区间</th>
                <th className="py-2">状态</th>
                <th className="py-2">候选 / 已补丁</th>
                <th className="py-2">创建时间</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {ripples.map((job) => (
                <tr key={job.id}>
                  <td className="py-2 font-mono text-[11px]">{job.id.slice(0, 14)}</td>
                  <td className="py-2 font-mono text-[11px]">
                    Ch.{job.sourceChapterNo} → [{job.rangeStartChapterNo}..{job.rangeEndChapterNo}]
                  </td>
                  <td className="py-2 font-mono text-[11px] text-sky-300">{job.status}</td>
                  <td className="py-2 font-mono text-[11px]">
                    {job.patchedCount}/{job.totalCandidates}
                  </td>
                  <td className="py-2 font-mono text-[10px] text-muted-foreground">
                    {job.createdAt ? new Date(job.createdAt).toLocaleString("zh-CN") : "-"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </AdminShell>
  );
}
