package dev.daze.worldmap.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import dev.daze.worldmap.Mark;
import dev.daze.worldmap.SafeFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Всё, что относится к текущему миру/серверу: карты измерений, личные метки, состояние серверной части мода
 * (общие метки, игроки, пинги, права), цель навигации.
 */
public final class Session {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "worldmap-io");
        t.setDaemon(true);
        return t;
    });

    public static Session current;

    public static final class Dim {
        public final String id;
        public final MapData data;
        public MapTiles tiles;

        Dim(String id, MapData data) {
            this.id = id;
            this.data = data;
            this.tiles = new MapTiles(data);
        }
    }

    /** Права, которые прислал сервер с модом. null — на сервере мода нет. */
    public record Caps(boolean teleport, int cooldown, boolean publish, boolean op, boolean pings, boolean players) {}

    public record Remote(UUID id, String name, double x, double y, double z, float yaw, long seen) {}

    public record Ping(String from, int x, int y, int z, String dim, long time) {}

    public final Path root;
    public final boolean singleplayer;
    private final Map<String, Dim> dims = new LinkedHashMap<>();
    public final List<Mark> local = new ArrayList<>();
    public final Map<UUID, Mark> shared = new LinkedHashMap<>();
    public final Map<UUID, Remote> remote = new LinkedHashMap<>();
    public final List<Ping> pings = new ArrayList<>();
    public Caps caps;
    public Mark nav;
    public long lastTeleport;
    public String pendingArrival;

    private Session(Path root, boolean singleplayer) {
        this.root = root;
        this.singleplayer = singleplayer;
        try {
            Path f = root.resolve("marks.json");
            if (Files.exists(f)) {
                List<Mark> list = GSON.fromJson(Files.readString(f), new TypeToken<List<Mark>>() {}.getType());
                if (list != null) local.addAll(list);
            }
        } catch (Exception e) {
            LOG.warn("worldmap: не удалось прочитать метки", e);
        }
    }

    static Session open() {
        Minecraft mc = Minecraft.getInstance();
        String key;
        if (mc.getSingleplayerServer() != null)
            key = "sp_" + mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
        else {
            ServerData sd = mc.getCurrentServer();
            key = "mp_" + (sd != null ? sd.ip : "unknown");
        }
        Path root = mc.gameDirectory.toPath().resolve("worldmap").resolve(key.replaceAll("[^a-zA-Z0-9._-]", "_"));
        return new Session(root, mc.getSingleplayerServer() != null);
    }

    public static String dirName(String dim) {
        return dim.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** Карта измерения; при первом обращении загружается с диска в фоне. */
    public Dim dim(String id) {
        return dims.computeIfAbsent(id, k -> {
            Dim d = new Dim(k, new MapData(root.resolve(dirName(k))));
            Minecraft mc = Minecraft.getInstance();
            HolderGetter<Block> blocks = mc.level.holderLookup(Registries.BLOCK);
            IO.execute(() -> d.data.load(blocks));
            return d;
        });
    }

    /** Измерения, для которых есть сохранённая карта, плюс текущее. */
    public List<String> knownDims() {
        List<String> out = new ArrayList<>();
        for (String d : List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"))
            if (dims.containsKey(d) || Files.isDirectory(root.resolve(dirName(d)))) out.add(d);
        for (String d : dims.keySet()) if (!out.contains(d)) out.add(d);
        try (Stream<Path> s = Files.isDirectory(root) ? Files.list(root) : Stream.empty()) {
            s.filter(Files::isDirectory).forEach(p -> {
                String name = p.getFileName().toString().replaceFirst("_", ":");
                if (!out.contains(name) && name.contains(":")) out.add(name);
            });
        } catch (Exception ignored) {
        }
        return out;
    }

    public List<Mark> allMarks() {
        List<Mark> out = new ArrayList<>(local);
        out.addAll(shared.values());
        return out;
    }

    public boolean serverMod() {
        return caps != null;
    }

    public void saveMarks() {
        String json = GSON.toJson(new ArrayList<>(local));
        Path f = root.resolve("marks.json");
        IO.execute(() -> {
            try {
                SafeFiles.writeString(f, json);
            } catch (Exception e) {
                LOG.warn("worldmap: не удалось сохранить метки", e);
            }
        });
    }

    public void saveMaps() {
        for (Dim d : dims.values()) {
            Runnable r = d.data.saveTask();
            if (r != null) IO.execute(r);
        }
    }

    /** Тайлы заново (после смены ресурспаков текстуры блоков другие). */
    void rebuildTiles() {
        for (Dim d : dims.values()) {
            d.tiles.close();
            d.tiles = new MapTiles(d.data);
            d.data.markAllChanged();
        }
    }

    void close() {
        saveMaps();
        saveMarks();
        for (Dim d : dims.values()) d.tiles.close();
        dims.clear();
    }
}
