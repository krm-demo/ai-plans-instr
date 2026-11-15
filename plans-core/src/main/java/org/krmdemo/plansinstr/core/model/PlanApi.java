package org.krmdemo.plansinstr.core.model;

import org.krmdemo.plansinstr.core.model.enums.PlanType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** Input and output DTOs of the Lambda functions that are not {@link PlanInstructions} itself. */
public final class PlanApi {

    private PlanApi() {
    }

    /** Input of {@code loadPlan}. */
    public record LoadRequest(@NotNull UUID planId) {
    }

    /** Input of {@code lookupPlan}: the current (non-cancelled) plan of a user for a plan type. */
    public record LookupRequest(
            @NotBlank String userId,
            @NotNull PlanType planType) {
    }

    /** Output of {@code searchPlans}. {@code limit} and {@code offset} echo the applied paging. */
    public record SearchResult(
            List<PlanInstructions> items,
            int limit,
            int offset) {
    }
}
