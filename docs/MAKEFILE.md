# Using the Makefile

The `Makefile` wraps the everyday commands for building, running and testing the gateway. Run `make` or `make help` to list every target.

## Prerequisites

| Tool | Why | Check |
|---|---|---|
| JDK 21 | builds and runs the app | `java -version` |
| Docker (with Compose v2) | local PostgreSQL + WireMock, Testcontainers | `docker compose version` |
| `make`, `curl` | commands and demo calls | preinstalled on macOS / most Linux |

Maven isn't needed because the project ships the Maven wrapper (`./mvnw`).

The Makefile finds JDK 21 by itself, in this order:
1. `JAVA_HOME`, if it is already set
2. `/usr/libexec/java_home -v 21` (macOS)
3. Homebrew's `/opt/homebrew/opt/openjdk@21`

To use a different JDK, pass it: `make test JAVA_HOME=/path/to/jdk-21`.

## Quick start

```bash
make up       # start PostgreSQL + WireMock (docker compose), wait until healthy
make run      # start the app on :8080 with the demo flows (Ctrl+C to stop)
# in a second terminal:
make demo     # call every demo flow
make audit    # see the audit rows those calls produced
make down     # stop the containers (data is kept)
```

`make run` runs `make up` first, so `make run` on its own is enough.

### Oracle instead of PostgreSQL

Add `DB=oracle` to the same commands; everything else works the same:

```bash
make run DB=oracle     # starts Oracle Free + WireMock, then the app on :8080 (profiles dev,oracle)
make demo
make audit DB=oracle
make sql DB=oracle     # SQL*Plus as gateway/gateway on FREEPDB1 (same as: make sqlplus)
make down              # stops PostgreSQL and Oracle containers (data is kept)
```

The first `make up DB=oracle` downloads Oracle Database Free (`gvenzl/oracle-free:23-slim-faststart`, several GB) and creates the database (1-3 minutes); later starts take seconds. The app user is `DB_USERNAME` / `DB_PASSWORD` (`gateway` / `gateway`) in the pluggable database `FREEPDB1`; Liquibase creates the tables and the demo data on the first run, exactly as on PostgreSQL. Both databases can exist side by side; `DB` picks the one a command uses. `make run-oracle` and `make up-oracle` are shortcuts.

To use an Oracle server that isn't in Docker, don't use `make run`; set `DB_URL=jdbc:oracle:thin:@//host:1521/SERVICE`, `DB_USERNAME`, `DB_PASSWORD` and start with the `oracle` profile (see the README's Configuration section).

Instead of `make demo`, you can send requests one at a time from your IDE with [`http/gateway.http`](../http/gateway.http). In IntelliJ, click ▶ next to a request; in VS Code, install the *REST Client* extension and click *Send Request*. Each request has a comment with the expected status and response.

## What `docker-compose.yml` starts

| Service | Container | Port | Purpose |
|---|---|---|---|
| `postgres` | `json-gateway-postgres` | `5432` | Gateway database `gateway`, user `gateway` / password `gateway`. Data is kept in the `postgres-data` volume. |
| `oracle` | `json-gateway-oracle` | `1521` | Only with `DB=oracle` (compose profile `oracle`). Oracle Database Free 23ai, service `FREEPDB1`, app user `gateway` / `gateway`, SYS password `oracle`. Data is kept in the `oracle-data` volume. |
| `wiremock` | `json-gateway-wiremock` | `8089` | Fake downstream systems `CORE_BANKING` and `NOTIFICATION`, using the stubs in `src/test/resources/wiremock/mappings`. |

The credentials match the defaults in `application-dev.yml`, so the app connects without extra configuration. Liquibase creates the tables and seeds the demo flows on the first `make run`.

## Targets

### Infrastructure

| Target | What it does |
|---|---|
| `make up` | Starts the database (`DB=postgres` default, or `DB=oracle`) and WireMock in the background and waits until both are healthy. `make up-oracle` = `make up DB=oracle`. |
| `make down` | Stops and removes all containers, PostgreSQL and Oracle. **The database volumes are kept.** |
| `make db-reset` | Stops the database of `DB` and **deletes its volume** (asks first; `CONFIRM=yes` skips). The next `make run` (with the same `DB`) starts from an empty DB and re-seeds the demo flows. |
| `make db-truncate` | **Deletes all rows** from the gateway tables (flows, steps, rules, lookups, schemas, target systems, audit) but keeps the tables; `DB=oracle` runs the Oracle script through SQL*Plus. Asks you to type `yes`; `make db-truncate CONFIRM=yes` skips the question. Then run `make reload` if the app is running. |
| `make ps` | Shows container status. |
| `make logs` | Follows container logs (Ctrl+C to stop following). |
| `make sql` | Opens a SQL shell in the gateway database: `psql`, or SQL*Plus with `DB=oracle`. |
| `make psql` / `make sqlplus` | The same for PostgreSQL / Oracle explicitly. |

#### Emptying the database: `db-truncate` vs `db-reset`

| | `make db-truncate` | `make db-reset` |
|---|---|---|
| Removes | all rows of the 9 gateway tables (incl. target systems) | the whole database (volume) |
| Keeps | tables, Liquibase history | nothing |
| Demo flows afterwards | **not** re-seeded (Liquibase considers them already applied) | re-seeded on the next `make run` |
| App can stay running | yes, then `make reload` (it will have 0 flows) | no, restart with `make run` |
| Use when | you want a clean slate for your own config | you want the demo data back, or the schema is broken |

The SQL behind `db-truncate` is in [`scripts/`](../scripts): `truncate-all.postgres.sql` and `truncate-all.oracle.sql` (used by `make db-truncate DB=oracle`, or run it yourself with SQL*Plus/SQLcl against another Oracle). Both use the default table names; edit them if you configured `gateway.db.tables.*` or a schema. Liquibase's own tables are deliberately not emptied, because the app would then fail to start.

> After truncating, any config you add must bring its own lookup rows. Otherwise `make reload` rejects it with `lookup_code '...' has no gw_lookup_entry rows`. The tutorial script [`examples/account-overview.sql`](examples/account-overview.sql) inserts the lookups it needs, so it works on an empty database too.

### Application

| Target | What it does |
|---|---|
| `make run` | Runs the app with profile `dev` against the compose PostgreSQL and WireMock. `make run DB=oracle` (or `make run-oracle`) uses the compose Oracle Free with profiles `dev,oracle`. |
| `make run-test` | Runs the app with a **throwaway** Testcontainers database (PostgreSQL, or Oracle Free with `DB=oracle`) and in-process WireMock. Docker Compose isn't used, and nothing persists after you stop it. |
| `make build` | Builds the executable jar into `target/` (tests skipped). Run it with `java -jar target/json-gateway-0.0.1-SNAPSHOT.jar`. |
| `make clean` | Deletes build output. |

### Tests

| Target | Docker needed | What it runs |
|---|---|---|
| `make test-unit` | no | Unit tests only. Fast; use it while coding. |
| `make test-it` | yes | Integration tests against PostgreSQL (Testcontainers); `DB=oracle` runs them on Oracle Free. |
| `make test` | yes | Everything. Run it before committing. `make test DB=oracle` runs the whole suite on Oracle Free. |
| `make test-oracle` | yes | Integration tests against Oracle Free. The first run downloads a large image (~1 GB+). |

Tests use their own Testcontainers databases and never touch the compose database.

### Database DDL (DBA-managed schemas)

| Target | What it does |
|---|---|
| `make ddl-pending` | Connects to an **existing** database and generates only the SQL it is still missing (e.g. new tables after an upgrade), including the `gw_db_changelog` rows, so Liquibase won't try again later. Nothing is executed. Default target: the compose DB of `DB` (`make ddl-pending DB=oracle` for the compose Oracle). Other DBs: `make ddl-pending DDL_DB_URL=jdbc:oracle:thin:@//host:1521/SERVICE DB_USERNAME=... DB_PASSWORD=...`. |
| `make ddl-postgres` | Generates the PostgreSQL DDL offline (no DB connection) into `target/liquibase/update.sql`. |
| `make ddl-oracle` | Same for Oracle. |

Use `DDL_ARGS` to rename tables or set a schema:

```bash
make ddl-oracle DDL_ARGS="-Dtbl.flow=MW_ROUTE -Dtbl.audit_transaction=MW_AUDIT_TX -Dliquibase.defaultSchemaName=GATEWAY"
```

The demo-flow seed data is never included in the generated DDL.

**After upgrading the app** (a new version adds tables, e.g. `gw_target_system`):
- **Liquibase enabled** (default, incl. local dev): just restart the app; it creates what's missing.
- **DBA-managed** (`LIQUIBASE_ENABLED=false`): run `make ddl-pending` against that database and give the DBA `target/liquibase/update.sql`.

Don't create the new tables by hand: Liquibase wouldn't know about them and would fail with "already exists" on its next run.

### Demo calls (app must be running)

| Target | What it does |
|---|---|
| `make health` | `GET /actuator/health` |
| `make demo` | Calls each demo flow: inquiry, history, a successful transfer, insufficient funds (422), blocked beneficiary (debit skipped), unknown account (404) and an invalid request (400). |
| `make reload` | `POST /admin/config/reload`, which applies config changes made in the DB without a restart. |
| `make audit` | Shows the 10 most recent rows of `gw_audit_transaction` (`DB=oracle`: from the compose Oracle). |

## Overridable variables

Pass any of these on the command line, e.g. `make up DB_PORT=5433`.

| Variable | Default | Used by |
|---|---|---|
| `DB` | `postgres` | Which database `up`, `run`, `run-test`, `db-reset`, `db-truncate`, `sql`, `audit`, `ddl-pending`, `test`, `test-it` use: `postgres` or `oracle` |
| `APP_PORT` | `8080` | `run`, `run-test`, demo targets |
| `DB_PORT` | `5432` | compose port mapping, `run` |
| `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | `gateway` | compose, `run`, `sql`, `audit` (`DB_USERNAME`/`DB_PASSWORD` are also the Oracle app user) |
| `ORACLE_PORT` | `1521` | `DB=oracle`: compose port mapping, `run` |
| `ORACLE_SERVICE` | `FREEPDB1` | `DB=oracle`: service name in the JDBC URL and SQL*Plus |
| `ORACLE_SYS_PASSWORD` | `oracle` | `DB=oracle`: SYS/SYSTEM password of the container (first start only) |
| `WIREMOCK_PORT` | `8089` | compose port mapping, `run` |
| `ADMIN_TOKEN` | `dev-admin-token` | `run`, `reload` |
| `CORE_BANKING_URL` | `http://localhost:$(WIREMOCK_PORT)` | `run`: base URL of the core banking system, e.g. `make run CORE_BANKING_URL=http://10.20.30.40:9080`. It fills the `${CORE_BANKING_URL:...}` placeholder in the demo `gw_target_system` row; if you put a literal URL in that row, the row wins. |
| `NOTIFICATION_URL` | `http://localhost:$(WIREMOCK_PORT)` | `run`: base URL of the notification system |
| `JAVA_HOME` | auto-detected | all Maven targets |
| `DDL_ARGS` | empty | `ddl-postgres`, `ddl-oracle` |

If you change `DB_PORT`, `WIREMOCK_PORT` or the DB credentials, pass the same values to every command, including `make down`. Alternatively, put them in a `.env` file next to `docker-compose.yml`; Docker Compose reads it automatically.

## Example: add a flow and try it

```bash
make run                       # terminal 1
make psql                      # terminal 2
```

```sql
INSERT INTO gw_flow (code, name, http_method, path_pattern) VALUES ('PING', 'Ping', 'GET', '/v1/ping');
INSERT INTO gw_mapping_rule (flow_id, phase, seq, target_type, target_path, constant_value)
  SELECT id, 'FLOW_RESPONSE', 1, 'BODY', '$.pong', 'true' FROM gw_flow WHERE code = 'PING';
\q
```

```bash
make reload                    # {"flows":4,...}
curl localhost:8080/api/v1/ping   # {"pong":true}
```

If the new rows are invalid, `make reload` returns `422` with the list of problems and the previous configuration stays active.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Unable to locate a Java Runtime` / wrong Java version | Install JDK 21 (`brew install openjdk@21`), or pass `JAVA_HOME=...`. |
| `port is already allocated` on `make up` | Another service uses the port. Use `make up DB_PORT=5433` (and the same `DB_PORT` for `make run`), or stop the other service. |
| `make run` can't connect to the DB | Check `make ps` shows postgres `healthy`. |
| Demo calls return `GW-502-CONNECTION` | WireMock isn't running. Run `make up` and check `make ps`. |
| App won't start after editing changelog files | Liquibase rejects changed changesets. Locally, `make db-reset` and start again. |
| Start completely fresh | `make db-reset && make run` |
