import { getAccessToken, refreshSession } from "@/lib/api";

export type SseEventType =
  | "run_started"
  | "thought_delta"
  | "tool_call"
  | "tool_result"
  | "text_delta"
  | "interrupt"
  | "usage"
  | "ping"
  | "error"
  | "done";

export interface SseEnvelope<T = Record<string, unknown>> {
  event_id: string;
  run_id: string;
  trace_id?: string;
  seq: number;
  type: SseEventType;
  agent?: string;
  timestamp: string;
  payload: T;
}

export interface ParsedSseFrame {
  id?: string;
  event?: string;
  data: string;
  retry?: number;
}

export type StreamConnectionState =
  | "idle"
  | "connecting"
  | "streaming"
  | "reconnecting"
  | "paused"
  | "completed"
  | "cancelled"
  | "error";

/**
 * Incremental RFC-8895 Server-Sent Events parser.
 * Handles arbitrary TCP chunk splits, CRLF/LF newlines, multi-line `data:` blocks,
 * comment lines, and multi-byte UTF-8 boundaries when paired with streaming TextDecoder.
 */
export class SseFrameParser {
  private buffer = "";
  private currentId: string | undefined;
  private currentEvent: string | undefined;
  private currentData: string[] = [];
  private currentRetry: number | undefined;

  feed(chunk: string): ParsedSseFrame[] {
    this.buffer += chunk;
    const frames: ParsedSseFrame[] = [];

    let newlineIdx = this.buffer.indexOf("\n");
    while (newlineIdx !== -1) {
      let line = this.buffer.slice(0, newlineIdx);
      this.buffer = this.buffer.slice(newlineIdx + 1);
      if (line.endsWith("\r")) {
        line = line.slice(0, -1);
      }

      if (line === "") {
        const frame = this.flushFrame();
        if (frame) {
          frames.push(frame);
        }
      } else if (!line.startsWith(":")) {
        const colonIdx = line.indexOf(":");
        const field = colonIdx === -1 ? line : line.slice(0, colonIdx);
        let value = colonIdx === -1 ? "" : line.slice(colonIdx + 1);
        if (value.startsWith(" ")) {
          value = value.slice(1);
        }

        if (field === "id" && !value.includes("\0")) {
          this.currentId = value;
        } else if (field === "event") {
          this.currentEvent = value;
        } else if (field === "data") {
          this.currentData.push(value);
        } else if (field === "retry") {
          const parsed = Number.parseInt(value, 10);
          if (!Number.isNaN(parsed) && parsed >= 0) {
            this.currentRetry = parsed;
          }
        }
      }

      newlineIdx = this.buffer.indexOf("\n");
    }

    return frames;
  }

  flush(): ParsedSseFrame | null {
    return this.flushFrame();
  }

  private flushFrame(): ParsedSseFrame | null {
    if (this.currentData.length === 0) {
      this.currentEvent = undefined;
      return null;
    }
    const frame: ParsedSseFrame = {
      id: this.currentId,
      event: this.currentEvent,
      data: this.currentData.join("\n"),
      retry: this.currentRetry,
    };
    this.currentEvent = undefined;
    this.currentData = [];
    this.currentRetry = undefined;
    return frame;
  }
}

export interface ResumableSseOptions {
  url: string;
  initialLastEventId?: string | null;
  maxRetries?: number;
  baseRetryDelayMs?: number;
  maxRetryDelayMs?: number;
  heartbeatTimeoutMs?: number;
  onEvent: (envelope: SseEnvelope) => void;
  onStateChange: (state: StreamConnectionState, meta?: { attempt?: number; detail?: string }) => void;
  onLastEventId?: (eventId: string) => void;
}

export interface ResumableSseHandle {
  /** Disconnects the local HTTP stream without cancelling the backend run. */
  disconnect: () => void;
  /** Intentionally drops the active socket to test Last-Event-ID resumable reconnect. */
  simulateDrop: () => void;
}

function computeBackoffWithJitter(attempt: number, baseMs: number, maxMs: number): number {
  const exp = Math.min(baseMs * Math.pow(2, Math.max(0, attempt - 1)), maxMs);
  return Math.round(exp * (0.5 + Math.random() * 0.5));
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) {
      resolve();
      return;
    }
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      resolve();
    };
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

function waitUntilOnline(signal: AbortSignal): Promise<void> {
  if (typeof window === "undefined" || navigator.onLine) {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    const done = () => {
      window.removeEventListener("online", done);
      signal.removeEventListener("abort", done);
      resolve();
    };
    window.addEventListener("online", done, { once: true });
    signal.addEventListener("abort", done, { once: true });
  });
}

/**
 * Resilient fetch-based SSE client supporting Bearer auth, silent JWT refresh,
 * Last-Event-ID Redis Streams replay, exponential backoff + jitter, online/offline recovery,
 * and server heartbeat timeout detection.
 */
export function connectResumableSse(options: ResumableSseOptions): ResumableSseHandle {
  const maxRetries = options.maxRetries ?? 6;
  const baseDelayMs = options.baseRetryDelayMs ?? 600;
  const maxDelayMs = options.maxRetryDelayMs ?? 12000;
  const heartbeatTimeoutMs = options.heartbeatTimeoutMs ?? 35000;

  const masterController = new AbortController();
  let activeFetchController: AbortController | null = null;
  let lastEventId = options.initialLastEventId ?? null;
  let terminatedByServer = false;
  let attempt = 0;

  const loop = async () => {
    while (!masterController.signal.aborted && !terminatedByServer) {
      if (attempt > 0) {
        if (attempt > maxRetries) {
          options.onStateChange("error", { detail: `重连超过最大次数 (${maxRetries})` });
          return;
        }
        await waitUntilOnline(masterController.signal);
        if (masterController.signal.aborted) return;

        const delayMs = computeBackoffWithJitter(attempt, baseDelayMs, maxDelayMs);
        options.onStateChange("reconnecting", {
          attempt,
          detail: `第 ${attempt} 次重连（${delayMs}ms 后携带 Last-Event-ID 续接）`,
        });
        await sleep(delayMs, masterController.signal);
        if (masterController.signal.aborted) return;
      } else {
        options.onStateChange("connecting");
      }

      const fetchController = new AbortController();
      activeFetchController = fetchController;

      const onMasterAbort = () => fetchController.abort();
      masterController.signal.addEventListener("abort", onMasterAbort, { once: true });

      let heartbeatTimer: ReturnType<typeof setTimeout> | null = null;
      const resetHeartbeat = () => {
        if (heartbeatTimer) clearTimeout(heartbeatTimer);
        heartbeatTimer = setTimeout(() => {
          fetchController.abort(new Error("HEARTBEAT_TIMEOUT"));
        }, heartbeatTimeoutMs);
      };

      try {
        const headers = new Headers({
          Accept: "text/event-stream",
          "Cache-Control": "no-cache",
        });
        const token = getAccessToken();
        if (token) {
          headers.set("Authorization", `Bearer ${token}`);
        }
        if (lastEventId) {
          headers.set("Last-Event-ID", lastEventId);
        }

        resetHeartbeat();
        const response = await fetch(options.url, {
          method: "GET",
          headers,
          credentials: "include",
          signal: fetchController.signal,
        });

        if (response.status === 401) {
          const refreshed = await refreshSession();
          if (refreshed) {
            attempt += 1;
            continue;
          }
          options.onStateChange("error", { detail: "登录态已过期，请重新登录" });
          return;
        }

        if (!response.ok || !response.body) {
          throw new Error(`SSE 连接返回 HTTP ${response.status}`);
        }

        attempt = 0;
        options.onStateChange("streaming");

        const reader = response.body.getReader();
        const decoder = new TextDecoder("utf-8");
        const parser = new SseFrameParser();

        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            const tail = decoder.decode();
            if (tail) {
              for (const frame of parser.feed(tail)) {
                handleFrame(frame);
              }
            }
            const leftover = parser.flush();
            if (leftover) {
              handleFrame(leftover);
            }
            break;
          }

          resetHeartbeat();
          const text = decoder.decode(value, { stream: true });
          const frames = parser.feed(text);
          for (const frame of frames) {
            handleFrame(frame);
            if (terminatedByServer) {
              await reader.cancel();
              return;
            }
          }
        }

        if (!terminatedByServer && !masterController.signal.aborted) {
          attempt += 1;
        }
      } catch (err) {
        if (masterController.signal.aborted || terminatedByServer) {
          return;
        }
        attempt += 1;
        const msg = err instanceof Error ? err.message : "网络连接中断";
        options.onStateChange("reconnecting", { attempt, detail: msg });
      } finally {
        if (heartbeatTimer) clearTimeout(heartbeatTimer);
        masterController.signal.removeEventListener("abort", onMasterAbort);
        if (activeFetchController === fetchController) {
          activeFetchController = null;
        }
      }
    }
  };

  function handleFrame(frame: ParsedSseFrame) {
    let envelope: SseEnvelope;
    try {
      envelope = JSON.parse(frame.data) as SseEnvelope;
    } catch {
      return;
    }

    if (frame.id && envelope.type !== "ping") {
      lastEventId = frame.id;
      options.onLastEventId?.(frame.id);
    }

    options.onEvent(envelope);

    if (envelope.type === "done") {
      terminatedByServer = true;
      const doneStatus = envelope.payload?.status;
      options.onStateChange(doneStatus === "cancelled" ? "cancelled" : "completed");
    } else if (envelope.type === "interrupt") {
      terminatedByServer = true;
      options.onStateChange("paused");
    } else if (envelope.type === "error") {
      terminatedByServer = true;
      options.onStateChange("error", {
        detail: String(envelope.payload?.message ?? "生成发生错误"),
      });
    }
  }

  void loop();

  return {
    disconnect() {
      masterController.abort();
      activeFetchController?.abort();
    },
    simulateDrop() {
      activeFetchController?.abort(new Error("SIMULATED_NETWORK_DROP"));
    },
  };
}
