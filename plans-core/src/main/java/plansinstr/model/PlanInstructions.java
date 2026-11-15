package plansinstr.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.Currency;
import java.util.UUID;

/**
 * Persistent plan instructions.
 *
 * <p>Create vs. update is decided by {@code version}: a {@code null} version means the
 * record is new and will be inserted (its {@code planId} must be {@code null} and is
 * generated); a non-null version means an update that only succeeds if it equals the stored
 * version (optimistic locking).
 *
 * <p>The whole record is stored in the {@code payload} JSONB column; {@code planId},
 * {@code createdAt}, {@code updatedAt} and {@code version} are also real columns, and the
 * column values are authoritative when a record is read back.
 *
 * @param planId      primary key, a time-ordered UUIDv7 generated on create (must be
 *                    {@code null} on create)
 * @param planType    kind of plan
 * @param planStatus  lifecycle state (active, paused, cancelled)
 * @param userId      owner of the plan
 * @param ddaAccInfo  funding account details
 * @param invAccInfo  investment account details
 * @param currency    ISO 4217 currency of the plan
 * @param thresholdInfo threshold and nominal limits; optional
 * @param restInfo    who saved it, when, and on which build; set by the server on every save
 * @param createdAt   set by the repository on create (UTC); ignored on input
 * @param updatedAt   set by the repository on every save (UTC); ignored on input
 * @param version     starts at 0 and is incremented by every successful update
 */
public record PlanInstructions(
        UUID planId,
        @NotNull PlanType planType,
        @NotNull PlanStatus planStatus,
        @NotBlank String userId,
        @NotNull @Valid DdaAccInfo ddaAccInfo,
        @NotNull @Valid InvAccInfo invAccInfo,
        @NotNull Currency currency,
        @Valid ThresholdsInfo thresholdInfo,
        @Valid RestInfo restInfo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long version) {

    /** Returns a copy with the given audit information. */
    public PlanInstructions withRestInfo(RestInfo restInfo) {
        return new PlanInstructions(planId, planType, planStatus, userId, ddaAccInfo, invAccInfo,
                currency, thresholdInfo, restInfo, createdAt, updatedAt, version);
    }
}
