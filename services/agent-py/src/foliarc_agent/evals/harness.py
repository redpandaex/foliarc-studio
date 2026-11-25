from __future__ import annotations

import json
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from foliarc_agent.agent.genre_profiles import GenreProfileRegistry
from foliarc_agent.agent.guardrails import GuardrailEngine
from foliarc_agent.agent.router import IntentAndModelRouter
from foliarc_agent.agent.skills import SkillRegistry
from foliarc_agent.config import Settings
from foliarc_agent.rag.embeddings import EmbeddingProvider, RerankProvider
from foliarc_agent.rag.retriever import HybridRagRetriever
from foliarc_agent.rag.ripple_engine import RippleEngine
from foliarc_agent.rag.vector_store import (
    InMemoryVectorStore,
    VectorChunkRow,
    derive_narrative_order,
)


def _find_golden_dataset_path() -> Path:
    for parent in Path(__file__).resolve().parents[:6]:
        candidate = parent / "evals" / "golden_dataset.json"
        if candidate.is_file():
            return candidate
    return Path.cwd() / "evals" / "golden_dataset.json"


@dataclass(slots=True)
class EvalCaseResult:
    case_id: str
    category: str
    passed: bool
    score: float
    detail: str
    latency_ms: int


@dataclass(slots=True)
class EvalReport:
    total_cases: int
    passed_cases: int
    overall_pass_rate: float
    category_scores: dict[str, float]
    duration_ms: int
    cases: list[EvalCaseResult] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "total_cases": self.total_cases,
            "passed_cases": self.passed_cases,
            "overall_pass_rate": round(self.overall_pass_rate, 4),
            "category_scores": {k: round(v, 4) for k, v in self.category_scores.items()},
            "duration_ms": self.duration_ms,
            "cases": [asdict(c) for c in self.cases],
        }


class NovelEvalHarness:
    """
    Phase 6 Automated Golden Evaluation Harness (30+ novel domain cases):
    Evaluates Genre-Aware Skill Routing, Hybrid RAG Recall@5 & Anti-Spoiler/Multi-Scope isolation,
    Genre Anti-Cliche Rubric Coverage, Ripple Fact-Diff precision, and Input/Output Security Guardrails.
    """

    def __init__(self, settings: Settings | None = None, dataset_path: Path | None = None) -> None:
        self._settings = settings or Settings(llm_api_key="", embedding_api_key="")
        self._dataset_path = dataset_path or _find_golden_dataset_path()
        self._skills = SkillRegistry()
        self._router = IntentAndModelRouter(settings=self._settings, skill_registry=self._skills)
        self._guardrails = GuardrailEngine(self._settings)
        self._ripple = RippleEngine(self._settings)

    def load_dataset(self) -> list[dict[str, Any]]:
        if not self._dataset_path.is_file():
            return []
        raw = json.loads(self._dataset_path.read_text(encoding="utf-8"))
        return list(raw.get("cases") or [])

    async def run_all(self) -> EvalReport:
        t0 = time.perf_counter()
        cases = self.load_dataset()
        results: list[EvalCaseResult] = []
        for item in cases:
            cid = str(item["id"])
            cat = str(item["category"])
            c_start = time.perf_counter()
            if cat == "GENRE_ROUTING":
                passed, score, detail = await self._eval_genre_routing(item)
            elif cat == "RAG_ANTI_SPOILER":
                passed, score, detail = await self._eval_rag_case(item)
            elif cat == "GENRE_RUBRIC":
                passed, score, detail = self._eval_genre_rubric(item)
            elif cat == "RIPPLE_FACT_DIFF":
                passed, score, detail = await self._eval_ripple_case(item)
            elif cat == "GUARDRAILS":
                passed, score, detail = self._eval_guardrail_case(item)
            else:
                passed, score, detail = False, 0.0, f"Unknown category: {cat}"
            elapsed = int((time.perf_counter() - c_start) * 1000)
            results.append(
                EvalCaseResult(
                    case_id=cid,
                    category=cat,
                    passed=passed,
                    score=score,
                    detail=detail,
                    latency_ms=elapsed,
                )
            )

        by_cat: dict[str, list[float]] = {}
        for r in results:
            by_cat.setdefault(r.category, []).append(r.score)
        cat_scores = {k: sum(v) / len(v) for k, v in by_cat.items()}
        passed_cnt = sum(1 for r in results if r.passed)
        total = len(results)
        return EvalReport(
            total_cases=total,
            passed_cases=passed_cnt,
            overall_pass_rate=(passed_cnt / total) if total else 0.0,
            category_scores=cat_scores,
            duration_ms=int((time.perf_counter() - t0) * 1000),
            cases=results,
        )

    async def _eval_genre_routing(self, case: dict[str, Any]) -> tuple[bool, float, str]:
        decision = await self._router.route(
            action=str(case.get("action") or "CONTINUE"),
            instruction=str(case["instruction"]),
            genre_code=case.get("genre_code"),
            sub_genre_tags=case.get("sub_genre_tags"),
        )
        expected = case.get("expected_skill")
        forbidden = set(case.get("forbidden_skills") or [])
        actual = decision.recommended_skill
        if actual in forbidden:
            return False, 0.0, f"Triggered forbidden skill `{actual}`"
        if expected is not None and actual != expected:
            return False, 0.0, f"Expected `{expected}`, got `{actual}` (intent={decision.intent})"
        return True, 1.0, f"Matched skill={actual} via {decision.layer}"

    async def _eval_rag_case(self, case: dict[str, Any]) -> tuple[bool, float, str]:
        store = InMemoryVectorStore()
        embedder = EmbeddingProvider(self._settings)
        reranker = RerankProvider(self._settings)
        retriever = HybridRagRetriever(
            settings=self._settings,
            vector_store=store,
            embedder=embedder,
            reranker=reranker,
        )

        docs = list(case.get("chunks") or [])
        texts = [str(d["content"]) for d in docs]
        vecs = await embedder.embed_texts(texts) if texts else []
        rows = [
            VectorChunkRow(
                chunk_id=str(d["id"]),
                book_id=str(d.get("book_id") or "book-eval"),
                owner_id=str(d.get("owner_id") or "owner-eval"),
                document_id=str(d.get("document_id") or f"doc-{i}"),
                doc_version=1,
                parent_chunk_id=str(d["id"]),
                chapter_no=int(d.get("chapter_no") or 1),
                doc_type=str(d.get("doc_type") or "CHAPTER"),
                scene_title=str(d.get("title") or ""),
                content=str(d["content"]),
                dense_vec=vecs[i],
                scope=str(d.get("scope") or "BOOK"),
                narrative_order=int(d.get("narrative_order") or derive_narrative_order(int(d.get("chapter_no") or 1))),
                section_id=str(d.get("section_id") or ""),
            )
            for i, d in enumerate(docs)
        ]
        await store.upsert_chunks(rows)

        res = await retriever.search(
            owner_id=str(case.get("owner_id") or "owner-eval"),
            book_id=str(case.get("book_id") or "book-eval"),
            query=str(case["query"]),
            top_k=5,
            max_chapter_no=case.get("max_chapter_no"),
            max_narrative_order=case.get("max_narrative_order"),
            user_doc_ids=case.get("user_doc_ids"),
            global_doc_ids=case.get("global_doc_ids"),
        )
        hit_ids = [str(h["parent_chunk_id"]) for h in res.get("hits", [])]
        must_hit = set(case.get("must_include") or [])
        must_exclude = set(case.get("must_exclude") or [])

        leaked = must_exclude.intersection(hit_ids)
        missing = must_hit.difference(hit_ids)
        if leaked:
            return False, 0.0, f"Spoiler/tenant leak detected: {sorted(leaked)}"
        if missing:
            return False, 0.0, f"Recall miss: expected {sorted(missing)}, got {hit_ids}"
        return True, 1.0, f"Recall@5 hit={hit_ids}, leaks=0"

    def _eval_genre_rubric(self, case: dict[str, Any]) -> tuple[bool, float, str]:
        profile = GenreProfileRegistry.resolve(case.get("genre_code"), case.get("legacy_genre"))
        writer_block = profile.format_writer_guard_block()
        critic_block = profile.format_critic_rubric_block()
        combined = f"{writer_block}\n{critic_block}"
        required_terms = list(case.get("required_terms") or [])
        missing = [t for t in required_terms if t not in combined]
        if missing:
            return False, 0.0, f"Profile {profile.code} missing required rubric terms: {missing}"
        return True, 1.0, f"Profile {profile.code} rubric verified ({len(required_terms)} terms)"

    async def _eval_ripple_case(self, case: dict[str, Any]) -> tuple[bool, float, str]:
        res = await self._ripple.extract_fact_deltas(
            old_content=str(case["old_content"]),
            new_content=str(case["new_content"]),
            change_note=str(case.get("change_note") or ""),
        )
        expected_change = bool(case["expect_substantive_change"])
        actual_change = bool(res["hasSubstantiveFactChange"])
        if actual_change != expected_change:
            return False, 0.0, f"Expected substantive={expected_change}, got {actual_change}"
        expected_entity = case.get("expected_entity")
        if expected_entity:
            deltas = res.get("factDeltas") or []
            matched = any(
                expected_entity in str(d.get("entity") or "")
                or any(expected_entity in str(k) for k in (d.get("searchKeywords") or []))
                for d in deltas
            )
            if not matched:
                return False, 0.0, f"Expected entity `{expected_entity}` not found in {deltas}"
        return True, 1.0, f"FactDiff substantive={actual_change}, deltas={len(res.get('factDeltas') or [])}"

    def _eval_guardrail_case(self, case: dict[str, Any]) -> tuple[bool, float, str]:
        direction = str(case.get("direction") or "INPUT").upper()
        expect_allowed = bool(case["expect_allowed"])
        if direction == "INPUT":
            verdict = self._guardrails.check_input(str(case["text"]), "")
        else:
            verdict = self._guardrails.check_output(str(case["text"]))
        if verdict.allowed != expect_allowed:
            return False, 0.0, f"Expected allowed={expect_allowed}, got {verdict.allowed} ({verdict.rule_code})"
        return True, 1.0, f"Guardrail {direction} allowed={verdict.allowed} rule={verdict.rule_code}"
