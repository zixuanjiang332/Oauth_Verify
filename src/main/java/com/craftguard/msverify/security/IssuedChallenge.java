package com.craftguard.msverify.security;

import java.util.UUID;

public record IssuedChallenge(
        String challengeId,
        UUID minecraftUuid,
        long expiresAt,
        String token
) {
}
