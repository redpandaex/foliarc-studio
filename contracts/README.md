# Cross-Service Contracts

This directory holds the single source of truth for protocols shared across:
- `apps/web` (Next.js TypeScript frontend)
- `services/biz-java` (Java 21 Spring Boot 3 business & AI gateway)
- `services/agent-py` (Python 3.12 FastAPI Agent & RAG engine)

## Files

| File | Purpose |
| :--- | :--- |
| [sse-events.schema.json](./sse-events.schema.json) | Structured SSE event envelope v1 (`id`, `event`, `data`) used for streaming generation, Redis Streams replay (`Last-Event-ID`), HITL interrupts, and token usage accounting. |

## SSE Wire Format

Every event emitted from `biz-java` to `web` follows standard Server-Sent Events framing:

```text
id: 1732260000000-0
event: text_delta
data: {"event_id":"1732260000000-0","run_id":"...","seq":3,"type":"text_delta","timestamp":"2026-11-22T00:00:00Z","payload":{"content":"夜色如墨"}}

```

On reconnect, the client sends the HTTP header `Last-Event-ID: <last_received_event_id>` (or query parameter `lastEventId`) to replay missed frames from Redis Streams without re-invoking the LLM.
