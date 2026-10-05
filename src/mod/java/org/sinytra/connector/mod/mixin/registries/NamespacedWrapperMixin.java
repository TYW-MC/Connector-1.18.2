package org.sinytra.connector.mod.mixin.registries;

import net.minecraftforge.registries.ILockableRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops Forge from latching a registry into its locked/frozen state, so Connector can keep
 * accepting registrations from Fabric mods at a later stage than Forge expects.
 *
 * <p><b>1.18.2 port note.</b> Forge renamed the flag between 1.18.2 and 1.20.1:
 * <pre>
 *   1.18.2 : private boolean locked;   public void lock()  { this.locked = true; }   public void unfreeze()
 *   1.20.1 : private boolean frozen;   public void freeze(){ this.frozen = true; }
 * </pre>
 * The upstream body redirected {@code NamespacedWrapper.frozen} inside {@code freeze()}. On
 * 1.18.2 that field and method do not exist, which made the redirect fail its injection check
 * and abort the launch with a fatal MixinTransformerError. Redirecting {@code locked} inside
 * {@code lock()} is the exact 1.18.2 equivalent - {@code unfreeze()} is deliberately left alone,
 * mirroring upstream only blocking the freeze direction.
 */
@Mixin(targets = "net.minecraftforge.registries.NamespacedWrapper")
public class NamespacedWrapperMixin {

    // remap = false on BOTH the redirect and the @At: NamespacedWrapper is a Forge class, so its
    // member names are not part of the obfuscation mappings. Without it the Mixin annotation
    // processor fails the build with "Unable to locate obfuscation mapping for @Redirect target".
    @Redirect(method = "lock", remap = false, at = @At(value = "FIELD", target = "Lnet/minecraftforge/registries/NamespacedWrapper;locked:Z", remap = false))
    private void preventFreeze(@Coerce ILockableRegistry registry, boolean locked) {
        // Do nothing
    }
}
