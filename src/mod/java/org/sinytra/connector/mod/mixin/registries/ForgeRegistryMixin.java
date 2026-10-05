package org.sinytra.connector.mod.mixin.registries;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.IForgeRegistryEntry;
import org.sinytra.connector.mod.ConnectorLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Hands out a bindable {@code Holder} for entries that are not registered yet, while Fabric
 * mods are still running their entrypoints.
 *
 * <p><b>1.18.2 port note - the target method had to be replaced, not just renamed.</b>
 * 1.20.1 hooked {@code ForgeRegistry#getDelegateOrThrow(V)}, which does not exist in 1.18.2
 * (the Mixin AP reported <i>"Cannot find target method getDelegateOrThrow"</i>). The 1.18.2
 * counterparts are shaped differently:
 * <pre>
 *   private RegistryDelegate&lt;V&gt; getDelegate(V)      // private, and NOT Optional
 *   public Optional&lt;Holder&lt;V&gt;&gt; getHolder(V)         // helper lookup, empty when unknown
 *   Holder.Reference&lt;T&gt; NamespacedHolderHelper#createIntrusiveHolder(T)   // package-private
 * </pre>
 * {@code getDelegate} is unusable twice over: it is private and it throws
 * {@code IllegalStateException} for non-delegated registries instead of returning a value.
 * {@code getHolder(V)} is the public, non-throwing path (its body is just
 * {@code getHolderHelper().flatMap(helper -> helper.getHolder(value))}), so the shim moves
 * there: when it comes back empty during early loading we supply an intrusive holder, which is
 * the same contract the 1.20.1 code provided. Once loading has finished the method is left
 * completely untouched.
 *
 * <p>Two further 1.18.2 differences are absorbed here: the type parameter carries the
 * self-referential bound {@code V extends IForgeRegistryEntry&lt;V&gt;}, and the fallback must use
 * the two-argument {@code Holder.Reference.createIntrusive(Registry, T)} because
 * {@code Registry#holderOwner()} does not exist yet.
 *
 * <p>The descriptor is spelled out because {@code getHolder} has three overloads. It is written
 * in its erased form ({@code IForgeRegistryEntry}), which is exactly how {@code V} erases.
 */
@Mixin(ForgeRegistry.class)
public abstract class ForgeRegistryMixin<V extends IForgeRegistryEntry<V>> implements IForgeRegistry<V> {

}
