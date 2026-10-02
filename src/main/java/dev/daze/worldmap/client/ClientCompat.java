package dev.daze.worldmap.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.daze.worldmap.WorldMapMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.lang.reflect.Field;
import java.util.Map;

/** Различия клиентского API Minecraft между версиями. */
public final class ClientCompat {
    private ClientCompat() {}

    //? if >=1.21.9
    /*public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(WorldMapMod.id("main"));*/

    /** Cmd на macOS (в 26.3 ввод на SDL — другие коды модификаторов). */
    //? if <26.3 {
    public static final int MOD_SUPER = 8;
    //?} else
    /*public static final int MOD_SUPER = InputConstants.MOD_SUPER;*/

    public static KeyMapping key(String name, int glfwKey) {
        //? if <1.21.9 {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, glfwKey, "key.categories.worldmap");
        //?} else
        /*return new KeyMapping(name, glfwKey, CATEGORY);*/
    }

    public static boolean matches(KeyMapping k, int key, int scan) {
        //? if <1.21.9 {
        return k.matches(key, scan);
        //?} else
        /*return k.matches(new net.minecraft.client.input.KeyEvent(key, scan, 0));*/
    }

    public static boolean isKeyDown(int key) {
        //? if <1.21.9 {
        return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), key);
        //?} elif <26.3 {
        /*return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), key);
        *///?} else
        /*return InputConstants.isKeyDown(key);*/
    }

    public static net.minecraft.client.gui.screens.Screen screen() {
        //? if <26.2 {
        return Minecraft.getInstance().screen;
        //?} else
        /*return Minecraft.getInstance().gui.screen();*/
    }

    public static void setScreen(net.minecraft.client.gui.screens.Screen s) {
        //? if <26.2 {
        Minecraft.getInstance().setScreen(s);
        //?} else
        /*Minecraft.getInstance().gui.setScreen(s);*/
    }

    /** Интерфейс скрыт клавишей F1. */
    public static boolean hudHidden() {
        //? if <26.2 {
        return Minecraft.getInstance().options.hideGui;
        //?} else
        /*return Minecraft.getInstance().gui.hud.isHidden();*/
    }

    public static com.mojang.blaze3d.pipeline.RenderTarget mainTarget() {
        //? if <26.2 {
        return Minecraft.getInstance().getMainRenderTarget();
        //?} else
        /*return Minecraft.getInstance().gameRenderer.mainRenderTarget();*/
    }

    /** Цвет биома для блока (трава, листва, вода…); -1 — без окраски. */
    public static int tint(net.minecraft.world.level.block.state.BlockState s, net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.core.BlockPos p) {
        //? if <26.1 {
        return Minecraft.getInstance().getBlockColors().getColor(s, level, p, 0);
        //?} else {
        /*var src = Minecraft.getInstance().getBlockColors().getTintSource(s, 0);
        return src == null ? -1 : src.colorInWorld(s, level, p);
        *///?}
    }

    public static void addChat(Component line) {
        //? if <26.1 {
        Minecraft.getInstance().gui.getChat().addMessage(line);
        //?} elif <26.2 {
        /*Minecraft.getInstance().gui.getChat().addClientSystemMessage(line);
        *///?} else
        /*Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(line);*/
    }

    /** Только цифры (и минус) в поле ввода координат. */
    public static void numericOnly(net.minecraft.client.gui.components.EditBox e) {
        //? if <26.1
        e.setFilter(s -> s.matches("-?\\d{0,8}"));
    }

    public static boolean debugShown() {
        //? if <1.20.2 {
        return Minecraft.getInstance().options.renderDebug;
        //?} else
        /*return Minecraft.getInstance().getDebugOverlay().showDebugScreen();*/
    }

    /** Отложить на следующий кадр (например, смена экрана из обработчика ввода). */
    public static void later(Runnable r) {
        //? if <1.21.2 {
        Minecraft.getInstance().tell(r);
        //?} else
        /*Minecraft.getInstance().schedule(r);*/
    }

    public static Item item(ResourceLocation id) {
        //? if <1.21.2 {
        return BuiltInRegistries.ITEM.get(id);
        //?} else
        /*return BuiltInRegistries.ITEM.getValue(id);*/
    }

    public static Style clickToRun(Style style, String command, Component hover) {
        //? if <1.21.5 {
        return style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));
        //?} else {
        /*return style.withClickEvent(new ClickEvent.RunCommand(command)).withHoverEvent(new HoverEvent.ShowText(hover));
        *///?}
    }

    // ---------- текстуры ----------

    public static DynamicTexture texture(NativeImage img) {
        //? if <1.21.5 {
        return new DynamicTexture(img);
        //?} else
        /*return new DynamicTexture(() -> WorldMapMod.MODID + " tile", img);*/
    }

    /** Пиксель в формате ABGR (как хранит NativeImage). */
    public static void setPixel(NativeImage img, int x, int y, int abgr) {
        //? if <1.21.2 {
        img.setPixelRGBA(x, y, abgr);
        //?} else
        /*img.setPixel(x, y, argbToAbgr(abgr));*/
    }

    public static int getPixel(NativeImage img, int x, int y) {
        //? if <1.21.2 {
        return img.getPixelRGBA(x, y);
        //?} else
        /*return argbToAbgr(img.getPixel(x, y));*/
    }

    public static int argbToAbgr(int c) {
        return c & 0xFF00FF00 | (c & 0xFF) << 16 | (c >> 16) & 0xFF;
    }

    /** Глубину чистим, чтобы окно поверх карты не пряталось за её иконками (нужно, пока в GUI есть буфер глубины). */
    public static void clearDepth() {
        //? if <1.21.2 {
        com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        //?} elif <1.21.5
        /*com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT);*/
    }

    // ---------- приватные поля через рефлексию по типу (имена различаются между лоадерами) ----------

    private static Field bossEvents, spriteImage;

    public static int bossBars() {
        try {
            //? if <26.2 {
            BossHealthOverlay o = Minecraft.getInstance().gui.getBossOverlay();
            //?} else
            /*BossHealthOverlay o = Minecraft.getInstance().gui.hud.getBossOverlay();*/
            if (bossEvents == null) bossEvents = field(BossHealthOverlay.class, Map.class);
            return ((Map<?, ?>) bossEvents.get(o)).size();
        } catch (Exception e) {
            return 0;
        }
    }

    public static NativeImage originalImage(SpriteContents c) {
        try {
            if (spriteImage == null) spriteImage = field(SpriteContents.class, NativeImage.class);
            return (NativeImage) spriteImage.get(c);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field field(Class<?> owner, Class<?> type) throws NoSuchFieldException {
        for (Field f : owner.getDeclaredFields())
            if (f.getType() == type && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                return f;
            }
        throw new NoSuchFieldException(owner.getName() + " " + type.getName());
    }
}
