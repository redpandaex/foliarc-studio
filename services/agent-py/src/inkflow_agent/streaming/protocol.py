from typing import Any, Literal

from pydantic import BaseModel, Field

RunAction = Literal["CONTINUE", "REWRITE", "OUTLINE"]
ControlSignal = Literal["PAUSE", "CANCEL"]


class RunStartRequest(BaseModel):
    run_id: str
    trace_id: str | None = None
    action: RunAction = "CONTINUE"
    model_alias: str = "writer-main"
    book_title: str = ""
    genre: str | None = None
    synopsis: str | None = None
    chapter_title: str = ""
    chapter_context: str = ""
    instruction: str = ""


class RunResumeRequest(BaseModel):
    run_id: str
    trace_id: str | None = None
    action: RunAction = "CONTINUE"
    model_alias: str = "writer-main"
    book_title: str = ""
    genre: str | None = None
    synopsis: str | None = None
    chapter_title: str = ""
    chapter_context: str = ""
    instruction: str = ""
    accumulated_text: str = ""
    last_seq: int = Field(default=0, ge=0)
    prior_input_tokens: int = Field(default=0, ge=0)
    prior_cached_tokens: int = Field(default=0, ge=0)
    prior_output_tokens: int = Field(default=0, ge=0)


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
    extra: dict[str, Any] = Field(default_factory=dict)
