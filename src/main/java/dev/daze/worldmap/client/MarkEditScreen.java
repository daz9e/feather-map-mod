package dev.daze.worldmap.client;

import dev.daze.worldmap.Mark;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Редактор метки: название, координаты, иконка, цвет, видимость в мире, общая/личная. */
public class MarkEditScreen extends Widgets.UiScreen {
    private static final String[] ICONS = {
            "minecraft:compass", "minecraft:red_bed", "minecraft:campfire", "minecraft:chest", "minecraft:crafting_table",
            "minecraft:iron_pickaxe", "minecraft:diamond", "minecraft:emerald", "minecraft:fishing_rod", "minecraft:iron_sword",
            "minecraft:wheat", "minecraft:oak_sapling", "minecraft:oak_door", "minecraft:bell", "minecraft:obsidian",
            "minecraft:ender_eye", "minecraft:beacon", "minecraft:skeleton_skull"};
    private static final int W = 236, H = 202;

    private final MapScreen parent;
    private final Mark mark;
    private final boolean isNew;
    private EditBox name, bx, by, bz;
    private final List<Widgets.IconSlot> icons = new ArrayList<>();
    private final List<Widgets.Swatch> swatches = new ArrayList<>();
    private int px, py;

    public MarkEditScreen(MapScreen parent, Mark mark, boolean isNew) {
        super(Component.translatable(isNew ? "worldmap.edit.new" : "worldmap.edit.title"));
        this.parent = parent;
        this.mark = mark;
        this.isNew = isNew;
    }

    @Override
    protected void build() {
        icons.clear();
        swatches.clear();
        px = uw / 2 - W / 2;
        py = Math.max(4, uh / 2 - H / 2);
        int x = px + 10, y = py + 22;
        name = new EditBox(font, x, y, W - 20, 16, Component.translatable("worldmap.edit.name"));
        name.setMaxLength(40);
        name.setValue(mark.name);
        name.setHint(Component.translatable("worldmap.name.point").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(name);
        setInitialFocus(name);

        y += 26;
        int cw = (W - 20 - 3 * 10 - 2 * 6) / 3;
        bx = coord(x + 10, y, cw, mark.x);
        by = coord(x + 10 + (cw + 16), y + 0, cw, mark.y);
        bz = coord(x + 10 + 2 * (cw + 16), y, cw, mark.z);

        y += 30;
        List<String> ids = new ArrayList<>(List.of(ICONS));
        ItemStack held = minecraft.player.getMainHandItem();
        if (!held.isEmpty()) {
            String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            if (!ids.contains(id)) ids.set(ids.size() - 1, id);
        }
        if (mark.icon != null && !ids.contains(mark.icon)) ids.set(ids.size() - 2, mark.icon);
        int perRow = 9;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            Widgets.IconSlot slot = new Widgets.IconSlot(x + (i % perRow) * 24, y + (i / perRow) * 23, id, UI.iconStack(id), s -> {
                mark.icon = s.id;
                refresh();
            });
            icons.add(addRenderableWidget(slot));
        }

        y += 2 * 23 + 12;
        for (int i = 0; i < Mark.COLORS.length; i++) {
            int c = Mark.COLORS[i];
            swatches.add(addRenderableWidget(new Widgets.Swatch(x + i * 18, y, c, s -> {
                mark.color = s.color;
                refresh();
            })));
        }

        y += 22;
        Session s = Session.current;
        boolean canPub = s != null && s.caps != null && s.caps.publish();
        Widgets.Btn pub = new Widgets.Btn(x, y, W - 20, 14,
                () -> Component.translatable("worldmap.edit.public", onOff(mark.pub)), b -> mark.pub = !mark.pub);
        pub.active = canPub;
        if (!canPub) pub.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(
                s != null && s.caps != null ? "worldmap.edit.public_denied" : "worldmap.edit.public_no_mod")));
        addRenderableWidget(pub);

        y = py + H - 22;
        int bw = isNew ? (W - 24) / 2 : (W - 28) / 3;
        addRenderableWidget(new Widgets.Btn(x, y, bw, 14, Component.translatable("worldmap.edit.save"), b -> save()));
        addRenderableWidget(new Widgets.Btn(x + bw + 4, y, bw, 14, Component.translatable("gui.cancel"), b -> back()));
        if (!isNew) addRenderableWidget(new Widgets.Btn(x + 2 * (bw + 4), y, bw, 14,
                Component.translatable("worldmap.action.delete").withStyle(ChatFormatting.RED), b -> {
            Actions.delete(mark);
            back();
        }));
        refresh();
    }

    private static Component onOff(boolean v) {
        return Component.translatable(v ? "options.on" : "options.off");
    }

    private EditBox coord(int x, int y, int w, int v) {
        EditBox e = new EditBox(font, x, y, w, 14, Component.empty());
        e.setFilter(s -> s.matches("-?\\d{0,8}"));
        e.setValue(Integer.toString(v));
        return addRenderableWidget(e);
    }

    private void refresh() {
        for (Widgets.IconSlot s : icons) s.selected = s.id.equals(mark.icon);
        for (Widgets.Swatch s : swatches) s.selected = s.color == mark.color;
    }

    private static int parse(EditBox e, int fallback) {
        try {
            return Integer.parseInt(e.getValue());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void save() {
        String n = name.getValue().trim();
        if (n.isEmpty()) {
            name.setFocused(true);
            setFocused(name);
            Actions.sound(SoundEvents.VILLAGER_NO, 1.2f);
            return;
        }
        mark.name = n;
        mark.x = parse(bx, mark.x);
        mark.y = parse(by, mark.y);
        mark.z = parse(bz, mark.z);
        Actions.save(mark);
        Actions.sound(SoundEvents.VILLAGER_WORK_CARTOGRAPHER, 1f);
        back();
    }

    private void back() {
        minecraft.setScreen(parent != null ? parent.resumed() : null);
    }

    @Override
    public void onClose() {
        back();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            save();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void renderBackground(GuiGraphics g) {}

    @Override
    protected void renderUnder(GuiGraphics g, int mx, int my, float pt) {
        if (parent != null) parent.render(g, -1, -1, pt);
        // Карта под окном рисует иконки глубоко по Z — очищаем глубину, чтобы окно было сверху.
        com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, net.minecraft.client.Minecraft.ON_OSX);
        g.fill(0, 0, width, height, 0x70000000);
    }

    @Override
    protected void renderUi(GuiGraphics g, int mx, int my, float pt) {
        UI.panel(g, px, py, px + W, py + H);
        g.drawCenteredString(font, title, px + W / 2, py + 7, UI.TITLE);
        int x = px + 10;
        g.drawString(font, "X", bx.getX() - 8, bx.getY() + 3, UI.MUTED, false);
        g.drawString(font, "Y", by.getX() - 8, by.getY() + 3, UI.MUTED, false);
        g.drawString(font, "Z", bz.getX() - 8, bz.getY() + 3, UI.MUTED, false);
        g.drawString(font, Component.translatable("worldmap.edit.icon"), x, icons.get(0).getY() - 10, UI.MUTED, false);
        g.drawString(font, Component.translatable("worldmap.edit.color"), x, swatches.get(0).getY() - 10, UI.MUTED, false);
        // Превью метки.
        UI.diamond(g, px + W - 22, swatches.get(0).getY() + 7, 0.9f, 1, UI.iconStack(mark.icon), mark.color, 0, 0, 10);
    }
}
