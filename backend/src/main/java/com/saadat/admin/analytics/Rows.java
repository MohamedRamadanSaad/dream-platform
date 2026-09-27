package com.saadat.admin.analytics;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.UUID;

/** Tolerant converters for native-query column values (driver/Hibernate types vary). */
final class Rows {

    private Rows() {
    }

    static UUID uuid(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof UUID u ? u : UUID.fromString(o.toString());
    }

    static String string(Object o) {
        return o == null ? null : o.toString().trim();
    }

    static long longValue(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    static Double doubleOrNull(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    static BigDecimal decimal(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        return o instanceof Number n ? new BigDecimal(n.toString()) : new BigDecimal(o.toString());
    }

    static Instant instant(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Instant i) {
            return i;
        }
        if (o instanceof Timestamp t) {
            return t.toInstant();
        }
        if (o instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (o instanceof ZonedDateTime zdt) {
            return zdt.toInstant();
        }
        if (o instanceof LocalDateTime ldt) {
            return ldt.toInstant(ZoneOffset.UTC);
        }
        if (o instanceof Date d) {
            return d.toInstant();
        }
        return Instant.parse(o.toString());
    }
}
