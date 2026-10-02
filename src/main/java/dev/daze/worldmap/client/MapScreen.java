package dev.daze.worldmap.client;

import dev.daze.worldmap.Mark;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Полноэкранная карта без панелей: пергамент, текстурная карта, метки-ромбы, игроки, пинги.
 * В углу — маленькие кнопки-иконки, справа — узкий список меток с поиском.
 * Клик по метке — карточка; предложенная в чате точка — призрачная метка с кнопкой «Добавить».
 * Открытие/закрытие — облака; телепорт — ромб вырастает на весь экран и белая вспышка.
 */
public class MapScreen extends Widgets.UiScreen {
    private static final int SIDEBAR = 128, M = 6, TOOL = 18;
    private static final float MIN_ZOOM = 0.12f, MAX_ZOOM = 16f;

    private enum Phase { OPENING, IDLE, CLOSING, TRAVEL }

    private Phase phase = Phase.OPENING;
    private long phaseStart = System.nanoTime(), lastFrame = System.nanoTime();
    private final boolean fast = ClientConfig.get().fastAnimations;

    private String viewDim;
    private double camX, camZ, velX, velZ;
    private float zoom, targetZoom;
    private double anchorX, anchorY;
    private boolean follow, dragging;
    private double moved;

    private Mark hovered, selected, travelTo;
    /** Точка, которой поделились в чате, — ещё не добавлена. */
    private Mark proposal;
    private UUID lastClickId;
    private long lastClickTime;
    private Component tooltip;
    private double travelFromX, travelFromZ;
    private float travelFromZoom;
    private final Map<UUID, Float> hoverAnim = new HashMap<>();
    private final float[][] motes = new float[40][5];

    // Интерфейс в немедленном режиме: области, собранные за прошлый кадр.
    private record Hit(int x0, int y0, int x1, int y1, Runnable left, Runnable right) {}

    private List<Hit> hits = new ArrayList<>(), building = new ArrayList<>();

    private record MenuItem(Component label, Runnable action, boolean danger) {}

    private int menuX, menuY;
    private List<MenuItem> menu;

    private EditBox search;
    private float listScroll;

    public MapScreen() {
        this(null, null);
    }

    /** Открыть карту на точке (например, из чата). */
    public MapScreen(Mark focusOn, String dim) {
        super(Component.translatable("worldmap.title"));
        Minecraft mc = Minecraft.getInstance();
        viewDim = dim != null ? dim : Actions.currentDim();
        camX = focusOn != null ? focusOn.x + 0.5 : mc.player.getX();
        camZ = focusOn != null ? focusOn.z + 0.5 : mc.player.getZ();
        targetZoom = focusOn != null ? Math.max(ClientConfig.get().mapZoom, 2.5f) : ClientConfig.get().mapZoom;
        zoom = targetZoom * (fast ? 1f : 0.72f);
        Random r = new Random();
        for (float[] m : motes) {
            m[0] = r.nextFloat();
            m[1] = r.nextFloat();
            m[2] = 2 + r.nextInt(3);
            m[3] = 0.004f + r.nextFloat() * 0.01f;
            m[4] = r.nextFloat() * 6.28f;
        }
        Actions.sound(SoundEvents.BOOK_PAGE_TURN, 0.9f);
    }

    /** Карта с предложением добавить чужую точку. */
    public static MapScreen proposal(Mark shared) {
        MapScreen s = new MapScreen(shared, shared.dim);
        s.proposal = shared;
        return s;
    }

    /** Вернуться на карту из редактора/настроек без анимации открытия. */
    MapScreen resumed() {
        phase = Phase.IDLE;
        return this;
    }

    @Override
    protected void build() {
        String old = search != null ? search.getValue() : "";
        search = new EditBox(font, uw - SIDEBAR - M + 5, M + 5, SIDEBAR - 10, 13, Component.translatable("worldmap.search"));
        search.setHint(Component.translatable("worldmap.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(40);
        search.setValue(old);
        search.setResponder(s -> listScroll = 0);
        search.visible = ClientConfig.get().sidebar;
        addRenderableWidget(search);
    }

    private float t() {
        return (System.nanoTime() - phaseStart) / 1e9f;
    }

    private void phase(Phase p) {
        phase = p;
        phaseStart = System.nanoTime();
    }

    private Session.Dim dim() {
        return Session.current.dim(viewDim);
    }

    private boolean viewingHere() {
        return viewDim.equals(Actions.currentDim());
    }

    private boolean sidebarOpen() {
        return ClientConfig.get().sidebar;
    }

    /** Прозрачность интерфейса: появляется после облаков, тает при телепорте. */
    private float uiAlpha() {
        if (phase == Phase.OPENING) return fast ? 1 : UI.smooth((t() - 0.3f) / 0.35f);
        if (phase == Phase.TRAVEL) return 1 - UI.smooth((t() - 0.2f) / 0.3f);
        return 1;
    }

    // ---------- кадр ----------

    @Override
    protected boolean beforeFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (Session.current == null || mc.player == null) {
            ClientCompat.setScreen(null);
            return false;
        }
        if (phase == Phase.CLOSING && t() >= (fast ? 0.12f : 0.3f)) {
            ClientCompat.setScreen(null);
            Hud.startExit(false, null);
            return false;
        }
        if (phase == Phase.TRAVEL && t() >= 1.2f) {
            finishTravel();
            return false;
        }
        search.visible = sidebarOpen() && uiAlpha() > 0.5f;
        search.setX(uw - SIDEBAR - M + 5);
        List<Hit> swap = hits;
        hits = building;
        building = swap;
        building.clear();
        tooltip = null;
        return true;
    }

    @Override
    protected void renderUnder(Gfx g, int mx, int my, float pt) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        float t = t();
        step(dt, t);
        if (phase == Phase.OPENING && t >= (fast ? 0.15f : 0.81f)) phase(Phase.IDLE);
        if (phase == Phase.OPENING && !fast && t < 0.16f) return; // облака ещё наползают на мир

        Session.Dim d = dim();
        d.tiles.absorbChanges();
        d.tiles.pump();
        drawParchment(g, t);
        float mapAlpha = phase == Phase.TRAVEL ? 1 - UI.smooth((t - 0.35f) / 0.4f) : 1f;
        if (fast && phase == Phase.OPENING) mapAlpha = UI.smooth(t / 0.15f);
        drawTiles(g, d, now, mapAlpha);
        if (ClientConfig.get().grid) drawGrid(g, mapAlpha);

        boolean overUi = overUi(mx / u, my / u);
        hovered = phase == Phase.IDLE && menu == null && !overUi ? pick(mx, my) : null;
        drawPings(g, mapAlpha);
        drawPlayers(g, mapAlpha, mx, my, overUi);
        drawMarks(g, mapAlpha, t, dt);
        if (proposal != null && proposal.dim.equals(viewDim)) drawProposal(g, mapAlpha, t);
        if (viewingHere()) drawSelf(g, mapAlpha, t);
        if (phase == Phase.TRAVEL) drawTravel(g, t);

        if (!d.data.loaded) UI.outlined(g, Component.translatable("worldmap.loading").getString(), width / 2f, height / 2f, u, 1, UI.CREAM, 300);
        else if (d.data.size() == 0) UI.outlined(g, Component.translatable("worldmap.empty").getString(), width / 2f, height / 2f, u, 1, UI.CREAM, 300);
    }

    @Override
    protected void renderUi(Gfx g, int mx, int my, float pt) {
        float a = uiAlpha();
        if (a <= 0.02f) return;
        g.alpha(a);
        drawToolbar(g, mx, my);
        drawInfo(g, mx, my);
        if (sidebarOpen()) drawSidebar(g, mx, my);
        if (proposal != null && phase != Phase.TRAVEL) drawProposalCard(g, mx, my);
        else if (selected != null && phase != Phase.TRAVEL) drawCard(g, mx, my);
        if (menu != null) drawMenu(g, mx, my);
        g.alpha(1);
    }

    @Override
    protected void renderOver(Gfx g, int mx, int my, float pt) {
        float t = t();
        if (tooltip != null && menu == null) g.tooltip(tooltip, mx, my);
        if (phase == Phase.TRAVEL) {
            float white = UI.smooth((t - 0.8f) / 0.35f);
            if (white > 0) {
                g.push();
                g.translate(0, 0, 200);
                g.fill(0, 0, uw, uh, UI.argb(white, 0xFFFDF5));
                g.pop();
            }
        }
        float cover = 0;
        if (!fast) {
            if (phase == Phase.OPENING) cover = t < 0.16f ? UI.easeOut(t / 0.16f) : 1 - UI.easeOut((t - 0.16f) / 0.65f);
            else if (phase == Phase.CLOSING) cover = UI.easeOut(t / 0.28f);
        } else if (phase == Phase.CLOSING) {
            g.push();
            g.translate(0, 0, 250);
            g.fill(0, 0, uw, uh, UI.argb(UI.smooth(t / 0.12f), 0xEBDDB6));
            g.pop();
        }
        if (cover > 0) {
            g.push();
            g.translate(0, 0, 250);
            Clouds.draw(g, uw, uh, cover, t, 1f, 1f);
            g.pop();
        }
    }

    /** Плавный зум к курсору, инерция, WASD, слежение за игроком, полёт к метке при телепорте. */
    private void step(float dt, float t) {
        Minecraft mc = Minecraft.getInstance();
        if (phase == Phase.TRAVEL) {
            float p = UI.smooth(t / 0.55f);
            camX = Mth.lerp(p, travelFromX, travelTo.x + 0.5);
            camZ = Mth.lerp(p, travelFromZ, travelTo.z + 0.5);
            zoom = Mth.lerp(p, travelFromZoom, Math.max(travelFromZoom, 3.5f));
            return;
        }
        if (!search.isFocused() && phase == Phase.IDLE) {
            double speed = 420 / zoom * dt;
            double kx = 0, kz = 0;
            if (ClientCompat.isKeyDown(InputConstants.KEY_A) || ClientCompat.isKeyDown(InputConstants.KEY_LEFT)) kx -= 1;
            if (ClientCompat.isKeyDown(InputConstants.KEY_D) || ClientCompat.isKeyDown(InputConstants.KEY_RIGHT)) kx += 1;
            if (ClientCompat.isKeyDown(InputConstants.KEY_W) || ClientCompat.isKeyDown(InputConstants.KEY_UP)) kz -= 1;
            if (ClientCompat.isKeyDown(InputConstants.KEY_S) || ClientCompat.isKeyDown(InputConstants.KEY_DOWN)) kz += 1;
            if (kx != 0 || kz != 0) {
                camX += kx * speed;
                camZ += kz * speed;
                follow = false;
            }
        }
        if (follow && viewingHere() && !dragging) {
            float k = 1 - (float) Math.exp(-dt * 8);
            camX += (mc.player.getX() - camX) * k;
            camZ += (mc.player.getZ() - camZ) * k;
        }
        double wx = camX + (anchorX - width / 2.0) / zoom, wz = camZ + (anchorY - height / 2.0) / zoom;
        float k = 1 - (float) Math.exp(-dt * (phase == Phase.OPENING ? 5 : 14));
        zoom = zoom + (targetZoom - zoom) * k;
        if (!follow) {
            camX = wx - (anchorX - width / 2.0) / zoom;
            camZ = wz - (anchorY - height / 2.0) / zoom;
        }
        if (!dragging) {
            camX += velX * dt;
            camZ += velZ * dt;
            float f = (float) Math.exp(-dt * 6);
            velX *= f;
            velZ *= f;
        }
        if (phase == Phase.OPENING) {
            anchorX = width / 2.0;
            anchorY = height / 2.0;
        }
    }

    // ---------- слои карты (экранные координаты) ----------

    private void drawParchment(Gfx g, float t) {
        g.gradient(0, 0, width, height, 0xFFEBDDB6, 0xFFE2D1A5);
        int steps = 14;
        for (int i = 0; i < steps; i++) {
            int c = (int) (26 * (1 - i / (float) steps)) << 24 | 0x9C7F48;
            int bx = i * width / 70, by = i * height / 50, sw = Math.max(1, width / 70), sh = Math.max(1, height / 50);
            g.fill(bx, by, bx + sw, height - by, c);
            g.fill(width - bx - sw, by, width - bx, height - by, c);
            g.fill(bx + sw, by, width - bx - sw, by + sh, c);
            g.fill(bx + sw, height - by - sh, width - bx - sw, height - by, c);
        }
        for (float[] m : motes) {
            m[1] -= m[3] * 0.016f;
            if (m[1] < -0.02f) m[1] = 1.02f;
            float a = 0.35f + 0.35f * Mth.sin(t * 1.3f + m[4]);
            int x = (int) (m[0] * width), y = (int) (m[1] * height), s = (int) (m[2] * u);
            g.fill(x, y, x + s, y + s, UI.argb(a, 0xFBF4E0));
        }
    }

    private void drawTiles(Gfx g, Session.Dim d, long now, float alpha) {
        int t0x = Math.floorDiv((int) Math.floor(camX - width / 2.0 / zoom), MapTiles.TILE);
        int t1x = Math.floorDiv((int) Math.floor(camX + width / 2.0 / zoom), MapTiles.TILE);
        int t0z = Math.floorDiv((int) Math.floor(camZ - height / 2.0 / zoom), MapTiles.TILE);
        int t1z = Math.floorDiv((int) Math.floor(camZ + height / 2.0 / zoom), MapTiles.TILE);
        g.alpha(alpha);
        List<int[]> order = new ArrayList<>();
        for (int tz = t0z; tz <= t1z; tz++) for (int tx = t0x; tx <= t1x; tx++) order.add(new int[]{tx, tz});
        double ctx = camX / MapTiles.TILE - 0.5, ctz = camZ / MapTiles.TILE - 0.5;
        order.sort(Comparator.comparingDouble(a -> Math.hypot(a[0] - ctx, a[1] - ctz)));
        for (int[] tt : order) {
            ResourceLocation id = d.tiles.get(tt[0], tt[1], now);
            if (id == null) continue;
            g.push();
            g.translate((float) sx(tt[0] * MapTiles.TILE), (float) sy(tt[1] * MapTiles.TILE));
            g.scale(zoom);
            g.blit(id, 0, 0, MapTiles.TILE, MapTiles.TILE, MapTiles.SIZE, MapTiles.SIZE);
            g.pop();
        }
        g.alpha(1);
        d.tiles.evict(now, 60_000_000_000L);
    }

    private void drawGrid(Gfx g, float a) {
        int step = zoom >= 1.2f ? 16 : 512;
        if (step * zoom < 6) return;
        int color = UI.argb(0.18f * a, 0x2A1F14);
        for (double x = Math.floor(wx(0) / step) * step; x <= wx(width); x += step) {
            int px = (int) Math.round(sx(x));
            g.fill(px, 0, px + 1, height, color);
        }
        for (double z = Math.floor(wz(0) / step) * step; z <= wz(height); z += step) {
            int py = (int) Math.round(sy(z));
            g.fill(0, py, width, py + 1, color);
        }
    }

    private List<Mark> visibleMarks() {
        List<Mark> out = new ArrayList<>();
        for (Mark m : Session.current.allMarks()) if (m.dim.equals(viewDim)) out.add(m);
        return out;
    }

    private void drawPings(Gfx g, float mapAlpha) {
        long now = System.currentTimeMillis();
        for (Session.Ping p : Session.current.pings) {
            if (!p.dim().equals(viewDim)) continue;
            float age = (now - p.time()) / 1000f;
            float a = mapAlpha * (1 - UI.smoothstep(12, 15, age));
            float x = (float) sx(p.x() + 0.5), y = (float) sy(p.z() + 0.5);
            for (int i = 0; i < 2; i++) {
                float ph = (age * 0.8f + i * 0.5f) % 1f;
                UI.diamondRing(g, x, y, (4 + ph * 16) * u, UI.argb(a * (1 - ph), 0xFF5A3C));
            }
            UI.diamondFill(g, x, y, 4 * u, UI.argb(a, 0xFF5A3C));
            UI.outlined(g, p.from(), x, y + 8 * u, u, a, 0xFFD0B0, 40);
        }
    }

    /** Другие игроки: видимые клиенту + присланные сервером. */
    private void drawPlayers(Gfx g, float mapAlpha, int mx, int my, boolean overUi) {
        if (!ClientConfig.get().showPlayers || mapAlpha < 0.05f || !viewingHere()) return;
        Minecraft mc = Minecraft.getInstance();
        Map<UUID, double[]> pos = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        for (Session.Remote r : Session.current.remote.values()) {
            pos.put(r.id(), new double[]{r.x(), r.z()});
            names.put(r.id(), r.name());
        }
        for (Player p : mc.level.players()) {
            if (p == mc.player) continue;
            pos.put(p.getUUID(), new double[]{p.getX(), p.getZ()});
            names.put(p.getUUID(), dev.daze.worldmap.Compat.name(p));
        }
        for (var e : pos.entrySet()) {
            float x = (float) sx(e.getValue()[0]), y = (float) sy(e.getValue()[1]);
            if (x < -20 || y < -20 || x > width + 20 || y > height + 20) continue;
            String name = names.get(e.getKey());
            g.push();
            g.translate(x, y, 60);
            g.scale(u * 0.85f, u * 0.85f);
            g.fill(-6, -6, 6, 6, UI.argb(mapAlpha, 0x1A1410));
            g.fill(-5, -5, 5, 5, UI.argb(mapAlpha, 0x7AD0F0));
            PlayerInfo info = mc.getConnection() != null ? mc.getConnection().getPlayerInfo(e.getKey()) : null;
            if (info != null) g.face(info, -4, -4, 8);
            else g.fill(-4, -4, 4, 4, 0xFF555555);
            g.pop();
            boolean hot = !overUi && Math.abs(mx - x) < 7 * u && Math.abs(my - y) < 7 * u;
            if (hot) tooltip = Component.literal(name + " · " + UI.distance(Math.hypot(e.getValue()[0] - mc.player.getX(), e.getValue()[1] - mc.player.getZ())));
            if (zoom >= 1.2f || hot) UI.outlined(g, name, x, y + 7 * u, u * 0.8f, mapAlpha, 0xBFE8F7, 61);
        }
    }

    private void drawSelf(Gfx g, float mapAlpha, float t) {
        if (mapAlpha < 0.05f) return;
        Minecraft mc = Minecraft.getInstance();
        float x = (float) sx(mc.player.getX()), y = (float) sy(mc.player.getZ());
        g.push();
        g.translate(x, y, 50);
        g.scale(u, u);
        float p = (t * 0.7f) % 1f;
        UI.diamondRing(g, 0, 0, 6 + p * 10, UI.argb((1 - p) * 0.8f * mapAlpha, 0xFFFFFF));
        g.push();
        g.rotate(mc.player.getYRot() + 180);
        int ac = UI.argb(mapAlpha, 0xFFFFFF);
        g.fill(-1, -11, 1, -9, ac);
        g.fill(-2, -9, 2, -7, ac);
        g.fill(-3, -7, 3, -6, ac);
        g.pop();
        g.fill(-6, -6, 6, 6, UI.argb(mapAlpha, 0x1A1410));
        g.fill(-5, -5, 5, 5, UI.argb(mapAlpha, 0xFFFFFF));
        PlayerInfo self = mc.getConnection() != null ? mc.getConnection().getPlayerInfo(mc.player.getUUID()) : null;
        g.alpha(mapAlpha);
        if (self != null) g.face(self, -4, -4, 8);
        g.alpha(1);
        g.pop();
    }

    private float markScale() {
        return Mth.clamp(0.85f + zoom * 0.1f, 0.9f, 1.3f) * u;
    }

    private void drawMarks(Gfx g, float mapAlpha, float t, float dt) {
        int z = 100;
        float labelA = UI.smoothstep(0.9f, 1.7f, zoom);
        float base = markScale();
        Session s = Session.current;
        for (Mark m : visibleMarks()) {
            boolean travel = m == travelTo;
            float a = travel ? 1 : mapAlpha;
            if (a < 0.03f || (travel && t() > 0.6f)) continue;
            float x = (float) sx(m.x + 0.5), y = (float) sy(m.z + 0.5);
            if (x < -40 || y < -40 || x > width + 40 || y > height + 40) continue;
            boolean focus = m == hovered || travel || (selected != null && m.id.equals(selected.id));
            float h = hoverAnim.getOrDefault(m.id, 0f);
            h += ((focus ? 1 : 0) - h) * (1 - (float) Math.exp(-dt * 16));
            hoverAnim.put(m.id, h);
            float bob = Mth.sin(t * 2.2f + m.x * 0.13f) * 0.8f * (1 - h) * u;
            float sc = base * (1 + 0.28f * h + (travel ? 0.15f * Mth.sin(t * 18) * Math.max(0, 1 - t) : 0));
            UI.diamond(g, x, y + bob, sc, a, UI.icon(m), m.death ? 0xF07A6A : m.color, h, t, z);
            if (s.nav != null && s.nav.id.equals(m.id)) UI.diamondRing(g, x, y + bob, 14 * sc + Mth.sin(t * 4) * 1.5f, UI.argb(a * 0.9f, 0x7AE0FF));
            if (travel) sparkles(g, x, y, t, z + 120);
            float la = Math.max(labelA, h) * a;
            if (la > 0.03f) UI.outlined(g, m.name, x, y + bob + 12 * sc + 1, u, la, 0xFFF3D6, z + 160);
            z = Math.min(z + 40, 2400);
        }
    }

    /** Призрачная метка из чата: мерцает пунктиром, пока её не добавили. */
    private void drawProposal(Gfx g, float mapAlpha, float t) {
        float x = (float) sx(proposal.x + 0.5), y = (float) sy(proposal.z + 0.5);
        float sc = markScale() * (1.1f + 0.06f * Mth.sin(t * 3));
        float a = mapAlpha * (0.75f + 0.25f * Mth.sin(t * 3));
        for (int i = 0; i < 2; i++) {
            float ph = (t * 0.6f + i * 0.5f) % 1f;
            UI.diamondRing(g, x, y, (12 + ph * 14) * sc, UI.argb(mapAlpha * (1 - ph) * 0.8f, 0xFFE08A));
        }
        UI.diamond(g, x, y, sc, a, UI.icon(proposal), proposal.color, 0.6f, t, 2500);
        UI.outlined(g, proposal.name, x, y + 12 * sc + 1, u, mapAlpha, 0xFFE08A, 2700);
    }

    private void drawTravel(Gfx g, float t) {
        float p = Mth.clamp((t - 0.6f) / 0.55f, 0, 1);
        if (p <= 0) return;
        float s = u * (1 + 11 * p * p * p);
        float a = 1 - UI.smooth((p - 0.45f) / 0.55f);
        float x = Mth.lerp(p, (float) sx(travelTo.x + 0.5), width / 2f), y = Mth.lerp(p, (float) sy(travelTo.z + 0.5), height / 2f);
        g.push();
        g.translate(0, 0, 600);
        for (int i = 4; i > 0; i--) UI.diamondFill(g, x, y, (10 + i * 3) * s, UI.argb(a * 0.12f, 0xFFE9A8));
        UI.diamond(g, x, y, s, a, UI.icon(travelTo), travelTo.color, 1, t, 0);
        g.pop();
    }

    private static void sparkles(Gfx g, float x, float y, float t, int z) {
        g.push();
        g.translate(x, y, z);
        for (int i = 0; i < 10; i++) {
            float ang = i * 2.399f + t * 1.5f, life = (t * 1.6f + i * 0.37f) % 1f, r = 8 + life * 18;
            int px = Math.round(Mth.cos(ang) * r), py = Math.round(Mth.sin(ang) * r);
            int c = UI.argb(Mth.sin(life * Mth.PI), i % 3 == 0 ? 0xFFFFFF : 0xFFE17A);
            g.fill(px - 1, py, px + 2, py + 1, c);
            g.fill(px, py - 1, px + 1, py + 2, c);
        }
        g.pop();
    }

    // ---------- интерфейс (UI-координаты) ----------

    private void block(int x0, int y0, int x1, int y1) {
        building.add(new Hit(x0, y0, x1, y1, null, null));
    }

    private boolean button(Gfx g, int x, int y, int w, int h, Component label, int mx, int my, boolean enabled, boolean on, Runnable action) {
        boolean hot = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x, y, x + w, y + h, on ? 0xFFFFE08A : hot ? UI.GOLD : 0xFF6B5A44);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, on ? 0xFF4A3A26 : hot ? 0xFF3E3123 : 0xFF2F261C);
        g.centered(label, x + w / 2, y + (h - 8) / 2, !enabled ? UI.MUTED : on || hot ? 0xFFFFE08A : UI.TEXT);
        if (enabled) building.add(new Hit(x, y, x + w, y + h, () -> {
            Actions.click();
            action.run();
        }, null));
        return hot;
    }

    /** Квадратная кнопка с иконкой предмета и подсказкой при наведении. */
    private void tool(Gfx g, int x, int y, ItemStack icon, Component hint, int mx, int my, boolean on, Runnable action) {
        boolean hot = mx >= x && mx < x + TOOL && my >= y && my < y + TOOL;
        g.fill(x, y, x + TOOL, y + TOOL, on ? 0xFFFFE08A : hot ? UI.GOLD : 0xC06B5A44);
        g.fill(x + 1, y + 1, x + TOOL - 1, y + TOOL - 1, on ? 0xF04A3A26 : hot ? 0xF03E3123 : 0xE02B2219);
        g.push();
        g.translate(x + TOOL / 2f, y + TOOL / 2f, 0);
        g.scale(0.8f, 0.8f);
        g.item(icon, -8, -8);
        g.pop();
        if (hot) tooltip = hint;
        building.add(new Hit(x, y, x + TOOL, y + TOOL, () -> {
            Actions.click();
            action.run();
        }, null));
    }

    private void drawToolbar(Gfx g, int mx, int my) {
        int x = M, y = M;
        tool(g, x, y, new ItemStack(Items.WRITABLE_BOOK), Component.translatable("worldmap.marks"), mx, my, sidebarOpen(), this::toggleSidebar);
        x += TOOL + 3;
        if (viewingHere()) {
            tool(g, x, y, new ItemStack(Items.COMPASS), Component.translatable("worldmap.follow"), mx, my, follow, () -> follow = !follow);
            x += TOOL + 3;
        } else {
            tool(g, x, y, new ItemStack(Items.RECOVERY_COMPASS), Component.translatable("worldmap.back_home"), mx, my, false, () -> switchDim(Actions.currentDim()));
            x += TOOL + 3;
        }
        List<String> dims = Session.current.knownDims();
        if (dims.size() > 1) {
            int i = Math.max(0, dims.indexOf(viewDim));
            String next = dims.get((i + 1) % dims.size());
            ItemStack icon = new ItemStack(switch (viewDim) {
                case "minecraft:the_nether" -> Items.NETHERRACK;
                case "minecraft:the_end" -> Items.END_STONE;
                default -> Items.GRASS_BLOCK;
            });
            tool(g, x, y, icon, UI.dimName(viewDim).copy().append(" → ").append(UI.dimName(next)), mx, my, false, () -> switchDim(next));
            x += TOOL + 3;
        }
        tool(g, x, y, new ItemStack(Items.COMPARATOR), Component.translatable("worldmap.options"), mx, my, false,
                () -> ClientCompat.setScreen(new SettingsScreen(this)));
    }

    /** Едва заметная строка под картой: координаты, высота и биом под курсором. */
    private void drawInfo(Gfx g, int mx, int my) {
        double cx = wx(mx * u), cz = wz(my * u);
        int bx = (int) Math.floor(cx), bz = (int) Math.floor(cz);
        Session.Dim d = dim();
        int hgt = d.data.heightAt(bx, bz);
        String info = bx + ", " + (hgt != Integer.MIN_VALUE ? hgt + ", " : "") + bz;
        String biome = UI.biomeName(d.data.biomeAt(bx, bz)).getString();
        if (!biome.isEmpty()) info += "  ·  " + biome;
        g.push();
        g.translate(M + 1, uh - M - 6, 0);
        g.scale(0.75f, 0.75f);
        g.text(info, 0, 0, 0xB02A1F14, false);
        g.pop();
    }

    private List<Mark> listed() {
        Minecraft mc = Minecraft.getInstance();
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        List<Mark> out = new ArrayList<>();
        for (Mark m : Session.current.allMarks()) if (q.isEmpty() || m.name.toLowerCase(Locale.ROOT).contains(q)) out.add(m);
        String here = Actions.currentDim();
        out.sort(Comparator.<Mark>comparingInt(m -> m.dim.equals(here) ? 0 : 1)
                .thenComparingDouble(m -> Math.hypot(m.x - mc.player.getX(), m.z - mc.player.getZ())));
        return out;
    }

    private void drawSidebar(Gfx g, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        int x0 = uw - SIDEBAR - M, x1 = uw - M, y0 = M, y1 = uh - M;
        UI.panel(g, x0, y0, x1, y1);
        block(x0 - 1, y0 - 1, x1 + 1, y1 + 1);
        int ly0 = y0 + 23, ly1 = y1 - 20, rowH = 20;
        List<Mark> list = listed();
        int maxScroll = Math.max(0, list.size() * rowH - (ly1 - ly0));
        listScroll = Mth.clamp(listScroll, 0, maxScroll);
        g.scissor(x0, ly0, x1, ly1, u);
        String here = Actions.currentDim();
        for (int i = 0; i < list.size(); i++) {
            Mark m = list.get(i);
            int ry = ly0 + i * rowH - (int) listScroll;
            if (ry + rowH < ly0 || ry > ly1) continue;
            boolean hot = mx >= x0 && mx < x1 && my >= Math.max(ry, ly0) && my < Math.min(ry + rowH, ly1);
            boolean sel = selected != null && selected.id.equals(m.id);
            if (sel || hot) g.fill(x0 + 2, ry + 1, x1 - 2, ry + rowH - 1, sel ? 0x50FFE08A : UI.HOVER);
            UI.diamond(g, x0 + 12, ry + 10, 0.75f, 1, UI.icon(m), m.death ? 0xF07A6A : m.color, 0, 0, 0);
            g.push();
            g.translate(0, 0, 200);
            g.text(font.plainSubstrByWidth(m.name, SIDEBAR - 30), x0 + 24, ry + 2, sel ? 0xFFFFE08A : UI.CREAM, false);
            String sub = m.dim.equals(here) ? UI.distance(Math.hypot(m.x - mc.player.getX(), m.z - mc.player.getZ())) : UI.dimName(m.dim).getString();
            if (m.pub) sub += " · " + Component.translatable("worldmap.shared").getString();
            if (Session.current.nav != null && Session.current.nav.id.equals(m.id)) sub += " · »";
            g.translate(x0 + 24, ry + 11, 0);
            g.scale(0.75f, 0.75f);
            g.text(sub, 0, 0, UI.MUTED, false);
            g.pop();
            int top = Math.max(ry, ly0), bottom = Math.min(ry + rowH, ly1);
            building.add(new Hit(x0, top, x1, bottom, () -> clickEntry(m), () -> openMarkMenu(m, (int) (mx * u), (int) (my * u))));
        }
        g.endScissor();
        if (list.isEmpty()) g.centered(Component.translatable("worldmap.list.empty"), (x0 + x1) / 2, ly0 + 8, UI.MUTED);
        if (maxScroll > 0) {
            int track = ly1 - ly0, bar = Math.max(12, track * track / (list.size() * rowH));
            int by = ly0 + (int) ((track - bar) * listScroll / maxScroll);
            g.fill(x1 - 3, by, x1 - 1, by + bar, 0x80D9B266);
        }
        button(g, x0 + 5, y1 - 17, SIDEBAR - 10, 13, Component.translatable("worldmap.add_here"), mx, my, true, false, () -> {
            Mark m = Actions.newMark(mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), here);
            ClientCompat.setScreen(new MarkEditScreen(this, m, true));
        });
    }

    private void clickEntry(Mark m) {
        Actions.click();
        long now = System.currentTimeMillis();
        boolean dbl = m.id.equals(lastClickId) && now - lastClickTime < 350;
        lastClickId = m.id;
        lastClickTime = now;
        if (!m.dim.equals(viewDim)) switchDim(m.dim);
        selected = m;
        follow = false;
        focus(m.x + 0.5, m.z + 0.5, Math.max(targetZoom, 2.5f));
        if (dbl) startTravel(m);
    }

    /** Позиция карточки возле точки на карте, не залезая под список. */
    private int[] cardPos(Mark m, int w, int h) {
        int ax = (int) (sx(m.x + 0.5) / u), ay = (int) (sy(m.z + 0.5) / u);
        int maxX = (sidebarOpen() ? uw - SIDEBAR - M - 4 : uw - M) - w;
        int x = ax + 18 <= maxX ? ax + 18 : ax - 18 - w;
        return new int[]{Mth.clamp(x, M, Math.max(M, maxX)), Mth.clamp(ay - h / 2, M + TOOL + 4, uh - h - M - 10)};
    }

    private void drawCard(Gfx g, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        Mark fresh = Session.current.allMarks().stream().filter(o -> o.id.equals(selected.id)).findFirst().orElse(null);
        if (fresh == null) {
            selected = null;
            return;
        }
        selected = fresh;
        Mark sel = selected;
        int w = 146, h = 60;
        int[] p = cardPos(sel, w, h);
        int x = p[0], y = p[1];
        g.push();
        g.translate(0, 0, 300);
        UI.panel(g, x, y, x + w, y + h);
        block(x - 1, y - 1, x + w + 1, y + h + 1);
        g.text(font.plainSubstrByWidth(sel.name, w - 10), x + 5, y + 4, 0xFF000000 | sel.color, false);
        boolean here = sel.dim.equals(Actions.currentDim());
        String dist = here ? UI.distance(Math.hypot(sel.x - mc.player.getX(), sel.z - mc.player.getZ())) : UI.dimName(sel.dim).getString();
        Component owner = sel.pub ? Component.translatable("worldmap.card.shared", sel.ownerName)
                : sel.death ? Component.translatable("worldmap.card.death") : Component.translatable("worldmap.card.private");
        g.push();
        g.translate(x + 5, y + 14, 0);
        g.scale(0.75f, 0.75f);
        g.text(sel.x + ", " + sel.y + ", " + sel.z + "  ·  " + dist + "  ·  " + owner.getString(), 0, 0, UI.MUTED, false);
        g.pop();
        Component blocker = Actions.teleportBlocker();
        int bw = (w - 10 - 4) / 3, by = y + 24;
        boolean tpHot = button(g, x + 5, by, bw, 13, Component.translatable("worldmap.action.go"), mx, my, blocker == null, false, () -> startTravel(sel));
        if (!tpHot && blocker != null && !blocker.getString().isEmpty() && mx >= x + 5 && mx < x + 5 + bw && my >= by && my < by + 13) tooltip = blocker;
        boolean navOn = Session.current.nav != null && Session.current.nav.id.equals(sel.id);
        button(g, x + 7 + bw, by, bw, 13, Component.translatable(navOn ? "worldmap.action.nav_off" : "worldmap.action.nav"), mx, my, here, navOn, () -> Actions.navigate(sel));
        button(g, x + 9 + 2 * bw, by, bw, 13, Component.translatable("worldmap.action.share"), mx, my, true, false, () -> Actions.share(sel));
        button(g, x + 5, by + 16, bw, 13, Component.translatable("worldmap.action.edit"), mx, my, Actions.canEdit(sel), false,
                () -> ClientCompat.setScreen(new MarkEditScreen(this, sel.copy(), false)));
        button(g, x + 7 + bw, by + 16, bw, 13, Component.translatable("worldmap.action.copy"), mx, my, true, false, () -> {
            minecraft.keyboardHandler.setClipboard(sel.x + " " + sel.y + " " + sel.z);
            Actions.toast(Component.translatable("worldmap.copied"));
        });
        button(g, x + 9 + 2 * bw, by + 16, bw, 13, Component.translatable("worldmap.action.delete").withStyle(ChatFormatting.RED), mx, my, Actions.canEdit(sel), false, () -> {
            Actions.delete(sel);
            selected = null;
        });
        g.pop();
    }

    /** Карточка точки, которой поделились: «Добавить», «Вести», «Скрыть». */
    private void drawProposalCard(Gfx g, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        Mark pr = proposal;
        int w = 164, h = 58;
        int[] p = cardPos(pr, w, h);
        int x = p[0], y = p[1];
        g.push();
        g.translate(0, 0, 300);
        UI.panel(g, x, y, x + w, y + h);
        block(x - 1, y - 1, x + w + 1, y + h + 1);
        String from = pr.ownerName == null || pr.ownerName.isEmpty() ? "?" : pr.ownerName;
        g.push();
        g.translate(x + 5, y + 4, 0);
        g.scale(0.75f, 0.75f);
        g.text(Component.translatable("worldmap.proposal.from", from), 0, 0, UI.MUTED, false);
        g.pop();
        g.text(font.plainSubstrByWidth(pr.name, w - 10), x + 5, y + 12, 0xFFFFE08A, false);
        boolean here = pr.dim.equals(Actions.currentDim());
        String dist = here ? UI.distance(Math.hypot(pr.x - mc.player.getX(), pr.z - mc.player.getZ())) : UI.dimName(pr.dim).getString();
        g.push();
        g.translate(x + 5, y + 22, 0);
        g.scale(0.75f, 0.75f);
        g.text(pr.x + ", " + pr.y + ", " + pr.z + "  ·  " + dist, 0, 0, UI.TEXT, false);
        g.pop();
        int bw = (w - 10 - 4) / 3, by = y + 38;
        button(g, x + 5, by, bw, 14, Component.translatable("worldmap.proposal.add"), mx, my, true, true, () -> {
            Mark m = Actions.newMark(pr.x, pr.y, pr.z, pr.dim);
            m.name = pr.name;
            m.icon = pr.icon;
            m.color = Mark.COLORS[1];
            Actions.save(m);
            Actions.sound(SoundEvents.VILLAGER_WORK_CARTOGRAPHER, 1f);
            Actions.toast(Component.translatable("worldmap.chat.added", m.name).withStyle(ChatFormatting.GREEN));
            proposal = null;
            selected = m;
        });
        button(g, x + 7 + bw, by, bw, 14, Component.translatable("worldmap.action.nav"), mx, my, here, false, () -> {
            Mark m = pr.copy();
            Session.current.nav = m;
            Actions.toast(Component.translatable("worldmap.nav.on", m.name).withStyle(ChatFormatting.AQUA));
        });
        button(g, x + 9 + 2 * bw, by, bw, 14, Component.translatable("worldmap.proposal.dismiss"), mx, my, true, false, () -> proposal = null);
        g.pop();
    }

    private void drawMenu(Gfx g, int mx, int my) {
        int w = 60;
        for (MenuItem i : menu) w = Math.max(w, font.width(i.label()) + 14);
        int n = menu.size();
        int x = Math.min(menuX, uw - w - 2), y = Math.min(menuY, uh - n * 13 - 8);
        g.push();
        g.translate(0, 0, 400);
        UI.panel(g, x, y, x + w, y + n * 13 + 6);
        block(x - 1, y - 1, x + w + 1, y + n * 13 + 7);
        for (int i = 0; i < n; i++) {
            int iy = y + 3 + i * 13;
            boolean hot = mx >= x && mx < x + w && my >= iy && my < iy + 13;
            if (hot) g.fill(x + 2, iy, x + w - 2, iy + 13, UI.HOVER);
            MenuItem it = menu.get(i);
            g.text(it.label(), x + 7, iy + 3, it.danger() ? 0xFFF07A6A : hot ? 0xFFFFE08A : UI.TEXT, false);
            building.add(new Hit(x, iy, x + w, iy + 13, () -> {
                menu = null;
                Actions.click();
                it.action().run();
            }, null));
        }
        g.pop();
    }

    private boolean overUi(double umx, double umy) {
        for (Hit h : hits) if (umx >= h.x0 && umx < h.x1 && umy >= h.y0 && umy < h.y1) return true;
        return search.visible && search.isMouseOver(umx, umy);
    }

    // ---------- координаты ----------

    private double sx(double wx) {
        return width / 2.0 + (wx - camX) * zoom;
    }

    private double sy(double wz) {
        return height / 2.0 + (wz - camZ) * zoom;
    }

    private double wx(double sx) {
        return camX + (sx - width / 2.0) / zoom;
    }

    private double wz(double sy) {
        return camZ + (sy - height / 2.0) / zoom;
    }

    private Mark pick(double mx, double my) {
        Mark best = null;
        double bd = 0;
        float s = markScale();
        for (Mark m : visibleMarks()) {
            double d = (Math.abs(mx - sx(m.x + 0.5)) + Math.abs(my - sy(m.z + 0.5))) / (12 * s);
            if (d > 1) continue;
            if (best == null || d < bd) {
                best = m;
                bd = d;
            }
        }
        return best;
    }

    // ---------- ввод ----------

    @Override
    protected boolean onMouseDown(double mx, double my, int button) {
        if (phase == Phase.CLOSING || phase == Phase.TRAVEL) return true;
        double umx = mx / u, umy = my / u;
        if (search.visible && search.isMouseOver(umx, umy)) return super.onMouseDown(mx, my, button);
        search.setFocused(false);
        setFocused(null);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (umx >= h.x0 && umx < h.x1 && umy >= h.y0 && umy < h.y1) {
                Runnable r = button == 0 ? h.left : button == 1 ? h.right : null;
                if (r != null) r.run();
                else if (menu != null && h.left == null) menu = null;
                return true;
            }
        }
        if (menu != null) {
            menu = null;
            return true;
        }
        if (button == 0) {
            dragging = true;
            moved = 0;
            velX = velZ = 0;
            return true;
        }
        if (button == 1) {
            openMapMenu((int) mx, (int) my);
            return true;
        }
        if (button == 2 && viewingHere()) {
            int bx = (int) Math.floor(wx(mx)), bz = (int) Math.floor(wz(my));
            int y = dim().data.heightAt(bx, bz);
            Actions.ping(bx, y == Integer.MIN_VALUE ? minecraft.player.getBlockY() : y, bz);
        }
        return true;
    }

    @Override
    protected boolean onDrag(double mx, double my, int button, double dx, double dy) {
        if (dragging && button == 0) {
            camX -= dx / zoom;
            camZ -= dy / zoom;
            moved += Math.abs(dx) + Math.abs(dy);
            if (moved > 3) follow = false;
            velX = Mth.lerp(0.5, velX, -dx / zoom * 60);
            velZ = Mth.lerp(0.5, velZ, -dy / zoom * 60);
            return true;
        }
        return super.onDrag(mx, my, button, dx, dy);
    }

    @Override
    protected boolean onMouseUp(double mx, double my, int button) {
        if (dragging && button == 0) {
            dragging = false;
            if (moved < 4) {
                velX = velZ = 0;
                Mark m = pick(mx, my);
                long now = System.currentTimeMillis();
                if (m != null) {
                    boolean dbl = m.id.equals(lastClickId) && now - lastClickTime < 350;
                    lastClickId = m.id;
                    lastClickTime = now;
                    Actions.click();
                    if (dbl) startTravel(m);
                    else selected = m;
                } else selected = null;
            }
            return true;
        }
        return super.onMouseUp(mx, my, button);
    }

    @Override
    protected boolean onScroll(double mx, double my, double d) {
        if (phase != Phase.IDLE && phase != Phase.OPENING) return true;
        double umx = mx / u, umy = my / u;
        if (sidebarOpen() && umx >= uw - SIDEBAR - M && umx < uw - M && umy > M && umy < uh - M) {
            listScroll -= (float) d * 20;
            return true;
        }
        targetZoom = Mth.clamp(targetZoom * (float) Math.pow(1.2, d), MIN_ZOOM, MAX_ZOOM);
        anchorX = follow ? width / 2.0 : mx;
        anchorY = follow ? height / 2.0 : my;
        return true;
    }

    @Override
    protected boolean onKey(int key, int scan, int mods) {
        if (phase == Phase.CLOSING || phase == Phase.TRAVEL) return true;
        if (search.isFocused()) {
            if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_RETURN) {
                search.setFocused(false);
                setFocused(null);
                return true;
            }
            return superKey(key, scan, mods) || search.canConsumeInput();
        }
        if (key == InputConstants.KEY_ESCAPE && (menu != null || selected != null || proposal != null)) {
            menu = null;
            selected = null;
            proposal = null;
            return true;
        }
        if (key == InputConstants.KEY_ESCAPE || ClientCompat.matches(WorldMapClient.OPEN, key, scan)) {
            onClose();
            return true;
        }
        boolean ctrl = (mods & InputConstants.MOD_CONTROL) != 0 || (mods & ClientCompat.MOD_SUPER) != 0;
        if ((ctrl && key == InputConstants.KEY_F) || key == InputConstants.KEY_SLASH) {
            if (!sidebarOpen()) toggleSidebar();
            setFocused(search);
            search.setFocused(true);
            return true;
        }
        switch (key) {
            case InputConstants.KEY_SPACE -> {
                if (!viewingHere()) switchDim(Actions.currentDim());
                focus(minecraft.player.getX(), minecraft.player.getZ(), targetZoom);
            }
            case InputConstants.KEY_F -> follow = viewingHere() && !follow;
            case InputConstants.KEY_G -> {
                ClientConfig.get().grid = !ClientConfig.get().grid;
                ClientConfig.get().save();
            }
            case InputConstants.KEY_TAB -> toggleSidebar();
            case InputConstants.KEY_EQUALS, InputConstants.KEY_ADD -> zoomKey(1.4f);
            case InputConstants.KEY_MINUS -> zoomKey(1 / 1.4f);
            case InputConstants.KEY_DELETE, InputConstants.KEY_BACKSPACE -> {
                Mark m = hovered != null ? hovered : selected;
                if (m != null && Actions.canEdit(m)) {
                    Actions.delete(m);
                    if (m == selected) selected = null;
                }
            }
            case InputConstants.KEY_B -> {
                Mark m = Actions.newMark(minecraft.player.getBlockX(), minecraft.player.getBlockY(), minecraft.player.getBlockZ(), Actions.currentDim());
                ClientCompat.setScreen(new MarkEditScreen(this, m, true));
            }
            default -> {
                return super.onKey(key, scan, mods);
            }
        }
        return true;
    }

    private void zoomKey(float f) {
        targetZoom = Mth.clamp(targetZoom * f, MIN_ZOOM, MAX_ZOOM);
        anchorX = width / 2.0;
        anchorY = height / 2.0;
    }

    @Override
    public void onClose() {
        if (phase == Phase.CLOSING || phase == Phase.TRAVEL) return;
        Actions.sound(SoundEvents.BOOK_PAGE_TURN, 0.7f);
        phase(Phase.CLOSING);
    }

    @Override
    public void removed() {
        ClientConfig.get().mapZoom = targetZoom;
        ClientConfig.get().save();
    }

    private void toggleSidebar() {
        ClientConfig.get().sidebar = !ClientConfig.get().sidebar;
        ClientConfig.get().save();
        if (!sidebarOpen()) search.setFocused(false);
    }

    private void switchDim(String d) {
        if (d.equals(viewDim)) return;
        // Переход Верхний мир ⇄ Незер — с пересчётом координат 1:8.
        boolean toNether = d.equals("minecraft:the_nether") && viewDim.equals("minecraft:overworld");
        boolean fromNether = viewDim.equals("minecraft:the_nether") && d.equals("minecraft:overworld");
        if (toNether) {
            camX /= 8;
            camZ /= 8;
        } else if (fromNether) {
            camX *= 8;
            camZ *= 8;
        } else if (d.equals(Actions.currentDim())) {
            camX = minecraft.player.getX();
            camZ = minecraft.player.getZ();
        }
        viewDim = d;
        follow = false;
        selected = null;
    }

    private void focus(double x, double z, float zoomTo) {
        velX = (x - camX) * 6;
        velZ = (z - camZ) * 6;
        anchorX = width / 2.0;
        anchorY = height / 2.0;
        targetZoom = zoomTo;
    }

    // ---------- меню ----------

    private void openMapMenu(int mx, int my) {
        Mark target = pick(mx, my);
        if (target != null) {
            openMarkMenu(target, mx, my);
            return;
        }
        int bx = (int) Math.floor(wx(mx)), bz = (int) Math.floor(wz(my));
        int h = dim().data.heightAt(bx, bz);
        int by = h == Integer.MIN_VALUE ? minecraft.player.getBlockY() : h;
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(Component.translatable("worldmap.menu.point"), () ->
                ClientCompat.setScreen(new MarkEditScreen(this, Actions.newMark(bx, by, bz, viewDim), true)), false));
        if (viewingHere()) {
            items.add(new MenuItem(Component.translatable("worldmap.menu.nav_here"), () -> {
                Mark m = Actions.newMark(bx, by, bz, viewDim);
                m.name = bx + ", " + bz;
                Session.current.nav = m;
                Actions.toast(Component.translatable("worldmap.nav.on", m.name).withStyle(ChatFormatting.AQUA));
            }, false));
            items.add(new MenuItem(Component.translatable("worldmap.menu.ping"), () -> Actions.ping(bx, by, bz), false));
        }
        if (Actions.teleportBlocker() == null) items.add(new MenuItem(Component.translatable("worldmap.menu.tp_here"), () -> {
            Mark m = Actions.newMark(bx, by, bz, viewDim);
            m.name = bx + ", " + bz;
            startTravel(m);
        }, false));
        items.add(new MenuItem(Component.translatable("worldmap.menu.copy"), () -> {
            minecraft.keyboardHandler.setClipboard(bx + " " + by + " " + bz);
            Actions.toast(Component.translatable("worldmap.copied"));
        }, false));
        openMenu(mx, my, items);
    }

    private void openMarkMenu(Mark m, int mx, int my) {
        List<MenuItem> items = new ArrayList<>();
        boolean here = m.dim.equals(Actions.currentDim());
        if (Actions.teleportBlocker() == null) items.add(new MenuItem(Component.translatable("worldmap.action.go"), () -> startTravel(m), false));
        boolean navOn = Session.current.nav != null && Session.current.nav.id.equals(m.id);
        if (here) items.add(new MenuItem(Component.translatable(navOn ? "worldmap.action.nav_off" : "worldmap.action.nav"), () -> Actions.navigate(m), false));
        items.add(new MenuItem(Component.translatable("worldmap.action.share"), () -> Actions.share(m), false));
        if (Actions.canEdit(m)) {
            items.add(new MenuItem(Component.translatable("worldmap.action.edit"), () ->
                    ClientCompat.setScreen(new MarkEditScreen(this, m.copy(), false)), false));
            items.add(new MenuItem(Component.translatable("worldmap.action.delete"), () -> {
                Actions.delete(m);
                if (selected != null && selected.id.equals(m.id)) selected = null;
            }, true));
        }
        openMenu(mx, my, items);
    }

    private void openMenu(int mx, int my, List<MenuItem> items) {
        menuX = (int) (mx / u);
        menuY = (int) (my / u);
        menu = items;
        Actions.click();
    }

    // ---------- телепорт ----------

    private void startTravel(Mark m) {
        Component blocker = Actions.teleportBlocker();
        if (blocker != null) {
            Actions.sound(SoundEvents.VILLAGER_NO, 1f);
            Actions.toast(blocker.copy().withStyle(ChatFormatting.RED));
            return;
        }
        if (!m.dim.equals(viewDim)) switchDim(m.dim);
        menu = null;
        selected = null;
        travelTo = m;
        travelFromX = camX;
        travelFromZ = camZ;
        travelFromZoom = zoom;
        velX = velZ = 0;
        follow = false;
        Actions.sound(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.4f);
        phase(Phase.TRAVEL);
    }

    private void finishTravel() {
        Actions.teleport(travelTo.x, travelTo.y, travelTo.z, travelTo.dim);
        Actions.sound(SoundEvents.ENDERMAN_TELEPORT, 1.5f);
        ClientCompat.setScreen(null);
        Hud.startExit(true, travelTo.name);
    }

    // ---------- для демо ----------

    void demoZoom(float z) {
        anchorX = width / 2.0;
        anchorY = height / 2.0;
        targetZoom = z;
    }

    void demoSelect(Mark m) {
        selected = m;
    }

    void demoTravel(Mark m) {
        startTravel(m);
    }

    void demoCloseMenu() {
        menu = null;
    }

    void demoMenu(int sx, int sy) {
        openMapMenu(sx, sy);
    }
}
