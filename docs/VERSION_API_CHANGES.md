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

新架构（numen-api 拆分 + 调度器 + raw `NumenTool` + skill 体系）当前基线在 **`1.21.1`**，正逐档向上移植。

---

## 每档都要改的构建旋钮 📦

`gradle.properties`（core 与 api 各一份）：

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
新架构里文件路径/包名已变（`tulpa`→`numen`、工具类移入 `core/tools`），但**API 替换内容一致**。

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

### 1.20.4 落地实录(并仓迁移,1.20.1 树向上走两档时新踩的)❗

只记本次新碰到的;上面各节已有的(config-phase、CustomPacketPayload、GuiCompat、
RecipeHolder、ChannelBuilder)反着用即可,不重复。

**① Forge 49 的 dev 运行会为模块层做 JPMS 校验**——并仓布局特有的死局:runs 的
mods 块把 ai/ui 源码集聚进 numen_api 模组模块,而 api 的 `api project(':ai')`
又把 numen-ai.jar 拖上运行 classpath 当自动模块,同包双模块直接拒启
(`ResolutionException: Modules numen_api and numen.ai.SNAPSHOT export package …`)。
Forge 47(1.20.1)没有这道校验,同一份配置好好的。修法在 api/forge 与 core/forge:
```groovy
configurations.named('runtimeClasspath') {
    exclude module: 'ai'   // 兄弟工程 group 与本模块不同——带 group 的规则匹配不上,按 module 名来
    exclude module: 'ui'
}
```
类经 MOD_CLASSES(源码集输出)进运行一个不少;编译与打包不受影响。
排查提示:FG6 的 MinecraftRunTask 在**任务动作里**才组装 classpath,配置期改
`JavaExec.classpath` 是无效的;真实 java 命令行用 `--info` 抓。

**② gametest 注解与模板管线整套换**(Forge 49 ≠ 47):
- `PrefixGameTestTemplate` 没了,拆成 `GameTestDontPrefix`/`GameTestPrefix`;且本代把
  批次名与模板名交给同一套前缀逻辑(`GameTestHolder` 的值同时前缀到两者)。解法不用
  新注解:**模板名写 `numen:floor16`(带冒号直接按完整路径用、跳过前缀)**,批次照常
  前缀以满足 `forge.enabledGameTestNamespaces` 筛选。
- 模板一律问 `StructureTemplateManager` 要;"测试模板目录"这个来源只在
  `SharedConstants.IS_RUNNING_IN_IDE` 为真时登记,无头跑批里是假的(1.20.1 的
  `StructureUtils.spawnStructure` 自己读盘,没这一出;NeoForge 1.20.6 也没有)。
  解法:CompanionGameTests 静态块把仓库 SNBT `NbtUtils.snbtToStructure` 转成结构
  NBT 喂进存档 `generated/numen/structures/`(开闸口令 `numen.gametest.generated`
  只在 GameTestServer 运行配置上设)——仓库仍只存文本,不提交二进制。

**③ 1.20.2/1.20.3 的三处小刀**(向下手册没记,因为老树在那些点位不用这些 API):
```java
Recipe.getId()                     // 1.20.2 移除,id 归 RecipeHolder.id()(单测匿名配方覆写它会编译失败)
NbtIo.readCompressed(File)         // 1.20.3 移除:走 Path + NbtAccounter.unlimitedHeap()(原版读结构模板同款额度);writeCompressed 同步只收 Path
Blocks.GRASS → Blocks.SHORT_GRASS  // 1.20.3 改名(TALL_GRASS/GRASS_BLOCK 不变)
FlowerPotBlock.getContent() → getPotted()   // 仅 1.20.3/1.20.4 这一窗口叫 getPotted,1.20.2- 与 1.20.5+ 都叫 getContent
```

**④ ChunkMap 的同伴静默 mixin 换注入点**(1.20.2 合并了逐 chunk 跟踪):
`updateChunkTracking` → `applyChunkTrackingView`,取消前先
`player.setChunkTrackingView(ChunkTrackingView.EMPTY)`,恢复跟踪时才不会整圈重放。

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

## 对齐 1.21.1(7a3d3e251)之后的 Java 17 一代(1.20.1 先做,1.20.4 / 1.20.2 同一套写法)

整树换成 1.21.1 的新架构(Lua 程序整段在服务端跑、客户端函数反向请求、载荷层分片、回执三管道、SDK `@Fn` 登记、`agent/`、`lua/`、
`pathing/`、`bench/`、五个联动),再把这一代的平台差异逐处回放。1.20.1 上要改的东西,按类别:

### 语言 / 线程(Java 17)
```java
switch (x) { case A a -> … }          →  if (x instanceof A a) { … } else if …   // 模式 switch、record 解构
Math.clamp(v, lo, hi)                  →  Mth.clamp(v, lo, hi)
list.getFirst() / getLast()            →  list.get(0) / get(list.size() - 1)
Thread.ofVirtual().name(n).start(r)    →  守护平台线程:new Thread(r, n) + setDaemon(true)
```
没有虚拟线程时语义不变:服务端主线程永远不等 Lua;一段程序的执行体占一条守护线程(`numen-program-N`,`ServerPrograms`),脚本虚拟机占
一条(`lua-<块名>`,`LuaSandbox.start`);阻塞的宿主函数就在脚本自己的线程上等。单测 `LuaSandboxTest`、`SerialExecutorTest` 查的是同一条语义
(线程是守护的、不是调用方的线程)。写进了 `docs/shell.md` 的"运行"一节。

### 网络(没有 `CustomPacketPayload` 与配置阶段)
- 载荷实现 `NumenPayload`(`id()` + `write(FriendlyByteBuf)`,静态 `read`);程序上行/结果/停止、反向请求/结果、分片包都是这一套。
- 分片一份通用实现(`Fragments`/`Wire`),上限仍是 32767;Forge 47 只有一条 `SimpleChannel`(`numen_api:main`),所有载荷装进
  `Envelope(ResourceLocation id, byte[] data)` 多路复用,多出的包头开销记在 `Wire.FRAMING = 64`。Fabric 直接用 id + `FriendlyByteBuf`。
- GameTest 里扮主人客户端的连接不经真网线,下行的包经 `ForgeNetworkChannel.payloadOf` 读回,与客户端收包走同一张解码表。

### 身体与世界 API
- 没有属性的:方块/实体交互距离、潜行速度、重力、安全落差 → `pathing` 的 `BodyCompat`(数值与后来的属性默认值一致,算法照这一代原版);
  `EntityDimensions` 没有眼高 → `BodyStats` 单独带 `standingEye`/`crouchingEye`。
- **迈步高度**:这一代 `ServerPlayer` 构造时 `maxUpStep = 1.0`,一格高的台阶与门板不跳也不开门就迈上去了;真玩家客户端是 0.6。身体(`NumenPlayer`、
  GameTest 的 `TestBody`)构造后经 `BodyCompat.walkLikeAPlayer` 设回 0.6,规划与执行读同一个值。
- `SavedData` 老式工厂(`computeIfAbsent(load, new, name)`)、`ResourceLocation` 公开构造、物品 NBT 而不是组件(`Count` 是 byte、附魔 `Enchantments`)、
  `Recipe<?>.getId()`(没有 `RecipeHolder`)、`ServerGamePacketListenerImpl.send(Packet, PacketSendListener)`、`broadcastBreakEvent(EquipmentSlot)`
  代替 `onEquippedItemBroken`、Brigadier 1.0.18 的 `ResultConsumer` 与 `CommandSourceStack.withCallback`。
- DFU 6.0.8 的 `optionalFieldOf` 遇到一个坏元素就丢掉整张表:`PermissionStore` 用逐行容错的 `TABLE` 编解码,一行写坏只丢那一行。

### mixin
- MixinExtras 0.3.5(Forge 47 自带)没有 `@WrapMethod`(要 ≥ 0.4,**更旧的版本不报错、静默不生效**)。1.21.1 上用到它的四处在这一代都改成不依赖 MixinExtras 的
  `@Inject` HEAD/RETURN 成对写法(HEAD 记下、RETURN 报出):`PlayerAdvancementsRewardMixin`、`ItemEntityDropsMixin`、`ServerPlayerGameModeDropsMixin`、
  tlm 的 `TaskFeedOwnerMixin`。源码里一处 `@WrapMethod` 都不留,所以成品不必内嵌 MixinExtras。
- 保留 `MixinPlayerInfo`(authlib 4 的验签,`@ModifyArg`)。Forge 的 mixin 配置靠 jar 清单的 `MixinConfigs`(api 的在 api jar 里,core 的与 `numen_tlm.mixins.json`
  在 core 的 `jar`/`jarJar` 清单里);车万女仆那份是 `@Pseudo`,目标模组不在场静默跳过。

### GameTest(Forge 47 的无头服务器是原版 `GameTestServer`,与 NeoForge 21.1 不同)
- 用例由 `@GameTestHolder` 扫描登记(`ForgeGameTestHooks`),命名空间由 `-Dforge.enabledGameTestNamespaces` 定;结构模板是 SNBT(`DataVersion` 3465),
  运行前把本模组与寻路模块两处模板同步进 `core/forge/build/gameteststructures`;FG 按名字找 mods 里每个源码集的 `classes` 任务,所以 core/forge 里有一个同名替身
  `gametestClasses` 指向寻路模块的那一个。
- 这一代原版 GameTest 缺两样高版本有的东西,寻路用例的"场地是孤岛"靠它们:场地外围的**屏障围栏**与**整块场地的区块都钉住**
  (原版只钉起点周围固定几格,一百多格长的场地后半截没人加载)。补在 `pathing/src/gametest` 的 `StructureFenceMixin`(只在 GameTest 运行里经
  `--mixin.config numen_gametest.mixins.json` 挂,不进发行物)。寻路批次另关随机刻与火刻(耕地不会自己变干、火不会自己变老)。
- 不适用于这一代、没有搬的用例:`TickRateGameTests` 的 3 条(没有 `TickRateManager`);`does_not_place_on_the_world_border`(这一代原版世界边界对"方块在不在界内"
  的口径是只要有一部分在界内就算——身体站得进、方块却不在界内的那种场地造不出来);游戏里那一半的 bench(`:bench:game`,只在 NeoForge 分支)。
- 因这一代的差异改过的断言:`an_advancement_reward_arrives_as_an_event` 换成 1.20.1 里存在的进度 `minecraft:adventure/bullseye`(奖励经验 50);
  `an_outmatched_body_with_no_way_out_fights_back` 开头多等一步"钉住的区块开始刻实体"(区块异步升级,没等就加进去的僵尸既不动也查不到)。

### 联动
- tlm:她穿的模型(`Outfit`)是 Forge 能力(随玩家数据存盘),同步靠自己的 `SimpleChannel`(换装时发给看着她的人,新进入视野由 `StartTracking` 补发,
  频道对端缺席也接受);养女仆的动作(`Maids`)把车万女仆的 `*Message` 包经她自己的连接交给 `ServerGamePacketListenerImpl.handleCustomPayload`(Forge 这个入口不看
  连接有没有协商过频道);P 点与女仆数读车万女仆的 `PowerCapability`/`MaidNumCapability`。这一代车万女仆没有按坐标播语音的口,联动不做受伤/死亡的模型语音。
  开发运行里车万女仆要走 `fg.deobf`(它的发行 jar 是 SRG 名),仓库要用普通 `maven{}` 而不是 `exclusiveContent`。
- curios(Forge,Curios 5.14.1)、kaleidoscope(Forge,森罗厨房 1.5.0,cursemaven 文件号 8910014)、ftbquests(Forge,FTB Quests 2001.4.22 + Architectury 9.2.14):
  都只在 Forge 上接,与 1.21.1 的 NeoForge-only 一致。Fabric 上 Curios 不存在(对应的是 Trinkets/Accessories)、森罗厨房 1.1.0 起停发 Fabric、
  FTB Quests 联动 1.21.1 本身只接 NeoForge。Architectury 9.2.14 在 Forge 上的 `PlayerHooks.isFake` 是 `instanceof FakePlayer`,同伴不是,所以 Forge 不需要
  `ArchitecturyPlayerHooksMixin`;Fabric 上它按 `ServerPlayer` 子类判假,Fabric 的那份配置(`numen_api.fabric.architectury.mixins.json`)照旧在。

### 1.20.4(Forge 49 / Fabric 0.15.3):在 1.20.1 一代写法之上,这一代自己的差异

- **网络**:`CustomPacketPayload`(`write(FriendlyByteBuf)` + `id()`)是原版的,载荷直接实现它;Forge 49 仍只有一条 `SimpleChannel`
  (`ChannelBuilder.named(numen_api:main)`),所有载荷装进 `Envelope` 多路复用,`Wire.FRAMING = 64` 照旧。GameTest 里读下行包:`ForgeNetworkChannel.payloadOf`
  从 `ClientboundCustomPayloadPacket` 里的 `DiscardedPayload` 取字节(先读掉 SimpleChannel 的 varint 消息序号)。
- **配置阶段**:`placeNewPlayer` 多一个 `CommonListenerCookie`(`createInitial(profile)` 单参);`ServerPlayer` 构造多一个 `ClientInformation`。连接的协议编解码挂在
  channel 属性(`ATTRIBUTE_*_PROTOCOL`)上,`ServerGamePacketListenerImpl` 一建就读——假连接(`FakeConnection`、GameTest 的 `OwnerLine`、`TestBody` 的 `Wire`)
  必须自己种,不种直接空指针。`ServerCommonPacketListenerImpl.send(Packet)` 单参(`MixinServerCommonPacketListener`)。
- **世界 API**:`SavedData.Factory`(`computeIfAbsent(FACTORY, name)`,`load(CompoundTag)` 单参)、`AdvancementHolder`、`RecipeHolder`(`byKey` 给 `Optional<RecipeHolder<?>>`;
  合成台重算的 `slotChangedCraftingGrid` 没有"配方提示"参数)、`NbtIo.readCompressed(Path, NbtAccounter)`、`CommandResultCallback`、`TickRateManager`(`TickRateGameTests` 与寻路日志的刻速在)、
  `GuiGraphics.blitSprite` 与 GUI 精灵(心与鸡腿用 `hud/heart/*`、`hud/food_*`)、`mouseScrolled` 带横向滚动、`Blocks.SHORT_GRASS`(1.20.3 起改名)、
  `ServerPacksSource.createVanillaPackSource()`。没有:`CriteriaTriggers.ANY_BLOCK_USE`、`EntityDimensions` 眼高、方块/实体交互距离与摔落等属性(仍走 `BodyCompat`)。
- **GameTest(Forge 49 的 `GameTestServer`)**:
  - 用例类标 `@GameTestHolder(value = …, namespace = …)` 加 `@GameTestDontPrefix`:Forge 49 把持有者的值前缀到用例名、批次名与模板名上,而 `@BeforeBatch` 的批次名是原样的,
    不去掉前缀就对不上、开场设定(难度、时刻、晴天)悄悄不生效;命名空间让没有冒号的模板名落到 `numen:<名>`。`forge.enabledGameTestNamespaces` 按批次名前缀筛用例,
    我们的批次名不带前缀,所以运行配置不设它(登记进来的本来就只有我们自己的类)。
  - 无头跑批不登记"测试模板目录",`GameTestKit` 的静态块把 SNBT 转成结构 NBT 写进存档的 `generated/numen/structures/`(`numen.gametest.generated`)。
  - **结构方块就在 rel(0,0,0)**(1.20.1 不在):用例把 rel(0,0,0) 当地板盖掉,原版收场时找不到它(`GameTestInfo.succeed` 空指针);`longFloor` 跳过那一格。
  - 原版已经钉住整块场地的区块,只缺屏障围栏:`StructureFenceMixin` 注入 `StructureUtils.prepareTestStructure` 的返回处,围栏顶比包围盒顶高一层(寻路的"顶到屋顶翻不过去"用例靠它)。
  - 不适用于这一代、没有搬的用例:`does_not_place_on_the_world_border`(这一代的世界边界对"方块在不在界内"的口径是只要有一部分在界内就算,造不出那种场地)。
- **Forge 49 的开发运行**(`gradle/forge-run-classpath.gradle`、`api/forge/build.gradle` 的 `devJar`、`pathing/build.gradle`):dev 运行只把"自带 `META-INF/mods.toml` 的类路径条目"当模组,
  mods 块里合并多个源码集不再生效;别的目录由应用类加载器加载,碰到原版类就是两份 `ServerPlayer`(`LinkageError`),jar 则作为自动模块与 api 共有的包(`agent.llm`、`agent.memory`、`client.ui`)冲突
  而拒启。所以:兄弟模块的 jar 不上运行 classpath;core 的运行把 api 打成 `devJar`(内容同发行 jar,official 名)当模组;寻路的 GameTest 是一个自己的模组
  (`lowcodefml`,类、资源与 `mods.toml` 同处 `pathing/build/gametest-mod`)。api 自己的 Client/Server/Data 运行只做数据生成与冒烟,兄弟模块的类仍走源码集目录,
  不能在里面跑真实游戏逻辑。
- **mixin**:这一代 Fabric Loader 0.15.3 自带的 MixinExtras 没有 `@WrapMethod`(要 ≥ 0.4,更旧的静默不生效);同 1.20.1,四处都是 `@Inject` 成对写法,源码里没有 `@WrapMethod`,
  成品不内嵌 MixinExtras。`MixinPlayerInfo` 在仓库任何分支的历史里都不存在,没有可保留的。`numen_api.fabric.architectury.mixins.json` 的 Fabric 一份照旧。
- **联动**:ysm 三个加载器都在;curios(Forge,Curios 7.4.3+1.20.4)与 ftbquests(Forge,FTB Quests 2004.2.3、Library 2004.2.5、Teams 2004.1.2、Architectury 11.1.17)只在 Forge。
  这一代的 FTB Quests 没有 `Quest.isSearchable` 与 `TeamData.getCannotStartReason`:看不看得见改用 `Quest.isVisible(team)`,"为什么还不能开始"由插件自己点名还没完成的前置任务
  (`QuestBook.cannotStartReason`)。车万女仆、森罗厨房没有这一版(只有 1.20.1 与 1.21.1),不接;Fabric 上 Curios/FTB Quests 同 1.21.1 不接。

### 1.20.2(Forge 48.0.49 / Fabric 0.15.0):相对上一节 1.20.4 的差异

源码与 1.20.4 几乎一样,只有这几处不同:
- **NBT IO**:`NbtIo` 只有 `File` 形态(`readCompressed(File)`、`writeCompressed(CompoundTag, File)`);`Path` 加 `NbtAccounter` 是 1.20.3 起的。`readCompressed(File)` 内部不设限额,等同 `unlimitedHeap`。
- **花盆**:`FlowerPotBlock.getContent()`(`getPotted()` 只在 1.20.3 / 1.20.4 这一窗口)。**草**:`Blocks.GRASS`(`SHORT_GRASS` 是 1.20.3 起)。
- **没有 `TickRateManager`**(1.20.3 起才有):寻路日志不写刻速,`TickRateGameTests` 的 3 条没有搬。
- **Brigadier 1.0.18**:没有 `ContextChain` 与 `CommandResultCallback`,命令回执用 `ResultConsumer` 与 `CommandSourceStack.withCallback`(`Echo`、`McApi`、`OnHer`);
  `GameTestHelper.getBounds()` 是私有的,`Trial` 遍历场地里的格子量大小。
- **GameTest**:结构模板 `DataVersion` 3578;注解、模板与批次的写法同 1.20.4(`@GameTestHolder(namespace)` + `@GameTestDontPrefix`、`GameTestKit` 把 SNBT 喂进存档 `generated`)。
  `StructureUtils` 的形状同 1.20.1(`spawnStructure`,只钉起点周围几格区块),`StructureFenceMixin` 因此与 1.20.1 一样既围屏障又钉整块场地的区块。
- **Forge 48 的开发运行**:`MOD_CLASSES`(mods 块合并多个源码集)仍然生效,所以与 1.20.1 一样,pathing 与兄弟模块经 mods 块并进 numen 的两个模组;
  没有 1.20.4 那套 `devJar` / 寻路 GameTest 模组(那是 Forge 49 才需要的)。`:api:forge:Data` 不排除兄弟 jar 也能起,origin 里那段 ai/ui 的排除原样留着。
- **联动**:ysm、curios(Forge,Curios 6.1.0+1.20.2:`CuriosApi.getSlots()` 无参、`getItemStackSlots(stack, entity)`)。FTB Quests 没有 1.20.2 的版本(只有 1.20.1 的 2001 与 1.20.4 的 2004),
  不接;车万女仆、森罗厨房同 1.20.4,没有这一版。
