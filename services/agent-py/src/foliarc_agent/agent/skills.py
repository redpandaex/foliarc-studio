import os
from dataclasses import dataclass
from pathlib import Path

import yaml

from foliarc_agent.agent.genre_profiles import GenreProfileRegistry


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
    genres: tuple[str, ...] = ("ALL",)
    sub_tags: tuple[str, ...] = ()

    def matches_genre(self, genre_code: str | None, sub_genre_tags: str | None = None) -> bool:
        if not genre_code and not sub_genre_tags:
            return True
        if "ALL" in self.genres:
            return True
        if genre_code and genre_code.strip().upper() in self.genres:
            return True
        book_tags = GenreProfileRegistry.parse_sub_tags(sub_genre_tags)
        if book_tags and self.sub_tags:
            for bt in book_tags:
                bt_low = bt.lower()
                for st in self.sub_tags:
                    st_low = st.lower()
                    if bt_low in st_low or st_low in bt_low:
                        return True
        return False


class SkillRegistry:
    """Progressive-disclosure skill catalog backed by Markdown SKILL.md files with genre filtering."""

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

    def filter_for_genre(
        self,
        genre_code: str | None = None,
        sub_genre_tags: str | None = None,
    ) -> list[SkillSpec]:
        return [
            s
            for s in self._skills.values()
            if s.matches_genre(genre_code, sub_genre_tags)
        ]

    def list_catalog(
        self,
        genre_code: str | None = None,
        sub_genre_tags: str | None = None,
    ) -> list[dict[str, object]]:
        return [
            {
                "name": s.name,
                "description": s.description,
                "triggers": list(s.triggers),
                "genres": list(s.genres),
                "sub_tags": list(s.sub_tags),
            }
            for s in self.filter_for_genre(genre_code, sub_genre_tags)
        ]

    def render_catalog_prompt(
        self,
        genre_code: str | None = None,
        sub_genre_tags: str | None = None,
    ) -> str:
        """Compact index injected into static System Prompt (~120 tokens total)."""
        selected = self.filter_for_genre(genre_code, sub_genre_tags)
        if not selected:
            return ""
        lines = []
        for s in selected:
            short_desc = s.description.split("。")[0][:30]
            trig = "、".join(s.triggers[:4]) if s.triggers else "按需"
            lines.append(f"- `{s.name}`: {short_desc}（{trig}）")
        return "\n".join(lines)

    def load_skill(self, name: str) -> SkillSpec | None:
        return self._skills.get(name.strip())

    def available_names(
        self,
        genre_code: str | None = None,
        sub_genre_tags: str | None = None,
    ) -> list[str]:
        return [s.name for s in self.filter_for_genre(genre_code, sub_genre_tags)]

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
        genres_raw = meta.get("genres") or ["ALL"]
        if isinstance(genres_raw, str):
            genres_raw = [genres_raw]
        genres = tuple(str(g).strip().upper() for g in genres_raw if str(g).strip()) or ("ALL",)
        sub_tags_raw = meta.get("sub_tags") or []
        if isinstance(sub_tags_raw, str):
            sub_tags_raw = [sub_tags_raw]
        sub_tags = tuple(str(t).strip() for t in sub_tags_raw if str(t).strip())
        body = parts[2].strip()
        return SkillSpec(
            name=name,
            description=description,
            triggers=triggers,
            body=body,
            path=path,
            genres=genres,
            sub_tags=sub_tags,
        )
