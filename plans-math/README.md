# <img src="docs/icon-small.svg" width="56" height="32" alt=""> plans-math

[![plans-math](https://github.com/krm-demo/ai-plans-instr/actions/workflows/plans-math.yml/badge.svg)](https://github.com/krm-demo/ai-plans-instr/actions/workflows/plans-math.yml)
[![JUnit 5 tests](https://img.shields.io/endpoint?url=https://krm-demo.github.io/ai-plans-instr/plans-math/badges/tests.json)](https://krm-demo.github.io/ai-plans-instr/plans-math/tests/index.html)
[![JaCoCo coverage](https://img.shields.io/endpoint?url=https://krm-demo.github.io/ai-plans-instr/plans-math/badges/coverage.json)](https://krm-demo.github.io/ai-plans-instr/plans-math/jacoco/index.html)

[Site: krm-demo.org](https://krm-demo.org/swept-amount-math.en.html) ·
[Site preview (GitHub Pages)](https://krm-demo.github.io/krm-demo-org-site/swept-amount-math.en.html) ·
[Test report: every layout](https://krm-demo.github.io/ai-plans-instr/plans-math/tests/index.html) ·
[Coverage](https://krm-demo.github.io/ai-plans-instr/plans-math/jacoco/index.html)

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
to open the SVG itself and move the mouse over it. More layouts, one per test: [the test report](https://krm-demo.github.io/ai-plans-instr/plans-math/tests/index.html).

The interactive page with handles to drag is on the site; it is a page of the site, written in JavaScript, not part
of this library.

## Contents

- [Parameters](#parameters)
- [How it is drawn](#how-it-is-drawn)
- [Without JavaScript](#without-javascript)
- [Use it](#use-it)
- [Build](#build)
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
- **Sizes** L (760 px), M (560 px), S (380 px); smaller sizes show fewer labels.

## Without JavaScript

The SVG is made with [Thymeleaf](https://www.thymeleaf.org/) (no Spring): `SvgRenderer` computes the geometry and
hands the template [`templates/equilib.svg`](src/main/resources/templates/equilib.svg) layers of plain shapes. The
first line of every picture is a comment: when it was made, with which Java and which Thymeleaf.

Every picture is computed in advance, so the positions are finite: parameters are rounded to two significant digits
(`Sweep.snapped()`), and the current balance is sampled in 12, 24, 48 (default) or 96 **frames**. One SVG holds the
static picture and all frames; a frame is a thin transparent strip around its balance plus, hidden, the purple probe,
the status line and the highlighted row of the formula. CSS alone shows the frame under the mouse:

```css
.on { pointer-events: none }                      /* only the strips catch the mouse */
.f .on { visibility: hidden }
.f:hover .on { visibility: visible }
.frames:not(:hover) .d .on { visibility: visible } /* the default frame */
```

Hover works where the SVG is a document of its own or inline in HTML; as an `<img>` it is static (the default frame).

## Use it

```java
Sweep sweep = new Sweep(40_000, 60_000, 8_000, 10_000, 25_000, 30_000);   // T_min, T_max, τ_min, τ_max, A, L
Sweep.Point p = sweep.at(85_000);          // SWEPT_OUT, 25_000
String svg = SvgRenderer.render(sweep, "L", 48);
```

Not published to Maven Central (yet): build it from this repository.

## Build

```bash
mvn -pl plans-math -am verify                      # build, tests, coverage, pictures (target/pages/plans-math/svg)
mvn -pl plans-math compile exec:java@docs          # only the pictures
```

Every push to `main` that touches `plans-math/` runs [`.github/workflows/plans-math.yml`](../.github/workflows/plans-math.yml):
build and test, turn the Open Test Reporting XML into HTML, and publish the test report (one picture per layout), the
coverage report, the pictures and the badge numbers on [GitHub Pages](https://krm-demo.github.io/ai-plans-instr/plans-math/).

## Files

```
pom.xml                         Thymeleaf; JaCoCo; exec:java@docs (pictures), exec:java@open-test-html (report)
src/main/java/org/krmdemo/plansinstr/equilib/
  Sweep                         the parameters, the swept amount at() a balance, snapping
  Zone                          the statuses: titles, colours, terminal or not, the one description of each
  Layout                        data ranges and pixels, broken axes, 45° everywhere, clipping
  SvgRenderer                   the geometry: zones, function, labels, formula, legend, the frames
  SvgTemplate                   renders it with Thymeleaf
  EquilibDocs                   writes the pictures of this README (published on GitHub Pages)
src/main/resources/templates/equilib.svg   the Thymeleaf template of every picture
src/test/java/…                 SweepTest, SvgRendererTest, SweepStatusGalleryTest (one test per layout; and the
                                check that the status list above is Zone's, word for word)
docs/                           the two icons
```

## References

- [Thymeleaf](https://www.thymeleaf.org/doc/tutorials/3.1/usingthymeleaf.html), [Open Test Reporting](https://github.com/ota4j-team/open-test-reporting), [JaCoCo](https://www.jacoco.org/jacoco/)
- [Liang–Barsky line clipping](https://en.wikipedia.org/wiki/Liang%E2%80%93Barsky_algorithm)
- The interactive page: [krm-demo-org-site, `site/projects/swept-amount-math/`](https://github.com/krm-demo/krm-demo-org-site/tree/main/site/projects/swept-amount-math)
