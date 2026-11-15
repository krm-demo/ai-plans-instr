package com.example.plans.persistence;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates time-ordered UUIDv7 values (RFC 9562).
 *
 * <p>Layout: 48-bit Unix time in milliseconds, version 7, a 12-bit counter, variant bits and
 * 62 random bits. Ids from one generator are strictly increasing, even when many are created
 * in the same millisecond or the clock steps backwards. Postgres compares {@code uuid}
 * values bytewise, so {@code ORDER BY plan_id} matches creation order.
 *
 * <p>Ids created concurrently by different generator instances (e.g. two Lambda instances)
 * in the same millisecond are ordered randomly relative to each other.
 */
public final class UuidV7Generator {

    private final SecureRandom random = new SecureRandom();
    private long lastMillis = -1L;
    private int counter;

    /**
     * Returns the next id. Its embedded timestamp is at least {@code nowMillis}; it can be
     * one or more milliseconds later if the clock went backwards or the counter ran out.
     * Read the timestamp back with {@link #timestampMillis(UUID)}.
     */
    public synchronized UUID next(long nowMillis) {
        long ts = Math.max(nowMillis, lastMillis);
        if (ts > lastMillis) {
            counter = random.nextInt(1 << 11); // start in the lower half to leave headroom
        } else if (++counter > 0xFFF) {
            ts = lastMillis + 1;               // counter exhausted: move to the next millisecond
            counter = random.nextInt(1 << 11);
        }
        lastMillis = ts;
        long msb = (ts << 16) | 0x7000L | counter;
        long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }

    /** Extracts the creation time (epoch milliseconds) embedded in a UUIDv7. */
    public static long timestampMillis(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }
}
