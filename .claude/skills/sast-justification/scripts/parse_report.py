#!/usr/bin/env python3
"""Reads an HCL AppScan (Enterprise / Source) SAST report PDF into findings JSON, one entry per issue, in report order.

Usage: parse_report.py REPORT.pdf > findings.json

Needs `pdftotext` (poppler). Long values wrap onto indented continuation lines in the PDF (file paths are cut
mid-word); they are joined back, so `file` is the complete path. `file` is the path from the first `src/` (or the
scanner's full path when there is none); resolve it against the repository with find_commit.py.
"""
import json, re, subprocess, sys


def text_of(pdf):
    return subprocess.run(["pdftotext", "-layout", pdf, "-"], capture_output=True, text=True, check=True).stdout


def field(block, name):
    """A `Name:  value` field with its indented continuation lines joined (wrapped paths are cut mid-word)."""
    m = re.search(r"^\s*" + re.escape(name) + r":\s+(.+)\n((?:[ \t]{20,}\S.*\n)*)", block, re.M)
    if not m:
        return None
    return (m.group(1) + "".join(x.strip() for x in m.group(2).splitlines())).strip()


def scan_date(text):
    """The scan's date as YYYY-MM-DD HH:MM, from "Date Created: 11/10/26, 11:46 AM" (US month/day/year)."""
    m = re.search(r"Date Created:\s+(\d{1,2})/(\d{1,2})/(\d{2,4}),\s+(\d{1,2}):(\d{2})\s*([AP]M)?", text)
    if not m:
        return ""
    mo, d, y, h, mi, ap = m.groups()
    y = int(y) + (2000 if len(y) == 2 else 0)
    h = int(h) % 12 + (12 if ap == "PM" else 0) if ap else int(h)
    return f"{y:04d}-{int(mo):02d}-{int(d):02d} {h:02d}:{mi}"


def repo_path(full):
    p = full.replace("\\", "/")
    p = re.sub(r"\(\d+\)$", "", p)
    i = p.find("/src/")
    return p[i + 1:] if i >= 0 else p.lstrip("/")


def main():
    text = text_of(sys.argv[1])
    meta = {
        "tool": re.sub(r",\s*Version:\s*", " ", (re.search(r"Created by:\s*(.+)", text) or [None, ""])[1].strip()),
        "application": field(text, "Application Name") or "",
        "total": int((re.search(r"Total Issues:\s+(\d+)", text) or [None, "0"])[1]),
        "scan_date": scan_date(text),
    }
    blocks = re.split(r"\n\s*Issue +\d+ +of +\d+[^\n]*", text)[1:]
    findings = []
    for b in blocks:
        b = b.split("\nHow to Fix")[0]
        pre = b.split("Severity:")[0]
        # the issue type is the last non-empty heading line before "Severity:" (page footers contain digits/dates)
        heads = [l.strip() for l in pre.splitlines() if l.strip() and not re.search(r"\d{1,2}/\d{1,2}/\d{4}", l)]
        source = field(b, "Source File") or field(b, "Location") or ""
        line = field(b, "Line")
        findings.append({
            "no": len(findings) + 1,
            "id": field(b, "Id") or "",
            "type": heads[-1] if heads else "",
            "api": field(b, "API") or "",
            "severity": (field(b, "Severity") or "").split()[0] if field(b, "Severity") else "",
            "cvss": field(b, "CVSS") or "",
            "file": repo_path(source),
            "scanner_path": source,
            "line": int(line) if line and line.isdigit() else None,
            "test_code": "/src/test/" in "/" + repo_path(source),
        })
    meta["parsed"] = len(findings)
    json.dump({"meta": meta, "findings": findings}, sys.stdout, indent=1, ensure_ascii=False)
    if meta["total"] and meta["total"] != len(findings):
        print(f"\nWARNING: report says {meta['total']} issues, parsed {len(findings)}", file=sys.stderr)


if __name__ == "__main__":
    main()
