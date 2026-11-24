import hmac
import platform
from contextlib import asynccontextmanager
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Response, status
from pydantic import BaseModel, Field
from redis.asyncio import Redis
from redis.exceptions import RedisError

from foliarc_agent.config import get_settings
from foliarc_agent.rag.embeddings import EmbeddingProvider, RerankProvider
from foliarc_agent.rag.memory_extractor import StructuredMemoryExtractor
from foliarc_agent.rag.retriever import HybridRagRetriever
from foliarc_agent.rag.vector_store import MilvusVectorStore
from foliarc_agent.rag.worker import RagIngestionWorker
from foliarc_agent.streaming.protocol import (
    RunControlRequest,
    RunResumeRequest,
    RunStartRequest,
)
from foliarc_agent.streaming.runner import RunManager

APP_VERSION = "0.4.0"


class InternalRagSearchRequest(BaseModel):
    owner_id: str = Field(..., min_length=1)
    book_id: str = Field(..., min_length=1)
    query: str = Field(..., min_length=1, max_length=1000)
    top_k: int = Field(default=5, ge=1, le=20)
    max_chapter_no: int | None = Field(default=None, ge=0)
    doc_type: str | None = None
    rewrite_query: bool = False
    chapter_context: str = ""


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    redis = Redis.from_url(settings.redis_url, decode_responses=True)
    embedder = EmbeddingProvider(settings)
    reranker = RerankProvider(settings)
    vector_store = MilvusVectorStore(settings)
    rag_retriever = HybridRagRetriever(
        settings=settings,
        vector_store=vector_store,
        embedder=embedder,
        reranker=reranker,
    )
    memory_extractor = StructuredMemoryExtractor(settings)
    rag_worker = RagIngestionWorker(
        settings=settings,
        vector_store=vector_store,
        embedder=embedder,
        memory_extractor=memory_extractor,
    )

    app.state.redis = redis
    app.state.embedder = embedder
    app.state.reranker = reranker
    app.state.vector_store = vector_store
    app.state.rag_retriever = rag_retriever
    app.state.rag_worker = rag_worker
    app.state.run_manager = RunManager(
        settings=settings,
        redis=redis,
        rag_retriever=rag_retriever,
        embedder=embedder,
    )
    rag_worker.start()
    try:
        yield
    finally:
        await rag_worker.stop()
        await app.state.run_manager.shutdown()
        await redis.aclose()


app = FastAPI(
    title="FoliarcStudio Agent Engine",
    version=APP_VERSION,
    description="Python Agent Loop, Tool/Skill/MCP, Context Compression, Milvus Hybrid RAG & Sandbox Engine",
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
        "embedding_mode": app.state.embedder.mode if hasattr(app.state, "embedder") else "local_fallback",
        "rerank_mode": app.state.reranker.mode if hasattr(app.state, "reranker") else "local_fallback",
    }


@app.get("/health/ready")
async def readiness(response: Response) -> dict[str, Any]:
    redis_status = "down"
    try:
        if await app.state.redis.ping():
            redis_status = "up"
    except (RedisError, OSError):
        redis_status = "down"

    milvus_up = await app.state.vector_store.health_check() if hasattr(app.state, "vector_store") else False
    mq_connected = bool(app.state.rag_worker.connected) if hasattr(app.state, "rag_worker") else False

    ready = redis_status == "up"
    if not ready:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE

    return {
        "status": "ready" if ready else "degraded",
        "service": "agent-py",
        "dependencies": {
            "redis": redis_status,
            "milvus": "up" if milvus_up else "offline",
            "rabbitmq_worker": "connected" if mq_connected else "offline",
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
        "rag": {
            "milvus_collection": settings.milvus_collection,
            "embedding_mode": app.state.embedder.mode,
            "rerank_mode": app.state.reranker.mode,
            "embedding_dim": settings.embedding_dim,
            "rabbitmq_worker_connected": app.state.rag_worker.connected,
        },
        "multi_agent": {
            "nodes": ["ROUTER", "DIRECTOR", "LOREKEEPER", "WRITER", "CRITIC"],
            "hitl_interrupt": "OUTLINE_APPROVAL",
            "max_revisions": 2,
        },
        "skills": app.state.run_manager.skill_registry.list_catalog(),
        "model_aliases": {
            "writer-main": settings.llm_model_writer_main,
            "critic-reasoner": settings.llm_model_critic_reasoner,
            "fast-router": settings.llm_model_fast_router,
            "embed": settings.llm_model_embed,
            "rerank": settings.llm_model_rerank,
        },
    }


@app.post(
    "/internal/v1/rag/search",
    dependencies=[Depends(verify_internal_token)],
)
async def internal_rag_search(req: InternalRagSearchRequest) -> dict[str, Any]:
    return await app.state.rag_retriever.search(
        owner_id=req.owner_id,
        book_id=req.book_id,
        query=req.query,
        top_k=req.top_k,
        max_chapter_no=req.max_chapter_no,
        doc_type=req.doc_type,
        rewrite_query=req.rewrite_query,
        chapter_context=req.chapter_context,
    )


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
