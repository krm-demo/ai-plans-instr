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

    /**
     * How much of the picture a tier draws. The adaptive SVG holds them all and shows the one that fits the width it
     * is shown at; ICON_S and ICON are the fixed cart icons in the template.
     * <ul>
     * <li>MINI: S geometry, only the coloured slices, the axes, the function and its points: no text, no frames;</li>
     * <li>S: the graph with its labels and the status lines (the formula would break the harmony of a phone);</li>
     * <li>M and L: everything, the formula and the legend under the graph.</li>
     * </ul>
     */
    enum Detail {
        MINI("S"), S("S"), M("M"), L("L");

        final String profile;

        Detail(String profile) {
            this.profile = profile;
        }

        boolean text() { return this != MINI; }

        boolean formula() { return this == M || this == L; }

        static Detail of(String name) {
            return switch (name == null ? "L" : name.toUpperCase()) {
                case "MINI" -> MINI;
                case "S" -> S;
                case "M" -> M;
                default -> L;
            };
        }
    }

    /** The widths (CSS px of the rendered picture) where the adaptive SVG switches to the next tier. */
    public static final int[] TIER_WIDTHS = {80, 200, 340, 540, 740};

    private final Sweep s;
    private final Layout g;
    private final Layout.Profile pf;
    private final Detail detail;
    private final List<Map<String, Object>> layers = new ArrayList<>();
    private List<Map<String, Object>> cur;            // the shapes being added to

    private SvgRenderer(Sweep s, Detail detail) {
        this.s = s;
        this.detail = detail;
        this.pf = Layout.Profile.of(detail.profile);
        this.g = new Layout(s, pf);
    }

    /** The nearest allowed number of frames. */
    public static int frames(int n) {
        int best = DEFAULT_FRAMES;
        for (int c : FRAME_COUNTS) if (Math.abs(c - n) < Math.abs(best - n)) best = c;
        return best;
    }

    /**
     * The adaptive picture: one SVG that looks like an icon when shown small and like the full graph, with its
     * frames, when shown large. Its own CSS media queries pick the tier by the width the SVG is shown at
     * (in {@code <img>}, {@code <object>} or opened directly; inline in HTML the page's width counts instead):
     * below 80 px the small cart icon, below 200 px the icon with the $ in the cart, below 340 px the coloured
     * slices only, below 540 px S, below 740 px M, from 740 px L. Its natural size is that of L.
     *
     * @param sweep the parameters (use {@link Sweep#snapped()} for a finite set of pictures)
     */
    public static String render(Sweep sweep) {
        return render(sweep, DEFAULT_FRAMES, null);
    }

    /**
     * The adaptive picture with {@code frames} sampled balances (rounded to one of {@link #FRAME_COUNTS}) and its
     * default frame (shown when the mouse is elsewhere) at exactly {@code probe}, or 30% into Swept Out when null.
     */
    public static String render(Sweep sweep, int frames, Long probe) {
        return SvgTemplate.render(adaptiveModel(sweep, frames, probe));
    }

    /**
     * One tier only, at its fixed size: "L" (760 px), "M" (560 px), "S" (380 px) or "mini" (the slices, 380 px).
     */
    public static String render(Sweep sweep, String tier, int frames) {
        return SvgTemplate.render(singleModel(sweep, tier, frames, null));
    }

    static Map<String, Object> adaptiveModel(Sweep sweep, int frames, Long probe) {
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (Detail d : Detail.values()) tiers.add(new SvgRenderer(sweep, d).draw(frames(frames), probe));
        Map<String, Object> l = tiers.get(tiers.size() - 1);
        Map<String, Object> model = new HashMap<>();
        model.put("adaptive", Boolean.TRUE);
        model.put("width", l.get("width"));
        model.put("height", l.get("height"));
        model.put("viewBox", "0 0 " + l.get("width") + " " + l.get("height"));
        model.put("tiers", tiers);
        return model;
    }

    static Map<String, Object> singleModel(Sweep sweep, String tier, int frames, Long probe) {
        Map<String, Object> t = new SvgRenderer(sweep, Detail.of(tier)).draw(frames(frames), probe);
        t.put("cls", t.get("cls") + " one");
        Map<String, Object> model = new HashMap<>();
        model.put("width", t.get("width"));
        model.put("height", t.get("height"));
        model.put("viewBox", "0 0 " + t.get("width") + " " + t.get("height"));
        model.put("tiers", List.of(t));
        return model;
    }

    // ---------------------------------------------------------------- drawing

    private Map<String, Object> draw(int n, Long probe) {
        double tMin = s.thresholdMin(), tMax = s.thresholdMax(), nMin = s.nominalMin(), nMax = s.nominalMax();
        double a = s.sweptInMax(), l = s.sweptOutMax();
        double xl = tMin - nMin, xr = tMax + nMax, xk = tMin - a, xo = tMax + l;
        double xA = g.xMin(), xZ = g.xMax(), y0 = g.y(0), ax = g.axisX();
        boolean zeroA = a == 0;

        // The formula and the legend go under the graph; their height is known before drawing.
        List<Row> rows = detail.formula() ? formulaRows() : List.of();
        List<double[]> xLabels = new ArrayList<>();
        double rowsBottom = detail.text() ? labelRows(xLabels) : g.plotBottom;
        double fxTop = rowsBottom + 26, rowH = pf.font() * 1.55;
        double legendY = fxTop + rows.size() * rowH + 20;
        double height = detail.formula() ? legendY + (pf.width() < 500 ? 3 : 1) * 17 + 8 : rowsBottom + 12;

        // Zones: translucent bands the full height of the plot, hatched where the swept amount does not exist.
        layer("zones");
        double xoc = Math.min(xo, xZ);
        Object[][] bands = zeroA
            ? new Object[][] {{xA, xl, Zone.SWEPT_IN_ZERO}, {xl, tMin, Zone.IN_NOMINAL_MIN}, {tMin, tMax, Zone.IN_RANGE},
                              {tMax, xr, Zone.IN_NOMINAL_MAX}, {xr, xoc, Zone.SWEPT_OUT}, {xoc, xZ, Zone.SWEPT_OUT_OVERFLOW}}
            : new Object[][] {{xA, xk, Zone.SWEPT_IN_PARTIAL}, {xk, xl, Zone.SWEPT_IN_FULL}, {xl, tMin, Zone.IN_NOMINAL_MIN},
                              {tMin, tMax, Zone.IN_RANGE}, {tMax, xr, Zone.IN_NOMINAL_MAX}, {xr, xoc, Zone.SWEPT_OUT},
                              {xoc, xZ, Zone.SWEPT_OUT_OVERFLOW}};
        if (g.broken) {   // the cut of the x axis is filled when the same zone goes on behind it: unobtrusive
            Zone zl = s.at(Math.round(g.xs.get(0).d1())).zone(), zr = s.at(Math.round(g.xs.get(1).d0())).zone();
            if (zl == zr) band(g.xs.get(0).q1(), Layout.GAP, zl);
        }
        double wmY = g.plotTop + 0.1 * (g.plotBottom - g.plotTop);   // watermarks hang 10% below the top of the plot
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
            if (widest != null && widest[1] >= pf.wmMin() && detail.text()) {
                double cx = widest[0] + widest[1] / 2;
                watermarks.add(() -> cur.add(shape("text", "x", r(cx), "y", r(wmY + 5), "anchor", "end",
                    "transform", "rotate(-90 " + r(cx) + " " + r(wmY) + ")",
                    "cls", "wm", "style", "font-size:" + (pf.font() - 1) + "px;fill:" + z.ink()
                        + ";fill-opacity:" + (z.zero() && z != Zone.IN_RANGE ? ".44" : ".36"),
                    "body", z.titleMarkup(800))));
            }
        }
        edges(bands);
        if (detail.text()) {
            layer("marks");
            watermarks.forEach(Runnable::run);
            statusLabels();
        }

        // Axes, continuous; where they are cut, two short slanted strokes in the middle of the cut.
        layer("axes");
        String axSt = stroke("var(--soft)", 1, null, 1);
        cur.add(shape("line", "x1", r(pf.left()), "y1", r(y0), "x2", r(pf.right() + 12), "y2", r(y0), "style", axSt, "markerEnd", "url(#arrow)"));
        cur.add(shape("line", "x1", r(ax), "y1", r(g.plotBottom), "x2", r(ax), "y2", r(g.plotTop - 12), "style", axSt, "markerEnd", "url(#arrow)"));
        if (g.broken) {
            String br = stroke("var(--soft)", 1, null, 0.8);
            double cq = g.xs.get(0).q1() + Layout.GAP / 2, cy = g.ys.get(0).q1() - Layout.GAP / 2;
            line(cq - 4, y0 + 4, cq - 1, y0 - 4, br);
            line(cq + 1, y0 + 4, cq + 4, y0 - 4, br);
            line(ax - 4, cy + 1, ax + 4, cy - 2, br);
            line(ax - 4, cy + 4, ax + 4, cy + 1, br);
        }
        if (detail.text()) {
            cur.add(shape("text", "x", r(ax + 10), "y", r(g.plotTop - 6), "cls", "m", "style", "font-size:" + (pf.font() - 1) + "px;fill:var(--soft)",
                "body", "Swept amount, <tspan style=\"fill:" + PURPLE + ";font-style:normal;font-weight:700\">$</tspan>"));
        }

        // Projections of the points of interest onto the y axis, then the function.
        layer("function");
        String proj = stroke(GRAY, 0.6, "1.5 3", 0.8);
        double axData = g.xs.get(0).has(0) ? 0 : xA;      // the y axis, in dollars
        if (!zeroA) line(ax, g.y(a), g.x(Math.max(xk, xA)), g.y(a), stroke(Zone.SWEPT_IN_PARTIAL.ink(), 1, "1 3", 1));
        if (g.visY(l)) line(ax, g.y(l), pf.right(), g.y(l), stroke(Zone.SWEPT_OUT_OVERFLOW.ink(), 1, "1 3", 0.9));
        if (nMin > 0 && !zeroA) seg(xl, nMin, axData, nMin, proj);
        if (nMax > 0) seg(xr, nMax, axData, nMax, proj);

        // Dashed in the zone's colour: what would happen without the nominals, the caps and the limit.
        seg(xl, nMin, tMin, 0, stroke(Zone.IN_RANGE.ink(), 1.4, "4 3", 0.85));
        seg(tMax, 0, xr, nMax, stroke(Zone.IN_RANGE.ink(), 1.4, "4 3", 0.85));
        if (zeroA) seg(xA, tMin - xA, xl, nMin, stroke(Zone.SWEPT_IN_ZERO.ink(), 1.4, "4 3", 0.7));
        else if (xk > xA) seg(xA, tMin - xA, xk, a, stroke(Zone.SWEPT_IN_FULL.ink(), 1.4, "4 3", 0.6));
        if (xo < xZ) seg(xo, l, xZ, xZ - tMax, stroke(Zone.SWEPT_OUT_OVERFLOW.ink(), 1.4, "4 3", 0.75));

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
        if (xo < xZ) seg(xo, 0, xZ, 0, stroke(Zone.SWEPT_OUT_OVERFLOW.ink(), 3, "6 4", 1));

        // Points: filled where the point belongs to the piece, open where the piece only comes close.
        String red = Zone.SWEPT_OUT_OVERFLOW.ink(), open = "fill:var(--bg);stroke-width:2;stroke:";
        if (!zeroA && xk > xA) {   // T_min − A belongs to Swept In Full (filled in its colour), not to Partial (the ring)
            dot(xk, a, 4.5, "fill:" + Zone.SWEPT_IN_FULL.ink() + ";stroke:" + Zone.SWEPT_IN_PARTIAL.ink() + ";stroke-width:2");
        }
        // With τ = 0 there is no jump: T_min (T_max) itself needs nothing, so it is In Range, a filled blue point.
        String blue = "fill:" + Zone.IN_RANGE.ink();
        if (nMin == 0) {
            dot(xl, 0, 5, blue);
        } else if (zeroA) {
            dot(xl, 0, 5, "fill:" + red);              // T_min − τ_min needs τ_min, which the empty account cannot give
        } else {
            dot(xl, 0, 5, open + Zone.IN_RANGE.ink());
            dot(xl, nMin, 5, "fill:" + Zone.SWEPT_IN_FULL.ink());
        }
        if (nMax == 0) {
            dot(xr, 0, 5, blue);
        } else {
            dot(xr, 0, 5, open + Zone.IN_RANGE.ink());
            dot(xr, nMax, 5, "fill:" + Zone.SWEPT_OUT.ink());
        }
        if (xo < xZ) {
            // T_max + L is still allowed (a payment of exactly L): filled on the line; Overflow starts just after it.
            dot(xo, l, 5, "fill:" + Zone.SWEPT_OUT.ink());
            dot(xo, 0, 5, open + red);
        }

        Map<String, Object> tier = new HashMap<>();
        tier.put("cls", "tier t" + detail.name());
        tier.put("layers", layers);
        if (!detail.text()) {           // the slices only: cropped to the plot and its arrows
            double x0 = pf.left() - 8, y0t = g.plotTop - 12;
            tier.put("width", r(pf.right() + 14 - x0));
            tier.put("height", r(g.plotBottom + 8 - y0t));
            tier.put("viewBox", r(x0) + " " + r(y0t) + " " + tier.get("width") + " " + tier.get("height"));
            return tier;
        }

        // Labels: amounts under the x axis (in rows, never overlapping) and by the y axis.
        layer("labels");
        for (double[] q : xLabels) {
            line(g.x(q[0]), g.y(q[1]) + 5, g.x(q[0]), q[3] - 12, proj);
            String colour = zeroA && nMin > 0 && q[0] == xl ? Zone.SWEPT_IN_ZERO.ink() : ink((int) q[4]);   // where Swept In Zero ends
            text(q[2], q[3], "middle", colour, esc(Money.compact(q[0])), "tx", pf.font());
        }
        yLabels(ax);
        text(pf.right() + 12, rowsBottom + 8, "end", "var(--soft)", "Current balance, $", "m", pf.font() - 1);
        // The origin: "0" below the x axis and left of the y axis, where no line crosses it.
        if (g.xs.get(0).has(0)) text(ax - 6, y0 + pf.font() + 1, "end", "var(--soft)", "0", "tx", pf.font());

        // The formula: one row per status, the status on the right (the frames highlight theirs and add the amount).
        if (detail.formula()) formula(rows, fxTop, rowH, legendY);

        // The frames.
        frames(n, probe, rows, fxTop, rowH);

        tier.put("width", String.valueOf(pf.width()));
        tier.put("height", r(height));
        tier.put("viewBox", "0 0 " + pf.width() + " " + r(height));
        return tier;
    }

    private void formula(List<Row> rows, double fxTop, double rowH, double legendY) {
        layer("formula");
        double fxLeft = 8, valX = valX(), colB = valX + (pf.width() < 500 ? 128 : 150);
        double braceX = fxLeft + (pf.width() < 500 ? 46 : 62);
        text(fxLeft, fxTop + rows.size() * rowH / 2 + 4, null, "var(--ink)", tex("y(x) ="), "tx", pf.font() + 1);
        double b1 = fxTop + 2, b2 = fxTop + rows.size() * rowH - 2, bm = (b1 + b2) / 2, bx = braceX + 6;
        cur.add(shape("path", "style", "fill:none;stroke:var(--ink);stroke-width:1.6;stroke-linecap:round",
            "d", "M" + r(bx + 6) + " " + r(b1) + "Q" + r(bx) + " " + r(b1) + " " + r(bx) + " " + r(b1 + 8) + "V" + r(bm - 7)
                + "Q" + r(bx) + " " + r(bm) + " " + r(bx - 6) + " " + r(bm) + "Q" + r(bx) + " " + r(bm) + " " + r(bx) + " " + r(bm + 7)
                + "V" + r(b2 - 8) + "Q" + r(bx) + " " + r(b2) + " " + r(bx + 6) + " " + r(b2)));
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            double yy = fxTop + (i + 0.72) * rowH;
            String col = row.zone.terminal() ? row.zone.ink() : "var(--ink)";   // no money moves: red, both of them
            text(valX, yy, null, col, tex(row.value), "tx", pf.font());
            text(colB, yy, null, col, tex(row.cond), "tx", pf.font());
            if (pf.width() >= 500) {
                text(pf.width() - 8, yy, "end", row.zone.ink(), row.zone.titleMarkup(700), null, pf.width() < 700 ? 11.5 : 12.5);
            }
        }
        legend(legendY);
    }

    /** Where the values of the formula start. */
    private double valX() {
        return 8 + (pf.width() < 500 ? 70 : 84);
    }

    // ---------------------------------------------------------------- frames

    private void frames(int n, Long probe, List<Row> rows, double fxTop, double rowH) {
        double valX = valX();
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
                Row row = rows.get(k);
                if (row.zone == p.zone()) {
                    // the row of this balance: highlighted, with the value of y right after its expression
                    cur.add(shape("rect", "x", r(valX - 8), "y", r(fxTop + k * rowH + 1), "width", r(pf.width() - valX + 4),
                        "height", r(rowH - 2), "rx", "5", "style", "fill:" + p.zone().fill() + ";fill-opacity:.18"));
                    if (p.amount() != null) {                  // ∄ needs no "= ∄"
                        String v = pf.width() < 500 ? Money.compact(p.amount()) : Money.full(p.amount());
                        cur.add(shape("text", "x", r(valX + plainWidth(row.value) + 10), "y", r(fxTop + (k + 0.72) * rowH), "style",
                            "font-size:" + (pf.font() - 1) + "px;font-weight:600;fill:" + PURPLE, "body", esc("= " + v)));
                    }
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
        double bw = title.length() * 7.1 + 18, y0 = 30;
        cur.add(shape("rect", "x", r(LABEL_W), "y", r(y0), "width", r(bw), "height", "22", "rx", "7",
            "style", "fill:" + z.fill() + ";fill-opacity:.18"));
        text(LABEL_W + bw / 2, y0 + 15, "middle", z.ink(), z.titleMarkup(700), null, 12);
        String y = p.amount() == null ? "∄ (terminal failure)" : Money.full(p.amount());
        cur.add(shape("text", "x", r(LABEL_W + bw + 10), "y", r(y0 + 15), "style", "font-size:13px;fill:var(--soft)",
            "body", "<tspan style=\"fill:" + PURPLE + ";font-weight:600\">" + Money.full(p.balance()) + "</tspan> → "
                + "<tspan style=\"fill:" + PURPLE + ";font-weight:600\">" + esc(y) + "</tspan>"));
        String dir = direction(z);
        if (!dir.isEmpty()) {
            boolean below = pf.width() < 500;          // on S the direction gets a line of its own
            cur.add(shape("text", "x", below ? r(LABEL_W) : r(pf.width()), "y", r(below ? y0 + 38 : y0 + 15),
                "anchor", below ? null : "end", "cls", "m", "style", "font-size:12.5px;fill:var(--soft)", "body", dir));
        }
    }

    /** Which way the money goes in a zone; empty for the terminal failures. */
    static String direction(Zone z) {
        return switch (z) {
            case SWEPT_IN_PARTIAL, SWEPT_IN_FULL -> "from Investment-Account to DDA";
            case SWEPT_OUT -> "from DDA to Investment-Account";
            case SWEPT_IN_ZERO, SWEPT_OUT_OVERFLOW -> "";
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
            case 4 -> Zone.SWEPT_OUT_OVERFLOW.ink();
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
        java.util.Set<Double> seen = new java.util.HashSet<>();
        ys.removeIf(q -> !seen.add(q[0]));             // τ_min = τ_max: one label is enough
        ys.sort(Comparator.comparingDouble(q -> -g.y(q[0])));
        double prev = Double.MAX_VALUE;
        for (double[] q : ys) {
            double cy = Math.min(g.y(q[0]), prev - 15);
            prev = cy;
            line(ax - 3, g.y(q[0]), ax + 3, g.y(q[0]), stroke(ink((int) q[1]), 1.2, null, 1));
            text(ax + 7, cy - 4, null, ink((int) q[1]), esc(Money.compact(q[0])), "tx", pf.font());
        }
    }

    /** One row of the formula: its value, its condition and its status. */
    private record Row(String value, String cond, Zone zone) {
    }

    /** Roughly how wide a formula text is drawn (for the amount after it). */
    private double plainWidth(String t) {
        return t.replace("_min", "mi").replace("_max", "ma").length() * pf.font() * 0.56;
    }

    /** One row per status, in the order of x; rows that cannot happen (τ = 0, T_min = T_max) are left out. */
    private List<Row> formulaRows() {
        boolean t = s.singleThreshold();
        String tn = t ? "T" : "T_min", tx = t ? "T" : "T_max";
        boolean n0 = s.nominalMin() == 0, m0 = s.nominalMax() == 0;
        String inEdge = n0 ? "x < " + tn : "x ≤ " + tn + " − τ_min";            // where a sweep In is needed
        List<Row> rows = new ArrayList<>();
        if (s.sweptInMax() == 0) {
            rows.add(new Row("∄ ⏚", inEdge, Zone.SWEPT_IN_ZERO));
        } else {
            rows.add(new Row("A", "x < " + tn + " − A", Zone.SWEPT_IN_PARTIAL));
            rows.add(new Row(tn + " − x", tn + " − A ≤ " + inEdge, Zone.SWEPT_IN_FULL));
        }
        if (!n0) rows.add(new Row("0", tn + " − τ_min < x < " + tn, Zone.IN_NOMINAL_MIN));
        rows.add(new Row("0", t ? "x = T" : tn + " ≤ x ≤ " + tx, Zone.IN_RANGE));
        if (!m0) rows.add(new Row("0", tx + " < x < " + tx + " + τ_max", Zone.IN_NOMINAL_MAX));
        rows.add(new Row("x − " + tx, (m0 ? tx + " < x" : tx + " + τ_max ≤ x") + " ≤ " + tx + " + L", Zone.SWEPT_OUT));
        rows.add(new Row("∄ ⏚", "x > " + tx + " + L", Zone.SWEPT_OUT_OVERFLOW));
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
                "body", "<tspan class=\"tx\" style=\"font-size:13px;fill:var(--ink)\">" + tex(items.get(i)[0]) + "</tspan>"
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

    /**
     * Who owns the edge between two slices: the zone of the balance exactly at the boundary (its interval is closed
     * there). The owner's edge is an <b>extremal edge</b>: its surface turns away from the viewer, so the shading
     * darkens towards the edge along it, with a thin highlight ridge just inside; the neighbour's surface is simply
     * cut there, in a faint shadow (Ghose and Palmer, 2015: the side with the extremal edge is seen as the nearer one,
     * the one the border belongs to). A target balance [T] owns a point of its own: a narrow ridge, shaded on both
     * sides. Built from thin rectangles, so the template needs no gradients.
     */
    private void edges(Object[][] bands) {
        java.util.Set<Long> seen = new java.util.TreeSet<>();
        for (int i = 0; i + 1 < bands.length; i++) seen.add(Math.round((double) bands[i][1]));
        for (long v : seen) {
            if (v <= g.xMin() || v >= g.xMax()) continue;
            boolean visible = false;
            for (Layout.Seg sx : g.xs) visible |= v > sx.d0() && v < sx.d1();
            if (!visible) continue;
            Zone own = s.at(v).zone(), zl = s.at(v - 1).zone(), zr = s.at(v + 1).zone();
            if (zl == zr && own == zl) continue;
            double x = g.x(v);
            if (own == zl) extremal(x, -1, own);
            else if (own == zr) extremal(x, 1, own);
            else {                                      // a point of its own: [T]
                extremal(x - 2.5, 1, own);
                extremal(x + 2.5, -1, own);
                cur.add(shape("rect", "x", r(x - 2.5), "y", r(g.plotTop), "width", "5", "height", r(g.plotBottom - g.plotTop),
                    "style", "fill:" + own.fill() + ";fill-opacity:.10"));
            }
        }
    }

    /** The owner's extremal edge at {@code x}, the owner on side {@code d} (-1: left, 1: right). */
    private void extremal(double x, int d, Zone owner) {
        String y = r(g.plotTop), h = r(g.plotBottom - g.plotTop);
        double step = 1.5;
        for (int i = 0; i < 6; i++) {                   // the surface turning away: darker towards the edge
            double o = 0.28 * Math.pow(1 - i / 6.0, 1.6);
            double qx = d < 0 ? x - (i + 1) * step : x + i * step;
            cur.add(shape("rect", "x", r(qx), "y", y, "width", r(step), "height", h,
                "style", "fill:" + owner.fill() + ";fill-opacity:" + String.format(java.util.Locale.ROOT, "%.3f", o)));
        }
        double hx = x + d * 3.5;                        // the highlight ridge
        cur.add(shape("line", "x1", r(hx), "y1", y, "x2", r(hx), "y2", r(g.plotBottom),
            "style", "stroke:#fff;stroke-opacity:.55;stroke-width:1"));
        for (int i = 0; i < 4; i++) {                   // the neighbour, cut, in a faint shadow
            double o = 0.09 * (1 - i / 4.0);
            double qx = d < 0 ? x + i * step : x - (i + 1) * step;
            cur.add(shape("rect", "x", r(qx), "y", y, "width", r(step), "height", h,
                "style", "fill:#000;fill-opacity:" + String.format(java.util.Locale.ROOT, "%.3f", o)));
        }
    }

    private void band(double qa, double w, Zone z) {
        String x = r(qa), y = r(g.plotTop), wd = r(w), ht = r(g.plotBottom - g.plotTop);
        cur.add(shape("rect", "x", x, "y", y, "width", wd, "height", ht, "style", "fill:" + z.fill() + ";fill-opacity:" + z.opacity()));
        if (z.terminal()) cur.add(shape("rect", "x", x, "y", y, "width", wd, "height", ht, "fill", "url(#hatch)"));
    }

    private static final Pattern SUB = Pattern.compile("([A-Za-zα-ω]+)(?:_([a-z]+))?");

    /**
     * "T_min − τ_min" → as TeX sets it: math-italic letters (class "mi"), upright subscripts a little below the
     * baseline, upright digits and operators (escaped markup).
     */
    static String tex(String t) {
        StringBuilder o = new StringBuilder();
        Matcher m = SUB.matcher(t);
        int i = 0;
        boolean shifted = false;
        String up = "-0.2em", dn = "0.2em";
        while (m.find()) {
            String pre = esc(t.substring(i, m.start()));
            if (!pre.isEmpty()) {
                o.append("<tspan dy=\"").append(shifted ? up : "0").append("\">").append(pre).append("</tspan>");
                shifted = false;
            }
            o.append("<tspan class=\"mi\" dy=\"").append(shifted ? up : "0").append("\">").append(m.group(1)).append("</tspan>");
            shifted = m.group(2) != null;
            if (shifted) o.append("<tspan dy=\"").append(dn).append("\" style=\"font-size:0.7em\">").append(m.group(2)).append("</tspan>");
            i = m.end();
        }
        String post = esc(t.substring(i));
        if (!post.isEmpty()) o.append("<tspan dy=\"").append(shifted ? up : "0").append("\">").append(post).append("</tspan>");
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
