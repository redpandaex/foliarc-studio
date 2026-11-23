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

    app_name: str = "inkflow-agent"
    app_env: str = "dev"
    agent_py_host: str = "0.0.0.0"
    agent_py_port: int = 8000

    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_password: str = ""

    internal_service_token: str = "inkflow-internal-dev-token-change-me"
    biz_java_base_url: str = "http://localhost:8080"
    stream_ttl_seconds: int = 7200

    llm_base_url: str = "https://api.deepseek.com/v1"
    llm_api_key: str = ""
    llm_model_writer_main: str = "deepseek-chat"
    llm_model_critic_reasoner: str = "deepseek-reasoner"
    llm_model_fast_router: str = "deepseek-chat"
    llm_model_embed: str = "BAAI/bge-m3"
    llm_model_rerank: str = "BAAI/bge-reranker-v2-m3"

    @property
    def redis_url(self) -> str:
        if self.redis_password:
            return f"redis://:{self.redis_password}@{self.redis_host}:{self.redis_port}/0"
        return f"redis://{self.redis_host}:{self.redis_port}/0"

    @property
    def llm_configured(self) -> bool:
        return bool(self.llm_api_key and self.llm_api_key != "your-api-key-here")


@lru_cache
def get_settings() -> Settings:
    return Settings()
