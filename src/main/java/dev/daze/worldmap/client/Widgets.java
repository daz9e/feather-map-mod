package dev.daze.worldmap.client;

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

        //? if <1.21.9 {
        @Override
        public void onPress() {
            action.accept(this);
        }
        //?} else {
        /*@Override
        public void onPress(net.minecraft.client.input.InputWithModifiers input) {
            action.accept(this);
        }
        *///?}

        @Override
        //? if <1.21.11 {
        protected void renderWidget(GuiGraphics gg, int mx, int my, float pt) {
        //?} elif <26.1 {
        /*protected void renderContents(GuiGraphics gg, int mx, int my, float pt) {
        *///?} else
        /*protected void extractContents(GuiGraphics gg, int mx, int my, float pt) {*/
            Gfx g = new Gfx(gg);
            setMessage(label.get());
            boolean hot = active && isHoveredOrFocused();
            int border = selected ? 0xFFFFE08A : hot ? UI.GOLD : 0xFF6B5A44;
            g.fill(getX(), getY(), getX() + width, getY() + height, border);
            g.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, selected ? 0xFF4A3A26 : hot ? 0xFF3E3123 : 0xFF2F261C);
            int color = !active ? UI.MUTED : selected || hot ? 0xFFFFE08A : UI.TEXT;
            g.centered(getMessage(), getX() + width / 2, getY() + (height - 8) / 2, color);
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

        //? if <1.21.9 {
        @Override
        public void onPress() {
            action.accept(this);
        }
        //?} else {
        /*@Override
        public void onPress(net.minecraft.client.input.InputWithModifiers input) {
            action.accept(this);
        }
        *///?}

        @Override
        //? if <1.21.11 {
        protected void renderWidget(GuiGraphics gg, int mx, int my, float pt) {
        //?} elif <26.1 {
        /*protected void renderContents(GuiGraphics gg, int mx, int my, float pt) {
        *///?} else
        /*protected void extractContents(GuiGraphics gg, int mx, int my, float pt) {*/
            Gfx g = new Gfx(gg);
            boolean hot = isHoveredOrFocused();
            g.fill(getX(), getY(), getX() + 20, getY() + 20, selected ? 0xFFFFE08A : hot ? UI.GOLD : 0xFF6B5A44);
            g.fill(getX() + 1, getY() + 1, getX() + 19, getY() + 19, selected ? 0xFF4A3A26 : 0xFF2F261C);
            g.item(stack, getX() + 2, getY() + 2);
            if (isHovered()) g.tooltip(stack.getHoverName(), mx, my);
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

        //? if <1.21.9 {
        @Override
        public void onPress() {
            action.accept(this);
        }
        //?} else {
        /*@Override
        public void onPress(net.minecraft.client.input.InputWithModifiers input) {
            action.accept(this);
        }
        *///?}

        @Override
        //? if <1.21.11 {
        protected void renderWidget(GuiGraphics gg, int mx, int my, float pt) {
        //?} elif <26.1 {
        /*protected void renderContents(GuiGraphics gg, int mx, int my, float pt) {
        *///?} else
        /*protected void extractContents(GuiGraphics gg, int mx, int my, float pt) {*/
            Gfx g = new Gfx(gg);
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

        /** Перед кадром; false — кадр не рисовать (например, экран уже закрыт). */
        protected boolean beforeFrame() {
            return true;
        }

        /** Слой под интерфейсом, в обычных координатах экрана. */
        protected void renderUnder(Gfx g, int mx, int my, float pt) {}

        /** Слой интерфейса, в UI-координатах (виджеты рисуются после него). */
        protected void renderUi(Gfx g, int umx, int umy, float pt) {}

        protected void renderOver(Gfx g, int umx, int umy, float pt) {}

        //? if <26.1 {
        @Override
        public void render(GuiGraphics gg, int mx, int my, float pt) {
            renderFrame(gg, mx, my, pt);
        }
        //?} else {
        /*@Override
        public void extractRenderState(GuiGraphics gg, int mx, int my, float pt) {
            renderFrame(gg, mx, my, pt);
        }
        *///?}

        /** Кадр целиком; дочерние окна рисуют так карту под собой. */
        public void renderFrame(GuiGraphics gg, int mx, int my, float pt) {
            if (!beforeFrame()) return;
            Gfx g = new Gfx(gg);
            renderUnder(g, mx, my, pt);
            int umx = (int) (mx / u), umy = (int) (my / u);
            g.push();
            g.translate(0, 0, 3000);
            g.scale(u, u);
            renderUi(g, umx, umy, pt);
            //? if <26.1 {
            super.render(gg, umx, umy, pt);
            //?} else
            /*super.extractRenderState(gg, umx, umy, pt);*/
            renderOver(g, umx, umy, pt);
            g.pop();
        }

        //? if <1.20.2 {
        @Override
        public void renderBackground(GuiGraphics g) {}
        //?} elif <26.1 {
        /*@Override
        public void renderBackground(GuiGraphics g, int mx, int my, float pt) {}
        *///?} else {
        /*@Override
        public void extractBackground(GuiGraphics g, int mx, int my, float pt) {}
        *///?}

        // ---------- ввод: координаты экрана; по умолчанию — виджетам в UI-координатах ----------

        protected boolean onMouseDown(double mx, double my, int button) {
            return superMouseDown(mx / u, my / u, button);
        }

        protected boolean onMouseUp(double mx, double my, int button) {
            return superMouseUp(mx / u, my / u, button);
        }

        protected boolean onDrag(double mx, double my, int button, double dx, double dy) {
            return superDrag(mx / u, my / u, button, dx / u, dy / u);
        }

        protected boolean onScroll(double mx, double my, double delta) {
            return superScroll(mx / u, my / u, delta);
        }

        protected boolean onKey(int key, int scan, int mods) {
            return superKey(key, scan, mods);
        }

        //? if <1.21.9 {
        @Override
        public boolean mouseClicked(double mx, double my, int b) {
            return onMouseDown(mx, my, b);
        }

        @Override
        public boolean mouseReleased(double mx, double my, int b) {
            return onMouseUp(mx, my, b);
        }

        @Override
        public boolean mouseDragged(double mx, double my, int b, double dx, double dy) {
            return onDrag(mx, my, b, dx, dy);
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            return onKey(key, scan, mods);
        }

        protected final boolean superMouseDown(double mx, double my, int b) {
            return super.mouseClicked(mx, my, b);
        }

        protected final boolean superMouseUp(double mx, double my, int b) {
            return super.mouseReleased(mx, my, b);
        }

        protected final boolean superDrag(double mx, double my, int b, double dx, double dy) {
            return super.mouseDragged(mx, my, b, dx, dy);
        }

        protected final boolean superKey(int key, int scan, int mods) {
            return super.keyPressed(key, scan, mods);
        }
        //?} else {
        /*private int mods;

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean dbl) {
            mods = e.modifiers();
            return onMouseDown(e.x(), e.y(), e.button());
        }

        @Override
        public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent e) {
            mods = e.modifiers();
            return onMouseUp(e.x(), e.y(), e.button());
        }

        @Override
        public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent e, double dx, double dy) {
            mods = e.modifiers();
            return onDrag(e.x(), e.y(), e.button(), dx, dy);
        }

        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
            return onKey(e.key(), scan(e), e.modifiers());
        }

        private static int scan(net.minecraft.client.input.KeyEvent e) {
            //? if <26.3 {
            return e.scancode();
            //?} else
            /^return e.keycode();^/
        }

        private net.minecraft.client.input.MouseButtonEvent event(double mx, double my, int b) {
            return new net.minecraft.client.input.MouseButtonEvent(mx, my, new net.minecraft.client.input.MouseButtonInfo(b, mods));
        }

        protected final boolean superMouseDown(double mx, double my, int b) {
            return super.mouseClicked(event(mx, my, b), false);
        }

        protected final boolean superMouseUp(double mx, double my, int b) {
            return super.mouseReleased(event(mx, my, b));
        }

        protected final boolean superDrag(double mx, double my, int b, double dx, double dy) {
            return super.mouseDragged(event(mx, my, b), dx, dy);
        }

        protected final boolean superKey(int key, int scan, int mods) {
            return super.keyPressed(new net.minecraft.client.input.KeyEvent(key, scan, mods));
        }
        *///?}

        //? if <1.20.2 {
        @Override
        public boolean mouseScrolled(double mx, double my, double d) {
            return onScroll(mx, my, d);
        }

        protected final boolean superScroll(double mx, double my, double d) {
            return super.mouseScrolled(mx, my, d);
        }
        //?} else {
        /*@Override
        public boolean mouseScrolled(double mx, double my, double dx, double dy) {
            return onScroll(mx, my, dy);
        }

        protected final boolean superScroll(double mx, double my, double d) {
            return super.mouseScrolled(mx, my, 0, d);
        }
        *///?}

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
