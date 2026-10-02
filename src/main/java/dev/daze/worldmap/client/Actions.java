package dev.daze.worldmap.client;

import dev.daze.worldmap.Mark;
import dev.daze.worldmap.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Действия с метками, общие для карты, миникарты, редактора и клавиш. */
public final class Actions {
    /** Формат метки в чате: [Карта] Название @ x y z измерение иконка (у игроков без мода остаётся читаемым). */
    public static final Pattern SHARE = Pattern.compile("\\[(?:Карта|Map)] (.{1,40}?) @ (-?\\d+) (-?\\d+) (-?\\d+)(?: ([a-z0-9_:./-]+))?(?: ([a-z0-9_:./-]+))?\\s*$");
    private static final Pattern SENDER = Pattern.compile("^<([A-Za-z0-9_]{1,16})>");

    private Actions() {}

    public static Minecraft mc() {
        return Minecraft.getInstance();
    }

    public static String currentDim() {
        return mc().level.dimension().location().toString();
    }

    public static void sound(SoundEvent s, float pitch) {
        mc().getSoundManager().play(SimpleSoundInstance.forUI(s, pitch, 0.6f));
    }

    public static void click() {
        sound(SoundEvents.UI_BUTTON_CLICK.value(), 1f);
    }

    public static void toast(Component c) {
        if (mc().player != null) mc().player.displayClientMessage(c, true);
    }

    // ---------- метки ----------

    /** Сохраняет метку: личную — на диск, общую — на сервер. Переключение «общая/личная» переносит её. */
    public static void save(Mark m) {
        Session s = Session.current;
        if (s == null) return;
        s.local.removeIf(o -> o.id.equals(m.id));
        if (m.pub && s.caps != null && s.caps.publish()) {
            FriendlyByteBuf buf = PacketByteBufs.create();
            m.write(buf);
            ClientPlayNetworking.send(Net.MARK_PUT, buf);
            s.shared.put(m.id, m);
        } else {
            if (s.shared.containsKey(m.id)) deleteShared(m.id);
            m.pub = false;
            s.local.add(m);
        }
        s.saveMarks();
        if (s.nav != null && s.nav.id.equals(m.id)) s.nav = m;
    }

    public static void delete(Mark m) {
        Session s = Session.current;
        if (s == null) return;
        if (m.pub) deleteShared(m.id);
        s.local.removeIf(o -> o.id.equals(m.id));
        if (s.nav != null && s.nav.id.equals(m.id)) s.nav = null;
        s.saveMarks();
    }

    private static void deleteShared(java.util.UUID id) {
        Session s = Session.current;
        if (s.caps == null) return;
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUUID(id);
        ClientPlayNetworking.send(Net.MARK_DEL, buf);
        s.shared.remove(id);
    }

    public static boolean canEdit(Mark m) {
        Session s = Session.current;
        if (s == null) return false;
        if (!m.pub) return true;
        return s.caps != null && (s.caps.op() || mc().player.getUUID().equals(m.owner));
    }

    public static void navigate(Mark m) {
        Session s = Session.current;
        if (s == null) return;
        if (s.nav != null && s.nav.id.equals(m.id)) {
            s.nav = null;
            toast(Component.translatable("worldmap.nav.off"));
        } else {
            s.nav = m;
            toast(Component.translatable("worldmap.nav.on", m.name).withStyle(ChatFormatting.AQUA));
            sound(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1.2f);
        }
    }

    // ---------- телепорт ----------

    /** null — можно; иначе причина, почему нельзя. */
    public static Component teleportBlocker() {
        Session s = Session.current;
        if (s == null || mc().player == null) return Component.empty();
        if (s.caps != null) {
            if (!s.caps.teleport()) return Component.translatable("worldmap.tp.denied");
            long left = s.lastTeleport + s.caps.cooldown() * 1000L - System.currentTimeMillis();
            if (left > 0 && !s.caps.op()) return Component.translatable("worldmap.tp.cooldown", (int) Math.ceil(left / 1000.0));
            return null;
        }
        return mc().player.hasPermissions(2) ? null : Component.translatable("worldmap.tp.no_mod");
    }

    public static void teleport(int x, int y, int z, String dim) {
        Session s = Session.current;
        if (s == null || mc().getConnection() == null) return;
        s.lastTeleport = System.currentTimeMillis();
        if (s.caps != null) {
            FriendlyByteBuf buf = PacketByteBufs.create();
            buf.writeVarInt(x);
            buf.writeVarInt(z);
            buf.writeUtf(dim);
            ClientPlayNetworking.send(Net.TELEPORT, buf);
        } else {
            String tp = "tp @s " + x + " " + (y + 1) + " " + z;
            mc().getConnection().sendCommand(dim.equals(currentDim()) ? tp : "execute in " + dim + " run " + tp);
        }
    }

    // ---------- пинг и чат ----------

    public static void ping(int x, int y, int z) {
        Session s = Session.current;
        if (s == null) return;
        if (s.caps != null && s.caps.pings()) {
            FriendlyByteBuf buf = PacketByteBufs.create();
            buf.writeVarInt(x);
            buf.writeVarInt(y);
            buf.writeVarInt(z);
            ClientPlayNetworking.send(Net.PING, buf);
        } else {
            addPing(mc().player.getGameProfile().getName(), x, y, z, currentDim());
        }
    }

    public static void addPing(String from, int x, int y, int z, String dim) {
        Session s = Session.current;
        if (s == null) return;
        s.pings.removeIf(p -> p.from().equals(from));
        s.pings.add(new Session.Ping(from, x, y, z, dim, System.currentTimeMillis()));
        sound(SoundEvents.NOTE_BLOCK_BELL.value(), 1.6f);
    }

    /** Отправляет метку в чат от своего имени. */
    public static void share(Mark m) {
        if (mc().getConnection() == null) return;
        String name = m.name.replace("@", "").replace("[", "").replace("]", "").trim();
        mc().getConnection().sendChat(String.format(Locale.ROOT, "[Карта] %s @ %d %d %d %s %s",
                name, m.x, m.y, m.z, shortId(m.dim), shortId(m.icon == null ? "minecraft:compass" : m.icon)));
        sound(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1.3f);
    }

    private static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    private static String fullId(String id, String fallback) {
        if (id == null || id.isEmpty()) return fallback;
        return id.contains(":") ? id : "minecraft:" + id;
    }

    /** Ищет метку в тексте сообщения. */
    public static Mark parseShared(String text) {
        Matcher mt = SHARE.matcher(text);
        if (!mt.find()) return null;
        Mark m = new Mark();
        m.name = mt.group(1).trim();
        m.x = Integer.parseInt(mt.group(2));
        m.y = Integer.parseInt(mt.group(3));
        m.z = Integer.parseInt(mt.group(4));
        m.dim = fullId(mt.group(5), "minecraft:overworld");
        m.icon = fullId(mt.group(6), "minecraft:compass");
        // Начало сообщения «<Ник> …» — если сервер присылает чат как системный текст.
        Matcher sm = SENDER.matcher(text);
        if (sm.find()) m.ownerName = sm.group(1);
        return m;
    }

    /** Новая метка в точке с иконкой из руки. */
    public static Mark newMark(int x, int y, int z, String dim) {
        Mark m = new Mark();
        m.x = x;
        m.y = y;
        m.z = z;
        m.dim = dim;
        var held = mc().player.getMainHandItem();
        if (!held.isEmpty()) m.icon = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
        m.owner = mc().player.getUUID();
        m.ownerName = mc().player.getGameProfile().getName();
        return m;
    }
}
