package com.craftguard.msverify.storage;

import java.util.UUID;

public record VerifiedPlayer(
        UUID minecraftUuid,
        String minecraftName,
        String xuid,
        long verifiedAt
) {
}
