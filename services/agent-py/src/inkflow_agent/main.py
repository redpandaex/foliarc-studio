import hmac
import platform
from contextlib import asynccontextmanager
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Response, status
from redis.asyncio import Redis
from redis.exceptions import RedisError

from inkflow_agent.config import get_settings
from inkflow_agent.streaming.protocol import (
    RunControlRequest,
    RunResumeRequest,
    RunStartRequest,
)
from inkflow_agent.streaming.runner import RunManager

APP_VERSION = "0.2.0"


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    redis = Redis.from_url(settings.redis_url, decode_responses=True)
    app.state.redis = redis
    app.state.run_manager = RunManager(settings=settings, redis=redis)
    try:
        yield
    finally:
        await app.state.run_manager.shutdown()
        await redis.aclose()


app = FastAPI(
    title="InkFlow Studio Agent Engine",
    version=APP_VERSION,
    description="Python Agent Loop, Tool/Skill/MCP, Context Compression, Hybrid RAG & Sandbox Engine",
    lifespan=lifespan,
)


def verify_internal_token(x_internal_token: str | None = Header(default=None)) -> None:
    expected = get_settings().internal_service_token
    if not x_internal_token or not hmac.compare_digest(x_internal_token, expected):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Invalid internal service token",
        )


@app.get("/health")
async def health() -> dict[str, Any]:
    settings = get_settings()
    return {
        "status": "ok",
        "service": "agent-py",
        "version": APP_VERSION,
        "env": settings.app_env,
        "python": platform.python_version(),
        "llm_configured": settings.llm_configured,
    }


@app.get("/health/ready")
async def readiness(response: Response) -> dict[str, Any]:
    redis_status = "down"
    try:
        if await app.state.redis.ping():
            redis_status = "up"
    except (RedisError, OSError):
        redis_status = "down"

    ready = redis_status == "up"
    if not ready:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE

    return {
        "status": "ready" if ready else "degraded",
        "service": "agent-py",
        "dependencies": {
            "redis": redis_status,
        },
    }


@app.get("/api/v1/meta")
async def meta() -> dict[str, Any]:
    settings = get_settings()
    return {
        "service": "agent-py",
        "version": APP_VERSION,
        "llm_base_url": settings.llm_base_url,
        "llm_configured": settings.llm_configured,
        "model_aliases": {
            "writer-main": settings.llm_model_writer_main,
            "critic-reasoner": settings.llm_model_critic_reasoner,
            "fast-router": settings.llm_model_fast_router,
            "embed": settings.llm_model_embed,
            "rerank": settings.llm_model_rerank,
        },
    }


@app.post(
    "/internal/v1/runs/start",
    status_code=status.HTTP_202_ACCEPTED,
    dependencies=[Depends(verify_internal_token)],
)
async def start_run(req: RunStartRequest) -> dict[str, Any]:
    await app.state.run_manager.start_run(req)
    return {"accepted": True, "run_id": req.run_id}


@app.post(
    "/internal/v1/runs/{run_id}/resume",
    status_code=status.HTTP_202_ACCEPTED,
    dependencies=[Depends(verify_internal_token)],
)
async def resume_run(run_id: str, req: RunResumeRequest) -> dict[str, Any]:
    if req.run_id != run_id:
        raise HTTPException(status_code=400, detail="run_id mismatch")
    await app.state.run_manager.resume_run(req)
    return {"accepted": True, "run_id": run_id}


@app.post(
    "/internal/v1/runs/{run_id}/control",
    dependencies=[Depends(verify_internal_token)],
)
async def control_run(run_id: str, req: RunControlRequest) -> dict[str, Any]:
    signaled = await app.state.run_manager.send_control(run_id, req.signal)
    return {"run_id": run_id, "signal": req.signal, "active_signaled": signaled}
