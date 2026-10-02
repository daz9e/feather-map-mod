package dev.daze.worldmap.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Изученные столбцы одного измерения: верхний блок, растение над ним, высота, вода, цвета биома и сам биом.
 * Чанки заменяются целиком (сканер пишет новый объект), поэтому фоновые потоки читают их без блокировок.
 */
public final class MapData {
    private static final Logger LOG = LogUtils.getLogger();

    public static final class Chunk {
        public final BlockState[] top = new BlockState[256];
        public final BlockState[] over = new BlockState[256];
        public final short[] height = new short[256];
        public final byte[] depth = new byte[256];
        public final int[] tint = new int[256];
        public final int[] overTint = new int[256];
        public final int[] water = new int[256];
        /** Биом по ячейкам 4×4 блока (как хранит игра). */
        public final String[] biome = new String[16];
    }

    public final Path dir;
    private final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final Set<Long> dirtyRegions = ConcurrentHashMap.newKeySet();
    private final Set<Long> changed = ConcurrentHashMap.newKeySet();
    public volatile boolean loaded;

    public MapData(Path dir) {
        this.dir = dir;
    }

    public Chunk chunk(int cx, int cz) {
        return chunks.get(ChunkPos.asLong(cx, cz));
    }

    public int size() {
        return chunks.size();
    }

    public Set<Long> keys() {
        return chunks.keySet();
    }

    public List<Long> drainChanged() {
        List<Long> out = new ArrayList<>(changed);
        changed.removeAll(out);
        return out;
    }

    // ---------- сканирование (главный поток) ----------

    public void scan(ClientLevel level, LevelChunk lc) {
        ChunkPos cp = lc.getPos();
        Chunk c = new Chunk();
        Minecraft mc = Minecraft.getInstance();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        boolean ceiling = level.dimensionType().hasCeiling();
        int min = level.getMinBuildHeight();
        int known = 0;
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int i = z * 16 + x, wx = cp.getMinBlockX() + x, wz = cp.getMinBlockZ() + z;
                int y = ceiling ? ceilingTop(lc, p, wx, wz, min) : lc.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                if (y < min) continue;
                BlockState s = lc.getBlockState(p.set(wx, y, wz));
                // Карта высот на клиенте бывает устаревшей — спускаемся до настоящего верха.
                while (s.isAir() && y > min) s = lc.getBlockState(p.set(wx, --y, wz));
                if (s.isAir()) continue;
                int depth = 0;
                if (s.getFluidState().is(FluidTags.WATER)) {
                    c.water[i] = BiomeColors.getAverageWaterColor(level, p);
                    while (y > min && depth < 60 && s.getFluidState().is(FluidTags.WATER) && !s.isSolidRender(lc, p)) {
                        depth++;
                        s = lc.getBlockState(p.set(wx, --y, wz));
                    }
                }
                BlockState above = depth > 0 ? null : lc.getBlockState(p.set(wx, y + 1, wz));
                if (above != null && (above.isAir() || !above.getFluidState().isEmpty())) above = null;
                p.set(wx, y, wz);
                c.top[i] = s;
                c.height[i] = (short) (y + depth);
                c.depth[i] = (byte) depth;
                if (BlockPalette.of(s).tinted()) c.tint[i] = mc.getBlockColors().getColor(s, level, p, 0);
                if (above != null) {
                    c.over[i] = above;
                    if (BlockPalette.of(above).tinted()) c.overTint[i] = mc.getBlockColors().getColor(above, level, p.move(0, 1, 0), 0);
                }
                if ((x & 3) == 0 && (z & 3) == 0)
                    c.biome[(z >> 2) * 4 + (x >> 2)] = level.getBiome(p.set(wx, y, wz)).unwrapKey().map(k -> k.location().toString()).orElse(null);
                known++;
            }
        if (known == 0) return;
        long key = cp.toLong();
        chunks.put(key, c);
        changed.add(key);
        dirtyRegions.add(ChunkPos.asLong(cp.x >> 5, cp.z >> 5));
    }

    private static int ceilingTop(LevelChunk lc, BlockPos.MutableBlockPos p, int wx, int wz, int min) {
        int y = Math.min(lc.getMaxBuildHeight() - 1, 110);
        while (y > min && !lc.getBlockState(p.set(wx, y, wz)).isAir()) y--;
        while (y > min && lc.getBlockState(p.set(wx, y, wz)).isAir()) y--;
        return y;
    }

    // ---------- диск (поток ввода-вывода) ----------

    public void load(HolderGetter<Block> blocks) {
        try {
            if (Files.isDirectory(dir)) try (Stream<Path> files = Files.list(dir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (!f.getFileName().toString().endsWith(".wmr")) continue;
                    try {
                        readRegion(NbtIo.readCompressed(f.toFile()), blocks);
                    } catch (Exception e) {
                        LOG.warn("worldmap: не удалось прочитать {}", f, e);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("worldmap: {}", e.toString());
        }
        loaded = true;
    }

    private void readRegion(CompoundTag tag, HolderGetter<Block> blocks) {
        ListTag pal = tag.getList("palette", Tag.TAG_COMPOUND);
        BlockState[] palette = new BlockState[pal.size()];
        for (int i = 0; i < palette.length; i++) palette[i] = NbtUtils.readBlockState(blocks, pal.getCompound(i));
        ListTag bpal = tag.getList("biomes", Tag.TAG_STRING);
        for (Tag t : tag.getList("chunks", Tag.TAG_COMPOUND)) {
            CompoundTag ct = (CompoundTag) t;
            long key = ChunkPos.asLong(ct.getInt("x"), ct.getInt("z"));
            if (chunks.containsKey(key)) continue; // уже отсканирован свежим
            Chunk c = new Chunk();
            int[] top = ct.getIntArray("top"), over = ct.getIntArray("over"), h = ct.getIntArray("h");
            byte[] d = ct.getByteArray("d");
            int[] tint = ct.getIntArray("tint"), ot = ct.getIntArray("otint"), w = ct.getIntArray("water"), b = ct.getIntArray("b");
            if (top.length != 256 || h.length != 256 || d.length != 256) continue;
            for (int i = 0; i < 256; i++) {
                c.top[i] = top[i] < 0 ? null : palette[top[i]];
                c.over[i] = over[i] < 0 ? null : palette[over[i]];
                c.height[i] = (short) h[i];
                c.depth[i] = d[i];
                c.tint[i] = tint[i];
                c.overTint[i] = ot[i];
                c.water[i] = w[i];
            }
            if (b.length == 16) for (int i = 0; i < 16; i++) c.biome[i] = b[i] < 0 ? null : bpal.getString(b[i]);
            chunks.put(key, c);
            changed.add(key);
        }
    }

    /** Снимок изменённых регионов; запись — в потоке ввода-вывода. */
    public Runnable saveTask() {
        if (dirtyRegions.isEmpty()) return null;
        List<Long> regions = new ArrayList<>(dirtyRegions);
        dirtyRegions.removeAll(regions);
        Map<Long, List<Long>> byRegion = new HashMap<>();
        for (long key : chunks.keySet()) {
            long region = ChunkPos.asLong(ChunkPos.getX(key) >> 5, ChunkPos.getZ(key) >> 5);
            if (regions.contains(region)) byRegion.computeIfAbsent(region, k -> new ArrayList<>()).add(key);
        }
        Map<Long, Chunk> snapshot = new HashMap<>();
        for (List<Long> keys : byRegion.values()) for (long k : keys) snapshot.put(k, chunks.get(k));
        return () -> {
            try {
                Files.createDirectories(dir);
                for (var e : byRegion.entrySet()) {
                    Path f = dir.resolve("r." + ChunkPos.getX(e.getKey()) + "." + ChunkPos.getZ(e.getKey()) + ".wmr");
                    NbtIo.writeCompressed(writeRegion(e.getValue(), snapshot), f.toFile());
                }
            } catch (Exception ex) {
                LOG.warn("worldmap: не удалось сохранить карту", ex);
            }
        };
    }

    private static CompoundTag writeRegion(List<Long> keys, Map<Long, Chunk> snapshot) {
        Map<BlockState, Integer> index = new IdentityHashMap<>();
        Map<String, Integer> bindex = new HashMap<>();
        ListTag pal = new ListTag(), bpal = new ListTag(), list = new ListTag();
        for (long key : keys) {
            Chunk c = snapshot.get(key);
            if (c == null) continue;
            int[] top = new int[256], over = new int[256], h = new int[256], b = new int[16];
            for (int i = 0; i < 256; i++) {
                top[i] = idx(c.top[i], index, pal);
                over[i] = idx(c.over[i], index, pal);
                h[i] = c.height[i];
            }
            for (int i = 0; i < 16; i++) {
                String id = c.biome[i];
                b[i] = id == null ? -1 : bindex.computeIfAbsent(id, k -> {
                    bpal.add(StringTag.valueOf(k));
                    return bpal.size() - 1;
                });
            }
            CompoundTag ct = new CompoundTag();
            ct.putInt("x", ChunkPos.getX(key));
            ct.putInt("z", ChunkPos.getZ(key));
            ct.putIntArray("top", top);
            ct.putIntArray("over", over);
            ct.putIntArray("h", h);
            ct.putByteArray("d", c.depth.clone());
            ct.putIntArray("tint", c.tint.clone());
            ct.putIntArray("otint", c.overTint.clone());
            ct.putIntArray("water", c.water.clone());
            ct.putIntArray("b", b);
            list.add(ct);
        }
        CompoundTag tag = new CompoundTag();
        tag.put("palette", pal);
        tag.put("biomes", bpal);
        tag.put("chunks", list);
        return tag;
    }

    private static int idx(BlockState s, Map<BlockState, Integer> index, ListTag pal) {
        if (s == null) return -1;
        return index.computeIfAbsent(s, k -> {
            pal.add(NbtUtils.writeBlockState(k));
            return pal.size() - 1;
        });
    }

    /** Биом в точке, если столбец изучен. */
    public String biomeAt(int bx, int bz) {
        Chunk c = chunk(bx >> 4, bz >> 4);
        return c == null ? null : c.biome[((bz & 15) >> 2) * 4 + ((bx & 15) >> 2)];
    }

    /** Высота поверхности или Integer.MIN_VALUE. */
    public int heightAt(int bx, int bz) {
        Chunk c = chunk(bx >> 4, bz >> 4);
        int i = (bz & 15) * 16 + (bx & 15);
        return c == null || c.top[i] == null ? Integer.MIN_VALUE : c.height[i];
    }
}
