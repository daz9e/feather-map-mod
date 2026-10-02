package dev.daze.worldmap.mixin;

import dev.daze.worldmap.client.Demo;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Конец кадра — для скриншотов демо-сценария. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void worldmap$frameEnd(float pt, long nanos, boolean renderLevel, CallbackInfo ci) {
        Demo.frameEnd();
    }
}
