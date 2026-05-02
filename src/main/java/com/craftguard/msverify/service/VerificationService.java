package com.craftguard.msverify.service;

import com.craftguard.msverify.ConfigValues;
import com.craftguard.msverify.storage.PendingVerification;
import com.craftguard.msverify.storage.VerificationRepository;
import com.craftguard.msverify.storage.VerifiedPlayer;
import com.craftguard.msverify.util.UuidUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VerificationService {
    private final JavaPlugin plugin;
    private final VerificationRepository repository;
    private final VerificationApiClient apiClient;
    private final Map<UUID, VerifiedPlayer> verifiedCache = new ConcurrentHashMap<>();

    private volatile ConfigValues config;

    public VerificationService(JavaPlugin plugin, VerificationRepository repository, ConfigValues config) {
        this.plugin = plugin;
        this.repository = repository;
        this.apiClient = new VerificationApiClient(plugin);
        reload(config);
    }

    public void reload(ConfigValues nextConfig) {
        this.config = nextConfig;
    }

    public ConfigValues config() {
        return config;
    }

    public void loadVerifiedCache() throws SQLException {
        verifiedCache.clear();
        verifiedCache.putAll(repository.loadVerifiedPlayers());
        plugin.getLogger().info("已加载 " + verifiedCache.size() + " 个已验证玩家。");
    }

    public boolean isVerified(UUID uuid) {
        return verifiedCache.containsKey(uuid);
    }

    public GeneratedVerification createVerification(UUID minecraftUuid, String minecraftName)
            throws SQLException, IOException, InterruptedException {
        long now = System.currentTimeMillis();
        Optional<PendingVerification> reusable = repository.findReusablePendingVerification(minecraftUuid, now);
        if (reusable.isPresent()) {
            PendingVerification pending = reusable.get();
            return new GeneratedVerification(pending.token(), pending.link(), pending.expiresAt());
        }

        GeneratedVerification generated = apiClient.generate(config, minecraftUuid, minecraftName);
        repository.savePendingVerification(new PendingVerification(
                generated.token(),
                minecraftUuid,
                minecraftName,
                generated.link(),
                Math.min(generated.expiresAt(), now + config.challengeTtl().toMillis()),
                now
        ));
        return generated;
    }

    public List<PendingVerification> listPendingVerifications() throws SQLException {
        return repository.listPendingVerifications(System.currentTimeMillis());
    }

    public Optional<VerifiedPlayer> checkPendingVerification(PendingVerification pending)
            throws IOException, InterruptedException, SQLException {
        RemoteVerificationResult result = apiClient.fetchResult(config, pending.token());
        if (result.pending()) {
            return Optional.empty();
        }
        if (result.terminalFailure()) {
            repository.deletePendingVerification(pending.token());
            plugin.getLogger().warning("验证 token 已被远端标记为失败：" + pending.token() + " " + result.message());
            return Optional.empty();
        }
        if (!result.verified()) {
            return Optional.empty();
        }

        Optional<VerifiedPlayer> verified = repository.applyRemoteVerification(result, System.currentTimeMillis());
        verified.ifPresent(player -> verifiedCache.put(player.minecraftUuid(), player));
        if (verified.isEmpty()) {
            plugin.getLogger().warning("远端验证结果与本地 token 映射不匹配，token=" + pending.token());
        }
        return verified;
    }

    public int cleanupExpiredChallenges() throws SQLException {
        return repository.deleteExpiredPendingVerifications(System.currentTimeMillis());
    }

    public Optional<VerifiedPlayer> findVerified(String playerOrUuid) throws SQLException {
        Optional<UUID> parsedUuid = UuidUtil.parseUuid(playerOrUuid);
        if (parsedUuid.isPresent()) {
            VerifiedPlayer cached = verifiedCache.get(parsedUuid.get());
            if (cached != null) {
                return Optional.of(cached);
            }
            return repository.findVerified(parsedUuid.get());
        }

        Optional<VerifiedPlayer> cachedByName = verifiedCache.values().stream()
                .filter(player -> player.minecraftName().equalsIgnoreCase(playerOrUuid))
                .findFirst();
        if (cachedByName.isPresent()) {
            return cachedByName;
        }
        return repository.findVerifiedByName(playerOrUuid);
    }

    public boolean unverify(String playerOrUuid) throws SQLException {
        Optional<VerifiedPlayer> verifiedPlayer = findVerified(playerOrUuid);
        if (verifiedPlayer.isEmpty()) {
            Optional<UUID> parsedUuid = UuidUtil.parseUuid(playerOrUuid);
            if (parsedUuid.isEmpty()) {
                return false;
            }
            boolean removed = repository.removeVerified(parsedUuid.get());
            verifiedCache.remove(parsedUuid.get());
            return removed;
        }

        UUID uuid = verifiedPlayer.get().minecraftUuid();
        boolean removed = repository.removeVerified(uuid);
        verifiedCache.remove(uuid);
        return removed;
    }

}
