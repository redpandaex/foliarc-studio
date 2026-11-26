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

function splitParagraphs(text: string): string[] {
  const normalized = text.replace(/\r\n/g, "\n").trim();
  if (!normalized) return [];
  const blocks = normalized
    .split(/\n{2,}/)
    .map((p) => p.trim())
    .filter(Boolean);
  if (blocks.length > 1) return blocks;
  return normalized
    .split("\n")
    .map((p) => p.trim())
    .filter(Boolean);
}

/**
 * Computes a paragraph-level LCS diff and merges adjacent DELETE+ADD runs into REPLACE hunks
 * so authors can Accept/Reject each logical change like a Git hunk.
 */
export function computeParagraphDiffHunks(
  originalText: string,
  proposedText: string,
): DiffHunk[] {
  const oldParas = splitParagraphs(originalText);
  const newParas = splitParagraphs(proposedText);
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
  subtitle?: string;
  originalText: string;
  proposedText: string;
  onClose: () => void;
  onApply: (mergedText: string) => void;
}

export function AiDiffReviewModal({
  open,
  title,
  subtitle,
  originalText,
  proposedText,
  onClose,
  onApply,
}: AiDiffReviewModalProps) {
  const hunks = useMemo(
    () => computeParagraphDiffHunks(originalText, proposedText),
    [originalText, proposedText],
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
    return out.join("\n\n");
  }, [hunks, decisions]);

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
        className="flex max-h-[88vh] w-full max-w-4xl flex-col overflow-hidden rounded-xl border border-border bg-card shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-5 py-3.5">
          <div>
            <h2 className="text-sm font-semibold">{title}</h2>
            <p className="text-[11px] text-muted-foreground">
              {subtitle ?? "逐段审阅 AI 改写建议，点击右侧按钮切换采纳或保留原文"} · 共{" "}
              {changeHunks.length} 处改动，已选采纳 {acceptedCount} 处
            </p>
          </div>
          <div className="flex items-center gap-1.5">
            <Button size="xs" variant="outline" onClick={() => setAll(true)}>
              全部采纳
            </Button>
            <Button size="xs" variant="outline" onClick={() => setAll(false)}>
              全部保留原文
            </Button>
            <Button
              size="xs"
              variant="outline"
              onClick={() => setShowPreview((v) => !v)}
            >
              {showPreview ? "返回 Diff 视图" : "合并预览"}
            </Button>
            <Button size="xs" variant="ghost" onClick={onClose}>
              关闭
            </Button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto p-5">
          {showPreview ? (
            <div className="space-y-2">
              <div className="text-[11px] font-medium text-muted-foreground">
                最终写入正文预览（{mergedText.replace(/\s+/g, "").length} 字）
              </div>
              <div className="whitespace-pre-wrap rounded-lg border border-border bg-background p-4 text-sm leading-8">
                {mergedText || "（合并结果为空）"}
              </div>
            </div>
          ) : changeHunks.length === 0 ? (
            <div className="py-12 text-center text-xs text-muted-foreground">
              AI 生成内容与原文完全一致，无差异改动。
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
                        <p key={i} className={i > 0 ? "mt-2" : ""}>
                          {line}
                        </p>
                      ))}
                    </div>
                  );
                }

                const accepted = decisions[h.id] !== false;
                return (
                  <div
                    key={h.id}
                    className={`overflow-hidden rounded-lg border text-xs ${
                      accepted
                        ? "border-status-success-border bg-card"
                        : "border-status-warning-border bg-card"
                    }`}
                  >
                    <div className="flex items-center justify-between border-b border-border bg-muted px-3 py-1.5 text-[11px]">
                      <div className="flex items-center gap-2">
                        <span className="font-mono text-[10px] text-muted-foreground">
                          Hunk #{h.id + 1} · {h.kind}
                        </span>
                        <Badge status={accepted ? "success" : "warning"}>
                          {accepted ? "将采纳 AI 修改" : "将保留原文"}
                        </Badge>
                      </div>
                      <div className="flex gap-1.5">
                        <Button
                          size="xs"
                          variant={accepted ? "default" : "outline"}
                          onClick={() =>
                            setDecisions((prev) => ({ ...prev, [h.id]: true }))
                          }
                        >
                          Accept 采纳
                        </Button>
                        <Button
                          size="xs"
                          variant={!accepted ? "secondary" : "outline"}
                          onClick={() =>
                            setDecisions((prev) => ({ ...prev, [h.id]: false }))
                          }
                        >
                          Reject 保留原文
                        </Button>
                      </div>
                    </div>

                    {h.oldLines.length > 0 && (
                      <div
                        className={`space-y-1.5 border-b border-border bg-status-danger-bg px-3.5 py-2.5 leading-6 text-status-danger ${
                          accepted ? "opacity-80" : "ring-1 ring-inset ring-status-warning-border"
                        }`}
                      >
                        {h.oldLines.map((line, i) => (
                          <div key={i} className="flex gap-2">
                            <span className="select-none font-mono font-bold">-</span>
                            <p className="whitespace-pre-wrap">{line}</p>
                          </div>
                        ))}
                      </div>
                    )}

                    {h.newLines.length > 0 && (
                      <div
                        className={`space-y-1.5 bg-status-success-bg px-3.5 py-2.5 leading-6 text-status-success ${
                          accepted
                            ? "ring-1 ring-inset ring-status-success-border"
                            : "opacity-50 line-through"
                        }`}
                      >
                        {h.newLines.map((line, i) => (
                          <div key={i} className="flex gap-2">
                            <span className="select-none font-mono font-bold">+</span>
                            <p className="whitespace-pre-wrap">{line}</p>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>

        <div className="flex items-center justify-between border-t border-border bg-card px-5 py-3">
          <span className="text-[11px] text-muted-foreground">
            写入后将触发自动保存，并保留历史快照可随时回滚
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
              确认写入正文 ({acceptedCount}/{changeHunks.length} 处修改)
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
