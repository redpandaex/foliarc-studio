from dataclasses import dataclass

import httpx
from jinja2 import BaseLoader, Environment

from foliarc_agent.config import Settings
from foliarc_agent.streaming.protocol import RunAction

DEFAULT_WRITER_SYSTEM_TEMPLATE = """你是 FoliarcStudio（卷弧工坊）的企业级长篇小说创作 Agent。
你必须严格维护人物性格、战力境界、物品持有与世界观设定的一致性，杜绝“吃书”与人设崩塌。

【当前任务规范】
{{ action_guide }}

{% if book_title %}
【作品静态档案（请严格遵守）】
- 书名：《{{ book_title }}》
{% if genre %}- 题材：{{ genre }}{% endif %}
{% if synopsis %}- 简介：{{ synopsis }}{% endif %}
{% endif %}

{% if skill_catalog %}
【可用写作技能包（渐进式披露：需要时再调用 load_writing_skill 读取完整规范）】
{{ skill_catalog }}
{% endif %}

【工作流原则】
1. 跨章连贯性是最高优先级：若上下文提供了【前文章节承接】，必须直接承接上一章末尾的时间、地点、人物状态、对话余韵与未解悬念，绝不能另起炉灶或像第一章那样重新介绍世界观；若当前是第 2 章及以后且尚未看到上一章正文，先调用 read_chapter_excerpt 读取上一章。
2. 涉及具体人物状态、世界观名词、未回收伏笔或前文细节时，优先调用可用工具（含 MCP 设定库工具）核实，再动笔撰写。
3. 遇到打斗、伏笔、古风对话或卡文破局等强风格需求，按需调用 load_writing_skill 加载对应写作技能包。
4. 信息收集完毕后，直接输出高质量小说正文（或大纲），不要输出多余的客套话或工具调用解释。"""

ACTION_GUIDES: dict[RunAction, str] = {
    "CONTINUE": (
        "任务：紧接上一章结尾与当前章节已有正文自然续写后续剧情。"
        "保持人物姓名、性格语气、地点时间线、伤势物品与未解悬念完全连贯，"
        "信息核实完毕后直接输出正文段落。"
    ),
    "REWRITE": (
        "任务：对给定正文进行润色与细节扩写，提升画面感、动作节奏与对话张力，"
        "严格保留人物状态、前文衔接与世界观设定。直接输出润色后的正文。"
    ),
    "OUTLINE": (
        "任务：紧承上一章结尾、已有世界观与未回收伏笔，为当前章节规划 3-5 个清晰的场景节拍大纲"
        "（首节拍必须衔接上一章结尾，含核心冲突、伏笔照应与章末钩子），使用 Markdown 列表输出。"
    ),
}


@dataclass(frozen=True, slots=True)
class RenderedPrompt:
    template_name: str
    template_version: int
    system_prompt: str


class PromptRegistry:
    """Jinja2 prompt orchestrator loading active versioned templates from biz-java with local fallback."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._env = Environment(loader=BaseLoader(), autoescape=False, trim_blocks=True, lstrip_blocks=True)

    async def render_writer_system(
        self,
        *,
        action: RunAction,
        book_title: str,
        genre: str | None,
        synopsis: str | None,
        skill_catalog: str,
    ) -> RenderedPrompt:
        template_body = DEFAULT_WRITER_SYSTEM_TEMPLATE
        version = 1

        fetched = await self._fetch_active_template("novel_writer_system")
        if fetched is not None:
            template_body, version = fetched

        tpl = self._env.from_string(template_body)
        rendered = tpl.render(
            action_guide=ACTION_GUIDES.get(action, ACTION_GUIDES["CONTINUE"]),
            book_title=book_title,
            genre=genre or "",
            synopsis=synopsis or "",
            skill_catalog=skill_catalog,
        ).strip()

        return RenderedPrompt(
            template_name="novel_writer_system",
            template_version=version,
            system_prompt=rendered,
        )

    async def _fetch_active_template(self, name: str) -> tuple[str, int] | None:
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/prompts/{name}"
        headers = {"X-Internal-Token": self._settings.internal_service_token}
        try:
            async with httpx.AsyncClient(timeout=3.0) as client:
                resp = await client.get(url, headers=headers)
                if resp.status_code == 200:
                    data = resp.json()
                    body = data.get("templateBody")
                    ver = int(data.get("version", 1))
                    if body:
                        return body, ver
        except (httpx.HTTPError, ValueError, KeyError):
            return None
        return None
