# Schema references (`gw:CODE`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a `gw_json_schema` reference other gateway schemas with `{"$ref": "gw:CODE"}`, so a router flow's request schema can be a `oneOf` of its sub-routes' schemas and is documented as such in Swagger and the Studio API specification.

**Architecture:** `CompiledSchema` compiles all schemas of a config load together against a per-load networknt registry that maps `gw:<code>` to each schema's text, checks every `gw:` reference, and exposes the schemas each one references. The OpenAPI generator rewrites `gw:` refs to `#/components/schemas/<code>` and adds referenced schemas to components; the API specification, test-case generator, export and Studio UI follow those references.

**Tech Stack:** Java 25 build (Java 21 language level), Spring Boot 4.1, Jackson 3 (`tools.jackson`), networknt `json-schema-validator` 3.0.8 (draft 2020-12), JUnit 5 + AssertJ, Preact/htm in `studio.js` (no JS test suite).

**Spec:** `docs/superpowers/specs/2026-11-05-schema-references-design.md`

## Global Constraints

- JSON Schema draft 2020-12; networknt `json-schema-validator` 3.0.8. No new dependencies.
- PostgreSQL and Oracle; **no database / Liquibase change**. `request_schema_code` stays a single code.
- Existing schemas without `gw:` references must behave exactly as before (validation results, error messages, OpenAPI output).
- Reference syntax: `gw:CODE` (whole schema) and `gw:CODE#/<json-pointer>` (inside it). Codes are case-sensitive.
- Unknown-code error text: `json schema '<REFERRER>': $ref 'gw:<CODE>' references unknown schema '<CODE>'`.
- Reference cycles (self or mutual) are allowed and must never loop forever (load, OpenAPI, spec, Studio).
- Commit only the files of the task. Never stage `src/main/resources/application.yml` or `src/main/resources/application-oracle.yml`.
- Run tests with `./mvnw -B test -Dtest=<Class>` (unit tests need no Docker; `*IntegrationTest` classes need Docker/Testcontainers).

## Review Focus

1. **Schema codes that are not IRI-safe** (e.g. `MY SCHEMA`) and are not referenced must still compile exactly as before. Roots are compiled from their text, not by `gw:` location. Test: Task 1 `codeThatIsNotIriSafeStillCompiles`.
2. **Local refs inside a referenced schema** (`#/$defs/x` in SHARED, reached from the router) must point into SHARED's component, not the router's. Test: Task 2 `referencedSchemasKeepTheirOwnLocalRefsAndFragments`.
3. **A sub-schema that a sub-route flow uses directly and the router also references** appears once in components, and both operations point at it. Test: Task 2 `routerRequestIsOneOfNamedComponents`.
4. **Renaming `OLD` must not touch `gw:OLD_X`** (prefix collision) in Studio's rename rewrite. Check: Task 6 manual step 4.
5. **Cyclic references** terminate everywhere: validation, OpenAPI, the API spec and Studio's sample/field walks. Tests: Task 1 `cyclesCompileAndValidate`, Task 2 `cyclicReferencesTerminate`; Studio relies on its existing depth limits (Task 6 manual step 3).

---

### Task 1: Compile schemas together and check `gw:` references

**Files:**
- Modify: `src/main/java/com/mhamzah/gateway/schema/CompiledSchema.java`
- Modify: `src/main/java/com/mhamzah/gateway/config/ConfigCompiler.java:438-448` (`compileSchemas`)
- Modify: `docs/CONFIGURATION-GUIDE.md` §3.5 (line ~443) and the troubleshooting table (line ~1524)
- Test: `src/test/java/com/mhamzah/gateway/schema/CompiledSchemaTest.java`, `src/test/java/com/mhamzah/gateway/config/ConfigCompilerTest.java`

**Interfaces:**
- Produces (in `CompiledSchema`):
  - `public static final String REF_PREFIX = "gw:";`
  - `public record Compilation(Map<String, CompiledSchema> schemas, Map<String, List<String>> errors) {}`: `errors` is keyed by schema code, and each message has no `json schema '<code>': ` prefix.
  - `public static Compilation compileAll(Map<String, String> textsByCode)`
  - `public static Set<String> referencedCodes(JsonNode schema)`: the direct `gw:` codes in a schema document, as a `LinkedHashSet` in document order (fragment stripped; `"gw:"` gives `""`).
  - `public List<CompiledSchema> references()`: every schema reachable through `gw:` refs, transitively, each once, in breadth-first discovery order, never including `this`.
  - `compile(String code, String schemaText)` stays. It delegates to `compileAll(Map.of(code, schemaText))` and throws `IllegalArgumentException` with the first error, so the message text is unchanged.

- [ ] **Step 1: Write the failing tests** in `CompiledSchemaTest`. Use these schema texts (they are reused by later tasks):

```java
static final String TRANSFER = """
        {"type":"object","required":["fromAccount","amount"],
         "properties":{"fromAccount":{"type":"string"},"amount":{"type":"number"}}}""";
static final String TOPUP = """
        {"type":"object","required":["phoneNo","amount"],
         "properties":{"phoneNo":{"type":"string"},"amount":{"type":"number"}}}""";
static final String PAYMENT = """
        {"oneOf":[{"$ref":"gw:TRANSFER_REQUEST"},{"$ref":"gw:TOPUP_REQUEST"}]}""";

@Test void compileAllResolvesReferencesForValidation() {
    var c = CompiledSchema.compileAll(Map.of("TRANSFER_REQUEST", TRANSFER, "TOPUP_REQUEST", TOPUP, "PAYMENT_REQUEST", PAYMENT));
    assertThat(c.errors()).isEmpty();
    CompiledSchema p = c.schemas().get("PAYMENT_REQUEST");
    assertThat(p.validate(mapper.readTree("{\"fromAccount\":\"1\",\"amount\":5}"))).isEmpty();
    assertThat(p.validate(mapper.readTree("{\"phoneNo\":\"0812\",\"amount\":5}"))).isEmpty();
    assertThat(p.validate(mapper.readTree("{\"amount\":5}"))).isNotEmpty();
}
@Test void fragmentReference() {
    // SHARED = {"$defs":{"money":{"type":"number","minimum":1}}}
    // AMOUNT = {"type":"object","properties":{"amount":{"$ref":"gw:SHARED#/$defs/money"}}}
    // validate {"amount":0} -> exactly one message, starting with "$.amount: "
}
@Test void unknownReferenceIsAnErrorOfTheReferencingSchema() {
    var c = CompiledSchema.compileAll(Map.of("PAYMENT_REQUEST", "{\"oneOf\":[{\"$ref\":\"gw:NOPE\"},{\"$ref\":\"gw:\"}]}"));
    assertThat(c.errors().get("PAYMENT_REQUEST")).containsExactly(
            "$ref 'gw:NOPE' references unknown schema 'NOPE'", "$ref 'gw:' references unknown schema ''");
}
@Test void fragmentPointingToNothingIsAnError() {
    // SHARED = {"$defs":{}} ; R = {"$ref":"gw:SHARED#/$defs/nope"}
    // errors.get("R") contains "$ref 'gw:SHARED#/$defs/nope' points to nothing in schema 'SHARED'"
}
@Test void cyclesCompileAndValidate() {
    // NODE = {"type":"object","properties":{"child":{"$ref":"gw:NODE"}}}
    // A = {"type":"object","properties":{"b":{"$ref":"gw:B"}}} ; B = {"type":"object","properties":{"a":{"$ref":"gw:A"}}}
    // no errors; NODE.validate({"child":{"child":{"child":5}}}) has one message starting "$.child.child.child: "
    // NODE.references() is empty; A.references() maps to codes ["B"]
}
@Test void referencesAreTransitiveEachOnceInDiscoveryOrder() {
    // R = {"oneOf":[{"$ref":"gw:A"},{"$ref":"gw:B"}]} ; A = {"$ref":"gw:C"} ; B = {"$ref":"gw:C"} ; C = {"type":"string"}
    // R.references() codes == [A, B, C]
}
@Test void invalidReferencedSchemaDoesNotBreakTheReferencer() {
    // BROKEN = {"type":42} ; R = {"$ref":"gw:BROKEN"}
    // compileAll does not throw; errors has key BROKEN (meta-schema message); errors has no "unknown schema" for R
}
@Test void codeThatIsNotIriSafeStillCompiles() {
    // Map.of("MY SCHEMA", SCHEMA) -> no errors, schema validates {"amount":10} with no messages
}
@Test void referencedCodesListsDirectReferencesOnly() {
    // referencedCodes(PAYMENT) == [TRANSFER_REQUEST, TOPUP_REQUEST]; local "#/$defs/x" refs are ignored
}
```

In `ConfigCompilerTest`:

```java
@Test void rule2_unknownSchemaReference() {
    rows.schema("PAYMENT_REQUEST", "{\"oneOf\":[{\"$ref\":\"gw:NOPE\"}]}");
    assertInvalid("json schema 'PAYMENT_REQUEST': $ref 'gw:NOPE' references unknown schema 'NOPE'");
}
```

- [ ] **Step 2: Run the tests and check they fail**

Run: `./mvnw -B test -Dtest='CompiledSchemaTest,ConfigCompilerTest'`
Expected: compilation errors (`compileAll`, `references`, `referencedCodes` not defined).

- [ ] **Step 3: Implement `compileAll`** in `CompiledSchema`, in this order:
  1. Parse and meta-validate each text exactly as `compile` does today. On failure, record the existing message (`Schema '<code>' is not valid JSON: …` / `… is not a valid draft 2020-12 schema: …`) and leave that schema out of the registry.
  2. For each schema that parsed, walk its tree for `$ref` string values that start with `REF_PREFIX`. A code that is not a key of `textsByCode` gets the unknown-code message. A fragment where `targetNode.at(fragment.substring(1))` is missing gets `"$ref '<ref>' points to nothing in schema '<code>'"`, but only if the target parsed.
  3. Build one registry per call: `SchemaRegistry.withDefaultDialect(DRAFT_2020_12, b -> b.schemaRegistryConfig(<same JSON_PATH config>).schemas(<"gw:"+code -> text for parsed schemas>))`. Keep the static `META_SCHEMA` and its registry for meta-validation.
  4. Compile each parsed schema without reference errors via `registry.getSchema(text, InputFormat.JSON)`, **not** by `gw:` location (Review Focus 1). Catch `RuntimeException` and record `Schema '<code>' cannot be loaded: …`.
  5. After all are compiled, give each `CompiledSchema` its direct references (codes resolved against `schemas`, skipping failed ones). `references()` then computes the transitive list with a visited set.

  If `schemas(Map)` does not resolve the `gw:` scheme (the `compileAllResolvesReferencesForValidation` test fails on resolution), register the texts through `resourceLoaders(...)` with a `MapResourceLoader` instead. Same observable behavior.

- [ ] **Step 4: Switch `ConfigCompiler.compileSchemas`** to call `CompiledSchema.compileAll` once (map code → text from `rows.schemas()`, in row order). Add `"json schema '" + code + "': " + message` for every error and return `compilation.schemas()`. `schema(...)` (line ~1185) is unchanged.

- [ ] **Step 5: Run the tests and check they pass**

Run: `./mvnw -B test -Dtest='CompiledSchemaTest,ConfigCompilerTest,StartupValidationTest,OpenApiGeneratorTest'`
Expected: PASS (the existing tests show no-reference behavior is unchanged).

- [ ] **Step 6: Document it** in `docs/CONFIGURATION-GUIDE.md`:
  - §3.5: a paragraph on `{"$ref": "gw:CODE"}` / `gw:CODE#/$defs/x`. Cover: references are checked at load, cycles are allowed, and with validation on the standard semantics apply (`oneOf` = exactly one match, `anyOf` = at least one).
  - Troubleshooting table: a row `$ref 'gw:X' references unknown schema` | a `gw:` reference to a code with no `gw_json_schema` row | Insert the schema or fix the reference. Add a row for `points to nothing in schema`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mhamzah/gateway/schema/CompiledSchema.java src/main/java/com/mhamzah/gateway/config/ConfigCompiler.java \
        src/test/java/com/mhamzah/gateway/schema/CompiledSchemaTest.java src/test/java/com/mhamzah/gateway/config/ConfigCompilerTest.java \
        docs/CONFIGURATION-GUIDE.md
git commit -m "Resolve gw:CODE references between JSON schemas"
```

---

### Task 2: OpenAPI: `gw:` refs become named components

**Files:**
- Modify: `src/main/java/com/mhamzah/gateway/docs/OpenApiGenerator.java:307-332` (`ref`, `rewriteRefs`)
- Modify: `docs/CONFIGURATION-GUIDE.md` (router example, §3.5)
- Test: `src/test/java/com/mhamzah/gateway/docs/OpenApiGeneratorTest.java`

**Interfaces:**
- Consumes: `CompiledSchema.references()`, `CompiledSchema.REF_PREFIX` (Task 1).
- Produces: in the OpenAPI document, every `gw:CODE[#frag]` becomes `#/components/schemas/<escaped CODE>[frag without '#']`, and every referenced schema is present under `components/schemas/<CODE>`. Tasks 3 and 4 rely on this.

- [ ] **Step 1: Write the failing tests** in `OpenApiGeneratorTest`, using the Task 1 texts (copy the three constants):

```java
@Test void routerRequestIsOneOfNamedComponents() {
    rows.schema("TRANSFER_REQUEST", TRANSFER); rows.schema("TOPUP_REQUEST", TOPUP); rows.schema("PAYMENT_REQUEST", PAYMENT);
    rows.flow("TRANSFER", "POST", "/transfers", f -> f.withSchemas("TRANSFER_REQUEST", null));
    rows.flow("PAYMENT", "POST", "/payments", f -> f.withSchemas("PAYMENT_REQUEST", null).withSchemaValidation("NONE"));
    JsonNode doc = generate();
    JsonNode schemas = doc.get("components").get("schemas");
    assertThat(schemas.get("PAYMENT_REQUEST").get("oneOf").get(0).get("$ref").asString()).isEqualTo("#/components/schemas/TRANSFER_REQUEST");
    assertThat(schemas.get("PAYMENT_REQUEST").get("oneOf").get(1).get("$ref").asString()).isEqualTo("#/components/schemas/TOPUP_REQUEST");
    assertThat(schemas.get("TOPUP_REQUEST").get("required").toString()).isEqualTo("[\"phoneNo\",\"amount\"]"); // only referenced
    assertThat(doc.get("paths").get("/api/transfers").get("post").get("requestBody").get("content").get("application/json")
            .get("schema").get("$ref").asString()).isEqualTo("#/components/schemas/TRANSFER_REQUEST");
}
@Test void referencedSchemasKeepTheirOwnLocalRefsAndFragments() {
    // SHARED = {"$defs":{"money":{"type":"number"},"amount":{"$ref":"#/$defs/money"}}}
    // R = {"type":"object","properties":{"a":{"$ref":"gw:SHARED#/$defs/amount"}}}, used as request schema of a POST flow
    // R.properties.a.$ref == "#/components/schemas/SHARED/$defs/amount"
    // SHARED.$defs.amount.$ref == "#/components/schemas/SHARED/$defs/money"
}
@Test void cyclicReferencesTerminate() {
    // NODE = {"type":"object","properties":{"child":{"$ref":"gw:NODE"}}}, request schema of a POST flow
    // NODE.properties.child.$ref == "#/components/schemas/NODE"
}
```

- [ ] **Step 2: Run the tests and check they fail**

Run: `./mvnw -B test -Dtest=OpenApiGeneratorTest`
Expected: FAIL, because the `$ref` values are still `gw:...` and `TOPUP_REQUEST` is missing from components.

- [ ] **Step 3: Implement it** in `OpenApiGenerator`:
  - Extract `private static String pointer(String code)` (the existing `~0`/`~1` escaping).
  - `ref(schema, schemas)` adds the schema (as today) and then each of `schema.references()` the same way, skipping codes already present.
  - `rewriteRefs` also maps `$ref` values that start with `CompiledSchema.REF_PREFIX` to `pointer(code) + fragment-without-#`. Local `#...` refs keep using the owning schema's pointer.

- [ ] **Step 4: Run the tests and check they pass**

Run: `./mvnw -B test -Dtest=OpenApiGeneratorTest`
Expected: PASS (including the existing `schemasFromDatabaseAreReferencedWithInternalRefsRewritten`).

- [ ] **Step 5: Document the router example** in `docs/CONFIGURATION-GUIDE.md` §3.5. Show SQL inserting `TRANSFER_REQUEST`, `TOPUP_REQUEST` and `PAYMENT_REQUEST` (the `oneOf`), and the router flow with `request_schema_code = 'PAYMENT_REQUEST'` and `schema_validation = 'NONE'`. Add one sentence: Swagger shows the body as one of the named schemas, and validation stays with the sub-routes.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mhamzah/gateway/docs/OpenApiGenerator.java src/test/java/com/mhamzah/gateway/docs/OpenApiGeneratorTest.java docs/CONFIGURATION-GUIDE.md
git commit -m "Document gw:CODE references as OpenAPI components"
```

---

### Task 3: API specification shows one block per option

**Files:**
- Modify: `src/main/java/com/mhamzah/gateway/studio/testing/TestCaseGenerator.java` (add `options`)
- Modify: `src/main/java/com/mhamzah/gateway/studio/testing/ApiSpec.java:233-305` (request body + main response body)
- Test: create `src/test/java/com/mhamzah/gateway/studio/testing/ApiSpecTest.java`

**Interfaces:**
- Consumes: the OpenAPI document from Task 2 (`OpenApiGenerator.generate(registry, "/api", "Test API", null)`).
- Produces (in `TestCaseGenerator`):
  - `record Option(String label, JsonNode schema) {}`
  - `static List<Option> options(JsonNode openApi, JsonNode schema)`: follows `$ref`s only (not into `oneOf`). If the node it reaches has `oneOf`/`anyOf`, it returns one `Option` per entry: the label is the component name when the entry is `{"$ref":"#/components/schemas/X"}` (unescaped `~1`→`/`, `~0`→`~`), else its `title`, else `"Option <n>"`, and `schema` is `resolve(entry)`. Otherwise it returns a single `Option(null, resolve(schema))`.

- [ ] **Step 1: Write the failing test** `ApiSpecTest`. Compile `Rows` with a `ConfigCompiler` set up as in `OpenApiGeneratorTest.generate`, using the Task 2 router setup (TRANSFER + PAYMENT flows, three schemas). Then:

```java
JsonNode doc = OpenApiGenerator.generate(registry, "/api", "Test API", null);
// one flow per document: spec(code) = markdown of ApiSpec.build for that flow alone
String spec(String code) {
    List<FlowDefinition> one = registry.flows().stream().filter(f -> f.code().equals(code)).toList();
    return ReportRenderers.markdown(ApiSpec.build(doc, ApiSpec.endpoints(doc, one, Map.of()),
            "Spec", "http://localhost:8080", ZonedDateTime.parse("2026-11-05T10:00:00Z")));
}
assertThat(spec("PAYMENT")).contains("Body option 1 of 2: TRANSFER_REQUEST", "Body option 2 of 2: TOPUP_REQUEST",
        "| fromAccount |", "| phoneNo |", "Example request (TRANSFER_REQUEST):", "Example request (TOPUP_REQUEST):",
        "\"phoneNo\"");
assertThat(spec("TRANSFER")).doesNotContain("Body option").contains("Example request:");
```

Add `responseOneOfShowsOptionsToo`: a flow whose `response_schema_code` is `PAYMENT_REQUEST` gives `"Body option 1 of 2: TRANSFER_REQUEST"` under Response, plus `"Example response (TOPUP_REQUEST):"`. Also assert that an existing named-response flow still lists its titled shapes under "Other responses" (reuse the setup of the named-response test in `OpenApiGeneratorTest`).

- [ ] **Step 2: Run the test and check it fails**

Run: `./mvnw -B test -Dtest=ApiSpecTest`
Expected: FAIL (no "Body option" text).

- [ ] **Step 3: Implement `TestCaseGenerator.options`** with the signature above.

- [ ] **Step 4: Use it in `ApiSpec.endpoint`**:
  - Request body: replace the single `resolve` with `options(doc, content schema)`. With one option, output is unchanged. With several, emit for each option a `Paragraph("Body option <i> of <n>: <label> (<contentType>[, required]):")`, its field table, and its example. The example request code block repeats per option after `Paragraph("Example request (<label>):")`.
  - Main response: compute options from `okSchema.has("oneOf") ? okSchema.get("oneOf").get(0) : okSchema`. A named-response `oneOf` is built by the generator and its first entry is the default body, so "Other responses" is untouched. Several options give the same per-option pattern (`Body option …`, table, `Example response (<label>):`).

- [ ] **Step 5: Run the tests and check they pass**

Run: `./mvnw -B test -Dtest='ApiSpecTest,ReportRenderersTest'`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mhamzah/gateway/studio/testing/TestCaseGenerator.java src/main/java/com/mhamzah/gateway/studio/testing/ApiSpec.java \
        src/test/java/com/mhamzah/gateway/studio/testing/ApiSpecTest.java
git commit -m "Show every oneOf/anyOf body option in the API specification"
```

---

### Task 4: No "Missing required field" cases for unvalidated routers

**Files:**
- Modify: `src/main/java/com/mhamzah/gateway/studio/testing/TestCaseGenerator.java:98-133`
- Test: create `src/test/java/com/mhamzah/gateway/studio/testing/TestCaseGeneratorTest.java`

**Interfaces:**
- Consumes: `TestCaseGenerator.options(JsonNode, JsonNode)` (Task 3); `TestCaseGenerator.generate(JsonNode openApi, FlowDefinition flow, String apiBasePath)` (existing).

- [ ] **Step 1: Write the failing test.** Use the same registry setup as `ApiSpecTest`, plus a third flow `TRANSFER_NV` (`POST /transfers-nv`, `TRANSFER_REQUEST`, `schema_validation` `NONE`):

```java
@Test void unvalidatedRouterGetsNoMissingFieldCases() {
    List<TestCase> cases = TestCaseGenerator.generate(doc, flow("PAYMENT"), "/api");
    assertThat(cases).extracting(TestCase::name).noneMatch(n -> n.startsWith("Missing required field"));
    assertThat(cases.getFirst().body().has("fromAccount")).isTrue();   // happy path uses the first option
}
@Test void unvalidatedPlainFlowStillGetsThem() {
    assertThat(TestCaseGenerator.generate(doc, flow("TRANSFER_NV"), "/api")).extracting(TestCase::name)
            .contains("Missing required field 'fromAccount'");
}
```

(`flow(code)` looks the code up in `registry.flows()`. Check the `TestCase` body accessor name in `TestModel.java`.)

- [ ] **Step 2: Run the test and check it fails**

Run: `./mvnw -B test -Dtest=TestCaseGeneratorTest`
Expected: `unvalidatedRouterGetsNoMissingFieldCases` FAILS.

- [ ] **Step 3: Implement it.** Compute `boolean choice = op.has("requestBody") && options(doc, <request content schema>).size() > 1;` and generate the missing-required-field loop only when `validated || !choice`. Wrong-type cases are unchanged.

- [ ] **Step 4: Run the tests and check they pass**

Run: `./mvnw -B test -Dtest='TestCaseGeneratorTest,ApiSpecTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mhamzah/gateway/studio/testing/TestCaseGenerator.java src/test/java/com/mhamzah/gateway/studio/testing/TestCaseGeneratorTest.java
git commit -m "Skip missing-field test cases for unvalidated oneOf/anyOf request bodies"
```

---

### Task 5: Export brings referenced schemas along

**Files:**
- Modify: `src/main/java/com/mhamzah/gateway/studio/ApplicationExport.java:72-101` (`bundle`)
- Test: create `src/test/java/com/mhamzah/gateway/studio/ApplicationExportTest.java`

**Interfaces:**
- Consumes: `CompiledSchema.referencedCodes(JsonNode)` (Task 1); `ApplicationExport.bundle(StudioConfig, String)` (existing, package-private), returning a `Bundle` with `schemas()`.

- [ ] **Step 1: Write the failing test.** Build a `StudioConfig` like `StudioRowsTest.config`, with one flow in application `PAY` whose request schema is `PAYMENT_REQUEST`, and these schemas:
  - `PAYMENT_REQUEST` = `{"oneOf":[{"$ref":"gw:TRANSFER_REQUEST"},{"$ref":"gw:TOPUP_REQUEST"}]}`
  - `TRANSFER_REQUEST` = `{"$ref":"gw:SHARED#/$defs/acct"}`
  - `TOPUP_REQUEST` = `{"type":"object"}`
  - `SHARED` = `{"$defs":{"acct":{"type":"object"}}}`
  - `UNRELATED` = `{"type":"string"}`

  Also add `LOOP` = `{"$ref":"gw:LOOP"}`, used as another flow's response schema, to check that cycles terminate.

```java
assertThat(ApplicationExport.bundle(config, "PAY").schemas()).extracting(Schema::code)
        .containsExactlyInAnyOrder("PAYMENT_REQUEST", "TRANSFER_REQUEST", "TOPUP_REQUEST", "SHARED", "LOOP");
```

- [ ] **Step 2: Run the test and check it fails**

Run: `./mvnw -B test -Dtest=ApplicationExportTest`
Expected: FAIL (`TRANSFER_REQUEST`, `TOPUP_REQUEST` and `SHARED` missing).

- [ ] **Step 3: Implement it.** After the flow loop, expand `schemaCodes` to a fixed point. Repeatedly parse each newly added code's text from `config.schemas()` and add `CompiledSchema.referencedCodes(...)`. Skip unparsable texts and unknown codes; unknown codes still end up in `schemasNotFound` as today.

- [ ] **Step 4: Run the tests and check they pass**

Run: `./mvnw -B test -Dtest='ApplicationExportTest,StudioRowsTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mhamzah/gateway/studio/ApplicationExport.java src/test/java/com/mhamzah/gateway/studio/ApplicationExportTest.java
git commit -m "Export schemas referenced through gw:CODE"
```

---

### Task 6: Studio UI follows `gw:` references

**Files:**
- Modify: `src/main/resources/studio/studio.js`: `schemaSample` (line ~75), `schemaUses` (line ~402), `renderSchemas` `rename` (line ~1868), `fieldsOf` (line ~1878), the delete-schema confirmation text (line ~1936)
- Modify: `src/main/resources/assistant/studio-guide.md` "Schemas screen" (line ~147)

**Interfaces:**
- Consumes: `SCHEMA_TEXT` (code → text of the draft, existing global); `s.cfg.schemas` (`{code, desc, text}`).
- Produces (module-level helpers in `studio.js`, next to `schemaUses`):
  - `const gwRef = ref => /^gw:/.test(ref || '') ? { code: ref.slice(3).split('#')[0], frag: ref.includes('#') ? ref.slice(ref.indexOf('#') + 1) : '' } : null;`
  - `const refsTo = (text, code) => String(text || '').includes('"gw:' + code + '"') || String(text || '').includes('"gw:' + code + '#');`

- [ ] **Step 1: `schemaSample`.** When `schema.$ref` is a `gw:` ref, parse `SCHEMA_TEXT[code]` (ignore missing or invalid). Follow the fragment's `/`-separated keys, then recurse with that document as the new `root` and `depth + 1`. When the schema has `oneOf`/`anyOf`/`allOf`, recurse into every entry with the same `base`, which gives the union of all options' fields in the draggable fields and the preview sample (the designer's call in spec §3.5). The existing `depth > 6` cut-off bounds cycles.

- [ ] **Step 2: Schemas screen.**
  - `fieldsOf` also walks `oneOf`/`anyOf`/`allOf` entries and `gw:` refs, looking texts up in `s.cfg.schemas`, with a `seen` set of codes.
  - `schemaUses(cfg, code)` appends `'schema ' + x.code` for every other schema where `refsTo(x.text, code)`.
  - `rename` also rewrites every schema's `text`: `'"gw:' + old + '"'` → `'"gw:' + v + '"'` and `'"gw:' + old + '#'` → `'"gw:' + v + '#'` (`split/join`, no re-serialization).
  - The delete confirmation's "No flow or step uses it." becomes "Nothing uses it."

- [ ] **Step 3: Manually check routers and cycles.** Start the app in the dev profile (`make run` or `./run.sh`; Postgres via Docker), open Studio → Schemas, and create `TRANSFER_REQUEST`, `TOPUP_REQUEST` and `PAYMENT_REQUEST` as in Task 1. Create a POST flow with request schema `PAYMENT_REQUEST` and Save & reload. Expected:
  - The Mapping tab's CONTEXT lists `fromAccount`, `phoneNo` and `amount` under `request.body`.
  - The Schemas table shows those fields for `PAYMENT_REQUEST`, and "Used by" for `TOPUP_REQUEST` includes `schema PAYMENT_REQUEST`.
  - `/docs` (Swagger) shows the body as one of the two schemas.
  - A self-referencing schema (`{"type":"object","properties":{"child":{"$ref":"gw:NODE"}}}`) on a flow does not hang the Mapping tab.

- [ ] **Step 4: Manually check rename and delete.**
  - Add a schema `TOPUP_REQUEST_X` and a reference `{"$ref":"gw:TOPUP_REQUEST_X"}` inside some other schema.
  - Rename `TOPUP_REQUEST` to `TOPUP_REQ`. Expected: `PAYMENT_REQUEST`'s text now says `gw:TOPUP_REQ` with its formatting kept, the `gw:TOPUP_REQUEST_X` reference is untouched (Review Focus 4), and Save & reload succeeds.
  - Delete `TOPUP_REQ`. Expected: the warning lists `schema PAYMENT_REQUEST`, and saving is rejected with `references unknown schema 'TOPUP_REQ'`.

- [ ] **Step 5: Update `studio-guide.md` "Schemas screen".** Cover:
  - `{"$ref":"gw:CODE"}` references, and router schemas as a `oneOf` of sub-route schemas.
  - USED BY includes referencing schemas.
  - Rename updates `gw:` references.
  - A router's mapping fields are the union of its options.

- [ ] **Step 6: Run the Studio integration tests** (requires Docker)

Run: `./mvnw -B test -Dtest='StudioIntegrationTest,ApplicationExportIntegrationTest'`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/studio/studio.js src/main/resources/assistant/studio-guide.md
git commit -m "Studio: follow gw:CODE schema references in mapping, schemas list, rename and delete"
```

---

### Task 7: Full verification

- [ ] **Step 1: Run the whole suite** (requires Docker): `./mvnw -B test`. Expected: BUILD SUCCESS.
- [ ] **Step 2:** `git status --short` shows `application.yml` / `application-oracle.yml` still modified and unstaged, with nothing else left over.
