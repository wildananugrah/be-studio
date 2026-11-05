# Flow "Schemas" tab — Design (short)

**Date:** 2026-11-05
**Status:** Draft for review

## Intent

A developer working on a flow in Studio wants a **quick, read-only overview** of the data the flow handles: what the
client sends and gets back, and what each call to an external service sends and receives. Today that means opening
each schema on the Schemas screen and each scope of the Mapping tab.

- **Goal:** one tab, between Mapping and Tests, that shows every one of those field lists at a glance, in pipeline
  order, up to date with unsaved edits.
- **Non-goals:** editing (the tab only links to the editors); a partner-facing document (that is the API
  specification); server changes.
- **Anti-goal:** a view that looks authoritative but silently omits fields, e.g. a router's other `oneOf` options.

## Approaches

| | Approach | Trade-off |
|---|---|---|
| **A (chosen)** | Build the field trees in the browser from the Studio draft | Live with unsaved edits, no server work, reuses `schemaSample`-style walkers. A third interpreter of schemas besides Swagger and the API spec, so unusual schemas may render slightly differently. |
| B | Server endpoint returning field trees from the API-spec logic | Always matches the spec document, but only sees the saved config and needs a new endpoint; step bodies built from rules are not covered by that logic. |
| C | Embed the existing API specification | Nearly free, but only covers the client request/response, not steps. |

## Design

**Tab.** "Schemas", between Mapping and Tests; address `#/flow/<CODE>/schemas` (back/forward and reload work as for
the other tabs). Read-only.

**Blocks, in pipeline order** (my call):

1. **Client request**: the flow's request schema; without one, the `$.request.body` fields its rules, templates and
   expressions read. Path variables, query parameters and headers the flow reads are listed as a compact line above
   the body.
2. **One block per external call** (HTTP steps to a target system, in step order; database query, storage, transform
   and condition steps are left out, my call), titled `<order> · <name> → <TARGET>  <METHOD> <path>`, with two
   columns:
   - **Sends**: its STEP_REQUEST rules grouped by type (PATH / QUERY / HEADER / BODY), body fields as a tree from
     the rule targets; with a body template, the template's JSON keys instead. Rules marked *required* show M.
   - **Receives**: its response schema; else its response example (fields and types inferred from the values);
     else the `$.steps.<name>.body` fields later rules, templates and expressions read.
3. **Client response**: the response schema; else the FLOW_RESPONSE rule targets. Then one small block per named
   response (`gw_flow_response`) with its status and rule targets (my call).

**Each block** names its source: a schema code (click → opens it on the Schemas screen), "response example",
"mapping rules" or "fields the flow reads" (click → opens that scope in the Mapping tab).

**Field rows:** indented tree of name, type (`string`, `number`, `object`, `array of …`, or blank when unknown),
M/O (only when known from a schema's `required` or a rule's *required*), and the schema `description` / enum /
format as a tooltip. Arrays show their item fields under `name[]`.

**Schemas** follow `gw:CODE` references, local `$defs` refs and `allOf` (merged). `oneOf` / `anyOf` are shown as
side-by-side (or stacked) **options**, each headed with its schema code or title, never merged (anti-goal above).
Nesting is depth-limited, so reference cycles end with a "…" row.

**Empty states:** a block with nothing known says so in one line ("No schema and no rules read the body").

## Left to the builder

- Exact styling, consistent with the existing Studio tabs.
- Whether options sit side by side or stacked, depending on width.

## Docs

`src/main/resources/assistant/studio-guide.md`: a "Schemas tab" section under "One flow", and the tab in the
screen-address list.
