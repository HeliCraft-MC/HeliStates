package ru.helicraft.helistates;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import ru.helicraft.helistates.database.DatabaseManager;
import ru.helicraft.helistates.region.RegionManager;

import java.sql.SQLException;

public final class HeliStates extends JavaPlugin {
    @Getter
    private static HeliStates instance;

    private DatabaseManager databaseManager;
    private RegionManager regionManager;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        if(!getConfig().getBoolean("enabled")) return;

        databaseManager = new DatabaseManager();
        try {
            databaseManager.connect(getConfig().getString("mysql.connectionString"));
        } catch (SQLException e) {
            getLogger().warning("Failed to connect to database: " + e.getMessage());
        }

        regionManager = new RegionManager(databaseManager);

        World world = Bukkit.getWorlds().get(0);
        int radius = getConfig().getInt("regions.worldRadius", 5000);
        regionManager.generateRegions(world, radius);
        regionManager.saveRegions(world);

        getLogger().info("Enabled");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.disconnect();
        }
        getLogger().info("Disabled");
    }
}
