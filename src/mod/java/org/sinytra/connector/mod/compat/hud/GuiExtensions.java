package org.sinytra.connector.mod.compat.hud;

import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Extension surface that Connector grafts onto {@code net.minecraft.client.gui.Gui} so that Fabric
 * mods mixing into the vanilla HUD pipeline keep working on Forge, where
 * {@code ForgeIngameGui} replaces the vanilla render path.
 *
 * <p>1.18.2 note: on 1.20.1 the render context parameter was {@code GuiGraphics}. That class does
 * not exist before 1.20 - 1.18.2 renders straight into a {@link PoseStack} - so every method here
 * takes a {@code PoseStack} instead. The phase names are kept identical because
 * {@code MixinPatches} retargets Fabric mixin handlers onto these methods by name.
 */
public interface GuiExtensions {
    default boolean connector_wrapCancellableCall(String phase, Runnable runnable) {
        connector_setRenderState(phase, false);
        runnable.run();
        if (!connector_getRenderState(phase)) {
            return false;
        }
        return true;
    }

    boolean connector_getRenderState(String phase);
    void connector_setRenderState(String phase, boolean value);

    void connector_preRender(PoseStack poseStack, float tickDelta);

    void connector_postRender(PoseStack poseStack, float tickDelta);

    void connector_renderHealth(PoseStack poseStack);

    void connector_renderArmor(PoseStack poseStack);

    void connector_renderHotbar(PoseStack poseStack, float tickDelta);

    void connector_renderEffects(PoseStack poseStack, float tickDelta);

    void connector_beforeDebugEnabled(PoseStack poseStack, float tickDelta);
}
