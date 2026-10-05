package org.sinytra.connector.mod.mixin.hud;

import org.sinytra.connector.mod.compat.hud.GuiExtensions;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.gui.ForgeIngameGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.18.2 note: 1.20.1 targeted {@code net.minecraftforge.client.gui.overlay.ForgeGui}, which was
 * introduced when Forge reworked the HUD into an overlay pipeline. On 1.18.2 the Forge HUD class is
 * {@code net.minecraftforge.client.gui.ForgeIngameGui}, it still renders into a raw
 * {@code PoseStack}, and its status-bar hooks are {@code renderHealth(int, int, PoseStack)} and
 * {@code renderArmor(PoseStack, int, int)}.
 *
 * <p>The 1.20.1 debug hook injected right before the {@code Options.renderDebug} field read. The
 * 1.18.2 method reads the same option, but the surrounding bytecode is different enough that the
 * hook is anchored at the head of {@code renderHUDText} instead.
 */
@Mixin(ForgeIngameGui.class)
public class ForgeGuiMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void onRenderStart(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        ((GuiExtensions) this).connector_setRenderState("enableStatusBarRender", true);
    }

    @Inject(method = "renderHealth", at = @At("HEAD"), remap = false, cancellable = true)
    private void onRenderHealth(int width, int height, PoseStack poseStack, CallbackInfo ci) {
        GuiExtensions ext = (GuiExtensions) this;
        if (!ext.connector_getRenderState("enableStatusBarRender") || !ext.connector_wrapCancellableCall("renderHealth", () -> ext.connector_renderHealth(poseStack))) {
            ci.cancel();
            ext.connector_setRenderState("enableStatusBarRender", false);
        }
    }

    @Inject(method = "renderArmor", at = @At("HEAD"), remap = false, cancellable = true)
    private void onRenderArmor(PoseStack poseStack, int width, int height, CallbackInfo ci) {
        GuiExtensions ext = (GuiExtensions) this;
        if (!ext.connector_getRenderState("enableStatusBarRender") || !ext.connector_wrapCancellableCall("renderArmor", () -> ext.connector_renderArmor(poseStack))) {
            ci.cancel();
            ext.connector_setRenderState("enableStatusBarRender", false);
        }
    }

    @Inject(method = "renderHUDText", at = @At("HEAD"), remap = false)
    private void onRenderDebug(int width, int height, PoseStack poseStack, CallbackInfo ci) {
        ((GuiExtensions) this).connector_beforeDebugEnabled(poseStack, Minecraft.getInstance().getFrameTime());
    }
}
