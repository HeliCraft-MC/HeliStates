package ru.helicraft.helistates.region;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.util.Vector;
import ru.helicraft.helistates.database.DatabaseManager;
import ru.helicraft.helistates.HeliStates;
import ru.helicraft.states.regions.RegionGenerator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.CompletableFuture;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
     * Loads regions for the given world from the database.
     */
    public void loadRegions(World world) {
        List<RegionGenerator.Region> list = new ArrayList<>();
        try {
            Connection conn = databaseManager.getConnection();
            if (conn == null) return;
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, biome, area, outline FROM regions WHERE world=?");
            ps.setString(1, world.getName());
            var rs = ps.executeQuery();
            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString("id"));
                String biomeName = rs.getString("biome");
                int area = rs.getInt("area");
                String outlineStr = rs.getString("outline");
                List<Vector> outline = parseOutline(outlineStr);
                Biome biome = Biome.valueOf(biomeName);
                list.add(new RegionGenerator.Region(id, outline, area, biome));
            }
            rs.close();
            ps.close();
            regions = list;
        } catch (SQLException | IllegalArgumentException e) {
            Bukkit.getLogger().warning("Failed to load regions: " + e.getMessage());
        }
    }

    /**
     * Starts asynchronous generation and saving of regions.
     */
    public void generateAndSave(World world, Runnable callback) {
        generator.generate(world, new RegionGenerator.Callback() {
            @Override
            public void onFinished(List<RegionGenerator.Region> result) {
                regions = result;
                CompletableFuture.runAsync(() -> saveRegions(world))
                        .whenComplete((v, t) -> {
                            if (t != null) {
                                Bukkit.getLogger().warning("Failed to save regions: " + t.getMessage());
                            }
                            if (callback != null) Bukkit.getScheduler().runTask(HeliStates.getInstance(), callback);
                        });
            }

            @Override
            public void onError(Throwable t) {
                    "REPLACE INTO regions (id, world, biome, area, outline, min_x, min_z, max_x, max_z) VALUES (?,?,?,?,?,?,?,?,?)");
                ps.setString(3, reg.dominantBiome().toString());
                ps.setInt(4, reg.areaBlocks());
                ps.setString(5, outlineToString(reg.outline()));
                ps.setInt(6, minX);
                ps.setInt(7, minZ);
                ps.setInt(8, maxX);
                ps.setInt(9, maxZ);

    private static String outlineToString(List<Vector> outline) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < outline.size(); i++) {
            Vector v = outline.get(i);
            sb.append('[').append(v.getBlockX()).append(',').append(v.getBlockZ()).append(']');
            if (i < outline.size() - 1) sb.append(',');
        }
        sb.append(']');
        return sb.toString();
    }

    private static List<Vector> parseOutline(String data) {
        List<Vector> out = new ArrayList<>();
        if (data == null || data.length() < 2) return out;
        String content = data.substring(1, data.length() - 1);
        if (content.isEmpty()) return out;
        String[] pairs = content.split("\\],\\[");
        for (String p : pairs) {
            String cleaned = p.replace("[", "").replace("]", "");
            String[] parts = cleaned.split(",");
            if (parts.length != 2) continue;
            try {
                int x = Integer.parseInt(parts[0].trim());
                int z = Integer.parseInt(parts[1].trim());
                out.add(new Vector(x, 0, z));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
    private void saveRegions(World world) {
        try {
            Connection conn = databaseManager.getConnection();
            if (conn == null) {
                Bukkit.getLogger().warning("Failed to save regions: Database connection is null in saveRegions method.");
                return;
            }
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
