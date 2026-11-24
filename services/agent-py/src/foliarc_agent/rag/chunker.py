from __future__ import annotations

import json
import re
import uuid
from dataclasses import dataclass

from foliarc_agent.agent.context import TokenCounter
from foliarc_agent.rag.parsers import ParsedSection

_TOKEN_COUNTER = TokenCounter()


@dataclass(frozen=True, slots=True)
class ChunkRecord:
    id: str
    chunk_level: str  # "PARENT" | "CHILD"
    parent_chunk_id: str | None
    chunk_index: int
    chapter_no: int
    scene_title: str
    content: str
    token_count: int
    metadata_json: str


_DIALOGUE_SENTENCE_SPLIT_RE = re.compile(
    r"(?<=[。！？!?；;])(?![”’」』\"])"
)


def _split_dialogue_safe_units(paragraph: str) -> list[str]:
    """
    Split a long paragraph into sentence units while NEVER cutting inside Chinese or
    English dialogue quotes (`“...”`, `「...」`, `『...』`, `"..."`).
    """
    text = paragraph.strip()
    if not text:
        return []
    units: list[str] = []
    buf: list[str] = []
    quote_depth = 0
    ascii_quote_open = False
    openers = {"“", "「", "『"}
    closers = {"”", "」", "』"}
    end_punct = {"。", "！", "？", "!", "?", "；", ";"}

    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        buf.append(ch)
        if ch in openers:
            quote_depth += 1
        elif ch in closers:
            quote_depth = max(0, quote_depth - 1)
        elif ch == '"':
            ascii_quote_open = not ascii_quote_open

        in_quote = quote_depth > 0 or ascii_quote_open
        if not in_quote and ch in end_punct:
            # Consume trailing closing quotes attached to sentence end
            while i + 1 < n and text[i + 1] in closers:
                i += 1
                buf.append(text[i])
            unit = "".join(buf).strip()
            if unit:
                units.append(unit)
            buf.clear()
        i += 1

    rem = "".join(buf).strip()
    if rem:
        units.append(rem)
    return units or [text]


def _pack_units(units: list[str], max_chars: int, overlap_chars: int) -> list[str]:
    """Pack dialogue-safe sentence/paragraph units into bounded chunks with trailing overlap."""
    chunks: list[str] = []
    current: list[str] = []
    current_len = 0

    for unit in units:
        u_len = len(unit)
        if current and current_len + u_len + 1 > max_chars:
            joined = "\n".join(current).strip()
            if joined:
                chunks.append(joined)
            if overlap_chars > 0 and current:
                tail: list[str] = []
                tail_len = 0
                for prev in reversed(current):
                    if tail_len + len(prev) > overlap_chars and tail:
                        break
                    tail.insert(0, prev)
                    tail_len += len(prev) + 1
                current = tail
                current_len = tail_len
            else:
                current = []
                current_len = 0

        if u_len > max_chars:
            # Split oversized single unit along sentence boundaries safely
            sub_units = _split_dialogue_safe_units(unit)
            if len(sub_units) > 1:
                for su in sub_units:
                    if current and current_len + len(su) + 1 > max_chars:
                        chunks.append("\n".join(current).strip())
                        current = []
                        current_len = 0
                    current.append(su)
                    current_len += len(su) + 1
            else:
                # Hard wrap only if a single uninterrupted span exceeds max_chars
                step = max(80, max_chars - overlap_chars)
                for start in range(0, u_len, step):
                    piece = unit[start : start + max_chars].strip()
                    if piece:
                        chunks.append(piece)
        else:
            current.append(unit)
            current_len += u_len + 1

    if current:
        joined = "\n".join(current).strip()
        if joined:
            chunks.append(joined)
    return chunks


def build_parent_child_chunks(
    sections: list[ParsedSection],
    *,
    chapter_no: int = 0,
    doc_type: str = "WORLDBOOK",
    doc_title: str = "",
    parent_max_chars: int = 1100,
    child_max_chars: int = 320,
    child_overlap_chars: int = 60,
) -> tuple[list[ChunkRecord], list[ChunkRecord]]:
    """
    Novel-aware Parent-Child (Small-to-Big) chunking:
    - Parent chunks (~1100 chars): preserve complete scene/section context for LLM generation.
    - Child chunks (~320 chars): precise dense + BM25 retrieval units pointing to `parent_chunk_id`,
      never splitting mid-dialogue.
    """
    parents: list[ChunkRecord] = []
    children: list[ChunkRecord] = []
    parent_idx = 0
    child_idx = 0

    for section in sections:
        paragraphs = [p.strip() for p in re.split(r"\n{2,}", section.content) if p.strip()]
        if not paragraphs:
            continue

        parent_texts = _pack_units(paragraphs, max_chars=parent_max_chars, overlap_chars=0)
        for p_text in parent_texts:
            parent_id = str(uuid.uuid4())
            p_meta = json.dumps(
                {
                    "doc_title": doc_title,
                    "doc_type": doc_type,
                    "chapter_no": chapter_no,
                    "scene_title": section.title,
                    "page_no": section.page_no,
                },
                ensure_ascii=False,
            )
            parents.append(
                ChunkRecord(
                    id=parent_id,
                    chunk_level="PARENT",
                    parent_chunk_id=None,
                    chunk_index=parent_idx,
                    chapter_no=chapter_no,
                    scene_title=section.title,
                    content=p_text,
                    token_count=_TOKEN_COUNTER.count_text(p_text),
                    metadata_json=p_meta,
                )
            )
            parent_idx += 1

            p_units: list[str] = []
            for para in p_text.splitlines():
                if para.strip():
                    p_units.extend(_split_dialogue_safe_units(para))

            child_texts = _pack_units(
                p_units, max_chars=child_max_chars, overlap_chars=child_overlap_chars
            )
            for c_text in child_texts:
                children.append(
                    ChunkRecord(
                        id=str(uuid.uuid4()),
                        chunk_level="CHILD",
                        parent_chunk_id=parent_id,
                        chunk_index=child_idx,
                        chapter_no=chapter_no,
                        scene_title=section.title,
                        content=c_text,
                        token_count=_TOKEN_COUNTER.count_text(c_text),
                        metadata_json=p_meta,
                    )
                )
                child_idx += 1

    return parents, children
