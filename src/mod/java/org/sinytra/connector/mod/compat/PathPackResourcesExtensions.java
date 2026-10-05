package org.sinytra.connector.mod.compat;

import net.minecraftforge.forgespi.locating.IModFile;

public interface PathPackResourcesExtensions {
    boolean connector_isFabricMod();

    /**
     * The mod file this pack was built for ({@code ResourcePackLoader$1} only keeps the
     * {@code IModFileInfo}; we expose the underlying {@code IModFile}).
     *
     * <p>Used by {@link org.sinytra.connector.mod.mixin.lang.AbstractPackResourcesMixin} to resolve
     * {@code logoFile} entries whose value is a path (e.g. {@code assets/mymod/icon.png}) instead of
     * a bare root filename.
     */
    IModFile connector_getModFile();
}
