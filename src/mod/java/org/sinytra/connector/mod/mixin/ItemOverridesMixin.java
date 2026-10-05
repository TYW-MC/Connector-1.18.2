package org.sinytra.connector.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.AtlasSet;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBakery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collections;

/**
 * Lets Connector build an {@code ItemOverrides} with a {@code null} bakery, which is what a
 * Fabric mod does when it overrides {@code BakedModel#getOverrides()} outside of model
 * baking.
 *
 * <p><b>1.18.2 port note.</b> 1.20.1 injected into
 * {@code ItemOverrides(ModelBaker, BlockModel, List)} and redirected
 * {@code ModelBaker#getModelTextureGetter()}. Neither exists here: 1.18.2 has no
 * {@code ModelBaker} split yet (only the concrete {@code ModelBakery}), and no
 * {@code getModelTextureGetter()}. The equivalent 1.18.2 constructor is
 * {@code ItemOverrides(ModelBakery, BlockModel, Function, List)}, whose body is:
 * <pre>
 *   4: invokevirtual ModelBakery.getSpriteMap:()Lnet/minecraft/client/renderer/texture/AtlasSet;
 *   8: invokestatic  java/util/Objects.requireNonNull
 *  13: invokedynamic   apply:(Lnet/minecraft/client/renderer/texture/AtlasSet;)Ljava/util/function/Function;
 * </pre>
 * where the indy resolves to nothing more exotic than {@code AtlasSet::getSprite}. So the
 * 1.18.2 equivalent of "swap the texture getter when the baker is null" is to swap the
 * <em>AtlasSet</em> the lambda captures: return a stand-in that always hands back the
 * missing-texture sprite. Since {@code @Redirect} replaces the invocation outright, the
 * receiver never gets dereferenced and the following {@code requireNonNull} sees a
 * non-null value.
 */
@Mixin(ItemOverrides.class)
public class ItemOverridesMixin {

    @Redirect(
        method = "<init>(Lnet/minecraft/client/resources/model/ModelBakery;Lnet/minecraft/client/renderer/block/model/BlockModel;Ljava/util/function/Function;Ljava/util/List;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/model/ModelBakery;getSpriteMap()Lnet/minecraft/client/renderer/texture/AtlasSet;", remap = false)
    )
    private static AtlasSet connector$safeSpriteMap(ModelBakery bakery) {
        if (bakery == null) {
            return MissingSpriteAtlasSet.INSTANCE;
        }
        return bakery.getSpriteMap();
    }

    /**
     * An {@code AtlasSet} with no atlases at all. Every {@code getSprite} call short-circuits
     * to the missing-texture sprite, so nothing can reach the (empty) atlas map and NPE.
     */
    private static final class MissingSpriteAtlasSet extends AtlasSet {
        private static final MissingSpriteAtlasSet INSTANCE = new MissingSpriteAtlasSet();

        private MissingSpriteAtlasSet() {
            // Collectors.toMap over an empty stream - no atlases are registered, and every
            // lookup is served by the override below.
            super(Collections.emptyList());
        }

        @Override
        public TextureAtlasSprite getSprite(Material material) {
            return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(MissingTextureAtlasSprite.getLocation());
        }
    }
}
