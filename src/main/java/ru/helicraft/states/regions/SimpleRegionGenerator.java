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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Much simpler region generator based on a regular grid and two-pass
 * merge/split normalization. It does not interact with any claim plugins.
 */
public final class SimpleRegionGenerator {

    public record Region(UUID id,
                         List<Vector> outline,
                         double areaBlocks,
                         Biome dominantBiome) { }

    public interface Callback {
        void onFinished(List<Region> regions);
        default void onProgress(int percent) {}
        default void onError(@NotNull Throwable t) { t.printStackTrace(); }
    }

    public static final class Config {
        public int radiusBlocks = 5_000;
        /** Grid spacing between samples in blocks. */
        public int sampleSpacing = 32;
        public int MIN_CELLS = 300;
        public int MAX_CELLS = 1_500;
        public int slopeExtra = 2;
        public int maxParallelSamples = Runtime.getRuntime().availableProcessors();
        /** Timeout for asynchronous chunk loads in seconds. */
        public int chunkLoadTimeout = 40;
    }

    private static final Logger LOG = Logger.getLogger(SimpleRegionGenerator.class.getName());
    private final Config cfg;

    public SimpleRegionGenerator(Config cfg) { this.cfg = cfg; }

    public void generate(@NotNull World world, @NotNull Callback cb) {
        new BukkitRunnable(){@Override public void run(){asyncGenerate(world,cb);}}
                .runTaskAsynchronously(Objects.requireNonNull(
                        Bukkit.getPluginManager().getPlugin("HeliStates")));
    }

    private void asyncGenerate(World w, Callback cb){
        try{
            Grid g = sampleWorld(w, cb);
            boolean[][] used = new boolean[g.size][g.size];
            Map<Long,Integer> map = new HashMap<>();
            List<List<Cell>> regions = buildRegions(g, used, map);
            normalize(regions, g, map);
            List<Region> out = buildRegionObjects(regions, g, w);
            Bukkit.getScheduler().runTask(
                    Bukkit.getPluginManager().getPlugin("HeliStates"),
                    ()->cb.onFinished(Collections.unmodifiableList(out)));
        }catch(Throwable t){
            Bukkit.getScheduler().runTask(
                    Bukkit.getPluginManager().getPlugin("HeliStates"),
                    ()->cb.onError(t));
        }
    }

    private record Cell(int gx,int gz){}
    private static final class Grid {
        final int size, step, off;
        final int[][] height;
        final Biome[][] biome;
        final boolean[][] water;
        final boolean[][] hBarrier;
        final boolean[][] vBarrier;
        Grid(int size,int step,int off){
            this.size=size; this.step=step; this.off=off;
            height = new int[size][size];
            biome = new Biome[size][size];
            water = new boolean[size][size];
            hBarrier = new boolean[size-1][size];
            vBarrier = new boolean[size][size-1];
        }
    }

    private Grid sampleWorld(World w, Callback cb){
        int step = cfg.sampleSpacing;
        int size = cfg.radiusBlocks/step*2 + 1;
        int off = size/2;
        Grid g = new Grid(size, step, off);
        int total = size*size;
        int parallel = cfg.maxParallelSamples<=0 ?
                Runtime.getRuntime().availableProcessors()*2 : cfg.maxParallelSamples;
        Semaphore sem = new Semaphore(Math.max(1, parallel), true);
        AtomicInteger done = new AtomicInteger();
        int[] last = {0};
        List<CompletableFuture<Void>> futures = new ArrayList<>(total);
        for(int gx=0;gx<size;gx++){
            for(int gz=0;gz<size;gz++){
                int wx=(gx-off)*step;
                int wz=(gz-off)*step;
                try{sem.acquire();}catch(InterruptedException e){Thread.currentThread().interrupt();}
                CompletableFuture<Void> f=new CompletableFuture<>();
                int idx=gx,idz=gz;
                w.getChunkAtAsync(wx>>4,wz>>4,true)
                        .orTimeout(cfg.chunkLoadTimeout, TimeUnit.SECONDS)
                        .thenAccept(ch->{
                            try{
                                ChunkSnapshot s=ch.getChunkSnapshot(true,true,true);
                                int lx=Math.floorMod(wx,16), lz=Math.floorMod(wz,16);
                                g.height[idx][idz]=s.getHighestBlockYAt(lx,lz);
                                Biome b=s.getBiome(lx,64,lz);
                                g.biome[idx][idz]=b;
                                g.water[idx][idz]=isWater(b);
                            }catch(Throwable ex){LOG.log(Level.SEVERE,"sample error at coordinates (wx=" + wx + ", wz=" + wz + ")",ex);}finally{
                                int cur=done.incrementAndGet();
                                int pct=cur*100/total;
                                if(pct!=last[0]){ last[0]=pct; cb.onProgress(pct); }
                                sem.release(); f.complete(null);
                            }
                        })
                        .exceptionally(ex->{
                            if(done.get()<total && last[0]<100 && cb!=null){
                                cb.onError(ex);
                            }
                            sem.release();
                            f.completeExceptionally(ex);
                            return null;
                        });
                futures.add(f);
            }
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        cb.onProgress(100);
        buildBarriers(g);
        return g;
    }

    private void buildBarriers(Grid g){
        List<Integer> slopes=new ArrayList<>();
        for(int x=0;x<g.size;x++)
            for(int z=0;z<g.size;z++){
                if(x+1<g.size) slopes.add(Math.abs(g.height[x][z]-g.height[x+1][z]));
                if(z+1<g.size) slopes.add(Math.abs(g.height[x][z]-g.height[x][z+1]));
            }
        Collections.sort(slopes);
        int median=slopes.isEmpty()?0:slopes.get(slopes.size()/2);
        int thr=Math.max(6, median+cfg.slopeExtra);
        for(int x=0;x<g.size-1;x++)
            for(int z=0;z<g.size;z++)
                g.hBarrier[x][z]=g.water[x][z]||g.water[x+1][z]||Math.abs(g.height[x][z]-g.height[x+1][z])>=thr;
        for(int x=0;x<g.size;x++)
            for(int z=0;z<g.size-1;z++)
                g.vBarrier[x][z]=g.water[x][z]||g.water[x][z+1]||Math.abs(g.height[x][z]-g.height[x][z+1])>=thr;
    }

    private List<List<Cell>> buildRegions(Grid g, boolean[][] used, Map<Long,Integer> map){
        int step = g.step;
        List<List<Cell>> regs=new ArrayList<>();
        for(int x=0;x<g.size;x++)
            for(int z=0;z<g.size;z++) if(!used[x][z]){
                int id = regs.size();
                Deque<Cell> q=new ArrayDeque<>();
                q.add(new Cell(x,z)); used[x][z]=true;
                List<Cell> list=new ArrayList<>();
                while(!q.isEmpty()){
                    Cell c=q.poll(); list.add(c);
                    map.put(((long)c.gx<<32)|(c.gz&0xffffffffL), id);
                    if(c.gx>0 && !g.hBarrier[c.gx-1][c.gz] && !used[c.gx-1][c.gz]){used[c.gx-1][c.gz]=true;q.add(new Cell(c.gx-1,c.gz));}
                    if(c.gx+1<g.size && !g.hBarrier[c.gx][c.gz] && !used[c.gx+1][c.gz]){used[c.gx+1][c.gz]=true;q.add(new Cell(c.gx+1,c.gz));}
                    if(c.gz>0 && !g.vBarrier[c.gx][c.gz-1] && !used[c.gx][c.gz-1]){used[c.gx][c.gz-1]=true;q.add(new Cell(c.gx,c.gz-1));}
                    if(c.gz+1<g.size && !g.vBarrier[c.gx][c.gz] && !used[c.gx][c.gz+1]){used[c.gx][c.gz+1]=true;q.add(new Cell(c.gx,c.gz+1));}
                }
                regs.add(list);
            }
        return regs;
    }

    private void normalize(List<List<Cell>> regs, Grid g, Map<Long,Integer> map){
        boolean changed;
        do{
            changed=false;
            Iterator<List<Cell>> it=regs.iterator();
            while(it.hasNext()){
                List<Cell> r=it.next();
                if(r.size()>=cfg.MIN_CELLS) continue;
                Map<List<Cell>,Integer> border=new HashMap<>();
                for(Cell c:r){
                    if(c.gx>0){
                        int idx=map.getOrDefault(((long)(c.gx-1)<<32)|(c.gz&0xffffffffL), -1);
                        if(idx>=0) countBorder(border, g.hBarrier[c.gx-1][c.gz], regs.get(idx));
                    }
                    if(c.gx+1<g.size){
                        int idx=map.getOrDefault(((long)(c.gx+1)<<32)|(c.gz&0xffffffffL), -1);
                        if(idx>=0) countBorder(border, g.hBarrier[c.gx][c.gz], regs.get(idx));
                    }
                    if(c.gz>0){
                        int idx=map.getOrDefault(((long)c.gx<<32)|((c.gz-1)&0xffffffffL), -1);
                        if(idx>=0) countBorder(border, g.vBarrier[c.gx][c.gz-1], regs.get(idx));
                    }
                    if(c.gz+1<g.size){
                        int idx=map.getOrDefault(((long)c.gx<<32)|((c.gz+1)&0xffffffffL), -1);
                        if(idx>=0) countBorder(border, g.vBarrier[c.gx][c.gz], regs.get(idx));
                    }
                }
                border.remove(r);
                if(border.isEmpty()) continue;
                List<Cell> tgt=Collections.max(border.entrySet(),Map.Entry.comparingByValue()).getKey();
                for(Cell c:r){ map.put(((long)c.gx<<32)|(c.gz&0xffffffffL), regs.indexOf(tgt)); }
                tgt.addAll(r); it.remove(); changed=true; break;
            }
        }while(changed);
        List<List<Cell>> newRegs=new ArrayList<>();
        for(List<Cell> r:regs){
            if(r.size()<=cfg.MAX_CELLS){ newRegs.add(r); continue; }
            int k=(int)Math.ceil(r.size()/(double)cfg.MAX_CELLS);
            List<Vector> points=new ArrayList<>(r.size());
            for(Cell c:r) points.add(new Vector(c.gx,c.gz,0));
            List<List<Cell>> clusters=kMeans(r,k,5);
            int baseIndex=newRegs.size();
            for(int i=0;i<clusters.size();i++){
                for(Cell c:clusters.get(i)){
                    map.put(((long)c.gx<<32)|(c.gz&0xffffffffL), baseIndex+i);
                }
            }
            newRegs.addAll(clusters);
        }
        regs.clear();
        regs.addAll(newRegs);
    }

    private static void countBorder(Map<List<Cell>,Integer> border, boolean barrier, List<Cell> neighbor){
        if(barrier || neighbor==null) return;
        border.merge(neighbor,1,Integer::sum);
    }

    private static List<List<Cell>> kMeans(List<Cell> cells,int k,int iter){
        Random rnd=new Random();
        List<Vector> centers=new ArrayList<>(k);
        for(int i=0;i<k;i++){ Cell c=cells.get(rnd.nextInt(cells.size())); centers.add(new Vector(c.gx,c.gz,0)); }
        for(int it=0;it<iter;it++){
            List<List<Cell>> groups=new ArrayList<>(k); for(int i=0;i<k;i++) groups.add(new ArrayList<>());
            for(Cell c:cells){
                int idx=nearest(centers,c);
                groups.get(idx).add(c);
            }
            centers.clear();
            for(List<Cell> g:groups){
                double ax=0,az=0; for(Cell c:g){ax+=c.gx; az+=c.gz;}
                centers.add(new Vector(ax/g.size(),0,az/g.size()));
            }
            cells=new ArrayList<>(); for(List<Cell> g:groups) cells.addAll(g);
        }
        List<List<Cell>> groups=new ArrayList<>(k); for(int i=0;i<k;i++) groups.add(new ArrayList<>());
        for(Cell c:cells){
            int idx=nearest(centers,c);
            groups.get(idx).add(c);
        }
        return groups;
    }

    private static int nearest(List<Vector> centers,Cell c){
        double best=Double.MAX_VALUE; int idx=0;
        for(int i=0;i<centers.size();i++){
            Vector v=centers.get(i); double d=v.getX()-c.gx; d=d*d+(v.getZ()-c.gz)*(v.getZ()-c.gz);
            if(d<best){best=d; idx=i;}
        }
        return idx;
    }

    private List<Region> buildRegionObjects(List<List<Cell>> regs, Grid g, World w){
        List<Region> out=new ArrayList<>();
        for(List<Cell> list:regs){
            Map<Biome,Integer> cnt=new HashMap<>();
            double area=list.size()*g.step*g.step;
            for(Cell c:list) cnt.merge(g.biome[c.gx][c.gz],1,Integer::sum);
            Biome dom=Collections.max(cnt.entrySet(),Map.Entry.comparingByValue()).getKey();
            List<Vector> ring=traceBoundary(list,g);
            ring=removeCollinear(dedup(ring));
            if(ring.size()<3){
                int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
                int maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
                for(Cell c:list){
                    int wx=(c.gx-g.off)*g.step;
                    int wz=(c.gz-g.off)*g.step;
                    if(wx<minX)minX=wx; if(wx>maxX)maxX=wx;
                    if(wz<minZ)minZ=wz; if(wz>maxZ)maxZ=wz;
                }
                ring=List.of(
                        new Vector(minX,0,minZ),
                        new Vector(maxX,0,minZ),
                        new Vector(maxX,0,maxZ),
                        new Vector(minX,0,maxZ)
                );
            }
            out.add(new Region(UUID.randomUUID(),ring,area,dom));
        }
        return out;
    }

    private List<Vector> traceBoundary(List<Cell> cells, Grid g){
        Set<Long> S=new HashSet<>(cells.size()*2);
        cells.forEach(c->S.add(((long)c.gx<<32)|(c.gz&0xffffffffL)));
        int[] dx={1,0,-1,0};
        int[] dz={0,1,0,-1};
        Cell start=cells.stream().min(Comparator.comparingInt(c->(c.gx+c.gz)*100000+c.gx)).orElse(cells.get(0));
        int dir=0; List<Vector> ring=new ArrayList<>(cells.size()*4);
        Set<Integer> visited=new HashSet<>(cells.size()*4);
        int maxSteps=cells.size()*20; int steps=0; int x=start.gx,z=start.gz;
        do{
            int state=Objects.hash(x,z,dir);
            if(!visited.add(state)||++steps>maxSteps) break;
            ring.add(new Vector((x-g.off)*g.step,0,(z-g.off)*g.step));
            for(int i=0;i<4;i++){
                int nd=(dir+3)%4; int nx=x+dx[nd], nz=z+dz[nd];
                if(S.contains(((long)nx<<32)|(nz&0xffffffffL))){dir=nd;break;}
                dir=(dir+1)%4;
            }
            x+=dx[dir]; z+=dz[dir];
        }while(!(x==start.gx && z==start.gz && ring.size()>1));
        return ring;
    }

    private static List<Vector> dedup(List<Vector> pts){
        if(pts.size()<2) return pts;
        List<Vector> res=new ArrayList<>(pts.size());
        Vector prev=null;
        for(Vector v:pts){ if(prev==null||!prev.equals(v)){res.add(v);prev=v;} }
        return res;
    }

    private static List<Vector> removeCollinear(List<Vector> pts){
        if(pts.size()<3) return pts;
        List<Vector> res=new ArrayList<>(pts.size());
        int n=pts.size();
        for(int i=0;i<n;i++){
            Vector a=pts.get((i+n-1)%n); Vector b=pts.get(i); Vector c=pts.get((i+1)%n);
            if(!isCollinear(a,b,c)) res.add(b);
        }
        return res.size()<3?pts:res;
    }

    private static boolean isCollinear(Vector a,Vector b,Vector c){
        double abx=b.getX()-a.getX(), abz=b.getZ()-a.getZ();
        double bcx=c.getX()-b.getX(), bcz=c.getZ()-b.getZ();
        return Math.abs(abx*bcz-abz*bcx)<1e-6;
    }

    private static boolean isWater(Biome b){
        String n=b.toString().toLowerCase(Locale.ROOT);
        return n.contains("ocean")||n.contains("river")||n.contains("swamp")||n.contains("beach");
    }
}

