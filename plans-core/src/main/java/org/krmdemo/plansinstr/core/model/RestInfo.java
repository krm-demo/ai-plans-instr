package org.krmdemo.plansinstr.core.model;

import jakarta.validation.Valid;

import java.time.Instant;

/**
 * Who saved a plan, when, and on which build. The server sets this on every create and
 * update; any value a client sends for it is replaced.
 *
 * @param userAgent   HTTP {@code User-Agent} of the caller; {@code null} when the call did
 *                    not come through HTTP (for example a direct Lambda invoke)
 * @param timestamp   when the save was handled (UTC)
 * @param runtimeInfo the Lambda instance and build that handled the save
 */
public record RestInfo(
        String userAgent,
        Instant timestamp,
        @Valid RuntimeInfo runtimeInfo) {
}
