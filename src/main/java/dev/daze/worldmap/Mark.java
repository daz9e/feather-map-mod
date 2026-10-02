package dev.daze.worldmap;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** Метка карты: точка с иконкой, к ней можно телепортироваться и вести навигацию. */
public final class Mark {
    public static final int[] COLORS = {0x86EBDB, 0xF4D27A, 0xF07A6A, 0x9BE07A, 0x7AB8F0, 0xC79BF0, 0xF0A0C8, 0xF4F0E6};

    public UUID id = UUID.randomUUID();
    public String name = "";
    public int x, y, z;
    public String dim = "minecraft:overworld";
    public String icon = "minecraft:compass";
    public int color = COLORS[0];
    public boolean showInWorld = true;
    /** Общая метка: хранится на сервере и видна всем игрокам. */
    public boolean pub;
    public UUID owner;
    public String ownerName = "";
    public boolean death;
    public long created = System.currentTimeMillis();

    public Mark() {}

    public Mark copy() {
        Mark m = new Mark();
        m.id = id;
        m.name = name;
        m.x = x;
        m.y = y;
        m.z = z;
        m.dim = dim;
        m.icon = icon;
        m.color = color;
        m.showInWorld = showInWorld;
        m.pub = pub;
        m.owner = owner;
        m.ownerName = ownerName;
        m.death = death;
        m.created = created;
        return m;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(id);
        buf.writeUtf(name, 64);
        buf.writeVarInt(x);
        buf.writeVarInt(y);
        buf.writeVarInt(z);
        buf.writeUtf(dim, 128);
        buf.writeUtf(icon == null ? "" : icon, 128);
        buf.writeInt(color);
        buf.writeBoolean(showInWorld);
        buf.writeBoolean(owner != null);
        if (owner != null) buf.writeUUID(owner);
        buf.writeUtf(ownerName == null ? "" : ownerName, 32);
        buf.writeLong(created);
    }

    public static Mark read(FriendlyByteBuf buf) {
        Mark m = new Mark();
        m.id = buf.readUUID();
        m.name = buf.readUtf(64);
        m.x = buf.readVarInt();
        m.y = buf.readVarInt();
        m.z = buf.readVarInt();
        m.dim = buf.readUtf(128);
        m.icon = buf.readUtf(128);
        m.color = buf.readInt();
        m.showInWorld = buf.readBoolean();
        m.owner = buf.readBoolean() ? buf.readUUID() : null;
        m.ownerName = buf.readUtf(32);
        m.created = buf.readLong();
        m.pub = true;
        return m;
    }
}
