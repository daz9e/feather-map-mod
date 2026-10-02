package dev.daze.worldmap.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Кнопки в стиле карты: тёмный фон, золотая кайма при наведении, кремовый текст. */
public final class Widgets {
    private Widgets() {}

    public static class Btn extends AbstractButton {
        private final Consumer<Btn> action;
        private final Supplier<Component> label;
        public boolean selected;

        public Btn(int x, int y, int w, int h, Supplier<Component> label, Consumer<Btn> action) {
            super(x, y, w, h, label.get());
            this.label = label;
            this.action = action;
        }

        public Btn(int x, int y, int w, int h, Component label, Consumer<Btn> action) {
            this(x, y, w, h, () -> label, action);
        }

        @Override
        public void onPress() {
            action.accept(this);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            setMessage(label.get());
            boolean hot = active && isHoveredOrFocused();
            int border = selected ? 0xFFFFE08A : hot ? UI.GOLD : 0xFF6B5A44;
            g.fill(getX(), getY(), getX() + width, getY() + height, border);
            g.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, selected ? 0xFF4A3A26 : hot ? 0xFF3E3123 : 0xFF2F261C);
            Font f = UI.font();
            int color = !active ? UI.MUTED : selected || hot ? 0xFFFFE08A : UI.TEXT;
            g.drawCenteredString(f, getMessage(), getX() + width / 2, getY() + (height - 8) / 2, color);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }

    /** Квадрат с иконкой предмета (выбор иконки метки). */
    public static class IconSlot extends AbstractButton {
        public final ItemStack stack;
        public final String id;
        private final Consumer<IconSlot> action;
        public boolean selected;

        public IconSlot(int x, int y, String id, ItemStack stack, Consumer<IconSlot> action) {
            super(x, y, 20, 20, stack.getHoverName());
            this.id = id;
            this.stack = stack;
            this.action = action;
        }

        @Override
        public void onPress() {
            action.accept(this);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            boolean hot = isHoveredOrFocused();
            g.fill(getX(), getY(), getX() + 20, getY() + 20, selected ? 0xFFFFE08A : hot ? UI.GOLD : 0xFF6B5A44);
            g.fill(getX() + 1, getY() + 1, getX() + 19, getY() + 19, selected ? 0xFF4A3A26 : 0xFF2F261C);
            g.renderItem(stack, getX() + 2, getY() + 2);
            if (isHovered()) g.renderTooltip(UI.font(), stack.getHoverName(), mx, my);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }

    /** Цветной образец (цвет рамки метки). */
    public static class Swatch extends AbstractButton {
        public final int color;
        private final Consumer<Swatch> action;
        public boolean selected;

        public Swatch(int x, int y, int color, Consumer<Swatch> action) {
            super(x, y, 14, 14, Component.empty());
            this.color = color;
            this.action = action;
        }

        @Override
        public void onPress() {
            action.accept(this);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            int cx = getX() + 7, cy = getY() + 7;
            if (selected) UI.diamondFill(g, cx, cy, 9, 0xFFFFE08A);
            UI.diamondFill(g, cx, cy, 7.5f, 0xFF14201F);
            UI.diamondFill(g, cx, cy, 6.5f, 0xFF000000 | color);
            if (isHoveredOrFocused() && !selected) UI.diamondRing(g, cx, cy, 9, UI.GOLD);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {}
    }

    /** Экран, который рисует и обрабатывает ввод в «UI-пространстве» с собственным масштабом. */
    public abstract static class UiScreen extends Screen {
        protected float u = 1;
        protected int uw, uh;

        protected UiScreen(Component title) {
            super(title);
        }

        @Override
        protected final void init() {
            u = UI.scale(width);
            uw = (int) Math.ceil(width / u);
            uh = (int) Math.ceil(height / u);
            build();
        }

        protected abstract void build();

        /** Слой под интерфейсом, в обычных координатах экрана. */
        protected void renderUnder(GuiGraphics g, int mx, int my, float pt) {}

        /** Слой интерфейса, в UI-координатах (виджеты рисуются после него). */
        protected void renderUi(GuiGraphics g, int umx, int umy, float pt) {}

        protected void renderOver(GuiGraphics g, int umx, int umy, float pt) {}

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            renderUnder(g, mx, my, pt);
            int umx = (int) (mx / u), umy = (int) (my / u);
            g.pose().pushPose();
            g.pose().translate(0, 0, 3000);
            g.pose().scale(u, u, 1);
            renderUi(g, umx, umy, pt);
            super.render(g, umx, umy, pt);
            renderOver(g, umx, umy, pt);
            g.pose().popPose();
        }

        @Override
        public boolean mouseClicked(double mx, double my, int b) {
            return super.mouseClicked(mx / u, my / u, b);
        }

        @Override
        public boolean mouseReleased(double mx, double my, int b) {
            return super.mouseReleased(mx / u, my / u, b);
        }

        @Override
        public boolean mouseDragged(double mx, double my, int b, double dx, double dy) {
            return super.mouseDragged(mx / u, my / u, b, dx / u, dy / u);
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double d) {
            return super.mouseScrolled(mx / u, my / u, d);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
