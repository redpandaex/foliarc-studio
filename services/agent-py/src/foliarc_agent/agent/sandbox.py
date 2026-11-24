from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import struct
import time
from dataclasses import dataclass
from typing import Any, Protocol

import httpx

from foliarc_agent.config import Settings

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class SandboxExecutionResult:
    success: bool
    exit_code: int
    stdout: str
    stderr: str
    duration_ms: int
    provider: str
    container_id: str = ""
    from_warm_pool: bool = False
    timed_out: bool = False


class SandboxProvider(Protocol):
    """
    Unified code-execution sandbox abstraction (Module 2.10):
    - Production default: `DockerSandboxProvider` (Six-layer hardened Docker Engine API over UDS)
    - Drop-in runtime isolation: set `SANDBOX_DOCKER_RUNTIME=runsc` for gVisor kernel sandbox
    - Extensible for E2B / Firecracker MicroVM remote providers
    """

    @property
    def provider_name(self) -> str: ...

    async def start(self) -> None: ...

    async def shutdown(self) -> None: ...

    async def health_check(self) -> bool: ...

    def stats(self) -> dict[str, Any]: ...

    async def exec_python(
        self,
        *,
        run_id: str,
        code: str,
        timeout_seconds: float = 8.0,
    ) -> SandboxExecutionResult: ...


class StubSandboxProvider:
    """Safe fallback when Docker socket is unavailable (never runs untrusted code on host)."""

    @property
    def provider_name(self) -> str:
        return "stub"

    async def start(self) -> None:
        return None

    async def shutdown(self) -> None:
        return None

    async def health_check(self) -> bool:
        return False

    def stats(self) -> dict[str, Any]:
        return {"provider": "stub", "docker_available": False, "warm_pool_ready": 0}

    async def exec_python(
        self,
        *,
        run_id: str,
        code: str,
        timeout_seconds: float = 8.0,
    ) -> SandboxExecutionResult:
        _ = (run_id, timeout_seconds)
        preview = code.strip().splitlines()[0][:80] if code.strip() else ""
        return SandboxExecutionResult(
            success=False,
            exit_code=125,
            stdout="",
            stderr=(
                f"[Sandbox Stub] Docker 沙箱不可用，出于安全策略已拒绝在宿主机直接执行代码（首行: {preview}）。"
                "请确认已挂载 /var/run/docker.sock。"
            ),
            duration_ms=1,
            provider="stub",
        )


def _demux_docker_stream(raw: bytes) -> tuple[str, str]:
    """
    Decode Docker Engine multiplexed attach stream frames:
    8-byte header: [stream_type(1B: 1=stdout, 2=stderr), 0, 0, 0, size(4B big-endian)] + payload.
    """
    stdout_chunks: list[bytes] = []
    stderr_chunks: list[bytes] = []
    pos = 0
    total = len(raw)

    while pos + 8 <= total:
        stream_type = raw[pos]
        if stream_type not in (1, 2, 3) or raw[pos + 1 : pos + 4] != b"\x00\x00\x00":
            # Fallback if server returned unframed raw text
            stdout_chunks.append(raw[pos:])
            break
        size = struct.unpack(">I", raw[pos + 4 : pos + 8])[0]
        pos += 8
        payload = raw[pos : pos + size]
        pos += size
        if stream_type == 1:
            stdout_chunks.append(payload)
        elif stream_type == 2:
            stderr_chunks.append(payload)

    return (
        b"".join(stdout_chunks).decode("utf-8", errors="replace"),
        b"".join(stderr_chunks).decode("utf-8", errors="replace"),
    )


class DockerSandboxProvider:
    """
    Six-Layer Hardened Docker Container Sandbox (using Docker Engine REST API over Unix Socket):
    1. Network Isolation: `NetworkMode="none"` + `NetworkDisabled=True` (zero SSRF/exfiltration)
    2. Read-only RootFS + bounded `noexec,nosuid` tmpfs `/tmp` (32MiB)
    3. Zero Capabilities (`CapDrop=["ALL"]`), `no-new-privileges:true`, non-root `65534:65534`
    4. Strict Cgroups limits: 128MiB RAM (no swap), 0.5 CPU, `PidsLimit=32` (stops Fork Bombs)
    5. Zero host secret/environment passthrough (minimal sterile Python env only)
    6. Hard execution timeout + Burn-After-Reading (`DELETE /containers/{id}?force=true&v=true`)

    Lifecycle Governance:
    - Warm Pool (`pool_size` pre-warmed idle hardened containers, NEVER reused across executions!)
    - Background Zombie Reaper patrolling label `foliarc.sandbox=true`
    - Optional gVisor kernel runtime via `SANDBOX_DOCKER_RUNTIME=runsc`
    """

    SANDBOX_LABEL = "foliarc.sandbox=true"

    def __init__(self, settings: Settings) -> None:
        self._socket_path = settings.sandbox_docker_socket
        self._image = settings.sandbox_docker_image
        self._runtime = settings.sandbox_docker_runtime.strip()
        self._pool_size = max(0, settings.sandbox_pool_size)
        self._default_timeout = settings.sandbox_timeout_seconds
        self._memory_bytes = max(32, settings.sandbox_memory_mb) * 1024 * 1024
        self._nano_cpus = int(max(0.1, settings.sandbox_cpu_quota) * 1_000_000_000)
        self._pids_limit = max(8, settings.sandbox_pids_limit)
        self._max_age_seconds = max(30, settings.sandbox_max_age_seconds)

        self._pool: asyncio.Queue[str] = asyncio.Queue(maxsize=max(1, self._pool_size))
        self._reaper_task: asyncio.Task[None] | None = None
        self._refill_tasks: set[asyncio.Task[None]] = set()
        self._stop_event = asyncio.Event()
        self._total_execs = 0
        self._pool_hits = 0
        self._reaped_count = 0

    @property
    def provider_name(self) -> str:
        return f"docker:{self._runtime}" if self._runtime else "docker-hardened"

    def _new_client(self, timeout_sec: float = 15.0) -> httpx.AsyncClient:
        transport = httpx.AsyncHTTPTransport(uds=self._socket_path)
        return httpx.AsyncClient(
            transport=transport,
            base_url="http://docker",
            timeout=httpx.Timeout(timeout_sec, connect=5.0),
        )

    async def health_check(self) -> bool:
        if not os.path.exists(self._socket_path):
            return False
        try:
            async with self._new_client(3.0) as client:
                resp = await client.get("/_ping")
                return resp.status_code == 200
        except (httpx.HTTPError, OSError):
            return False

    def stats(self) -> dict[str, Any]:
        return {
            "provider": self.provider_name,
            "image": self._image,
            "runtime": self._runtime or "runc",
            "warm_pool_ready": self._pool.qsize(),
            "warm_pool_target": self._pool_size,
            "total_executions": self._total_execs,
            "warm_pool_hits": self._pool_hits,
            "zombies_reaped": self._reaped_count,
            "hardening": {
                "network": "none",
                "readonly_rootfs": True,
                "cap_drop": ["ALL"],
                "no_new_privileges": True,
                "user": "65534:65534",
                "memory_mb": self._memory_bytes // (1024 * 1024),
                "pids_limit": self._pids_limit,
            },
        }

    async def start(self) -> None:
        self._stop_event.clear()
        if not await self.health_check():
            logger.warning("Docker socket %s unreachable; warm pool not started", self._socket_path)
            return
        await self.reap_zombies(force_all=True)
        for _ in range(self._pool_size):
            self._schedule_refill()
        self._reaper_task = asyncio.create_task(self._reaper_loop(), name="foliarc-sandbox-reaper")

    async def shutdown(self) -> None:
        self._stop_event.set()
        if self._reaper_task is not None and not self._reaper_task.done():
            self._reaper_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._reaper_task
        for t in list(self._refill_tasks):
            t.cancel()
        if self._refill_tasks:
            await asyncio.gather(*self._refill_tasks, return_exceptions=True)
        # Destroy any remaining pooled idle containers cleanly
        while not self._pool.empty():
            cid = self._pool.get_nowait()
            with contextlib.suppress(Exception):
                await self._destroy_container(cid)

    async def exec_python(
        self,
        *,
        run_id: str,
        code: str,
        timeout_seconds: float = 8.0,
    ) -> SandboxExecutionResult:
        started = time.perf_counter()
        self._total_execs += 1
        effective_timeout = max(1.0, min(timeout_seconds, 20.0))

        container_id: str | None = None
        from_pool = False
        try:
            try:
                container_id = self._pool.get_nowait()
                from_pool = True
                self._pool_hits += 1
            except asyncio.QueueEmpty:
                container_id = await self._spawn_hardened_container(run_id=run_id)

            stdout, stderr, exit_code, timed_out = await self._exec_in_container(
                container_id=container_id,
                code=code,
                timeout_seconds=effective_timeout,
            )
            elapsed_ms = int((time.perf_counter() - started) * 1000)
            return SandboxExecutionResult(
                success=(exit_code == 0 and not timed_out),
                exit_code=exit_code,
                stdout=stdout[:12000],
                stderr=stderr[:6000],
                duration_ms=elapsed_ms,
                provider=self.provider_name,
                container_id=container_id[:12],
                from_warm_pool=from_pool,
                timed_out=timed_out,
            )
        except (httpx.HTTPError, OSError, RuntimeError) as exc:
            elapsed_ms = int((time.perf_counter() - started) * 1000)
            return SandboxExecutionResult(
                success=False,
                exit_code=125,
                stdout="",
                stderr=f"Docker 沙箱执行失败: {exc}",
                duration_ms=elapsed_ms,
                provider=self.provider_name,
                container_id=(container_id or "")[:12],
                from_warm_pool=from_pool,
            )
        finally:
            # Burn-After-Reading: ALWAYS destroy container immediately, NEVER reuse across tenants!
            if container_id is not None:
                with contextlib.suppress(Exception):
                    await self._destroy_container(container_id)
            if self._pool_size > 0 and not self._stop_event.is_set():
                self._schedule_refill()

    async def _spawn_hardened_container(self, *, run_id: str = "pool") -> str:
        host_config: dict[str, Any] = {
            "NetworkMode": "none",
            "ReadonlyRootfs": True,
            "Tmpfs": {"/tmp": "rw,noexec,nosuid,size=32m"},
            "CapDrop": ["ALL"],
            "SecurityOpt": ["no-new-privileges:true"],
            "Memory": self._memory_bytes,
            "MemorySwap": self._memory_bytes,
            "NanoCpus": self._nano_cpus,
            "PidsLimit": self._pids_limit,
        }
        if self._runtime:
            host_config["Runtime"] = self._runtime

        payload = {
            "Image": self._image,
            "Cmd": ["sleep", str(self._max_age_seconds + 60)],
            "User": "65534:65534",
            "WorkingDir": "/tmp",
            "NetworkDisabled": True,
            "Env": [
                "PYTHONUNBUFFERED=1",
                "PYTHONDONTWRITEBYTECODE=1",
                "HOME=/tmp",
                "TMPDIR=/tmp",
            ],
            "Labels": {
                "foliarc.sandbox": "true",
                "foliarc.run_id": run_id[:64],
                "foliarc.created_at": str(int(time.time())),
            },
            "HostConfig": host_config,
        }

        async with self._new_client(15.0) as client:
            create_resp = await client.post("/containers/create", json=payload)
            if create_resp.status_code >= 400:
                raise RuntimeError(f"create container HTTP {create_resp.status_code}: {create_resp.text[:300]}")
            container_id = str(create_resp.json()["Id"])
            start_resp = await client.post(f"/containers/{container_id}/start")
            if start_resp.status_code >= 400:
                await self._destroy_container(container_id)
                raise RuntimeError(f"start container HTTP {start_resp.status_code}: {start_resp.text[:300]}")
            return container_id

    async def _exec_in_container(
        self,
        *,
        container_id: str,
        code: str,
        timeout_seconds: float,
    ) -> tuple[str, str, int, bool]:
        async with self._new_client(timeout_seconds + 5.0) as client:
            exec_create = await client.post(
                f"/containers/{container_id}/exec",
                json={
                    "AttachStdout": True,
                    "AttachStderr": True,
                    "Tty": False,
                    "User": "65534:65534",
                    "WorkingDir": "/tmp",
                    "Cmd": ["python3", "-I", "-c", code],
                },
            )
            exec_create.raise_for_status()
            exec_id = str(exec_create.json()["Id"])

            try:
                start_resp = await asyncio.wait_for(
                    client.post(
                        f"/exec/{exec_id}/start",
                        json={"Detach": False, "Tty": False},
                    ),
                    timeout=timeout_seconds,
                )
            except TimeoutError:
                return "", f"沙箱脚本执行超过 {timeout_seconds:.1f}s 超时限制，容器已被强杀并销毁。", 124, True

            stdout, stderr = _demux_docker_stream(start_resp.content)
            inspect_resp = await client.get(f"/exec/{exec_id}/json")
            exit_code = int(inspect_resp.json().get("ExitCode", 1)) if inspect_resp.status_code == 200 else 1
            return stdout, stderr, exit_code, False

    async def _destroy_container(self, container_id: str) -> None:
        async with self._new_client(10.0) as client:
            await client.delete(f"/containers/{container_id}", params={"force": "true", "v": "true"})

    def _schedule_refill(self) -> None:
        if self._pool.full() or self._stop_event.is_set():
            return

        async def _refill() -> None:
            try:
                if self._pool.full() or self._stop_event.is_set():
                    return
                cid = await self._spawn_hardened_container(run_id="warm-pool")
                try:
                    self._pool.put_nowait(cid)
                except asyncio.QueueFull:
                    await self._destroy_container(cid)
            except (httpx.HTTPError, OSError, RuntimeError) as exc:
                logger.debug("Warm pool refill skipped: %s", exc)

        task = asyncio.create_task(_refill(), name="foliarc-sandbox-refill")
        self._refill_tasks.add(task)
        task.add_done_callback(self._refill_tasks.discard)

    async def reap_zombies(self, *, force_all: bool = False) -> int:
        """Patrols Docker daemon for expired sandbox containers and force-removes zombies."""
        reaped = 0
        now = time.time()
        try:
            async with self._new_client(10.0) as client:
                resp = await client.get(
                    "/containers/json",
                    params={
                        "all": "true",
                        "filters": '{"label":["foliarc.sandbox=true"]}',
                    },
                )
                if resp.status_code != 200:
                    return 0
                for item in resp.json():
                    cid = str(item.get("Id", ""))
                    labels = item.get("Labels") or {}
                    created_ts = float(labels.get("foliarc.created_at", item.get("Created", 0)))
                    if force_all or (now - created_ts > self._max_age_seconds):
                        await client.delete(f"/containers/{cid}", params={"force": "true", "v": "true"})
                        reaped += 1
        except (httpx.HTTPError, OSError, ValueError) as exc:
            logger.debug("Zombie reaper patrol skipped: %s", exc)
        self._reaped_count += reaped
        return reaped

    async def _reaper_loop(self) -> None:
        while not self._stop_event.is_set():
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(self._stop_event.wait(), timeout=60.0)
            if self._stop_event.is_set():
                break
            await self.reap_zombies(force_all=False)
            for _ in range(self._pool_size - self._pool.qsize()):
                self._schedule_refill()


def create_sandbox_provider(settings: Settings) -> SandboxProvider:
    if settings.sandbox_provider.lower() in ("docker", "gvisor") and os.path.exists(
        settings.sandbox_docker_socket
    ):
        return DockerSandboxProvider(settings)
    return StubSandboxProvider()
