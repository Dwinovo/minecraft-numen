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

## 1.21.1 → 1.20.6 ✓（已验证,双 loader 编译 + 出包 + datagen + 230 单测 + 47 条游戏内用例全绿）

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
sm.assemble(new SmithingRecipeInput(EMPTY,EMPTY,EMPTY), ra) → sm.assemble(new SimpleContainer(3), ra)
//   ⚠ 锻造别换 getResultItem:本代 SmithingTrimRecipe.getResultItem 是带纹饰的铁胸甲预览
//   (非空),会把纹饰配方错列成产物。空容器 assemble 与 1.21.1 空输入语义逐位一致
//   (变换/纹饰对空底座都折成 EMPTY)。删掉那三个 crafting.*Input import。
// VertexConsumer 旧链式（PathVizRenderer）：
vc.addVertex(pose,…).setColor(c).setNormal(pose,…)
  → vc.vertex(pose.pose(),…).color(c).normal(pose,…).endVertex()
// FakeConnection：删 disconnect(DisconnectionDetails) 重写（1.21+ 才有）+ 其 import；保留 disconnect(Component)。
```

> ⚠ **NeoForge publish 需在线**:1.20.6 的 NeoForm runtime 依赖 `log4j:2.11.+`(动态版本),
> `--offline` 解析不了 → publish 用**在线**(非 MC 下载,只拉 maven 制品)。

### 0.1.1 功能面移植追加(1.21.1 的 118 文件搬到 1.20.6 时新碰到的)

1.20.6 是**夹心版本**:加载器与语言层面(NeoForge、Java 21)、组件系统、网络层
(`StreamCodec` / `CustomPacketPayload.Type` / `RegistryFriendlyByteBuf`)都已经是
1.21 那一族的形态,但 MC 本体在 **1.21.2 那一刀之前**。所以 0.1.1 的整套代码搬过来
只有下面四条要改,主仓 118 文件里改到源码的只有 3 处。

**`MinecraftServer#getServerDirectory` 返回 `File`** ❗(`BlueprintStore`)——1.21 起才是
`Path`。取图纸目录要先转:
```java
server.getServerDirectory().resolve("schematics")
    → server.getServerDirectory().toPath().resolve("schematics")
```

**`ResourceLocation` 的两个工厂本代都没有** ❗(`BuildTool`、`CompanionGameTests`)——
除了已记在上面的 `fromNamespaceAndPath`,0.1.1 还新用了两个:
```java
ResourceLocation.parse("minecraft:igloo/top")   → new ResourceLocation("minecraft:igloo/top")
ResourceLocation.withDefaultNamespace("hud/heart/full") → new ResourceLocation("hud/heart/full")
```

**标签目录是复数形态** 🔁(`InitTag` 的注释、datagen 产物)——本代 `TagManager` 里写死的是
`tags/blocks` / `tags/items`(1.21 才改成单数)。datagen 自己会写对路径,但**注释里的示例
路径**要按本代写,否则照着注释去数据包里放 json 会放到一个永远不会被读的目录。

**头顶气泡的手性跟本代名牌走** 🔁(api 侧 `SpeechBubbleRenderer`)——1.20.6 的
`EntityRenderer#renderNameTag` 是 `scale(-0.025F, -0.025F, 0.025F)`(1.21.1 是
`scale(0.025F, …)`)。气泡要和名牌同一套手性:
```java
poseStack.scale(SCALE, -SCALE, SCALE)  → poseStack.scale(-SCALE, -SCALE, SCALE)
// 层次也跟着翻:边框/填充/文字的 z 由 +0.02/+0.04/+0.06 改成 -0.02/-0.04/-0.06
```
只翻 Y 的话行列式为负、整个空间被镜像,所有面的绕序随之翻转而被背面剔除——自己画的方块
可以双面画糊过去,原版画的字形不能,结果就是"有框没字"。

### 1.21.2 那一刀的哪些条目在本代**不**适用

`1.21.1 → 1.21.4` 小节里记的绝大多数条目在这里**不存在**,不要照搬:
注册表 `get` → `getValue`/`Optional`、配方 `PlacementInfo`/`CraftingInput`、
`Level#getMinBuildHeight` → `getMinY`、渲染状态化(`EntityRenderState`)、
`ClientInput` 拆容器、gametest 数据驱动化、`CompoundTag` getter 的 Optional 化、
`ItemStack.parse`/`save` 删除、`Component.Serializer` 删除——本代统统还是老形态。

反过来,1.20.x 那三档为"**没有**组件系统"做的降级改写也一概**不**适用:
NBT 键白名单、`isSameItemSameTags`、`ItemStack.of`、去掉 `HolderLookup.Provider` 参数线
——本代 `DataComponents` / `ItemStack.parse(registries, tag)` /
`ItemStack.isSameItemSameComponents` / `BlockEntity#loadWithComponents` 全都在,
0.1.1 的原始写法原样编译通过。

### gametest:本代不需要任何绕行

- 框架是**注解式**(`@GameTestHolder` / `@PrefixGameTestTemplate` / `@GameTest`),
  不是 1.21.5 起那套数据驱动测试实例。
- `StructureTemplateManager` 的 `.snbt` 源确实仍然只在 `SharedConstants.IS_RUNNING_IN_IDE`
  为真时登记(与 1.21.1 完全相同的一行判断),但 NeoForge 的 `runGameTestServer` 开发跑批里
  这个开关是真的,所以 `StructureUtils.testStructuresDir` 这条通路直接可用——**不需要**
  1.20.4 那套"另存 .nbt 喂进存档 generated/" 的绕行。
- `GameTestHelper#absolutePos` 本代仍以 `getStructureBlockPos()` 为基准(1.21.4 起才改成
  `getTestOrigin()`,高一格),坐标不用挪。
- `GameTestServer` 自带固定的平坦 "Test Level",**不需要**在 build.gradle 里写
  `server.properties`。
- 模板 SNBT 的 `DataVersion` 要重盖成本代的 **3839**(`SharedConstants.WORLD_VERSION`):
  向下移植没有数据修复器可依靠。palette 里的方块名逐条核实——本代已是 `short_grass`
  (1.20.3 起改名),这类错不报编译错、断言数目也不变、测试照样全绿,只是该长草的地方
  变成空气。

### 单测夹具

`SynchedEntityData` 在 **1.20.5 起**就已经是 `Builder` 组装 + `build()` 校验"每个 id 都已
定义",与 1.21.x 同形,所以 1.21.4 那份夹具补丁(补 `Abilities` 与满血 `SynchedEntityData`)
在本代**原样成立**。1.20.x 那三档的公开构造器写法不适用于本代。

### 1.20.6 落地实录(并仓迁移,全量对齐 1.21.1 时新踩的)❗

只记本次新碰到的;上面各节已有的不重复。

**附魔整族回等级查询**——1.21 数据驱动附魔的读法(`EnchantmentEffectComponents` /
`enchantment.effects.*` / `Holder<Enchantment>` 键集遍历)本代都不存在,`Enchantments.*`
是 Enchantment 本体(不是 ResourceKey):
```java
// 效率挖速(ToolSet):MINING_EFFICIENCY 属性效果 → 经典公式(同曲线):
speed += eff * eff + 1;  // eff = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.EFFICIENCY, item)
// 深海探索者(CalculationContext):WATER_MOVEMENT_EFFICIENCY 属性 →
EnchantmentHelper.getDepthStrider(player);  // 乘数 = min(1, level/3),同曲线
// 霜行者/精准采集(CalculationContext、MovementPlacement、ToolSet):Holder 键集遍历 →
EnchantmentHelper.getItemEnchantmentLevel(Enchantments.FROST_WALKER / SILK_TOUCH, stack)
// 迅捷潜行(ExecHarness):Attributes.SNEAKING_SPEED 本代不存在 →
Mth.clamp(0.3f + EnchantmentHelper.getSneakingSpeedBonus(player), 0.0f, 1.0f)
// 附魔伤害排序(WeaponDamage):
EnchantmentHelper.modifyDamage(level, stack, target, source, base)
  → base + EnchantmentHelper.getDamageBonus(stack, target.getType())   // 收 EntityType,1.20.5 已不是 MobType
// gametest 夹具:不需要 registry.getHolderOrThrow(key)——
ItemStack.enchant(Enchantment, int) / ItemEnchantments.getLevel(Enchantment) 直接收本体
```

**战斗类签名**:
```java
CombatRules.getDamageAfterAbsorb(self, dmg, src, armor, tough) → getDamageAfterAbsorb(dmg, src, armor, tough)  // 去实体参
Item.getAttackDamageBonus(Entity target, float dmg, DamageSource src) → getAttackDamageBonus(Player attacker, float dmg)
CrossbowItem.getChargeDuration(stack, entity) → getChargeDuration(stack)
```

**配方接口本代已收 HolderLookup.Provider**(1.20.5 那刀):`Recipe#assemble/getResultItem`
的注册表参数与 1.21.1 同形,走 `getResultItem` 的枚举代码原样编译。泛型仍是
`Recipe<C extends Container>`:单测里的匿名配方 `Recipe<CraftingInput>` → `Recipe<Container>`,
`matches/assemble` 的输入参数跟着换。锻造空输入的正确改法见上面反向 delta 的 ⚠。

**1.21 实验性内容的类本代存在**:`MaceItem` 等 1.21 内容 1.20.5/6 已随包(实验性 flag 关闭),
`instanceof MaceItem` 照常编译、运行时恒 false——不必删。

**工具材质标签只有 stone 一个**:`wooden/iron/gold/diamond/netherite_tool_materials`
是 1.21 加的,本代 `stack.is(tag)` 对不存在的标签恒 false——ToolSet 材质廉价度排序
退化为平速先到先得(注释已说明),不必改代码。

**gametest 前必须先跑 datagen(core 的也要)**:同伴的蓝图数据携带
(`numen:safe_block_entity_data`)与垫路选料(`numen:scaffolds`)都是 datagen 生成的
数据包标签,生成物已 gitignore——干净 worktree 直接 `runGameTestServer` 会挂 5 条
(蓝图告示牌/旗帜、爬塔跟随、创造徒手垫路),报错口吻全是"标签空/无路可走",
看着像寻路回归,其实是 `:core:neoforge:runData` 没跑。

### 对齐 1.21.1@7a3d3e251 时新碰到的(2026-10)

0.1.3 之后 1.21.1 新增了 `pathing/`(只依赖原版的寻路模块)、联动插件、`bench/`、客户端函数反向请求等。上面各节里
已记的条目照旧适用(`ResourceLocation` 工厂、`getServerDirectory().toPath()`、气泡手性、`DisconnectionDetails`、
战斗类签名、附魔回等级查询、锻造空容器、标签目录复数、`DataVersion` 3839),下面只记**新碰到的**:

**寻路模块的身体快照读 1.21 才有的属性**❗(`pathing/body/Snapshots`、`Controls`、`plan/DigTime`)——本代没有
`MINING_EFFICIENCY`、`SUBMERGED_MINING_SPEED`、`WATER_MOVEMENT_EFFICIENCY`、`OXYGEN_BONUS`、`SNEAKING_SPEED`,这几样是附魔直接
起的作用。快照的字段与曲线不动,只换取值的来源:
```java
水下移动效率  Math.min(1.0, EnchantmentHelper.getDepthStrider(body) / 3.0)      // 与 1.21 属性同一条曲线
水下挖掘速度  EnchantmentHelper.hasAquaAffinity(body) ? 1.0 : 0.2                // 原版 0.2,水下速掘提到 1
氧气加成      EnchantmentHelper.getRespiration(body)                              // 每级 1,扣氧 1/(b+1) 的概率不变
迅捷潜行      Mth.clamp(0.3F + EnchantmentHelper.getSneakingSpeedBonus(body), 0, 1)
冰霜行者      EnchantmentHelper.getItemEnchantmentLevel(Enchantments.FROST_WALKER, 靴子) > 0
挖掘效率      身体别处没有这一份,记 0;手上那件按附魔等级 L 加 L² + 1(DigTime.efficiency),不再摘"手上那件的修饰符"
```

**`LivingEntity.onEquippedItemBroken` 本代没有**❗(`NumenPlayer`)——装备碎掉时本代调的是
`broadcastBreakEvent(EquipmentSlot)`,且在 `shrink` 之前,那一格里此刻放的就是碎掉的那件:覆写它,
`getItemBySlot(slot).getItem()` 取物品。

**`CraftingMenu.slotChangedCraftingGrid` 少最后一个参数**❗(`CraftingMenuAccessor`、`CraftOps`)——1.21 起可以把已知配方
当 hint 传进去免查表,本代是五个参数、自己按格局查配方:去掉 hint,调用点同步去掉。

**`Pig.equipSaddle` 只收 `SoundSource`**(gametest)、**`ServerPlayer.changeDimension` 要 `ITeleporter` 给落点**(gametest,
没有 `DimensionTransition`):
```java
pig.equipSaddle(new ItemStack(Items.SADDLE), SoundSource.NEUTRAL) → pig.equipSaddle(SoundSource.NEUTRAL)
companion.changeDimension(new DimensionTransition(nether, pos, …)) → companion.changeDimension(nether, new ITeleporter() {
    getPortalInfo(…) { return new PortalInfo(pos, Vec3.ZERO, 0, 0); } })
```
`ItemStack.enchant`、`ItemEnchantments.getLevel` 在本代收 `Enchantment` 本体,gametest 里的 `registry.getHolderOrThrow(key)` 一并去掉。
单测夹具绑标签:`Registries.tagsDirPath(key)` → `TagManager.getTagDir(key)`。

**`WorldBorder.isWithinBounds(BlockPos)` 语义不同**🔁——本代是"这一格与边界相交就算在界内",1.21 起改成"这一格的角点在界内"。寻路里
判世界边界只此一处且直接问原版(`Bounds.allowsEdit`),所以代码不用动;但 `does_not_place_on_the_world_border` 的前提
("身体站得进那一排,那一排的格子却不在界内")在本代**构造不出来**:身体碰撞盒与那一格相交,这一格就必然在界内。该用例连同它的
`pathing_border` 批次在本分支不适用,删去。

**本代的原版测试服务器不关火刻**🔁——1.21 的 `GameTestServer.TEST_GAME_RULES` 里 `doFireTick=false`,本代没有这一条。摆在路边的火会在
走的途中自己长一级,`passes_cactus_fire_berries_and_magma_unhurt` 的"账与世界对得上"就稳定挂(每次都挂,与机器负载无关)。
`Worlds.settle`(寻路用例的批次开场)自己把 `doFireTick` 关掉。

**机器满载时寻路用例会成片"时限内没收场"**(不是回归):GameTest 服务器不限速地跑刻,规划在另一个线程池里,线程池排不上队
(日志里"排队 xxxms")时刻数先耗光。同一份代码,别的任务占着 95% CPU 时连跑五次分别挂 11 / 8 / 15 / 7 / 43 条,把游戏进程调成高优先级
(Windows:`(Get-Process -Id <pid>).PriorityClass = 'High'`)后只剩上面那一条真问题,修掉之后全绿。

**评测模组 `bench/game` 的 `neoforge.mods.toml` 把 `loaderVersion` 写死成 `[4,)`**(1.21 一代的 FML)📦——本代 FML 是 javafml 3,
dev 服务器起不来(`Mod File main needs language provider javafml:4`)。本分支改成 `[2,)`,与 `gradle.properties` 的
`neoforge_loader_version_range` 同值;这是个没有跟着旋钮走的字面量,逐分支都要改。

**NeoForge 20.6.139 自带 MixinExtras 0.4.1**——已有 `@WrapMethod`,不需要像 1.21 分支那样内嵌新版。

**NeoForge 数据附件**:本代的 `AttachmentType.Builder.sync` 也没有(与 21.0 同),但本分支没有依赖附件同步的联动(见下)。

**联动插件:本分支带 ysm、curios,不带 tlm、kaleidoscope、ftbquests**。逐个查过:
- `tlm`:Modrinth 上车万女仆没有 1.20.6 的任何构建(只有 1.16.5 / 1.18~1.20.1 / 1.21.1 的各代)。
- `kaleidoscope`:CurseForge 项目 1309203 全部文件只有 1.21.1 与 1.20.1。
- `ftbquests`:maven.ftb.dev 的 `ftb-quests-neoforge` 有 2004.x(1.20.4)与 2111.x,没有 2006.x。
- `curios`:maven.theillusivec4.top 有 `8.1.0+1.20.6`,编译通过一处改动——Curios 8 的 `ICuriosItemHandler` 没有"槽位停用"
  (`isSlotActive`,9.x 才有),格子在就是启用的,`CurioGearSlot` 去掉停用判断。
- `ysm`:不引用 YSM 的类,只用命令与 NBT 键,原样带;但 YSM 本身没有 1.20.6 的构建(Modrinth 只有 1.20/1.20.1/1.21/1.21.1/26.1.2),
  装得上 YSM 的环境不存在,联动闸门恒关——留着是因为它零依赖、与 1.21.1 逐字一致,联动机制(`Builtin`/`Gate`)在 Fabric 侧只有它在用。
`core/neoforge/neoforge.mods.toml` 去掉 `numen_tlm.mixins.json`,`gradle.properties` 只留 `curios_version`。

## 1.20.6 → 1.20.4 / 1.20.4 → 1.20.2 / 1.20.2 → 1.20.1
_待移植时填写_
