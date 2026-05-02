package com.craftguard.msverify.storage;

import java.util.UUID;

public record CompletedVerification(
        String challengeId,
        UUID minecraftUuid,
        String minecraftName,
        String xuid,
        long verifiedAt
) {
}
