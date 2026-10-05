package org.sinytra.connector.mod.mixin.registries;

import org.sinytra.connector.mod.ConnectorMod;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Allows Connector to keep a registry writable past the point vanilla freezes it, because
 * Fabric mods register content at a later stage than Forge expects.
 *
 * <p><b>1.18.2 port note.</b> The body is unchanged from 1.20.1, but the cast has to go
 * through {@code Object}. In 1.19.3+ {@code net.minecraft.core.Registry} is an <i>interface</i>,
 * and a narrowing cast from any non-final class to an interface is always legal - which is
 * what the 1.20.1 source relied on. In 1.18.2 it is still an abstract <i>class</i>:
 * <pre>
 *   public abstract class Registry&lt;T&gt; implements Keyable, IdMap&lt;T&gt;
 *   public class MappedRegistry&lt;T&gt; extends WritableRegistry&lt;T&gt;
 *   public Registry&lt;T&gt; freeze()
 * </pre>
 * This mixin class is not a {@code Registry} subtype, and javac rejects a direct cast between
 * two unrelated non-final class types when the target is a parameterized type
 * ("incompatible types: MappedRegistryMixin cannot be converted to Registry&lt;?&gt;").
 * Casting via {@code Object} performs the widening step javac needs and compiles to a single
 * {@code CHECKCAST} at runtime, which succeeds because the mixin is merged into
 * {@code MappedRegistry}, a genuine {@code Registry} subclass.
 */
@Mixin(MappedRegistry.class)
public class MappedRegistryMixin {
    @Inject(method = "freeze", at = @At("HEAD"), cancellable = true)
    private void preventFreeze(CallbackInfoReturnable<Registry<?>> cir) {
        if (ConnectorMod.preventFreeze()) {
            cir.setReturnValue((Registry<?>) (Object) this);
        }
    }
}
