package org.sinytra.connector.mod.compat;

import com.mojang.logging.LogUtils;
import org.sinytra.connector.loader.ConnectorEarlyLoader;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fluids.FluidAttributes;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Bridges Fabric fluids onto the 1.18.2 Forge fluid model.
 *
 * <p><b>1.18.2 vs 1.20.1 — why this file looks nothing like upstream.</b>
 * <p>1.20 introduced the {@code FluidType} registry:
 * {@code FluidType}, {@code IClientFluidTypeExtensions}, the
 * {@code ForgeRegistries.Keys.FLUID_TYPES} registry, and {@code RegisterEvent} used to
 * populate it. None of those exist in 1.18.2 — verified missing from the mapped forge
 * jar:
 * <pre>
 *   [MISSING] net.minecraftforge.fluids.FluidType
 *   [MISSING] net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions
 *   [MISSING] net.minecraftforge.registries.RegisterEvent
 * </pre>
 *
 * <p>1.18.2 uses {@code FluidAttributes} instead. There is no registry for them: a
 * {@code Fluid} produces its own attributes lazily via
 * {@code Fluid#createAttributes()}, whose Forge default implementation is
 * {@code ForgeHooks.createVanillaFluidAttributes(Fluid)} — and that method
 * <b>throws</b> for any non-vanilla fluid:
 * <pre>
 *   184: areturn
 *   185: new  java/lang/RuntimeException            // "Mod fluids must override createAttributes."
 *   195: athrow
 * </pre>
 *
 * <p>So the hook is not a registry event any more, it is that throw site. See
 * {@code mixin/ForgeHooksMixin#getFabricVanillaFluidType}, which {@code @Inject}s at
 * {@code @At(value = "NEW", target = "java/lang/RuntimeException")} and returns our
 * attributes instead. That is the 1.18.2-shaped equivalent of upstream's
 * {@code getVanillaFluidType} hook, and it is why {@link #init(IEventBus)} is a no-op.
 */
public final class FluidHandlerCompat {
    private static final Map<Fluid, FluidAttributes> FABRIC_FLUID_ATTRIBUTES = new ConcurrentHashMap<>();
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 1.18.2 has no {@code FluidType} registry and therefore no {@code RegisterEvent} to
     * populate one. Kept so {@code ConnectorMod} keeps a single, version-stable init call
     * site; deliberately does nothing.
     */
    public static void init(IEventBus bus) {
        // no-op: 1.18.2 attributes are produced on demand by Fluid#createAttributes()
    }

    /**
     * Returns the Fabric-derived attributes for {@code fluid}, or {@code null} if the
     * fluid does not belong to a Connector mod (in which case Forge's vanilla handling,
     * or the mod's own {@code createAttributes()} override, must win).
     */
    @Nullable
    public static FluidAttributes getFabricFluidAttributes(Fluid fluid) {
        if (!isFabricFluid(fluid)) {
            return null;
        }
        return FABRIC_FLUID_ATTRIBUTES.computeIfAbsent(fluid, FabricFluidAttributes::create);
    }

    private static boolean isFabricFluid(Fluid fluid) {
        ResourceLocation key = ForgeRegistries.FLUIDS.getKey(fluid);
        if (key == null) {
            // Not registered yet: never claim ownership, let Forge/mod handle it.
            return false;
        }
        return ModList.get().getModContainerById(key.getNamespace())
            .map(container -> ConnectorEarlyLoader.isConnectorMod(container.getModId()))
            .orElse(false);
    }

    private static final class FabricFluidAttributes extends FluidAttributes {
        private final Fluid fluid;
        @Nullable
        private final FluidRenderHandler renderHandler;
        private final Component name;

        private FabricFluidAttributes(Fluid fluid, @Nullable FluidRenderHandler renderHandler) {
            // The builder's still/flowing locations would be copied into private fields that
            // we override the getters for anyway, and the Builder constructor performs no
            // null-check (verified in bytecode), so nulls here are safe and intentional.
            super(FluidAttributes.builder(null, null), fluid);
            this.fluid = fluid;
            this.renderHandler = renderHandler;
            this.name = FluidVariantAttributes.getName(FluidVariant.of(fluid));
        }

        private static FabricFluidAttributes create(Fluid fluid) {
            FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid);
            if (handler == null) {
                LOGGER.warn("Connector fluid {} has no registered FluidRenderHandler; textures will be missing", fluid);
            }
            return new FabricFluidAttributes(fluid, handler);
        }

        @Override
        public Component getDisplayName(FluidStack stack) {
            // 1.18.2's Component has no literal()/empty(); the name comes from FFAPI which
            // is responsible for producing a non-null instance.
            return this.name.copy();
        }

        @Override
        public ResourceLocation getStillTexture() {
            return textureAt(0);
        }

        @Override
        public ResourceLocation getFlowingTexture() {
            return textureAt(1);
        }

        @Nullable
        @Override
        public ResourceLocation getOverlayTexture() {
            return textureAt(2);
        }

        /**
         * <b>Must be overridden.</b> The inherited implementation reads the private
         * {@code stillTexture}/{@code flowingTexture}/{@code overlayTexture} fields
         * <i>directly</i> rather than going through the getters above, so without this it
         * would hand the texture stitcher a stream of {@code null}s and NPE.
         */
        @Override
        public Stream<ResourceLocation> getTextures() {
            return Stream.of(getStillTexture(), getFlowingTexture(), getOverlayTexture())
                .filter(Objects::nonNull);
        }

        @Override
        public int getColor() {
            return colorAt(null, null);
        }

        @Override
        public int getColor(FluidStack stack) {
            return getColor();
        }

        private int colorAt(@Nullable net.minecraft.world.level.BlockAndTintGetter view,
                            @Nullable net.minecraft.core.BlockPos pos) {
            if (this.renderHandler == null) {
                return 0xFFFFFFFF;
            }
            int baseColor = this.renderHandler.getFluidColor(view, pos, this.fluid.defaultFluidState());
            // Fabric hands back RGB; Forge wants ARGB and treats -1 as "no tint".
            return 0xFF000000 | baseColor;
        }

        @Nullable
        private ResourceLocation textureAt(int index) {
            if (this.renderHandler == null) {
                return null;
            }
            TextureAtlasSprite[] sprites = this.renderHandler.getFluidSprites(null, null, this.fluid.defaultFluidState());
            if (sprites == null || sprites.length <= index || sprites[index] == null) {
                return null;
            }
            // 1.18.2 TextureAtlasSprite exposes getName(); contents().name() is 1.19+.
            return sprites[index].getName();
        }
    }

    private FluidHandlerCompat() {}
}
