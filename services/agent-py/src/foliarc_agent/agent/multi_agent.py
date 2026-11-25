from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any, Literal, Protocol

import httpx
from pydantic import BaseModel, Field

from foliarc_agent.agent.mcp_client import McpLoreClient
from foliarc_agent.agent.router import FallbackStreamClient, IntentAndModelRouter
from foliarc_agent.agent.skills import SkillRegistry
from foliarc_agent.config import Settings
from foliarc_agent.rag.retriever import HybridRagRetriever
from foliarc_agent.streaming.llm_client import LlmStreamChunk
from foliarc_agent.streaming.protocol import ControlSignal

logger = logging.getLogger(__name__)

EmitWithAgentFn = Callable[[str, dict[str, Any], str], Awaitable[str]]
CheckControlFn = Callable[[], Awaitable[ControlSignal | None]]


class EditorialBlackboard(BaseModel):
    """
    Shared State (Blackboard Pattern) persisted to PostgreSQL `agent_checkpoints`
    after every Multi-Agent node transition to prevent message-passing distortion
    and enable zero-resource-hold HITL interrupts & deterministic recovery.
    """

    run_id: str
    owner_id: str = ""
    book_id: str = ""
    chapter_no: int | None = None
    section_id: str | None = None
    narrative_order: int | None = None
    precision_mode: str = "AUTO"
    action: str = "CONTINUE"
    book_title: str = ""
    genre: str | None = None
    synopsis: str | None = None
    story_charter: str = ""
    chapter_title: str = ""
    chapter_context: str = ""
    previous_chapters_context: str = ""
    rolling_summary_context: str = ""
    active_state_brief: str = ""
    pov_character: str = ""
    foreshadowing_writer_pack: str = ""
    foreshadowing_critic_pack: str = ""
    critic_guard_brief: str = ""
    director_lore_brief: str = ""
    instruction: str = ""
    requested_model_alias: str = "writer-main"
    require_outline_approval: bool = True

    step_no: int = 0
    current_node: str = "ROUTER"
    route_intent: str = "CHAPTER_COMPOSE"
    route_layer: str = "DEFAULT"
    route_reason: str = ""
    recommended_skill: str | None = None
    model_plan: dict[str, str] = Field(
        default_factory=lambda: {
            "director": "fast-router",
            "lorekeeper": "fast-router",
            "writer": "writer-main",
            "critic": "critic-reasoner",
        }
    )

    outline_text: str = ""
    outline_beats: list[str] = Field(default_factory=list)
    human_approved: bool = False
    human_feedback: str = ""

    lore_brief: str = ""
    rag_citations: list[dict[str, Any]] = Field(default_factory=list)

    draft_text: str = ""
    critic_score: int = 0
    critic_passed: bool = False
    critic_issues: list[str] = Field(default_factory=list)
    critic_guidance: str = ""
    revision_count: int = 0
    max_revisions: int = 2


class CheckpointStore(Protocol):
    async def save(
        self,
        *,
        run_id: str,
        step_no: int,
        node_name: str,
        status: str,
        state: EditorialBlackboard,
    ) -> None: ...

    async def load_latest(self, run_id: str) -> EditorialBlackboard | None: ...


class HttpBizCheckpointStore:
    """Persists and reloads Blackboard Checkpoints via biz-java PostgreSQL endpoints."""

    def __init__(self, settings: Settings) -> None:
        self._base_url = settings.biz_java_base_url.rstrip("/")
        self._token = settings.internal_service_token

    async def save(
        self,
        *,
        run_id: str,
        step_no: int,
        node_name: str,
        status: str,
        state: EditorialBlackboard,
    ) -> None:
        url = f"{self._base_url}/internal/v1/runs/{run_id}/checkpoints"
        headers = {"X-Internal-Token": self._token}
        payload = {
            "step_no": step_no,
            "node_name": node_name,
            "status": status,
            "state_json": state.model_dump_json(),
        }
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.post(url, headers=headers, json=payload)
                if resp.status_code >= 400:
                    logger.warning("Checkpoint save failed for %s step %d: HTTP %s", run_id, step_no, resp.status_code)
        except httpx.HTTPError as exc:
            logger.warning("Could not persist checkpoint for %s: %s", run_id, exc)

    async def load_latest(self, run_id: str) -> EditorialBlackboard | None:
        url = f"{self._base_url}/internal/v1/runs/{run_id}/checkpoint/latest"
        headers = {"X-Internal-Token": self._token}
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.get(url, headers=headers)
                if resp.status_code != 200:
                    return None
                data = resp.json()
                if not data.get("found"):
                    return None
                raw_json = data["checkpoint"]["stateJson"]
                return EditorialBlackboard.model_validate_json(raw_json)
        except (httpx.HTTPError, KeyError, ValueError) as exc:
            logger.warning("Could not load latest checkpoint for %s: %s", run_id, exc)
            return None


class InMemoryCheckpointStore:
    """In-memory CheckpointStore for fast unit tests."""

    def __init__(self) -> None:
        self.records: dict[str, list[tuple[int, str, str, EditorialBlackboard]]] = {}

    async def save(
        self,
        *,
        run_id: str,
        step_no: int,
        node_name: str,
        status: str,
        state: EditorialBlackboard,
    ) -> None:
        snap = EditorialBlackboard.model_validate(state.model_dump())
        self.records.setdefault(run_id, []).append((step_no, node_name, status, snap))

    async def load_latest(self, run_id: str) -> EditorialBlackboard | None:
        items = self.records.get(run_id)
        if not items:
            return None
        return EditorialBlackboard.model_validate(items[-1][3].model_dump())


@dataclass(slots=True)
class MultiAgentResult:
    status: Literal["COMPLETED", "PAUSED", "CANCELLED", "INTERRUPTED"]
    accumulated_text: str
    input_tokens: int
    cached_tokens: int
    output_tokens: int
    current_node: str
    route_intent: str
    revision_count: int
    interrupt_type: str | None = None
    interrupt_payload: dict[str, Any] | None = None


class EditorialTeamOrchestrator:
    """
    Phase 4 Multi-Agent Novel Editorial Pipeline:
      ROUTER -> DIRECTOR -> [HITL OUTLINE_APPROVAL INTERRUPT] -> LOREKEEPER -> WRITER <-> CRITIC (max 2)
    """

    def __init__(
        self,
        *,
        settings: Settings,
        llm_client: FallbackStreamClient,
        router: IntentAndModelRouter,
        checkpoint_store: CheckpointStore,
        skill_registry: SkillRegistry,
        rag_retriever: HybridRagRetriever | None = None,
        enable_mcp: bool = True,
        max_revisions: int = 2,
    ) -> None:
        self._settings = settings
        self._llm = llm_client
        self._router = router
        self._checkpoints = checkpoint_store
        self._skills = skill_registry
        self._rag = rag_retriever
        self._enable_mcp = enable_mcp
        self._max_revisions = max_revisions

    async def run(
        self,
        *,
        initial_state: EditorialBlackboard,
        emit: EmitWithAgentFn,
        control_event: asyncio.Event,
        check_control: CheckControlFn,
        resumed: bool = False,
        approval_payload: dict[str, Any] | None = None,
    ) -> MultiAgentResult:
        state = initial_state
        state.max_revisions = self._max_revisions
        total_in = 0
        total_cached = 0
        total_out = 0

        if resumed:
            restored = await self._checkpoints.load_latest(state.run_id)
            if restored is not None:
                state = restored
                state.max_revisions = self._max_revisions
                if initial_state.instruction and not approval_payload:
                    state.instruction = initial_state.instruction

            if approval_payload is not None:
                approved = bool(approval_payload.get("approved", True))
                edited_outline = str(approval_payload.get("edited_outline") or "").strip()
                feedback = str(approval_payload.get("feedback") or "").strip()
                if edited_outline:
                    state.outline_text = edited_outline
                    state.outline_beats = _extract_beats(edited_outline)
                if feedback:
                    state.human_feedback = feedback
                if approved:
                    state.human_approved = True
                    state.current_node = "LOREKEEPER"
                    await emit(
                        "agent_handoff",
                        {
                            "from_agent": "human",
                            "to_agent": "lorekeeper",
                            "reason": "作者已批准章节大纲，交由设定管家核验设定与前文记忆",
                        },
                        "director",
                    )
                else:
                    state.human_approved = False
                    state.current_node = "DIRECTOR"
                    await emit(
                        "agent_handoff",
                        {
                            "from_agent": "human",
                            "to_agent": "director",
                            "reason": f"作者打回大纲并提出修改意见：{feedback or '请调整节奏与冲突'}",
                        },
                        "director",
                    )

        async def _on_fallback(src: str, dst: str, reason: str) -> None:
            await emit(
                "thought_delta",
                {"content": f"\n[模型容灾降级] {src} 不可用，自动切换至 {dst}（原因：{reason[:120]}）\n"},
                state.current_node.lower(),
            )

        async def _collect_stream(
            *,
            agent: str,
            model_alias: str,
            messages: list[dict[str, Any]],
            temperature: float = 0.7,
            max_tokens: int = 1800,
            stream_as: Literal["text_delta", "thought_delta", "outline_delta"] = "thought_delta",
        ) -> tuple[str, ControlSignal | None]:
            nonlocal total_in, total_cached, total_out
            parts: list[str] = []
            seg_in = 0
            seg_cached = 0
            seg_out = 0
            stream_iter = self._llm.stream_chat(
                model_alias=model_alias,
                messages=messages,
                temperature=temperature,
                max_tokens=max_tokens,
                on_fallback=_on_fallback,
            )
            try:
                while True:
                    ctrl = await check_control()
                    if ctrl is not None:
                        return "".join(parts), ctrl

                    next_task = asyncio.create_task(anext(stream_iter))
                    ctrl_task = asyncio.create_task(control_event.wait())
                    done, pending = await asyncio.wait(
                        {next_task, ctrl_task},
                        return_when=asyncio.FIRST_COMPLETED,
                    )
                    for t in pending:
                        t.cancel()
                        with contextlib.suppress(asyncio.CancelledError):
                            await t

                    if ctrl_task in done and control_event.is_set():
                        return "".join(parts), await check_control()

                    try:
                        chunk: LlmStreamChunk = next_task.result()
                    except StopAsyncIteration:
                        break

                    if chunk.reasoning_delta:
                        await emit("thought_delta", {"content": chunk.reasoning_delta}, agent)
                    if chunk.content_delta:
                        parts.append(chunk.content_delta)
                        if stream_as == "text_delta":
                            await emit("text_delta", {"content": chunk.content_delta}, agent)
                        elif stream_as == "outline_delta":
                            await emit(
                                "thought_delta",
                                {"content": chunk.content_delta},
                                agent,
                            )
                    if chunk.input_tokens is not None:
                        seg_in = chunk.input_tokens
                    if chunk.cached_tokens is not None:
                        seg_cached = chunk.cached_tokens
                    if chunk.output_tokens is not None:
                        seg_out = chunk.output_tokens
            finally:
                with contextlib.suppress(Exception):
                    await stream_iter.aclose()

            total_in += seg_in
            total_cached += seg_cached
            total_out += seg_out
            return "".join(parts), await check_control()

        # =====================================================================
        # NODE 0: ROUTER (Multi-layer Intent + Model Routing)
        # =====================================================================
        if state.current_node == "ROUTER":
            decision = await self._router.route(
                action=state.action,
                instruction=state.instruction,
                chapter_context=state.chapter_context,
                requested_model_alias=state.requested_model_alias,
            )
            state.route_intent = decision.intent
            state.route_layer = decision.layer
            state.route_reason = decision.reason
            state.recommended_skill = decision.recommended_skill
            state.model_plan = decision.model_plan.to_dict()
            state.step_no += 1
            await self._checkpoints.save(
                run_id=state.run_id,
                step_no=state.step_no,
                node_name="ROUTER",
                status="COMPLETED",
                state=state,
            )
            await emit("route_decision", decision.to_dict(), "router")
            await emit(
                "agent_handoff",
                {
                    "from_agent": "router",
                    "to_agent": "director",
                    "reason": f"意图={decision.intent}（{decision.layer}），移交总编规划章节节拍大纲",
                },
                "router",
            )
            state.current_node = "DIRECTOR"

        # =====================================================================
        # NODE 1: DIRECTOR (Beat Sheet Planner + HITL Interrupt)
        # =====================================================================
        if state.current_node == "DIRECTOR":
            await self._hydrate_director_continuity(state)
            director_msgs = self._build_director_messages(state)
            outline_text, ctrl = await _collect_stream(
                agent="director",
                model_alias=state.model_plan.get("director", "fast-router"),
                messages=director_msgs,
                temperature=0.6,
                max_tokens=900,
                stream_as="outline_delta",
            )
            if ctrl in ("PAUSE", "CANCEL"):
                return self._make_result(
                    "PAUSED" if ctrl == "PAUSE" else "CANCELLED",
                    state,
                    total_in,
                    total_cached,
                    total_out,
                )

            state.outline_text = outline_text.strip()
            state.outline_beats = _extract_beats(state.outline_text)
            state.step_no += 1

            if state.require_outline_approval and not state.human_approved:
                await self._checkpoints.save(
                    run_id=state.run_id,
                    step_no=state.step_no,
                    node_name="DIRECTOR",
                    status="INTERRUPTED",
                    state=state,
                )
                interrupt_payload = {
                    "interrupt_type": "OUTLINE_APPROVAL",
                    "checkpoint_step": state.step_no,
                    "node": "DIRECTOR",
                    "route_intent": state.route_intent,
                    "recommended_skill": state.recommended_skill,
                    "outline_text": state.outline_text,
                    "beats": state.outline_beats,
                    "message": "总编已完成本章场景节拍大纲，请审阅/微调后点击「批准并开始执笔」或打回重拟。",
                }
                return MultiAgentResult(
                    status="INTERRUPTED",
                    accumulated_text=state.draft_text,
                    input_tokens=total_in,
                    cached_tokens=total_cached,
                    output_tokens=total_out,
                    current_node="DIRECTOR",
                    route_intent=state.route_intent,
                    revision_count=state.revision_count,
                    interrupt_type="OUTLINE_APPROVAL",
                    interrupt_payload=interrupt_payload,
                )

            await self._checkpoints.save(
                run_id=state.run_id,
                step_no=state.step_no,
                node_name="DIRECTOR",
                status="COMPLETED",
                state=state,
            )
            await emit(
                "agent_handoff",
                {
                    "from_agent": "director",
                    "to_agent": "lorekeeper",
                    "reason": "章节大纲规划完毕，移交设定管家核验设定与前文记忆",
                },
                "director",
            )
            state.current_node = "LOREKEEPER"

        # =====================================================================
        # NODE 2: LOREKEEPER (MCP Lore + Milvus Hybrid RAG + Structured Memory)
        # =====================================================================
        if state.current_node == "LOREKEEPER":
            ctrl = await check_control()
            if ctrl in ("PAUSE", "CANCEL"):
                return self._make_result("PAUSED" if ctrl == "PAUSE" else "CANCELLED", state, total_in, total_cached, total_out)

            lore_brief, citations = await self._run_lorekeeper(state, emit)
            state.lore_brief = lore_brief
            state.rag_citations = citations
            state.step_no += 1
            await self._checkpoints.save(
                run_id=state.run_id,
                step_no=state.step_no,
                node_name="LOREKEEPER",
                status="COMPLETED",
                state=state,
            )
            await emit(
                "agent_handoff",
                {
                    "from_agent": "lorekeeper",
                    "to_agent": "writer",
                    "reason": f"设定核验完成（命中 {len(citations)} 条知识库引用），移交执笔作家撰写正文",
                },
                "lorekeeper",
            )
            state.current_node = "WRITER"

        # =====================================================================
        # NODE 3 & 4: WRITER <-> CONTINUITY CRITIC (Reflection Loop, max 2)
        # =====================================================================
        while state.current_node in ("WRITER", "CRITIC"):
            if state.current_node == "WRITER":
                if state.revision_count > 0:
                    await emit(
                        "draft_reset",
                        {
                            "revision": state.revision_count,
                            "reason": f"审稿编辑打回（得分 {state.critic_score}/100），开始第 {state.revision_count} 次返工重写",
                        },
                        "writer",
                    )

                skill_body = ""
                if state.recommended_skill:
                    spec = self._skills.load_skill(state.recommended_skill)
                    if spec is not None:
                        skill_body = spec.body
                        await emit(
                            "tool_call",
                            {
                                "call_id": f"skill-{state.step_no}",
                                "tool": "load_writing_skill",
                                "source": "skill",
                                "arguments": {"skill_name": spec.name},
                            },
                            "writer",
                        )
                        await emit(
                            "tool_result",
                            {
                                "call_id": f"skill-{state.step_no}",
                                "tool": "load_writing_skill",
                                "source": "skill",
                                "success": True,
                                "latency_ms": 1,
                                "summary": f"已挂载写作技能包: {spec.name}",
                            },
                            "writer",
                        )

                writer_msgs = self._build_writer_messages(state, skill_body)
                draft_text, ctrl = await _collect_stream(
                    agent="writer",
                    model_alias=state.model_plan.get("writer", "writer-main"),
                    messages=writer_msgs,
                    temperature=0.8 if state.revision_count == 0 else 0.65,
                    max_tokens=2400,
                    stream_as="text_delta",
                )
                state.draft_text = draft_text
                if ctrl in ("PAUSE", "CANCEL"):
                    return self._make_result(
                        "PAUSED" if ctrl == "PAUSE" else "CANCELLED",
                        state,
                        total_in,
                        total_cached,
                        total_out,
                    )

                state.step_no += 1
                await self._checkpoints.save(
                    run_id=state.run_id,
                    step_no=state.step_no,
                    node_name="WRITER",
                    status="COMPLETED",
                    state=state,
                )
                await emit(
                    "agent_handoff",
                    {
                        "from_agent": "writer",
                        "to_agent": "critic",
                        "reason": f"第 {state.revision_count + 1} 稿正文完成（{len(state.draft_text)} 字），移交审稿编辑核查设定一致性",
                    },
                    "writer",
                )
                state.current_node = "CRITIC"

            if state.current_node == "CRITIC":
                critic_msgs = self._build_critic_messages(state)
                raw_review, ctrl = await _collect_stream(
                    agent="critic",
                    model_alias=state.model_plan.get("critic", "critic-reasoner"),
                    messages=critic_msgs,
                    temperature=0.1,
                    max_tokens=700,
                    stream_as="thought_delta",
                )
                if ctrl in ("PAUSE", "CANCEL"):
                    return self._make_result(
                        "PAUSED" if ctrl == "PAUSE" else "CANCELLED",
                        state,
                        total_in,
                        total_cached,
                        total_out,
                    )

                parsed_review = _parse_critic_json(raw_review)
                state.critic_score = int(parsed_review["score"])
                state.critic_passed = bool(parsed_review["passed"])
                state.critic_issues = list(parsed_review["issues"])
                state.critic_guidance = str(parsed_review["revision_guidance"])

                state.step_no += 1
                await self._checkpoints.save(
                    run_id=state.run_id,
                    step_no=state.step_no,
                    node_name="CRITIC",
                    status="COMPLETED",
                    state=state,
                )
                await emit(
                    "critic_review",
                    {
                        "passed": state.critic_passed,
                        "score": state.critic_score,
                        "issues": state.critic_issues,
                        "revision_guidance": state.critic_guidance,
                        "revision_count": state.revision_count,
                        "max_revisions": state.max_revisions,
                    },
                    "critic",
                )

                if not state.critic_passed and state.revision_count < state.max_revisions:
                    state.revision_count += 1
                    await emit(
                        "agent_handoff",
                        {
                            "from_agent": "critic",
                            "to_agent": "writer",
                            "reason": (
                                f"审稿未通过（{state.critic_score}/100），打回执笔作家进行第 "
                                f"{state.revision_count}/{state.max_revisions} 次返工"
                            ),
                        },
                        "critic",
                    )
                    state.current_node = "WRITER"
                else:
                    state.current_node = "DONE"
                    break

        return self._make_result("COMPLETED", state, total_in, total_cached, total_out)

    async def _hydrate_director_continuity(self, state: EditorialBlackboard) -> None:
        """
        Ensures DIRECTOR always sees the preceding chapter's ending and core story bible
        (characters + open foreshadowings) BEFORE planning the new chapter's beat sheet,
        preventing disconnected Chapter 2+ outlines.
        """
        if not state.owner_id or not state.book_id:
            return
        headers = {
            "X-Internal-Token": self._settings.internal_service_token,
            "X-Owner-Id": state.owner_id,
        }
        base = self._settings.biz_java_base_url.rstrip("/")
        async with httpx.AsyncClient(timeout=5.0) as client:
            if not state.previous_chapters_context.strip() and state.chapter_no and state.chapter_no > 1:
                try:
                    resp = await client.get(
                        f"{base}/internal/v1/books/{state.book_id}/lore/chapters/{state.chapter_no - 1}",
                        headers=headers,
                        params={"maxChars": 3200},
                    )
                    if resp.status_code == 200:
                        data = resp.json()
                        excerpt = str(data.get("excerpt") or "").strip()
                        if excerpt:
                            prev_title = str(data.get("title") or "")
                            state.previous_chapters_context = (
                                f"【上一章（第 {state.chapter_no - 1} 章《{prev_title}》）正文衔接】\n{excerpt}"
                            )
                except (httpx.HTTPError, ValueError, KeyError):
                    pass

            if not state.director_lore_brief.strip():
                try:
                    resp = await client.get(
                        f"{base}/internal/v1/books/{state.book_id}/lore/bible",
                        headers=headers,
                    )
                    if resp.status_code == 200:
                        bible = resp.json()
                        lines: list[str] = []
                        chars = bible.get("characters") or []
                        if chars:
                            char_items = [
                                f"- {c.get('name', '')}（{c.get('realmOrLevel') or c.get('roleType', '')}"
                                f"{'，位置：' + c['currentLocation'] if c.get('currentLocation') else ''}"
                                f"{'，持有：' + c['inventory'] if c.get('inventory') else ''}"
                                f"{'，状态：' + c['statusNotes'] if c.get('statusNotes') else ''}）"
                                for c in chars[:10]
                            ]
                            lines.append("主要人物：\n" + "\n".join(char_items))
                        clues = bible.get("openForeshadowings") or []
                        if clues:
                            clue_items = [
                                f"- {f.get('title', '')}：{str(f.get('description') or '')[:120]}"
                                for f in clues[:8]
                            ]
                            lines.append("未回收伏笔：\n" + "\n".join(clue_items))
                        if lines:
                            state.director_lore_brief = "\n".join(lines)[:1800]
                except (httpx.HTTPError, ValueError, KeyError):
                    pass

    async def _run_lorekeeper(
        self,
        state: EditorialBlackboard,
        emit: EmitWithAgentFn,
    ) -> tuple[str, list[dict[str, Any]]]:
        """Collects grounded facts from MCP Lore Server, Milvus Hybrid RAG, and Structured Memory."""
        brief_parts: list[str] = []
        citations: list[dict[str, Any]] = []
        prev_tail = state.previous_chapters_context.strip()[-350:] if state.previous_chapters_context else ""
        query_seed = f"{state.chapter_title} {state.instruction} {state.outline_text[:300]} {prev_tail}".strip()

        # 1. MCP Novel Lore Bible
        if self._enable_mcp and state.owner_id and state.book_id:
            await emit(
                "tool_call",
                {
                    "call_id": "lore-mcp-bible",
                    "tool": "novel-lore-mcp::read_bible_resource",
                    "source": "mcp",
                    "arguments": {"book_id": state.book_id},
                },
                "lorekeeper",
            )
            try:
                async with McpLoreClient(
                    biz_java_url=self._settings.biz_java_base_url,
                    internal_token=self._settings.internal_service_token,
                    owner_id=state.owner_id,
                    book_id=state.book_id,
                ) as mcp:
                    bible_text = await mcp.read_bible_resource()
                if bible_text.strip():
                    brief_parts.append(f"【MCP 设定圣经】\n{bible_text[:2200]}")
                await emit(
                    "tool_result",
                    {
                        "call_id": "lore-mcp-bible",
                        "tool": "novel-lore-mcp::read_bible_resource",
                        "source": "mcp",
                        "success": True,
                        "latency_ms": 50,
                        "summary": f"已加载作品设定圣经（{len(bible_text)} 字符）",
                    },
                    "lorekeeper",
                )
            except (OSError, RuntimeError, ValueError, TimeoutError) as exc:
                await emit(
                    "tool_result",
                    {
                        "call_id": "lore-mcp-bible",
                        "tool": "novel-lore-mcp::read_bible_resource",
                        "source": "mcp",
                        "success": False,
                        "latency_ms": 10,
                        "summary": f"MCP 设定库暂不可用: {str(exc)[:80]}",
                    },
                    "lorekeeper",
                )

        # 2. Milvus Hybrid RAG (with anti-spoiler filter chapter_no <= current_chapter_no)
        if self._rag is not None and state.owner_id and state.book_id and query_seed:
            await emit(
                "tool_call",
                {
                    "call_id": "lore-rag-search",
                    "tool": "search_knowledge_base",
                    "source": "rag",
                    "arguments": {
                        "query": query_seed[:120],
                        "max_chapter_no": state.chapter_no,
                    },
                },
                "lorekeeper",
            )
            try:
                rag_res = await self._rag.search(
                    owner_id=state.owner_id,
                    book_id=state.book_id,
                    query=query_seed[:300],
                    top_k=4,
                    max_chapter_no=state.chapter_no,
                    max_narrative_order=state.narrative_order,
                    precision_mode=state.precision_mode,
                    rewrite_query=False,
                )
                hits = rag_res.get("hits", [])
                citations = [
                    {
                        "document_title": h.get("document_title", ""),
                        "scene_title": h.get("scene_title", ""),
                        "chapter_no": h.get("chapter_no", 0),
                        "scope": h.get("scope", "BOOK"),
                        "score": h.get("score", 0.0),
                    }
                    for h in hits
                ]
                if hits:
                    scope_labels = {
                        "BOOK": "本书",
                        "USER": "作者私库",
                        "ASSIGNED": "定向资料",
                        "GLOBAL": "公共参考",
                    }
                    rag_lines = [
                        f"- [{scope_labels.get(str(h.get('scope', 'BOOK')).upper(), '本书')} · {h.get('document_title')} · Ch.{h.get('chapter_no', 0)}] {h.get('parent_context', '')[:450]}"
                        for h in hits
                    ]
                    brief_parts.append(
                        "【多作用域混合检索参考（冲突优先级：本书 > 作者私库 > 定向资料 > 公共参考）】\n"
                        + "\n".join(rag_lines)
                    )
                await emit(
                    "tool_result",
                    {
                        "call_id": "lore-rag-search",
                        "tool": "search_knowledge_base",
                        "source": "rag",
                        "success": True,
                        "latency_ms": 40,
                        "summary": f"混合召回 {len(hits)} 条设定/前文父块（防剧透 <= 第{state.chapter_no or '∞'}章）",
                    },
                    "lorekeeper",
                )
            except (httpx.HTTPError, RuntimeError, ValueError, OSError) as exc:
                await emit(
                    "tool_result",
                    {
                        "call_id": "lore-rag-search",
                        "tool": "search_knowledge_base",
                        "source": "rag",
                        "success": False,
                        "latency_ms": 10,
                        "summary": f"知识库检索降级跳过: {str(exc)[:80]}",
                    },
                    "lorekeeper",
                )

        # 3. Structured Long-Term Memory (Character relations + Timeline)
        if state.owner_id and state.book_id:
            await emit(
                "tool_call",
                {
                    "call_id": "lore-structured-mem",
                    "tool": "get_structured_memory",
                    "source": "memory",
                    "arguments": {"max_chapter_no": state.chapter_no},
                },
                "lorekeeper",
            )
            try:
                url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/rag/books/{state.book_id}/memory"
                params = {"maxChapterNo": state.chapter_no} if state.chapter_no is not None else {}
                async with httpx.AsyncClient(timeout=8.0) as client:
                    resp = await client.get(
                        url,
                        headers={
                            "X-Internal-Token": self._settings.internal_service_token,
                            "X-Owner-Id": state.owner_id,
                        },
                        params=params,
                    )
                    if resp.status_code == 200:
                        mem = resp.json()
                        rel_lines = [
                            f"- {r['sourceCharacter']} -[{r['relationType']}]-> {r['targetCharacter']}: {r.get('description', '')}"
                            for r in mem.get("relations", [])[:8]
                        ]
                        tl_lines = [
                            f"- 第{t['chapterNo']}章 {t['eventTitle']}: {t.get('summary', '')}"
                            for t in mem.get("timelineEvents", [])[-8:]
                        ]
                        if rel_lines:
                            brief_parts.append("【人物关系网】\n" + "\n".join(rel_lines))
                        if tl_lines:
                            brief_parts.append("【前文事件时间线】\n" + "\n".join(tl_lines))
                        await emit(
                            "tool_result",
                            {
                                "call_id": "lore-structured-mem",
                                "tool": "get_structured_memory",
                                "source": "memory",
                                "success": True,
                                "latency_ms": 20,
                                "summary": f"已加载 {len(rel_lines)} 条关系、{len(tl_lines)} 条时间线记忆",
                            },
                            "lorekeeper",
                        )
            except (httpx.HTTPError, KeyError, ValueError) as exc:
                await emit(
                    "tool_result",
                    {
                        "call_id": "lore-structured-mem",
                        "tool": "get_structured_memory",
                        "source": "memory",
                        "success": False,
                        "latency_ms": 10,
                        "summary": f"结构化记忆查询跳过: {str(exc)[:80]}",
                    },
                    "lorekeeper",
                )

        return "\n\n".join(brief_parts) if brief_parts else "（本书暂未录入额外设定或历史记忆）", citations

    @staticmethod
    def _build_director_messages(state: EditorialBlackboard) -> list[dict[str, str]]:
        sys_prompt = (
            "你是 FoliarcStudio 编辑部的【Director 总编/剧情规划师】。\n"
            "请为当前小说章节设计一份清晰、可执行、充满张力的【场景节拍大纲（Beat Sheet）】。\n"
            "输出要求：\n"
            "1. 使用 3~4 条编号列表（1. 2. 3. 4.），每条写明【场景地点】【核心冲突/转折】【人物动机】【伏笔或结尾钩子】。\n"
            "2. 跨章连贯性是首要铁律：如果提供了【前文章节承接】，第 1 个节拍必须直接紧接上一章结尾的时间、地点、人物状态与未解悬念，沿用前文已出场的人物与冲突，严禁另起炉灶或重新开篇。\n"
            "3. 不写正文，只输出精炼可编辑的大纲（300~500 字）。"
        )
        user_parts = [f"作品：《{state.book_title}》"]
        if state.genre:
            user_parts.append(f"题材：{state.genre}")
        if state.story_charter.strip():
            user_parts.append(f"【作品故事宪章（L0 静态底座）】\n{state.story_charter.strip()[:1200]}")
        elif state.synopsis:
            user_parts.append(f"简介：{state.synopsis}")
        if state.chapter_no or state.chapter_title:
            user_parts.append(f"目标章节：第 {state.chapter_no or '?'} 章 {state.chapter_title}")
        if state.rolling_summary_context.strip():
            user_parts.append(f"【前文阶段脉络与近期章摘（L2）】\n{state.rolling_summary_context.strip()[:4200]}")
        if state.active_state_brief.strip():
            user_parts.append(f"【当前活跃人物与待回收伏笔（L3）】\n{state.active_state_brief.strip()[:1800]}")
        elif state.director_lore_brief.strip():
            user_parts.append(f"【作品核心设定与伏笔】\n{state.director_lore_brief.strip()}")
        if state.pov_character.strip():
            user_parts.append(
                f"【本章指定叙事视角（POV）】{state.pov_character.strip()}（安排场景节拍时须严格遵守该角色的认知边界，严禁读心术）"
            )
        if state.foreshadowing_writer_pack.strip():
            user_parts.append(
                "【本章强制伏笔导演包（最高优先级：必须为每条 PLANT/HINT/PAYOFF 分配明确场景节拍；PLANT 须遵守冰山原则自然藏入动作/物件，严禁暴露隐藏底牌）】\n"
                + state.foreshadowing_writer_pack.strip()[:2200]
            )
        if state.previous_chapters_context.strip():
            user_parts.append(
                f"【前文章节承接（L1 热窗口，必须紧密衔接上一章结尾，严禁脱节）】\n{state.previous_chapters_context.strip()[-2800:]}"
            )
        if state.chapter_context.strip():
            user_parts.append(f"【当前章节已有正文尾部】\n{state.chapter_context[-2500:]}")
        user_parts.append(
            f"【作者指令】\n{state.instruction or '请紧承上一章结尾自然推进本章节冲突，延续已出场人物线索并设计结尾钩子'}"
        )
        if state.human_feedback:
            user_parts.append(f"【作者针对上一版大纲的打回修改意见（务必落实）】\n{state.human_feedback}")
        return [
            {"role": "system", "content": sys_prompt},
            {"role": "user", "content": "\n\n".join(user_parts)},
        ]

    @staticmethod
    def _build_writer_messages(state: EditorialBlackboard, skill_body: str) -> list[dict[str, str]]:
        static_prefix = [
            "你是 FoliarcStudio 编辑部的【Writer 执笔作家】。",
            "请严格按照总编拟定并经作者批准的【章节大纲】、【前文章节承接】与设定管家提供的【设定核验简报】撰写高质量中文小说正文。",
            "跨章连贯铁律：必须紧接上一章的结尾场景、时间线、人物状态、对话余韵与未解悬念自然展开，禁止另起炉灶、凭空切换无关场景或重复介绍前文已交代的背景。",
            "参考资料冲突优先级：本书已写正文与本书设定（BOOK） > 作者个人资料库（USER） > 平台定向资料（ASSIGNED） > 平台公共资料（GLOBAL）。发生冲突时一律以本书原创设定为准。",
            "伏笔与视角铁律：严格执行【本章伏笔导演包】中的埋入/照应/启用指令；对标有【严禁提前点破真相】的潜伏伏笔只写表面现象绝不挑破底牌；严格遵守 POV 视角边界，绝不允许当前视角角色凭空知晓【幕后客观事实】。",
            "严禁出现人设 OOC、战力境界矛盾、时间线错乱或“吃书”现象；直接输出小说正文，不要输出任何大纲标题或元说明。",
        ]
        if state.book_title:
            static_prefix.append(f"当前作品：《{state.book_title}》（{state.genre or '长篇小说'}）")
        # L0 Static Charter pinned in immutable System Prefix to maximize KV Cache hits
        if state.story_charter.strip():
            static_prefix.append(f"【作品故事宪章（L0 静态底座）】\n{state.story_charter.strip()[:1200]}")
        elif state.synopsis:
            static_prefix.append(f"【故事简介】\n{state.synopsis}")
        if skill_body:
            static_prefix.append(f"【已挂载专项写作技能规范】\n{skill_body[:2500]}")

        user_parts = [
            f"【作者批准的章节节拍大纲】\n{state.outline_text}",
            f"【设定管家核验简报（L4 强约束）】\n{state.lore_brief[:3000]}",
        ]
        if state.rolling_summary_context.strip():
            user_parts.append(f"【前文阶段脉络与近期章摘（L2）】\n{state.rolling_summary_context.strip()[:4200]}")
        if state.active_state_brief.strip():
            user_parts.append(f"【当前活跃人物状态与待回收伏笔（L3）】\n{state.active_state_brief.strip()[:1800]}")
        if state.pov_character.strip():
            user_parts.append(
                f"【本章叙事视角（POV）】{state.pov_character.strip()}（仅可描写该角色亲见/亲闻/已知的信息，严禁越权读心）"
            )
        if state.foreshadowing_writer_pack.strip():
            user_parts.append(
                f"【本章伏笔导演包（只含允许落笔信息，严禁提前泄底）】\n{state.foreshadowing_writer_pack.strip()[:2400]}"
            )
        if state.previous_chapters_context.strip():
            user_parts.append(
                f"【上一章及前文衔接（L1 热窗口，必须紧接其结尾展开，严禁割裂）】\n{state.previous_chapters_context.strip()[-2800:]}"
            )
        if state.chapter_context.strip():
            user_parts.append(f"【当前章节已有正文尾部】\n{state.chapter_context[-2000:]}")
        if state.instruction.strip():
            user_parts.append(f"【作者补充要求】\n{state.instruction.strip()}")
        if state.revision_count > 0 and state.critic_guidance:
            user_parts.append(
                f"【审稿编辑打回返工意见（第 {state.revision_count} 次重写，务必逐条修复）】\n"
                f"上稿得分：{state.critic_score}/100\n"
                f"问题清单：{'; '.join(state.critic_issues)}\n"
                f"修改建议：{state.critic_guidance}\n\n"
                f"【上一稿待修订正文】\n{state.draft_text[:3000]}"
            )
        return [
            {"role": "system", "content": "\n\n".join(static_prefix)},
            {"role": "user", "content": "\n\n".join(user_parts)},
        ]

    @staticmethod
    def _build_critic_messages(state: EditorialBlackboard) -> list[dict[str, str]]:
        sys_prompt = (
            "你是 FoliarcStudio 编辑部的【ContinuityCritic 审稿与一致性审查编辑】。\n"
            "请对照【批准大纲】、【作品故事宪章】、【当前活跃状态】、【上一章结尾参考】与【设定核验简报】，严格审阅【执笔作家草稿】是否存在：\n"
            "1. 人物 OOC（性格/称谓/立场崩塌）\n"
            "2. 战力境界、持有物品或地点时间线矛盾（吃书）\n"
            "3. 遗漏大纲核心节拍或文风质量明显不足\n"
            "4. 跨章衔接断层：若提供了【上一章结尾参考】，本章必须自然承接上一章末尾的剧情、人物状态与悬念；若另起炉灶、与上一章关联薄弱或重复前文，必须扣分并打回重写\n"
            "5. 伏笔导演与防剧透红线：检查本章指定的 PLANT 是否自然埋入、PAYOFF 是否与当年埋设原文零矛盾，以及是否存在把【严禁提前点破真相】的潜伏底牌意外剧透的严重失误\n"
            "6. 死亡墓碑与视角红线：检查是否出现已亡角色无故复活，或当前 POV 视角角色凭空知晓【幕后客观事实】（读心术/视角越权）\n"
            '请仅输出严格 JSON 对象：{"passed": true/false, "score": 0-100, "issues": ["问题1"], "revision_guidance": "具体修改指令"}'
            "\n评分 >= 78 且无硬伤时 passed 为 true；否则 passed 为 false 并给出可执行修改意见。"
        )
        user_sections = [
            f"【批准大纲】\n{state.outline_text[:1500]}",
        ]
        if state.story_charter.strip():
            user_sections.append(f"【作品故事宪章】\n{state.story_charter.strip()[:800]}")
        if state.active_state_brief.strip():
            user_sections.append(f"【当前活跃状态与伏笔】\n{state.active_state_brief.strip()[:1500]}")
        if state.foreshadowing_critic_pack.strip():
            user_sections.append(
                f"【伏笔导演与隐藏底牌核验包（含潜伏真相防提前剧透审查 + 当年原文锚点）】\n{state.foreshadowing_critic_pack.strip()[:2400]}"
            )
        if state.critic_guard_brief.strip():
            user_sections.append(
                f"【连续性硬红线（已亡角色墓碑 / POV 视角约束）】\n{state.critic_guard_brief.strip()[:1000]}"
            )
        if state.previous_chapters_context.strip():
            user_sections.append(f"【上一章结尾参考】\n{state.previous_chapters_context.strip()[-1800:]}")
        user_sections.append(f"【设定核验简报】\n{state.lore_brief[:2500]}")
        user_sections.append(f"【待审稿正文（第 {state.revision_count + 1} 稿）】\n{state.draft_text[:4500]}")
        return [
            {"role": "system", "content": sys_prompt},
            {"role": "user", "content": "\n\n".join(user_sections)},
        ]

    @staticmethod
    def _make_result(
        status: Literal["COMPLETED", "PAUSED", "CANCELLED", "INTERRUPTED"],
        state: EditorialBlackboard,
        in_tok: int,
        cached_tok: int,
        out_tok: int,
    ) -> MultiAgentResult:
        return MultiAgentResult(
            status=status,
            accumulated_text=state.draft_text,
            input_tokens=in_tok,
            cached_tokens=cached_tok,
            output_tokens=out_tok,
            current_node=state.current_node,
            route_intent=state.route_intent,
            revision_count=state.revision_count,
        )


def _extract_beats(outline_text: str) -> list[str]:
    beats: list[str] = []
    for raw in outline_text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line[0].isdigit() or line.startswith(("-", "*", "•", "第")):
            beats.append(line.lstrip("-*• ").strip())
    return beats[:8] if beats else ([outline_text[:200]] if outline_text.strip() else [])


def _parse_critic_json(raw: str) -> dict[str, Any]:
    start = raw.find("{")
    end = raw.rfind("}")
    if start >= 0 and end > start:
        try:
            obj = json.loads(raw[start : end + 1])
            score = max(0, min(100, int(obj.get("score", 82))))
            passed = bool(obj.get("passed", score >= 78))
            issues = [str(x).strip() for x in (obj.get("issues") or []) if str(x).strip()]
            guidance = str(obj.get("revision_guidance") or "").strip()
            return {
                "passed": passed,
                "score": score,
                "issues": issues[:6],
                "revision_guidance": guidance,
            }
        except (ValueError, TypeError, json.JSONDecodeError):
            pass
    return {
        "passed": True,
        "score": 82,
        "issues": [],
        "revision_guidance": "",
    }
