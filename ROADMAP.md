# Fabric-on-Forge 1.18.2 移植路线图

> 目标：让 **Minecraft 1.18.2 / Forge** 能够直接加载 **任意** 1.18.2 的 Fabric mod（客户端 + 服务端）。
> 上游基线：Sinytra Connector 1.20.1 分支 + ForgifiedFabricAPI 1.20.1。
>
> **范围说明**：用户目标是"随时往 mods 里丢任意 Fabric mod 都能跑"，
> 因此 **FFAPI 必须全量实现，不能按 mod 裁剪**。

---

## 一、当前总体进度

```
┌──────────────────────────────────────────────────────────────────────────┐
│ 第 3 层  ForgifiedFabricAPI   1073 文件 / 9.2 万行   ⏳ 待做（最大工程）   │
│          ★ 已确定策略：取上游 Fabric API 1.18.2 为基线 + 叠加 Forge 补丁  │
├──────────────────────────────────────────────────────────────────────────┤
│ 第 2 层  Connector            104 文件                                   │
│          ├─ main 源集（核心）        ✅ 编译通过（73 个 class 已产出）    │
│          └─ mod  源集（Mixin/兼容）  🔄 76 个错误（见 5.5）               │
├──────────────────────────────────────────────────────────────────────────┤
│ 第 1 层  Adapter              127 文件               ✅ 已通 + 已发布      │
├──────────────────────────────────────────────────────────────────────────┤
│ 第 0 层  Fabric Loader fork   128 文件（9 个桥接）    ✅ 已通 + 已发布      │
└──────────────────────────────────────────────────────────────────────────┘
```

**本轮净进展**：
- Connector `main` 源集：**12 → 0 个错误，编译通过**（改造 A 降级 + 改造 B 完成 + 4 处新暴露的 API 差异）
- Connector `mod` 源集：编译推进到真实层，暴露出 **76 个 1.20.1→1.18.2 的 Minecraft/Forge API 差异**（此前被 main 源集的失败完全掩盖）

---

## 二、已确认的事实（实测，非推测）

| 事实 | 证据 |
| --- | --- |
| Connector 生态从未支持过 1.18.2 | 分支/标签/maven 三处实测 |
| **Adapter 核心与 MC 版本无关** | `definition` 模块 113 文件对 `net.minecraft.*` 零引用 |
| **Sinytra fabric-loader fork 与 MC 版本无关** | 9 个桥接类的 Forge API 在 1.18.2 全部存在且签名一致（javap 逐类核对） |
| **MixinTransmogrifier / FART 与 MC 版本无关** | 源码对 `net.minecraft.*` 零引用，纯字节码/Mixin 工具 |
| **1.18.2 Forge 运行时命名空间 = official 类名 + SRG 成员名** | javap 实证 |
| **`ForgeAutoRenamingTool` 不需要本地编译** | 上游 `org.sinytra:ForgeAutoRenamingTool:1.0.14` 是零 MC 依赖的薄 jar（66 类 / 97KB），1.18.2 直接可用 |
| **1.18.2 的 jar 包装层必须继承 `impl.Jar`** | `JarModuleFinder` 字节码里存在 `checkcast cpw/mods/jarhandling/impl/Jar` |

### 关键结论

这不是"改版本号"式移植，而是**重建兼容层**。实测证明**整条链的分层隔离度极高**：
每一层都只依赖 FORGE 的加载层 API，**只有 FFAPI 才是真正跟 Minecraft API 版本强绑定的那一层**。

---

## 三、目录结构（F 盘）

| 目录 | 来源 | 状态 |
| --- | --- | --- |
| `F:\Adapter-1.18.2` | Sinytra/Adapter `1.20.1` | ✅ 核心编译通过并发布 |
| `F:\SinytraLoader-1.18.2` | Sinytra/ForgifiedFabricLoader `1.20.1` | ✅ 编译 + 打包 + 发布 |
| `F:\MixinTransmogrifier-1.18.2` | Sinytra/MixinTransmogrifier `main` | ✅ 编译 + 打包 + 发布（改 1 处源码） |
| `F:\ForgeAutoRenamingTool-1.18.2` | Sinytra/ForgeAutoRenamingTool `master` | ⛔ **已废弃**：改用上游 1.0.14 |
| `F:\FabricApiStub-1.18.2` | **新建**：FFAPI 编译期桩 | ✅ 已发布（脚手架，非移植） |
| `F:\Connector-1.18.2` | Sinytra/Connector `1.20.1` | ✅ main 源集通过 / 🔄 mod 源集 76 错误 |
| `F:\ForgifiedFabricAPI-1.18.2` | Sinytra/ForgifiedFabricAPI `1.20.1` | ⏳ 待按新策略重建 |
| `F:\_baselines` | **新建**：上游基线 + 编译日志 + 探针 | ✅ |

---

## 四、已装入本地 Maven 的构件

| 坐标 | 说明 |
| --- | --- |
| `org.sinytra.adapter:definition:1.11.67-1.18.2` | Adapter 核心（113 文件，零代码修改） |
| `org.sinytra.adapter:runtime:1.0.0+1.18.2` | Adapter 运行时 |
| `dev.su5ed.sinytra:fabric-loader:0.0.0+0.14.25+1.18.2` | Loader fork（含 3.97MB `mappings.tsrg`） |
| `io.github.steelwoolmc:mixin-transmogrifier:0.4.7+1.18.2` | Mixin 兼容层 |
| `dev.su5ed.sinytra.fabric-api:fabric-api:0.0.0-1.18.2` | **FFAPI 1.18.2 编译期桩**（5 个类，见 5.2） |

**注意**：`org.sinytra:ForgeAutoRenamingTool` **不是**本地构件，直接用上游 `1.0.14`。

---

## 五、阶段 4 — Connector 本体

### 5.1 已完成

- [x] `gradle.properties`：`versionMc=1.18.2`、`versionForge=40.3.12`、各版本号对齐
- [x] `mods.toml`：`loaderVersion="[40,)"`、`versionRange="[1.18.2,1.19)"`
- [x] Parchment 映射改 `2022.11.06-1.18.2`
- [x] **FART 切回上游 `1.0.14`**（原本自建构件只发布了 `-all` 分类器 → 一次性消掉 79 个错误）
- [x] **FFAPI 编译期桩**（见 5.2）
- [x] 1.18.2 API 降级：进度条 / `ServiceRunner` / 定位器 SPI
- [x] **改造 A：早期加载钩子 → 反射式可选 hook**
- [x] **改造 B：`SecureJar.ModuleDataProvider` → `impl.Jar` 子类**（见 5.4）
- [x] `main` 源集**编译通过**（73 个 class）

### 5.2 FFAPI 编译期桩（`F:\FabricApiStub-1.18.2`）

Connector 本体**只引用 FFAPI 的 5 个类**：

| 类 | 位置 |
| --- | --- |
| `FluidRenderHandler` | `api/client/render/fluid/v1` |
| `FluidRenderHandlerRegistry` | `api/client/render/fluid/v1` |
| `FluidVariant` | `api/transfer/v1/fluid` |
| `FluidVariantAttributes` | `api/transfer/v1/fluid` |
| `DynamicRegistriesImpl` | `impl/registry/sync` |

→ 用手写的 5 个 stand-in 发布成 `dev.su5ed.sinytra.fabric-api:fabric-api:0.0.0-1.18.2`，
让 Connector 能进入**真正的编译阶段**。

**这是脚手架，不是移植**：它只在编译期存在，运行时不具备真实行为，
最终必须被真正的 FFAPI 1.18.2 构建替换。

### 5.3 已修复的真实 1.20.1 → 1.18.2 差异（全部有 javap/源码/运行实证）

| # | 1.20.1 用法 | 1.18.2 实际 | 处理 |
| --- | --- | --- | --- |
| 1 | `StartupNotificationManager.addProgressBar(name, n)` → `ProgressMeter` | **不存在**。只有 `StartupMessageManager.addModMessage(String)`，无计数器 | 新增 `ConnectorUtil.Progress` shim：报告一次消息，计数器置空操作 |
| 2 | `cpw.mods.modlauncher.api.ServiceRunner` | **不存在**（ModLauncher 10+；1.18.2 是 9.1.3） | 在 `ConnectorUtil` 内声明等价的嵌套 `@FunctionalInterface` |
| 3 | `net.minecraftforge.forgespi.locating.IDependencyLocator` | **不存在** | 见下方"重要发现" |
| 4 | `AbstractJarFileModProvider` | `AbstractJarFileModLocator` | `ConnectorLocator extends AbstractJarFileModLocator implements IModLocator`，实现 `scanCandidates()` → `Stream.empty()` |
| 5 | `IModLocator.ModFileOrException` | `AbstractModLocator.createMod(Path...)` → `Optional<IModFile>` | `createModOrThrow` 改 `.orElseThrow(...)` |
| 6 | `ModDiscoverer.dependencyLocatorList` | 只有 `modLocatorList` | `ConnectorEarlyLocator` 反射 `modLocatorList` |
| 7 | `SecureJar.moduleDataProvider().findFile(name)` | `SecureJar` 直接有 `findFile(String)` | 直接调用 |
| 8 | `SecureJar.moduleDataProvider().descriptor().version()` | 无该入口 | 读 manifest 的 `IMPLEMENTATION_VERSION` |
| 9 | `SecureJar.moduleDataProvider().getManifest()`（`FabricMixinBootstrap`） | `SecureJar.getManifest()` | 直接调用 |
| 10 | `Component.literal(s)` / `Component.empty()` | **都不存在**（`Component` 是接口，只有 `nullToEmpty`） | 桩内改用 `Component.nullToEmpty("")` |
| 11 | `DataResult.error(Supplier<String>)` | `DataResult.error(String)`（1.18.2 无 Supplier 重载） | 去掉 lambda（`ConnectorConfig`） |
| 12 | **`LogMarkers.SCAN` 是 slf4j `Marker`、`LogUtils.getLogger()` 可接受** | **`LogMarkers.SCAN` 是 log4j `Marker`**，而 `LogUtils.getLogger()` 返回 **slf4j `Logger`**（无 Marker 重载） | `ConnectorLocator` 的 LOGGER 换成 **log4j `LogManager.getLogger(...)`**（1.18.2 的 FML 自己就这么用） |
| 13 | Gson 2.10+ 的 `JsonArray.asList()` | Gson **2.8.9** 无此方法 | 改用 `StreamSupport.stream(jsonArray.spliterator(), false)` |
| 14 | `new ModFileInfo(ModFile, IConfigurable, Consumer<ModFileInfo>, List)` | 只有 `(ModFile, IConfigurable, List<LanguageSpec>)` | 去掉 `Consumer` 参数 |

#### 重要发现：1.18.2 的「依赖定位」本就是 `IModLocator` 的第二个方法

1.18.2 的 `ModDiscoverer.discoverMods()`（**fmlloader 源码实证**）分两趟：

```java
// 第一趟
var locatedFiles = locator.scanMods();
...
// 第二趟 —— 日志原文就是 "Attempting to load dependencies..."
LOGGER.debug(LogMarkers.SCAN, "Successfully Loaded {} mods. Attempting to load dependencies...", ...);
locator.scanMods(locatedMods);
```

而 `IModLocator` 的两个默认方法都返回 `Collections.emptyList()`（`javap -c` 实证）。

→ **1.20.1 的 `IDependencyLocator` 只是把第二趟抽成了独立接口**，
所以 1.18.2 上 `implements IModLocator` + 重写 `scanMods(Iterable<IModFile>)` **完全等价**。

### 5.4 两个结构性改造

#### 改造 A — 早期加载钩子：**已降级为反射式可选 hook**（⚠️ 挂载点仍待设计）

| 项 | 内容 |
| --- | --- |
| 1.20.1 做法 | 反射拿 `ImmediateWindowHandler.provider`，用匿名子类重写 `updateModuleReads(ModuleLayer)`，在 GAME 层建立后运行 `ConnectorEarlyLoader.setup()` / `preLaunch()` |
| 1.18.2 现状 | **完全没有**：两个类不存在，`updateModuleReads` / `ImmediateWindow` 字符串在 fmlloader 里**一个都搜不到** |
| 本轮处理 | 改为 `Class.forName` 反射查找，找到就装 hook、找不到就**告警 + no-op**。代码保持诚实，不假装能工作 |
| ⚠️ 未解决 | **没有这个钩子，Fabric entrypoint 永远不会被初始化**。必须找到 1.18.2 的等价挂载点。候选：`EarlyProgressVisualization.handOffWindow(...)` 的调用点、`ITransformationService` 生命周期、`BackgroundWaiter.runAndTick(...)` |

#### 改造 B — `SecureJar.ModuleDataProvider`：**✅ 已完成**

| 项 | 内容 |
| --- | --- |
| 涉及文件 | `service/FabricASMFixer.java`、`service/hacks/ModuleLayerMigrator.java`、**新增** `service/hacks/SecureJarImpls.java` |
| 1.20.1 接口 | `SecureJar.ModuleDataProvider { descriptor(); name(); uri(); findFile(String); open(String); getManifest(); verifyAndGetSigners(...); }`（securejarhandler 2.x） |
| **1.18.2 对应物** | `cpw.mods.jarhandling.JarMetadata { name(); version(); descriptor(); }`（securejarhandler **1.0.8**） |

**根因（字节码实证）** — 1.18.2 的 `JarModuleFinder` 只接受实体 `impl.Jar`：

```java
private static JarModuleFinder$1ref lambda$new$0(SecureJar jar) {
    return new 1ref(jar, new JarModuleReference((Jar) jar)); // checkcast cpw/mods/jarhandling/impl/Jar
}
```

而 `JarModuleFinder$JarModuleReference` 的字段本身也声明为
`private final cpw.mods.jarhandling.impl.Jar jar;`。
→ 1.20.1 那种"手写一个 `SecureJar` 实现"的做法会在建模块层时直接 `ClassCastException`。

**第二重差异（字节码实证）** — 1.18.2 的 `JarModuleReader.open(String)` 是：

```
jar.findFile(name) → map(Paths::get) → map(Files::newInputStream)
```

1.20.1 的 `ModuleDataProvider.open(String)` 能直接吐一个内存里的 `InputStream`；
1.18.2 只能给出 `URI`，而且必须能被 `Paths.get` 解析成**真实文件**。

**落地方案**：

1. 新增 `SecureJarImpls`，其 `ConnectorJar extends cpw.mods.jarhandling.impl.Jar`，
   只覆写 `getPackages()` 与 `findFile(String)`（模块名通过传给基类的 `JarMetadata` 控制）。
   `Jar` 非 final、构造函数 public、这两个方法都可覆写（javap 实证）。
2. 四个用途各对应一个静态工厂：

   | 工厂 | 用途 | 对应 1.20.1 |
   | --- | --- | --- |
   | `generated(base, name, lookup)` | GAME 层的生成类 jar | `FabricASMGeneratedClassesSecureJar` |
   | `overlay(original, lookup)` | 包住 minecraft 模块的 jar | `ModuleDataProviderWrapper`（minecraft 用） |
   | `renamed(original, name)` | 换名（`connector$authlib`） | `SimpleSecureJar` |
   | `empty(original, name)` | 空 jar，阻断二次加载 | `EmptyModuleDataProvider` |

3. **运行时生成类改为落盘**：`SecureJarImpls.classUriMaterialiser(cacheDir, URLs)`
   把 fabric-asm 的 `URL` 内容拷进 `Files.createTempFile(...)` 再返回其 `URI`，
   由 `JarModuleReader` 用 `Files.newInputStream` 读回。**只缓存命中，不缓存 miss**
   （fabric-asm 可能在之后才注册新 URL）。
4. `FabricASMFixer.injectMinecraftModuleReader()` 把 `JarModuleReference.jar` 字段
   换成 `SecureJarImpls.overlay(...)`（`UnsafeHacks` 是泛型的，运行时字段确实是 `Jar`）。
5. `ModuleLayerMigrator.REF_MODULE_PROVIDER_FIELD` 的 `VarHandle` 类型改 `Jar.class`；
   `DESCRIPTOR_PACKAGES_FIELD`（清空原模块 packages 以避开 split package）保持不变。

**尚未验证**：以上均通过编译，但**运行时行为需要实机启动验证**
（`impl.Jar` 子类能否在真实 FML 启动流程里被接受、临时目录落盘是否有权限/时序问题）。

### 5.5 剩余 76 个错误 = `mod` 源集（Mixin + 兼容层）

全部集中在 `src/mod/java`，按根因分 5 类：

| # | 类别 | 涉及文件 | 1.18.2 对应物 | 预估难度 |
| --- | --- | --- | --- | --- |
| ① | **注册表包迁移** `net.minecraft.core.registries` | `ConnectorMod`、`LateSheetsInit`、`BlockColorsMixin`、`ItemColorsMixin`、`ParticleEngineMixin`、`BuiltInRegistriesMixin` | 1.18.2 是 `net.minecraft.core.Registry` + `net.minecraft.core.RegistryAccess`，且**没有** `BuiltInRegistries`（1.19.2 才有） | 中（面广但机械） |
| ② | **1.20 HUD 体系** `GuiGraphics` / `client.gui.overlay` / `RenderGuiEvent` | `GuiMixin`(10)、`ForgeGuiMixin`(8)、`GuiExtensions`(8)、`HudRenderInvoker`(3) | 1.18.2 用 `PoseStack` + `RenderGameOverlayEvent` + `ForgeIngameGui` | 高（**需重写**） |
| ③ | **1.19+ 流体 API** `FluidType` / `IClientFluidTypeExtensions` / `RegisterEvent` | `FluidHandlerCompat`(10) | 1.18.2 用 `FluidAttributes` + `RegistryEvent.Register<Fluid>` | 高（**需重写**） |
| ④ | **Mixin target 在 1.18.2 不存在** | `ForgeGui`(1.20)、`RecipeBookManager`(1.19+)、`BuiltInRegistries`(1.19.2+)、`RegistryDataLoader`(1.19.3+)、`PathPackResources`、`PoiTypes` | 一部分**直接删除**，一部分改 target | 中（需逐个判断必要性） |
| ⑤ | **零散 API 变更** | `TagConverter`、`KeyMappingMixin`、`ItemOverridesMixin`、`ForgeHooksMixin`、`TagLoaderMixin`、`ConnectorLoader` | 逐个查 javap | 中 |

**判断要点**：④ 里的 `RecipeBookManager` / `BuiltInRegistries` / `RegistryDataLoader` 针对的是
**1.19+ 才引入的机制**，在 1.18.2 上**根本不存在对应问题**，正确做法通常是**直接删除该 mixin**，
而不是硬造一个 target。

---

## 六、阶段 5 — ForgifiedFabricAPI（最大工程，策略已确定）

### 6.1 关键统计（实测）

| 指标 | 数量 |
| --- | --- |
| 全部文件 | 1073 |
| 引用 `net.minecraftforge` 的文件 | 207 |
| 其中 **排除 testmod 后** | **126** |
| 其余 | ~800 个文件是**上游 Fabric API 原码** |

Forge 胶水高度收敛：集中在 `impl/**/compat/*`、`impl/**` 的少量类和 `mixin/**`。

### 6.2 策略：补丁式移植（而不是把 1.20.1 全量降级）

因为 FFAPI 本质是 **「上游 Fabric API + 一层 Forge 胶水」**，所以：

1. **基线** = 上游 **Fabric API `1.18.2` 分支**源码（sha `db2d6da`，可下载，已抓取到 `F:\_baselines`）
   → 这部分**天然为 1.18.2 编译通过**，零移植成本
2. **叠加** = FFAPI 相对上游 Fabric API 的 **Forge 差异补丁**（≈126 个非测试文件）
3. 用 `F:\_baselines\fabric-1.20.1.tar.gz` 与 `F:\ForgifiedFabricAPI-1.20.1` 做 diff，
   把差异**精确提取**成最小补丁集，再落到 1.18.2 基线上

这比"把 1.20.1 的 9.2 万行逐行降到 1.18.2"小一个数量级。

### 6.3 待办

- [ ] 从上游 Fabric API `1.18.2` 建立基线工作区
- [ ] 与 `ForgifiedFabricAPI-1.20.1` 做目录级 diff，产出 Forge 补丁清单
- [ ] 单模块试点：`fabric-api-base` 先编译通过，建立降级模式
- [ ] 验证 Architectury Loom 1.3 的 `platform=forge` 在 1.18.2 可用
      （**已有正面证据**：本机 Gradle 缓存里已存在 `forge-1.18.2-40.2.x-minecraft-merged-named.jar`）
- [ ] 横向铺开到全部 45 个子模块
- [ ] 用真实 FFAPI 替换 `F:\FabricApiStub-1.18.2` 的桩

---

## 七、1.18.2 API 差异速查表（持续累积）

| 1.20.1 / 1.19+ | 1.18.2 |
| --- | --- |
| `Component.literal(s)` / `Component.empty()` | **都不存在**。`Component` 仍是接口，唯一静态工厂是 `nullToEmpty(String)`；或用 `new TextComponent(s)` |
| `DataResult.error(Supplier<String>)` | `DataResult.error(String)` |
| `StartupNotificationManager` / `ProgressMeter` | `StartupMessageManager.addModMessage(String)` |
| `ImmediateWindowHandler` / `ImmediateWindowProvider` | 无（用 `EarlyProgressVisualization` / `BackgroundWaiter`） |
| `ServiceRunner`（modlauncher 10+） | `LamdbaExceptionUtils.Runnable_WithExceptions` |
| `IDependencyLocator` | `IModLocator.scanMods(Iterable<IModFile>)`（第二趟） |
| `AbstractJarFileModProvider` | `AbstractJarFileModLocator` |
| `IModLocator.ModFileOrException` | `AbstractModLocator.createMod(Path...)` → `Optional<IModFile>` |
| `ModDiscoverer.dependencyLocatorList` | `ModDiscoverer.modLocatorList` |
| `SecureJar.ModuleDataProvider` | `cpw.mods.jarhandling.JarMetadata`（三方法） |
| `SecureJar.moduleDataProvider().open(String)` | **不存在**：`SecureJar.findFile(String)` → `URI` → `Paths.get` → `Files.newInputStream` |
| 自定义 `SecureJar` 实现 | **不可以**：`JarModuleFinder` 会 `checkcast impl.Jar`，必须继承 `impl.Jar` |
| `LogMarkers.SCAN`（slf4j `Marker`）+ `LogUtils.getLogger()` | `LogMarkers.SCAN` 是 **log4j `Marker`**，必须配 log4j 的 `LogManager.getLogger(...)` |
| Gson `JsonArray.asList()`（2.10+） | 无（Gson **2.8.9**），用 `StreamSupport.stream(arr.spliterator(), false)` |
| `new ModFileInfo(ModFile, IConfigurable, Consumer<ModFileInfo>, List)` | `(ModFile, IConfigurable, List<LanguageSpec>)` |
| `net.minecraft.core.registries.*` | `net.minecraft.core.Registry`（`BuiltInRegistries` 1.19.2 才有） |
| `GuiGraphics`（1.20） | `com.mojang.blaze3d.vertex.PoseStack` |
| `net.minecraftforge.client.gui.overlay.*` / `RenderGuiEvent` | `RenderGameOverlayEvent` + `ForgeIngameGui` |
| `FluidType` / `IClientFluidTypeExtensions`（1.19+） | `FluidAttributes` |
| `net.minecraftforge.registries.RegisterEvent` | `RegistryEvent.Register<T>` |
| `com.mojang:logging:1.0.1` | **1.0.0**（1.0.1 是 1.19+） |
| `FMLPaths.getOrCreateGameRelativePath(Path)` | `(Path, String)` 两参数 |

---

## 八、环境

| 项 | 值 |
| --- | --- |
| JDK | 17.0.15 (Liberica) `D:\Program Files\BellSoft\LibericaJDK-17\` |
| Gradle | 8.4（Connector/桩）+ 8.8（各工具项目） |
| Forge 1.18.2 | 40.3.12 |
| Parchment 1.18.2 | 2022.11.06-1.18.2 |
| Fabric Loader 上游 | 0.14.25 |
| ModLauncher | 9.1.3 |
| forgeSPI | 4.0.15-4.x |
| securejarhandler | **1.0.8** |
| fmlloader | 1.18.2-40.2.10（缓存内，含 **sources jar** 可读原始实现） |

### 调试速查

- **FG 编译期 MC jar**（javap 用，official 命名）：
  `~/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.18.2-40.3.12_mapped_official_1.18.2/forge-1.18.2-40.3.12_mapped_official_1.18.2.jar`
- **fmlloader 1.18.2 源码**（读 FML 原始实现）：
  `~/.gradle/caches/fabric-loom/forge/transformed-dependencies-v1/.../fmlloader/1.18.2-40.2.0/fmlloader-1.18.2-40.2.0-sources.jar`
- **securejarhandler 1.0.8**：`~/.gradle/caches/modules-2/files-2.1/cpw.mods/securejarhandler/1.0.8/...`
- Windows 下 `javap` 的 classpath 分隔符是 **`;`** 不是 `:`
- 判断某个 API 是否存在的最快方式：`jar xf x.jar && grep -rl "<字符串>" .`
- 想知道某接口是否可自行实现：看调用方字节码里有没有 `checkcast`

### 踩坑

- `gradle.properties` **不支持行内注释**（`key=value  # xx` 会把注释并入值）
- Gradle 的 javac 输出受 daemon 的 `file.encoding` 影响；加
  `-Dfile.encoding=UTF-8 -Duser.language=en -Duser.country=US` 才能看到英文错误
- Shadow 构建自建的库时：**别把 shadow jar 当主构件发布**。
  Connector 的 `shade` 是 `isTransitive = false`，它期望**上游那种薄 jar**（依赖由 POM 声明）
- **javac 会因为早期错误而跳过整个编译单元**：`FabricMixinBootstrap` 里的
  `moduleDataProvider()` 在第一次编译时从未报错，因为它所在的源集在更早阶段就失败了。
  → 修完一批错误后**必须重新全量编译**，不要以为"没报错就是没问题"
