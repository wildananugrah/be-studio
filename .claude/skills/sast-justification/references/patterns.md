# Common AppScan Source findings (Java / Spring) and how they usually resolve

Starting points only: confirm each against the code at the scanned commit.

| Rule (API in the report) | Usually | Check this | Evidence to show |
|---|---|---|---|
| Potential SQL injection detected in Java code | False positive when only identifiers from deployment config (table / schema names) are concatenated and every value is a `?`; real when request data reaches the SQL text | Where each concatenated piece comes from; whether identifiers are validated at startup | The flagged line, the bind parameters, the identifier validation. A schema or owner compared as a *value* (`WHERE owner = '...'`) can and should become a bind parameter: fix it |
| Potential SQL injection, JPQL `createQuery("select e from " + X.getSimpleName() ...)` | False positive: entity class names from code | The list of classes passed | The loader method with the fixed class list |
| Potential regular expression injection in Java code | False positive when the pattern is a constant and data is only the input; real when a pattern is built from input | Whether the *pattern* (not the subject) can come from input; `Matcher.quoteReplacement` on replacements | The constant `Pattern.compile`, the replacement escaping. Spring `PathPattern.matches` is not a regex |
| Insecure Spring CSRF Request Mapping Detected | False positive for stateless APIs authenticated per request by headers (no session cookie); real for cookie-session web apps without CSRF tokens | Sessions, cookies, Spring Security config, how the browser UI sends its token | The mappings (reads separated from writes) and the header-based auth |
| Password stored in Java String object | Accepted risk when a framework API only takes `String` (Spring config binding, `SslBundle`, `HikariDataSource.setPassword`) | Where the password is read and passed | The flagged field and the framework call that needs a `String` |
| Predictable seed for SecureRandom instance | False positive for `new SecureRandom()` without `setSeed`; real when seeded with time or a constant | Any `setSeed` / seeded constructor | The constructor call; if the surrounding feature is the real risk (e.g. a trust-all TLS mode), a setting that forbids it in production |
| Potential External Entities (XXE) attack vector | False positive when the factory is hardened right after creation | `disallow-doctype-decl`, external entities off, `FEATURE_SECURE_PROCESSING`, `setXIncludeAware(false)`, `setExpandEntityReferences(false)` | The whole factory method with the features highlighted; a test that rejects a DOCTYPE |
| OS injection detected when using ProcessBuilder Java API | False positive when in test code or with fixed arguments; real when arguments come from input | Where the command and arguments come from | The call and its constant arguments |

Test code (`src/test`) is never packaged or deployed: such findings are false positives, and the follow-up
"exclude `src/test` from the SAST scope" is added automatically.
