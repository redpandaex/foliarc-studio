from __future__ import annotations

import json
import math
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any, Literal

import httpx

from foliarc_agent.config import Settings
from foliarc_agent.rag.embeddings import EmbeddingProvider
from foliarc_agent.streaming.llm_client import (
    LlmStreamChunk,
    LlmUpstreamError,
    OpenAICompatibleStreamClient,
)

RouteIntent = Literal[
    "COMBAT_SCENE",
    "FORESHADOWING",
    "ANCIENT_DIALOGUE",
    "PLOT_UNBLOCK",
    "STYLE_POLISH",
    "OUTLINE_PLAN",
    "CHAPTER_COMPOSE",
]

RouteLayer = Literal["RULE", "EMBEDDING", "LLM_CLASSIFIER", "DEFAULT"]


@dataclass(frozen=True, slots=True)
class ModelPlan:
    director: str
    lorekeeper: str
    writer: str
    critic: str

    def to_dict(self) -> dict[str, str]:
        return {
            "director": self.director,
            "lorekeeper": self.lorekeeper,
            "writer": self.writer,
            "critic": self.critic,
        }


@dataclass(frozen=True, slots=True)
class RouteDecision:
    intent: RouteIntent
    confidence: float
    layer: RouteLayer
    recommended_skill: str | None
    model_plan: ModelPlan
    reason: str
    fallback_chain: dict[str, list[str]] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "intent": self.intent,
            "confidence": round(self.confidence, 3),
            "layer": self.layer,
            "recommended_skill": self.recommended_skill,
            "model_plan": self.model_plan.to_dict(),
            "reason": self.reason,
            "fallback_chain": self.fallback_chain,
        }


_RULE_CATALOG: list[tuple[RouteIntent, str | None, tuple[str, ...]]] = [
    ("COMBAT_SCENE", "combat-scene", ("打斗", "交手", "比试", "剑招", "法术对决", "追杀", "搏杀", "激战", "生死战")),
    ("FORESHADOWING", "foreshadowing-craft", ("伏笔", "悬疑", "草蛇灰线", "暗线", "照应", "反转", "线索")),
    ("ANCIENT_DIALOGUE", "ancient-dialogue", ("对话", "宗门议事", "世家交锋", "论道", "古风对话", "试探", "对白")),
    ("PLOT_UNBLOCK", "plot-unblock", ("卡文", "破局", "钩子", "冲突升级", "怎么推进", "不知怎么写")),
    ("OUTLINE_PLAN", None, ("大纲", "细纲", "章节规划", "节拍表", "分幕")),
    ("STYLE_POLISH", None, ("润色", "扩写", "改写", "文笔", "细节描写")),
]

_SEMANTIC_PROTOTYPES: dict[RouteIntent, tuple[str | None, str]] = {
    "COMBAT_SCENE": ("combat-scene", "高强度动作打斗、冷兵器交锋、修仙法术对决、近身搏杀与招式节奏描写"),
    "FORESHADOWING": ("foreshadowing-craft", "悬疑推理、埋设长线伏笔、回收前文线索、制造草蛇灰线与结局反转"),
    "ANCIENT_DIALOGUE": ("ancient-dialogue", "古风仙侠人物交谈、宗门长老议事、江湖言辞试探、潜台词博弈与论道对话"),
    "PLOT_UNBLOCK": ("plot-unblock", "解决写作卡文瓶颈、设计剧情转折与章节末尾悬念钩子、推进主线冲突"),
    "STYLE_POLISH": (None, "文学润色、环境氛围扩写、心理活动细腻刻画与叙事节奏打磨"),
    "OUTLINE_PLAN": (None, "规划章节结构大纲、拆解三到四个核心场景节拍与起承转合"),
    "CHAPTER_COMPOSE": (None, "顺承前文剧情推进长篇小说新一章正文创作与人物互动"),
}

DEFAULT_FALLBACK_CHAINS: dict[str, list[str]] = {
    "critic-reasoner": ["critic-reasoner", "writer-main", "fast-router"],
    "writer-main": ["writer-main", "fast-router"],
    "fast-router": ["fast-router", "writer-main"],
}


def _cosine_similarity(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b, strict=False))
    na = math.sqrt(sum(x * x for x in a)) or 1.0
    nb = math.sqrt(sum(y * y for y in b)) or 1.0
    return dot / (na * nb)


class IntentAndModelRouter:
    """
    Multi-layer novel intent & cost-aware model router:
    1. Layer 1: Deterministic rule/keyword fast path (<1ms, 0 tokens)
    2. Layer 2: Embedding semantic similarity against canonical novel intent prototypes
    3. Layer 3: Optional fast-model tie-breaker when semantic margin is narrow
    Allocates low-cost `fast-router` to Director/Lorekeeper, flagship `writer-main` to Writer,
    and reasoning `critic-reasoner` (with automatic fallback) to ContinuityCritic.
    """

    def __init__(
        self,
        *,
        settings: Settings,
        embedder: EmbeddingProvider | None = None,
    ) -> None:
        self._settings = settings
        self._embedder = embedder or EmbeddingProvider(settings)
        self._prototype_vecs: dict[RouteIntent, list[float]] | None = None

    async def route(
        self,
        *,
        action: str,
        instruction: str,
        chapter_context: str = "",
        requested_model_alias: str = "writer-main",
    ) -> RouteDecision:
        text = f"{instruction.strip()} {chapter_context[-300:]}".strip()

        # Layer 1: Rule / Keyword fast channel
        for intent, skill, keywords in _RULE_CATALOG:
            matched = [kw for kw in keywords if kw in instruction]
            if matched:
                return self._build_decision(
                    intent=intent,
                    confidence=0.95,
                    layer="RULE",
                    skill=skill,
                    requested_model_alias=requested_model_alias,
                    reason=f"规则快通道命中关键词: {', '.join(matched[:3])}",
                )

        if action.upper() == "OUTLINE":
            return self._build_decision(
                intent="OUTLINE_PLAN",
                confidence=0.9,
                layer="RULE",
                skill=None,
                requested_model_alias=requested_model_alias,
                reason="动作类型为 OUTLINE，命中大纲规划快通道",
            )
        if action.upper() == "REWRITE":
            return self._build_decision(
                intent="STYLE_POLISH",
                confidence=0.88,
                layer="RULE",
                skill=None,
                requested_model_alias=requested_model_alias,
                reason="动作类型为 REWRITE，命中润色扩写快通道",
            )

        # Layer 2: Semantic Embedding routing
        if text:
            best_intent, best_score, margin = await self._embedding_route(text)
            if best_score >= 0.20 and margin >= 0.03:
                skill = _SEMANTIC_PROTOTYPES[best_intent][0]
                return self._build_decision(
                    intent=best_intent,
                    confidence=min(0.89, 0.55 + best_score),
                    layer="EMBEDDING",
                    skill=skill,
                    requested_model_alias=requested_model_alias,
                    reason=f"语义向量路由命中 {best_intent} (cos={best_score:.3f}, margin={margin:.3f})",
                )

            # Layer 3: Lightweight LLM classifier tie-breaker when configured
            if self._settings.llm_configured and instruction.strip():
                llm_intent = await self._llm_classify(instruction.strip())
                if llm_intent is not None:
                    return self._build_decision(
                        intent=llm_intent,
                        confidence=0.82,
                        layer="LLM_CLASSIFIER",
                        skill=_SEMANTIC_PROTOTYPES[llm_intent][0],
                        requested_model_alias=requested_model_alias,
                        reason=f"轻量快模型分类器判定为 {llm_intent}",
                    )

        return self._build_decision(
            intent="CHAPTER_COMPOSE",
            confidence=0.75,
            layer="DEFAULT",
            skill=None,
            requested_model_alias=requested_model_alias,
            reason="默认进入标准长篇章节协同创作通道",
        )

    async def _embedding_route(self, text: str) -> tuple[RouteIntent, float, float]:
        if self._prototype_vecs is None:
            intents = list(_SEMANTIC_PROTOTYPES.keys())
            proto_texts = [_SEMANTIC_PROTOTYPES[i][1] for i in intents]
            vecs = await self._embedder.embed_texts(proto_texts)
            self._prototype_vecs = dict(zip(intents, vecs, strict=True))

        query_vec = (await self._embedder.embed_texts([text]))[0]
        scored = sorted(
            (
                (intent, _cosine_similarity(query_vec, vec))
                for intent, vec in self._prototype_vecs.items()
            ),
            key=lambda x: x[1],
            reverse=True,
        )
        top_intent, top_score = scored[0]
        second_score = scored[1][1] if len(scored) > 1 else 0.0
        return top_intent, top_score, top_score - second_score

    async def _llm_classify(self, instruction: str) -> RouteIntent | None:
        url = f"{self._settings.llm_base_url.rstrip('/')}/chat/completions"
        valid = list(_SEMANTIC_PROTOTYPES.keys())
        prompt = (
            f"请将以下小说写作指令分类为唯一一个标签：{', '.join(valid)}。"
            '仅返回 JSON：{"intent":"..."}'
        )
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(10.0)) as client:
                resp = await client.post(
                    url,
                    headers={"Authorization": f"Bearer {self._settings.llm_api_key}"},
                    json={
                        "model": self._settings.llm_model_fast_router,
                        "messages": [
                            {"role": "system", "content": prompt},
                            {"role": "user", "content": instruction[:500]},
                        ],
                        "temperature": 0.0,
                        "max_tokens": 40,
                    },
                )
                if resp.status_code != 200:
                    return None
                raw = resp.json()["choices"][0]["message"]["content"]
                start = raw.find("{")
                end = raw.rfind("}")
                if start >= 0 and end > start:
                    parsed = json.loads(raw[start : end + 1])
                    candidate = str(parsed.get("intent", "")).upper()
                    if candidate in _SEMANTIC_PROTOTYPES:
                        return candidate  # type: ignore[return-value]
        except (httpx.HTTPError, KeyError, ValueError, json.JSONDecodeError):
            return None
        return None

    @staticmethod
    def _build_decision(
        *,
        intent: RouteIntent,
        confidence: float,
        layer: RouteLayer,
        skill: str | None,
        requested_model_alias: str,
        reason: str,
    ) -> RouteDecision:
        writer_model = requested_model_alias if requested_model_alias in DEFAULT_FALLBACK_CHAINS else "writer-main"
        # Simple/structural tasks use fast-router; heavy consistency review uses critic-reasoner (with fallback)
        critic_model = "writer-main" if intent in ("STYLE_POLISH", "OUTLINE_PLAN") else "critic-reasoner"
        plan = ModelPlan(
            director="fast-router",
            lorekeeper="fast-router",
            writer=writer_model,
            critic=critic_model,
        )
        return RouteDecision(
            intent=intent,
            confidence=confidence,
            layer=layer,
            recommended_skill=skill,
            model_plan=plan,
            reason=reason,
            fallback_chain=DEFAULT_FALLBACK_CHAINS,
        )


FallbackHook = Callable[[str, str, str], Awaitable[None]]


class FallbackStreamClient:
    """
    Wraps an OpenAI-compatible streaming client with resilient multi-model failover:
    on retryable upstream errors (429 / 5xx / timeout) before streaming starts,
    automatically falls back along the ordered alias chain and emits telemetry.
    """

    def __init__(
        self,
        primary_client: OpenAICompatibleStreamClient,
        fallback_chains: dict[str, list[str]] | None = None,
    ) -> None:
        self._client = primary_client
        self._chains = fallback_chains or DEFAULT_FALLBACK_CHAINS

    async def stream_chat(
        self,
        *,
        model_alias: str,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
        temperature: float = 0.8,
        max_tokens: int = 2048,
        on_fallback: FallbackHook | None = None,
    ) -> AsyncIterator[LlmStreamChunk]:
        chain = list(self._chains.get(model_alias, [model_alias]))
        if model_alias not in chain:
            chain.insert(0, model_alias)

        last_err: LlmUpstreamError | None = None
        for idx, alias in enumerate(chain):
            yielded_any = False
            try:
                async for chunk in self._client.stream_chat(
                    model_alias=alias,
                    messages=messages,
                    tools=tools,
                    temperature=temperature,
                    max_tokens=max_tokens,
                ):
                    yielded_any = True
                    yield chunk
                return
            except LlmUpstreamError as exc:
                last_err = exc
                has_next = idx + 1 < len(chain)
                if exc.retryable and has_next and not yielded_any:
                    next_alias = chain[idx + 1]
                    if on_fallback is not None:
                        await on_fallback(alias, next_alias, f"{exc.code}: {exc}")
                    continue
                raise

        if last_err is not None:
            raise last_err
