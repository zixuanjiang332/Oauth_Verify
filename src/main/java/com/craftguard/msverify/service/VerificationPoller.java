package com.craftguard.msverify.service;

import com.craftguard.msverify.ConfigValues;
import com.craftguard.msverify.storage.PendingVerification;
import com.craftguard.msverify.storage.VerifiedPlayer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VerificationPoller {
    private final JavaPlugin plugin;
    private final VerificationService verificationService;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    private BukkitTask task;

    public VerificationPoller(JavaPlugin plugin, VerificationService verificationService) {
        this.plugin = plugin;
        this.verificationService = verificationService;
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
            List<PendingVerification> pendingItems = verificationService.listPendingVerifications();
            int verifiedCount = 0;
            for (PendingVerification pending : pendingItems) {
                Optional<VerifiedPlayer> verified = verificationService.checkPendingVerification(pending);
                if (verified.isPresent()) {
                    verifiedCount++;
                }
            }
            if (verifiedCount > 0) {
                plugin.getLogger().info("已应用 " + verifiedCount + " 条完成验证记录。");
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
}
