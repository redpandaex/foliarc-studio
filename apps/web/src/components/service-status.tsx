"use client";

import { useCallback, useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";

type ProbeState = "idle" | "checking" | "ok" | "error";

const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

const STATUS_META: Record<ProbeState, { badge: "success" | "warning" | "danger" | "neutral"; label: string }> = {
  idle: { badge: "neutral", label: "待检测" },
  checking: { badge: "warning", label: "检测中" },
  ok: { badge: "success", label: "正常" },
  error: { badge: "danger", label: "异常" },
};

export function ServiceStatus() {
  const [web, setWeb] = useState<ProbeState>("idle");
  const [biz, setBiz] = useState<ProbeState>("idle");
  const [assistant, setAssistant] = useState<ProbeState>("idle");

  const runChecks = useCallback(async () => {
    setWeb("checking");
    setBiz("checking");
    setAssistant("checking");

    await Promise.all([
      fetch("/api/health", { cache: "no-store" })
        .then((res) => setWeb(res.ok ? "ok" : "error"))
        .catch(() => setWeb("error")),
      fetch(`${API_BASE}/api/v1/health`, { cache: "no-store" })
        .then((res) => setBiz(res.ok ? "ok" : "error"))
        .catch(() => setBiz("error")),
      fetch(`${API_BASE}/api/v1/health/agent`, { cache: "no-store" })
        .then((res) => setAssistant(res.ok ? "ok" : "error"))
        .catch(() => setAssistant("error")),
    ]);
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => {
      void runChecks();
    }, 0);
    return () => clearTimeout(timer);
  }, [runChecks]);

  const items: { name: string; state: ProbeState }[] = [
    { name: "站点", state: web },
    { name: "作品服务", state: biz },
    { name: "AI 助手", state: assistant },
  ];

  const checking = items.some((i) => i.state === "checking");

  return (
    <Card className="p-4">
      <div className="flex items-center justify-between gap-3 pb-3">
        <span className="text-xs font-semibold">服务状态</span>
        <Button size="xs" variant="outline" loading={checking} onClick={() => void runChecks()}>
          重新检测
        </Button>
      </div>
      <div className="grid gap-2 sm:grid-cols-3">
        {items.map((item) => {
          const meta = STATUS_META[item.state];
          return (
            <div
              key={item.name}
              className="flex items-center justify-between rounded-lg border border-border bg-background/60 px-3 py-2 text-xs"
            >
              <span className="font-medium">{item.name}</span>
              <Badge status={meta.badge}>{meta.label}</Badge>
            </div>
          );
        })}
      </div>
    </Card>
  );
}
