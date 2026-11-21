package org.krmdemo.plansinstr.equilib;

/**
 * Where a current balance falls, from the far left of the x axis to the far right.
 * <p>
 * Two zones are a <b>terminal failure</b> (⏚, the same mark as on the AuditStatus state charts): the swept amount
 * does not exist there (∄), the plan cannot restore the balance with one sweep.
 */
public enum Zone {

    /** A = 0: a sweep in is needed, but the investment account is empty. Terminal failure. */
    SWEPT_IN_ZERO("Swept in zero", "#E24B4A", "#E24B4A", 0.10, true),
    /** All of A comes back, and the balance is still below T_min. */
    SWEPT_IN_PARTIAL("Swept in partial", "#A8692A", "#8A5418", 0.17, false),
    /** Exactly the missing amount comes back: y = T_min − x. */
    SWEPT_IN_FULL("Swept in full", "#EF9F27", "#BA7517", 0.13, false),
    /** Below T_min, but by less than the nominal τ_min: not worth a transfer. */
    IN_NOMINAL_MIN("In nominal min", Zone.BLUE, Zone.BLUE, 0.06, false),
    /** Between the thresholds. */
    IN_RANGE("In range", Zone.BLUE, Zone.BLUE, 0.15, false),
    /** Above T_max, but by less than the nominal τ_max. */
    IN_NOMINAL_MAX("In nominal max", Zone.BLUE, Zone.BLUE, 0.06, false),
    /** The excess goes to the investment account: y = x − T_max. */
    SWEPT_OUT("Swept out", "#1D9E75", "#1D9E75", 0.13, false),
    /** The excess is above L, the largest single payment. Terminal failure. */
    OVERFLOW("Overflow", "#E24B4A", "#E24B4A", 0.10, true);

    private static final String BLUE = "#378ADD";

    private final String title;
    private final String fill;
    private final String ink;
    private final double opacity;
    private final boolean terminal;

    Zone(String title, String fill, String ink, double opacity, boolean terminal) {
        this.title = title;
        this.fill = fill;
        this.ink = ink;
        this.opacity = opacity;
        this.terminal = terminal;
    }

    /** "Swept in full", "Overflow"... — the same titles as on the site and in the README. */
    public String title() { return title; }

    /** Colour of the zone's band (drawn translucent). */
    public String fill() { return fill; }

    /** Colour of the zone's line, label and watermark. */
    public String ink() { return ink; }

    /** Opacity of the band. */
    public double opacity() { return opacity; }

    /** True when the swept amount does not exist here: a terminal failure (⏚). */
    public boolean terminal() { return terminal; }

    /** One of the three zero zones between the sweeps. */
    public boolean zero() { return this == IN_NOMINAL_MIN || this == IN_RANGE || this == IN_NOMINAL_MAX; }
}
