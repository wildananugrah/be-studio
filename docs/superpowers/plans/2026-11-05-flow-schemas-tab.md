# Flow "Schemas" tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A read-only Studio flow tab, between Mapping and Tests, that shows in pipeline order the fields of the client request, of what each external-service step sends and receives, and of the client response (plus named responses).

**Architecture:** A new browser-free ES module `studio/flow-fields.js` turns the Studio draft (flow object + schema texts) into plain data: `Shape`s (field trees, with `oneOf`/`anyOf` options) and `Block`s (one per pipeline stage). It is unit-tested with `node --test`. `studio.js` imports it and only renders the blocks (`renderSchemasTab`) and wires the tab and its address.

**Tech Stack:** Plain ES modules, Preact + htm (`./vendor/htm-preact.js`) for rendering, Node 24 `node:test` + `node:assert` for tests (no npm, no package.json).

**Spec:** `docs/superpowers/specs/2026-11-05-flow-schemas-tab-design.md`

## Global Constraints

- Tab label **"Schemas"**, placed between Mapping and Tests; address `#/flow/<CODE>/schemas`.
- Read-only: the tab never mutates the draft.
- Built from the **draft** (unsaved edits included): `this.state.cfg` and `SCHEMA_TEXT`.
- External calls only: steps that are not database query (`isSql`), storage (`isStore`), transform (`isTransform`) or condition (`isCondition`) steps.
- `oneOf` / `anyOf` at a body's root are shown as separate options, **never merged**.
- Source labels, verbatim: the schema code, `response example`, `body template`, `mapping rules`, `fields the flow reads`.
- `flow-fields.js` must not touch the DOM, `window` or Preact (so Node can import it).
- No server changes; no new dependencies.
- Commit only the task's files; never stage `src/main/resources/application.yml` / `application-oracle.yml`.
- JS tests: `node --test "src/test/js/*.test.mjs"` (they are not part of the Maven build).

## Review Focus

1. **A router's request** (`{"oneOf":[{"$ref":"gw:A"},{"$ref":"gw:B"}]}`) shows two options labelled `A` and `B` with their own fields. Test: Task 1 `router oneOf gives one option per schema`.
2. **Reference cycles** (`NODE.child → gw:NODE`) end in a `…` row instead of hanging the tab. Test: Task 1 `cycles end in an ellipsis row`.
3. **A schema mid-edit** (invalid JSON, or a `gw:` code that does not exist yet) shows a one-line problem in its block, not a blank or broken tab. Test: Task 1 `missing or invalid schema gives a problem`.
4. **A body template with unquoted placeholders** (`{"amount": ${body.amount}}`) still lists its keys. Test: Task 2 `body template keys with unquoted placeholders`.
5. **Rule targets in every syntax** (`$.a.b`, `$.items[*].sku`, `$['x-y']`, `$` itself) build the right tree, and STATUS rules are ignored. Tests: Task 1 `paths build a tree`, Task 2 `client response ignores STATUS rules`.

---

### Task 1: Field trees from schemas, examples and paths

**Files:**
- Create: `src/main/resources/studio/flow-fields.js`
- Modify: `src/main/resources/studio/studio.js:257` (move `toks` into the new module and import it from there)
- Test: create `src/test/js/flow-fields.test.mjs`

**Interfaces:**
- Produces (exports of `flow-fields.js`):
  - Data types (JSDoc only):
    - `Field = { name: string, type: string, required: boolean|null, notes: string, children: Field[] }`. `type` is `''` when unknown. Array items are a child named `'[]'`. A depth cut-off is a child `{ name: '…', type: '', required: null, notes: 'more levels (or a reference cycle)', children: [] }`.
    - `Option = { label: string|null, fields: Field[] }`
    - `Shape = { options: Option[], problem?: string }`: one unlabelled option for a plain body.
  - `export function toks(path)`, moved verbatim from `studio.js`.
  - `export function schemaShape(code: string, texts: Record<string,string>): Shape`. It follows `gw:CODE[#/ptr]`, local `#/...` refs and `allOf` (merged, including the schema's own `properties` next to it). A root `oneOf`/`anyOf` gives one option per entry, labelled with the entry's `gw:` code, else its `title`, else `Option n`. A nested `oneOf`/`anyOf` becomes type `one of`, with the union of its options' fields as children. `notes` joins description, `enum` ("one of A, B"), `format` and `const`. Maximum depth is 8 levels.
  - `export function exampleShape(value: any): Shape`: types inferred from JSON values (`string`, `number`, `boolean`, `object`, `array of <item type>`, `null` → `''`). `required` is always `null`.
  - `export function pathsShape(paths: string[], required?: Set<string>): Shape`: a tree from JSON paths, all relative to the body (`$.a.b`, `$.items[*].sku`, `$['x-y']`). `$` alone adds nothing. Leaf `type` is `''`. `required` is `true` for paths in `required` and `null` otherwise.

- [ ] **Step 1: Write the failing tests** in `src/test/js/flow-fields.test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { schemaShape, exampleShape, pathsShape } from '../../main/resources/studio/flow-fields.js';

const texts = {
  TRANSFER_REQUEST: '{"type":"object","required":["fromAccount","amount"],"properties":{"fromAccount":{"type":"string","description":"Debit account"},"amount":{"type":"number"}}}',
  TOPUP_REQUEST: '{"type":"object","required":["phoneNo"],"properties":{"phoneNo":{"type":"string"},"amount":{"type":"number"}}}',
  PAYMENT_REQUEST: '{"oneOf":[{"$ref":"gw:TRANSFER_REQUEST"},{"$ref":"gw:TOPUP_REQUEST"}]}',
  NODE: '{"type":"object","properties":{"name":{"type":"string"},"child":{"$ref":"gw:NODE"}}}',
  LIST: '{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"sku":{"type":"string","enum":["A","B"]}}}}}}',
  BROKEN: '{not json',
};
const names = fields => fields.map(f => f.name);

test('plain schema gives one unlabelled option', () => {
  const s = schemaShape('TRANSFER_REQUEST', texts);
  assert.equal(s.options.length, 1);
  assert.equal(s.options[0].label, null);
  assert.deepEqual(s.options[0].fields.map(f => [f.name, f.type, f.required]), [['fromAccount', 'string', true], ['amount', 'number', true]]);
  assert.match(s.options[0].fields[0].notes, /Debit account/);
});
test('router oneOf gives one option per schema', () => {
  const s = schemaShape('PAYMENT_REQUEST', texts);
  assert.deepEqual(s.options.map(o => o.label), ['TRANSFER_REQUEST', 'TOPUP_REQUEST']);
  assert.deepEqual(names(s.options[1].fields), ['phoneNo', 'amount']);
  assert.equal(s.options[1].fields[1].required, false);
});
test('arrays nest their item fields under []', () => {
  const items = schemaShape('LIST', texts).options[0].fields[0];
  assert.equal(items.type, 'array of object');
  assert.equal(items.children[0].name, '[]');
  assert.equal(items.children[0].children[0].name, 'sku');
  assert.match(items.children[0].children[0].notes, /one of A, B/);
});
test('cycles end in an ellipsis row', () => {
  let f = schemaShape('NODE', texts).options[0].fields.find(x => x.name === 'child');
  let depth = 0;
  while (f.children.some(c => c.name === 'child')) { f = f.children.find(c => c.name === 'child'); depth++; }
  assert.ok(depth < 10);
  assert.ok(JSON.stringify(schemaShape('NODE', texts)).includes('"name":"…"'));
});
test('missing or invalid schema gives a problem', () => {
  assert.match(schemaShape('NOPE', texts).problem, /NOPE/);
  assert.match(schemaShape('BROKEN', texts).problem, /BROKEN/);
  assert.deepEqual(schemaShape('BROKEN', texts).options, []);
});
test('example values give types', () => {
  const s = exampleShape({ responseCode: '00', balance: 10.5, ok: true, tags: ['a'], owner: { name: 'x' } });
  assert.deepEqual(s.options[0].fields.map(f => [f.name, f.type]),
    [['responseCode', 'string'], ['balance', 'number'], ['ok', 'boolean'], ['tags', 'array of string'], ['owner', 'object']]);
  assert.equal(s.options[0].fields[4].children[0].name, 'name');
});
test('paths build a tree', () => {
  const s = pathsShape(['$.customer.name', '$.items[*].sku', "$['x-y']", '$', '$.customer.id'], new Set(['$.customer.id']));
  const [customer, items, xy] = s.options[0].fields;
  assert.deepEqual(names(s.options[0].fields), ['customer', 'items', 'x-y']);
  assert.deepEqual(names(customer.children), ['name', 'id']);
  assert.equal(customer.children[1].required, true);
  assert.equal(items.children[0].name, '[]');
  assert.equal(items.children[0].children[0].name, 'sku');
});
```

- [ ] **Step 2: Run the tests and check they fail**

Run: `node --test "src/test/js/*.test.mjs"`
Expected: FAIL with `Cannot find module …/flow-fields.js`.

- [ ] **Step 3: Implement `flow-fields.js`** with the exports above. Move `toks` from `studio.js` into it, and add `import { toks } from './flow-fields.js';` to `studio.js` after the vendor import. No other `studio.js` change in this task.

- [ ] **Step 4: Run the tests and check they pass**

Run: `node --test "src/test/js/*.test.mjs"` and `node --check src/main/resources/studio/studio.js`
Expected: all tests pass; the check prints nothing.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/studio/flow-fields.js src/main/resources/studio/studio.js src/test/js/flow-fields.test.mjs
git commit -m "Studio: field trees from schemas, examples and paths (flow-fields.js)"
```

---

### Task 2: The overview blocks of a flow

**Files:**
- Modify: `src/main/resources/studio/flow-fields.js`
- Test: `src/test/js/flow-fields.test.mjs`

**Interfaces:**
- Consumes: `schemaShape`, `exampleShape`, `pathsShape`, `toks` (Task 1).
- Produces:
  - `Column = { heading: string, source: { label: string, schema?: string, scope?: string }, params: { type: 'PATH'|'QUERY'|'HEADER', names: string[] }[], shape: Shape }`. `schema` is set when the label is a schema code; `scope` is the Mapping-tab scope to open (`'resp'`, `'resp:' + response.id`, or `step.id`).
  - `Block = { key: string, title: string, detail: string, columns: Column[] }`
  - `export function flowOverview(f, { texts, isExternal }): Block[]`, where `f` is a Studio draft flow (`reqSchema`, `respSchema`, `path`, `steps[]`, `response[]`, `responses[]`; rules `{ type, target, source, constant, required }`) and `isExternal(step) → boolean`.

  The blocks, in order:
  1. `{ key: 'request', title: 'Client request' }` with one column `heading: 'Body'`.
     - With `f.reqSchema`: `schemaShape`, source `{ label: code, schema: code }`.
     - Otherwise: `pathsShape` of the `$.request.body…` paths read by any rule `source`, any step `bodyTemplate` slot (`${request.body.x}`) or any `condition`/`success` expression. Source `{ label: 'fields the flow reads' }`.
     - `params`: PATH = the `{vars}` of `f.path` plus `$.request.path.x` reads; QUERY / HEADER = `$.request.query.x` / `$.request.headers.x` reads. Empty types are omitted.
  2. One block per step with `isExternal(step)`, sorted by `order` (stable, so parallel steps keep their array order): `key: step.id`, `title: step.order + ' · ' + step.name + ' → ' + step.target`, `detail: step.method + ' ' + step.path`.
     - Column `Sends`:
       - With a non-empty `bodyTemplate`: `exampleShape(JSON.parse(template with every ${…} replaced by null))`, source `{ label: 'body template', scope: step.id }`. If it still does not parse, use `{ options: [], problem: 'Body template is not valid JSON' }`.
       - Otherwise: `pathsShape` of its BODY rule targets (required from `rule.required`), source `{ label: 'mapping rules', scope: step.id }`.
       - `params` from its PATH / QUERY / HEADER rule targets.
     - Column `Receives`:
       - With `respSchema`: `schemaShape`, source `{ label: code, schema: code }`.
       - Otherwise, with a parsable `example`: `exampleShape`, source `{ label: 'response example' }`.
       - Otherwise: `pathsShape` of the `$.steps.<name>.body…` reads (rules, templates, expressions, as in 1), source `{ label: 'fields the flow reads' }`.
  3. `{ key: 'response', title: 'Client response' }`, column `Body`.
     - With `f.respSchema`: `schemaShape`, source `{ label: code, schema: code }`.
     - Otherwise: `pathsShape` of `f.response` BODY rule targets, source `{ label: 'mapping rules', scope: 'resp' }`.
     - HEADER rules give `params`. STATUS rules are ignored.
  4. One block per named response: `key: 'resp:' + r.id`, `title: "Response '" + r.name + "' · " + r.status`, built like 3 from `r.rules`, with source scope `'resp:' + r.id`.

- [ ] **Step 1: Write the failing tests** (append to `flow-fields.test.mjs`):

```js
import { flowOverview } from '../../main/resources/studio/flow-fields.js';
const rule = (type, target, source = '', extra = {}) => ({ type, target, source, constant: '', required: false, ...extra });
const flow = {
  reqSchema: '', respSchema: '', path: '/accounts/{accountNo}/debit',
  steps: [
    { id: 's1', order: 1, name: 'debit', target: 'CORE', method: 'POST', path: '/debit/{acc}', bodyTemplate: '', respSchema: '', example: '{"responseCode":"00","balance":1}',
      condition: '', success: '${steps.debit.body.responseCode} == \'00\'',
      rules: [rule('PATH', 'acc', '$.request.path.accountNo'), rule('BODY', '$.amount', '$.request.body.amount', { required: true }), rule('HEADER', 'X-Channel', '$.request.headers.x-channel')] },
    { id: 's2', order: 2, name: 'notify', target: 'NOTIFY', method: 'POST', path: '/n', bodyTemplate: '{"to": ${request.body.phone}, "text": "${steps.debit.body.balance}"}', respSchema: '', example: '', condition: '', success: '', rules: [] },
    { id: 's3', order: 3, name: 'shape', target: 'TRANSFORM', method: '', path: '', rules: [rule('BODY', '$.x', '$.steps.debit.body.balance')] },
  ],
  response: [rule('STATUS', 'status', '$.steps.debit.status'), rule('BODY', '$.balance', '$.steps.debit.body.balance'), rule('HEADER', 'X-Ref', '$.steps.debit.body.ref')],
  responses: [{ id: 'o1', name: 'refused', status: '422', rules: [rule('BODY', '$.reason', '$.steps.debit.body.message')] }],
};
const external = s => !['TRANSFORM', 'CONDITION'].includes(s.target);
const blocks = () => flowOverview(flow, { texts, isExternal: external });

test('blocks follow the pipeline and skip non-external steps', () => {
  assert.deepEqual(blocks().map(b => b.key), ['request', 's1', 's2', 'response', 'resp:o1']);
  assert.equal(blocks()[1].title, '1 · debit → CORE');
  assert.equal(blocks()[1].detail, 'POST /debit/{acc}');
});
test('client request without schema lists the fields the flow reads', () => {
  const [col] = blocks()[0].columns;
  assert.equal(col.source.label, 'fields the flow reads');
  assert.deepEqual(col.shape.options[0].fields.map(f => f.name), ['amount', 'phone']);
  assert.deepEqual(col.params, [{ type: 'PATH', names: ['accountNo'] }, { type: 'HEADER', names: ['x-channel'] }]);
});
test('client request with a schema uses it', () => {
  const b = flowOverview({ ...flow, reqSchema: 'PAYMENT_REQUEST' }, { texts, isExternal: external })[0].columns[0];
  assert.deepEqual(b.source, { label: 'PAYMENT_REQUEST', schema: 'PAYMENT_REQUEST' });
  assert.equal(b.shape.options.length, 2);
});
test('a step sends its rules and receives its example', () => {
  const [sends, receives] = blocks()[1].columns;
  assert.deepEqual(sends.source, { label: 'mapping rules', scope: 's1' });
  assert.deepEqual(sends.shape.options[0].fields.map(f => [f.name, f.required]), [['amount', true]]);
  assert.deepEqual(sends.params, [{ type: 'PATH', names: ['acc'] }, { type: 'HEADER', names: ['X-Channel'] }]);
  assert.equal(receives.source.label, 'response example');
  assert.deepEqual(receives.shape.options[0].fields.map(f => f.name), ['responseCode', 'balance']);
});
test('body template keys with unquoted placeholders', () => {
  const [sends, receives] = blocks()[2].columns;
  assert.equal(sends.source.label, 'body template');
  assert.deepEqual(sends.shape.options[0].fields.map(f => f.name), ['to', 'text']);
  assert.equal(receives.source.label, 'fields the flow reads');
  assert.deepEqual(receives.shape.options[0].fields, []);
});
test('client response ignores STATUS rules', () => {
  const [col] = blocks()[3].columns;
  assert.deepEqual(col.source, { label: 'mapping rules', scope: 'resp' });
  assert.deepEqual(col.shape.options[0].fields.map(f => f.name), ['balance']);
  assert.deepEqual(col.params, [{ type: 'HEADER', names: ['X-Ref'] }]);
  assert.equal(blocks()[4].title, "Response 'refused' · 422");
  assert.equal(blocks()[4].columns[0].source.scope, 'resp:o1');
});
```

- [ ] **Step 2: Run the tests and check they fail**

Run: `node --test "src/test/js/*.test.mjs"`
Expected: FAIL (`flowOverview` is not exported).

- [ ] **Step 3: Implement `flowOverview`** as specified in Interfaces. Read paths come from rule `source`s, `${…}` slots of body templates (`request.…` / `steps.…`, part before any `:` default), and `${…}` in `condition` / `success`. Normalise each to a `$.`-prefixed path before filtering by prefix.

- [ ] **Step 4: Run the tests and check they pass**

Run: `node --test "src/test/js/*.test.mjs"`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/studio/flow-fields.js src/test/js/flow-fields.test.mjs
git commit -m "Studio: overview blocks of a flow's request, external calls and responses"
```

---

### Task 3: The Schemas tab in Studio

**Files:**
- Modify: `src/main/resources/studio/studio.js`. Touch points: the tab list (`renderFlow`, line ~1970), the two route parsers that whitelist tabs (lines ~736 and ~2981: add `'schemas'`), the tab switch (`${s.tab === 'schemas' && this.renderSchemasTab(f)}`), and a new method `renderSchemasTab(f)` next to `renderTests`.
- Modify: `src/main/resources/assistant/studio-guide.md` (a "### Schemas tab" section between "Mapping tab" and "Tests tab", and `schemas` in the address list at line ~16)

**Interfaces:**
- Consumes: `flowOverview(f, { texts: SCHEMA_TEXT, isExternal: st => !isSql(st) && !isStore(st) && !isTransform(st) && !isCondition(st) })` (Task 2). Links: `this.openSchema(code)` for `source.schema`, and `this.setState({ tab: 'mapping', scope, preview: null }, () => this.changed())` for `source.scope`.

- [ ] **Step 1: Wire the tab.**
  - Tabs: `[['Pipeline','pipeline'], ['Mapping','mapping'], ['Schemas','schemas'], ['Tests','tests'], ['Rows (SQL)','rows']]`.
  - Add `'schemas'` to both route whitelists.

- [ ] **Step 2: Implement `renderSchemasTab(f)`.**
  - A scrollable column of cards in the existing Studio card style, one card per `Block`.
  - Card header: `title`, with `detail` in mono. Body: one sub-column per `Column` (side by side when there are two).
  - Each column: `heading`, then a source chip. The chip is a button that opens the schema or the mapping scope when the source has `schema`/`scope`, and plain text otherwise.
  - Next: one mono line per `params` entry (`PATH acc, …`), then either `problem` (in the error colour), an empty-state line ("No fields known: no schema, example or rules"), or the options.
  - Each option is headed with its label when it has one, then field rows indented by depth. Each row shows `name`, `type` and `M`/`O` (blank when `required` is `null`), with `notes` as the `title` tooltip.
  - Options sit side by side when the column is wide enough, otherwise stacked (CSS `flex-wrap`).

- [ ] **Step 3: Check syntax and the module tests**

Run: `node --check src/main/resources/studio/studio.js && node --test "src/test/js/*.test.mjs"`
Expected: no syntax errors; all tests pass.

- [ ] **Step 4: Check it by hand in the browser.** Start the app with the dev profile (`./run.sh` or `make run`) and open Studio:
  - (a) A flow with steps: the Schemas tab sits between Mapping and Tests. `#/flow/<CODE>/schemas` survives a reload, and Back/Forward work.
  - (b) A router with `PAYMENT_REQUEST`: two options, each labelled.
  - (c) Clicking a schema chip opens it on the Schemas screen; clicking "mapping rules" opens that scope in Mapping.
  - (d) Editing a rule in Mapping (unsaved) and returning: the tab shows the change.
  - (e) Breaking a schema's JSON: the block shows the problem and the rest of the tab still renders.
  - (f) A self-referencing schema ends in a `…` row.

- [ ] **Step 5: Document it** in `studio-guide.md`:
  - What each block shows and where its fields come from (the source chips and their links).
  - That it is read-only and follows unsaved edits.
  - That database, storage, transform and condition steps are not shown.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/studio/studio.js src/main/resources/assistant/studio-guide.md
git commit -m "Studio: Schemas tab with a flow's request, external calls and responses"
```
