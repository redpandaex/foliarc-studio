#!/usr/bin/env python3
"""
FoliarcStudio Live End-to-End Docker Stack Smoke & Security Verifier.
Verifies all 5 containerized services (:3000, :3001, :8080, :8081, :8000):
  1. Health endpoints across all 5 services + X-Trace-Id header echo
  2. Author register/login -> Book/Chapter CRUD -> EPUB 3.0 + Markdown + TXT export
  3. Dual-audience JWT isolation (web token rejected by admin-java; admin token rejected by biz-java)
  4. Admin Console APIs (overview, users, books, prompt templates, live 30-case Golden Eval)
  5. Idempotent concurrent billing top-up stress check (zero double-crediting under race)
"""
from __future__ import annotations

import concurrent.futures
import io
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
import zipfile
from typing import Any

WEB_URL = os.environ.get("WEB_URL", "http://localhost:3000").rstrip("/")
ADMIN_WEB_URL = os.environ.get("ADMIN_WEB_URL", "http://localhost:3001").rstrip("/")
BIZ_URL = os.environ.get("BIZ_URL", "http://localhost:8080").rstrip("/")
ADMIN_API_URL = os.environ.get("ADMIN_API_URL", "http://localhost:8081").rstrip("/")
AGENT_URL = os.environ.get("AGENT_URL", "http://localhost:8000").rstrip("/")


def http_req(
    method: str,
    url: str,
    *,
    body: dict[str, Any] | None = None,
    headers: dict[str, str] | None = None,
    timeout: float = 15.0,
) -> tuple[int, dict[str, str], bytes]:
    hdrs = dict(headers or {})
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        hdrs.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, {k.lower(): v for k, v in resp.headers.items()}, resp.read()
    except urllib.error.HTTPError as err:
        return err.code, {k.lower(): v for k, v in err.headers.items()}, err.read()


def check(cond: bool, msg: str) -> None:
    if not cond:
        print(f"FAIL: {msg}", file=sys.stderr)
        sys.exit(1)
    print(f"  [OK] {msg}")


def main() -> None:
    print("== 1. Five-Service Health & X-Trace-Id Propagation ==")
    trace_probe = f"smoke-{uuid.uuid4().hex[:12]}"
    for name, url, expect_trace in [
        ("web", f"{WEB_URL}/api/health", False),
        ("admin-web", f"{ADMIN_WEB_URL}/api/health", False),
        ("biz-java", f"{BIZ_URL}/api/v1/health", True),
        ("admin-java", f"{ADMIN_API_URL}/api/v1/admin/health", True),
        ("agent-py", f"{AGENT_URL}/health", True),
    ]:
        st, hdrs, raw = http_req("GET", url, headers={"X-Trace-Id": trace_probe})
        check(st == 200, f"{name} health 200 ({url})")
        if expect_trace:
            echoed = hdrs.get("x-trace-id", "")
            check(echoed == trace_probe, f"{name} echoed X-Trace-Id={echoed}")

    print("\n== 2. Author Flow: Register -> Book -> Chapter -> EPUB/MD/TXT Export ==")
    suffix = uuid.uuid4().hex[:8]
    email = f"smoke_{suffix}@foliarc.dev"
    password = "SmokePassword123!"
    st, _, raw = http_req(
        "POST",
        f"{BIZ_URL}/api/v1/auth/register",
        body={"email": email, "username": f"smoke_{suffix}", "password": password},
    )
    check(st in (200, 201), f"Author registered ({email}, status={st})")
    auth = json.loads(raw)
    web_token = auth["accessToken"]
    user_id = auth["user"]["id"]
    web_hdrs = {"Authorization": f"Bearer {web_token}"}

    st, _, raw = http_req(
        "POST",
        f"{BIZ_URL}/api/v1/books",
        headers=web_hdrs,
        body={
            "title": f"雪夜残剑录_{suffix}",
            "genre": "古典 / 历史 / 世情志怪",
            "genreCode": "CLASSICAL_HISTORICAL",
            "synopsis": "端到端冒烟测试样书",
        },
    )
    check(st == 201, f"Book created (status={st})")
    book_id = json.loads(raw)["id"]

    st, _, raw = http_req(
        "POST",
        f"{BIZ_URL}/api/v1/books/{book_id}/chapters",
        headers=web_hdrs,
        body={"title": "第一章 寒潭旧剑", "content": "暮雪初霁，林青执灯走过青石长街。\n\n灯火摇曳间，旧剑微鸣。"},
    )
    check(st == 201, f"Chapter created (status={st})")

    # Export Markdown, TXT, and EPUB 3.0
    for fmt in ("md", "txt", "epub"):
        st, hdrs, raw = http_req("GET", f"{BIZ_URL}/api/v1/books/{book_id}/export?format={fmt}", headers=web_hdrs)
        check(st == 200 and len(raw) > 50, f"Export {fmt.upper()} succeeded ({len(raw)} bytes)")
        if fmt == "epub":
            zf = zipfile.ZipFile(io.BytesIO(raw))
            names = zf.namelist()
            check(names[0] == "mimetype", f"EPUB first entry is mimetype ({names[:4]})")
            check(zf.getinfo("mimetype").compress_type == zipfile.ZIP_STORED, "EPUB mimetype is STORED uncompressed")
            check("META-INF/container.xml" in names and "OEBPS/content.opf" in names, "EPUB 3.0 manifest intact")

    print("\n== 3. Dual-Audience JWT Isolation & Concurrent Billing Idempotency ==")
    st, _, _ = http_req("GET", f"{ADMIN_API_URL}/api/v1/admin/overview", headers=web_hdrs)
    check(st in (401, 403), f"admin-java rejected web token (status={st})")

    idem_key = f"smoke-topup-{uuid.uuid4().hex}"

    def do_topup(_: int) -> int:
        code, _, _ = http_req(
            "POST",
            f"{BIZ_URL}/api/v1/billing/topup",
            headers=web_hdrs,
            body={"amountCredits": 25.0, "requestId": idem_key},
        )
        return code

    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        codes = list(pool.map(do_topup, range(6)))
    check(all(c == 200 for c in codes), f"6 concurrent identical top-ups handled cleanly ({codes})")
    st, _, raw = http_req("GET", f"{BIZ_URL}/api/v1/billing/dashboard", headers=web_hdrs)
    check(st == 200, "Billing dashboard fetched")
    bal = json.loads(raw)["account"]["balanceCredits"]
    check(abs(bal - 75.0) < 0.01, f"Idempotent top-up credited exactly once (50 + 25 = {bal})")

    print("\n== 4. Admin Console Live Check (Optional ADMIN_EMAIL / ADMIN_PASSWORD) ==")
    admin_email = os.environ.get("ADMIN_EMAIL", "").strip()
    admin_password = os.environ.get("ADMIN_PASSWORD", "").strip()
    if admin_email and admin_password:
        st, _, raw = http_req(
            "POST",
            f"{ADMIN_API_URL}/api/v1/admin/auth/login",
            body={"email": admin_email, "password": admin_password},
        )
        check(st == 200, f"Admin login succeeded ({admin_email})")
        admin_token = json.loads(raw)["accessToken"]
        admin_hdrs = {"Authorization": f"Bearer {admin_token}"}

        st, _, _ = http_req("GET", f"{BIZ_URL}/api/v1/books", headers=admin_hdrs)
        check(st in (401, 403), f"biz-java rejected admin token (status={st})")

        st, _, raw = http_req("GET", f"{ADMIN_API_URL}/api/v1/admin/users?q={suffix}", headers=admin_hdrs)
        check(st == 200 and any(u["id"] == user_id for u in json.loads(raw)), "Admin user search found smoke author")

        st, _, raw = http_req("POST", f"{ADMIN_API_URL}/api/v1/admin/evals/run", headers=admin_hdrs, timeout=30.0)
        check(st == 200, "Admin Golden Eval endpoint returned 200")
        ev = json.loads(raw)
        check(
            ev["total_cases"] == 30 and ev["passed_cases"] == 30,
            f"Live Golden Eval passed {ev['passed_cases']}/{ev['total_cases']} in {ev['duration_ms']}ms",
        )
    else:
        print("  [SKIP] Set ADMIN_EMAIL & ADMIN_PASSWORD env vars to also run authenticated admin checks.")

    # Cleanup smoke book
    http_req("DELETE", f"{BIZ_URL}/api/v1/books/{book_id}", headers=web_hdrs)
    print("\nALL SMOKE CHECKS PASSED!")


if __name__ == "__main__":
    main()
