from __future__ import annotations

import json
import os
import sys
from contextlib import AsyncExitStack
from dataclasses import dataclass
from types import TracebackType
from typing import Any, Self

from mcp import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client


@dataclass(frozen=True, slots=True)
class DiscoveredMcpTool:
    name: str
    description: str
    input_schema: dict[str, Any]


class McpLoreClient:
    """
    Real MCP Client connecting to `novel-lore-mcp` over stdio transport with tenant-scoped
    environment variables (`INKFLOW_OWNER_ID`, `INKFLOW_BOOK_ID`), performing standard MCP
    `initialize`, `tools/list`, `resources/read`, and `tools/call` RPCs.
    """

    def __init__(
        self,
        *,
        biz_java_url: str,
        internal_token: str,
        owner_id: str,
        book_id: str,
    ) -> None:
        self._biz_java_url = biz_java_url
        self._internal_token = internal_token
        self._owner_id = owner_id
        self._book_id = book_id
        self._stack = AsyncExitStack()
        self._session: ClientSession | None = None

    async def __aenter__(self) -> Self:
        env = os.environ.copy()
        env.update(
            {
                "INKFLOW_BIZ_JAVA_URL": self._biz_java_url,
                "INKFLOW_INTERNAL_TOKEN": self._internal_token,
                "INKFLOW_OWNER_ID": self._owner_id,
                "INKFLOW_BOOK_ID": self._book_id,
            }
        )
        params = StdioServerParameters(
            command=sys.executable,
            args=["-m", "inkflow_agent.mcp_server.novel_lore_server", "--transport", "stdio"],
            env=env,
        )
        read_stream, write_stream = await self._stack.enter_async_context(stdio_client(params))
        self._session = await self._stack.enter_async_context(
            ClientSession(read_stream, write_stream)
        )
        await self._session.initialize()
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self._stack.aclose()
        self._session = None

    async def list_tools(self) -> list[DiscoveredMcpTool]:
        if self._session is None:
            raise RuntimeError("McpLoreClient session is not open")
        result = await self._session.list_tools()
        tools: list[DiscoveredMcpTool] = []
        for t in result.tools:
            raw_schema = getattr(t, "input_schema", None) or getattr(t, "inputSchema", None)
            schema = raw_schema if isinstance(raw_schema, dict) else {"type": "object", "properties": {}}
            tools.append(
                DiscoveredMcpTool(
                    name=t.name,
                    description=t.description or "",
                    input_schema=schema,
                )
            )
        return tools

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> str:
        if self._session is None:
            raise RuntimeError("McpLoreClient session is not open")
        result = await self._session.call_tool(name, arguments=arguments)
        texts: list[str] = []
        for item in result.content:
            text = getattr(item, "text", None)
            if text is not None:
                texts.append(str(text))
            else:
                texts.append(json.dumps(item.model_dump(), ensure_ascii=False))
        joined = "\n".join(texts)
        is_err = getattr(result, "is_error", None)
        if is_err is None:
            is_err = getattr(result, "isError", False)
        if is_err:
            raise RuntimeError(joined or f"MCP tool {name} returned error")
        return joined

    async def read_bible_resource(self) -> str:
        if self._session is None:
            raise RuntimeError("McpLoreClient session is not open")
        result = await self._session.read_resource("lore://bible/current")
        parts = [str(getattr(c, "text", "")) for c in result.contents]
        return "\n".join(p for p in parts if p)
