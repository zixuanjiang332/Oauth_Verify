package com.craftguard.msverify.service;

import com.craftguard.msverify.ConfigValues;
import com.craftguard.msverify.security.HmacSigner;
import com.craftguard.msverify.storage.BatchApplyResult;
import com.craftguard.msverify.storage.CompletedVerification;
import com.craftguard.msverify.util.UuidUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VerificationPoller {
    private final JavaPlugin plugin;
    private final VerificationService verificationService;
    private final HttpClient httpClient;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    private BukkitTask task;

    public VerificationPoller(JavaPlugin plugin, VerificationService verificationService) {
        this.plugin = plugin;
        this.verificationService = verificationService;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void start() {
        ConfigValues config = verificationService.config();
        if (!config.pollerEnabled()) {
            plugin.getLogger().info("验证轮询已停用。");
            return;
        }
        long intervalTicks = Math.max(20L, config.pollInterval().toSeconds() * 20L);
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::pollOnce, 20L, intervalTicks);
    }

    public void restart() {
        stop();
        start();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void pollOnce() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }

        try {
            ConfigValues config = verificationService.config();
            String cursor = verificationService.currentCursor();
            URI uri = buildCompletedUri(config.completedEndpoint(), config.serverId(), cursor);
            long timestamp = System.currentTimeMillis();
            String rawPathAndQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            String canonical = "GET\n" + rawPathAndQuery + "\n" + config.serverId() + "\n" + cursor + "\n" + timestamp;
            String signature = new HmacSigner(config.sharedSecret()).signBase64Url(canonical);

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(config.requestTimeout())
                    .header("Accept", "application/json")
                    .header("X-MSVerify-Server", config.serverId())
                    .header("X-MSVerify-Timestamp", Long.toString(timestamp))
                    .header("X-MSVerify-Signature", signature)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                plugin.getLogger().warning("验证轮询请求失败，HTTP 状态码：" + response.statusCode());
                return;
            }

            PollResponse pollResponse = parseResponse(response.body());
            BatchApplyResult result = verificationService.applyCompleted(pollResponse.items(), pollResponse.nextCursor());
            for (String warning : result.warnings()) {
                plugin.getLogger().warning(warning);
            }
            if (!result.verifiedPlayers().isEmpty()) {
                plugin.getLogger().info("已应用 " + result.verifiedPlayers().size() + " 条完成验证记录。");
            }
            verificationService.cleanupExpiredChallenges();
        } catch (IOException exception) {
            plugin.getLogger().warning("验证轮询网络错误：" + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("验证轮询被中断。");
        } catch (SQLException exception) {
            plugin.getLogger().warning("验证轮询存储错误：" + exception.getMessage());
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("验证轮询解析错误：" + exception.getMessage());
        } finally {
            polling.set(false);
        }
    }

    private URI buildCompletedUri(String endpoint, String serverId, String cursor) {
        String separator = endpoint.contains("?") ? "&" : "?";
        String url = endpoint
                + separator
                + "serverId="
                + URLEncoder.encode(serverId, StandardCharsets.UTF_8)
                + "&since="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        return URI.create(url);
    }

    private PollResponse parseResponse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String nextCursor = getString(root, "nextCursor", null);
        List<CompletedVerification> items = new ArrayList<>();
        JsonArray array = root.has("items") && root.get("items").isJsonArray()
                ? root.getAsJsonArray("items")
                : new JsonArray();

        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String challengeId = getString(object, "challengeId", "");
            Optional<UUID> minecraftUuid = UuidUtil.parseUuid(getString(object, "minecraftUuid", ""));
            String minecraftName = getString(object, "minecraftName", "");
            String xuid = getString(object, "xuid", "");
            long verifiedAt = getLong(object, "verifiedAt", System.currentTimeMillis());

            if (challengeId.isBlank() || minecraftUuid.isEmpty() || minecraftName.isBlank() || xuid.isBlank()) {
                plugin.getLogger().warning("已跳过格式不正确的完成验证记录。");
                continue;
            }
            items.add(new CompletedVerification(challengeId, minecraftUuid.get(), minecraftName, xuid, verifiedAt));
        }

        return new PollResponse(items, nextCursor);
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

    private record PollResponse(List<CompletedVerification> items, String nextCursor) {
    }
}
