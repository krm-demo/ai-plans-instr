package org.krmdemo.plansinstr.equilib;

/**
 * The parameters of a cash sweep, in whole dollars, and the swept amount <i>y</i> for any current balance <i>x</i>
 * of the DDA (the current account).
 *
 * <table class="striped">
 * <caption>The swept amount and the sweep status ({@link Zone})</caption>
 * <thead>
 * <tr><th scope="col"><i>y</i></th><th scope="col">when</th><th scope="col">status</th></tr>
 * </thead>
 * <tbody>
 * <tr><td><i>A</i></td><td><i>x</i> &lt; <i>T</i><sub>min</sub> − <i>A</i></td><td>Swept <b>In</b> Partial (<i>A</i> &gt; 0)</td></tr>
 * <tr><td><i>T</i><sub>min</sub> − <i>x</i></td><td><i>T</i><sub>min</sub> − <i>A</i> ≤ <i>x</i> ≤ <i>T</i><sub>min</sub> − <i>τ</i><sub>min</sub></td><td>Swept <b>In</b> Full (<i>A</i> &gt; 0)</td></tr>
 * <tr><td>∄ ⏚</td><td><i>x</i> ≤ <i>T</i><sub>min</sub> − <i>τ</i><sub>min</sub></td><td>Swept <b>In</b> Zero (<i>A</i> = 0)</td></tr>
 * <tr><td>0</td><td><i>T</i><sub>min</sub> − <i>τ</i><sub>min</sub> &lt; <i>x</i> &lt; <i>T</i><sub>min</sub></td><td><b>In</b> Nominal Min</td></tr>
 * <tr><td>0</td><td><i>T</i><sub>min</sub> ≤ <i>x</i> ≤ <i>T</i><sub>max</sub></td><td><b>In</b> Range</td></tr>
 * <tr><td>0</td><td><i>T</i><sub>max</sub> &lt; <i>x</i> &lt; <i>T</i><sub>max</sub> + <i>τ</i><sub>max</sub></td><td><b>In</b> Nominal Max</td></tr>
 * <tr><td><i>x</i> − <i>T</i><sub>max</sub></td><td><i>T</i><sub>max</sub> + <i>τ</i><sub>max</sub> ≤ <i>x</i> ≤ <i>T</i><sub>max</sub> + <i>L</i></td><td>Swept <b>Out</b></td></tr>
 * <tr><td>∄ ⏚</td><td><i>x</i> &gt; <i>T</i><sub>max</sub> + <i>L</i></td><td>Swept <b>Out</b> Overflow</td></tr>
 * </tbody>
 * </table>
 *
 * <p>The nominals are the smallest transfers worth making: a sweep happens only if it moves at least <i>τ</i> (and
 * more than nothing). So <i>y</i> jumps at <i>T</i><sub>min</sub> − <i>τ</i><sub>min</sub> and at
 * <i>T</i><sub>max</sub> + <i>τ</i><sub>max</sub>, and the point of the jump belongs to the sweep.
 *
 * <p>The graph, the demo page and the tests name the components by their symbols (in parentheses below); the
 * threshold is written as over the graph: <code>−$8k [$40k ; $60k] +$10k</code> (<code>[$50k]</code> for a target
 * balance, zero nominals left out).
 *
 * @param thresholdMin (<i>T</i><sub>min</sub>) the lowest balance the DDA should keep
 * @param thresholdMax (<i>T</i><sub>max</sub>) the highest balance the DDA should keep; <i>T</i><sub>min</sub> ≤
 *                     <i>T</i><sub>max</sub>, and when they are equal, just <i>T</i>: the target balance
 * @param nominalMin   (<i>τ</i><sub>min</sub>) the smallest sweep <b>In</b> worth making
 * @param nominalMax   (<i>τ</i><sub>max</sub>) the smallest sweep <b>Out</b> worth making
 * @param sweptInMax   (<i>A</i>, available) the balance of the investment account: the most one sweep <b>In</b> can
 *                     bring back; 0, or at least <i>τ</i><sub>min</sub> (a smaller <i>A</i> could never move a whole
 *                     nominal)
 * @param sweptOutMax  (<i>L</i>, limit) the largest single payment into the investment account: above 0 and at
 *                     least <i>τ</i><sub>max</sub>
 */
public record Sweep(long thresholdMin, long thresholdMax, long nominalMin, long nominalMax,
                    long sweptInMax, long sweptOutMax) {

    /** The demo of the README with L in millions (broken axes): thresholds in thousands. */
    public static final Sweep DEMO = new Sweep(40_000, 60_000, 8_000, 10_000, 25_000, 2_000_000);

    public Sweep {
        if (thresholdMin < 0 || nominalMin < 0 || nominalMax < 0 || sweptInMax < 0) {
            throw new IllegalArgumentException("amounts cannot be negative");
        }
        if (thresholdMin > thresholdMax) {
            throw new IllegalArgumentException("thresholdMin must not be above thresholdMax");
        }
        if (sweptInMax != 0 && sweptInMax < nominalMin) {
            throw new IllegalArgumentException("sweptInMax (A) must be 0 or at least nominalMin");
        }
        if (sweptOutMax <= 0 || sweptOutMax < nominalMax) {
            throw new IllegalArgumentException("sweptOutMax (L) must be above 0 and at least nominalMax");
        }
    }

    /** The swept amount at one current balance: its zone and the amount, or null where it does not exist (∄). */
    public record Point(long balance, Zone zone, Long amount) {
    }

    public Point at(long x) {
        long need = thresholdMin - x;
        if (need > 0 && need >= nominalMin) {
            if (sweptInMax == 0) return new Point(x, Zone.SWEPT_IN_ZERO, null);
            if (need > sweptInMax) return new Point(x, Zone.SWEPT_IN_PARTIAL, sweptInMax);
            return new Point(x, Zone.SWEPT_IN_FULL, need);
        }
        long excess = x - thresholdMax;
        if (excess > 0 && excess >= nominalMax) {
            return excess > sweptOutMax ? new Point(x, Zone.SWEPT_OUT_OVERFLOW, null) : new Point(x, Zone.SWEPT_OUT, excess);
        }
        if (x < thresholdMin) return new Point(x, Zone.IN_NOMINAL_MIN, 0L);
        if (x <= thresholdMax) return new Point(x, Zone.IN_RANGE, 0L);
        return new Point(x, Zone.IN_NOMINAL_MAX, 0L);
    }

    /** T_min = T_max: the labels and the formula say just T. */
    public boolean singleThreshold() {
        return thresholdMin == thresholdMax;
    }

    /**
     * Rounds an amount to two significant digits (37 512 → 38 000, 2 340 000 → 2 300 000). That keeps the number of
     * possible values of every parameter finite: at most 90 per power of ten.
     */
    public static long snap(long v) {
        if (v <= 0) return 0;
        long step = 1;
        while (v / step >= 100) step *= 10;
        return Math.round((double) v / step) * step;
    }

    /** The same parameters, each rounded by {@link #snap(long)}; then made consistent again where rounding broke it. */
    public Sweep snapped() {
        long tMin = snap(thresholdMin), tMax = Math.max(tMin, snap(thresholdMax));
        long nMin = snap(nominalMin), nMax = snap(nominalMax);
        long a = sweptInMax == 0 ? 0 : Math.max(snap(sweptInMax), nMin);
        long l = Math.max(snap(sweptOutMax), Math.max(nMax, 1));
        return new Sweep(tMin, tMax, nMin, nMax, a, l);
    }
}
