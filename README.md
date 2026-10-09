# JSON Gateway

A Spring Boot service that receives JSON requests, transforms them, calls one or more downstream services (JSON, plain XML or SOAP), and transforms the results into a JSON response. Routing, mappings, orchestration and validation are **configured in database tables** and loaded at startup. Logic that config can't express goes into small custom Java classes.

Design spec: [`docs/superpowers/specs/2026-10-08-json-gateway-design.md`](docs/superpowers/specs/2026-10-08-json-gateway-design.md)

## Running locally

Requirements: JDK 21 and Docker. The quickest way is the Makefile. See **[docs/MAKEFILE.md](docs/MAKEFILE.md)** for every target.

```bash
make up      # PostgreSQL + WireMock via docker-compose.yml
make run     # app on :8080 with the demo flows
make demo    # call every demo flow (second terminal)
make down
```

**API documentation:** open **http://localhost:8080/docs** (Swagger UI) after setting `gateway.docs.enabled: true` in `application.yml`, or with `GATEWAY_DOCS_ENABLED=true make run`. It lists every enabled flow with its parameters and bodies, generated from the database configuration, and updates after `make reload`. The raw OpenAPI 3.1 description is at `/docs/openapi.json` (import it into Postman or a client generator).

**Gateway Studio:** open **http://localhost:8080/studio** and enter the admin token (`dev-admin-token` with `make run`). It is a browser editor for flows (drag-and-drop pipeline, mapping rules with a live preview run by the server), target systems, lookups and JSON schemas (draft 2020-12; write one, generate it from an example JSON document, or start one from a flow's sample data with **+ New schema** under a schema field). Edits stay in the page until **Save & reload**, which validates the whole configuration with the same checks as a reload, replaces the config rows in one transaction and reloads; an invalid config is rejected with 422 and nothing is written, and a save based on an out-of-date copy is rejected with 409. The **Rows (SQL)** tab gives the INSERTs for a flow, to commit as a Liquibase changeset for other environments. It is on with the `dev` profile and off otherwise (`gateway.studio.enabled`).

**Unit test documents:** a flow's **Tests** tab in Gateway Studio generates test cases from the flow's operation in the API description (the Swagger one): a happy path, required fields only, one per missing required field or parameter and, when the flow has a request schema, one per field sent with the wrong type. Edit them or add your own, then **Run**: each case is sent through this gateway over HTTP with its own `X-Correlation-Id`, so the downstream systems are really called. Per case the evidence is the incoming request (client to gateway), the outgoing requests (gateway to downstream), the incoming responses (downstream to gateway), the outgoing response (gateway to client), the audit rows and the log lines of that correlation ID, with `gateway.masking.fields` masked. Download it as Word (.docx), PDF or Markdown. The audit trail is only there when the flow is audited: `gateway.audit.enabled=true` (`GATEWAY_AUDIT_ENABLED`) with `audit_mode` INHERIT, or `audit_mode` ON. Tests run against the live (saved) configuration; the last 20 runs are kept in memory for download.

**Project assistant:** the **Ask** button in Gateway Studio opens a chat that answers questions about this project: setup, running, building flows in Studio, custom classes, testing, configuration and troubleshooting, with step-by-step instructions and links that jump to the right Studio screen. It knows the project documentation (this README, `docs/`, the Makefile, `http/gateway.http`, a Studio user guide) and the gateway's live configuration, including the flow you have open. It calls Claude through the Anthropic Messages API, configured in a git-ignored `.env` file (copy `.env.example`): `AI_BASE_URL` (optional, default `https://api.anthropic.com`; any endpoint that serves the Anthropic Messages API), `AI_AUTH_KEY`, `AI_MODEL` (default `claude-opus-5-5`), optional `AI_AUTH_TYPE` (`api-key` = `x-api-key` header, `bearer` = `Authorization: Bearer`; `auto` picks `api-key` for Anthropic `sk-ant-api` keys, Bearer otherwise) and optional `AI_EFFORT`. For an OpenRouter key use `AI_BASE_URL=https://openrouter.ai/api` and an OpenRouter model id such as `anthropic/claude-sonnet-5.5`. Environment variables override the file; restart after changing it. The key stays on the server; the browser only talks to `/studio/api/assistant` with the admin token. Switch the assistant off with `gateway.assistant.enabled: false` in `application.yml` (env `GATEWAY_ASSISTANT_ENABLED=false`): the Ask button and its API disappear and nothing calls the AI endpoint; the rest of Studio keeps working.

To try requests one by one from your IDE, open **[`http/gateway.http`](http/gateway.http)** (IntelliJ HTTP Client or the VS Code REST Client extension). Every request there says what it should return.

Without make, `./mvnw spring-boot:test-run` starts a throwaway PostgreSQL in Docker, stubs the downstream systems with WireMock, and seeds three demo flows (profile `dev`):

```bash
curl localhost:8080/api/v1/accounts/1001
curl localhost:8080/api/v1/accounts/1001/transactions?limit=5
curl -X POST localhost:8080/api/v1/transfers -H 'Content-Type: application/json' \
     -d '{"fromAccount":"1001","toAccount":"2002","amount":1000}'
curl -X POST localhost:8080/api/v1/transfers -H 'Content-Type: application/json' \
     -d '{"fromAccount":"1001","toAccount":"2002","amount":999999999}'   # 422 INSUFFICIENT_FUNDS
curl -X POST localhost:8080/admin/config/reload -H 'X-Admin-Token: dev-admin-token'
```

Against your own PostgreSQL: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev` with `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `CORE_BANKING_URL` and `NOTIFICATION_URL` set.

## Tests

```bash
./mvnw test               # unit + integration tests (PostgreSQL in Docker)
./mvnw test -Poracle-it   # same suite against Oracle Free in Docker (large image, slow first run)
```

## Adding an integration

**Full guide with a step-by-step tutorial, reference and recipes: [docs/CONFIGURATION-GUIDE.md](docs/CONFIGURATION-GUIDE.md).** Writing Java handlers: **[docs/CUSTOM-CLASSES.md](docs/CUSTOM-CLASSES.md)**.

An integration is a set of rows. All tables are in the default schema unless `gateway.db.schema` is set, and all table names are configurable.

| Table | One row per | Key columns |
|---|---|---|
| `gw_flow` | inbound endpoint | `code`, `http_method`, `path_pattern` (e.g. `/v1/accounts/{accountNo}`), optional `request_schema_code` / `response_schema_code`, `request_handler` / `response_handler` / `error_handler`, `success_status`, `timeout_ms`, `audit_mode` (INHERIT/ON/OFF) |
| `gw_flow_step` | downstream call | `name`, `step_order` (**same order = run in parallel**), `target_system`, `http_method`, `path_template` (e.g. `/core/accounts/{acc}`), `condition_expr`, `success_expr`, `on_failure` (STOP/CONTINUE), `timeout_ms`, handlers |
| `gw_mapping_rule` | field | `phase` (STEP_REQUEST/FLOW_RESPONSE), `step_id`, `seq`, `target_type` (BODY/HEADER/QUERY/PATH), `target_path`, `source_path` **or** `constant_value`, `default_value`, `converter`, `lookup_code`, `field_handler`, `required` |
| `gw_lookup_entry` | value translation | `lookup_code`, `source_value` (`*` = fallback), `target_value` |
| `gw_json_schema` | JSON Schema (2020-12) | `code`, `schema_text` |

Then call `POST /admin/config/reload` with header `X-Admin-Token`, or restart the app. Invalid config is rejected with the complete list of errors, and the current config stays live.

### What mappings can read

```
$.request.headers.<lower-case-name>   $.request.path.<var>   $.request.query.<name>   $.request.body...
$.steps.<name>.outcome   (SUCCESS / FAILED / SKIPPED / TIMEOUT)
$.steps.<name>.status    $.steps.<name>.headers.<lower-case-name>   $.steps.<name>.body...
$.correlationId
```

A step can only read steps with a lower `step_order`. Paths support `.name`, `['odd name']`, `[0]` and `[*]`. With `[*]`, arrays are mapped element by element, e.g. `$.steps.history.body.items[*].amt` → `$.transactions[*].amount`. `target_path = $` copies a whole object.

Rule order: value → `default_value` (if missing/null) → `lookup_code` → `converter` → `field_handler` → `required` check → write.

### Converters

`TO_STRING`, `TO_NUMBER`, `TO_BOOLEAN`, `TRIM`, `UPPER`, `LOWER`, `PAD_LEFT:12:0`, `PAD_RIGHT:20: `, `SUBSTRING:0:6`, `DATE_FORMAT:yyyy-MM-dd:ddMMyyyy`, `DECIMAL_SCALE:2`. Use `\:` for a literal colon, as in `DATE_FORMAT:HH\:mm:HHmm`. Decimal values keep their exact scale (`12500.50` stays `12500.50`).

### Conditions and success checks

`condition_expr` decides whether a step runs. `success_expr` decides whether a 2xx response is really a success; if it is false, the error type is `DOWNSTREAM_BUSINESS_ERROR`.

```
${steps.inquiry.body.status} == 'ACTIVE' and ${request.body.amount} > 0
${steps.debit.body.responseCode} == '00'
```

Only comparisons, `and`/`or`/`not`, arithmetic, `?:` and `matches` are allowed. Method calls, type references, constructors and bean references are rejected when the config is loaded.

## Custom classes

Implement one of these as a Spring bean in `com.mhamzah.gateway.extension.custom` (or in any jar on the classpath), then put the **bean name** in the config column. For an API over your own table (no downstream call), see the `tbl_ms_user` CRUD example: flows without steps whose `request_handler` answers directly ([`docs/CUSTOM-CLASSES.md`](docs/CUSTOM-CLASSES.md#47-msuserhandlers-a-crud-api-over-your-own-table-tbl_ms_user)).

| Interface | Column | Use for |
|---|---|---|
| `FieldHandler` | `gw_mapping_rule.field_handler` | one field: value in, value out (masking, check digits, special formats) |
| `MessageHandler` | flow/step `request_handler` / `response_handler` | whole message: HTTP headers + JSON body (signatures, tokens, restructuring). A flow `request_handler` can return a response to short-circuit the flow. |
| `ErrorHandler` | `gw_flow.error_handler` | turning failures into the client error response |
| `BodyCodec` | `gw_target_system.body_codec` / `gw_flow_step.body_codec` | the wire format of downstream calls. Built in: `xmlCodec`, `soapCodec`, `soap12Codec`; write one for SOAP headers / WS-Security / signed XML ([guide](docs/CONFIGURATION-GUIDE.md#415-xml-or-soap-downstream)) |

**Error mapping** is the common case, so it has a base class. Extend `LookupErrorHandler`, say where the downstream code is and which lookup table translates it, and put the mappings in `gw_lookup_entry`:

```java
@Component("coreBankingErrorHandler")
public class CoreBankingErrorHandler extends LookupErrorHandler {
    protected String errorCodePath() { return "$.body.responseCode"; }
    protected String lookupCode()    { return "CORE_BANKING_ERRORS"; }
}
```

```
lookup_code=CORE_BANKING_ERRORS  source_value=51
target_value={"status":422,"errorCode":"INSUFFICIENT_FUNDS","errorMessage":"Insufficient balance"}
```

Unmapped codes and other error types fall back to the standard error body:

```json
{ "errorCode": "GW-502-DOWNSTREAM", "errorMessage": "Downstream call failed", "correlationId": "...", "step": "debit", "details": [] }
```

### Error responses with their own shape

`FLOW_RESPONSE` rules only build **successful** responses. When a step with `on_failure = STOP` fails, the flow's `error_handler` builds the response instead, so an error response can look completely different from a success response. The handler receives the downstream status, headers and body of the failed call in `GatewayError`.

| You need | Use |
|---|---|
| Translate the downstream error code to your own status / code / message | `LookupErrorHandler` plus `gw_lookup_entry` rows (above). New codes need only new rows. |
| The same translation, but a different body layout or fields copied from the downstream error body | `LookupErrorHandler` and override `buildBody(...)` |
| Full control, e.g. HTTP 200 with a response code for every failure | Implement `ErrorHandler` (see `PartnerErrorHandler`) |
| A failed call should not fail the request | `on_failure = CONTINUE`. `FLOW_RESPONSE` rules can then read `$.steps.<name>.outcome` and `$.steps.<name>.body`. |

```java
@Component("cardSystemErrorHandler")
public class CardSystemErrorHandler extends LookupErrorHandler {
    @Override protected String errorCodePath() { return "$.body.error.code"; }
    @Override protected String lookupCode()    { return "CARD_ERRORS"; }

    @Override
    protected ObjectNode buildBody(String errorCode, String errorMessage, JsonNode downstreamCode,
            GatewayError error, ExecutionContext ctx) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("responseCode", errorCode);
        body.put("responseMessage", error.downstreamBody().path("error").path("desc").asString(errorMessage));
        body.put("referenceNo", ctx.correlationId());
        return body;
    }
}
```

Details: [CONFIGURATION-GUIDE §3.9 and §5.3](docs/CONFIGURATION-GUIDE.md#39-error-responses), [CUSTOM-CLASSES §4.4–4.5](docs/CUSTOM-CLASSES.md#44-partnererrorhandler-errorhandler-written-from-scratch).

## Logging

Every inbound call is logged in a standard format (`com.mhamzah.gateway.logging`): one `REQUEST` line and one `RESPONSE` line. Both carry the correlation ID through the log pattern (`logging.pattern.correlation`); the timestamp, thread and logger are left out below.

```
[abc-123] REQUEST method=[GET] path=[/api/v1/accounts/1001/transactions] headers=[{..., X-Correlation-Id=abc-123}] parameters=[{limit=5}]
[abc-123] RESPONSE method=[GET] path=[/api/v1/accounts/1001/transactions] responseHeaders=[{X-Correlation-Id=abc-123, Content-Type=application/json}] responseBody=[{...}]
```

- `parameters` and `body` / `responseBody` appear only when present. Parameters come from the query string only, so a form-encoded body is never consumed.
- Headers, query parameters and JSON bodies are masked with `gateway.masking.fields`. A body that is not JSON is logged as it is.
- The correlation ID is the client's `X-Correlation-Id` when safe (up to 64 characters of `A-Z a-z 0-9 . _ : -`), otherwise a new UUID. The same ID is sent downstream, returned in the `X-Correlation-Id` response header, and written to the audit trail.
- `/actuator/**` calls are not logged.
- Besides these lines, the gateway logs one summary line per request (`GET /v1/... flow=... status=... 12ms`) and one per downstream call (`step=... target=... status=... outcome=...`).

## Configuration (`application.yml`)

| Setting | Purpose |
|---|---|
| `gateway.target-systems.<NAME>.base-url / connect-timeout-ms / read-timeout-ms / static-headers / tls.*` | Optional: downstream systems from application config. Usually they're rows in `gw_target_system` (IP/port reloadable); a DB row with the same code wins. See [recipe 4.13](docs/CONFIGURATION-GUIDE.md#413-downstream-systems-add-one-change-its-ip--port). |
| HTTPS downstream (`gw_target_system.tls_mode`, `tls_trust_store`, `tls_key_store`, passwords) | `http://` or `https://` comes from `base_url`. For HTTPS per target: `VERIFY` (default, JVM CAs), `INSECURE` (no certificate checks, dev/test only) or `CUSTOM` (own CA trust store and/or client certificate for mutual TLS; PEM, PKCS12 or JKS, by path or `${ENV}`). Editable in Studio's **Target systems** > **TLS**. See [recipe 4.16](docs/CONFIGURATION-GUIDE.md#416-https-downstream-certificates-and-keys). |
| `gateway.audit.enabled`, `store-payloads`, `queue-capacity`, `batch-size` | DB audit trail. `enabled` is the global default; each flow can override it with `audit_mode`. |
| `gateway.masking.fields` (`GATEWAY_MASKING_FIELDS`) | Field/header/query parameter names masked in audit payloads and in the request/response logs, case-insensitive. Default `pin,password,cardNo,cvv,authorization,x-admin-token`. The environment variable is comma-separated and **replaces** the whole list, so repeat the defaults you still want: `GATEWAY_MASKING_FIELDS=pin,password,cardNo,cvv,authorization,x-admin-token,nik`. |
| `gateway.db.schema`, `gateway.db.tables.*`, `gateway.db.liquibase-tables.*` | Schema and table names. Letters, digits and `_` only; tables ≤ 25 characters. |
| `spring.datasource.hikari.*` (`DB_POOL_MAX`, `DB_POOL_MIN_IDLE`, …) | HikariCP pool `gateway-pool`. Metrics are at `/actuator/metrics/hikaricp.*`. |
| `GATEWAY_ADMIN_TOKEN` | Required for `/admin/config/reload`. When blank, reload is disabled. |
| `gateway.docs.enabled` (`GATEWAY_DOCS_ENABLED`), `gateway.docs.title` (`GATEWAY_DOCS_TITLE`) | Swagger UI at `/docs` and OpenAPI at `/docs/openapi.json`. **Off by default**; when off, both return 404. Only `application.yml` (or the environment variable) sets it; no profile overrides it. The description lists every endpoint, so enable it in production only on purpose. |
| `gateway.studio.enabled` (`GATEWAY_STUDIO_ENABLED`) | Gateway Studio at `/studio` and its API at `/studio/api/*` (admin token required). **Off by default**, on in the `dev` profile; when off, both return 404. It writes straight to the config tables, so keep it off in production, where DBAs apply changes as Liquibase changesets. |

**Oracle:** activate profile `oracle` and set `DB_URL` (e.g. `jdbc:oracle:thin:@//host:1521/SERVICE`), `DB_USERNAME` and `DB_PASSWORD`.

**DBA-managed schemas:** set `LIQUIBASE_ENABLED=false`, and generate the DDL for review. The output is written to `target/liquibase/update.sql`:

```bash
# without a database connection (offline):
./mvnw liquibase:updateSQL -Dliquibase.url=offline:oracle -Dtbl.flow=MW_ROUTE   # or offline:postgresql
# against the target database:
./mvnw liquibase:updateSQL -Dliquibase.url=... -Dliquibase.username=... -Dliquibase.password=... \
    -Dliquibase.defaultSchemaName=GATEWAY -Dtbl.flow=MW_ROUTE
```

Override any table name with `-Dtbl.<logical_name>=...` (`flow`, `flow_step`, `mapping_rule`, `lookup_entry`, `json_schema`, `audit_transaction`, `audit_step`). Choose table names before the first deployment: Liquibase checksums include them, so renaming later needs a migration.
