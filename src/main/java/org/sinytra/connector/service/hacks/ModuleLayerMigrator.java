package org.sinytra.connector.service.hacks;

import com.mojang.logging.LogUtils;
import cpw.mods.jarhandling.SecureJar;
import cpw.mods.jarhandling.impl.Jar;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import org.slf4j.Logger;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.lang.reflect.Field;
import java.util.Set;

import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.uncheck;

public class ModuleLayerMigrator {
    public static final MethodHandles.Lookup TRUSTED_LOOKUP = uncheck(() -> {
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Unsafe unsafe = (Unsafe) theUnsafe.get(null);
        Field hackfield = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        return (MethodHandles.Lookup) unsafe.getObject(unsafe.staticFieldBase(hackfield), unsafe.staticFieldOffset(hackfield));
    });
    private static final Class<?> JAR_MODULE_REF_CLASS = uncheck(() -> Class.forName("cpw.mods.cl.JarModuleFinder$JarModuleReference"));
    // 1.18.2 (securejarhandler 1.0.8) declares this field as the concrete
    // cpw.mods.jarhandling.impl.Jar rather than a SecureJar.ModuleDataProvider, and
    // JarModuleFinder checkcasts every jar to that class, so replacements must be Jar subclasses.
    private static final VarHandle REF_MODULE_PROVIDER_FIELD = uncheck(() -> TRUSTED_LOOKUP.findVarHandle(JAR_MODULE_REF_CLASS, "jar", Jar.class));
    private static final VarHandle DESCRIPTOR_PACKAGES_FIELD = uncheck(() -> TRUSTED_LOOKUP.findVarHandle(ModuleDescriptor.class, "packages", Set.class));
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * "Moves" a module from the {@link cpw.mods.modlauncher.api.IModuleLayerManager.Layer#BOOT BOOT} layer to {@link cpw.mods.modlauncher.api.IModuleLayerManager.Layer#GAME GAME}
     * in order to make it transformable. This is achieved by disabling the old module and preventing it from loading its classes, then adding a new one to the upper layer
     * to load classes from instead.
     * <p/>
     * Libraries transferred by this method are only used by minecraft after the {@link cpw.mods.modlauncher.api.IModuleLayerManager.Layer#GAME GAME} layer becomes available.
     * In theory, all of this <i>should</i> work just fine without breaking other mods.
     *
     * @param moduleName name of the module to transfer
     * @return the new jar to be placed on the {@link cpw.mods.modlauncher.api.IModuleLayerManager.Layer#GAME GAME} layer
     */
    public static SecureJar moveModule(String moduleName) {
        try {
            LOGGER.debug("Attempting to make module {} transformable", moduleName);
            ModuleLayer layer = Launcher.INSTANCE.findLayerManager().orElseThrow().getLayer(IModuleLayerManager.Layer.BOOT).orElseThrow();
            ResolvedModule module = layer.configuration().findModule(moduleName).orElseThrow(() -> new RuntimeException("Module %s not found".formatted(moduleName)));
            Module actualModule = layer.findModule(moduleName).orElseThrow(() -> new RuntimeException("Module %s not found".formatted(moduleName)));

            ModuleReference reference = module.reference();
            if (!JAR_MODULE_REF_CLASS.isInstance(reference)) {
                throw new RuntimeException("Module %s does not contain a jar module reference".formatted(moduleName));
            }

            // Replace module jar with an empty one to avoid loading classes twice
            Jar originalJar = (Jar) REF_MODULE_PROVIDER_FIELD.get(reference);
            Jar emptyJar = SecureJarImpls.empty(originalJar, originalJar.name());
            REF_MODULE_PROVIDER_FIELD.set(reference, emptyJar);

            // Remove all packages from the original module to avoid split package conflicts
            ModuleDescriptor desc = actualModule.getDescriptor();
            DESCRIPTOR_PACKAGES_FIELD.set(desc, Set.of());

            LOGGER.info("Successfully made module {} transformable", moduleName);
            return SecureJarImpls.renamed(originalJar, "connector$" + moduleName);
        } catch (Throwable t) {
            throw new RuntimeException("Error making module %s transformable".formatted(moduleName), t);
        }
    }
}
