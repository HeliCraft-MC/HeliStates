package ru.helicraft.helistates;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import ru.helicraft.helistates.bluemap.BlueMapRegionLayer; // ← new
import ru.helicraft.helistates.command.HeliCommand;
import ru.helicraft.helistates.database.DatabaseManager;
import ru.helicraft.helistates.region.RegionManager;
import ru.helicraft.states.regions.RegionGenerator;

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
        RegionGenerator.Config cfg = new RegionGenerator.Config();
        cfg.radiusBlocks   = getConfig().getInt   ("regions.worldRadius",    5000);
        cfg.sampleSpacing  = getConfig().getInt   ("regions.sampleSpacing",     8);
        cfg.TERRAIN_WEIGHT = getConfig().getDouble("regions.terrainWeight",   1.0);
        cfg.BIOME_WEIGHT   = getConfig().getDouble("regions.biomeWeight",     0.4);
        cfg.MIN_CELLS      = getConfig().getInt   ("regions.minCells",         30);
        cfg.MAX_CELLS      = getConfig().getInt   ("regions.maxCells",       3000);
        cfg.COAST_BUFFER   = getConfig().getInt   ("regions.coastBuffer",      50);
        cfg.CHAIKIN_ITER   = getConfig().getInt   ("regions.chaikinIter",       3);

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
        if (databaseManager != null) databaseManager.disconnect();
        getLogger().info("HeliStates disabled");
    }
}
