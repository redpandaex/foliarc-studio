"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
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

type RangeMode = "BIDIRECTIONAL" | "FORWARD" | "BACKWARD" | "CUSTOM_RANGE" | "FULL_BOOK";

const JOB_STATUS_LABELS: Record<string, { label: string; className: string }> = {
  ANALYZING: { label: "正在扫描影响范围...", className: "bg-sky-500/15 text-sky-700 dark:text-sky-400" },
  NO_CHANGE: { label: "无实质设定冲突", className: "bg-emerald-500/15 text-emerald-700 dark:text-emerald-400" },
  READY_TO_PATCH: { label: "已定位受影响章节", className: "bg-amber-500/15 text-amber-700 dark:text-amber-400" },
  BLAST_RADIUS_EXCEEDED: { label: "冲突较多 · 建议分批修补", className: "bg-rose-500/15 text-rose-700 dark:text-rose-400" },
  PATCHING: { label: "正在生成局部修订建议...", className: "bg-sky-500/15 text-sky-700 dark:text-sky-400" },
  READY_FOR_REVIEW: { label: "局部修改建议待审阅", className: "bg-indigo-500/15 text-indigo-700 dark:text-indigo-400" },
  PARTIALLY_APPLIED: { label: "已部分采纳 · 可继续修补", className: "bg-amber-500/15 text-amber-700 dark:text-amber-400" },
  APPLIED: { label: "全部修改已同步完成", className: "bg-emerald-500/15 text-emerald-700 dark:text-emerald-400" },
  FAILED: { label: "扫描失败", className: "bg-destructive/15 text-destructive" },
  CANCELLED: { label: "已取消", className: "bg-muted text-muted-foreground" },
};

const PATCH_STATUS_LABELS: Record<string, string> = {
  PENDING: "生成中",
  READY_FOR_REVIEW: "待审阅",
  APPLIED: "已采纳写回",
  REJECTED: "已跳过",
  VERSION_CONFLICT: "版本冲突（正文已被修改）",
  MANUAL_REVIEW_REQUIRED: "需手动校对",
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

  // Poll active job while ANALYZING or PATCHING
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
        setError(err instanceof Error ? err.message : "查询蝴蝶效应进度失败");
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
        setQuotaError(err.message);
      } else {
        setError(err instanceof Error ? err.message : "发起蝴蝶效应检测失败");
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
        setQuotaError(err.message);
      } else {
        setError(err instanceof Error ? err.message : "生成局部修改建议失败");
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
      setError(err instanceof Error ? err.message : "采纳修改建议失败");
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
      setError(err instanceof Error ? err.message : "取消任务失败");
    } finally {
      setSubmitting(false);
    }
  }

  const reviewablePatches: RipplePatchItem[] = (detail?.patches ?? []).filter(
    (p) => p.status === "READY_FOR_REVIEW",
  );

  function renderImpactList(title: string, items: RippleImpactItem[]) {
    if (items.length === 0) return null;
    return (
      <div className="space-y-1.5">
        <div className="text-[11px] font-semibold text-foreground">
          {title}（{items.length}）
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
                    ? "border-emerald-500/30 bg-emerald-500/5 opacity-80"
                    : isStrong
                      ? "border-amber-500/40 bg-amber-500/5"
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
                      className="mt-0.5"
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
                  <span
                    className={`shrink-0 rounded px-1.5 py-0.5 text-[10px] ${
                      isApplied
                        ? "bg-emerald-500/15 text-emerald-700 dark:text-emerald-400"
                        : isStrong
                          ? "bg-rose-500/15 text-rose-700 dark:text-rose-400"
                          : "bg-muted text-muted-foreground"
                    }`}
                  >
                    {isApplied ? "已同步" : isStrong ? "强冲突" : "轻度提及"}
                  </span>
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
      className="fixed inset-0 z-50 flex justify-end bg-black/40"
      onClick={onClose}
    >
      <div
        className="flex h-full w-[600px] max-w-full flex-col border-l border-border bg-card shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border px-5 py-3.5">
          <div>
            <h2 className="text-sm font-semibold">同步前后文 · 蝴蝶效应检测</h2>
            <p className="text-[11px] text-muted-foreground">
              当前基准：第 {chapterNo} 章《{chapterTitle}》
              {vip && (
                <>
                  {" · "}
                  {vip.displayName}（最多框选 {maxRange} 章 / 单批修补 {vip.entitlements.rippleMaxPatchChapters} 章）
                </>
              )}
            </p>
          </div>
          <Button size="xs" variant="ghost" onClick={onClose}>
            关闭
          </Button>
        </div>

        <div className="flex-1 overflow-y-auto p-5 space-y-4 text-xs">
          {quotaError && (
            <div className="flex items-center justify-between gap-3 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-amber-800 dark:text-amber-300">
              <span>{quotaError}</span>
              <Link href="/vip" className="shrink-0 font-semibold underline">
                升级权益 →
              </Link>
            </div>
          )}
          {error && (
            <div className="rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-2 text-destructive">
              {error}
            </div>
          )}

          {/* Stage 1: Range Selector */}
          <section className="space-y-3 rounded-xl border border-border bg-background p-4">
            <div className="flex items-center justify-between">
              <span className="font-semibold">1. 选择检测范围（支持前文倒推 + 后文顺延）</span>
              {vip && vip.rippleDailyScanLimit > 0 && (
                <span className="font-mono text-[10px] text-muted-foreground">
                  今日已扫 {vip.rippleScansUsedToday}/{vip.rippleDailyScanLimit} 次
                </span>
              )}
            </div>

            <div className="grid grid-cols-2 gap-1.5 sm:grid-cols-3">
              {(
                [
                  ["BIDIRECTIONAL", "双向窗口（推荐）"],
                  ["FORWARD", "仅查后文顺延"],
                  ["BACKWARD", "仅查前文倒推"],
                  ["CUSTOM_RANGE", "自由框选 / 按卷"],
                  ["FULL_BOOK", allowFullBook ? "全书扫描" : "全书扫描（工作室版）"],
                ] as [RangeMode, string][]
              ).map(([mode, label]) => (
                <button
                  key={mode}
                  type="button"
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
                    <label className="text-[10px] text-muted-foreground">起始章号</label>
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
                    <label className="text-[10px] text-muted-foreground">结束章号</label>
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
                    <div className="text-[10px] text-muted-foreground">或直接按卷勾选：</div>
                    <div className="flex flex-wrap gap-1.5">
                      {volumes.map((v) => {
                        const vid = v.id ?? "";
                        const active = selectedVolumeIds.includes(vid);
                        return (
                          <button
                            key={vid}
                            type="button"
                            onClick={() => toggleVolume(vid)}
                            className={`rounded-full px-2 py-0.5 text-[10px] ${
                              active
                                ? "bg-primary text-primary-foreground"
                                : "border border-border bg-background text-muted-foreground"
                            }`}
                          >
                            {active ? `✓ ${v.title}` : v.title}
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
                <label className="text-[10px] text-muted-foreground">改动简述（选填，辅助精准定位）</label>
                <input
                  value={changeNote}
                  onChange={(e) => setChangeNote(e.target.value)}
                  placeholder="例如：二长老改为重伤逃走而非战死"
                  className="w-full rounded border border-input bg-background px-2 py-1.5 text-xs"
                />
              </div>
              <div className="space-y-1">
                <label className="text-[10px] text-muted-foreground">对比改前快照（选填）</label>
                <select
                  value={oldVersionId}
                  onChange={(e) => setOldVersionId(e.target.value)}
                  className="w-full rounded border border-input bg-background px-2 py-1.5 text-xs"
                >
                  <option value="">自动对比最近一次保存版本</option>
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
              disabled={submitting}
              onClick={() => void handleStartAnalyze()}
            >
              {submitting ? "正在提交检测..." : "开始检测前后文影响"}
            </Button>
          </section>

          {/* Recent Jobs Selector */}
          {recentJobs.length > 0 && (
            <div className="flex items-center gap-2 overflow-x-auto pb-1">
              <span className="shrink-0 text-[10px] text-muted-foreground">历史记录：</span>
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
                  第{j.sourceChapterNo}章 ({j.rangeStartChapter}~{j.rangeEndChapter}章)
                </button>
              ))}
            </div>
          )}

          {/* Stage 2: Impact Radar Report */}
          {detail && (
            <section className="space-y-3 rounded-xl border border-border bg-background p-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <span className="font-semibold">2. 双向影响雷达报告</span>
                  <span
                    className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${
                      JOB_STATUS_LABELS[detail.job.status]?.className ?? "bg-muted text-muted-foreground"
                    }`}
                  >
                    {JOB_STATUS_LABELS[detail.job.status]?.label ?? detail.job.status}
                  </span>
                </div>
                {(detail.job.status === "ANALYZING" || detail.job.status === "PATCHING") && (
                  <Button size="xs" variant="outline" disabled={submitting} onClick={() => void handleCancelJob()}>
                    取消任务
                  </Button>
                )}
              </div>

              <div className="flex flex-wrap gap-3 font-mono text-[10px] text-muted-foreground">
                <span>范围：第 {detail.job.rangeStartChapter}~{detail.job.rangeEndChapter} 章</span>
                <span>前文冲突：{detail.job.backwardConflictCount}</span>
                <span>后文冲突：{detail.job.forwardConflictCount}</span>
                <span>轻度提及：{detail.job.minorMentionCount}</span>
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
                <div className="rounded-lg border border-rose-500/40 bg-rose-500/10 px-3 py-2 text-[11px] text-rose-700 dark:text-rose-400">
                  本次改动波及强冲突章节超出单批上限（每次最多勾选 {detail.job.maxPatchBatchLimit} 章），请分批勾选生成修改建议并逐步采纳。
                </div>
              )}

              {renderImpactList("前文倒推排查（统一铺垫/消除旧矛盾）", detail.backwardImpacts)}
              {renderImpactList("后文蝴蝶效应（修复剧情逻辑穿帮）", detail.forwardImpacts)}

              {(detail.backwardImpacts.length > 0 || detail.forwardImpacts.length > 0) && (
                <div className="flex items-center justify-between gap-2 border-t border-border pt-3">
                  <span className="text-[11px] text-muted-foreground">
                    已选 {selectedImpactIds.length}/{detail.job.maxPatchBatchLimit} 章
                    {detail.job.estimatedCostCredits > 0 &&
                      ` · 预估约 ${detail.job.estimatedCostCredits.toFixed(2)} Credits`}
                  </span>
                  <Button
                    size="xs"
                    disabled={submitting || selectedImpactIds.length === 0}
                    onClick={() => void handleGeneratePatches()}
                  >
                    {submitting ? "正在生成..." : "为所选章节生成局部修改建议"}
                  </Button>
                </div>
              )}
            </section>
          )}

          {/* Stage 3 & 4: Sparse Diff Review & Apply */}
          {detail && detail.patches.length > 0 && (
            <section className="space-y-3 rounded-xl border border-border bg-background p-4">
              <div className="flex items-center justify-between">
                <span className="font-semibold">3. 逐章局部 Diff 审阅与采纳</span>
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
                    {selectedPatchIds.length === reviewablePatches.length ? "取消全选" : "全选待审阅"}
                  </button>
                )}
              </div>

              {applySummary && (
                <div className="rounded-lg border border-emerald-500/40 bg-emerald-500/10 px-3 py-2 text-[11px] text-emerald-700 dark:text-emerald-400">
                  已成功写回 {applySummary.appliedCount} 章（均已自动保存改前快照并触发记忆刷新）
                  {applySummary.conflictCount > 0 && `，${applySummary.conflictCount} 章版本冲突已跳过`}
                  {applySummary.manualReviewCount > 0 && `，${applySummary.manualReviewCount} 章需手动校对`}
                  。
                </div>
              )}

              <ul className="space-y-2.5">
                {detail.patches.map((patch) => {
                  const canReview = patch.status === "READY_FOR_REVIEW";
                  const checked = selectedPatchIds.includes(patch.id);
                  return (
                    <li key={patch.id} className="rounded-lg border border-border p-3 space-y-2">
                      <div className="flex items-center justify-between gap-2">
                        <label className="flex items-center gap-2 font-medium">
                          {canReview && (
                            <input
                              type="checkbox"
                              checked={checked}
                              onChange={() => togglePatch(patch.id)}
                            />
                          )}
                          <span>
                            第 {patch.chapterNo} 章《{patch.chapterTitle}》
                            <span className="ml-1.5 text-[10px] text-muted-foreground">
                              ({patch.direction === "BACKWARD" ? "前文" : "后文"} · 第{patch.batchNo}批)
                            </span>
                          </span>
                        </label>
                        <span className="rounded bg-muted px-1.5 py-0.5 text-[10px] text-muted-foreground">
                          {PATCH_STATUS_LABELS[patch.status] ?? patch.status}
                        </span>
                      </div>

                      {patch.hunks.length === 0 ? (
                        <p className="text-[11px] text-muted-foreground">
                          无可自动锚定的局部段落差异，请打开该章手动微调。
                        </p>
                      ) : (
                        <div className="space-y-2">
                          {patch.hunks.map((h, i) => (
                            <div key={i} className="rounded border border-border/70 text-[11px] overflow-hidden">
                              {h.reason && (
                                <div className="border-b border-border/60 bg-muted/40 px-2 py-1 text-[10px] text-muted-foreground">
                                  改动说明：{h.reason}
                                </div>
                              )}
                              <div className="bg-rose-500/10 px-2.5 py-1.5 text-rose-800 dark:text-rose-300 line-through whitespace-pre-wrap">
                                - {h.originalParagraph}
                              </div>
                              <div className="bg-emerald-500/10 px-2.5 py-1.5 text-emerald-800 dark:text-emerald-300 whitespace-pre-wrap">
                                + {h.patchedParagraph}
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
                  disabled={submitting || selectedPatchIds.length === 0}
                  onClick={() => void handleApplyPatches()}
                >
                  {submitting
                    ? "正在写回章节并备份快照..."
                    : `采纳并写入所选 ${selectedPatchIds.length} 章`}
                </Button>
              )}
            </section>
          )}
        </div>
      </div>
    </div>
  );
}
