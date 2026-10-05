package org.sinytra.connector.mod.mixin.fieldtypes;

import org.sinytra.connector.mod.compat.fieldtypes.DelegateBackedIdMapper;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.core.IdMapper;
import net.minecraft.core.Registry;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.IRegistryDelegate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;

@SuppressWarnings("unused")
@Mixin(BlockColors.class)
public class BlockColorsMixin {
    // 1.18.2 note: 1.20.1 keyed this map by Holder.Reference<Block> and resolved delegates through
    // ForgeRegistries.BLOCKS#getDelegateOrThrow, which does not exist here. On 1.18.2 the registry
    // still hands out Forge registry delegates.
    //
    // 1.18.2 port note (shadow 名)：这里必须写运行时的 SRG 名 f_92571_。
    // 该 mixin 的源映射（yarn/mojmap）里 BlockColors.blockColors 的类型是 IdMapper<BlockColor>，
    // 而 Forge 补丁把它换成了 Map<IRegistryDelegate<Block>, BlockColor>；
    // 因为（名称, 描述符）对不上，TinyRemapper 无法生成 refmap 条目
    // （构建产物 mixins.connectormod.refmap.json 里确实没有 fieldtypes/BlockColorsMixin），
    // Mixin 于是退化成按字面名 `blockColors` 查找 -> "@Shadow field blockColors was not located"，
    // 进而导致本类提供的 connector$getBlockColors() 不存在，
    // 而 FieldToMethodTransformer 会把 Fabric 模组对 f_92571_ 的访问重定向到这个方法 -> NoSuchMethodError。
    // 直接使用 SRG 名即可命中（refmap 查不到 -> 按字面名使用 -> 正好是 runtime 字段名）。
    @Shadow
    @Final
    private Map<IRegistryDelegate<Block>, BlockColor> f_92571_;

    @Unique
    private IdMapper<BlockColor> connector$blockColors;

    @Unique
    public IdMapper<BlockColor> connector$getBlockColors() {
        if (this.connector$blockColors == null) {
            this.connector$blockColors = new DelegateBackedIdMapper<>(
                this.f_92571_,
                Registry.BLOCK::byId,
                Registry.BLOCK::getId
            );
        }
        return this.connector$blockColors;
    }
}
