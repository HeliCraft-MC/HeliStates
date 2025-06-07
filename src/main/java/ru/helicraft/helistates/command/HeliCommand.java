package ru.helicraft.helistates.command;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import ru.helicraft.helistates.region.RegionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HeliCommand implements CommandExecutor, TabCompleter {

    private final RegionManager regionManager;

    public HeliCommand(RegionManager manager) {
        this.regionManager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("generate")) {
            sender.sendMessage("Starting region generation...");
            World world = Bukkit.getWorlds().get(0);
            regionManager.generateAndSave(world, () -> sender.sendMessage("Region generation finished."));
            return true;
        }
        sender.sendMessage("/" + label + " generate");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0], Collections.singletonList("generate"), completions);
            return completions;
        }
        return Collections.emptyList();
    }
}
