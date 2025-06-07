package ru.helicraft.helistates;

import org.bukkit.plugin.java.JavaPlugin;

public final class HeliStates extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("Enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabled");
    }
}
