package org.sinytra.connector.mod.mixin.fieldtypes;

import org.sinytra.connector.mod.compat.fieldtypes.RedirectingInt2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;

@SuppressWarnings("unused")
@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {
    // 1.18.2 port note (shadow 名)：源映射里 ParticleEngine.providers 的类型是
    // Int2ObjectMap<ParticleProvider<?>>（按 raw id），Forge 补丁换成了
    // Map<ResourceLocation, ParticleProvider<?>>（按 id 名），refmap 生成失败，
    // 必须直接写运行时 SRG 名 f_107293_，否则 connector$getProviders() 不会生成，
    // FieldToMethodTransformer 重定向过来的调用会抛 NoSuchMethodError。
    @Shadow
    @Final
    private Map<ResourceLocation, ParticleProvider<?>> f_107293_;
    @Unique
    private Int2ObjectMap<ParticleProvider<?>> connector$providers;

    @Unique
    public Int2ObjectMap<ParticleProvider<?>> connector$getProviders() {
        if (this.connector$providers == null) {
            this.connector$providers = new RedirectingInt2ObjectMap<>(
                    i -> Registry.PARTICLE_TYPE.getKey(Registry.PARTICLE_TYPE.byId(i)),
                    key -> Registry.PARTICLE_TYPE.getId(Registry.PARTICLE_TYPE.get(key)),
                    this.f_107293_
            );
        }
        return this.connector$providers;
    }
}
