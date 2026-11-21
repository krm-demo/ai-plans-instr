package org.krmdemo.plansinstr.equilib;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the graph of one {@link Sweep} goes: the data ranges of the axes, fitted to the parameters, and their pixels.
 * <p>
 * One scale for both axes, so every slope is 45° (0° and 90° are the only other angles). When L is far bigger than
 * the thresholds, the axes are <b>broken</b>: the "pan" (thresholds, nominals, A) stays bottom left, the end of the
 * "handle" (Swept out up to T_max + L) goes top right, and the cut between them runs along the Swept out line.
 */
final class Layout {

    /** One piece of an axis: data range [d0, d1] drawn on pixels [q0, q1]. */
    record Seg(double d0, double d1, double q0, double q1) {
        double map(double v) { return q0 + (v - d0) * (q1 - q0) / (d1 - d0); }
        double unmap(double q) { return d0 + (q - q0) * (d1 - d0) / (q1 - q0); }
        boolean has(double v) { return v >= d0 && v <= d1; }
        double qLo() { return Math.min(q0, q1); }
        double qHi() { return Math.max(q0, q1); }
    }

    /** A size of the picture. */
    record Profile(String name, int width, int left, int right, int top, int font, int rowGap, int wmMin, int pri) {
        static final Profile L = new Profile("L", 760, 22, 736, 82, 15, 19, 22, 3);
        static final Profile M = new Profile("M", 560, 18, 540, 78, 14, 18, 19, 2);
        static final Profile S = new Profile("S", 380, 14, 366, 92, 13, 17, 17, 1);

        static Profile of(String name) {
            return switch (name == null ? "L" : name.toUpperCase()) {
                case "M" -> M;
                case "S" -> S;
                default -> L;
            };
        }
    }

    static final double GAP = 10;

    final Profile pf;
    final List<Seg> xs = new ArrayList<>();
    final List<Seg> ys = new ArrayList<>();
    final double plotTop, plotBottom;
    final boolean broken;
    /** Step of the balances the frames are sampled at, per x piece. */
    final double[] steps;

    /**
     * Broken axes keep ONE scale everywhere. The pan ends at x1 = T_max + y1, where y1 is the top of its y range,
     * so the Swept out line leaves it exactly through its top right corner. The same amount Δ is cut out of both
     * axes, and the handle panel starts at (x1 + Δ, y1 + Δ) = (T_max + L − tail, L − tail): back on the same line,
     * which goes on across the cut at 45° as if nothing happened.
     */
    Layout(Sweep s, Profile pf) {
        this.pf = pf;
        double tMin = s.thresholdMin(), tMax = s.thresholdMax(), nMin = s.nominalMin(), nMax = s.nominalMax();
        double a = s.sweptInMax(), l = s.sweptOutMax(), end = tMax + l;

        double coreLo = tMin - Math.max(a, nMin), coreHi = tMax + nMax;
        double span = Math.max(coreHi - coreLo, Math.max(tMax * 0.2, 10));
        double xLo = coreLo - 0.3 * span, panHi = coreHi + 0.45 * span;
        double yNeed = Math.max(Math.max(Math.max(a, nMin), nMax) * 1.25, 0.15 * span);

        // Broken: the pan up to the corner (x1, y1) on the line y = x − T_max, then the handle near (T_max + L, L).
        double y1 = Math.max(yNeed, panHi - tMax), x1 = tMax + y1;
        double tail = 0.3 * (x1 - xLo), head = 0.12 * tail, margin = 0.35 * tail;
        broken = end - tail > x1 + 0.5 * tail;

        double yLo;
        if (broken) {
            yLo = -Math.max(0.12 * y1, 0.05 * span);
            double k = (pf.right() - pf.left() - GAP) / ((x1 - xLo) + tail + margin);   // pixels per dollar, both axes
            double wl = (x1 - xLo) * k, hl = (y1 - yLo) * k, hu = (tail + head) * k;
            plotTop = pf.top();
            plotBottom = plotTop + hu + GAP + hl;
            xs.add(new Seg(xLo, x1, pf.left(), pf.left() + wl));
            xs.add(new Seg(end - tail, end + margin, pf.left() + wl + GAP, pf.right()));
            ys.add(new Seg(yLo, y1, plotBottom, plotBottom - hl));
            ys.add(new Seg(l - tail, l + head, plotTop + hu, plotTop));
            steps = new double[] {niceStep(span), niceStep(tail + margin)};
        } else {
            double xHi = Math.max(panHi, end + 0.25 * span);
            double yTop = Math.max(yNeed, l * 1.15);
            yLo = -Math.max(0.12 * yTop, 0.05 * span);
            double k = (pf.right() - pf.left()) / (xHi - xLo);
            double hl = (yTop - yLo) * k, minH = 0.3 * pf.width();
            if (hl < minH) { yTop = yLo + minH / k; hl = minH; }
            plotTop = pf.top();
            plotBottom = plotTop + hl;
            xs.add(new Seg(xLo, xHi, pf.left(), pf.right()));
            ys.add(new Seg(yLo, yTop, plotBottom, plotTop));
            steps = new double[] {niceStep(xHi - xLo)};
        }
    }

    /** About a hundredth of a range, rounded to a power of ten. */
    static double niceStep(double range) {
        return Math.pow(10, Math.floor(Math.log10(Math.max(range, 1))) - 2);
    }

    double x(double v) { return pick(xs, v).map(v); }
    double y(double v) { return pick(ys, v).map(v); }
    boolean visX(double v) { return xs.stream().anyMatch(sg -> sg.has(v)); }
    boolean visY(double v) { return ys.stream().anyMatch(sg -> sg.has(v)); }
    double xMin() { return xs.get(0).d0(); }
    double xMax() { return xs.get(xs.size() - 1).d1(); }
    double yMin() { return ys.get(0).d0(); }
    double yMax() { return ys.get(ys.size() - 1).d1(); }
    /** The pixel of the y axis: at x = 0 when that is in view, else at the left edge of the plot. */
    double axisX() { return xs.get(0).has(0) ? x(0) : pf.left(); }

    private static Seg pick(List<Seg> axis, double v) {
        Seg sg = axis.get(0);
        for (Seg s : axis) if (v >= s.d0()) sg = s;
        return sg;
    }

    /** Liang–Barsky: the part of a segment inside the box, or null. */
    static double[] clip(double x1, double y1, double x2, double y2, double a, double b, double c, double d) {
        double t0 = 0, t1 = 1, dx = x2 - x1, dy = y2 - y1;
        double[] p = {-dx, dx, -dy, dy}, q = {x1 - a, b - x1, y1 - c, d - y1};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return null;
                continue;
            }
            double r = q[i] / p[i];
            if (p[i] < 0) {
                if (r > t1) return null;
                if (r > t0) t0 = r;
            } else {
                if (r < t0) return null;
                if (r < t1) t1 = r;
            }
        }
        if (t1 - t0 < 1e-9) return null;
        return new double[] {x1 + t0 * dx, y1 + t0 * dy, x1 + t1 * dx, y1 + t1 * dy};
    }
}
