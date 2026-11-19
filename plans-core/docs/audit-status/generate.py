#!/usr/bin/env python3
"""
Generates the AuditStatus state-chart diagrams.

Every status box links to its constant in AuditStatus.java on GitHub (line anchors are read
from the source each run); the title links to the enum itself. Run it after changing
AuditStatus.java, then commit the result:

    python3 plans-core/docs/audit-status/generate.py

Writes the SVGs into the enum's javadoc doc-files/ folder (javadoc copies it, so
AuditStatus's @see links work in the IDE and in the published javadoc) and, when
rsvg-convert is on the PATH, PNG previews next to this script.
AuditStatusDiagramTest fails when the SVGs are out of date.
"""
import os
import re
import shutil
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
CORE = os.path.normpath(os.path.join(HERE, "..", ".."))
ENUM_REL = "src/main/java/org/krmdemo/plansinstr/core/model/enums/AuditStatus.java"
ENUM_PATH = os.path.join(CORE, ENUM_REL)
SVG_DIR = os.path.join(os.path.dirname(ENUM_PATH), "doc-files")
SRC_URL = "https://github.com/krm-demo/ai-plans-instr/blob/main/plans-core/" + ENUM_REL


def read_lines():
    lines, decl = {}, None
    with open(ENUM_PATH, encoding="utf-8") as f:
        for n, text in enumerate(f, start=1):
            m = re.match(r"^    ([A-Z][A-Z0-9_]*)\(Kind\.", text)
            if m:
                lines[m.group(1)] = n
            elif decl is None and re.match(r"^public enum AuditStatus\b", text):
                decl = n
    return lines, decl


LINES, DECL_LINE = read_lines()
USED = set()


def href(status):
    USED.add(status)
    return f"{SRC_URL}#L{LINES[status]}"


def link(status, inner):
    n = LINES[status]
    return (f'  <a href="{href(status)}" target="_top" data-status="{status}">\n'
            f'    <title>AuditStatus.{status}, AuditStatus.java line {n}</title>\n{inner}\n  </a>')

IN = dict(lane_fill="#fff7ed", lane_stroke="#fdba74", stroke="#b45309", text="#7c2d12",
          final_fill="#fde7c7", arrow="#b45309", title="#9a3412", mk="arrow-in")
OUT = dict(lane_fill="#f0fdfa", lane_stroke="#5eead4", stroke="#0d9488", text="#134e4a",
           final_fill="#ccfbf1", arrow="#0f766e", title="#0f766e", mk="arrow-out")
FONT = "system-ui, -apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif"


def defs():
    def marker(i, c):
        return (f'<marker id="{i}" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" '
                f'orient="auto-start-reverse"><path d="M 0 1 L 10 5 L 0 9 z" fill="{c}"/></marker>')
    return f'''  <defs>
    {marker("arrow-main", "#475569")}
    {marker("arrow-fail", "#dc2626")}
    {marker("arrow-in", IN["arrow"])}
    {marker("arrow-out", OUT["arrow"])}
    <g id="ground-symbol">
      <line x1="0" y1="0" x2="0" y2="14" stroke="#dc2626" stroke-width="2.5" stroke-linecap="round"/>
      <line x1="-18" y1="14" x2="18" y2="14" stroke="#dc2626" stroke-width="2.5" stroke-linecap="round"/>
      <line x1="-11" y1="21" x2="11" y2="21" stroke="#dc2626" stroke-width="2.5" stroke-linecap="round"/>
      <line x1="-4" y1="28" x2="4" y2="28" stroke="#dc2626" stroke-width="2.5" stroke-linecap="round"/>
    </g>
    <filter id="shadow" x="-5%" y="-5%" width="110%" height="115%">
      <feDropShadow dx="0" dy="2" stdDeviation="3" flood-color="#0f172a" flood-opacity="0.06"/>
    </filter>
  </defs>'''


def header(w, legend_xy):
    lx, ly = legend_xy
    return f'''  <rect width="{w}" height="100%" fill="#f8fafc" rx="12"/>
  <a href="{SRC_URL}#L{DECL_LINE}" target="_top">
    <title>enum AuditStatus, AuditStatus.java line {DECL_LINE}</title>
    <text x="40" y="46" font-size="22" font-weight="700" fill="#0f172a" text-decoration="underline">AuditStatus State Machine</text>
  </a>
  <text x="40" y="68" font-size="13" fill="#64748b">Workflow lifecycle with grounded terminal failure states · click a status for its source</text>
  <g transform="translate({lx}, {ly})">
    <rect x="0" y="0" width="560" height="44" rx="8" fill="#ffffff" stroke="#e2e8f0"/>
    <circle cx="22" cy="22" r="6" fill="#0f172a"/>
    <text x="35" y="26" font-size="12" fill="#334155">Initial</text>
    <rect x="85" y="13" width="18" height="18" rx="4" fill="#eff6ff" stroke="#3b82f6" stroke-width="1.5"/>
    <text x="110" y="26" font-size="12" fill="#334155">Transient</text>
    <rect x="185" y="13" width="18" height="18" rx="4" fill="{IN["final_fill"]}" stroke="{IN["stroke"]}" stroke-width="2"/>
    <text x="210" y="26" font-size="12" fill="{IN["title"]}" font-weight="600">Swept-In</text>
    <rect x="280" y="13" width="18" height="18" rx="4" fill="{OUT["final_fill"]}" stroke="{OUT["stroke"]}" stroke-width="2"/>
    <text x="305" y="26" font-size="12" fill="{OUT["title"]}" font-weight="600">Swept-Out</text>
    <g transform="translate(405, 11) scale(0.65)"><use href="#ground-symbol"/></g>
    <text x="425" y="26" font-size="12" fill="#dc2626" font-weight="600">Grounded Failure</text>
  </g>'''


def box(x, y, w, label, fill="#eff6ff", stroke="#3b82f6", text="#1e3a8a", sw=1.8, bold=600):
    return link(label, f'  <g transform="translate({x}, {y})" filter="url(#shadow)">\n'
            f'    <rect class="box" width="{w}" height="44" rx="8" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>\n'
            f'    <text x="{w/2}" y="27" font-size="12.5" font-weight="{bold}" fill="{text}" text-anchor="middle">{label}</text>\n'
            f'  </g>')


def branch_box(x, y, label, c):
    return box(x, y, 165, label, fill="#ffffff", stroke=c["stroke"], text=c["text"])


def final_box(x, y, label, c):
    return link(label, f'  <g transform="translate({x}, {y})" filter="url(#shadow)">\n'
            f'    <rect class="box" width="150" height="50" rx="10" fill="{c["final_fill"]}" stroke="{c["stroke"]}" stroke-width="2.5"/>\n'
            f'    <rect x="4" y="4" width="142" height="42" rx="7" fill="none" stroke="{c["stroke"]}" stroke-width="1"/>\n'
            f'    <text x="75" y="30" font-size="13.5" font-weight="700" fill="{c["text"]}" text-anchor="middle">{label}</text>\n'
            f'  </g>')


def fail_stack(x, y, a, b):
    # 170 x 54, one link per half; the ground symbol hangs underneath (28px)
    def half(status, top, h, ty):
        return link(status,
                    f'    <rect class="half" x="{x+2}" y="{y+top}" width="166" height="{h}" rx="6" fill="#dc2626" fill-opacity="0"/>\n'
                    f'    <text x="{x+85}" y="{y+ty}" font-size="11.5" font-weight="700" fill="#991b1b" text-anchor="middle">{status}</text>')
    return (f'  <rect x="{x}" y="{y}" width="170" height="54" rx="8" fill="#fef2f2" stroke="#ef4444" stroke-width="1.8" filter="url(#shadow)"/>\n'
            f'  <line x1="{x+12}" y1="{y+27}" x2="{x+158}" y2="{y+27}" stroke="#fecaca" stroke-width="1"/>\n'
            + half(a, 2, 25, 20) + "\n" + half(b, 27, 25, 43) + "\n"
            f'  <use href="#ground-symbol" x="{x+85}" y="{y+54}"/>')


def lane(x, y, w, h, title, c, right=False):
    tx, anchor = (x + w - 20, "end") if right else (x + 20, "start")
    return (f'  <rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" fill="{c["lane_fill"]}" stroke="{c["lane_stroke"]}" stroke-dasharray="4 4"/>\n'
            f'  <text x="{tx}" y="{y+25}" text-anchor="{anchor}" font-size="12" font-weight="700" letter-spacing="0.05em" fill="{c["title"]}">{title}</text>')


def line(x1, y1, x2, y2, kind="main"):
    st = {"main": ('#475569', 2, '', 'arrow-main'),
          "fail": ('#dc2626', 1.8, ' stroke-dasharray="4 3"', 'arrow-fail'),
          "in": (IN["arrow"], 2.2, '', IN["mk"]),
          "out": (OUT["arrow"], 2.2, '', OUT["mk"])}[kind]
    return (f'  <line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{st[0]}" stroke-width="{st[1]}"{st[2]} '
            f'marker-end="url(#{st[3]})"/>')


def path(d, c):
    return f'  <path d="{d}" fill="none" stroke="{c["arrow"]}" stroke-width="2" marker-end="url(#{c["mk"]})"/>'


def guard(cx, cy, text, c):
    return (f'  <rect x="{cx-39}" y="{cy-10}" width="78" height="20" rx="4" fill="#ffffff" stroke="{c["lane_stroke"]}"/>\n'
            f'  <text x="{cx}" y="{cy+4}" font-size="11" font-weight="600" fill="{c["title"]}" text-anchor="middle">{text}</text>')


STYLE = """  <style>
    a { cursor: pointer; }
    a:hover .box { stroke-width: 3.5; }
    a:hover .half { fill-opacity: 0.1; }
  </style>"""


def svg(w, h, body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" font-family="{FONT}">\n'
            + f"  <!-- generated by plans-core/docs/audit-status/generate.py from AuditStatus.java: do not edit -->\n"
            + STYLE + "\n" + defs() + "\n" + "\n".join(body) + "\n</svg>\n")


# ---------------- Horizontal: flow left -> right; SweptOut up, SweptIn down ----------------
def horizontal():
    W, H = 1180, 680
    b = [header(W, (580, 26))]
    b.append(lane(440, 105, 700, 260, "SWEPT-OUT BRANCH (HIGH BALANCE)", OUT))
    b.append(lane(440, 385, 700, 260, "SWEPT-IN BRANCH (LOW BALANCE)", IN))
    # common trunk
    b.append('  <circle cx="45" cy="375" r="7" fill="#0f172a"/>')
    b.append(line(52, 375, 84, 375))
    b.append(line(210, 375, 254, 375))
    b.append(line(150, 397, 150, 454, "fail"))
    # split
    b.append(path("M 415 360 L 445 360 L 445 215 L 474 215", OUT))
    b.append(guard(401, 300, "balance ≥ T", OUT))
    b.append(path("M 415 390 L 445 390 L 445 495 L 474 495", IN))
    b.append(guard(401, 446, "balance &lt; T", IN))
    # top (out)
    b.append(line(630, 215, 684, 215, "out"))
    b.append(line(555, 237, 555, 269, "fail"))
    b.append(line(855, 215, 924, 215, "out"))
    # bottom (in)
    b.append(line(630, 495, 684, 495, "in"))
    b.append(line(555, 517, 555, 549, "fail"))
    b.append(line(855, 495, 924, 495, "in"))
    # nodes
    b.append(box(90, 353, 120, "SCHEDULED", fill="#ffffff", stroke="#0f172a", text="#0f172a", sw=2.5, bold=700))
    b.append(box(260, 353, 155, "CHECK_ONE_PASSED"))
    b.append(fail_stack(65, 460, "CHECK_ONE_FAILED", "CHECK_ONE_TIMEOUT"))
    b.append(box(480, 193, 150, "HIGH_BALANCE", fill="#ffffff", stroke=OUT["stroke"], text=OUT["text"]))
    b.append(fail_stack(470, 275, "CHECK_HIGH_FAILED", "CHECK_HIGH_TIMEOUT"))
    b.append(branch_box(690, 193, "CHECK_HIGH_PASSED", OUT))
    b.append(final_box(930, 190, "SWEPT_OUT", OUT))
    b.append(box(480, 473, 150, "LOW_BALANCE", fill="#ffffff", stroke=IN["stroke"], text=IN["text"]))
    b.append(fail_stack(470, 555, "CHECK_LOW_FAILED", "CHECK_LOW_TIMEOUT"))
    b.append(branch_box(690, 473, "CHECK_LOW_PASSED", IN))
    b.append(final_box(930, 470, "SWEPT_IN", IN))
    return svg(W, H, b)


# ---------------- Vertical: flow top -> bottom; SweptIn left, SweptOut right ----------------
def vertical():
    W, H = 920, 790
    b = [header(W, (40, 88))]
    cx = 460
    # trunk
    b.append(f'  <circle cx="{cx}" cy="160" r="7" fill="#0f172a"/>')
    b.append(line(cx, 167, cx, 186))
    b.append(box(cx - 60, 190, 120, "SCHEDULED", fill="#ffffff", stroke="#0f172a", text="#0f172a", sw=2.5, bold=700))
    b.append(line(cx + 60, 212, 594, 212, "fail"))
    b.append(fail_stack(600, 185, "CHECK_ONE_FAILED", "CHECK_ONE_TIMEOUT"))
    b.append(line(cx, 234, cx, 276))
    b.append(box(cx - 77.5, 280, 155, "CHECK_ONE_PASSED"))
    # lanes
    b.append(lane(30, 385, 420, 375, "SWEPT-IN BRANCH (LOW BALANCE)", IN))
    b.append(lane(470, 385, 420, 375, "SWEPT-OUT BRANCH (HIGH BALANCE)", OUT, right=True))
    lx, rx = 355, 565  # main column centres
    b.append(path(f"M {cx-15} 324 L {cx-15} 352 L {lx} 352 L {lx} 434", IN))
    b.append(guard(400, 352, "balance &lt; T", IN))
    b.append(path(f"M {cx+15} 324 L {cx+15} 352 L {rx} 352 L {rx} 434", OUT))
    b.append(guard(520, 352, "balance ≥ T", OUT))
    # left lane (in): failure to the outer (left) side
    b.append(box(lx - 82.5, 440, 165, "LOW_BALANCE", fill="#ffffff", stroke=IN["stroke"], text=IN["text"]))
    b.append(line(lx - 82.5, 462, 236, 462, "fail"))
    b.append(fail_stack(60, 435, "CHECK_LOW_FAILED", "CHECK_LOW_TIMEOUT"))
    b.append(line(lx, 484, lx, 546, "in"))
    b.append(branch_box(lx - 82.5, 550, "CHECK_LOW_PASSED", IN))
    b.append(line(lx, 594, lx, 656, "in"))
    b.append(final_box(lx - 75, 660, "SWEPT_IN", IN))
    # right lane (out): failure to the outer (right) side
    b.append(box(rx - 82.5, 440, 165, "HIGH_BALANCE", fill="#ffffff", stroke=OUT["stroke"], text=OUT["text"]))
    b.append(line(rx + 82.5, 462, 684, 462, "fail"))
    b.append(fail_stack(690, 435, "CHECK_HIGH_FAILED", "CHECK_HIGH_TIMEOUT"))
    b.append(line(rx, 484, rx, 546, "out"))
    b.append(branch_box(rx - 82.5, 550, "CHECK_HIGH_PASSED", OUT))
    b.append(line(rx, 594, rx, 656, "out"))
    b.append(final_box(rx - 75, 660, "SWEPT_OUT", OUT))
    return svg(W, H, b)


def main():
    os.makedirs(SVG_DIR, exist_ok=True)
    rsvg = shutil.which("rsvg-convert")
    for name, fn in [("audit-status-horizontal", horizontal), ("audit-status-vertical", vertical)]:
        svg_path = os.path.join(SVG_DIR, name + ".svg")
        with open(svg_path, "w", encoding="utf-8") as f:
            f.write(fn())
        print("wrote", os.path.relpath(svg_path, CORE))
        if rsvg:
            png_path = os.path.join(HERE, name + ".png")
            subprocess.run([rsvg, "-w", "1400", svg_path, "-o", png_path], check=True)
            print("wrote", os.path.relpath(png_path, CORE))
    missing = sorted(set(LINES) - USED)
    if missing:
        raise SystemExit(f"statuses not drawn in the diagram: {missing}")


if __name__ == "__main__":
    main()
