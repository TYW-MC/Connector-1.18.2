package org.sinytra.connector.service;

import com.mojang.logging.LogUtils;
import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.unsafe.UnsafeHacks;
import org.sinytra.connector.loader.ConnectorEarlyLoader;
import org.sinytra.connector.service.hacks.ConnectorForkJoinThreadFactory;
import org.sinytra.connector.service.hacks.LenientRuntimeEnumExtender;
import org.sinytra.connector.service.hacks.ModuleLayerMigrator;
import org.slf4j.Logger;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.uncheck;
import static org.sinytra.connector.service.hacks.ModuleLayerMigrator.TRUSTED_LOOKUP;

public class ConnectorLoaderService implements ITransformationService {
    private static final String NAME = "connector_loader";
    private static final String AUTHLIB_MODULE = "authlib";
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Guards {@link #runEarlyLoader()} so the Fabric loader setup happens exactly once no matter
     * which 1.18.2 mount point ends up firing first.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean EARLY_LOADER_RUN = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Forge hook classes the early loader piggybacks on. Both exist on 1.20.1 but are completely
     * absent from 1.18.2 (the names do not even occur in {@code fmlloader}), so they are resolved
     * reflectively instead of being linked at compile time.
     */
    private static final String IMMEDIATE_WINDOW_PROVIDER = "net.minecraftforge.fml.loading.ImmediateWindowProvider";
    private static final String IMMEDIATE_WINDOW_HANDLER = "net.minecraftforge.fml.loading.ImmediateWindowHandler";

    static {
        // Enable frame expansion fixes on our mixin fork
        System.setProperty("mixin.env.fixFrameExpansion", "true");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(IEnvironment environment) {
        installEarlyLoaderHook();
        ConnectorForkJoinThreadFactory.install();
    }

    /**
     * Runs the Fabric entrypoint setup as early as possible.
     *
     * <p>1.20.1 hijacks the {@code ImmediateWindowHandler.provider} field and piggybacks on
     * {@code ImmediateWindowProvider#updateModuleReads(ModuleLayer)}, which Forge invokes once the
     * module layer has been built but before any mod class is loaded.
     *
     * <p>1.18.2 offers no equivalent: neither type exists, and no {@code updateModuleReads} is
     * invoked anywhere in {@code fmlloader}. The hook is therefore installed reflectively and
     * degrades to a logged warning when the types are missing; in that case
     * {@link ConnectorPreLaunchPlugin} drives {@link #runEarlyLoader()} from the launch plugin
     * transform pipeline ({@code FMLLoader.beforeStart} is not reachable from a mixin because
     * {@code fmlloader} sits on the parent classpath rather than in the GAME module layer).
     */
    private static void installEarlyLoaderHook() {
        Class<?> providerType;
        try {
            providerType = Class.forName(IMMEDIATE_WINDOW_PROVIDER);
            Class.forName(IMMEDIATE_WINDOW_HANDLER);
        } catch (ClassNotFoundException e) {
            LOGGER.warn("{} / {} are unavailable on this Forge version; falling back to the launch plugin transform pipeline as the Fabric loader setup mount point",
                IMMEDIATE_WINDOW_HANDLER, IMMEDIATE_WINDOW_PROVIDER);
            return;
        }

        try {
            Class<?> handlerType = Class.forName(IMMEDIATE_WINDOW_HANDLER);
            VarHandle provider = uncheck(() -> TRUSTED_LOOKUP.findStaticVarHandle(handlerType, "provider", providerType));
            Object original = provider.get();
            Object replacement = Proxy.newProxyInstance(providerType.getClassLoader(), new Class<?>[]{providerType},
                (proxy, method, args) -> {
                    if (method.getName().equals("updateModuleReads") && method.getParameterCount() == 1) {
                        runEarlyLoader();
                    }
                    try {
                        return method.invoke(original, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
            provider.set(replacement);
        } catch (Throwable t) {
            LOGGER.error("Failed to install Connector's early loader hook", t);
        }
    }

    /**
     * Runs the Fabric loader setup exactly once. On 1.20.1 this is driven by the
     * {@code ImmediateWindowHandler} hook; on 1.18.2, where that hook does not exist, it is driven
     * by {@link ConnectorPreLaunchPlugin} from the launch plugin transform pipeline instead.
     */
    static void runEarlyLoader() {
        if (!EARLY_LOADER_RUN.compareAndSet(false, true)) {
            return;
        }
        if (ConnectorEarlyLoader.hasEncounteredException()) {
            return;
        }
        // Setup entrypoints
        ConnectorEarlyLoader.setup();
        // Invoke mixin on a dummy class to initialize mixin plugins
        // Necessary to avoid duplicate class definition errors when a plugin loads the class that is being transformed
        uncheck(() -> Class.forName("org.sinytra.connector.mod.DummyTarget", false, Thread.currentThread().getContextClassLoader()));
        // Run preLaunch
        ConnectorEarlyLoader.preLaunch();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {
        List<ILaunchPluginService> injectPlugins = List.of(new ConnectorPreLaunchPlugin());

        try {
            Field launchPluginsField = Launcher.class.getDeclaredField("launchPlugins");
            launchPluginsField.setAccessible(true);
            LaunchPluginHandler launchPluginHandler = (LaunchPluginHandler) launchPluginsField.get(Launcher.INSTANCE);
            Field pluginsField = LaunchPluginHandler.class.getDeclaredField("plugins");
            pluginsField.setAccessible(true);
            Map<String, ILaunchPluginService> plugins = (Map<String, ILaunchPluginService>) pluginsField.get(launchPluginHandler);
            // Sort launch plugins
            LinkedHashMap<String, ILaunchPluginService> sortedPlugins = new LinkedHashMap<>();
            // Mixin must come first
            sortedPlugins.put("mixin", plugins.remove("mixin"));
            // Handle cases where a mixin has already made the enum mutable
            plugins.remove("runtime_enum_extender");
            sortedPlugins.put("runtime_enum_extender", new LenientRuntimeEnumExtender());
            // Our plugins come after mixin
            injectPlugins.forEach(plugin -> sortedPlugins.put(plugin.name(), plugin));
            // The rest goes to the end
            sortedPlugins.putAll(plugins);
            UnsafeHacks.setField(pluginsField, launchPluginHandler, sortedPlugins);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Resource> completeScan(IModuleLayerManager layerManager) {
        if (LoadingModList.get().getBrokenFiles().isEmpty()) {
            LoadingModList.get().getErrors().addAll(ConnectorEarlyLoader.getLoadingExceptions());
        }
        else {
            LOGGER.warn("Broken FML mod files found, not adding Connector locator errors");
        }
        return List.of(new Resource(IModuleLayerManager.Layer.GAME, List.of(
            new FabricASMFixer.FabricASMGeneratedClassesSecureJar(),
            ModuleLayerMigrator.moveModule(AUTHLIB_MODULE)
        )));
    }

    @Override
    public List<ITransformer> transformers() {
        return List.of();
    }
}
