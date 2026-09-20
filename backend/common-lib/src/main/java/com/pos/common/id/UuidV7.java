package com.pos.common.id;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * UUID version 7 (RFC 9562): a 48-bit millisecond timestamp followed by random bits.
 *
 * <p>Used for every primary key in the system for two reasons. Being time-ordered, sequential
 * inserts land at the end of the B-tree instead of scattering across it the way UUIDv4 does, which
 * keeps index pages dense. And being client-generatable, an offline till can mint an id for a sale
 * it has not yet been able to send, with no risk of collision when it syncs.
 *
 * <p>Ordering is to the millisecond. Two ids generated inside the same millisecond have no
 * guaranteed order relative to each other, which is fine for indexing and must not be relied on for
 * sequencing business events - use {@code occurredAt} for that.
 */
public final class UuidV7 {

    private UuidV7() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    public static UUID randomUUID() {
        return generate(System.currentTimeMillis());
    }

    static UUID generate(long epochMilli) {
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        // 48 bits timestamp | 4 bits version (7) | 12 bits random
        long msb = (epochMilli & 0xFFFF_FFFF_FFFFL) << 16;
        msb |= 0x7000L;
        msb |= ((long) (random[0] & 0x0F) << 8) | (random[1] & 0xFFL);

        // 2 bits variant (binary 10) | 62 bits random
        long lsb = 0L;
        for (int i = 2; i < 10; i++) {
            lsb = (lsb << 8) | (random[i] & 0xFFL);
        }
        lsb &= 0x3FFF_FFFF_FFFF_FFFFL;
        lsb |= 0x8000_0000_0000_0000L;

        return new UUID(msb, lsb);
    }

    /** The creation instant encoded in a v7 UUID. */
    public static Instant timestampOf(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("not a UUIDv7: version " + uuid.version());
        }
        return Instant.ofEpochMilli(uuid.getMostSignificantBits() >>> 16);
    }
}
