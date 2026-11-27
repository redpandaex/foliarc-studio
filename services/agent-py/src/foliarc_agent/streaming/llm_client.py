import json
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any

import httpx

from foliarc_agent.agent.genre_profiles import GenreProfileRegistry
from foliarc_agent.config import Settings
from foliarc_agent.streaming.protocol import RunAction


class LlmConfigurationError(RuntimeError):
    """Raised when LLM_API_KEY is missing or left as a placeholder."""


class LlmUpstreamError(RuntimeError):
    """Raised when the upstream OpenAI-compatible gateway returns an error."""

    def __init__(self, message: str, *, retryable: bool = False, code: str = "LLM_UPSTREAM_ERROR"):
        super().__init__(message)
        self.retryable = retryable
        self.code = code


@dataclass(slots=True)
class ToolCallDelta:
    index: int
    id: str = ""
    name: str = ""
    arguments_delta: str = ""


@dataclass(slots=True)
class LlmStreamChunk:
    reasoning_delta: str = ""
    content_delta: str = ""
    tool_call_deltas: list[ToolCallDelta] = field(default_factory=list)
    input_tokens: int | None = None
    cached_tokens: int | None = None
    output_tokens: int | None = None
    finish_reason: str | None = None


def resolve_model_name(settings: Settings, model_alias: str) -> str:
    mapping = {
        "writer-main": settings.llm_model_writer_main,
        "critic-reasoner": settings.llm_model_critic_reasoner,
        "fast-router": settings.llm_model_fast_router,
    }
    return mapping.get(model_alias, settings.llm_model_writer_main)


_VALID_REASONING_EFFORTS = frozenset({"none", "minimal", "low", "medium", "high", "xhigh"})


def is_gemini_thinking_model(model_name: str) -> bool:
    """Detects Gemini 2.5/3/4 thinking models whose thinking tokens share max_tokens."""
    low = model_name.strip().lower()
    if not low:
        return False
    return any(tok in low for tok in ("gemini-2.5", "gemini-3", "gemini-4"))


def is_reasoning_model(model_name: str, model_alias: str = "") -> bool:
    """Detects reasoning/thinking models whose max_tokens covers both reasoning_content and content."""
    if model_alias == "critic-reasoner":
        return True
    low = model_name.strip().lower()
    if not low:
        return False
    if is_gemini_thinking_model(low):
        return True
    if any(tok in low for tok in ("reasoner", "thinking", "qwq", "deepseek-r1", "glm-z1")):
        return True
    return low.startswith(("o1", "o3", "o4"))


@dataclass(frozen=True, slots=True)
class SlotCapabilities:
    thinking: bool
    reasoning_effort: str | None
    allow_temperature: bool
    allow_json_mode: bool
    max_tokens_ceiling: int

    def effective_max_tokens(self, requested: int) -> int:
        if not self.thinking:
            return requested
        return min(self.max_tokens_ceiling, max(requested + 12288, requested * 4, 16384))


def resolve_slot_capabilities(settings: Settings, model_name: str, model_alias: str) -> SlotCapabilities:
    json_mode = "auto"
    if model_alias == "critic-reasoner":
        mode, slot_effort = settings.llm_slot_critic_thinking, settings.llm_slot_critic_effort
        json_mode = settings.llm_slot_critic_json_mode.strip().lower()
    elif model_alias == "fast-router":
        mode, slot_effort = settings.llm_slot_fast_thinking, settings.llm_slot_fast_effort
    else:
        mode, slot_effort = settings.llm_slot_writer_thinking, settings.llm_slot_writer_effort

    mode = mode.strip().lower()
    slot_effort = slot_effort.strip().lower()
    gemini = is_gemini_thinking_model(model_name)
    detected = is_reasoning_model(model_name, model_alias)
    thinking = mode == "on" or (mode != "off" and (detected or bool(slot_effort)))

    effort = slot_effort or settings.llm_reasoning_effort.strip().lower()
    if model_alias == "fast-router" and not slot_effort:
        effort = "low" if effort in {"high", "xhigh"} and (thinking or gemini) else ""

    allow_json_mode = not thinking or gemini
    if json_mode in {"true", "false"}:
        allow_json_mode = json_mode == "true"

    ceiling = 8192
    if thinking:
        ceiling = max(4096, min(65536, settings.llm_slot_max_output_ceiling))
        if detected and not gemini and mode != "on":
            ceiling = min(16384, ceiling)

    return SlotCapabilities(
        thinking=thinking,
        reasoning_effort=effort if effort in _VALID_REASONING_EFFORTS else None,
        allow_temperature=not thinking,
        allow_json_mode=allow_json_mode,
        max_tokens_ceiling=ceiling,
    )


def resolve_reasoning_effort(settings: Settings, model_name: str, model_alias: str) -> str | None:
    return resolve_slot_capabilities(settings, model_name, model_alias).reasoning_effort


def effective_max_tokens(settings: Settings, model_alias: str, requested_max_tokens: int) -> int:
    """Expands output token cap for reasoning models so thinking chains do not starve final content."""
    model = resolve_model_name(settings, model_alias)
    return resolve_slot_capabilities(settings, model, model_alias).effective_max_tokens(requested_max_tokens)


def build_novel_messages(
    *,
    action: RunAction,
    book_title: str,
    genre: str | None,
    synopsis: str | None,
    chapter_title: str,
    chapter_context: str,
    instruction: str,
    accumulated_text: str = "",
    previous_chapters_context: str = "",
    story_charter: str = "",
    rolling_summary_context: str = "",
    active_state_brief: str = "",
    pov_character: str = "",
    foreshadowing_writer_pack: str = "",
    genre_code: str = "",
    sub_genre_tags: str = "",
    style_anchors_text: str = "",
) -> list[dict[str, str]]:
    action_guides = {
        "CONTINUE": (
            "任务：基于前文章节衔接与当前章节已有正文自然续写后续剧情。"
            "若当前章节尚无正文，必须紧接上一章末尾的场景、人物状态、对话余韵与未解悬念展开，"
            "严禁另起炉灶或重新开篇。保持人物语气、叙事视角与文风连贯，直接输出正文段落，不要输出开场白或元说明。"
        ),
        "REWRITE": (
            "任务：对给定正文进行润色与细节扩写，提升画面感、动作节奏与对话张力，"
            "保留核心剧情与人物设定并确保与前文衔接自然。直接输出润色后的正文。"
        ),
        "OUTLINE": (
            "任务：紧承上一章结尾与已有世界观，为当前章节规划清晰的场景节拍大纲（3-5 个场景，包含承接前文、核心冲突、伏笔照应与结尾钩子）。"
            "使用简洁的 Markdown 列表输出。"
        ),
    }

    system_parts = [
        "你是 FoliarcStudio（卷弧工坊）的专业长篇小说创作助手，擅长中文网文与严肃文学创作。",
        action_guides.get(action, action_guides["CONTINUE"]),
    ]
    if book_title:
        system_parts.append(f"当前作品：《{book_title}》")
    if genre or genre_code:
        profile = GenreProfileRegistry.resolve(genre_code, genre)
        genre_label = genre or profile.display_name
        if sub_genre_tags.strip():
            genre_label = f"{genre_label}（标签：{sub_genre_tags.strip()}）"
        system_parts.append(f"题材风格：{genre_label}")
        system_parts.append(profile.format_writer_guard_block())
    if story_charter.strip():
        system_parts.append(f"【作品故事宪章（L0 静态底座）】\n{story_charter.strip()[:1200]}")
    elif synopsis:
        system_parts.append(f"故事简介：{synopsis}")
    if style_anchors_text.strip():
        system_parts.append(
            "【本书文风锚点（请模仿其句式长短、白描密度、对话节奏与用词克制度，严禁照抄剧情）】\n"
            + style_anchors_text.strip()[:1000]
        )

    user_parts = []
    if chapter_title:
        user_parts.append(f"当前章节：{chapter_title}")
    if rolling_summary_context.strip():
        user_parts.append(f"【前文阶段脉络与近期章摘（L2）】\n{rolling_summary_context.strip()[:4200]}")
    if active_state_brief.strip():
        user_parts.append(f"【当前活跃人物与待回收伏笔（L3）】\n{active_state_brief.strip()[:1800]}")
    if pov_character.strip():
        user_parts.append(
            f"【本章叙事视角（POV）】{pov_character.strip()}（严禁视角角色凭空知晓幕后客观事实或出现读心术！）"
        )
    if foreshadowing_writer_pack.strip():
        user_parts.append(
            f"【本章强制伏笔导演包（严格遵守埋入/照应/启用与防剧透红线）】\n{foreshadowing_writer_pack.strip()[:2200]}"
        )
    if previous_chapters_context.strip():
        user_parts.append(
            f"【前文章节承接（L1 热窗口，必须紧密衔接上一章结尾，严禁割裂或重启剧情）】\n{previous_chapters_context.strip()[-2800:]}"
        )
    if chapter_context.strip():
        tail_context = chapter_context[-4000:]
        user_parts.append(f"【当前章节已有正文】\n{tail_context}")
    if instruction.strip():
        user_parts.append(f"【作者指令】\n{instruction.strip()}")
    else:
        user_parts.append("【作者指令】\n请紧接前文自然推进当前场景，输出约 500-900 字高质量内容。")

    messages: list[dict[str, str]] = [
        {"role": "system", "content": "\n".join(system_parts)},
        {"role": "user", "content": "\n\n".join(user_parts)},
    ]

    if accumulated_text:
        messages.append({"role": "assistant", "content": accumulated_text})
        resume_prompt = (
            "你在刚才的位置暂停了。请严格从上面最后一个字之后无缝续写，绝对不要重复已经写过的句子。"
        )
        if instruction.strip():
            resume_prompt += f"\n作者补充/调整的新指令：{instruction.strip()}"
        messages.append({"role": "user", "content": resume_prompt})

    return messages


class OpenAICompatibleStreamClient:
    """Streams tokens and function tool_calls from any OpenAI-compatible /chat/completions API."""

    def __init__(self, settings: Settings, http_client: httpx.AsyncClient | None = None) -> None:
        self._settings = settings
        self._external_client = http_client

    async def stream_chat(
        self,
        *,
        model_alias: str,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
        temperature: float = 0.8,
        max_tokens: int = 2048,
        response_format: dict[str, Any] | None = None,
    ) -> AsyncIterator[LlmStreamChunk]:
        if not self._settings.llm_configured:
            raise LlmConfigurationError(
                "未配置有效的 LLM_API_KEY，请在仓库根目录 .env 中填入 DeepSeek 或 OpenAI 兼容中转站密钥并重启 agent-py。"
            )

        base_url = self._settings.llm_base_url.rstrip("/")
        url = f"{base_url}/chat/completions"
        model = resolve_model_name(self._settings, model_alias)
        capabilities = resolve_slot_capabilities(self._settings, model, model_alias)

        payload: dict[str, Any] = {
            "model": model,
            "messages": messages,
            "max_tokens": capabilities.effective_max_tokens(max_tokens),
            "stream": True,
            "stream_options": {"include_usage": True},
        }
        if capabilities.allow_temperature:
            payload["temperature"] = temperature
        if response_format is not None and capabilities.allow_json_mode:
            payload["response_format"] = response_format
        if capabilities.reasoning_effort:
            payload["reasoning_effort"] = capabilities.reasoning_effort
        if tools:
            payload["tools"] = tools
            payload["tool_choice"] = "auto"

        headers = {
            "Authorization": f"Bearer {self._settings.llm_api_key}",
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        }

        owns_client = self._external_client is None
        client = self._external_client or httpx.AsyncClient(
            timeout=httpx.Timeout(connect=15.0, read=180.0, write=30.0, pool=15.0)
        )
        try:
            async with client.stream("POST", url, headers=headers, json=payload) as response:
                if response.status_code >= 400:
                    body = (await response.aread()).decode("utf-8", errors="replace")[:500]
                    retryable = response.status_code in {408, 429, 500, 502, 503, 504}
                    raise LlmUpstreamError(
                        f"上游大模型接口返回 HTTP {response.status_code}: {body}",
                        retryable=retryable,
                        code=f"LLM_HTTP_{response.status_code}",
                    )

                async for raw_line in response.aiter_lines():
                    line = raw_line.strip()
                    if not line or line.startswith(":"):
                        continue
                    if not line.startswith("data:"):
                        continue
                    data_str = line[5:].strip()
                    if data_str == "[DONE]":
                        break
                    chunk = _parse_openai_chunk(data_str)
                    if chunk is not None:
                        yield chunk
        except httpx.TimeoutException as exc:
            raise LlmUpstreamError(
                f"请求上游大模型超时: {exc}", retryable=True, code="LLM_TIMEOUT"
            ) from exc
        except httpx.HTTPError as exc:
            raise LlmUpstreamError(
                f"上游大模型网络连接异常: {exc}", retryable=True, code="LLM_NETWORK_ERROR"
            ) from exc
        finally:
            if owns_client:
                await client.aclose()


def _parse_openai_chunk(data_str: str) -> LlmStreamChunk | None:
    try:
        obj = json.loads(data_str)
    except json.JSONDecodeError:
        return None

    reasoning_delta = ""
    content_delta = ""
    tool_deltas: list[ToolCallDelta] = []
    finish_reason = None

    choices = obj.get("choices") or []
    if choices:
        choice = choices[0] or {}
        delta = choice.get("delta") or {}
        reasoning_delta = delta.get("reasoning_content") or delta.get("reasoning") or ""
        content_delta = delta.get("content") or ""
        finish_reason = choice.get("finish_reason")

        for tc in delta.get("tool_calls") or []:
            fn = tc.get("function") or {}
            tool_deltas.append(
                ToolCallDelta(
                    index=int(tc.get("index", 0)),
                    id=str(tc.get("id") or ""),
                    name=str(fn.get("name") or ""),
                    arguments_delta=str(fn.get("arguments") or ""),
                )
            )

    usage = obj.get("usage") or {}
    input_tokens = usage.get("prompt_tokens")
    output_tokens = usage.get("completion_tokens")
    cached_tokens = None
    prompt_details = usage.get("prompt_tokens_details") or {}
    if "cached_tokens" in prompt_details:
        cached_tokens = prompt_details.get("cached_tokens")
    elif "prompt_cache_hit_tokens" in usage:
        cached_tokens = usage.get("prompt_cache_hit_tokens")

    if (
        not reasoning_delta
        and not content_delta
        and not tool_deltas
        and finish_reason is None
        and input_tokens is None
        and output_tokens is None
    ):
        return None

    return LlmStreamChunk(
        reasoning_delta=reasoning_delta,
        content_delta=content_delta,
        tool_call_deltas=tool_deltas,
        input_tokens=input_tokens,
        cached_tokens=cached_tokens,
        output_tokens=output_tokens,
        finish_reason=finish_reason,
    )
