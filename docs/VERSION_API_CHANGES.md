# Minecraft 版本间 API 变动记录（移植手册）

Numen 采用**分支即版本**模型：每个受支持的 MC 版本一条分支（`1.21.1`、`1.21.4`、…、`26.1.2`），
Fabric + NeoForge 同源。向上移植（把低版本分支的代码搬到高版本）时，绝大多数改动是
**机械的映射/签名替换**——本文件逐版本记录这些 MC/loader API 变动，作为移植配方。

> 规则：每完成一档移植（`A → B`），把这一档碰到的**每一个** API 变动追加到对应小节。
> 宁可啰嗦：一条记录省下的是下一个人（或下一个 MC 版本）重新踩坑的时间。

约定：
- ❗ = 编译期会直接报错的破坏性变更；🔁 = 行为/语义变化需留意；📦 = 构建/依赖（gradle.properties 等）。
- 代码示例用 `旧 → 新`。

---

## 版本阶梯

`1.20.1 → 1.20.2 → 1.20.4 → 1.20.6 → 1.21.1 → 1.21.4 → 1.21.5 → 1.21.8 → 1.21.10 → 1.21.11 → 26.1.2`

新架构（Numen API 拆分 + 调度器 + raw `NumenTool` + skill 体系）当前基线在 **`1.21.1`**，正逐档向上移植。

---

## 每档都要改的构建旋钮 📦

`gradle.properties`（Numen 与 api 各一份）：

| 键 | 含义 |
|---|---|
| `minecraft_version` | 目标 MC，如 `1.21.4` |
| `minecraft_version_range` | 如 `[1.21.4, 1.21.5)` |
| `neo_form_version` | NeoForm 数据版本（见 projects.neoforged.net/neoforged/neoform） |
| `fabric_version` | Fabric API，如 `0.117.0+1.21.4` |
| `neoforge_version` | 如 `21.4.123` |
| `fabric_loader_version` | 一般跨小版本不变 |

loader 依赖 build.gradle 里的 `numen-api-*-<mc>` 坐标也要同步成目标 MC 版本（api 须先发对应版本到 maven）。

> 下载 MC 用国内镜像（BMCLAPI），否则容易卡死/断流。

---

## 1.21.1 → 1.21.4

来源：老架构 `v0.0.2-1.21.1-beta` ↔ `v0.0.2-1.21.4-beta` 的纯 MC delta（约 30 个 java 文件）。
新架构里文件路径/包名已变（`tulpa`→`numen`、工具类移入 `numen/tools`），但**API 替换内容一致**。

### 注册表查询 ❗
按 `ResourceLocation` 取值的方法整体重命名：
```java
BuiltInRegistries.ITEM.get(id)         → BuiltInRegistries.ITEM.getValue(id)
BuiltInRegistries.ENTITY_TYPE.get(id)  → BuiltInRegistries.ENTITY_TYPE.getValue(id)
// 同理 BLOCK、MOB_EFFECT 等所有 BuiltInRegistries.* 的 get(ResourceLocation)
```
波及（新架构对应类）：`CollectItems`、`DropItems`、`EatItem`、`Equip`、`Hunt`、
`InteractAt`、`InteractEntity`、`ScanBlocks`、`PlaceBlock`、`MineBlock` 等所有按 id 取 Item/EntityType 的工具与 task。

### registryAccess 查注册表 ❗
```java
registryAccess().registryOrThrow(Registries.STRUCTURE)  → registryAccess().lookupOrThrow(Registries.STRUCTURE)
```
波及：`GetSelfStatusTool`（结构感知）、`LocateBiome*`、任何 `registryOrThrow`。

### 配方系统 ❗（改动最大）
1. RecipeManager 入口换名：
   ```java
   level.getRecipeManager().getRecipes()  → level.recipeAccess().getRecipes()
   ```
2. 通用配料获取走 `PlacementInfo`（1.21.1 没有此类）：
   ```java
   // 判空：
   cr.getIngredients().isEmpty() || allMatch(Ingredient::isEmpty)
     → PlacementInfo info = cr.placementInfo();
       info.isImpossibleToPlace() || info.ingredients().isEmpty()
   // 遍历配料：
   recipe.getIngredients()  → recipe.placementInfo().ingredients()
   ```
3. 单输入配方（熔炼/切石）直接 `.input()`：
   ```java
   sc.getIngredients().get(0)        → sc.input()                 // StonecutterRecipe
   cookingRecipe.getIngredients().get(0) → cookingRecipe.input()  // AbstractCookingRecipe
   ```
4. shaped 配方网格类型变了（gap 由 `Ingredient.EMPTY` 变 `Optional.empty()`）：
   ```java
   NonNullList<Ingredient> cells = shaped.getIngredients();
   cells.get(i).isEmpty() ? "." : describe(cells.get(i))
     → List<Optional<Ingredient>> cells = shaped.getIngredients();   // row-major
       cells.get(i).map(LookupRecipeTool::describe).orElse(".")
   ```
   需 `import net.minecraft.world.item.crafting.PlacementInfo;`
波及：`LookupRecipeTool`（主要）。

### Client / UI 渲染（待移植时逐条补全）
老分支这一档还改了下列客户端文件，多为渲染签名（GuiGraphics / 文本测量 / 颜色）调整，
移植到新架构的 `client/` 时按编译错误对照补：
`NumenScreen`(老 `TulpaScreen`)、`Dropdown`、`ProviderDropdown`、`FlatEditBox`、
`SimpleButton`、`PathVizRenderer`、`NumenToasts`。
<!-- TODO: 实际移植时把每个渲染 API 变动写到这里 -->

### 其它
`BlockDigger`、`NavSnapshot`、`CachedNavView`、`ScanBlocksJob`、`EquipCompanionTask`、
`ShootCompanionTask`、`EatCompanionTask`、`InteractAtTaskRecord` 有零星签名微调（各 1–4 行）。
<!-- TODO: 移植时确认并记录 -->

---

## 1.21.4 → 1.21.5
<!-- 来源：v0.0.2-1.21.4-beta ↔ v0.0.2-1.21.5-beta（约 16 文件）。移植时填写。 -->
_待移植时填写_

## 1.21.5 → 1.21.8
<!-- 约 24 文件 -->
_待移植时填写_

## 1.21.8 → 1.21.10
_待移植时填写_

## 1.21.10 → 1.21.11
_待移植时填写_

## 1.21.11 → 26.1.2
_（升级链的完整配方在各上行分支的本文件里；本分支是从 1.21.1 向下分出的）_

---

# 向下移植（↓ 低于 1.21.1）

新架构基线在 1.21.1;往下是把 1.21.1 的新 API **改回**旧 API。参考物同样是老架构 tag diff,
方向取 `git diff v0.0.2-1.21.1-beta v0.0.2-1.20.x-beta` 的 `+` 侧(即 1.20.x 的写法)。

## 1.21.1 → 1.20.6 ✓（已验证,双 loader 编译 + 出包通过）

构建旋钮:MC `1.20.6` / range `[1.20.6, 1.21)` / NeoForm `1.20.6-20240627.102356` /
Fabric `0.100.8+1.20.6` / **Fabric loader `0.16.10`** / NeoForge `20.6.139`。Java 仍 21;fabric/build.gradle 仍 remap loom(不动)。

### 反向 MC delta
```java
// ResourceLocation 工厂 → 公开构造器(1.20.6 构造器是 public;工厂是 1.21+)。大量文件(payload/screen/entity)：
ResourceLocation.fromNamespaceAndPath(ns, path) → new ResourceLocation(ns, path)
//   注意全限定写法要修正为 new net.minecraft.resources.ResourceLocation(...)（别写成 net.minecraft.resources.new …）
// 配方 assemble → getResultItem（1.20.6 无 CraftingInput/SingleRecipeInput/SmithingRecipeInput）：
cr.assemble(CraftingInput.EMPTY, ra) → cr.getResultItem(ra)
cook/sc.assemble(new SingleRecipeInput(ItemStack.EMPTY), ra) → .getResultItem(ra)
sm.assemble(new SmithingRecipeInput(EMPTY,EMPTY,EMPTY), ra) → sm.getResultItem(ra)
//   删掉那三个 crafting.*Input import。
// VertexConsumer 旧链式（PathVizRenderer）：
vc.addVertex(pose,…).setColor(c).setNormal(pose,…)
  → vc.vertex(pose.pose(),…).color(c).normal(pose,…).endVertex()
// FakeConnection：删 disconnect(DisconnectionDetails) 重写（1.21+ 才有）+ 其 import；保留 disconnect(Component)。
```

> ⚠ **NeoForge publish 需在线**:1.20.6 的 NeoForm runtime 依赖 `log4j:2.11.+`(动态版本),
> `--offline` 解析不了 → publish 用**在线**(非 MC 下载,只拉 maven 制品)。

## 1.20.6 → 1.20.4 ✓（分水岭：NeoForge → **Forge** + Java 21 → **17**；双 loader 编译 + 出包通过）

**这不是机械档,是加载器 + 工具链切换。** `1.20.4` 及以下用 **Forge**(不是 NeoForge)、**Java 17**。
构建旋钮:MC `1.20.4` / range `[1.20.4, 1.21)` / Fabric `0.92.1+1.20.4` / **loader `0.15.3`** /
`forge_version=49.0.19`(去掉 neoforge/neoform);`java_version=17`。

### 构建机制（整套换掉）❗
- **Gradle wrapper 9.2 → 8.7**(9.2 与 loom 1.14 都要 Java 21,和 1.20.4 的 Java 17 冲突)。
- `common`:NeoForge moddev → **Sponge VanillaGradle**(`org.spongepowered.gradle.vanilla`);mixin 0.8.5 / mixinextras 0.3.5。
- Fabric:`fabric-loom-remap` → 普通 **`fabric-loom` 1.6-SNAPSHOT** + `officialMojangMappings()`。
- `forge/` 子项目:**ForgeGradle 6** (`net.minecraftforge.gradle [6.0,6.2)`) + Sponge mixin + `reobfJar`。
- `settings.gradle` 加 Forge + Sponge 仓库、`include('forge')`;buildSrc expandProps neoforge→forge(`META-INF/mods.toml`)。api 那边额外保留 `apiJar`/`numen-api-*` 发布。
- 删 `neoforge/` 子项目。**三个 build.gradle 的 api 坐标都要 → 1.20.4。**

### Forge 平台层（api）❗
NeoForge 的 4 个平台实现要重写成 Forge 版(在 api):
- `ForgePlatformHelper`(`ModList`/`FMLPaths`/`FMLEnvironment`)、`ForgeNumenConfig`(`ForgeConfigSpec`)、
  `ForgeBlockCapabilityReader`(`ForgeCapabilities` item/fluid/energy,null + 6 面)、
  `ForgeNetworkChannel`(单 `SimpleChannel`,所有 payload 走一个 `(id,bytes)` 信封,靠 `payload.id()` 路由)。
- Forge `@Mod` 入口 + client + datagen + `META-INF/mods.toml` + 4 个 `META-INF/services` provider 文件 + `*.forge.mixins.json`。

### 网络层降级（api common）❗**最大的坑**
1.20.4 **没有** `StreamCodec`/`RegistryFriendlyByteBuf`/`CustomPacketPayload.Type`/`ByteBufCodecs`。
所有 payload(12 个)+ `INetworkChannel` + `NumenNetwork` + 两端通道重写成 1.20.4 形状:
`ID` 常量 + `id()` + `write(FriendlyByteBuf)` + 静态 `read(FriendlyByteBuf)` 当 decoder;`ItemStack` 用 `writeItem/readItem`。
> ⚠ 反向 delta 会漏掉这个(它是 1.20.5 引入的,不在 1.20.6↔1.20.4 老 tag diff 里,因为老 tag 本身是 1.20.4 老写法)。

### Java 17 / 1.20.4 语言 + MC 降级
```
sealed switch(类型模式) → if/else instanceof（ConvoLog、NumenLlmClient、NumenScreen）
Math.clamp(v,lo,hi) → Math.max(lo, Math.min(hi, v))；Mth.clamp 保留
VertexConsumer.setColor/setNormal(pose) → color/normal(pose.normal(), …).endVertex()（Matrix3f 重载）
CommonListenerCookie.createInitial(profile, false) → createInitial(profile)
PlayerList.load 返回 nullable CompoundTag（不是 Optional）；SavedData save/load 去掉 HolderLookup.Provider 参
new ResourceLocation(ns,path)（公开构造器）；recipe.getResultItem(registryAccess())
chunk.status.ChunkStatus → chunk.ChunkStatus；player.blockInteractionRange() → 4.5D
HuntCompanionTask getAttributeModifiers(slot) Multimap；food：getFoodProperties()/isEdible()
ItemStack.isSameItemSameComponents → isSameItemSameTags；FakeConnection 只有 disconnect(Component)
```

### ⚠ Forge JiJ + 产物文件名
core 内嵌 api 用 **ForgeGradle jarJar**:`jarJar.enable()` + `jarJar(group,name,version){ transitive=false; jarJar.ranged(it,'[0.0.0,)') }`。
`transitive=false` 必须(否则把整个 Forge/MC 依赖图打进去,94MB)。
**FG6 的 jarJar 产物带 `-all` classifier** → 可分发的 Forge 单文件是 `numen-forge-1.20.4-0.0.3-all.jar`(内嵌 api),
plain `numen-forge-1.20.4-0.0.3.jar` 是不含 api 的 reobf jar。**发布/CI 要发 `-all.jar`。**

## 1.20.4 → 1.20.2 ✓（纯旋钮档）

老 tag 之间 **零源码差异**,只有 `gradle.properties`:MC `1.20.2` / range `[1.20.2, 1.21)` /
Fabric `0.91.0+1.20.2` / loader `0.15.0` / Forge `48.0.49`(+ core 三个 build.gradle 的 api 坐标 → 1.20.2)。

## 1.20.2 → 1.20.1 ✓（最老支持版；Forge/Java17;双 loader 编译 + 出包通过）

构建旋钮:MC `1.20.1` / range `[1.20.1, 1.21)` / Fabric `0.92.1+1.20.1` / loader `0.15.11` / Forge `47.2.30`。
1.20.1 **早于 1.20.2 的 configuration phase 和 sprite GuiGraphics**,所以多处 API 回退(基本全在 api,core 少量)。

### api ❗
```java
// GuiCompat shim（新文件）：1.20.1 的 GuiGraphics 没有 blitSprite(ResourceLocation,…)（sprite blit 是 1.20.2+）。
//   自己按 PNG 头读尺寸 + mcmeta nine_slice 拼 blit。所有 g.blitSprite(sprite,…) → GuiCompat.blitSprite(g, sprite,…)（24 处/6 屏）。
//   附带 1.20.1 屏幕回退：skin 用 ResourceLocation、mouseScrolled 3 参、renderEntityInInventory 锚点式。
// config-phase 回退：
new NumenPlayer(server, level, profile, ClientInformation.createDefault()) → new NumenPlayer(server, level, profile)
placeNewPlayer(conn, player, CommonListenerCookie.createInitial(profile)) → placeNewPlayer(conn, player)   // 2 参
CompanionRegistry: 去 SavedData.Factory → getDataStorage().computeIfAbsent(::load, ::new, "numen_companions")
FakeConnection: 换 1.20.1 版本体（send(Packet, PacketSendListener) 两参,无 runOnceConnected/flushChannel）
// mixin 换目标：ServerCommonPacketListenerImpl（config-phase 类，1.20.1 没有）→ ServerGamePacketListenerImpl；改 mixins.json
// Forge 网络：Forge 47.x classic —— NetworkRegistry.newSimpleChannel（不是 ChannelBuilder）+ registerMessage + NetworkEvent.Context
```

### ❗ 最大的坑:`CustomPacketPayload` 1.20.1 不存在
`net.minecraft.network.protocol.common.custom.CustomPacketPayload` 是 1.20.2 configuration-phase 引入的,1.20.1 没有。
api 引入 mod-local 接口 `com.dwinovo.numen.network.NumenPayload`(`ResourceLocation id()` + `write(FriendlyByteBuf)`),
把 api 的 13 个 payload + `INetworkChannel` + 两端通道 + **core 的 3 个 net payload**(ExecuteTool/TaskResult/CancelTasks)
的 `implements CustomPacketPayload` 全换成 `implements NumenPayload`(`id()/write()/static read()` 不变)。

### core ❗
```java
// 除上面的 payload → NumenPayload 外：
// RecipeHolder 1.20.1 不存在,getRecipes() 直接返回 Recipe<?>（QueryExtraTools）：
for (RecipeHolder<?> h : mgr.getRecipes()) { Recipe<?> r = h.value(); … }
   → for (Recipe<?> r : mgr.getRecipes()) { … }   // 去掉 import RecipeHolder
```

### 1.20.1 落地实录(2026-08 并仓迁移新踩的坑,1.20.2/1.20.4 分支照抄)❗
```groovy
// ① 同仓 api 的 mixin 在 Forge 开发运行里不会自动注册：api 以源码集进运行,
//    没有 mod jar 的 MixinConfigs MANIFEST 项 → BoatAccessor 等运行期根本没打进去
//    （表现为 Boat cannot be cast to BoatAccessor,载具/穿门两条 GameTest 挂）。
//    core/forge 每个 run 显式挂（发行物不受影响,api jar MANIFEST 已带 MixinConfigs,jarJar 进 core）：
minecraft.runs.configureEach {
    args '--mixin.config', 'numen_api.mixins.json'
    args '--mixin.config', 'numen_api.forge.mixins.json'
}
```
```groovy
// ①c mixin AP 与增量编译不相容：增量轮次不重跑 AP，refmap 丢失且 jar 静默不带；开发期全程 disableRefMap 照不出，生产 SRG 直接拒启。修法：api/forge、core/forge 两处 compileJava options.incremental=false + jar doLast 绞索（有 mixins.json 必有 refmap，缺了判死）。
// ①b FG6 双输出共目录的任务竞态：api/forge 把 classes+resources 重定向进同一个
//    build/sourcesSets/main，跨项目 classpath 的 builtBy 只挂 compileJava →
//    :core:forge:compileJava 与 :api:forge:processResources 写/读同目录无依赖边，
//    Gradle 8 验证器看调度顺序间歇性报 implicit_dependency 把构建直接判死。
//    产出侧一行治好所有消费方（api/forge/build.gradle 重定向块之后）：
tasks.named('compileJava') { dependsOn tasks.named('processResources') }
```
```java
// ② GameTest 夹具的三个 1.20.1 数据形态（1.21 形态写进去就是静默空数据）：
//    旗帜花纹：Patterns 短哈希 + 染料序数（不是 patterns + 注册名/颜色名）
layer.putString("Pattern", "ts"); layer.putInt("Color", 14);   // stripe_top / 红
//    物品 NBT 的数量是 byte "Count"（int "count" 读出来是空叠）：
stack.putByte("Count", (byte) 1);
//    潜影盒内容在 tag.BlockEntityTag.Items（不是 components.minecraft:container）,
//    自定义名在 tag.display.Name。
// ③ 1.20.1 空花纹也会写出 Patterns:[] —— 断言"花纹存活"必须查列表非空,光查键名会假绿：
!saved.getList("Patterns", Tag.TAG_COMPOUND).isEmpty()
```

---

## 对齐 1.21.1 之后的 Java 17 一代(1.20.1 先做,1.20.4 / 1.20.2 同一套写法)

0.1.5 这一轮对齐到 1.21.1 的 19c8710b0:整树换成 1.21.1(模块按模组 id 分:`numen-api/`、`numen/`、`numen/pathing`、纯 JVM 模块、
`plugins/`),再把这一代的平台差异逐处回放。每处 API 差异只在一个地方适配。1.20.1 上要改的东西,按类别:

### 构建
- 加载器是 Forge 47(ForgeGradle 6,`numen-api/forge`、`numen/forge`)与 Fabric(Loom 1.6,官方映射);common 与只写原版的联动(ysm、jei)用
  VanillaGradle。Forge 的成品是 SRG 名:`compileJava` 关增量(mixin 注解处理器在增量轮次不重跑,refmap 会静默丢掉),jar 里查 refmap。
- Numen API 在 Forge 上随 Numen 的 `jarJar` 发;它与内嵌库(commonmark、mcp 的第三方库)都挂在 `numen/forge` 的 jarJar 上(ForgeGradle 的 jarJar
  只认坐标,嵌不进别的项目的 -all)。
- 联动的 GameTest 跑批共用 `buildSrc/numen-plugin-gametest-forge.gradle`:运行类路径同本体一样带上 mcp 的重定位 jar;
  `forge.enabledGameTestNamespaces` 设成套件名(我们用例的模板命名空间),目标模组自带的 GameTest(车万女仆有一条)不跑。
- 端到端起服层是 `numen-e2e-forge.gradle`(ForgeGradle 的 `e2e` 运行配置)。ForgeGradle 6 在自己的 afterEvaluate 里把 userdev 的
  server 配置挂成 `server` 的父配置并当场按名字顺序只并一层,`e2e` 挂 `server` 当父配置拿不到主类(还会带上 GameTest 的东西),
  所以在那之后把 userdev 那份直接并进 `e2e`;运行任务的参数、类路径在任务动作里才拼,起服任务用 `RunConfigGenerator` 的同几个方法拼出同一条命令放到后台。
- 这一代没有的:`:bench:game`(只在 NeoForge 分支)、KubeJS 联动(KubeJS 6 没有 1.21 那套插件接口)、Paper 端(Paper 1.20.x 没有
  插件引导阶段装标签的接口)。
- mcp 的重定位 jar 用 Shadow 8.3.9(Shadow 9 要 Gradle 9,ForgeGradle 6 与 Loom 1.6 只到 Gradle 8),任务写法与 1.21.1 相同;8.3 这一线
  合成的清单不加 `Multi-Release`,不需要 `addMultiReleaseAttribute`。Forge 47 读声明了 Multi-Release 却没有多版本目录的 jar 直接起不来
  (snakeyaml-engine 就是这样),所以它也打进重定位 jar。
- Gson、slf4j 对着这一代游戏自带的版本编译与单测(1.20.1:Gson 2.10、slf4j 2.0.1)。
- 开发运行(游戏、数据生成、GameTest)也用 Java 17:Loom 的运行任务不跟着工具链走,`numen-common`/`numen-api-common` 给所有
  `JavaExec` 指定这一代的工具链。
- 根项目一处写齐 ForgeGradle、MixinGradle、VanillaGradle 的版本:子项目继承父项目的构建脚本类路径(`:plugins:ysm` 用 VanillaGradle、
  它的子项目 `:plugins:ysm:gametest` 用 ForgeGradle),各写各的话 srgutils 有两份,子项目里 ForgeGradle 初始化就崩。
- Forge 47 的事件总线变换器给每个新加载的类按线程上下文加载器查父类。外接大脑端点的内嵌 Tomcat 默认另建一个上级是系统加载器的
  应用类加载器、处理请求时换成线程上下文加载器,在模组加载器下它找不到类,专用服一起端点就刷几十行 EVENTBUS 错误;端点停了
  Reactor 的工作线程还带着作废的它。`McpEndpoint` 给上下文的加载器就是加载端点的那个(各版本同一写法)。

### 语言 / 线程(Java 17)
```java
switch (x) { case A a -> … }          →  if (x instanceof A a) { … } else if …   // 模式 switch、record 解构
x instanceof T t   // x 的静态类型就是 T  →  先判空再用            // Java 17 不许
Math.clamp(v, lo, hi)                  →  Mth.clamp(v, lo, hi)
list.getFirst()                        →  list.get(0)
Thread.ofVirtual().name(n).start(r)    →  守护平台线程(缓存线程池)
```
没有虚拟线程时语义不变:服务端主线程永远不等 Lua;一段程序的执行体占一条守护线程(`ServerPrograms`),阻塞的宿主函数就在脚本自己的线程上等。

### 网络(没有 `CustomPacketPayload` 与配置阶段)
- 载荷实现 `NumenPayload`(`id()` + `write(FriendlyByteBuf)`,静态 `read`);`INetworkChannel` 按 `ResourceLocation` 登记解码器。
- Forge 47 只有一条 `SimpleChannel`(`numen_api:main`),所有载荷装进 `Envelope(id, bytes)` 多路复用,包头开销记在 `Wire.FRAMING = 64`;
  `canSend` 问 `isRemotePresent`。Fabric 一种载荷一条频道,客户端进游戏后才发 `minecraft:register`,开场在频道登记那一刻发。
- 客户端报语言(`ServerboundClientInformationPacket`)在进游戏之后,原版服务端不记它:平台服务 `IClientLanguage`,模组上由
  `ServerPlayerLanguageMixin` 在 `updateOptions` 记下。
- GameTest 里扮主人客户端的连接:Forge 用 `NetworkHooks.appendConnectionData` 挂上与服务端同一张频道表(`ForgeMockConnection`),
  Fabric 替他发 `minecraft:register`(`FabricMockConnection`);下行的包经各自通道的解码表读回。

### 身体与世界 API
- 没有属性的:方块/实体交互距离、潜行速度、重力 → `numen-api` 的 `BodyCompat`(数值与后来的属性默认值一致,算法照这一代原版);
  `EntityDimensions` 没有眼高 → `BodyStats` 带 `standingEye`。迈步高度:身体构造后 `BodyCompat.walkLikeAPlayer` 设回 0.6。
- 物品 NBT 而不是组件:成书的字(`WrittenBookItem.TAG_*`)、潜影盒里的东西(`BlockEntityTag.Items`)、旗帜花纹(`BannerBlockEntity.getPatternCount`)、
  附魔表(`EnchantmentHelper.deserializeEnchantments`)、自定义名(`hasCustomHoverName`)。放置时物品带给方块实体的只有 `BlockEntityTag`
  (`BlockItem.updateCustomBlockEntityTag`),自定义名由方块的 `setPlacedBy` 接。
- 配方:`Recipe<?>.getId()`,没有 `RecipeHolder`;食物 `Item.getFoodProperties()`,一口的饱和度 = 营养 × 系数 × 2。
- 信标:`BEACON_EFFECTS` 是 `MobEffect[][]`,效果是 `MobEffect` 不是 `Holder`。
- 盔甲架:`getScale()` 已含小号的 0.5,没有 `getAgeScale`。
- 跨维度:`changeDimension(ServerLevel)`(落点由目的维度定)与 `/tp` 走的 `teleportTo(ServerLevel, …)`,没有 `DimensionTransition`。
- 合成器(crafter)这一代没有:它的窗口与 `numen.gui.toggle` 不在。
- 聊天栏流式行:私有 `addMessage(内容, 签名, 刻, 标签, 只重排)` 不记日志地入栏(没有 `addMessageToDisplayQueue`)。

### mixin
- Forge 47 不带 MixinExtras(开发运行实测缺 `Operation` 类;Fabric 的加载器带)。1.21.1 上用到它的地方在这一代都只用 Mixin 自己的注入:
  `@WrapMethod` 改成 `@Inject` HEAD/RETURN 成对写法,测试夹具 `GameTestServerWorldMixin` 的 `@WrapOperation`/`@ModifyExpressionValue`
  改成带被注入方法参数的 `@Redirect`。编译期也不放 MixinExtras,用到它就编译不过;成品不内嵌它。
- 玩家连接唯一的下行出口是 `ServerGamePacketListenerImpl.send(Packet, PacketSendListener)`(`MixinServerGamePacketListener`)。

### 数据与目录
- 这一代数据包的目录是复数:标签 `tags/blocks`、`tags/items`;Fabric 的 GameTest 结构模板放在 `data/<命名空间>/gametest/structures/`。
  写成 1.21 的单数,编译、打包都不报,开服读不到(natural 标签读不到,挖地形方块一律成了"来历不明")。

### GameTest
- 套件经原版 `@GameTestGenerator`(`GameTestSuites`)报到,Forge 经 `RegisterGameTestsEvent`、Fabric 经 `fabric-gametest` 入口登记;
  `TestFunction` 是这一代的类与构造。结构模板 SNBT 的 `DataVersion` 是 3465。
- 这一代原版只钉场地起点周围几格区块、不围栏:`StructureChunksMixin` 在 `GameTestInfo.spawnStructure` 之后钉住整块场地、就地生成周围两圈,
  并照高版本围上屏障;这一代 `@GameTest` 没有 `skyAccess`,要去顶的用例标 `@SkyAccess`。
- 原版写死的不同:场地起点写死 `(0, -60, 0)`(改的是交给 `runTestBatches` 的参数);区块刻洗牌用的是 `Collections.shuffle(List)`(进程级、
  种子不定的随机数);开批是 `GameTestBatchRunner.runBatch`;测试服的固定规则只关了刷怪与天气,`GameTestServerRulesMixin` 补上随机刻 0
  与关火刻(1.21.1 原版自带)。
- Forge 47 的服务端入口在 GameTest 模式下拿 `server.properties` 建好的世界直接 `new GameTestServer(...)`,不经 `GameTestServer.create`:
  测试世界成了普通地形,夹具挂在 `create` 上的清空世界目录、数据包维度、种子都不生效。numen-api/forge 的开发期小模组里
  `ForgeGameTestWorldMixin`(只在 Forge 跑批的运行配置里挂)把那一处换回 `create`。
- 这一代原版给新玩家挑出生点(`ServerPlayer.fudgeSpawnLocation`)用当场新建、种子不定的随机数,扮主人的玩家每次开服落脚不同,
  身边走刻的区块跟着变、世界随机数就错开:测试夹具 `ServerPlayerSpawnMixin` 按玩家在这一批里的编号定种子。
- 这一代原版的 `GameTestInfo` 每次按坐标去世界里取场地的结构方块(它就在场地原点那一格),用例在原点铺地面后范围成了 null,
  收场时清场空指针、服务器崩;`GameTestStructureBlockMixin` 照 1.21.1 用 `spawnStructure` 记下的那个。
- Forge 的 `StructureUtils` 补丁按模板名的路径(不带命名空间)在模板目录里找 SNBT;原版与 Fabric 开发期找不到时拿整个名字(带冒号)
  拼路径,Windows 上直接抛 `InvalidPathException`,所以 Fabric 的模板一定要放进数据包。
- 因这一代的差异改过的用例场景:`an_advancement_reward_arrives_as_an_event` 用 bullseye(这一代没有 blowback,奖励 50 点经验);
  `a_dimension_change_is_acknowledged` 去末地(去下界会落在传送门里,站在门里冷却每刻重置);
  `a_body_alone_in_a_dimension_keeps_ticking_and_moving` 用 `teleportTo` 去下界的指定落点;成书、潜影盒、旗帜、信标、村民交易的场景数据换成 NBT 写法;
  `a_rotated_shape_is_built_as_vanilla_rotates_it` 的六向朝向格用发射器(这一代没有合成器,唯一带 `orientation` 的拼图方块不可建造)。

### 联动
- tlm、curios、kaleidoscope、ftbquests:只在 Forge 上接(同前)。jei:Forge 与 Fabric 都接(JEI 15)。litematica:Fabric 上对着
  Litematica 0.15.4(只发在 Modrinth)编译,Forge 上的目标模组是 Forgematica(模组 id `forgematica`,包与类同 Litematica),
  目标模组 id 由宿主交给联动。
- ftbquests(FTB Quests 2001):书没有 `getTeamData(玩家)`,队伍进度经 `TeamProgress` 问 FTB Teams;Forge 的专用服务器把 FTB 标了
  `OnlyIn` 的取名方法删掉,服务端那本书的名字由 `QuestNames` 照 FTB 的规则自己算(物品任务项、物品奖励、类型显示名)。
- tlm:这一代车万女仆没有按坐标播语音的口,不做模型语音(没有 `MaidVoice`);外观同步走自己的 `SimpleChannel`(`Outfit`/`OutfitClient`)。
  它的祭坛配方类型叫 `altar_crafting`,由 `RecipeType.simple` 造出、没进注册表:配方书的类别取注册表名,没登记的取类型自己报的名字(`RecipeBook.categoryOf`)。
- 端到端的协议客户端是 MCProtocolLib 1.20-2(1.20.1 的协议;它依赖的认证库与 NBT 库在 JitPack)。

### 1.20.4(Forge 49 / Fabric 0.15.3):在 1.20.1 一代写法之上,这一代自己的差异
以对齐好的 1.20.1 为底,只改这一代与 1.20.1 不同的地方;1.20.1 那些差异里 1.20.2/1.20.3 起已经消失的,照回 1.21.1 的写法。
- **构建(Forge 49 的开发运行)**:FML 只把"自带 `META-INF/mods.toml` 的类路径根"当模组,`MOD_CLASSES` 合并多个源码集的做法没有了;
  一个模组的类不在同一个根里,就由应用类加载器加载(碰到原版类就是两份 `ServerPlayer`),兄弟模块的 jar 又以自动模块的身份与模组的同名包冲突。
  所以开发运行里每个模组是一个 jar,官方名:`numen-api/forge` 的 `devJar`(内容同发行 jar)与 `gametestDevJar`,`numen/forge` 的 `devJar`/
  `gametestDevJar`(本体、平铺的寻路模块,带 GameTest 的再加夹具与用例),联动跑批的 `gametestDevJar`(再加联动本身与它的用例)。
  运行配置的类路径换成空源码集 `devRun`/`gameTestRun`(原版、Forge、库与这几个 jar),`numen-api/forge` 的运行时类路径里排除纯 JVM 模块的 jar;
  mcp 的重定位 jar 已平铺在 Numen API 的 jar 里,不再另作库。
- Gson 2.10.1、slf4j 2.0.7;插件:Curios 7.4.3、FTB Quests 2004.2.3(Library 2004.2.5、Teams 2004.1.2、Architectury 11.1.17)、JEI 17.3.1.5、
  Litematica 0.17.4(MaLiLib 0.18.1);车万女仆、森罗厨房、YSM 没有 1.20.4 的构建,Forgematica 只有 NeoForge 1.20.4 的版本:这几个联动不带。
- **网络**:`CustomPacketPayload`(`write` + `id()`)是原版的,载荷直接实现它,1.20.1 的 `NumenPayload` 删掉;还没有载荷类型与 `StreamCodec`,
  手写 `write`/`read` 照旧。Forge 49 的频道是 `ChannelBuilder.named(numen_api:main).optional().simpleChannel()`,一条 `Envelope` 多路复用;
  读下行包时 SimpleChannel 的消息序号是 varint,载荷是 `DiscardedPayload`。`canSend` 问 `player.connection.getConnection()`。
- **配置阶段**:`ServerPlayer` 构造带 `ClientInformation`、`placeNewPlayer` 带 `CommonListenerCookie.createInitial(profile)`(单参);连接的协议
  编解码挂在 channel 属性上,假连接(`FakeConnection`、`OwnerLine`、`TestBody` 的 `Wire`)自己种。客户端语言原版就记(`clientInformation().language()`),
  `IClientLanguage` 一套删掉。玩家连接的下行出口与断开在 `ServerCommonPacketListenerImpl`(`MixinServerCommonPacketListener`,`disconnect(Component)`)。
- **世界 API**:`SavedData.Factory(新建, 读, 数据修复类型)` 与 `save(CompoundTag)`;`AdvancementHolder`、`RecipeHolder`(配方输入仍是 `Container`,
  合成台重算没有配方提示);`CommandResultCallback`、`Commands.validateParseResults`/`ContextChain`、`NbtIo.readCompressed(Path, NbtAccounter)`、
  `ServerPacksSource.createVanillaPackSource()`;`Blocks.SHORT_GRASS`;`FlowerPotBlock.getPotted()`。
- **客户端**:`GuiGraphics.blitSprite` 与 GUI 精灵在(`GuiCompat` 与 `GuiIconsAccessor` 删掉,心与鸡腿用 `hud/heart/*`、`hud/food_*`);`PlayerSkin`;
  `mouseScrolled` 带横向滚动;`renderBackground(g, x, y, t)`;`MutableComponent.withColor`。
- **GameTest**:原版取场地起点又是随机一处(y = -59),起点照 1.21.1 用 `@ModifyVariable` 换;区块刻洗牌是 `Util.shuffle(list, 世界随机数)`,
  原版已经钉住整块场地的区块(`ChunkTickShuffleMixin`、`StructureChunksMixin` 照 1.21.1),只缺屏障:`StructureFenceMixin` 在
  `GameTestInfo.prepareTestStructure` 之后照高版本 `encaseStructure` 围(包围盒从结构方块之上算,四周向外一格、顶比包围盒高一层);
  `GameTestHelper.getBounds()` 是公开的(`TestBounds` 删掉),AABB 还没有 `getMinPosition`。场地的结构方块原版仍按坐标去世界里取
  (`GameTestStructureBlockMixin` 照旧);出生点散布仍是 `RandomSource.create()`(`ServerPlayerSpawnMixin` 照旧);固定规则仍只关刷怪与天气
  (`GameTestServerRulesMixin` 照旧);`@GameTest` 没有 `skyAccess`(`SkyAccess` 照旧)。模板一律经 `StructureTemplateManager` 取,
  测试模板目录这个来源只在 `IS_RUNNING_IN_IDE` 时登记:`StructureTemplateTestDirMixin` 在测试服里照样登记。结构模板 SNBT 的 `DataVersion` 是 3700。
- Forge 49 的 GameTest 用例过滤(`forge.enabledGameTestNamespaces`)按批次名(等于这个名字或以它加分隔符开头),联动跑批的批次名就是套件名。
- 端到端的协议客户端是 MCProtocolLib 1.20.4-1(有配置阶段:品牌包到了报语言,ping 回 pong;它的 pom 指着已经没有的 Adventure 4.15.0 快照,
  用 Adventure 的 BOM 取同版正式发行);JEI 17 没有 `getRecipeIngredients`,配方的格位由 `createRecipeLayoutDrawable` 排出来读;
  Litematica 0.17 用 `getAreaSize`。FTB Quests 2004 没有 `Quest.isSearchable` 与 `TeamData.getCannotStartReason`(用 `isVisible`,
  `QuestBook.cannotStartReason` 点名还没完成的前置任务),服务端那本书的 JSON 标题按 FTB 的顺序先试 `Component.Serializer`(`QuestNames`)。

### 1.20.2(Forge 48 / Fabric 0.15.0):在 1.20.4 写法之上退回去的地方
以对齐好的 1.20.4 为底:1.20.2 起就有的(`CustomPacketPayload`、配置阶段、`SavedData.Factory`、`AdvancementHolder`/`RecipeHolder`、`blitSprite`、`PlayerSkin`、
四参 `mouseScrolled`)照 1.20.4;1.20.3 才有的,照 1.20.1 的写法。
- **构建**:Forge 48 的开发运行仍认 `MOD_CLASSES` 合并的源码集,`numen-api/forge`、`numen/forge` 的构建脚本照 1.20.1(没有 1.20.4 的 `devJar`)。
  Gson 2.10.1、slf4j 2.0.7。插件:Curios 6.1.0(`CuriosApi.getSlots()` 不带参数,`getItemStackSlots(物品, 生物)`)、JEI 16.0.0.2、
  Litematica 0.16.1(MaLiLib 0.17.0);车万女仆、森罗厨房、YSM、FTB Quests 没有 1.20.2 的构建,Forgematica 也没有 Forge 1.20.2 的版本:这几个联动不带,
  也就没有联动跑批。
- **1.20.3 才有、这里照 1.20.1 的**:回执是 `ResultConsumer`(`Echo`),解析结果自己查(`McApi.validate`,没有 `Commands.validateParseResults` 与
  `ContextChain`);`NbtIo.readCompressed(File)`;`Blocks.GRASS`/`"minecraft:grass"`;`FlowerPotBlock.getContent()`;`withStyle` 上色(没有
  `MutableComponent.withColor(int)`);`ServerLoginPacketListenerImpl.isValidUsername`;`ShapedRecipe` 的构造带宽高与格位表(没有 `ShapedRecipePattern`)。
- **GameTest** 与 1.20.1 同一套原版:场地起点写死 `(0, -60, 0)`、`Collections.shuffle` 洗区块刻、`spawnStructure` 摆场地且只钉起点周围几格区块
  (`GameTestOrigin`、`ChunkTickShuffleMixin`、`StructureChunksMixin`、`GameTestStructureBlockMixin` 照 1.20.1)、`getBounds()` 是私有的
  (`TestBounds` 照 1.20.1)、模板由 `StructureUtils` 直接读测试模板目录(不要 `StructureTemplateTestDirMixin`)。结构模板 SNBT 的 `DataVersion` 是 3578。
- 端到端的协议客户端是 MCProtocolLib 1.20.2-1。
