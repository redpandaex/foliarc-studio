"use client";

import { useMemo, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

export interface DiffHunk {
  id: number;
  kind: "EQUAL" | "ADD" | "DELETE" | "REPLACE";
  oldLines: string[];
  newLines: string[];
}

interface SplitResult {
  paragraphs: string[];
  separator: string;
}

function splitParagraphsPreserveIndent(text: string): SplitResult {
  const normalized = text.replace(/\r\n/g, "\n");
  if (!normalized.trim()) {
    return { paragraphs: [], separator: "\n\n" };
  }
  const hasDoubleNewline = /\n{2,}/.test(normalized);
  if (hasDoubleNewline) {
    const blocks = normalized
      .split(/\n{2,}/)
      .filter((p) => p.trim().length > 0);
    return { paragraphs: blocks, separator: "\n\n" };
  }
  const lines = normalized.split("\n").filter((p) => p.trim().length > 0);
  return { paragraphs: lines, separator: "\n" };
}

/**
 * Computes a paragraph-level LCS diff and merges adjacent DELETE+ADD runs into REPLACE hunks
 * while preserving original paragraph indentation.
 */
export function computeParagraphDiffHunks(
  originalText: string,
  proposedText: string,
): DiffHunk[] {
  const { paragraphs: oldParas } = splitParagraphsPreserveIndent(originalText);
  const { paragraphs: newParas } = splitParagraphsPreserveIndent(proposedText);
  const m = oldParas.length;
  const n = newParas.length;

  const dp: number[][] = Array.from({ length: m + 1 }, () =>
    new Array<number>(n + 1).fill(0),
  );
  for (let i = m - 1; i >= 0; i -= 1) {
    for (let j = n - 1; j >= 0; j -= 1) {
      if (oldParas[i] === newParas[j]) {
        dp[i][j] = dp[i + 1][j + 1] + 1;
      } else {
        dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
      }
    }
  }

  type RawOp = { type: "EQUAL" | "DELETE" | "ADD"; text: string };
  const ops: RawOp[] = [];
  let i = 0;
  let j = 0;
  while (i < m && j < n) {
    if (oldParas[i] === newParas[j]) {
      ops.push({ type: "EQUAL", text: oldParas[i] });
      i += 1;
      j += 1;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      ops.push({ type: "DELETE", text: oldParas[i] });
      i += 1;
    } else {
      ops.push({ type: "ADD", text: newParas[j] });
      j += 1;
    }
  }
  while (i < m) {
    ops.push({ type: "DELETE", text: oldParas[i] });
    i += 1;
  }
  while (j < n) {
    ops.push({ type: "ADD", text: newParas[j] });
    j += 1;
  }

  const hunks: DiffHunk[] = [];
  let idx = 0;
  let hunkId = 0;
  while (idx < ops.length) {
    if (ops[idx].type === "EQUAL") {
      const equalLines: string[] = [];
      while (idx < ops.length && ops[idx].type === "EQUAL") {
        equalLines.push(ops[idx].text);
        idx += 1;
      }
      hunks.push({
        id: hunkId++,
        kind: "EQUAL",
        oldLines: equalLines,
        newLines: equalLines,
      });
      continue;
    }

    const oldLines: string[] = [];
    const newLines: string[] = [];
    while (idx < ops.length && ops[idx].type !== "EQUAL") {
      if (ops[idx].type === "DELETE") {
        oldLines.push(ops[idx].text);
      } else {
        newLines.push(ops[idx].text);
      }
      idx += 1;
    }
    const kind: DiffHunk["kind"] =
      oldLines.length > 0 && newLines.length > 0
        ? "REPLACE"
        : newLines.length > 0
          ? "ADD"
          : "DELETE";
    hunks.push({
      id: hunkId++,
      kind,
      oldLines,
      newLines,
    });
  }
  return hunks;
}

interface AiDiffReviewModalProps {
  open: boolean;
  title: string;
  applyTargetLabel?: string;
  originalText: string;
  proposedText: string;
  onClose: () => void;
  onApply: (mergedText: string) => void;
}

export function AiDiffReviewModal({
  open,
  title,
  applyTargetLabel = "应用到正文",
  originalText,
  proposedText,
  onClose,
  onApply,
}: AiDiffReviewModalProps) {
  const hunks = useMemo(
    () => computeParagraphDiffHunks(originalText, proposedText),
    [originalText, proposedText],
  );
  const preferredSeparator = useMemo(
    () => splitParagraphsPreserveIndent(originalText).separator,
    [originalText],
  );
  const [decisions, setDecisions] = useState<Record<number, boolean>>({});
  const [showPreview, setShowPreview] = useState(false);

  const changeHunks = useMemo(
    () => hunks.filter((h) => h.kind !== "EQUAL"),
    [hunks],
  );

  const acceptedCount = useMemo(
    () => changeHunks.filter((h) => decisions[h.id] !== false).length,
    [changeHunks, decisions],
  );

  const mergedText = useMemo(() => {
    if (changeHunks.length === 0 || acceptedCount === 0) {
      return originalText;
    }
    if (acceptedCount === changeHunks.length && originalText.trim().length === 0) {
      return proposedText;
    }
    const out: string[] = [];
    for (const h of hunks) {
      if (h.kind === "EQUAL") {
        out.push(...h.oldLines);
      } else if (decisions[h.id] !== false) {
        out.push(...h.newLines);
      } else {
        out.push(...h.oldLines);
      }
    }
    return out.join(preferredSeparator);
  }, [hunks, decisions, changeHunks.length, acceptedCount, originalText, proposedText, preferredSeparator]);

  if (!open) return null;

  function setAll(accept: boolean) {
    const next: Record<number, boolean> = {};
    for (const h of changeHunks) {
      next[h.id] = accept;
    }
    setDecisions(next);
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="flex max-h-[88vh] w-full max-w-4xl flex-col overflow-hidden rounded-xl border border-border bg-card shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-5 py-3.5">
          <div className="flex items-center gap-2.5">
            <h2 className="text-sm font-semibold">{title}</h2>
            <Badge status="info" className="font-mono text-[10.5px]">
              {acceptedCount} / {changeHunks.length} 处采用
            </Badge>
          </div>
          <div className="flex items-center gap-1.5">
            <Button size="xs" variant="outline" onClick={() => setAll(true)}>
              全部采用
            </Button>
            <Button size="xs" variant="outline" onClick={() => setAll(false)}>
              全部保留
            </Button>
            <Button
              size="xs"
              variant="outline"
              onClick={() => setShowPreview((v) => !v)}
            >
              {showPreview ? "返回对比" : "合并预览"}
            </Button>
            <Button size="xs" variant="ghost" onClick={onClose}>
              关闭
            </Button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto p-5">
          {showPreview ? (
            <div className="space-y-2">
              <div className="text-[11px] font-medium text-muted-foreground tabular-nums">
                合并结果（{mergedText.replace(/\s+/g, "").length} 字）
              </div>
              <div className="whitespace-pre-wrap rounded-lg border border-border bg-background p-4 font-editor text-sm leading-8">
                {mergedText || "（内容为空）"}
              </div>
            </div>
          ) : changeHunks.length === 0 ? (
            <div className="py-12 text-center text-xs text-muted-foreground">
              建议内容与原文一致，无差异改动。
            </div>
          ) : (
            <div className="space-y-3">
              {hunks.map((h) => {
                if (h.kind === "EQUAL") {
                  return (
                    <div
                      key={h.id}
                      className="rounded-lg border border-border/50 bg-muted/20 px-3.5 py-2 text-xs leading-6 text-muted-foreground"
                    >
                      {h.oldLines.map((line, i) => (
                        <p key={i} className={`whitespace-pre-wrap ${i > 0 ? "mt-2" : ""}`}>
                          {line}
                        </p>
                      ))}
                    </div>
                  );
                }

                const accepted = decisions[h.id] !== false;
                const changeIndex = changeHunks.findIndex((c) => c.id === h.id) + 1;
                return (
                  <div
                    key={h.id}
                    className={`overflow-hidden rounded-lg border text-xs ${
                      accepted
                        ? "border-status-success-border bg-card"
                        : "border-status-warning-border bg-card"
                    }`}
                  >
                    <div className="flex items-center justify-between border-b border-border bg-muted/60 px-3 py-1.5 text-[11px]">
                      <div className="flex items-center gap-2">
                        <span className="font-mono text-[10.5px] text-muted-foreground">
                          第 {changeIndex} 处
                        </span>
                        <Badge status={accepted ? "success" : "warning"}>
                          {accepted ? "采用建议" : "保留原文"}
                        </Badge>
                      </div>
                      <div
                        role="group"
                        aria-label={`第 ${changeIndex} 处选择`}
                        className="inline-flex rounded-md border border-border bg-background p-0.5"
                      >
                        <button
                          type="button"
                          aria-pressed={!accepted}
                          onClick={() =>
                            setDecisions((prev) => ({ ...prev, [h.id]: false }))
                          }
                          className={`rounded px-2 py-0.5 text-[11px] font-medium transition-colors ${
                            !accepted
                              ? "bg-status-warning-bg text-status-warning"
                              : "text-muted-foreground hover:text-foreground"
                          }`}
                        >
                          原文
                        </button>
                        <button
                          type="button"
                          aria-pressed={accepted}
                          onClick={() =>
                            setDecisions((prev) => ({ ...prev, [h.id]: true }))
                          }
                          className={`rounded px-2 py-0.5 text-[11px] font-medium transition-colors ${
                            accepted
                              ? "bg-status-success-bg text-status-success"
                              : "text-muted-foreground hover:text-foreground"
                          }`}
                        >
                          建议
                        </button>
                      </div>
                    </div>

                    <div className="grid divide-y divide-border md:grid-cols-2 md:divide-x md:divide-y-0">
                      <div
                        onClick={() =>
                          setDecisions((prev) => ({ ...prev, [h.id]: false }))
                        }
                        className={`cursor-pointer space-y-1.5 p-3 leading-6 transition-opacity ${
                          !accepted
                            ? "bg-status-warning-bg/55 text-foreground ring-1 ring-inset ring-status-warning-border"
                            : "bg-background/60 text-muted-foreground opacity-70 hover:opacity-95"
                        }`}
                      >
                        <div className="text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
                          原文
                        </div>
                        {h.oldLines.length > 0 ? (
                          h.oldLines.map((line, i) => (
                            <p key={i} className="whitespace-pre-wrap">
                              {line}
                            </p>
                          ))
                        ) : (
                          <p className="italic text-muted-foreground">（新增段落）</p>
                        )}
                      </div>

                      <div
                        onClick={() =>
                          setDecisions((prev) => ({ ...prev, [h.id]: true }))
                        }
                        className={`cursor-pointer space-y-1.5 p-3 leading-6 transition-opacity ${
                          accepted
                            ? "bg-status-success-bg/55 text-foreground ring-1 ring-inset ring-status-success-border"
                            : "bg-background/60 text-muted-foreground opacity-60 hover:opacity-95"
                        }`}
                      >
                        <div className="text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
                          建议
                        </div>
                        {h.newLines.length > 0 ? (
                          h.newLines.map((line, i) => (
                            <p key={i} className="whitespace-pre-wrap">
                              {line}
                            </p>
                          ))
                        ) : (
                          <p className="italic text-muted-foreground">（删除此段）</p>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        <div className="flex items-center justify-between border-t border-border bg-card px-5 py-3">
          <span className="text-xs text-muted-foreground tabular-nums">
            已采用 {acceptedCount}/{changeHunks.length} 处 · 更新当前草稿并自动保存
          </span>
          <div className="flex items-center gap-2">
            <Button size="sm" variant="outline" onClick={onClose}>
              取消
            </Button>
            <Button
              size="sm"
              onClick={() => {
                onApply(mergedText);
                onClose();
              }}
            >
              {applyTargetLabel}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
