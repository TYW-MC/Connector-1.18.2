package org.sinytra.connector.mod.mixin;

import org.sinytra.connector.mod.compat.TagConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.Tag;
import net.minecraft.tags.TagLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * 1.18.2 returns {@code Map<ResourceLocation, Tag.Builder>} from {@code TagLoader#load},
 * where 1.20.1 returned {@code Map<ResourceLocation, List<TagLoader.EntryWithSource>>}
 * (and, later in the chain, a third shape again via {@code TagLoader#build}).
 *
 * <p>The injection point itself is unchanged and still safe: {@code TagLoader#loadAndBuild}
 * is the only caller in {@code TagManager#reload}, and it simply does
 * {@code build(load(manager))} - so rewriting the builders right at RETURN of {@code load}
 * still lands ahead of tag construction, including a fresh {@code Tag.Builder} that other
 * loader mixins may have contributed to.
 */
@Mixin(TagLoader.class)
public class TagLoaderMixin {

    @Inject(method = "load", at = @At("RETURN"))
    private void afterLoad(ResourceManager manager, CallbackInfoReturnable<Map<ResourceLocation, Tag.Builder>> cir) {
        TagConverter.postProcessTags(cir.getReturnValue());
    }
}
