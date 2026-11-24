from __future__ import annotations

import asyncio
import json
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any, Literal

import httpx
from pydantic import BaseModel, Field, ValidationError

from foliarc_agent.agent.mcp_client import McpLoreClient
from foliarc_agent.agent.sandbox import SandboxProvider, StubSandboxProvider
from foliarc_agent.agent.skills import SkillRegistry
from foliarc_agent.rag.retriever import HybridRagRetriever

ToolSource = Literal["builtin", "skill", "mcp", "sandbox", "rag", "memory"]


@dataclass(slots=True)
class ToolDefinition:
    name: str
    description: str
    parameters_schema: dict[str, Any]
    source: ToolSource
    handler: Callable[[dict[str, Any]], Awaitable[str]]
    arg_model: type[BaseModel] | None = None
    timeout_seconds: float = 15.0
    max_retries: int = 1


@dataclass(frozen=True, slots=True)
class ToolExecutionResult:
    call_id: str
    tool_name: str
    source: ToolSource
    success: bool
    output: str
    summary: str
    latency_ms: int


class LoadSkillArgs(BaseModel):
    skill_name: str = Field(..., description="技能包名称，如 combat-scene、foreshadowing-craft、ancient-dialogue、plot-unblock")


class ReadChapterExcerptArgs(BaseModel):
    chapter_no: int = Field(..., ge=1, description="要查阅的章节序号（正整数）")
    max_chars: int = Field(default=1600, ge=200, le=4000, description="返回正文尾部最大字符数")


class UpdateForeshadowingArgs(BaseModel):
    title: str = Field(..., min_length=1, max_length=160, description="伏笔标题")
    description: str = Field(default="", max_length=4000, description="伏笔详细说明与触发条件")
    planted_chapter_no: int | None = Field(default=None, ge=1, description="埋设章节序号")
    resolved_chapter_no: int | None = Field(default=None, ge=1, description="回收章节序号")
    status: Literal["OPEN", "RESOLVED", "ABANDONED"] = Field(default="OPEN", description="伏笔状态")


class RunSandboxScriptArgs(BaseModel):
    purpose: str = Field(..., min_length=1, max_length=200, description="执行该脚本的业务目的，如统计人物出场或校验战力数值")
    code: str = Field(..., min_length=1, max_length=8000, description="待在隔离沙箱中执行的 Python 代码")


class SearchKnowledgeBaseArgs(BaseModel):
    query: str = Field(..., min_length=1, max_length=500, description="检索词或问题（如角色法宝、宗门规则、前文场景）")
    top_k: int = Field(default=4, ge=1, le=10, description="返回最相关的父文档片段数量")
    doc_type: Literal["WORLDBOOK", "CHAPTER", "STYLE_REF", "OUTLINE"] | None = Field(
        default=None, description="可选按资料类型过滤"
    )
    rewrite_query: bool = Field(default=False, description="若包含代词或模糊描述，开启多查询改写")


class GetStructuredMemoryArgs(BaseModel):
    max_chapter_no: int | None = Field(
        default=None, ge=0, description="防剧透章节上限，默认使用当前创作章节号"
    )


class ToolRegistry:
    """
    Unified tool registry supporting:
    - Pydantic strong-typed schemas & OpenAI function-calling export
    - Dynamic MCP tool registration (`source='mcp'`)
    - Parallel tool call execution with timeout & retry
    - Structured error self-healing (returns repair hints instead of crashing the loop)
    """

    def __init__(self) -> None:
        self._tools: dict[str, ToolDefinition] = {}

    def register(self, tool: ToolDefinition) -> None:
        self._tools[tool.name] = tool

    def get(self, name: str) -> ToolDefinition | None:
        return self._tools.get(name)

    def tool_names(self) -> list[str]:
        return list(self._tools.keys())

    def to_openai_tools(self) -> list[dict[str, Any]]:
        return [
            {
                "type": "function",
                "function": {
                    "name": t.name,
                    "description": t.description,
                    "parameters": t.parameters_schema,
                },
            }
            for t in self._tools.values()
        ]

    async def register_mcp_tools(self, mcp_client: McpLoreClient) -> list[str]:
        discovered = await mcp_client.list_tools()
        names: list[str] = []
        for tool in discovered:

            async def _invoke(args: dict[str, Any], _name: str = tool.name) -> str:
                return await mcp_client.call_tool(_name, args)

            self.register(
                ToolDefinition(
                    name=tool.name,
                    description=tool.description,
                    parameters_schema=tool.input_schema,
                    source="mcp",
                    handler=_invoke,
                    timeout_seconds=15.0,
                    max_retries=1,
                )
            )
            names.append(tool.name)
        return names

    async def execute_one(
        self,
        *,
        call_id: str,
        tool_name: str,
        raw_arguments: str | dict[str, Any],
    ) -> ToolExecutionResult:
        started = time.perf_counter()
        tool = self._tools.get(tool_name)
        if tool is None:
            available = ", ".join(self._tools.keys())
            msg = json.dumps(
                {
                    "ok": False,
                    "error_type": "UNKNOWN_TOOL",
                    "message": f"未找到工具 `{tool_name}`",
                    "hint": f"请从可用工具列表中选择: {available}",
                },
                ensure_ascii=False,
            )
            return ToolExecutionResult(
                call_id=call_id,
                tool_name=tool_name,
                source="builtin",
                success=False,
                output=msg,
                summary=f"工具 `{tool_name}` 不存在（已返回自愈提示）",
                latency_ms=int((time.perf_counter() - started) * 1000),
            )

        # 1. Parse JSON arguments safely (self-healing on malformed JSON)
        if isinstance(raw_arguments, str):
            try:
                parsed_args = json.loads(raw_arguments) if raw_arguments.strip() else {}
                if not isinstance(parsed_args, dict):
                    raise TypeError("Tool arguments JSON must be an object")
            except (json.JSONDecodeError, TypeError, ValueError) as exc:
                msg = json.dumps(
                    {
                        "ok": False,
                        "error_type": "INVALID_JSON_ARGUMENTS",
                        "message": f"工具 `{tool_name}` 的参数不是合法 JSON 对象: {exc}",
                        "hint": "请输出严格合法的 JSON 对象参数后重试。",
                    },
                    ensure_ascii=False,
                )
                return ToolExecutionResult(
                    call_id=call_id,
                    tool_name=tool_name,
                    source=tool.source,
                    success=False,
                    output=msg,
                    summary="参数 JSON 解析失败（已触发自愈提示）",
                    latency_ms=int((time.perf_counter() - started) * 1000),
                )
        else:
            parsed_args = dict(raw_arguments)

        # 2. Pydantic strong validation (self-healing on schema mismatch)
        validated_args = parsed_args
        if tool.arg_model is not None:
            try:
                model_obj = tool.arg_model.model_validate(parsed_args)
                validated_args = model_obj.model_dump()
            except ValidationError as exc:
                msg = json.dumps(
                    {
                        "ok": False,
                        "error_type": "VALIDATION_ERROR",
                        "message": f"工具 `{tool_name}` 参数校验失败",
                        "details": exc.errors(include_url=False),
                        "hint": "请根据参数 schema 修正字段类型或必填项后重新调用。",
                    },
                    ensure_ascii=False,
                )
                return ToolExecutionResult(
                    call_id=call_id,
                    tool_name=tool_name,
                    source=tool.source,
                    success=False,
                    output=msg,
                    summary="Pydantic 参数校验失败（已触发自愈提示）",
                    latency_ms=int((time.perf_counter() - started) * 1000),
                )

        # 3. Execute with timeout & transient retry
        last_error: str | None = None
        for attempt in range(tool.max_retries + 1):
            try:
                output = await asyncio.wait_for(
                    tool.handler(validated_args),
                    timeout=tool.timeout_seconds,
                )
                latency = int((time.perf_counter() - started) * 1000)
                return ToolExecutionResult(
                    call_id=call_id,
                    tool_name=tool_name,
                    source=tool.source,
                    success=True,
                    output=output,
                    summary=_summarize_output(output),
                    latency_ms=latency,
                )
            except TimeoutError:
                last_error = f"工具 `{tool_name}` 执行超过 {tool.timeout_seconds}s 超时"
            except (httpx.HTTPError, RuntimeError, ValueError, OSError) as exc:
                last_error = str(exc)
                if attempt >= tool.max_retries:
                    break

        msg = json.dumps(
            {
                "ok": False,
                "error_type": "EXECUTION_ERROR",
                "message": last_error or "未知执行错误",
                "hint": "请检查参数是否合理，或直接基于已有上下文继续创作。",
            },
            ensure_ascii=False,
        )
        return ToolExecutionResult(
            call_id=call_id,
            tool_name=tool_name,
            source=tool.source,
            success=False,
            output=msg,
            summary=f"执行失败: {(last_error or '')[:120]}",
            latency_ms=int((time.perf_counter() - started) * 1000),
        )

    async def execute_batch(
        self, calls: list[tuple[str, str, str | dict[str, Any]]]
    ) -> list[ToolExecutionResult]:
        if not calls:
            return []
        tasks = [
            self.execute_one(call_id=cid, tool_name=name, raw_arguments=args)
            for cid, name, args in calls
        ]
        return list(await asyncio.gather(*tasks))


def register_builtin_novel_tools(
    registry: ToolRegistry,
    *,
    skill_registry: SkillRegistry,
    biz_java_url: str,
    internal_token: str,
    owner_id: str,
    book_id: str,
    run_id: str,
    chapter_no: int | None = None,
    chapter_context: str = "",
    rag_retriever: HybridRagRetriever | None = None,
    sandbox_provider: SandboxProvider | None = None,
) -> None:
    sandbox = sandbox_provider or StubSandboxProvider()
    headers = {"X-Internal-Token": internal_token, "X-Owner-Id": owner_id}
    base = biz_java_url.rstrip("/")

    async def _load_skill(args: dict[str, Any]) -> str:
        skill = skill_registry.load_skill(args["skill_name"])
        if skill is None:
            return json.dumps(
                {
                    "ok": False,
                    "message": f"未找到名为 `{args['skill_name']}` 的技能包",
                    "available_skills": skill_registry.available_names(),
                },
                ensure_ascii=False,
            )
        return f"# Skill: {skill.name}\n\n{skill.body}"

    registry.register(
        ToolDefinition(
            name="load_writing_skill",
            description="渐进式加载指定小说写作技能包的完整技法与检查清单（仅在场景匹配时按需调用）。",
            parameters_schema=LoadSkillArgs.model_json_schema(),
            source="skill",
            arg_model=LoadSkillArgs,
            handler=_load_skill,
            max_retries=0,
        )
    )

    if owner_id and book_id:

        async def _read_chapter(args: dict[str, Any]) -> str:
            url = f"{base}/internal/v1/books/{book_id}/lore/chapters/{args['chapter_no']}"
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.get(url, headers=headers, params={"maxChars": args["max_chars"]})
                if resp.status_code == 404:
                    return json.dumps(
                        {"found": False, "message": f"第 {args['chapter_no']} 章不存在"},
                        ensure_ascii=False,
                    )
                if resp.status_code >= 400:
                    raise RuntimeError(f"HTTP {resp.status_code}: {resp.text[:200]}")
                return resp.text

        registry.register(
            ToolDefinition(
                name="read_chapter_excerpt",
                description="读取当前小说指定章节（如上一章）的正文节选与字数，用于衔接前文剧情与细节。",
                parameters_schema=ReadChapterExcerptArgs.model_json_schema(),
                source="builtin",
                arg_model=ReadChapterExcerptArgs,
                handler=_read_chapter,
            )
        )

        async def _update_foreshadowing(args: dict[str, Any]) -> str:
            url = f"{base}/internal/v1/books/{book_id}/lore/foreshadowings"
            payload = {
                "title": args["title"],
                "description": args["description"],
                "plantedChapterNo": args.get("planted_chapter_no"),
                "resolvedChapterNo": args.get("resolved_chapter_no"),
                "status": args["status"],
            }
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.post(url, headers=headers, json=payload)
                if resp.status_code >= 400:
                    raise RuntimeError(f"HTTP {resp.status_code}: {resp.text[:200]}")
                return resp.text

        registry.register(
            ToolDefinition(
                name="update_foreshadowing",
                description="新增或更新当前小说的伏笔追踪记录（埋设新伏笔或标记旧伏笔为 RESOLVED）。",
                parameters_schema=UpdateForeshadowingArgs.model_json_schema(),
                source="builtin",
                arg_model=UpdateForeshadowingArgs,
                handler=_update_foreshadowing,
            )
        )

        if rag_retriever is not None:

            async def _search_kb(args: dict[str, Any]) -> str:
                result = await rag_retriever.search(
                    owner_id=owner_id,
                    book_id=book_id,
                    query=args["query"],
                    top_k=args["top_k"],
                    max_chapter_no=chapter_no,
                    doc_type=args.get("doc_type"),
                    rewrite_query=args.get("rewrite_query", False),
                    chapter_context=chapter_context,
                )
                return json.dumps(result, ensure_ascii=False)

            registry.register(
                ToolDefinition(
                    name="search_knowledge_base",
                    description=(
                        "使用 Milvus 企业级混合检索（Dense + BM25 + RRF + 重排 + 防剧透章节预过滤）"
                        "检索本书上传的设定集 PDF/Word/MD 资料与已定稿前文章节，返回完整场景父块与出处。"
                    ),
                    parameters_schema=SearchKnowledgeBaseArgs.model_json_schema(),
                    source="rag",
                    arg_model=SearchKnowledgeBaseArgs,
                    handler=_search_kb,
                    timeout_seconds=20.0,
                )
            )

        async def _get_memory(args: dict[str, Any]) -> str:
            cap = args.get("max_chapter_no") if args.get("max_chapter_no") is not None else chapter_no
            params: dict[str, Any] = {}
            if cap is not None:
                params["maxChapterNo"] = cap
            url = f"{base}/internal/v1/rag/books/{book_id}/memory"
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.get(url, headers=headers, params=params)
                if resp.status_code >= 400:
                    raise RuntimeError(f"HTTP {resp.status_code}: {resp.text[:200]}")
                return resp.text

        registry.register(
            ToolDefinition(
                name="get_structured_memory",
                description=(
                    "读取本书自动抽取的结构化长程记忆：人物最新状态、伏笔清单、人物关系三元组与事件时间线"
                    "（自动按当前章节号防剧透过滤）。"
                ),
                parameters_schema=GetStructuredMemoryArgs.model_json_schema(),
                source="memory",
                arg_model=GetStructuredMemoryArgs,
                handler=_get_memory,
            )
        )

    async def _run_sandbox(args: dict[str, Any]) -> str:
        res = await sandbox.exec_python(run_id=run_id, code=args["code"])
        return json.dumps(
            {
                "success": res.success,
                "provider": res.provider,
                "exit_code": res.exit_code,
                "stdout": res.stdout,
                "stderr": res.stderr,
                "duration_ms": res.duration_ms,
            },
            ensure_ascii=False,
        )

    registry.register(
        ToolDefinition(
            name="run_sandbox_script",
            description="在隔离沙箱中运行 Python 统计或数值校验脚本（如角色出场统计、资产/境界核算）。",
            parameters_schema=RunSandboxScriptArgs.model_json_schema(),
            source="sandbox",
            arg_model=RunSandboxScriptArgs,
            handler=_run_sandbox,
            max_retries=0,
        )
    )


def _summarize_output(output: str) -> str:
    clean = output.replace("\n", " ").strip()
    return clean if len(clean) <= 140 else f"{clean[:140]}..."
