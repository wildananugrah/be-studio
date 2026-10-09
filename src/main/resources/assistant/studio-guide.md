# Gateway Studio user guide

Gateway Studio is the browser UI of the JSON gateway for configuring flows, target systems, lookups and JSON schemas, previewing mappings, and generating unit test documents. This guide describes every screen as it is built.

## Opening Studio

1. Enable it: `gateway.studio.enabled: true` in `application.yml` (env `GATEWAY_STUDIO_ENABLED=true`). The `dev` profile (`make run`) turns it on. When off, `/studio` and `/studio/api/*` return 404. Keep it off in production: it writes straight to the config tables.
2. Open `http://localhost:8080/studio` (port from `make run`, `APP_PORT`).
   `gateway.studio.mode` (env `GATEWAY_STUDIO_MODE`): `edit` (default) or `view-only`. View-only shows a **VIEW ONLY** badge instead of **Save & reload**; the palette and every add/delete control are hidden, fields cannot be changed, and unit-test runs are disabled (the server refuses saves and runs with 403). Browsing, mapping previews, generating test cases, the Rows (SQL) tab and the assistant still work.
3. Enter the admin token (`gateway.admin.token`, env `GATEWAY_ADMIN_TOKEN`; `make run` uses `dev-admin-token`) and click **Open studio**. The token is kept only in that browser tab (sessionStorage). A rejected token sends you back to this screen.

## The header (always visible)

- Logo and **Gateway Studio** title.
- Menu: **Flows**, **Target systems**, **Lookups**, **Schemas**.
- **unsaved changes** (amber) and **Discard** when something was edited. Discard asks for confirmation in a dialog, then reloads from the database.
- **Every delete asks first**: deleting a flow, step, mapping rule, target system or its header, lookup or lookup entry, schema, test case, removing a policy chip, clearing the assistant conversation and resetting an edited sample all open a confirmation dialog naming what goes (and what still uses it). Enter or the red button confirms; Esc, Cancel or a click outside cancels. Deletes only change the draft until Save & reload.
- Host of the gateway (e.g. `localhost:8080`).
- **Ask** opens this assistant.
- **Save & reload** (orange). The badge shows the number of problems. Saving validates everything with the same checks as `POST /admin/config/reload`, replaces the config rows in one transaction and reloads; in-flight requests finish on the previous snapshot. Outcomes appear in a dark toast at the bottom right:
  - **200**: saved and live.
  - **422**: rejected, nothing written; the problems list stays visible.
  - **409**: someone else saved after you loaded. Nothing written. Copy what you need, then Discard to load the latest.
  - **503**: database error, nothing written.
- Leaving the page with unsaved changes asks for confirmation.

Edits are only in the browser until **Save & reload**. Validation runs on the server while you type (about every 350 ms), so problems show up live.

## Flows screen

A table of every flow (`gw_flow`): ENDPOINT (method badge + `/api` + path), CODE and name, STEPS count, ERROR HANDLER, AUDIT mode, ENABLED toggle. A red number next to the endpoint is the count of problems in that flow.

- Click a row to open the flow.
- The **ENABLED** toggle switches the flow on or off (saved with Save & reload). Disabled flows are not routed.
- **+ New flow** creates `NEW_FLOW_n` (`GET /v1/new-n`) and opens it.
- Problems that belong to no flow (e.g. a broken schema) are listed under the table.

## One flow

The bar under the header shows: **Flows /** breadcrumb, method badge, full path, flow code, and the tabs **Pipeline**, **Mapping**, **Tests**, **Rows (SQL)**.

### Pipeline tab

Left: the **palette**. Middle: the **pipeline**, left to right. Right: the **inspector** for whatever is selected.

The pipeline, left to right:
1. **FLOW** card (top): name, timeout, audit, enabled. Click it to edit flow fields.
2. **CLIENT REQUEST** (dark): method and path; click it to edit them (they are also in the Flow inspector).
3. **Inbound** card: request schema and request handler chips. Click to edit.
4. Step columns, one per `step_order`. Steps in the same column run **in parallel** ("parallel · 2"). Each step card shows method, name, target system + path template, chips (conditions, timeouts, schemas, handlers, codec), request-rule count and STOP/CONTINUE. A red **!** means the step has a problem.
5. **Client response** card: number of FLOW_RESPONSE rules, **Edit response mapping →**, chips (response schema, response handler, non-200 success status).
6. **CLIENT** (dark): success status + X-Correlation-Id.
7. **On failure** card (below): the error handler and, for lookup-based handlers, the lookup rows (code → status → errorCode).

Bottom left: either "Flow valid · ready to save" or a red panel listing the flow's problems; click a problem to select the card it belongs to.

**Palette (drag onto the pipeline):**
- *Inbound*: "Validate · SCHEMA" (request_schema_code), request handler beans (JAVA badge).
- *File storage*: "Store file · STORAGE" for each storage (Storages screen = gw_storage rows, plus gateway.storages in application.yml; default LOCAL_FILES = ./data/files from application.yml; S3_FILES is a disabled row in the dev data). Creates a **file storage step** (STORE FILE badge): Operation PUT (store the uploaded file) or DELETE (delete the file at the key); **Uploaded file (form field)** = the multipart field (creates BODY `$.file ← $.request.files.<field>`; `body` for a raw upload such as Content-Type application/pdf); **Object key** = path_template like `/{yyyy}/{MM}/{uuid}-{filename}` with built-ins {uuid} {correlationId} {filename} {name} {ext} {field} {yyyy} {MM} {dd} {HH} {mm} {ss} or PATH rules; **Key variables** lists them, with **+ Add rule for {x}** for missing ones. Result `$.steps.<name>.body` = {storage, key, location, filename, contentType, size, sha256, etag?} → map in Client response (e.g. `$.fileId ← $.steps.store.body.key`). Storage allowed-types / max-size → 415 GW-415-FILE / 413 GW-413-FILE; failures 502 GW-502-STORAGE. Uploads: multipart text fields are `$.request.body`, files `$.request.files.<field>` = {field, filename, contentType, size, sha256}; form-urlencoded fields are `$.request.body`; gateway.files.max-size (20MB) limits uploads (413). The Tests tab sends JSON only (no files): test uploads with curl, http/gateway.http or Swagger UI. Details: CONFIGURATION-GUIDE §4.18.
- *Database query*: "Query DATASOURCE" for each datasource of `gateway.sql.datasources` (default `GATEWAY_DB` = the gateway's own database, read-only). Drops like a call and creates a **database query step** (SQL badge on the card). Its inspector: Name, **Datasource**, **SQL** (editor with syntax highlighting; one statement; Tab indents), **Parameters** (each `:name` in the SQL and the rule that sets it, red when missing; **+ Add rule for :x** creates BODY rules `$.x ← $.request.path.x` (path variable of the same name) or `$.request.query.x` (GET/DELETE) or `$.request.body.x`, with `TO_NUMBER` for id/limit/offset-like names), then order, on failure, Run only if, Success when (e.g. `${steps.query.body.rowCount} > 0` → 422 when no row), timeout (query timeout), response schema, handlers, enabled. The result is `$.steps.<name>.body` = `{rows: [...], rowCount, truncated}` or `{updated: n}` for INSERT/UPDATE/DELETE; map it in Client response (`$.user ← $.steps.query.body.rows[0]`, `$.items[*].x ← $.steps.query.body.rows[*].col`). Values are bound, never pasted into the SQL. Read-only datasources accept only SELECT/WITH. Errors: 504 timeout, 502 GW-502-CONNECTION unreachable, 502 GW-502-DATABASE anything else (details only in log/audit). Path/query values are text: numeric columns need TO_NUMBER. Unquoted column names come back lower case; quoted aliases keep their case. Details: CONFIGURATION-GUIDE §4.17.
- *Downstream call*: "Call TARGET" for each target system. Drop on the dashed "New step_order n (runs after)" zone, on "Insert as step_order n (runs before)", or on "Run in parallel at step_order n" under an existing column.
- *Step policy* (drop on a step card): Run only if… (condition_expr), Success check (success_expr), Continue on failure, Step timeout 3s, Validate · SCHEMA (response_schema_code), request handler beans, body codec beans (wire format).
- *Client response*: Validate · SCHEMA (response schema), Respond 201 Created, response handler beans.
- *Errors*: every ErrorHandler bean (e.g. "Map codes via CORE_BANKING_ERRORS").
- *Flow*: Audit on, Audit off, Flow timeout 8s.
You can also drag an existing step card to another column to change its order. A chip's × removes that setting.

**Inspector fields:**
- Flow: Code, Name, Flow timeout (ms) (empty = `gateway.default-flow-timeout-ms`), Audit (INHERIT/ON/OFF), Enabled; **Delete flow**.
- Inbound: Method, Path (relative to `/api`; `{name}` becomes `$.request.path.name`), Request schema (+ **+ New schema** / **Edit SCHEMA →**), Request handler.
- Downstream step: Name (later rules read it as `$.steps.<name>`), Target system, Method, Path (appended to the base URL; each `{var}` needs a PATH rule), Order (same number = parallel), If it fails (STOP / CONTINUE), Run only if, Success when, Timeout (ms) (empty = target read timeout), Response schema, Request handler, Response handler, Wire format (body codec), Enabled; **Edit request mapping →**, **Delete step**.
- Client response: Success status, Response schema, Response handler; **Edit response mapping →**.
- On failure: Error handler.

### Mapping tab

Edit the mapping rules of one step (STEP_REQUEST: what that call sends) or of the client response (FLOW_RESPONSE: what the client gets).

- Top: scope buttons, one per step ("1 · beneficiary") plus **Client response**.
- Left: **CONTEXT** tree of sample data (`$.request…`, `$.steps.<earlier steps>…`, `$.correlationId`). It is built from the flow's path variables, its request schema (every property shows under body), the fields existing rules read and earlier step results; a new flow without rules or schema shows only empty headers/path/query/body. The **+** next to headers / path / query / body adds a field the client sends: type its name (body: `customer.name`, `items[*].sku`), Enter → a rule reading it is created in the current scope (a step: same HEADER / QUERY / PATH / BODY; the client response or a SQL step: BODY `$.<name>`); a path variable is also appended to the flow path as `/{name}`. Alternatively set a request schema on the Inbound card, or edit SAMPLE CONTEXT (kept in the tab only); **CONVERTERS** (TO_STRING, TO_NUMBER, TO_BOOLEAN, TRIM, UPPER, LOWER, PAD_LEFT:12:0, PAD_RIGHT, SUBSTRING:0:6, DATE_FORMAT:yyyyMMdd:dd/MM/yyyy, DECIMAL_SCALE:2); **LOOKUPS**; **FIELD HANDLERS**.
- Middle: the rules, top to bottom. Each rule: number, target type (BODY/HEADER/QUERY/PATH for steps; BODY/HEADER for the response), target path, ← source (or a constant when no source), lookup / converter / field-handler chips, default value, **required** toggle, the live result, ×.
  - Drag a context field onto a rule to set its source; onto "Drop a context field here to add a rule" to add one. Drag a converter, lookup or field handler onto a rule to attach it.
  - **Converter editor**: click a converter chip on a rule (it shows e.g. "DATE yyyyMMdd → dd/MM/yyyy ✎"), or drop a converter on a rule, to open it under the rule: pick the converter type, fill its arguments in plain fields (DATE_FORMAT: from format and to format, each with a "Common formats…" list: yyyyMMdd, ddMMyyyy, MMddyyyy, yyyy-MM-dd, dd/MM/yyyy, MM/dd/yyyy, dd-MM-yyyy, dd MMM yyyy, yyMMdd, yyyyMMddHHmmss, yyyy-MM-dd HH:mm:ss, yyyy-MM-dd'T'HH:mm:ss, …, plus a legend: yyyy year, MM month, dd day, HH hour, mm minute, ss second, MMM Oct; PAD_LEFT/PAD_RIGHT: total length and pad character with quick 0 / space / * / - buttons; SUBSTRING: from position (0 = first) and to position (exclusive, empty = to the end); DECIMAL_SCALE: decimal places). **Try it** runs the real server converter on a sample value and shows the result or the error. **Spec** shows/edits the raw text stored in `gw_mapping_rule.converter`; colons inside arguments are escaped (`\:`) automatically. **Done** closes it.
  - For a database query step the Mapping tab is **Parameters of <step>**: only BODY rules; target `$.id` is bound to `:id`. The live preview shows `SQL · DATASOURCE`, each `:param` with its bound value, and the SQL (the query is not run by the preview).
  - **+ Constant rule** adds a rule with a constant value.
  - Rules run: value → default → lookup → converter → field handler → required check → write.
- Right (dark): **LIVE PREVIEW**. The server runs the real rules (real converters, lookups, field handlers) on the sample data and shows the request line (method, resolved base URL, path, query) or `HTTP/1.1 <status>`, headers and body, plus mapping errors. **SAMPLE CONTEXT** shows the generated sample; edit it (kept in the tab only) or **Regenerate from rules**.

### Tests tab (unit test documents)

1. Save & reload first: cases are generated from, and run against, the **live** configuration (buttons are disabled while there are unsaved changes).
2. **Generate from Swagger** builds cases from the flow's operation in the OpenAPI description: "Happy path - all fields", "Happy path - required fields only", "Missing required field 'x'" (expect 400), missing required query/header, and "Wrong type for 'x'" (expect 400, only with a request schema).
3. Edit any case: checkbox (include in run), name, **expect** status, **Edit request** (description, method, path, query/headers JSON, body JSON). **+ Custom case** adds one.
4. **Run N cases** sends each case through the gateway over HTTP with its own `X-Correlation-Id` (format `UT-…`). Real downstream systems are called.
5. Each case shows got/expected and **PASS** / **FAIL** / **RECORDED** (no expected status). **Evidence** shows: 1 Incoming request (client → gateway), 2 Outgoing request (gateway → downstream), 3 Incoming response (downstream → gateway), 4 Outgoing response (gateway → client), 5 Audit trail, 6 Logs.
6. **Last run** bar: tick **audit trail** / **logs**, then **Download** **Word** (.docx), **PDF** or **Markdown**.
- "AUDIT TRAIL ON/OFF" chip: the audit trail is recorded only if the flow is audited: `gateway.audit.enabled=true` (env `GATEWAY_AUDIT_ENABLED`) with audit_mode INHERIT, or audit_mode ON.
- Masked fields (`gateway.masking.fields`) are masked in every message, audit row and log line. The last 20 runs are kept in memory.

### Rows (SQL) tab

The INSERT statements for this flow (gw_flow, gw_flow_step, gw_mapping_rule, plus the lookups its rules use), with counts and **Copy SQL**. Use it to promote a flow to another environment as a Liquibase changeset; Save & reload already writes it to this gateway's database.

## Target systems screen

Cards for `gw_target_system` rows (database targets win over `gateway.target-systems` in application.yml):
- Code (rename updates the steps that use it), "N steps", enabled toggle.
- **BASE_URL**: `scheme://host:port[/base]`, no query. Use `${ENV_VAR:default}` placeholders for per-environment addresses and secrets; the line below shows the resolved value (placeholders resolve on the server).
- CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, BODY_CODEC (JSON default, xmlCodec, soapCodec, soap12Codec, custom).
- **TLS** (`gw_target_system.tls_*`): an **HTTP**/**HTTPS** badge from the resolved base URL (the scheme of BASE_URL decides; TLS settings only apply to https://) and a mode select:
  - **VERIFY** (default): the JVM's trusted CAs and host name check.
  - **INSECURE**: skip certificate and host name checks (red warning; dev/test only).
  - **CUSTOM**: **TRUST STORE** (server CA or self-signed certificate; replaces the JVM CAs for this target) and/or **KEY STORE** (client certificate + private key for mutual TLS), each with a password field. A store is PEM text, or a path on the gateway server to .pem/.crt, .p12/.pfx or .jks; `${ENV_VAR}` placeholders keep keys and passwords out of the database. Stores are re-read on every Save & reload; a missing file or wrong password is a save problem (422).
- **FIXED HEADERS** (`gw_target_system_header`): name/value, **+ Header**.
- **Delete target**. **+ New target system** adds `NEW_SYSTEM_n`.
- Read-only cards below: targets from application config (`gateway.target-systems`).

## Lookups screen

Cards per `lookup_code` (`gw_lookup_entry`): SOURCE_VALUE → TARGET_VALUE rows, **+ Row**, **+ Fallback \*** (`*` matches anything else), **Delete lookup**, rename on blur. Targets are parsed as JSON when valid (error handlers use objects like `{"status":422,"errorCode":"...","errorMessage":"..."}`), otherwise used as text. "N rules" shows usage.

## Schemas screen

Cards per JSON schema (`gw_json_schema`, draft 2020-12): code (rename updates references), "used by …", description, schema text, **Format**, **Generate from example JSON…** (paste an example; every non-null field becomes required, types from the values), **Delete schema**. **+ New schema** adds a template. An invalid schema shows the server's meta-schema error on the card and blocks saving. Invalid inbound body → 400 `GW-400-SCHEMA`; invalid client response → 500.

## Storages screen

Where file storage steps put uploads (table `gw_storage`); changes apply on Save & reload, no restart. Cards per database storage: code (rename updates the steps), **LOCAL | S3** switch, steps count, enabled toggle.
- LOCAL: **BASE_DIR** (directory on the gateway host, created by the first upload).
- S3: **BUCKET**, **KEY_PREFIX**, **REGION**, **ENDPOINT** (only for S3-compatible servers such as MinIO, e.g. http://minio:9000), **Path-style addressing** toggle (MinIO), **ACCESS_KEY**, **SECRET_KEY** (empty = default AWS credentials). A literal key shows "Stored in the database as plain text: prefer ${ENV_VAR}"; placeholders show "→ from the environment".
- **ALLOWED_TYPES** (comma-separated, image/* style; else 415) and **MAX_SIZE** (10MB; else 413).
- **Test connection**: checks the directory (writable / creatable) or the bucket (HeadBucket with these credentials), unsaved settings too; writes nothing. ✓ or ✗ with the reason (wrong bucket, credentials, endpoint).
- **Delete storage** (asks first). **+ Local storage** / **+ S3 storage** add one (S3 starts with ${CODE_BUCKET} etc. placeholders).
- Below, read-only: storages from application.yml (gateway.storages) with **Override in database** (copies it into gw_storage; the row then wins). A disabled row hides the application.yml storage of the same code.
- Problems of a storage (unresolvable placeholder, missing bucket/base dir, bad max size) show on its card and block the save.
- To use S3: fill in S3_FILES (or + S3 storage) → Test connection → enable → Flows → the upload step → Storage = S3_FILES → Save & reload.

## Audit trail screen

Menu **Audit trail** (also **Audit trail →** in a flow's header, pre-filtered to that flow). Read-only, also in view-only mode. Shows `gw_audit_transaction` rows, newest first, 50 per page (**← Newer** / **Older →**).
- Filters: flow, status (2xx / 4xx / 5xx / All errors), search (exact correlation ID, or part of the path or error code), **from** / **to** time (server time zone), **Refresh**, **auto-refresh 5s**.
- Each row: client status, method, path, duration, start time, flow code, error code, correlation ID.
- Click a row for its timeline: **1 · Client → gateway** (request body), one block per step (`gw_audit_step`): outcome (SUCCESS / FAILED / TIMEOUT / SKIPPED), downstream HTTP status, target, duration, method + URL, **Sent (after mapping)** = the body the step's STEP_REQUEST rules produced, **Received** = the downstream answer; then **Gateway → client** (status + response body) and **Logs** (every log line with that correlation ID). **Open flow X →** jumps to the flow; **copy** copies the correlation ID.
- Use it to verify a flow: compare "Sent (after mapping)" with what the downstream expects, and the client response with the mapping rules.
- Calls appear only when audited: `gateway.audit.enabled=true` with audit_mode INHERIT, or audit_mode ON. Payloads need `gateway.audit.store-payloads=true` (masked with `gateway.masking.fields`). Logs are kept in memory per correlation ID on this instance (`gateway.studio.log-buffer.max-transactions` 2000, `max-lines` 300), so older calls or calls from another instance show no logs: search the log output for the correlation ID.
- Unit test runs (Tests tab) are audited too: search their `UT-…` correlation IDs.

## Common tasks

- **Add a new endpoint**: Flows → + New flow → set Code, Name, Method and Path in the inspector → drag "Call TARGET" onto the pipeline → select the step, set Path and Method → Edit request mapping (drag context fields; a PATH rule for every `{var}`) → Client response → Edit response mapping → Save & reload → Tests tab → Generate → Run.
- **Endpoint that reads a database table**: Flows → + New flow → Method GET, Path `/v1/things/{id}` → drag "Query GATEWAY_DB" (or another datasource) onto the pipeline → write the SQL in the inspector (`SELECT id, name FROM my_table WHERE id = :id`) → **+ Add rule for :id** (path variable, TO_NUMBER) → Success when `${steps.query.body.rowCount} > 0` → Client response → map `$.steps.query.body.rows[0].name` etc. → Save & reload. For writes, the datasource needs `read-only: false` in application.yml.
- **Endpoint that accepts a file upload**: Flows → + New flow → Method POST, Path `/v1/attachments` → drag "Store file · LOCAL_FILES" (or S3_FILES) onto the pipeline → set Uploaded file (form field), e.g. `file`, and the Object key, e.g. `/attachments/{yyyy}/{uuid}-{filename}` → Client response: map `$.fileId ← $.steps.store.body.key`, `$.size ← $.steps.store.body.size` → Save & reload → `curl -F "file=@x.pdf" localhost:8080/api/v1/attachments`.
- **Change a date or number format**: Mapping tab → click the converter chip on the rule (or drop DATE_FORMAT / PAD_LEFT / SUBSTRING / DECIMAL_SCALE on it) → set the formats/arguments → check **Try it** → Done → Save & reload.
- **Check that a flow transforms correctly**: call it (Tests tab, `make demo`, curl), open **Audit trail** (or the flow's **Audit trail →**), click the call, and compare each step's **Sent (after mapping)** and the **Gateway → client** body with what you expect; the **Logs** show the steps' status and timing.
- **Point a downstream system at a new IP/port**: Target systems → edit BASE_URL → Save & reload.
- **Call a downstream over HTTPS with a private CA or client certificate**: Target systems → BASE_URL `https://…` → TLS: CUSTOM → trust store path (e.g. `/etc/gateway/tls/ca.pem`) and/or key store (e.g. `/etc/gateway/tls/client.p12` + password `${CLIENT_P12_PASSWORD}`) → Save & reload. For a dev server with a self-signed certificate: TLS: INSECURE.
- **Map downstream error codes**: Lookups → add rows to e.g. `CORE_BANKING_ERRORS` (`51` → `{"status":422,"errorCode":"INSUFFICIENT_FUNDS","errorMessage":"..."}`) → set the flow's error handler (On failure card) → Save & reload.
- **Validate a request body**: Schemas → + New schema (or Inbound inspector → + New schema) → Inbound → Request schema → Save & reload.
- **Call two systems in parallel**: give both steps the same Order (or drop the second call on "Run in parallel at step_order n").
