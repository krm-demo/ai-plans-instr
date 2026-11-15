package com.example.plans.model;

import java.math.BigDecimal;

/**
 * Limits applied to the plan. All values are optional; when both bounds of a pair are
 * present, the minimum must not exceed the maximum.
 *
 * @param thresholdMin lower bound of the threshold
 * @param thresholdMax upper bound of the threshold
 * @param nominalMin   lower bound of the nominal amount
 * @param nominalMax   upper bound of the nominal amount
 */
public record ThresholdsInfo(
        BigDecimal thresholdMin,
        BigDecimal thresholdMax,
        BigDecimal nominalMin,
        BigDecimal nominalMax) {

    public ThresholdsInfo {
        requireOrdered("threshold", thresholdMin, thresholdMax);
        requireOrdered("nominal", nominalMin, nominalMax);
    }

    private static void requireOrdered(String name, BigDecimal min, BigDecimal max) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + "Min must not exceed " + name + "Max");
        }
    }
}
