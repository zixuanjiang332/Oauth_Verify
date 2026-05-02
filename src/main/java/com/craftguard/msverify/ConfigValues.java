package com.craftguard.msverify;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

public record ConfigValues(
        String serverId,
        String verificationGenerateEndpoint,
        String verificationResultEndpoint,
        String apiKey,
        String databaseFileName,
        Duration challengeTtl,
        boolean pollerEnabled,
        Duration pollInterval,
        Duration requestTimeout,
        String reloadMessage,
        String noPermissionMessage,
        String usageMessage
) {
    private static final String DEFAULT_API_KEY = "local-test-api-key-change-me";

    public static ConfigValues from(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        ConfigValues values = new ConfigValues(
                config.getString("server-id", "survival-1"),
                config.getString("verification-generate-endpoint", "https://www.chaos-smp.cn/verify/gen"),
                config.getString("verification-result-endpoint", "https://www.chaos-smp.cn/verify/get"),
                config.getString("api-key", DEFAULT_API_KEY),
                config.getString("database-file", "msverify.db"),
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
                verificationGenerateEndpoint,
                verificationResultEndpoint,
                apiKey,
                databaseFileName,
                challengeTtl,
                pollerEnabled,
                pollInterval,
                requestTimeout,
                reloadMessage,
                noPermissionMessage,
                usageMessage
        );
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

    public String sharedSecret() {
        return apiKey;
    }

    private void warnIfUnsafe(JavaPlugin plugin) {
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("CHANGE_ME") || apiKey.length() < 16) {
            plugin.getLogger().warning("api-key 缺失或过短。正式服请使用足够长的随机密钥。");
        }
    }
}
