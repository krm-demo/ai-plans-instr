# <img src="docs/icon-small.svg" width="56" height="32" alt=""> plans-math

[![plans-math](https://github.com/krm-demo/ai-plans-instr/actions/workflows/plans-math.yml/badge.svg)](https://github.com/krm-demo/ai-plans-instr/actions/workflows/plans-math.yml)
[![JUnit tests](https://img.shields.io/endpoint?url=https://krm-demo.github.io/ai-plans-instr/plans-math/badges/tests.json)](https://krm-demo.github.io/ai-plans-instr/plans-math/tests/demo/index.html)
[![JaCoCo coverage](https://img.shields.io/endpoint?url=https://krm-demo.github.io/ai-plans-instr/plans-math/badges/coverage.json)](https://krm-demo.github.io/ai-plans-instr/plans-math/jacoco/index.html)

**[GitHub Pages of the project](https://krm-demo.github.io/ai-plans-instr/plans-math/)**: the interactive graph, the pictures, the reports ·
[Demo tests](https://krm-demo.github.io/ai-plans-instr/plans-math/tests/demo/index.html) · [Technical tests](https://krm-demo.github.io/ai-plans-instr/plans-math/tests/tech/index.html) · [Coverage](https://krm-demo.github.io/ai-plans-instr/plans-math/jacoco/index.html) · [JavaDoc](https://krm-demo.github.io/ai-plans-instr/plans-math/apidocs/index.html) · [Site: krm-demo.org](https://krm-demo.org/)

[![Balances Equilibrium: swept amount per current balance](https://krm-demo.github.io/ai-plans-instr/plans-math/svg/equilib-thousands.svg)](https://krm-demo.github.io/ai-plans-instr/plans-math/svg/equilib-thousands.svg)

- Swept **In** Zero — a sweep In is needed, but the investment account is empty (*A* = 0): terminal failure ⏚. Like a yellow card: on the next round the plan is cancelled or paused.
- Swept **In** Partial — far below *T*<sub>min</sub>: all of *A* comes back and the balance is still short; the investment account is emptied.
- Swept **In** Full — exactly the missing *T*<sub>min</sub> − *x* comes back from the investment account, at least *τ*<sub>min</sub> (at *T*<sub>min</sub> − *A* that is all of *A*: emptied to the cent).
- **In** Nominal Min — below *T*<sub>min</sub> by less than *τ*<sub>min</sub>: not worth a transfer.
- **In** Range — between *T*<sub>min</sub> and *T*<sub>max</sub> (or right at the target *T*): nothing to transfer.
- **In** Nominal Max — above *T*<sub>max</sub> by less than *τ*<sub>max</sub>: not worth a transfer.
- Swept **Out** — the excess *x* − *T*<sub>max</sub>, at least *τ*<sub>max</sub>, goes from the DDA to the investment account; exactly *L* is still allowed.
- Swept **Out** Overflow — the excess is above *L*, the largest single payment the investment account accepts: terminal failure ⏚.

**Balances Equilibrium**, a Java library: for a current balance *x* of the DDA it finds the sweep status and the
swept amount *y*, and draws both as one SVG without JavaScript. The picture above is rebuilt on every push; click it
to open the SVG itself, move the mouse over it and make the window narrower. Everything the demo page can show,
without moving a handle: [the demo tests](https://krm-demo.github.io/ai-plans-instr/plans-math/tests/demo/index.html).

The **demo page**, interactive, with handles to drag (JavaScript), is on the [GitHub Pages of the project](https://krm-demo.github.io/ai-plans-instr/plans-math/);
its source is [`src/site/`](src/site), and it is rebuilt on every push to `main`. It is not part of the library.

## Contents

- [Parameters](#parameters)
- [How it is drawn](#how-it-is-drawn)
- [One picture, every size](#one-picture-every-size)
- [The Thymeleaf template](#the-thymeleaf-template)
- [Use it](#use-it)
- [Tests: demo and technical](#tests-demo-and-technical)
- [Build](#build)
- [GitHub Pages](#github-pages)
- [Files](#files)
- [References](#references)
## Parameters

The current account is a **DDA** (demand deposit account); a **cash sweep** keeps its balance between two thresholds
by moving money to and from an **investment account**:

| Symbol | Name | Meaning |
|---|---|---|
| *T*<sub>min</sub> | `thresholdMin` | the lowest balance the DDA should keep |
| *T*<sub>max</sub> | `thresholdMax` | the highest; *T*<sub>min</sub> ≤ *T*<sub>max</sub>. When they are equal, it is the **target** balance *T* |
| *τ*<sub>min</sub> | `nominalMin` | the smallest sweep **In** worth making (0: any) |
| *τ*<sub>max</sub> | `nominalMax` | the smallest sweep **Out** worth making (0: any) |
| *A* | `sweptInMax` | **available**: the balance of the investment account; 0, or at least *τ*<sub>min</sub> |
| *L* | `sweptOutMax` | **limit**: the largest single payment the investment account accepts; at least *τ*<sub>max</sub> |

The names are those of `ThresholdsInfo` in [plans-core](../plans-core), plus the two limits. The formula is on every
picture, one row per status; the row of the balance under the mouse is highlighted, with the value of *y*.

## How it is drawn

- **Only 0°, 45° and 90°.** Both axes have the same dollars per pixel, so every slope is exactly 45°; the height of
  the picture follows from its width.
- **Broken axes.** When *T*<sub>max</sub> + *L* is far to the right (thresholds in thousands, *L* in millions), the
  same amount is cut out of both axes where Swept **Out** leaves the "pan", and the line goes on across the cut as
  its own continuation. The cut shows only as two small marks on each axis.
- **Simpler layouts** follow from the parameters: a target *T* (one threshold) has no In Range band, just the point
  *x* = *T*; *τ* = 0 removes the jumps and the In Nominal zones; the formula leaves out the rows that cannot happen.
- **The points.** Where *y* jumps, the point belongs to the sweep (filled) and the zero line is open; a payment of
  exactly *L* is allowed (filled), Swept **Out** Overflow starts right after it (open, red).
- **Two lines on top:** *threshold:* −*τ*<sub>min</sub> [*T*<sub>min</sub> ; *T*<sub>max</sub>] +*τ*<sub>max</sub> and the
  interval (*T*<sub>min</sub> − *τ*<sub>min</sub> ; *T*<sub>max</sub> + *τ*<sub>max</sub>) where nothing is swept
  (*target balance:* [*T*]; a nominal of 0 is left out), and *sweep status:* of the balance under the probe.
- **Colours**: brown Swept **In** Partial, amber Swept **In** Full, blue the **In** zones, teal Swept **Out**, red and
  hatched the terminal failures, purple the probe and the $ of the swept amount. Text and axes follow the reader's
  light or dark mode.
- **Terminal failures** look the same: Swept **In** Zero and Swept **Out** Overflow are both red, hatched, ⏚, and
  their rows of the formula are red: no money moves in either.
- **Tiers** L (760 px), M (560 px), S (380 px) and smaller: see below.

## One picture, every size

`SvgRenderer.render(sweep)` returns **one** SVG that holds six tiers, nested `<svg>` elements, and shows the one that
fits the width it is shown at. Its own CSS media queries do it, so it works in `<img>`, `<object>`, a Markdown image or
opened directly (inline in HTML the width of the page counts instead):

| Shown at | Tier | What is in it |
|---|---|---|
| below 80 px | `icon-s` | the cart, bold, without the $ |
| 80–199 px | `icon` | the cart with its axes and the $ in it |
| 200–339 px | `tMINI` | the coloured slices, the axes and the function: no text |
| 340–539 px | `tS` | the graph, its labels and the status lines (no formula: it would break the harmony of a phone) |
| 540–739 px | `tM` | everything: the formula and the legend under the graph |
| 740 px and more | `tL` | everything, at full size |

The natural size of the picture is that of L, so the smaller tiers keep its proportions (an `<img>` cannot change its
height). `render(sweep, "S", 48)` and the like give one tier alone, at its own size.

Every picture is computed in advance, so the positions are finite: parameters are rounded to two significant digits
(`Sweep.snapped()`), and the current balance is sampled in 12, 24, 48 (default) or 96 **frames** (in each of S, M, L).
A frame is a thin transparent strip around its balance plus, hidden, the purple probe, the status line and the
highlighted row of the formula. CSS alone shows the frame under the mouse:

```css
.on { pointer-events: none }                      /* only the strips catch the mouse */
.f .on { visibility: hidden }
.f:hover .on { visibility: visible }
.frames:not(:hover) .d .on { visibility: visible } /* the default frame */
```

Hover works where the SVG is a document of its own (opened directly, `<object>`) or inline in HTML; as an `<img>` it
is static (the default frame).

## The Thymeleaf template

The markup is [`src/main/resources/templates/equilib.svg`](src/main/resources/templates/equilib.svg), a
[Thymeleaf](https://www.thymeleaf.org/) template in XML mode, without Spring: `SvgTemplate` sets up a
`TemplateEngine` with a class-path resolver (`templates/` + name + `.svg`) and processes it with a `Context` that holds
the model. `SvgRenderer` does all the geometry and builds that model from plain `Map`s and `List`s (OGNL, Thymeleaf's
expression language without Spring, reads map keys as properties):

```
width, height, viewBox      the natural size (that of L)
adaptive                    true: the two icon tiers are written too (th:if)
tiers[]                     cls ("tier tS" …), viewBox, layers[]
  layers[]                  cls ("zones", "marks", "axes", "function", "labels", "formula", "frames"), shapes[]
    shapes[]                tag ("line", "rect", "circle", "path", "text") and its attributes; text has a body (markup)
    frames[]                only in the "frames" layer: cls ("f", "f d" for the default), the strip, shapes[]
comment                     "generated … · Java … · Thymeleaf …", the first comment of every picture
```

The template walks it with a few features only:

- `th:each` over tiers, layers, shapes and frames; `th:switch` on `${e.tag}` with one `th:case` per kind of shape;
- `th:attr="x1=${e.x1},…,style=${e.style}"`: an attribute whose value is null is left out, so one line serves every
  shape of its kind (`marker-end` only where there is one);
- `th:utext="${e.body}"` for the text of a label, which may hold `<tspan>`s (subscripts, the bolder **In** and **Out**);
- `th:if="${adaptive}"` around the two icons, which are static SVG in the template, the same carts as `docs/`;
- the CSS in `<style>`: the light and dark colours, the media queries of the tiers and the `:hover` of the frames.

To change the look, edit the template (static parts, CSS); to change what is drawn, `SvgRenderer`.

## Use it

```java
Sweep sweep = new Sweep(40_000, 60_000, 8_000, 10_000, 25_000, 30_000);   // T_min, T_max, τ_min, τ_max, A, L
Sweep.Point p = sweep.at(85_000);          // SWEPT_OUT, 25_000
String svg = SvgRenderer.render(sweep);    // one adaptive SVG: icon … full graph
String s = SvgRenderer.render(sweep, "S", 48);   // one tier alone
```

Not published to Maven Central (yet): build it from this repository.

## Tests: demo and technical

- **Demo tests** (`@Tag("demo")`, [`SweepDemoTest`](src/test/java/org/krmdemo/plansinstr/equilib/SweepDemoTest.java)):
  the [report](https://krm-demo.github.io/ai-plans-instr/plans-math/tests/demo/index.html) shows everything the demo page can show. Six groups (two thresholds with
  nominals; no nominals; target balance; Swept **In** Zero; coinciding ends; broken axes); in each, sets of
  parameters titled as over the graph (`threshold: −$8k [$40k ; $60k] +$10k · A $25k · L $30k`); in each set, the
  balances in increasing order (`x = $32k = Tₘᵢₙ − τₘᵢₙ → Swept In Full $8k`), each checked against the rules
  written once more and shown at S, M and L side by side. About 170 cases, chosen by functional coverage: category
  partition, pairwise combination, equivalence classes and boundary values, output uniqueness
  ([`DemoCases`](src/test/java/org/krmdemo/plansinstr/equilib/DemoCases.java) explains).
  Change a colour or a font, push, scroll: every case shows the change.
- **Technical tests** (untagged): validation, frames, tiers, the README's status list —
  [report](https://krm-demo.github.io/ai-plans-instr/plans-math/tests/tech/index.html).

## Build

Java 21 and Maven, from the root of the repository:

```bash
mvn -pl plans-math -am verify                      # build, both kinds of tests, coverage, JavaDoc, pictures, demo page
mvn -pl plans-math -am verify -Dgroups=demo        # only the demo tests (or -DexcludedGroups=demo)
mvn -pl plans-math compile exec:java@docs          # only the pictures, into plans-math/docs
```

`verify` leaves in `plans-math/target/`: `open-test-report/demo/` and `open-test-report/tech/` (Open Test Reporting
XML and the pictures), `site/jacoco/` (coverage), `reports/apidocs/` (JavaDoc) and `pages/plans-math/` (the demo page
with its numbers, the pictures, the badge JSON). HTML from a test report:
`mvn -pl plans-math exec:java@open-test-html -Dotr.xml=… -Dotr.html=…` (as the workflow does).

## GitHub Pages

Every push to `main` that touches `plans-math/` runs [`.github/workflows/plans-math.yml`](../.github/workflows/plans-math.yml):
it builds and tests; `DemoPage` (a Thymeleaf template, [`src/site/index.html`](src/site/index.html)) writes the demo
page with the status list from `Zone` and the numbers of the tests and the coverage; the workflow turns the two
Open Test Reporting XMLs into HTML, adds them, the coverage report and the JavaDoc, and **commits** the lot to the
`gh-pages` branch (only the latest: the pictures of old runs do not pile up), which
GitHub Pages serves at [https://krm-demo.github.io/ai-plans-instr/plans-math/](https://krm-demo.github.io/ai-plans-instr/plans-math/).

## Files

```
pom.xml                         Thymeleaf; JaCoCo; surefire: demo and technical tests; JavaDoc; exec:java@docs,
                                @demo-page, @open-test-html
src/main/java/org/krmdemo/plansinstr/equilib/
  Sweep                         the parameters, the swept amount at() a balance, snapping
  Zone                          the statuses: titles, colours, terminal or not, the one description of each
  Layout                        data ranges and pixels, broken axes, 45° everywhere, clipping
  SvgRenderer                   the geometry: the tiers, zones, function, labels, formula, legend, the frames
  SvgTemplate                   renders it with Thymeleaf
  EquilibDocs                   writes the pictures of this README
src/main/resources/templates/equilib.svg   the Thymeleaf template of every picture
src/site/                       the demo page: index.html (Thymeleaf), the JavaScript widget, page.js (palettes)
src/test/java/…                 SweepDemoTest, DemoPictures, DemoProbes, DemoCases: the demo tests;
                                SweepTest, SvgRendererTest, SweepStatusTest: the technical ones;
                                DemoPage: writes the demo page
docs/                           the two icons
```

## References

- T. J. Ostrand, M. J. Balcer, *The category-partition method for specifying and generating functional tests*, CACM 31(6), 1988;
  N. Alshahwan, M. Harman, *Coverage and fault detection of the output-uniqueness test selection criteria*, ISSTA 2014
- [Thymeleaf](https://www.thymeleaf.org/doc/tutorials/3.1/usingthymeleaf.html), [Open Test Reporting](https://github.com/ota4j-team/open-test-reporting), [JaCoCo](https://www.jacoco.org/jacoco/)
- [Liang–Barsky line clipping](https://en.wikipedia.org/wiki/Liang%E2%80%93Barsky_algorithm)
- [Media queries in SVG](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_media_queries/Using_media_queries) apply to the SVG's own viewport
- [peaceiris/actions-gh-pages](https://github.com/peaceiris/actions-gh-pages): the commit to `gh-pages`
