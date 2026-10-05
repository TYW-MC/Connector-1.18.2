package org.sinytra.connector.mod.mixin.lang;

import org.sinytra.connector.mod.compat.PathPackResourcesExtensions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraftforge.resource.PathResourcePack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;

/**
 * Makes Fabric mod language files reachable through the pack Forge builds for them.
 *
 * <p><b>1.18.2 port note.</b> 1.20.1 hooked {@code net.minecraftforge.resource.PathPackResources}
 * and redirected the {@code lang/} path through the private
 * {@code getPathFromLocation(PackType.SERVER_DATA, location)} helper, then fed the resulting
 * path array to {@code getRootResource(String...)}. Both of those are 1.20-era shapes:
 * <ul>
 *   <li>The class is named {@code PathResourcePack} here (no trailing {@code s}).</li>
 *   <li>{@code getPathFromLocation} does not exist - the path array is built inline by
 *       {@code resolve(String...)} / {@code getResource(PackType, ResourceLocation)}.</li>
 *   <li>{@code getRootResource} takes a single {@code String} (for {@code pack.mcmeta}-style
 *       root files), not a path array, and {@code IoSupplier} does not exist yet: every
 *       resource accessor returns a plain {@code InputStream}.</li>
 * </ul>
 *
 * <p>So instead of hand-building the {@code data/<ns>/lang/...} path we ask for the resource
 * again with {@code PackType.SERVER_DATA}. That goes through the same overridden
 * {@code resolve} the 1.20.1 code relied on
 * ({@code ResourcePackLoader$1.resolve} → {@code IModFile.findResource}), and therefore
 * produces the identical lookup. The {@code CLIENT_RESOURCES} guard is required here, because
 * unlike a raw path helper this call re-enters the very method we are injecting into -
 * without it the {@code SERVER_DATA} pass would recurse straight back into this handler.
 *
 * <p>The descriptor is spelled out because {@code PathResourcePack} has two {@code getResource}
 * overloads (a {@code String} one for root files and this one), which a bare method name
 * cannot disambiguate.
 */
@Mixin(PathResourcePack.class)
public abstract class PathResourcePackMixin {

    @Inject(
        method = "getResource(Lnet/minecraft/server/packs/PackType;Lnet/minecraft/resources/ResourceLocation;)Ljava/io/InputStream;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void getFabricLangResources(PackType type, ResourceLocation location, CallbackInfoReturnable<InputStream> cir) throws IOException {
        if (type != PackType.CLIENT_RESOURCES
            || !(this instanceof PathPackResourcesExtensions ext)
            || !ext.connector_isFabricMod()
            || !location.getPath().startsWith("lang/")) {
            return;
        }
        InputStream serverLang = ((PackResources) this).getResource(PackType.SERVER_DATA, location);
        if (serverLang != null) {
            cir.setReturnValue(serverLang);
        }
    }
}
