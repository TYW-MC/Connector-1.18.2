package org.sinytra.connector.mod.compat;

import com.google.common.collect.BiMap;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import org.sinytra.connector.loader.ConnectorEarlyLoader;
import net.fabricmc.fabric.impl.registry.sync.DynamicRegistriesImpl;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.IForgeRegistryEntry;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static cpw.mods.modlauncher.api.LamdbaExceptionUtils.uncheck;

/**
 * Keeps client-only registry entries that a Connector mod registered on the client but not on
 * the server, so that the client does not lose them when Forge syncs the server's registry.
 *
 * <p><b>1.18.2 port note.</b> The type parameter of {@code ForgeRegistry} changed shape.
 * 1.20.1 dropped {@code IForgeRegistryEntry} and declared {@code ForgeRegistry<V>} unbounded;
 * 1.18.2 still has the self-referential bound, verified from the class file:
 * <pre>
 *   public class ForgeRegistry&lt;V extends IForgeRegistryEntry&lt;V&gt;&gt;
 *       implements IForgeRegistryInternal&lt;V&gt;, IForgeRegistryModifiable&lt;V&gt;
 *   public interface IForgeRegistry&lt;V extends IForgeRegistryEntry&lt;V&gt;&gt; extends Iterable&lt;V&gt;
 * </pre>
 * Hence every {@code V} here needs {@code extends IForgeRegistryEntry<V>}. The reflected
 * {@code names} field is unchanged and still a {@code BiMap<ResourceLocation, V>}:
 * <pre>
 *   private final com.google.common.collect.BiMap&lt;ResourceLocation, V&gt; names;
 * </pre>
 */
public class RegistryUtil {
    private static final VarHandle REGISTRY_NAMES = uncheck(() -> MethodHandles.privateLookupIn(ForgeRegistry.class, MethodHandles.lookup()).findVarHandle(ForgeRegistry.class, "names", BiMap.class));
    private static final ResourceLocation PARTICLE_TYPE_REGISTRY = ForgeRegistries.Keys.PARTICLE_TYPES.location();
    private static final Logger LOGGER = LogUtils.getLogger();

    public static boolean isRegisteredFabricDynamicRegistry(ResourceKey<?> key) {
        return DynamicRegistriesImpl.FABRIC_DYNAMIC_REGISTRY_KEYS.stream().anyMatch(key::equals);
    }

    public static <V extends IForgeRegistryEntry<V>> void retainFabricClientEntries(ResourceLocation name, ForgeRegistry<V> from, IForgeRegistry<V> to) {
        if (FMLLoader.getDist().isClient() && name.equals(PARTICLE_TYPE_REGISTRY)) {
            List<Pair<ResourceLocation, V>> list = new ArrayList<>();

            for (Map.Entry<ResourceKey<V>, V> entry : to.getEntries()) {
                ResourceLocation location = entry.getKey().location();
                if (!from.containsKey(location) && ConnectorEarlyLoader.isConnectorMod(location.getNamespace())) {
                    list.add(Pair.of(location, entry.getValue()));
                }
            }

            if (!list.isEmpty()) {
                LOGGER.info("Connector found {} items to retain in registry {}", list.size(), name);
            }

            for (Pair<ResourceLocation, V> pair : list) {
                RegistryUtil.getNames(from).put(pair.getFirst(), pair.getSecond());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <V extends IForgeRegistryEntry<V>> BiMap<ResourceLocation, V> getNames(ForgeRegistry<V> registry) {
        return (BiMap<ResourceLocation, V>) uncheck(() -> REGISTRY_NAMES.get(registry));
    }
}
