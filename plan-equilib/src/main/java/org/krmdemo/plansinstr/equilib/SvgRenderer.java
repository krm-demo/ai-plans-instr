package org.krmdemo.plansinstr.equilib;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Draws the swept amount per current balance as one SVG that needs no JavaScript.
 * <p>
 * The picture is everything that does not depend on the probe (zones, the function, labels, the formula), plus a
 * finite number of <b>frames</b>: one per sampled current balance. A frame is a thin transparent vertical strip
 * around its balance and, hidden until the mouse is over that strip, the purple probe, the readout line on top
 * and the highlight of its row of the formula. CSS {@code :hover} does the rest, so only the frame under the mouse
 * is shown; with the mouse elsewhere, the default frame (about 30% into Swept out) is.
 * <p>
 * Hover works where the SVG is a document of its own or inline in HTML; as an {@code <img>} (a README on GitHub)
 * the picture is static and shows the default frame.
 */
public final class SvgRenderer {

    /** Allowed numbers of frames: a finite set, so that every picture can be rendered ahead of time. */
    public static final int[] FRAME_COUNTS = {12, 24, 48, 96};
    public static final int DEFAULT_FRAMES = 48;

    static final String PURPLE = "#8B5CF6";
    static final String GRAY = "#888780";
    static final String SERIF = "Georgia, 'Times New Roman', serif";
    static final String SANS = "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif";

    private final Sweep s;
    private final Layout g;
    private final Layout.Profile pf;
    private final StringBuilder h = new StringBuilder(64 * 1024);

    private SvgRenderer(Sweep s, Layout.Profile pf) {
        this.s = s;
        this.pf = pf;
        this.g = new Layout(s, pf);
    }

    /** The nearest allowed number of frames. */
    public static int frames(int n) {
        int best = DEFAULT_FRAMES;
        for (int c : FRAME_COUNTS) if (Math.abs(c - n) < Math.abs(best - n)) best = c;
        return best;
    }

    /**
     * @param sweep   the parameters (use {@link Sweep#snapped()} for a finite set of pictures)
     * @param size    "L" (760 px), "M" (560 px) or "S" (380 px)
     * @param frames  number of sampled balances, rounded to one of {@link #FRAME_COUNTS}
     */
    public static String render(Sweep sweep, String size, int frames) {
        return new SvgRenderer(sweep, Layout.Profile.of(size)).draw(frames(frames));
    }

    // ---------------------------------------------------------------- drawing

    private String draw(int n) {
        double tMin = s.thresholdMin(), tMax = s.thresholdMax(), nMin = s.nominalMin(), nMax = s.nominalMax();
        double a = s.sweptInMax(), l = s.sweptOutMax();
        double xl = tMin - nMin, xr = tMax + nMax, xk = tMin - a, xo = tMax + l;
        double xA = g.xMin(), xZ = g.xMax(), yA = g.yMin(), yZ = g.yMax(), y0 = g.y(0), ax = g.axisX();
        boolean zeroA = a == 0;
        double inLeft = zeroA ? xl : xk;                 // where the sweep-in zones meet

        // The formula and the legend go under the graph; their height is known before drawing.
        List<Row> rows = formulaRows();
        List<double[]> xLabels = new ArrayList<>();
        double rowsBottom = labelRows(xLabels);
        double fxTop = rowsBottom + 26, rowH = pf.font() * 1.55;
        double legendY = fxTop + rows.size() * rowH + 20;
        double height = legendY + (pf.width() < 500 ? 3 : 1) * 17 + 8;

        h.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(pf.width()).append(' ').append(r(height))
            .append("\" width=\"").append(pf.width()).append("\" height=\"").append(r(height)).append("\" role=\"img\"")
            .append(" aria-label=\"Balances Equilibrium: swept amount per current balance\">\n")
            .append("<title>Balances Equilibrium: swept amount per current balance</title>\n")
            .append("<style>\n")
            .append("svg{--ink:#18212c;--soft:#5a6777;--rule:#d6dde7;--bg:#fbfcfe}\n")
            .append("@media (prefers-color-scheme: dark){svg{--ink:#e6ebf2;--soft:#9aa7b8;--rule:#3a4452;--bg:#141a22}}\n")
            .append(".on{pointer-events:none}.f .on{visibility:hidden}.f:hover .on{visibility:visible}.frames:not(:hover) .d .on{visibility:visible}\n")
            .append(".hit{fill:#000;fill-opacity:0;pointer-events:all}.f:hover .hit{fill:").append(PURPLE).append(";fill-opacity:.05}\n")
            .append("text{font-family:").append(SANS).append("}.m{font-family:").append(SERIF).append(";font-style:italic}\n")
            .append("</style>\n")
            .append("<rect width=\"100%\" height=\"100%\" style=\"fill:var(--bg)\"/>\n")
            .append("<defs><pattern id=\"hatch\" width=\"6\" height=\"6\" patternUnits=\"userSpaceOnUse\" patternTransform=\"rotate(45)\">")
            .append("<line x1=\"0\" y1=\"0\" x2=\"0\" y2=\"6\" style=\"stroke:#E24B4A;stroke-width:1;stroke-opacity:.35\"/></pattern>")
            .append("<marker id=\"arrow\" viewBox=\"0 0 10 10\" refX=\"8\" refY=\"5\" markerWidth=\"6\" markerHeight=\"6\" orient=\"auto-start-reverse\">")
            .append("<path d=\"M2 1L8 5L2 9\" style=\"fill:none;stroke:var(--soft);stroke-width:1.5\"/></marker></defs>\n");

        // Zones: translucent bands per panel, hatched where the swept amount does not exist; a watermark each.
        double xoc = Math.min(xo, xZ);
        Object[][] bands = zeroA
            ? new Object[][] {{xA, xl, Zone.SWEPT_IN_ZERO}, {xl, tMin, Zone.IN_NOMINAL_MIN}, {tMin, tMax, Zone.IN_RANGE},
                              {tMax, xr, Zone.IN_NOMINAL_MAX}, {xr, xoc, Zone.SWEPT_OUT}, {xoc, xZ, Zone.OVERFLOW}}
            : new Object[][] {{xA, xk, Zone.SWEPT_IN_PARTIAL}, {xk, xl, Zone.SWEPT_IN_FULL}, {xl, tMin, Zone.IN_NOMINAL_MIN},
                              {tMin, tMax, Zone.IN_RANGE}, {tMax, xr, Zone.IN_NOMINAL_MAX}, {xr, xoc, Zone.SWEPT_OUT},
                              {xoc, xZ, Zone.OVERFLOW}};
        Layout.Seg low = g.ys.get(0);
        double wmY = (low.q0() + low.q1()) / 2;
        StringBuilder marks = new StringBuilder();
        for (Object[] b : bands) {
            Zone z = (Zone) b[2];
            double[] widest = null;
            for (Layout.Seg sx : g.xs) {
                double lo = Math.max((double) b[0], sx.d0()), hi = Math.min((double) b[1], sx.d1());
                if (hi <= lo) continue;
                double qa = sx.map(lo), w = sx.map(hi) - qa;
                if (w <= 0.5) continue;
                if (widest == null || w > widest[1]) widest = new double[] {qa, w};
                for (Layout.Seg sy : g.ys) {
                    String rect = "<rect x=\"" + r(qa) + "\" y=\"" + r(sy.qLo()) + "\" width=\"" + r(w) + "\" height=\"" + r(sy.qHi() - sy.qLo()) + "\"";
                    h.append(rect).append(" style=\"fill:").append(z.fill()).append(";fill-opacity:").append(z.opacity()).append("\"/>");
                    if (z.terminal()) h.append(rect).append(" fill=\"url(#hatch)\"/>");
                }
            }
            if (widest != null && widest[1] >= pf.wmMin()) {
                double cx = widest[0] + widest[1] / 2;
                marks.append("<text transform=\"rotate(-90 ").append(r(cx)).append(' ').append(r(wmY)).append(")\" x=\"").append(r(cx))
                    .append("\" y=\"").append(r(wmY + 5)).append("\" text-anchor=\"middle\" style=\"font-size:").append(pf.font())
                    .append("px;font-weight:500;letter-spacing:1.5px;fill:").append(z.ink()).append(";fill-opacity:")
                    .append(z.zero() && z != Zone.IN_RANGE ? ".42" : ".34").append("\">").append(z.title())
                    .append(z.terminal() ? " ⏚" : "").append("</text>");
            }
        }
        h.append('\n').append(marks).append("\n<g class=\"grid\"></g><g class=\"aux\"></g>\n");

        // Axes with break marks.
        String axSt = stroke("var(--soft)", 1, null, 1);
        for (int i = 0; i < g.xs.size(); i++) {
            Layout.Seg sx = g.xs.get(i);
            boolean last = i == g.xs.size() - 1;
            h.append("<line x1=\"").append(r(sx.q0())).append("\" y1=\"").append(r(y0)).append("\" x2=\"").append(r(last ? sx.q1() + 6 : sx.q1()))
                .append("\" y2=\"").append(r(y0)).append("\" style=\"").append(axSt).append('"').append(last ? " marker-end=\"url(#arrow)\"" : "").append("/>");
            if (!last) for (double q : new double[] {sx.q1(), g.xs.get(i + 1).q0()}) line(q - 3, y0 + 5, q + 3, y0 - 5, axSt);
        }
        for (int i = 0; i < g.ys.size(); i++) {
            Layout.Seg sy = g.ys.get(i);
            boolean last = i == g.ys.size() - 1;
            h.append("<line x1=\"").append(r(ax)).append("\" y1=\"").append(r(sy.q0())).append("\" x2=\"").append(r(ax)).append("\" y2=\"")
                .append(r(last ? sy.q1() - 6 : sy.q1())).append("\" style=\"").append(axSt).append('"').append(last ? " marker-end=\"url(#arrow)\"" : "").append("/>");
            if (!last) for (double q : new double[] {sy.q1(), g.ys.get(i + 1).q0()}) line(ax - 5, q + 3, ax + 5, q - 3, axSt);
        }
        text(ax + 8, g.plotTop - 8, null, "var(--soft)", "Swept amount, $", "m", 12);
        h.append('\n');

        // Projections of the points of interest onto the y axis, then the function.
        String proj = stroke(GRAY, 0.6, "1.5 3", 0.8);
        if (!zeroA) line(ax, g.y(a), g.x(Math.max(xk, xA)), g.y(a), stroke(Zone.SWEPT_IN_PARTIAL.ink(), 1, "1 3", 1));
        if (g.visY(l)) line(ax, g.y(l), pf.right(), g.y(l), stroke(Zone.OVERFLOW.ink(), 1, "1 3", 0.9));
        double axData = g.xs.get(0).has(0) ? 0 : xA;      // the y axis, in dollars
        if (nMin > 0 && !zeroA) seg(xl, nMin, axData, nMin, proj);
        if (nMax > 0) seg(xr, nMax, axData, nMax, proj);

        // Dashed in the zone's colour: what would happen without the nominals, the caps and the limit.
        seg(xl, nMin, tMin, 0, stroke(Zone.IN_RANGE.ink(), 1.4, "4 3", 0.85));
        seg(tMax, 0, xr, nMax, stroke(Zone.IN_RANGE.ink(), 1.4, "4 3", 0.85));
        if (zeroA) seg(xA, tMin - xA, xl, nMin, stroke(Zone.SWEPT_IN_ZERO.ink(), 1.4, "4 3", 0.7));
        else if (xk > xA) seg(xA, tMin - xA, xk, a, stroke(Zone.SWEPT_IN_FULL.ink(), 1.4, "4 3", 0.6));
        if (xo < xZ) seg(xo, l, xZ, xZ - tMax, stroke(Zone.OVERFLOW.ink(), 1.4, "4 3", 0.75));

        // Solid: what happens. Red dashed on the axis: the terminal failures (∄).
        double w = 2.75;
        if (zeroA) {
            seg(xA, 0, xl, 0, stroke(Zone.SWEPT_IN_ZERO.ink(), 3, "6 4", 1));
        } else {
            if (xk > xA) seg(xA, a, xk, a, stroke(Zone.SWEPT_IN_PARTIAL.ink(), w, null, 1));
            double a0 = Math.max(xk, xA);
            if (xl > xA) seg(a0, tMin - a0, xl, nMin, stroke(Zone.SWEPT_IN_FULL.ink(), w, null, 1));
            seg(xl, 0, xl, nMin, stroke(Zone.SWEPT_IN_FULL.ink(), 2, "4 3", 1));
        }
        seg(xl, 0, xr, 0, stroke(Zone.IN_RANGE.ink(), w, null, 1));
        if (xr < xZ) seg(xr, nMax, xoc, xoc - tMax, stroke(Zone.SWEPT_OUT.ink(), w, null, 1));
        seg(xr, 0, xr, nMax, stroke(Zone.SWEPT_OUT.ink(), 2, "4 3", 1));
        if (xo < xZ) seg(xo, 0, xZ, 0, stroke(Zone.OVERFLOW.ink(), 3, "6 4", 1));

        String open = "fill:var(--bg);stroke:" + Zone.IN_RANGE.ink() + ";stroke-width:2";
        if (!zeroA && xk > xA) dot(xk, a, 4.5, "fill:" + Zone.SWEPT_IN_PARTIAL.ink());
        if (zeroA || nMin > 0) dot(xl, 0, 5, open);
        if (!zeroA) dot(xl, nMin, 5, "fill:" + Zone.SWEPT_IN_FULL.ink());
        if (nMax > 0) dot(xr, 0, 5, open);
        dot(xr, nMax, 5, "fill:" + Zone.SWEPT_OUT.ink());
        if (xo < xZ) dot(xo, l, 5, "fill:" + Zone.SWEPT_OUT.ink());
        h.append('\n');

        // Labels: amounts under the x axis (in rows, never overlapping) and by the y axis.
        for (double[] q : xLabels) {
            line(g.x(q[0]), g.y(q[1]) + 5, g.x(q[0]), q[3] - 12, proj);
            text(q[2], q[3], "middle", ink((int) q[4]), Money.compact(q[0]), "m", pf.font());
        }
        yLabels(ax);
        text(pf.right(), rowsBottom + 6, "end", "var(--soft)", "Current balance, $", "m", 12);
        h.append('\n');

        // The formula: one row per zone (the frames highlight theirs), then the parameters.
        double fxLeft = pf.width() < 500 ? 8 : 40, colB = fxLeft + (pf.width() < 500 ? 120 : 190);
        double braceX = fxLeft + (pf.width() < 500 ? 46 : 62);
        text(fxLeft, fxTop + rows.size() * rowH / 2 - 2, null, "var(--ink)", tex("y(x) ="), "m", pf.font() + 1);
        double b1 = fxTop + 2, b2 = fxTop + rows.size() * rowH - 2, bm = (b1 + b2) / 2, bx = braceX + 6;
        h.append("<path d=\"M").append(r(bx + 6)).append(' ').append(r(b1)).append("Q").append(r(bx)).append(' ').append(r(b1)).append(' ')
            .append(r(bx)).append(' ').append(r(b1 + 8)).append("V").append(r(bm - 7)).append("Q").append(r(bx)).append(' ').append(r(bm)).append(' ')
            .append(r(bx - 6)).append(' ').append(r(bm)).append("Q").append(r(bx)).append(' ').append(r(bm)).append(' ').append(r(bx)).append(' ')
            .append(r(bm + 7)).append("V").append(r(b2 - 8)).append("Q").append(r(bx)).append(' ').append(r(b2)).append(' ').append(r(bx + 6))
            .append(' ').append(r(b2)).append("\" style=\"fill:none;stroke:var(--ink);stroke-width:1.6;stroke-linecap:round\"/>");
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            double yy = fxTop + (i + 0.72) * rowH;
            text(braceX + 18, yy, null, "var(--ink)", tex(row.value), "m", pf.font());
            text(colB, yy, null, "var(--ink)", tex(row.cond), "m", pf.font());
        }
        legend(legendY);
        h.append('\n');

        // The frames.
        frames(n, rows, fxTop, rowH, colB);
        h.append("</svg>\n");
        return h.toString();
    }

    // ---------------------------------------------------------------- frames

    private void frames(int n, List<Row> rows, double fxTop, double rowH, double colB) {
        // Sample balances evenly in pixels over all x pieces, snapped to the step of their piece.
        List<double[]> samples = new ArrayList<>();       // {balance, pixel, seg index}
        double total = 0;
        for (Layout.Seg sx : g.xs) total += sx.qHi() - sx.qLo();
        for (int i = 0; i < n; i++) {
            double q = (i + 0.5) * total / n;
            for (int k = 0; k < g.xs.size(); k++) {
                Layout.Seg sx = g.xs.get(k);
                double wdt = sx.qHi() - sx.qLo();
                if (q <= wdt || k == g.xs.size() - 1) {
                    double step = g.steps[k], x = Math.round(sx.unmap(sx.qLo() + Math.min(q, wdt)) / step) * step;
                    samples.add(new double[] {x, sx.map(x), k});
                    break;
                }
                q -= wdt;
            }
        }
        // The default frame: about 30% into Swept out (as far as it is in view).
        double outLo = s.thresholdMax() + s.nominalMax(), outHi = Math.min(s.thresholdMax() + s.sweptOutMax(), g.xs.get(0).d1());
        double target = outLo + 0.3 * (outHi - outLo);
        int dflt = 0;
        for (int i = 1; i < samples.size(); i++) {
            if (Math.abs(samples.get(i)[0] - target) < Math.abs(samples.get(dflt)[0] - target)) dflt = i;
        }

        h.append("<g class=\"frames\">\n");
        for (int i = 0; i < samples.size(); i++) {
            double[] sm = samples.get(i);
            Layout.Seg sx = g.xs.get((int) sm[2]);
            double lo = i > 0 && samples.get(i - 1)[2] == sm[2] ? (samples.get(i - 1)[1] + sm[1]) / 2 : sx.qLo();
            double hi = i < samples.size() - 1 && samples.get(i + 1)[2] == sm[2] ? (samples.get(i + 1)[1] + sm[1]) / 2 : sx.qHi();
            Sweep.Point p = s.at(Math.round(sm[0]));
            h.append("<g class=\"f").append(i == dflt ? " d" : "").append("\"><rect class=\"hit\" x=\"").append(r(lo)).append("\" y=\"")
                .append(r(g.plotTop)).append("\" width=\"").append(r(hi - lo)).append("\" height=\"").append(r(g.plotBottom - g.plotTop))
                .append("\"/><g class=\"on\">");
            seg(sm[0], g.yMin(), sm[0], g.yMax(), stroke(PURPLE, 1.4, "5 4", 0.9));
            if (p.amount() != null && g.visY(p.amount())) {
                double py = g.y(p.amount());
                line(g.axisX(), py, sm[1], py, stroke(PURPLE, 1.1, "3 3", 0.8));
                dot(sm[0], p.amount(), 7, "fill:" + PURPLE + ";fill-opacity:.18;stroke:" + PURPLE + ";stroke-width:1.4");
                dot(sm[0], p.amount(), 3.2, "fill:" + PURPLE);
            }
            readout(p);
            for (int k = 0; k < rows.size(); k++) {
                if (rows.get(k).zones.contains(p.zone())) {
                    h.append("<rect x=\"").append(r(colB - 8)).append("\" y=\"").append(r(fxTop + k * rowH + 1)).append("\" width=\"")
                        .append(r(pf.width() - colB)).append("\" height=\"").append(r(rowH - 2)).append("\" rx=\"5\" style=\"fill:")
                        .append(p.zone().fill()).append(";fill-opacity:.18\"/>");
                }
            }
            h.append("</g></g>\n");
        }
        h.append("</g>\n");
    }

    /** The line on top: the zone as a badge, then x → y. */
    private void readout(Sweep.Point p) {
        Zone z = p.zone();
        String title = z.title() + (z.terminal() ? " ⏚" : "");
        double bw = title.length() * 6.9 + 18;
        h.append("<rect x=\"0\" y=\"4\" width=\"").append(r(bw)).append("\" height=\"22\" rx=\"7\" style=\"fill:").append(z.fill()).append(";fill-opacity:.18\"/>");
        text(bw / 2, 19, "middle", z.ink(), title, null, 12);
        String y = p.amount() == null ? "∄ (terminal failure)" : Money.full(p.amount());
        h.append("<text x=\"").append(r(bw + 10)).append("\" y=\"19\" style=\"font-size:13px;fill:var(--soft)\"><tspan class=\"m\" style=\"fill:var(--ink)\">x</tspan> ")
            .append("<tspan style=\"fill:").append(PURPLE).append(";font-weight:600\">").append(Money.full(p.balance())).append("</tspan> → ")
            .append("<tspan class=\"m\" style=\"fill:var(--ink)\">y</tspan> <tspan style=\"fill:").append(PURPLE).append(";font-weight:600\">")
            .append(y).append("</tspan></text>");
    }

    // ---------------------------------------------------------------- labels, formula, legend

    /** Places the x labels in rows; fills {x, y of the point, centre, baseline, colour index}; returns the bottom. */
    private double labelRows(List<double[]> out) {
        double tMin = s.thresholdMin(), tMax = s.thresholdMax(), nMin = s.nominalMin(), nMax = s.nominalMax();
        double a = s.sweptInMax(), l = s.sweptOutMax();
        List<double[]> xs = new ArrayList<>();          // {x, y, pri, colour index}
        xs.add(new double[] {tMin, 0, 1, 0});
        if (!s.singleThreshold()) xs.add(new double[] {tMax, 0, 1, 0});
        if (nMin > 0) xs.add(new double[] {tMin - nMin, a == 0 ? 0 : nMin, 2, 1});
        if (nMax > 0) xs.add(new double[] {tMax + nMax, nMax, 2, 2});
        if (a > 0) xs.add(new double[] {tMin - a, a, 2, 3});
        xs.add(new double[] {tMax + l, l, 1, 4});
        xs.removeIf(q -> q[2] > pf.pri() || !g.visX(q[0]) || !g.visY(q[1]));
        xs.sort(Comparator.comparingDouble(q -> q[0]));
        List<Double> rowEnds = new ArrayList<>();
        double bottom = g.plotBottom + 18;
        for (double[] q : xs) {
            double w = Money.compact(q[0]).length() * 7.4 * pf.font() / 14 + 4;
            double cx = Math.min(Math.max(g.x(q[0]), w / 2 + 2), pf.width() - 2 - w / 2);
            int row = 0;
            while (row < rowEnds.size() && cx - w / 2 < rowEnds.get(row) + 9) row++;
            if (row == rowEnds.size()) rowEnds.add(0.0);
            rowEnds.set(row, cx + w / 2);
            double base = g.plotBottom + 18 + row * pf.rowGap();
            bottom = Math.max(bottom, base);
            out.add(new double[] {q[0], q[1], cx, base, q[3]});
        }
        return bottom + 14;
    }

    private String ink(int i) {
        return switch (i) {
            case 1 -> Zone.SWEPT_IN_FULL.ink();
            case 2 -> Zone.SWEPT_OUT.ink();
            case 3 -> Zone.SWEPT_IN_PARTIAL.ink();
            case 4 -> Zone.OVERFLOW.ink();
            default -> "var(--ink)";
        };
    }

    private void yLabels(double ax) {
        List<double[]> ys = new ArrayList<>();          // {y, colour index}
        if (s.sweptInMax() > 0) ys.add(new double[] {s.sweptInMax(), 3});
        ys.add(new double[] {s.sweptOutMax(), 4});
        if (pf.pri() >= 2 && s.nominalMin() > 0 && s.sweptInMax() > 0) ys.add(new double[] {s.nominalMin(), 1});
        if (pf.pri() >= 2 && s.nominalMax() > 0) ys.add(new double[] {s.nominalMax(), 2});
        ys.removeIf(q -> !g.visY(q[0]));
        ys.sort(Comparator.comparingDouble(q -> -g.y(q[0])));
        double prev = Double.MAX_VALUE;
        for (double[] q : ys) {
            double cy = Math.min(g.y(q[0]), prev - 15);
            prev = cy;
            line(ax - 3, g.y(q[0]), ax + 3, g.y(q[0]), stroke(ink((int) q[1]), 1.2, null, 1));
            text(ax + 7, cy - 4, null, ink((int) q[1]), Money.compact(q[0]), "m", pf.font());
        }
    }

    /** One row of the formula and the zones it covers. */
    private record Row(String value, String cond, List<Zone> zones) {
    }

    private List<Row> formulaRows() {
        String tn = s.singleThreshold() ? "T" : "T_min", tx = s.singleThreshold() ? "T" : "T_max";
        List<Row> rows = new ArrayList<>();
        if (s.sweptInMax() == 0) {
            rows.add(new Row("∄ ⏚", "x ≤ " + tn + " − τ_min", List.of(Zone.SWEPT_IN_ZERO)));
        } else {
            rows.add(new Row("A", "x < " + tn + " − A", List.of(Zone.SWEPT_IN_PARTIAL)));
            rows.add(new Row(tn + " − x", tn + " − A ≤ x ≤ " + tn + " − τ_min", List.of(Zone.SWEPT_IN_FULL)));
        }
        rows.add(new Row("0", tn + " − τ_min < x < " + tx + " + τ_max", List.of(Zone.IN_NOMINAL_MIN, Zone.IN_RANGE, Zone.IN_NOMINAL_MAX)));
        rows.add(new Row("x − " + tx, tx + " + τ_max ≤ x ≤ " + tx + " + L", List.of(Zone.SWEPT_OUT)));
        rows.add(new Row("∄ ⏚", "x > " + tx + " + L", List.of(Zone.OVERFLOW)));
        return rows;
    }

    private void legend(double y) {
        String[][] items = {
            {"T_min", Money.full(s.thresholdMin())}, {"T_max", Money.full(s.thresholdMax())},
            {"τ_min", Money.full(s.nominalMin())}, {"τ_max", Money.full(s.nominalMax())},
            {"A", Money.full(s.sweptInMax())}, {"L", Money.full(s.sweptOutMax())}};
        int perLine = pf.width() < 500 ? 2 : 6;
        double colW = (pf.width() - 16) / (double) perLine;
        for (int i = 0; i < items.length; i++) {
            double x = 8 + (i % perLine) * colW, yy = y + (i / perLine) * 17;
            h.append("<text x=\"").append(r(x)).append("\" y=\"").append(r(yy)).append("\" style=\"font-size:12px;fill:var(--soft)\">")
                .append("<tspan class=\"m\" style=\"font-size:13px;fill:var(--ink)\">").append(tex(items[i][0])).append("</tspan>")
                .append("<tspan dx=\"4\" dy=\"0\">").append(items[i][1]).append("</tspan></text>");
        }
    }

    // ---------------------------------------------------------------- primitives

    private static final Pattern SUB = Pattern.compile("([A-Za-zα-ω]+)_([a-z]+)");

    /** "T_min − τ_min" → letters with subscripts as tspans. */
    static String tex(String t) {
        StringBuilder o = new StringBuilder();
        Matcher m = SUB.matcher(t);
        int i = 0;
        boolean shifted = false;
        while (m.find()) {
            String pre = esc(t.substring(i, m.start()));
            if (!pre.isEmpty()) {
                o.append("<tspan dy=\"").append(shifted ? -4 : 0).append("\">").append(pre).append("</tspan>");
                shifted = false;
            }
            o.append("<tspan dy=\"").append(shifted ? -4 : 0).append("\">").append(m.group(1)).append("</tspan>")
                .append("<tspan dy=\"4\" style=\"font-size:0.75em;font-style:normal\">").append(m.group(2)).append("</tspan>");
            shifted = true;
            i = m.end();
        }
        String post = esc(t.substring(i));
        if (!post.isEmpty()) o.append("<tspan dy=\"").append(shifted ? -4 : 0).append("\">").append(post).append("</tspan>");
        return o.toString();
    }

    private void seg(double x1, double y1, double x2, double y2, String st) {
        for (Layout.Seg sx : g.xs) {
            for (Layout.Seg sy : g.ys) {
                double[] c = Layout.clip(x1, y1, x2, y2, sx.d0(), sx.d1(), sy.d0(), sy.d1());
                if (c != null) line(sx.map(c[0]), sy.map(c[1]), sx.map(c[2]), sy.map(c[3]), st);
            }
        }
    }

    private void dot(double x, double y, double radius, String st) {
        if (g.visX(x) && g.visY(y)) {
            h.append("<circle cx=\"").append(r(g.x(x))).append("\" cy=\"").append(r(g.y(y))).append("\" r=\"").append(radius)
                .append("\" style=\"").append(st).append("\"/>");
        }
    }

    private void line(double a, double b, double c, double d, String st) {
        h.append("<line x1=\"").append(r(a)).append("\" y1=\"").append(r(b)).append("\" x2=\"").append(r(c)).append("\" y2=\"")
            .append(r(d)).append("\" style=\"").append(st).append("\"/>");
    }

    private void text(double x, double y, String anchor, String colour, String body, String cls, double size) {
        h.append("<text x=\"").append(r(x)).append("\" y=\"").append(r(y)).append('"');
        if (anchor != null) h.append(" text-anchor=\"").append(anchor).append('"');
        if (cls != null) h.append(" class=\"").append(cls).append('"');
        h.append(" style=\"font-size:").append(r(size)).append("px;fill:").append(colour).append("\">")
            .append(body.startsWith("<tspan") ? body : esc(body)).append("</text>");
    }

    private static String stroke(String col, double w, String dash, double op) {
        return "stroke:" + col + ";stroke-width:" + w + (dash != null ? ";stroke-dasharray:" + dash : "")
            + (op != 1 ? ";stroke-opacity:" + op : "") + ";stroke-linecap:round;fill:none";
    }

    private static String r(double v) {
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("\\.?0+$", "");
    }

    private static String esc(String t) {
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
