package ru.helicraft.helistates.region;

import org.bukkit.Bukkit;
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
    private static final int OCEAN_BUFFER = 50;

    public RegionManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public List<Region> getRegions() {
        return regions;
    }

    /**
     * Generate regions using a ridge/valley skeletonisation algorithm.
     * This scans the world at a fixed resolution, extracts skeleton lines
     * from the height map and then performs a flood fill between the
     * skeletons to obtain regions. Oceans that are further than
     * {@value #OCEAN_BUFFER} blocks from land are merged into a single
     * global ocean region.
     */
    public void generateRegions(World world, int radius) {

        Set<String> oceanBiomes = new HashSet<>();
        for (Biome biome : Biome.values()) {
            String name = biome.toString().toLowerCase(Locale.ROOT);
            if (name.contains("ocean")) {
                oceanBiomes.add(name);
            }
        }

        final int step = 4; // resolution in blocks
        int size = radius * 2 / step + 1;

        int[][] height = new int[size][size];
        boolean[][] ocean = new boolean[size][size];

        for (int x = -radius; x <= radius; x += step) {
            for (int z = -radius; z <= radius; z += step) {
                int ix = (x + radius) / step;
                int iz = (z + radius) / step;
                height[ix][iz] = world.getHighestBlockYAt(x, z);
                Biome biome = world.getBiome(x, world.getMinHeight(), z);
                ocean[ix][iz] = oceanBiomes.contains(biome.toString().toLowerCase(Locale.ROOT));
            }
        }

        boolean[][] ridge = skeletonize(height, true);
        boolean[][] valley = skeletonize(height, false);
        boolean[][] boundary = new boolean[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                boundary[i][j] = ridge[i][j] || valley[i][j];
            }
        }

        int[][] distToLand = distanceToLand(ocean, step);
        int[][] regionIds = new int[size][size];
        int nextId = 1;
        Map<Integer, BoundingBox> boxes = new HashMap<>();

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (regionIds[i][j] != 0) continue;
                if (boundary[i][j]) continue;

                if (ocean[i][j] && distToLand[i][j] > OCEAN_BUFFER) continue; // handled later as global ocean

                BoundingBox box = floodFill(i, j, regionIds, nextId, boundary, ocean, distToLand, step, world, radius);
                boxes.put(nextId, box);
                nextId++;
            }
        }

        // global ocean region
        BoundingBox oceanBox = null;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (ocean[i][j] && distToLand[i][j] > OCEAN_BUFFER) {
                    int wx = i * step - radius;
                    int wz = j * step - radius;
                    BoundingBox cb = new BoundingBox(wx, world.getMinHeight(), wz,
                            wx + step, world.getMaxHeight(), wz + step);
                    if (oceanBox == null) oceanBox = cb.clone();
                    else oceanBox.union(cb);
                }
            }
        }

        for (BoundingBox b : boxes.values()) {
            regions.add(new Region(UUID.randomUUID(), b, false));
        }
        if (oceanBox != null) {
            regions.add(new Region(UUID.randomUUID(), oceanBox, true));
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


    private boolean[][] skeletonize(int[][] height, boolean ridge) {
        int w = height.length;
        int h = height[0].length;
        boolean[][] mask = new boolean[w][h];
        int threshold = 3;
        for (int x = 1; x < w - 1; x++) {
            for (int z = 1; z < h - 1; z++) {
                int c = height[x][z];
                int n = height[x][z - 1];
                int s = height[x][z + 1];
                int wv = height[x - 1][z];
                int e = height[x + 1][z];
                if (ridge) {
                    if (c - n >= threshold && c - s >= threshold && c - wv >= threshold && c - e >= threshold) {
                        mask[x][z] = true;
                    }
                } else {
                    if (n - c >= threshold && s - c >= threshold && wv - c >= threshold && e - c >= threshold) {
                        mask[x][z] = true;
                    }
                }
            }
        }
        return thin(mask);
    }

    private boolean[][] thin(boolean[][] grid) {
        int w = grid.length;
        int h = grid[0].length;
        boolean changed;
        do {
            changed = false;
            java.util.List<int[]> del = new java.util.ArrayList<>();
            for (int x = 1; x < w - 1; x++) {
                for (int z = 1; z < h - 1; z++) {
                    if (!grid[x][z]) continue;
                    int[] n = neighbours(grid, x, z);
                    int count = n[8];
                    int t = n[9];
                    if (count >= 2 && count <= 6 && t == 1 && n[0] * n[2] * n[4] == 0 && n[2] * n[4] * n[6] == 0) {
                        del.add(new int[]{x, z});
                    }
                }
            }
            if (!del.isEmpty()) {
                changed = true;
                for (int[] p : del) grid[p[0]][p[1]] = false;
                del.clear();
            }
            for (int x = 1; x < w - 1; x++) {
                for (int z = 1; z < h - 1; z++) {
                    if (!grid[x][z]) continue;
                    int[] n = neighbours(grid, x, z);
                    int count = n[8];
                    int t = n[9];
                    if (count >= 2 && count <= 6 && t == 1 && n[0] * n[2] * n[6] == 0 && n[0] * n[4] * n[6] == 0) {
                        del.add(new int[]{x, z});
                    }
                }
            }
            if (!del.isEmpty()) {
                changed = true;
                for (int[] p : del) grid[p[0]][p[1]] = false;
            }
        } while (changed);
        return grid;
    }

    private int[] neighbours(boolean[][] grid, int x, int z) {
        int[] out = new int[10];
        int[] dx = {0,1,1,1,0,-1,-1,-1};
        int[] dz = {-1,-1,0,1,1,1,0,-1};
        int count = 0;
        int trans = 0;
        for (int i = 0; i < 8; i++) {
            boolean v = grid[x + dx[i]][z + dz[i]];
            out[i] = v ? 1 : 0;
            if (v) count++;
        }
        for (int i = 0; i < 7; i++) {
            if (out[i] == 0 && out[i+1] == 1) trans++;
        }
        if (out[7] == 0 && out[0] == 1) trans++;
        out[8] = count;
        out[9] = trans;
        return out;
    }

    private int[][] distanceToLand(boolean[][] ocean, int step) {
        int w = ocean.length;
        int h = ocean[0].length;
        int[][] dist = new int[w][h];
        for (int[] row : dist) java.util.Arrays.fill(row, Integer.MAX_VALUE);
        java.util.Deque<int[]> q = new java.util.ArrayDeque<>();
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                if (!ocean[i][j]) {
                    dist[i][j] = 0;
                    q.add(new int[]{i,j});
                }
            }
        }
        int[] dx = {1,-1,0,0};
        int[] dz = {0,0,1,-1};
        while (!q.isEmpty()) {
            int[] p = q.poll();
            for (int k = 0; k < 4; k++) {
                int nx = p[0] + dx[k];
                int nz = p[1] + dz[k];
                if (nx < 0 || nz < 0 || nx >= w || nz >= h) continue;
                if (dist[nx][nz] > dist[p[0]][p[1]] + step) {
                    dist[nx][nz] = dist[p[0]][p[1]] + step;
                    q.add(new int[]{nx,nz});
                }
            }
        }
        return dist;
    }

    private BoundingBox floodFill(int sx, int sz, int[][] ids, int id, boolean[][] boundary,
                                  boolean[][] ocean, int[][] dist, int step, World world, int radius) {
        java.util.Deque<int[]> q = new java.util.ArrayDeque<>();
        q.add(new int[]{sx,sz});
        ids[sx][sz] = id;
        BoundingBox box = null;
        int[] dx = {1,-1,0,0};
        int[] dz = {0,0,1,-1};
        while (!q.isEmpty()) {
            int[] p = q.poll();
            int x = p[0];
            int z = p[1];
            int wx = x * step - radius;
            int wz = z * step - radius;
            BoundingBox cb = new BoundingBox(wx, world.getMinHeight(), wz, wx + step, world.getMaxHeight(), wz + step);
            if (box == null) box = cb.clone();
            else box.union(cb);
            for (int i = 0; i < 4; i++) {
                int nx = x + dx[i];
                int nz = z + dz[i];
                if (nx < 0 || nz < 0 || nx >= ids.length || nz >= ids[0].length) continue;
                if (ids[nx][nz] != 0) continue;
                if (boundary[nx][nz]) continue;
                if (ocean[nx][nz] && dist[nx][nz] > OCEAN_BUFFER) continue;
                ids[nx][nz] = id;
                q.add(new int[]{nx,nz});
            }
        }
        return box;
    }
}
