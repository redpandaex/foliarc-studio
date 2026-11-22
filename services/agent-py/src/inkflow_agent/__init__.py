import uvicorn

from inkflow_agent.config import get_settings


def main() -> None:
    settings = get_settings()
    uvicorn.run(
        "inkflow_agent.main:app",
        host=settings.agent_py_host,
        port=settings.agent_py_port,
        reload=settings.app_env == "dev",
    )
