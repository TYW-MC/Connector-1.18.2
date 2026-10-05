package org.sinytra.connector.locator;

import com.mojang.logging.LogUtils;
import org.sinytra.connector.loader.ConnectorEarlyLoader;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import net.minecraftforge.fml.loading.moddiscovery.ModDiscoverer;
import net.minecraftforge.fml.unsafe.UnsafeHacks;
import net.minecraftforge.forgespi.locating.IModLocator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.MarkerFilter;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * An ugly hack to sort FML dependency providers and make sure {@link ConnectorLocator ours} comes last.
 */
public class ConnectorEarlyLocator extends AbstractJarFileModLocator {
    private static final String NAME = "connector_early_locator";
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public Stream<Path> scanCandidates() {
        // Unfortunately, FML doesn't provide a way to sort mod/dependency locators by priority, so we have to create our own
        try {
            Method method = FMLLoader.class.getDeclaredMethod("getModDiscoverer");
            method.setAccessible(true);
            ModDiscoverer discoverer = (ModDiscoverer) method.invoke(null);
            // 1.18.2 note: ModDiscoverer has a single `modLocatorList` - there is no separate
            // `dependencyLocatorList` (added in 1.20.x), because the dependency pass reuses the
            // very same locator list. Sorting it therefore covers both scan passes.
            Field field = ModDiscoverer.class.getDeclaredField("modLocatorList");
            field.setAccessible(true);
            List<IModLocator> modLocatorList = (List<IModLocator>) field.get(discoverer);
            // 1 - move under; 0 - preserve original order
            List<IModLocator> sorted = new ArrayList<>(modLocatorList);
            sorted.sort(Comparator.comparingInt(loc -> loc instanceof ConnectorLocator ? 1 : 0));
            if (!sorted.equals(modLocatorList)) {
                // 1.18.2 note: we are invoked from inside ModDiscoverer#discoverMods(), which is
                // iterating modLocatorList with a for-each loop while calling IModLocator#scanMods()
                // on each element. ArrayList#sort() bumps modCount, so sorting in place makes the
                // in-flight iterator throw a fatal ConcurrentModificationException on the very next
                // Itr#next(). Replacing the (private final) field instead leaves the running
                // iterator on the original list, while the dependency pass - which re-reads the
                // field - observes the sorted list. UnsafeHacks is used because a plain
                // Field#set() cannot write a final field on Java 17.
                UnsafeHacks.setField(field, discoverer, sorted);
            }
        } catch (Throwable t) {
            LOGGER.error("Error sorting FML dependency locators", t);
            // We can't throw here as that would prevent the connector mod from loading and lead to fabric loader being loaded twice instead
            ConnectorEarlyLoader.addGenericLoadingException(t, "Error sorting FML dependency locators");
        }
        injectLogMarkers();

        // Locate our own embedded jars
        return EmbeddedDependencies.locateEmbeddedJars();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {}

    private static void injectLogMarkers() {
        // Deconstruct grouped log markers system property
        String markerselection = System.getProperty("connector.logging.markers", "");
        Arrays.stream(markerselection.split(",")).forEach(marker -> System.setProperty("connector.logging.marker." + marker.toLowerCase(Locale.ROOT), "ACCEPT"));

        // Obtain a reference to the logger's Configuration object
        org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(ConnectorEarlyLocator.class);
        Configuration config = logger.getContext().getConfiguration();

        // Add a marker filter to the logger's configuration
        config.addFilter(MarkerFilter.createFilter("MIXINPATCH", parseLogMarker("connector.logging.marker.mixinpatch"), Filter.Result.NEUTRAL));

        // Reconfigure the logger with the updated configuration
        logger.getContext().updateLoggers();
    }

    private static Filter.Result parseLogMarker(String propertyName) {
        String value = System.getProperty(propertyName, "DENY");
        return Filter.Result.valueOf(value);
    }
}
