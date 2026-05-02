package com.craftguard.msverify;

import com.craftguard.msverify.command.MsVerifyCommand;
import com.craftguard.msverify.listener.VerificationGateListener;
import com.craftguard.msverify.service.VerificationPoller;
import com.craftguard.msverify.service.VerificationService;
import com.craftguard.msverify.storage.VerificationRepository;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class MsVerifyPlugin extends JavaPlugin {
    private ConfigValues configValues;
    private VerificationRepository repository;
    private VerificationService verificationService;
    private VerificationPoller verificationPoller;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        reloadConfig();
        configValues = ConfigValues.from(this);

        try {
            repository = new VerificationRepository(this, configValues.databaseFileName());
            repository.open();
            verificationService = new VerificationService(this, repository, configValues);
            verificationService.loadVerifiedCache();
        } catch (SQLException exception) {
            getLogger().severe("初始化 MsVerify 存储失败：" + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new VerificationGateListener(this, verificationService), this);

        MsVerifyCommand command = new MsVerifyCommand(this, verificationService);
        PluginCommand pluginCommand = getCommand("msverify");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        verificationPoller = new VerificationPoller(this, verificationService);
        verificationPoller.start();
        getLogger().info("MsVerify 已启用，server-id 为 " + configValues.serverId() + "。");
    }

    @Override
    public void onDisable() {
        if (verificationPoller != null) {
            verificationPoller.stop();
        }
        if (repository != null) {
            repository.close();
        }
    }

    public synchronized ConfigValues currentConfig() {
        return configValues;
    }

    public synchronized void reloadMsVerify() {
        reloadConfig();
        ConfigValues nextConfig = ConfigValues.from(this);
        if (!nextConfig.databaseFileName().equals(configValues.databaseFileName())) {
            getLogger().warning("配置中的 database-file 已变化，但切换存储路径需要完整重启服务器。");
            nextConfig = nextConfig.withDatabaseFileName(configValues.databaseFileName());
        }

        configValues = nextConfig;
        verificationService.reload(nextConfig);
        if (verificationPoller != null) {
            verificationPoller.restart();
        }
    }
}
