package com.craftguard.msverify.storage;

import java.util.UUID;

public record PendingVerification(
        String token,
        UUID minecraftUuid,
        String minecraftName,
        String link,
        long expiresAt,
        long createdAt
) {
}
