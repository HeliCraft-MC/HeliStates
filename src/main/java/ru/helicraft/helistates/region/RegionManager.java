package ru.helicraft.helistates.region;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.util.BoundingBox;
import ru.helicraft.helistates.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

public class RegionManager {
    private final List<Region> regions = new ArrayList<>();
    private final DatabaseManager databaseManager;

    public RegionManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public List<Region> getRegions() {
        return regions;
    }

    public void generateRegions(World world, int radius) {
        // Very simplified placeholder algorithm.
        // We iterate over chunks within radius and group them by biome,
        // splitting when height differences are too great.

        Set<String> oceanBiomes = new HashSet<>();
        for (Biome biome : Biome.values()) {
            String name = biome.toString().toLowerCase(Locale.ROOT);
            if (name.contains("ocean")) {
                oceanBiomes.add(name);
            }
        }

        int chunkRadius = radius >> 4;
        boolean[][] visited = new boolean[chunkRadius * 2 + 1][chunkRadius * 2 + 1];
        int center = chunkRadius;

        for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
            for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                if (visited[cx + center][cz + center]) continue;

                Chunk start = world.getChunkAt(cx, cz);
                Biome startBiome = start.getBlock(8, world.getMinHeight(), 8).getBiome();
                boolean startOcean = oceanBiomes.contains(startBiome.toString().toLowerCase(Locale.ROOT));

                BoundingBox box = chunkBox(world, start);
                Queue<Chunk> queue = new ArrayDeque<>();
                queue.add(start);
                visited[cx + center][cz + center] = true;

                while (!queue.isEmpty()) {
                    Chunk c = queue.poll();
                    box.union(chunkBox(world, c));

                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (Math.abs(dx) + Math.abs(dz) != 1) continue;
                            int nx = c.getX() + dx;
                            int nz = c.getZ() + dz;
                            if (Math.abs(nx) > chunkRadius || Math.abs(nz) > chunkRadius) continue;
                            if (visited[nx + center][nz + center]) continue;

                            Chunk neighbor = world.getChunkAt(nx, nz);
                            Biome nBiome = neighbor.getBlock(8, world.getMinHeight(), 8).getBiome();
                            boolean nOcean = oceanBiomes.contains(nBiome.toString().toLowerCase(Locale.ROOT));

                            // stop at ocean/land boundary
                            if (startOcean != nOcean) continue;

                            int height = neighbor.getBlock(8, world.getHighestBlockYAt(neighbor.getBlock(8, 0, 8).getLocation()) - 1, 8).getY();
                            int startHeight = start.getBlock(8, world.getHighestBlockYAt(start.getBlock(8,0,8).getLocation()) -1,8).getY();
                            if (Math.abs(height - startHeight) > 20) continue; // ridge/valley boundary

                            visited[nx + center][nz + center] = true;
                            queue.add(neighbor);
                        }
                    }
                }

                Region region = new Region(UUID.randomUUID(), box, startOcean);
                regions.add(region);
            }
        }
    }

    public void saveRegions(World world) {
        try {
            Connection conn = databaseManager.getConnection();
            if (conn == null) return;
            PreparedStatement ps = conn.prepareStatement(
                    "REPLACE INTO regions (id, world, min_x, min_z, max_x, max_z) VALUES (?,?,?,?,?,?)");
            for (Region region : regions) {
                ps.setString(1, region.getId().toString());
                ps.setString(2, world.getName());
                ps.setInt(3, (int) region.getBox().getMinX());
                ps.setInt(4, (int) region.getBox().getMinZ());
                ps.setInt(5, (int) region.getBox().getMaxX());
                ps.setInt(6, (int) region.getBox().getMaxZ());
                ps.addBatch();
            }
            ps.executeBatch();
            ps.close();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("Failed to save regions: " + e.getMessage());
        }
    }

    private BoundingBox chunkBox(World world, Chunk chunk) {
        int minX = chunk.getX() << 4;
        int minZ = chunk.getZ() << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        return new BoundingBox(minX, world.getMinHeight(), minZ, maxX, world.getMaxHeight(), maxZ);
    }
}
