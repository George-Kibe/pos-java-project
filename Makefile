# POS platform - developer entrypoint. Run `make` for the target list.
SHELL := /bin/bash

COMPOSE_FILE := infra/compose/docker-compose.yml
ENV_FILE     := .env
SERVICES_FILE := infra/compose/docker-compose.services.yml
PROD_FILE    := infra/compose/docker-compose.prod.yml
DC           := docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE)
DC_ALL       := docker compose --env-file $(ENV_FILE) -f $(COMPOSE_FILE) -f $(SERVICES_FILE)
DC_PROD      := $(DC_ALL) -f $(PROD_FILE)
# Secrets the project generates itself (random values), as opposed to credentials you supply.
GENERATED_SECRETS := [A-Z_]+PASSWORD|MPESA_CALLBACK_TOKEN|WEB_SESSION_SECRET
MVN          := ./mvnw
BACKEND      := backend

INFRA_SERVICES := postgres kafka redis

.DEFAULT_GOAL := help
.PHONY: help prod-up env env-sync doctor infra-up infra-down infra-restart infra-logs topics ps logs \
        up down images service-logs \
        build fmt test it verify web-check web-e2e admin admin-check demo-seed demo-clear postman api-smoke psql redis-cli kafka-topics kafka-topics-sync clean nuke \
		check-env

## ---------------------------------------------------------------------------
## Help
## ---------------------------------------------------------------------------
help: ## Show this help
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z0-9_-]+:.*?##/ {printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2} /^## [^-]/ {printf "\n\033[1m%s\033[0m\n", substr($$0,4)}' $(MAKEFILE_LIST)
	@echo

check-env:
	@test -f $(ENV_FILE) || { echo "ERROR: $(ENV_FILE) not found. Run 'make env' first."; exit 1; }

## ---------------------------------------------------------------------------
## Environment
## ---------------------------------------------------------------------------
env: ## Create .env from .env.example with generated secrets (never overwrites)
	@if [ -f $(ENV_FILE) ]; then echo "$(ENV_FILE) already exists - leaving it alone."; exit 0; fi; \
	cp .env.example $(ENV_FILE); \
	for key in $$(grep -oE "^($(GENERATED_SECRETS))=$$" .env.example | tr -d '=' | grep -vx 'SMTP_PASSWORD'); do \
		secret=$$(openssl rand -hex 24); \
		sed -i "s|^$${key}=$$|$${key}=$${secret}|" $(ENV_FILE); \
	done; \
	echo "Generated $(ENV_FILE) with random secrets."; \
	echo "Fill in SMTP and M-Pesa values when you reach those phases."

env-sync: check-env ## Add generated secrets that a newer .env.example introduced (never overwrites)
	@# `make env` never touches an existing .env, so a secret added in a later phase is missing
	@# from it. This fills in only keys that are absent or empty and that the project generates
	@# itself - never a provider credential such as SMTP or M-Pesa, which you supply. M-Pesa keys
	@# are left alone entirely: the callback token only means something alongside the public
	@# callback URL, and that is set up by hand.
	@for key in $$(grep -oE "^($(GENERATED_SECRETS))=$$" .env.example | tr -d '=' | grep -vx 'SMTP_PASSWORD' | grep -v '^MPESA_'); do \
		if grep -qE "^$${key}=.+" $(ENV_FILE); then continue; fi; \
		secret=$$(openssl rand -hex 24); \
		if grep -qE "^$${key}=$$" $(ENV_FILE); then \
			sed -i "s|^$${key}=$$|$${key}=$${secret}|" $(ENV_FILE); \
		else \
			[ -z "$$(tail -c 1 $(ENV_FILE))" ] || echo >> $(ENV_FILE); \
			printf '%s=%s\n' "$${key}" "$${secret}" >> $(ENV_FILE); \
		fi; \
		echo "Generated $${key}"; \
	done

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
infra-up: check-env ## Start Postgres, Kafka, Redis and create topics
	$(DC) up -d --wait $(INFRA_SERVICES)
	$(DC) up kafka-init
	@echo
	@$(MAKE) --no-print-directory ps
	@echo "  Postgres  localhost:5432   Kafka  localhost:29092   Redis  localhost:6379"

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
up: check-env ## Start infrastructure and all services (gateway on :8080)
	$(DC_ALL) up -d --build --wait
	@echo
	@$(DC_ALL) ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}'
	@echo
	@echo "  Web app                     http://localhost:3000"
	@echo "  Gateway (the only ingress)  http://localhost:8080"

down: check-env ## Stop services and infrastructure (volumes kept)
	$(DC_ALL) down --remove-orphans

images: check-env ## Rebuild the service images
	$(DC_ALL) build

service-logs: check-env ## Tail application service logs
	$(DC_ALL) logs -f auth-service catalog-service notification-service api-gateway

prod-up: check-env ## Production: everything behind Traefik (TLS, branch/HQ networks only)
	@# Refuses to start without POS_DOMAIN, ACME_EMAIL and ALLOWED_CLIENT_NETWORKS in .env.
	$(DC_PROD) up -d --build --wait
	@$(DC_PROD) ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}'

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

web-check: ## Web app: lint, typecheck, unit tests and a production build
	cd frontend/web && npm run lint && npm run typecheck && npm test && npm run build

web-e2e: check-env ## Web app: browser end-to-end run against the running stack (no mail is sent)
	@# The run registers throwaway accounts, so notification-service captures their emails to files
	@# in its container instead of sending them, and the gateway's per-address credential limit is
	@# raised for the run. Both services are put back as configured afterwards, pass or fail.
	$(DC_ALL) -f infra/compose/docker-compose.e2e.yml up -d --wait notification-service api-gateway
	@set -a; . ./$(ENV_FILE); set +a; \
		(cd frontend/web && npm run e2e); status=$$?; \
		$(DC_ALL) up -d --wait notification-service api-gateway; \
		exit $$status

## ---------------------------------------------------------------------------
## API collection
## ---------------------------------------------------------------------------
postman: ## Regenerate the Postman collection (postman/) from the running services' OpenAPI specs
	python3 scripts/postman/generate.py --base-url http://localhost:$${GATEWAY_PORT:-8080} --out postman

api-smoke: check-env ## Send every GET in the collection to the stack; fails on any 5xx (needs demo-seed)
	@set -a; . ./$(ENV_FILE); set +a; python3 scripts/postman/smoke.py

## ---------------------------------------------------------------------------
## Demo data
## ---------------------------------------------------------------------------
admin: check-env ## Create an administrator (every permission, every branch): make admin email=... name="..."
	@# Signs in as the bootstrap administrator from .env. The password is temporary (changed at first
	@# sign-in): from ADMIN_PASSWORD, asked for at a terminal, or generated and shown once. No email is sent.
	@test -n "$(email)" && test -n "$(name)" || { echo 'Usage: make admin email=jane@example.com name="Jane Wambui"'; exit 2; }
	@python3 scripts/admin/create_admin.py --email "$(email)" --name "$(name)"

admin-check: check-env ## Prove an administrator's rights across every service and branch: make admin-check email=...
	@test -n "$(email)" || { echo 'Usage: make admin-check email=jane@example.com'; exit 2; }
	@python3 scripts/admin/check_admin.py --email "$(email)"

demo-seed: check-env ## Seed demo data through the APIs (repeatable after demo-clear; no mail is sent)
	@# Seeding creates staff and customers, whose welcome emails must not go out through Gmail, and
	@# signs in dozens of times from one address: the same overlay as web-e2e covers both. Both
	@# services are put back as configured afterwards, pass or fail.
	$(DC_ALL) -f infra/compose/docker-compose.e2e.yml up -d --wait notification-service api-gateway
	@set -a; . ./$(ENV_FILE); set +a; \
		python3 scripts/demo/seed.py --env-out postman/local.postman_environment.json; status=$$?; \
		$(DC_ALL) up -d --wait notification-service api-gateway; \
		exit $$status

demo-clear: check-env ## Remove the demo data (DEMO codes, @demo.pos.local) and nothing else
	@set -a; . ./$(ENV_FILE); set +a; \
		$(DC) exec -T -e PGPASSWORD="$$POSTGRES_SUPERUSER_PASSWORD" postgres \
		psql -h localhost -U "$$POSTGRES_SUPERUSER" -d "$$POSTGRES_DB" -q < scripts/demo/clear.sql

## ---------------------------------------------------------------------------
## Shells and inspection
## ---------------------------------------------------------------------------
psql: check-env ## Open a psql shell as the superuser
	@$(DC) exec postgres psql -U "$$(grep '^POSTGRES_SUPERUSER=' $(ENV_FILE) | cut -d= -f2)" -d "$$(grep '^POSTGRES_DB=' $(ENV_FILE) | cut -d= -f2)"

redis-cli: check-env ## Open a redis-cli shell
	@$(DC) exec redis sh -c 'redis-cli -a "$$REDIS_PASSWORD"'

kafka-topics: check-env ## List Kafka topics
	@$(DC) exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list | sort

kafka-topics-sync: check-env ## Create any topics missing from the running broker
	@# kafka-init is a one-shot that already ran, so a topic added to the catalogue after the
	@# broker was first started does not exist yet. The script leaves existing topics untouched,
	@# so this is safe to run at any time - and has to be run when the catalogue grows, or the
	@# first event on the new topic sits in the outbox retrying against a topic that is not there.
	@$(DC) run --rm --no-deps kafka-init

## ---------------------------------------------------------------------------
## Cleanup
## ---------------------------------------------------------------------------
clean: ## Remove build output
	cd $(BACKEND) && $(MVN) -B clean

nuke: check-env ## Stop everything and DELETE ALL DATA VOLUMES
	@read -p "This deletes all Postgres, Kafka and Redis data. Type 'yes' to continue: " ok; \
	[ "$$ok" = "yes" ] || { echo "Aborted."; exit 1; }
	$(DC) down -v --remove-orphans
