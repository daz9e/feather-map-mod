package dev.daze.worldmap;

import net.fabricmc.api.ModInitializer;

public class WorldMapMod implements ModInitializer {
    public static final String MODID = "worldmap";

    @Override
    public void onInitialize() {
        ServerMap.init();
    }
}
