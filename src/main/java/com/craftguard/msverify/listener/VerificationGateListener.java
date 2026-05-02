package com.craftguard.msverify.listener;

import com.craftguard.msverify.security.IssuedChallenge;
import com.craftguard.msverify.service.VerificationService;
import com.craftguard.msverify.ConfigValues;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VerificationGateListener implements Listener {
    private final JavaPlugin plugin;
    private final VerificationService verificationService;
    private final Set<UUID> gatedPlayers = ConcurrentHashMap.newKeySet();

    public VerificationGateListener(JavaPlugin plugin, VerificationService verificationService) {
        this.plugin = plugin;
        this.verificationService = verificationService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (verificationService.isVerified(player.getUniqueId())) {
            gatedPlayers.remove(player.getUniqueId());
            return;
        }

        gatedPlayers.add(player.getUniqueId());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> sendVerificationLink(player.getUniqueId(), player.getName()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        gatedPlayers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isGated(event.getPlayer())) {
            return;
        }
        if (event.hasChangedBlock()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isGated(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text("请先完成 Microsoft 验证后再使用命令。"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (isGated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (isGated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isGated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isGated(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isGated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isGated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isGated(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isGated(player)) {
            event.setCancelled(true);
        }
    }

    private boolean isGated(Player player) {
        UUID uuid = player.getUniqueId();
        if (!gatedPlayers.contains(uuid)) {
            return false;
        }
        if (verificationService.isVerified(uuid)) {
            gatedPlayers.remove(uuid);
            return false;
        }
        return true;
    }

    private void sendVerificationLink(UUID uuid, String name) {
        try {
            IssuedChallenge challenge = verificationService.issueChallenge(uuid, name);
            ConfigValues config = verificationService.config();
            String verificationUrl = config.buildVerificationUrl(challenge.token());
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline() || !gatedPlayers.contains(uuid)) {
                    return;
                }
                player.sendMessage(config.renderVerificationChatComponent(verificationUrl));
            });
        } catch (SQLException exception) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    player.kick(Component.text("验证存储暂时不可用，请稍后重试。"));
                }
            });
        }
    }
}
