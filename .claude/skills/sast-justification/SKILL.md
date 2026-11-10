---
name: sast-justification
description: Turns an HCL AppScan SAST report (PDF) into a Bahasa Indonesia justification table, one row per finding (false positive? fixed? justification), with a screenshot of the flagged code as evidence for every finding plus a test-results page. Use when the user gives an AppScan / SAST / CISO security scan report PDF and wants the findings justified, explained, closed as false positives, or documented with evidence ("justifikasi temuan SAST", "bukti screenshot").
---

# SAST justification with evidence

Input: an AppScan SAST report PDF and the git repository that was scanned (usually the current working directory).
Output, next to the PDF unless the user says otherwise:
- `sast-justifikasi-<app>.md`: summary, one table row per finding in report order (No, AppScan Id, Jenis temuan,
  Severity, Lokasi, *False positive*?, Sudah diperbaiki?, Justifikasi, Bukti link), test evidence, follow-ups.
- `sast-evidence/NN-<File>-<line>.png`: per finding, the flagged line highlighted in the scanned commit, supporting
  code, before/after for fixes; `00-hasil-test.png` with the test results.

Scripts are in `scripts/` of this skill (call them with their full path). Work files go in a scratch directory.

## Steps

1. **Parse the report.** `python3 scripts/parse_report.py REPORT.pdf > work/findings.json`. Needs `pdftotext`
   (poppler; `brew install poppler`). Check the stderr warning and that `parsed` equals `total`. File paths in the PDF
   wrap mid-word; the script joins them, so trust `file`, not a quick read of the PDF text.

2. **Find the scanned commit.** `python3 scripts/find_commit.py work/findings.json REPO`. It scores recent commits by
   whether each flagged line looks like what its rule reports, prints the pick and every flagged line at that commit,
   and writes resolved paths back. Read the printed lines: each must plausibly be the code the rule flags. If not,
   look at other candidates (`--max 1000`, or other branches) before going on. Line numbers in the report only make
   sense against that commit: all evidence is taken from it with `git show`, never from the working tree.

3. **Judge every finding by reading the code** at the scanned commit (`git show <commit>:<file>`, with enough context
   to follow where the values come from). For each one decide:
   - `false_positive`: the pattern is there but no untrusted input reaches it, or it is already mitigated. Say why,
     concretely (where the value comes from, what validates or escapes it). Test code under `src/test` is never
     shipped: a false positive, and say so.
   - `fixed`: a real weakness that was (or is now, with the user's agreement) changed in the code. Show before/after.
   - `accepted`: real but low risk with no practical fix (e.g. a framework API that only accepts `String`). Explain
     the residual risk.
   Common AppScan patterns and how they usually resolve: [references/patterns.md](references/patterns.md). Do not
   copy those blindly: confirm each against the code.

4. **Offer fixes for real issues** (cheap, safe ones). Only change code when the user agrees; then run the tests,
   commit as the user asks, and use that commit as `fix_commit`. A compensating control (e.g. a setting that forbids
   an insecure mode) can back a false positive; note it in the justification and `fixed_label`.

5. **Collect test evidence.** Run the tests that back the fixes and key justifications (e.g. Maven
   `-Dtest=...`, once per database profile if relevant) and copy their JUnit XML reports
   (`target/surefire-reports/TEST-*.xml`) to one directory per run.

6. **Write `work/decisions.json`** in the format of [references/decisions-format.md](references/decisions-format.md):
   the parsed findings plus `status`, `justification` (Bahasa), and the code `panels` to show. Repeated findings can
   use `same_as` to point at the first one.

7. **Render.**
   ```bash
   python3 scripts/render.py work/decisions.json OUT_DIR
   npm install --prefix <skill>/scripts playwright-core   # once, if scripts/node_modules is missing
   node scripts/shot.mjs OUT_DIR/sast-evidence
   ```
   `shot.mjs` finds a local Chromium (Playwright's browsers, Google Chrome, or `CHROME_PATH`).

8. **Check before handing over.** Read a few PNGs (a fixed one, a false positive with several panels, the test page):
   the highlighted line must be the reported one and long lines readable. Check every `sast-evidence/` link in the
   Markdown resolves. Leave the HTML sources in `sast-evidence/html/` or delete them if the user wants only PNGs.

## Writing the justifications

- Bahasa Indonesia, plain and specific; keep technical terms as engineers say them (*false positive*, *bind
  parameter*, *stateless*, class and setting names in backticks).
- Each justification names the evidence: the line, the constant, the validation, the escape, the test.
- Count severities from the findings themselves; the report's chart is easy to misread.
- The output describes security weaknesses of an internal system: keep it as local files and do not publish or
  upload it unless the user asks.
