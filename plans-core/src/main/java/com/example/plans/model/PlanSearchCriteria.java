package com.example.plans.model;

import java.time.LocalDateTime;
import java.util.Currency;
import java.util.Set;

/**
 * Filters for {@code search}. Every field is optional; supplied filters are combined with AND.
 * Results are ordered by creation time, newest first.
 *
 * @param userId        exact user id
 * @param planTypes     match any of these types
 * @param planStatuses  match any of these statuses
 * @param currency      exact currency
 * @param ddaAccNum     exact DDA account number
 * @param invAccNum     exact investment account number
 * @param createdFrom   created at or after this instant (inclusive)
 * @param createdTo     created before this instant (exclusive)
 * @param limit         page size; defaults to 50, capped at 500
 * @param offset        number of rows to skip; defaults to 0
 */
public record PlanSearchCriteria(
        String userId,
        Set<PlanType> planTypes,
        Set<PlanStatus> planStatuses,
        Currency currency,
        String ddaAccNum,
        String invAccNum,
        LocalDateTime createdFrom,
        LocalDateTime createdTo,
        Integer limit,
        Integer offset) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;

    public PlanSearchCriteria {
        planTypes = planTypes == null ? Set.of() : Set.copyOf(planTypes);
        planStatuses = planStatuses == null ? Set.of() : Set.copyOf(planStatuses);
        limit = (limit == null || limit < 1) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        offset = (offset == null || offset < 0) ? 0 : offset;
    }
}
