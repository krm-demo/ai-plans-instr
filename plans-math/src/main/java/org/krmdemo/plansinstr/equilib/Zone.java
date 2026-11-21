package org.krmdemo.plansinstr.equilib;

/**
 * The sweep status of a current balance, from the far left of the x axis to the far right.
 * <p>
 * "In" and "Out" are the key words of every title and are always capitalised (on the pictures they are also a bit
 * bolder). Two statuses are a <b>terminal failure</b> (⏚, the mark of the AuditStatus state charts): the swept amount
 * does not exist there (∄), one sweep cannot restore the balance.
 * <p>
 * {@link #description()} is the one and only explanation of each status: the list under the picture in the README
 * (and, word for word, on the site) is checked against it by a test.
 */
public enum Zone {

    SWEPT_IN_ZERO("Swept In Zero", "#E24B4A", "#E24B4A", 0.07, true,
        "a sweep In is needed, but the investment account is empty (A = 0): terminal failure ⏚. "
            + "Like a yellow card: on the next round the plan is cancelled or paused."),
    SWEPT_IN_PARTIAL("Swept In Partial", "#A8692A", "#8A5418", 0.11, false,
        "far below T_min: all of A comes back and the balance is still short; the investment account is emptied."),
    SWEPT_IN_FULL("Swept In Full", "#EF9F27", "#BA7517", 0.09, false,
        "exactly the missing T_min − x comes back from the investment account, at least τ_min "
            + "(at T_min − A that is all of A: emptied to the cent)."),
    IN_NOMINAL_MIN("In Nominal Min", Zone.BLUE, Zone.BLUE, 0.04, false,
        "below T_min by less than τ_min: not worth a transfer."),
    IN_RANGE("In Range", Zone.BLUE, Zone.BLUE, 0.10, false,
        "between T_min and T_max (or right at the target T): nothing to transfer."),
    IN_NOMINAL_MAX("In Nominal Max", Zone.BLUE, Zone.BLUE, 0.04, false,
        "above T_max by less than τ_max: not worth a transfer."),
    SWEPT_OUT("Swept Out", "#1D9E75", "#1D9E75", 0.09, false,
        "the excess x − T_max, at least τ_max, goes from the DDA to the investment account; exactly L is still allowed."),
    SWEPT_OUT_OVERFLOW("Swept Out Overflow", "#E24B4A", "#E24B4A", 0.07, true,
        "the excess is above L, the largest single payment the investment account accepts: terminal failure ⏚.");

    private static final String BLUE = "#378ADD";

    private final String title;
    private final String fill;
    private final String ink;
    private final double opacity;
    private final boolean terminal;
    private final String description;

    Zone(String title, String fill, String ink, double opacity, boolean terminal, String description) {
        this.title = title;
        this.fill = fill;
        this.ink = ink;
        this.opacity = opacity;
        this.terminal = terminal;
        this.description = description;
    }

    /** "Swept In Full", "Swept Out Overflow"... — the same titles everywhere: pictures, README, site. */
    public String title() { return title; }

    /** What the status means, in one sentence (symbols written as T_min, τ_max...). */
    public String description() { return description; }

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

    /** The title as SVG markup: "In" and "Out" in their own, bolder tspan; ⏚ after a terminal one. */
    String titleMarkup(int weight) {
        String t = (" " + title + " ").replace(" In ", " <tspan style=\"font-weight:" + weight + "\">In</tspan> ")
            .replace(" Out ", " <tspan style=\"font-weight:" + weight + "\">Out</tspan> ").trim();
        return t + (terminal ? " ⏚" : "");
    }

    /**
     * The statuses as a Markdown list, "In" and "Out" in bold, symbols in italics with subscripts: the list under the
     * picture in the README (a test checks it is there word for word) and, translated to HTML, on the site.
     */
    public static String statusListMarkdown() {
        StringBuilder md = new StringBuilder();
        for (Zone z : values()) {
            String title = (" " + z.title + " ").replace(" In ", " **In** ").replace(" Out ", " **Out** ").trim();
            md.append("- ").append(title).append(" — ").append(symbols(z.description)).append('\n');
        }
        return md.toString();
    }

    /**
     * The same list as HTML for the GitHub Pages of the project: each status name in the colour of its slice ("In" and
     * "Out" in bold, ⏚ after a terminal one), {@code data-zone} for pages that recolour it.
     */
    public static String statusListHtml() {
        StringBuilder h = new StringBuilder("<ul class=\"statuses\">\n");
        for (Zone z : values()) {
            String title = (" " + z.title + " ").replace(" In ", " <b>In</b> ").replace(" Out ", " <b>Out</b> ").trim();
            h.append("  <li><span class=\"st\" data-zone=\"").append(z.name()).append("\" style=\"color:").append(z.ink).append("\">")
                .append(title).append(z.terminal ? " ⏚" : "").append("</span> — ")
                .append(symbols(SvgRenderer.esc(z.description)).replaceAll("\\*([^*]+)\\*", "<i>$1</i>")).append("</li>\n");
        }
        return h.append("</ul>\n").toString();
    }

    private static String symbols(String t) {
        return t.replaceAll("(?<!\\w)([Tτ])_(min|max)(?!\\w)", "*$1*<sub>$2</sub>")
            .replaceAll("(?<![\\w*>])([ALTx])(?![\\w*<])", "*$1*")
            .replaceAll("(?<![\\w*>])τ(?![\\w*<])", "*τ*");
    }
}
