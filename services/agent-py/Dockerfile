# syntax=docker/dockerfile:1.7

# ==============================================================================
# Stage 1: Build virtual environment with uv (locked dependencies + non-editable wheel)
# ==============================================================================
FROM python:3.12-slim AS builder

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=0 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

RUN pip install --no-cache-dir "uv==0.12.13"

WORKDIR /app

COPY pyproject.toml uv.lock README.md ./
ARG UV_DEFAULT_INDEX=https://pypi.tuna.tsinghua.edu.cn/simple
ENV UV_DEFAULT_INDEX=${UV_DEFAULT_INDEX}
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-install-project --no-dev

COPY src ./src
COPY skills ./skills
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev --no-editable

# ==============================================================================
# Stage 2: Minimal non-root runtime image (includes novel-lore-mcp in venv)
# ==============================================================================
FROM python:3.12-slim AS runtime

RUN groupadd --system inkflow && \
    useradd --system --gid inkflow --home-dir /app --shell /sbin/nologin inkflow

WORKDIR /app

COPY --from=builder --chown=inkflow:inkflow /app/.venv /app/.venv
COPY --from=builder --chown=inkflow:inkflow /app/skills /app/skills

ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    INKFLOW_SKILLS_DIR=/app/skills

USER inkflow

EXPOSE 8000

HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health/ready', timeout=3)"]

CMD ["uvicorn", "inkflow_agent.main:app", "--host", "0.0.0.0", "--port", "8000"]
