package com.craftguard.msverify.service;

import com.craftguard.msverify.ConfigValues;
import com.craftguard.msverify.util.UuidUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public final class VerificationApiClient {
    private final JavaPlugin plugin;
    private final HttpClient httpClient;

    public VerificationApiClient(JavaPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public GeneratedVerification generate(ConfigValues config, UUID minecraftUuid, String minecraftName)
            throws IOException, InterruptedException {
        URI uri = buildGenerateUri(config.verificationGenerateEndpoint(), config.serverId(), minecraftUuid, minecraftName);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(config.requestTimeout())
                .header("Accept", "application/json")
                .header("X-Api-Key", config.apiKey())
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("生成验证链接失败，HTTP 状态码：" + response.statusCode());
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String token = getString(root, "token", "");
        String link = firstNonBlank(
                getString(root, "link", ""),
                getString(root, "url", ""),
                getString(root, "verificationUrl", "")
        );
        long expiresAt = getLong(root, "expiresAt", System.currentTimeMillis() + config.challengeTtl().toMillis());
        if (token.isBlank() || link.isBlank()) {
            throw new IOException("生成验证链接响应缺少 token 或 link。");
        }
        return new GeneratedVerification(token, link, expiresAt);
    }

    public RemoteVerificationResult fetchResult(ConfigValues config, String token) throws IOException, InterruptedException {
        URI uri = buildResultUri(config.verificationResultEndpoint(), token);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(config.requestTimeout())
                .header("Accept", "application/json")
                .header("X-Api-Key", config.apiKey())
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) {
            return new RemoteVerificationResult("not_found", token, null, "", "", "", 0L, "远端没有找到 token。");
        }
        if (response.statusCode() == 202) {
            return new RemoteVerificationResult("pending", token, null, "", "", "", 0L, "等待玩家完成验证。");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("查询验证结果失败，HTTP 状态码：" + response.statusCode());
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String status = getString(root, "status", "pending");
        if (!"verified".equalsIgnoreCase(status)) {
            return new RemoteVerificationResult(
                    status,
                    getString(root, "token", token),
                    null,
                    "",
                    "",
                    "",
                    0L,
                    getString(root, "message", "")
            );
        }

        Optional<UUID> uuid = UuidUtil.parseUuid(firstNonBlank(
                getString(root, "minecraftUuid", ""),
                getString(root, "uuid", "")
        ));
        if (uuid.isEmpty()) {
            plugin.getLogger().warning("远端验证结果缺少有效 UUID，token=" + token);
            return new RemoteVerificationResult("error", token, null, "", "", "", 0L, "远端验证结果缺少有效 UUID。");
        }

        return new RemoteVerificationResult(
                "verified",
                getString(root, "token", token),
                uuid.get(),
                getString(root, "minecraftName", ""),
                getString(root, "xuid", ""),
                getString(root, "email", ""),
                getLong(root, "verifiedAt", System.currentTimeMillis()),
                ""
        );
    }

    private URI buildGenerateUri(String endpoint, String serverId, UUID minecraftUuid, String minecraftName) {
        String separator = endpoint.contains("?") ? "&" : "?";
        String url = endpoint
                + separator
                + "serverId=" + encode(serverId)
                + "&minecraftUuid=" + encode(minecraftUuid.toString())
                + "&minecraftName=" + encode(minecraftName);
        return URI.create(url);
    }

    private URI buildResultUri(String endpoint, String token) {
        String separator = endpoint.contains("?") ? "&" : "?";
        return URI.create(endpoint + separator + "token=" + encode(token));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String getString(JsonObject object, String field, String fallback) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return fallback;
        }
        return object.get(field).getAsString();
    }

    private long getLong(JsonObject object, String field, long fallback) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(field).getAsLong();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private String firstNonBlank(String first, String... rest) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        for (String value : rest) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
