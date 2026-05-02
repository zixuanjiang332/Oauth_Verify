package com.craftguard.msverify.service;

import com.craftguard.msverify.ConfigValues;
import com.craftguard.msverify.security.ChallengeTokenService;
import com.craftguard.msverify.security.IssuedChallenge;
import com.craftguard.msverify.storage.BatchApplyResult;
import com.craftguard.msverify.storage.CompletedVerification;
import com.craftguard.msverify.storage.VerificationRepository;
import com.craftguard.msverify.storage.VerifiedPlayer;
import com.craftguard.msverify.util.UuidUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VerificationService {
    private final JavaPlugin plugin;
    private final VerificationRepository repository;
    private final Map<UUID, VerifiedPlayer> verifiedCache = new ConcurrentHashMap<>();

    private volatile ConfigValues config;
    private volatile ChallengeTokenService challengeTokenService;

    public VerificationService(JavaPlugin plugin, VerificationRepository repository, ConfigValues config) {
        this.plugin = plugin;
        this.repository = repository;
        reload(config);
    }

    public void reload(ConfigValues nextConfig) {
        this.config = nextConfig;
        this.challengeTokenService = new ChallengeTokenService(nextConfig);
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

    public IssuedChallenge issueChallenge(UUID minecraftUuid, String minecraftName) throws SQLException {
        IssuedChallenge challenge = challengeTokenService.issue(minecraftUuid, minecraftName);
        repository.saveChallenge(challenge);
        return challenge;
    }

    public String currentCursor() throws SQLException {
        return repository.getCursor(config.serverId());
    }

    public BatchApplyResult applyCompleted(List<CompletedVerification> items, String nextCursor) throws SQLException {
        BatchApplyResult result = repository.applyCompletedBatch(config.serverId(), items, nextCursor, System.currentTimeMillis());
        for (VerifiedPlayer player : result.verifiedPlayers()) {
            verifiedCache.put(player.minecraftUuid(), player);
        }
        return result;
    }

    public int cleanupExpiredChallenges() throws SQLException {
        return repository.deleteExpiredChallenges(System.currentTimeMillis());
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
