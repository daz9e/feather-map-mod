package dev.daze.worldmap;

import net.minecraft.resources.ResourceLocation;

/** Каналы. Сервер без мода их не знает — тогда клиент работает автономно. */
public final class Net {
    // клиент → сервер
    public static final ResourceLocation TELEPORT = id("teleport");
    public static final ResourceLocation MARK_PUT = id("mark_put");
    public static final ResourceLocation MARK_DEL = id("mark_del");
    public static final ResourceLocation PING = id("ping");
    // сервер → клиент
    public static final ResourceLocation CONFIG = id("config");
    public static final ResourceLocation MARKS = id("marks");
    public static final ResourceLocation MARK_UPD = id("mark_upd");
    public static final ResourceLocation MARK_GONE = id("mark_gone");
    public static final ResourceLocation PLAYERS = id("players");
    public static final ResourceLocation PINGED = id("pinged");
    public static final ResourceLocation TP_RESULT = id("tp_result");

    private Net() {}

    public static ResourceLocation id(String path) {
        return new ResourceLocation(WorldMapMod.MODID, path);
    }
}
