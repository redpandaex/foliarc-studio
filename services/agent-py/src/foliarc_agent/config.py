from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


def _resolve_env_files() -> tuple[Path, ...]:
    """Locate .env in monorepo root or current working directory safely (also container-safe)."""
    candidates: list[Path] = []
    here = Path(__file__).resolve()
    for parent in here.parents[:6]:
        env_path = parent / ".env"
        if env_path.is_file():
            candidates.append(env_path)
            break
    candidates.append(Path(".env"))
    return tuple(candidates)


class Settings(BaseSettings):
    """Application settings loaded from environment variables or repo-root .env."""

    model_config = SettingsConfigDict(
        env_file=_resolve_env_files(),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_name: str = "foliarc-agent"
    app_env: str = "dev"
    agent_py_host: str = "0.0.0.0"
    agent_py_port: int = 8000

    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_password: str = ""

    internal_service_token: str = "foliarc-internal-dev-token-change-me"
    biz_java_base_url: str = "http://localhost:8080"
    stream_ttl_seconds: int = 7200

    # --- Phase 3: MinIO, RabbitMQ, Milvus & Embedding/Rerank ---
    minio_endpoint: str = "http://localhost:9000"
    minio_root_user: str = "minioadmin"
    minio_root_password: str = "minioadmin"
    minio_bucket_docs: str = "foliarc-docs"

    rabbitmq_host: str = "localhost"
    rabbitmq_port: int = 5672
    rabbitmq_user: str = "foliarc"
    rabbitmq_password: str = "foliarc_dev_password"
    rag_worker_enabled: bool = True

    milvus_host: str = "localhost"
    milvus_port: int = 19530
    milvus_collection: str = "foliarc_novel_chunks_v2"
    milvus_summary_collection: str = "foliarc_summary_vectors"

    llm_base_url: str = "https://api.deepseek.com/v1"
    llm_api_key: str = ""
    llm_model_writer_main: str = "deepseek-chat"
    llm_model_critic_reasoner: str = "deepseek-reasoner"
    llm_model_fast_router: str = "deepseek-chat"
    llm_model_embed: str = "BAAI/bge-m3"
    llm_model_rerank: str = "BAAI/bge-reranker-v2-m3"

    embedding_base_url: str = ""
    embedding_api_key: str = ""
    embedding_dim: int = 1024
    rerank_base_url: str = ""
    rerank_api_key: str = ""

    # --- Phase 5: Guardrails & Hardened Docker Sandbox ---
    guardrails_enabled: bool = True
    sandbox_provider: str = "docker"  # "docker" | "stub" (extensible: "gvisor", "e2e/e2b")
    sandbox_docker_socket: str = "/var/run/docker.sock"
    sandbox_docker_image: str = "python:3.12-slim"
    sandbox_docker_runtime: str = ""  # set "runsc" to enable gVisor kernel isolation
    sandbox_pool_size: int = 2
    sandbox_timeout_seconds: float = 8.0
    sandbox_memory_mb: int = 128
    sandbox_cpu_quota: float = 0.5
    sandbox_pids_limit: int = 32
    sandbox_max_age_seconds: int = 180

    @property
    def redis_url(self) -> str:
        if self.redis_password:
            return f"redis://:{self.redis_password}@{self.redis_host}:{self.redis_port}/0"
        return f"redis://{self.redis_host}:{self.redis_port}/0"

    @property
    def rabbitmq_url(self) -> str:
        return (
            f"amqp://{self.rabbitmq_user}:{self.rabbitmq_password}"
            f"@{self.rabbitmq_host}:{self.rabbitmq_port}/"
        )

    @property
    def milvus_uri(self) -> str:
        return f"http://{self.milvus_host}:{self.milvus_port}"

    @property
    def llm_configured(self) -> bool:
        return bool(self.llm_api_key and self.llm_api_key != "your-api-key-here")

    @property
    def embedding_remote_configured(self) -> bool:
        return bool(
            self.embedding_base_url
            and self.embedding_api_key
            and self.embedding_api_key != "your-api-key-here"
        )

    @property
    def rerank_remote_configured(self) -> bool:
        return bool(
            self.rerank_base_url
            and self.rerank_api_key
            and self.rerank_api_key != "your-api-key-here"
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()
