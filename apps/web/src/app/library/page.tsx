"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { useAuth } from "@/components/auth-provider";
import { Alert } from "@/components/ui/alert";
import { Badge, type UiStatus } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { useConfirm } from "@/components/ui/confirm-dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Select } from "@/components/ui/select";
import { ThemeToggle } from "@/components/ui/theme-toggle";
import { useToast } from "@/components/ui/toast";
import {
  ApiClientError,
  libraryApi,
  vipApi,
  type KnowledgeDocType,
  type LibraryDocumentItem,
  type MyVipStatus,
} from "@/lib/api";

const DOC_TYPE_LABELS: Record<string, string> = {
  WORLDBOOK: "世界观设定",
  STYLE_REF: "文风参考",
  OUTLINE: "大纲笔记",
  CHAPTER: "历史文稿",
};

const ACCESS_META: Record<string, { text: string; status: UiStatus }> = {
  USER: { text: "个人资料", status: "info" },
  ASSIGNED: { text: "专属资料", status: "ai" },
  GLOBAL: { text: "公共资料", status: "success" },
};

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

function statusDisplay(status: LibraryDocumentItem["status"]): { text: string; tone: UiStatus } {
  if (status === "READY") return { text: "已就绪", tone: "success" };
  if (status === "FAILED") return { text: "解析失败", tone: "danger" };
  return { text: "处理中...", tone: "warning" };
}

export default function PersonalLibraryPage() {
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();
  const confirm = useConfirm();
  const toast = useToast();
  const [docs, setDocs] = useState<LibraryDocumentItem[]>([]);
  const [vipStatus, setVipStatus] = useState<MyVipStatus | null>(null);
  const [quotaHint, setQuotaHint] = useState<{
    message: string;
    recommendedPlanName?: string;
    recommendedLimit?: string;
  } | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [docType, setDocType] = useState<KnowledgeDocType>("WORLDBOOK");
  const [title, setTitle] = useState("");
  const [genreTags, setGenreTags] = useState("");
  const [uploading, setUploading] = useState(false);

  const loadDocs = useCallback(async () => {
    try {
      const [list, myVip] = await Promise.all([
        libraryApi.list(),
        vipApi.me().catch(() => null),
      ]);
      setDocs(list);
      if (myVip) setVipStatus(myVip);
      setError(null);
    } catch (err) {
      setError(err instanceof ApiClientError ? err.message : "加载资料库失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (authLoading) return;
    if (!user) {
      router.replace("/login");
      return;
    }
    let cancelled = false;
    Promise.all([libraryApi.list(), vipApi.me().catch(() => null)])
      .then(([list, myVip]) => {
        if (!cancelled) {
          setDocs(list);
          if (myVip) setVipStatus(myVip);
          setError(null);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof ApiClientError ? err.message : "加载资料库失败");
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [authLoading, user, router]);

  const hasInFlight = docs.some((d) =>
    ["QUEUED", "PARSING", "CHUNKING", "EMBEDDING"].includes(d.status),
  );
  useEffect(() => {
    if (!hasInFlight) return;
    const timer = setInterval(() => {
      void loadDocs();
    }, 2500);
    return () => clearInterval(timer);
  }, [hasInFlight, loadDocs]);

  async function handleUpload(e: FormEvent) {
    e.preventDefault();
    if (!selectedFile) return;
    setUploading(true);
    setError(null);
    setQuotaHint(null);
    try {
      await libraryApi.upload({
        file: selectedFile,
        docType,
        title: title.trim() || undefined,
        genreTags: genreTags.trim() || undefined,
      });
      setSelectedFile(null);
      setTitle("");
      setGenreTags("");
      toast.success("资料已上传，系统正在后台整理，就绪后所有作品均可按需启用。");
      await loadDocs();
    } catch (err) {
      if (err instanceof ApiClientError && err.code === "VIP_QUOTA_EXCEEDED") {
        setQuotaHint({
          message: err.message,
          recommendedPlanName: err.details.recommendedPlanName,
          recommendedLimit: err.details.recommendedLimit,
        });
      } else {
        toast.error(err instanceof ApiClientError ? err.message : "上传资料失败");
      }
    } finally {
      setUploading(false);
    }
  }

  async function handleDelete(doc: LibraryDocumentItem) {
    const result = await confirm({
      title: `移除个人资料「${doc.title}」？`,
      description: `确认从个人资料库移除「${doc.title}」？移除后对应向量索引也将同步清理。`,
      confirmLabel: "确认移除",
      variant: "destructive",
      onConfirm: async () => {
        await libraryApi.remove(doc.id);
        setDocs((prev) => prev.filter((item) => item.id !== doc.id));
      },
    });
    if (result === "succeeded") {
      toast.success("已删除个人资料。");
    }
  }

  const personalDocs = docs.filter((d) => d.accessType === "USER");
  const platformDocs = docs.filter((d) => d.accessType !== "USER");

  if (authLoading || (!user && loading)) {
    return (
      <main className="mx-auto flex max-w-5xl flex-1 items-center justify-center px-6 py-16 text-sm text-muted-foreground">
        正在加载我的资料库...
      </main>
    );
  }

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="border-b border-border bg-card">
        <div className="mx-auto flex h-14 max-w-5xl items-center justify-between px-6">
          <div className="flex items-center gap-3">
            <Link href="/books" className="text-xs font-medium text-muted-foreground hover:text-foreground">
              ← 返回书架
            </Link>
            <span className="text-muted-foreground">/</span>
            <h1 className="text-sm font-semibold">我的资料库</h1>
          </div>
          <div className="flex items-center gap-2">
            <ThemeToggle />
            <Link href="/books" className={buttonVariants({ variant: "outline", size: "sm" })}>
              作品列表
            </Link>
          </div>
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-6 px-6 py-8">
        <div>
          <h2 className="text-xl font-bold tracking-tight">个人参考资料</h2>
          <p className="mt-1 text-xs text-muted-foreground">
            上传自己的设定文档、历史考据或文风参考（支持 PDF / Word / Markdown / TXT，单文件 ≤ 10MB）。
            进入任意作品的「故事设定 → 故事档案」即可自由开关本书要参考哪些资料；当设定冲突时，始终以本书原创设定为准。
          </p>
        </div>

        {quotaHint && (
          <Alert
            status="vip"
            action={
              <Link href="/vip" className={buttonVariants({ size: "xs" })}>
                升级{quotaHint.recommendedPlanName ? `至 ${quotaHint.recommendedPlanName}` : "会员"} →
              </Link>
            }
          >
            {quotaHint.message}
          </Alert>
        )}
        {error && (
          <Alert status="danger" announce="assertive">
            {error}
          </Alert>
        )}

        <Card className="p-5">
          <form onSubmit={handleUpload} className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5 sm:col-span-2">
              <label className="text-xs font-medium text-muted-foreground">选择文件 *</label>
              <Input
                type="file"
                accept=".pdf,.docx,.md,.txt"
                onChange={(e) => setSelectedFile(e.target.files?.[0] ?? null)}
              />
            </div>
            <Select
              id="library-doc-type"
              label="资料用途"
              value={docType}
              options={[
                { value: "WORLDBOOK", label: "世界观设定" },
                { value: "STYLE_REF", label: "文风参考" },
                { value: "OUTLINE", label: "大纲笔记" },
              ]}
              onValueChange={(v) => {
                if (v) setDocType(v as KnowledgeDocType);
              }}
            />
            <div className="space-y-1">
              <label className="text-xs font-medium text-muted-foreground">资料标题（可选）</label>
              <Input
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="默认使用文件名"
              />
            </div>
            <div className="space-y-1.5 sm:col-span-2">
              <label className="text-xs font-medium text-muted-foreground">
                适用题材标签（可选，逗号分隔）
              </label>
              <div className="flex gap-2">
                <Input
                  value={genreTags}
                  onChange={(e) => setGenreTags(e.target.value)}
                  placeholder="例如：东方玄幻, 武侠（留空表示所有作品通用）"
                  className="min-w-0 flex-1"
                />
                <Button type="submit" size="sm" disabled={!selectedFile} loading={uploading}>
                  {uploading ? "上传中..." : "上传到个人资料库"}
                </Button>
              </div>
            </div>
          </form>
        </Card>

        <section className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 className="text-sm font-semibold">我上传的资料 ({personalDocs.length})</h3>
            <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
              {vipStatus ? (
                <>
                  <span>
                    {vipStatus.displayName}：{vipStatus.libraryUsage.usedDocs}/{vipStatus.libraryUsage.maxDocs} 份 ·{" "}
                    {formatFileSize(vipStatus.libraryUsage.usedBytes)} /{" "}
                    {formatFileSize(vipStatus.libraryUsage.maxTotalBytes)}
                  </span>
                  <Link href="/vip" className="text-status-vip underline hover:opacity-80">
                    扩容
                  </Link>
                </>
              ) : (
                <span>相同文件自动去重</span>
              )}
            </div>
          </div>
          {loading ? (
            <p className="py-6 text-center text-xs text-muted-foreground">加载中...</p>
          ) : personalDocs.length === 0 ? (
            <EmptyState
              title="还没有上传个人资料"
              description="上传后即可在你名下的多部作品间复用，并支持按本书随时开关启用。"
            />
          ) : (
            <ul className="space-y-2">
              {personalDocs.map((doc) => {
                const st = statusDisplay(doc.status);
                return (
                  <li key={doc.id}>
                    <Card className="flex items-center justify-between gap-4 p-4 text-xs">
                      <div className="min-w-0 space-y-1">
                        <div className="flex items-center gap-2">
                          <span className="truncate font-semibold">{doc.title}</span>
                          <Badge status={st.tone}>{st.text}</Badge>
                        </div>
                        <div className="flex flex-wrap items-center gap-2 text-[11px] text-muted-foreground">
                          <span>{DOC_TYPE_LABELS[doc.docType] ?? doc.docType}</span>
                          <span>·</span>
                          <span>{formatFileSize(doc.fileSize)}</span>
                          {doc.genreTags && (
                            <>
                              <span>·</span>
                              <span>标签：{doc.genreTags}</span>
                            </>
                          )}
                        </div>
                        {doc.errorMessage && (
                          <p className="text-[11px] text-status-danger">{doc.errorMessage}</p>
                        )}
                      </div>
                      <Button size="xs" variant="destructive" onClick={() => void handleDelete(doc)}>
                        删除
                      </Button>
                    </Card>
                  </li>
                );
              })}
            </ul>
          )}
        </section>

        {platformDocs.length > 0 && (
          <section className="space-y-3">
            <h3 className="text-sm font-semibold">
              平台与编辑部提供的参考资料 ({platformDocs.length})
            </h3>
            <ul className="space-y-2">
              {platformDocs.map((doc) => {
                const acc = ACCESS_META[doc.accessType] ?? ACCESS_META.GLOBAL;
                const st = statusDisplay(doc.status);
                return (
                  <li key={doc.id}>
                    <Card className="flex items-center justify-between gap-4 p-4 text-xs">
                      <div className="min-w-0 space-y-1">
                        <div className="flex items-center gap-2">
                          <span className="truncate font-semibold">{doc.title}</span>
                          <Badge status={acc.status}>{acc.text}</Badge>
                          <Badge status={st.tone}>{st.text}</Badge>
                        </div>
                        <div className="flex flex-wrap items-center gap-2 text-[11px] text-muted-foreground">
                          <span>{DOC_TYPE_LABELS[doc.docType] ?? doc.docType}</span>
                          {doc.genreTags && (
                            <>
                              <span>·</span>
                              <span>适用题材：{doc.genreTags}</span>
                            </>
                          )}
                          <span>·</span>
                          <span>只读（可在单部作品中开关启用）</span>
                        </div>
                      </div>
                    </Card>
                  </li>
                );
              })}
            </ul>
          </section>
        )}
      </main>
    </div>
  );
}
