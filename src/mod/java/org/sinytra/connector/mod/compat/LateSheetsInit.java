package org.sinytra.connector.mod.compat;

import net.minecraft.client.renderer.Sheets;
import net.minecraft.world.level.block.state.properties.WoodType;

public class LateSheetsInit {
    /**
     * 1.18.2 note: on 1.20.1 this had to re-create sign / hanging-sign / banner / decorated-pot
     * materials for entries registered after {@code Sheets} was first class-loaded, using the
     * 1.20-only factories {@code createSignMaterial} / {@code createHangingSignMaterial} /
     * {@code createBannerMaterial} / {@code createDecoratedPotMaterial}.
     *
     * <p>On 1.18.2 {@code Sheets} exposes {@link Sheets#addWoodType(WoodType)}, which builds the
     * sign material for a wood type and stores it in {@code SIGN_MATERIALS}. That covers Fabric
     * mods registering a {@code WoodType} after client start-up. Hanging signs, decorated pots and
     * the {@code BANNER_PATTERN} registry are all 1.20 additions and have no 1.18.2 counterpart,
     * so only the wood-type pass is kept here.
     */
    public static void completeSheetsInit() {
        WoodType.values().forEach(Sheets::addWoodType);
    }

    private LateSheetsInit() {}
}
