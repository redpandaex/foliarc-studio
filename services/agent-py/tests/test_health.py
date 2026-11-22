from fastapi.testclient import TestClient

from inkflow_agent.main import app


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
