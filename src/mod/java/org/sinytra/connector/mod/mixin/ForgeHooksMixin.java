package org.sinytra.connector.mod.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import org.sinytra.connector.mod.ConnectorMod;
import org.sinytra.connector.mod.compat.FluidHandlerCompat;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.fluids.FluidAttributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(ForgeHooks.class)
public abstract class ForgeHooksMixin {

    /**
     * 1.20.1 upstream hooks {@code ForgeHooks#getVanillaFluidType(Fluid)}, which returns
     * {@code null} for non-vanilla fluids and lets the caller fall through to the
     * {@code FluidType} registry. Neither that method nor {@code FluidType} exists in
     * 1.18.2.
     *
     * <p>The 1.18.2 equivalent is {@code ForgeHooks#createVanillaFluidAttributes(Fluid)},
     * which builds {@code FluidAttributes} for air/water/lava and otherwise <b>throws</b>:
     * <pre>
     *   184: areturn
     *   185: new   java/lang/RuntimeException     // "Mod fluids must override createAttributes."
     *   195: athrow
     * </pre>
     * so we inject at that throw site. Cancelling with a value is exactly the "this is a
     * Fabric mod fluid, here are its attributes" answer that the registry lookup used to
     * provide; if we return {@code null} (non-Connector fluid) the original branch runs and
     * the {@code RuntimeException} is thrown unchanged, preserving Forge semantics.
     */
    @Inject(method = "createVanillaFluidAttributes", at = @At(value = "NEW", target = "java/lang/RuntimeException"), remap = false, cancellable = true)
    private static void connector$getFabricFluidAttributes(Fluid fluid, CallbackInfoReturnable<FluidAttributes> cir) {
        FluidAttributes fabricFluidAttributes = FluidHandlerCompat.getFabricFluidAttributes(fluid);
        if (fabricFluidAttributes != null) {
            cir.setReturnValue(fabricFluidAttributes);
        }
    }

    @Inject(at = @At("TAIL"), method = "modifyAttributes", remap = false)
    private static void connector$allowAttributeMixins(CallbackInfo ci, @Local Map<EntityType<? extends LivingEntity>, AttributeSupplier.Builder> modifiedMap) {
        modifiedMap.forEach((entity, attributes) -> {
            final var fromVanilla = DefaultAttributes.getSupplier(entity);
            // A mod is mixing into DefaultAttributes to add their attribute
            if (ForgeHooks.getAttributesView().get(entity) != fromVanilla) {
                ConnectorMod.LOG.debug("Entity {} has its attributes added via a mixin. Adding event-modified attributes.", entity);
                final AttributeSupplier.Builder newBuilder = new AttributeSupplier.Builder(fromVanilla);
                newBuilder.combine(attributes);
                // `instances` is private in vanilla; widened at build time by
                // accesstransformer.cfg (`public-f ... AttributeSupplier f_22241_`).
                fromVanilla.instances = newBuilder.build().instances;
            }
        });
    }
}
