from __future__ import annotations

import asyncio
import difflib
import json
import logging
import re
from typing import Any

import httpx

from foliarc_agent.agent.genre_profiles import GenreProfileRegistry
from foliarc_agent.config import Settings

logger = logging.getLogger(__name__)

_FACT_DIFF_SYSTEM_PROMPT = """你是长篇小说蝴蝶效应分析引擎（Stage 1 · Fact Diff 原子事实提取器）。
请对比作者修改某一章节前后的文本差异，严格甄别：
1. 若修改仅为错别字修正、标点调整、修辞润色、语序微调（不改变人物生死/位置/关系/道具归属/设定规则/时间线/人名地名），必须返回 `"hasSubstantiveFactChange": false` 并清空 `factDeltas`。
2. 若存在实质事实变动，提炼结构化 `factDeltas` 数组，每项包含：
   - `type`: CHARACTER_STATE / ITEM_OWNERSHIP / WORLD_RULE / RELATIONSHIP / TIMELINE / NAME_OR_TITLE
   - `entity`: 核心涉事人物、法宝、势力或规则名称
   - `oldFact`: 修改前的旧事实（尽量简明准确）
   - `newFact`: 修改后的新事实
   - `searchKeywords`: 3~6 个用于在全书检索相关段落的关键词数组（含旧事实关键词与涉事实体）
严格输出 JSON 对象（不要输出 Markdown 代码块）：
{"hasSubstantiveFactChange": true/false, "changeSummary": "一句话总结核心改动", "factDeltas": [...]}"""

_IMPACT_CLASSIFY_SYSTEM_PROMPT = """你是长篇小说双向蝴蝶效应影响雷达（Stage 2 · Impact Radar 冲突判别器）。
请对照【核心事实变更清单】与【目标章节信息】，结合扫描方向（BACKWARD 前文倒推 vs FORWARD 后文顺延）进行严格逻辑审阅：
- 若方向为 `BACKWARD`（目标章节在修改章节之前）：判断前文已写事实是否与新设定直接矛盾，或前文是否缺少合理铺垫/称谓不统一。
- 若方向为 `FORWARD`（目标章节在修改章节之后）：判断后文剧情、人物生死/状态、物品流向、规则或对话是否因前文改动产生硬逻辑穿帮。
输出严格 JSON 对象（不要输出 Markdown 代码块）：
{"severity": "STRONG_CONFLICT" | "MINOR_MENTION" | "UNAFFECTED", "conflictReason": "40字以内明确冲突点（注明前文铺垫矛盾或后文剧情冲突）", "matchedExcerpt": "目标正文中发生冲突或提及的1~2句原话"}"""

_SURGICAL_PATCH_SYSTEM_PROMPT = """你是长篇小说最小手术级修订专家（Stage 3 · Sparse Surgical Patch）。
请根据【核心事实变更清单】和【冲突说明】，对【目标章节正文】做出**自然段级别的最小必要修改**：
硬规则：
1. 仅输出真正需要改动的自然段 Patch Hunk，无关段落绝对不要触碰或重写！
2. 每个 Hunk 的 `originalParagraph` 必须与目标正文中的某一完整段落（或连续句子）**逐字完全一致**，便于程序精确锚定替换。
3. `patchedParagraph` 必须保持原作者的叙事节奏、人称视角、题材文风与相近字数，严禁加入 AI 套话。
4. 若方向为 `BACKWARD`（前文），侧重统一旧设定/称谓或补足轻盈铺垫；若为 `FORWARD`（后文），侧重消除硬逻辑矛盾并顺滑衔接后续剧情。
严格输出 JSON 对象（不要输出 Markdown 代码块）：
{"hunks": [{"originalParagraph": "原文完整段落", "patchedParagraph": "最小改动后段落", "reason": "修改原因"}]}"""

_PUNCT_SPACE_RE = re.compile(r"[\s，。！？、；：“”‘’\"'（）()【】《》—…·.,!?;:\-]+")


def extract_text_diff_hunks(
    old_content: str,
    new_content: str,
    *,
    context_chars: int = 200,
) -> list[dict[str, str]]:
    """Extracts paragraph-level changed blocks with surrounding context using difflib."""
    old_lines = [line.strip() for line in (old_content or "").splitlines() if line.strip()]
    new_lines = [line.strip() for line in (new_content or "").splitlines() if line.strip()]
    matcher = difflib.SequenceMatcher(None, old_lines, new_lines)
    hunks: list[dict[str, str]] = []
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == "equal":
            continue
        old_block = "\n".join(old_lines[i1:i2]).strip()
        new_block = "\n".join(new_lines[j1:j2]).strip()
        if _PUNCT_SPACE_RE.sub("", old_block) == _PUNCT_SPACE_RE.sub("", new_block):
            continue
        ctx_before = "\n".join(new_lines[max(0, j1 - 1) : j1])[-context_chars:]
        ctx_after = "\n".join(new_lines[j2 : min(len(new_lines), j2 + 1)])[:context_chars]
        hunks.append(
            {
                "tag": tag,
                "old": old_block,
                "new": new_block,
                "context_before": ctx_before,
                "context_after": ctx_after,
            }
        )
    return hunks


def _extract_tokens(text: str) -> list[str]:
    clean = _PUNCT_SPACE_RE.sub(" ", text or "").strip()
    if not clean:
        return []
    tokens: list[str] = []
    for part in clean.split():
        if len(part) >= 2:
            tokens.append(part)
    return list(dict.fromkeys(tokens))


def _diff_changed_phrases(old_text: str, new_text: str) -> tuple[str, str, list[str]]:
    """Finds differing substring pairs and unchanged anchor tokens adjacent to the edit."""
    sm = difflib.SequenceMatcher(None, old_text, new_text)
    old_parts: list[str] = []
    new_parts: list[str] = []
    anchors: list[str] = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            seg = old_text[i1:i2]
            anchors.extend(_extract_tokens(seg))
            continue
        if i2 > i1:
            old_parts.append(old_text[i1:i2].strip())
        if j2 > j1:
            new_parts.append(new_text[j1:j2].strip())
    return (
        " ".join(p for p in old_parts if p),
        " ".join(p for p in new_parts if p),
        list(dict.fromkeys(anchors)),
    )


def validate_patch_hunks(content: str, hunks: list[dict[str, str]]) -> tuple[bool, list[dict[str, str]]]:
    """Verifies every hunk anchors verbatim in target content and changes text cleanly."""
    if not hunks:
        return False, []
    valid_hunks: list[dict[str, str]] = []
    working = content or ""
    for hunk in hunks:
        orig = str(hunk.get("originalParagraph") or "").strip()
        patched = str(hunk.get("patchedParagraph") or "").strip()
        reason = str(hunk.get("reason") or "").strip()
        if not orig or not patched or orig == patched:
            return False, []
        idx = working.find(orig)
        if idx < 0:
            return False, []
        working = working[:idx] + patched + working[idx + len(orig) :]
        valid_hunks.append(
            {
                "originalParagraph": orig,
                "patchedParagraph": patched,
                "reason": reason or "保持前后文事实一致",
            }
        )
    return True, valid_hunks


class RippleEngine:
    """
    Phase 5.8D Bidirectional Self-Selected Range Ripple Edit Engine:
    - Stage 1: Atomic Fact Diff extraction (filters pure typo/polish edits)
    - Stage 2: Bidirectional Impact Radar (BACKWARD setup/contradiction vs FORWARD plot conflict)
    - Stage 3: Sparse Surgical Patch generation (paragraph-level verbatim-anchored hunks)
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def extract_fact_deltas(
        self,
        *,
        old_content: str,
        new_content: str,
        change_note: str = "",
        genre_code: str = "",
    ) -> dict[str, Any]:
        hunks = extract_text_diff_hunks(old_content, new_content)
        note = (change_note or "").strip()
        if not hunks and not note:
            return {
                "hasSubstantiveFactChange": False,
                "changeSummary": "未检测到实质事实变更（正文一致或仅空白/标点调整）",
                "factDeltas": [],
                "inputTokens": 0,
                "outputTokens": 0,
            }

        if self._settings.llm_configured:
            try:
                profile = GenreProfileRegistry.resolve(genre_code or None)
                diff_lines = [
                    f"[变更块 {idx + 1}]\n旧文：{h['old'][:600]}\n新文：{h['new'][:600]}"
                    for idx, h in enumerate(hunks[:10])
                ]
                user_msg = (
                    f"作品题材：{profile.display_name}\n"
                    f"作者改动说明：{note or '（未填写）'}\n\n"
                    + ("\n\n".join(diff_lines) if diff_lines else f"新正文摘录：{new_content[:800]}")
                )
                raw, in_tok, out_tok = await self._call_llm(
                    _FACT_DIFF_SYSTEM_PROMPT,
                    user_msg,
                    max_tokens=900,
                    json_mode=True,
                )
                parsed = self._parse_json(raw)
                if parsed is not None:
                    deltas = self._normalize_deltas(parsed.get("factDeltas") or [])
                    has_change = bool(parsed.get("hasSubstantiveFactChange", bool(deltas)))
                    return {
                        "hasSubstantiveFactChange": has_change and bool(deltas),
                        "changeSummary": str(parsed.get("changeSummary") or note or "检测到实质设定变更").strip()[:400],
                        "factDeltas": deltas if has_change else [],
                        "inputTokens": in_tok,
                        "outputTokens": out_tok,
                    }
            except (httpx.HTTPError, KeyError, ValueError, json.JSONDecodeError) as exc:
                logger.warning("Ripple Stage 1 LLM failed, falling back deterministically: %s", exc)

        return self._fallback_extract_fact_deltas(hunks, note)

    async def evaluate_impacts(
        self,
        *,
        fact_deltas: list[dict[str, Any]],
        change_summary: str,
        source_order: int,
        candidates: list[dict[str, Any]],
        genre_code: str = "",
        concurrency: int = 10,
    ) -> tuple[list[dict[str, Any]], int, int]:
        if not fact_deltas or not candidates:
            return [], 0, 0

        sem = asyncio.Semaphore(max(1, concurrency))
        total_in = 0
        total_out = 0
        deltas_json = json.dumps(fact_deltas, ensure_ascii=False)
        profile = GenreProfileRegistry.resolve(genre_code or None)

        async def _check_one(cand: dict[str, Any]) -> dict[str, Any] | None:
            nonlocal total_in, total_out
            target_order = int(cand.get("narrativeOrder") or 0)
            direction = "BACKWARD" if target_order < source_order else "FORWARD"
            content = str(cand.get("content") or "")
            summary = str(cand.get("summaryText") or "")

            if self._settings.llm_configured:
                async with sem:
                    try:
                        dir_hint = (
                            "前文倒推（BACKWARD）：核查是否与新设定冲突或缺少铺垫"
                            if direction == "BACKWARD"
                            else "后文顺延（FORWARD）：核查后续剧情/状态/物品是否逻辑穿帮"
                        )
                        user_msg = (
                            f"题材：{profile.display_name}\n"
                            f"扫描方向：{dir_hint}\n"
                            f"核心改动摘要：{change_summary}\n"
                            f"事实变更清单：{deltas_json[:1800]}\n\n"
                            f"目标章节：第 {cand.get('chapterNo')} 章第 {cand.get('sectionNo', 1)} 节《{cand.get('chapterTitle', '')}》\n"
                            f"章节摘要：{summary[:500]}\n"
                            f"章节正文片段：\n{content[:3200]}"
                        )
                        raw, in_tok, out_tok = await self._call_llm(
                            _IMPACT_CLASSIFY_SYSTEM_PROMPT,
                            user_msg,
                            max_tokens=300,
                            json_mode=True,
                        )
                        total_in += in_tok
                        total_out += out_tok
                        parsed = self._parse_json(raw)
                        if parsed is not None:
                            severity = str(parsed.get("severity") or "UNAFFECTED").upper()
                            if severity not in ("STRONG_CONFLICT", "MINOR_MENTION", "UNAFFECTED"):
                                severity = "UNAFFECTED"
                            if severity == "UNAFFECTED":
                                return None
                            return {
                                "targetSectionId": str(cand["sectionId"]),
                                "chapterNo": int(cand.get("chapterNo") or 0),
                                "sectionNo": int(cand.get("sectionNo") or 1),
                                "narrativeOrder": target_order,
                                "chapterTitle": str(cand.get("chapterTitle") or ""),
                                "direction": direction,
                                "severity": severity,
                                "conflictReason": str(parsed.get("conflictReason") or "").strip()[:300],
                                "matchedExcerpt": str(parsed.get("matchedExcerpt") or "").strip()[:400],
                            }
                    except (httpx.HTTPError, KeyError, ValueError, json.JSONDecodeError) as exc:
                        logger.warning("Ripple Stage 2 LLM check failed for %s: %s", cand.get("sectionId"), exc)

            return self._fallback_classify_impact(cand, fact_deltas, direction)

        results = await asyncio.gather(*(_check_one(c) for c in candidates))
        impacts = [r for r in results if r is not None]
        impacts.sort(key=lambda x: (0 if x["severity"] == "STRONG_CONFLICT" else 1, x["narrativeOrder"]))
        return impacts, total_in, total_out

    async def generate_surgical_patches(
        self,
        *,
        fact_deltas: list[dict[str, Any]],
        change_summary: str,
        targets: list[dict[str, Any]],
        genre_code: str = "",
        style_anchors_text: str = "",
        concurrency: int = 8,
    ) -> tuple[list[dict[str, Any]], int, int]:
        if not targets:
            return [], 0, 0

        sem = asyncio.Semaphore(max(1, concurrency))
        total_in = 0
        total_out = 0
        profile = GenreProfileRegistry.resolve(genre_code or None)
        deltas_json = json.dumps(fact_deltas, ensure_ascii=False)

        async def _patch_one(target: dict[str, Any]) -> dict[str, Any]:
            nonlocal total_in, total_out
            patch_id = str(target["patchId"])
            content = str(target.get("currentContent") or "")
            direction = str(target.get("direction") or "FORWARD").upper()
            conflict_reason = str(target.get("conflictReason") or "")

            if self._settings.llm_configured and content.strip():
                async with sem:
                    try:
                        sys_prompt = _SURGICAL_PATCH_SYSTEM_PROMPT + "\n\n" + profile.format_writer_guard_block()
                        if style_anchors_text.strip():
                            sys_prompt += (
                                "\n\n【本书文风锚点（修订段落须保持一致文风）】\n"
                                + style_anchors_text.strip()[:900]
                            )
                        user_msg = (
                            f"修订方向：{direction}（{'前文铺垫统一' if direction == 'BACKWARD' else '后文逻辑修复'}）\n"
                            f"事实变更摘要：{change_summary}\n"
                            f"原子变更清单：{deltas_json[:1800]}\n"
                            f"本章冲突说明：{conflict_reason}\n\n"
                            f"【目标章节完整正文（第 {target.get('chapterNo')} 章《{target.get('chapterTitle', '')}》）】\n"
                            f"{content[:10000]}"
                        )
                        raw, in_tok, out_tok = await self._call_llm(
                            sys_prompt,
                            user_msg,
                            max_tokens=1600,
                            json_mode=True,
                        )
                        total_in += in_tok
                        total_out += out_tok
                        parsed = self._parse_json(raw)
                        if parsed is not None:
                            hunks = list(parsed.get("hunks") or [])
                            ok, valid = validate_patch_hunks(content, hunks)
                            return {
                                "patchId": patch_id,
                                "status": "READY_FOR_REVIEW" if ok else "MANUAL_REVIEW_REQUIRED",
                                "hunks": valid if ok else hunks,
                            }
                    except (httpx.HTTPError, KeyError, ValueError, json.JSONDecodeError) as exc:
                        logger.warning("Ripple Stage 3 LLM patch failed for %s: %s", patch_id, exc)

            return self._fallback_generate_patch(target, fact_deltas)

        results = await asyncio.gather(*(_patch_one(t) for t in targets))
        return list(results), total_in, total_out

    async def _call_llm(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        max_tokens: int,
        json_mode: bool = True,
    ) -> tuple[str, int, int]:
        url = f"{self._settings.llm_base_url.rstrip('/')}/chat/completions"
        payload: dict[str, Any] = {
            "model": self._settings.llm_model_fast_router,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": 0.15,
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
            data = resp.json()
            text = str(data["choices"][0]["message"]["content"]).strip()
            usage = data.get("usage") or {}
            return text, int(usage.get("prompt_tokens") or 0), int(usage.get("completion_tokens") or 0)

    @staticmethod
    def _parse_json(raw: str) -> dict[str, Any] | None:
        start = raw.find("{")
        end = raw.rfind("}")
        if start < 0 or end <= start:
            return None
        obj = json.loads(raw[start : end + 1])
        return obj if isinstance(obj, dict) else None

    @staticmethod
    def _normalize_deltas(raw_list: list[Any]) -> list[dict[str, Any]]:
        normalized: list[dict[str, Any]] = []
        for item in raw_list[:10]:
            if not isinstance(item, dict):
                continue
            old_fact = str(item.get("oldFact") or "").strip()
            new_fact = str(item.get("newFact") or "").strip()
            entity = str(item.get("entity") or "").strip()
            if not (old_fact or new_fact or entity):
                continue
            kws_raw = item.get("searchKeywords") or []
            kws = [str(k).strip() for k in kws_raw if str(k).strip()] if isinstance(kws_raw, list) else []
            if entity and entity not in kws:
                kws.insert(0, entity)
            normalized.append(
                {
                    "type": str(item.get("type") or "CHARACTER_STATE").strip().upper(),
                    "entity": entity or "关键设定",
                    "oldFact": old_fact,
                    "newFact": new_fact,
                    "searchKeywords": list(dict.fromkeys(kws))[:8],
                }
            )
        return normalized

    @staticmethod
    def _fallback_extract_fact_deltas(hunks: list[dict[str, str]], change_note: str) -> dict[str, Any]:
        deltas: list[dict[str, Any]] = []
        for h in hunks[:6]:
            old_b = h["old"]
            new_b = h["new"]
            old_phrase, new_phrase, shared_anchors = _diff_changed_phrases(old_b, new_b)
            # Detect pure typo / minor polish when diff is 1-char edit with no author change_note
            if not change_note and old_b and new_b:
                ratio = difflib.SequenceMatcher(None, old_b, new_b).ratio()
                if ratio >= 0.96 and len(old_phrase) <= 1 and len(new_phrase) <= 1:
                    continue
            old_tokens = _extract_tokens(old_phrase or old_b)
            # Prefer anchor immediately preceding the change (usually the subject entity)
            entity = (
                shared_anchors[-1]
                if shared_anchors
                else (old_tokens[0] if old_tokens else "核心设定")
            )
            keywords = list(
                dict.fromkeys([*shared_anchors, *(old_tokens[:4]), *(_extract_tokens(new_phrase)[:2])])
            )
            deltas.append(
                {
                    "type": "CHARACTER_STATE",
                    "entity": entity[:40],
                    "oldFact": (old_phrase or old_b)[:120],
                    "newFact": (new_phrase or new_b)[:120],
                    "searchKeywords": [k for k in keywords if k][:6],
                }
            )

        if not deltas and change_note:
            note_tokens = _extract_tokens(change_note)
            deltas.append(
                {
                    "type": "WORLD_RULE",
                    "entity": note_tokens[0] if note_tokens else "作者声明改动",
                    "oldFact": "",
                    "newFact": change_note[:160],
                    "searchKeywords": note_tokens[:6] or [change_note[:20]],
                }
            )

        has_change = bool(deltas)
        summary = (
            change_note
            if change_note
            else (
                f"检测到 {len(deltas)} 处实质设定变动（{deltas[0]['entity']}：{deltas[0]['oldFact']} → {deltas[0]['newFact']}）"
                if deltas
                else "未检测到实质事实变更（仅润色或错别字修订）"
            )
        )
        return {
            "hasSubstantiveFactChange": has_change,
            "changeSummary": summary[:400],
            "factDeltas": deltas,
            "inputTokens": 0,
            "outputTokens": 0,
        }

    @staticmethod
    def _fallback_classify_impact(
        cand: dict[str, Any],
        fact_deltas: list[dict[str, Any]],
        direction: str,
    ) -> dict[str, Any] | None:
        content = str(cand.get("content") or "")
        summary = str(cand.get("summaryText") or "")
        haystack = f"{content}\n{summary}"
        paragraphs = [p.strip() for p in content.splitlines() if p.strip()]

        def _find_excerpt(needle: str) -> str:
            for p in paragraphs:
                if needle in p:
                    return p[:280]
            return summary[:280]

        for delta in fact_deltas:
            old_fact = str(delta.get("oldFact") or "").strip()
            entity = str(delta.get("entity") or "").strip()
            old_tokens = [t for t in [old_fact, *_extract_tokens(old_fact)] if len(t) >= 2]
            for tok in old_tokens:
                if tok in haystack:
                    prefix = "前文铺垫/旧事实矛盾" if direction == "BACKWARD" else "后文剧情逻辑冲突"
                    return {
                        "targetSectionId": str(cand["sectionId"]),
                        "chapterNo": int(cand.get("chapterNo") or 0),
                        "sectionNo": int(cand.get("sectionNo") or 1),
                        "narrativeOrder": int(cand.get("narrativeOrder") or 0),
                        "chapterTitle": str(cand.get("chapterTitle") or ""),
                        "direction": direction,
                        "severity": "STRONG_CONFLICT",
                        "conflictReason": f"{prefix}：正文仍沿用旧事实「{tok[:30]}」，与新设定「{str(delta.get('newFact') or '')[:30]}」矛盾",
                        "matchedExcerpt": _find_excerpt(tok),
                    }
            if entity and len(entity) >= 2 and entity in haystack:
                return {
                    "targetSectionId": str(cand["sectionId"]),
                    "chapterNo": int(cand.get("chapterNo") or 0),
                    "sectionNo": int(cand.get("sectionNo") or 1),
                    "narrativeOrder": int(cand.get("narrativeOrder") or 0),
                    "chapterTitle": str(cand.get("chapterTitle") or ""),
                    "direction": direction,
                    "severity": "MINOR_MENTION",
                    "conflictReason": f"提及涉事对象「{entity}」，建议复核措辞与语气",
                    "matchedExcerpt": _find_excerpt(entity),
                }
        return None

    @staticmethod
    def _fallback_generate_patch(
        target: dict[str, Any],
        fact_deltas: list[dict[str, Any]],
    ) -> dict[str, Any]:
        patch_id = str(target["patchId"])
        content = str(target.get("currentContent") or "")
        direction = str(target.get("direction") or "FORWARD").upper()
        paragraphs = [p.strip() for p in content.splitlines() if p.strip()]
        hunks: list[dict[str, str]] = []

        for para in paragraphs:
            updated = para
            reasons: list[str] = []
            for delta in fact_deltas:
                old_fact = str(delta.get("oldFact") or "").strip()
                new_fact = str(delta.get("newFact") or "").strip()
                if not old_fact or not new_fact or old_fact == new_fact:
                    continue
                if old_fact in updated:
                    updated = updated.replace(old_fact, new_fact)
                    tag = "统一前文铺垫" if direction == "BACKWARD" else "修复后文逻辑冲突"
                    reasons.append(f"{tag}：「{old_fact[:24]}」→「{new_fact[:24]}」")
            if updated != para:
                hunks.append(
                    {
                        "originalParagraph": para,
                        "patchedParagraph": updated,
                        "reason": "；".join(reasons),
                    }
                )

        ok, valid = validate_patch_hunks(content, hunks)
        return {
            "patchId": patch_id,
            "status": "READY_FOR_REVIEW" if ok else "MANUAL_REVIEW_REQUIRED",
            "hunks": valid,
        }
