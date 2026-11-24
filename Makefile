# FoliarcStudio — Monorepo Task Runner
# Pins JDK 21 for biz-java without modifying system default JDK 8.

SHELL := /usr/bin/env bash
JAVA21_HOME ?= /usr/lib/jvm/temurin-21
COMPOSE_FILE := infra/docker-compose.yml
ENV_FILE := .env

.PHONY: help env infra-core-up infra-core-down infra-rag-up infra-rag-down infra-ps \
        app-up app-down app-logs full-up \
        dev-py dev-java dev-web test-py test-java test-web test-all

help:
	@echo "FoliarcStudio — Available Make Targets"
	@echo "  make env             Copy .env.example to .env if missing"
	@echo "  make infra-core-up   Start PostgreSQL 16 + Redis 7 (profile: core)"
	@echo "  make infra-core-down Stop core containers"
	@echo "  make infra-rag-up    Start MinIO + etcd + Milvus Standalone + RabbitMQ (profile: rag)"
	@echo "  make infra-rag-down  Stop RAG containers"
	@echo "  make infra-ps        Show running containers"
	@echo "  make app-up          Build & start containerized stack (core + agent-py + biz-java + web)"
	@echo "  make full-up         Build & start ALL containers including RAG + RabbitMQ (profile: all)"
	@echo "  make app-down        Stop containerized app + core stack"
	@echo "  make app-logs        Follow logs for containerized web, biz-java, agent-py"
	@echo "  make dev-py          Start Python FastAPI Agent Engine (:8000)"
	@echo "  make dev-java        Start Java 21 Spring Boot Biz/Gateway (:8080)"
	@echo "  make dev-web         Start Next.js Frontend (:3000)"
	@echo "  make test-py         Run Python pytest + ruff"
	@echo "  make test-java       Run Java Maven tests (pinned to JDK 21)"
	@echo "  make test-web        Run Next.js lint + typecheck"
	@echo "  make test-all        Run all service checks"

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

full-up: env
	docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) --profile all up -d --build

app-down:
	docker compose -f $(COMPOSE_FILE) --profile app down

app-logs:
	docker compose -f $(COMPOSE_FILE) --profile app logs -f agent-py biz-java web

dev-py: env
	uv --directory services/agent-py run uvicorn foliarc_agent.main:app --reload --host 0.0.0.0 --port 8000

dev-java: env
	@set -a && source $(ENV_FILE) && set +a && \
	export JAVA_HOME=$(JAVA21_HOME) && \
	export PATH="$$JAVA_HOME/bin:$$PATH" && \
	unset CLASS_PATH && \
	cd services/biz-java && ./mvnw -s .mvn/settings.xml spring-boot:run

dev-web:
	cd apps/web && pnpm dev

test-py:
	uv --directory services/agent-py run pytest
	uv --directory services/agent-py run ruff check .

test-java:
	@export JAVA_HOME=$(JAVA21_HOME) && \
	export PATH="$$JAVA_HOME/bin:$$PATH" && \
	unset CLASS_PATH && \
	cd services/biz-java && ./mvnw -s .mvn/settings.xml test

test-web:
	cd apps/web && pnpm lint && pnpm exec tsc --noEmit

test-all: test-py test-java test-web
