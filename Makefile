# POS platform - developer entrypoint. Run `make` for the target list.
SHELL := /bin/bash

COMPOSE_FILE := infra/compose/docker-compose.yml
ENV_FILE     := .env
SERVICES_FILE := infra/compose/docker-compose.services.yml
DC           := docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE)
DC_ALL       := docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) -f $(SERVICES_FILE)
MVN          := ./mvnw
BACKEND      := backend

INFRA_SERVICES := postgres kafka redis mailpit

.DEFAULT_GOAL := help
.PHONY: help env doctor infra-up infra-down infra-restart infra-logs topics ps logs \
        up down images service-logs \
        build fmt test it verify psql redis-cli kafka-topics clean nuke check-env

## ---------------------------------------------------------------------------
## Help
## ---------------------------------------------------------------------------
help: ## Show this help
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z_-]+:.*?##/ {printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2} /^## [^-]/ {printf "\n\033[1m%s\033[0m\n", substr($$0,4)}' $(MAKEFILE_LIST)
	@echo

check-env:
	@test -f $(ENV_FILE) || { echo "ERROR: $(ENV_FILE) not found. Run 'make env' first."; exit 1; }

## ---------------------------------------------------------------------------
## Environment
## ---------------------------------------------------------------------------
env: ## Create .env from .env.example with generated secrets (never overwrites)
	@if [ -f $(ENV_FILE) ]; then echo "$(ENV_FILE) already exists - leaving it alone."; exit 0; fi; \
	cp .env.example $(ENV_FILE); \
	for key in $$(grep -oE '^[A-Z_]+PASSWORD=$$' .env.example | tr -d '=' | grep -vx 'SMTP_PASSWORD'); do \
		secret=$$(openssl rand -hex 24); \
		sed -i "s|^$${key}=$$|$${key}=$${secret}|" $(ENV_FILE); \
	done; \
	echo "Generated $(ENV_FILE) with random secrets."; \
	echo "Fill in SMTP and M-Pesa values when you reach those phases."

doctor: ## Check that required tooling is present
	@echo "java    : $$(java -version 2>&1 | head -1)"
	@echo "node    : $$(node -v 2>/dev/null || echo MISSING)"
	@echo "docker  : $$(docker --version 2>/dev/null || echo MISSING)"
	@echo "compose : $$(docker compose version 2>/dev/null || echo MISSING)"
	@echo "mvnw    : $$(cd $(BACKEND) && ./mvnw -v 2>/dev/null | head -1 || echo MISSING)"
	@docker info >/dev/null 2>&1 && echo "daemon  : reachable" || echo "daemon  : NOT REACHABLE"

## ---------------------------------------------------------------------------
## Infrastructure
## ---------------------------------------------------------------------------
infra-up: check-env ## Start Postgres, Kafka, Redis, Mailpit and create topics
	$(DC) up -d --wait $(INFRA_SERVICES)
	$(DC) up kafka-init
	@echo
	@$(MAKE) --no-print-directory ps
	@echo "  Postgres  localhost:5432   Kafka  localhost:29092   Redis  localhost:6379"
	@echo "  Mailpit   http://localhost:8025"

infra-down: check-env ## Stop infrastructure (data volumes are kept)
	$(DC) down --remove-orphans

infra-restart: infra-down infra-up ## Restart infrastructure

infra-logs: check-env ## Tail infrastructure logs
	$(DC) logs -f $(INFRA_SERVICES)

topics: check-env ## (Re)create Kafka topics - idempotent
	$(DC) run --rm kafka-init

ps: check-env ## Show container status
	@$(DC) ps --format 'table {{.Name}}\t{{.Service}}\t{{.Status}}'

logs: check-env ## Tail one service: make logs svc=postgres
	@test -n "$(svc)" || { echo "Usage: make logs svc=<service>"; exit 1; }
	$(DC) logs -f $(svc)

## ---------------------------------------------------------------------------
## Application services
## ---------------------------------------------------------------------------
up: infra-up ## Start infrastructure and all services (gateway on :8080)
	@# Services come up after the topics exist: broker auto-creation is off, so a producer
	@# starting first would fail on its first publish rather than waiting.
	$(DC_ALL) up -d --build --wait auth-service catalog-service notification-service api-gateway
	@echo
	@$(DC_ALL) ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}'
	@echo
	@echo "  Gateway (the only ingress)  http://localhost:8080"
	@echo "  Mailpit                     http://localhost:8025"

down: check-env ## Stop infrastructure (data volumes are kept)
	$(DC) down --remove-orphans

infra-restart: infra-down infra-up ## Restart infrastructure

infra-logs: check-env ## Tail infrastructure logs
	$(DC) logs -f $(INFRA_SERVICES)

topics: check-env ## (Re)create Kafka topics - idempotent
	$(DC) run --rm kafka-init

ps: check-env ## Show container status
	@$(DC) ps --format 'table {{.Name}}\t{{.Service}}\t{{.Status}}'

logs: check-env ## Tail one service: make logs svc=postgres
	@test -n "$(svc)" || { echo "Usage: make logs svc=<service>"; exit 1; }
	$(DC) logs -f $(svc)

## ---------------------------------------------------------------------------
## Application services
## ---------------------------------------------------------------------------
up: check-env ## Start infrastructure and all services (gateway on :8080)
	$(DC_ALL) up -d --build --wait
	@echo
	@$(DC_ALL) ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}'
	@echo
	@echo "  Gateway (the only ingress)  http://localhost:8080"
	@echo "  Mailpit                     http://localhost:8025"

down: check-env ## Stop services and infrastructure (volumes kept)
	$(DC_ALL) down --remove-orphans

images: check-env ## Rebuild the service images
	$(DC_ALL) build

service-logs: check-env ## Tail application service logs
	$(DC_ALL) logs -f auth-service catalog-service notification-service api-gateway

## ---------------------------------------------------------------------------
## Build and test
## ---------------------------------------------------------------------------
build: ## Build all backend modules
	cd $(BACKEND) && $(MVN) -B -T1C clean install

fmt: ## Apply code formatting
	cd $(BACKEND) && $(MVN) -B spotless:apply

test: ## Run unit tests
	cd $(BACKEND) && $(MVN) -B test

it: ## Run integration tests (Testcontainers - needs Docker)
	cd $(BACKEND) && $(MVN) -B verify -Pintegration

verify: ## Full gate: format check, build, unit + integration tests, coverage
	cd $(BACKEND) && $(MVN) -B clean verify -Pintegration

## ---------------------------------------------------------------------------
## Shells and inspection
## ---------------------------------------------------------------------------
psql: check-env ## Open a psql shell as the superuser
	@$(DC) exec postgres psql -U "$$(grep '^POSTGRES_SUPERUSER=' $(ENV_FILE) | cut -d= -f2)" -d "$$(grep '^POSTGRES_DB=' $(ENV_FILE) | cut -d= -f2)"

redis-cli: check-env ## Open a redis-cli shell
	@$(DC) exec redis sh -c 'redis-cli -a "$$REDIS_PASSWORD"'

kafka-topics: check-env ## List Kafka topics
	@$(DC) exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list | sort

## ---------------------------------------------------------------------------
## Cleanup
## ---------------------------------------------------------------------------
clean: ## Remove build output
	cd $(BACKEND) && $(MVN) -B clean

nuke: check-env ## Stop everything and DELETE ALL DATA VOLUMES
	@read -p "This deletes all Postgres, Kafka and Redis data. Type 'yes' to continue: " ok; \
	[ "$$ok" = "yes" ] || { echo "Aborted."; exit 1; }
	$(DC) down -v --remove-orphans
