package org.sinytra.connector.mod.mixin.network;

import net.minecraft.network.Connection;
import net.minecraftforge.network.HandshakeHandler;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Supplier;

/**
 * Backfills a Forge handshake when the network channel does not have one yet, so Connector can
 * bridge Fabric networking onto Forge's channel system.
 *
 * <p><b>1.18.2 port note.</b> The body is unchanged, but {@code remap = false} was added at
 * class level. {@code HandshakeHandler} is a pure Forge class - none of its members exist in
 * the Minecraft mappings - so the Mixin AP emitted
 * <i>"Unable to locate obfuscation mapping for @Shadow method"</i> for both shadows and would
 * have emitted an empty refmap entry for the redirect target. Declaring the whole mixin
 * non-remapped is the correct statement of intent and silences all three.
 */
@Mixin(value = HandshakeHandler.class, remap = false)
public abstract class HandshakeHandlerMixin {
    @Shadow
    private static HandshakeHandler getHandshake(Supplier<NetworkEvent.Context> contextSupplier) {
        return null;
    }

    @Shadow
    static void registerHandshake(Connection manager, NetworkDirection direction) {}

    @Redirect(method = "lambda$biConsumerFor$1", at = @At(value = "INVOKE", target = "Lnet/minecraftforge/network/HandshakeHandler;getHandshake(Ljava/util/function/Supplier;)Lnet/minecraftforge/network/HandshakeHandler;"), remap = false)
    private static HandshakeHandler redirectGetHandshake(Supplier<NetworkEvent.Context> contextSupplier) {
        HandshakeHandler result = getHandshake(contextSupplier);
        if (result == null) {
            registerHandshake(contextSupplier.get().getNetworkManager(), contextSupplier.get().getDirection());
            return getHandshake(contextSupplier);
        }
        return result;
    }
}
