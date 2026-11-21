package org.krmdemo.plansinstr.equilib;

/**
 * The parameters of a cash sweep, in whole dollars, and the swept amount for any current balance.
 *
 * <pre>
 *   y = A               x &lt; T_min − A                     Swept in partial   (A &gt; 0)
 *   y = T_min − x       T_min − A ≤ x ≤ T_min − τ_min     Swept in full      (A &gt; 0)
 *   ∄ (⏚)               x ≤ T_min − τ_min                 Swept in zero      (A = 0)
 *   y = 0               T_min − τ_min &lt; x &lt; T_max + τ_max  In nominal min / In range / In nominal max
 *   y = x − T_max       T_max + τ_max ≤ x ≤ T_max + L     Swept out
 *   ∄ (⏚)               x &gt; T_max + L                     Overflow
 * </pre>
 *
 * The nominals are the smallest transfers worth making: a sweep happens only if it moves at least τ (and more
 * than nothing). So y jumps at T_min − τ_min and at T_max + τ_max, and the point of the jump belongs to the sweep.
 *
 * @param thresholdMin T_min, the lowest balance the DDA should keep
 * @param thresholdMax T_max, the highest balance the DDA should keep (T_min ≤ T_max; just T when equal)
 * @param nominalMin   τ_min, the smallest sweep in worth making
 * @param nominalMax   τ_max, the smallest sweep out worth making
 * @param sweptInMax   A, available: the balance of the investment account, the most one sweep in can bring back;
 *                     0, or at least τ_min (a smaller A could never move a whole nominal)
 * @param sweptOutMax  L, limit: the largest single payment into the investment account (at least τ_max, above 0)
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
