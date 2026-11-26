from fastapi.testclient import TestClient

from foliarc_agent.main import app


def test_health_endpoint() -> None:
    with TestClient(app) as client:
        response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["service"] == "agent-py"


def test_meta_endpoint() -> None:
    with TestClient(app) as client:
        response = client.get("/api/v1/meta")
    assert response.status_code == 200
    body = response.json()
    assert "writer-main" in body["model_aliases"]
    assert "critic-reasoner" in body["model_aliases"]


def test_trace_id_middleware_propagates_and_generates() -> None:
    with TestClient(app) as client:
        r1 = client.get("/health", headers={"X-Trace-Id": "trace-custom-123"})
        assert r1.headers.get("X-Trace-Id") == "trace-custom-123"

        r2 = client.get(
            "/health",
            headers={"traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"},
        )
        assert r2.headers.get("X-Trace-Id") == "4bf92f3577b34da6a3ce929d0e0e4736"

        r3 = client.get("/health")
        assert len(r3.headers.get("X-Trace-Id", "")) == 32
