"use client";

import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { DataTable } from "@/components/ui/data-table";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { useToast } from "@/components/ui/toast";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { AdminShell } from "@/components/admin-shell";
import {
  adminAuditApi,
  adminPromptsApi,
  type AdminAuditLogRow,
  type PromptTemplateRow,
} from "@/lib/admin-api";
import {
  badgeForStatus,
  labelAuditAction,
  labelStatus,
  labelTargetType,
} from "@/lib/admin-labels";
import { formatAdminError } from "@/lib/error-messages";

export default function AdminPromptsAndAuditPage() {
  const [prompts, setPrompts] = useState<PromptTemplateRow[]>([]);
  const [audits, setAudits] = useState<AdminAuditLogRow[]>([]);
  const [name, setName] = useState("novel_writer_system");
  const [stage, setStage] = useState<"DRAFT" | "ACTIVE">("DRAFT");
  const [description, setDescription] = useState("");
  const [templateBody, setTemplateBody] = useState("");
  const [expandedAuditId, setExpandedAuditId] = useState<string | null>(null);
  const toast = useToast();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const loadAll = useCallback(async () => {
    setError(null);
    try {
      const [p, a] = await Promise.all([adminPromptsApi.list(), adminAuditApi.list(100)]);
      setPrompts(p);
      setAudits(a);
    } catch (err) {
      setError(formatAdminError(err, "加载模板与操作记录失败，请稍后重试"));
    }
  }, []);

  useEffect(() => {
    let active = true;
    Promise.all([adminPromptsApi.list(), adminAuditApi.list(100)])
      .then(([p, a]) => {
        if (!active) return;
        setPrompts(p);
        setAudits(a);
        setError(null);
      })
      .catch((err: unknown) => {
        if (!active) return;
        setError(formatAdminError(err, "加载模板与操作记录失败，请稍后重试"));
      });
    return () => {
      active = false;
    };
  }, []);

  async function handlePublish(e: FormEvent) {
    e.preventDefault();
    if (!name.trim() || !templateBody.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const created = await adminPromptsApi.publish({
        name: name.trim(),
        stage,
        description: description.trim(),
        templateBody,
      });
      const isActive = created.status === "ACTIVE" || created.stage === "ACTIVE";
      toast.success(
        isActive
          ? `已发布并启用 ${created.name} v${created.version}`
          : `已保存草稿 ${created.name} v${created.version}`,
      );
      setDescription("");
      setTemplateBody("");
      await loadAll();
    } catch (err) {
      setError(formatAdminError(err, "发布版本失败，请稍后重试", { action: "save" }));
    } finally {
      setBusy(false);
    }
  }

  async function handleActivate(row: PromptTemplateRow) {
    setBusy(true);
    setError(null);
    try {
      await adminPromptsApi.activate(row.id);
      toast.success(`已启用 ${row.name} v${row.version}`);
      await loadAll();
    } catch (err) {
      setError(formatAdminError(err, "启用版本失败，请稍后重试", { action: "save" }));
    } finally {
      setBusy(false);
    }
  }

  return (
    <AdminShell
      title="提示词与审计"
      actions={
        <Button type="button" onClick={() => void loadAll()} variant="outline" size="sm">
          刷新
        </Button>
      }
    >
      {error && (
        <Alert
          status="danger"
          announce="assertive"
          action={
            <Button type="button" variant="outline" size="sm" onClick={() => void loadAll()}>
              重新加载
            </Button>
          }
        >
          {error}
        </Alert>
      )}

      <section className="grid gap-6 lg:grid-cols-12">
        <Card className="p-4 lg:col-span-5">
          <form onSubmit={handlePublish} className="space-y-3">
            <h2 className="text-sm font-semibold">新建模板版本</h2>
            <div className="space-y-1">
              <label htmlFor="name" className="text-xs text-muted-foreground">
                模板名称
              </label>
              <Input
                id="name"
                required
                value={name}
                onChange={(e) => setName(e.target.value)}
                className="font-mono"
              />
            </div>

            <div className="space-y-1">
              <div className="text-xs text-muted-foreground">发布方式</div>
              <div
                role="group"
                aria-label="发布方式"
                className="inline-flex rounded-lg border border-border bg-muted/40 p-0.5"
              >
                <Button
                  type="button"
                  size="sm"
                  variant={stage === "DRAFT" ? "default" : "ghost"}
                  aria-pressed={stage === "DRAFT"}
                  onClick={() => setStage("DRAFT")}
                >
                  存为草稿
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant={stage === "ACTIVE" ? "default" : "ghost"}
                  aria-pressed={stage === "ACTIVE"}
                  onClick={() => setStage("ACTIVE")}
                >
                  发布并启用
                </Button>
              </div>
            </div>

            <div className="space-y-1">
              <label htmlFor="description" className="text-xs text-muted-foreground">
                版本说明（选填）
              </label>
              <Input
                id="description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="加强中式民俗悬疑氛围控制"
              />
            </div>

            <div className="space-y-1">
              <label htmlFor="templateBody" className="text-xs text-muted-foreground">
                模板正文
              </label>
              <Textarea
                id="templateBody"
                required
                rows={6}
                value={templateBody}
                onChange={(e) => setTemplateBody(e.target.value)}
                placeholder="输入模板正文"
                className="font-mono"
              />
            </div>

            <Button type="submit" loading={busy} variant="default" size="sm" className="w-full">
              {stage === "ACTIVE" ? "发布并启用" : "保存草稿"}
            </Button>
          </form>
        </Card>

        <Card className="p-4 lg:col-span-7">
          <h2 className="mb-3 text-sm font-semibold">模板版本 ({prompts.length})</h2>
          {prompts.length === 0 ? (
            <EmptyState title="暂无模板版本" />
          ) : (
            <div className="max-h-[440px] space-y-2 overflow-y-auto pr-1">
              {prompts.map((p) => (
                <Card key={p.id} className="p-3 text-xs">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="flex items-center gap-2 font-mono font-semibold">
                      <span>{p.name}</span>
                      <span className="text-primary">v{p.version}</span>
                      <Badge status={badgeForStatus(p.status)}>{labelStatus(p.status)}</Badge>
                    </div>
                    {p.status !== "ACTIVE" && (
                      <Button
                        type="button"
                        disabled={busy}
                        onClick={() => void handleActivate(p)}
                        variant="outline"
                        size="sm"
                      >
                        启用此版本
                      </Button>
                    )}
                  </div>
                  {p.description && <p className="mt-1 text-muted-foreground">{p.description}</p>}
                  <pre className="mt-2 max-h-24 overflow-x-auto whitespace-pre-wrap rounded bg-muted p-2 font-mono text-[11px] text-muted-foreground">
                    {p.templateBody}
                  </pre>
                </Card>
              ))}
            </div>
          )}
        </Card>
      </section>

      <Card id="audit-logs" className="p-4">
        <h2 className="mb-3 text-sm font-semibold">操作记录 ({audits.length})</h2>
        <DataTable
          caption="操作记录"
          rows={audits}
          rowKey={(a) => a.id}
          empty={<EmptyState title="暂无操作记录" />}
          columns={[
            {
              id: "created",
              header: "时间",
              className: "text-xs text-muted-foreground tabular-nums",
              cell: (a) => (a.createdAt ? new Date(a.createdAt).toLocaleString("zh-CN") : "-"),
            },
            { id: "operator", header: "操作人", cell: (a) => a.operatorName },
            {
              id: "action",
              header: "动作",
              cell: (a) => <Badge status="info">{labelAuditAction(a.action)}</Badge>,
            },
            {
              id: "target",
              header: "对象",
              cell: (a) => (
                <div>
                  <span>{labelTargetType(a.targetType)}</span>
                  {expandedAuditId === a.id && (
                    <div className="mt-1 space-y-0.5 font-mono text-[10px] text-muted-foreground">
                      <div>对象编号：{a.targetId}</div>
                      {a.clientIp && <div>来源 IP：{a.clientIp}</div>}
                      {a.traceId && <div>追踪编号：{a.traceId}</div>}
                    </div>
                  )}
                </div>
              ),
            },
            {
              id: "detail",
              header: "详情",
              align: "end",
              cell: (a) => (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() =>
                    setExpandedAuditId((prev) => (prev === a.id ? null : a.id))
                  }
                >
                  {expandedAuditId === a.id ? "收起" : "查看"}
                </Button>
              ),
            },
          ]}
        />
      </Card>
    </AdminShell>
  );
}
