package org.krmdemo.plansinstr.equilib;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Dollar amounts as text: "$40,000" in full, "$40k" or "$2.1M" where room is short. */
final class Money {

    private Money() {
    }

    static String full(double v) {
        DecimalFormat f = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));
        return (v < 0 ? "−" : "") + "$" + f.format(Math.abs(Math.round(v)));
    }

    static String compact(double v) {
        double a = Math.abs(v);
        String sign = v < 0 ? "−" : "";
        if (a >= 1e6) return sign + "$" + oneDecimal(a / 1e6) + "M";
        if (a >= 1e3) return sign + "$" + oneDecimal(a / 1e3) + "k";
        return sign + "$" + Math.round(a);
    }

    private static String oneDecimal(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}
