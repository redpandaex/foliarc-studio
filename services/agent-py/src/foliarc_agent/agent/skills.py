import os
from dataclasses import dataclass
from pathlib import Path

import yaml


def _resolve_default_skills_dir() -> Path:
    env_dir = os.getenv("FOLIARC_SKILLS_DIR")
    if env_dir:
        candidate = Path(env_dir)
        if candidate.is_dir():
            return candidate
    cwd_candidate = Path.cwd() / "skills"
    if cwd_candidate.is_dir():
        return cwd_candidate
    for parent in Path(__file__).resolve().parents[:5]:
        candidate = parent / "skills"
        if candidate.is_dir():
            return candidate
    return cwd_candidate


_DEFAULT_SKILLS_DIR = _resolve_default_skills_dir()


@dataclass(frozen=True, slots=True)
class SkillSpec:
    name: str
    description: str
    triggers: tuple[str, ...]
    body: str
    path: Path


class SkillRegistry:
    """Progressive-disclosure skill catalog backed by Markdown SKILL.md files."""

    def __init__(self, skills_dir: Path | None = None) -> None:
        self._dir = skills_dir or _DEFAULT_SKILLS_DIR
        self._skills: dict[str, SkillSpec] = {}
        self.reload()

    def reload(self) -> None:
        self._skills.clear()
        if not self._dir.exists():
            return
        for skill_file in sorted(self._dir.glob("*/SKILL.md")):
            spec = self._parse_file(skill_file)
            if spec is not None:
                self._skills[spec.name] = spec

    def list_catalog(self) -> list[dict[str, object]]:
        return [
            {
                "name": s.name,
                "description": s.description,
                "triggers": list(s.triggers),
            }
            for s in self._skills.values()
        ]

    def render_catalog_prompt(self) -> str:
        """Compact index injected into static System Prompt (~120 tokens total)."""
        if not self._skills:
            return ""
        lines = []
        for s in self._skills.values():
            trig = "、".join(s.triggers) if s.triggers else "按需"
            lines.append(f"- `{s.name}`: {s.description}（触发关键词：{trig}）")
        return "\n".join(lines)

    def load_skill(self, name: str) -> SkillSpec | None:
        return self._skills.get(name.strip())

    def available_names(self) -> list[str]:
        return list(self._skills.keys())

    @staticmethod
    def _parse_file(path: Path) -> SkillSpec | None:
        raw = path.read_text(encoding="utf-8")
        if not raw.startswith("---"):
            return None
        parts = raw.split("---", 2)
        if len(parts) < 3:
            return None
        meta = yaml.safe_load(parts[1]) or {}
        name = str(meta.get("name") or path.parent.name).strip()
        description = str(meta.get("description") or "").strip()
        triggers_raw = meta.get("triggers") or []
        triggers = tuple(str(t).strip() for t in triggers_raw if str(t).strip())
        body = parts[2].strip()
        return SkillSpec(
            name=name,
            description=description,
            triggers=triggers,
            body=body,
            path=path,
        )
