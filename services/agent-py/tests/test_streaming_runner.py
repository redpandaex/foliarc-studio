import asyncio
import json
from collections.abc import AsyncIterator
from typing import Any

import httpx
import pytest

from foliarc_agent.config import Settings
from foliarc_agent.streaming.llm_client import (
    LlmStreamChunk,
    OpenAICompatibleStreamClient,
    build_novel_messages,
)
from foliarc_agent.streaming.protocol import (
    RunResumeRequest,
    RunStartRequest,
    RunStateCallback,
)
from foliarc_agent.streaming.runner import RunManager, stream_key


class FakeRedis:
    """Minimal in-memory Redis Streams + KV double for unit tests."""

    def __init__(self) -> None:
        self.kv: dict[str, str] = {}
        self.streams: dict[str, list[tuple[str, dict[str, str]]]] = {}
        self._counter = 0

    async def xadd(self, key: str, fields: dict[str, str]) -> str:
        self._counter += 1
        entry_id = f"1700000000000-{self._counter}"
        self.streams.setdefault(key, []).append((entry_id, dict(fields)))
        return entry_id

    async def expire(self, key: str, ttl: int) -> bool:
        return True

    async def set(self, key: str, value: str, ex: int | None = None) -> bool:
        self.kv[key] = value
        return True

    async def get(self, key: str) -> str | None:
        return self.kv.get(key)

    async def delete(self, key: str) -> int:
        return 1 if self.kv.pop(key, None) is not None else 0


class RecordingReporter:
    def __init__(self) -> None:
        self.reports: list[tuple[str, RunStateCallback]] = []

    async def report(self, run_id: str, state: RunStateCallback) -> None:
        self.reports.append((run_id, state))


class ScriptedLlmClient:
    """Streams deterministic chunks with an optional barrier to test cooperative Pause."""

    def __init__(self, segments: list[list[LlmStreamChunk]], pause_after_first_chunk: bool = False):
        self._segments = list(segments)
        self._pause_after_first = pause_after_first_chunk
        self.first_chunk_emitted = asyncio.Event()
        self.received_messages: list[list[dict[str, str]]] = []

    async def stream_chat(
        self,
        *,
        model_alias: str,
        messages: list[dict[str, str]],
        **kwargs: Any,
    ) -> AsyncIterator[LlmStreamChunk]:
        self.received_messages.append(messages)
        chunks = self._segments.pop(0)
        for idx, chunk in enumerate(chunks):
            yield chunk
            if idx == 0 and self._pause_after_first:
                self.first_chunk_emitted.set()
                await asyncio.sleep(5)


@pytest.mark.asyncio
async def test_openai_compatible_sse_parsing_with_reasoning_and_cache_usage() -> None:
    sse_body = (
        ": keep-alive\n\n"
        'data: {"choices":[{"delta":{"reasoning_content":"梳理伏笔"}}]}\n\n'
        'data: {"choices":[{"delta":{"content":"夜雨落青阶，"}}]}\n\n'
        'data: {"choices":[{"delta":{"content":"林青按剑回望。"},"finish_reason":"stop"}],'
        '"usage":{"prompt_tokens":120,"prompt_cache_hit_tokens":80,"completion_tokens":14}}\n\n'
        "data: [DONE]\n\n"
    )

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path.endswith("/chat/completions")
        assert request.headers["Authorization"] == "Bearer sk-test-valid-key"
        return httpx.Response(200, content=sse_body.encode("utf-8"))

    settings = Settings(llm_api_key="sk-test-valid-key", llm_base_url="https://mock.llm/v1")
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http_client:
        client = OpenAICompatibleStreamClient(settings, http_client=http_client)
        chunks = [
            c
            async for c in client.stream_chat(
                model_alias="writer-main",
                messages=build_novel_messages(
                    action="CONTINUE",
                    book_title="星渊剑录",
                    genre="东方玄幻",
                    synopsis=None,
                    chapter_title="第一章",
                    chapter_context="前文",
                    instruction="继续写",
                ),
            )
        ]

    assert [c.reasoning_delta for c in chunks if c.reasoning_delta] == ["梳理伏笔"]
    assert "".join(c.content_delta for c in chunks) == "夜雨落青阶，林青按剑回望。"
    assert chunks[-1].input_tokens == 120
    assert chunks[-1].cached_tokens == 80
    assert chunks[-1].output_tokens == 14


@pytest.mark.asyncio
async def test_cooperative_pause_checkpoint_and_resume_continuation() -> None:
    fake_redis = FakeRedis()
    reporter = RecordingReporter()
    llm = ScriptedLlmClient(
        segments=[
            [
                LlmStreamChunk(content_delta="第一段开头，"),
                LlmStreamChunk(content_delta="不应该在暂停后出现"),
            ],
            [
                LlmStreamChunk(content_delta="顺着修改后的指令继续展开。"),
                LlmStreamChunk(input_tokens=200, cached_tokens=100, output_tokens=30),
            ],
        ],
        pause_after_first_chunk=True,
    )
    manager = RunManager(
        settings=Settings(llm_api_key="sk-test"),
        redis=fake_redis,  # type: ignore[arg-type]
        llm_client=llm,  # type: ignore[arg-type]
        state_reporter=reporter,
    )

    run_id = "run-pause-resume-1"
    await manager.start_run(
        RunStartRequest(
            run_id=run_id,
            action="CONTINUE",
            book_title="星渊剑录",
            chapter_title="第一章",
            instruction="营造紧张感",
        )
    )
    await asyncio.wait_for(llm.first_chunk_emitted.wait(), timeout=2.0)
    await manager.send_control(run_id, "PAUSE")

    # Wait for first segment task to finish cleanly
    await asyncio.sleep(0.05)

    events_after_pause = fake_redis.streams[stream_key(run_id)]
    types_after_pause = [fields["type"] for _, fields in events_after_pause]
    assert types_after_pause == ["run_started", "text_delta", "interrupt"]
    paused_state = reporter.reports[-1][1]
    assert paused_state.status == "PAUSED"
    assert paused_state.accumulated_text == "第一段开头，"
    assert paused_state.last_seq == 3

    # Disable pause barrier for segment 2 and resume with a revised instruction
    llm._pause_after_first = False
    await manager.resume_run(
        RunResumeRequest(
            run_id=run_id,
            action="CONTINUE",
            book_title="星渊剑录",
            chapter_title="第一章",
            instruction="改为突然出现神秘剑客",
            accumulated_text=paused_state.accumulated_text,
            last_seq=paused_state.last_seq,
        )
    )
    await asyncio.sleep(0.05)

    all_events = fake_redis.streams[stream_key(run_id)]
    all_types = [fields["type"] for _, fields in all_events]
    all_seqs = [int(fields["seq"]) for _, fields in all_events]
    assert all_types == [
        "run_started",
        "text_delta",
        "interrupt",
        "run_started",
        "text_delta",
        "usage",
        "done",
    ]
    assert all_seqs == [1, 2, 3, 4, 5, 6, 7]

    # Verify continuation prompt included accumulated assistant text & revised instruction
    resume_msgs = llm.received_messages[1]
    assert any(m["role"] == "assistant" and m["content"] == "第一段开头，" for m in resume_msgs)
    assert "神秘剑客" in resume_msgs[-1]["content"]

    final_state = reporter.reports[-1][1]
    assert final_state.status == "COMPLETED"
    assert final_state.accumulated_text == "第一段开头，顺着修改后的指令继续展开。"
    assert json.loads(all_events[-1][1]["payload"])["status"] == "completed"


def test_direct_build_novel_messages_injects_previous_chapter_continuity() -> None:
    msgs = build_novel_messages(
        action="CONTINUE",
        book_title="星渊剑录",
        genre="东方玄幻",
        synopsis="少年持剑破局",
        chapter_title="第二章 密林遇袭",
        chapter_context="",
        previous_chapters_context="【上一章（第 1 章《启程》）正文衔接】\n林青推开客栈木门，门外赫然立着三名黑衣死士。",
        instruction="",
    )
    user_content = msgs[1]["content"]
    assert "前文章节承接" in user_content
    assert "三名黑衣死士" in user_content
