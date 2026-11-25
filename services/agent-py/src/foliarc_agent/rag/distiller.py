from __future__ import annotations

import asyncio
import hashlib
import json
import logging
from typing import Any

import httpx

from foliarc_agent.agent.genre_profiles import GenreProfileRegistry
from foliarc_agent.config import Settings

logger = logging.getLogger(__name__)

DIRECT_DISTILL_CHAR_LIMIT = 12_000
MAP_SEGMENT_SIZE = 5_000
MAP_SEGMENT_OVERLAP = 200

_UNIFIED_DISTILL_PROMPT = """你是长篇小说记忆编译引擎。请阅读给定章节/小节正文，一次同时产出【结构化单节摘要】与【结构化记忆抽取】，严格输出 JSON 对象（不要输出 Markdown 代码块）：
{
  "summary": {
    "timeAndPlace": "故事内时间推进与地点变迁",
    "summaryText": "140~220字客观高密度梗概（承接上节、核心冲突、转折与明确结果）",
    "stateChanges": "不可逆硬事实变更（伤亡/道具得失/能力或规则变动；无则空）",
    "clueSignals": "新埋异常细节、暗线照应或旧伏笔回收；无则空",
    "povNotes": "本节叙事视角与关键信息差（谁得知秘密、谁仍蒙在鼓里）",
    "involvedCharacters": ["出场人物标准名"],
    "endingHook": "一句话概括本节结尾画面与下节必须承接的危机或动作"
  },
  "memory": {
    "characterUpdates": [
      {
        "name": "标准角色名",
        "aliases": [],
        "lifecycleStatus": "ACTIVE",
        "currentLocation": "本节结束位置",
        "statusNotes": "伤势/心境/状态变更",
        "inventoryDelta": "新增或失去的物品",
        "customAttributes": {},
        "isHighRisk": false
      }
    ],
    "foreshadowingSignals": [
      {
        "code": null,
        "action": "PLANTED",
        "title": "15字以内简短标题",
        "quote": "原文中最具代表性的1~2句线索原话",
        "description": "线索含义与进展"
      }
    ],
    "relations": [
      {
        "sourceCharacter": "角色A",
        "targetCharacter": "角色B",
        "relationType": "关系类型",
        "description": "本节事件体现的关系变动",
        "knownBy": "知晓范围"
      }
    ],
    "timelineEvents": [
      {
        "inStoryTime": "故事内时间点",
        "eventTitle": "核心事件（20字以内）",
        "summary": "60字以内客观事实（口述或谎言须注明“XX声称”）",
        "involvedCharacters": "角色A, 角色B",
        "location": "地点",
        "knownBy": "知情人物列表"
      }
    ]
  }
}
硬规则：严格区分客观事实与人物口述/猜测；严禁脑补未发生剧情。"""

_MAP_SEGMENT_PROMPT = """你是长篇小说超长章节分段事实提炼器。请将给定片段提炼为 180~260 字的紧凑【段落事实卡】，严格保留：
1. 时间地点变迁与核心事件进展；
2. 出场人物与硬事实变更（伤亡、道具得失、身份/关系变化）；
3. 重要伏笔线索或异常细节。
直接输出纯文本事实卡，严禁遗漏关键转折。"""

_TIER1_ROLLUP_PROMPT = """你是长篇小说卷级总编。请将以下连续小节摘要归并为一份 320~420 字的【Tier 1 卷级摘要】，严格按 4 行输出：
- 主线推进：...
- 人物与格局：...
- 硬事实与遗留物：...
- 未解暗线：...
不要编造未出现的设定。"""

_TIER2_ROLLUP_PROMPT = """你是万章长篇小说篇级总编。请将以下多份卷级摘要归并为一份 220~300 字的【Tier 2 篇级里程碑】，只保留改变大局势的阶段性结局、主角团核心成长、地图/舞台转换与跨篇未回收主线伏笔，删去已完结的局部过渡细节。"""

_TIER3_ROLLUP_PROMPT = """你是史诗级长篇编年史主编。请将以下多份篇级里程碑浓缩为 80~120 字的【Tier 3 纪元编年史】，概括本阶段的大时代主题、主要敌对势力结局与迈入下一纪元的契机。"""

_UPGRADED_CHARTER_PROMPT = """你是长篇小说架构师。请结合作品摘要、2 段真实原文采样（开头与最新片段）以及既有档案，客观归纳一份 260~420 字的【作品静态档案（L0 故事宪章）】，按以下 5 行输出：
- 核心主线：...
- 叙事视角与人称（基于原文采样确认）：...
- 文风节奏质感（基于原文采样归纳）：...
- 已确立世界观与硬规则：...
- 叙事禁忌底线：...
要求：严格忠实于原文采样与已有事实，增量演进避免无故推翻前版。"""


def split_logical_segments(
    content: str,
    *,
    segment_size: int = MAP_SEGMENT_SIZE,
    overlap: int = MAP_SEGMENT_OVERLAP,
) -> list[str]:
    """Splits ultra-long prose (12k~100k chars) along paragraph boundaries with overlap."""
    text = content.strip()
    if len(text) <= segment_size:
        return [text] if text else []

    paragraphs = [p.strip() for p in text.split("\n") if p.strip()]
    segments: list[str] = []
    buf: list[str] = []
    buf_len = 0

    for para in paragraphs:
        # Handle single gigantic paragraph without line breaks
        if len(para) > segment_size:
            if buf:
                segments.append("\n".join(buf))
                buf = []
                buf_len = 0
            step = max(1, segment_size - overlap)
            for idx in range(0, len(para), step):
                chunk = para[idx : idx + segment_size].strip()
                if chunk:
                    segments.append(chunk)
                if idx + segment_size >= len(para):
                    break
            continue

        if buf and buf_len + len(para) + 1 > segment_size:
            merged = "\n".join(buf)
            segments.append(merged)
            tail = merged[-overlap:] if overlap > 0 and len(merged) > overlap else ""
            buf = [tail, para] if tail else [para]
            buf_len = sum(len(x) for x in buf) + len(buf)
        else:
            buf.append(para)
            buf_len += len(para) + 1

    if buf:
        segments.append("\n".join(buf))
    return segments


class ChapterDistiller:
    """
    Phase 5.8A Unified Section Distiller & Multi-Tier Logarithmic Rollup Synthesizer:
    - Single-pass unified JSON distillation (Summary + Structured Memory in 1 LLM call, ~45% cheaper)
    - Automatic parallel Map-Reduce for ultra-long chapters/sections (>12,000 up to 100,000 chars)
    - 4-tier logarithmic rollup synthesis (Tier 1 Volume / Tier 2 Arc / Tier 3 Epoch)
    - Prose-sampled L0 Auto-Emergent Story Charter
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def distill_section(
        self,
        *,
        chapter_no: int,
        section_no: int = 1,
        chapter_title: str = "",
        section_title: str = "",
        content: str,
        volume_arc_goal: str = "",
        prev_ending_hook: str = "",
        known_characters: list[str] | None = None,
        open_foreshadowings: list[str] | None = None,
        genre: str | None = None,
        genre_code: str | None = None,
    ) -> dict[str, Any]:
        text = (content or "").strip()
        if not text:
            return self._empty_distill_result(chapter_title or section_title)

        used_map_reduce = len(text) > DIRECT_DISTILL_CHAR_LIMIT
        known = [c for c in (known_characters or []) if c]

        if self._settings.llm_configured:
            try:
                if used_map_reduce:
                    fact_cards = await self._map_segments(
                        text,
                        chapter_no=chapter_no,
                        section_no=section_no,
                        known_characters=known,
                    )
                    tail_excerpt = text[-1500:]
                    distill_body = (
                        f"【超长章节 Map-Reduce 分段事实卡（共 {len(fact_cards)} 段）】\n"
                        + "\n\n".join(f"[片段 {i + 1}] {card}" for i, card in enumerate(fact_cards))
                        + f"\n\n【本节末尾原文（最后 1500 字，用于精准提炼 endingHook）】\n{tail_excerpt}"
                    )
                else:
                    distill_body = f"【本节正文】\n{text}"

                user_prompt = self._build_distill_user_prompt(
                    chapter_no=chapter_no,
                    section_no=section_no,
                    chapter_title=chapter_title,
                    section_title=section_title,
                    volume_arc_goal=volume_arc_goal,
                    prev_ending_hook=prev_ending_hook,
                    known_characters=known,
                    open_foreshadowings=open_foreshadowings or [],
                    genre=genre,
                    genre_code=genre_code,
                    body_block=distill_body,
                )
                raw = await self._call_fast_llm(
                    _UNIFIED_DISTILL_PROMPT,
                    user_prompt,
                    max_tokens=1400,
                    json_mode=True,
                )
                parsed = self._parse_distill_json(raw, fallback_title=section_title or chapter_title)
                if parsed is not None:
                    parsed["meta"] = {
                        "usedMapReduce": used_map_reduce,
                        "segmentCount": len(split_logical_segments(text)) if used_map_reduce else 1,
                        "sourceChars": len(text),
                    }
                    return parsed
            except (httpx.HTTPError, KeyError, ValueError, json.JSONDecodeError) as exc:
                logger.warning("Unified section distillation failed, falling back deterministically: %s", exc)

        return self._fallback_distill(
            chapter_no=chapter_no,
            section_no=section_no,
            chapter_title=chapter_title,
            section_title=section_title,
            content=text,
            known_characters=known,
            used_map_reduce=used_map_reduce,
        )

    async def summarize_rollup(
        self,
        *,
        tier_level: int,
        seq_no: int,
        start_chapter_no: int,
        end_chapter_no: int,
        items: list[dict[str, Any]],
        title: str = "",
    ) -> dict[str, str]:
        lines: list[str] = []
        open_threads_acc: list[str] = []
        for item in items:
            label = item.get("label") or f"第{item.get('chapterNo', '?')}章"
            summary = str(item.get("summaryText") or "").strip()
            changes = str(item.get("stateChanges") or "").strip()
            clues = str(item.get("clueSignals") or item.get("openThreads") or "").strip()
            line = f"{label}：{summary}"
            if changes:
                line += f" [硬事实：{changes}]"
            if clues:
                line += f" [暗线：{clues}]"
                open_threads_acc.append(clues)
            lines.append(line)

        joined = "\n".join(lines)
        source_hash = hashlib.sha256(joined.encode("utf-8")).hexdigest()[:32]
        open_threads = "；".join(dict.fromkeys(x for x in open_threads_acc if x))[:600]

        prompt = (
            _TIER1_ROLLUP_PROMPT
            if tier_level == 1
            else _TIER2_ROLLUP_PROMPT if tier_level == 2 else _TIER3_ROLLUP_PROMPT
        )
        max_out = 700 if tier_level == 1 else 500 if tier_level == 2 else 260
        char_cap = 1100 if tier_level == 1 else 700 if tier_level == 2 else 300

        if self._settings.llm_configured and lines:
            try:
                user_msg = (
                    f"归并层级：Tier {tier_level}（序号 {seq_no}，覆盖第 {start_chapter_no}~{end_chapter_no} 章"
                    f"{'，' + title if title else ''}）\n\n"
                    f"{joined[:9000]}"
                )
                raw = await self._call_fast_llm(prompt, user_msg, max_tokens=max_out, json_mode=False)
                if raw.strip():
                    return {
                        "summaryText": raw.strip()[:char_cap],
                        "openThreads": open_threads,
                        "sourceHash": source_hash,
                    }
            except (httpx.HTTPError, KeyError, ValueError) as exc:
                logger.warning("Tier %s rollup LLM call failed, using fallback: %s", tier_level, exc)

        snippet_len = 85 if tier_level == 1 else 65 if tier_level == 2 else 45
        fallback_parts: list[str] = []
        for item in items:
            lbl = item.get("label") or f"第{item.get('chapterNo', '?')}章"
            fallback_parts.append(f"{lbl}{str(item.get('summaryText') or '')[:snippet_len]}")
        fallback = "；".join(fallback_parts)[:char_cap]
        return {
            "summaryText": fallback or f"Tier {tier_level} 第 {seq_no} 阶段（第 {start_chapter_no}~{end_chapter_no} 章）归档摘要。",
            "openThreads": open_threads,
            "sourceHash": source_hash,
        }

    async def infer_story_charter(
        self,
        *,
        book_title: str,
        genre: str | None,
        existing_synopsis: str | None,
        existing_auto_charter: str = "",
        rollup_summaries: list[dict[str, Any]] | None = None,
        recent_summaries: list[dict[str, Any]] | None = None,
        first_section_sample: str = "",
        latest_section_sample: str = "",
        known_characters: list[str] | None = None,
    ) -> str:
        rollups = rollup_summaries or []
        recents = recent_summaries or []
        first_excerpt = (first_section_sample or "").strip()[:500]
        latest_excerpt = (latest_section_sample or "").strip()[:500]

        if self._settings.llm_configured and (rollups or recents or first_excerpt or latest_excerpt):
            try:
                rollup_block = "\n".join(
                    f"- [T{r.get('tierLevel', 1)}] 第{r.get('startChapterNo')}~{r.get('endChapterNo')}章：{r.get('summaryText', '')}"
                    for r in rollups[:6]
                )
                recent_block = "\n".join(
                    f"- 第{c.get('chapterNo')}章：{c.get('summaryText', '')}" for c in recents[-3:]
                )
                user_msg = (
                    f"书名：《{book_title}》\n"
                    f"题材：{genre or '未指定'}\n"
                    f"简介：{existing_synopsis or '无'}\n"
                    f"主要角色：{', '.join((known_characters or [])[:15]) or '暂无'}\n"
                    f"上一版自动档案（供增量演化）：\n{existing_auto_charter or '无'}\n\n"
                    f"前序卷/篇摘要：\n{rollup_block or '暂无'}\n\n"
                    f"近期摘要：\n{recent_block or '暂无'}\n\n"
                    f"【原文采样 1 · 开篇前 500 字】\n{first_excerpt or '暂无'}\n\n"
                    f"【原文采样 2 · 最新正文前 500 字】\n{latest_excerpt or '暂无'}"
                )
                raw = await self._call_fast_llm(_UPGRADED_CHARTER_PROMPT, user_msg, max_tokens=700, json_mode=False)
                if raw.strip():
                    return raw.strip()[:1400]
            except (httpx.HTTPError, KeyError, ValueError) as exc:
                logger.warning("Upgraded auto-charter LLM call failed, using fallback: %s", exc)

        pov_hint = "第一人称叙事" if ("我" in first_excerpt[:200] and first_excerpt.count("我") >= 3) else "第三人称限知叙事"
        premise = (existing_synopsis or "").strip()
        if not premise and recents:
            premise = " ".join(str(c.get("summaryText", ""))[:90] for c in recents[:2]).strip()
        chars_str = "、".join((known_characters or [])[:8]) or "随剧情逐步建立"
        return (
            f"- 核心主线：《{book_title}》{('（' + genre + '）') if genre else ''}，{premise or '围绕主角核心遭遇与主线矛盾持续推进。'}\n"
            f"- 叙事视角与人称：{pov_hint}\n"
            f"- 文风节奏质感：严格承接已写正文采样的句式密度、氛围与叙事节奏\n"
            f"- 已确立世界观与硬规则：已知核心人物 {chars_str}\n"
            f"- 叙事禁忌底线：不吃书、不违背已确立时间线与人物状态"
        )

    async def _map_segments(
        self,
        text: str,
        *,
        chapter_no: int,
        section_no: int,
        known_characters: list[str],
    ) -> list[str]:
        segments = split_logical_segments(text, segment_size=MAP_SEGMENT_SIZE, overlap=MAP_SEGMENT_OVERLAP)
        sem = asyncio.Semaphore(4)

        async def _distill_one(idx: int, seg: str) -> str:
            async with sem:
                msg = (
                    f"第 {chapter_no} 章第 {section_no} 节 · 片段 {idx + 1}/{len(segments)}\n"
                    f"已知角色：{', '.join(known_characters[:25]) or '暂无'}\n\n"
                    f"{seg}"
                )
                return await self._call_fast_llm(_MAP_SEGMENT_PROMPT, msg, max_tokens=420, json_mode=False)

        cards = await asyncio.gather(*(_distill_one(i, seg) for i, seg in enumerate(segments)))
        return [c.strip() for c in cards if c and c.strip()]

    async def _call_fast_llm(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        max_tokens: int,
        json_mode: bool = False,
    ) -> str:
        url = f"{self._settings.llm_base_url.rstrip('/')}/chat/completions"
        payload: dict[str, Any] = {
            "model": self._settings.llm_model_fast_router,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": 0.2,
            "max_tokens": max_tokens,
        }
        if json_mode:
            payload["response_format"] = {"type": "json_object"}
        async with httpx.AsyncClient(timeout=httpx.Timeout(60.0, connect=10.0)) as client:
            resp = await client.post(
                url,
                headers={"Authorization": f"Bearer {self._settings.llm_api_key}"},
                json=payload,
            )
            resp.raise_for_status()
            return str(resp.json()["choices"][0]["message"]["content"]).strip()

    @staticmethod
    def _build_distill_user_prompt(
        *,
        chapter_no: int,
        section_no: int,
        chapter_title: str,
        section_title: str,
        volume_arc_goal: str,
        prev_ending_hook: str,
        known_characters: list[str],
        open_foreshadowings: list[str],
        genre: str | None,
        body_block: str,
        genre_code: str | None = None,
    ) -> str:
        profile = GenreProfileRegistry.resolve(genre_code, genre) if (genre_code or genre) else None
        parts = [
            f"题材：{genre or (profile.display_name if profile else '通用长篇')}",
            f"位置：第 {chapter_no} 章《{chapter_title}》· 第 {section_no} 节《{section_title or chapter_title}》",
        ]
        if profile is not None:
            parts.append(profile.format_distill_focus_block())
        if volume_arc_goal:
            parts.append(f"当前卷纲：{volume_arc_goal[:300]}")
        if prev_ending_hook:
            parts.append(f"上一节结尾悬念：{prev_ending_hook[:220]}")
        parts.append(f"已知活跃角色：{', '.join(known_characters[:40]) or '暂无'}")
        if open_foreshadowings:
            parts.append(f"待追踪伏笔清单：{'；'.join(open_foreshadowings[:25])}")
        parts.append("")
        parts.append(body_block)
        return "\n".join(parts)

    @staticmethod
    def _parse_distill_json(raw: str, *, fallback_title: str) -> dict[str, Any] | None:
        start = raw.find("{")
        end = raw.rfind("}")
        if start < 0 or end <= start:
            return None
        obj = json.loads(raw[start : end + 1])
        summary_raw = obj.get("summary") or {}
        memory_raw = obj.get("memory") or {}
        summary_text = str(summary_raw.get("summaryText") or "").strip()
        if not summary_text:
            return None

        inv_chars = summary_raw.get("involvedCharacters") or []
        if isinstance(inv_chars, list):
            chars_str = ", ".join(str(x).strip() for x in inv_chars if str(x).strip())[:300]
        else:
            chars_str = str(inv_chars).strip()[:300]

        return {
            "summary": {
                "timeAndPlace": str(summary_raw.get("timeAndPlace") or "").strip()[:240],
                "summaryText": summary_text[:520],
                "stateChanges": str(summary_raw.get("stateChanges") or "").strip()[:400],
                "clueSignals": str(summary_raw.get("clueSignals") or "").strip()[:400],
                "povNotes": str(summary_raw.get("povNotes") or "").strip()[:260],
                "involvedCharacters": chars_str,
                "endingHook": str(summary_raw.get("endingHook") or "").strip()[:260],
                "keyEvents": str(summary_raw.get("stateChanges") or fallback_title or "章节剧情推进").strip()[:360],
            },
            "memory": {
                "characterUpdates": list(memory_raw.get("characterUpdates") or []),
                "foreshadowingSignals": list(memory_raw.get("foreshadowingSignals") or []),
                "relations": list(memory_raw.get("relations") or []),
                "timelineEvents": list(memory_raw.get("timelineEvents") or []),
            },
        }

    @staticmethod
    def _empty_distill_result(title: str) -> dict[str, Any]:
        return {
            "summary": {
                "timeAndPlace": "",
                "summaryText": title or "空章节",
                "stateChanges": "",
                "clueSignals": "",
                "povNotes": "",
                "involvedCharacters": "",
                "endingHook": "",
                "keyEvents": title or "",
            },
            "memory": {
                "characterUpdates": [],
                "foreshadowingSignals": [],
                "relations": [],
                "timelineEvents": [],
            },
            "meta": {"usedMapReduce": False, "segmentCount": 0, "sourceChars": 0},
        }

    @staticmethod
    def _fallback_distill(
        *,
        chapter_no: int,
        section_no: int,
        chapter_title: str,
        section_title: str,
        content: str,
        known_characters: list[str],
        used_map_reduce: bool,
    ) -> dict[str, Any]:
        segments = split_logical_segments(content) if used_map_reduce else [content]
        if used_map_reduce and len(segments) > 1:
            # Deterministic offline Map-Reduce: keep head/mid/tail beats from every segment so nothing is blindly dropped
            per_seg = max(40, min(90, 320 // len(segments)))
            snippets = [" ".join(seg.split())[:per_seg] for seg in segments]
            summary_text = "；".join(snippets)[:420]
        else:
            clean = " ".join(content.split())
            summary_text = clean if len(clean) <= 220 else f"{clean[:120]}……{clean[-100:]}"

        clean_full = " ".join(content.split())
        ending_hook = clean_full[-90:] if len(clean_full) > 90 else clean_full
        mentioned = [name for name in known_characters if name and name in content]
        label = section_title or chapter_title or f"第{chapter_no}章第{section_no}节"

        return {
            "summary": {
                "timeAndPlace": "",
                "summaryText": summary_text,
                "stateChanges": "",
                "clueSignals": "",
                "povNotes": "",
                "involvedCharacters": ", ".join(mentioned[:10]),
                "endingHook": ending_hook,
                "keyEvents": label,
            },
            "memory": {
                "characterUpdates": [
                    {
                        "name": name,
                        "aliases": [],
                        "lifecycleStatus": "ACTIVE",
                        "currentLocation": "",
                        "statusNotes": f"出场于第{chapter_no}章第{section_no}节",
                        "inventoryDelta": "",
                        "isHighRisk": False,
                    }
                    for name in mentioned[:6]
                ],
                "foreshadowingSignals": [],
                "relations": [],
                "timelineEvents": [
                    {
                        "inStoryTime": f"第{chapter_no}章第{section_no}节",
                        "eventTitle": label[:20],
                        "summary": summary_text[:60],
                        "involvedCharacters": ", ".join(mentioned[:6]),
                        "location": "",
                        "knownBy": "公开",
                    }
                ],
            },
            "meta": {
                "usedMapReduce": used_map_reduce,
                "segmentCount": len(segments),
                "sourceChars": len(content),
            },
        }
