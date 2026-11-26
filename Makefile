# FoliarcStudio — Monorepo Task Runner
# Pins JDK 21 for services/java (foliarc-common, biz-java, admin-java) without modifying system default JDK 8.

SHELL := /usr/bin/env bash
JAVA21_HOME ?= /usr/lib/jvm/temurin-21
COMPOSE_FILE := infra/docker-compose.yml
ENV_FILE := .env

.PHONY: help env infra-core-up infra-core-down infra-rag-up infra-rag-down infra-ps \
        app-up app-down app-logs gateway-logs admin-up admin-down full-up smoke \
        dev-py dev-java dev-admin-java dev-web dev-admin-web \
        test-py test-java test-web test-admin test-all

help:
	@echo "FoliarcStudio — Available Make Targets"
	@echo "  make env             Copy .env.example to .env if missing"
	@echo "  make infra-core-up   Start PostgreSQL 16 + Redis 7 (profile: core)"
	@echo "  make infra-core-down Stop core containers"
	@echo "  make infra-rag-up    Start MinIO + etcd + Milvus Standalone + RabbitMQ (profile: rag)"
	@echo "  make infra-rag-down  Stop RAG containers"
	@echo "  make infra-ps        Show running containers"
	@echo "  make app-up          Build & start author stack (core + agent-py + biz-java + web)"
	@echo "  make admin-up        Build & start independent admin stack (admin-java :8081 + admin :3001)"
	@echo "  make full-up         Build & start ALL containers including RAG + Admin (profile: all)"
	@echo "  make app-down        Stop containerized app + core stack"
	@echo "  make admin-down      Stop containerized admin stack"
	@echo "  make app-logs        Follow logs for gateway, web, biz-java, agent-py, admin"
	@echo "  make gateway-logs    Follow Nginx gateway JSON access logs"
	@echo "  make dev-py          Start Python FastAPI Agent Engine (:8000)"
	@echo "  make dev-java        Start Java 21 Author Biz Gateway (:8080)"
	@echo "  make dev-admin-java  Start Java 21 Independent Admin API (:8081)"
	@echo "  make dev-web         Start Next.js Author Studio (:3000)"
	@echo "  make dev-admin-web   Start Next.js Admin Console (:3001)"
	@echo "  make test-py         Run Python pytest + ruff (incl. 30-case Golden Eval)"
	@echo "  make test-java       Run Java multi-module Maven tests (foliarc-common, biz-java, admin-java)"
	@echo "  make test-web        Run Author Web Next.js lint + typecheck"
	@echo "  make test-admin      Run Admin Console Next.js lint + typecheck"
	@echo "  make test-all        Run all 4-stack verification checks"

env:
	@if [ ! -f $(ENV_FILE) ]; then cp .env.example $(ENV_FILE) && echo "Created $(ENV_FILE) from .env.example"; else echo "$(ENV_FILE) already exists"; fi

infra-core-up: env
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) --profile core up -d

infra-core-down:
	docker compose -f $(COMPOSE_FILE) --profile core down

infra-rag-up: env
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) --profile rag up -d

infra-rag-down:
	docker compose -f $(COMPOSE_FILE) --profile rag down

infra-ps:
	docker compose -f $(COMPOSE_FILE) --profile all ps

app-up: env
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) --profile app up -d --build

admin-up: env
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) --profile core --profile admin up -d --build

full-up: env
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) --profile all up -d --build

app-down:
	docker compose -f $(COMPOSE_FILE) --profile app down

admin-down:
	docker compose -f $(COMPOSE_FILE) --profile admin down

app-logs:
	docker compose -f $(COMPOSE_FILE) --profile all logs -f gateway agent-py biz-java admin-java web admin

gateway-logs:
	docker compose -f $(COMPOSE_FILE) --profile all logs -f gateway

dev-py: env
	uv --directory services/agent-py run uvicorn foliarc_agent.main:app --reload --host 0.0.0.0 --port 8000

dev-java: env
	@set -a && source $(ENV_FILE) && set +a && \
	export JAVA_HOME=$(JAVA21_HOME) && \
	export PATH="$$JAVA_HOME/bin:$$PATH" && \
	unset CLASS_PATH && \
	cd services/java && ./mvnw -s .mvn/settings.xml -pl biz-java -am spring-boot:run

dev-admin-java: env
	@set -a && source $(ENV_FILE) && set +a && \
	export JAVA_HOME=$(JAVA21_HOME) && \
	export PATH="$$JAVA_HOME/bin:$$PATH" && \
	unset CLASS_PATH && \
	cd services/java && ./mvnw -s .mvn/settings.xml -pl admin-java -am spring-boot:run

dev-web:
	cd apps/web && pnpm dev

dev-admin-web:
	cd apps/admin && pnpm dev

test-py:
	uv --directory services/agent-py run pytest
	uv --directory services/agent-py run ruff check .

test-java:
	@export JAVA_HOME=$(JAVA21_HOME) && \
	export PATH="$$JAVA_HOME/bin:$$PATH" && \
	unset CLASS_PATH && \
	cd services/java && ./mvnw -s .mvn/settings.xml test

test-web:
	cd apps/web && pnpm lint && pnpm exec tsc --noEmit

test-admin:
	cd apps/admin && pnpm lint && pnpm exec tsc --noEmit

smoke:
	python3 scripts/e2e_smoke.py

test-all: test-py test-java test-web test-admin
