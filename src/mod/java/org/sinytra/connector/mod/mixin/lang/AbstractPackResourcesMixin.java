package org.sinytra.connector.mod.mixin.lang;

import org.sinytra.connector.mod.compat.PathPackResourcesExtensions;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraftforge.forgespi.locating.IModFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Stops the FML mod list from crashing on mods whose {@code logoFile} is a <b>path</b>.
 *
 * <p><b>The crash.</b> Clicking an entry in Forge's mod list calls
 * {@code ModListScreen.updateCache()} which does
 * {@code resourcePack.getRootResource(selectedMod.getLogoFile())}. On 1.18.2
 * {@code net.minecraftforge.resource.PathResourcePack} does <i>not</i> override
 * {@code getRootResource}; it inherits {@code AbstractPackResources.m_5542_}, whose very first
 * check is (verified from bytecode, {@code AbstractPackResources.java:47}):
 * <pre>
 *   if (name.contains("/") || name.contains("\\"))
 *       throw new IllegalArgumentException("Root resources can only be filenames, not paths (no / allowed!)");
 *   return this.m_5541_(name);      // plain filename → delegate to the pack's own getResource
 * </pre>
 * {@code ModListScreen} only catches {@link IOException}, so the {@code IllegalArgumentException}
 * escapes and the game dies with {@code Description: mouseClicked event handler}.
 *
 * <p><b>Who trips it.</b> {@code logoFile} is documented by Forge as a root filename, but two
 * producers in this port write a path instead:
 * <ul>
 *   <li>the FFAPI generator ({@code gradle/ffapi-setup.gradle}: {@code logoFile = json.icon}) →
 *       {@code logoFile = "assets/fabric-api-base/icon.png"} in all 38 module {@code mods.toml}s;</li>
 *   <li>Connector's own {@code ConnectorModMetadataParser} →
 *       {@code logoFile = fabric.mod.json 的 icon} (e.g. {@code assets/mishanguc/icon.png}).</li>
 * </ul>
 *
 * <p><b>The fix.</b> Keep the metadata untouched (so the icons keep working) and make the lookup
 * tolerant: for a name that contains a separator, resolve it through the owning mod file
 * ({@code IModFile.findResource}, i.e. exactly what {@code ResourcePackLoader$1.resolve} already
 * does for every other resource) and hand back the stream. If the entry does not exist we return
 * {@code null} - which {@code ModListScreen} treats as "this mod has no logo" - instead of throwing.
 *
 * <p>Names without a separator and packs that are not Connector/Forge mod packs are left completely
 * untouched, so vanilla and Forge semantics are preserved everywhere else.
 */
@Mixin(AbstractPackResources.class)
public abstract class AbstractPackResourcesMixin {

    private static final Logger CONNECTOR_LOGGER = LoggerFactory.getLogger(AbstractPackResourcesMixin.class);

    @Inject(
        method = "getRootResource(Ljava/lang/String;)Ljava/io/InputStream;",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void connector$resolvePathLikeRootResource(String fileName, CallbackInfoReturnable<InputStream> cir) {
        if (fileName.indexOf('/') < 0 && fileName.indexOf('\\') < 0) {
            return; // Plain root filename: keep vanilla behaviour (and the vanilla exception path).
        }

        if (!(this instanceof PathPackResourcesExtensions ext)) {
            return; // Not a mod pack built by Connector/Forge; leave the original semantics alone.
        }

        IModFile modFile = ext.connector_getModFile();
        if (modFile == null) {
            cir.setReturnValue(null);
            return;
        }

        List<String> segments = new ArrayList<>();
        for (String segment : fileName.replace('\\', '/').split("/")) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        if (segments.isEmpty()) {
            cir.setReturnValue(null);
            return;
        }

        try {
            Path resolved = modFile.findResource(segments.toArray(new String[0]));
            cir.setReturnValue(resolved != null && Files.isRegularFile(resolved)
                ? Files.newInputStream(resolved)
                : null);
        } catch (Throwable t) {
            // A broken logo entry must never be able to kill the game.
            CONNECTOR_LOGGER.debug("[Connector] Could not resolve root resource '{}' of mod file '{}'",
                fileName, modFile.getFileName(), t);
            cir.setReturnValue(null);
        }
    }
}
