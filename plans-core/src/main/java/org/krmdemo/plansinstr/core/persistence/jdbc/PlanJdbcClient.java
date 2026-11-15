package org.krmdemo.plansinstr.core.persistence.jdbc;

import org.krmdemo.plansinstr.core.model.PlanInstructions;
import org.krmdemo.plansinstr.core.model.PlanSearchCriteria;
import org.krmdemo.plansinstr.core.model.enums.PlanType;
import org.krmdemo.plansinstr.core.persistence.PlanNotFoundException;
import org.krmdemo.plansinstr.core.persistence.UuidV7Generator;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * All persistence of {@link PlanInstructions}: the table {@code plan_instructions} holds one
 * {@link VersionedEntity} per plan, with the plan as its JSONB payload.
 *
 * <p>{@code planId}, {@code createdAt}, {@code updatedAt} and {@code version} of a plan are the
 * envelope's {@code id}, creation time, {@code lastUpdatedAt} and {@code version}. They are not
 * stored inside the payload; a plan gets them from the envelope when it is read.
 */
@Repository
public class PlanJdbcClient {

    private final VersionedEntityJdbcClient<PlanInstructions> entities;

    public PlanJdbcClient(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.entities = new VersionedEntityJdbcClient<>(jdbc, json, clock, "plan_instructions",
                PlanInstructions.class);
    }

    /** Loads a plan by primary key. */
    public Optional<PlanInstructions> load(UUID planId) {
        return entities.findById(planId).map(PlanJdbcClient::toPlan);
    }

    /**
     * Creates the plan when {@code version} is null, otherwise updates it.
     *
     * @param reason why the plan is written; stored as {@code last_updated_reason}
     * @return the stored state, including generated id, timestamps and the new version
     * @throws IllegalArgumentException when creating with a planId, or updating without one
     * @throws PlanNotFoundException when updating a plan that does not exist
     * @throws OptimisticLockingFailureException when the stored version differs from the
     *         one supplied
     */
    public PlanInstructions save(PlanInstructions plan, String reason) {
        return plan.version() == null ? insert(plan, reason) : update(plan, reason);
    }

    private PlanInstructions insert(PlanInstructions plan, String reason) {
        if (plan.planId() != null) {
            throw new IllegalArgumentException("planId is generated; it must be null when creating a plan");
        }
        return toPlan(entities.insert(payloadOf(plan), reason));
    }

    private PlanInstructions update(PlanInstructions plan, String reason) {
        if (plan.planId() == null) {
            throw new IllegalArgumentException("planId is required when version is set");
        }
        try {
            return toPlan(entities.update(plan.planId(), Math.toIntExact(plan.version()), payloadOf(plan),
                    reason, Map.of()));
        } catch (OptimisticLockingFailureException e) {
            if (entities.findById(plan.planId()).isEmpty()) {
                throw new PlanNotFoundException(plan.planId());
            }
            throw e;
        }
    }

    /** Finds the most recently updated non-cancelled plan of a user for a plan type. */
    public Optional<PlanInstructions> lookup(String userId, PlanType planType) {
        return entities.find("""
                        WHERE payload ->> 'userId' = :userId
                          AND payload ->> 'planType' = :planType
                          AND payload ->> 'planStatus' <> 'CANCELLED'
                        ORDER BY last_updated_at DESC
                        LIMIT 1""",
                        Map.of("userId", userId, "planType", planType.name()))
                .stream()
                .findFirst()
                .map(PlanJdbcClient::toPlan);
    }

    /** Returns one page of plans matching all supplied criteria, newest first. */
    public List<PlanInstructions> search(PlanSearchCriteria c) {
        StringBuilder sql = new StringBuilder("WHERE 1 = 1");
        Map<String, Object> p = new HashMap<>();

        if (c.userId() != null) {
            sql.append(" AND payload ->> 'userId' = :userId");
            p.put("userId", c.userId());
        }
        if (!c.planTypes().isEmpty()) {
            sql.append(" AND payload ->> 'planType' IN (:planTypes)");
            p.put("planTypes", c.planTypes().stream().map(Enum::name).toList());
        }
        if (!c.planStatuses().isEmpty()) {
            sql.append(" AND payload ->> 'planStatus' IN (:planStatuses)");
            p.put("planStatuses", c.planStatuses().stream().map(Enum::name).toList());
        }
        if (c.currency() != null) {
            sql.append(" AND payload ->> 'currency' = :currency");
            p.put("currency", c.currency().getCurrencyCode());
        }
        if (c.ddaAccNum() != null) {
            sql.append(" AND payload -> 'ddaAccInfo' ->> 'ddaAccNum' = :ddaAccNum");
            p.put("ddaAccNum", c.ddaAccNum());
        }
        if (c.invAccNum() != null) {
            sql.append(" AND payload -> 'invAccInfo' ->> 'invAccNum' = :invAccNum");
            p.put("invAccNum", c.invAccNum());
        }
        // The creation time is inside the UUIDv7 id, so a range of creation times is a range of ids.
        if (c.createdFrom() != null) {
            sql.append(" AND id >= :createdFromId");
            p.put("createdFromId", firstIdOf(c.createdFrom()));
        }
        if (c.createdTo() != null) {
            sql.append(" AND id < :createdToId");
            p.put("createdToId", firstIdOf(c.createdTo()));
        }
        // for the same reason this is newest-first by creation time
        sql.append(" ORDER BY id DESC LIMIT :limit OFFSET :offset");
        p.put("limit", c.limit());
        p.put("offset", c.offset());

        return entities.find(sql.toString(), p).stream().map(PlanJdbcClient::toPlan).toList();
    }

    private static UUID firstIdOf(LocalDateTime utc) {
        return UuidV7Generator.firstOf(utc.toInstant(ZoneOffset.UTC).toEpochMilli());
    }

    /** The plan as it is stored: without the fields that the envelope carries. */
    private static PlanInstructions payloadOf(PlanInstructions p) {
        return new PlanInstructions(null, p.planType(), p.planStatus(), p.userId(), p.ddaAccInfo(),
                p.invAccInfo(), p.currency(), p.thresholdInfo(), p.restInfo(), null, null, null);
    }

    /** The stored plan with the envelope's fields; the timestamps are UTC. */
    private static PlanInstructions toPlan(VersionedEntity<PlanInstructions> entity) {
        PlanInstructions p = entity.payload();
        return new PlanInstructions(
                entity.id(),
                p.planType(), p.planStatus(), p.userId(), p.ddaAccInfo(), p.invAccInfo(),
                p.currency(), p.thresholdInfo(), p.restInfo(),
                LocalDateTime.ofInstant(entity.createdAt(), ZoneOffset.UTC),
                LocalDateTime.ofInstant(entity.lastUpdatedAt(), ZoneOffset.UTC),
                (long) entity.version());
    }
}
