package com.craftguard.msverify.util;

import java.util.Optional;
import java.util.UUID;

public final class UuidUtil {
    private UuidUtil() {
    }

    public static Optional<UUID> parseUuid(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }

        String value = input.trim();
        if (value.length() == 32) {
            value = value.substring(0, 8)
                    + "-"
                    + value.substring(8, 12)
                    + "-"
                    + value.substring(12, 16)
                    + "-"
                    + value.substring(16, 20)
                    + "-"
                    + value.substring(20);
        }

        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
