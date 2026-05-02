package com.craftguard.msverify.service;

import java.util.UUID;

public record RemoteVerificationResult(
        String status,
        String token,
        UUID minecraftUuid,
        String minecraftName,
        String xuid,
        String email,
        long verifiedAt,
        String message
) {
    public boolean verified() {
        return "verified".equalsIgnoreCase(status);
    }

    public boolean pending() {
        return "pending".equalsIgnoreCase(status);
    }

    public boolean terminalFailure() {
        return "expired".equalsIgnoreCase(status)
                || "denied".equalsIgnoreCase(status)
                || "error".equalsIgnoreCase(status)
                || "not_found".equalsIgnoreCase(status);
    }
}
