# decisions.json

Input of `scripts/render.py`. Start from `findings.json` (parse_report.py, find_commit.py) and add your verdicts.

```json
{
  "app_name": "api-gateway-backend",
  "app_desc": "JSON gateway dan Gateway Studio",
  "report": { "tool": "HCL AppScan Enterprise 10.10.0", "application": "sast_scanning_..." },
  "report_date": "10 November 2026",
  "repo": "/absolute/path/to/repo",
  "scanned_commit": "d349f04",
  "fix_commit": "30a4ca8",
  "doc_name": "sast-justifikasi-api-gateway-backend",
  "test_command": "mvn test (Testcontainers: PostgreSQL dan Oracle Free)",
  "test_env": "PostgreSQL dan Oracle Free melalui Testcontainers",
  "tests": [
    { "db": "PostgreSQL", "reports": "/abs/work/tests-pg" },
    { "db": "Oracle", "reports": ["/abs/work/tests-ora/TEST-x.xml"] }
  ],
  "test_map": [
    { "class": "AuditPartitionIntegrationTest", "scope": "PostgreSQL, Oracle", "supports": "23, 27: query dengan *bind parameter*" }
  ],
  "followups": [
    { "action": "Set `GATEWAY_TLS_ALLOW_INSECURE=false` di production", "reason": "Temuan nomor 9" }
  ],
  "findings": [ ... ]
}
```

`report` is `meta` from findings.json. `fix_commit`, `tests`, `test_map`, `followups`, `doc_name` (default
`sast-justifikasi-<app_name>`), `test_command` and `test_env` are optional. Two follow-ups are added automatically:
excluding `src/test` from the scan (when test code was flagged) and closing / re-scanning in AppScan.

## A finding

Every field from findings.json (`no`, `id`, `type`, `api`, `severity`, `file`, `line`, `test_code`) plus:

| Field | Required | Meaning |
|---|---|---|
| `status` | ✔ | `false_positive`, `fixed` or `accepted` |
| `justification` | ✔ (or `same_as`) | Bahasa text for the table (Markdown inline: `code`, `**bold**`, `*italic*`) |
| `same_as` | | Number of an earlier finding with the same reason: the table says "Sama dengan nomor N." (or your `justification`), the screenshot note reuses that finding's text |
| `note` | | Text under the screenshot when it should differ from `justification` |
| `panels` | | Code to show (below). Default: the flagged line ±6 at the scanned commit |
| `before_after` | | `true`: the first two panels side by side (use for fixes: scanned commit left, fix commit right) |
| `fixed_label` | | Text of "Sudah diperbaiki?" instead of the default (`Tidak perlu` / `Ya` / `Tidak (risiko diterima)`), e.g. `Tidak perlu (kontrol tambahan dibuat)` |
| `badge` | | Text of the coloured label on the screenshot instead of the default |

## A panel

```json
{ "rev": "scanned", "file": "src/main/java/.../DbNames.java", "start": 17, "end": 22, "marks": [19], "title": "Nama tabel divalidasi saat startup" }
```

| Field | Meaning |
|---|---|
| `rev` | `scanned` (default), `fix`, or any commit / ref |
| `file` | Default: the finding's file |
| `start`, `end` | Line range to show (keep it short: 6–20 lines) |
| `marks` | Lines to highlight; include the reported line in the first panel |
| `title` | One line above the code saying what it proves (Bahasa) |

Always open with the reported line (highlighted) at the scanned commit, then the code that proves the
justification: the validation, the constant, the escape, the hardening, the setting, the test.
