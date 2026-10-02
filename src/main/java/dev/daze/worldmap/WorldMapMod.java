package dev.daze.worldmap;

import net.minecraft.resources.ResourceLocation;

/** Общие константы мода; точки входа лоадеров — в пакете platform. */
public final class WorldMapMod {
    public static final String MODID = "worldmap";

    private WorldMapMod() {}

    public static ResourceLocation id(String path) {
        //? if <1.21 {
        return new ResourceLocation(MODID, path);
        //?} else {
        /*return ResourceLocation.fromNamespaceAndPath(MODID, path);
        *///?}
    }
}
