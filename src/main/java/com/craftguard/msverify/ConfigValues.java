package com.craftguard.msverify;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public record ConfigValues(
        String serverId,
        String verificationStartUrl,
        String completedEndpoint,
        String sharedSecret,
        String databaseFileName,
        boolean microsoftOAuthEnabled,
        String microsoftAuthorizationEndpoint,
        String microsoftClientId,
        String microsoftRedirectUri,
        String microsoftScope,
        String microsoftPrompt,
        Duration challengeTtl,
        boolean pollerEnabled,
        Duration pollInterval,
        Duration requestTimeout,
        String reloadMessage,
        String noPermissionMessage,
        String usageMessage
) {
    private static final String DEFAULT_SECRET = "CHANGE_ME_TO_A_LONG_RANDOM_SECRET";

    public static ConfigValues from(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        ConfigValues values = new ConfigValues(
                config.getString("server-id", "survival-1"),
                config.getString("verification-start-url", "https://verify.example.com/start"),
                config.getString("completed-endpoint", "https://verify.example.com/api/verifications/completed"),
                config.getString("shared-secret", DEFAULT_SECRET),
                config.getString("database-file", "msverify.db"),
                config.getBoolean("microsoft-oauth.enabled", true),
                config.getString("microsoft-oauth.authorization-endpoint", "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize"),
                config.getString("microsoft-oauth.client-id", "CHANGE_ME_CLIENT_ID"),
                config.getString("microsoft-oauth.redirect-uri", "https://verify.example.com/oauth/microsoft/callback"),
                config.getString("microsoft-oauth.scope", "XboxLive.signin openid email profile"),
                config.getString("microsoft-oauth.prompt", "select_account"),
                Duration.ofSeconds(Math.max(30, config.getLong("challenge.ttl-seconds", 300))),
                config.getBoolean("poller.enabled", true),
                Duration.ofSeconds(Math.max(5, config.getLong("poller.interval-seconds", 15))),
                Duration.ofSeconds(Math.max(3, config.getLong("poller.request-timeout-seconds", 10))),
                config.getString("messages.reload", "MsVerify 配置已重新加载。"),
                config.getString("messages.no-permission", "你没有权限使用这个命令。"),
                config.getString("messages.usage", "用法：/msverify <reload|status|unverify>")
        );
        values.warnIfUnsafe(plugin);
        return values;
    }

    public ConfigValues withDatabaseFileName(String databaseFileName) {
        return new ConfigValues(
                serverId,
                verificationStartUrl,
                completedEndpoint,
                sharedSecret,
                databaseFileName,
                microsoftOAuthEnabled,
                microsoftAuthorizationEndpoint,
                microsoftClientId,
                microsoftRedirectUri,
                microsoftScope,
                microsoftPrompt,
                challengeTtl,
                pollerEnabled,
                pollInterval,
                requestTimeout,
                reloadMessage,
                noPermissionMessage,
                usageMessage
        );
    }

    public String buildVerificationUrl(String challengeToken) {
        if (microsoftOAuthEnabled) {
            return buildMicrosoftAuthorizationUrl(challengeToken);
        }

        String separator = verificationStartUrl.contains("?") ? "&" : "?";
        return verificationStartUrl
                + separator
                + "challenge="
                + URLEncoder.encode(challengeToken, StandardCharsets.UTF_8)
                + "&serverId="
                + URLEncoder.encode(serverId, StandardCharsets.UTF_8);
    }

    private String buildMicrosoftAuthorizationUrl(String challengeToken) {
        String separator = microsoftAuthorizationEndpoint.contains("?") ? "&" : "?";
        StringBuilder builder = new StringBuilder(microsoftAuthorizationEndpoint)
                .append(separator)
                .append("client_id=").append(encode(microsoftClientId))
                .append("&response_type=code")
                .append("&redirect_uri=").append(encode(microsoftRedirectUri))
                .append("&scope=").append(encode(microsoftScope))
                .append("&state=").append(encode(challengeToken));
        if (microsoftPrompt != null && !microsoftPrompt.isBlank()) {
            builder.append("&prompt=").append(encode(microsoftPrompt));
        }
        return builder.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public Component renderVerificationChatComponent(String verificationUrl) {
        Component link = Component.text("[点击这里完成验证]")
                .color(NamedTextColor.AQUA)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(verificationUrl))
                .hoverEvent(HoverEvent.showText(Component.text("打开验证页面")));

        return Component.text("本服务器需要完成 Microsoft 验证后才能活动。", NamedTextColor.YELLOW)
                .append(Component.newline())
                .append(link)
                .append(Component.newline())
                .append(Component.text("按 T 打开聊天，然后点击上方链接。", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("此验证仅用于拦截无法通过 Microsoft/Xbox 登录链路的账号，不会保存你的密码。", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("链接将在 " + expiresMinutes() + " 分钟后过期。", NamedTextColor.GRAY));
    }

    private long expiresMinutes() {
        long expiresMinutes = Math.max(1, challengeTtl.toMinutes());
        return expiresMinutes;
    }

    private void warnIfUnsafe(JavaPlugin plugin) {
        if (DEFAULT_SECRET.equals(sharedSecret) || sharedSecret.length() < 32) {
            plugin.getLogger().warning("shared-secret 缺失或过短。正式服请使用足够长的随机密钥。");
        }
        if (microsoftOAuthEnabled && ("CHANGE_ME_CLIENT_ID".equals(microsoftClientId) || microsoftRedirectUri.contains("verify.example.com"))) {
            plugin.getLogger().warning("microsoft-oauth 已启用，但 client-id 或 redirect-uri 仍是占位值。");
        }
    }
}
