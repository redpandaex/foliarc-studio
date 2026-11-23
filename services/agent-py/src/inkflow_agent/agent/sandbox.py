from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True, slots=True)
class SandboxExecResult:
    success: bool
    stdout: str
    stderr: str
    exit_code: int
    duration_ms: int
    provider: str


class SandboxProvider(Protocol):
    """
    Abstract execution sandbox protocol.
    Phase 2 defines the Tool contract; Phase 5 drops in Hardened DockerSandboxProvider
    (network=none, read-only rootfs, cap-drop=ALL, Warm Pool) with zero Agent changes.
    """

    async def exec_python(
        self,
        *,
        run_id: str,
        code: str,
        timeout_seconds: int = 10,
    ) -> SandboxExecResult: ...


class StubSandboxProvider:
    """Safe default provider before Phase 5 Hardened Docker Container Sandbox is enabled."""

    async def exec_python(
        self,
        *,
        run_id: str,
        code: str,
        timeout_seconds: int = 10,
    ) -> SandboxExecResult:
        return SandboxExecResult(
            success=False,
            stdout="",
            stderr=(
                "Docker 硬化执行沙箱（DockerSandboxProvider）将在 Phase 5 启用，"
                "当前 Phase 2 阶段不会在宿主机直接执行任意生成代码（安全隔离保护）。"
            ),
            exit_code=126,
            duration_ms=0,
            provider="stub-phase2",
        )
