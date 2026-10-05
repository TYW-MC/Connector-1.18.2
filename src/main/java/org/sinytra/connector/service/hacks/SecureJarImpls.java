package org.sinytra.connector.service.hacks;

import cpw.mods.jarhandling.JarMetadata;
import cpw.mods.jarhandling.impl.Jar;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.module.ModuleDescriptor;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.jar.Manifest;

/**
 * 1.18.2 replacements for the {@code SecureJar.ModuleDataProvider} wrappers used on 1.20.1.
 *
 * <h2>Why the 1.20.1 approach cannot be reused</h2>
 * On 1.18.2 (securejarhandler 1.0.8) {@code SecureJar} is only a thin facade over a real file
 * system, and {@code cpw.mods.cl.JarModuleFinder} hard-casts every jar handed to it to the
 * concrete {@code cpw.mods.jarhandling.impl.Jar}:
 *
 * <pre>{@code
 * private static JarModuleFinder$1ref lambda$new$0(SecureJar jar) {
 *     return new 1ref(jar, new JarModuleReference((Jar) jar)); // checkcast cpw/mods/jarhandling/impl/Jar
 * }
 * }</pre>
 *
 * <p>1.20.1 wrapped modules by supplying a hand written {@code SecureJar.ModuleDataProvider}. Doing
 * the same here would blow up with a {@link ClassCastException} the moment ModLauncher builds the
 * module layer, because only {@code impl.Jar} instances survive that checkcast.
 *
 * <p>The wrapper below therefore <em>extends</em> {@code impl.Jar} and overrides only the
 * accessors Connector actually needs to change ({@code getPackages()} and
 * {@code findFile(String)}; the module name is controlled through the {@link JarMetadata} passed
 * to the base constructor). Everything else - file system handling, package scanning, signature
 * verification - is inherited unchanged.
 *
 * <h2>Why runtime generated classes have to hit the disk</h2>
 * {@code impl.Jar} is read through {@code JarModuleFinder$JarModuleReader}, whose
 * {@code open(String)} is:
 *
 * <pre>{@code
 * jar.findFile(name).map(Paths::get).map(Files::newInputStream)
 * }</pre>
 *
 * <p>1.20.1's {@code ModuleDataProvider.open(String)} could hand out an {@link InputStream} backed
 * by an arbitrary {@link URL}. On 1.18.2 the lookup can only yield a {@link URI} that
 * {@link java.nio.file.Paths#get(URI)} understands, i.e. a real file. The fabric-asm generated
 * classes are consequently materialised into a temporary directory by
 * {@link #classUriMaterialiser} before their URI is reported.
 */
public final class SecureJarImpls {
    /** Packages the fabric-asm libraries generate classes into. */
    public static final Set<String> GENERATED_PACKAGES = Set.of("com.chocohead.gen.mixin", "me.shedaniel.gen.mixin");

    private SecureJarImpls() {
    }

    /**
     * Builds a memoising lookup that resolves a class/resource name to a {@link URI} by copying it
     * out of one of the fabric-asm {@code URL}s into {@code cacheDir}.
     *
     * <p>The returned function never throws: a miss is reported as {@link Optional#empty()}, which
     * lets {@link ConnectorJar#findFile(String)} fall through to the wrapped jar.
     */
    public static Function<String, Optional<URI>> classUriMaterialiser(Path cacheDir, Iterable<URL> urls) {
        Map<String, Optional<URI>> cache = new ConcurrentHashMap<>();
        return name -> cache.computeIfAbsent(name, key -> {
            for (URL url : urls) {
                try {
                    URLConnection connection = new URL(url, key).openConnection();
                    Path target = Files.createTempFile(cacheDir, "generated-", ".class");
                    try (InputStream in = connection.getInputStream();
                         OutputStream out = Files.newOutputStream(target)) {
                        in.transferTo(out);
                    }
                    target.toFile().deleteOnExit();
                    return Optional.of(target.toUri());
                } catch (Exception ignored) {
                    // Not provided by this URL, try the next one.
                }
            }
            return Optional.empty();
        });
    }

    /**
     * An {@code impl.Jar} that keeps its file backing but lets Connector override its module name,
     * its exposed packages and its file lookup.
     *
     * <p>The module name is passed to {@link JarMetadata}, so {@link Jar#name()} already reports it;
     * only {@code getPackages()} and {@code findFile(String)} need explicit overrides.
     */
    public static class ConnectorJar extends Jar {
        private final Set<String> packages;
        private final boolean allowOriginalFiles;
        private final Function<String, Optional<URI>> generatedLookup;

        /**
         * @param packages         the exact set of packages this jar reports
         * @param allowOriginalFiles whether lookups may fall through to the backing file system
         * @param generatedLookup  consulted first for every lookup; may be {@code null}
         */
        protected ConnectorJar(Path base, String name, Set<String> packages, boolean allowOriginalFiles,
                               Function<String, Optional<URI>> generatedLookup) {
            super(Manifest::new,
                wrapper -> new ConnectorJarMetadata(name, packages),
                (pkg, cls) -> true,
                base);
            this.packages = Set.copyOf(packages);
            this.allowOriginalFiles = allowOriginalFiles;
            this.generatedLookup = generatedLookup;
        }

        @Override
        public Set<String> getPackages() {
            // Deliberately does not defer to super.getPackages(): the base implementation walks the
            // file system, which is exactly what the "empty" and "generated" flavours must hide.
            return packages;
        }

        @Override
        public Optional<URI> findFile(String name) {
            if (generatedLookup != null) {
                Optional<URI> generated = generatedLookup.apply(name);
                if (generated.isPresent()) {
                    return generated;
                }
            }
            return allowOriginalFiles ? super.findFile(name) : Optional.empty();
        }
    }

    /**
     * A jar that serves <em>only</em> the classes generated at runtime by fabric-asm, exposed under
     * the given module name. Mirrors 1.20.1's {@code FabricASMGeneratedClassesSecureJar}.
     *
     * @param base a directory that exists; {@code impl.Jar} always needs a readable file backing
     */
    public static ConnectorJar generated(Path base, String name, Function<String, Optional<URI>> lookup) {
        return new ConnectorJar(base, name, GENERATED_PACKAGES, false, lookup);
    }

    /**
     * A copy of {@code original} that additionally serves the fabric-asm generated classes. Mirrors
     * 1.20.1's {@code ModuleDataProviderWrapper} as used for the minecraft module.
     *
     * <p>{@code allowOriginalFiles} must be {@code true}: 1.20.1's wrapper only overrode
     * {@code findFile} in order to <em>add</em> the generated packages and delegated everything
     * else to the wrapped provider. Turning the fall-through off here would make the whole
     * minecraft jar disappear the moment any fabric-asm mod is present.
     */
    public static ConnectorJar overlay(Jar original, Function<String, Optional<URI>> lookup) {
        return new ConnectorJar(original.getPrimaryPath(), original.name(), original.getPackages(), true, lookup);
    }

    /**
     * A copy of {@code original} exposed under a different module name. Mirrors 1.20.1's
     * {@code SimpleSecureJar}/{@code ModuleDataProviderWrapper} pair used when migrating a module
     * from the BOOT to the GAME layer.
     *
     * <p>{@code allowOriginalFiles} must be {@code true}. This jar <em>is</em> the migrated module:
     * it is the only thing that can still serve its classes once the BOOT-layer
     * {@link #empty(Jar, String) empty} copy has taken over the original reference. 1.20.1's
     * {@code ModuleDataProviderWrapper#findFile} simply forwarded to the wrapped provider; leaving
     * the flag at {@code false} makes the module resolve but serve nothing, which surfaces as
     * {@code ClassNotFoundException} for every class in it (observed on 1.18.2 as
     * {@code NoClassDefFoundError: com/mojang/authlib/yggdrasil/YggdrasilAuthenticationService}
     * from {@code net.minecraft.server.Main#main}).
     */
    public static ConnectorJar renamed(Jar original, String name) {
        return new ConnectorJar(original.getPrimaryPath(), name, original.getPackages(), true, null);
    }

    /**
     * A jar that reports no packages and finds no files. Mirrors 1.20.1's
     * {@code EmptyModuleDataProvider}, which stopped the original module from loading classes twice.
     *
     * <p>{@code allowOriginalFiles} is {@code false} to match: {@code EmptyModuleDataProvider} returned
     * {@link Optional#empty()} from both {@code findFile} and {@code open}. Keeping the fall-through
     * enabled would let the BOOT-layer copy keep handing out the very classes that the GAME-layer
     * {@link #renamed(Jar, String) copy} is meant to own, which is exactly the duplicated class
     * definition this step exists to prevent.
     */
    public static ConnectorJar empty(Jar original, String name) {
        return new ConnectorJar(original.getPrimaryPath(), name, Set.of(), false, null);
    }

    /** Metadata describing a {@link ConnectorJar}; the name may differ from the backing file. */
    private record ConnectorJarMetadata(String name, Set<String> packages) implements JarMetadata {
        @Override
        public String version() {
            return "";
        }

        @Override
        public ModuleDescriptor descriptor() {
            return ModuleDescriptor.newAutomaticModule(name)
                .packages(new LinkedHashSet<>(packages))
                .build();
        }
    }
}
