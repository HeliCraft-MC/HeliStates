package ru.helicraft.helistates.region;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.util.Vector;
import ru.helicraft.helistates.database.DatabaseManager;
import ru.helicraft.states.regions.RegionGenerator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class RegionManager {

    private final DatabaseManager databaseManager;
    private final RegionGenerator generator;
    private List<RegionGenerator.Region> regions = new ArrayList<>();

    public RegionManager(DatabaseManager databaseManager, RegionGenerator.Config cfg) {
        this.databaseManager = databaseManager;
        this.generator = new RegionGenerator(cfg);
    }

    public List<RegionGenerator.Region> getRegions() {
        return regions;
    }

    /**
     * Starts asynchronous generation and saving of regions.
     */
    public void generateAndSave(World world, Runnable callback) {
        generator.generate(world, new RegionGenerator.Callback() {
            @Override
            public void onFinished(List<RegionGenerator.Region> result) {
                regions = result;
                saveRegions(world);
                if (callback != null) callback.run();
            }

            @Override
            public void onError(Throwable t) {
                HeliStates.getInstance().getLogger().warning("Region generation failed: " + t.getMessage());
            }
        });
    }

    private void saveRegions(World world) {
        try {
            Connection conn = databaseManager.getConnection();
            if (conn == null) return;
            PreparedStatement ps = conn.prepareStatement(
                    "REPLACE INTO regions (id, world, min_x, min_z, max_x, max_z) VALUES (?,?,?,?,?,?)");
            for (RegionGenerator.Region reg : regions) {
                int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                for (Vector v : reg.outline()) {
                    int x = v.getBlockX();
                    int z = v.getBlockZ();
                    if (x < minX) minX = x;
                    if (z < minZ) minZ = z;
                    if (x > maxX) maxX = x;
                    if (z > maxZ) maxZ = z;
                }
                ps.setString(1, reg.id().toString());
                ps.setString(2, world.getName());
                ps.setInt(3, minX);
                ps.setInt(4, minZ);
                ps.setInt(5, maxX);
                ps.setInt(6, maxZ);
                ps.addBatch();
            }
            ps.executeBatch();
            ps.close();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("Failed to save regions: " + e.getMessage());
        }
    }
}
