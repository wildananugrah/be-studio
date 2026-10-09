# JSON Gateway - developer commands. Run `make help` for the list; see docs/MAKEFILE.md for details.

SHELL := /bin/bash

# JDK 21: use JAVA_HOME if set, otherwise macOS java_home, otherwise Homebrew's openjdk@21
JAVA_HOME ?= $(shell /usr/libexec/java_home -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21)
export JAVA_HOME

MVN      := ./mvnw -B
COMPOSE  := docker compose

# Database for run / up / db-* / sql / audit / ddl-pending / test: postgres (default) or oracle.
#   make run DB=oracle
DB ?= postgres

APP_PORT      ?= 8080
DB_PORT       ?= 5432
DB_NAME       ?= gateway
DB_USERNAME   ?= gateway
DB_PASSWORD   ?= gateway
WIREMOCK_PORT ?= 8089
# Oracle Free container (DB=oracle): listener port, pluggable database (service name), SYS/SYSTEM password.
# The application user is DB_USERNAME / DB_PASSWORD, created by the container on first start.
ORACLE_PORT         ?= 1521
ORACLE_SERVICE      ?= FREEPDB1
ORACLE_SYS_PASSWORD ?= oracle
ADMIN_TOKEN   ?= dev-admin-token
# Downstream systems; default to the WireMock stubs. Point them at real systems with e.g.
#   make run CORE_BANKING_URL=http://10.20.30.40:9080
CORE_BANKING_URL ?= http://localhost:$(WIREMOCK_PORT)
NOTIFICATION_URL ?= http://localhost:$(WIREMOCK_PORT)
export DB_PORT DB_NAME DB_USERNAME DB_PASSWORD WIREMOCK_PORT ORACLE_PORT ORACLE_SYS_PASSWORD

BASE_URL := http://localhost:$(APP_PORT)

# Compose including the optional oracle service, for commands that must see every container.
COMPOSE_ALL := $(COMPOSE) --profile oracle

ifeq ($(DB),oracle)
  DB_SERVICE      := oracle
  DB_JDBC_URL     := jdbc:oracle:thin:@//localhost:$(ORACLE_PORT)/$(ORACLE_SERVICE)
  SPRING_PROFILES := dev,oracle
  TEST_PROFILE    := -Poracle-it
  # SQL*Plus inside the container, connected as the application user
  SQLPLUS         := $(COMPOSE_ALL) exec -T oracle sqlplus -S -L $(DB_USERNAME)/$(DB_PASSWORD)@//localhost:1521/$(ORACLE_SERVICE)
else ifeq ($(DB),postgres)
  DB_SERVICE      := postgres
  DB_JDBC_URL     := jdbc:postgresql://localhost:$(DB_PORT)/$(DB_NAME)
  SPRING_PROFILES := dev
  TEST_PROFILE    :=
else
  $(error DB must be postgres or oracle, not '$(DB)')
endif

.DEFAULT_GOAL := help

##@ Help

.PHONY: help
help: ## Show this help
	@awk 'BEGIN {FS = ":.*##"; printf "\nUsage: make \033[36m<target>\033[0m [VAR=value]\n"} \
		/^[a-zA-Z0-9_-]+:.*?##/ { printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2 } \
		/^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5) }' $(MAKEFILE_LIST)
	@echo

##@ Infrastructure (docker compose; DB=postgres or DB=oracle)

.PHONY: up
up: ## Start the database (DB=postgres|oracle) and WireMock, wait until healthy
	@if [ "$(DB)" = "oracle" ]; then echo "Starting Oracle Free (first start creates the database: 1-3 minutes)..."; fi
	$(COMPOSE_ALL) up -d --wait $(DB_SERVICE) wiremock

.PHONY: up-oracle
up-oracle: ## Same as 'make up DB=oracle'
	@$(MAKE) --no-print-directory up DB=oracle

.PHONY: down
down: ## Stop all containers, PostgreSQL and Oracle (keeps the database volumes)
	$(COMPOSE_ALL) down

.PHONY: db-reset
db-reset: ## Stop the DB (DB=postgres|oracle) AND delete its volume (all data lost); asks first (CONFIRM=yes skips)
	@if [ "$(CONFIRM)" != "yes" ]; then \
		read -r -p "Delete the whole $(DB) database volume (all data)? Type 'yes': " answer; \
		[ "$$answer" = "yes" ] || { echo "Aborted, nothing deleted."; exit 1; }; \
	fi
	$(COMPOSE_ALL) rm -s -f $(DB_SERVICE)
	docker volume rm -f json-gateway_$(DB_SERVICE)-data
	@echo "Done. 'make run DB=$(DB)' recreates the database with the demo data."

.PHONY: db-truncate
db-truncate: ## Delete ALL rows (config, target systems, audit) in the compose DB (DB=postgres|oracle); asks first (CONFIRM=yes skips)
	@if [ "$(CONFIRM)" != "yes" ]; then \
		read -r -p "Delete ALL flows, steps, rules, lookups, schemas, target systems and audit rows in the $(DB) database? Type 'yes': " answer; \
		[ "$$answer" = "yes" ] || { echo "Aborted, nothing deleted."; exit 1; }; \
	fi
ifeq ($(DB),oracle)
	{ cat scripts/truncate-all.oracle.sql; echo "EXIT"; } | $(SQLPLUS)
else
	$(COMPOSE) exec -T postgres psql -v ON_ERROR_STOP=1 -U $(DB_USERNAME) -d $(DB_NAME) < scripts/truncate-all.postgres.sql
endif
	@echo "Done. If the app is running, run 'make reload' so it drops the old flows from memory."

.PHONY: ps
ps: ## Show container status
	$(COMPOSE_ALL) ps

.PHONY: logs
logs: ## Follow container logs
	$(COMPOSE_ALL) logs -f

.PHONY: sql
sql: ## Open a SQL shell in the database (DB=postgres: psql, DB=oracle: SQL*Plus)
ifeq ($(DB),oracle)
	$(COMPOSE_ALL) exec oracle sqlplus -L $(DB_USERNAME)/$(DB_PASSWORD)@//localhost:1521/$(ORACLE_SERVICE)
else
	$(COMPOSE) exec postgres psql -U $(DB_USERNAME) -d $(DB_NAME)
endif

.PHONY: psql
psql: ## Open a psql shell in the PostgreSQL database
	$(COMPOSE) exec postgres psql -U $(DB_USERNAME) -d $(DB_NAME)

.PHONY: sqlplus
sqlplus: ## Open SQL*Plus in the Oracle database (as DB_USERNAME)
	@$(MAKE) --no-print-directory sql DB=oracle

##@ Application

.PHONY: run
run: up ## Run the app (profile dev) against the compose DB (DB=postgres|oracle) + WireMock
	DB_URL=$(DB_JDBC_URL) \
	DB_USERNAME=$(DB_USERNAME) DB_PASSWORD=$(DB_PASSWORD) \
	CORE_BANKING_URL=$(CORE_BANKING_URL) \
	NOTIFICATION_URL=$(NOTIFICATION_URL) \
	GATEWAY_ADMIN_TOKEN=$(ADMIN_TOKEN) \
	$(MVN) spring-boot:run -Dspring-boot.run.profiles=$(SPRING_PROFILES) -Dspring-boot.run.arguments=--server.port=$(APP_PORT)

.PHONY: run-oracle
run-oracle: ## Same as 'make run DB=oracle': the app on the compose Oracle Free (profiles dev,oracle)
	@$(MAKE) --no-print-directory run DB=oracle

.PHONY: run-test
run-test: ## Run the app with a throwaway Testcontainers DB (DB=postgres|oracle) + in-process WireMock (no compose needed)
	$(MVN) spring-boot:test-run -Dspring-boot.run.jvmArguments=-Dit.db=$(DB) -Dspring-boot.run.arguments=--server.port=$(APP_PORT)

.PHONY: build
build: ## Build the executable jar (skips tests) into target/
	$(MVN) package -DskipTests

.PHONY: clean
clean: ## Remove build output
	$(MVN) clean
	rm -f databasechangelog.csv

##@ Tests

.PHONY: test
test: ## Run all tests (unit + integration via Testcontainers; DB=oracle runs them on Oracle Free)
	$(MVN) test $(TEST_PROFILE)

.PHONY: test-unit
test-unit: ## Run unit tests only (no Docker needed)
	$(MVN) test -Dtest='!*IntegrationTest,!StartupValidationTest'

.PHONY: test-it
test-it: ## Run integration tests only (Testcontainers; DB=postgres|oracle)
	$(MVN) test $(TEST_PROFILE) -Dtest='*IntegrationTest'

.PHONY: test-oracle
test-oracle: ## Run integration tests against Oracle Free (large image, slow first run)
	$(MVN) test -Poracle-it -Dtest='*IntegrationTest'

##@ Database DDL (for DBA-managed schemas)

# Database for ddl-pending; defaults to the docker compose DB of DB=postgres|oracle
DDL_DB_URL ?= $(DB_JDBC_URL)

.PHONY: ddl-pending
ddl-pending: ## SQL for the changes not yet applied to a DB (default: compose DB of DB=...) into target/liquibase/update.sql
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
audit: ## Show the 10 most recent audit transactions (DB=postgres|oracle)
ifeq ($(DB),oracle)
	@printf '%s\n' "SET LINESIZE 220 PAGESIZE 50 FEEDBACK OFF" \
		"COLUMN correlation_id FORMAT A40" "COLUMN flow_code FORMAT A20" "COLUMN http_method FORMAT A6" \
		"COLUMN path FORMAT A40" "COLUMN error_code FORMAT A24" \
		"SELECT correlation_id, flow_code, http_method, path, client_status, error_code, duration_ms, started_at FROM gw_audit_transaction ORDER BY id DESC FETCH FIRST 10 ROWS ONLY;" \
		"EXIT" | $(SQLPLUS)
else
	@$(COMPOSE) exec postgres psql -U $(DB_USERNAME) -d $(DB_NAME) -c \
		"SELECT correlation_id, flow_code, http_method, path, client_status, error_code, duration_ms, started_at \
		 FROM gw_audit_transaction ORDER BY id DESC LIMIT 10;"
endif
