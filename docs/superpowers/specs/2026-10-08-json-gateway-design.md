# JSON Gateway — Design Spec

**Date:** 2026-10-08
**Status:** Draft for review

## 1. Purpose

A Spring Boot backend that sits between client applications and downstream REST services. For each configured inbound endpoint it validates the request, transforms it, calls one or more downstream services (sequentially, in parallel, or conditionally), transforms the combined result into a response, and returns it. All transformation and orchestration behavior is configured in database tables and loaded at startup, so a new integration is added by inserting rows (plus, when needed, a small custom Java class) without changing core code.

### Success criteria

- A new integration endpoint can be added with SQL/Liquibase rows only, for anything covered by the config-driven features (Section 6).
- Logic that config cannot express is added as a Spring bean implementing one of three extension interfaces and referenced by bean name from the DB.
- The same build runs on PostgreSQL (development) and Oracle (switchable by Spring profile, no code change).
- Invalid configuration prevents startup (or is rejected by reload) with a clear list of errors.
- Every transaction can be audited to the DB, switchable globally and per flow.

### Out of scope (for this version)

- Admin CRUD API / UI for config (config is managed by SQL/Liquibase).
- Cross-instance reload coordination (reload is called per instance).
- Non-JSON payloads (XML, form, multipart).
- Retries, circuit breakers, rate limiting.
- Dynamic downstream auth (OAuth token fetch, signing) as config — handled with a `MessageHandler`.

## 2. Tech stack

| Concern | Choice |
|---|---|
| Language / runtime | Java 21 (virtual threads) |
| Framework | Spring Boot 4.1.x (Spring Framework 7, Jackson 3) |
| Build | Maven with wrapper (`mvnw`) |
| DB schema | Liquibase (single changelog, generates Postgres and Oracle DDL) |
| Config read | Spring Data JPA / Hibernate; dialect selected by profile |
| Connection pool | HikariCP (explicitly configured, Section 4.1) |
| Ops endpoints | Spring Boot Actuator — `health` and `metrics` exposed; others disabled |
| DB (dev) | PostgreSQL |
| DB (switchable) | Oracle (profile `oracle`) |
| JSON | Jackson 3 `JsonNode` |
| JSON Schema | networknt `json-schema-validator` 3.x (Jackson 3 line), draft 2020-12 |
| Downstream HTTP | JDK `java.net.http.HttpClient` (per-request read timeout; RestClient only supports per-factory timeouts, and the effective step timeout is capped by the remaining flow time) |
| Conditions | Restricted SpEL (`SimpleEvaluationContext`, read-only) |
| Tests | JUnit 5, Testcontainers (PostgreSQL, Oracle Free), WireMock |

Base package: `com.mhamzah.gateway`.

Profiles:
- `dev` — PostgreSQL, Liquibase `dev` context (demo flows), WireMock-backed demo target systems.
- `oracle` — Oracle datasource and dialect.
- Production profiles supply datasource and target-system settings via environment.

## 3. Architecture

Single Maven module, packages by responsibility:

| Package | Responsibility |
|---|---|
| `config` | JPA entities for config tables; `ConfigLoader` reads all config and compiles an immutable `FlowRegistry` snapshot (compiled paths, parsed schemas, resolved handler beans, route matchers, parsed conditions). Snapshot held in an `AtomicReference`, swapped atomically on reload. Also `ConfigValidator` (fail-fast rules, Section 11). |
| `routing` | Catch-all controller on `/api/**`; matches HTTP method + path pattern (Spring `PathPatternParser`) against the current snapshot. |
| `engine` | `FlowExecutor` — builds `ExecutionContext`, runs the lifecycle (Section 7), schedules step groups. |
| `mapping` | `MappingEngine` (applies rules), `JsonPath` (own read/write path syntax, Section 6.2), `ConverterRegistry`, lookup resolution. |
| `schema` | `SchemaValidator` using schemas precompiled at load. |
| `invoke` | `DownstreamClient` — JDK `HttpClient` (one per connect timeout), per-request read timeout, correlation-ID propagation. |
| `condition` | `ConditionEvaluator` — restricted SpEL with path placeholders (Section 6.5). |
| `extension` | `FieldHandler`, `MessageHandler`, `ErrorHandler` interfaces; `DefaultErrorHandler`; `LookupErrorHandler` base class; `extension.custom` for project-specific handlers. |
| `audit` | `AuditService` — async bounded queue + background writer. |
| `admin` | `POST /admin/config/reload`, protected by an admin token header. |
| `masking` | `Masker` — masks configured field names in logged/audited payloads. |

Custom handlers are Spring beans (in `extension.custom` or a separate jar on the classpath). The DB references them by bean name.

## 4. Configuration in `application.yml`

```yaml
gateway:
  api-base-path: /api               # inbound routes are matched under this prefix
  default-flow-timeout-ms: 30000
  default-step-timeout-ms: 10000
  admin:
    token: ${GATEWAY_ADMIN_TOKEN}   # required header X-Admin-Token for /admin/**
  audit:
    enabled: true                   # global switch
    store-payloads: true            # false = audit rows without payload columns
    queue-capacity: 10000
  masking:
    fields: [pin, password, cardNo, cvv]   # field names (any depth), case-insensitive
    mask: "****"
  target-systems:
    CORE_BANKING:
      base-url: https://core.internal/api
      connect-timeout-ms: 3000
      read-timeout-ms: 10000
      static-headers:
        X-Channel-Id: GATEWAY
    NOTIFICATION:
      base-url: https://notif.internal
```

A step references a target system by name. Target systems can be defined here **or** in `gw_target_system` (Section 5.8); a database row wins over a definition here with the same code. *(Amended 2026-10-08: originally base URLs lived only in application.yml; they were moved to the database on request so IP/port can change with a reload. Per-environment portability is kept through `${...}` placeholders in the database values.)*

### 4.1 Database connection pool (HikariCP)

The application uses a single HikariCP pool for the one `DataSource`. HikariCP is declared explicitly in the POM (`com.zaxxer:HikariCP`, version managed by Spring Boot) and `spring.datasource.type` is set to `com.zaxxer.hikari.HikariDataSource`, so the pool implementation cannot silently change through a transitive dependency.

The request path never touches the database: config is served from the in-memory `FlowRegistry`, and audit rows are written by one background writer thread. DB consumers are therefore config load/reload, the audit writer, and Liquibase at startup. One pool is sufficient; a separate audit pool is not needed.

Shared settings (`application.yml`), all overridable per environment:

```yaml
spring:
  datasource:
    type: com.zaxxer.hikari.HikariDataSource
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      pool-name: gateway-pool
      maximum-pool-size: ${DB_POOL_MAX:10}
      minimum-idle: ${DB_POOL_MIN_IDLE:2}
      connection-timeout: ${DB_POOL_CONNECTION_TIMEOUT_MS:30000}   # wait for a connection
      validation-timeout: 5000
      idle-timeout: 600000          # 10 min
      max-lifetime: 1800000         # 30 min; keep below DB/firewall idle cut-off
      keepalive-time: 300000        # 5 min; keeps idle connections alive through firewalls
      leak-detection-threshold: ${DB_POOL_LEAK_DETECTION_MS:0}      # 0 = off; set e.g. 20000 in dev
      register-mbeans: true         # pool metrics via JMX
```

Profile specifics:

- `dev` (PostgreSQL): driver `org.postgresql.Driver`; `leak-detection-threshold: 20000`.
- `oracle`: driver `oracle.jdbc.OracleDriver`; `data-source-properties: { oracle.jdbc.implicitStatementCacheSize: 50, oracle.net.CONNECT_TIMEOUT: 5000 }`. No `connection-test-query` — Hikari uses JDBC4 `Connection.isValid()` for both databases.

Behavior:

- The audit writer borrows one connection per batch and returns it immediately; it never holds a connection while waiting on the queue.
- If the pool cannot provide a connection within `connection-timeout`, the audit batch fails, is logged with its correlation IDs, and is dropped (Section 10) — client requests are unaffected.
- At startup, failure to obtain a connection fails the application (Liquibase and `ConfigLoader` need the DB). A reload that cannot get a connection returns `503 {"errors": ["database unavailable"]}` and keeps the current config.
- Pool metrics (active, idle, pending, timeouts) are exposed through Spring Boot Actuator `/actuator/metrics/hikaricp.*` and JMX.

### 4.2 Database schema and table names

The schema and every table name are configurable. The names in Section 5 are the defaults.

```yaml
gateway:
  db:
    schema: ${DB_SCHEMA:}               # empty = the connection's default schema
    tables:
      flow: gw_flow
      flow-step: gw_flow_step
      mapping-rule: gw_mapping_rule
      lookup-entry: gw_lookup_entry
      json-schema: gw_json_schema
      audit-transaction: gw_audit_transaction
      audit-step: gw_audit_step
    liquibase-tables:
      changelog: gw_db_changelog        # Liquibase's own tracking tables
      changelog-lock: gw_db_changelog_lock
```

Applied consistently in every place the application touches the DB:

| Consumer | How the names are applied |
|---|---|
| JPA (config read) | Entities use logical table names; a custom Hibernate `PhysicalNamingStrategy` maps them to `gateway.db.tables.*`. `hibernate.default_schema` is set from `gateway.db.schema` when non-empty. |
| Audit writer (JDBC) | Insert SQL is built once at startup from the configured schema and table names (`schema.table` when a schema is set). |
| Liquibase | Changelogs reference tables and derived object names only through changelog parameters (`${tbl.flow}`, …), populated from `gateway.db.tables.*`. `default-schema` and the changelog/lock table names come from `gateway.db.schema` and `gateway.db.liquibase-tables`. |

Derived object names (primary keys, unique constraints, indexes, foreign keys) are built from the table name plus a short suffix: `<table>_PK`, `<table>_UK1`, `<table>_IX1`, `<table>_FK1`.

**Validation at startup.** Invalid values fail startup with a clear message. This also guarantees configured names are safe to put into SQL.

- Each table name and the schema match `^[A-Za-z][A-Za-z0-9_]*$`. Names are unquoted, so case follows the database's default folding: lower-case in PostgreSQL, upper-case in Oracle.
- Each table name is ≤ 25 characters, leaving room for the 5-character suffix within Oracle's 30-character identifier limit. The schema is ≤ 30 characters.
- All seven table names are distinct.

**DBA-managed schemas.** For environments where DBAs apply DDL themselves, set `spring.liquibase.enabled: false`. The DBA can generate the exact SQL for the configured names and schema with `./mvnw liquibase:updateSQL -P<db-profile>`. The same `gateway.db.*` values must then be given to the application, and `ConfigLoader` fails startup if the configured tables do not exist.

## 5. Data model

Tables are created by Liquibase. Table names below are defaults (configurable, Section 4.2). Portability rules: `clob` for large text, Liquibase `boolean`, identifiers ≤ 30 characters, identity/sequence via Liquibase `autoIncrement`, enums stored as `varchar`.

### 5.1 `gw_flow` — one row per inbound endpoint

| Column | Type | Notes |
|---|---|---|
| id | bigint PK | |
| code | varchar(100) unique | e.g. `TRANSFER_INTRABANK` |
| name | varchar(200) | |
| http_method | varchar(10) | GET/POST/PUT/PATCH/DELETE |
| path_pattern | varchar(500) | relative to `api-base-path`, e.g. `/v1/accounts/{accountNo}/transfer` |
| request_schema_code | varchar(100) null | FK-by-code to `gw_json_schema` |
| response_schema_code | varchar(100) null | |
| request_handler | varchar(100) null | `MessageHandler` bean name |
| response_handler | varchar(100) null | `MessageHandler` bean name |
| error_handler | varchar(100) null | `ErrorHandler` bean name; null = `defaultErrorHandler` |
| success_status | int | default 200 |
| timeout_ms | int null | null = global default |
| audit_mode | varchar(10) | INHERIT / ON / OFF |
| enabled | boolean | |

### 5.2 `gw_flow_step` — downstream calls

| Column | Type | Notes |
|---|---|---|
| id | bigint PK | |
| flow_id | bigint FK | |
| name | varchar(100) | unique within flow; used as `$.steps.<name>` |
| step_order | int | same value = same parallel group |
| target_system | varchar(100) | `gw_target_system.code` or key under `gateway.target-systems` |
| http_method | varchar(10) | |
| path_template | varchar(500) | e.g. `/v1/accounts/{accountNo}`; variables filled by PATH mappings |
| condition_expr | varchar(1000) null | Section 6.5; null = always run |
| success_expr | varchar(1000) null | Section 6.5 syntax; evaluated after a 2xx response is stored, e.g. `${steps.inquiry.body.responseCode} == '00'`. False → `DOWNSTREAM_BUSINESS_ERROR`. Null = any 2xx is success |
| on_failure | varchar(10) | STOP / CONTINUE |
| timeout_ms | int null | null = target system / global default |
| response_schema_code | varchar(100) null | validates downstream response body |
| request_handler | varchar(100) null | `MessageHandler`, after request mapping, before call |
| response_handler | varchar(100) null | `MessageHandler`, after response, before storing in context |
| enabled | boolean | disabled steps are dropped at load |

### 5.3 `gw_mapping_rule` — one row per field

| Column | Type | Notes |
|---|---|---|
| id | bigint PK | |
| flow_id | bigint FK | |
| step_id | bigint FK null | required for STEP_REQUEST, null for FLOW_RESPONSE |
| phase | varchar(20) | STEP_REQUEST / FLOW_RESPONSE |
| seq | int | application order within (step, phase) |
| target_type | varchar(10) | BODY / HEADER / QUERY / PATH (FLOW_RESPONSE allows BODY / HEADER only) |
| target_path | varchar(500) | BODY: path (Section 6.2). HEADER/QUERY/PATH: plain name |
| source_path | varchar(500) null | context path, e.g. `$.request.body.amount` |
| constant_value | varchar(4000) null | literal; parsed as JSON if valid JSON, else string |
| default_value | varchar(4000) null | used when source resolves to missing/null; same parsing as constant |
| converter | varchar(200) null | e.g. `PAD_LEFT:12:0` (Section 6.4) |
| lookup_code | varchar(100) null | `gw_lookup_entry.lookup_code` |
| field_handler | varchar(100) null | `FieldHandler` bean name |
| required | boolean | missing after default → `MAPPING_ERROR` |

Constraint (validated at load): exactly one of `source_path` / `constant_value` is set — except when `field_handler` is set, in which case both may be null (handler produces the value from nothing).

### 5.4 `gw_lookup_entry`

| Column | Type | Notes |
|---|---|---|
| id | bigint PK | |
| lookup_code | varchar(100) | |
| source_value | varchar(500) | `*` = fallback for unmatched values |
| target_value | varchar(4000) | parsed as JSON if valid JSON, else string |

Unique `(lookup_code, source_value)`. No match and no `*` row → value passes through unchanged.

### 5.5 `gw_json_schema`

| Column | Type | Notes |
|---|---|---|
| id | bigint PK | |
| code | varchar(100) unique | |
| schema_text | clob | JSON Schema, draft 2020-12 |
| description | varchar(500) null | |

### 5.6 `gw_audit_transaction`

`id, correlation_id varchar(64), flow_code varchar(100) null, http_method, path varchar(1000), client_status int, error_type varchar(50) null, error_code varchar(100) null, request_payload clob null, response_payload clob null, started_at timestamp, duration_ms bigint`. Index on `correlation_id` and `started_at`.

### 5.7 `gw_audit_step`

`id, transaction_id FK, step_name, target_system, http_method, url varchar(2000), http_status int null, outcome varchar(10) (SUCCESS/FAILED/SKIPPED/TIMEOUT/CANCELLED — CANCELLED = stopped because a sibling in the same parallel group failed with STOP), request_payload clob null, response_payload clob null, started_at timestamp null, duration_ms bigint null`.

### 5.8 `gw_target_system` — downstream systems

| Column | Type | Notes |
|---|---|---|
| id | bigint PK | |
| code | varchar(100) unique | referenced by `gw_flow_step.target_system` |
| base_url | varchar(500) | `http(s)://host:port[/path]`; may contain `${NAME}` / `${NAME:default}` resolved from the Spring `Environment` at load/reload |
| connect_timeout_ms | int null | default 3000 |
| read_timeout_ms | int null | default for steps of this target; null = `gateway.default-step-timeout-ms` |
| enabled | boolean | false = row ignored (application config used if present) |

### 5.9 `gw_target_system_header` — fixed headers per target

`id, target_code varchar(100) FK → gw_target_system.code (cascade), header_name varchar(200), header_value varchar(4000)` (may contain `${...}`); unique `(target_code, header_name)`.

Effective targets = `gateway.target-systems` from application config, replaced per code by enabled database rows (no field-level merge). Target systems are part of the reloadable `FlowRegistry` snapshot; the startup/reload log lists each target with its source.

## 6. Config-driven features

### 6.1 Execution context

A JSON tree that mappings and conditions read from:

```
$.request.headers.<lowercase-name>     string (first value)
$.request.path.<var>                   string (from path_pattern variables)
$.request.query.<name>                 string (first value)
$.request.body                         parsed JSON ({} if empty)
$.steps.<name>.outcome                 SUCCESS / FAILED / SKIPPED / TIMEOUT
$.steps.<name>.status                  HTTP status (absent if no response)
$.steps.<name>.headers.<lowercase>     response headers
$.steps.<name>.body                    parsed response body ({} if empty)
$.correlationId                        string
```

### 6.2 Path syntax

A deliberately small syntax, used both for reading the context and writing the target, implemented on Jackson `JsonNode`:

- `$` — root
- `.name` — object field (names: letters, digits, `_`, `-`)
- `['any name']` — object field with other characters
- `[n]` — array index
- `[*]` — every array element

Reading a missing path yields "missing" (not an error). Writing creates intermediate objects/arrays as needed.

**Arrays:** wildcards map positionally. `source items[*].price` → `target detail[*].harga` writes one element per source element; `a[*].b[*].c` → `x[*].y[*].z` maps two levels. A rule whose target has a different number of `[*]` than its source is rejected at load, except a target with zero `[*]` and source with ≥1 `[*]`, which writes the array of collected values.

**Passthrough:** `target_path = $` with an object source copies the whole object into the target root (merged with other rules applied before/after by `seq`).

### 6.3 Rule evaluation order

For each rule, in `seq` order:

1. **Value:** `constant_value`, or read `source_path` from context.
2. **Default:** if missing or JSON null, use `default_value`.
3. **Lookup:** if `lookup_code`, translate (string form of value → target value).
4. **Converter:** if `converter`, apply.
5. **Field handler:** if `field_handler`, call `FieldHandler.handle(value, ctx)`.
6. **Required:** if still missing and `required`, raise `MAPPING_ERROR`; if missing and not required, skip the rule (no write).
7. **Write** to target (BODY path, or outgoing HEADER/QUERY/PATH variable as string).

With `[*]` sources, steps 2–6 apply per element.

### 6.4 Converters (built in)

Syntax: `NAME` or `NAME:arg1:arg2` (use `\:` for a literal colon in an argument).

| Converter | Example | Effect |
|---|---|---|
| `TO_STRING` | | number/boolean → string |
| `TO_NUMBER` | | numeric string → number |
| `TO_BOOLEAN` | | `"true"/"false"/"Y"/"N"/"1"/"0"` → boolean |
| `PAD_LEFT` | `PAD_LEFT:12:0` | left-pad string to length with char |
| `PAD_RIGHT` | `PAD_RIGHT:20: ` | right-pad |
| `TRIM` | | trim whitespace |
| `UPPER` / `LOWER` | | case |
| `SUBSTRING` | `SUBSTRING:0:6` | begin, end (end optional) |
| `DATE_FORMAT` | `DATE_FORMAT:yyyy-MM-dd:ddMMyyyy` | reformat date/time string |
| `DECIMAL_SCALE` | `DECIMAL_SCALE:2` | set scale, HALF_UP |

Unknown converter names or bad arguments are rejected at load. Conversion failure at runtime → `MAPPING_ERROR`.

### 6.5 Step conditions

Restricted SpEL with path placeholders: `${<path>}` refers to a context path (without the leading `$.`).

```
${steps.inquiry.body.status} == 'ACTIVE' and ${request.body.amount} > 0
```

At load, placeholders are extracted and replaced with variables (`#p0`, `#p1`, …) and the expression is parsed. At runtime each placeholder is resolved to a Java value (string, number, boolean, or null when missing) and the expression is evaluated with `SimpleEvaluationContext.forReadOnlyDataBinding()` — no type references, no constructors, no bean references, no arbitrary method calls. A non-boolean result at runtime raises `MAPPING_ERROR` (500).

The same syntax and sandbox are used for `gw_flow_step.success_expr` (Section 7).

## 7. Request lifecycle

1. **Route.** Capture the current `FlowRegistry` snapshot once for the whole request. Match method + path; disabled flows are not registered. No match → `ROUTE_NOT_FOUND`.
2. **Correlation.** `X-Correlation-Id` header or generated UUID; put in MDC and `$.correlationId`.
3. **Build context** (Section 6.1). Body not valid JSON → `INVALID_JSON`.
4. **Validate inbound** body against `request_schema_code` if set → `REQUEST_SCHEMA_INVALID`.
5. **Flow `request_handler`** if set; may modify request headers/body in the context or return a response to short-circuit (returned as-is at step 10; audit still applies).
6. **Execute step groups** by ascending `step_order`. For each group:
   1. Evaluate each step's condition; false → `outcome=SKIPPED`.
   2. Run remaining steps — inline if one, otherwise in parallel on virtual threads.
   3. Per step: STEP_REQUEST mappings → outgoing body/headers/query/path vars → step `request_handler` → HTTP call (`target base-url + path_template`, step timeout, `X-Correlation-Id`, target static headers) → step `response_handler` → validate `response_schema_code` → store into `$.steps.<name>` → evaluate `success_expr` if set (false → step fails with `DOWNSTREAM_BUSINESS_ERROR`, `outcome=FAILED`).
   4. Wait for all steps in the group. If any step failed with `on_failure=STOP`, cancel unfinished siblings and go to error handling with that step's error (first by `name` order if several).
   5. Steps with `on_failure=CONTINUE` record `outcome=FAILED`/`TIMEOUT` plus whatever status/headers/body exist.
   The flow timeout caps total time across groups → `FLOW_TIMEOUT`.
7. **Build response** with FLOW_RESPONSE mappings (BODY and HEADER targets). No FLOW_RESPONSE rules → empty JSON object body.
8. **Validate outbound** against `response_schema_code` if set → `RESPONSE_SCHEMA_INVALID`.
9. **Flow `response_handler`** if set.
10. **Return** `success_status`, mapped headers, `X-Correlation-Id`, body.
11. **Audit** (Section 10) if enabled for the flow.

**Downstream success** = 2xx and body empty or valid JSON. Otherwise the step fails; status, headers, and body (parsed if JSON, else as a JSON string) are kept for the `ErrorHandler`.

**Concurrency safety:** each step writes only `$.steps.<own name>`; context storage is thread-safe. Config validation rejects any STEP_REQUEST mapping or condition of a step that reads `$.steps.X` unless X has a lower `step_order` than that step.

## 8. Extension interfaces

```java
public interface FieldHandler {
    /** value may be null (missing). Return null to mean "missing". */
    JsonNode handle(JsonNode value, ExecutionContext ctx);
}

public interface MessageHandler {
    /** Mutate message headers/body in place. Return a non-null response to short-circuit
        (only honored at the flow request hook; elsewhere it is ignored and a WARN is logged). */
    GatewayResponse handle(MessageView message, ExecutionContext ctx);
}

public interface ErrorHandler {
    GatewayResponse handle(GatewayError error, ExecutionContext ctx);
}
```

- `MessageView` exposes mutable HTTP headers and JSON body for the message at that hook (inbound request, outgoing step request, step response, or final response).
- `ExecutionContext` exposes read access to the context tree (Section 6.1), the flow code, and correlation ID.
- `GatewayResponse` = status, headers, JSON body.
- A handler that throws → `HANDLER_ERROR`.

### Hook points (`MessageHandler`)

| Hook | Configured on | Runs |
|---|---|---|
| Flow request | `gw_flow.request_handler` | after inbound validation, before steps |
| Step request | `gw_flow_step.request_handler` | after STEP_REQUEST mappings, before HTTP call |
| Step response | `gw_flow_step.response_handler` | after HTTP response, before schema validation/storing |
| Flow response | `gw_flow.response_handler` | after outbound validation, before return |

## 9. Error handling

All failures become a `GatewayError { type, stepName, downstreamStatus, downstreamHeaders, downstreamBody, messages, cause }` and go to the flow's `ErrorHandler` (or `defaultErrorHandler` when unset or no flow matched).

| Type | Default status | Default `errorCode` |
|---|---|---|
| `ROUTE_NOT_FOUND` | 404 | `GW-404-ROUTE` |
| `INVALID_JSON` | 400 | `GW-400-JSON` |
| `REQUEST_SCHEMA_INVALID` | 400 | `GW-400-SCHEMA` |
| `MAPPING_ERROR` (source under `$.request.*`) | 400 | `GW-400-MAPPING` |
| `MAPPING_ERROR` (other) | 500 | `GW-500-MAPPING` |
| `DOWNSTREAM_HTTP_ERROR` | 502 | `GW-502-DOWNSTREAM` |
| `DOWNSTREAM_BUSINESS_ERROR` (`success_expr` false) | 422 | `GW-422-BUSINESS` |
| `DOWNSTREAM_CONNECTION` | 502 | `GW-502-CONNECTION` |
| `DOWNSTREAM_INVALID_RESPONSE` | 502 | `GW-502-INVALID-RESPONSE` |
| `DOWNSTREAM_TIMEOUT` | 504 | `GW-504-DOWNSTREAM` |
| `FLOW_TIMEOUT` | 504 | `GW-504-FLOW` |
| `HANDLER_ERROR` | 500 | `GW-500-HANDLER` |
| `RESPONSE_SCHEMA_INVALID` | 500 | `GW-500-RESPONSE-SCHEMA` |
| `INTERNAL` | 500 | `GW-500-INTERNAL` |

Default error body:

```json
{
  "errorCode": "GW-502-DOWNSTREAM",
  "errorMessage": "Downstream call failed",
  "correlationId": "…",
  "step": "inquiry",
  "details": ["…"]
}
```

`step` omitted when not applicable. `details` carries validation messages; it never includes stack traces or unmasked payloads.

### `LookupErrorHandler` (base class for frequent error mapping)

Abstract `ErrorHandler`. A subclass provides:

- `errorCodePath()` — path within the failed step's result, e.g. `$.body.responseCode`
- `lookupCode()` — `gw_lookup_entry.lookup_code` to use

For `DOWNSTREAM_HTTP_ERROR` and `DOWNSTREAM_BUSINESS_ERROR`, it reads the downstream code from the failed step's result (`status`, `headers`, `body`), looks it up, and builds the response from the lookup target value, which is a JSON object `{"status": 422, "errorCode": "INSUFFICIENT_FUNDS", "errorMessage": "Insufficient balance"}`. No match → delegates to `DefaultErrorHandler`. All other error types → `DefaultErrorHandler`.

One example subclass ships: `CoreBankingErrorHandler` (bean `coreBankingErrorHandler`) with demo lookup rows.

**Business errors on HTTP 2xx** (e.g. core returns 200 with `responseCode != "00"`) are detected by the step's `success_expr` and raised as `DOWNSTREAM_BUSINESS_ERROR`, honoring `on_failure`.

### Safety nets

- `ErrorHandler` throws → `DefaultErrorHandler`; that throws → hard-coded 500 JSON with `GW-500-INTERNAL` and correlation ID.
- `CONTINUE` failures never reach the `ErrorHandler`.
- Audit failures never affect the response.
- Error logs include correlation ID; payloads masked.

## 10. Audit

- **Enabled for a request** when `gw_flow.audit_mode = ON`, or `INHERIT` and `gateway.audit.enabled = true`. Unmatched routes are audited when the global switch is on.
- One `gw_audit_transaction` row per request; one `gw_audit_step` row per configured step (including SKIPPED).
- Payloads masked via `Masker`; omitted entirely when `gateway.audit.store-payloads = false`.
- Writes are asynchronous: request thread enqueues into a bounded queue (`queue-capacity`); a background writer batches inserts. Queue full → entry dropped and a WARN logged with the correlation ID. Never blocks or fails the client request.
- On shutdown the writer drains the queue (bounded by a 10s grace period).

## 11. Config loading and reload

- **Startup:** `ConfigLoader` runs after Liquibase migration, loads all config, runs `ConfigValidator`, builds the snapshot. Any validation error → application fails to start, logging the full error list.
- **Reload:** `POST /admin/config/reload` with `X-Admin-Token`. Builds and validates a new snapshot; on success swaps it atomically and returns `200 {"flows": n, "loadedAt": …}`; on validation failure keeps the current snapshot and returns `422 {"errors": [...]}`; if the database is unreachable, keeps the current snapshot and returns `503` (Section 4.1). In-flight requests finish on the snapshot they captured.
- Unauthorized reload → 401.

### Validation rules (`ConfigValidator`)

1. Unique `code` per flow; no two enabled flows with the same method + equivalent path pattern.
2. Referenced schema codes exist and parse as valid draft 2020-12 schemas.
3. Referenced handler bean names exist and implement the right interface.
4. Referenced target systems exist (`gw_target_system` or `gateway.target-systems`); every effective target has an absolute http(s) `base_url` with a host and no query/fragment; timeouts > 0; header names are RFC 7230 tokens and values contain no line breaks; every `${...}` placeholder resolves.
5. Step names unique within a flow; `step_order` ≥ 1.
6. Paths parse; `[*]` counts compatible (Section 6.2).
7. Exactly-one-of `source_path` / `constant_value` (unless `field_handler`); phase/step_id/target_type combinations valid.
8. Converters known with valid arguments; lookup codes exist.
9. Conditions parse and contain only allowed constructs.
10. A step's mappings, `condition_expr`, and `success_expr` may only reference `$.steps.X` where X has a lower `step_order` (its own `success_expr` may also reference itself).
11. Every `{var}` in a step's `path_template` has a PATH mapping for that step.

## 12. Logging and masking

- MDC: `correlationId`, `flowCode`.
- INFO per request (method, path, flow, status, duration) and per step (target, method, URL, status, outcome, duration).
- DEBUG payload logging, masked.
- Masking: any object field whose name matches `gateway.masking.fields` (case-insensitive, any depth) has its value replaced with `gateway.masking.mask`.

## 13. Testing

**Unit (no Spring context):**
- `JsonPath` read/write, wildcards, creation of intermediates.
- `MappingEngine`: each rule feature in Section 6.3, all target types, arrays, passthrough, required.
- Each converter, including bad-argument rejection.
- Lookup with and without `*` fallback.
- `ConditionEvaluator`: correct evaluation; sandbox blocks `T(...)`, `new`, bean refs, method calls.
- `ConfigValidator`: one test per rule in Section 11.
- Step scheduling: ordering, parallel group, skip, STOP cancels siblings, CONTINUE records failure, flow timeout.
- `DefaultErrorHandler`, `LookupErrorHandler`, handler-throws fallback chain.
- `Masker`.

**Integration (`@SpringBootTest`, Testcontainers PostgreSQL, WireMock):**
- Liquibase migrates; seeded config loads.
- The `DataSource` bean is a `HikariDataSource` named `gateway-pool` with the configured sizes; Hikari metrics appear under `/actuator/metrics`.
- Custom schema and table names: with a non-default schema and renamed tables (e.g. `MW_ROUTE`, `MW_STEP`, …), Liquibase creates them in that schema, config loads through JPA, and audit rows land in the renamed audit tables.
- Invalid `gateway.db.*` values (bad characters, too long, duplicates) fail startup (unit test on the validator).
- Reload while the database is unreachable returns 503 and keeps the current config.
- End-to-end over HTTP: single step; multi-step with data passing; parallel group; conditional skip; downstream 4xx/5xx/timeout/connection refused; inbound schema rejection; each handler hook; error mapping via `LookupErrorHandler`.
- Reload: new config live; invalid config rejected with old config retained; unauthorized reload rejected.
- Audit: rows written when on; none when off globally / per flow; payloads masked; `store-payloads=false`.

**Oracle:** the integration suite runs under Maven profile `oracle-it` against Testcontainers Oracle Free. Opt-in; intended before release.

**Demo config:** Liquibase `dev` context seeds three flows — single-step, multi-step with parallel group and condition, and error mapping via `coreBankingErrorHandler` — backed by WireMock stubs under the `dev` profile, runnable locally and callable with `curl`.
