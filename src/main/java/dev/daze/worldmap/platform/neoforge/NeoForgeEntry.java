package dev.daze.worldmap.platform.neoforge;

import dev.daze.worldmap.ServerMap;
import dev.daze.worldmap.WorldMapMod;
import dev.daze.worldmap.platform.Platform;
import dev.daze.worldmap.platform.RawPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
//? if >=1.20.5 {
/*import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
*///?} else {
import dev.daze.worldmap.Net;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
import net.neoforged.neoforge.network.registration.IPayloadRegistrar;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
//?}

import java.nio.file.Path;

@Mod(WorldMapMod.MODID)
public class NeoForgeEntry implements Platform {
    public NeoForgeEntry(IEventBus modBus) {
        Platform.set(this);
        var bus = NeoForge.EVENT_BUS;
        bus.addListener((ServerStartedEvent e) -> ServerMap.onStarted(e.getServer()));
        bus.addListener((ServerStoppingEvent e) -> ServerMap.onStopping(e.getServer()));
        //? if >=1.20.5 {
        /*bus.addListener((ServerTickEvent.Post e) -> ServerMap.onTick(e.getServer()));
        *///?} else {
        bus.addListener((TickEvent.ServerTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) ServerMap.onTick(ServerLifecycleHooks.getCurrentServer());
        });
        //?}
        bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) ServerMap.onPlayerJoin(p);
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) ServerMap.onPlayerLeave(p);
        });
        bus.addListener((RegisterCommandsEvent e) -> ServerMap.registerCommands(e.getDispatcher()));
        modBus.addListener(NeoForgeEntry::registerPayloads);
        if (isClient()) NeoForgeClientEntry.init(modBus);
    }

    private static boolean isClient() {
        //? if >=1.21.9 {
        /*return FMLEnvironment.getDist() == Dist.CLIENT;
        *///?} else
        return FMLEnvironment.dist == Dist.CLIENT;
    }

    // Канал необязательный: подключаемся к серверам и клиентам без мода.
    //? if >=1.20.5 {
    /*private static void registerPayloads(RegisterPayloadHandlersEvent e) {
        PayloadRegistrar r = e.registrar("1").optional();
        r.playBidirectional(RawPayload.TYPE, RawPayload.CODEC, (p, ctx) -> {
            if (ctx.player() instanceof ServerPlayer sp) ServerMap.receive(sp.level().getServer(), sp, p.data());
            else if (isClient()) NeoForgeClientEntry.receive(p.data());
        });
    }
    *///?} else {
    private static void registerPayloads(RegisterPayloadHandlerEvent e) {
        IPayloadRegistrar r = e.registrar(WorldMapMod.MODID).optional();
        r.play(Net.CHANNEL, RawPayload::read, h -> h
                .server((p, ctx) -> ctx.player().ifPresent(pl -> {
                    if (pl instanceof ServerPlayer sp) ServerMap.receive(sp.server, sp, p.data());
                }))
                .client((p, ctx) -> NeoForgeClientEntry.receive(p.data())));
    }
    //?}

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public void sendToPlayer(ServerPlayer player, byte[] data) {
        //? if >=1.20.5 {
        /*PacketDistributor.sendToPlayer(player, new RawPayload(data));
        *///?} else
        PacketDistributor.PLAYER.with(player).send(new RawPayload(data));
    }

    @Override
    public boolean canSend(ServerPlayer player) {
        //? if >=1.20.5 {
        /*return player.connection.hasChannel(RawPayload.TYPE);
        *///?} else
        return player.connection.isConnected(Net.CHANNEL);
    }
}
