"""
InkFlow Studio `novel-lore-mcp` Server (Official Python MCP SDK v2).

Supports BOTH `stdio` (for local Agent Host / IDE clients) and `streamable-http`
(for remote multi-service deployments). Enforces tenant isolation by forwarding
`X-Internal-Token` and `X-Owner-Id` on every request to `biz-java`.
"""

from __future__ import annotations

import argparse
import json
import os
from typing import Any

import httpx
from mcp.server.mcpserver import MCPServer


def _env(key: str, default: str = "") -> str:
    return os.environ.get(key, default).strip()


class _BizLoreHttpClient:
    def __init__(self, base_url: str, internal_token: str, owner_id: str, book_id: str) -> None:
        self._base_url = base_url.rstrip("/")
        self._headers = {
            "X-Internal-Token": internal_token,
            "X-Owner-Id": owner_id,
        }
        self._book_id = book_id

    def _ensure_scope(self) -> None:
        if not self._book_id or not self._headers["X-Owner-Id"]:
            raise RuntimeError("MCP server missing bound INKFLOW_BOOK_ID or INKFLOW_OWNER_ID scope")

    async def get(self, path: str, params: dict[str, Any] | None = None) -> Any:
        self._ensure_scope()
        url = f"{self._base_url}/internal/v1/books/{self._book_id}/lore/{path.lstrip('/')}"
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, headers=self._headers, params=params)
            if resp.status_code >= 400:
                raise RuntimeError(f"biz-java returned HTTP {resp.status_code}: {resp.text[:300]}")
            return resp.json()

    async def post(self, path: str, payload: dict[str, Any]) -> Any:
        self._ensure_scope()
        url = f"{self._base_url}/internal/v1/books/{self._book_id}/lore/{path.lstrip('/')}"
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, headers=self._headers, json=payload)
            if resp.status_code >= 400:
                raise RuntimeError(f"biz-java returned HTTP {resp.status_code}: {resp.text[:300]}")
            return resp.json()


def create_server(
    *,
    biz_java_url: str | None = None,
    internal_token: str | None = None,
    owner_id: str | None = None,
    book_id: str | None = None,
) -> MCPServer:
    client = _BizLoreHttpClient(
        base_url=biz_java_url or _env("INKFLOW_BIZ_JAVA_URL", "http://127.0.0.1:8080"),
        internal_token=internal_token
        or _env("INKFLOW_INTERNAL_TOKEN", "inkflow-internal-dev-token-change-me"),
        owner_id=owner_id or _env("INKFLOW_OWNER_ID"),
        book_id=book_id or _env("INKFLOW_BOOK_ID"),
    )

    mcp = MCPServer(
        name="novel-lore-mcp",
        version="0.2.0",
        instructions=(
            "InkFlow Studio 小说设定库 MCP Server。用于检索和更新当前作品的人物状态、"
            "世界观词条与未回收伏笔，强制绑定租户与作品权限边界。"
        ),
    )

    @mcp.tool(
        name="get_character_state",
        description="查询当前小说中指定角色（或全部角色）的性格、境界、当前位置、伤势状态与持有物品。写人物出场前应优先核实。",
    )
    async def get_character_state(name: str = "") -> str:
        params = {"name": name} if name.strip() else None
        rows = await client.get("characters", params=params)
        if not rows:
            return json.dumps(
                {"found": False, "message": f"未找到匹配角色: {name or '全部'}", "characters": []},
                ensure_ascii=False,
            )
        return json.dumps({"found": True, "characters": rows}, ensure_ascii=False)

    @mcp.tool(
        name="search_worldbook",
        description="按关键词检索当前小说的世界观设定词条（力量体系、宗门势力、法宝物品、地名规则等）。",
    )
    async def search_worldbook(keyword: str = "") -> str:
        params = {"q": keyword} if keyword.strip() else None
        rows = await client.get("worldbook", params=params)
        if not rows:
            return json.dumps(
                {"found": False, "message": f"未检索到包含「{keyword}」的世界观设定", "entries": []},
                ensure_ascii=False,
            )
        return json.dumps({"found": True, "entries": rows}, ensure_ascii=False)

    @mcp.tool(
        name="list_open_foreshadowings",
        description="列出当前小说中尚未回收（或指定状态：OPEN/RESOLVED/ABANDONED）的伏笔清单，避免遗忘前文暗线。",
    )
    async def list_open_foreshadowings(status: str = "OPEN") -> str:
        params = {"status": status} if status.strip() else None
        rows = await client.get("foreshadowings", params=params)
        return json.dumps({"count": len(rows), "foreshadowings": rows}, ensure_ascii=False)

    @mcp.tool(
        name="upsert_character_state",
        description="创建或更新角色的最新状态（境界突破、受伤、移动位置或获得新物品时调用）。",
    )
    async def upsert_character_state(
        name: str,
        role_type: str = "SUPPORTING",
        personality: str | None = None,
        realm_or_level: str | None = None,
        current_location: str | None = None,
        status_notes: str | None = None,
        inventory: str | None = None,
    ) -> str:
        payload: dict[str, Any] = {
            "name": name,
            "roleType": role_type,
        }
        if personality is not None:
            payload["personality"] = personality
        if realm_or_level is not None:
            payload["realmOrLevel"] = realm_or_level
        if current_location is not None:
            payload["currentLocation"] = current_location
        if status_notes is not None:
            payload["statusNotes"] = status_notes
        if inventory is not None:
            payload["inventory"] = inventory
        saved = await client.post("characters", payload)
        return json.dumps({"updated": True, "character": saved}, ensure_ascii=False)

    @mcp.resource(
        "lore://bible/current",
        name="novel-bible",
        description="当前作品完整设定集（书目信息、人物表、世界观词条、未回收伏笔）",
        mime_type="application/json",
    )
    async def read_novel_bible() -> str:
        bible = await client.get("bible")
        return json.dumps(bible, ensure_ascii=False)

    return mcp


def main() -> None:
    parser = argparse.ArgumentParser(description="InkFlow Studio novel-lore-mcp Server")
    parser.add_argument(
        "--transport",
        choices=["stdio", "streamable-http"],
        default="stdio",
        help="MCP transport protocol (default: stdio)",
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8010)
    args = parser.parse_args()

    server = create_server()
    if args.transport == "streamable-http":
        server.run(transport="streamable-http", host=args.host, port=args.port)
    else:
        server.run(transport="stdio")


if __name__ == "__main__":
    main()
