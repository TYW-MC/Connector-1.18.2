package org.sinytra.connector.mod.compat.hud;

import org.sinytra.connector.ConnectorUtil;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * Bridges Forge's 1.18.2 overlay events onto the {@link GuiExtensions} surface.
 *
 * <p>1.18.2 note: 1.20.1 used {@code RenderGuiEvent.Pre/Post} with
 * {@code getGuiGraphics()} / {@code getPartialTick()}. On 1.18.2 the HUD event is
 * {@link RenderGameOverlayEvent} with {@code getMatrixStack()} / {@code getPartialTicks()}, and it
 * fires once per {@link RenderGameOverlayEvent.ElementType} - the whole-HUD pass is {@code ALL}.
 */
@EventBusSubscriber(modid = ConnectorUtil.CONNECTOR_MODID, value = Dist.CLIENT)
public final class HudRenderInvoker {

    @SubscribeEvent
    public static void beforeRenderHud(RenderGameOverlayEvent.Pre event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        if (Minecraft.getInstance().gui instanceof GuiExtensions ext) {
            if (!ext.connector_wrapCancellableCall("preRender", () -> ext.connector_preRender(event.getMatrixStack(), event.getPartialTicks()))) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void afterRenderHud(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        if (Minecraft.getInstance().gui instanceof GuiExtensions ext) {
            ext.connector_postRender(event.getMatrixStack(), event.getPartialTicks());
        }
    }

    private HudRenderInvoker() {}
}
