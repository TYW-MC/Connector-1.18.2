package org.sinytra.connector.service;

import com.mojang.logging.LogUtils;
import cpw.mods.jarhandling.impl.Jar;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraftforge.fml.unsafe.UnsafeHacks;
import org.objectweb.asm.tree.ClassNode;
import org.sinytra.connector.service.hacks.SecureJarImpls;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.uncheck;

/**
 * Makes the classes produced at runtime by the fabric-asm libraries ({@code mm} /
 * {@code mm_shedaniel}) visible to the module layer.
 *
 * <h2>1.18.2 deviations</h2>
 * The 1.20.1 implementation wraps the minecraft module with a
 * {@code SecureJar.ModuleDataProvider} and registers a free standing {@code SecureJar} on the GAME
 * layer, relying on {@code ModuleDataProvider#open(String)} to hand out byte streams for classes
 * that only exist in memory.
 *
 * <p>Neither is available on 1.18.2 (securejarhandler 1.0.8):
 * <ul>
 *   <li>{@code SecureJar} has no {@code moduleDataProvider()} and no {@code open(String)}.</li>
 *   <li>{@code cpw.mods.cl.JarModuleFinder} hard-casts every jar to
 *       {@code cpw.mods.jarhandling.impl.Jar}, and the field it stores on
 *       {@code JarModuleFinder$JarModuleReference} is declared as that concrete class too.</li>
 *   <li>{@code JarModuleReader.open(String)} resolves bytes through
 *       {@code jar.findFile(name).map(Paths::get).map(Files::newInputStream)}, so anything it can
 *       read must exist on disk.</li>
 * </ul>
 *
 * <p>Consequently both wrappers here are {@code impl.Jar} subclasses (see
 * {@link SecureJarImpls}), and the generated classes are materialised into a temporary directory
 * before their URI is reported.
 */
public class FabricASMFixer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> FABRIC_ASM_MODIDS = Set.of("mm", "mm_shedaniel");
    private static final String MINECRAFT_MODULE = "minecraft";
    private static final String GENERATED_MODULE = "fabric_asm_generated_classes";

    /** Registered by the injected fabric-asm hook, see injectFabricASM.js. */
    public static final List<URL> URLS = new ArrayList<>();

    private static Path classCacheDirectory;
    private static Function<String, Optional<URI>> generatedLookup;

    // Called from injected ASM hook, see injectFabricASM.js
    @SuppressWarnings("unused")
    public static Consumer<URL> fishAddURL() {
        return URLS::add;
    }

    // Called from injected ASM hook, see injectFabricASM.js
    @SuppressWarnings("unused")
    public static String flattenMixinClass(String name) {
        return name.replace('/', '_');
    }

    // Called from injected ASM hook, see injectFabricASM.js
    @SuppressWarnings("unused")
    public static void permitEnumSubclass(ClassNode enumNode, String anonymousClassName) {
        if (enumNode.permittedSubclasses != null) {
            enumNode.permittedSubclasses.add(anonymousClassName);
        }
    }

    /**
     * A directory that exists on disk. {@code impl.Jar} always needs a readable file backing, even
     * when nothing is ever served from it.
     */
    private static synchronized Path classCacheDirectory() throws IOException {
        if (classCacheDirectory == null) {
            classCacheDirectory = Files.createTempDirectory("connector-fabric-asm");
            classCacheDirectory.toFile().deleteOnExit();
        }
        return classCacheDirectory;
    }

    /**
     * Lazily built lookup that materialises fabric-asm generated classes into
     * {@link #classCacheDirectory()} so their URIs can be resolved back into actual files.
     */
    private static synchronized Function<String, Optional<URI>> generatedLookup() {
        if (generatedLookup == null) {
            try {
                generatedLookup = SecureJarImpls.classUriMaterialiser(classCacheDirectory(), URLS);
            } catch (IOException e) {
                LOGGER.error("Failed to create the Fabric ASM class cache directory; classes generated at runtime will be unavailable", e);
                generatedLookup = name -> Optional.empty();
            }
        }
        return generatedLookup;
    }

    public static void injectMinecraftModuleReader() {
        try {
            if (FABRIC_ASM_MODIDS.stream().noneMatch(FabricLoader.getInstance()::isModLoaded)) {
                return;
            }
            ModuleLayer layer = Launcher.INSTANCE.findLayerManager().orElseThrow().getLayer(IModuleLayerManager.Layer.GAME).orElseThrow();
            ResolvedModule resolvedModule = layer.configuration().findModule(MINECRAFT_MODULE).orElseThrow();

            Class<?> jarModuleReference = Class.forName("cpw.mods.cl.JarModuleFinder$JarModuleReference");
            ModuleReference reference = resolvedModule.reference();
            if (!jarModuleReference.isInstance(reference)) {
                LOGGER.error("Minecraft module does not contain a jar module reference");
                return;
            }

            Field jarField = jarModuleReference.getDeclaredField("jar");
            // 1.18.2 holds a concrete cpw.mods.jarhandling.impl.Jar here. UnsafeHacks.getField /
            // setField are generic, so the assignment stays valid as long as the value really is a
            // Jar instance (SecureJarImpls.ConnectorJar is).
            Jar originalJar = UnsafeHacks.getField(jarField, reference);
            Jar wrappedJar = SecureJarImpls.overlay(originalJar, generatedLookup());
            UnsafeHacks.setField(jarField, reference, wrappedJar);

            LOGGER.debug("Successfully replaced minecraft module reference jar");
        } catch (Throwable t) {
            LOGGER.error("Error injecting Fabric ASM minecraft module reader", t);
        }
    }

    /**
     * The jar placed on the GAME layer that exposes the fabric-asm generated classes under the
     * {@code fabric_asm_generated_classes} module.
     *
     * <p>On 1.20.1 this was a free standing {@code SecureJar} whose {@code ModuleDataProvider}
     * streamed bytes straight out of memory. On 1.18.2 it has to be an {@code impl.Jar} subclass and
     * the bytes have to be written to disk first, hence the temporary directory.
     */
    public static class FabricASMGeneratedClassesSecureJar extends SecureJarImpls.ConnectorJar {
        public FabricASMGeneratedClassesSecureJar() {
            super(uncheck(FabricASMFixer::classCacheDirectory),
                GENERATED_MODULE,
                SecureJarImpls.GENERATED_PACKAGES,
                false,
                generatedLookup());
        }
    }
}
