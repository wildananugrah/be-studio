#!/usr/bin/env python3
"""Finds the commit that was scanned: the one whose flagged lines best look like what each rule reports.

Usage: find_commit.py findings.json [REPO] [--max 300]

For each recent commit, every finding's file is resolved (by suffix when the path differs) and its flagged line is
checked against hints for the rule (an SQL rule's line should build SQL, a regex rule's line should use a pattern,
...). Prints the best candidates, then every finding's flagged line at the best one, so they can be checked by eye.
Writes the resolved `file` back into findings.json.
"""
import json, re, subprocess, sys

HINTS = [
    (r"sql", r"select|insert|update|delete|query|sql|jdbc|createQuery|from "),
    (r"regular expression|regex", r"matches|Pattern|replaceAll|replaceFirst|split|matcher"),
    (r"csrf", r"Mapping|@RequestMapping|@PostMapping|@PutMapping|@DeleteMapping|@GetMapping"),
    (r"xxe|external entit", r"Factory|XML|SAX|DocumentBuilder|Transformer"),
    (r"securerandom|entropy|random", r"Random"),
    (r"os injection|command|processbuilder", r"ProcessBuilder|exec\("),
    (r"password|credential", r"password|secret|passw"),
    (r"path|traversal|file", r"File|Path|Files\."),
    (r"xss|cross-site scripting", r"write|print|innerHTML|html"),
    (r"log", r"log\."),
]


def git(repo, *args):
    return subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True).stdout


def main():
    path = sys.argv[1]
    repo = next((a for a in sys.argv[2:] if not a.startswith("--")), ".")
    limit = int(sys.argv[sys.argv.index("--max") + 1]) if "--max" in sys.argv else 300
    data = json.load(open(path))
    findings = data["findings"]
    commits = git(repo, "log", "--all", f"-n{limit}", "--format=%h %ci %s").splitlines()
    scan = data["meta"].get("scan_date", "")
    cache, scores = {}, []
    for c in commits:
        sha = c.split()[0]
        files = set(git(repo, "ls-tree", "-r", "--name-only", sha).splitlines())
        score = 0
        for f in findings:
            target = f["file"] if f["file"] in files else next((x for x in files if x.endswith("/" + f["file"].split("/src/")[-1]) or x.endswith(f["file"].split("/")[-1]) and f["file"].endswith(x.split("/src/")[-1])), None)
            if not target or not f["line"]:
                continue
            key = (sha, target)
            if key not in cache:
                cache[key] = git(repo, "show", f"{sha}:{target}").split("\n")
            lines = cache[key]
            if f["line"] > len(lines):
                continue
            text = lines[f["line"] - 1]
            hint = next((h for r, h in HINTS if re.search(r, (f["api"] + " " + f["type"]).lower())), None)
            score += 2 if hint and re.search(hint, text, re.I) else (1 if text.strip() else 0)
        scores.append((score, c))
    # ties are common (the flagged files did not change for a while). When the tied commits hold the same content in
    # every flagged file, any of them shows the same code: take the newest. Otherwise take the newest one made before
    # the scan (the report's time has no zone: it is compared as local time, so check the pick).
    top = max(s for s, _ in scores)
    tied = [c for s, c in scores if s == top]
    flagged = sorted({f["file"] for f in findings})
    blobs = {c.split()[0]: tuple(git(repo, "rev-parse", f"{c.split()[0]}:{f}").strip() for f in flagged) for c in tied}
    same = len(set(blobs.values())) == 1
    if same:
        pick, why = tied[0], "they hold identical content in every flagged file, so the newest is used"
    else:
        import datetime
        def utc(c):
            return datetime.datetime.fromisoformat(" ".join(c.split()[1:4]).replace(" +", "+").replace(" -", "-")).astimezone(datetime.timezone.utc)
        cut = datetime.datetime.fromisoformat(scan).astimezone(datetime.timezone.utc) if scan else None
        before = [c for c in tied if not cut or utc(c) <= cut]
        pick, why = (before or tied)[0], "their content differs, so the newest one made before the scan date is used"
    scores.sort(key=lambda s: -s[0])
    print(f"Scan date in the report: {scan or '(not found)'}")
    print("Best candidates (score, commit, date, subject):")
    for s, c in scores[:8]:
        print(f"  {s:4d}  {c[:100]}")
    print(f"{len(tied)} commit(s) share the top score; {why}: {pick.split()[0]}")
    best = pick.split()[0]
    files = set(git(repo, "ls-tree", "-r", "--name-only", best).splitlines())
    print(f"\nFlagged lines at {best}:")
    for f in findings:
        if f["file"] not in files:
            hit = [x for x in files if x.endswith(f["file"].split("/")[-1])]
            if len(hit) == 1:
                f["file"] = hit[0]
        lines = git(repo, "show", f"{best}:{f['file']}").split("\n") if f["file"] in files else []
        text = lines[f["line"] - 1].strip() if f["line"] and len(lines) >= f["line"] else "(file or line not found)"
        print(f"  #{f['no']:<3} {f['file'].split('/')[-1]}:{f['line']}  {text[:110]}")
    data["meta"]["scanned_commit_guess"] = best
    json.dump(data, open(path, "w"), indent=1, ensure_ascii=False)


if __name__ == "__main__":
    main()
