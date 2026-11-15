package com.example.plans.model;

import java.time.Instant;

/**
 * The running Lambda instance that handled a save.
 *
 * @param fullHostName canonical host name of the machine the code ran on
 * @param startedAt    when this JVM (Lambda instance) started
 * @param buildNumber  build of the Lambda that handled the request, e.g. {@code 42-1a2b3c4}
 *                     (CI run number and short commit id)
 */
public record RuntimeInfo(
        String fullHostName,
        Instant startedAt,
        String buildNumber) {
}
