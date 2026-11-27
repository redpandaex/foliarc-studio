"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { Alert } from "@/components/ui/alert";
import { Badge, type UiStatus } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  ApiClientError,
  rippleApi,
  vipApi,
  type ApplyRipplePatchesResult,
  type BookTreeResponse,
  type ChapterVersion,
  type MyVipStatus,
  type RippleImpactItem,
  type RippleJobDetail,
  type RippleJobItem,
  type RipplePatchItem,
} from "@/lib/api";
import { formatWebError } from "@/lib/error-messages";

type RangeMode = "BIDIRECTIONAL" | "FORWARD" | "BACKWARD" | "CUSTOM_RANGE" | "FULL_BOOK";

const JOB_STATUS_LABELS: Record<string, { label: string; status: UiStatus }> = {
  ANALYZING: { label: "检测中", status: "info" },
  NO_CHANGE: { label: "无冲突", status: "success" },
  READY_TO_PATCH: { label: "已发现受影响章节", status: "warning" },
  BLAST_RADIUS_EXCEEDED: { label: "影响较多 · 需分批", status: "danger" },
  PATCHING: { label: "生成建议中", status: "info" },
  READY_FOR_REVIEW: { label: "待审阅", status: "ai" },
  PARTIALLY_APPLIED: { label: "已部分写入", status: "warning" },
  APPLIED: { label: "已完成", status: "success" },
  FAILED: { label: "检测失败", status: "danger" },
  CANCELLED: { label: "已取消", status: "neutral" },
};

const PATCH_STATUS_LABELS: Record<string, string> = {
  PENDING: "生成中",
  READY_FOR_REVIEW: "待审阅",
  APPLIED: "已写入",
  REJECTED: "已拒绝",
  VERSION_CONFLICT: "版本冲突",
  MANUAL_REVIEW_REQUIRED: "需手工校对",
};

interface RippleEditDrawerProps {
  open: boolean;
  bookId: string;
  sectionId: string;
  chapterNo: number;
  chapterTitle: string;
  currentContent: string;
  tree: BookTreeResponse | null;
  versions: ChapterVersion[];
  onClose: () => void;
  onAppliedSections: (sectionIds: string[]) => void;
}

export function RippleEditDrawer({
  open,
  bookId,
  sectionId,
  chapterNo,
  chapterTitle,
  currentContent,
  tree,
  versions,
  onClose,
  onAppliedSections,
}: RippleEditDrawerProps) {
  const [vip, setVip] = useState<MyVipStatus | null>(null);
  const [recentJobs, setRecentJobs] = useState<RippleJobItem[]>([]);
  const [activeJobId, setActiveJobId] = useState<string | null>(null);
  const [detail, setDetail] = useState<RippleJobDetail | null>(null);

  const [rangeMode, setRangeMode] = useState<RangeMode>("BIDIRECTIONAL");
  const [rangeStart, setRangeStart] = useState(String(Math.max(1, chapterNo - 20)));
  const [rangeEnd, setRangeEnd] = useState(String(chapterNo + 25));
  const [selectedVolumeIds, setSelectedVolumeIds] = useState<string[]>([]);
  const [oldVersionId, setOldVersionId] = useState("");
  const [changeNote, setChangeNote] = useState("");

  const [selectedImpactIds, setSelectedImpactIds] = useState<string[]>([]);
  const [selectedPatchIds, setSelectedPatchIds] = useState<string[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [applySummary, setApplySummary] = useState<ApplyRipplePatchesResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [quotaError, setQuotaError] = useState<string | null>(null);

  const volumes = useMemo(
    () => (tree?.volumes ?? []).filter((v) => v.id !== null),
    [tree],
  );

  useEffect(() => {
    if (!open) return;
    let active = true;
    void Promise.all([
      vipApi.me().catch(() => null),
      rippleApi.listJobs(bookId).catch(() => []),
    ]).then(([vipData, jobs]) => {
      if (!active) return;
      setVip(vipData);
      setRecentJobs(jobs);
    });
    return () => {
      active = false;
    };
  }, [open, bookId]);

  useEffect(() => {
    if (!open || !activeJobId) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout> | null = null;

    const loadDetail = async () => {
      try {
        const next = await rippleApi.getJob(bookId, activeJobId);
        if (!active) return;
        setDetail(next);
        setRecentJobs((prev) => {
          const rest = prev.filter((j) => j.id !== next.job.id);
          return [next.job, ...rest];
        });
        if (next.job.status === "ANALYZING" || next.job.status === "PATCHING") {
          timer = setTimeout(() => void loadDetail(), 2500);
        }
      } catch (err) {
        if (!active) return;
        setError(formatWebError(err, "加载修订进度失败，请稍后重试"));
      }
    };

    void loadDetail();
    return () => {
      active = false;
      if (timer) clearTimeout(timer);
    };
  }, [open, bookId, activeJobId]);

  if (!open) return null;

  const maxRange = vip?.entitlements.rippleMaxRangeChapters ?? 50;
  const maxBatch = detail?.job.maxPatchBatchLimit ?? vip?.entitlements.rippleMaxPatchChapters ?? 5;
  const allowFullBook = Boolean(vip?.entitlements.rippleAllowFullBook);

  function toggleImpact(id: string) {
    setSelectedImpactIds((prev) => {
      if (prev.includes(id)) return prev.filter((x) => x !== id);
      if (prev.length >= maxBatch) return prev;
      return [...prev, id];
    });
  }

  function togglePatch(id: string) {
    setSelectedPatchIds((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
    );
  }

  function toggleVolume(vid: string) {
    setSelectedVolumeIds((prev) =>
      prev.includes(vid) ? prev.filter((x) => x !== vid) : [...prev, vid],
    );
  }

  async function handleStartAnalyze() {
    setSubmitting(true);
    setError(null);
    setQuotaError(null);
    setApplySummary(null);
    setSelectedImpactIds([]);
    setSelectedPatchIds([]);
    try {
      const startNum = Number.parseInt(rangeStart, 10);
      const endNum = Number.parseInt(rangeEnd, 10);
      const created = await rippleApi.analyze(bookId, sectionId, {
        rangeMode,
        rangeStartChapter:
          rangeMode === "CUSTOM_RANGE" && selectedVolumeIds.length === 0 && !Number.isNaN(startNum)
            ? startNum
            : undefined,
        rangeEndChapter:
          rangeMode === "CUSTOM_RANGE" && selectedVolumeIds.length === 0 && !Number.isNaN(endNum)
            ? endNum
            : undefined,
        volumeIds:
          rangeMode === "CUSTOM_RANGE" && selectedVolumeIds.length > 0
            ? selectedVolumeIds
            : undefined,
        oldVersionId: oldVersionId || undefined,
        newContent: currentContent,
        changeNote: changeNote.trim() || undefined,
      });
      setActiveJobId(created.id);
      setRecentJobs((prev) => [created, ...prev.filter((j) => j.id !== created.id)]);
    } catch (err) {
      if (err instanceof ApiClientError && err.code === "VIP_QUOTA_EXCEEDED") {
        setQuotaError(formatWebError(err, "已超出当前套餐跨章修订上限"));
      } else {
        setError(formatWebError(err, "发起影响检测失败，请稍后重试"));
      }
    } finally {
      setSubmitting(false);
    }
  }

  async function handleGeneratePatches() {
    if (!detail || selectedImpactIds.length === 0) return;
    setSubmitting(true);
    setError(null);
    setQuotaError(null);
    setApplySummary(null);
    try {
      const next = await rippleApi.generatePatches(bookId, detail.job.id, selectedImpactIds);
      setDetail(next);
      setActiveJobId(next.job.id);
    } catch (err) {
      if (err instanceof ApiClientError && err.code === "VIP_QUOTA_EXCEEDED") {
        setQuotaError(formatWebError(err, "已超出单批修订上限"));
      } else {
        setError(formatWebError(err, "生成修改建议失败，请稍后重试"));
      }
    } finally {
      setSubmitting(false);
    }
  }

  async function handleApplyPatches() {
    if (!detail) return;
    const reviewable = detail.patches.filter((p) => p.status === "READY_FOR_REVIEW");
    const accepted = selectedPatchIds.filter((id) => reviewable.some((p) => p.id === id));
    const rejected = reviewable.filter((p) => !accepted.includes(p.id)).map((p) => p.id);
    if (accepted.length === 0 && rejected.length === 0) return;

    setSubmitting(true);
    setError(null);
    try {
      const result = await rippleApi.applyPatches(bookId, detail.job.id, {
        patchIds: accepted,
        rejectedPatchIds: rejected,
      });
      setApplySummary(result);
      setSelectedPatchIds([]);
      setSelectedImpactIds([]);
      const refreshed = await rippleApi.getJob(bookId, detail.job.id);
      setDetail(refreshed);
      if (result.appliedSectionIds.length > 0) {
        onAppliedSections(result.appliedSectionIds);
      }
    } catch (err) {
      setError(formatWebError(err, "写入修改失败，请稍后重试"));
    } finally {
      setSubmitting(false);
    }
  }

  async function handleCancelJob() {
    if (!detail) return;
    setSubmitting(true);
    setError(null);
    try {
      const cancelled = await rippleApi.cancelJob(bookId, detail.job.id);
      setDetail({ ...detail, job: cancelled });
    } catch (err) {
      setError(formatWebError(err, "取消任务失败，请稍后重试"));
    } finally {
      setSubmitting(false);
    }
  }

  const reviewablePatches: RipplePatchItem[] = (detail?.patches ?? []).filter(
    (p) => p.status === "READY_FOR_REVIEW",
  );
  const acceptedReviewCount = reviewablePatches.filter((p) =>
    selectedPatchIds.includes(p.id),
  ).length;
  const rejectedReviewCount = reviewablePatches.length - acceptedReviewCount;

  const applyButtonLabel = submitting
    ? "处理中…"
    : acceptedReviewCount > 0 && rejectedReviewCount > 0
      ? `写入 ${acceptedReviewCount} 项，拒绝 ${rejectedReviewCount} 项`
      : acceptedReviewCount > 0
        ? `写入 ${acceptedReviewCount} 项`
        : `拒绝全部 ${rejectedReviewCount} 项`;

  function renderImpactList(title: string, items: RippleImpactItem[]) {
    if (items.length === 0) return null;
    return (
      <div className="space-y-1.5">
        <div className="text-[11px] font-semibold text-foreground">
          {title} ({items.length})
        </div>
        <ul className="space-y-1.5">
          {items.map((imp) => {
            const isApplied = imp.patchStatus === "APPLIED";
            const checked = selectedImpactIds.includes(imp.id);
            const isStrong = imp.severity === "STRONG_CONFLICT";
            return (
              <li
                key={imp.id}
                className={`rounded-lg border p-2 text-xs ${
                  isApplied
                    ? "border-status-success-border bg-status-success-bg opacity-85"
                    : isStrong
                      ? "border-status-warning-border bg-status-warning-bg"
                      : "border-border bg-background"
                }`}
              >
                <div className="flex items-start justify-between gap-2">
                  <label className="flex min-w-0 flex-1 cursor-pointer items-start gap-2">
                    <input
                      type="checkbox"
                      disabled={isApplied}
                      checked={checked}
                      onChange={() => toggleImpact(imp.id)}
                      className="mt-0.5 accent-primary"
                    />
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span className="font-mono text-[11px] font-semibold">
                          第 {imp.chapterNo} 章
                          {imp.sectionNo > 1 ? `·第 ${imp.sectionNo} 节` : ""}
                        </span>
                        <span className="truncate font-medium">{imp.chapterTitle}</span>
                      </div>
                      <p className="mt-0.5 text-[11px] text-muted-foreground">
                        {imp.conflictReason}
                      </p>
                      {imp.matchedExcerpt && (
                        <p className="mt-1 border-l-2 border-border pl-2 text-[10px] italic text-muted-foreground">
                          「{imp.matchedExcerpt}」
                        </p>
                      )}
                    </div>
                  </label>
                  <Badge
                    status={isApplied ? "success" : isStrong ? "danger" : "neutral"}
                    className="shrink-0 text-[10px]"
                  >
                    {isApplied ? "已写入" : isStrong ? "强冲突" : "轻度提及"}
                  </Badge>
                </div>
              </li>
            );
          })}
        </ul>
      </div>
    );
  }

  return (
    <div
      className="fixed inset-0 z-50 flex justify-end bg-black/40 backdrop-blur-[1px]"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="跨章修订"
        className="flex h-full w-[600px] max-w-full flex-col border-l border-border bg-surface-elevated shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between border-b border-border px-5 py-3.5">
          <div className="min-w-0">
            <h2 className="text-sm font-semibold">跨章修订</h2>
            <p className="truncate text-[11px] text-muted-foreground tabular-nums">
              基准：第 {chapterNo} 章《{chapterTitle}》
              {vip && ` · 范围 ≤ ${maxRange} 章 · 单批 ≤ ${vip.entitlements.rippleMaxPatchChapters} 章`}
            </p>
          </div>
          <Button size="xs" variant="ghost" onClick={onClose}>
            关闭
          </Button>
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto p-5 text-xs">
          {quotaError && (
            <Alert
              status="vip"
              action={
                <Link href="/vip" className="shrink-0 font-semibold underline">
                  升级会员
                </Link>
              }
            >
              {quotaError}
            </Alert>
          )}
          {error && (
            <Alert status="danger" announce="assertive">
              {error}
            </Alert>
          )}

          {/* Step 1: Select Range */}
          <section className="space-y-3 rounded-xl border border-border bg-background p-4">
            <div className="flex items-center justify-between">
              <span className="font-semibold">1. 选择范围</span>
              {vip && vip.rippleDailyScanLimit > 0 && (
                <span className="font-mono text-[10px] text-muted-foreground tabular-nums">
                  今日 {vip.rippleScansUsedToday}/{vip.rippleDailyScanLimit} 次
                </span>
              )}
            </div>

            <div className="grid grid-cols-2 gap-1.5 sm:grid-cols-3">
              {(
                [
                  ["BIDIRECTIONAL", "前后双向"],
                  ["FORWARD", "仅后文"],
                  ["BACKWARD", "仅前文"],
                  ["CUSTOM_RANGE", "自选章节 / 分卷"],
                  ["FULL_BOOK", allowFullBook ? "全书" : "全书（工作室版）"],
                ] as [RangeMode, string][]
              ).map(([mode, label]) => (
                <button
                  key={mode}
                  type="button"
                  aria-pressed={rangeMode === mode}
                  onClick={() => setRangeMode(mode)}
                  className={`rounded-md border px-2 py-1.5 text-[11px] font-medium transition-colors ${
                    rangeMode === mode
                      ? "border-primary bg-primary/10 text-primary"
                      : "border-border text-muted-foreground hover:bg-muted"
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>

            {rangeMode === "CUSTOM_RANGE" && (
              <div className="space-y-2 rounded-lg bg-muted/30 p-2.5">
                <div className="grid grid-cols-2 gap-2">
                  <div className="space-y-1">
                    <label className="text-[10px] text-muted-foreground">起始章</label>
                    <input
                      type="number"
                      min={1}
                      value={rangeStart}
                      onChange={(e) => {
                        setSelectedVolumeIds([]);
                        setRangeStart(e.target.value);
                      }}
                      className="w-full rounded border border-input bg-background px-2 py-1 text-xs"
                    />
                  </div>
                  <div className="space-y-1">
                    <label className="text-[10px] text-muted-foreground">结束章</label>
                    <input
                      type="number"
                      min={1}
                      value={rangeEnd}
                      onChange={(e) => {
                        setSelectedVolumeIds([]);
                        setRangeEnd(e.target.value);
                      }}
                      className="w-full rounded border border-input bg-background px-2 py-1 text-xs"
                    />
                  </div>
                </div>
                {volumes.length > 0 && (
                  <div className="space-y-1">
                    <div className="text-[10px] text-muted-foreground">按卷选择</div>
                    <div className="flex flex-wrap gap-1.5">
                      {volumes.map((v) => {
                        const vid = v.id ?? "";
                        const active = selectedVolumeIds.includes(vid);
                        return (
                          <button
                            key={vid}
                            type="button"
                            aria-pressed={active}
                            onClick={() => toggleVolume(vid)}
                            className={`rounded-full px-2 py-0.5 text-[10px] ${
                              active
                                ? "bg-primary text-primary-foreground"
                                : "border border-border bg-background text-muted-foreground"
                            }`}
                          >
                            {v.title}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                )}
              </div>
            )}

            <div className="grid gap-2 sm:grid-cols-2">
              <div className="space-y-1">
                <label className="text-[10px] text-muted-foreground">改动简述（选填）</label>
                <input
                  value={changeNote}
                  onChange={(e) => setChangeNote(e.target.value)}
                  placeholder="二长老改为重伤逃走"
                  className="w-full rounded border border-input bg-background px-2 py-1.5 text-xs"
                />
              </div>
              <div className="space-y-1">
                <label className="text-[10px] text-muted-foreground">对比基线（选填）</label>
                <select
                  value={oldVersionId}
                  onChange={(e) => setOldVersionId(e.target.value)}
                  className="w-full rounded border border-input bg-background px-2 py-1.5 text-xs"
                >
                  <option value="">最近一次保存版本</option>
                  {versions.map((v) => (
                    <option key={v.id} value={v.id}>
                      v{v.versionNo} · {v.changeSummary || "历史快照"}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <Button
              type="button"
              size="sm"
              className="w-full"
              loading={submitting}
              onClick={() => void handleStartAnalyze()}
            >
              {submitting ? "检测中…" : "检测影响"}
            </Button>
          </section>

          {recentJobs.length > 0 && (
            <div className="flex items-center gap-2 overflow-x-auto pb-1">
              <span className="shrink-0 text-[10px] text-muted-foreground">最近记录</span>
              {recentJobs.slice(0, 5).map((j) => (
                <button
                  key={j.id}
                  type="button"
                  onClick={() => {
                    setActiveJobId(j.id);
                    setApplySummary(null);
                  }}
                  className={`shrink-0 rounded-full border px-2.5 py-0.5 text-[10px] ${
                    activeJobId === j.id
                      ? "border-primary bg-primary/10 text-primary"
                      : "border-border text-muted-foreground hover:bg-muted"
                  }`}
                >
                  第{j.sourceChapterNo}章（{j.rangeStartChapter}–{j.rangeEndChapter}章）
                </button>
              ))}
            </div>
          )}

          {/* Step 2: Impact Overview */}
          {detail && (
            <section className="space-y-3 rounded-xl border border-border bg-background p-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <span className="font-semibold">2. 查看影响</span>
                  <Badge
                    status={JOB_STATUS_LABELS[detail.job.status]?.status ?? "neutral"}
                    className="text-[10px]"
                  >
                    {JOB_STATUS_LABELS[detail.job.status]?.label ?? detail.job.status}
                  </Badge>
                </div>
                {(detail.job.status === "ANALYZING" || detail.job.status === "PATCHING") && (
                  <Button
                    size="xs"
                    variant="outline"
                    disabled={submitting}
                    onClick={() => void handleCancelJob()}
                  >
                    取消
                  </Button>
                )}
              </div>

              <div className="flex flex-wrap gap-3 font-mono text-[10px] text-muted-foreground tabular-nums">
                <span>第 {detail.job.rangeStartChapter}–{detail.job.rangeEndChapter} 章</span>
                <span>前文冲突 {detail.job.backwardConflictCount}</span>
                <span>后文冲突 {detail.job.forwardConflictCount}</span>
                <span>轻度提及 {detail.job.minorMentionCount}</span>
              </div>

              {detail.job.changeSummary && (
                <p className="rounded bg-muted/40 px-2.5 py-1.5 text-[11px] text-foreground">
                  {detail.job.changeSummary}
                </p>
              )}

              {detail.factDeltas.length > 0 && (
                <div className="flex flex-wrap gap-1.5">
                  {detail.factDeltas.map((d, idx) => (
                    <span
                      key={`${d.entity}-${idx}`}
                      className="rounded border border-border bg-muted/30 px-2 py-0.5 text-[10px]"
                    >
                      <strong>{d.entity}</strong>：{d.oldFact || "原设定"} → {d.newFact}
                    </span>
                  ))}
                </div>
              )}

              {detail.job.status === "BLAST_RADIUS_EXCEEDED" && (
                <Alert status="danger" className="py-2 text-[11px]">
                  单批上限 {detail.job.maxPatchBatchLimit} 章，请分批勾选处理。
                </Alert>
              )}

              {renderImpactList("前文影响", detail.backwardImpacts)}
              {renderImpactList("后文影响", detail.forwardImpacts)}

              {(detail.backwardImpacts.length > 0 || detail.forwardImpacts.length > 0) && (
                <div className="flex items-center justify-between gap-2 border-t border-border pt-3">
                  <span className="text-[11px] text-muted-foreground tabular-nums">
                    已选 {selectedImpactIds.length}/{detail.job.maxPatchBatchLimit} 项
                    {detail.job.estimatedCostCredits > 0 &&
                      ` · 约 ${detail.job.estimatedCostCredits.toFixed(2)} 积分`}
                  </span>
                  <Button
                    size="xs"
                    disabled={submitting || selectedImpactIds.length === 0}
                    onClick={() => void handleGeneratePatches()}
                  >
                    {submitting ? "生成中…" : "生成修改建议"}
                  </Button>
                </div>
              )}
            </section>
          )}

          {/* Step 3: Review & Apply */}
          {detail && detail.patches.length > 0 && (
            <section className="space-y-3 rounded-xl border border-border bg-background p-4">
              <div className="flex items-center justify-between">
                <span className="font-semibold">3. 审阅修改</span>
                {reviewablePatches.length > 0 && (
                  <button
                    type="button"
                    onClick={() =>
                      setSelectedPatchIds(
                        selectedPatchIds.length === reviewablePatches.length
                          ? []
                          : reviewablePatches.map((p) => p.id),
                      )
                    }
                    className="text-[11px] text-primary hover:underline"
                  >
                    {selectedPatchIds.length === reviewablePatches.length ? "取消全选" : "全选待审"}
                  </button>
                )}
              </div>

              {applySummary && (
                <div className="flex flex-wrap gap-2 rounded-lg border border-border bg-muted/40 px-3 py-2 text-[11px]">
                  <Badge status="success">已写入 {applySummary.appliedCount} 项</Badge>
                  {applySummary.conflictCount > 0 && (
                    <Badge status="warning">冲突跳过 {applySummary.conflictCount} 项</Badge>
                  )}
                  {applySummary.manualReviewCount > 0 && (
                    <Badge status="danger">需手工校对 {applySummary.manualReviewCount} 项</Badge>
                  )}
                </div>
              )}

              <ul className="space-y-2.5">
                {detail.patches.map((patch) => {
                  const canReview = patch.status === "READY_FOR_REVIEW";
                  const checked = selectedPatchIds.includes(patch.id);
                  return (
                    <li key={patch.id} className="space-y-2 rounded-lg border border-border p-3">
                      <div className="flex items-center justify-between gap-2">
                        <label className="flex items-center gap-2 font-medium">
                          {canReview && (
                            <input
                              type="checkbox"
                              checked={checked}
                              onChange={() => togglePatch(patch.id)}
                              className="accent-primary"
                            />
                          )}
                          <span>
                            第 {patch.chapterNo} 章《{patch.chapterTitle}》
                            <span className="ml-1.5 text-[10px] text-muted-foreground">
                              {patch.direction === "BACKWARD" ? "前文" : "后文"} · 第 {patch.batchNo} 批
                            </span>
                          </span>
                        </label>
                        <Badge
                          status={
                            patch.status === "APPLIED"
                              ? "success"
                              : patch.status === "VERSION_CONFLICT" ||
                                  patch.status === "MANUAL_REVIEW_REQUIRED"
                                ? "danger"
                                : patch.status === "READY_FOR_REVIEW"
                                  ? "ai"
                                  : "neutral"
                          }
                          className="text-[10px]"
                        >
                          {PATCH_STATUS_LABELS[patch.status] ?? patch.status}
                        </Badge>
                      </div>

                      {patch.hunks.length === 0 ? (
                        <p className="text-[11px] text-muted-foreground">
                          需打开该章手工校对。
                        </p>
                      ) : (
                        <div className="space-y-2">
                          {patch.hunks.map((h, i) => (
                            <div
                              key={i}
                              className="overflow-hidden rounded border border-border/70 text-[11px]"
                            >
                              {h.reason && (
                                <div className="border-b border-border/60 bg-muted/40 px-2 py-1 text-[10px] text-muted-foreground">
                                  {h.reason}
                                </div>
                              )}
                              <div className="whitespace-pre-wrap bg-status-danger-bg px-2.5 py-1.5 text-status-danger line-through">
                                {h.originalParagraph}
                              </div>
                              <div className="whitespace-pre-wrap bg-status-success-bg px-2.5 py-1.5 text-status-success">
                                {h.patchedParagraph}
                              </div>
                            </div>
                          ))}
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>

              {reviewablePatches.length > 0 && (
                <Button
                  type="button"
                  size="sm"
                  className="w-full"
                  loading={submitting}
                  onClick={() => void handleApplyPatches()}
                >
                  {applyButtonLabel}
                </Button>
              )}
            </section>
          )}
        </div>
      </div>
    </div>
  );
}
