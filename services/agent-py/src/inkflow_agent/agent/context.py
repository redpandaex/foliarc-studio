import hashlib
import json
from dataclasses import dataclass, field
from typing import Any

import tiktoken


class TokenCounter:
    """Accurate tiktoken counter with a deterministic offline fallback estimator."""

    def __init__(self, encoding_name: str = "cl100k_base") -> None:
        self._encoding = None
        try:
            self._encoding = tiktoken.get_encoding(encoding_name)
        except (KeyError, ValueError, OSError):
            self._encoding = None

    def count_text(self, text: str) -> int:
        if not text:
            return 0
        if self._encoding is not None:
            return len(self._encoding.encode(text))
        # Deterministic CJK + Latin heuristic when offline without cached BPE file
        cjk = sum(1 for ch in text if "\u4e00" <= ch <= "\u9fff")
        other = len(text) - cjk
        return max(1, int(cjk * 1.5 + other / 3.5))

    def count_message(self, message: dict[str, Any]) -> int:
        total = 4  # per-message framing overhead
        content = message.get("content")
        if isinstance(content, str):
            total += self.count_text(content)
        tool_calls = message.get("tool_calls")
        if tool_calls:
            total += self.count_text(json.dumps(tool_calls, ensure_ascii=False))
        return total

    def count_messages(self, messages: list[dict[str, Any]]) -> int:
        return sum(self.count_message(m) for m in messages) + 2


@dataclass(slots=True)
class ContextBudget:
    """Token budget allocation across static prefix, loop history, and output reservation."""

    max_context_tokens: int = 16000
    reserved_output_tokens: int = 2048
    max_tool_output_chars: int = 1800
    keep_recent_messages: int = 6

    @property
    def available_input_tokens(self) -> int:
        return max(1024, self.max_context_tokens - self.reserved_output_tokens)


@dataclass(slots=True)
class ContextStats:
    static_prefix_tokens: int = 0
    total_input_tokens: int = 0
    compressed_turns: int = 0
    offloaded_observations: int = 0
    rolling_summary: str = ""
    offload_store: dict[str, str] = field(default_factory=dict)


class ContextManager:
    """
    Context Engineering manager enforcing:
    1. KV Cache Prefix Optimization: static System Prompt stays immutable at index 0.
    2. Tool Output Offloading: oversized tool observations are stored by ref ID and summarized.
    3. Sliding Window + Rolling Summary Compression: older ReAct turns compact cleanly when budget tightens.
    """

    def __init__(
        self,
        *,
        budget: ContextBudget | None = None,
        counter: TokenCounter | None = None,
    ) -> None:
        self.budget = budget or ContextBudget()
        self.counter = counter or TokenCounter()
        self.stats = ContextStats()

    def offload_tool_output(self, tool_name: str, raw_output: str) -> str:
        """Truncates oversized tool outputs and stores full text under a deterministic ref ID."""
        cap = self.budget.max_tool_output_chars
        if len(raw_output) <= cap:
            return raw_output

        digest = hashlib.sha256(f"{tool_name}:{raw_output}".encode()).hexdigest()[:10]
        obs_id = f"obs_{digest}"
        self.stats.offload_store[obs_id] = raw_output
        self.stats.offloaded_observations += 1

        head_len = int(cap * 0.7)
        tail_len = int(cap * 0.2)
        preview = (
            f"{raw_output[:head_len]}\n\n"
            f"... [工具 `{tool_name}` 输出过长（共 {len(raw_output)} 字符），已外置归档为 {obs_id}，以下为尾部片段] ...\n\n"
            f"{raw_output[-tail_len:]}"
        )
        return preview

    def build_messages(
        self,
        *,
        static_system_prompt: str,
        initial_user_prompt: str,
        loop_messages: list[dict[str, Any]],
        accumulated_text: str = "",
        revised_instruction: str = "",
    ) -> list[dict[str, Any]]:
        """
        Assembles messages keeping the static system prompt immutable at position 0 (KV Cache hit),
        followed by rolling summary (if compressed), recent ReAct turns, and optional resume suffix.
        """
        static_msg: dict[str, Any] = {"role": "system", "content": static_system_prompt}
        user_msg: dict[str, Any] = {"role": "user", "content": initial_user_prompt}

        self.stats.static_prefix_tokens = self.counter.count_message(static_msg)

        tail_messages: list[dict[str, Any]] = []
        if accumulated_text:
            tail_messages.append({"role": "assistant", "content": accumulated_text})
            resume_note = (
                "你在刚才的位置暂停了。请严格从上面最后一个字之后无缝续写，绝对不要重复已经写过的句子。"
            )
            if revised_instruction.strip():
                resume_note += f"\n作者补充/调整的新指令：{revised_instruction.strip()}"
            tail_messages.append({"role": "user", "content": resume_note})

        candidate = [static_msg, user_msg, *loop_messages, *tail_messages]
        total_tokens = self.counter.count_messages(candidate)

        if total_tokens <= self.budget.available_input_tokens:
            self.stats.total_input_tokens = total_tokens
            return candidate

        # Budget exceeded: compact older loop messages into a structured rolling summary
        # while preserving static_msg (KV Cache prefix!) and recent turns verbatim.
        keep_n = self.budget.keep_recent_messages
        if len(loop_messages) > keep_n:
            older = loop_messages[:-keep_n]
            recent = loop_messages[-keep_n:]
            # Ensure we don't split an assistant(tool_calls) from its following tool observations
            while recent and recent[0].get("role") == "tool" and older:
                recent.insert(0, older.pop())

            if older:
                summary_text = self._summarize_older_turns(older)
                self.stats.rolling_summary = summary_text
                self.stats.compressed_turns += len(older)
                summary_msg: dict[str, Any] = {
                    "role": "user",
                    "content": (
                        "【历史工具调用与推理压缩摘要（上下文预算自动压缩）】\n"
                        f"{summary_text}"
                    ),
                }
                candidate = [static_msg, user_msg, summary_msg, *recent, *tail_messages]

        # Secondary safeguard: if initial_user_prompt itself is huge, trim middle of user_msg
        total_tokens = self.counter.count_messages(candidate)
        if total_tokens > self.budget.available_input_tokens:
            overflow_chars = max(400, (total_tokens - self.budget.available_input_tokens) * 3)
            u_text = str(user_msg["content"])
            if len(u_text) > overflow_chars + 600:
                keep_tail = max(600, len(u_text) - overflow_chars)
                candidate[1] = {
                    "role": "user",
                    "content": f"...[前文过长已按 Token 预算截断]...\n{u_text[-keep_tail:]}",
                }
                total_tokens = self.counter.count_messages(candidate)

        self.stats.total_input_tokens = total_tokens
        return candidate

    @staticmethod
    def _summarize_older_turns(messages: list[dict[str, Any]]) -> str:
        bullets: list[str] = []
        for msg in messages:
            role = msg.get("role", "")
            if role == "assistant" and msg.get("tool_calls"):
                names = [tc.get("function", {}).get("name", "unknown") for tc in msg["tool_calls"]]
                bullets.append(f"- Agent 调用了工具: {', '.join(names)}")
            elif role == "tool":
                name = msg.get("name", "tool")
                snippet = str(msg.get("content", ""))[:220].replace("\n", " ")
                bullets.append(f"- 工具 `{name}` 返回要点: {snippet}")
            elif role == "assistant" and msg.get("content"):
                snippet = str(msg["content"])[:200].replace("\n", " ")
                bullets.append(f"- 阶段性推理: {snippet}")
        return "\n".join(bullets) if bullets else "- 已完成前期设定检索。"
