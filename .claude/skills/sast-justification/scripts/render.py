#!/usr/bin/env python3
"""Renders the SAST justification: one evidence page per finding, a test-results page and the Bahasa Markdown table.

Usage: render.py decisions.json OUT_DIR
  -> OUT_DIR/<doc_name>.md, OUT_DIR/sast-evidence/html/*.html (shot.mjs turns them into OUT_DIR/sast-evidence/*.png)

decisions.json is described in references/decisions-format.md. Code is read with `git show <commit>:<file>` from the
repository in decisions["repo"], so the screenshots always show the scanned (or fixed) code, not the working tree.
"""
import glob, html, json, os, re, subprocess, sys, datetime

LABEL = {"false_positive": ("False positive", "#2F7D4F"), "fixed": ("Diperbaiki", "#1F6FB2"),
         "accepted": ("Risiko diterima (belum diperbaiki)", "#B7791F")}
TEST_LABEL = ("False positive · kode pengujian", "#5B6B7A")
KW = (r"\b(public|private|protected|static|final|class|record|interface|enum|return|new|if|else|for|while|try|catch|"
      r"throw|throws|void|boolean|int|long|double|String|import|package|this|null|true|false|case|switch|default|"
      r"extends|implements|var|const|let|function|def|async|await)\b")


def md_inline(text):
    """`code`, **bold** and *italic* to HTML (for the note under each screenshot)."""
    out = []
    for part in re.split(r"(`[^`]+`)", text):
        if part.startswith("`") and part.endswith("`") and len(part) > 1:
            out.append("<code>" + html.escape(part[1:-1]) + "</code>")
        else:
            p = html.escape(part)
            p = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", p)
            p = re.sub(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)", r"<i>\1</i>", p)
            out.append(p)
    return "".join(out)


def hl(line):
    tok = re.compile(r'(//.*$|#.*$|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|@\w+|' + KW + r"|\b\d+\b)")
    out, pos = [], 0
    for m in tok.finditer(line):
        out.append(html.escape(line[pos:m.start()]))
        t = m.group(0)
        cls = "c" if t.startswith(("//", "/*", "#")) else "s" if t[0] in "\"'" else "a" if t[0] == "@" else "n" if t.isdigit() else "k"
        out.append(f'<span class="{cls}">{html.escape(t)}</span>')
        pos = m.end()
    out.append(html.escape(line[pos:]))
    return "".join(out)


class Repo:
    def __init__(self, path):
        self.path, self.cache = path, {}

    def lines(self, rev, file):
        k = (rev, file)
        if k not in self.cache:
            r = subprocess.run(["git", "-C", self.path, "show", f"{rev}:{file}"], capture_output=True, text=True)
            if r.returncode:
                raise SystemExit(f"cannot read {file} at {rev}: {r.stderr.strip()}")
            self.cache[k] = r.stdout.split("\n")
        return self.cache[k]


def panel(repo, rev, file, start, end, marks, title):
    lines = repo.lines(rev, file)
    start, end = max(1, start), min(end, len(lines))
    rows = "".join(f'<tr class="{"hit" if n in marks else ""}"><td class="ln">{n}</td><td class="code">{hl(lines[n - 1]) or "&nbsp;"}</td></tr>'
                   for n in range(start, end + 1))
    t = f'<div class="pt">{html.escape(title)}</div>' if title else ""
    return (f'<div class="panel"><div class="ph"><span class="path">{html.escape(file)}</span><span class="rev">commit {html.escape(rev)}</span></div>'
            f"{t}<table>{rows}</table></div>")


CSS = """body{margin:0;background:#F4F3EF;font:13px -apple-system,Segoe UI,Arial,sans-serif;color:#17181C}
.wrap{width:1180px;padding:22px 26px}.hd{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.no{font:700 15px Menlo,Consolas,monospace;background:#17181C;color:#fff;border-radius:6px;padding:3px 9px}
.ty{font-size:17px;font-weight:600}.sev{font:600 11px Menlo,monospace;border-radius:4px;padding:2px 7px;color:#fff}
.vd{font:600 12px -apple-system,Arial;border-radius:5px;padding:3px 9px;color:#fff}
.meta{margin-top:8px;font:12px Menlo,Consolas,monospace;color:#5A5D66;line-height:1.6}
.panel{margin-top:14px;background:#fff;border:1px solid #E1DED5;border-radius:8px;overflow:hidden}
.ph{display:flex;justify-content:space-between;gap:10px;padding:7px 12px;background:#FAF9F6;border-bottom:1px solid #EEECE5;font:12px Menlo,Consolas,monospace}
.path{color:#2B2D33;font-weight:600}.rev{color:#8A8D95;white-space:nowrap}
.pt{padding:6px 12px;font-size:12.5px;color:#3E4047;border-bottom:1px solid #F1EFE9}
table{border-collapse:collapse;width:100%;font:12px/1.55 Menlo,Consolas,monospace}
td.ln{width:46px;text-align:right;padding:0 10px;color:#A9ABB1;vertical-align:top}
td.code{white-space:pre;padding:0 12px 0 4px;color:#2B2D33}
tr.hit td{background:#FFF2C2}tr.hit td.ln{color:#8A6A00;font-weight:700;box-shadow:inset 3px 0 #E0A800}
.k{color:#7A3EB1;font-weight:600}.s{color:#2E7D46}.c{color:#8A8D95;font-style:italic}.a{color:#9A6A00}.n{color:#B35C00}
.note{margin-top:14px;padding:10px 13px;background:#fff;border-left:4px solid var(--c);border-radius:4px;font-size:13px;line-height:1.5}
.note code{font:12px Menlo,Consolas,monospace;background:#F3F1EC;padding:0 3px;border-radius:3px}
.two{display:grid;grid-template-columns:1fr 1fr;gap:12px}.two td.code{white-space:pre-wrap;word-break:break-all}
.sum{margin-top:12px;display:inline-block;background:#2F7D4F;color:#fff;border-radius:6px;padding:6px 12px;font-weight:600}
table.t{margin-top:14px;background:#fff;border:1px solid #E1DED5}
table.t th{text-align:left;background:#FAF9F6;padding:6px 10px;border-bottom:1px solid #E1DED5;font:600 11px -apple-system,Arial}
table.t td{padding:4px 10px;border-bottom:1px solid #F3F1EC}.r{text-align:right}.ok{color:#2F7D4F;font-weight:700}.bad{color:#C0392B;font-weight:700}
ul{margin:12px 0 0 18px;padding:0;line-height:1.6}h1{font-size:18px;margin:0}"""


def doc(body, color="#2F7D4F"):
    return f'<!doctype html><html><head><meta charset="utf-8"><style>{CSS}</style></head><body><div class="wrap" style="--c:{color}">{body}</div></body></html>'


def justification(f, by_no):
    if f.get("same_as") and not f.get("justification"):
        return f"Sama dengan nomor {f['same_as']}."
    return f["justification"]


def finding_page(d, f, repo, by_no):
    scanned, fix = d["scanned_commit"], d.get("fix_commit")
    test = f.get("test_code") and f["status"] == "false_positive"
    label, color = (f.get("badge"), LABEL[f["status"]][1]) if f.get("badge") else (TEST_LABEL if test else LABEL[f["status"]])
    revs = {"scanned": scanned, "fix": fix}
    panels = f.get("panels") or [{"rev": "scanned", "start": f["line"] - 6, "end": f["line"] + 6, "marks": [f["line"]],
                                  "title": "Baris yang dilaporkan (disorot)"}]
    html_panels = [panel(repo, revs.get(p.get("rev", "scanned"), p.get("rev")), p.get("file", f["file"]), p["start"], p["end"],
                         set(p.get("marks", [])), p.get("title", "")) for p in panels]
    if f.get("before_after") and len(html_panels) >= 2:
        html_panels = [f'<div class="two">{html_panels[0]}{html_panels[1]}</div>'] + html_panels[2:]
    base = by_no[f["same_as"]] if f.get("same_as") else f
    note = f.get("note") or base.get("note") or base["justification"]
    sev_color = {"High": "#C0392B", "Critical": "#8E1B12", "Medium": "#D9822B", "Low": "#C9A227"}.get(f["severity"], "#777")
    body = (f'<div class="hd"><span class="no">#{f["no"]}</span><span class="ty">{html.escape(f["api"] or f["type"])}</span>'
            f'<span class="sev" style="background:{sev_color}">{html.escape(f["severity"])}</span>'
            f'<span class="vd" style="background:{color}">{html.escape(label)}</span></div>'
            f'<div class="meta">AppScan Id {html.escape(str(f["id"]))} · {html.escape(f["file"])} : {f["line"]} · kode yang dipindai = commit {html.escape(scanned)}</div>'
            + "".join(html_panels) + f'<div class="note">{md_inline(note)}</div>')
    return doc(body, color)


def test_rows(d):
    rows = []
    for t in d.get("tests", []):
        paths = t["reports"] if isinstance(t["reports"], list) else sorted(glob.glob(os.path.join(t["reports"], "*.xml")))
        for p in paths:
            x = open(p, encoding="utf-8").read()
            suite = (re.search(r'<testsuite[^>]*\bname="([^"]+)"', x) or [None, os.path.basename(p)])[1].split(".")[-1]
            for m in re.finditer(r'<testcase\b[^>]*\bname="([^"]+)"[^>]*?\btime="([^"]+)"[^>]*?(/?)>', x):
                body = "" if m.group(3) else x[m.end():x.find("</testcase>", m.end())]
                rows.append((t["db"], suite, m.group(1), float(m.group(2)), "<failure" not in body and "<error" not in body))
    return rows


def tests_page(d, rows):
    passed = sum(r[4] for r in rows)
    legend = "".join(f'<li><b>{html.escape(m["class"])}</b>{" (" + html.escape(m["scope"]) + ")" if m.get("scope") else ""}: {md_inline(m["supports"])}</li>' for m in d.get("test_map", []))
    trs = "".join(f'<tr><td>{html.escape(db)}</td><td>{html.escape(s)}</td><td>{html.escape(n)}</td><td class="r">{t:.2f} s</td>'
                  f'<td class="{"ok" if ok else "bad"}">{"PASS" if ok else "FAIL"}</td></tr>' for db, s, n, t, ok in rows)
    body = (f'<h1>Bukti pengujian: test yang mendukung perbaikan dan justifikasi</h1>'
            f'<div class="meta">commit {html.escape(d.get("fix_commit") or d["scanned_commit"])} · {html.escape(d.get("test_command", "test"))} · '
            f'{datetime.datetime.now().strftime("%d-%m-%Y %H:%M")}</div>'
            f'<div class="sum" style="background:{"#2F7D4F" if passed == len(rows) else "#C0392B"}">{passed} / {len(rows)} test lulus, {len(rows) - passed} gagal</div>'
            f'<ul>{legend}</ul><table class="t"><tr><th>Database / profil</th><th>Kelas test</th><th>Test case</th><th class="r">Waktu</th><th>Hasil</th></tr>{trs}</table>')
    return doc(body), passed


def ranges(xs):
    xs = sorted(xs)
    out, i = [], 0
    while i < len(xs):
        j = i
        while j + 1 < len(xs) and xs[j + 1] == xs[j] + 1:
            j += 1
        out.append(str(xs[i]) if i == j else f"{xs[i]}, {xs[j]}" if j == i + 1 else f"{xs[i]}–{xs[j]}")
        i = j + 1
    return ", ".join(out)


def esc_cell(text):
    return str(text).replace("|", "\\|").replace("\n", " ")


def markdown(d, names, rows, passed):
    F = d["findings"]
    fp = [f["no"] for f in F if f["status"] == "false_positive"]
    fixed = [f["no"] for f in F if f["status"] == "fixed"]
    acc = [f["no"] for f in F if f["status"] == "accepted"]
    test = [f["no"] for f in F if f.get("test_code") and f["status"] == "false_positive"]
    by_no = {f["no"]: f for f in F}
    rep = d["report"]
    sev = {}
    for f in F:
        sev[f["severity"]] = sev.get(f["severity"], 0) + 1
    sev_text = ", ".join(f"{v} {k}" for k, v in sorted(sev.items(), key=lambda kv: ["Critical", "High", "Medium", "Low"].index(kv[0]) if kv[0] in ["Critical", "High", "Medium", "Low"] else 9))
    fix = d.get("fix_commit")
    lines = [f"# Justifikasi Temuan SAST: {d['app_name']}", "",
             f"**Aplikasi:** {d['app_name']}" + (f" ({d['app_desc']})" if d.get("app_desc") else ""),
             f"**Pemindaian:** {rep.get('tool', 'AppScan')}, aplikasi `{rep.get('application', '')}`" + (f", laporan tanggal {d['report_date']}" if d.get("report_date") else ""),
             f"**Kode yang dipindai:** commit `{d['scanned_commit']}`" + (f" · **Perbaikan:** commit `{fix}`" if fix else ""),
             f"**Total temuan:** {len(F)} ({sev_text})", "",
             "Setiap baris di tabel sama dengan satu temuan di laporan AppScan, dengan urutan dan AppScan Id yang sama. Untuk setiap temuan dijelaskan apakah temuan tersebut *false positive*, apakah sudah diperbaiki, dan jika belum, justifikasinya. Kolom **Bukti** menautkan screenshot kode pada commit yang dipindai: baris yang dilaporkan disorot, ditambah kode pendukung dan, untuk temuan yang diperbaiki, perbandingan sebelum / sesudah. Screenshot ada di folder `sast-evidence/`.",
             "", "## Ringkasan", "", "| Status | Jumlah | Nomor temuan |", "|---|---|---|",
             f"| *False positive*, tidak perlu perbaikan | {len(fp)} | {ranges(fp) or '-'} |",
             f"| Bukan *false positive*, sudah diperbaiki | {len(fixed)} | {ranges(fixed) or '-'} |",
             f"| Bukan *false positive*, belum diperbaiki (risiko diterima) | {len(acc)} | {ranges(acc) or '-'} |",
             f"| **Total** | **{len(F)}** | |", ""]
    if test:
        lines += [f"Dari {len(fp)} *false positive*, {len(test)} berada di kode pengujian (`src/test`) yang tidak pernah dipaketkan atau di-*deploy*: nomor {ranges(test)}.", ""]
    lines += ["## Tabel temuan", "",
              "| No | AppScan Id | Jenis temuan | Severity | Lokasi (file : baris) | *False positive*? | Sudah diperbaiki? | Justifikasi | Bukti |",
              "|---|---|---|---|---|---|---|---|---|"]
    for f in F:
        is_fp = "Ya" if f["status"] == "false_positive" else "Tidak"
        done = f.get("fixed_label") or {"false_positive": "Tidak perlu", "fixed": "Ya", "accepted": "Tidak (risiko diterima)"}[f["status"]]
        kind = f"{f['type']} (*{esc_cell(f['api'])}*)" if f.get("api") and f["api"] != f["type"] else f["type"]
        lines.append(f"| {f['no']} | {f['id']} | {esc_cell(kind)} | {f['severity']} | `{f['file']}` : {f['line']} | {is_fp} | {done} | "
                     f"{esc_cell(justification(f, by_no))} | [screenshot](sast-evidence/{names[f['no']]}.png) |")
    if rows:
        lines += ["", "## Bukti pengujian", "",
                  f"Test yang mendukung perbaikan dan justifikasi dijalankan pada commit `{fix or d['scanned_commit']}`"
                  + (f" ({d['test_env']})" if d.get("test_env") else "") + f": **{passed} dari {len(rows)} lulus**. "
                  f"Screenshot: [sast-evidence/00-hasil-test.png](sast-evidence/00-hasil-test.png).", ""]
        if d.get("test_map"):
            lines += ["| Kelas test | Mendukung temuan |", "|---|---|"] + [f"| `{m['class']}`" + (f" ({m['scope']})" if m.get("scope") else "") + f" | {esc_cell(m['supports'])} |" for m in d["test_map"]]
    fu = list(d.get("followups", []))
    if test and not any("src/test" in x["action"] for x in fu):
        fu.append({"action": "Keluarkan `src/test` dari cakupan pemindaian SAST", "reason": f"Kode pengujian tidak dirilis; {len(test)} dari {len(F)} temuan berada di sana"})
    fu.append({"action": "Tandai temuan *false positive* dan risiko yang diterima di AppScan dengan justifikasi di atas, lalu pindai ulang" + (f" commit `{fix}` atau setelahnya" if fix else ""),
               "reason": f"Memastikan temuan nomor {ranges(fixed)} sudah tidak muncul" if fixed else "Mencatat alasan penutupan setiap temuan"})
    lines += ["", "## Tindak lanjut", "", "| No | Tindakan | Alasan |", "|---|---|---|"]
    lines += [f"| {i} | {esc_cell(x['action'])} | {esc_cell(x['reason'])} |" for i, x in enumerate(fu, 1)]
    return "\n".join(lines) + "\n"


def main():
    d = json.load(open(sys.argv[1], encoding="utf-8"))
    out = sys.argv[2]
    ev = os.path.join(out, "sast-evidence", "html")
    os.makedirs(ev, exist_ok=True)
    repo = Repo(d["repo"])
    by_no = {f["no"]: f for f in d["findings"]}
    names = {}
    for f in d["findings"]:
        if f["status"] not in LABEL:
            raise SystemExit(f"finding {f['no']}: status must be false_positive, fixed or accepted")
        if not f.get("justification") and not f.get("same_as"):
            raise SystemExit(f"finding {f['no']}: justification (or same_as) is required")
        name = f"{f['no']:02d}-{re.sub(r'[^A-Za-z0-9]+', '', os.path.splitext(os.path.basename(f['file']))[0])}-{f['line']}"
        open(os.path.join(ev, name + ".html"), "w", encoding="utf-8").write(finding_page(d, f, repo, by_no))
        names[f["no"]] = name
    rows = test_rows(d)
    passed = 0
    if rows:
        page, passed = tests_page(d, rows)
        open(os.path.join(ev, "00-hasil-test.html"), "w", encoding="utf-8").write(page)
    md_path = os.path.join(out, d.get("doc_name", f"sast-justifikasi-{d['app_name']}") + ".md")
    open(md_path, "w", encoding="utf-8").write(markdown(d, names, rows, passed))
    print(f"{len(names)} finding pages, {'1 test page' if rows else 'no test page'} in {ev}")
    print(f"markdown: {md_path}")


if __name__ == "__main__":
    main()
