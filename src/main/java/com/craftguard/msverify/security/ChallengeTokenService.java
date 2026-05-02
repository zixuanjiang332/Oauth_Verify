package com.craftguard.msverify.security;

import com.craftguard.msverify.ConfigValues;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

public final class ChallengeTokenService {
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private final Gson gson = new Gson();
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConfigValues config;
    private final HmacSigner signer;

    public ChallengeTokenService(ConfigValues config) {
        this.config = config;
        this.signer = new HmacSigner(config.sharedSecret());
    }

    public IssuedChallenge issue(UUID minecraftUuid, String minecraftName) {
        long issuedAt = System.currentTimeMillis();
        long expiresAt = issuedAt + config.challengeTtl().toMillis();
        String challengeId = UUID.randomUUID().toString();

        JsonObject payload = new JsonObject();
        payload.addProperty("v", 1);
        payload.addProperty("challengeId", challengeId);
        payload.addProperty("serverId", config.serverId());
        payload.addProperty("minecraftUuid", minecraftUuid.toString());
        payload.addProperty("minecraftName", minecraftName);
        payload.addProperty("issuedAt", issuedAt);
        payload.addProperty("expiresAt", expiresAt);
        payload.addProperty("nonce", randomNonce());

        String payloadJson = gson.toJson(payload);
        String payloadPart = BASE64_URL.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signaturePart = signer.signBase64Url(payloadJson);
        return new IssuedChallenge(challengeId, minecraftUuid, expiresAt, payloadPart + "." + signaturePart);
    }

    public Optional<JsonObject> verify(String token, long nowMillis) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Optional.empty();
        }

        String payloadJson;
        try {
            payloadJson = new String(BASE64_URL_DECODER.decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }

        if (!signer.verifyBase64Url(payloadJson, parts[1])) {
            return Optional.empty();
        }

        JsonObject payload;
        try {
            payload = JsonParser.parseString(payloadJson).getAsJsonObject();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }

        if (!payload.has("expiresAt") || payload.get("expiresAt").getAsLong() < nowMillis) {
            return Optional.empty();
        }
        return Optional.of(payload);
    }

    private String randomNonce() {
        byte[] bytes = new byte[18];
        secureRandom.nextBytes(bytes);
        return BASE64_URL.encodeToString(bytes);
    }
}
