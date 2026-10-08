# JSON Gateway - developer commands. Run `make help` for the list; see docs/MAKEFILE.md for details.

SHELL := /bin/bash

# JDK 21: use JAVA_HOME if set, otherwise macOS java_home, otherwise Homebrew's openjdk@21
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21)
export JAVA_HOME

MVN      := ./mvnw -B
COMPOSE  := docker compose

APP_PORT      ?= 8080
DB_PORT       ?= 5432
DB_NAME       ?= gateway
DB_USERNAME   ?= gateway
DB_PASSWORD   ?= gateway
WIREMOCK_PORT ?= 8089
ADMIN_TOKEN   ?= dev-admin-token
# Downstream systems; default to the WireMock stubs. Point them at real systems with e.g.
#   make run CORE_BANKING_URL=http://10.20.30.40:9080
CORE_BANKING_URL ?= http://localhost:$(WIREMOCK_PORT)
NOTIFICATION_URL ?= http://localhost:$(WIREMOCK_PORT)
export DB_PORT DB_NAME DB_USERNAME DB_PASSWORD WIREMOCK_PORT

BASE_URL := http://localhost:$(APP_PORT)

.DEFAULT_GOAL := help

##@ Help

.PHONY: help
help: ## Show this help
	@awk 'BEGIN {FS = ":.*##"; printf "\nUsage: make \033[36m<target>\033[0m [VAR=value]\n"} \
		/^[a-zA-Z0-9_-]+:.*?##/ { printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2 } \
		/^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5) }' $(MAKEFILE_LIST)
	@echo

##@ Infrastructure (docker compose)

.PHONY: up
up: ## Start PostgreSQL and WireMock, wait until the DB is healthy
	$(COMPOSE) up -d --wait

.PHONY: down
down: ## Stop containers (keeps the database volume)
	$(COMPOSE) down

.PHONY: db-reset
db-reset: ## Stop containers AND delete the database volume (all data lost)
	$(COMPOSE) down -v

.PHONY: db-truncate
db-truncate: ## Delete ALL rows (config, target systems, audit) in the compose DB; asks first (CONFIRM=yes skips)
	@if [ "$(CONFIRM)" != "yes" ]; then \
		read -r -p "Delete ALL flows, steps, rules, lookups, schemas, target systems and audit rows in '$(DB_NAME)'? Type 'yes': " answer; \
		[ "$$answer" = "yes" ] || { echo "Aborted, nothing deleted."; exit 1; }; \
	fi
	$(COMPOSE) exec -T postgres psql -v ON_ERROR_STOP=1 -U $(DB_USERNAME) -d $(DB_NAME) < scripts/truncate-all.postgres.sql
	@echo "Done. If the app is running, run 'make reload' so it drops the old flows from memory."

.PHONY: ps
ps: ## Show container status
	$(COMPOSE) ps

.PHONY: logs
logs: ## Follow container logs
	$(COMPOSE) logs -f

.PHONY: psql
psql: ## Open a psql shell in the database
	$(COMPOSE) exec postgres psql -U $(DB_USERNAME) -d $(DB_NAME)

##@ Application

.PHONY: run
run: up ## Run the app (profile dev) against the docker compose DB + WireMock
	DB_URL=jdbc:postgresql://localhost:$(DB_PORT)/$(DB_NAME) \
	CORE_BANKING_URL=$(CORE_BANKING_URL) \
	NOTIFICATION_URL=$(NOTIFICATION_URL) \
	GATEWAY_ADMIN_TOKEN=$(ADMIN_TOKEN) \
	$(MVN) spring-boot:run -Dspring-boot.run.profiles=dev -Dspring-boot.run.arguments=--server.port=$(APP_PORT)

.PHONY: run-test
run-test: ## Run the app with throwaway Testcontainers DB + in-process WireMock (no compose needed)
	$(MVN) spring-boot:test-run -Dspring-boot.run.arguments=--server.port=$(APP_PORT)

.PHONY: build
build: ## Build the executable jar (skips tests) into target/
	$(MVN) package -DskipTests

.PHONY: clean
clean: ## Remove build output
	$(MVN) clean
	rm -f databasechangelog.csv

##@ Tests

.PHONY: test
test: ## Run all tests (unit + integration on PostgreSQL via Testcontainers)
	$(MVN) test

.PHONY: test-unit
test-unit: ## Run unit tests only (no Docker needed)
	$(MVN) test -Dtest='!*IntegrationTest,!StartupValidationTest'

.PHONY: test-it
test-it: ## Run integration tests only (PostgreSQL via Testcontainers)
	$(MVN) test -Dtest='*IntegrationTest'

.PHONY: test-oracle
test-oracle: ## Run integration tests against Oracle Free (large image, slow first run)
	$(MVN) test -Poracle-it -Dtest='*IntegrationTest'

##@ Database DDL (for DBA-managed schemas)

# Database for ddl-pending; defaults to the docker compose DB
DDL_DB_URL ?= jdbc:postgresql://localhost:$(DB_PORT)/$(DB_NAME)

.PHONY: ddl-pending
ddl-pending: ## SQL for the changes not yet applied to a DB (default: compose DB) into target/liquibase/update.sql
	rm -f databasechangelog.csv
	$(MVN) -q liquibase:updateSQL -Dliquibase.url=$(DDL_DB_URL) \
		-Dliquibase.username=$(DB_USERNAME) -Dliquibase.password=$(DB_PASSWORD) $(DDL_ARGS)
	@echo "Pending DDL written to target/liquibase/update.sql (nothing was executed)"

.PHONY: ddl-postgres
ddl-postgres: ## Generate PostgreSQL DDL offline into target/liquibase/update.sql
	rm -f databasechangelog.csv
	$(MVN) -q liquibase:updateSQL -Dliquibase.url=offline:postgresql $(DDL_ARGS)
	rm -f databasechangelog.csv
	@echo "DDL written to target/liquibase/update.sql"

.PHONY: ddl-oracle
ddl-oracle: ## Generate Oracle DDL offline into target/liquibase/update.sql
	rm -f databasechangelog.csv
	$(MVN) -q liquibase:updateSQL -Dliquibase.url=offline:oracle $(DDL_ARGS)
	rm -f databasechangelog.csv
	@echo "DDL written to target/liquibase/update.sql"

##@ Demo calls (app must be running)

.PHONY: reload
reload: ## Reload configuration from the database
	@curl -s -X POST $(BASE_URL)/admin/config/reload -H 'X-Admin-Token: $(ADMIN_TOKEN)'; echo

.PHONY: health
health: ## Show application health
	@curl -s $(BASE_URL)/actuator/health; echo

.PHONY: demo
demo: ## Call every demo flow and print the responses
	@echo "--- account inquiry";            curl -s $(BASE_URL)/api/v1/accounts/1001; echo
	@echo "--- transaction history";        curl -s "$(BASE_URL)/api/v1/accounts/1001/transactions?limit=5"; echo
	@echo "--- transfer";                   curl -s -X POST $(BASE_URL)/api/v1/transfers -H 'Content-Type: application/json' \
		-d '{"fromAccount":"1001","toAccount":"2002","amount":1000}'; echo
	@echo "--- transfer, insufficient funds"; curl -s -X POST $(BASE_URL)/api/v1/transfers -H 'Content-Type: application/json' \
		-d '{"fromAccount":"1001","toAccount":"2002","amount":999999999}'; echo
	@echo "--- transfer, blocked beneficiary"; curl -s -X POST $(BASE_URL)/api/v1/transfers -H 'Content-Type: application/json' \
		-d '{"fromAccount":"1001","toAccount":"3003","amount":1000}'; echo
	@echo "--- unknown account";            curl -s $(BASE_URL)/api/v1/accounts/9999; echo
	@echo "--- invalid request";            curl -s -X POST $(BASE_URL)/api/v1/transfers -H 'Content-Type: application/json' \
		-d '{"fromAccount":"1001"}'; echo

.PHONY: audit
audit: ## Show the 10 most recent audit transactions
	@$(COMPOSE) exec postgres psql -U $(DB_USERNAME) -d $(DB_NAME) -c \
		"SELECT correlation_id, flow_code, http_method, path, client_status, error_code, duration_ms, started_at \
		 FROM gw_audit_transaction ORDER BY id DESC LIMIT 10;"
