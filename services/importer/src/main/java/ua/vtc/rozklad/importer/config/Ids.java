package ua.vtc.rozklad.importer.config;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/** Time-ordered UUID v7 (RFC 9562) for primary keys: new rows land at the end of the index. */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        long millis = Clock.systemUTC().millis();
        long randA = RANDOM.nextInt(1 << 12);
        long mostSignificant = (millis << 16) | (0x7L << 12) | randA;
        long leastSignificant = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
