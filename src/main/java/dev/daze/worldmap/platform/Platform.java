package dev.daze.worldmap.platform;

import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/** То, что зависит от лоадера. Реализацию задаёт точка входа (FabricEntry / NeoForgeEntry / ForgeEntry). */
public interface Platform {
    Path configDir();

    /** Пакет канала worldmap:net на сервер (отправитель задаёт клиентская точка входа). */
    default void sendToServer(byte[] data) {
        if (Holder.toServer != null) Holder.toServer.accept(data);
    }

    void sendToPlayer(ServerPlayer player, byte[] data);

    /** Есть ли у игрока клиентская часть мода (канал зарегистрирован). */
    boolean canSend(ServerPlayer player);

    static Platform get() {
        return Holder.instance;
    }

    static void set(Platform p) {
        Holder.instance = p;
    }

    static void setClientSender(java.util.function.Consumer<byte[]> sender) {
        Holder.toServer = sender;
    }

    final class Holder {
        private static Platform instance;
        private static java.util.function.Consumer<byte[]> toServer;

        private Holder() {}
    }
}
