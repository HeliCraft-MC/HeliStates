/*
 *  RegionGenerator.java
 *  Новый генератор регионов по сухопутным барьерам.
 *
 *  PaperMC 1.21.5, Java 21.
 *  © 2025 Maksim & HeliCraftMC
 */
package ru.helicraft.states.regions;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import ru.helicraft.helistates.HeliStates;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("LanguageDetectionInspection")
public final class RegionGenerator {

    /* ---------- Публичные типы ---------- */
    public record Region(UUID id,
                         List<Vector> outline,
                         double areaBlocks,
                         Biome dominantBiome) { }

    public interface Callback {
        void onFinished(List<Region> regions);
        default void onProgress(int percent) {}
        default void onError(@NotNull Throwable t) { t.printStackTrace(); }
    }

    /* ---------- Конфигурация ---------- */
    public static final class Config {
        public int radiusBlocks = 5_000;
        public int sampleSpacing = 8;

        /** Крутой склон, который считается барьером. */
        public int STEEP_SLOPE = 10;

        /** Минимальные / максимальные размеры регионов (в ячейках). */
        public int MIN_CELLS = 400;
        public int MAX_CELLS = 3_000;

        /** Скругление Chaikin. */
        public int CHAIKIN_ITER = 2;

        /** Сколько одновременно загружаем чанков. */
        public int maxParallelSamples = Runtime.getRuntime().availableProcessors() * 2;

        /** Группы "похожих" биомов */
        public Map<Biome,String> biomeGroups = new HashMap<>();
    }

    /**
     * Calculates effective concurrency limit.
     * @param configured value from config; {@code 0} or negative means CPU x2
     * @param cpus available processors
     */
    static int computeConcurrency(int configured, int cpus) {
        int base = configured > 0 ? configured : cpus * 2;
        return Math.max(1, base);
    }

    /* ---------- Внутренние структуры ---------- */
    /** Hex cell in axial (q,r) coordinates */
    private record Cell(int q, int r) { }

    private static final class Grid {
        final int w, h, spacing;
        final int minQ, minR;
        /** hex side length in blocks */
        final double hexSize;
        final int[][]   height;
        final Biome[][] biome;
        final boolean[][] ridge;

        Grid(int w, int h, int spacing, int minQ, int minR, double hexSize) {
            this.w = w; this.h = h; this.spacing = spacing;
            this.minQ = minQ; this.minR = minR;
            this.hexSize = hexSize;
            height = new int[w][h];
            biome  = new Biome[w][h];
            ridge  = new boolean[w][h];
        }
        boolean inside(int q,int r){return q>=0&&r>=0&&q<w&&r<h;}

        /** World X coordinate of cell center */
        double worldX(int q, int r){
            int aq=q+minQ, ar=r+minR;
            return Math.sqrt(3.0) * hexSize * (aq + ar/2.0);
        }

        /** World Z coordinate of cell center */
        double worldZ(int q, int r){
            int aq=q+minQ, ar=r+minR;
            return 1.5 * hexSize * ar;
        }
    }

    /* ---------- Поля ---------- */
    private static final Logger LOG = Logger.getLogger(RegionGenerator.class.getName());
    private final Config cfg;

    public RegionGenerator(Config cfg){this.cfg=cfg;}

    /* ---------- Public API ---------- */
    public void generate(@NotNull World world,@NotNull Callback cb){
        new BukkitRunnable(){@Override public void run(){asyncGenerate(world,cb);}}
                .runTaskAsynchronously(Objects.requireNonNull(
                        Bukkit.getPluginManager().getPlugin("HeliStates")));
    }

    /* ---------- Асинхронная часть ---------- */
    private void asyncGenerate(World w, Callback cb){
        try{
            long t0 = System.currentTimeMillis();
            Grid g = sampleWorld(w,cb);
            buildBarriers(g);
            List<Region> regions = growRegions(g,w);

            long dt = System.currentTimeMillis()-t0;
            LOG.info("Land-region generation finished in "+dt+" ms; regions: "+regions.size());
            Bukkit.getScheduler().runTask(
                    Bukkit.getPluginManager().getPlugin("HeliStates"),
                    ()->cb.onFinished(Collections.unmodifiableList(regions)));
        }catch(Throwable t){
            Bukkit.getScheduler().runTask(
                    Bukkit.getPluginManager().getPlugin("HeliStates"),
                    ()->cb.onError(t));
        }
    }

    /* ---------- 1. Выборка высот и биомов ---------- */
    private Grid sampleWorld(World w, Callback cb){
        int radius = cfg.radiusBlocks;
        int s = cfg.sampleSpacing;
        // side length of hex cell
        double hexSize = s / 2.0;

        List<Cell> centers = new ArrayList<>();
        int qMin=Integer.MAX_VALUE,qMax=Integer.MIN_VALUE;
        int rMin=Integer.MAX_VALUE,rMax=Integer.MIN_VALUE;

        int range = (int)Math.ceil(radius / s) * 2 + 2;
        for(int q=-range;q<=range;q++)
            for(int r=-range;r<=range;r++){
                double wx=Math.sqrt(3.0) * hexSize * (q + r/2.0);
                double wz=1.5 * hexSize * r;
                if(Math.hypot(wx,wz) <= radius){
                    centers.add(new Cell(q,r));
                    if(q<qMin)qMin=q; if(q>qMax)qMax=q;
                    if(r<rMin)rMin=r; if(r>rMax)rMax=r;
                }
            }

        int gw=qMax - qMin + 1;
        int gh=rMax - rMin + 1;
        Grid g=new Grid(gw,gh,s,qMin,rMin,hexSize);

        AtomicInteger done=new AtomicInteger(); int total=centers.size();
        int concurrency = computeConcurrency(cfg.maxParallelSamples, Runtime.getRuntime().availableProcessors());
        Semaphore sem=new Semaphore(concurrency, true);
        List<CompletableFuture<Void>> futures=new ArrayList<>(total);

        for(Cell c:centers){
            try{sem.acquire();}catch(InterruptedException e){Thread.currentThread().interrupt();}
            int qi=c.q - qMin, ri=c.r - rMin;
            double wxD=g.worldX(qi,ri); double wzD=g.worldZ(qi,ri);
            int wx=(int)Math.floor(wxD); int wz=(int)Math.floor(wzD);
            CompletableFuture<Void> f=new CompletableFuture<>();
            w.getChunkAtAsync(Math.floorDiv(wx,16), Math.floorDiv(wz,16), true)
                .orTimeout(30, TimeUnit.SECONDS)
                .thenAccept(ch->{
                    try{
                        ChunkSnapshot snap=ch.getChunkSnapshot(true,true,true);
                        Biome b=snap.getBiome(Math.floorMod(wx,16),64,Math.floorMod(wz,16));
                        g.biome[qi][ri]=b;
                        if(isWater(b)){
                            g.height[qi][ri]=w.getMaxHeight()+100;
                            g.ridge[qi][ri]=true;
                        }else{
                            g.height[qi][ri]=snap.getHighestBlockYAt(Math.floorMod(wx,16),Math.floorMod(wz,16));
                        }
                    }catch(Throwable ex){LOG.log(Level.SEVERE,"sample error",ex);}finally{
                        int cur=done.incrementAndGet();
                        if(cur%Math.max(1,total/20)==0)cb.onProgress(cur*100/total);
                        sem.release(); f.complete(null);
                    }
                })
                .exceptionally(ex->{
                    LOG.log(Level.SEVERE,
                            "chunk load failed: " + ex.getClass().getSimpleName(), ex);
                    int cur=done.incrementAndGet();
                    if(cur%Math.max(1,total/20)==0)cb.onProgress(cur*100/total);
                    sem.release(); f.complete(null);
                    return null;
                });
            futures.add(f);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        cb.onProgress(100);
        return g;
    }

    /* ---------- 2. Построение барьерной маски ---------- */
    private void buildBarriers(Grid g){
        int[] dq={1,1,0,-1,-1,0};
        int[] dr={0,-1,-1,0,1,1};

        double sum=0; int cnt=0;
        for(int q=0;q<g.w;q++)
            for(int r=0;r<g.h;r++)
                for(int i=0;i<6;i++){
                    int nq=q+dq[i], nr=r+dr[i];
                    if(!g.inside(nq,nr))continue;
                    sum+=Math.abs(g.height[q][r]-g.height[nq][nr]);
                    cnt++;
                }
        int avg = (int)Math.round(sum / Math.max(1, cnt));
        int threshold = Math.max(cfg.STEEP_SLOPE, avg + 2);

        for(int q=0;q<g.w;q++)
            for(int r=0;r<g.h;r++){
                if(g.ridge[q][r])continue;
                for(int i=0;i<6;i++){ 
                    int nq=q+dq[i], nr=r+dr[i];
                    if(!g.inside(nq,nr))continue;
                    if(!similarBiome(g.biome[q][r],g.biome[nq][nr])){
                        g.ridge[q][r]=g.ridge[nq][nr]=true; continue;
                    }
                    if(Math.abs(g.height[q][r]-g.height[nq][nr])>=threshold){
                        g.ridge[q][r]=g.ridge[nq][nr]=true;
                    }
                }
            }
    }

    /* ---------- 3. Рост, merge, split ---------- */
    private List<Region> growRegions(Grid g, World w){
        int[][] region=new int[g.w][g.h];
        for(int[] row:region)Arrays.fill(row,-1);
        Map<Integer,List<Cell>> regCells=new HashMap<>();
        int id=0;
        int[] dq={1,1,0,-1,-1,0};
        int[] dr={0,-1,-1,0,1,1};
        for(int q=0;q<g.w;q++)
            for(int r=0;r<g.h;r++) if(region[q][r]==-1 && !g.ridge[q][r]){
                Deque<Cell> qCells=new ArrayDeque<>();
                qCells.add(new Cell(q,r)); List<Cell> list=new ArrayList<>();
                region[q][r]=id;
                while(!qCells.isEmpty()){
                    Cell c=qCells.poll(); list.add(c);
                    for(int i=0;i<6;i++){ 
                        int nq=c.q+dq[i], nr=c.r+dr[i];
                        if(!g.inside(nq,nr)||g.ridge[nq][nr])continue;
                        if(region[nq][nr]!=-1)continue;
                        region[nq][nr]=id; qCells.add(new Cell(nq,nr));
                    }
                }
                regCells.put(id,list); id++;
            }

        mergeSmall(regCells,region,g);
        splitLarge(regCells,region,g);

        return buildRegionObjects(regCells,g,w);
    }

    /* ---------- merge / split и геометрия ---------- */
    private void mergeSmall(Map<Integer,List<Cell>> reg,int[][] region,Grid g){
        boolean merged;
        do{
            merged=false;
            Iterator<Map.Entry<Integer,List<Cell>>> it=reg.entrySet().iterator();
            while(it.hasNext()){
                var e=it.next();
                if(e.getValue().size()>=cfg.MIN_CELLS) continue;
                int id=e.getKey();
                Map<Integer,Integer> border=new HashMap<>();
                int[] dq={1,1,0,-1,-1,0};
                int[] dr={0,-1,-1,0,1,1};
                for(Cell c:e.getValue()){
                    for(int i=0;i<6;i++){
                        int nq=c.q+dq[i], nr=c.r+dr[i];
                        if(!g.inside(nq,nr))continue;
                        int rid=region[nq][nr];
                        if(rid<0||rid==id)continue;
                        border.merge(rid,1,Integer::sum);
                    }
                }
                if(border.isEmpty())continue;
                int tgt=Collections.max(border.entrySet(),Map.Entry.comparingByValue()).getKey();
                e.getValue().forEach(c->region[c.q][c.r]=tgt);
                reg.computeIfAbsent(tgt,k->new ArrayList<>()).addAll(e.getValue());
                it.remove();
                merged=true;
                break;
            }
        }while(merged);
    }

    private void splitLarge(Map<Integer,List<Cell>> reg,int[][] region,Grid g){
        boolean split;
        do{
            split=false;
            for(var e:new ArrayList<>(reg.entrySet())){
                if(e.getValue().size()<=cfg.MAX_CELLS) continue;
                List<Cell> list=e.getValue();
                list.sort(Comparator.comparingInt(c->c.q+c.r));
                List<Cell> sub=list.subList(0,list.size()/2);
                int newId=reg.keySet().stream().mapToInt(i->i).max().orElse(0)+1;
                sub.forEach(c->{region[c.q][c.r]=newId;});
                reg.put(newId,new ArrayList<>(sub));
                list.removeAll(sub);
                split=true;
                break;
            }
        }while(split);
    }

    private List<Region> buildRegionObjects(Map<Integer,List<Cell>> regCells,Grid g,World w){
        List<Region> out=new ArrayList<>();
        for(var e:regCells.entrySet()){
            List<Cell> cells=e.getValue();
            Map<Biome,Integer> cnt=new HashMap<>();
            double cellArea = 3.0 * Math.sqrt(3.0) / 2.0 * g.hexSize * g.hexSize;
            double area=0;
            for(Cell c:cells){
                cnt.merge(g.biome[c.q][c.r],1,Integer::sum);
                area+=cellArea;
            }
            Biome dom=Collections.max(cnt.entrySet(),Map.Entry.comparingByValue()).getKey();
            if(isWater(dom)){
                boolean nearLand=cells.stream().anyMatch(c->{
                    int[] dq={1,1,0,-1,-1,0};
                    int[] dr={0,-1,-1,0,1,1};
                    for(int d=0;d<6;d++)
                        for(int k=1;k<=2;k++){
                            int nq=c.q+dq[d]*k, nr=c.r+dr[d]*k;
                            if(!g.inside(nq,nr))continue;
                            if(!isWater(g.biome[nq][nr]))return true;
                        }
                    return false;
                });
                if(!nearLand) continue;
            }

            List<Vector> ring=traceBoundary(cells,g,cfg.CHAIKIN_ITER);
            for(int i=0;i<cfg.CHAIKIN_ITER;i++) ring=chaikin(ring);
            ring=dedup(ring);
            ring=removeCollinear(ring);
            if(ring.size()>=3) out.add(new Region(UUID.randomUUID(),ring,area,dom));
        }
        return out;
    }

    private static List<Vector> traceBoundary(List<Cell> cells,Grid g,int chaikinIter){
        Set<Long> S=new HashSet<>(cells.size()*2);
        cells.forEach(c->S.add(((long)c.q<<32)|(c.r&0xffffffffL)));
        int[] dq={1,1,0,-1,-1,0};
        int[] dr={0,-1,-1,0,1,1};
        Cell start=cells.stream().min(Comparator.comparingInt(c->(c.q+c.r)*100000+c.q)).orElse(cells.get(0));
        int dir=0; List<Vector> ring=new ArrayList<>(cells.size()*6);
        Set<Long> visited=new HashSet<>(cells.size()*6);
        int maxSteps=cells.size()*(chaikinIter+12);
        int steps=0; int q=start.q,r=start.r;
        do{
            long state=((long)(q & 0x1FFFFF) << 42) | ((long)(r & 0x1FFFFF) << 21) | (dir & 0x1F);
            if(!visited.add(state)||++steps>maxSteps) break;
            ring.add(new Vector(g.worldX(q,r),0,g.worldZ(q,r)));
            for(int i=0;i<6;i++){
                int nd=(dir+5)%6; int nq=q+dq[nd], nr=r+dr[nd];
                if(S.contains(((long)nq<<32)|(nr&0xffffffffL))){dir=nd;break;}
                dir=(dir+1)%6;
            }
            q+=dq[dir]; r+=dr[dir];
        }while(!(q==start.q&&r==start.r&&ring.size()>1));
        return ring;
    }

    private static List<Vector> chaikin(List<Vector> pts){
        if(pts.size()<3) return pts;
        List<Vector> res=new ArrayList<>(pts.size()*2);
        for(int i=0;i<pts.size();i++){
            Vector p0=pts.get(i); Vector p1=pts.get((i+1)%pts.size());
            res.add(p0.clone().multiply(0.75).add(p1.clone().multiply(0.25)));
            res.add(p0.clone().multiply(0.25).add(p1.clone().multiply(0.75)));
        }
        return res;
    }

    private static List<Vector> dedup(List<Vector> pts){
        if(pts.size()<2) return pts;
        List<Vector> res=new ArrayList<>(pts.size());
        Vector prev=null;
        for(Vector v:pts){
            if(prev==null||!prev.equals(v)){res.add(v);prev=v;}
        }
        return res;
    }

    private static List<Vector> removeCollinear(List<Vector> pts){
        if(pts.size()<3) return pts;
        List<Vector> res=new ArrayList<>(pts.size());
        int n=pts.size();
        for(int i=0;i<n;i++){
            Vector a=pts.get((i+n-1)%n);
            Vector b=pts.get(i);
            Vector c=pts.get((i+1)%n);
            if(!isCollinear(a,b,c)) res.add(b);
        }
        return res.size()<3?pts:res;
    }

    private static boolean isCollinear(Vector a,Vector b,Vector c){
        double abx=b.getX()-a.getX(), abz=b.getZ()-a.getZ();
        double bcx=c.getX()-b.getX(), bcz=c.getZ()-b.getZ();
        return Math.abs(abx*bcz-abz*bcx)<1e-6;
    }

    /* ---------- утилиты ---------- */
    private static boolean isWater(Biome b){
        String n=b.toString().toLowerCase(Locale.ROOT);
        return n.contains("ocean")||n.contains("river")||n.contains("swamp")||n.contains("beach");
    }

    private boolean similarBiome(Biome a,Biome b){
        if(a==b) return true;
        String ga=cfg.biomeGroups.get(a);
        return ga!=null && ga.equals(cfg.biomeGroups.get(b));
    }
}

