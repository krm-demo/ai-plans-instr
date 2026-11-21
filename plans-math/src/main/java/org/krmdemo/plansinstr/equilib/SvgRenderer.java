package org.krmdemo.plansinstr.equilib;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Draws the swept amount per current balance as one SVG that needs no JavaScript.
 * <p>
 * This class does the geometry and builds a <b>model</b>: layers of plain shapes (line, rect, circle, path, text),
 * each a map of its attributes. The markup is the Thymeleaf template {@code templates/equilib.svg}, rendered by
 * {@link SvgTemplate}. Maps rather than records, because Thymeleaf's expressions (OGNL) read map keys directly.
 * <p>
 * Besides the static picture, the model has a finite number of <b>frames</b>: one per sampled current balance. A
 * frame is a thin transparent vertical strip around its balance and, hidden until the mouse is over that strip, the
 * purple probe, the status line on top and the highlight of its row of the formula. CSS {@code :hover} does the rest,
 * so only the frame under the mouse is shown; with the mouse elsewhere, the default frame is.
 */
public final class SvgRenderer {

    /** Allowed numbers of frames: a finite set, so that every picture can be rendered ahead of time. */
    public static final int[] FRAME_COUNTS = {12, 24, 48, 96};
    public static final int DEFAULT_FRAMES = 48;

    static final String PURPLE = "#8B5CF6";
    static final String GRAY = "#888780";

    private final Sweep s;
    private final Layout g;
    private final Layout.Profile pf;
    private final List<Map<String, Object>> layers = new ArrayList<>();
    private List<Map<String, Object>> cur;            // the shapes being added to

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
        return render(sweep, size, frames, null);
    }

    /** The same, with the default frame at exactly {@code probe} (the picture shown when the mouse is elsewhere). */
    public static String render(Sweep sweep, String size, int frames, Long probe) {
        return SvgTemplate.render(model(sweep, size, frames, probe));
    }

    /** The model the template renders: width, height, viewBox and the layers of shapes. */
    static Map<String, Object> model(Sweep sweep, String size, int frames, Long probe) {
        return new SvgRenderer(sweep, Layout.Profile.of(size)).draw(frames(frames), probe);
    }

    // ---------------------------------------------------------------- drawing

    private Map<String, Object> draw(int n, Long probe) {
        double tMin = s.thresholdMin(), tMax = s.thresholdMax(), nMin = s.nominalMin(), nMax = s.nominalMax();
        double a = s.sweptInMax(), l = s.sweptOutMax();
        double xl = tMin - nMin, xr = tMax + nMax, xk = tMin - a, xo = tMax + l;
        double xA = g.xMin(), xZ = g.xMax(), y0 = g.y(0), ax = g.axisX();
        boolean zeroA = a == 0;

        // The formula and the legend go under the graph; their height is known before drawing.
        List<Row> rows = formulaRows();
        List<double[]> xLabels = new ArrayList<>();
        double rowsBottom = labelRows(xLabels);
        double fxTop = rowsBottom + 26, rowH = pf.font() * 1.55;
        double legendY = fxTop + rows.size() * rowH + 20;
        double height = legendY + (pf.width() < 500 ? 3 : 1) * 17 + 8;

        // Zones: translucent bands the full height of the plot, hatched where the swept amount does not exist.
        layer("zones");
        double xoc = Math.min(xo, xZ);
        Object[][] bands = zeroA
            ? new Object[][] {{xA, xl, Zone.SWEPT_IN_ZERO}, {xl, tMin, Zone.IN_NOMINAL_MIN}, {tMin, tMax, Zone.IN_RANGE},
                              {tMax, xr, Zone.IN_NOMINAL_MAX}, {xr, xoc, Zone.SWEPT_OUT}, {xoc, xZ, Zone.OVERFLOW}}
            : new Object[][] {{xA, xk, Zone.SWEPT_IN_PARTIAL}, {xk, xl, Zone.SWEPT_IN_FULL}, {xl, tMin, Zone.IN_NOMINAL_MIN},
                              {tMin, tMax, Zone.IN_RANGE}, {tMax, xr, Zone.IN_NOMINAL_MAX}, {xr, xoc, Zone.SWEPT_OUT},
                              {xoc, xZ, Zone.OVERFLOW}};
        if (g.broken) {   // the cut of the x axis is filled when the same zone goes on behind it: unobtrusive
            Zone zl = s.at(Math.round(g.xs.get(0).d1())).zone(), zr = s.at(Math.round(g.xs.get(1).d0())).zone();
            if (zl == zr) band(g.xs.get(0).q1(), Layout.GAP, zl);
        }
        Layout.Seg low = g.ys.get(0);
        double wmY = (low.q0() + low.q1()) / 2;
        List<Runnable> watermarks = new ArrayList<>();
        for (Object[] b : bands) {
            Zone z = (Zone) b[2];
            double[] widest = null;
            for (Layout.Seg sx : g.xs) {
                double lo = Math.max((double) b[0], sx.d0()), hi = Math.min((double) b[1], sx.d1());
                if (hi <= lo) continue;
                double qa = sx.map(lo), w = sx.map(hi) - qa;
                if (w <= 0.5) continue;
                if (widest == null || w > widest[1]) widest = new double[] {qa, w};
                band(qa, w, z);
            }
            if (widest != null && widest[1] >= pf.wmMin()) {
                double cx = widest[0] + widest[1] / 2;
                watermarks.add(() -> cur.add(shape("text", "x", r(cx), "y", r(wmY + 5), "anchor", "middle",
                    "transform", "rotate(-90 " + r(cx) + " " + r(wmY) + ")",
                    "style", "font-size:" + pf.font() + "px;font-weight:500;letter-spacing:1.5px;fill:" + z.ink()
                        + ";fill-opacity:" + (z.zero() && z != Zone.IN_RANGE ? ".42" : ".34"),
                    "body", esc(z.title() + (z.terminal() ? " ⏚" : "")))));
            }
        }
        layer("marks");
        watermarks.forEach(Runnable::run);
        statusLabels();

        // Axes, continuous; where they are cut, two short slanted strokes in the middle of the cut.
        layer("axes");
        String axSt = stroke("var(--soft)", 1, null, 1);
        cur.add(shape("line", "x1", r(pf.left()), "y1", r(y0), "x2", r(pf.right() + 6), "y2", r(y0), "style", axSt, "markerEnd", "url(#arrow)"));
        cur.add(shape("line", "x1", r(ax), "y1", r(g.plotBottom), "x2", r(ax), "y2", r(g.plotTop - 6), "style", axSt, "markerEnd", "url(#arrow)"));
        if (g.broken) {
            String br = stroke("var(--soft)", 1, null, 0.8);
            double cq = g.xs.get(0).q1() + Layout.GAP / 2, cy = g.ys.get(0).q1() - Layout.GAP / 2;
            line(cq - 4, y0 + 4, cq - 1, y0 - 4, br);
            line(cq + 1, y0 + 4, cq + 4, y0 - 4, br);
            line(ax - 4, cy + 1, ax + 4, cy - 2, br);
            line(ax - 4, cy + 4, ax + 4, cy + 1, br);
        }
        cur.add(shape("text", "x", r(ax + 8), "y", r(g.plotTop - 8), "cls", "m", "style", "font-size:12px;fill:var(--soft)",
            "body", "Swept amount, <tspan style=\"fill:" + PURPLE + ";font-style:normal;font-weight:700\">$</tspan>"));

        // Projections of the points of interest onto the y axis, then the function.
        layer("function");
        String proj = stroke(GRAY, 0.6, "1.5 3", 0.8);
        double axData = g.xs.get(0).has(0) ? 0 : xA;      // the y axis, in dollars
        if (!zeroA) line(ax, g.y(a), g.x(Math.max(xk, xA)), g.y(a), stroke(Zone.SWEPT_IN_PARTIAL.ink(), 1, "1 3", 1));
        if (g.visY(l)) line(ax, g.y(l), pf.right(), g.y(l), stroke(Zone.OVERFLOW.ink(), 1, "1 3", 0.9));
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
        if (g.broken && xr < g.xs.get(0).d1()) {       // across the cut: from corner to corner, at 45°
            line(g.xs.get(0).q1(), g.ys.get(0).q1(), g.xs.get(1).q0(), g.ys.get(1).q0(), stroke(Zone.SWEPT_OUT.ink(), w, null, 1));
        }
        seg(xr, 0, xr, nMax, stroke(Zone.SWEPT_OUT.ink(), 2, "4 3", 1));
        if (xo < xZ) seg(xo, 0, xZ, 0, stroke(Zone.OVERFLOW.ink(), 3, "6 4", 1));

        // Points: filled where the point belongs to the piece, open where the piece only comes close.
        String red = Zone.OVERFLOW.ink(), open = "fill:var(--bg);stroke-width:2;stroke:";
        if (!zeroA && xk > xA) dot(xk, a, 4.5, "fill:" + Zone.SWEPT_IN_PARTIAL.ink());
        if (zeroA) {
            // T_min − τ_min itself needs a sweep in of τ_min, which the empty account cannot make: a failure point.
            dot(xl, 0, 5, nMin > 0 ? "fill:" + red : open + Zone.IN_RANGE.ink());
        } else {
            if (nMin > 0) dot(xl, 0, 5, open + Zone.IN_RANGE.ink());
            dot(xl, nMin, 5, "fill:" + Zone.SWEPT_IN_FULL.ink());
        }
        if (nMax > 0) dot(xr, 0, 5, open + Zone.IN_RANGE.ink());
        dot(xr, nMax, 5, "fill:" + Zone.SWEPT_OUT.ink());
        if (xo < xZ) {
            // T_max + L is still allowed (a payment of exactly L): filled on the line; Overflow starts just after it.
            dot(xo, l, 5, "fill:" + Zone.SWEPT_OUT.ink());
            dot(xo, 0, 5, open + red);
        }

        // Labels: amounts under the x axis (in rows, never overlapping) and by the y axis.
        layer("labels");
        for (double[] q : xLabels) {
            line(g.x(q[0]), g.y(q[1]) + 5, g.x(q[0]), q[3] - 12, proj);
            String colour = zeroA && q[0] == xl ? Zone.SWEPT_IN_ZERO.ink() : ink((int) q[4]);   // where Swept in zero ends
            text(q[2], q[3], "middle", colour, esc(Money.compact(q[0])), "m", pf.font());
        }
        yLabels(ax);
        text(pf.right(), rowsBottom + 6, "end", "var(--soft)", "Current balance, $", "m", 12);

        // The formula: one row per zone (the frames highlight theirs), then the parameters.
        layer("formula");
        double fxLeft = pf.width() < 500 ? 8 : 40, colB = fxLeft + (pf.width() < 500 ? 120 : 190);
        double braceX = fxLeft + (pf.width() < 500 ? 46 : 62);
        text(fxLeft, fxTop + rows.size() * rowH / 2 - 2, null, "var(--ink)", tex("y(x) ="), "m", pf.font() + 1);
        double b1 = fxTop + 2, b2 = fxTop + rows.size() * rowH - 2, bm = (b1 + b2) / 2, bx = braceX + 6;
        cur.add(shape("path", "style", "fill:none;stroke:var(--ink);stroke-width:1.6;stroke-linecap:round",
            "d", "M" + r(bx + 6) + " " + r(b1) + "Q" + r(bx) + " " + r(b1) + " " + r(bx) + " " + r(b1 + 8) + "V" + r(bm - 7)
                + "Q" + r(bx) + " " + r(bm) + " " + r(bx - 6) + " " + r(bm) + "Q" + r(bx) + " " + r(bm) + " " + r(bx) + " " + r(bm + 7)
                + "V" + r(b2 - 8) + "Q" + r(bx) + " " + r(b2) + " " + r(bx + 6) + " " + r(b2)));
        for (int i = 0; i < rows.size(); i++) {
            double yy = fxTop + (i + 0.72) * rowH;
            text(braceX + 18, yy, null, "var(--ink)", tex(rows.get(i).value), "m", pf.font());
            text(colB, yy, null, "var(--ink)", tex(rows.get(i).cond), "m", pf.font());
        }
        legend(legendY);

        // The frames.
        frames(n, probe, rows, fxTop, rowH, colB);

        Map<String, Object> model = new HashMap<>();
        model.put("width", String.valueOf(pf.width()));
        model.put("height", r(height));
        model.put("viewBox", "0 0 " + pf.width() + " " + r(height));
        model.put("layers", layers);
        return model;
    }

    // ---------------------------------------------------------------- frames

    private void frames(int n, Long probe, List<Row> rows, double fxTop, double rowH, double colB) {
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
        // The default frame: the given probe, or about 30% into Swept out (as far as it is in view).
        int dflt;
        if (probe != null) {
            probe = Math.max(Math.round(g.xMin()), Math.min(Math.round(g.xMax()), probe));   // keep it in view
            int k = 0;
            for (int i = 0; i < g.xs.size(); i++) if (probe >= g.xs.get(i).d0()) k = i;
            if (!g.visX(probe)) probe = Math.round(g.xs.get(k).d1());                          // not in the cut
            samples.add(new double[] {probe, g.xs.get(k).map(probe), k});
            samples.sort(Comparator.comparingDouble(q -> q[1]));
            dflt = 0;
            for (int i = 0; i < samples.size(); i++) if (samples.get(i)[0] == probe) dflt = i;
        } else {
            double outLo = s.thresholdMax() + s.nominalMax(), outHi = Math.min(s.thresholdMax() + s.sweptOutMax(), g.xs.get(0).d1());
            double target = outLo + 0.3 * (outHi - outLo);
            dflt = 0;
            for (int i = 1; i < samples.size(); i++) {
                if (Math.abs(samples.get(i)[0] - target) < Math.abs(samples.get(dflt)[0] - target)) dflt = i;
            }
        }

        Map<String, Object> framesLayer = layer("frames");
        List<Map<String, Object>> frames = new ArrayList<>();
        framesLayer.put("frames", frames);
        for (int i = 0; i < samples.size(); i++) {
            double[] sm = samples.get(i);
            Layout.Seg sx = g.xs.get((int) sm[2]);
            double lo = i > 0 && samples.get(i - 1)[2] == sm[2] ? (samples.get(i - 1)[1] + sm[1]) / 2 : sx.qLo();
            double hi = i < samples.size() - 1 && samples.get(i + 1)[2] == sm[2] ? (samples.get(i + 1)[1] + sm[1]) / 2 : sx.qHi();
            Sweep.Point p = s.at(Math.round(sm[0]));
            Map<String, Object> f = new HashMap<>();
            f.put("cls", i == dflt ? "f d" : "f");
            f.put("x", r(lo));
            f.put("y", r(g.plotTop));
            f.put("width", r(Math.max(hi - lo, 0.5)));
            f.put("height", r(g.plotBottom - g.plotTop));
            cur = new ArrayList<>();
            f.put("shapes", cur);
            line(sm[1], g.plotBottom, sm[1], g.plotTop, stroke(PURPLE, 1.4, "5 4", 0.9));
            if (p.amount() != null && g.visY(p.amount())) {
                double py = g.y(p.amount());
                line(g.axisX(), py, sm[1], py, stroke(PURPLE, 1.1, "3 3", 0.8));
                dot(sm[0], p.amount(), 7, "fill:" + PURPLE + ";fill-opacity:.18;stroke:" + PURPLE + ";stroke-width:1.4");
                dot(sm[0], p.amount(), 3.2, "fill:" + PURPLE);
            }
            statusLine(p);
            for (int k = 0; k < rows.size(); k++) {
                if (rows.get(k).zones.contains(p.zone())) {
                    cur.add(shape("rect", "x", r(colB - 8), "y", r(fxTop + k * rowH + 1), "width", r(pf.width() - colB),
                        "height", r(rowH - 2), "rx", "5", "style", "fill:" + p.zone().fill() + ";fill-opacity:.18"));
                }
            }
            frames.add(f);
        }
    }

    // ---------------------------------------------------------------- the two status lines

    private static final String LABEL = "font-size:12.5px;fill:var(--soft)";

    /** "threshold:" or, when T_min = T_max, "target balance:" — the label of the first status line. */
    static String thresholdLabel(Sweep s) {
        return s.singleThreshold() ? "target balance:" : "threshold:";
    }

    /**
     * The threshold with its nominals: "−$8k [$40k ; $60k] +$10k", just "[$50k]" for a target balance; a nominal of 0
     * is left out (no "−$0" or "+$0").
     */
    static String thresholdClosed(Sweep s) {
        String core = s.singleThreshold() ? "[" + Money.compact(s.thresholdMin()) + "]"
            : "[" + Money.compact(s.thresholdMin()) + " ; " + Money.compact(s.thresholdMax()) + "]";
        return (s.nominalMin() > 0 ? "−" + Money.compact(s.nominalMin()) + " " : "") + core
            + (s.nominalMax() > 0 ? " +" + Money.compact(s.nominalMax()) : "");
    }

    /** The same as an open interval: "($32k ; $70k)", the balances where nothing is swept. */
    static String thresholdOpen(Sweep s) {
        return "(" + Money.compact(s.thresholdMin() - s.nominalMin()) + " ; " + Money.compact(s.thresholdMax() + s.nominalMax()) + ")";
    }

    private static final double LABEL_W = 100;

    private void statusLabels() {
        cur.add(shape("text", "x", "0", "y", "17", "cls", "m", "style", LABEL, "body", esc(thresholdLabel(s))));
        cur.add(shape("text", "x", r(LABEL_W), "y", "17", "style", "font-size:13px;fill:var(--ink)",
            "body", esc(thresholdClosed(s)) + (pf.width() >= 500
                ? "<tspan dx=\"12\" style=\"fill:var(--soft)\">" + esc(thresholdOpen(s)) + "</tspan>" : "")));
        cur.add(shape("text", "x", "0", "y", "45", "cls", "m", "style", LABEL, "body", "sweep status:"));
    }

    /** The second status line, one per frame: the zone as a badge, the balance → the amount, which way the money goes. */
    private void statusLine(Sweep.Point p) {
        Zone z = p.zone();
        String title = z.title() + (z.terminal() ? " ⏚" : "");
        double bw = title.length() * 6.9 + 18, y0 = 30;
        cur.add(shape("rect", "x", r(LABEL_W), "y", r(y0), "width", r(bw), "height", "22", "rx", "7",
            "style", "fill:" + z.fill() + ";fill-opacity:.18"));
        text(LABEL_W + bw / 2, y0 + 15, "middle", z.ink(), esc(title), null, 12);
        String y = p.amount() == null ? "∄ (terminal failure)" : Money.full(p.amount());
        cur.add(shape("text", "x", r(LABEL_W + bw + 10), "y", r(y0 + 15), "style", "font-size:13px;fill:var(--soft)",
            "body", "<tspan style=\"fill:" + PURPLE + ";font-weight:600\">" + Money.full(p.balance()) + "</tspan> → "
                + "<tspan style=\"fill:" + PURPLE + ";font-weight:600\">" + esc(y) + "</tspan>"));
        String dir = direction(z);
        if (!dir.isEmpty()) {
            boolean below = pf.width() < 700;          // on M and S the direction gets a line of its own
            cur.add(shape("text", "x", below ? r(LABEL_W) : r(pf.width()), "y", r(below ? y0 + 38 : y0 + 15),
                "anchor", below ? null : "end", "cls", "m", "style", "font-size:12.5px;fill:var(--soft)", "body", dir));
        }
    }

    /** Which way the money goes in a zone; empty for the terminal failures. */
    static String direction(Zone z) {
        return switch (z) {
            case SWEPT_IN_PARTIAL, SWEPT_IN_FULL -> "from Investment-Account to DDA";
            case SWEPT_OUT -> "from DDA to Investment-Account";
            case SWEPT_IN_ZERO, OVERFLOW -> "";
            default -> "nothing to transfer";
        };
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
            text(ax + 7, cy - 4, null, ink((int) q[1]), esc(Money.compact(q[0])), "m", pf.font());
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
        boolean single = s.singleThreshold();
        List<String[]> items = new ArrayList<>();
        if (single) {
            items.add(new String[] {"T", Money.full(s.thresholdMin())});
        } else {
            items.add(new String[] {"T_min", Money.full(s.thresholdMin())});
            items.add(new String[] {"T_max", Money.full(s.thresholdMax())});
        }
        items.add(new String[] {"τ_min", Money.full(s.nominalMin())});
        items.add(new String[] {"τ_max", Money.full(s.nominalMax())});
        items.add(new String[] {"A", Money.full(s.sweptInMax())});
        items.add(new String[] {"L", Money.full(s.sweptOutMax())});
        int perLine = pf.width() < 500 ? 2 : 6;
        double colW = (pf.width() - 16) / (double) perLine;
        for (int i = 0; i < items.size(); i++) {
            double x = 8 + (i % perLine) * colW, yy = y + (i / perLine) * 17;
            cur.add(shape("text", "x", r(x), "y", r(yy), "style", "font-size:12px;fill:var(--soft)",
                "body", "<tspan class=\"m\" style=\"font-size:13px;fill:var(--ink)\">" + tex(items.get(i)[0]) + "</tspan>"
                    + "<tspan dx=\"4\">" + items.get(i)[1] + "</tspan>"));
        }
    }

    // ---------------------------------------------------------------- primitives

    private Map<String, Object> layer(String cls) {
        Map<String, Object> layer = new HashMap<>();
        layer.put("cls", cls);
        cur = new ArrayList<>();
        layer.put("shapes", cur);
        layers.add(layer);
        return layer;
    }

    /** A shape: its tag and attributes; null values are left out (and so leave out the attribute). */
    static Map<String, Object> shape(String tag, String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tag", tag);
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private void band(double qa, double w, Zone z) {
        String x = r(qa), y = r(g.plotTop), wd = r(w), ht = r(g.plotBottom - g.plotTop);
        cur.add(shape("rect", "x", x, "y", y, "width", wd, "height", ht, "style", "fill:" + z.fill() + ";fill-opacity:" + z.opacity()));
        if (z.terminal()) cur.add(shape("rect", "x", x, "y", y, "width", wd, "height", ht, "fill", "url(#hatch)"));
    }

    private static final Pattern SUB = Pattern.compile("([A-Za-zα-ω]+)_([a-z]+)");

    /** "T_min − τ_min" → letters with subscripts as tspans (escaped markup). */
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
            cur.add(shape("circle", "cx", r(g.x(x)), "cy", r(g.y(y)), "r", r(radius), "style", st));
        }
    }

    private void line(double a, double b, double c, double d, String st) {
        cur.add(shape("line", "x1", r(a), "y1", r(b), "x2", r(c), "y2", r(d), "style", st));
    }

    /** A text; {@code body} is markup (escaped text or tspans). */
    private void text(double x, double y, String anchor, String colour, String body, String cls, double size) {
        cur.add(shape("text", "x", r(x), "y", r(y), "anchor", anchor, "cls", cls,
            "style", "font-size:" + r(size) + "px;fill:" + colour, "body", body));
    }

    private static String stroke(String col, double w, String dash, double op) {
        return "stroke:" + col + ";stroke-width:" + w + (dash != null ? ";stroke-dasharray:" + dash : "")
            + (op != 1 ? ";stroke-opacity:" + op : "") + ";stroke-linecap:round;fill:none";
    }

    static String r(double v) {
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("\\.?0+$", "");
    }

    static String esc(String t) {
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
