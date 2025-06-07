package ru.helicraft.helistates;

import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;

public final class HeliStates extends JavaPlugin {
    @Getter
    private static HeliStates instance;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        if(!getConfig().getBoolean("enabled")) return;

        getLogger().info("Enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabled");
    }
}
