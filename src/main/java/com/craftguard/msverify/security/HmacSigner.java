package com.craftguard.msverify.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

public final class HmacSigner {
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secretBytes;

    public HmacSigner(String secret) {
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String signBase64Url(String data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sign(data));
    }

    public boolean verifyBase64Url(String data, String suppliedSignature) {
        byte[] expected = signBase64Url(data).getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = suppliedSignature.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, supplied);
    }

    private byte[] sign(String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 不可用", exception);
        }
    }
}
