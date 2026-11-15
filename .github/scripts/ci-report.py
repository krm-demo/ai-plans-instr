#!/usr/bin/env python3
"""
Turns the results of "mvn verify" into small, human-friendly reports (standard library only).

Reads
  * JaCoCo XML report      (plans-core/target/site/jacoco/jacoco.xml)
  * Surefire XML reports   (plans-core/target/surefire-reports/TEST-*.xml)

Writes into the output directory (the root of the GitHub Pages site)
  * badges/coverage.json   shields.io "endpoint" badge: line coverage in percent
  * badges/tests.json      shields.io "endpoint" badge: number of passed / failed tests
  * index.html             one page with test results, coverage per package and links

Prints a Markdown summary to stdout (append it to $GITHUB_STEP_SUMMARY).
"""
import argparse
import glob
import html
import json
import os
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone


def pct(covered, missed):
    total = covered + missed
    return None if total == 0 else 100.0 * covered / total


def fmt_pct(value):
    return "n/a" if value is None else f"{value:.1f}%"


def coverage_color(value):
    if value is None:
        return "lightgrey"
    for limit, color in ((90, "brightgreen"), (80, "green"), (70, "yellowgreen"),
                         (60, "yellow"), (50, "orange")):
        if value >= limit:
            return color
    return "red"


def read_jacoco(path):
    """Returns (totals, packages). totals/packages hold {type: (covered, missed)}."""
    if not path or not os.path.isfile(path):
        return None, []
    root = ET.parse(path).getroot()

    def counters(element):
        return {c.get("type"): (int(c.get("covered")), int(c.get("missed")))
                for c in element.findall("counter")}

    packages = [(p.get("name").replace("/", "."), counters(p)) for p in root.findall("package")]
    return counters(root), packages


def read_surefire(directory):
    """Returns (summary dict, list of (class, tests, failed, skipped, seconds), failures)."""
    summary = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "time": 0.0}
    per_class = {}
    failures = []
    for path in sorted(glob.glob(os.path.join(directory, "TEST-*.xml"))):
        for suite in ET.parse(path).getroot().iter("testsuite"):
            for case in suite.findall("testcase"):
                name = f'{case.get("classname", "?").split(".")[-1]}.{case.get("name")}'
                cls = case.get("classname", "?").split(".")[-1]
                seconds = float(case.get("time") or 0)
                bad = case.find("failure") is not None or case.find("error") is not None
                skipped = case.find("skipped") is not None
                entry = per_class.setdefault(cls, [0, 0, 0, 0.0])
                entry[0] += 1
                entry[1] += 1 if bad else 0
                entry[2] += 1 if skipped else 0
                entry[3] += seconds
                summary["tests"] += 1
                summary["failures"] += 1 if bad else 0
                summary["skipped"] += 1 if skipped else 0
                summary["time"] += seconds
                if bad:
                    node = case.find("failure") if case.find("failure") is not None else case.find("error")
                    failures.append((name, (node.get("message") or "").strip()))
    rows = [(cls, *values) for cls, values in sorted(per_class.items())]
    return summary, rows, failures


def write_json(path, label, message, color):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"schemaVersion": 1, "label": label, "message": message, "color": color}, f)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jacoco", help="path to jacoco.xml")
    ap.add_argument("--surefire", required=True, help="directory with TEST-*.xml")
    ap.add_argument("--out", required=True, help="output directory (Pages root)")
    ap.add_argument("--build", default="local", help="build number shown on the page")
    ap.add_argument("--sha", default="", help="commit id shown on the page")
    ap.add_argument("--repo", default="", help="owner/name, used for the commit link")
    args = ap.parse_args()

    totals, packages = read_jacoco(args.jacoco)
    summary, class_rows, failures = read_surefire(args.surefire)

    # ---- badges ----------------------------------------------------------------------------
    line_cov = pct(*totals["LINE"]) if totals and "LINE" in totals else None
    branch_cov = pct(*totals["BRANCH"]) if totals and "BRANCH" in totals else None
    write_json(os.path.join(args.out, "badges", "coverage.json"), "coverage",
               fmt_pct(line_cov) if line_cov is None else f"{line_cov:.0f}%", coverage_color(line_cov))

    tests, failed, skipped = summary["tests"], summary["failures"], summary["skipped"]
    passed = tests - failed - skipped
    if tests == 0:
        tests_msg, tests_color = "no tests", "lightgrey"
    elif failed:
        tests_msg, tests_color = f"{failed} failed, {passed} passed", "red"
    else:
        tests_msg = f"{passed} passed" + (f", {skipped} skipped" if skipped else "")
        tests_color = "brightgreen"
    write_json(os.path.join(args.out, "badges", "tests.json"), "tests", tests_msg, tests_color)

    # ---- Markdown for the job summary ------------------------------------------------------
    md = [f"## Build {args.build}", ""]
    md += [f"**Tests:** {tests_msg} in {summary['time']:.1f} s  ",
           f"**Line coverage:** {fmt_pct(line_cov)}  **Branch coverage:** {fmt_pct(branch_cov)}", ""]
    if class_rows:
        md += ["| Test class | Tests | Failed | Skipped | Time |", "|---|---:|---:|---:|---:|"]
        md += [f"| {c} | {t} | {f} | {s} | {sec:.2f} s |" for c, t, f, s, sec in class_rows]
        md.append("")
    if failures:
        md += ["### Failed tests", ""]
        md += [f"- `{name}`: {message[:300]}" for name, message in failures]
        md.append("")
    if packages:
        md += ["| Package | Lines | Branches |", "|---|---:|---:|"]
        for name, c in packages:
            md.append(f"| {name} | {fmt_pct(pct(*c['LINE'])) if 'LINE' in c else 'n/a'} "
                      f"| {fmt_pct(pct(*c['BRANCH'])) if 'BRANCH' in c else 'n/a'} |")
        md.append("")
    print("\n".join(md))

    # ---- the Pages index -------------------------------------------------------------------
    e = html.escape
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    commit = ""
    if args.sha:
        short = args.sha[:7]
        commit = (f'<a href="https://github.com/{e(args.repo)}/commit/{e(args.sha)}">{e(short)}</a>'
                  if args.repo else e(short))
    page = [
        "<!doctype html><html lang='en'><head><meta charset='utf-8'>",
        "<meta name='viewport' content='width=device-width, initial-scale=1'>",
        "<title>plans - build report</title>",
        "<style>body{font:16px/1.5 system-ui,sans-serif;max-width:60rem;margin:2rem auto;padding:0 1rem}"
        "table{border-collapse:collapse;margin:1rem 0}td,th{border:1px solid #8884;padding:.3rem .7rem;text-align:left}"
        "td.n,th.n{text-align:right}.bad{color:#c00;font-weight:600}nav a{margin-right:1.2rem}</style></head><body>",
        f"<h1>plans - build {e(args.build)}</h1>",
        f"<p>{commit} &middot; {e(now)}</p>",
        "<nav><a href='jacoco/index.html'>Coverage report</a><a href='apidocs/index.html'>Javadoc</a></nav>",
        f"<h2>Tests: {e(tests_msg)}</h2>",
    ]
    if class_rows:
        page.append("<table><tr><th>Class</th><th class='n'>Tests</th><th class='n'>Failed</th>"
                    "<th class='n'>Skipped</th><th class='n'>Time</th></tr>")
        for c, t, f, s, sec in class_rows:
            page.append(f"<tr><td>{e(c)}</td><td class='n'>{t}</td>"
                        f"<td class='n{' bad' if f else ''}'>{f}</td><td class='n'>{s}</td>"
                        f"<td class='n'>{sec:.2f} s</td></tr>")
        page.append("</table>")
    for name, message in failures:
        page.append(f"<p class='bad'>{e(name)}: {e(message[:300])}</p>")
    page.append(f"<h2>Coverage: lines {e(fmt_pct(line_cov))}, branches {e(fmt_pct(branch_cov))}</h2>")
    if packages:
        page.append("<table><tr><th>Package</th><th class='n'>Lines</th><th class='n'>Branches</th></tr>")
        for name, c in packages:
            page.append(f"<tr><td>{e(name)}</td><td class='n'>{fmt_pct(pct(*c['LINE'])) if 'LINE' in c else 'n/a'}</td>"
                        f"<td class='n'>{fmt_pct(pct(*c['BRANCH'])) if 'BRANCH' in c else 'n/a'}</td></tr>")
        page.append("</table>")
    page.append("</body></html>")
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "index.html"), "w", encoding="utf-8") as f:
        f.write("\n".join(page))

    # a red build must still fail the job (the script itself only reports)
    return 0


if __name__ == "__main__":
    sys.exit(main())
