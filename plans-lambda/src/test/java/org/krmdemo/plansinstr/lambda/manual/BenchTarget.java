package org.krmdemo.plansinstr.lambda.manual;

import org.krmdemo.plansinstr.core.model.PlanInstructions;
import org.krmdemo.plansinstr.core.model.enums.PlanStatus;
import org.krmdemo.plansinstr.core.model.enums.PlanType;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * One database under the CRUD benchmark ({@link CrudBenchmarkTest}): the same operations on
 * {@link PlanInstructions}, whatever stores them.
 */
interface BenchTarget extends AutoCloseable {

    /** Short name for the report, for example {@code embedded-postgres}. */
    String name();

    /** One line about where it runs, for the report. */
    String about();

    /** A new plan: {@code planId} and {@code version} are null; returns the stored plan. */
    PlanInstructions create(PlanInstructions plan);

    Optional<PlanInstructions> load(UUID planId);

    /** Optimistic update: the plan's {@code version} must be the stored one. */
    PlanInstructions update(PlanInstructions plan);

    boolean delete(UUID planId);

    /** The user's most recently updated plan of the type that is not cancelled. */
    Optional<PlanInstructions> lookup(String userId, PlanType planType);

    /** The user's plans, newest first; {@code status} null means any. */
    List<PlanInstructions> byUser(String userId, PlanStatus status);

    /** The plans funded from a DDA account. */
    List<PlanInstructions> byDdaAccount(String ddaAccNum);

    /**
     * The newest plan of every user whose id starts with the prefix ({@code DISTINCT ON} in
     * Postgres); returns how many there are, or empty when the target cannot do it in one query.
     */
    default OptionalInt latestPlanPerUser(String userIdPrefix) {
        return OptionalInt.empty();
    }

    @Override
    void close();
}
