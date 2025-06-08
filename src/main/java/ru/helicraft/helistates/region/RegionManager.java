package ru.helicraft.helistates.region;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import ru.helicraft.helistates.HeliStates;
import ru.helicraft.helistates.database.DatabaseManager;
import ru.helicraft.states.regions.RegionGenerator;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Загружает и генерирует регионы, сохраняет их в БД
 * и рассылает обновления слушателям.
 */
@SuppressWarnings("SqlResolve")
public class RegionManager {

    private static final Logger LOG = JavaPlugin.getPlugin(HeliStates.class).getLogger();

    /* ---------- SQL ---------- */
    private static final String SELECT_SQL =
            "SELECT id, biome, area, outline FROM regions WHERE world = ?";
    private static final String UPSERT_SQL =
            "REPLACE INTO regions " +
                    "(id, world, biome, area, outline, min_x, min_z, max_x, max_z) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)";

    /* ---------- Поля ---------- */
    private final DatabaseManager databaseManager;
    private final RegionGenerator generator;
    private final ExecutorService dbPool =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "HeliStates-DB"));

    private final List<Consumer<List<RegionGenerator.Region>>> listeners =
            new CopyOnWriteArrayList<>();

    @Getter
    private volatile List<RegionGenerator.Region> regions = Collections.emptyList();
    @Getter
    private volatile World world;

    /* ---------- ctor ---------- */
    public RegionManager(DatabaseManager db, RegionGenerator.Config cfg) {
        this.databaseManager = db;
        this.generator       = new RegionGenerator(cfg);
    }

    /* ---------- Подписка ---------- */
    public void addUpdateListener(Consumer<List<RegionGenerator.Region>> l) {
        listeners.add(l);
        l.accept(regions); // сразу отдаём текущее состояние
    }
    private void notifyListeners() {
        listeners.forEach(l -> l.accept(regions));
    }

    /* ---------- Загрузка ---------- */
    public void loadRegions(World world) {
        this.world = world;
        List<RegionGenerator.Region> list = new ArrayList<>();
        try (PreparedStatement ps =
                     databaseManager.getConnection().prepareStatement(SELECT_SQL)) {
            ps.setString(1, world.getName());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID  id   = UUID.fromString(rs.getString("id"));
                    Biome bio  = org.bukkit.Registry.BIOME.get(org.bukkit.NamespacedKey.minecraft(rs.getString("biome").toLowerCase()));
                    double area = rs.getDouble("area");
                    List<Vector> outline = parseOutline(rs.getString("outline"));
                    list.add(new RegionGenerator.Region(id, outline, area, bio));
                }
            }
            regions = list;
            notifyListeners();
        } catch (SQLException | IllegalArgumentException ex) {
            LOG.warning("Failed to load regions: " + ex.getMessage());
        }
    }

    /* ---------- Генерация ---------- */
    public void generateAndSave(World world, Consumer<Integer> onProgress, Runnable whenDone) {
        if (HeliStates.DEBUG)
            LOG.info("[DEBUG] starting generation for world " + world.getName());
        this.world = world;
        generator.generate(world, new RegionGenerator.Callback() {

            @Override public void onProgress(int p){
                if(onProgress!=null) onProgress.accept(p);
            }

            @Override
            public void onFinished(List<RegionGenerator.Region> result) {
                regions = result;
                CompletableFuture
                        .runAsync(() -> saveRegions(world), dbPool)
                        .whenComplete((v, err) ->
                                Bukkit.getScheduler().runTask(
                                        HeliStates.getInstance(), () -> {
                                            if (err != null)
                                                LOG.warning(
                                                        "Failed to save regions: " + err);
                                            notifyListeners();
                                            if (whenDone != null) whenDone.run();
                                        }));
            }

            @Override
            public void onError(Throwable t) {
                LOG.log(Level.SEVERE, "Region generation failed", t);
            }
        });
    }

    /* ---------- Сохранение ---------- */
    private void saveRegions(World world) {
        if (HeliStates.DEBUG)
            LOG.info("[DEBUG] saving regions to DB");
        Connection conn = databaseManager.getConnection();
        if (conn == null) {
            LOG.warning("DB connection is null; abort save.");
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
            conn.setAutoCommit(false);
            for (RegionGenerator.Region r : regions) {
                BoundingBox b = bbox(r.outline());
                ps.setString(1, r.id().toString());
                ps.setString(2, world.getName());
                ps.setString(3, r.dominantBiome().toString());
                ps.setDouble(4, r.areaBlocks());
                ps.setString(5, outlineToString(r.outline()));
                ps.setInt   (6, b.minX); ps.setInt(7, b.minZ);
                ps.setInt   (8, b.maxX); ps.setInt(9, b.maxZ);
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException ex) {
            try { conn.rollback(); } catch (SQLException ignore) {}
            LOG.warning("Failed to save regions: " + ex.getMessage());
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignore) {}
            if (HeliStates.DEBUG)
                LOG.info("[DEBUG] region save completed");
        }
    }

    /* ---------- Утилиты ---------- */
    private static String outlineToString(List<Vector> o) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < o.size(); i++) {
            Vector v = o.get(i);
            sb.append('[').append(v.getBlockX()).append(',').append(v.getBlockZ()).append(']');
            if (i < o.size() - 1) sb.append(',');
        }
        return sb.append(']').toString();
    }
    private static List<Vector> parseOutline(String s) {
        List<Vector> out = new ArrayList<>();
        if (s == null || s.length() < 4) return out;
        for (String p : s.substring(1, s.length() - 1).split("\\],\\[")) {
            String[] xy = p.replace("[", "").replace("]", "").split(",");
            if (xy.length != 2) continue;
            try {
                out.add(new Vector(Integer.parseInt(xy[0].trim()), 0,
                        Integer.parseInt(xy[1].trim())));
            } catch (NumberFormatException ignore) {}
        }
        return out;
    }
    private static BoundingBox bbox(List<Vector> pts) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Vector v : pts) {
            int x = v.getBlockX(), z = v.getBlockZ();
            if (x < minX) minX = x;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (z > maxZ) maxZ = z;
        }
        return new BoundingBox(minX, minZ, maxX, maxZ);
    }
    private record BoundingBox(int minX, int minZ, int maxX, int maxZ) {}

    /** Останавливает фоновые задачи и пул БД. */
    public void shutdown() {
        dbPool.shutdown();
        try {
            if (!dbPool.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warning("DB pool did not terminate in time");
                dbPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            dbPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
