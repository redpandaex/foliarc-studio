import hmac
import platform
from contextlib import asynccontextmanager
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Response, status
from pydantic import BaseModel, Field
from redis.asyncio import Redis
from redis.exceptions import RedisError

from foliarc_agent.agent.guardrails import GuardrailEngine
from foliarc_agent.agent.sandbox import create_sandbox_provider
from foliarc_agent.config import get_settings
from foliarc_agent.rag.distiller import ChapterDistiller
from foliarc_agent.rag.embeddings import EmbeddingProvider, RerankProvider
from foliarc_agent.rag.memory_extractor import StructuredMemoryExtractor
from foliarc_agent.rag.retriever import HybridRagRetriever
from foliarc_agent.rag.ripple_engine import RippleEngine
from foliarc_agent.rag.summarizer import HierarchicalSummarizer
from foliarc_agent.rag.vector_store import MilvusSummaryVectorStore, MilvusVectorStore
from foliarc_agent.rag.worker import RagIngestionWorker
from foliarc_agent.streaming.protocol import (
    RunControlRequest,
    RunResumeRequest,
    RunStartRequest,
)
from foliarc_agent.streaming.runner import RunManager

APP_VERSION = "0.5.0"


class InternalRagSearchRequest(BaseModel):
    owner_id: str = Field(..., min_length=1)
    book_id: str = Field(..., min_length=1)
    query: str = Field(..., min_length=1, max_length=1000)
    top_k: int = Field(default=5, ge=1, le=20)
    max_chapter_no: int | None = Field(default=None, ge=0)
    max_narrative_order: int | None = Field(default=None, ge=0)
    precision_mode: str = "AUTO"
    doc_type: str | None = None
    rewrite_query: bool = False
    chapter_context: str = ""
    user_doc_ids: list[str] | None = None
    assigned_doc_ids: list[str] | None = None
    global_doc_ids: list[str] | None = None


class InternalSandboxExecRequest(BaseModel):
    run_id: str = "manual-check"
    code: str = Field(..., min_length=1, max_length=10000)
    timeout_seconds: float = Field(default=6.0, ge=1.0, le=20.0)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    redis = Redis.from_url(settings.redis_url, decode_responses=True)
    embedder = EmbeddingProvider(settings)
    reranker = RerankProvider(settings)
    vector_store = MilvusVectorStore(settings)
    summary_store = MilvusSummaryVectorStore(settings)
    rag_retriever = HybridRagRetriever(
        settings=settings,
        vector_store=vector_store,
        embedder=embedder,
        reranker=reranker,
        summary_store=summary_store,
    )
    memory_extractor = StructuredMemoryExtractor(settings)
    summarizer = HierarchicalSummarizer(settings)
    distiller = ChapterDistiller(settings)
    ripple_engine = RippleEngine(settings)
    rag_worker = RagIngestionWorker(
        settings=settings,
        vector_store=vector_store,
        embedder=embedder,
        memory_extractor=memory_extractor,
        summarizer=summarizer,
        distiller=distiller,
        summary_store=summary_store,
        ripple_engine=ripple_engine,
    )
    guardrails = GuardrailEngine(settings)
    sandbox = create_sandbox_provider(settings)

    app.state.redis = redis
    app.state.embedder = embedder
    app.state.reranker = reranker
    app.state.vector_store = vector_store
    app.state.rag_retriever = rag_retriever
    app.state.rag_worker = rag_worker
    app.state.guardrails = guardrails
    app.state.sandbox = sandbox
    app.state.run_manager = RunManager(
        settings=settings,
        redis=redis,
        rag_retriever=rag_retriever,
        embedder=embedder,
        sandbox_provider=sandbox,
        guardrails=guardrails,
    )
    rag_worker.start()
    await sandbox.start()
    try:
        yield
    finally:
        await sandbox.shutdown()
        await rag_worker.stop()
        await app.state.run_manager.shutdown()
        await redis.aclose()


app = FastAPI(
    title="FoliarcStudio Agent Engine",
    version=APP_VERSION,
    description="Python Agent Loop, Tool/Skill/MCP, Multi-Agent, Milvus Hybrid RAG, Guardrails & Hardened Docker Sandbox",
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
        "sandbox_provider": app.state.sandbox.provider_name if hasattr(app.state, "sandbox") else "stub",
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
    sandbox_up = await app.state.sandbox.health_check() if hasattr(app.state, "sandbox") else False

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
            "docker_sandbox": "up" if sandbox_up else "stub",
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
            "milvus_summary_collection": settings.milvus_summary_collection,
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
        "sandbox": app.state.sandbox.stats(),
        "guardrails_enabled": settings.guardrails_enabled,
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
    "/internal/v1/sandbox/exec",
    dependencies=[Depends(verify_internal_token)],
)
async def internal_sandbox_exec(req: InternalSandboxExecRequest) -> dict[str, Any]:
    res = await app.state.sandbox.exec_python(
        run_id=req.run_id,
        code=req.code,
        timeout_seconds=req.timeout_seconds,
    )
    return {
        "success": res.success,
        "exit_code": res.exit_code,
        "stdout": res.stdout,
        "stderr": res.stderr,
        "duration_ms": res.duration_ms,
        "provider": res.provider,
        "container_id": res.container_id,
        "from_warm_pool": res.from_warm_pool,
        "timed_out": res.timed_out,
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
        max_narrative_order=req.max_narrative_order,
        precision_mode=req.precision_mode,
        doc_type=req.doc_type,
        rewrite_query=req.rewrite_query,
        chapter_context=req.chapter_context,
        user_doc_ids=req.user_doc_ids,
        assigned_doc_ids=req.assigned_doc_ids,
        global_doc_ids=req.global_doc_ids,
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
