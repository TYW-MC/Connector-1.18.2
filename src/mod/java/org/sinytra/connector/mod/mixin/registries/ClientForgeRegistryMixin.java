package org.sinytra.connector.mod.mixin.registries;

import org.sinytra.connector.mod.compat.RegistryUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.IForgeRegistryEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the point where a client-side registry receives the server's entries, so that
 * Connector mod entries which only exist on the client survive the sync.
 *
 * <p><b>1.18.2 port note.</b> Only the type-parameter bound changed:
 * {@code ForgeRegistry<V extends IForgeRegistryEntry<V>>} here, unbounded in 1.20.1. The
 * injection point is unchanged and still valid - {@code sync} is still a two-argument
 * package-private method and still clears a {@code BiMap} as its first observable action:
 * <pre>
 *   void sync(net.minecraft.resources.ResourceLocation, net.minecraftforge.registries.ForgeRegistry&lt;V&gt;)
 * </pre>
 * (package-private, which is fine to inject into; {@code remap = false} is required because
 * {@code ForgeRegistry} is a Forge class whose members are never obfuscated.)
 */
@Mixin(ForgeRegistry.class)
public abstract class ClientForgeRegistryMixin<V extends IForgeRegistryEntry<V>> implements IForgeRegistry<V> {

    @Inject(method = "sync", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/BiMap;clear()V", ordinal = 0), remap = false)
    private void retainFabricClientEntries(ResourceLocation name, ForgeRegistry<V> from, CallbackInfo ci) {
        RegistryUtil.retainFabricClientEntries(name, from, this);
    }
}
