package dev.daze.worldmap.client;

import dev.daze.worldmap.Compat;
import com.mojang.blaze3d.platform.NativeImage;
import dev.daze.worldmap.WorldMapMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Тайлы 128×128 блоков → текстуры 512×512 (4 px на блок). Картинка тайла строится в фоновом потоке:
 * уменьшенные текстуры блоков, свет с северо-запада, фаски, тени от высоких блоков, вода с глубиной и пеной,
 * дизеринг по краю изученной области. В видеопамять загружается на потоке рендера.
 */
public final class MapTiles {
    public static final int TILE = 128, PX = BlockPalette.RES, SIZE = TILE * PX;
    private static final int FADE = 6;
    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "worldmap-tiles");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static int serial;

    private static final class Tile {
        ResourceLocation id;
        DynamicTexture tex;
        boolean dirty = true, building, empty = true;
        long lastUsed;
    }

    private record Done(long key, Tile tile, NativeImage img, boolean any) {}

    private final MapData data;
    private final Map<Long, Tile> tiles = new HashMap<>();
    /** Тайлы, где есть данные, но текстура ещё не создана. */
    private final Set<Long> known = new HashSet<>();
    private final ConcurrentLinkedQueue<Done> done = new ConcurrentLinkedQueue<>();
    private volatile boolean closed;

    public MapTiles(MapData data) {
        this.data = data;
    }

    /** Помечает тайлы, которые затрагивают изменившиеся чанки (с запасом на тени и дизеринг). */
    public void absorbChanges() {
        for (long key : data.drainChanged()) {
            int bx = ChunkPos.getX(key) * 16, bz = ChunkPos.getZ(key) * 16;
            int m = FADE + 8;
            for (int tz = Math.floorDiv(bz - m, TILE); tz <= Math.floorDiv(bz + 15 + m, TILE); tz++)
                for (int tx = Math.floorDiv(bx - m, TILE); tx <= Math.floorDiv(bx + 15 + m, TILE); tx++) {
                    long k = Compat.chunkKey(tx, tz);
                    known.add(k);
                    Tile t = tiles.get(k);
                    if (t != null) t.dirty = true;
                }
        }
    }

    /** Применяет готовые картинки (поток рендера). */
    public void pump() {
        Done d;
        long budget = System.nanoTime() + 4_000_000L;
        while (System.nanoTime() < budget && (d = done.poll()) != null) {
            d.tile.building = false;
            if (tiles.get(d.key) != d.tile) {
                d.img.close();
                continue;
            }
            d.tile.empty = !d.any;
            if (d.tile.tex == null) {
                d.tile.tex = ClientCompat.texture(d.img);
                d.tile.id = WorldMapMod.id("tile/" + serial++);
                Minecraft.getInstance().getTextureManager().register(d.tile.id, d.tile.tex);
            } else d.tile.tex.setPixels(d.img);
            d.tile.tex.upload();
        }
    }

    /** Текстура тайла или null; при необходимости ставит постройку в очередь. */
    public ResourceLocation get(int tx, int tz, long now) {
        long k = Compat.chunkKey(tx, tz);
        Tile t = tiles.get(k);
        if (t == null) {
            if (!known.contains(k)) return null;
            t = new Tile();
            tiles.put(k, t);
        }
        t.lastUsed = now;
        if (t.dirty && !t.building) {
            t.dirty = false;
            t.building = true;
            Tile tile = t;
            WORKER.execute(() -> {
                if (closed) return;
                NativeImage img = new NativeImage(SIZE, SIZE, true);
                boolean any;
                try {
                    any = new Builder(data).render(tx, tz, img);
                } catch (Throwable e) {
                    any = false;
                }
                done.add(new Done(k, tile, img, any));
            });
        }
        return t.empty || t.tex == null ? null : t.id;
    }

    /** Освобождает видеопамять тайлов, которые давно не показывались. */
    public void evict(long now, long maxAge) {
        Iterator<Map.Entry<Long, Tile>> it = tiles.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Tile t = e.getValue();
            if (now - t.lastUsed > maxAge && !t.building) {
                if (t.id != null) Minecraft.getInstance().getTextureManager().release(t.id);
                it.remove();
            }
        }
    }

    public void close() {
        closed = true;
        for (Tile t : tiles.values()) if (t.id != null) Minecraft.getInstance().getTextureManager().release(t.id);
        tiles.clear();
        Done d;
        while ((d = done.poll()) != null) d.img.close();
    }

    // ---------- построение картинки (фоновый поток) ----------

    private static final class Col {
        boolean known;
        int h, depth;
    }

    private static final class Builder {
        private final MapData data;
        private int x0, z0, span;
        private MapData.Chunk[] grid;

        Builder(MapData data) {
            this.data = data;
        }

        private MapData.Chunk chunkAt(int bx, int bz) {
            int cx = (bx >> 4) - (x0 >> 4), cz = (bz >> 4) - (z0 >> 4);
            if (cx < 0 || cz < 0 || cx >= span || cz >= span) return null;
            return grid[cz * span + cx];
        }

        private Col at(int bx, int bz, Col r) {
            MapData.Chunk c = chunkAt(bx, bz);
            int i = (bz & 15) * 16 + (bx & 15);
            r.known = c != null && c.top[i] != null;
            if (r.known) {
                r.h = c.height[i];
                r.depth = c.depth[i];
            }
            return r;
        }

        boolean render(int tx, int tz, NativeImage img) {
            int m = FADE + 8;
            int bx0 = tx * TILE, bz0 = tz * TILE;
            x0 = Math.floorDiv(bx0 - m, 16) * 16;
            z0 = Math.floorDiv(bz0 - m, 16) * 16;
            span = (TILE + 2 * m) / 16 + 2;
            grid = new MapData.Chunk[span * span];
            boolean any = false;
            for (int cz = 0; cz < span; cz++)
                for (int cx = 0; cx < span; cx++) {
                    int ax = (x0 >> 4) + cx, az = (z0 >> 4) + cz;
                    MapData.Chunk c = data.chunk(ax, az);
                    grid[cz * span + cx] = c;
                    if (c != null && ax >= bx0 >> 4 && ax < (bx0 >> 4) + TILE / 16 && az >= bz0 >> 4 && az < (bz0 >> 4) + TILE / 16) any = true;
                }
            img.fillRect(0, 0, SIZE, SIZE, 0);
            if (!any) return false;

            int dw = TILE + 2 * FADE + 2;
            float[] dist = new float[dw * dw];
            Col r = new Col();
            for (int z = 0; z < dw; z++)
                for (int x = 0; x < dw; x++)
                    dist[z * dw + x] = at(bx0 - FADE - 1 + x, bz0 - FADE - 1 + z, r).known ? 99 : 0;
            chamfer(dist, dw);

            Col n = new Col(), w = new Col(), s = new Col(), e = new Col(), q = new Col();
            int[] px = new int[PX * PX];
            BlockPalette.Face waterFace = BlockPalette.water();
            for (int z = 0; z < TILE; z++)
                for (int x = 0; x < TILE; x++) {
                    int bx = bx0 + x, bz = bz0 + z;
                    MapData.Chunk c = chunkAt(bx, bz);
                    int i = (bz & 15) * 16 + (bx & 15);
                    if (c == null || c.top[i] == null) continue;
                    int h = c.height[i], depth = c.depth[i];
                    boolean wet = depth > 0;
                    at(bx, bz - 1, n);
                    at(bx - 1, bz, w);
                    at(bx, bz + 1, s);
                    at(bx + 1, bz, e);
                    int hn = n.known ? n.h : h, hw = w.known ? w.h : h, hs = s.known ? s.h : h, he = e.known ? e.h : h;

                    BlockPalette.Face top = BlockPalette.of(c.top[i]);
                    for (int k = 0; k < px.length; k++) {
                        int a = top.argb()[k];
                        if ((a >>> 24) < 128) a = darker(avg(top.argb()), 0.55f);
                        px[k] = top.tinted() ? mul(a, c.tint[i]) : a;
                    }
                    if (c.over[i] != null) {
                        BlockPalette.Face over = BlockPalette.of(c.over[i]);
                        for (int k = 0; k < px.length; k++) {
                            int a = over.argb()[k];
                            if ((a >>> 24) >= 140) px[k] = over.tinted() ? mul(a, c.overTint[i]) : a;
                        }
                    }
                    if (wet) {
                        float op = Math.min(0.93f, 0.5f + depth * 0.075f);
                        for (int k = 0; k < px.length; k++) px[k] = mix(px[k], mul(waterFace.argb()[k] | 0xFF000000, c.water[i]), op);
                    }

                    float light = 1f + Math.max(-6, Math.min(6, (h - hn) + (h - hw))) * (wet ? 0f : 0.05f);
                    float shadow = 1f;
                    for (int k = 1; k <= 6; k++) {
                        at(bx - k, bz - k, q);
                        if (q.known && q.h - h >= k) {
                            shadow = wet ? 0.82f : 0.7f;
                            break;
                        }
                    }
                    long seed = (bx * 341873128712L) ^ (bz * 132897987541L);
                    for (int py = 0; py < PX; py++)
                        for (int pxx = 0; pxx < PX; pxx++) {
                            float f = light * shadow;
                            if (!wet) {
                                if (py == 0) f *= h > hn ? 1.16f : h < hn ? 0.78f : 1f;
                                if (pxx == 0) f *= h > hw ? 1.10f : h < hw ? 0.84f : 1f;
                                if (py == PX - 1 && h > hs) f *= 0.74f;
                                if (pxx == PX - 1 && h > he) f *= 0.84f;
                            } else if ((py == 0 && !(n.known && n.depth > 0)) || (pxx == 0 && !(w.known && w.depth > 0))
                                    || (py == PX - 1 && !(s.known && s.depth > 0)) || (pxx == PX - 1 && !(e.known && e.depth > 0))) f *= 1.35f;
                            else if (((seed >>> (py * 4 + pxx)) & 15) == 0) f *= 1.12f;
                            int color = grade(px[py * PX + pxx], f);

                            float gx = FADE + 1 + x + (pxx + 0.5f) / PX - 0.5f, gz = FADE + 1 + z + (py + 0.5f) / PX - 0.5f;
                            float d = bilinear(dist, dw, gx, gz);
                            int ix = x * PX + pxx, iy = z * PX + py;
                            float t = (BAYER[(iy & 3) * 4 + (ix & 3)] + 0.5f) / 16f;
                            if (d / FADE < t) continue;
                            ClientCompat.setPixel(img, ix, iy, toAbgr(color));
                        }
                }
            return true;
        }
    }

    private static void chamfer(float[] d, int n) {
        for (int z = 0; z < n; z++)
            for (int x = 0; x < n; x++) {
                float v = d[z * n + x];
                if (x > 0) v = Math.min(v, d[z * n + x - 1] + 1);
                if (z > 0) v = Math.min(v, d[(z - 1) * n + x] + 1);
                if (x > 0 && z > 0) v = Math.min(v, d[(z - 1) * n + x - 1] + 1.414f);
                if (x < n - 1 && z > 0) v = Math.min(v, d[(z - 1) * n + x + 1] + 1.414f);
                d[z * n + x] = v;
            }
        for (int z = n - 1; z >= 0; z--)
            for (int x = n - 1; x >= 0; x--) {
                float v = d[z * n + x];
                if (x < n - 1) v = Math.min(v, d[z * n + x + 1] + 1);
                if (z < n - 1) v = Math.min(v, d[(z + 1) * n + x] + 1);
                if (x < n - 1 && z < n - 1) v = Math.min(v, d[(z + 1) * n + x + 1] + 1.414f);
                if (x > 0 && z < n - 1) v = Math.min(v, d[(z + 1) * n + x - 1] + 1.414f);
                d[z * n + x] = v;
            }
    }

    private static float bilinear(float[] d, int n, float x, float z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        float fx = x - ix, fz = z - iz;
        ix = Math.max(0, Math.min(n - 2, ix));
        iz = Math.max(0, Math.min(n - 2, iz));
        float a = d[iz * n + ix], b = d[iz * n + ix + 1], c = d[(iz + 1) * n + ix], e = d[(iz + 1) * n + ix + 1];
        return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + e * fx) * fz;
    }

    private static int avg(int[] argb) {
        int r = 0, g = 0, b = 0, n = 0;
        for (int a : argb)
            if ((a >>> 24) >= 128) {
                r += (a >> 16) & 255;
                g += (a >> 8) & 255;
                b += a & 255;
                n++;
            }
        return n == 0 ? 0xFF000000 : 0xFF000000 | (r / n) << 16 | (g / n) << 8 | (b / n);
    }

    private static int darker(int c, float f) {
        return 0xFF000000 | (int) (((c >> 16) & 255) * f) << 16 | (int) (((c >> 8) & 255) * f) << 8 | (int) ((c & 255) * f);
    }

    private static int mul(int a, int tint) {
        if (tint == -1 || tint == 0) return a;
        int r = ((a >> 16) & 255) * ((tint >> 16) & 255) / 255;
        int g = ((a >> 8) & 255) * ((tint >> 8) & 255) / 255;
        int b = (a & 255) * (tint & 255) / 255;
        return (a & 0xFF000000) | r << 16 | g << 8 | b;
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    /** Свет + лёгкая стилизация: чуть насыщеннее и теплее, как у нарисованной карты. */
    private static int grade(int c, float f) {
        float r = ((c >> 16) & 255) / 255f, g = ((c >> 8) & 255) / 255f, b = (c & 255) / 255f;
        float l = 0.3f * r + 0.59f * g + 0.11f * b;
        float sat = 1.18f;
        r = (l + (r - l) * sat) * f * 1.03f;
        g = (l + (g - l) * sat) * f;
        b = (l + (b - l) * sat) * f * 0.94f;
        return 0xFF000000 | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, (int) (v * 255f)));
    }

    private static int toAbgr(int argb) {
        return (argb & 0xFF00FF00) | (argb & 0xFF) << 16 | (argb >> 16) & 0xFF;
    }
}
