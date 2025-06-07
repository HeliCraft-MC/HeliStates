package ru.helicraft.helistates.command;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.util.StringUtil;
import ru.helicraft.helistates.region.RegionManager;

import java.util.*;

public class HeliCommand implements CommandExecutor, TabCompleter {

    private final RegionManager rm;
    public HeliCommand(RegionManager rm){ this.rm=rm; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd,
                             String label, String[] args){

        if(args.length>0 && args[0].equalsIgnoreCase("generate")){
            sender.sendMessage("§7[HeliStates] §a⏳ Генерация началась…");
            World world=Bukkit.getWorlds().get(0);

            rm.generateAndSave(world,
                    p-> sender.sendMessage("§7[HeliStates] §e"+p+"%"),
                    ()->sender.sendMessage("§7[HeliStates] §a✔ Готово!")
            );
            return true;
        }
        sender.sendMessage("/"+label+" generate");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){
        if(args.length==1){
            List<String> list=new ArrayList<>();
            StringUtil.copyPartialMatches(args[0], List.of("generate"), list);
            return list;
        }
        return Collections.emptyList();
    }
}
