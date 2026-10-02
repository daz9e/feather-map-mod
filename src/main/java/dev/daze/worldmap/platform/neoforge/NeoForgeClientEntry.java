package dev.daze.worldmap.platform.neoforge;

import dev.daze.worldmap.Compat;
import dev.daze.worldmap.client.Hud;
import dev.daze.worldmap.client.WorldMapClient;
import dev.daze.worldmap.platform.Platform;
import dev.daze.worldmap.platform.RawPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
//? if >=1.21.4 {
/*import dev.daze.worldmap.WorldMapMod;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
*///?} else
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
//? if >=1.21.6 {
/*import net.neoforged.neoforge.client.network.ClientPacketDistributor;
*///?} else
import net.neoforged.neoforge.network.PacketDistributor;
//? if >=1.20.5 {
/*import net.neoforged.neoforge.client.event.ClientTickEvent;
*///?} else
import net.neoforged.neoforge.event.TickEvent;

/** Клиентская часть для NeoForge. */
final class NeoForgeClientEntry {
    private NeoForgeClientEntry() {}

    static void init(IEventBus modBus) {
        //? if >=1.21.6 {
        /*Platform.setClientSender(data -> ClientPacketDistributor.sendToServer(new RawPayload(data)));
        *///?} elif >=1.20.5 {
        /*Platform.setClientSender(data -> PacketDistributor.sendToServer(new RawPayload(data)));
        *///?} else
        Platform.setClientSender(data -> PacketDistributor.SERVER.noArg().send(new RawPayload(data)));
        modBus.addListener((RegisterKeyMappingsEvent e) -> {
            for (KeyMapping k : WorldMapClient.KEYS) e.register(k);
        });
        //? if >=1.21.4 {
        /*modBus.addListener((AddClientReloadListenersEvent e) ->
                e.addListener(WorldMapMod.id("tiles"), (ResourceManagerReloadListener) rm -> WorldMapClient.onResourcesReloaded()));
        *///?} else {
        modBus.addListener((RegisterClientReloadListenersEvent e) ->
                e.registerReloadListener((ResourceManagerReloadListener) rm -> WorldMapClient.onResourcesReloaded()));
        //?}

        var bus = NeoForge.EVENT_BUS;
        bus.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> WorldMapClient.onJoin());
        bus.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> WorldMapClient.onDisconnect());
        bus.addListener((ChunkEvent.Load e) -> {
            if (e.getLevel().isClientSide() && e.getChunk() instanceof LevelChunk) WorldMapClient.onChunkLoad(e.getChunk().getPos());
        });
        //? if >=1.20.5 {
        /*bus.addListener((ClientTickEvent.Post e) -> WorldMapClient.onTick());
        *///?} else {
        bus.addListener((TickEvent.ClientTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) WorldMapClient.onTick();
        });
        //?}
        //? if >=1.21 {
        /*bus.addListener((RenderGuiEvent.Post e) -> Hud.render(e.getGuiGraphics(), e.getPartialTick().getGameTimeDeltaPartialTick(false)));
        *///?} else
        bus.addListener((RenderGuiEvent.Post e) -> Hud.render(e.getGuiGraphics(), e.getPartialTick()));
        bus.addListener((ClientChatReceivedEvent e) -> {
            boolean overlay = e instanceof ClientChatReceivedEvent.System s && s.isOverlay();
            if (!WorldMapClient.onChat(e.getMessage(), senderName(e), overlay)) e.setCanceled(true);
        });
        bus.addListener((RegisterClientCommandsEvent e) -> WorldMapClient.registerCommands(e.getDispatcher()));
    }

    static void receive(byte[] data) {
        WorldMapClient.receive(data);
    }

    private static String senderName(ClientChatReceivedEvent e) {
        var conn = Minecraft.getInstance().getConnection();
        if (e.getSender() == null || conn == null) return null;
        PlayerInfo info = conn.getPlayerInfo(e.getSender());
        return info == null ? null : Compat.name(info.getProfile());
    }
}
