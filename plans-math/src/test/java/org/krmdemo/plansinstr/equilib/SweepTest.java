package org.krmdemo.plansinstr.equilib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SweepTest {

    // T_min 40k, T_max 60k, τ_min 8k, τ_max 10k, A 25k, L 30k
    private final Sweep s = new Sweep(40_000, 60_000, 8_000, 10_000, 25_000, 30_000);

    private void check(long x, Zone zone, Long y) {
        Sweep.Point p = s.at(x);
        assertEquals(zone, p.zone(), "zone at " + x);
        assertEquals(y, p.amount(), "amount at " + x);
    }

    @Test
    void zonesFromLeftToRight() {
        check(0, Zone.SWEPT_IN_PARTIAL, 25_000L);
        check(14_999, Zone.SWEPT_IN_PARTIAL, 25_000L);
        check(15_000, Zone.SWEPT_IN_FULL, 25_000L);          // T_min − A: continuous
        check(32_000, Zone.SWEPT_IN_FULL, 8_000L);           // T_min − τ_min: the jump belongs to the sweep
        check(32_001, Zone.IN_NOMINAL_MIN, 0L);
        check(40_000, Zone.IN_RANGE, 0L);
        check(60_000, Zone.IN_RANGE, 0L);
        check(69_999, Zone.IN_NOMINAL_MAX, 0L);
        check(70_000, Zone.SWEPT_OUT, 10_000L);              // T_max + τ_max: the jump belongs to the sweep
        check(90_000, Zone.SWEPT_OUT, 30_000L);              // T_max + L
        check(90_001, Zone.SWEPT_OUT_OVERFLOW, null);
    }

    @Test
    void sweptInZeroWhenTheInvestmentAccountIsEmpty() {
        Sweep empty = new Sweep(40_000, 60_000, 8_000, 10_000, 0, 30_000);
        assertEquals(Zone.SWEPT_IN_ZERO, empty.at(32_000).zone());
        assertNull(empty.at(0).amount());
        assertEquals(Zone.IN_NOMINAL_MIN, empty.at(32_001).zone());
    }

    @Test
    void noNominalsMeansNoJumpButNothingToSweepAtTheThreshold() {
        Sweep exact = new Sweep(50_000, 50_000, 0, 0, 20_000, 25_000);
        assertEquals(Zone.SWEPT_IN_FULL, exact.at(49_999).zone());
        assertEquals(Zone.IN_RANGE, exact.at(50_000).zone());
        assertEquals(Zone.SWEPT_OUT, exact.at(50_001).zone());
    }

    @Test
    void inconsistentParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Sweep(60_000, 40_000, 0, 0, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new Sweep(40_000, 60_000, 8_000, 0, 5_000, 10));
        assertThrows(IllegalArgumentException.class, () -> new Sweep(40_000, 60_000, 0, 10_000, 0, 5_000));
    }

    @Test
    void snappingKeepsTwoSignificantDigits() {
        assertEquals(38_000, Sweep.snap(37_512));
        assertEquals(2_300_000, Sweep.snap(2_340_000));
        assertEquals(10_000, Sweep.snap(9_999));
        assertEquals(7, Sweep.snap(7));
        assertEquals(0, Sweep.snap(-5));
    }
}
