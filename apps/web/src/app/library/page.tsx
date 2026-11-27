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
import { formatDocFailureReason, formatWebError } from "@/lib/error-messages";

const DOC_TYPE_LABELS: Record<string, string> = {
  WORLDBOOK: "世界观设定",
  STYLE_REF: "文风参考",
  OUTLINE: "大纲笔记",
  CHAPTER: "历史文稿",
};

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

function statusDisplay(doc: LibraryDocumentItem): { text: string; tone: UiStatus } {
  if (doc.status === "READY") return { text: "已就绪", tone: "success" };
  if (doc.status === "FAILED") return { text: "解析失败", tone: "danger" };
  if (doc.progress > 0 && doc.progress < 100) {
    return { text: `处理中 ${doc.progress}%`, tone: "warning" };
  }
  return { text: "处理中", tone: "warning" };
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
      setError(formatWebError(err, "加载资料库失败，请稍后重试"));
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
    let active = true;
    void Promise.all([libraryApi.list(), vipApi.me().catch(() => null)])
      .then(([list, myVip]) => {
        if (!active) return;
        setDocs(list);
        if (myVip) setVipStatus(myVip);
        setError(null);
      })
      .catch((err: unknown) => {
        if (!active) return;
        setError(formatWebError(err, "加载资料库失败，请稍后重试"));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
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
      toast.success("已上传，正在解析");
      await loadDocs();
    } catch (err) {
      if (err instanceof ApiClientError && err.code === "VIP_QUOTA_EXCEEDED") {
        setQuotaHint({
          message: formatWebError(err, "个人资料库已达上限，升级会员可扩容", {
            quotaHint: "个人资料库已达上限，升级会员可扩容",
          }),
          recommendedPlanName: err.details.recommendedPlanName,
        });
      } else {
        toast.error(formatWebError(err, "上传资料失败，请稍后重试", { action: "upload" }));
      }
    } finally {
      setUploading(false);
    }
  }

  async function handleDelete(doc: LibraryDocumentItem) {
    const result = await confirm({
      title: `移除「${doc.title}」？`,
      description: `移除后，名下作品的后续创作将不再引用这份资料。`,
      confirmLabel: "移除",
      variant: "destructive",
      onConfirm: async () => {
        await libraryApi.remove(doc.id);
        setDocs((prev) => prev.filter((item) => item.id !== doc.id));
      },
    });
    if (result === "succeeded") {
      toast.success(`已移除「${doc.title}」`);
    }
  }

  const personalDocs = docs.filter((d) => d.accessType === "USER");
  const assignedDocs = docs.filter((d) => d.accessType === "ASSIGNED");
  const globalDocs = docs.filter((d) => d.accessType !== "USER" && d.accessType !== "ASSIGNED");

  if (authLoading || (!user && loading)) {
    return (
      <main className="mx-auto flex max-w-5xl flex-1 items-center justify-center px-6 py-16 text-xs text-muted-foreground">
        加载中…
      </main>
    );
  }

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="border-b border-border bg-card">
        <div className="mx-auto flex h-14 max-w-5xl items-center justify-between px-6">
          <div className="flex items-center gap-3">
            <Link
              href="/books"
              className="text-xs font-medium text-muted-foreground hover:text-foreground"
            >
              书架
            </Link>
            <span className="text-muted-foreground">/</span>
            <h1 className="text-sm font-semibold">资料库</h1>
          </div>
          <div className="flex items-center gap-2">
            <ThemeToggle />
            <Link href="/vip" className={buttonVariants({ variant: "outline", size: "sm" })}>
              会员
            </Link>
          </div>
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-6 px-6 py-8">
        {quotaHint && (
          <Alert
            status="vip"
            action={
              <Link href="/vip" className={buttonVariants({ size: "xs" })}>
                升级{quotaHint.recommendedPlanName ? `至 ${quotaHint.recommendedPlanName}` : "会员"}
              </Link>
            }
          >
            {quotaHint.message}
          </Alert>
        )}

        {error && (
          <Alert
            status="danger"
            announce="assertive"
            action={
              <Button type="button" variant="outline" size="sm" onClick={() => void loadDocs()}>
                重新加载
              </Button>
            }
          >
            {error}
          </Alert>
        )}

        <Card className="p-5">
          <form onSubmit={handleUpload} className="grid gap-3.5 sm:grid-cols-2">
            <div className="space-y-1.5 sm:col-span-2">
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <label htmlFor="library-file" className="font-medium">
                  上传资料
                </label>
                <span>PDF / DOCX / MD / TXT · 单文件 ≤ 10 MB</span>
              </div>
              <Input
                id="library-file"
                type="file"
                accept=".pdf,.docx,.md,.txt"
                onChange={(e) => setSelectedFile(e.target.files?.[0] ?? null)}
              />
            </div>

            <Select
              id="library-doc-type"
              label="用途"
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
              <label htmlFor="library-title" className="text-xs font-medium text-muted-foreground">
                标题（选填）
              </label>
              <Input
                id="library-title"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="默认使用文件名"
              />
            </div>

            <div className="space-y-1.5 sm:col-span-2">
              <label htmlFor="library-tags" className="text-xs font-medium text-muted-foreground">
                题材标签（选填）
              </label>
              <div className="flex gap-2">
                <Input
                  id="library-tags"
                  value={genreTags}
                  onChange={(e) => setGenreTags(e.target.value)}
                  placeholder="东方玄幻、武侠"
                  className="min-w-0 flex-1"
                />
                <Button type="submit" size="sm" disabled={!selectedFile} loading={uploading}>
                  {uploading ? "上传中…" : "上传"}
                </Button>
              </div>
            </div>
          </form>
        </Card>

        {/* Group 1: Personal Docs */}
        <section className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 className="text-sm font-semibold">个人资料 ({personalDocs.length})</h2>
            {vipStatus && (
              <div className="flex items-center gap-2 text-xs text-muted-foreground tabular-nums">
                <span>
                  {vipStatus.libraryUsage.usedDocs} / {vipStatus.libraryUsage.maxDocs} 份 ·{" "}
                  {formatFileSize(vipStatus.libraryUsage.usedBytes)} /{" "}
                  {formatFileSize(vipStatus.libraryUsage.maxTotalBytes)}
                </span>
                <Link href="/vip" className="font-medium text-status-vip hover:underline">
                  扩容
                </Link>
              </div>
            )}
          </div>
          {loading ? (
            <p className="py-6 text-center text-xs text-muted-foreground">加载中…</p>
          ) : personalDocs.length === 0 ? (
            <EmptyState title="暂无个人资料" />
          ) : (
            <ul className="space-y-2">
              {personalDocs.map((doc) => {
                const st = statusDisplay(doc);
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
                          <span className="tabular-nums">{formatFileSize(doc.fileSize)}</span>
                          {doc.genreTags && (
                            <>
                              <span>·</span>
                              <span>{doc.genreTags}</span>
                            </>
                          )}
                        </div>
                        {doc.status === "FAILED" && (
                          <p className="text-[11px] text-status-danger">
                            {formatDocFailureReason(doc.errorMessage)}
                          </p>
                        )}
                      </div>
                      <Button size="xs" variant="destructive" onClick={() => void handleDelete(doc)}>
                        移除
                      </Button>
                    </Card>
                  </li>
                );
              })}
            </ul>
          )}
        </section>

        {/* Group 2: Assigned Docs */}
        {assignedDocs.length > 0 && (
          <section className="space-y-3">
            <h2 className="text-sm font-semibold">专属资料 ({assignedDocs.length})</h2>
            <ul className="space-y-2">
              {assignedDocs.map((doc) => {
                const st = statusDisplay(doc);
                return (
                  <li key={doc.id}>
                    <Card className="flex items-center justify-between gap-4 p-4 text-xs">
                      <div className="min-w-0 space-y-1">
                        <div className="flex items-center gap-2">
                          <span className="truncate font-semibold">{doc.title}</span>
                          <Badge status={st.tone}>{st.text}</Badge>
                          <Badge status="neutral">只读</Badge>
                        </div>
                        <div className="flex flex-wrap items-center gap-2 text-[11px] text-muted-foreground">
                          <span>{DOC_TYPE_LABELS[doc.docType] ?? doc.docType}</span>
                          {doc.genreTags && (
                            <>
                              <span>·</span>
                              <span>{doc.genreTags}</span>
                            </>
                          )}
                        </div>
                      </div>
                    </Card>
                  </li>
                );
              })}
            </ul>
          </section>
        )}

        {/* Group 3: Global Docs */}
        {globalDocs.length > 0 && (
          <section className="space-y-3">
            <h2 className="text-sm font-semibold">公共资料 ({globalDocs.length})</h2>
            <ul className="space-y-2">
              {globalDocs.map((doc) => {
                const st = statusDisplay(doc);
                return (
                  <li key={doc.id}>
                    <Card className="flex items-center justify-between gap-4 p-4 text-xs">
                      <div className="min-w-0 space-y-1">
                        <div className="flex items-center gap-2">
                          <span className="truncate font-semibold">{doc.title}</span>
                          <Badge status={st.tone}>{st.text}</Badge>
                          <Badge status="neutral">只读</Badge>
                        </div>
                        <div className="flex flex-wrap items-center gap-2 text-[11px] text-muted-foreground">
                          <span>{DOC_TYPE_LABELS[doc.docType] ?? doc.docType}</span>
                          {doc.genreTags && (
                            <>
                              <span>·</span>
                              <span>{doc.genreTags}</span>
                            </>
                          )}
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
