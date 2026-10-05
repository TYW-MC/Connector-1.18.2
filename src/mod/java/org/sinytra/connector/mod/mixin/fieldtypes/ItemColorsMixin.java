package org.sinytra.connector.mod.mixin.fieldtypes;

import org.sinytra.connector.mod.compat.fieldtypes.DelegateBackedIdMapper;
import net.minecraft.client.color.item.ItemColor;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.core.IdMapper;
import net.minecraft.core.Registry;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.IRegistryDelegate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;

@SuppressWarnings("unused")
@Mixin(ItemColors.class)
public class ItemColorsMixin {
    // 1.18.2 note: see BlockColorsMixin - 1.20.1 used Holder.Reference keys here.
    // 1.18.2 port note (shadow 名)：同 BlockColorsMixin，源映射里该字段类型是 IdMapper<ItemColor>，
    // Forge 补丁换成了 Map<IRegistryDelegate<Item>, ItemColor>，refmap 生成失败，
    // 必须直接写运行时 SRG 名 f_92674_。
    @Shadow
    @Final
    private Map<IRegistryDelegate<Item>, ItemColor> f_92674_;

    @Unique
    private IdMapper<ItemColor> connector$itemColors;

    @Unique
    public IdMapper<ItemColor> connector$getItemColors() {
        if (this.connector$itemColors == null) {
            this.connector$itemColors = new DelegateBackedIdMapper<>(
                this.f_92674_,
                Registry.ITEM::byId,
                Registry.ITEM::getId
            );
        }
        return this.connector$itemColors;
    }
}
