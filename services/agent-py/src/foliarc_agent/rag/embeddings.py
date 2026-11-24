from __future__ import annotations

import hashlib
import math
import re
from dataclasses import dataclass

import httpx

from foliarc_agent.config import Settings

_TOKEN_RE = re.compile(r"[a-zA-Z0-9_]+|[\u4e00-\u9fff]+")


def _extract_features(text: str) -> list[tuple[str, float]]:
    """
    Extract multilingual lexical features (English words + CJK unigrams/bigrams/trigrams)
    for deterministic zero-cost local dense embeddings and cross-feature reranking.
    """
    cleaned = text.lower().strip()
    if not cleaned:
        return []
    feats: list[tuple[str, float]] = []
    for match in _TOKEN_RE.finditer(cleaned):
        tok = match.group(0)
        if tok.isascii():
            feats.append((f"w:{tok}", 1.4))
            if len(tok) >= 4:
                for i in range(len(tok) - 2):
                    feats.append((f"c3:{tok[i : i + 3]}", 0.5))
        else:
            n = len(tok)
            for i in range(n):
                feats.append((f"zh1:{tok[i]}", 0.6))
                if i + 2 <= n:
                    feats.append((f"zh2:{tok[i : i + 2]}", 1.5))
                if i + 3 <= n:
                    feats.append((f"zh3:{tok[i : i + 3]}", 1.8))
    return feats


def local_hash_embed(text: str, dim: int = 1024) -> list[float]:
    """
    Deterministic L2-normalized signed feature-hashing embedding matching BGE-M3's 1024 dim.
    Guarantees zero-cost Milvus HNSW+COSINE operation until external Embedding API keys are added.
    """
    vec = [0.0] * dim
    feats = _extract_features(text)
    if not feats:
        vec[0] = 1.0
        return vec
    for feat, weight in feats:
        digest = hashlib.blake2b(feat.encode("utf-8"), digest_size=8).digest()
        h = int.from_bytes(digest[:4], "little")
        sign = 1.0 if (digest[4] & 1) == 0 else -1.0
        vec[h % dim] += sign * weight
    norm = math.sqrt(sum(v * v for v in vec))
    if norm <= 1e-12:
        vec[0] = 1.0
        return vec
    inv = 1.0 / norm
    return [v * inv for v in vec]


class EmbeddingProvider:
    """Dual-mode embedding client: remote OpenAI-compatible API with local fallback."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    @property
    def mode(self) -> str:
        return "remote" if self._settings.embedding_remote_configured else "local_fallback"

    @property
    def dim(self) -> int:
        return self._settings.embedding_dim

    async def embed_texts(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        if not self._settings.embedding_remote_configured:
            return [local_hash_embed(t, self.dim) for t in texts]

        base = self._settings.embedding_base_url.rstrip("/")
        url = f"{base}/embeddings" if not base.endswith("/embeddings") else base
        headers = {
            "Authorization": f"Bearer {self._settings.embedding_api_key}",
            "Content-Type": "application/json",
        }
        vectors: list[list[float]] = []
        batch_size = 32
        async with httpx.AsyncClient(timeout=httpx.Timeout(60.0, connect=10.0)) as client:
            for start in range(0, len(texts), batch_size):
                batch = texts[start : start + batch_size]
                resp = await client.post(
                    url,
                    headers=headers,
                    json={"model": self._settings.llm_model_embed, "input": batch},
                )
                resp.raise_for_status()
                payload = resp.json()
                data = sorted(payload.get("data", []), key=lambda item: item.get("index", 0))
                for item in data:
                    vec = [float(x) for x in item["embedding"]]
                    vectors.append(self._align_dim(vec))
        return vectors

    def _align_dim(self, vec: list[float]) -> list[float]:
        target = self.dim
        if len(vec) == target:
            return vec
        if len(vec) > target:
            truncated = vec[:target]
            norm = math.sqrt(sum(v * v for v in truncated)) or 1.0
            return [v / norm for v in truncated]
        return vec + [0.0] * (target - len(vec))


@dataclass(frozen=True, slots=True)
class RerankCandidate:
    item_id: str
    text: str
    title: str
    base_score: float


class RerankProvider:
    """Dual-mode reranker: remote BGE/SiliconFlow/Jina Rerank API with local cross-feature fallback."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    @property
    def mode(self) -> str:
        return "remote" if self._settings.rerank_remote_configured else "local_fallback"

    async def rerank(
        self,
        query: str,
        candidates: list[RerankCandidate],
        top_k: int,
    ) -> list[tuple[str, float]]:
        if not candidates:
            return []
        if self._settings.rerank_remote_configured:
            try:
                return await self._remote_rerank(query, candidates, top_k)
            except (httpx.HTTPError, KeyError, ValueError):
                pass
        return self._local_rerank(query, candidates, top_k)

    async def _remote_rerank(
        self,
        query: str,
        candidates: list[RerankCandidate],
        top_k: int,
    ) -> list[tuple[str, float]]:
        base = self._settings.rerank_base_url.rstrip("/")
        url = f"{base}/rerank" if not base.endswith("/rerank") else base
        headers = {
            "Authorization": f"Bearer {self._settings.rerank_api_key}",
            "Content-Type": "application/json",
        }
        docs = [f"{c.title}\n{c.text}"[:2000] for c in candidates]
        async with httpx.AsyncClient(timeout=httpx.Timeout(30.0, connect=10.0)) as client:
            resp = await client.post(
                url,
                headers=headers,
                json={
                    "model": self._settings.llm_model_rerank,
                    "query": query,
                    "documents": docs,
                    "top_n": min(top_k, len(candidates)),
                },
            )
            resp.raise_for_status()
            payload = resp.json()
        ranked: list[tuple[str, float]] = []
        for row in payload.get("results", []):
            idx = int(row["index"])
            score = float(row.get("relevance_score", row.get("score", 0.0)))
            if 0 <= idx < len(candidates):
                ranked.append((candidates[idx].item_id, round(score, 4)))
        ranked.sort(key=lambda x: x[1], reverse=True)
        return ranked[:top_k]

    def _local_rerank(
        self,
        query: str,
        candidates: list[RerankCandidate],
        top_k: int,
    ) -> list[tuple[str, float]]:
        q_feats = {k for k, _ in _extract_features(query)}
        q_lower = query.lower().strip()
        q_terms = [t for t in _TOKEN_RE.findall(q_lower) if len(t) >= 2]
        scored: list[tuple[str, float]] = []
        for c in candidates:
            doc_lower = f"{c.title} {c.text}".lower()
            d_feats = {k for k, _ in _extract_features(doc_lower)}
            overlap = len(q_feats & d_feats) / max(1, len(q_feats))
            exact_hits = sum(1 for t in q_terms if t in doc_lower) / max(1, len(q_terms))
            title_boost = 0.12 if any(t in c.title.lower() for t in q_terms) else 0.0
            # Blend normalized RRF base score with cross-lexical match
            final = 0.45 * min(1.0, c.base_score * 12.0) + 0.35 * overlap + 0.20 * exact_hits + title_boost
            scored.append((c.item_id, round(min(1.0, final), 4)))
        scored.sort(key=lambda x: x[1], reverse=True)
        return scored[:top_k]
