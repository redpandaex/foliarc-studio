from typing import Any, Literal

from pydantic import BaseModel, Field

RunAction = Literal["CONTINUE", "REWRITE", "OUTLINE"]
RunMode = Literal["AGENT", "DIRECT", "MULTI_AGENT"]
ControlSignal = Literal["PAUSE", "CANCEL"]


class RunStartRequest(BaseModel):
    run_id: str
    owner_id: str = ""
    book_id: str = ""
    chapter_no: int | None = None
    section_id: str | None = None
    narrative_order: int | None = None
    precision_mode: str = "AUTO"
    mode: RunMode = "AGENT"
    trace_id: str | None = None
    action: RunAction = "CONTINUE"
    model_alias: str = "writer-main"
    book_title: str = ""
    genre: str | None = None
    synopsis: str | None = None
    story_charter: str = ""
    chapter_title: str = ""
    chapter_context: str = ""
    previous_chapters_context: str = ""
    rolling_summary_context: str = ""
    active_state_brief: str = ""
    pov_character: str = ""
    foreshadowing_writer_pack: str = ""
    foreshadowing_critic_pack: str = ""
    critic_guard_brief: str = ""
    instruction: str = ""
    require_outline_approval: bool = True


class RunResumeRequest(BaseModel):
    run_id: str
    owner_id: str = ""
    book_id: str = ""
    chapter_no: int | None = None
    section_id: str | None = None
    narrative_order: int | None = None
    precision_mode: str = "AUTO"
    mode: RunMode = "AGENT"
    trace_id: str | None = None
    action: RunAction = "CONTINUE"
    model_alias: str = "writer-main"
    book_title: str = ""
    genre: str | None = None
    synopsis: str | None = None
    story_charter: str = ""
    chapter_title: str = ""
    chapter_context: str = ""
    previous_chapters_context: str = ""
    rolling_summary_context: str = ""
    active_state_brief: str = ""
    pov_character: str = ""
    foreshadowing_writer_pack: str = ""
    foreshadowing_critic_pack: str = ""
    critic_guard_brief: str = ""
    instruction: str = ""
    accumulated_text: str = ""
    last_seq: int = Field(default=0, ge=0)
    prior_input_tokens: int = Field(default=0, ge=0)
    prior_cached_tokens: int = Field(default=0, ge=0)
    prior_output_tokens: int = Field(default=0, ge=0)
    approval_payload: dict[str, Any] | None = None


class RunControlRequest(BaseModel):
    signal: ControlSignal


class RunStateCallback(BaseModel):
    status: Literal["RUNNING", "PAUSED", "COMPLETED", "CANCELLED", "FAILED"]
    accumulated_text: str
    last_seq: int
    last_event_id: str | None = None
    input_tokens: int = 0
    cached_tokens: int = 0
    output_tokens: int = 0
    error_message: str | None = None
    current_node: str | None = None
    interrupt_type: str | None = None
    interrupt_payload_json: str | None = None
    route_intent: str | None = None
    revision_count: int | None = None
    sandbox_calls: int = 0
    extra: dict[str, Any] = Field(default_factory=dict)
