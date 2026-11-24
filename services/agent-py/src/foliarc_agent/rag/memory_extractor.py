from __future__ import annotations

import json
import logging
from typing import Any

import httpx

from foliarc_agent.config import Settings

logger = logging.getLogger(__name__)

_MEMORY_SYSTEM_PROMPT = """你是长篇小说结构化记忆抽取器（Structured Long-Term Memory Extractor）。
给定一章小说正文与已有设定摘要，请精准抽取本章发生的**增量事实**，严格输出 JSON 对象（不要包含 Markdown 代码块或多余文字），结构如下：
{
  "characterUpdates": [
    {
      "name": "角色姓名",
      "roleType": "PROTAGONIST|ANTAGONIST|SUPPORTING",
      "personality": "性格特征（无变化可留空字符串）",
      "realmOrLevel": "当前修为/实力境界（无变化留空）",
      "currentLocation": "本章结束时所在地点",
      "statusNotes": "伤势、心境或关键状态变更",
      "inventory": "新获得或持有的关键物品/法宝"
    }
  ],
  "foreshadowingUpdates": [
    {
      "title": "简短伏笔标题（15字以内）",
      "description": "伏笔具体线索与含义",
      "plantedChapterNo": 1,
      "resolvedChapterNo": null,
      "status": "OPEN|RESOLVED"
    }
  ],
  "relations": [
    {
      "sourceCharacter": "角色A",
      "targetCharacter": "角色B",
      "relationType": "师徒|仇敌|盟友|同门|挚友|恋人|上下级|交易",
      "description": "本章体现的关系动态或具体事件",
      "sinceChapterNo": 1,
      "confidence": 0.9
    }
  ],
  "timelineEvents": [
    {
      "chapterNo": 1,
      "eventTitle": "核心事件标题（20字以内）",
      "summary": "60字以内的客观事件梗概",
      "involvedCharacters": "角色A, 角色B",
      "location": "发生地点"
    }
  ]
}
规则：
1. 只抽取正文中明确发生或提及的事实，严禁脑补未出场角色。
2. 每类最多返回 6 条最高价值记录；若本章无新增可返回空数组 []。"""


class StructuredMemoryExtractor:
    """Extracts characters, foreshadowings, character relations, and timeline events from a chapter."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def extract(
        self,
        *,
        chapter_no: int,
        chapter_title: str,
        chapter_content: str,
        existing_bible: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        if self._settings.llm_configured:
            try:
                result = await self._extract_with_llm(
                    chapter_no=chapter_no,
                    chapter_title=chapter_title,
                    chapter_content=chapter_content,
                    existing_bible=existing_bible,
                )
                if result is not None:
                    return result
            except (httpx.HTTPError, KeyError, ValueError, json.JSONDecodeError) as exc:
                logger.warning("LLM structured memory extraction failed, using fallback: %s", exc)
        return self._fallback_extract(
            chapter_no=chapter_no,
            chapter_title=chapter_title,
            chapter_content=chapter_content,
            existing_bible=existing_bible,
        )

    async def _extract_with_llm(
        self,
        *,
        chapter_no: int,
        chapter_title: str,
        chapter_content: str,
        existing_bible: dict[str, Any] | None,
    ) -> dict[str, Any] | None:
        bible_summary = ""
        if existing_bible:
            known_chars = [c.get("name", "") for c in existing_bible.get("characters", []) if c.get("name")]
            open_fs = [f.get("title", "") for f in existing_bible.get("openForeshadowings", []) if f.get("title")]
            bible_summary = (
                f"已知角色：{', '.join(known_chars[:20]) or '无'}\n"
                f"未回收伏笔：{', '.join(open_fs[:15]) or '无'}\n"
            )

        user_prompt = (
            f"{bible_summary}\n"
            f"当前章节：第 {chapter_no} 章《{chapter_title}》\n\n"
            f"章节正文：\n{chapter_content[:7000]}"
        )
        url = f"{self._settings.llm_base_url.rstrip('/')}/chat/completions"
        async with httpx.AsyncClient(timeout=httpx.Timeout(60.0, connect=10.0)) as client:
            resp = await client.post(
                url,
                headers={"Authorization": f"Bearer {self._settings.llm_api_key}"},
                json={
                    "model": self._settings.llm_model_fast_router,
                    "messages": [
                        {"role": "system", "content": _MEMORY_SYSTEM_PROMPT},
                        {"role": "user", "content": user_prompt},
                    ],
                    "temperature": 0.1,
                    "max_tokens": 1600,
                },
            )
            resp.raise_for_status()
            raw = resp.json()["choices"][0]["message"]["content"].strip()

        start = raw.find("{")
        end = raw.rfind("}")
        if start < 0 or end <= start:
            return None
        parsed = json.loads(raw[start : end + 1])
        return self._normalize(parsed, chapter_no)

    @staticmethod
    def _normalize(parsed: dict[str, Any], chapter_no: int) -> dict[str, Any]:
        chars = []
        for c in parsed.get("characterUpdates") or []:
            name = str(c.get("name", "")).strip()
            if not name:
                continue
            item: dict[str, Any] = {"name": name}
            if c.get("roleType") in ("PROTAGONIST", "ANTAGONIST", "SUPPORTING"):
                item["roleType"] = c["roleType"]
            for field in ("personality", "realmOrLevel", "currentLocation", "statusNotes", "inventory"):
                val = c.get(field)
                if isinstance(val, str) and val.strip():
                    item[field] = val.strip()
            chars.append(item)

        foreshadows = []
        for f in parsed.get("foreshadowingUpdates") or []:
            title = str(f.get("title", "")).strip()
            if not title:
                continue
            status = str(f.get("status", "OPEN")).upper()
            if status not in ("OPEN", "RESOLVED", "ABANDONED"):
                status = "OPEN"
            foreshadows.append(
                {
                    "title": title[:160],
                    "description": str(f.get("description", "")).strip(),
                    "plantedChapterNo": f.get("plantedChapterNo") or (chapter_no if status == "OPEN" else None),
                    "resolvedChapterNo": f.get("resolvedChapterNo") or (chapter_no if status == "RESOLVED" else None),
                    "status": status,
                }
            )

        relations = []
        for r in parsed.get("relations") or []:
            src = str(r.get("sourceCharacter", "")).strip()
            dst = str(r.get("targetCharacter", "")).strip()
            rel = str(r.get("relationType", "")).strip()
            if not (src and dst and rel):
                continue
            relations.append(
                {
                    "sourceCharacter": src[:120],
                    "targetCharacter": dst[:120],
                    "relationType": rel[:60],
                    "description": str(r.get("description", "")).strip(),
                    "sinceChapterNo": r.get("sinceChapterNo") or chapter_no,
                    "confidence": float(r.get("confidence", 0.85)),
                }
            )

        timeline = []
        for t in parsed.get("timelineEvents") or []:
            title = str(t.get("eventTitle", "")).strip()
            if not title:
                continue
            timeline.append(
                {
                    "chapterNo": int(t.get("chapterNo") or chapter_no),
                    "eventTitle": title[:250],
                    "summary": str(t.get("summary", "")).strip(),
                    "involvedCharacters": str(t.get("involvedCharacters", "")).strip(),
                    "location": str(t.get("location", "")).strip(),
                }
            )

        return {
            "chapterNo": chapter_no,
            "characterUpdates": chars[:6],
            "foreshadowingUpdates": foreshadows[:6],
            "relations": relations[:6],
            "timelineEvents": timeline[:6],
        }

    @staticmethod
    def _fallback_extract(
        *,
        chapter_no: int,
        chapter_title: str,
        chapter_content: str,
        existing_bible: dict[str, Any] | None,
    ) -> dict[str, Any]:
        known = [
            c.get("name", "")
            for c in (existing_bible or {}).get("characters", [])
            if c.get("name") and c["name"] in chapter_content
        ]
        summary = chapter_content.replace("\n", " ").strip()[:120]
        return {
            "chapterNo": chapter_no,
            "characterUpdates": [],
            "foreshadowingUpdates": [],
            "relations": [],
            "timelineEvents": [
                {
                    "chapterNo": chapter_no,
                    "eventTitle": (chapter_title or f"第{chapter_no}章事件")[:200],
                    "summary": summary,
                    "involvedCharacters": ", ".join(known[:6]),
                    "location": "",
                }
            ],
        }
