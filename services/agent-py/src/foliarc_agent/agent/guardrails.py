from __future__ import annotations

import logging
import re
from dataclasses import dataclass

import httpx

from foliarc_agent.config import Settings

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class GuardrailVerdict:
    allowed: bool
    stage: str  # "INPUT" | "OUTPUT" | "SANDBOX"
    action: str  # "PASS" | "BLOCK" | "WARN"
    rule_code: str
    message: str


_INJECTION_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    (
        "PROMPT_INJECTION_IGNORE_RULES",
        re.compile(
            r"(ignore\s+(all\s+)?(previous|prior|above)\s+(instructions|prompts|rules)"
            r"|忽略你?(之前|上面|前面|所有|全部){1,2}的?(系统|安全)?(指令|提示词|规则|设定|系统提示)"
            r"|无视(一切|以上|所有)(系统|安全)?(指令|规则|限制))",
            re.IGNORECASE,
        ),
    ),
    (
        "SYSTEM_PROMPT_EXFILTRATION",
        re.compile(
            r"(reveal|print|dump|output|show|repeat)\s+(your\s+)?(system\s+prompt|hidden\s+instructions|developer\s+prompt)"
            r"|(输出|打印|泄露|告诉我|复述|展示)(你的|完整的)?(系统提示词|系统指令|System\s*Prompt|隐藏规则|内部密钥|API\s*Key)"
            r"|(系统提示词|系统指令|System\s*Prompt|隐藏规则).{0,16}(输出|打印|泄露|复述|展示)",
            re.IGNORECASE,
        ),
    ),
    (
        "JAILBREAK_ROLE_OVERRIDE",
        re.compile(
            r"(<\|im_start\|>\s*system|<\|system\|>|\[SYSTEM\s+OVERRIDE\]"
            r"|you\s+are\s+now\s+DAN|进入\s*DAN\s*越狱模式|开发者越狱模式|解除所有安全限制)",
            re.IGNORECASE,
        ),
    ),
]

_COMPLIANCE_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    (
        "COMPLIANCE_WEAPON_OR_EXPLOSIVE",
        re.compile(
            r"(详细制造炸药配方|制作土制炸弹步骤|合成剧毒化学武器教程|how\s+to\s+build\s+a\s+bomb)",
            re.IGNORECASE,
        ),
    ),
    (
        "COMPLIANCE_EXTREME_HARM",
        re.compile(
            r"(教唆自杀具体方法|勒索病毒完整攻击源码|窃取银行卡木马制作教程)",
            re.IGNORECASE,
        ),
    ),
]

_SECRET_LEAK_PATTERNS: list[re.Pattern[str]] = [
    re.compile(r"sk-[a-zA-Z0-9]{24,}"),
    re.compile(r"foliarc-internal-dev-token", re.IGNORECASE),
    re.compile(r"【可用写作技能包（渐进式披露"),
]


class GuardrailEngine:
    """
    Input & Output Guardrails for enterprise novel AI safety:
    - Input: Prompt Injection / System Prompt Extraction / Jailbreak / Compliance blocking
    - Output: Secret / System Prompt leakage redaction & compliance check
    - Audit: Asynchronously records BLOCK/WARN decisions into Java PG `guardrail_audit_logs`
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    def check_input(self, instruction: str, chapter_context: str = "") -> GuardrailVerdict:
        if not self._settings.guardrails_enabled:
            return GuardrailVerdict(True, "INPUT", "PASS", "DISABLED", "")

        target = instruction.strip()
        if not target:
            return GuardrailVerdict(True, "INPUT", "PASS", "OK", "")

        for code, pat in _INJECTION_PATTERNS:
            if pat.search(target):
                return GuardrailVerdict(
                    allowed=False,
                    stage="INPUT",
                    action="BLOCK",
                    rule_code=code,
                    message="输入触发安全护栏拦截：检测到疑似 Prompt 注入、系统提示词套取或越狱指令。",
                )

        combined = f"{target}\n{chapter_context[-1500:]}"
        for code, pat in _COMPLIANCE_PATTERNS:
            if pat.search(combined):
                return GuardrailVerdict(
                    allowed=False,
                    stage="INPUT",
                    action="BLOCK",
                    rule_code=code,
                    message="输入触发内容合规护栏拦截：包含高风险违规内容，已拒绝执行。",
                )

        return GuardrailVerdict(True, "INPUT", "PASS", "OK", "")

    def check_output(self, text: str) -> GuardrailVerdict:
        if not self._settings.guardrails_enabled or not text:
            return GuardrailVerdict(True, "OUTPUT", "PASS", "OK", "")

        for pat in _SECRET_LEAK_PATTERNS:
            if pat.search(text):
                return GuardrailVerdict(
                    allowed=False,
                    stage="OUTPUT",
                    action="BLOCK",
                    rule_code="OUTPUT_SECRET_OR_PROMPT_LEAK",
                    message="输出触发安全护栏拦截：疑似包含内部密钥或系统提示词泄露。",
                )

        for code, pat in _COMPLIANCE_PATTERNS:
            if pat.search(text):
                return GuardrailVerdict(
                    allowed=False,
                    stage="OUTPUT",
                    action="BLOCK",
                    rule_code=code,
                    message="输出触发内容合规护栏拦截：生成内容包含违规片段。",
                )

        return GuardrailVerdict(True, "OUTPUT", "PASS", "OK", "")

    async def report_audit(
        self,
        *,
        verdict: GuardrailVerdict,
        user_id: str | None = None,
        book_id: str | None = None,
        run_id: str | None = None,
        detail: str = "",
    ) -> None:
        if verdict.action == "PASS":
            return
        url = f"{self._settings.biz_java_base_url.rstrip('/')}/internal/v1/runs/guardrails/audit"
        headers = {"X-Internal-Token": self._settings.internal_service_token}
        payload = {
            "userId": user_id or None,
            "bookId": book_id or None,
            "runId": run_id or None,
            "stage": verdict.stage,
            "action": verdict.action,
            "ruleCode": verdict.rule_code,
            "detail": (detail or verdict.message)[:800],
        }
        try:
            async with httpx.AsyncClient(timeout=5.0) as client:
                await client.post(url, headers=headers, json=payload)
        except httpx.HTTPError as exc:
            logger.debug("Guardrail audit callback skipped: %s", exc)
