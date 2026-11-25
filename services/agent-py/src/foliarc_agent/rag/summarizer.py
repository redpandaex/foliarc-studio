from __future__ import annotations

import hashlib
import json
import logging
from typing import Any

import httpx

from foliarc_agent.config import Settings

logger = logging.getLogger(__name__)

_CHAPTER_SUMMARY_PROMPT = """你是长篇小说剧情编审。请将给定章节提炼为高密度的【结构化单章摘要】，严格输出 JSON 对象（不要输出 Markdown 代码块）：
{
  "summaryText": "150~240字客观紧凑的本章剧情梗概（交代地点、核心冲突、关键转折与结果）",
  "keyEvents": "用分号分隔的 2~4 个关键事件短语",
  "involvedCharacters": "用逗号分隔的本章实际出场人物姓名",
  "endingHook": "一句话概括本章结尾留下的悬念、危机或下一章必须承接的画面"
}
要求：只记录正文明确发生的事实，严禁脑补或剧透未来章节。"""

_VOLUME_SUMMARY_PROMPT = """你是长篇小说总编。请将以下连续多章的单章摘要归并为一份 300~480 字的【卷级主线里程碑摘要】，清晰交代：
1. 本阶段主线推进与核心矛盾演变；
2. 主要人物的境界/立场/关系重大变化；
3. 本阶段收束与引向下一卷的重大悬念。
直接输出纯文本摘要，不要加多余标题。"""

_AUTO_CHARTER_PROMPT = """你是长篇小说架构师。作者开书时没有手填大纲或设定，请根据已写章节的摘要与出场人物，客观归纳一份 250~400 字的【作品静态档案（故事宪章）】，按以下 4 行输出：
- 核心主线：一句话概括故事背景与主角核心目标
- 叙事视角：如“第三人称限知（林青视角）”或“第一人称”
- 文风基调：如“冷峻武侠写实、节奏紧凑、注重动作细节”
- 已确立规则：已明确出现的核心人物、力量体系或世界观铁律
要求：严禁擅自编造正文未出现的设定。"""


class HierarchicalSummarizer:
    """
    Phase 5.5 Hierarchical Long-Term Memory Synthesizer:
    - Chapter Summary (L2 recent window, ~180 chars)
    - Volume Milestone Summary (L2 long-range arc, 10 chapters -> 1 milestone)
    - Zero-Config Auto-Emergent L0 Story Charter (low-frequency refresh, never overrides author charter)
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def summarize_chapter(
        self,
        *,
        chapter_no: int,
        chapter_title: str,
        chapter_content: str,
        known_characters: list[str] | None = None,
    ) -> dict[str, str]:
        if self._settings.llm_configured:
            try:
                user_msg = (
                    f"已知主要角色：{', '.join((known_characters or [])[:20]) or '暂无'}\n"
                    f"章节：第 {chapter_no} 章《{chapter_title}》\n\n"
                    f"正文：\n{chapter_content[:7500]}"
                )
                raw = await self._call_fast_llm(_CHAPTER_SUMMARY_PROMPT, user_msg, max_tokens=700)
                start = raw.find("{")
                end = raw.rfind("}")
                if start >= 0 and end > start:
                    obj = json.loads(raw[start : end + 1])
                    summary_text = str(obj.get("summaryText") or "").strip()
                    if summary_text:
                        return {
                            "summaryText": summary_text[:500],
                            "keyEvents": str(obj.get("keyEvents") or "").strip()[:400],
                            "involvedCharacters": str(obj.get("involvedCharacters") or "").strip()[:300],
                            "endingHook": str(obj.get("endingHook") or "").strip()[:260],
                        }
            except (httpx.HTTPError, KeyError, ValueError, json.JSONDecodeError) as exc:
                logger.warning("LLM chapter summarization failed, using fallback: %s", exc)

        return self._fallback_chapter_summary(
            chapter_title=chapter_title,
            chapter_content=chapter_content,
            known_characters=known_characters or [],
        )

    async def summarize_volume(
        self,
        *,
        volume_no: int,
        start_chapter_no: int,
        end_chapter_no: int,
        chapter_summaries: list[dict[str, Any]],
    ) -> tuple[str, str]:
        lines = [
            f"第 {c.get('chapterNo')} 章《{c.get('chapterTitle', '')}》：{c.get('summaryText', '')}"
            for c in chapter_summaries
        ]
        joined = "\n".join(lines)
        source_hash = hashlib.sha256(joined.encode("utf-8")).hexdigest()[:32]

        if self._settings.llm_configured and lines:
            try:
                user_msg = (
                    f"待归并卷次：第 {volume_no} 卷（第 {start_chapter_no}~{end_chapter_no} 章）\n\n"
                    f"{joined[:8000]}"
                )
                raw = await self._call_fast_llm(_VOLUME_SUMMARY_PROMPT, user_msg, max_tokens=750)
                if raw.strip():
                    return raw.strip()[:1200], source_hash
            except (httpx.HTTPError, KeyError, ValueError) as exc:
                logger.warning("LLM volume rollup failed, using fallback: %s", exc)

        fallback = "；".join(
            f"第{c.get('chapterNo')}章{str(c.get('summaryText', ''))[:70]}"
            for c in chapter_summaries
        )[:900]
        return fallback or f"第 {volume_no} 卷（第 {start_chapter_no}~{end_chapter_no} 章）剧情归档。", source_hash

    async def infer_story_charter(
        self,
        *,
        book_title: str,
        genre: str | None,
        existing_synopsis: str | None,
        chapter_summaries: list[dict[str, Any]],
        known_characters: list[str] | None = None,
    ) -> str:
        lines = [
            f"第 {c.get('chapterNo')} 章《{c.get('chapterTitle', '')}》：{c.get('summaryText', '')}"
            for c in chapter_summaries[:12]
        ]
        if self._settings.llm_configured and lines:
            try:
                user_msg = (
                    f"书名：《{book_title}》\n"
                    f"题材：{genre or '未指定'}\n"
                    f"已有简介：{existing_synopsis or '无'}\n"
                    f"已出场角色：{', '.join((known_characters or [])[:15]) or '暂无'}\n\n"
                    f"已写章节摘要：\n" + "\n".join(lines)[:6000]
                )
                raw = await self._call_fast_llm(_AUTO_CHARTER_PROMPT, user_msg, max_tokens=600)
                if raw.strip():
                    return raw.strip()[:1200]
            except (httpx.HTTPError, KeyError, ValueError) as exc:
                logger.warning("LLM auto-charter synthesis failed, using fallback: %s", exc)

        premise = (existing_synopsis or "").strip()
        if not premise and chapter_summaries:
            premise = " ".join(str(c.get("summaryText", ""))[:90] for c in chapter_summaries[:3]).strip()
        chars_str = "、".join((known_characters or [])[:8]) or "待随着剧情补充"
        return (
            f"- 核心主线：《{book_title}》{('（' + genre + '）') if genre else ''}，{premise or '围绕主角前期遭遇逐步展开主线冲突。'}\n"
            f"- 叙事视角：延续前文章节已确立的叙事人称与视角\n"
            f"- 文风基调：与前文保持一致的语言节奏与场景质感\n"
            f"- 已确立人物与线索：{chars_str}"
        )

    async def _call_fast_llm(self, system_prompt: str, user_prompt: str, *, max_tokens: int) -> str:
        url = f"{self._settings.llm_base_url.rstrip('/')}/chat/completions"
        async with httpx.AsyncClient(timeout=httpx.Timeout(45.0, connect=10.0)) as client:
            resp = await client.post(
                url,
                headers={"Authorization": f"Bearer {self._settings.llm_api_key}"},
                json={
                    "model": self._settings.llm_model_fast_router,
                    "messages": [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_prompt},
                    ],
                    "temperature": 0.2,
                    "max_tokens": max_tokens,
                },
            )
            resp.raise_for_status()
            return str(resp.json()["choices"][0]["message"]["content"]).strip()

    @staticmethod
    def _fallback_chapter_summary(
        *,
        chapter_title: str,
        chapter_content: str,
        known_characters: list[str],
    ) -> dict[str, str]:
        clean = " ".join(chapter_content.split())
        if len(clean) <= 220:
            summary_text = clean
        else:
            summary_text = f"{clean[:120]}……{clean[-100:]}"
        ending_hook = clean[-90:] if len(clean) > 90 else clean
        mentioned = [name for name in known_characters if name and name in chapter_content]
        return {
            "summaryText": summary_text,
            "keyEvents": chapter_title or "章节剧情推进",
            "involvedCharacters": ", ".join(mentioned[:8]),
            "endingHook": ending_hook,
        }
