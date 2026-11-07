package ua.vtc.rozklad.core.shared.domain;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates primary keys in Java (RFC 9562 UUID version 7): time-ordered, so new rows land at
 * the end of B-tree indexes instead of random pages.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long VERSION_7 = 0x7000L;
    private static final long VARIANT_RFC = 0x8000_0000_0000_0000L;
    private static final long VARIANT_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

    private Ids() {
    }

    public static UUID newId() {
        long timestampMillis = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long mostSigBits = (timestampMillis << 16) | VERSION_7 | randA;
        long leastSigBits = (RANDOM.nextLong() & VARIANT_MASK) | VARIANT_RFC;
        return new UUID(mostSigBits, leastSigBits);
    }
}
