package com.craftguard.msverify.command;

import com.craftguard.msverify.MsVerifyPlugin;
import com.craftguard.msverify.service.VerificationService;
import com.craftguard.msverify.storage.VerifiedPlayer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class MsVerifyCommand implements CommandExecutor, TabCompleter {
    private final MsVerifyPlugin plugin;
    private final VerificationService verificationService;

    public MsVerifyCommand(MsVerifyPlugin plugin, VerificationService verificationService) {
        this.plugin = plugin;
        this.verificationService = verificationService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("msverify.admin")) {
            sender.sendMessage(plugin.currentConfig().noPermissionMessage());
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(plugin.currentConfig().usageMessage());
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        switch (subCommand) {
            case "reload" -> {
                plugin.reloadMsVerify();
                sender.sendMessage(plugin.currentConfig().reloadMessage());
                return true;
            }
            case "status" -> {
                if (args.length != 2) {
                    sender.sendMessage("用法：/msverify status <玩家名|UUID>");
                    return true;
                }
                runStatus(sender, args[1]);
                return true;
            }
            case "unverify" -> {
                if (args.length != 2) {
                    sender.sendMessage("用法：/msverify unverify <玩家名|UUID>");
                    return true;
                }
                runUnverify(sender, args[1]);
                return true;
            }
            default -> {
                sender.sendMessage(plugin.currentConfig().usageMessage());
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("msverify.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("reload", "status", "unverify"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("status") || args[0].equalsIgnoreCase("unverify"))) {
            List<String> names = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .toList();
            return filter(names, args[1]);
        }
        return List.of();
    }

    private void runStatus(CommandSender sender, String playerOrUuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<VerifiedPlayer> verifiedPlayer = verificationService.findVerified(playerOrUuid);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (verifiedPlayer.isEmpty()) {
                        sender.sendMessage("没有找到 " + playerOrUuid + " 的验证记录。");
                        return;
                    }
                    VerifiedPlayer player = verifiedPlayer.get();
                    sender.sendMessage("已验证玩家：");
                    sender.sendMessage("  UUID: " + player.minecraftUuid());
                    sender.sendMessage("  玩家名: " + player.minecraftName());
                    sender.sendMessage("  XUID: " + player.xuid());
                    sender.sendMessage("  验证时间: " + Instant.ofEpochMilli(player.verifiedAt()));
                });
            } catch (SQLException | RuntimeException exception) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage("查询验证状态失败：" + exception.getMessage())
                );
            }
        });
    }

    private void runUnverify(CommandSender sender, String playerOrUuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean removed = verificationService.unverify(playerOrUuid);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (removed) {
                        sender.sendMessage("已移除 " + playerOrUuid + " 的验证记录。");
                    } else {
                        sender.sendMessage("没有找到 " + playerOrUuid + " 的验证记录。");
                    }
                });
            } catch (SQLException | RuntimeException exception) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage("移除验证记录失败：" + exception.getMessage())
                );
            }
        });
    }

    private List<String> filter(List<String> values, String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                matches.add(value);
            }
        }
        return matches;
    }
}
