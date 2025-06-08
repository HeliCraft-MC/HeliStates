/*
 *  RegionGenerator.java
 *  Полноценный генератор условных географических регионов
 *  по методу Ridge / Valley Skeletonisation + пост-обработка.
 *
 *  Под PaperMC 1.21.5, Java 21.
 *
 *  © 2025 Maksim & HeliCraftMC
 */
package ru.helicraft.states.regions;

import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.HeightMap;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import ru.helicraft.helistates.HeliStates;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Основной сервис. Использовать через {@link #generate(World, Callback)}.
 */
@SuppressWarnings("LanguageDetectionInspection")
public final class RegionGenerator {

    /* ---------- Публичные типы ---------- */

    /** Крайний результат: список регионов с готовыми сглаженными полигонами. */
    public record Region(UUID id,
                         List<Vector> outline,
                         int areaBlocks,
                         Biome dominantBiome) { }

    /** Callback, уведомляющий о завершении построения. */
    public interface Callback {
        void onFinished(List<Region> regions);
        default void onProgress(int percent) {}
        default void onError(@NotNull Throwable t) { t.printStackTrace(); }
    }

    /* ---------- Конфигурация (можно читать из .yml) ---------- */

    public static final class Config {
        /** Радиус обработки от spawn, в блоках. */
        public int radiusBlocks = 5_000;

        /** Шаг выборки height-map, блоков. Меньше = точнее, но медленнее. */
        public int sampleSpacing = 8;

        /** Вес рельефа в формуле дистанции. */
        public double TERRAIN_WEIGHT = 1.0;

        /** Вес различия биомов (меньше рельефа). */
        public double BIOME_WEIGHT = 0.4;

        /** Максимальное число одновременно загружаемых чанков. */
        public int maxParallelSamples = Runtime.getRuntime().availableProcessors() * 2;

        /** Мин. и макс. размер клеток в регионе до мержа / сплита */
        public int MIN_CELLS = 30;
        public int MAX_CELLS = 3_000;

        /** Ширина прибрежной зоны, блоков. */
        public int COAST_BUFFER = 50;

        /** Итерации Chaikin. */
        public int CHAIKIN_ITER = 3;
    }

    /* ---------- Внутренние структуры ---------- */

    private record Cell(int gx, int gz) {
        int toX(int spacing) { return gx * spacing; }
        int toZ(int spacing) { return gz * spacing; }
    }

    private static final class Grid {
        final int w, h, spacing;
        final double[][] height;
        final Biome[][] biome;
        final int[][] basin;          // id водосбора
        final boolean[][] ridge;      // true, если принадлежит хребту

        Grid(int w, int h, int spacing) {
            this.w = w;
            this.h = h;
            this.spacing = spacing;
            height = new double[w][h];
            biome  = new Biome[w][h];
            basin  = new int[w][h];
            ridge  = new boolean[w][h];
            for (int[] row : basin) Arrays.fill(row, -1);
        }
        boolean inside(int x, int z) { return x >= 0 && z >= 0 && x < w && z < h; }
    }

    /* ---------- Поля ---------- */

    private static final Logger LOG = JavaPlugin.getPlugin(HeliStates.class).getLogger();
    private final Config cfg;

    /* ---------- ctor ---------- */

    public RegionGenerator(Config cfg) { this.cfg = cfg; }

    /* ---------- Public API ---------- */

    /**
     * Стартует асинхронную генерацию регионов.
     */
    public void generate(@NotNull World world, @NotNull Callback cb) {
        new BukkitRunnable() {
            @Override public void run() { asyncGenerate(world, cb); }
        }.runTaskAsynchronously(Objects.requireNonNull(Bukkit.getPluginManager()
                                                             .getPlugin("HeliStates")));
    }

    /* ---------- Асинхронная часть ---------- */

    private void asyncGenerate(World w, Callback cb) {
        try {
            long t0 = System.currentTimeMillis();
            if (HeliStates.DEBUG) LOG.info("[DEBUG] starting generation for " + w.getName());

            Grid g = sampleWorld(w, cb);
            if (HeliStates.DEBUG) LOG.info("[DEBUG] building watersheds");
            buildWatersheds(g);
            if (HeliStates.DEBUG) LOG.info("[DEBUG] thinning skeleton");
            thinSkeleton(g);
            if (HeliStates.DEBUG) LOG.info("[DEBUG] post-processing");
            List<Region> regions = postProcess(g, w);

            long dt = System.currentTimeMillis() - t0;
            LOG.info("Region generation finished in " + dt + " ms; regions: " + regions.size());
            Bukkit.getScheduler().runTask(
                    Bukkit.getPluginManager().getPlugin("HeliStates"),
                    () -> cb.onFinished(Collections.unmodifiableList(regions)));
        } catch (Throwable t) {
            Bukkit.getScheduler().runTask(
                    Bukkit.getPluginManager().getPlugin("HeliStates"),
                    () -> cb.onError(t));
        }
    }

    /* ---------- 1. Сбор карты высот ---------- */

    private Grid sampleWorld(World w, Callback cb) {
        int r       = cfg.radiusBlocks;
        int spacing = cfg.sampleSpacing;
        int minX    = -r, minZ = -r, maxX = r, maxZ = r;
        int gw      = (maxX - minX) / spacing + 1;
        int gh      = (maxZ - minZ) / spacing + 1;
        Grid g      = new Grid(gw, gh, spacing);

        LOG.info("Sampling heightmap...");
        long tStart = System.currentTimeMillis();

        if (HeliStates.DEBUG) {
            Runtime rt = Runtime.getRuntime();
            LOG.info("[DEBUG] Grid size: " + gw + "×" + gh + " → " + ((long) gw * gh) + " cells");
            LOG.info("[DEBUG] Memory before sampling: free=" + rt.freeMemory()
                    + " total=" + rt.totalMemory()
                    + " max=" + rt.maxMemory());
        }

        AtomicInteger done = new AtomicInteger(0);
        int total = gw * gh;

        int step = Math.max(1, total / 20); // progress every ~5%

        int concurrency = cfg.maxParallelSamples > 0 ? cfg.maxParallelSamples : Runtime.getRuntime().availableProcessors() * 2;
        if (HeliStates.DEBUG) {
            LOG.info("[DEBUG] Concurrency limit (semaphore permits): " + concurrency);
        }
        Semaphore sem = new Semaphore(concurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>(total);

        for (int gx = 0; gx < gw; gx++) {
            for (int gz = 0; gz < gh; gz++) {
                try {
                    sem.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while acquiring semaphore", e);
                }

                int cellX = gx, cellZ = gz;
                int wx = minX + cellX * spacing;
                int wz = minZ + cellZ * spacing;

                CompletableFuture<Void> f = new CompletableFuture<>();
                w.getChunkAtAsync(wx >> 4, wz >> 4, true, true, chunk -> {
                    try {
                        ChunkSnapshot snap = chunk.getChunkSnapshot(true, true, true);
                        int y = snap.getHighestBlockYAt(wx & 15, wz & 15);
                        g.height[cellX][cellZ] = y;
                        g.biome[cellX][cellZ] = snap.getBiome(wx & 15, 64, wz & 15);
                    } catch (Throwable ex) {
                        if (HeliStates.DEBUG) {
                            LOG.log(Level.SEVERE, "[DEBUG] Error at cell (" + cellX + "," + cellZ + ")", ex);
                        }
                    } finally {
                        int curr = done.incrementAndGet();
                        if (curr % step == 0) {
                            int percent = curr * 100 / total;
                            if (HeliStates.DEBUG) {
                                LOG.info("[DEBUG] Sampled " + curr + " / " + total + " (" + percent + "%)");
                            } else {
                                Bukkit.getScheduler().runTask(
                                        HeliStates.getInstance(),
                                        () -> cb.onProgress(percent)
                                );
                            }
                        }
                        sem.release();
                        f.complete(null);
                    }
                });
                futures.add(f);
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Bukkit.getScheduler().runTask(HeliStates.getInstance(), () -> cb.onProgress(100));

        if (HeliStates.DEBUG) {
            long elapsed = System.currentTimeMillis() - tStart;
            Runtime rt = Runtime.getRuntime();
            LOG.info("[DEBUG] Sampling finished in " + elapsed + " ms");
            LOG.info("[DEBUG] Memory after sampling: free=" + rt.freeMemory()
                    + " total=" + rt.totalMemory());
        }

        return g;
    }


    /* ---------- 2. Watershed + Ridge пометка ---------- */

    private void buildWatersheds(Grid g) {
        int[] dx = {1, 0, -1, 0,  1, -1, -1, 1};
        int[] dz = {0, 1,  0,-1,  1,  1, -1,-1};

        record Node(int x, int z, double h) implements Comparable<Node> {
            @Override public int compareTo(Node o) { return Double.compare(h, o.h); }
        }
        PriorityQueue<Node> pq = new PriorityQueue<>();

        for (int x = 0; x < g.w; x++)
            for (int z = 0; z < g.h; z++)
                pq.offer(new Node(x, z, g.height[x][z]));

        int basinId = 0;
        while (!pq.isEmpty()) {
            Node n = pq.poll();
            if (g.basin[n.x][n.z] != -1) continue;

            /* Находим локальный минимум одним проходом. */
            int cx = n.x, cz = n.z;
            while (true) {
                double curH = g.height[cx][cz];
                int nx = cx, nz = cz;
                for (int i = 0; i < 8; i++) {
                    int tx = cx + dx[i], tz = cz + dz[i];
                    if (!g.inside(tx, tz)) continue;
                    if (g.height[tx][tz] + 1e-3 < curH) { // вниз по склону
                        curH = g.height[tx][tz];
                        nx = tx; nz = tz;
                    }
                }
                if (nx == cx && nz == cz) break; // минимум
                cx = nx; cz = nz;
            }

            int target = g.basin[cx][cz];
            if (target == -1) target = g.basin[cx][cz] = basinId++;

            /* Поднимаемся обратно и окрашиваем путь. */
            cx = n.x; cz = n.z;
            while (g.basin[cx][cz] == -1) {
                g.basin[cx][cz] = target;
                /* Отмечаем рёбра: если два соседа ведут к разным бассейнам — граница. */
                int diff = 0;
                for (int i = 0; i < 8; i++) {
                    int tx = cx + dx[i], tz = cz + dz[i];
                    if (!g.inside(tx, tz)) continue;
                    if (g.basin[tx][tz] != -1 && g.basin[tx][tz] != target) diff++;
                }
                if (diff > 1) g.ridge[cx][cz] = true;

                /* Двигаемся к минимуму так же, как и раньше. */
                double curH = g.height[cx][cz];
                int nx = cx, nz = cz;
                for (int i = 0; i < 8; i++) {
                    int tx = cx + dx[i], tz = cz + dz[i];
                    if (!g.inside(tx, tz)) continue;
                    if (g.height[tx][tz] + 1e-3 < curH) {
                        curH = g.height[tx][tz]; nx = tx; nz = tz;
                    }
                }
                if (nx == cx && nz == cz) break;
                cx = nx; cz = nz;
            }
        }
    }

    /* ---------- 3. Thinning (Zhang–Suen) ---------- */

    private void thinSkeleton(Grid g) {
        boolean changed;
        IntPredicate removeCond1 = p -> p == 2 || p == 3;
        // Zhang-Suen две фазы
        do {
            changed = thinningPass(g, 0, removeCond1);
            changed |= thinningPass(g, 1, removeCond1);
        } while (changed);
    }

    private boolean thinningPass(Grid g, int phase, IntPredicate cond) {
        List<Cell> toRemove = new ArrayList<>();
        int[] nx = {-1, -1, 0, 1, 1, 1, 0, -1};
        int[] nz = { 0,  1, 1, 1, 0,-1,-1, -1};
        for (int x = 1; x < g.w - 1; x++)
            for (int z = 1; z < g.h - 1; z++) if (g.ridge[x][z]) {
                int n = 0, t = 0;
                for (int i = 0; i < 8; i++) if (g.ridge[x + nx[i]][z + nz[i]]) n++;
                for (int i = 0; i < 8; i++)
                    if (!g.ridge[x + nx[i]][z + nz[i]] &&
                        g.ridge[x + nx[(i + 1) % 8]][z + nz[(i + 1) % 8]]) t++;
                boolean m1 = phase == 0
                        ? !g.ridge[x - 1][z] || !g.ridge[x][z + 1] || !g.ridge[x + 1][z]
                        : !g.ridge[x - 1][z] || !g.ridge[x][z - 1] || !g.ridge[x + 1][z];
                if (cond.test(n) && t == 1 && m1) toRemove.add(new Cell(x, z));
            }
        toRemove.forEach(c -> g.ridge[c.gx][c.gz] = false);
        return !toRemove.isEmpty();
    }

    /* ---------- 4. Формирование полигонов и пост-обработка ---------- */

    private List<Region> postProcess(Grid g, World w) {
        int wCell = g.w, hCell = g.h;
        int[][] region = new int[wCell][hCell];
        for (int[] row : region) Arrays.fill(row, -1);

        /* Поиск связных компонент внутри каждого бассейна,
           разделённых линиями ridge. */
        int id = 0;
        int[] dx = {1, -1, 0, 0}, dz = {0, 0, 1, -1};
        Map<Integer,List<Cell>> regCells = new HashMap<>();

        for (int x = 0; x < wCell; x++)
            for (int z = 0; z < hCell; z++) if (region[x][z] == -1 && !g.ridge[x][z]) {
                int bid = g.basin[x][z];
                Deque<Cell> q = new ArrayDeque<>();
                q.add(new Cell(x, z));
                List<Cell> list = new ArrayList<>();
                region[x][z] = id;
                while (!q.isEmpty()) {
                    Cell c = q.poll();
                    list.add(c);
                    for (int i = 0; i < 4; i++) {
                        int nx = c.gx + dx[i], nz = c.gz + dz[i];
                        if (!g.inside(nx, nz) || g.ridge[nx][nz]) continue;
                        if (g.basin[nx][nz] != bid || region[nx][nz] != -1) continue;
                        region[nx][nz] = id;
                        q.add(new Cell(nx, nz));
                    }
                }
                regCells.put(id, list);
                id++;
            }

        /* Слияние маленьких регионов */
        mergeSmall(regCells, region, g);

        /* Деление слишком больших */
        splitLarge(regCells, region, g);

        /* Преобразуем в Region-объекты с полигонами */
        return buildRegionObjects(regCells, g, w);
    }

    /* ---------- 4a. Слияние маленьких ---------- */

    private void mergeSmall(Map<Integer,List<Cell>> regCells,
                            int[][] region, Grid g) {
        boolean merged;
        do {
            merged = false;
            Iterator<Map.Entry<Integer,List<Cell>>> it = regCells.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getValue().size() >= cfg.MIN_CELLS) continue;
                /* Ищем соседа с максимальной общей границей */
                int id = e.getKey();
                Map<Integer,Integer> border = new HashMap<>();
                for (Cell c : e.getValue()) {
                    for (int dx1 = -1; dx1 <= 1; dx1++)
                        for (int dz1 = -1; dz1 <= 1; dz1++) {
                            int nx = c.gx + dx1, nz = c.gz + dz1;
                            if (!g.inside(nx, nz) || region[nx][nz] == id) continue;
                            border.merge(region[nx][nz],1,Integer::sum);
                        }
                }
                if (border.isEmpty()) continue;
                int tgt = Collections.max(border.entrySet(),
                        Map.Entry.comparingByValue()).getKey();
                /* Перекрашиваем */
                e.getValue().forEach(c -> region[c.gx][c.gz] = tgt);
                regCells.get(tgt).addAll(e.getValue());
                it.remove();
                merged = true;
                break;
            }
        } while (merged);
    }

    /* ---------- 4b. Деление крупных ---------- */

    private void splitLarge(Map<Integer,List<Cell>> regCells,
                            int[][] region, Grid g) {
        boolean split;
        do {
            split = false;
            for (var e : new ArrayList<>(regCells.entrySet())) {
                if (e.getValue().size() <= cfg.MAX_CELLS) continue;
                /* Делим по среднему хребту — упрощённо: отделяем половину ячеек */
                List<Cell> list = e.getValue();
                list.sort(Comparator.comparingInt(c -> c.gx + c.gz));
                List<Cell> sub = list.subList(0, list.size() / 2);
                int newId = regCells.keySet().stream().mapToInt(i->i).max().orElse(0) + 1;
                sub.forEach(c -> { region[c.gx][c.gz] = newId; });
                regCells.put(newId, new ArrayList<>(sub));
                list.removeAll(sub);
                split = true;
                break;
            }
        } while (split);
    }

    /* ---------- 4c. Создание Region-объектов с полигонами ---------- */

    private List<Region> buildRegionObjects(Map<Integer,List<Cell>> regCells,
                                            Grid g, World w) {
        List<Region> out = new ArrayList<>();
        for (var e : regCells.entrySet()) {
            List<Cell> cells = e.getValue();
            /* Считаем площадь и доминирующий биом */
            Map<Biome,Integer> cnt = new HashMap<>();
            int area = 0;
            for (Cell c : cells) {
                cnt.merge(g.biome[c.gx][c.gz], 1, Integer::sum);
                area += cfg.sampleSpacing * cfg.sampleSpacing;
            }
            Biome dom = Collections.max(cnt.entrySet(),
                    Map.Entry.comparingByValue()).getKey();

            /* Океан → пропуск, если нет суши в пределах COAST_BUFFER */
            if (isOcean(dom)) {
                boolean nearLand = cells.stream().anyMatch(c -> {
                    for (int dx = -cfg.COAST_BUFFER; dx <= cfg.COAST_BUFFER; dx+=g.spacing)
                        for (int dz = -cfg.COAST_BUFFER; dz <= cfg.COAST_BUFFER; dz+=g.spacing) {
                            int nx = c.gx + dx / g.spacing, nz = c.gz + dz / g.spacing;
                            if (!g.inside(nx, nz)) continue;
                            if (!isOcean(g.biome[nx][nz])) return true;
                        }
                    return false;
                });
                if (!nearLand) continue; // международные воды
            }

            /* Контур достраиваем алгоритмом «краевая трассировка» */
            List<Vector> ring = traceBoundary(cells, g);
            /* Сглаживаем Chaikin-ом */
            for (int i = 0; i < cfg.CHAIKIN_ITER; i++) ring = chaikin(ring);

            out.add(new Region(UUID.randomUUID(), ring, area, dom));
        }
        return out;
    }



    /* ---------- Chaikin smoothing ---------- */

    private static List<Vector> chaikin(List<Vector> pts) {
        if (pts.size() < 3) return pts;
        List<Vector> res = new ArrayList<>(pts.size() * 2);
        for (int i = 0; i < pts.size(); i++) {
            Vector p0 = pts.get(i);
            Vector p1 = pts.get((i + 1) % pts.size());
            res.add(p0.clone().multiply(0.75).add(p1.clone().multiply(0.25)));
            res.add(p0.clone().multiply(0.25).add(p1.clone().multiply(0.75)));
        }
        return res;
    }

    /* ---------- Грубый обход края (Marching Squares lite) ---------- */

    private List<Vector> traceBoundary(List<Cell> cells, Grid g) {
        Set<Long> S = new HashSet<>(cells.size()*2);
        cells.forEach(c -> S.add(((long)c.gx<<32)| (c.gz&0xffffffffL)));

        int[] dx = {1, 0, -1, 0}, dz = {0, 1, 0, -1};
        Cell start = cells.stream()
                .min(Comparator.comparingInt(c -> c.gx * 100000 + c.gz))
                .orElse(cells.get(0));
        int dir = 0;
        List<Vector> ring = new ArrayList<>();
        int x = start.gx, z = start.gz;
        int spacing = g.spacing;

        do {
            ring.add(new Vector(x * spacing, 0, z * spacing));
            /* Поворачиваем пока слева внутри */
            for (int i = 0; i < 4; i++) {
                int nd = (dir + 3) % 4;
                int nx = x + dx[nd], nz = z + dz[nd];
                if (S.contains(((long)nx<<32)|(nz&0xffffffffL))) { dir = nd; break; }
                dir = (dir + 1) % 4;
            }
            x += dx[dir]; z += dz[dir];
        } while (!(x == start.gx && z == start.gz && ring.size() > 1));

        return ring;
    }

    private static boolean isOcean(Biome biome) {
        String name = biome.toString().toLowerCase(Locale.ROOT);
        return name.contains("ocean");
    }
}
