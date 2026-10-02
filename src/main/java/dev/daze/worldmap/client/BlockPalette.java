package dev.daze.worldmap.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Верхняя грань блока, уменьшенная до 4×4 пикселей (ARGB), — «пиксель-арт» для карты. Потокобезопасно: тайлы строятся в фоне. */
public final class BlockPalette {
    public static final int RES = 4;

    public record Face(int[] argb, boolean tinted) {}

    private static final Map<BlockState, Face> CACHE = new ConcurrentHashMap<>();
    private static volatile Face water;

    private BlockPalette() {}

    public static void clear() {
        CACHE.clear();
        water = null;
    }

    public static Face water() {
        if (water == null) water = sample(Minecraft.getInstance().getBlockRenderer().getBlockModelShaper()
                .getParticleIcon(Blocks.WATER.defaultBlockState()), true);
        return water;
    }

    public static Face of(BlockState state) {
        Face f = CACHE.get(state);
        if (f == null) {
            f = compute(state);
            CACHE.put(state, f);
        }
        return f;
    }

    private static Face compute(BlockState state) {
        try {
            BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            RandomSource rnd = RandomSource.create(42L);
            BakedQuad quad = first(model.getQuads(state, Direction.UP, rnd), true);
            if (quad == null) quad = first(model.getQuads(state, null, rnd.fork()), true);
            if (quad == null) quad = first(model.getQuads(state, null, rnd.fork()), false);
            if (quad != null) return sample(quad.getSprite(), quad.isTinted());
            return sample(model.getParticleIcon(), false);
        } catch (Exception e) {
            int c = 0xFF000000 | state.getBlock().defaultMapColor().col;
            int[] px = new int[RES * RES];
            java.util.Arrays.fill(px, c);
            return new Face(px, false);
        }
    }

    private static BakedQuad first(List<BakedQuad> quads, boolean upOnly) {
        for (BakedQuad q : quads) if (!upOnly || q.getDirection() == Direction.UP) return q;
        return null;
    }

    private static Face sample(TextureAtlasSprite sprite, boolean tinted) {
        SpriteContents c = sprite.contents();
        NativeImage img = c.originalImage;
        int w = c.width(), h = Math.min(c.height(), w);
        int[] out = new int[RES * RES];
        for (int cy = 0; cy < RES; cy++)
            for (int cx = 0; cx < RES; cx++) {
                long r = 0, g = 0, b = 0, n = 0, total = 0;
                int x0 = cx * w / RES, x1 = Math.max(x0 + 1, (cx + 1) * w / RES);
                int y0 = cy * h / RES, y1 = Math.max(y0 + 1, (cy + 1) * h / RES);
                for (int y = y0; y < y1; y++)
                    for (int x = x0; x < x1; x++) {
                        int abgr = img.getPixelRGBA(x, y);
                        total++;
                        if ((abgr >>> 24) < 16) continue;
                        r += abgr & 0xFF;
                        g += (abgr >> 8) & 0xFF;
                        b += (abgr >> 16) & 0xFF;
                        n++;
                    }
                if (n == 0) continue;
                int a = (int) (255 * n / total);
                out[cy * RES + cx] = (a << 24) | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        return new Face(out, tinted);
    }
}
