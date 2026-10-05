package org.sinytra.connector.mod.mixin.lang;

import org.sinytra.connector.loader.ConnectorEarlyLoader;
import org.sinytra.connector.mod.compat.PathPackResourcesExtensions;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IModFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.List;

/**
 * Tags the resource pack Forge generates for each mod ({@code ResourcePackLoader$1}) with a
 * flag telling {@link PathResourcePackMixin} whether the pack belongs to a Fabric mod, so that
 * the language-file fallback only kicks in for Connector mods.
 *
 * <p><b>1.18.2 port note.</b> The anonymous class still exists and still extends
 * {@code PathResourcePack}, but its constructor lost an argument: 1.20.1's
 * {@code (String packId, boolean isBuiltin, Path source, IModFileInfo modFile)} is
 * {@code (String packId, Path source, IModFileInfo modFile)} here, verified from the class
 * file:
 * <pre>
 *   net.minecraftforge.resource.ResourcePackLoader$1(java.lang.String, java.nio.file.Path,
 *       net.minecraftforge.forgespi.language.IModFileInfo)
 * </pre>
 * The injected handler's parameters must mirror that list exactly and in order, otherwise
 * Mixin refuses the injection.
 */
@Mixin(targets = "net/minecraftforge/resource/ResourcePackLoader$1", remap = false)
public class PathResourcePackAnonMixin implements PathPackResourcesExtensions {
    @Unique
    private boolean connector_isFabricMod;

    @Unique
    private IModFile connector_modFile;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(String packId, Path source, IModFileInfo modFile, CallbackInfo ci) {
        connector_modFile = modFile.getFile();
        List<IModInfo> mods = modFile.getMods();
        if (!mods.isEmpty()) {
            connector_isFabricMod = ConnectorEarlyLoader.isConnectorMod(mods.get(0).getModId());
        }
    }

    @Override
    public boolean connector_isFabricMod() {
        return this.connector_isFabricMod;
    }

    @Override
    public IModFile connector_getModFile() {
        return this.connector_modFile;
    }
}
