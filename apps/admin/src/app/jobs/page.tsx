"use client";

import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { DataTable } from "@/components/ui/data-table";
import { EmptyState } from "@/components/ui/empty-state";
import { Select } from "@/components/ui/select";
import { useToast } from "@/components/ui/toast";

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
  const toast = useToast();
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
        setError(
          err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载异步任务失败",
        );
      });
    return () => {
      active = false;
    };
  }, [statusFilter]);

  async function handleRetryOutbox(eventId: string) {
    setBusyId(eventId);
    setError(null);
    try {
      await adminJobsApi.retryOutbox(eventId);
      toast.success(`Outbox 事件 ${eventId.slice(0, 8)} 已重置为 PENDING 等待中继投递。`);
      await loadJobs(statusFilter);
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "重试失败");
    } finally {
      setBusyId(null);
    }
  }

  async function handleRetryDocument(docId: string) {
    setBusyId(docId);
    setError(null);
    try {
      await adminJobsApi.retryDocument(docId);
      toast.success(`文档 ${docId.slice(0, 8)} 已重新入队解析。`);
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
          <Select
            id="statusFilter"
            label="状态筛选"
            hideLabel
            value={statusFilter}
            onValueChange={(value) => value !== null && setStatusFilter(value)}
            options={[
              { value: "", label: "全部状态" },
              { value: "PENDING", label: "PENDING" },
              { value: "FAILED", label: "FAILED" },
              { value: "PUBLISHED", label: "PUBLISHED" },
              { value: "READY", label: "READY" },
            ]}
          />
          <Button
            type="button"
            onClick={() => void loadJobs(statusFilter)}
            variant="outline"
            size="sm"
          >
            刷新
          </Button>
        </div>
      }
    >
      {error && (
        <Alert status="danger" announce="assertive">
          {error}
        </Alert>
      )}

      <div className="flex gap-2">
        {(
          [
            ["outbox", `Outbox 发件箱 (${outbox.length})`],
            ["documents", `文档导入任务 (${docs.length})`],
            ["ripples", `蝴蝶效应作业 (${ripples.length})`],
          ] as [JobTab, string][]
        ).map(([k, label]) => (
          <Button
            key={k}
            type="button"
            onClick={() => setTab(k)}
            aria-pressed={tab === k}
            size="sm"
            variant={tab === k ? "default" : "outline"}
          >
            {label}
          </Button>
        ))}
      </div>

      {tab === "outbox" && (
        <Card className="p-4">
          <DataTable
            caption="Outbox 发件箱"
            rows={outbox}
            rowKey={(ev) => ev.id}
            empty={<EmptyState title="暂无 Outbox 事件" />}
            columns={[
              {
                id: "event",
                header: "事件类型 / RoutingKey",
                cell: (ev) => (
                  <>
                    <div className="font-mono font-medium">{ev.eventType}</div>
                    <div className="font-mono text-[10px] text-muted-foreground">
                      {ev.routingKey}
                    </div>
                  </>
                ),
              },
              {
                id: "aggregate",
                header: "聚合 ID",
                className: "font-mono text-[11px]",
                cell: (ev) => (
                  <>
                    {ev.aggregateType}:{ev.aggregateId.slice(0, 12)}
                  </>
                ),
              },
              {
                id: "status",
                header: "状态",
                className: "font-mono text-[11px]",
                cell: (ev) => (
                  <Badge
                    status={
                      ev.status === "FAILED"
                        ? "danger"
                        : ev.status === "PUBLISHED"
                          ? "success"
                          : "warning"
                    }
                  >
                    {ev.status}
                  </Badge>
                ),
              },
              {
                id: "retries",
                header: "重试次数 / 错误",
                className: "text-[11px] text-muted-foreground",
                cell: (ev) => (
                  <>
                    #{ev.retryCount} {ev.lastError ? `· ${ev.lastError.slice(0, 80)}` : ""}
                  </>
                ),
              },
              {
                id: "actions",
                header: "操作",
                align: "end",
                cell: (ev) => (
                  <Button
                    type="button"
                    disabled={busyId === ev.id}
                    onClick={() => void handleRetryOutbox(ev.id)}
                    variant="outline"
                    size="sm"
                  >
                    重试投递
                  </Button>
                ),
              },
            ]}
          />
        </Card>
      )}

      {tab === "documents" && (
        <Card className="p-4">
          <DataTable
            caption="文档导入任务"
            rows={docs}
            rowKey={(doc) => doc.id}
            empty={<EmptyState title="暂无文档导入任务" />}
            columns={[
              {
                id: "title",
                header: "文档标题",
                className: "font-medium",
                cell: (doc) => doc.title,
              },
              {
                id: "scope",
                header: "作用域 / 类型",
                className: "font-mono text-[11px]",
                cell: (doc) => (
                  <>
                    {doc.scope} / {doc.docType}
                  </>
                ),
              },
              {
                id: "progress",
                header: "进度",
                className: "font-mono text-[11px]",
                cell: (doc) => (
                  <>
                    <Badge
                      status={
                        doc.status === "READY"
                          ? "success"
                          : doc.status === "FAILED"
                            ? "danger"
                            : "warning"
                      }
                    >
                      {doc.status}
                    </Badge>{" "}
                    ({doc.progress}%) · {doc.chunkCount} chunks
                  </>
                ),
              },
              {
                id: "error",
                header: "错误",
                className: "text-[11px] text-status-danger",
                cell: (doc) => doc.errorMessage || "-",
              },
              {
                id: "actions",
                header: "操作",
                align: "end",
                cell: (doc) => (
                  <Button
                    type="button"
                    disabled={busyId === doc.id}
                    onClick={() => void handleRetryDocument(doc.id)}
                    variant="outline"
                    size="sm"
                  >
                    重新解析
                  </Button>
                ),
              },
            ]}
          />
        </Card>
      )}

      {tab === "ripples" && (
        <Card className="p-4">
          <DataTable
            caption="蝴蝶效应作业"
            rows={ripples}
            rowKey={(job) => job.id}
            empty={<EmptyState title="暂无蝴蝶效应作业" />}
            columns={[
              {
                id: "id",
                header: "Job ID",
                className: "font-mono text-[11px]",
                cell: (job) => job.id.slice(0, 14),
              },
              {
                id: "chapters",
                header: "源章节 / 扫描区间",
                className: "font-mono text-[11px]",
                cell: (job) => (
                  <>
                    Ch.{job.sourceChapterNo} → [{job.rangeStartChapterNo}..{job.rangeEndChapterNo}]
                  </>
                ),
              },
              {
                id: "status",
                header: "状态",
                className: "font-mono text-[11px] text-status-info",
                cell: (job) => (
                  <Badge status={job.status === "FAILED" ? "danger" : "info"}>{job.status}</Badge>
                ),
              },
              {
                id: "progress",
                header: "候选 / 已补丁",
                className: "font-mono text-[11px]",
                cell: (job) => (
                  <>
                    {job.patchedCount}/{job.totalCandidates}
                  </>
                ),
              },
              {
                id: "created",
                header: "创建时间",
                className: "font-mono text-[10px] text-muted-foreground",
                cell: (job) =>
                  job.createdAt ? new Date(job.createdAt).toLocaleString("zh-CN") : "-",
              },
            ]}
          />
        </Card>
      )}
    </AdminShell>
  );
}
