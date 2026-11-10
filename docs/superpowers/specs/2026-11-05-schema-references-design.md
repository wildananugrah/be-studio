# Schema references (`gw:CODE`) — Design

**Date:** 2026-11-05
**Status:** Draft for review

## 1. Intent

Some flows are **routers**: their steps call smaller flows (sub-routes) of the same gateway, each with its own
`request_schema_code`. The router's API documentation should say that its request body is **one of** the bodies its
sub-routes accept, built from the `gw_json_schema` rows that already exist, not a hand-copied merge of them.

The goal is **documentation**: Swagger/OpenAPI and Studio's API specification document must describe the router
accurately. Validation is left to the sub-routes; a router will normally run with `schema_validation` = `RESPONSE`
or `NONE`.

### Goals

- A `gw_json_schema` can reference another one by its code, so a router schema can be written as
  `{"oneOf": [{"$ref": "gw:TRANSFER_REQUEST"}, {"$ref": "gw:TOPUP_REQUEST"}]}`.
- Swagger/OpenAPI shows the router body as a `oneOf` of named components.
- Studio's API specification document shows every alternative, not just the first.

### Non-goals

- Deriving the alternatives automatically from the router's steps (the gateway calls itself over plain HTTP, so it
  does not know a step targets one of its own flows, and a step may reshape the body).
- Database changes. `request_schema_code` stays a single code; no new columns or tables.
- A new validation mode for routers. If validation is on, standard JSON Schema semantics apply (`oneOf` = exactly
  one match, `anyOf` = at least one).

### Anti-goals

- Documentation that silently shows only one sub-route's body for a router (what the API specification document
  does today with any `oneOf`).
- A reload that accepts a schema referencing a code that does not exist.

## 2. Approaches considered

| | Approach | Trade-off |
|---|---|---|
| **A (chosen)** | `$ref: "gw:CODE"` inside `schema_text` | Standard JSON Schema, no DB change, usable in any schema (request, response, step response, nested). Cost: cross-schema resolution at load, OpenAPI ref rewriting, export must follow references. |
| B | New flow column listing the sub-schema codes; docs build the `oneOf` | Simple to fill in, but needs a Liquibase change and Studio form changes, and only works for flow requests. |
| C | Derive from the router's steps by matching step URLs to own flows | Automatic, but fragile URL matching, and wrong when a step's mapping reshapes the body. |

The user picked A.

## 3. Design

### 3.1 Reference syntax

- `{"$ref": "gw:CODE"}` — the whole `gw_json_schema` with that `code`.
- `{"$ref": "gw:CODE#/$defs/x"}` — a location inside it (JSON pointer fragment).
- Any other `$ref` (local `#/...`) keeps working as today.
- Codes are case-sensitive, exactly as in `gw_json_schema.code`.

### 3.2 Loading and validation (`CompiledSchema`, `ConfigCompiler`)

- Today each schema is compiled in isolation against a static `SchemaRegistry`. Schemas must now be compiled
  together against a registry built per config load that maps each IRI `gw:<code>` to that row's `schema_text`
  (networknt 3.0.8: `SchemaRegistry.Builder.schemas(Map<String,String>)` / `schemas(Function)`), so a reload picks
  up changed schemas. Keep the existing `PathType.JSON_PATH` config and draft 2020-12 default dialect.
- Every `gw:` reference is checked at load. A reference to an unknown code is a config error, in the style of the
  existing messages:
  `json schema 'ROUTER_REQUEST': $ref 'gw:FOO' references unknown schema 'FOO'`.
  This applies to the Studio save validation too (it goes through the same compiler).
- A reference to a schema that is itself invalid reports the referenced schema's own error; the referencing schema
  must not crash the load.
- Reference cycles (A → B → A, or self-reference) are allowed, as in JSON Schema; neither loading nor the docs may
  loop forever.
- `CompiledSchema` exposes the schemas it references (directly and transitively), so consumers that only hold a
  `CompiledSchema` (the OpenAPI generator) can reach them.
- With validation on, validation follows references with normal JSON Schema semantics. Error messages are whatever
  the validator produces; no special formatting for `oneOf` failures.

### 3.3 Swagger / OpenAPI (`OpenApiGenerator`)

- When a schema is added to `components/schemas`, every `gw:CODE` reference inside it is rewritten to
  `#/components/schemas/CODE` (`gw:CODE#/p` → `#/components/schemas/CODE/p`, with `~`/`/` escaped as the existing
  pointer code does), and CODE itself is added to components (once), recursively.
- A referenced schema is added even if no flow uses it directly.
- Local refs inside a referenced schema are rewritten as today (`rewriteRefs`).
- Result: Swagger UI shows the router body as `oneOf` of `TRANSFER_REQUEST` / `TOPUP_REQUEST` by name.

### 3.4 Studio API specification document (`ApiSpec`, `TestCaseGenerator`)

- Today `TestCaseGenerator.resolve` reduces `oneOf`/`anyOf` to its first option, so the document shows only that
  one. For a request body (and a response body) that is a `oneOf`/`anyOf`, the document shows **one block per
  option**: a heading with the option's name (its component name, i.e. the schema code, or its `title`, else
  "Option n"), that option's field table, and an example for it. The existing "Other responses" handling of
  named-response `oneOf`s (which uses `title`) must keep working.
- The "Request schema" fact row keeps showing the router's own schema code.

### 3.5 Other Studio surfaces (builder's discretion on exact UX)

Items marked *(designer's call)* were settled by the designer after review, not by the user; they may be revisited.

- **Mapping editor fields and preview sample** (`studio.js`, `schemaSample`, used for `$.request.body` and step
  response bodies): follow `gw:` references to the schemas Studio already has loaded (`SCHEMA_TEXT`), and handle
  `oneOf`/`anyOf` (today it ignores them, so a router shows no request fields). *(designer's call)* Merge the
  options: the draggable fields are the union of every option's fields, and the preview sample context is that
  merged body. A merged sample may match no single sub-route; that is acceptable because it only feeds the mapping
  preview, and keeping one schema walk avoids separating the field palette from the sample. Guard against cycles.
- **JSON schemas page** (`fieldsOf`, the FIELDS column and search): also follow `gw:` references and walk
  `oneOf`/`anyOf`, guarding against cycles.
- **Rename / delete / "Used by"** *(designer's call)*: schema-to-schema references count as uses.
  - "Used by" (`schemaUses`) and the delete warning also list schemas that reference the schema through `gw:`.
  - Renaming a schema rewrites references in other schemas' text by plain text replacement of the `$ref` values
    `"gw:OLD"` → `"gw:NEW"` and `"gw:OLD#` → `"gw:NEW#` (no re-serialization, so the user's formatting is kept).
  - Without this, renaming a referenced schema would make the next save fail with "references unknown schema".
- **Generated test cases** (`TestCaseGenerator`): the happy path keeps using the first option.
  *(designer's call)* When the request body schema's root (after resolving references) is a `oneOf`/`anyOf` and
  request validation is off, generate no "Missing required field" cases. The 400 they expect assumes a mapping rule
  rejects the body, which is wrong for a router: the body may match another option, or the sub-route may answer
  with a different status. "Wrong type" cases are already generated only when validation is on; that is unchanged.

### 3.6 Export (`ApplicationExport`)

- Schemas referenced through `gw:` (transitively) by the exported flows' schemas are included in the export, so an
  imported application loads without "references unknown schema" errors.

### 3.7 Documentation

- `docs/CONFIGURATION-GUIDE.md` §3.5 `gw_json_schema`: the `gw:CODE` syntax, the unknown-code error (also in the
  troubleshooting table), a router example (sub-route schemas + router schema + `schema_validation = 'NONE'`), and a
  note on `oneOf` vs `anyOf` if validation is turned on.
- `src/main/resources/assistant/studio-guide.md`: the same, briefly, for the Studio assistant.

## 4. Constraints

- Java 21, Spring Boot 4.1 / Jackson 3, networknt `json-schema-validator` 3.0.8, draft 2020-12.
- PostgreSQL and Oracle; no schema migration.
- Existing schemas without `gw:` references must behave exactly as before (validation results, OpenAPI output).

## 5. Testing

- Unit: compile with references (whole schema and fragment), unknown code error, cycle, referenced schema invalid,
  validation through a reference when validation is on.
- OpenAPI: router flow produces `oneOf` of `$ref`s to `#/components/schemas/<code>`, referenced components present,
  nested/transitive references, fragment references.
- ApiSpec: a router shows one block per option.
- TestCaseGenerator: a router with validation off gets no "Missing required field" cases; a non-router flow is
  unchanged.
- Studio (manual or existing JS checks): router fields appear in the mapping editor; renaming a referenced schema
  updates the referencing schema's text; "Used by" lists referencing schemas.
- Export: referenced-only schemas are included.
- Optional: a dev demo router flow (Liquibase `dev` context) for manual checking in Swagger and Studio.

## 6. Left to the builder

- Exact internal API for the per-load registry and for exposing referenced schemas from `CompiledSchema`.
- Layout and wording of the per-option blocks in the API specification document.
- Whether to add the dev demo router flow.
