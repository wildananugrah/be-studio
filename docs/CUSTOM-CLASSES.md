# Custom Classes Guide

How to add Java logic to the gateway when database configuration is not enough. Covers when to write one, how to build, test and wire it, five working examples, and the API.

- [1. When do you need a custom class?](#1-when-do-you-need-a-custom-class)
- [2. The interfaces and where they run](#2-the-interfaces-and-where-they-run)
- [3. Step by step: build your own](#3-step-by-step-build-your-own)
- [4. The examples in this repository](#4-the-examples-in-this-repository)
- [5. API reference](#5-api-reference)
- [6. Settings and secrets for your class](#6-settings-and-secrets-for-your-class)
- [7. Rules for writing handlers](#7-rules-for-writing-handlers)
- [8. Troubleshooting](#8-troubleshooting)

---

## 1. When do you need a custom class?

Use configuration first ([CONFIGURATION-GUIDE.md](CONFIGURATION-GUIDE.md)). Write a class only for what config can't express:

| You need… | Config can do it? | Use |
|---|---|---|
| Rename/move fields, constants, defaults, arrays | ✔ mapping rules | |
| Format dates, pad numbers, change case | ✔ converters | |
| Translate codes (`A` → `ACTIVE`) | ✔ lookups | |
| Translate downstream error codes into your error response | ✔ lookups + 10-line class | `LookupErrorHandler` ([4.5](#45-corebankingerrorhandler--lookuperrorhandler-the-easy-way-for-error-codes)) |
| Formatting config can't express (currency text, masking, check digits) | ✘ | `FieldHandler` |
| Signatures, tokens, encryption, computed headers | ✘ | `MessageHandler` |
| Reject or answer a request before calling anything | ✘ | `MessageHandler` at the flow request hook |
| An error format config can't produce | ✘ | `ErrorHandler` |
| Call a plain XML or SOAP downstream | ✔ `body_codec = xmlCodec / soapCodec / soap12Codec` ([guide §4.15](CONFIGURATION-GUIDE.md#415-xml-or-soap-downstream)) | |
| SOAP header, WS-Security, signed or unusual XML | ✘ | `BodyCodec` ([4.6](#46-partnersoapcodec-bodycodec-soap-with-a-ws-security-header)) |

---

## 2. The interfaces and where they run

```
client request
   │
   ├─ [flow request_handler]       MessageHandler  ← can short-circuit (return a response)
   │
   ├─ for each step:
   │     STEP_REQUEST rules ──── [field_handler on a rule]   FieldHandler
   │     [step request_handler]    MessageHandler  ← sign, add tokens, last-minute changes
   │     [body_codec].encode       BodyCodec       ← JSON → wire format (XML, SOAP, …)
   │     ──► HTTP call ──►
   │     [body_codec].decode       BodyCodec       ← wire format → JSON
   │     [step response_handler]   MessageHandler  ← decrypt, normalise the downstream answer
   │
   ├─ FLOW_RESPONSE rules ──── [field_handler on a rule]   FieldHandler
   ├─ [flow response_handler]      MessageHandler
   │
   └─ on any failure:  [flow error_handler]   ErrorHandler   → the error response
```

| Interface | Method | Configured in | Gets | Returns |
|---|---|---|---|---|
| `FieldHandler` | `JsonNode handle(JsonNode value, ExecutionContext ctx)` | `gw_mapping_rule.field_handler` | one value (null if missing) | the new value (null = missing) |
| `MessageHandler` | `GatewayResponse handle(MessageView message, ExecutionContext ctx)` | `gw_flow.request_handler` / `response_handler`, `gw_flow_step.request_handler` / `response_handler` | mutable headers + JSON body | `null` to continue; a response to short-circuit (**flow request hook only**) |
| `ErrorHandler` | `GatewayResponse handle(GatewayError error, ExecutionContext ctx)` | `gw_flow.error_handler` | the typed error | the client response |
| `BodyCodec` | `String encode(JsonNode body, Map headers, ExecutionContext ctx)` / `JsonNode decode(String body, Map headers)` / `String contentType()` | `gw_target_system.body_codec`, `gw_flow_step.body_codec` (wins) | the JSON body + mutable headers / the raw response text | the wire body / the JSON the flow works with |

All of them are Spring beans. **The database refers to them by bean name.**

---

## 3. Step by step: build your own

We'll walk through `IdrAmountFormatter`, which turns `1500000.00` into `"Rp1.500.000,00"`. It is already in the repo, so you can compare.

### Step 1: create the class

Put it in `src/main/java/com/mhamzah/gateway/extension/custom/`, or anywhere under `com.mhamzah.gateway`. Spring only finds beans in that package tree.

```java
package com.mhamzah.gateway.extension.custom;

@Component("idrAmountFormatter")                 // ← the bean name you will put in the database
public class IdrAmountFormatter implements FieldHandler {

    @Override
    public JsonNode handle(JsonNode value, ExecutionContext ctx) {
        if (value == null || value.isNull()) {
            return null;                         // missing stays missing
        }
        BigDecimal amount = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asString().trim());
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        DecimalFormat format = new DecimalFormat("#,##0.00", symbols);   // new per call: thread safety
        return JsonNodeFactory.instance.stringNode((amount.signum() < 0 ? "-Rp" : "Rp") + format.format(amount.abs()));
    }
}
```

Choose a clear, unique bean name in camelCase. The bean name is what the configuration refers to, so treat it as fixed once flows use it.

### Step 2: unit test it

Handlers are plain Java, so test them without Spring, a database or HTTP. Build an `ExecutionContext` the way the engine does:

```java
private static ExecutionContext ctx() {
    ObjectNode request = JsonNodeFactory.instance.objectNode();
    request.putObject("headers");
    request.putObject("body");
    return new ExecutionContext("DEMO_FLOW", "corr-42", Map.of(), request);
}

@Test
void formatsAsRupiah() {
    JsonNode out = new IdrAmountFormatter().handle(JsonNodeFactory.instance.stringNode("25000.5"), ctx());
    assertThat(out.stringValue()).isEqualTo("Rp25.000,50");
}
```

The examples' tests are in `src/test/java/com/mhamzah/gateway/extension/custom/CustomHandlersTest.java`. Run them with `make test-unit`.

### Step 3: build and restart

New classes are code, so they need a restart (unlike config, which only needs a reload):

```bash
make test-unit     # or make test
make run           # restart the app
```

### Step 4: wire it in the database

Put the bean name in the right column, then reload:

```sql
UPDATE gw_mapping_rule SET field_handler = 'idrAmountFormatter'
WHERE target_path = '$.balance'
  AND flow_id = (SELECT id FROM gw_flow WHERE code = 'ACCOUNT_INQUIRY');
```

```bash
make reload
```

If the name is wrong, or the bean implements a different interface, the reload is rejected:
`mapping rule 5 of flow 'ACCOUNT_INQUIRY': field_handler 'idrAmountFormater' is not a FieldHandler bean` (the number is `gw_mapping_rule.id`)

### Step 5: call it

```bash
$ curl localhost:8080/api/v1/accounts/1001
{"accountNo":"1001","accountName":"BUDI SANTOSO","balance":"Rp1.500.000,00","currency":"IDR","status":"ACTIVE"}
```

---

## 4. The examples in this repository

All five are in `src/main/java/com/mhamzah/gateway/extension/custom/`. [`examples/custom-classes.sql`](examples/custom-classes.sql) wires the first four into one endpoint (4.6 needs a SOAP partner, so it is covered by unit tests only), `GET /api/v3/accounts/{accountNo}`:

```bash
make run    # with the new code
docker compose exec -T postgres psql -U gateway -d gateway < docs/examples/custom-classes.sql
make reload
```

Then use the requests in the *Custom class examples* section of [`http/gateway.http`](../http/gateway.http), or the `curl` commands below. The integration test `customClassExampleFlowWorksEndToEnd` runs this same script on PostgreSQL and Oracle.

### 4.1 `IdrAmountFormatter`: FieldHandler

| | |
|---|---|
| Bean name | `idrAmountFormatter` |
| Wire it | `gw_mapping_rule.field_handler = 'idrAmountFormatter'` |
| Does | `1500000.00` → `"Rp1.500.000,00"`, `-750` → `"-Rp750,00"`; missing stays missing; non-numeric text → `HANDLER_ERROR` (500) |

The field handler runs **after** `default_value`, `lookup_code` and `converter` on the same rule, so it receives the already-converted value.

### 4.2 `ChannelGuard`: MessageHandler at the flow request hook (short-circuit)

| | |
|---|---|
| Bean name | `channelGuard` |
| Wire it | `gw_flow.request_handler = 'channelGuard'` |
| Setting | `custom.channel-guard.allowed: MOBILE,ATM,WEB` |
| Does | Returns `403` when `X-Channel` is missing or not allowed. **No step is called.** |

```java
String channel = message.headers().get("X-Channel");       // case-insensitive map
if (channel != null && allowed.contains(channel.trim().toUpperCase(Locale.ROOT))) {
    return null;                                             // continue with the flow
}
return GatewayResponse.of(403, body);                        // short-circuit: this is the response
```

```bash
$ curl -i localhost:8080/api/v3/accounts/1001
HTTP/1.1 403
{"errorCode":"CHANNEL_NOT_ALLOWED","errorMessage":"X-Channel header is required","correlationId":"…"}
```

Returning a response short-circuits **only** at the flow request hook. Elsewhere a returned response is ignored and a warning is logged.

### 4.3 `RequestSigner`: MessageHandler at the step request hook

| | |
|---|---|
| Bean name | `requestSigner` |
| Wire it | `gw_flow_step.request_handler = 'requestSigner'` |
| Setting | `custom.request-signer.secret` ← env `REQUEST_SIGNER_SECRET` (dev default `dev-signing-secret`) |
| Does | Adds `X-Timestamp` and `X-Signature = Base64(HMAC-SHA256(secret, timestamp + ":" + body))` to the outgoing call |

It runs after the step's request mapping and sees exactly the body that is sent (none for GET), so the signature always matches what the partner receives. The receiving side checks the signature the same way. With `openssl`, for a GET (empty body):

```bash
printf '%s' '2026-10-08T09:00:00Z:' | openssl dgst -sha256 -hmac dev-signing-secret -binary | base64
```

The class takes a `Clock` in a second constructor, so the unit test can fix the time and compare against a signature computed independently with `openssl`.

### 4.4 `PartnerErrorHandler`: ErrorHandler written from scratch

| | |
|---|---|
| Bean name | `partnerErrorHandler` |
| Wire it | `gw_flow.error_handler = 'partnerErrorHandler'` |
| Does | Always HTTP 200 with an ISO-8583-style `responseCode`, the format some partners require |

| Error | responseCode |
|---|---|
| invalid JSON, schema violation, missing required request field | `30` Format error |
| downstream or flow timeout | `68` Response received too late |
| downstream error whose body has `responseCode` (e.g. core `14`, `51`) | that code, with its `responseMessage` |
| anything else | `96` System malfunction |

```bash
$ curl localhost:8080/api/v3/accounts/9999 -H 'X-Channel: ATM'
{"responseCode":"14","responseMessage":"Account not found","correlationId":"…"}
```

### 4.5 `CoreBankingErrorHandler`: LookupErrorHandler, the easy way for error codes

When all you need is "downstream code → my error code / message / status", extend `LookupErrorHandler` and keep the mapping in the database:

```java
@Component("coreBankingErrorHandler")
public class CoreBankingErrorHandler extends LookupErrorHandler {
    @Override protected String errorCodePath() { return "$.body.responseCode"; }    // where the code is
    @Override protected String lookupCode()    { return "CORE_BANKING_ERRORS"; }     // which lookup table
}
```

```sql
INSERT INTO gw_lookup_entry (lookup_code, source_value, target_value) VALUES ('CORE_BANKING_ERRORS', '51',
  '{"status":422,"errorCode":"INSUFFICIENT_FUNDS","errorMessage":"Insufficient balance"}');
```

New codes are new rows plus `make reload`, with no code change. See the [configuration guide, recipe 4.10](CONFIGURATION-GUIDE.md#410-downstream-returns-http-200-with-an-error-code-in-the-body).

### 4.6 `PartnerSoapCodec`: BodyCodec, SOAP with a WS-Security header

| | |
|---|---|
| Bean name | `partnerSoapCodec` |
| Wire it | `gw_target_system.body_codec = 'partnerSoapCodec'` (or on one step: `gw_flow_step.body_codec`) |
| Settings | `custom.partner-soap.username` / `.password` ← env `PARTNER_SOAP_USERNAME` / `PARTNER_SOAP_PASSWORD` |
| Does | Everything the built-in `soapCodec` does, plus a `<wsse:Security><wsse:UsernameToken>` SOAP header |

Plain XML and plain SOAP need no code: set `body_codec` to `xmlCodec`, `soapCodec` or `soap12Codec` ([configuration guide §4.15](CONFIGURATION-GUIDE.md#415-xml-or-soap-downstream)). Write a `BodyCodec` when the partner wants more than that. For SOAP, extend `SoapCodec` and override `header(ctx)`; it returns the header elements as JSON in the same form `BODY` rules use (`@` = attribute, `#text` = text):

```java
@Component("partnerSoapCodec")
public class PartnerSoapCodec extends SoapCodec {
    public PartnerSoapCodec(@Value("${custom.partner-soap.username:dev-user}") String username,
                            @Value("${custom.partner-soap.password:dev-password}") String password) {
        super(Version.SOAP_1_1);
        ...
    }

    @Override
    protected JsonNode header(ExecutionContext ctx) {
        ObjectNode header = object();
        ObjectNode security = header.putObject("wsse:Security");
        security.put("@xmlns:wsse", WSSE);
        ObjectNode token = security.putObject("wsse:UsernameToken");
        token.put("wsse:Username", username);
        ObjectNode pwd = token.putObject("wsse:Password");
        pwd.put("@Type", PASSWORD_TEXT);
        pwd.put("#text", password);
        return header;
    }
}
```

Other shapes:

- **Completely different format** (fixed-length text, a custom XML layout): implement `BodyCodec` directly. `XmlJson.toXml(json)`, `XmlJson.toJson(xml)` and `XmlJson.parse(xml)` (XXE-safe DOM) are there to reuse.
- **Sign the XML that is actually sent**: a step `request_handler` only sees JSON. Do it in `encode`: build the XML, compute the signature, put it in `headers` (mutable, case-insensitive), return the XML.
- **Set the Content-Type yourself** (e.g. a SOAP 1.2 `action` parameter): put `Content-Type` in `headers` inside `encode`; it wins over `contentType()`.

Errors: throw `IllegalArgumentException` from `encode` for JSON that can't be encoded (`MAPPING_ERROR`) and from `decode` for a response that isn't in the expected format (`DOWNSTREAM_INVALID_RESPONSE` on 2xx; on an error status the raw text is kept). A codec is shared by all requests, so it must be **thread-safe**: no per-request fields.

Only the JSON form goes into the audit tables, so the SOAP header (and its password) is never stored there.

---

## 5. API reference

### `ExecutionContext`: everything known about the current request

| Method | Returns |
|---|---|
| `read("$.request.body.amount")` | value at a [context path](CONFIGURATION-GUIDE.md#331-what-a-rule-can-read), or `null` if missing |
| `read("$.steps.inquiry.body.acctName")` | results of earlier steps |
| `lookup("CODE", value)` | `Optional<JsonNode>` translated through `gw_lookup_entry` (entry or `*` fallback) |
| `correlationId()` | the request's correlation ID: put it in your logs and error bodies |
| `flowCode()` | code of the running flow (null when no flow matched) |
| `snapshot()` | deep copy of the whole context, e.g. for debugging |

### `MessageView`: the message at a MessageHandler hook

| Method | |
|---|---|
| `headers()` | mutable `Map<String,String>`, **case-insensitive** |
| `body()` / `setBody(JsonNode)` | read / replace the JSON body. At the step request hook this is **exactly what will be sent**: `null` for GET (and for DELETE without a body), since no body goes out |

### `BodyCodec`: the wire format of a downstream call

| Method | |
|---|---|
| `contentType()` | Content-Type of an encoded request (a `Content-Type` that `encode` puts in `headers` wins) |
| `accept()` | Accept header; defaults to `contentType()` |
| `encode(body, headers, ctx)` | JSON → wire text, after the step `request_handler`. Not called when no body is sent (GET). `headers` is mutable |
| `decode(body, headers)` | wire text → JSON, for 2xx and error responses, before the step `response_handler`. Not called for an empty body (that is `{}`) |

Built-in beans: `jsonCodec` (default), `xmlCodec`, `soapCodec`, `soap12Codec`. Helpers in `com.mhamzah.gateway.codec.XmlJson`: `toXml(JsonNode)`, `appendElements(StringBuilder, JsonNode)`, `toJson(String)`, `toJson(Element)`, `parse(String)`.

### `GatewayResponse`

`GatewayResponse.of(status, body)` or `new GatewayResponse(status, headersMap, body)`. `X-Correlation-Id` is added automatically.

### `GatewayError`: what an ErrorHandler receives (and what any handler may throw)

| Method | |
|---|---|
| `type()` | `ErrorType`: `ROUTE_NOT_FOUND`, `INVALID_JSON`, `REQUEST_SCHEMA_INVALID`, `MAPPING_ERROR`, `DOWNSTREAM_HTTP_ERROR`, `DOWNSTREAM_BUSINESS_ERROR`, `DOWNSTREAM_CONNECTION`, `DOWNSTREAM_INVALID_RESPONSE`, `DOWNSTREAM_TIMEOUT`, `FLOW_TIMEOUT`, `HANDLER_ERROR`, `RESPONSE_SCHEMA_INVALID`, `INTERNAL` |
| `stepName()` | failing step, or null |
| `downstreamStatus()` / `downstreamHeaders()` / `downstreamBody()` | what the downstream system answered, if anything |
| `details()` | validation messages that are safe to show the client |
| `clientError()` | true for a `MAPPING_ERROR` caused by the request |
| `type().defaultStatus()` / `defaultCode()` / `defaultMessage()` | the standard values (`502`, `GW-502-DOWNSTREAM`, …) |

To fail on purpose with a specific type, from any handler:

```java
throw GatewayError.of(ErrorType.DOWNSTREAM_BUSINESS_ERROR)
        .message("Limit exceeded").details(List.of("daily limit is 50.000.000")).build();
```

Reuse the standard error body in your own `ErrorHandler`: `DefaultErrorHandler.body(code, message, error, ctx)`.

### JSON (Jackson 3, package `tools.jackson.databind`)

| Need | Code |
|---|---|
| Text / number / boolean | `JsonNodeFactory.instance.stringNode("x")`, `.numberNode(new BigDecimal("12.50"))`, `.booleanNode(true)` |
| Object | `ObjectNode o = JsonNodeFactory.instance.objectNode(); o.put("k", "v"); o.putObject("child"); o.putArray("list")` |
| Read | `node.get("field")` (null if absent), `node.path("field")` (never null), `node.isNumber()`, `node.decimalValue()`, `node.asString()`, `node.stringValue()` |
| Parse / write | `JsonValues.MAPPER.readTree(text)`, `node.toString()` (compact JSON) |

Use `BigDecimal` (`decimalValue()`) for money, never `double`.

---

## 6. Settings and secrets for your class

Inject settings with `@Value`, give them a section in `application.yml`, and take secrets from environment variables:

```java
public RequestSigner(@Value("${custom.request-signer.secret:dev-signing-secret}") String secret) { … }
```

```yaml
custom:
  request-signer:
    secret: ${REQUEST_SIGNER_SECRET:dev-signing-secret}   # set REQUEST_SIGNER_SECRET outside dev
```

```bash
REQUEST_SIGNER_SECRET=… make run
```

Never commit real secrets, and never write them to the configuration tables. For per-target secrets used only as headers, a `${ENV_VAR}` in `gw_target_system_header` may be enough ([configuration guide 3.11](CONFIGURATION-GUIDE.md#311-gw_target_system--gw_target_system_header)).

---

## 7. Rules for writing handlers

1. **Thread-safe and stateless.** One bean instance serves all requests, and parallel steps run at the same time. Don't keep per-request data in fields. Create non-thread-safe objects (`DecimalFormat`, `Mac`, `SimpleDateFormat`) per call.
2. **Fast.** Handler time counts against the step and flow timeouts. Avoid slow remote calls in a handler; model them as a step instead.
3. **Null-safe.** A `FieldHandler` gets `null` for a missing value, and returning `null` means "missing". A `MessageHandler` body can be `null`.
4. **Fail loudly, clearly.**
   - Any exception becomes `HANDLER_ERROR` (500) and is logged with the correlation ID.
   - Throw `GatewayError` when you want a specific error type.
   - Throw `IllegalArgumentException` with a clear message for bad data.
5. **No secrets or personal data in logs.** Log the correlation ID instead of payloads.
6. **Short-circuit only from the flow request hook.** Elsewhere, return `null`.
7. **Bean names are an API.** The database refers to them, so don't rename one that flows use.
8. **Test without Spring.** Construct the class directly. Inject time and other variables (like `RequestSigner`'s `Clock`) so results are predictable.

**Keeping custom classes in a separate jar** (e.g. one per partner) also works. Keep the classes under `com.mhamzah.gateway...` so component scanning finds them, add the jar as a dependency in `pom.xml`, rebuild and restart.

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Reload/startup: `field_handler 'x' is not a FieldHandler bean` (or `MessageHandler`/`ErrorHandler`/`BodyCodec`) | Name typo, class missing `@Component("x")`, class outside `com.mhamzah.gateway`, wrong interface, or the app hasn't been restarted since the class was added | Fix the name/annotation/package, rebuild, restart |
| `500 GW-500-HANDLER` | The handler threw an exception | Search the log for the response's `correlationId`; the stack trace is there |
| Log: `Ignoring response returned by request_handler of step …` | A step or response hook returned a response | Return `null`; throw a `GatewayError` to fail instead |
| Changes to the class have no effect | Code changes need a restart; `make reload` only reloads DB config | Restart (`make run`) |
| Signature rejected by the partner | Different string-to-sign, secret or body formatting | Compare with the `openssl` command in [4.3](#43-requestsigner--messagehandler-at-the-step-request-hook); the body is signed as compact JSON exactly as sent |
