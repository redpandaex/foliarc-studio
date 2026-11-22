import platform
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Response, status
from redis.asyncio import Redis
from redis.exceptions import RedisError

from inkflow_agent.config import get_settings

APP_VERSION = "0.1.0"


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    app.state.redis = Redis.from_url(settings.redis_url, decode_responses=True)
    try:
        yield
    finally:
        await app.state.redis.aclose()


app = FastAPI(
    title="InkFlow Studio Agent Engine",
    version=APP_VERSION,
    description="Python Agent Loop, Tool/Skill/MCP, Context Compression, Hybrid RAG & Sandbox Engine",
    lifespan=lifespan,
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
        "model_aliases": {
            "writer-main": settings.llm_model_writer_main,
            "critic-reasoner": settings.llm_model_critic_reasoner,
            "fast-router": settings.llm_model_fast_router,
            "embed": settings.llm_model_embed,
            "rerank": settings.llm_model_rerank,
        },
    }
