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
  adminJobsApi,
  type AdminLibraryDocument,
  type OutboxEventRow,
  type RippleJobRow,
} from "@/lib/admin-api";
import {
  badgeForStatus,
  labelDocType,
  labelScope,
  labelStatus,
  labelTargetType,
} from "@/lib/admin-labels";
import { formatAdminError, formatJobFailureReason } from "@/lib/error-messages";

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
      setError(formatAdminError(err, "加载任务列表失败，请稍后重试"));
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
        setError(formatAdminError(err, "加载任务列表失败，请稍后重试"));
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
      toast.success("已重新排队投递");
      await loadJobs(statusFilter);
    } catch (err) {
      setError(formatAdminError(err, "重试投递失败，请稍后重试", { action: "retry" }));
    } finally {
      setBusyId(null);
    }
  }

  async function handleRetryDocument(docId: string) {
    setBusyId(docId);
    setError(null);
    try {
      await adminJobsApi.retryDocument(docId);
      toast.success("已重新排队处理");
      await loadJobs(statusFilter);
    } catch (err) {
      setError(formatAdminError(err, "重新处理失败，请稍后重试", { action: "retry" }));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <AdminShell
      title="任务中心"
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
              { value: "PENDING", label: "排队中" },
              { value: "FAILED", label: "失败" },
              { value: "PUBLISHED", label: "已投递" },
              { value: "READY", label: "已就绪" },
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
        <Alert
          status="danger"
          announce="assertive"
          action={
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => void loadJobs(statusFilter)}
            >
              重新加载
            </Button>
          }
        >
          {error}
        </Alert>
      )}

      <div
        role="tablist"
        aria-label="任务分类"
        className="inline-flex flex-wrap gap-1 rounded-lg border border-border bg-muted/40 p-1"
      >
        {(
          [
            ["outbox", `事件投递 (${outbox.length})`],
            ["documents", `资料处理 (${docs.length})`],
            ["ripples", `跨章修订 (${ripples.length})`],
          ] as [JobTab, string][]
        ).map(([k, label]) => (
          <Button
            key={k}
            type="button"
            role="tab"
            aria-selected={tab === k}
            onClick={() => setTab(k)}
            size="sm"
            variant={tab === k ? "default" : "ghost"}
          >
            {label}
          </Button>
        ))}
      </div>

      {tab === "outbox" && (
        <Card className="p-4">
          <DataTable
            caption="事件投递任务"
            rows={outbox}
            rowKey={(ev) => ev.id}
            empty={
              statusFilter ? (
                <EmptyState
                  title="当前状态下暂无投递任务"
                  action={
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => setStatusFilter("")}
                    >
                      查看全部
                    </Button>
                  }
                />
              ) : (
                <EmptyState title="暂无投递任务" />
              )
            }
            columns={[
              {
                id: "event",
                header: "事件",
                cell: (ev) => (
                  <>
                    <div className="font-medium">{ev.eventType}</div>
                    <div className="font-mono text-[10px] text-muted-foreground">
                      {ev.id.slice(0, 12)}
                    </div>
                  </>
                ),
              },
              {
                id: "aggregate",
                header: "关联对象",
                cell: (ev) => (
                  <>
                    <span>{labelTargetType(ev.aggregateType)}</span>
                    <span className="ml-1.5 font-mono text-[11px] text-muted-foreground">
                      {ev.aggregateId.slice(0, 10)}
                    </span>
                  </>
                ),
              },
              {
                id: "status",
                header: "状态",
                cell: (ev) => (
                  <Badge status={badgeForStatus(ev.status)}>{labelStatus(ev.status)}</Badge>
                ),
              },
              {
                id: "retries",
                header: "重试与结果",
                className: "text-xs text-muted-foreground",
                cell: (ev) => (
                  <>
                    <span className="font-mono tabular-nums">{ev.retryCount} 次</span>
                    {ev.lastError && (
                      <span className="ml-2 text-status-danger">
                        {formatJobFailureReason(ev.lastError, "投递失败")}
                      </span>
                    )}
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
                    loading={busyId === ev.id}
                    onClick={() => void handleRetryOutbox(ev.id)}
                    variant="outline"
                    size="sm"
                  >
                    重试
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
            caption="资料处理任务"
            rows={docs}
            rowKey={(doc) => doc.id}
            empty={
              statusFilter ? (
                <EmptyState
                  title="当前状态下暂无资料任务"
                  action={
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => setStatusFilter("")}
                    >
                      查看全部
                    </Button>
                  }
                />
              ) : (
                <EmptyState title="暂无资料处理任务" />
              )
            }
            columns={[
              {
                id: "title",
                header: "资料",
                className: "font-medium",
                cell: (doc) => doc.title,
              },
              {
                id: "scope",
                header: "范围与类型",
                cell: (doc) => (
                  <div className="flex flex-wrap items-center gap-1.5">
                    <Badge status="neutral">{labelScope(doc.scope, doc.accessType)}</Badge>
                    <span className="text-xs text-muted-foreground">
                      {labelDocType(doc.docType)}
                    </span>
                  </div>
                ),
              },
              {
                id: "progress",
                header: "状态与进度",
                cell: (doc) => (
                  <div className="flex items-center gap-2 text-xs">
                    <Badge status={badgeForStatus(doc.status)}>{labelStatus(doc.status)}</Badge>
                    <span className="font-mono text-muted-foreground tabular-nums">
                      {doc.progress}% · {doc.chunkCount} 段
                    </span>
                  </div>
                ),
              },
              {
                id: "error",
                header: "异常",
                className: "text-xs text-status-danger",
                cell: (doc) =>
                  doc.errorMessage ? formatJobFailureReason(doc.errorMessage, "解析失败") : "-",
              },
              {
                id: "actions",
                header: "操作",
                align: "end",
                cell: (doc) => (
                  <Button
                    type="button"
                    loading={busyId === doc.id}
                    onClick={() => void handleRetryDocument(doc.id)}
                    variant="outline"
                    size="sm"
                  >
                    重新处理
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
            caption="跨章修订任务"
            rows={ripples}
            rowKey={(job) => job.id}
            empty={<EmptyState title="暂无跨章修订任务" />}
            columns={[
              {
                id: "id",
                header: "任务编号",
                className: "font-mono text-xs",
                cell: (job) => job.id.slice(0, 14),
              },
              {
                id: "chapters",
                header: "修订范围",
                className: "text-xs tabular-nums",
                cell: (job) => (
                  <>
                    第 {job.sourceChapterNo} 章 → 第 {job.rangeStartChapterNo}–
                    {job.rangeEndChapterNo} 章
                  </>
                ),
              },
              {
                id: "status",
                header: "状态",
                cell: (job) => (
                  <Badge status={badgeForStatus(job.status)}>{labelStatus(job.status)}</Badge>
                ),
              },
              {
                id: "progress",
                header: "已修订 / 候选",
                className: "font-mono text-xs tabular-nums",
                cell: (job) => (
                  <>
                    {job.patchedCount} / {job.totalCandidates}
                  </>
                ),
              },
              {
                id: "created",
                header: "创建时间",
                className: "text-xs text-muted-foreground tabular-nums",
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
