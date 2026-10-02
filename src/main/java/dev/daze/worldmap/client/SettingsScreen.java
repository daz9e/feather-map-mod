package dev.daze.worldmap.client;

import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/** Настройки карты и миникарты. */
public class SettingsScreen extends Widgets.UiScreen {
    private static final int W = 250;
    private final MapScreen parent;
    private int px, py, ph;

    public SettingsScreen(MapScreen parent) {
        super(Component.translatable("worldmap.settings"));
        this.parent = parent;
    }

    @Override
    protected void build() {
        ClientConfig c = ClientConfig.get();
        Object[][] rows = {
                {(Supplier<Component>) () -> opt("worldmap.opt.compass", onOff(c.compass)), (Runnable) () -> c.compass = !c.compass},
                {(Supplier<Component>) () -> opt("worldmap.opt.players", onOff(c.showPlayers)), (Runnable) () -> c.showPlayers = !c.showPlayers},
                {(Supplier<Component>) () -> opt("worldmap.opt.deaths", onOff(c.deathMarks)), (Runnable) () -> c.deathMarks = !c.deathMarks},
                {(Supplier<Component>) () -> opt("worldmap.opt.grid", onOff(c.grid)), (Runnable) () -> c.grid = !c.grid},
                {(Supplier<Component>) () -> opt("worldmap.opt.animations", Component.translatable(c.fastAnimations ? "worldmap.anim.fast" : "worldmap.anim.full")), (Runnable) () -> c.fastAnimations = !c.fastAnimations},
                {(Supplier<Component>) () -> opt("worldmap.opt.ui_scale", c.uiScale == 0 ? Component.translatable("worldmap.auto") : Component.literal("×" + c.uiScale)),
                        (Runnable) () -> {
                            float[] steps = {0, 1, 1.5f, 2, 2.5f};
                            int i = 0;
                            while (i < steps.length && steps[i] != c.uiScale) i++;
                            c.uiScale = steps[(i + 1) % steps.length];
                            ClientCompat.later(this::rebuildWidgets);
                        }},
        };
        ph = 24 + rows.length * 18 + 26;
        px = uw / 2 - W / 2;
        py = Math.max(4, uh / 2 - ph / 2);
        int y = py + 22;
        for (Object[] r : rows) {
            @SuppressWarnings("unchecked") Supplier<Component> label = (Supplier<Component>) r[0];
            Runnable act = (Runnable) r[1];
            addRenderableWidget(new Widgets.Btn(px + 10, y, W - 20, 15, label, b -> {
                act.run();
                c.save();
            }));
            y += 18;
        }
        addRenderableWidget(new Widgets.Btn(px + W / 2 - 50, py + ph - 20, 100, 15, Component.translatable("gui.done"), b -> onClose()));
    }

    private static Component opt(String key, Component value) {
        return Component.translatable(key).append(": ").append(value);
    }

    private static Component onOff(boolean v) {
        return Component.translatable(v ? "options.on" : "options.off");
    }

    @Override
    public void onClose() {
        ClientConfig.get().save();
        ClientCompat.setScreen(parent != null ? parent.resumed() : null);
    }

    @Override
    protected void renderUnder(Gfx g, int mx, int my, float pt) {
        if (parent != null) parent.renderFrame(g.g, -1, -1, pt);
        // Карта под окном рисует иконки глубоко по Z — очищаем глубину, чтобы окно было сверху.
        ClientCompat.clearDepth();
        g.fill(0, 0, width, height, 0x70000000);
    }

    @Override
    protected void renderUi(Gfx g, int mx, int my, float pt) {
        UI.panel(g, px, py, px + W, py + ph);
        g.centered(title, px + W / 2, py + 7, UI.TITLE);
    }
}
