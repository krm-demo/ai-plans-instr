package com.example.plans.exec;

import com.example.plans.model.DdaAccInfo;
import com.example.plans.model.InvAccInfo;
import com.example.plans.model.PlanInstructions;
import com.example.plans.model.PlanStatus;
import com.example.plans.model.PlanType;
import com.example.plans.model.ThresholdsInfo;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;

final class TestData {

    static final Instant T0 = Instant.parse("2026-11-15T14:00:00Z");
    static final UUID PLAN_ID = UUID.fromString("019a8c3e-6f2a-7b1c-9d4e-2a7f3c1b8e55");

    private TestData() {
    }

    static PlanInstructions planInstr(String thresholdMax, PlanStatus status) {
        return new PlanInstructions(PLAN_ID, PlanType.RECURRING_INVESTMENT, status, "u1",
                new DdaAccInfo("123", "B1"), new InvAccInfo("456", "X"), Currency.getInstance("USD"),
                new ThresholdsInfo(null, new BigDecimal(thresholdMax), null, null),
                null, null, null, 3L);
    }

    static PlanInstructions planInstr(String thresholdMax) {
        return planInstr(thresholdMax, PlanStatus.ACTIVE);
    }

    /** A clock the test moves forward by hand. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
