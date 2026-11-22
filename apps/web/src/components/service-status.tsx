"use client";

import { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/button";

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
    <section className="rounded-xl border border-border bg-card p-6 shadow-xs">
      <div className="flex items-center justify-between gap-4 pb-4 border-b border-border">
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
              <span
                className={
                  item.state.status === "ok"
                    ? "inline-block h-2 w-2 rounded-full bg-emerald-500"
                    : item.state.status === "checking"
                      ? "inline-block h-2 w-2 rounded-full bg-amber-400"
                      : "inline-block h-2 w-2 rounded-full bg-rose-500"
                }
              />
              <span className="font-mono text-muted-foreground">
                {item.state.detail || item.state.status}
              </span>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
