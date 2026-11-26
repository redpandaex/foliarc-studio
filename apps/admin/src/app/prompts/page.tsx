"use client";

import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { DataTable } from "@/components/ui/data-table";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { useToast } from "@/components/ui/toast";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { AdminShell } from "@/components/admin-shell";
import {
  AdminApiError,
  adminAuditApi,
  adminPromptsApi,
  type AdminAuditLogRow,
  type PromptTemplateRow,
} from "@/lib/admin-api";

export default function AdminPromptsAndAuditPage() {
  const [prompts, setPrompts] = useState<PromptTemplateRow[]>([]);
  const [audits, setAudits] = useState<AdminAuditLogRow[]>([]);
  const [name, setName] = useState("novel_writer_system");
  const [stage, setStage] = useState("DRAFT");
  const [description, setDescription] = useState("");
  const [templateBody, setTemplateBody] = useState("");
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
      setError(
        err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载模板与审计失败",
      );
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
        setError(
          err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载模板与审计失败",
        );
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
      toast.success(
        `已发布 Prompt 模板 ${created.name} v${created.version}（草稿态，可点击一键激活）。`,
      );
      setDescription("");
      setTemplateBody("");
      await loadAll();
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "发布模板失败");
    } finally {
      setBusy(false);
    }
  }

  async function handleActivate(row: PromptTemplateRow) {
    setBusy(true);
    setError(null);
    try {
      await adminPromptsApi.activate(row.id);
      toast.success(`已将 ${row.name} v${row.version} 切换为 ACTIVE，旧版本已归档。`);
      await loadAll();
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "激活版本失败");
    } finally {
      setBusy(false);
    }
  }

  return (
    <AdminShell
      title="Prompt 版本管理 & 后台操作审计"
      subtitle="管理多 Agent 核心 Prompt 模板版本发布、一键灰度激活/回滚，以及全部管理员写操作审计流水"
      actions={
        <Button type="button" onClick={() => void loadAll()} variant="outline" size="sm">
          刷新
        </Button>
      }
    >
      {error && (
        <Alert status="danger" announce="assertive">
          {error}
        </Alert>
      )}

      <section className="grid gap-6 lg:grid-cols-12">
        <Card className="p-4 lg:col-span-5">
          <form onSubmit={handlePublish} className="space-y-3">
            <h2 className="text-sm font-semibold">发布新版本 Prompt 模板</h2>
            <div className="grid grid-cols-2 gap-2">
              <div className="space-y-1">
                <label htmlFor="name" className="text-xs text-muted-foreground">
                  模板标识 (name)
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
                <Select
                  id="stage"
                  label="发布状态 (stage)"
                  value={stage}
                  onValueChange={(value) => value !== null && setStage(value)}
                  options={[
                    { value: "DRAFT", label: "DRAFT（存为草稿）" },
                    { value: "ACTIVE", label: "ACTIVE（发布并立即激活）" },
                  ]}
                />
              </div>
            </div>
            <div className="space-y-1">
              <label htmlFor="description" className="text-xs text-muted-foreground">
                版本说明
              </label>
              <Input
                id="description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="例如：加强中式民俗恐怖反套路约束"
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
                placeholder="输入 System Prompt 模板正文..."
                className="font-mono"
              />
            </div>
            <Button type="submit" loading={busy} variant="default" size="sm" className="w-full">
              发布新版本
            </Button>
          </form>
        </Card>

        <Card className="p-4 lg:col-span-7">
          <h2 className="mb-3 text-sm font-semibold">模板版本清单 ({prompts.length})</h2>
          {prompts.length === 0 ? (
            <EmptyState title="暂无 Prompt 模板版本" />
          ) : (
            <div className="max-h-[420px] space-y-2 overflow-y-auto pr-1">
              {prompts.map((p) => (
                <Card key={p.id} className="p-3 text-xs">
                  <div className="flex items-center justify-between gap-2">
                    <div className="font-mono font-semibold">
                      {p.name} <span className="text-primary">v{p.version}</span> · {p.stage}
                    </div>
                    <div className="flex items-center gap-2">
                      <Badge status={p.status === "ACTIVE" ? "success" : "neutral"}>
                        {p.status}
                      </Badge>
                      {p.status !== "ACTIVE" && (
                        <Button
                          type="button"
                          disabled={busy}
                          onClick={() => void handleActivate(p)}
                          variant="outline"
                          size="sm"
                        >
                          激活此版本
                        </Button>
                      )}
                    </div>
                  </div>
                  {p.description && <p className="mt-1 text-muted-foreground">{p.description}</p>}
                  <pre className="mt-2 max-h-24 overflow-x-auto whitespace-pre-wrap rounded bg-muted p-2 font-mono text-[10px] text-muted-foreground">
                    {p.templateBody}
                  </pre>
                </Card>
              ))}
            </div>
          )}
        </Card>
      </section>

      <Card className="p-4">
        <h2 className="mb-3 text-sm font-semibold">管理员操作审计日志 ({audits.length})</h2>
        <DataTable
          caption="管理员操作审计日志"
          rows={audits}
          rowKey={(a) => a.id}
          empty={<EmptyState title="暂无审计日志" />}
          columns={[
            {
              id: "action",
              header: "动作",
              className: "font-mono text-[11px] text-primary",
              cell: (a) => <Badge status="info">{a.action}</Badge>,
            },
            { id: "operator", header: "操作人", cell: (a) => a.operatorName },
            {
              id: "target",
              header: "目标",
              className: "font-mono text-[11px] text-muted-foreground",
              cell: (a) => (
                <>
                  {a.targetType}:{a.targetId}
                </>
              ),
            },
            {
              id: "ip",
              header: "来源 IP",
              className: "font-mono text-[11px] text-muted-foreground",
              cell: (a) => a.clientIp || "-",
            },
            {
              id: "created",
              header: "时间",
              className: "font-mono text-[10px] text-muted-foreground",
              cell: (a) => (a.createdAt ? new Date(a.createdAt).toLocaleString("zh-CN") : "-"),
            },
          ]}
        />
      </Card>
    </AdminShell>
  );
}
