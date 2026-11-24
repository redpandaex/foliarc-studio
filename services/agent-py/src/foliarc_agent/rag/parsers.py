from __future__ import annotations

import io
import re
from dataclasses import dataclass
from pathlib import Path

import pymupdf
from docx import Document as DocxDocument


@dataclass(frozen=True, slots=True)
class ParsedSection:
    title: str
    content: str
    page_no: int | None = None


def parse_document_bytes(file_name: str, data: bytes) -> list[ParsedSection]:
    """
    Multi-format document parser supporting PDF (PyMuPDF), Word (.docx),
    Markdown (.md), and Plain Text (.txt) while preserving headings & tables.
    """
    ext = Path(file_name).suffix.lower()
    if ext == ".pdf":
        return _parse_pdf(data)
    if ext == ".docx":
        return _parse_docx(data)
    return _parse_text_or_markdown(data.decode("utf-8", errors="replace"))


def _parse_pdf(data: bytes) -> list[ParsedSection]:
    sections: list[ParsedSection] = []
    with pymupdf.open(stream=data, filetype="pdf") as doc:
        for idx, page in enumerate(doc, start=1):
            parts: list[str] = []
            text = (page.get_text("text") or "").strip()
            if text:
                parts.append(text)
            # Extract tables cleanly when present
            try:
                finder = page.find_tables()
                for table in finder.tables:
                    rows = table.extract()
                    md_rows = []
                    for row in rows:
                        cells = [str(c or "").replace("\n", " ").strip() for c in row]
                        if any(cells):
                            md_rows.append("| " + " | ".join(cells) + " |")
                    if md_rows:
                        parts.append("\n".join(md_rows))
            except (RuntimeError, ValueError, AttributeError):
                pass
            combined = "\n\n".join(p for p in parts if p).strip()
            if combined:
                first_line = combined.splitlines()[0].strip()[:60]
                title = first_line if len(first_line) <= 45 else f"第 {idx} 页"
                sections.append(ParsedSection(title=title, content=combined, page_no=idx))
    return sections


def _parse_docx(data: bytes) -> list[ParsedSection]:
    doc = DocxDocument(io.BytesIO(data))
    sections: list[ParsedSection] = []
    current_title = "正文"
    buf: list[str] = []

    def flush() -> None:
        text = "\n\n".join(b for b in buf if b.strip()).strip()
        if text:
            sections.append(ParsedSection(title=current_title, content=text))
        buf.clear()

    for para in doc.paragraphs:
        text = para.text.strip()
        if not text:
            continue
        style_name = (para.style.name if para.style else "").lower()
        if style_name.startswith("heading") or re.match(r"^第[一二三四五六七八九十百千万0-9]+[章节回卷]", text):
            flush()
            current_title = text[:80]
        else:
            buf.append(text)

    for table in doc.tables:
        md_rows = []
        for row in table.rows:
            cells = [c.text.replace("\n", " ").strip() for c in row.cells]
            if any(cells):
                md_rows.append("| " + " | ".join(cells) + " |")
        if md_rows:
            buf.append("\n".join(md_rows))

    flush()
    return sections


_HEADING_RE = re.compile(
    r"^(?:#{1,4}\s+(.+)|第[一二三四五六七八九十百千万0-9]+[章节回卷幕][^\n]{0,40})$"
)
_SCENE_BREAK_RE = re.compile(r"^\s*(?:---+|\*\*\*+|===+|###)\s*$")


def _parse_text_or_markdown(text: str) -> list[ParsedSection]:
    sections: list[ParsedSection] = []
    current_title = "正文"
    buf: list[str] = []
    scene_idx = 0

    def flush() -> None:
        body = "\n".join(buf).strip()
        if body:
            sections.append(ParsedSection(title=current_title, content=body))
        buf.clear()

    for raw_line in text.replace("\r\n", "\n").splitlines():
        line = raw_line.strip()
        if not line:
            if buf and buf[-1] != "":
                buf.append("")
            continue
        if _SCENE_BREAK_RE.match(line):
            flush()
            scene_idx += 1
            current_title = f"场景 {scene_idx}"
            continue
        m = _HEADING_RE.match(line)
        if m:
            flush()
            heading = (m.group(1) or line).strip()
            current_title = heading[:80]
            continue
        buf.append(line)

    flush()
    return sections
