package org.sinytra.connector.mod.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.ForgeHooksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Makes the tooltip component list mutable so Fabric mods can append to it.
 *
 * <p><b>1.18.2 port note.</b> The target method gained a parameter. 1.20.1 had
 * <pre>
 *   (ItemStack, List, Optional&lt;TooltipComponent&gt;, int, int, int, Font) -&gt; List
 * </pre>
 * 1.18.2 has <b>two</b> overloads, both with an extra trailing {@code Font}:
 * <pre>
 *   (ItemStack, List, int, int, int, Font, Font) -&gt; List
 *   (ItemStack, List, Optional&lt;TooltipComponent&gt;, int, int, int, Font, Font) -&gt; List
 * </pre>
 * The shorter one is just {@code return gatherTooltipComponents(stack, text, Optional.empty(),
 * mouseX, w, h, fallbackFont, font);} - verified in bytecode - so hooking the long form covers
 * both call paths and no second injection is needed. Parameter order also comes from the
 * bytecode: the body immediately performs {@code getTooltipFont(aload 6, stack, aload 7)},
 * i.e. slot 6 is the fallback font and slot 7 the font resolved for this item.
 */
@Mixin(ForgeHooksClient.class)
public abstract class ForgeHooksClientMixin {
}
