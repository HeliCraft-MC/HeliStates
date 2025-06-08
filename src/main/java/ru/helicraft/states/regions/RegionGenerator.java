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
                         int areaBlocks,
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
    private record Cell(int gx, int gz) { }

    private static final class Grid {
        final int w, h, spacing;
        final int minX, minZ;
        final int[][]   height;
        final Biome[][] biome;
        final boolean[][] ridge;

        Grid(int w, int h, int spacing, int minX, int minZ) {
            this.w = w; this.h = h; this.spacing = spacing;
            this.minX = minX; this.minZ = minZ;
            height = new int[w][h];
            biome  = new Biome[w][h];
            ridge  = new boolean[w][h];
        }
        boolean inside(int x,int z){return x>=0&&z>=0&&x<w&&z<h;}
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
        int r = cfg.radiusBlocks, s = cfg.sampleSpacing;
        int minX=-r,minZ=-r,maxX=r,maxZ=r;
        int gw=(maxX-minX)/s+1, gh=(maxZ-minZ)/s+1;
        Grid g=new Grid(gw,gh,s,minX,minZ);

        AtomicInteger done=new AtomicInteger(); int total=gw*gh;
        int concurrency = computeConcurrency(cfg.maxParallelSamples, Runtime.getRuntime().availableProcessors());
        Semaphore sem=new Semaphore(concurrency, true);
        List<CompletableFuture<Void>> futures=new ArrayList<>(total);

        for(int gx=0;gx<gw;gx++)for(int gz=0;gz<gh;gz++){
            try{sem.acquire();}catch(InterruptedException e){Thread.currentThread().interrupt();}
            int cellX=gx,cellZ=gz,wx=minX+cellX*s,wz=minZ+cellZ*s;
            CompletableFuture<Void> f=new CompletableFuture<>();
            w.getChunkAtAsync(wx>>4, wz>>4, true)
                .orTimeout(30, TimeUnit.SECONDS)
                .thenAccept(ch->{
                    try{
                        ChunkSnapshot snap=ch.getChunkSnapshot(true,true,true);
                        Biome b=snap.getBiome(wx&15,64,wz&15);
                        g.biome[cellX][cellZ]=b;
                        if(isWater(b)){
                            g.height[cellX][cellZ]=w.getMaxHeight()+100;
                            g.ridge[cellX][cellZ]=true;
                        }else{
                            g.height[cellX][cellZ]=snap.getHighestBlockYAt(wx&15,wz&15);
                        }
                    }catch(Throwable ex){LOG.log(Level.SEVERE,"sample error",ex);}finally{
                        int cur=done.incrementAndGet();
                        if(cur%Math.max(1,total/20)==0)cb.onProgress(cur*100/total);
                        sem.release(); f.complete(null);
                    }
                })
                .exceptionally(ex->{
                    LOG.log(Level.SEVERE,"chunk load timeout",ex);
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
        int[] dx={1,-1,0,0, 1,1,-1,-1};
        int[] dz={0,0,1,-1,1,-1,1,-1};

        double sum=0; int cnt=0;
        for(int x=0;x<g.w;x++)
            for(int z=0;z<g.h;z++)
                for(int i=0;i<8;i++){
                    int nx=x+dx[i],nz=z+dz[i];
                    if(!g.inside(nx,nz))continue;
                    sum+=Math.abs(g.height[x][z]-g.height[nx][nz]);
                    cnt++;
                }
        int threshold=Math.max(cfg.STEEP_SLOPE,(int)Math.round((sum/Math.max(1,cnt))*1.5));

        for(int x=0;x<g.w;x++)
            for(int z=0;z<g.h;z++){
                if(g.ridge[x][z])continue;
                for(int i=0;i<8;i++){
                    int nx=x+dx[i],nz=z+dz[i];
                    if(!g.inside(nx,nz))continue;
                    if(!similarBiome(g.biome[x][z],g.biome[nx][nz])){
                        g.ridge[x][z]=g.ridge[nx][nz]=true; continue;
                    }
                    if(Math.abs(g.height[x][z]-g.height[nx][nz])>=threshold){
                        g.ridge[x][z]=g.ridge[nx][nz]=true;
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
        int[] dx={1,-1,0,0},dz={0,0,1,-1};
        for(int x=0;x<g.w;x++)
            for(int z=0;z<g.h;z++) if(region[x][z]==-1 && !g.ridge[x][z]){
                Deque<Cell> q=new ArrayDeque<>();
                q.add(new Cell(x,z)); List<Cell> list=new ArrayList<>();
                region[x][z]=id;
                while(!q.isEmpty()){
                    Cell c=q.poll(); list.add(c);
                    for(int i=0;i<4;i++){
                        int nx=c.gx+dx[i],nz=c.gz+dz[i];
                        if(!g.inside(nx,nz)||g.ridge[nx][nz])continue;
                        if(region[nx][nz]!=-1)continue;
                        region[nx][nz]=id; q.add(new Cell(nx,nz));
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
                for(Cell c:e.getValue()){
                    for(int dx1=-1;dx1<=1;dx1++)
                        for(int dz1=-1;dz1<=1;dz1++){
                            int nx=c.gx+dx1,nz=c.gz+dz1;
                            if(!g.inside(nx,nz))continue;
                            int rid=region[nx][nz];
                            if(rid<0||rid==id)continue;
                            border.merge(rid,1,Integer::sum);
                        }
                }
                if(border.isEmpty())continue;
                int tgt=Collections.max(border.entrySet(),Map.Entry.comparingByValue()).getKey();
                e.getValue().forEach(c->region[c.gx][c.gz]=tgt);
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
                list.sort(Comparator.comparingInt(c->c.gx+c.gz));
                List<Cell> sub=list.subList(0,list.size()/2);
                int newId=reg.keySet().stream().mapToInt(i->i).max().orElse(0)+1;
                sub.forEach(c->{region[c.gx][c.gz]=newId;});
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
            int area=0;
            for(Cell c:cells){
                cnt.merge(g.biome[c.gx][c.gz],1,Integer::sum);
                area+=cfg.sampleSpacing*cfg.sampleSpacing;
            }
            Biome dom=Collections.max(cnt.entrySet(),Map.Entry.comparingByValue()).getKey();
            if(isWater(dom)){
                boolean nearLand=cells.stream().anyMatch(c->{
                    for(int dx=-cfg.sampleSpacing*2;dx<=cfg.sampleSpacing*2;dx+=g.spacing)
                        for(int dz=-cfg.sampleSpacing*2;dz<=cfg.sampleSpacing*2;dz+=g.spacing){
                            int nx=c.gx+dx/g.spacing,nz=c.gz+dz/g.spacing;
                            if(!g.inside(nx,nz))continue;
                            if(!isWater(g.biome[nx][nz]))return true;
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
        cells.forEach(c->S.add(((long)c.gx<<32)|(c.gz&0xffffffffL)));
        int[] dx={1,0,-1,0},dz={0,1,0,-1};
        Cell start=cells.stream().min(Comparator.comparingInt(c->c.gx*100000+c.gz)).orElse(cells.get(0));
        int dir=0; List<Vector> ring=new ArrayList<>(cells.size()*4);
        Set<Long> visited=new HashSet<>(cells.size()*4);
        int maxSteps=cells.size()*(chaikinIter+8);
        int steps=0; int x=start.gx,z=start.gz; int spacing=g.spacing; int offX=g.minX,offZ=g.minZ;
        do{
            long state=(((long)x&0xffffffffL)<<34)|((long)(z&0xffffffffL)<<2)|(dir&0x3);
            if(!visited.add(state)||++steps>maxSteps) break;
            ring.add(new Vector(offX+x*spacing,0,offZ+z*spacing));
            for(int i=0;i<4;i++){
                int nd=(dir+3)%4; int nx=x+dx[nd],nz=z+dz[nd];
                if(S.contains(((long)nx<<32)|(nz&0xffffffffL))){dir=nd;break;}
                dir=(dir+1)%4;
            }
            x+=dx[dir]; z+=dz[dir];
        }while(!(x==start.gx&&z==start.gz&&ring.size()>1));
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

