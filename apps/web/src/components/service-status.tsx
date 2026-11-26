"use client";

import { useCallback, useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";

interface ProbeResult {
  status: "idle" | "checking" | "ok" | "error";
  detail: string;
}

const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export function ServiceStatus() {
  const [web, setWeb] = useState<ProbeResult>({ status: "idle", detail: "" });
  const [javaBiz, setJavaBiz] = useState<ProbeResult>({
    status: "idle",
    detail: "",
  });
  const [agentBridge, setAgentBridge] = useState<ProbeResult>({
    status: "idle",
    detail: "",
  });

  const runChecks = useCallback(async () => {
    setWeb({ status: "checking", detail: "探测中..." });
    setJavaBiz({ status: "checking", detail: "探测中..." });
    setAgentBridge({ status: "checking", detail: "探测中..." });

    try {
      const res = await fetch("/api/health", { cache: "no-store" });
      const data = await res.json();
      setWeb({
        status: res.ok ? "ok" : "error",
        detail: `service=${data.service}`,
      });
    } catch (err) {
      setWeb({ status: "error", detail: String(err) });
    }

    try {
      const res = await fetch(`${API_BASE}/api/v1/health`, {
        cache: "no-store",
      });
      const data = await res.json();
      setJavaBiz({
        status: res.ok ? "ok" : "error",
        detail: `JDK ${data.javaVersion} · virtualThread=${String(data.virtualThread)}`,
      });
    } catch {
      setJavaBiz({
        status: "error",
        detail: `无法连接 ${API_BASE}（请先运行 make dev-java）`,
      });
    }

    try {
      const res = await fetch(`${API_BASE}/api/v1/health/agent`, {
        cache: "no-store",
      });
      const data = await res.json();
      setAgentBridge({
        status: res.ok ? "ok" : "error",
        detail: res.ok
          ? `Python ${data.agent?.python ?? ""} · llmConfigured=${String(data.agent?.llm_configured)}`
          : `Java 桥接返回 ${res.status}（请确认 make dev-py 已启动）`,
      });
    } catch {
      setAgentBridge({
        status: "error",
        detail: "需先启动 biz-java 与 agent-py",
      });
    }
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => {
      void runChecks();
    }, 0);
    return () => clearTimeout(timer);
  }, [runChecks]);

  const items = [
    { name: "Frontend (Next.js)", endpoint: "/api/health", state: web },
    {
      name: "Business & Gateway (Java 21)",
      endpoint: `${API_BASE}/api/v1/health`,
      state: javaBiz,
    },
    {
      name: "Agent Engine Bridge (Java → Python)",
      endpoint: `${API_BASE}/api/v1/health/agent`,
      state: agentBridge,
    },
  ];

  return (
    <Card className="p-6">
      <div className="flex items-center justify-between gap-4 border-b border-border pb-4">
        <div>
          <h2 className="text-base font-semibold">三端连通性探测</h2>
          <p className="text-sm text-muted-foreground">
            验证 Next.js → Spring Boot (JDK 21) → Python FastAPI 基础链路
          </p>
        </div>
        <Button size="sm" variant="outline" onClick={() => void runChecks()}>
          重新检测
        </Button>
      </div>

      <div className="mt-4 divide-y divide-border">
        {items.map((item) => (
          <div
            key={item.name}
            className="flex flex-col gap-1 py-3 sm:flex-row sm:items-center sm:justify-between"
          >
            <div>
              <div className="text-sm font-medium">{item.name}</div>
              <div className="font-mono text-xs text-muted-foreground">
                {item.endpoint}
              </div>
            </div>
            <div className="flex items-center gap-2 text-xs">
              <Badge
                status={
                  item.state.status === "ok"
                    ? "success"
                    : item.state.status === "checking"
                      ? "warning"
                      : item.state.status === "error"
                        ? "danger"
                        : "neutral"
                }
              >
                {item.state.status === "ok"
                  ? "正常"
                  : item.state.status === "checking"
                    ? "检测中"
                    : item.state.status === "error"
                      ? "异常"
                      : "待测"}
              </Badge>
              <span className="font-mono text-muted-foreground">
                {item.state.detail || item.state.status}
              </span>
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
}
