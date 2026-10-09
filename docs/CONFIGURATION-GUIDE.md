# Configuration Guide

How to build integrations by configuring the gateway in the database.

- [1. How it works](#1-how-it-works)
- [2. Tutorial: build a flow from scratch](#2-tutorial-build-a-flow-from-scratch)
- [3. Reference](#3-reference)
- [4. Recipes](#4-recipes)
- [5. Custom classes (Java)](#5-custom-classes-java)
- [6. Validation errors and how to fix them](#6-validation-errors-and-how-to-fix-them)
- [7. Working practices](#7-working-practices)

The examples use the local stack from [MAKEFILE.md](MAKEFILE.md) (`make run`), with PostgreSQL, the WireMock downstreams and the demo data. Every output shown below was produced by the running application.

---

## 1. How it works

```
client ──► GET /api/v2/accounts/1001/overview
              │
              ▼
        ┌─ gw_flow ──────────────── matched by http_method + path_pattern
        │    │
        │    ├─ request schema check (optional)
        │    │
        │    ├─ gw_flow_step, step_order 1 ─┬─ "account" ──► CORE_BANKING  (in parallel)
        │    │                              └─ "history" ──► CORE_BANKING
        │    ├─ gw_flow_step, step_order 2 ─── ...           (after group 1 finishes)
        │    │      each step: STEP_REQUEST mapping rules build what is sent
        │    │
        │    └─ FLOW_RESPONSE mapping rules build what the client gets
        │
client ◄── 200 {"account": {...}, "recent": [...]}
```

Seven tables hold the configuration:

| Table | Holds | You need it for |
|---|---|---|
| `gw_flow` | one row per inbound endpoint | every integration |
| `gw_flow_step` | one row per downstream call | every call to another system |
| `gw_mapping_rule` | one row per field to write | building step requests and the client response |
| `gw_lookup_entry` | code translation tables | translating codes, e.g. `A` → `ACTIVE`, and error mapping |
| `gw_json_schema` | JSON Schemas | validating requests and responses |
| `gw_target_system` | one row per downstream system: base URL (IP/host + port), timeouts | where the steps send their calls |
| `gw_target_system_header` | fixed headers per downstream system | e.g. `X-Channel-Id`, API keys (as `${ENV}` placeholders) |

**When changes take effect:** config is read at startup. After changing rows, call `make reload` (`POST /admin/config/reload` with header `X-Admin-Token`). The app validates the whole configuration first:
- If it is valid, it is switched in atomically, and requests already in progress finish on the old version.
- If it is invalid, you get a `422` listing every problem, and the old configuration keeps running.

**Downstream addresses.** A step names a target system, such as `CORE_BANKING`. Its base URL (IP/host and port), timeouts and fixed headers come from `gw_target_system`, and can be changed with a reload. A system can also be defined in `application.yml`; the database row wins when both exist. See [Recipe 4.13](#413-downstream-systems-add-one-change-its-ip--port).

---

## 2. Tutorial: build a flow from scratch

We'll build `GET /api/v2/accounts/{accountNo}/overview`. It calls core banking twice **in parallel**, once for account details and once for recent history, and merges the results:

```json
{
  "account": { "number": "1001", "holder": "BUDI SANTOSO", "status": "ACTIVE", "balance": 1500000.00 },
  "recent": [ { "date": "01/10/2026", "amount": 150000.00, "direction": "CREDIT" }, ... ],
  "historyStatus": "SUCCESS",
  "requestedBy": "teller-07"
}
```

The downstream (core banking) responses look like this:

```
GET /core/accounts/1001             → {"acctNo":"1001","acctName":"BUDI SANTOSO","availBal":"1500000.00","ccy":"IDR","statusCd":"A"}
GET /core/accounts/1001/history?limit=3 → {"items":[{"trxDt":"20261001","amt":"150000.00","dc":"C"}, ...]}
```

The complete script is in [`examples/account-overview.sql`](examples/account-overview.sql). Run the snippets below one by one in `make psql`, or run the whole file:
`docker compose exec -T postgres psql -U gateway -d gateway < docs/examples/account-overview.sql`

### Step 1: plan on paper

| Question | Answer |
|---|---|
| Inbound endpoint? | `GET /v2/accounts/{accountNo}/overview` (the `/api` prefix is added automatically) |
| Which calls? | `account`: `GET /core/accounts/{acc}`; `history`: `GET /core/accounts/{acc}/history?limit=3` |
| Order? | Neither needs the other's result, so both get `step_order = 1` and run in parallel. |
| If one fails? | The account is essential (`STOP`); history is nice-to-have (`CONTINUE`). |
| Response fields? | Rename and restructure the core fields, translate the codes, format dates. |

### Step 2: the flow row

```sql
INSERT INTO gw_flow (code, name, http_method, path_pattern)
VALUES ('ACCOUNT_OVERVIEW', 'Account overview', 'GET', '/v2/accounts/{accountNo}/overview');
```

Everything else takes its default: status 200, no schema, default error handler, audit as configured globally, enabled.

### Step 3: the step rows

```sql
INSERT INTO gw_flow_step (flow_id, name, step_order, target_system, http_method, path_template)
SELECT id, 'account', 1, 'CORE_BANKING', 'GET', '/core/accounts/{acc}'
FROM gw_flow WHERE code = 'ACCOUNT_OVERVIEW';

INSERT INTO gw_flow_step (flow_id, name, step_order, target_system, http_method, path_template, on_failure)
SELECT id, 'history', 1, 'CORE_BANKING', 'GET', '/core/accounts/{acc}/history', 'CONTINUE'
FROM gw_flow WHERE code = 'ACCOUNT_OVERVIEW';
```

The step `name` matters: later rules read a step's result as `$.steps.<name>...`.

### Step 4: what each call sends (`STEP_REQUEST` rules)

Each `{acc}` in a `path_template` needs a `PATH` rule. The `account` call also forwards a channel header, with a default when the client sends none.

Below, each rule is a separate INSERT so it's easy to read. The script [`examples/account-overview.sql`](examples/account-overview.sql) inserts the same rules in **one** statement per phase, using a `VALUES` list with a `step` column that picks the step by name. It also marks the `PATH` rules `required`. Use whichever style you prefer.

```sql
-- account: {acc} ← the accountNo path variable of the inbound URL
INSERT INTO gw_mapping_rule (flow_id, step_id, phase, seq, target_type, target_path, source_path)
SELECT f.id, s.id, 'STEP_REQUEST', 1, 'PATH', 'acc', '$.request.path.accountNo'
FROM gw_flow f JOIN gw_flow_step s ON s.flow_id = f.id
WHERE f.code = 'ACCOUNT_OVERVIEW' AND s.name = 'account';

-- account: header X-Channel ← inbound header x-channel, or "MOBILE"
INSERT INTO gw_mapping_rule (flow_id, step_id, phase, seq, target_type, target_path, source_path, default_value)
SELECT f.id, s.id, 'STEP_REQUEST', 2, 'HEADER', 'X-Channel', '$.request.headers.x-channel', 'MOBILE'
FROM gw_flow f JOIN gw_flow_step s ON s.flow_id = f.id
WHERE f.code = 'ACCOUNT_OVERVIEW' AND s.name = 'account';

-- history: {acc} and a fixed query parameter ?limit=3
INSERT INTO gw_mapping_rule (flow_id, step_id, phase, seq, target_type, target_path, source_path)
SELECT f.id, s.id, 'STEP_REQUEST', 1, 'PATH', 'acc', '$.request.path.accountNo'
FROM gw_flow f JOIN gw_flow_step s ON s.flow_id = f.id
WHERE f.code = 'ACCOUNT_OVERVIEW' AND s.name = 'history';

INSERT INTO gw_mapping_rule (flow_id, step_id, phase, seq, target_type, target_path, constant_value)
SELECT f.id, s.id, 'STEP_REQUEST', 2, 'QUERY', 'limit', '3'
FROM gw_flow f JOIN gw_flow_step s ON s.flow_id = f.id
WHERE f.code = 'ACCOUNT_OVERVIEW' AND s.name = 'history';
```

### Step 5: what the client gets (`FLOW_RESPONSE` rules)

Response rules have **no** `step_id`. They read the step results from `$.steps.<name>.body`:

| seq | target | source | extra |
|---|---|---|---|
| 1 | `$.account.number` | `$.steps.account.body.acctNo` | |
| 2 | `$.account.holder` | `$.steps.account.body.acctName` | |
| 3 | `$.account.status` | `$.steps.account.body.statusCd` | lookup `ACCOUNT_STATUS` (A→ACTIVE) |
| 4 | `$.account.balance` | `$.steps.account.body.availBal` | converter `DECIMAL_SCALE:2` (text → number) |
| 5 | `$.recent[*].date` | `$.steps.history.body.items[*].trxDt` | converter `DATE_FORMAT:yyyyMMdd:dd/MM/yyyy` |
| 6 | `$.recent[*].amount` | `$.steps.history.body.items[*].amt` | converter `TO_NUMBER` |
| 7 | `$.recent[*].direction` | `$.steps.history.body.items[*].dc` | lookup `DEBIT_CREDIT` |
| 8 | `$.historyStatus` | `$.steps.history.outcome` | SUCCESS / FAILED / … |
| 9 | `$.requestedBy` | `$.request.headers.x-user-id` | default `anonymous` |
| 10 | header `X-Data-Source` | | constant `CORE_BANKING` |

One row as an example; the full set is in the script:

```sql
INSERT INTO gw_mapping_rule (flow_id, phase, seq, target_type, target_path, source_path, lookup_code)
SELECT id, 'FLOW_RESPONSE', 3, 'BODY', '$.account.status', '$.steps.account.body.statusCd', 'ACCOUNT_STATUS'
FROM gw_flow WHERE code = 'ACCOUNT_OVERVIEW';
```

`[*]` maps arrays element by element. Rules 5–7 each fill one field of every element of `recent`.

### Step 6: reload and call it

```bash
$ make reload
{"flows":4,"loadedAt":"2026-10-08T07:52:00.805924Z"}

$ curl -i localhost:8080/api/v2/accounts/1001/overview -H 'X-User-Id: teller-07'
HTTP/1.1 200
X-Data-Source: CORE_BANKING
{"account":{"number":"1001","holder":"BUDI SANTOSO","status":"ACTIVE","balance":1500000.00},
 "recent":[{"date":"01/10/2026","amount":150000.00,"direction":"CREDIT"},
           {"date":"03/10/2026","amount":25000.50,"direction":"DEBIT"}],
 "historyStatus":"SUCCESS","requestedBy":"teller-07"}
```

Account `2002` has no history in the stub, so its history call fails (HTTP 404). Because that step is `CONTINUE`, the flow still succeeds. `recent` is simply absent and `historyStatus` says what happened:

```bash
$ curl localhost:8080/api/v2/accounts/2002/overview
{"account":{"number":"2002","holder":"SITI AMINAH","status":"ACTIVE","balance":250000.00},
 "historyStatus":"FAILED","requestedBy":"anonymous"}
```

**See it in the API docs.** With `gateway.docs.enabled: true` in `application.yml` (or `GATEWAY_DOCS_ENABLED=true make run`), the new endpoint is on **http://localhost:8080/docs** right after the reload. The docs show:
- the path parameters from `path_pattern`;
- the query parameters and headers that your rules read, marked required when a `required` rule reads them;
- the request body from `request_schema_code`, or, without a schema, the `$.request.body...` fields your rules read;
- the response body from `response_schema_code`, or the fields your `FLOW_RESPONSE` rules write.

Add a JSON schema if you want types and descriptions in the docs, not just field names.

### Step 7: change something

Change the date format, reload, and the next request uses the new rule:

```sql
UPDATE gw_mapping_rule SET converter = 'DATE_FORMAT:yyyyMMdd:yyyy-MM-dd' WHERE target_path = '$.recent[*].date';
```

```bash
$ make reload && curl -s localhost:8080/api/v2/accounts/1001/overview
... "recent":[{"date":"2026-10-01",...},{"date":"2026-10-03",...}] ...
```

### Step 8: what a mistake looks like

Suppose three mistakes are made at once: a converter with a missing argument, a condition that reads a step which hasn't run yet, and an unknown target system. The reload is rejected and lists all three; the previous config keeps serving traffic:

```bash
$ make reload
{"errors":[
  "mapping rule 36 of flow 'ACCOUNT_OVERVIEW': converter 'PAD_LEFT:x': PAD_LEFT expects 2 argument(s), got 1",
  "flow 'ACCOUNT_OVERVIEW' step 'account' condition_expr: references step 'history' which does not run before it",
  "flow 'ACCOUNT_OVERVIEW' step 'history': target_system 'CORE' is not configured (gw_target_system or gateway.target-systems)"]}
HTTP 422

$ curl -o /dev/null -w '%{http_code}' localhost:8080/api/v2/accounts/1001/overview
200
```

At **startup**, the same errors stop the application from starting, so a broken config never serves traffic.

### Step 9: remove it

Steps and rules are deleted with their flow:

```sql
DELETE FROM gw_flow WHERE code = 'ACCOUNT_OVERVIEW';
```

Then run `make reload`. To switch a flow off without deleting it, use `UPDATE gw_flow SET enabled = false WHERE code = '...'`.

---

## 3. Reference

### 3.1 `gw_flow`

| Column | Required | Default | Meaning |
|---|---|---|---|
| `code` | ✔ | | Unique ID of the flow (appears in logs and audit) |
| `name` | | | Description |
| `http_method` | ✔ | | `GET`, `POST`, `PUT`, `PATCH`, `DELETE` |
| `path_pattern` | ✔ | | Starts with `/`, relative to `/api`. `{name}` captures a segment into `$.request.path.name`. When several patterns match, the most specific wins (`/accounts/me` before `/accounts/{id}`). Two flows with the same method and pattern are rejected. |
| `request_schema_code` | | | `gw_json_schema.code` that validates the request body → `400 GW-400-SCHEMA` |
| `response_schema_code` | | | Validates the final response body → `500 GW-500-RESPONSE-SCHEMA` |
| `request_handler` | | | `MessageHandler` bean run before the steps; can short-circuit ([§5.2](#52-messagehandler--headers--body)) |
| `response_handler` | | | `MessageHandler` bean run on the final response |
| `error_handler` | | `defaultErrorHandler` | `ErrorHandler` bean that builds error responses ([§5.3](#53-errorhandler--error-responses)) |
| `success_status` | | `200` | HTTP status of a successful response |
| `timeout_ms` | | `gateway.default-flow-timeout-ms` (30000) | Limit for the whole flow → `504 GW-504-FLOW` |
| `audit_mode` | | `INHERIT` | `INHERIT` (follow `gateway.audit.enabled`), `ON`, `OFF` |
| `enabled` | | `true` | `false` = the route does not exist (404) |

### 3.2 `gw_flow_step`

| Column | Required | Default | Meaning |
|---|---|---|---|
| `flow_id` | ✔ | | Owning flow |
| `name` | ✔ | | Letters, digits, `_`, `-`; unique within the flow. Results are at `$.steps.<name>`. |
| `step_order` | ✔ | | ≥ 1. Groups run in ascending order; **steps with the same number run in parallel**. |
| `target_system` | ✔ | | `gw_target_system.code` (or a key under `gateway.target-systems` in `application.yml`). For a database query step: a datasource of `gateway.sql.datasources` ([§4.17](#417-query-a-database)). |
| `http_method` | ✔ (HTTP steps) | | `GET`, `POST`, `PUT`, `PATCH`, `DELETE`. Empty for a database query step. |
| `path_template` | ✔ (HTTP steps) | | Starts with `/`, appended to the target's `base-url`. Every `{var}` needs a `PATH` rule. Empty for a database query step. |
| *(file storage)* | | | When `target_system` names a storage of `gateway.storages`, the step is a **file storage step**: `http_method` `PUT` (store) or `DELETE`, `path_template` the object key ([§4.18](#418-file-uploads-local-storage-and-s3)). |
| `sql_text` | | | Makes this a **database query step**: one SQL statement with `:name` parameters, run on the `target_system` datasource instead of an HTTP call ([§4.17](#417-query-a-database)). |
| `condition_expr` | | | Run only when true; otherwise the outcome is `SKIPPED` ([§3.6](#36-expressions-condition_expr-success_expr)) |
| `success_expr` | | | Checked after a 2xx response; false → `DOWNSTREAM_BUSINESS_ERROR` |
| `on_failure` | | `STOP` | `STOP`: end the flow with an error. `CONTINUE`: record the failure and carry on. |
| `timeout_ms` | | target `read-timeout-ms`, else `gateway.default-step-timeout-ms` (10000) | Read timeout → `504 GW-504-DOWNSTREAM` |
| `response_schema_code` | | | Validates the downstream response body → `502 GW-502-INVALID-RESPONSE` |
| `request_handler` | | | `MessageHandler` run after the request is mapped, before sending |
| `response_handler` | | | `MessageHandler` run on a 2xx response (already decoded to JSON), before schema check and storing |
| `body_codec` | | target's `body_codec`, else `jsonCodec` | Wire format of this call: `jsonCodec`, `xmlCodec`, `soapCodec`, `soap12Codec` or a custom `BodyCodec` bean ([§4.15](#415-xml-or-soap-downstream)). Wins over the target system's. |
| `enabled` | | `true` | `false` = the step and its rules are ignored |

### 3.3 `gw_mapping_rule`

| Column | Required | Meaning |
|---|---|---|
| `flow_id` | ✔ | Owning flow |
| `step_id` | for `STEP_REQUEST` | Step whose request this builds. Must be NULL for `FLOW_RESPONSE`. |
| `phase` | ✔ | `STEP_REQUEST` (what a step sends) or `FLOW_RESPONSE` (what the client receives) |
| `seq` | ✔ | Order of application within the step/response. A later rule writing the same target overwrites. |
| `target_type` | default `BODY` | `BODY`, `HEADER`, `QUERY`, `PATH`. `FLOW_RESPONSE` allows only `BODY` and `HEADER`. |
| `target_path` | ✔ | `BODY`: a path like `$.a.b`. Otherwise the plain header / query parameter / path variable name. |
| `source_path` | one of | Where the value comes from ([§3.3.1](#331-what-a-rule-can-read)) |
| `constant_value` | one of | A fixed value ([§3.3.3](#333-literal-values)) |
| `default_value` | | Used when the source is missing or null |
| `lookup_code` | | Translate the value through `gw_lookup_entry` |
| `converter` | | Format/convert the value ([§3.3.5](#335-converters)) |
| `field_handler` | | `FieldHandler` bean for custom logic ([§5.1](#51-fieldhandler--one-field)) |
| `required` | default `false` | Missing after default/lookup/converter/handler → error (instead of omitting the field) |

Set exactly one of `source_path` / `constant_value`. The exception is a rule with a `field_handler`, which may have neither.

#### 3.3.1 What a rule can read

```
$.request.headers.<name>       inbound HTTP header, name in lower case     $.request.headers.x-user-id
$.request.path.<var>           {var} captured by path_pattern               $.request.path.accountNo
$.request.query.<name>         query parameter (first value)                $.request.query.limit
$.request.body...              inbound JSON body                            $.request.body.customer.name
$.steps.<step>.outcome         SUCCESS | FAILED | SKIPPED | TIMEOUT
$.steps.<step>.status          downstream HTTP status (absent if no response)
$.steps.<step>.headers.<name>  downstream response header, lower case
$.steps.<step>.body...         downstream JSON body ({} if empty)
$.steps.<step>.errorType       error type when the step failed
$.correlationId                request correlation ID
```

**A step may only read steps with a lower `step_order`.** The response may read any step. A skipped or failed step can still be read; check `outcome` first if needed.

#### 3.3.2 Path syntax

| Syntax | Example | Meaning |
|---|---|---|
| `$` | `$` | the root (as a target: merge an object into the root) |
| `.name` | `$.customer.name` | object field (letters, digits, `_`, `-`) |
| `['name']` | `$['first name']` | object field with other characters |
| `[n]` | `$.items[0].id` | array element |
| `[*]` | `$.items[*].id` | every array element |

Writing creates missing objects and arrays automatically: `$.data.account.no` produces `{"data":{"account":{"no":...}}}`.

#### 3.3.3 Literal values

`constant_value`, `default_value` and lookup `target_value` are **parsed as JSON when valid, otherwise used as text**:

| You write | You get |
|---|---|
| `MOBILE` | `"MOBILE"` (text) |
| `00` | `"00"` (text: not valid JSON) |
| `123` | `123` (number) |
| `"123"` | `"123"` (text: quoted JSON string) |
| `12.50` | `12.50` (number, exact) |
| `true` | `true` (boolean) |
| `{"a":1}` | an object |

#### 3.3.4 Rule evaluation order

For each rule, in `seq` order:

1. **value**: `constant_value`, or read `source_path`
2. **default**: if the value is missing or `null`, use `default_value`
3. **lookup**: if `lookup_code` is set, translate
4. **converter**: if `converter` is set, convert
5. **field handler**: if `field_handler` is set, call it
6. **required**: if the value is still missing, raise an error when `required`, otherwise **skip the rule** (the field is omitted)
7. **write** the value to the target

`HEADER`, `QUERY` and `PATH` values are written as text. **Mark `PATH` rules `required`**: a missing path variable otherwise becomes an empty URL segment.

**Arrays.** With `[*]` in the source, steps 2–6 run for every element:
- **Target also has `[*]`** (the same number of them): elements map by position. `items[*].price` → `detail[*].harga`.
- **Target has no `[*]`**: the values are collected into one array. `items[*].price` → `$.prices` gives `[10,20]`.
- An empty source array produces an empty target array. A missing source array writes nothing (or raises an error if the rule is `required`).

**Copy a whole object.** `target_path = $` with an object source merges every field into the target. Rules with a higher `seq` can then override single fields.

#### 3.3.5 Converters

| Converter | Example | Input → output |
|---|---|---|
| `TO_STRING` | `TO_STRING` | `12` → `"12"` |
| `TO_NUMBER` | `TO_NUMBER` | `"12.50"` → `12.50`, `"7"` → `7` |
| `TO_BOOLEAN` | `TO_BOOLEAN` | `"Y"`/`"1"`/`"true"`/`"yes"` → `true`, `"N"`/`"0"`/`"false"`/`"no"` → `false` |
| `TRIM` | `TRIM` | `"  x "` → `"x"` |
| `UPPER` / `LOWER` | `UPPER` | `"ab"` → `"AB"` |
| `PAD_LEFT:len:char` | `PAD_LEFT:12:0` | `12500` → `"000000012500"` |
| `PAD_RIGHT:len:char` | `PAD_RIGHT:5: ` | `"ab"` → `"ab   "` |
| `SUBSTRING:begin[:end]` | `SUBSTRING:0:6` | `"abcdefgh"` → `"abcdef"` (out-of-range is clamped) |
| `DATE_FORMAT:in:out` | `DATE_FORMAT:yyyyMMdd:dd/MM/yyyy` | `"20261001"` → `"01/10/2026"` |
| `DECIMAL_SCALE:n` | `DECIMAL_SCALE:2` | `"10.005"` → `10.01`, `"10"` → `10.00` (half-up) |

In Gateway Studio, click a converter on a rule (or drop one onto it) to open the **converter editor**: pick the converter, fill in its arguments (date formats have a list of common ones: `ddMMyyyy`, `MMddyyyy`, `yyyy-MM-dd`, `dd/MM/yyyy`, …), and check the result in **Try it**, which runs the real converter on a sample value. It writes the spec, colons escaped, for you.

Write `\:` for a literal colon inside an argument: `DATE_FORMAT:yyyy-MM-dd'T'HH\:mm:HHmm`. Date patterns use Java `DateTimeFormatter` syntax. A value that can't be converted, such as `TO_NUMBER` of `"abc"`, raises a mapping error. Decimal numbers are always kept exact (`12500.50` never becomes `12500.5`).

### 3.4 `gw_lookup_entry`

| Column | Meaning |
|---|---|
| `lookup_code` | Table name, referenced by `gw_mapping_rule.lookup_code` (or a `LookupErrorHandler`) |
| `source_value` | Value to match, compared as text (number `51` matches `'51'`). `*` = fallback for anything not listed. |
| `target_value` | Replacement, parsed as a [literal](#333-literal-values) |

If nothing matches and there is no `*` row, the value passes through unchanged.

```sql
INSERT INTO gw_lookup_entry (lookup_code, source_value, target_value) VALUES ('ACCOUNT_STATUS', 'A', 'ACTIVE');
INSERT INTO gw_lookup_entry (lookup_code, source_value, target_value) VALUES ('ACCOUNT_STATUS', '*', 'UNKNOWN');
```

### 3.5 `gw_json_schema`

| Column | Meaning |
|---|---|
| `code` | Referenced by `request_schema_code` / `response_schema_code` |
| `schema_text` | JSON Schema, **draft 2020-12**. Checked when config is loaded; an invalid schema is a config error. |
| `description` | Free text |

Validation messages are returned to the client in `details`, e.g. `"$.amount: must have an exclusive minimum value of 0"`.

### 3.6 Expressions (`condition_expr`, `success_expr`)

Write `${path}` to read from the context; the path is written without the leading `$.`:

```
${steps.inquiry.body.status} == 'ACTIVE'
${request.body.amount} > 0 and ${request.body.currency} == 'IDR'
${steps.debit.body.responseCode} == '00'
not ${request.body.vip} or ${steps.check.status} == 200
${request.body.account} matches '[0-9]{10}'
${request.body.note} == null
${request.headers['x-channel']} != 'ATM'
```

| Allowed | Not allowed (rejected at load) |
|---|---|
| `== != < > <= >=`, `and or not`, `+ - * / %`, `? :`, `?:`, `matches`, literals `'text'` `12` `1.5` `true` `null` | method calls (`'a'.length()`), `T(...)`, `new`, `@bean`, property access on values (`${x}.size`), lists `{1,2}`, assignment |

Values arrive as text, numbers, booleans, or `null` when missing. **Numeric comparisons need JSON numbers.** `"1500000.00"` (in quotes) is text, and `> 100` on it fails at runtime. Compare text with text, or ask the provider for numbers.

- `condition_expr` can read steps from **earlier** groups.
- `success_expr` can also read **its own step**. It is checked after a 2xx response is stored, so `${steps.<self>.body...}` works.

### 3.7 What a step sends

- **URL**: target `base-url` + `path_template`, with `{var}` replaced (URL-encoded) by `PATH` rules, plus `?name=value` from `QUERY` rules.
- **Headers**:
  - The target system's fixed headers (`gw_target_system_header`, or `static-headers` in application.yml).
  - Then `HEADER` rules (these win).
  - Then the step `request_handler`.
  - Always added: `X-Correlation-Id`, plus `Accept` and (when a body is sent) `Content-Type` of the step's body codec unless already set: `application/json` by default, `application/xml` for `xmlCodec`, `text/xml` for `soapCodec`.
- **Body**: the JSON built by `BODY` rules (`{}` if there are none), encoded by the step's body codec (sent as is for JSON, converted for XML / SOAP). It is never sent for `GET`. For `DELETE` it is sent only when not empty.

### 3.8 How a step ends

| Situation | outcome | Error type (with `on_failure=STOP`) |
|---|---|---|
| 2xx, body empty or JSON, schema OK, `success_expr` true or unset | `SUCCESS` | |
| `condition_expr` false | `SKIPPED` | |
| Non-2xx response | `FAILED` | `DOWNSTREAM_HTTP_ERROR` |
| 2xx but body is not JSON (or cannot be decoded by the step's body codec), or fails `response_schema_code` | `FAILED` | `DOWNSTREAM_INVALID_RESPONSE` |
| Request body cannot be encoded by the body codec (e.g. XML with two root fields) | `FAILED` | `MAPPING_ERROR` |
| `success_expr` false | `FAILED` | `DOWNSTREAM_BUSINESS_ERROR` |
| Read timeout | `TIMEOUT` | `DOWNSTREAM_TIMEOUT` |
| Cannot connect | `FAILED` | `DOWNSTREAM_CONNECTION` |
| Mapping / handler failed | `FAILED` | `MAPPING_ERROR` / `HANDLER_ERROR` |

The result (`outcome`, `status`, `headers`, `body`) is stored in `$.steps.<name>` for **every** outcome. A failed step's body holds the downstream error payload, which later rules and error handlers can read. When a `STOP` step fails inside a parallel group, the other steps of that group are cancelled.

### 3.9 Error responses

Without a custom `error_handler`, errors look like this:

```json
{ "errorCode": "GW-502-DOWNSTREAM", "errorMessage": "Downstream call failed",
  "correlationId": "…", "step": "debit", "details": ["…"] }
```

`step` and `details` appear only when relevant.

| Error type | Status | errorCode |
|---|---|---|
| `ROUTE_NOT_FOUND` | 404 | `GW-404-ROUTE` |
| `INVALID_JSON` | 400 | `GW-400-JSON` |
| `REQUEST_SCHEMA_INVALID` | 400 | `GW-400-SCHEMA` |
| `MAPPING_ERROR` from a `$.request.*` source (the client's fault) | 400 | `GW-400-MAPPING` |
| `MAPPING_ERROR` otherwise | 500 | `GW-500-MAPPING` |
| `DOWNSTREAM_HTTP_ERROR` | 502 | `GW-502-DOWNSTREAM` |
| `DOWNSTREAM_BUSINESS_ERROR` | 422 | `GW-422-BUSINESS` |
| `DOWNSTREAM_CONNECTION` | 502 | `GW-502-CONNECTION` |
| `DOWNSTREAM_INVALID_RESPONSE` | 502 | `GW-502-INVALID-RESPONSE` |
| `DOWNSTREAM_TIMEOUT` | 504 | `GW-504-DOWNSTREAM` |
| `FLOW_TIMEOUT` | 504 | `GW-504-FLOW` |
| `HANDLER_ERROR` | 500 | `GW-500-HANDLER` |
| `RESPONSE_SCHEMA_INVALID` | 500 | `GW-500-RESPONSE-SCHEMA` |
| `INTERNAL` | 500 | `GW-500-INTERNAL` |

Every response, error or not, carries the `X-Correlation-Id` header. The ID is taken from the request when it is safe (up to 64 characters of `A-Z a-z 0-9 . _ : -`), otherwise generated.

### 3.10 Audit

When audit is on for a flow, every request writes one `gw_audit_transaction` row, plus one `gw_audit_step` row per step, including skipped and cancelled ones. Payloads are stored with sensitive fields masked (`gateway.masking.fields`). Writing is asynchronous and never slows down or fails a request.

```sql
SELECT correlation_id, flow_code, client_status, error_code, duration_ms
FROM gw_audit_transaction ORDER BY id DESC FETCH FIRST 10 ROWS ONLY;
```

### 3.11 `gw_target_system` / `gw_target_system_header`

**`gw_target_system`**: one row per downstream system.

| Column | Required | Default | Meaning |
|---|---|---|---|
| `code` | ✔ | | Name used in `gw_flow_step.target_system`, e.g. `CORE_BANKING` |
| `base_url` | ✔ | | `http(s)://host-or-ip:port[/base-path]`, no query string. May contain `${ENV_VAR}` / `${ENV_VAR:default}`. |
| `connect_timeout_ms` | | 3000 | TCP connect timeout |
| `read_timeout_ms` | | `gateway.default-step-timeout-ms` (10000) | Default read timeout for steps calling this system (a step's own `timeout_ms` wins) |
| `body_codec` | | `jsonCodec` | Wire format for every step calling this system: `xmlCodec`, `soapCodec`, `soap12Codec` or a custom `BodyCodec` bean ([§4.15](#415-xml-or-soap-downstream)). A step's own `body_codec` wins. |
| `enabled` | | `true` | `false` = row ignored (an `application.yml` definition with the same code is used, if any) |
| `tls_mode` | | `VERIFY` | Only for `https://` URLs ([§4.16](#416-https-downstream-certificates-and-keys)): `VERIFY` (JVM CAs + host name check), `INSECURE` (no checks, dev/test only) or `CUSTOM` (the stores below) |
| `tls_trust_store` | | | `CUSTOM`: the CA / self-signed certificate(s) to trust instead of the JVM CAs. PEM text, or a path to `.pem`/`.crt`, `.p12`/`.pfx` or `.jks`; `${...}` allowed |
| `tls_trust_store_password` | | | Password of a `.p12`/`.jks` trust store |
| `tls_key_store` | | | `CUSTOM`: client certificate + private key for mutual TLS. PEM (chain + key), or `.p12`/`.pfx`/`.jks` |
| `tls_key_store_password` | | | Password of the key store, or of an encrypted PEM key |

**`gw_target_system_header`**: fixed headers sent on every call to that system. A step's `HEADER` rules and `request_handler` can still override them.

| Column | Meaning |
|---|---|
| `target_code` | `gw_target_system.code`; deleting the system deletes its headers |
| `header_name` | e.g. `X-Channel-Id`, `X-Api-Key` |
| `header_value` | Text, or a `${...}` placeholder so secrets stay out of the database |

`${...}` placeholders are filled in when config is loaded or reloaded, from environment variables or application properties. An unknown placeholder without a default is a config error.

> **Access control.** Whoever can write these tables decides where the gateway sends traffic, and which environment values (`${...}`) go into outgoing headers. Grant write access to the config tables only to the people who administer the gateway.

---

## 4. Recipes

Each recipe shows only the rows that matter. The tutorial pattern `SELECT id ... FROM gw_flow WHERE code = '...'` fills in `flow_id` / `step_id`.

### 4.1 POST endpoint with request validation

```sql
INSERT INTO gw_json_schema (code, schema_text) VALUES ('PAYMENT_REQUEST',
 '{"type":"object","required":["account","amount"],
   "properties":{"account":{"type":"string","pattern":"^[0-9]{10}$"},
                 "amount":{"type":"number","exclusiveMinimum":0}}}');

INSERT INTO gw_flow (code, name, http_method, path_pattern, request_schema_code, success_status)
VALUES ('PAYMENT', 'Payment', 'POST', '/v1/payments', 'PAYMENT_REQUEST', 201);
```

### 4.2 Rename, nest and flatten fields

| target_path | source_path |
|---|---|
| `$.nama_nasabah` | `$.request.body.customer.name` (nested → flat) |
| `$.data.account.no` | `$.request.body.accountNo` (flat → nested) |
| `$.customer` | `$.request.body.profile` (whole sub-object) |

### 4.3 Copy everything, then adjust

| seq | target_path | source_path / constant | |
|---|---|---|---|
| 1 | `$` | `$.steps.core.body` | copy every field of the core response |
| 2 | `$.status` | `$.steps.core.body.statusCd` + lookup | override one field |
| 3 | `$.source` | constant `CORE` | add a field |

### 4.4 Move values between headers, path, query and body

| target_type | target_path | source_path | Effect |
|---|---|---|---|
| `BODY` | `$.userId` | `$.request.headers.x-user-id` | header → body |
| `HEADER` | `X-Account` | `$.request.body.account` | body → header |
| `QUERY` | `account` | `$.request.path.accountNo` | path → query |
| `BODY` | `$.lang` | `$.request.query.lang` + default `id` | query → body |
| `HEADER` (`FLOW_RESPONSE`) | `X-Ref` | `$.steps.debit.body.refNo` | downstream body → client response header |

### 4.5 Fixed values and defaults

| target_path | source_path | constant_value | default_value |
|---|---|---|---|
| `$.channel` | | `MOBILE` | |
| `$.version` | | `2` (number) | |
| `$.branch` | | `"001"` (text with quotes; plain `001` would also stay text) | |
| `$.remark` | `$.request.body.note` | | `TRANSFER` |

### 4.6 Format amounts and dates

| target_path | source_path | converter |
|---|---|---|
| `$.amount` | `$.request.body.amount` | `PAD_LEFT:15:0` → `"000000000001000"` |
| `$.trxDate` | `$.request.body.date` | `DATE_FORMAT:yyyy-MM-dd:yyyyMMdd` |
| `$.balance` | `$.steps.core.body.availBal` | `DECIMAL_SCALE:2` → `1500000.00` |
| `$.flag` | `$.steps.core.body.activeFlag` | `TO_BOOLEAN` (`"Y"` → `true`) |

Converters apply **after** the default and lookup, so `default_value` `0` with `PAD_LEFT:15:0` yields `"000000000000000"`.

### 4.7 Translate codes

```sql
INSERT INTO gw_lookup_entry (lookup_code, source_value, target_value) VALUES ('DEBIT_CREDIT', 'D', 'DEBIT');
INSERT INTO gw_lookup_entry (lookup_code, source_value, target_value) VALUES ('DEBIT_CREDIT', 'C', 'CREDIT');
```

Then set `lookup_code = 'DEBIT_CREDIT'` on the rule.

### 4.8 Sequential calls: use one call's result in the next

```
step "inquiry"  step_order 1
step "debit"    step_order 2
rule (STEP_REQUEST of debit): $.beneficiaryName ← $.steps.inquiry.body.acctName
```

### 4.9 Run a call only when needed

```sql
UPDATE gw_flow_step SET condition_expr = '${steps.beneficiary.body.statusCd} == ''A'''
WHERE name = 'debit' AND flow_id = (SELECT id FROM gw_flow WHERE code = 'TRANSFER');
```

(In SQL, a `'` inside a string is written `''`.) A skipped step has `outcome = SKIPPED`, which the response can report.

### 4.10 Downstream returns HTTP 200 with an error code in the body

Many core systems answer `200 {"responseCode":"51"}` for a business failure:
1. Set `success_expr` so that only `00` counts as success.
2. Translate the failure codes with a `LookupErrorHandler` ([§5.3](#53-errorhandler--error-responses)). The demo has one called `coreBankingErrorHandler`.

```sql
UPDATE gw_flow_step SET success_expr = '${steps.debit.body.responseCode} == ''00''' WHERE ...;
UPDATE gw_flow SET error_handler = 'coreBankingErrorHandler' WHERE code = 'TRANSFER';

INSERT INTO gw_lookup_entry (lookup_code, source_value, target_value) VALUES ('CORE_BANKING_ERRORS', '51',
  '{"status":422,"errorCode":"INSUFFICIENT_FUNDS","errorMessage":"Insufficient balance"}');
```

Result: `422 {"errorCode":"INSUFFICIENT_FUNDS","errorMessage":"Insufficient balance","correlationId":"…"}`. Codes without a lookup row fall back to the standard error (`GW-422-BUSINESS`). The same handler maps non-2xx responses that carry a code, e.g. `404 {"responseCode":"14"}` → `ACCOUNT_NOT_FOUND`.

### 4.11 Best-effort call (notification, logging)

Set `on_failure = 'CONTINUE'`. The flow succeeds even when this call fails, and the response can expose `$.steps.notify.outcome`.

### 4.12 Timeouts

```sql
UPDATE gw_flow_step SET timeout_ms = 3000 WHERE name = 'inquiry' AND flow_id = ...;   -- one call
UPDATE gw_flow SET timeout_ms = 8000 WHERE code = 'TRANSFER';                          -- whole flow
```

A step never waits longer than the flow has left.

### 4.13 Downstream systems: add one, change its IP / port

Downstream systems live in `gw_target_system` (reference: [§3.11](#311-gw_target_system--gw_target_system_header)). Changes take effect with `make reload`, without a restart.

**Add a system:**

```sql
INSERT INTO gw_target_system (code, base_url, connect_timeout_ms, read_timeout_ms)
VALUES ('CARD_SYSTEM', 'https://10.20.30.50:8443/cards', 2000, 8000);

INSERT INTO gw_target_system_header (target_code, header_name, header_value)
VALUES ('CARD_SYSTEM', 'X-Api-Key', '${CARD_API_KEY}');   -- the secret stays in an env variable
```

Steps can then use `target_system = 'CARD_SYSTEM'`.

**Change the IP / host / port:**

```sql
UPDATE gw_target_system SET base_url = 'http://10.20.30.41:9080' WHERE code = 'CORE_BANKING';
```

```bash
make reload    # next requests go to 10.20.30.41:9080
```

`base_url` is everything before the step's `path_template`: scheme, host or IP, port and an optional base path. With `http://10.20.30.40:9080/api` and `path_template = /core/accounts/{acc}`, the gateway calls `http://10.20.30.40:9080/api/core/accounts/1001`.

**Same config, different address per environment.** Write a placeholder instead of a literal, e.g. `base_url = '${CORE_BANKING_URL:http://localhost:8089}'`, and set `CORE_BANKING_URL` per environment. The dev demo rows do this, which is why `make run CORE_BANKING_URL=http://10.20.30.40:9080` works locally.

**Alternative: application config.** A system can instead be defined under `gateway.target-systems` in `application.yml` (restart needed):

```yaml
gateway:
  target-systems:
    CARD_SYSTEM:
      base-url: https://cards.internal/api
      connect-timeout-ms: 2000
      read-timeout-ms: 8000
      static-headers:
        X-Api-Key: ${CARD_API_KEY}
```

When a code is in both places, **the database row wins** and the startup/reload log says `overrides application config`. Setting `enabled = false` on the row makes the gateway use the application config again. To see which definition is active, check the log after start or reload:

```
target CORE_BANKING -> http://localhost:8089 (database)
```

HTTP or HTTPS is decided by the scheme of `base_url`. For `https://`, the JVM's trusted CAs are used unless the target sets `tls_mode` ([§4.16](#416-https-downstream-certificates-and-keys)).

### 4.14 Switch things off

```sql
UPDATE gw_flow SET enabled = false WHERE code = 'PAYMENT';           -- endpoint returns 404
UPDATE gw_flow_step SET enabled = false WHERE name = 'notify' AND ...; -- step and its rules ignored
UPDATE gw_flow SET audit_mode = 'OFF' WHERE code = 'HEALTH_PING';    -- no audit rows for this flow
```

On Oracle, booleans are numbers: `enabled = 0` / `1`, `required = 1`.

### 4.15 XML or SOAP downstream

Everything inside the gateway stays JSON: mapping rules, expressions, schemas, handlers and the audit trail. Only the HTTP call to the downstream is converted. Pick the format with `body_codec`:

| `body_codec` | Downstream speaks | Content-Type sent |
|---|---|---|
| *(empty)* / `jsonCodec` | JSON (default) | `application/json` |
| `xmlCodec` | plain XML over HTTP | `application/xml; charset=UTF-8` |
| `soapCodec` | SOAP 1.1 | `text/xml; charset=UTF-8` |
| `soap12Codec` | SOAP 1.2 | `application/soap+xml; charset=UTF-8` |
| your bean, e.g. `partnerSoapCodec` | anything else (SOAP header, WS-Security, signed XML) | yours ([CUSTOM-CLASSES.md §4.6](CUSTOM-CLASSES.md#46-partnersoapcodec-bodycodec-soap-with-a-ws-security-header)) |

Set it on the **target system** (every step calling it) and, only for the odd endpoint that differs, on the **step** (wins):

```sql
UPDATE gw_target_system SET body_codec = 'soapCodec' WHERE code = 'LEGACY_CORE';   -- all calls are SOAP
UPDATE gw_flow_step SET body_codec = 'jsonCodec' WHERE name = 'health' AND ...;      -- except this one
```

**How JSON and XML correspond.** `BODY` rules build JSON; the codec turns it into XML:

| JSON built by rules | XML sent |
|---|---|
| `{"Inquiry":{"accountNo":"123"}}` | `<Inquiry><accountNo>123</accountNo></Inquiry>` |
| `{"L":{"item":[{"id":1},{"id":2}]}}` | `<L><item><id>1</id></item><item><id>2</id></item></L>` (array = repeated element) |
| `{"amt":{"@currency":"IDR","#text":"10"}}` | `<amt currency="IDR">10</amt>` (`@` = attribute, `#text` = text) |
| `{"ns:Req":{"@xmlns:ns":"urn:bank"}}` | `<ns:Req xmlns:ns="urn:bank"/>` (prefixes and namespaces) |
| `{"a":null}` | `<a/>` |

Use the `['...']` path syntax for names with `:` or `@`: `target_path = $['ns:Req']['ns:accountNo']`.

For `xmlCodec` the body must have **exactly one** top-level field (the root element). For `soapCodec` each top-level field becomes an element inside `<soapenv:Body>`; the envelope is added for you.

**Reading the response.** The XML is decoded to JSON before anything else sees it:

- Element names lose their namespace prefix: `<ns2:InquiryResponse>` → `InquiryResponse`.
- Every value is **text** (XML has no numbers): `<balance>100.10</balance>` → `"100.10"`. Compare it as text in `success_expr`, or convert it with a `converter`.
- A repeated element becomes an array, a single one stays an object. If a list can have one item, read it with a path that works for both, or normalise it in a `response_handler`.
- Attributes become `@name`, text next to attributes or children becomes `#text`, and `xsi:nil="true"` becomes `null`.
- `xmlCodec`: `$.steps.<name>.body.<RootElement>...`. `soapCodec`: the children of `<Body>`, so also `$.steps.<name>.body.<ResponseElement>...`. The SOAP Header is ignored.
- A SOAP fault normally comes with HTTP 500 → `DOWNSTREAM_HTTP_ERROR`, and the decoded fault is in the step body for error handlers: `$.steps.<name>.body.Fault.faultstring` (SOAP 1.1) or `.Fault.Reason.Text` (SOAP 1.2). If your service answers faults with HTTP 200, add `success_expr = ${steps.<name>.body.Fault} == null`.
- DOCTYPE declarations are rejected (no XXE).

**Complete SOAP example** (statements in the same style as the tutorial):

```sql
INSERT INTO gw_target_system (code, base_url, body_codec)
VALUES ('LEGACY_CORE', '${LEGACY_CORE_URL:http://localhost:8089}', 'soapCodec');

-- flow POST /api/v1/legacy/inquiry with one step 'inquiry' -> LEGACY_CORE POST /ws/AccountService
-- STEP_REQUEST rules of step 'inquiry':
--   HEADER  SOAPAction                               constant  "urn:bank/Inquiry"
--   BODY    $['ns:InquiryRequest']['@xmlns:ns']      constant  "urn:bank"
--   BODY    $['ns:InquiryRequest']['ns:accountNo']   source    $.request.body.accountNo
-- FLOW_RESPONSE rules:
--   BODY    $.name     source  $.steps.inquiry.body.InquiryResponse.name
--   BODY    $.balance  source  $.steps.inquiry.body.InquiryResponse.balance
```

The gateway sends:

```xml
POST /ws/AccountService
Content-Type: text/xml; charset=UTF-8
SOAPAction: urn:bank/Inquiry

<?xml version="1.0" encoding="UTF-8"?><soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body><ns:InquiryRequest xmlns:ns="urn:bank"><ns:accountNo>123</ns:accountNo></ns:InquiryRequest></soapenv:Body></soapenv:Envelope>
```

and turns `<S:Body><ns2:InquiryResponse><ns2:name>BUDI</ns2:name><ns2:balance>100.10</ns2:balance>…` into `{"name":"BUDI","balance":"100.10"}` for the client. For SOAP 1.2 (`soap12Codec`) the `SOAPAction` header is moved into the Content-Type `action` parameter automatically. The integration test `soapTargetSystemIsCalledInXmlAndAnsweredInJson` runs exactly this flow.

**Audit and masking.** The audit tables store the JSON form of the request and response, so `gateway.masking.fields` works for XML and SOAP calls too.

### 4.16 HTTPS downstream: certificates and keys

Whether a downstream is called over HTTP or HTTPS is the scheme of its `base_url` (`http://` or `https://`). For HTTPS, each target system chooses how the connection is secured with `tls_mode`:

| `tls_mode` | What happens | Use for |
|---|---|---|
| `VERIFY` (default, NULL) | The server certificate must chain to a CA the JVM trusts, and match the host name | Public or company-CA certificates the JVM already trusts |
| `INSECURE` | Any certificate and host name is accepted; a warning is logged on every reload | Dev/test systems with self-signed certificates only. Never production: anyone in the network path can read and change the traffic |
| `CUSTOM` | `tls_trust_store` replaces the JVM CAs for this target, and/or `tls_key_store` presents a client certificate (mutual TLS) | An internal CA or a pinned self-signed certificate; partners that require a client certificate |

A store is either **PEM text** (`-----BEGIN CERTIFICATE-----...`), or a **path** on the gateway server to a `.pem`/`.crt` file (PEM), a `.p12`/`.pfx` file (PKCS12) or a `.jks` file. `classpath:` and `file:` prefixes work. A PEM key store contains the client certificate chain and the private key (one file). Passwords are needed for PKCS12/JKS stores and encrypted PEM keys.

Keep key material out of the database: put files on the server (or a mounted secret) and store only the path, or use `${ENV_VAR}` placeholders for paths, PEM text and passwords. Files are read on every load and reload, so a renewed certificate is picked up by `make reload` (or **Save & reload** in Studio) without a restart. A wrong path, an unreadable store or a wrong password is a config error, so the reload is rejected and the old configuration keeps running.

```sql
-- internal CA: trust only that CA for CORE_BANKING
UPDATE gw_target_system SET base_url = 'https://core.internal:9443', tls_mode = 'CUSTOM',
       tls_trust_store = '/etc/gateway/tls/company-ca.pem'
 WHERE code = 'CORE_BANKING';

-- partner that requires mutual TLS: our client certificate from a PKCS12 file, password from the environment
UPDATE gw_target_system SET base_url = 'https://api.partner.co.id', tls_mode = 'CUSTOM',
       tls_key_store = '/etc/gateway/tls/gateway-client.p12', tls_key_store_password = '${PARTNER_P12_PASSWORD}'
 WHERE code = 'PARTNER';

-- dev only: a test server with a self-signed certificate
UPDATE gw_target_system SET tls_mode = 'INSECURE' WHERE code = 'CARD_SYSTEM';
```

The same settings exist for targets in `application.yml`:

```yaml
gateway:
  target-systems:
    PARTNER:
      base-url: https://api.partner.co.id
      tls:
        mode: CUSTOM
        trust-store: /etc/gateway/tls/partner-ca.pem
        key-store: /etc/gateway/tls/gateway-client.p12
        key-store-password: ${PARTNER_P12_PASSWORD}
```

In Gateway Studio: **Target systems**, then the target's **TLS** section. The badge shows HTTP or HTTPS from the resolved base URL; pick the mode and, for `CUSTOM`, fill in the trust store and/or key store.

### 4.17 Query a database

A **database query step** runs one SQL statement instead of an HTTP call. Its request mapping supplies the SQL parameters from the client's path, query string, headers or body; the rows come back as JSON in `$.steps.<step>.body`, and the response mapping shapes them for the client like any other step result.

**1. The datasource** (`application.yml`). `GATEWAY_DB` (the gateway's own database) is there by default, read-only:

```yaml
gateway:
  sql:
    max-rows: 1000                       # more rows are cut off, truncated: true
    datasources:
      GATEWAY_DB:
        url: ${GATEWAY_SQL_DB_URL:}      # empty = the gateway's own database
        read-only: ${GATEWAY_SQL_DB_READ_ONLY:true}
      REPORTING:                         # any other database: its own small connection pool
        url: jdbc:postgresql://reporting-db:5432/reports
        username: ${REPORTING_DB_USER}
        password: ${REPORTING_DB_PASSWORD}
        max-pool-size: 5
        read-only: true
```

`read-only: true` allows only `SELECT` / `WITH` (checked at reload) and runs each query in a read-only transaction, so the database itself refuses writes. Set it to `false` for `INSERT` / `UPDATE` / `DELETE`. The JDBC driver must be on the classpath (PostgreSQL and Oracle are).

**2. The step**: `target_system` = the datasource, `sql_text` = the statement, no `http_method` / `path_template`.

```sql
INSERT INTO gw_flow_step (flow_id, name, step_order, target_system, sql_text, success_expr)
SELECT id, 'user', 1, 'GATEWAY_DB',
       'SELECT id, username, full_name AS "fullName", email, phone, status, created_at AS "createdAt"
        FROM tbl_ms_user
        WHERE id = :id',
       '${steps.user.body.rowCount} > 0'
FROM gw_flow WHERE code = 'SQL_USER_DETAIL';
```

**3. The parameters**: one `BODY` rule per `:name`, writing `$.name`. The value is **bound** (`?`), never pasted into the SQL, so request data cannot change the statement:

| target_path | source_path | converter | default |
|---|---|---|---|
| `$.id` | `$.request.path.id` | `TO_NUMBER` | |
| `$.q` | `$.request.query.q` | | |
| `$.status` | `$.request.query.status` | `UPPER` | |
| `$.limit` | `$.request.query.limit` | `TO_NUMBER` | `10` |

Path and query values are text: add `TO_NUMBER` when the column is a number (PostgreSQL refuses to compare text with a number). A missing value is bound as `NULL`, so optional filters read `COALESCE(:status, status)`. An array binds each element, for `IN (:ids)`. Every `:name` must have a rule; the reload says which one is missing.

**4. The result** is the step's body:

```json
{"rows": [{"id": 1, "username": "budi.santoso", "fullName": "Budi Santoso", ...}], "rowCount": 1, "truncated": false}
```

and `{"updated": 1}` for `INSERT` / `UPDATE` / `DELETE`. Numbers stay numbers, dates and times become ISO-8601 text (`2026-10-09T14:14:15.305862`), PostgreSQL `json`/`jsonb` columns become JSON. Unquoted column names come back in lower case on both PostgreSQL and Oracle; use a quoted alias (`AS "fullName"`) for any other spelling.

**5. Map it for the client** with `FLOW_RESPONSE` rules, as for any step: `$.user ← $.steps.user.body.rows[0]` for one row, `$.items[*].name ← $.steps.users.body.rows[*].fullName` for a list, `$.count ← $.steps.users.body.rowCount`, plus converters and lookups.

The demo flows (`103-dev-demo-sql-flows.xml`, dev profile) do exactly this:

```bash
curl -s localhost:8080/api/v1/sql/users/1
{"user":{"id":1,"username":"budi.santoso","fullName":"Budi Santoso","email":"budi.santoso@example.com","phone":"081200000001","status":"ACTIVE","createdAt":"2026-10-09T14:14:15.305862"}}

curl -s 'localhost:8080/api/v1/sql/users?status=active&limit=5'
{"items":[{"id":1,"username":"budi.santoso","name":"Budi Santoso","active":true},{"id":2,"username":"siti.aminah","name":"Siti Aminah","active":true}],"count":2}

curl -s localhost:8080/api/v1/sql/users/999      # no row: success_expr is false
{"errorCode":"GW-422-BUSINESS","errorMessage":"Downstream reported a business error","correlationId":"…","step":"user"}

curl -s localhost:8080/api/v1/sql/users/abc      # TO_NUMBER on the path value fails: the client's fault
{"errorCode":"GW-400-MAPPING","errorMessage":"Message mapping failed","correlationId":"…","step":"user","details":["mapping rule 28 ($.request.path.id -> $.id): Not a number: 'abc'"]}
```

When the database fails:

| What happened | Error |
|---|---|
| Query timeout (`timeout_ms`, else `gateway.default-step-timeout-ms`) | `504 GW-504-DOWNSTREAM` (or `GW-504-FLOW` when the flow's time ran out first) |
| Database unreachable | `502 GW-502-CONNECTION` |
| Anything else: constraint, syntax, permission, … | `502 GW-502-DATABASE` |

The client gets only the standard message; the database's own error (SQL state, vendor code, message) is in the log and in the audit trail's step response, so table and column names never reach the client. Condition, success expression, response schema, request/response handlers, `on_failure`, parallel groups and the audit trail work as for HTTP steps (the audit row shows method `SQL` and `DATASOURCE: SELECT …` as the URL; the parameters are the request payload, the result the response payload).

In Gateway Studio: drag **Query GATEWAY_DB** (palette, *Database query*) onto the pipeline. The inspector has the datasource, an SQL editor with syntax highlighting, and the list of `:parameters` with the rule that sets each one; **+ Add rule for :x** creates the missing rules (source guessed from a path variable of the same name, else the query string or body; `TO_NUMBER` for id-like names). The Mapping tab's *Parameters of …* shows the bound values live, and the context tree offers the selected columns (`$.steps.<step>.body.rows[*].<column>`) for the response mapping.

### 4.18 File uploads, local storage and S3

**What the gateway accepts.** Besides JSON, a flow can receive:

| Request `Content-Type` | `$.request.body` | `$.request.files` |
|---|---|---|
| `application/json` (or none) | the JSON | |
| `multipart/form-data` | the text fields | one entry per file field: `$.request.files.<field>` |
| `application/x-www-form-urlencoded` | the form fields (not the query string's) | |
| a binary type: `application/pdf`, `image/*`, `audio/*`, `video/*`, `application/octet-stream`, `application/zip`, `text/csv`, Word / Excel | `{}` | the whole body as `$.request.files.body`; its name from `Content-Disposition: …; filename=…` or `X-File-Name` |

A file entry describes the upload; the content itself stays in the gateway (custom Java code reads it with `ctx.file("<field>")`):

```json
{"field": "file", "filename": "Q3 report.pdf", "contentType": "application/pdf", "size": 24680, "sha256": "3a0104d0…"}
```

so rules can read `$.request.files.file.filename`, `.size` and so on. Limits: `gateway.files.max-size` (default 20MB, env `GATEWAY_FILES_MAX_SIZE`) per file or raw body and `gateway.files.max-request-size` (25MB) per multipart request; more → `413 GW-413-FILE` before any flow runs. Request schemas validate `$.request.body` (the text fields).

**Storages** are configured like target systems: in the database (`gw_storage`, Gateway Studio → **Storages**), and optionally in `application.yml` (`gateway.storages`). An enabled database row wins over an `application.yml` storage of the same code; a disabled row hides it. Database storages apply on **Save & reload** (or `POST /admin/config/reload`), with no restart.

`gw_storage`:

| Column | Meaning |
|---|---|
| `code` | What a step's `target_system` names, e.g. `S3_FILES` |
| `storage_type` | `LOCAL` or `S3` |
| `base_dir` | LOCAL: a directory on the gateway host (created by the first upload) |
| `bucket`, `key_prefix`, `region` | S3 |
| `endpoint`, `path_style` | S3-compatible servers (MinIO, …): their URL and `true`; empty = AWS |
| `access_key`, `secret_key` | S3 credentials; empty = the default AWS chain (environment, profile, instance role) |
| `allowed_types` | Comma-separated, `image/*` style; empty = any. Otherwise `415 GW-415-FILE` |
| `max_size` | `10MB`, `512KB`, …; empty = only the upload limit. Otherwise `413 GW-413-FILE` |
| `enabled` | `false` = not available (and hides an `application.yml` storage of the same code) |

Every text value may be a `${ENV_VAR}` / `${ENV_VAR:default}` placeholder, resolved by the gateway on every reload, so buckets and keys stay in the environment, not in the table (an unresolvable placeholder in an enabled storage is a validation error and the reload is refused). The dev data has `S3_FILES`, disabled, pointing at `${S3_BUCKET}`, `${S3_ACCESS_KEY:}` and so on.

```sql
INSERT INTO gw_storage (code, storage_type, bucket, key_prefix, region, access_key, secret_key, allowed_types, max_size)
VALUES ('S3_FILES', 'S3', '${S3_BUCKET}', 'uploads/', 'ap-southeast-1', '${S3_ACCESS_KEY}', '${S3_SECRET_KEY}',
        'image/*,application/pdf', '10MB');
```

The same in `application.yml` (`LOCAL_FILES`, `./data/files`, is there by default):

```yaml
gateway:
  storages:
    LOCAL_FILES:
      type: local
      base-dir: ${GATEWAY_FILES_DIR:./data/files}
      allowed-types: image/*,application/pdf,text/csv,text/plain,application/octet-stream
      max-size: 10MB
    S3_ARCHIVE:
      type: s3
      bucket: ${S3_BUCKET}
      prefix: uploads/
      region: ap-southeast-1
      endpoint: ${S3_ENDPOINT:}
      path-style: false
      access-key: ${S3_ACCESS_KEY:}
      secret-key: ${S3_SECRET_KEY:}
```

**The step.** A step whose `target_system` is a storage is a **file storage step**:

```sql
INSERT INTO gw_flow_step (flow_id, name, step_order, target_system, http_method, path_template)
SELECT id, 'store', 1, 'LOCAL_FILES', 'PUT', '/{yyyy}/{MM}/{uuid}-{filename}'
FROM gw_flow WHERE code = 'FILE_UPLOAD';
-- which file: BODY $.file <- the upload
INSERT INTO gw_mapping_rule (flow_id, step_id, phase, seq, target_type, target_path, source_path, required)
SELECT f.id, s.id, 'STEP_REQUEST', 1, 'BODY', '$.file', '$.request.files.file', true
FROM gw_flow f JOIN gw_flow_step s ON s.flow_id = f.id WHERE f.code = 'FILE_UPLOAD' AND s.name = 'store';
```

- `http_method`: `PUT` stores, `DELETE` deletes the object at the key.
- `path_template` is the **object key**. Its `{variables}` are PATH rules of the step or built in: `{uuid}` `{correlationId}` `{filename}` `{name}` (without extension) `{ext}` `{field}` `{yyyy}` `{MM}` `{dd}` `{HH}` `{mm}` `{ss}`; a rule wins over a built-in. Every value is made safe for a key (letters, digits, `. _ -`; anything else becomes `_`; never `..` or `/`), so a client's file name `../../etc/passwd` is stored as `…-passwd` inside the storage. Local keys are resolved under `base-dir` and checked to stay there; S3 keys get the storage's `prefix`.
- BODY `$.file` ← `$.request.files.<field>` is required for `PUT`; an optional BODY `$.contentType` overrides the upload's content type.
- A missing upload is the client's fault: with `required` on the rule → `400 GW-400-MAPPING`.

The step's result (`$.steps.<step>.body`), for the response mapping:

```json
{"storage": "LOCAL_FILES", "key": "2026/10/a5ac2518-…-demo.pdf", "location": "file:///…/data/files/2026/10/a5ac2518-…-demo.pdf",
 "filename": "demo.pdf", "contentType": "application/pdf", "size": 13, "sha256": "3a0104d0…", "etag": "…(S3 only)"}
```

and `{"storage", "key", "deleted"}` for `DELETE`. S3 objects get the content type and user metadata `filename` and `sha256`. Writing fails → `502 GW-502-STORAGE` (details in the log and audit trail only); a timeout → `504`. The audit trail shows the step with method `PUT`/`DELETE`, the location as its URL, the file's description as the request (never the content).

**The demo flows** (`104-dev-demo-file-flows.xml`, dev profile):

```bash
curl -s -F "file=@invoice.pdf;type=application/pdf" -F "description=Invoice October" localhost:8080/api/v1/files
{"fileId":"2026/10/a5ac2518-6212-4783-9a94-a8f917b30e4c-demo.pdf","filename":"demo.pdf","contentType":"application/pdf","size":13,"sha256":"3a0104d0…","description":"Invoice October"}

curl -s -H "Content-Type: image/png" -H "X-File-Name: logo.png" --data-binary @logo.png localhost:8080/api/v1/documents
{"fileId":"documents/5a2f3e9c-4d29-4c7d-90d8-753f95a4f2fd-logo.png","filename":"logo.png","contentType":"image/png","size":13}

curl -s -X DELETE localhost:8080/api/v1/files/2026/10/a5ac2518-6212-4783-9a94-a8f917b30e4c-demo.pdf
{"fileId":"2026/10/a5ac2518-6212-4783-9a94-a8f917b30e4c-demo.pdf","deleted":true}
```

**To S3 instead**: in Studio → **Storages**, fill in `S3_FILES` (bucket, region, keys, or keep the `${S3_…}` placeholders and set those environment variables), **Test connection** (it checks the bucket with these settings, unsaved ones too, and writes nothing), switch it on, then select it as the step's storage and **Save & reload**. For MinIO and other S3-compatible servers set the endpoint (e.g. `http://localhost:9000`) and path-style addressing. The API docs (`/docs`) describe upload flows as `multipart/form-data` (or a binary body), so Swagger UI's *Try it out* can send files.

In Gateway Studio, **Storages** lists the database storages (add with **+ Local storage** / **+ S3 storage**; a literal key gets a "stored as plain text" warning) and, read-only, those of `application.yml` (**Override in database** copies one into the table). Drag **Store file · LOCAL_FILES** (palette, *File storage*, which lists both kinds) onto the pipeline. The inspector has the storage (with its location, allowed types and limit), the operation, the form field of the upload, the object key, and the key's variables with **+ Add rule for {x}** for the ones that are neither built in nor set. The live preview shows the key, location and file the step would use. The flow's **Tests** tab sends JSON only, so generated cases for upload flows have no file; test them with curl, `http/gateway.http` or Swagger UI.

---

## 5. Custom classes (Java)

> **Full step-by-step guide with five working examples, tests, API reference and troubleshooting: [CUSTOM-CLASSES.md](CUSTOM-CLASSES.md).** This section is a short overview.

Use a custom class when configuration can't express the logic. A handler is a Spring bean that implements one of three interfaces (a fourth, `BodyCodec`, changes the wire format of downstream calls, see [§4.15](#415-xml-or-soap-downstream)). Put it in `src/main/java/com/mhamzah/gateway/extension/custom/` (or any package under `com.mhamzah.gateway`), give it a bean name with `@Component("...")`, and put that name in the config column.

New classes need a **rebuild and restart**. After that, any flow can use them through a config change and a reload. If a config row references a bean that doesn't exist, the reload or startup fails with `'<name>' is not a FieldHandler bean` (or `MessageHandler` / `ErrorHandler` / `BodyCodec`).

**API cheat sheet:**

| Type | Useful methods |
|---|---|
| `ExecutionContext ctx` | `ctx.read("$.request.body.x")` (null when missing) · `ctx.lookup("CODE", value)` · `ctx.correlationId()` · `ctx.flowCode()` · `ctx.snapshot()` (copy of the whole context) |
| `MessageView message` | `message.headers()` (mutable, case-insensitive) · `message.body()` · `message.setBody(node)` |
| `GatewayResponse` | `GatewayResponse.of(status, body)` · `new GatewayResponse(status, headers, body)` |
| `GatewayError` | `throw GatewayError.of(ErrorType.X).message("…").details(List.of("…")).build()` fails the request with that error type |
| JSON (Jackson 3) | `tools.jackson.databind.JsonNode`; create values with `JsonNodeFactory.instance.stringNode(..)`, `.objectNode()`, `.numberNode(..)` |

### 5.1 `FieldHandler`: one field

Runs as step 5 of [rule evaluation](#334-rule-evaluation-order). It gets the value so far (`null` if missing) and returns the new value (`null` = missing).

```java
package com.mhamzah.gateway.extension.custom;

import com.mhamzah.gateway.extension.ExecutionContext;
import com.mhamzah.gateway.extension.FieldHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** 4111111111111111 -> 411111******1111 */
@Component("maskCardNumber")
public class MaskCardNumber implements FieldHandler {

    @Override
    public JsonNode handle(JsonNode value, ExecutionContext ctx) {
        if (value == null || value.isNull()) {
            return null;
        }
        String pan = value.asString();
        if (pan.length() < 10) {
            return value;
        }
        String masked = pan.substring(0, 6) + "*".repeat(pan.length() - 10) + pan.substring(pan.length() - 4);
        return JsonNodeFactory.instance.stringNode(masked);
    }
}
```

```sql
INSERT INTO gw_mapping_rule (flow_id, phase, seq, target_type, target_path, source_path, field_handler)
SELECT id, 'FLOW_RESPONSE', 5, 'BODY', '$.card', '$.steps.card.body.pan', 'maskCardNumber' FROM gw_flow WHERE code = '...';
```

A field handler can also produce a value from nothing (rule with neither source nor constant), e.g. a generated reference number or a timestamp.

### 5.2 `MessageHandler`: headers + body

Four hook points:

| Column | Runs | `message` contains |
|---|---|---|
| `gw_flow.request_handler` | before any step | inbound headers + body (changes are visible to all rules) |
| `gw_flow_step.request_handler` | after the step's request is mapped | outgoing headers + body |
| `gw_flow_step.response_handler` | after a 2xx JSON response | downstream headers + body (changes are what gets stored) |
| `gw_flow.response_handler` | after the response is mapped | outgoing client headers + body |

Example: sign every outgoing request to a partner:

```java
@Component("partnerSigner")
public class PartnerSigner implements MessageHandler {

    private final byte[] secret;

    public PartnerSigner(@Value("${partner.signing-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public GatewayResponse handle(MessageView message, ExecutionContext ctx) {
        String payload = message.body() == null ? "" : message.body().toString();
        message.headers().put("X-Signature", hmacSha256Hex(secret, payload)); // your HMAC helper (javax.crypto.Mac)
        message.headers().put("X-Timestamp", Instant.now().toString());
        return null; // continue
    }
}
```

Only a **flow** `request_handler` may stop the flow early, by returning a response. For example, a block list:

```java
@Override
public GatewayResponse handle(MessageView message, ExecutionContext ctx) {
    if (blocked(ctx.read("$.request.body.account"))) {
        return GatewayResponse.of(403, JsonNodeFactory.instance.objectNode()
                .put("errorCode", "ACCOUNT_BLOCKED").put("correlationId", ctx.correlationId()));
    }
    return null;
}
```

A handler that throws an ordinary exception produces `HANDLER_ERROR` (500). To fail with a specific error type, throw a `GatewayError`.

### 5.3 `ErrorHandler`: error responses

**Mapping a downstream error code (the common case).** Extend `LookupErrorHandler`. You provide where the code is (a path inside `{"status":…, "headers":{…}, "body":{…}}` of the failed call) and which lookup table translates it:

```java
@Component("cardSystemErrorHandler")
public class CardSystemErrorHandler extends LookupErrorHandler {
    @Override protected String errorCodePath() { return "$.body.error.code"; }
    @Override protected String lookupCode()    { return "CARD_ERRORS"; }
}
```

```sql
INSERT INTO gw_lookup_entry (lookup_code, source_value, target_value) VALUES ('CARD_ERRORS', 'C05',
  '{"status":409,"errorCode":"CARD_BLOCKED","errorMessage":"Card is blocked"}');
UPDATE gw_flow SET error_handler = 'cardSystemErrorHandler' WHERE code = '...';
```

- It handles `DOWNSTREAM_HTTP_ERROR` and `DOWNSTREAM_BUSINESS_ERROR`. Everything else, and unknown codes, get the standard error body.
- New codes are just new `gw_lookup_entry` rows plus a reload. No code change needed.
- To change the body shape, override `buildBody(...)`.

**Fully custom error handler:**

```java
@Component("partnerErrorHandler")
public class PartnerErrorHandler implements ErrorHandler {
    @Override
    public GatewayResponse handle(GatewayError error, ExecutionContext ctx) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("rc", error.type() == ErrorType.REQUEST_SCHEMA_INVALID ? "30" : "96");
        body.put("rcDesc", error.getMessage());
        body.put("ref", ctx.correlationId());
        return GatewayResponse.of(200, body); // this partner expects HTTP 200 with a response code
    }
}
```

`GatewayError` provides `type()`, `stepName()`, `downstreamStatus()`, `downstreamHeaders()`, `downstreamBody()`, `details()` and `clientError()`. If an error handler throws, the gateway falls back to the standard error body, so clients always get a JSON error.

---

## 6. Validation errors and how to fix them

Reload (and startup) report every problem at once. The common ones:

| Message contains | Cause | Fix |
|---|---|---|
| `has the same route as flow` | Two enabled flows with the same method and pattern (`{id}` vs `{no}` counts as the same) | Change one pattern or disable a flow |
| `http_method 'X' must be one of` | Typo / unsupported method | Use GET, POST, PUT, PATCH, DELETE |
| `path_pattern ... must start with '/'` | Missing leading slash | Add it |
| `references unknown schema` | `*_schema_code` without a `gw_json_schema` row | Insert the schema or fix the code |
| `json schema 'X': ... not a valid draft 2020-12 schema` | Broken schema JSON | Fix `schema_text` |
| `is not a FieldHandler / MessageHandler / ErrorHandler bean` | Bean name typo, or the class isn't deployed | Fix the name or deploy the class |
| `target_system 'X' is not configured` | Unknown target system | Fix the name or add a `gw_target_system` row ([4.13](#413-downstream-systems-add-one-change-its-ip--port)) |
| `target system 'X': base_url '...' must start with http:// or https://` / `has no host` / `must not contain a query` | Bad address, e.g. `10.1.1.1:9080` without `http://` | Write the full URL: `http://10.1.1.1:9080` |
| `target system 'X': Could not resolve placeholder 'Y'` | `${Y}` in `base_url` / `header_value` but no such env variable or property | Set `Y` for the app, or use a default: `${Y:value}` |
| `target system 'X' header ...: invalid header name` / `must not contain line breaks` | Header name with spaces etc., or a value with a line break | Fix the header row |
| `target system 'X' (application config): base-url is required` | `gateway.target-systems.X` in yml without `base-url` | Add `base-url`, or remove the yml entry |
| `Duplicate step name` / `step name ... must match` | Name clash or bad characters | Use unique names of letters, digits, `_`, `-` |
| `path_template variable {x} has no PATH mapping rule` | `{x}` in the template but no `PATH` rule named `x` | Add the rule (and make it `required`) |
| `references step 'x' which does not run before it` | Reads a step in the same or a later group | Give the referenced step a lower `step_order` |
| `references unknown step 'x'` | Typo, or the step is disabled | Fix the name / enable the step |
| `must set exactly one of source_path / constant_value` | Both or neither set | Keep one (or add a `field_handler`) |
| `STEP_REQUEST rule requires step_id` / `FLOW_RESPONSE rule must not have step_id` | `phase` and `step_id` don't match | Fix `step_id` or `phase` |
| `target_type QUERY is not allowed for FLOW_RESPONSE` | Responses only have body and headers | Use `BODY` or `HEADER` |
| `target has N [*] but source has M` | Array shapes don't match | Use the same number of `[*]`, or none in the target |
| `a wildcard source cannot be written to a HEADER target` | Arrays can't go into headers / query / path | Pick one element (`[0]`) instead |
| `converter '...'` | Unknown converter or wrong arguments | See [§3.3.5](#335-converters) |
| `lookup_code 'X' has no gw_lookup_entry rows` | No rows for that table | Insert the lookup rows |
| `condition_expr` / `success_expr` + `not allowed` | Method call, `T(...)` etc. | Use only the [allowed operators](#36-expressions-condition_expr-success_expr) |
| `source_path must start with $.request, $.steps or $.correlationId` | Typo in the root | Fix the path |

The message names the flow, step or rule ID, so you can find the row: `mapping rule 36 of flow 'ACCOUNT_OVERVIEW'` is `gw_mapping_rule.id = 36`.

---

## 7. Working practices

**Moving config between environments.** Keep each integration's SQL in version control, not only in someone's SQL client. Two options:
- **Recommended:** add a Liquibase changeset under `src/main/resources/db/changelog/changes/` (see `100-dev-demo-flows.xml`). It is applied automatically and only once, in every environment.
- Or keep plain SQL scripts like [`examples/account-overview.sql`](examples/account-overview.sql) and run them through your normal DB change process.

Use `code` and step `name` (not numeric IDs) to link rows, as the examples do with `SELECT id FROM gw_flow WHERE code = ...`. IDs differ between environments.

**Oracle notes.**
- Booleans are `NUMBER(1)`: use `1` / `0`.
- `schema_text` is a CLOB; inserting literals up to 4000 characters is fine.
- The tutorial script's `VALUES (...) AS r(...)` shortcut is PostgreSQL syntax. On Oracle, write one `INSERT ... SELECT` per rule.

**Checklist for a new integration:**
1. The target system exists in `gw_target_system` (or `application.yml`), with the right address for every environment.
2. `gw_flow` row with a unique `code` and route.
3. One `gw_flow_step` per call. Parallel calls share a `step_order`.
4. `STEP_REQUEST` rules for each step, including a `required` `PATH` rule per `{var}`.
5. `FLOW_RESPONSE` rules for the client response.
6. Optional: schema, lookups, `success_expr`, `error_handler`.
7. `make reload`, then test the happy path and at least one failure.
8. Check `gw_audit_transaction` for the requests.
