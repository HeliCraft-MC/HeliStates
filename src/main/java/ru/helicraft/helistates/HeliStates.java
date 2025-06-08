package ru.helicraft.helistates;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import ru.helicraft.helistates.bluemap.BlueMapRegionLayer; // ← new
import ru.helicraft.helistates.command.HeliCommand;
import ru.helicraft.helistates.database.DatabaseManager;
import ru.helicraft.helistates.region.RegionManager;
import ru.helicraft.states.regions.SimpleRegionGenerator;

import java.sql.SQLException;

public final class HeliStates extends JavaPlugin {

    @Getter
    private static HeliStates instance;

    public static boolean DEBUG;

    private DatabaseManager databaseManager;
    private RegionManager   regionManager;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        if (!getConfig().getBoolean("enabled")) return;

        DEBUG = getConfig().getBoolean("debug");

        /* DB */
        databaseManager = new DatabaseManager();
        try {
            databaseManager.connect(getConfig().getConfigurationSection("database"));
        } catch (SQLException e) {
            getLogger().warning("Failed to connect to database: " + e.getMessage());
        }

        /* Region-генератор */
        SimpleRegionGenerator.Config cfg = new SimpleRegionGenerator.Config();
        cfg.radiusBlocks   = getConfig().getInt("regions.worldRadius",    5000);
        int stepCfg = getConfig().getInt("regions.sampleSpacing",
                getConfig().getInt("regions.sampleStep", 32));
        cfg.sampleSpacing  = stepCfg;
        cfg.MIN_CELLS      = getConfig().getInt("regions.minCells",        300);
        cfg.MAX_CELLS      = getConfig().getInt("regions.maxCells",       1500);
        cfg.slopeExtra     = getConfig().getInt("regions.slopeExtra",        2);
        cfg.maxParallelSamples = getConfig().getInt("regions.maxParallelSamples", 0);
        cfg.chunkLoadTimeout = getConfig().getInt("regions.chunkTimeout", 40);



        regionManager = new RegionManager(databaseManager, cfg);
        new BlueMapRegionLayer(regionManager); // ← интеграция с картой

        /* Auto-load первого мира */
        if (Bukkit.getWorlds().isEmpty()) {
            getLogger().warning("No worlds loaded – plugin idle.");
            return;
        }
        World world = Bukkit.getWorlds().get(0);
        regionManager.loadRegions(world);

        getCommand("helistates").setExecutor(new HeliCommand(regionManager));
        getCommand("helistates").setTabCompleter(new HeliCommand(regionManager));

        getLogger().info("HeliStates enabled");
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        if (regionManager != null) regionManager.shutdown();
        if (databaseManager != null) databaseManager.disconnect();
        getLogger().info("HeliStates disabled");
    }
}
