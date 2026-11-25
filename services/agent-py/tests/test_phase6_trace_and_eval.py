import asyncio
import json
from collections.abc import AsyncIterator
from typing import Any

import pytest

from foliarc_agent.config import Settings
from foliarc_agent.evals import NovelEvalHarness
from foliarc_agent.streaming.llm_client import LlmStreamChunk
from foliarc_agent.streaming.protocol import RunStartRequest, RunStateCallback
from foliarc_agent.streaming.runner import RunManager, redact_trace_payload


class _FakeRedis:
    def __init__(self) -> None:
        self.kv: dict[str, str] = {}
        self.streams: dict[str, list[dict[str, str]]] = {}

    async def delete(self, key: str) -> None:
        self.kv.pop(key, None)

    async def get(self, key: str) -> str | None:
        return self.kv.get(key)

    async def set(self, key: str, value: str, ex: int | None = None) -> None:
        self.kv[key] = value

    async def xadd(self, key: str, fields: dict[str, str]) -> str:
        items = self.streams.setdefault(key, [])
        items.append(fields)
        return f"1700000000-{len(items)}"

    async def expire(self, key: str, ttl: int) -> None:
        return None


class _FakeLlm:
    async def stream_chat(
        self,
        *,
        model_alias: str,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
        temperature: float = 0.8,
        max_tokens: int = 2048,
    ) -> AsyncIterator[LlmStreamChunk]:
        yield LlmStreamChunk(reasoning_delta="思考中...", content_delta="雪夜风紧，")
        yield LlmStreamChunk(content_delta="林青推开古寺木门。", input_tokens=150, cached_tokens=90, output_tokens=30)


class _RecordingReporter:
    def __init__(self) -> None:
        self.states: list[RunStateCallback] = []
        self.persisted_events: list[dict[str, Any]] = []

    async def report(self, run_id: str, state: RunStateCallback) -> None:
        self.states.append(state)

    async def report_events(self, run_id: str, events: list[dict[str, Any]]) -> None:
        self.persisted_events.extend(events)


@pytest.mark.asyncio
async def test_phase6_golden_eval_harness_passes_all_30_novel_cases() -> None:
    harness = NovelEvalHarness(Settings(llm_api_key="", embedding_api_key=""))
    report = await harness.run_all()
    assert report.total_cases == 30
    failed = [f"{c.case_id}:{c.detail}" for c in report.cases if not c.passed]
    assert failed == [], f"Eval failures: {failed}"
    assert report.overall_pass_rate == 1.0
    assert set(report.category_scores.keys()) == {
        "GENRE_ROUTING",
        "GENRE_RUBRIC",
        "RAG_ANTI_SPOILER",
        "RIPPLE_FACT_DIFF",
        "GUARDRAILS",
    }


@pytest.mark.asyncio
async def test_phase6_structured_trace_persistence_excludes_token_deltas_and_redacts_secrets() -> None:
    # Verify recursive redaction strips secrets while keeping token counts intact
    redacted = redact_trace_payload(
        {
            "api_key": "sk-secret-123",
            "authorization": "Bearer xyz",
            "input_tokens": 120,
            "nested": {"password": "pw", "ok": "visible"},
        }
    )
    assert redacted["api_key"] == "***REDACTED***"
    assert redacted["authorization"] == "***REDACTED***"
    assert redacted["input_tokens"] == 120
    assert redacted["nested"]["password"] == "***REDACTED***"
    assert redacted["nested"]["ok"] == "visible"

    settings = Settings(llm_api_key="", embedding_api_key="", guardrails_enabled=False)
    redis = _FakeRedis()
    reporter = _RecordingReporter()
    manager = RunManager(
        settings=settings,
        redis=redis,  # type: ignore[arg-type]
        llm_client=_FakeLlm(),  # type: ignore[arg-type]
        state_reporter=reporter,  # type: ignore[arg-type]
        enable_mcp=False,
    )

    await manager.start_run(
        RunStartRequest(
            run_id="run-trace-1",
            owner_id="u-1",
            book_id="b-1",
            mode="DIRECT",
            action="CONTINUE",
            book_title="雪夜行",
            instruction="续写雪夜进寺",
        )
    )
    task = manager._active.get("run-trace-1")
    if task and task.task:
        await asyncio.wait_for(task.task, timeout=5.0)

    event_types = [e["event_type"] for e in reporter.persisted_events]
    assert "run_started" in event_types
    assert "usage" in event_types
    assert "done" in event_types
    assert "text_delta" not in event_types
    assert "thought_delta" not in event_types
    # Unified auto-generated trace_id across every persisted event
    trace_ids = {e["trace_id"] for e in reporter.persisted_events}
    assert len(trace_ids) == 1 and "" not in trace_ids
    usage_ev = next(e for e in reporter.persisted_events if e["event_type"] == "usage")
    assert json.loads(usage_ev["payload_json"])["cached_tokens"] == 90
