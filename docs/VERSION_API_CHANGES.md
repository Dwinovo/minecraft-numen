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

`1.20.1 → 1.20.2 → 1.20.4 → 1.20.6 → 1.21 → 1.21.1 → 1.21.4 → 1.21.5 → 1.21.8 → 1.21.10 → 1.21.11 → 26.1.2`

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

## 1.21.1 → 1.21（向下，本分支的那一档）

游戏代码零改动——两版同属一个 API 纪元（blitSprite 纪元 1.20.6–1.21.1），MC 与 NeoForge 21.0↔21.1
对我们碰的面无破坏。要改的是旋钮 📦、NeoForge 21.0 自带的 MixinExtras 太旧（见下）、联动插件能不能带（见下）：

| 键 | 1.21.1 | 1.21 |
|---|---|---|
| `minecraft_version` | `1.21.1` | `1.21` |
| `minecraft_version_range` | `[1.21.1, 1.21.2)` | `[1.21, 1.21.1)` |
| `neo_form_version` | `1.21.1-20240808.144430` | `1.21-20240613.152323` |
| `fabric_version` | `0.116.7+1.21.1` | `0.102.0+1.21` |
| `neoforge_version` | `21.1.233` | `21.0.167` |

### 落地实录（2026-08-25）

- 改完旋钮首次 `build` 即绿（途中 maven.neoforged.net TLS 抖动一次，重试即愈）；
- 893 单测 0 跳过；四路 datagen 绿；jar 内验金：NeoForge 区间 `[1.21, 1.21.1)`、
  fabric 依赖照模板展开、306 个语言键（含 MCP 失联播报新键）在 jar 里；
- gametests 裁决行见 CI/提交说明；
- 蓝图调色板无需扫描：1.21↔1.21.1 方块集完全一致。

### 对齐 1.21.1@7a3d3e251 时新碰到的（2026-10）

**NeoForge 21.0 自带 MixinExtras 0.3.5，没有 `@WrapMethod`**（0.4.0 起才有；21.1 自带 0.4.1）❗🔁
本树有三处 mixin 用它（api 的 `PlayerAdvancementsRewardMixin`、core 的 `ItemEntityDropsMixin`、
`ServerPlayerGameModeDropsMixin`）。最险的是**运行期静默失效**：旧服务不认的注解只是被当成一个没人调的方法，
不报错，同伴的掉落归属、进度奖励全不出事件（GameTest 里表现为“Nothing dropped”）。做法是让游戏里跑的是 0.5.3，
源码一字不改：
- 编译：`api/neoforge`、`core/neoforge` 各加 `compileOnly mixinextras-neoforge:0.5.3`（Gradle 同坐标取高版本，顶掉 NeoForge 带来的 0.3.5）；
- 成品：`core/neoforge` 的 `jarJar` 内嵌 `mixinextras-neoforge`（范围 `[0.5.3,)`）；FML 的内嵌选择让高版本顶替 NeoForge 自带的那份，
  日志里是 `MixinExtrasServiceImpl(version=0.5.3)`；
- 开发期运行（GameTest/datagen）没有成品 jar，内嵌带不进去：`core/neoforge` 的 `devJarJar` 源码集把同一份按内嵌布局
  （`META-INF/jarjar/metadata.json` + jar）摆成目录，挂成 `numen` 模组的一部分。**别**走 `additionalRuntimeClasspath`
  或根工程的 `resolutionStrategy`：前者在模块层里和 NeoForge 自带那份撞名（`reads more than one module named mixinextras.neoforge`），
  后者管不到 MDG 自己解析的游戏类路径，运行时仍是 0.3.5。
Fabric 不受影响（fabric-loader 0.18.1 自带 0.5.0）。

**NeoForge 数据附件在 21.0 没有 `sync`**（`AttachmentType.Builder.sync(StreamCodec)`、`getExistingDataOrNull` 都是 21.1 才有）：
这一代的 TLM 外观 `Outfit` 要自己发包同步。本分支没有 TLM 联动，见下，没有落地。

**联动插件：本分支只带 ysm**。逐个查过：
- `tlm`：车万女仆所有 NeoForge 构建（1.4.1、1.5.3，Modrinth）的 `neoforge.mods.toml` 写死 `minecraft [1.21.1,1.21.2)`，在 MC 1.21 上 FML 就不让加载；
  Modrinth 把 1.4.1 标成“1.21”但同样写 `[1.21.1,1.21.2)`，且缺插件用到的 `TabIndex.BAUBLE/CURIOS`、`TaskManager.getNotHiddenTaskList`。
- `kaleidoscope`：CurseForge 项目 1309203 全部文件只有 1.21.1 与 1.20.1。
- `curios`：maven.theillusivec4.top 上 `curios-neoforge` 没有 1.21.0 的构建（8.1.0+1.20.6 之后直接是 9.2.0+1.21.1）。
- `ftbquests`：maven.ftb.dev 有 2100.1.x（1.21.0），但缺插件用到的 `Quest.isSearchable(TeamData)`、`TeamData.getCannotStartReason`、
  `Chapter.isHideTextUntilComplete`、`BaseQuestFile.getTeamData(Player)`——不是换个名字，是功能还没有。
- `ysm`：只用命令与 NBT 键，不引用 YSM 的类，原样带。
随之 `core/neoforge/neoforge.mods.toml` 去掉 `numen_tlm.mixins.json`，`gradle.properties` 去掉联动版本键。api 里
`ArchitecturyPlayerHooksMixin` 与它的配置照旧（只在 Architectury 在场时挂，不依赖联动）。

### 对齐 1.21.1@19c8710b0 时新碰到的(2026-11)

整树换成 1.21.1 之后(目录已是 `numen-api/`、`numen/`、`numen/pathing`,另有 `mcp`、`permission`、`script`、`store` 与 Paper 端),
游戏代码仍然只有下面几处要动;旋钮照上表。

**NeoForge 21.0 的 `@EventBusSubscriber` 默认挂游戏总线**❗🔁(编译不报错,运行才崩)——21.1 起按事件类型自己挑总线,1.21.1 的代码因此
不写 `bus`;21.0 不写就把 `GatherDataEvent`、`RegisterEvent` 这类模组总线事件挂到游戏总线,模组构造时
`IModBusEvent events are not allowed on the common NeoForge bus`。三处订阅写明 `bus = EventBusSubscriber.Bus.MOD`:
`numen-api/neoforge` 与 `numen/neoforge` 的 `data/DataGenerators`、`plugins/ysm/gametest` 的 `YsmStub`。

**MixinExtras 改由 Numen API 内嵌**🔁——上一轮由 core 内嵌、开发期另摆 `devJarJar` 目录;现在 `@WrapMethod` 的使用者里
Numen API 自己就有一处(`PlayerAdvancementsRewardMixin`),内嵌归它:`numen-api/neoforge` 的 `jarJar` 带 `mixinextras-neoforge`
(范围 `[0.5.3,)`),`numen-api/neoforge` 与 `numen/neoforge` 各 `compileOnly` 0.5.3。Numen 的成品内嵌 Numen API 的 jar,内嵌的内嵌
FML 一样会选;开发期运行时 Numen API 以它自己的 jar 上类路径(带着 `META-INF/jarjar`),所以 `devJarJar` 那一套不再需要,
日志里是 `MixinExtrasServiceImpl(version=0.5.3)`。

**联动插件:本分支带 ysm、kubejs、litematica,不带 tlm、kaleidoscope、curios、ftbquests、jei**(前四个的理由见上一节,照旧成立):
- `kubejs`:maven.latvian.dev 有 1.21 的 `kubejs-neoforge 2100.7.0-build.125`(rhino 取它 POM 里的 `2100.2.5-build.46`),编译通过;
- `litematica`:1.21 这一条线的 `0.19.61`(MaLiLib `0.21.10`)声明支持 1.21 与 1.21.1,原样;
- `jei`:1.21 上 JEI 最新是 `19.8.2.99`,没有联动读配方成分用的 `IIngredientSupplier` / `IRecipeManager.getRecipeIngredients`
  (1.21.1 的 19.x 后期才有),删掉这个模块,`numen/fabric` 的 JEI 仓库与 Fabric API 依赖、`fabric.mod.json` 的 `jei_mod_plugin` 入口一并去掉。

**不带 Paper 端**——1.21 有开发包(`1.21-R0.1-SNAPSHOT`,最后一版 build 132),但 Paper 1.21 的 API 里没有标签的生命周期事件
(`LifecycleEvents.TAGS`、`io.papermc.paper.tag.PreFlattenTagRegistrar`、`TagEntry`,Paper 1.21.1 起才有):插件没有受支持的办法往
标签里加条目,`numen:natural`、`numen:tame_foods`、`numen:throwaway` 在 Paper 上就是空的。不为它在 NMS 上补一层,`numen/paper`、
`numen-api/paper` 两个模块与只为它们存在的构建件一起删:`settings.gradle` 两项、`gradle.properties` 的 `paper_dev_bundle`、根
`build.gradle` 的 paperweight / run-paper 插件与 `releaseJars` 的 Paper 部分、buildSrc 的 `numen-paper`、`numen-e2e-paper`、
`numen-gametest-paper`、`PaperServer`、链接检查(`LinkCheck`、`LinkCheckTask` 与它的单测、asm 依赖)、`Modules` 里只为 Paper 分的
服务端/客户端两张表(并成一张)、apiJar 里的 `PluginTags`、CI 的链接检查与 Paper 的端到端/GameTest;端到端里只在 Paper 上跑、NeoForge 上恒跳过的
`HandshakeTest.the_server_opens_once_and_serves_only_a_matching_hello` 与它的 `e2e.platform` 系统属性一并删去。

**本轮实录**:datagenAll、两个加载器出包;单测 2992 个 0 失败 0 跳过;默认整套 GameTest NeoForge 441、Fabric 440 全过,慢批 38 全过;
同一原点两次开服逐条比对,Fabric 全同,NeoForge 只有 `a_restored_follow_finds_the_same_entity_by_its_id` 差一刻(1.21.1 同样存在的
等规划结果不按刻等齐,在 1.21.1 上修);`e2eTest` 7 个场景全过;ysm 5、kubejs 3 条全过。成品 jar 起真专用服(NeoForge 21.0.167):
日志是 `Found gamelibrary file "mixinextras-neoforge-0.5.3.jar" [parent: …numen_api…]` 与 `MixinExtrasServiceImpl(version=0.5.3)`,
`-Dmixin.debug.export` 导出的 `ItemEntity`、`ServerPlayerGameMode` 里有 `wrapMethod$…numen$followMerge`、`wrapMethod$…numen$breaking`。

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
_待移植时填写_
