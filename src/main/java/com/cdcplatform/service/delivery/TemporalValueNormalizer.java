package com.cdcplatform.service.delivery;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

public final class TemporalValueNormalizer {
    private static final long EPOCH_MILLIS_THRESHOLD = 10_000_000_000L;

    private TemporalValueNormalizer() {}

    public static Object normalize(String column, Object value) {
        if (!(value instanceof Number number) || !isTemporalColumn(column)
                || Math.abs(number.longValue()) < EPOCH_MILLIS_THRESHOLD) return value;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(number.longValue()), ZoneOffset.UTC);
    }

    private static boolean isTemporalColumn(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.endsWith("_at") || normalized.endsWith("_time") || normalized.endsWith("_date")
                || normalized.equals("createdat") || normalized.equals("updatedat");
    }
}
