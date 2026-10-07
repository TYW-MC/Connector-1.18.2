package org.sinytra.connector.mod.mixin;

import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Forge's predicate-shaped render layer map in sync with the vanilla one.
 *
 * <p>Forge stores block/fluid render layers twice. {@code f_109275_}/{@code f_109276_} are the
 * vanilla maps, and {@code blockRenderChecks}/{@code fluidRenderChecks} are a second,
 * predicate-shaped copy derived from them. That copy is built once, in
 * {@code ItemBlockRenderTypes}'s static initialiser, and it is what {@code canRenderInLayer}
 * answers from; vanilla's chunk builder reads the vanilla maps instead, so the two can silently
 * disagree. Anything registered into the vanilla maps afterwards - which is exactly what a Fabric
 * mod does through Fabric API's {@code BlockRenderLayerMap} - is invisible to the predicate map,
 * which then falls back to its default predicate that only accepts the solid layer.
 *
 * <p>Optimisation mods that replace the chunk builder notice this: Embeddium/Rubidium build a
 * state's render type list in {@code EmbeddiumRenderLayerCache} by asking
 * {@code ItemBlockRenderTypes.canRenderInLayer(BlockState, RenderType)}, so a block that a Fabric
 * mod registered as cutout/translucent is rendered as solid there. The alpha channel of its
 * texture is ignored and its transparent pixels are drawn in their own colour - the road line and
 * road sign textures of a content mod, whose transparent pixels are pure black (yellow lines) or
 * near-white (white lines), showed up as solid black or white faces in game.
 *
 * <p>Fabric API forwards its own registrations to {@code setRenderLayer} so that both maps stay in
 * sync, but that only covers mods going through that API. This fallback covers the rest - mods
 * that mix into the vanilla maps directly, or that register before/independently of the API - by
 * answering from the vanilla map whenever the block or fluid has no predicate entry of its own.
 *
 * <p>The "has no entry of its own" test has to go through {@code containsKey}: Forge builds the
 * predicate maps as fastutil maps whose
 * {@code defaultReturnValue} is a solid-only predicate, so {@code get} returns a non-null value
 * for unknown blocks as well.
 */
@Mixin(ItemBlockRenderTypes.class)
public abstract class ItemBlockRenderTypesMixin {

    @Inject(
        method = "canRenderInLayer(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/RenderType;)Z",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private static void connector$fallBackToVanillaBlockLayer(BlockState state, RenderType layer, CallbackInfoReturnable<Boolean> cir) {
        // Leaves are special-cased inside the method body (cutoutMipped with fancy graphics, solid
        // without); they are registered in the vanilla map at bootstrap, so leave them alone.
        if (state.getBlock() instanceof LeavesBlock) {
            return;
        }

        if (!ItemBlockRenderTypes.getBlockLayerPredicatesView().containsKey(state.getBlock().delegate)) {
            cir.setReturnValue(ItemBlockRenderTypes.getChunkRenderType(state) == layer);
        }
    }

    @Inject(
        method = "canRenderInLayer(Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/client/renderer/RenderType;)Z",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private static void connector$fallBackToVanillaFluidLayer(FluidState state, RenderType layer, CallbackInfoReturnable<Boolean> cir) {
        if (!ItemBlockRenderTypes.getFluidLayerPredicatesView().containsKey(state.getType().delegate)) {
            cir.setReturnValue(ItemBlockRenderTypes.getRenderLayer(state) == layer);
        }
    }
}
