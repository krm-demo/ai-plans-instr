package plansinstr.exec;

import plansinstr.execution.PlanExecution;
import plansinstr.model.DdaAccInfo;
import plansinstr.model.InvAccInfo;
import plansinstr.model.PlanInstructions;
import plansinstr.model.PlanStatus;
import plansinstr.model.PlanType;
import plansinstr.model.ThresholdsInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

final class TestPlans {

    static final Instant T0 = Instant.parse("2026-11-15T14:00:00Z");

    private TestPlans() {
    }

    static PlanInstructions plan(String thresholdMax) {
        return new PlanInstructions(
                UUID.fromString("019a8c3e-6f2a-7b1c-9d4e-2a7f3c1b8e55"),
                PlanType.RECURRING_INVESTMENT, PlanStatus.ACTIVE, "u1",
                new DdaAccInfo("123", "B1"), new InvAccInfo("456", "X"),
                Currency.getInstance("USD"),
                new ThresholdsInfo(null, thresholdMax == null ? null : new BigDecimal(thresholdMax), null, null),
                null, null, null, 3L);
    }

    static PlanExecution scheduled() {
        return PlanExecution.schedule(UUID.fromString("019a8c40-0000-7000-8000-000000000001"),
                plan("500.00"), T0);
    }

    static Instant at(int seconds) {
        return T0.plusSeconds(seconds);
    }

    static BigDecimal amt(String value) {
        return new BigDecimal(value);
    }
}
