package org.sinytra.connector.mod.mixin.boot;

import net.minecraft.client.main.Main;
import org.sinytra.connector.mod.ConnectorLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.18.2 port note:
// 1.20.1 的 Connector 在 `Minecraft.<init>` 里注入一个方法调用点：
//   @Inject(method = "<init>",
//           at = @At(value = "INVOKE",
//                    target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;"))
// 这个写法在 Mixin 0.8.5 上**必然**失败，与目标是否存在无关：
// `@At("INVOKE")` 的 RestrictTargetLevel 是 METHODS_ONLY，而注入方法 `<init>` 是构造器，
// Injector#checkTargetForNode 会直接抛
//   "Found @Inject targetting a constructor in injector ..."
// （服务端的 boot.ServerMainMixin 不受影响，因为它的目标是普通方法 Main#main。）
// 构造器内可用的点只有 HEAD / RETURN：HEAD 在 super() 之前（静态 handler 与构造器
// 的非静态修饰符不匹配，会被 checkTargetModifiers(target, true) 拒绝；非静态又会在
// super() 之前访问 this），RETURN 则在全部字段/资源包/reload 实例都建好之后，太晚。
//
// 因此改为在客户端入口 `net.minecraft.client.main.Main#main` 里、`new Minecraft(gameConfig)`
// 之前注入 —— 该位置：
//   * 已经执行完 Bootstrap.bootStrap()（原版注册表已就绪，Fabric 模组的注册代码可用）；
//   * 早于 Minecraft 构造、早于资源包重载实例的创建（比原来的构造器中途注入更早）；
//   * 目标是普通静态方法，`@At("INVOKE")` 合法，handler 为 static 与目标修饰符一致。
// 实测 1.18.2 客户端 Main#main 的字节码偏移 1350 处正是
//   invokespecial net/minecraft/client/Minecraft."<init>":(Lnet/minecraft/client/main/GameConfig;)V
// 且偏移 1115 处已调用 Bootstrap.bootStrap()。
//
// 注：保留 priority = 3000，让 ConnectorExtras 的 mixin 优先应用。
@Mixin(value = Main.class, priority = 3000)
public class MainMixin {
    @Inject(
        method = "main",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;<init>(Lnet/minecraft/client/main/GameConfig;)V"),
        remap = false
    )
    private static void connectorEarlyInit(String[] args, CallbackInfo ci) {
        ConnectorLoader.load();
    }
}
