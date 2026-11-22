package org.krmdemo.plansinstr.equilib;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The cases of the demo tests: a small set that still shows everything the demo page can show.
 *
 * <p>How they are chosen, in the terms of testing theory:
 * <ul>
 * <li><b>Category partition</b> (Ostrand and Balcer, 1988): each parameter falls into a few categories —
 *     thresholds apart or one target balance; τ<sub>min</sub> zero or not, with A zero, equal to τ<sub>min</sub>
 *     or larger; τ<sub>max</sub> zero or not, with L equal to τ<sub>max</sub> or larger; L huge (broken axes).</li>
 * <li><b>Pairwise combination</b>: every pair of categories appears in some set of parameters (about 20 sets
 *     instead of all 60), sorted into six groups.</li>
 * <li><b>Equivalence classes and boundary values</b> (Myers, 1979): for each set, one balance inside every status
 *     and one exactly on every boundary between statuses.</li>
 * <li><b>Output uniqueness</b> (Alshahwan and Harman, 2014): within a picture, a balance inside a status is kept only
 *     if it shows that status between neighbours not shown yet: Swept Out at $75k shows nothing that $80k did not.</li>
 * </ul>
 * Within a set the balances go up, as on the graph.
 */
final class DemoCases {

    /** A group of sets of parameters that share a theme. */
    record Group(String title, String about, List<Frame> frames) {
    }

    /** One set of parameters and its balances, in increasing order. */
    record Frame(String title, Sweep sweep, List<Probe> probes) {
    }

    /** A balance to show: its symbol when it is a boundary ("T_min − τ_min"), else null. */
    record Probe(long balance, String symbol) {
    }

    private DemoCases() {
    }

    private static final String[][] GROUPS = {
        {"Two thresholds with nominals", "The usual picture: every status, both jumps."},
        {"No nominals on one side or both", "τ = 0: no jump on that side, no In Nominal status; the threshold itself is In Range."},
        {"Target balance [T]", "T_min = T_max = T: In Range is the single point x = T."},
        {"Swept In Zero: A = 0", "The investment account is empty: the whole left side is a terminal failure ⏚."},
        {"Coinciding ends: A = τ_min or L = τ_max", "Swept In Full or Swept Out shrinks to a single point."},
        {"Huge L: broken axes", "L in millions: one scale, the axes cut along the Swept Out line."},
    };

    private static int groupOf(Sweep s) {
        if (s.sweptOutMax() > 100_000) return 5;
        if (s.sweptInMax() == 0) return 3;
        if ((s.nominalMin() > 0 && s.sweptInMax() == s.nominalMin()) || (s.nominalMax() > 0 && s.sweptOutMax() == s.nominalMax())) return 4;
        if (s.singleThreshold()) return 2;
        if (s.nominalMin() == 0 || s.nominalMax() == 0) return 1;
        return 0;
    }

    static List<Group> groups() {
        long tMin = 40_000, tMax = 60_000, t = 50_000;
        long[][] minSide = {{0, 0}, {0, 25_000}, {8_000, 0}, {8_000, 8_000}, {8_000, 25_000}};   // {τ_min, A}
        long[][] maxSide = {{0, 30_000}, {10_000, 10_000}, {10_000, 30_000}};                     // {τ_max, L}
        List<Sweep> sweeps = new ArrayList<>();
        sweeps.add(new Sweep(tMin, tMax, 8_000, 10_000, 25_000, 30_000));       // the usual picture first
        for (int a = 0; a < minSide.length; a++) {                               // pairwise: thresholds alternate
            for (int l = 0; l < maxSide.length; l++) {
                boolean target = (a + l) % 2 == 1;
                sweeps.add(new Sweep(target ? t : tMin, target ? t : tMax, minSide[a][0], maxSide[l][0], minSide[a][1], maxSide[l][1]));
            }
        }
        sweeps.add(new Sweep(tMin, tMax, 0, 0, 25_000, 30_000));               // no nominals at all
        sweeps.add(new Sweep(t, t, 5_000, 5_000, 20_000, 25_000));              // the target with nominals, as in the README
        sweeps.add(new Sweep(tMin, tMax, 8_000, 10_000, 25_000, 2_000_000));     // huge L: broken axes
        sweeps.add(new Sweep(t, t, 0, 0, 0, 2_000_000));

        List<List<Frame>> byGroup = new ArrayList<>();
        for (int i = 0; i < GROUPS.length; i++) byGroup.add(new ArrayList<>());
        Set<String> seenSweeps = new HashSet<>();
        for (Sweep s : sweeps) {
            if (!seenSweeps.add(s.toString())) continue;
            byGroup.get(groupOf(s)).add(new Frame(title(s), s, probes(s)));
        }
        List<Group> groups = new ArrayList<>();
        for (int i = 0; i < GROUPS.length; i++) groups.add(new Group(GROUPS[i][0], GROUPS[i][1], byGroup.get(i)));
        return groups;
    }

    /**
     * One balance inside every status and one on every boundary; a balance inside is kept only if it shows a status
     * between neighbours not shown yet (output uniqueness within the picture).
     */
    static List<Probe> probes(Sweep s) {
        TreeMap<Long, String> bounds = boundaries(s);
        List<Long> b = new ArrayList<>(bounds.keySet());
        List<Long> inner = new ArrayList<>();
        inner.add(b.get(0) - 5_000);
        for (int i = 0; i + 1 < b.size(); i++) if (b.get(i + 1) - b.get(i) > 1) inner.add((b.get(i) + b.get(i + 1)) / 2);
        inner.add(b.get(b.size() - 1) + 5_000);
        Set<String> seen = new HashSet<>();
        TreeMap<Long, Probe> all = new TreeMap<>();
        for (long x : inner) {
            Zone z = s.at(x).zone();
            if (seen.add(s.at(left(s, x, z)).zone() + "<" + z + ">" + s.at(right(s, x, z)).zone())) all.put(x, new Probe(x, null));
        }
        bounds.forEach((x, sym) -> all.put(x, new Probe(x, sym)));
        return new ArrayList<>(all.values());
    }

    /** The boundaries between statuses, with their symbols as on the graph. */
    static TreeMap<Long, String> boundaries(Sweep s) {
        TreeMap<Long, String> b = new TreeMap<>();
        boolean target = s.singleThreshold();
        String tl = target ? "T" : "T_min", tr = target ? "T" : "T_max";
        if (s.sweptInMax() > s.nominalMin()) b.put(s.thresholdMin() - s.sweptInMax(), tl + " − A");
        if (s.nominalMin() > 0) b.putIfAbsent(s.thresholdMin() - s.nominalMin(), tl + " − τ_min");
        b.putIfAbsent(s.thresholdMin(), tl);
        b.putIfAbsent(s.thresholdMax(), tr);
        if (s.nominalMax() > 0) b.putIfAbsent(s.thresholdMax() + s.nominalMax(), tr + " + τ_max");
        b.putIfAbsent(s.thresholdMax() + s.sweptOutMax(), tr + " + L");
        return b;
    }

    /** The balance just left of the status of x (to tell its neighbours). */
    private static long left(Sweep s, long x, Zone z) {
        long lo = x;
        while (lo > x - 3_000_000 && s.at(lo).zone() == z) lo -= Math.max(1, Math.abs(lo - x) / 2 + 1);
        long hi = x;
        while (hi - lo > 1) {
            long m = (lo + hi) / 2;
            if (s.at(m).zone() == z) hi = m; else lo = m;
        }
        return lo;
    }

    /** The balance just right of the status of x. */
    private static long right(Sweep s, long x, Zone z) {
        long hi = x;
        while (hi < x + 3_000_000 && s.at(hi).zone() == z) hi += Math.max(1, Math.abs(hi - x) / 2 + 1);
        long lo = x;
        while (hi - lo > 1) {
            long m = (lo + hi) / 2;
            if (s.at(m).zone() == z) lo = m; else hi = m;
        }
        return hi;
    }

    /** As over the graph of the demo page: the threshold, then A and L. */
    static String title(Sweep s) {
        return SvgRenderer.thresholdLabel(s) + " " + SvgRenderer.thresholdClosed(s) + "  ·  A " + Money.compact(s.sweptInMax())
            + "  ·  L " + Money.compact(s.sweptOutMax());
    }

    /** "x = $32k = Tₘᵢₙ − τₘᵢₙ  →  Swept In Full  $8k". */
    static String name(Sweep s, Probe p) {
        Sweep.Point pt = s.at(p.balance());
        String sym = p.symbol() == null ? "" : " = " + p.symbol().replace("_min", "ₘᵢₙ").replace("_max", "ₘₐₓ");
        String amount = pt.amount() == null ? "∄" : money(pt.amount());
        return "x = " + money(p.balance()) + sym + "  →  " + pt.zone().title() + (pt.zone().terminal() ? " ⏚" : "") + "  " + amount;
    }

    /** The file of the picture a demo test publishes for a balance (the gallery of the demo page finds it by name). */
    static String pictureName(Frame f, Probe p) {
        return "sweep-" + Integer.toHexString(f.title().hashCode()) + "-x-" + p.balance() + ".svg";
    }

    /** Compact ("$32k", "$2.1M"), unless that hides dollars that matter ("$2,065,000"). */
    static String money(long v) {
        String c = Money.compact(v);
        double back = Double.parseDouble(c.replaceAll("[^0-9.]", "")) * (c.endsWith("M") ? 1e6 : c.endsWith("k") ? 1e3 : 1);
        return Math.abs(back - Math.abs(v)) < 0.5 ? c : Money.full(v);
    }

    /**
     * The statuses written once more, straight from the rules of the README (not from {@link Sweep#at}): the oracle
     * the demo tests check the library against.
     */
    static Sweep.Point expected(Sweep s, long x) {
        if (x < s.thresholdMin()) {
            long need = s.thresholdMin() - x;
            if (need < s.nominalMin()) return new Sweep.Point(x, Zone.IN_NOMINAL_MIN, 0L);
            if (s.sweptInMax() == 0) return new Sweep.Point(x, Zone.SWEPT_IN_ZERO, null);
            return need > s.sweptInMax() ? new Sweep.Point(x, Zone.SWEPT_IN_PARTIAL, s.sweptInMax())
                : new Sweep.Point(x, Zone.SWEPT_IN_FULL, need);
        }
        if (x <= s.thresholdMax()) return new Sweep.Point(x, Zone.IN_RANGE, 0L);
        long excess = x - s.thresholdMax();
        if (excess < s.nominalMax()) return new Sweep.Point(x, Zone.IN_NOMINAL_MAX, 0L);
        return excess > s.sweptOutMax() ? new Sweep.Point(x, Zone.SWEPT_OUT_OVERFLOW, null) : new Sweep.Point(x, Zone.SWEPT_OUT, excess);
    }

    /** Prints the cases (and checks them against the oracle) without JUnit. */
    public static void main(String[] args) {
        int n = 0, sets = 0;
        Map<Zone, Integer> perZone = new LinkedHashMap<>();
        for (Group g : groups()) {
            System.out.println("## " + g.title());
            for (Frame f : g.frames()) {
                sets++;
                System.out.println("  " + f.title());
                for (Probe p : f.probes()) {
                    n++;
                    Sweep.Point a = f.sweep().at(p.balance()), e = expected(f.sweep(), p.balance());
                    perZone.merge(a.zone(), 1, Integer::sum);
                    System.out.println("    " + name(f.sweep(), p) + (a.equals(e) ? "" : "   !!! expected " + e));
                }
            }
        }
        System.out.println(n + " cases in " + sets + " sets; per status " + perZone);
    }
}
