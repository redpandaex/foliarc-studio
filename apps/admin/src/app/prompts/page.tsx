"use client";

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
  const [name, setName] = useState("writer_system_core");
  const [stage, setStage] = useState("DRAFT");
  const [description, setDescription] = useState("");
  const [templateBody, setTemplateBody] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const loadAll = useCallback(async () => {
    setError(null);
    try {
      const [p, a] = await Promise.all([adminPromptsApi.list(), adminAuditApi.list(100)]);
      setPrompts(p);
      setAudits(a);
    } catch (err) {
      setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载模板与审计失败");
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
        setError(err instanceof AdminApiError ? `[${err.code}] ${err.message}` : "加载模板与审计失败");
      });
    return () => {
      active = false;
    };
  }, []);

  async function handlePublish(e: FormEvent) {
    e.preventDefault();
    if (!name.trim() || !templateBody.trim()) return;
    setBusy(true);
    setNotice(null);
    setError(null);
    try {
      const created = await adminPromptsApi.publish({
        name: name.trim(),
        stage,
        description: description.trim(),
        templateBody,
      });
      setNotice(`已发布 Prompt 模板 ${created.name} v${created.version}（草稿态，可点击一键激活）。`);
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
    setNotice(null);
    setError(null);
    try {
      await adminPromptsApi.activate(row.id);
      setNotice(`已将 ${row.name} v${row.version} 切换为 ACTIVE，旧版本已归档。`);
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
        <button
          type="button"
          onClick={() => void loadAll()}
          className="rounded-md border border-border bg-card px-3 py-1.5 text-xs hover:bg-muted"
        >
          刷新
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

      <section className="grid gap-6 lg:grid-cols-12">
        <form
          onSubmit={handlePublish}
          className="space-y-3 rounded-xl border border-border bg-card p-4 lg:col-span-5"
        >
          <h2 className="text-sm font-semibold">发布新版本 Prompt 模板</h2>
          <div className="grid grid-cols-2 gap-2">
            <div className="space-y-1">
              <label className="text-xs text-muted-foreground">模板标识 (name)</label>
              <input
                required
                value={name}
                onChange={(e) => setName(e.target.value)}
                className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 font-mono text-xs"
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs text-muted-foreground">发布状态 (stage)</label>
              <select
                value={stage}
                onChange={(e) => setStage(e.target.value)}
                className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-xs"
              >
                <option value="DRAFT">DRAFT（存为草稿）</option>
                <option value="ACTIVE">ACTIVE（发布并立即激活）</option>
              </select>
            </div>
          </div>
          <div className="space-y-1">
            <label className="text-xs text-muted-foreground">版本说明</label>
            <input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="例如：加强中式民俗恐怖反套路约束"
              className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-xs"
            />
          </div>
          <div className="space-y-1">
            <label className="text-xs text-muted-foreground">模板正文</label>
            <textarea
              required
              rows={6}
              value={templateBody}
              onChange={(e) => setTemplateBody(e.target.value)}
              placeholder="输入 System Prompt 模板正文..."
              className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 font-mono text-xs"
            />
          </div>
          <button
            type="submit"
            disabled={busy}
            className="w-full rounded-md bg-blue-600 py-1.5 text-xs font-medium text-white hover:bg-blue-500 disabled:opacity-50"
          >
            发布新版本
          </button>
        </form>

        <div className="rounded-xl border border-border bg-card p-4 lg:col-span-7">
          <h2 className="mb-3 text-sm font-semibold">模板版本清单 ({prompts.length})</h2>
          {prompts.length === 0 ? (
            <p className="py-8 text-center text-xs text-muted-foreground">暂无 Prompt 模板版本</p>
          ) : (
            <div className="max-h-[420px] space-y-2 overflow-y-auto pr-1">
              {prompts.map((p) => (
                <div
                  key={p.id}
                  className="rounded-lg border border-border/80 bg-background/40 p-3 text-xs"
                >
                  <div className="flex items-center justify-between gap-2">
                    <div className="font-mono font-semibold">
                      {p.name} <span className="text-blue-400">v{p.version}</span> · {p.stage}
                    </div>
                    <div className="flex items-center gap-2">
                      <span
                        className={`rounded px-1.5 py-0.5 font-mono text-[10px] ${
                          p.status === "ACTIVE"
                            ? "bg-emerald-500/20 text-emerald-300"
                            : "bg-muted text-muted-foreground"
                        }`}
                      >
                        {p.status}
                      </span>
                      {p.status !== "ACTIVE" && (
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() => void handleActivate(p)}
                          className="rounded border border-blue-500/40 px-2 py-0.5 text-[11px] text-blue-300 hover:bg-blue-500/10"
                        >
                          激活此版本
                        </button>
                      )}
                    </div>
                  </div>
                  {p.description && <p className="mt-1 text-muted-foreground">{p.description}</p>}
                  <pre className="mt-2 max-h-24 overflow-x-auto whitespace-pre-wrap rounded bg-black/30 p-2 font-mono text-[10px] text-muted-foreground">
                    {p.templateBody}
                  </pre>
                </div>
              ))}
            </div>
          )}
        </div>
      </section>

      <section className="rounded-xl border border-border bg-card p-4">
        <h2 className="mb-3 text-sm font-semibold">管理员操作审计日志 ({audits.length})</h2>
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="border-b border-border text-[11px] text-muted-foreground">
              <tr>
                <th className="py-2">动作</th>
                <th className="py-2">操作人</th>
                <th className="py-2">目标</th>
                <th className="py-2">来源 IP</th>
                <th className="py-2">时间</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {audits.map((a) => (
                <tr key={a.id}>
                  <td className="py-2 font-mono text-[11px] text-blue-300">{a.action}</td>
                  <td className="py-2">{a.operatorName}</td>
                  <td className="py-2 font-mono text-[11px] text-muted-foreground">
                    {a.targetType}:{a.targetId}
                  </td>
                  <td className="py-2 font-mono text-[11px] text-muted-foreground">
                    {a.clientIp || "-"}
                  </td>
                  <td className="py-2 font-mono text-[10px] text-muted-foreground">
                    {a.createdAt ? new Date(a.createdAt).toLocaleString("zh-CN") : "-"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </AdminShell>
  );
}
