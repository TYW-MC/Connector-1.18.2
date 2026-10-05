package org.sinytra.connector.mod.compat;

// 1.18.2 port note:
// 1.20.1 的实现通过 VarHandle 改写 `RenderType.chunkLayerId`，把区块渲染层重新编号，
// 以便模组（重）注册自定义 RenderType 后 Fabric 侧期望的层顺序仍然成立。
// 1.18.2 没有这个字段，也没有这个机制：
//   $ javap -p net.minecraft.client.renderer.RenderType  ->  无 chunkLayerId
//   （直接在 RenderType.class 里搜 "chunkLayer"/"chunkLayerId" 也是 0 命中）
// 原有实现的静态初始化块会因此抛 NoSuchFieldException -> ExceptionInInitializerError，
// 冒泡到 FML 变成 "Mod loading error has occurred"，直接拖垮客户端启动。
// 1.18.2 的区块层顺序由 RenderType.m_110506_()（原版固定的 chunkBufferLayers 列表）决定，
// 模组不会向其中插入新层，因此这里安全地退化为空实现。
public class LateRenderTypesInit {
    public static void regenerateRenderTypeIds() {
        // no-op on 1.18.2
    }
}
