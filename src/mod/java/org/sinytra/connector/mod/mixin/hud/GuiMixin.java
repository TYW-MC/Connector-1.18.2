package org.sinytra.connector.mod.mixin.hud;

import org.sinytra.connector.mod.compat.hud.GuiExtensions;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * 1.18.2 note: the 1.20.1 variant passed {@code GuiGraphics} around and injected into
 * {@code Gui.renderHotbar(float, GuiGraphics)} / {@code Gui.renderEffects(GuiGraphics)}. On 1.18.2
 * the HUD draws into a raw {@code PoseStack}, {@code renderHotbar(float, PoseStack)} keeps its
 * {@code float} first, and the whole-HUD entry point is {@code render(PoseStack, float)}.
 */
@Mixin(value = Gui.class, priority = 200)
public abstract class GuiMixin implements GuiExtensions {
    @Unique
    private Map<String, Boolean> connector_renderStates = new HashMap<>();

    @Override
    public boolean connector_getRenderState(String phase) {
        return this.connector_renderStates.getOrDefault(phase, false);
    }

    @Override
    public void connector_setRenderState(String phase, boolean value) {
        this.connector_renderStates.put(phase, value);
    }

    @Override
    public void connector_preRender(PoseStack poseStack, float tickDelta) {
        // Let mods mixin into this method
        connector_setRenderState("preRender", true);
    }

    @Override
    public void connector_postRender(PoseStack poseStack, float tickDelta) {
        // Let mods mixin into this method
    }

    @Override
    public void connector_renderHealth(PoseStack poseStack) {
        // Let mods mixin into this method
        connector_setRenderState("renderHealth", true);
    }

    @Override
    public void connector_renderArmor(PoseStack poseStack) {
        // Let mods mixin into this method
        connector_setRenderState("renderArmor", true);
    }

    @Override
    public void connector_renderHotbar(PoseStack poseStack, float tickDelta) {
        // Let mods mixin into this method
    }

    @Override
    public void connector_renderEffects(PoseStack poseStack, float tickDelta) {
        // Let mods mixin into this method
    }

    @Override
    public void connector_beforeDebugEnabled(PoseStack poseStack, float tickDelta) {
        // Let mods mixin into this method
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void connector$onRender(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        this.connector_preRender(poseStack, partialTick);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void connector$afterRender(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        this.connector_postRender(poseStack, partialTick);
    }

    @Inject(method = "renderHotbar", at = @At("HEAD"))
    private void connector$onRenderHotbar(float partialTick, PoseStack poseStack, CallbackInfo ci) {
        this.connector_renderHotbar(poseStack, partialTick);
    }

    @Inject(method = "renderEffects", at = @At("HEAD"))
    private void connector$onRenderEffects(PoseStack poseStack, CallbackInfo ci) {
        this.connector_renderEffects(poseStack, Minecraft.getInstance().getFrameTime());
    }
}
