# Minecraft 版本间 API 变动记录（移植手册）

Numen 采用**分支即版本**模型：每个受支持的 MC 版本一条分支（`1.21.1`、`1.21.4`、…、`26.1.2`），
Fabric + NeoForge 同源，**api 与 core 各自同名分支**。向上移植（把低版本分支搬到高版本）时，绝大多数
改动是**机械的映射/签名替换**——本文件逐版本记录这些 MC/loader API 变动，作为移植配方。

> 规则：每完成一档移植（`A → B`），把这一档碰到的**每一个** API 变动追加到对应小节。
> 宁可啰嗦：一条记录省下的是下一个人（或下一个 MC 版本）重新踩坑的时间。

约定：❗=编译期破坏性变更；📦=构建/依赖。代码示例用 `旧 → 新`。

---

## 版本阶梯

`1.20.1 → 1.20.2 → 1.20.4 → 1.20.6 → 1.21.1 → 1.21.4 → 1.21.5 → 1.21.8 → 1.21.10 → 1.21.11 → 26.1.2`

新架构（numen-api 拆分 + 调度器 + raw `NumenTool` + skill 体系）基线在 **`1.21.1`**，正逐档向上移植。
**已移植：1.21.1 → 1.21.4 → 1.21.5 → 1.21.8 → 1.21.10 ✓**

## 每档的流程

1. **api 先**：从下一档低版本分支开新分支 → 改构建旋钮 → 编译修 → `publish` 本地 maven **并 push numen-maven**。
2. **core 后**：开/重置同名分支为低版本内容 → 改构建旋钮 + api 依赖坐标指向目标 MC → 编译修 → 出包验证（内嵌 api）。
3. 边修边把变动追加到本文件。

> CI 在干净环境从远程 maven 取 api，所以 api 制品**必须 push 到 numen-maven**，否则 CI 编不过。
> 同坐标重发后 core 端若编不到新符号，删 `.gradle/loom-cache/remapped_mods/.../com/dwinovo/numen` 再编（Loom remap 缓存；新坐标无此问题）。下载 MC 用 BMCLAPI 镜像。

## 每档都要改的构建旋钮 📦

`gradle.properties`（core 与 api 各一份；core 还要改 `fabric/build.gradle`、`neoforge/build.gradle` 里的 `numen-api-*-<mc>` 坐标）：

| 键 | 1.21.1 | 1.21.4 |
|---|---|---|
| `minecraft_version` | 1.21.1 | 1.21.4 |
| `minecraft_version_range` | `[1.21.1, 1.21.2)` | `[1.21.4, 1.21.5)` |
| `neo_form_version` | 1.21.1-20240808.144430 | 1.21.4-20241203.161809 |
| `fabric_version` | 0.116.7+1.21.1 | 0.117.0+1.21.4 |
| `neoforge_version` | 21.1.233 | 21.4.123 |

---

## 1.21.1 → 1.21.4 ✓（已验证，双 loader 编译 + 出包通过）

### 通用（common，api 与 core 都有）

**注册表按 id 取值** ❗ — 方法整体改名：
```java
BuiltInRegistries.ITEM.get(rl)        → BuiltInRegistries.ITEM.getValue(rl)
BuiltInRegistries.BLOCK.get(rl)       → BuiltInRegistries.BLOCK.getValue(rl)
BuiltInRegistries.ENTITY_TYPE.get(rl) → BuiltInRegistries.ENTITY_TYPE.getValue(rl)
// 1.21.1 .get(ResourceLocation) 返回 T；1.21.4 返回 Optional<Reference<T>>，要 .getValue() 拿 T
```

**registryAccess 查注册表** ❗：
```java
registryAccess().registryOrThrow(Registries.STRUCTURE) → registryAccess().lookupOrThrow(Registries.STRUCTURE)
```

**高度访问器** ❗ — `LevelHeightAccessor` 方法改名（实现类的 `@Override` 方法名也要跟着改）：
```java
level.getMinBuildHeight() → level.getMinY()
level.getMaxBuildHeight() → level.getMaxY()
// getHeight() 不变
```

**Entity.teleportTo** ❗ — 末尾新增 boolean 参数：
```java
e.teleportTo(level, x, y, z, Set.of(), yRot, xRot) → e.teleportTo(level, x, y, z, Set.of(), yRot, xRot, false)
```

**spawnAtLocation** ❗ — 新增首个 `ServerLevel` 参数：
```java
player.spawnAtLocation(stack) → player.spawnAtLocation(serverLevel, stack)
```

**物品使用动画枚举改名** ❗：
```java
import net.minecraft.world.item.UseAnim;   → import net.minecraft.world.item.ItemUseAnimation;
UseAnim.CROSSBOW                            → ItemUseAnimation.CROSSBOW
```

**配方系统大改** ❗（`QueryExtraTools` / 老 `LookupRecipeTool`）：
```java
level.getRecipeManager().getRecipes()       → level.recipeAccess().getRecipes()
// 通用配料：1.21.4 走 PlacementInfo（新增 import net.minecraft.world.item.crafting.PlacementInfo）
cr.getIngredients().isEmpty() || allMatch(Ingredient::isEmpty)
                                            → PlacementInfo info = cr.placementInfo();
                                              info.isImpossibleToPlace() || info.ingredients().isEmpty()
recipe.getIngredients()                     → recipe.placementInfo().ingredients()  // 无空位，去掉 isEmpty 判断
// 单输入配方（切石/熔炼）：
sc.getIngredients().get(0)                  → sc.input()
cookingRecipe.getIngredients().get(0)       → cookingRecipe.input()
// shaped 网格：类型变了，空位由 Ingredient.EMPTY 变 Optional.empty()
NonNullList<Ingredient> = shaped.getIngredients()  → List<Optional<Ingredient>> = shaped.getIngredients()
cells.get(i).isEmpty() ? "." : describe(cells.get(i))
                                            → cells.get(i).map(X::describe).orElse(".")
// 配料里的物品：
Arrays.stream(ing.getItems()).map(s -> ...s.getItem()...)   // ItemStack[]
                                            → ing.items().map(h -> ...h.value()...)  // Stream<Holder<Item>>
cookingRecipe.getCookingTime()              → cookingRecipe.cookingTime()
```

### 客户端 / UI（api）

**GuiGraphics.blitSprite** ❗ — 新增首参（RenderType 函数）：
```java
g.blitSprite(sprite, x, y, w, h) → g.blitSprite(net.minecraft.client.renderer.RenderType::guiTextured, sprite, x, y, w, h)
```
波及 6 个文件 22 处：`NumenScreen`、`Dropdown`、`ProviderDropdown`、`FlatEditBox`、`SimpleButton`、`NumenToasts`。

### NeoForge loader

**客户端资源重载事件** ❗（`NumenNeoForgeClient`）：
```java
import ...client.event.RegisterClientReloadListenersEvent; → import ...client.event.AddClientReloadListenersEvent;
event.registerReloadListener(listener)
   → event.addListener(ResourceLocation.fromNamespaceAndPath(MOD_ID, "skill_loader"), listener)
```

**数据生成** ❗（`DataGenerators` / `ModBlockTagsProvider`）：
```java
gatherData(GatherDataEvent event)        → gatherData(GatherDataEvent.Client event)
// 标签 provider 不再要 ExistingFileHelper：
new ModBlockTagsProvider(out, lookup, event.getExistingFileHelper())  → new ModBlockTagsProvider(out, lookup)
super(output, lookup, MOD_ID, existingFileHelper)                     → super(output, lookup, MOD_ID)
```
（`ModItemTagsProvider` 不吃 EFH，无需改。）

---

## 1.21.4 → 1.21.5 ✓（已验证，双 loader 编译 + 出包通过）

构建旋钮：MC `1.21.5` / range `[1.21.5, 1.21.6)` / NeoForm `1.21.5-20250325.162830` /
Fabric `0.119.6+1.21.5` / NeoForge `21.5.97`。

### 通用（common）

**世界明暗判断** ❗（`GetWorldInfoTool`）：
```java
level.isDay()   → level.isBrightOutside()
level.isNight() → level.isDarkOutside()
```

**Inventory 选中槽** ❗ — 字段 `selected` 私有化，改读写方法（`BlockDigger`、`Equip/Hunt/Mine/Shoot` task）：
```java
inv.selected        → inv.getSelectedSlot()
inv.selected = slot → inv.setSelectedSlot(slot)
```

**SavedData → SavedDataType** ❗ — 存档数据走 codec 化的 `SavedDataType`（`CompanionRegistry`，在 api）：
```java
// 删 save()/load() 重写 + SavedData.Factory，改成：
import net.minecraft.world.level.saveddata.SavedDataType;
private static final SavedDataType<T> TYPE = new SavedDataType<>(
        "numen_companions", T::new, CODEC, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);
getDataStorage().computeIfAbsent(FACTORY, "name") → computeIfAbsent(TYPE)
// 仍 extends SavedData；不再需要 HolderLookup/CompoundTag/NbtOps import
```

**CompoundTag codec 化 NBT** ❗（`NumenPlayer`）：
```java
output.putUUID(KEY, uuid)                        → output.store(KEY, UUIDUtil.CODEC, uuid)
if (input.hasUUID(KEY)) x = input.getUUID(KEY)   → input.read(KEY, UUIDUtil.CODEC).ifPresent(v -> x = v)
```

### NeoForge loader
**事件总线合并** — 1.21.5 把 mod-bus 与 game-bus 合并。旧的「构造器分别向 modBus / NeoForge.EVENT_BUS
注册」**仍可编译可用**，只是 `@EventBusSubscriber(bus=…)` 的 `bus` 属性标记为 deprecated-for-removal
（仅警告）。为最小改动本档未改写为 `@EventBusSubscriber`，留待将来必要时再做。

### 未触及（新架构无需改，记录备查）
老分支这一档还改过：`SmithingRecipe.baseIngredient()`（1.21.4 Optional → 1.21.5 直接 Ingredient）、
9 字段 payload 改回 `StreamCodec.composite`（1.21.4 上限 8）、`GetOwnerStatusTool` 的 `EntityReference`。
新架构当前实现未用到这些点，故本档未改；后续相关代码若改动碰到，再按此补。

### 0.0.9 功能面二次移植追加（2026-07 全量对齐时新碰到的）

**物品类扁平化** ❗ — `SwordItem`/`DiggerItem` 等工具子类被删，武器身份改数据组件（`ToolSet.isWeapon`）：
```java
item instanceof SwordItem || item instanceof AxeItem || ... → itemStack.has(DataComponents.WEAPON)
// AxeItem/TridentItem/MaceItem 类还在,但 SwordItem 没了;统一走 minecraft:weapon 组件最稳
```

**Inventory 结构重构** ❗ — 盔甲/副手迁入 `EntityEquipment`，主背包列表私有化：
```java
inv.items                      → inv.getNonEquipmentItems()   // 36 格主背包(含快捷栏)
inv.offhand.set(0, stack)      → inv.setItem(Inventory.SLOT_OFFHAND, stack)  // 经槽位映射进 equipment
new Inventory(player)          → new Inventory(player, entityEquipment)      // 与实体共享同一实例
// 测试里 Unsafe 裸构 ServerPlayer 时,LivingEntity.equipment 字段也要反射种上同一实例,
// 否则 getOffhandItem()(读实体 equipment)与背包写入(写 Inventory 的 equipment)不同源
```

**MobEffects 字段改名** ❗（`ToolSet` 药水修正）：
```java
MobEffects.DIG_SPEED → MobEffects.HASTE
MobEffects.DIG_SLOWDOWN → MobEffects.MINING_FATIGUE
```

**世界明暗判断**（0.0.9 里在 `PerceptionTools`）：`isDay/isNight → isBrightOutside/isDarkOutside`（同上节）。

**（api 仓）shader 体系再地震** ❗ — 1.21.2–1.21.4 的 `ShaderProgram`/`CompiledShaderProgram`/JSON
配置整体移除，改代码定义的 `RenderPipeline`（`RenderPipeline.builder()` 声明 GLSL 位置/uniform/混合/
顶点格式），懒编译零注册（NeoForge `RegisterShadersEvent` 已删）；自定义 uniform 只能在裸
`RenderPass` 上 `setUniform`，GUI 内需 `g.flush()` 后对主 RenderTarget 自开 pass 绘制，scissor 用
`RenderSystem.SCISSOR_STATE` 手动继承（`RoundRect` 全重写，shader json 删除、GLSL 原样复用）。

**（api 仓）`DynamicTexture` 构造器** ❗ — 新增调试名首参：`new DynamicTexture(img)` →
`new DynamicTexture(() -> "label", img)`（`SkinTextures`）。

### 0.1.1 功能面移植追加（1.21.4 的十个提交搬到 1.21.5 时新碰到的）

**gametest 整套改数据驱动** ❗❗ — 本档最大的一刀，注解入口<b>全部删除</b>：
`@GameTest` / `@BeforeBatch` / `net.neoforged.neoforge.gametest.@GameTestHolder` /
`@PrefixGameTestTemplate` 都没了。用例改成 `minecraft:test_instance` 注册表里的条目
（结构、超时、环境收进 `TestData`），批次前置改成"测试环境"`TestEnvironmentDefinition.setup`。
移植办法（见 `core/gametest/` 四个新文件）：
```
自带 @NumenTest(template/timeoutTicks/batch) 注解  ← 与旧 @GameTest 同形状，用例方法一字不改
NumenTestInstance extends GameTestInstance         ← 直接持有方法引用
NumenTestEnvironment implements TestEnvironmentDefinition  ← 旧 @BeforeBatch 的"和平+正午"
NumenGameTests：反射扫注解 → NeoForge 的 RegisterGameTestsEvent 登记实例与环境
```
坑一：**不能用原版 `FunctionGameTestInstance`**——它按 `Registries.TEST_FUNCTION` 取用例体，
而该注册表在 `BuiltInRegistries` 引导时（`BuiltinTestFunctions::bootstrap` 一次跑完所有
loader）就冻结了，模组加载轮不上。
坑二：`TEST_INSTANCE` / `TEST_ENVIRONMENT` 在 `SYNCHRONIZED_REGISTRIES` 里，自定义实例
类型与环境类型的 `MapCodec` 必须经 `DeferredRegister` 注册进 `TEST_INSTANCE_TYPE` /
`TEST_ENVIRONMENT_DEFINITION_TYPE`，否则同步给客户端时找不到类型。
坑三：`GameTestHelper` 的断言/失败消息由 `String` 改成 `Component`
（`assertTrue(boolean, Component)`、`fail(Component)`）；`StructureUtils.testStructuresDir`
由 `String` 改成 `Path`。
**未变**：`absolutePos` 仍以 `getTestOrigin()` 为基准（与 1.21.4 同），所以 1.21.4 那套
"模板底部垫一层空气"的约定原样沿用，用例坐标一字不改；SNBT 模板照旧从
`testStructuresDir` 按 `ResourceLocation.getPath()` 取，命名空间不参与寻址。
模板里的 `DataVersion` 仍是 4189（1.21.4）——**向上移植不必重新盖版本号**，
`readStructure` 会走 `DataFixTypes.STRUCTURE.updateToCurrentVersion` 正向修到 4325
（1.21.5 的 `SharedConstants.WORLD_VERSION`）；反倒是硬盖成 4325 会跳过修数据器。

**`CompoundTag` / `ListTag` 读取全面 Optional 化** ❗（`BlueprintFormats`、`BlueprintStore`、
`BuildStates`、`BuildTaskRecord`、`BuildCompanionTask`）：
```java
tag.getInt(k)      → Optional<Integer>；要值用 tag.getIntOr(k, 默认)
tag.getCompound(k) → Optional<CompoundTag>；要值用 getCompoundOrEmpty(k)
tag.getList(k, 类型) → getList(k) 返 Optional<ListTag>；要值用 getListOrEmpty(k)（不再按元素类型筛）
tag.contains(k, TAG_X) → 没了，改判 tag.getX(k).isPresent()（缺键与类型不符同样是 empty，判据等价）
tag.getAllKeys()   → keySet()
Tag.getAsString()  → asString() 返 Optional<String>
ListTag.getInt(i)/getDouble(i) → getIntOr(i, 默认)/getDoubleOr(i, 默认)
```
**坑**：`getListOrEmpty` 不筛元素类型，旧代 `for (Tag t : getList(k, TAG_COMPOUND))` 直接强转
会 CCE，逐个 `instanceof` 挡一手（或用 `compoundStream()`）。

**`Inventory` 主背包列表私有化** ❗ — `inv.items` → `inv.getNonEquipmentItems()`；
副手 `inv.offhand.set(0, s)` → `inv.setItem(Inventory.SLOT_OFFHAND, s)`。

**`Entity.moveTo` → `snapTo`** ❗（同签名同语义；`CompanionFactory`、图纸实体落位）。

**`ServerLevel.onBlockStateChange` → `updatePOIOnBlockStateChange`** ❗❗ — 纯改名，签名与
`Level.setBlock` / `WorldGenRegion.setBlock` 两处调用点一字未动。**这条只在运行期炸**
（mixin 找不到目标 → `InvalidInjectionException`，编译期毫无征兆），跑一次 datagen 就能抓到。

**（api 仓）`TicketType` 改成不带泛型的 record** ❗ — `TicketType.create(名, 比较器, 超时)` 没了，
改 `new TicketType(超时, 是否入档, TicketUse)`；`chunkSource.addRegionTicket(型,pos,半径,值)` →
`addTicketWithRadius(型,pos,半径)`。票级算法两代一致（`ChunkLevel.byStatus(FULL) - 半径`），
重复添加同型同级票据照旧 `resetTicksLeft()`。`persist=false` 的票不入档
（`TicketStorage.packTickets` 只序列化 persist 的），因此**无需**注册进
`BuiltInRegistries.TICKET_TYPE`。

**（api 仓）`ClientInput` 两个冲量字段并成 `protected Vec2 moveVector`** ❗ —
`leftImpulse`/`forwardImpulse` 没了，包外写不进。转盘喂输入改成：屏幕算出向量返回、
`MixinKeyboardInput`（它继承 `ClientInput`）落盘。向量算法照抄原版 `KeyboardInput.tick`：
两轴冲量各取 ±1/0 后整体 `normalized()`。
**未变**：`PlayerRenderState.id` 还在（从 `EntityRenderState` 挪到了 `PlayerRenderState`，
`LivingEntityRenderer.render` 的 mixin 先 `instanceof PlayerRenderState` 再取 `id`，照旧可用）；
`ChatComponent.allMessages` / `refreshTrimmedMessages` 名字不变。

**（api 仓）`CompoundTag.getInt` 同样 Optional 化**（`CompanionFactory` 读 `playerGameType`）。

### 用例夹具的两处版本相关调整（都<b>只在运行期</b>现形）

**点击事件序列化换形状** ❗（`safe_block_entity_data_is_a_datapack_tag`）：
```
Style 里的键名  clickEvent → click_event
run_command 的参数  value → command
// 旧写法 {"text":"x","clickEvent":{"action":"run_command","value":"/give ..."}}
// 本代   {"text":"x","click_event":{"action":"run_command","command":"/give ..."}}
```
**坑**：旧形状不会解码失败，只是事件当未知字段被丢掉——组件照常解析成功、
`getStyle().getClickEvent()` 返回 null。于是"带点击事件的牌子必须拒收"这条用例
**以"没检测到威胁"的形式变红**；判据本身（读的是解析后的 `Style`）一行都不用改。

**随机刻必须停摆** ❗（`build_japanese_cottage`，本代新加）——把
`RULE_RANDOMTICKING` 设为 0，与"和平 + 正午"同属排除环境随机性：
日式小屋图纸里有 163 格草方块，盖上屋顶后随机刻把它们退化成泥土，而验收要的是
「5857 格<b>同时</b>就位」的那一瞬——先落的草在最后一格落定前就已退化，那一瞬
永远不会到来。表现为跑满 400000 tick 超时，报 `cottage cell mismatch ... 
want=grass_block got=dirt`。停掉随机刻后同一条用例 **10 秒**跑完，
说明建造本身既快且全对，退化纯属环境噪声。

## 1.21.5 → 1.21.8 ✓（已验证，双 loader 编译 + 出包 + datagen + 230 单测 + 47 条游戏内用例全绿）

**跨过 1.21.6/1.21.7**，含 1.21.6 的 GUI 深绘制 + IO 大改。构建旋钮：MC `1.21.8` / range `[1.21.8, 1.21.9)` /
NeoForm `1.21.8-20250717.133445` / Fabric `0.136.1+1.21.8` / NeoForge `21.8.47`。

### GUI 深绘制(api,1.21.6 GuiRenderState 重构)❗ 渲染第三震
GuiGraphics 不再即时绘制:元素收集为 `GuiElementRenderState`,GuiRenderer 帧末统一
批渲染(按 pipeline+textureSetup 分组,mesh 用**各管线自带的顶点格式**构建,只喂标准
UBO)。自定义 uniform 通道彻底消失:
```java
// RoundRect:弃"flush 后自开 RenderPass 直绘",改自定义 GuiElementRenderState 提交:
g.guiRenderState.submitGuiElement(state)         // guiRenderState/scissorStack 是 private
//   → common AT(META-INF/accesstransformer.cfg) + fabric AW(numen_api.accesswidener) 开放,
//     fabric.mod.json 需声明 "accessWidener";零 mixin
// SDF 参数从 uniform 迁顶点属性(NEW_ENTITY 格式):UV0=局部偏移(线性插值),
//   UV1=(半宽,半高)×16,UV2.x=圆角×16(flat varying);GLSL 迁 std140 UBO
//   (DynamicTransforms/Projection,照抄 vanilla core/gui 的内联写法)
// pose 变 Matrix3x2f(2D 仿射):顶点走 addVertexWith2DPose(pose,x,y,z);
//   scissor 从 g.scissorStack.peek() 取,bounds=transformMaxBounds+intersection
g.blitSprite(RenderType::guiTextured, …)   → g.blitSprite(RenderPipelines.GUI_TEXTURED, …)
g.renderTooltip(font, st, mx, my)          → g.setTooltipForNextFrame(font, st, mx, my)
g.renderComponentTooltip(font, list, x, y) → g.setComponentTooltipForNextFrame(font, list, x, y)
camera.getPosition()                        → camera.position()
// MultiLineEditBox 构造器包私有化 → builder()(setShowBackground(false) 可关原版方框底);
//   自绘控件底只能走 Screen.renderBackground 底层通道(控件在 super.render 先画,视图压不到底)
```

### 存档 / IO(api,1.21.6 ValueInput/ValueOutput 重构)❗
```java
public void addAdditionalSaveData(CompoundTag)  → protected void addAdditionalSaveData(ValueOutput)
public void readAdditionalSaveData(CompoundTag) → protected void readAdditionalSaveData(ValueInput)
// store/read(key,CODEC) 同名可用。import net.minecraft.world.level.storage.ValueInput/ValueOutput
getPlayerList().load(player)   → getPlayerList().load(player, ProblemReporter.DISCARDING)
send(Packet, PacketSendListener, boolean) → send(Packet, ChannelFutureListener, boolean)  // io.netty
```

### 通用(core)
```java
player.serverLevel()  → player.level()   // 1.21.6 起 ServerPlayer.level() 协变返回 ServerLevel
```

### NeoForge loader
```java
@EventBusSubscriber(modid, bus = Bus.MOD)  → @EventBusSubscriber(modid)   // 总线合并,bus 属性删除
onRenderLevel(RenderLevelStageEvent e){ if(getStage()!=…) return; } → onRenderLevel(RenderLevelStageEvent.AfterTranslucentBlocks e)
PacketDistributor.sendToServer(payload)    → ClientPacketDistributor.sendToServer(payload)  // client.network
```

### 数据生成(core)
```java
getOrCreateTagBuilder(key)  → valueLookupBuilder(key)                       // Fabric
extends net.minecraft.data.tags.ItemTagsProvider + 空 block-tag lookup
    → extends net.neoforged.neoforge.common.data.ItemTagsProvider, super(output, lookup, MOD_ID)
```

### 树替换陷阱 ❗
`git checkout <src> -- .` 不删除目标分支独有文件——替换后必须
`comm -23 <(git ls-tree -r HEAD --name-only|sort) <(git ls-tree -r <src> --name-only|sort)`
清点并 `git rm` 残留(本档 api 清了 14 个、core 清了 47 个旧 0.0.x 文件)。

### 0.1.1 功能面移植追加(1.21.5 的十一个提交搬到 1.21.8 时新碰到的)

上一档的整套适配(gametest 数据驱动、CompoundTag Optional 化、Inventory 私有化、
snapTo、TicketType record、ClientInput moveVector)在本档**原样成立,一处未动**;
47 条用例方法体与 8 个批次一字未改。本档新增的只有下面几条。

**`ItemStack.parse` / `ItemStack.save` 双双删除** ❗(`BuildStates`)——这对便捷方法没了,
读写改直接用它们自己的实现,语义一字不差:
```java
ItemStack.parse(registries, tag)
    → ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag)
              .resultOrPartial()                       // 旧方法内部就是这句(外加一行日志)
stack.save(registries)
    → ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack)
              .getOrThrow()                            // 空叠不允许编码,调用点先 filter 非空
```

**`Component.Serializer` 整个内部类删除** ❗(`BuildStates.hasClickEvent`)——文本组件的
JSON 解码统一走 codec:
```java
Component.Serializer.fromJson(json, RegistryAccess.EMPTY)   // 返回 @Nullable MutableComponent
    → ComponentSerialization.CODEC.parse(
          RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE),
          JsonParser.parseString(json)).result()
```
**判据方向要留意**:这是"告示牌带点击事件就拒收"的安全判据,解不出来必须当作"有事件"
(拒收)。旧代靠 `fromJson` 抛 `JsonParseException` 走 catch 落到 true;新代 codec 不抛异常、
只返回失败的 `DataResult`,所以要显式 `if (component == null) return true;` 把这条路补回来
——漏了就变成"解析失败 = 安全",安全判据整个反过来。

**`CompoundTag` → `ValueInput` 的桥** ❗(`BuildCompanionTask`,摆设落位与方块实体装数据)
——1.21.6 的 IO 重构把实体/方块实体的**读取入口**也换了,手上是 NBT 时要现包一层:
```java
EntityType.create(CompoundTag, level, reason) → EntityType.create(ValueInput, level, reason)
EntityType.by(CompoundTag)                    → EntityType.by(ValueInput)
be.loadWithComponents(CompoundTag, RegistryAccess) → be.loadWithComponents(ValueInput)
// 桥:TagValueInput.create(ProblemReporter.DISCARDING, registries, tag)
//    (反向是 TagValueOutput.createWithContext(...).buildResult())
```

**api 构件坐标:主仓 `common/build.gradle` 指着上一档的 artifactId** ❗——1.21.8 分支的
基线里 fabric/neoforge 已经是 `numen-api-*-1.21.8`,唯独 common 还留着
`numen-api-common-1.21.5`。上一档发过这个坐标,所以它能解析、也**照样编译得过**
(实测:本档改回 1.21.5 依旧 BUILD SUCCESSFUL)——主仓 common 恰好没碰到两代之间
签名有别的那几个 api 方法。也正因为如此它**毫无征兆**:编译不报错、跑测试不报错,
主仓 common 却是拿另一代 MC 编出来的 api 类在编译,哪天用到 `NumenPlayer` 存档一类
1.21.6 改过签名的成员就会突然炸,而且看不出跟坐标有关。三个 build.gradle 的
artifactId 要逐个核对,不是只核对版本号。

### 只在真机客户端才能目视验证的部分
本档 api 侧的 GUI 深绘制(RoundRect 的 SDF 顶点属性化、圆角/描边/裁剪的实际观感、
人设编辑框的圆角底与聚焦环、轮盘的缩放与呼吸动画)与头顶气泡的位姿,
编译、mixin 目标(已 javap 逐条核对描述符)、datagen、gametest 都覆盖不到,
只能开客户端看。

## 1.21.8 → 1.21.10 ✓（已验证，双 loader 编译 + 出包 + 222 测试全绿；跳过 1.21.9）

含 1.21.9 的**输入 API 重构 + NeoForge Transfer 重写 + authlib 9**。构建旋钮：MC `1.21.10` /
range `[1.21.10, 1.21.11)` / NeoForm `1.21.10-20251010.172816` / Fabric `0.138.4+1.21.10` / NeoForge `21.10.64`。

### 客户端输入 ❗（api：NumenScreen、SettingsScreen）
```java
keyPressed(int keyCode, int scanCode, int modifiers)  → keyPressed(KeyEvent event)          // event.key()
mouseClicked(double x, double y, int button)          → mouseClicked(MouseButtonEvent event, boolean dbl)
                                                                              // event.x()/y()/button()
// 方法开头取局部 mouseX/mouseY/button 保持方法体不变;super 调用透传 event。
// 只有 Screen 子类受影响,自有 (mx,my) 辅助方法(ChatView/Dropdown/SettingsView)不动。
// import net.minecraft.client.input.KeyEvent / MouseButtonEvent
```

### 其它客户端（api）
```java
// EditBox 格式化器:setFormatter(BiFunction) → addFormatter(EditBox.TextFormatter);fmt.apply→fmt.format(FlatEditBox)
// KeyMapping 分类:字符串 "key.categories.misc" → KeyMapping.Category.MISC(NumenKeys;默认键保持 N)
// PlayerSkin 包移动:net.minecraft.client.resources → net.minecraft.world.entity.player(4 文件)
// GuiElementRenderState.buildVertices(VertexConsumer, float z) → buildVertices(VertexConsumer)
//   addVertexWith2DPose(pose, x, y, z) → addVertexWith2DPose(pose, x, y)(RoundRect$State,z 由体系管理)
// ShapeRenderer.renderLineBox 首参 PoseStack → PoseStack.Pose(传 poseStack.last(),PathDebugRenderer)
// Fabric 世界渲染事件:...rendering.v1.WorldRenderEvents → ...rendering.v1.world.WorldRenderEvents
//   AFTER_TRANSLUCENT 与 ctx.camera() 均被撤:挂 BEFORE_DEBUG_RENDER(原版调试线绘制点),
//   ctx.matrixStack()→ctx.matrices(),相机走 Minecraft.getInstance().gameRenderer.getMainCamera()
// NeoForge RenderLevelStageEvent 子事件也撤了 getCamera() → 同样走 getMainCamera()
```

### authlib 9 ❗（api：CompanionFactory、MojangSkins）
```java
// GameProfile 变不可变 record:getProperties()/getId()/getName() → properties()/id()/name();
// 要带 textures 构造,只能建 Multimap → new PropertyMap(map) → new GameProfile(uuid, name, propMap)
// MinecraftServer.getProfileCache()/getSessionService() 收进 services() record:
server.getProfileCache().get(name)            → server.services().nameToIdCache().get(name) // Optional<NameAndId>,取 .id()
server.getSessionService().fetchProfile(...)  → server.services().sessionService().fetchProfile(...)
```

### 存档 load（api：CompanionFactory）❗
```java
getPlayerList().load(player, reporter).ifPresent(player::load)   // 1.21.8 形态
  → getPlayerList().loadPlayerData(new NameAndId(player.getGameProfile()))
        .map(tag -> TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), tag))
        .ifPresent(player::load)
```

### NeoForge 平台（api）❗
```java
// Transfer API 重写(NeoForgeBlockCapabilityReader 整体重写,可整搬旧 1.21.10 分支已迁版):
IItemHandler/IFluidHandler + Capabilities.ItemHandler/FluidHandler.BLOCK
   → ResourceHandler<ItemResource>/<FluidResource> + Capabilities.Item/Fluid.BLOCK
     (size()/getResource(i)/getAmountAsLong(i)/getCapacityAsLong(i,res))
IEnergyStorage + Capabilities.EnergyStorage.BLOCK → EnergyHandler + Capabilities.Energy.BLOCK
   canReceive()/canExtract() 没了 → 在回滚的 Transaction 里模拟 insert/extract 探测方向
// FMLLoader.isProduction() → FMLLoader.getCurrent().isProduction()(NeoForgePlatformHelper)
```

### NeoForge 核心入口（core：NumenCoreNeoForge）❗
```java
// FMLEnvironment.dist(静态字段没了)→ FMLLoader.getCurrent().getDist()
// IModFile.findResource 一直在变 → classloader 资源解析:
Path.of(NumenCoreNeoForge.class.getResource("/skills").toURI())   // 防御式 try/catch
```

（残留清点:本档 api 清 14 个旧文件/贴图、core 清 51 个旧 0.0.x 引擎类。）

### 0.1.1 功能面移植追加(1.21.8 的十二个提交搬到 1.21.10 时新碰到的)

前两档的整套适配(gametest 数据驱动、CompoundTag Optional 化、Inventory 私有化、
snapTo、ItemStack.CODEC、ComponentSerialization、TagValueInput 桥、ClientInput.moveVector、
ServerLevel.updatePOIOnBlockStateChange)在本档**原样成立,一处未动**;
47 条用例方法体与 8 个批次一字未改,common 230 单测全绿。本档新增的只有下面几条。

**渲染换成提交式管线** ❗(api：`MixinLivingEntityRenderer`、`SpeechBubbleRenderer`)
——头顶气泡走的就是实体渲染尾部，本档整条通道换了形状：
```java
// 挂载点:即时绘制 → 提交式
LivingEntityRenderer.render(S, PoseStack, MultiBufferSource, int)
    → LivingEntityRenderer.submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)
// 玩家渲染状态改名(同一个包)
PlayerRenderState → AvatarRenderState        // .id 字段仍在,取本体的写法不变
// 几何:不再直取 VertexConsumer
buffers.getBuffer(RenderType.text(tex)) → collector.submitCustomGeometry(poseStack, RenderType.text(tex),
        (PoseStack.Pose pose, VertexConsumer vc) -> { ... })   // 回调内用 vc.addVertex(pose, x, y, z)
//   注:提交时内部已 pose.copy(),回调是延后执行的,因此参与计算的局部变量要 final
// 文字:
font.drawInBatch(str, x, y, color, shadow, matrix, buffers, mode, bg, light)
    → collector.submitText(poseStack, x, y, FormattedCharSequence, shadow, Font.DisplayMode,
                              light, color, bgColor, outlineColor)   // 字符串要先 forward(s, Style.EMPTY)
// 相机朝向:广告牌旋转的来源换人
mc.getEntityRenderDispatcher().cameraOrientation() → CameraRenderState.orientation
//   (submit 的末位入参就是它;原版名牌 NameTagFeatureRenderer 用的也是这一个)
```

**Screen 输入事件化的补漏** (api：`CompanionChatScreen`、`CompanionWheelScreen`、`NumenScreen`)
——上一档基线只改了 `keyPressed` / `mouseClicked`，本档新搬来的两个屏又碰到两个：
```java
keyReleased(int keyCode, int scanCode, int modifiers) → keyReleased(KeyEvent event)
KeyMapping.matches(int keyCode, int scanCode)         → KeyMapping.matches(KeyEvent event)
```
`SafeUi` 护栏与新签名合并时，拆出来的 `mouseClickedInner` 也要改成收
`(MouseButtonEvent, boolean)` 并在方法开头取局部 `mouseX/mouseY/button`，
`super.mouseClicked` 要把 `event` 与 `doubleClick` 一并透传——否则模态屏的按钮收不到点击。

**`TicketType` 二度变形** ❗(api：`CompanionChunkLoader`)——1.21.5 刚把它改成 record，
1.21.9 又把「是否入档 + 用途」合并成一个位图：
```java
new TicketType(timeout, /*persist*/ false, TicketType.TicketUse.LOADING_AND_SIMULATION)
    → new TicketType(timeout, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION)
// 位:PERSIST=1 LOADING=2 SIMULATION=4 KEEP_DIMENSION_ACTIVE=8 CAN_EXPIRE_IF_UNLOADED=16
// 不置 PERSIST 即不入档,与旧代 persist=false 逐字等价;addTicketWithRadius 未变。
```

**窗口句柄不再是 `long`** (api：`CompanionWheelScreen` 的物理按键采样)：
```java
mc.getWindow().getWindow()            → mc.getWindow().handle()
InputConstants.isKeyDown(long, int)   → InputConstants.isKeyDown(Window, int)
// GLFW.glfwGetMouseButton 仍收 long,传 window.handle()
```

**`Entity.getServer()` 删除** ❗(core：`BlueprintTool`)——拿服务器统一走世界：
```java
companion.getServer() → companion.level().getServer()
```
这一条在主仓其余地方本就是 `level().getServer()` 写法，只有新搬下来的蓝图工具用了旧写法。

**api 构件坐标**：本档基线的三个 `build.gradle` artifactId **本来就是** `numen-api-*-1.21.10`
(上一档那个 common 指着 1.21.5 的坑未复现)，只需把三处版本号提到 `0.0.8-SNAPSHOT`。
即便如此也要逐个看——错了不报错。

**结构模板 DataVersion 不硬盖**：`.snbt` 仍留 4189，`readStructure` 会走
`DataFixTypes.STRUCTURE.updateToCurrentVersion` 正向修到本代；硬盖反而跳过修数据器。

### 只在真机客户端才能目视验证的部分
头顶气泡换到提交式管线后的**实际观感**(层次先后、小方尾位置、文字与底图的 z 关系、
与名牌的相对位置)、转盘的物理按键接管与不断步、GUI 圆角 SDF 的渲染结果、
快捷对话/语音三件套的手感——编译、mixin 目标(已 javap 逐条核对描述符)、datagen、
gametest、单测都覆盖不到，只能开客户端看。

## 1.21.10 → 1.21.11 ✓（已验证，双 loader 编译 + 出包 + datagen + 230 单测 + 47 条游戏内用例全绿）

构建旋钮:MC `1.21.11` / range `[1.21.11, 1.22)` / NeoForm `1.21.11-20251209.172050` /
Fabric `0.139.5+1.21.11` / NeoForge `21.11.42`。

### Mojang 大改名(机械替换,两仓 45 文件)❗
```java
ResourceLocation → Identifier                      // 类改名,包仍 net.minecraft.resources,工厂方法全保留
ResourceKey.location() → identifier()              // dimension().location()/ref.key().location()/unwrapKey k.location()
net.minecraft.Util → net.minecraft.util.Util       // 迁包(import 与全限定名都要改)
RenderType → net.minecraft.client.renderer.rendertype.RenderTypes   // 移包+复数化;RenderType.lines()→RenderTypes.lines()
```
注意:自有类型的 `.location()`(如 SkillRegistry 的 record 访问器)不要误替换——逐调用点确认接收者是 ResourceKey。

### GUI(api)
```java
AbstractButton.renderWidget → renderContents       // 仅 AbstractButton 系;EditBox 系仍是 renderWidget(FlatEditBox 不动)
KeyMapping 构造第 4 参 String 分类 → KeyMapping.Category.MISC
ShapeRenderer.renderLineBox 整个删除 → PathDebugRenderer 12 棱 seg() 自绘(不再依赖 vanilla 线框助手)
```

### 实体类包重组(core)
```java
net.minecraft.world.entity.monster.Spider          → monster.spider.Spider          // CaveSpider 同包
net.minecraft.world.entity.monster.ZombifiedPiglin → monster.zombie.ZombifiedPiglin
net.minecraft.world.entity.vehicle.Boat            → vehicle.boat.Boat              // AbstractBoat 层级不变,instanceof 语义不漂移
// EnderMan/Monster 未动
```

### 未发生的预警项
- FMLLoader.getCurrent() 中转在 21.11.42 仍在(loader 9 静态化预警留给 26.x);
- authlib 9 无感沿用;GuiRenderState/RoundRect$State 本档接口面零变化;AT/AW 目标字段未改名。

(残留清点:本档 api 清 14 个旧文件/贴图、core 清 51 个旧 0.0.x 引擎类——与 1.21.10 档同集合。)

### 0.1.1 功能面移植追加(1.21.10 的十三个提交搬到 1.21.11 时新碰到的)

前四档的整套适配在本档**原样成立,一处未动**——提交式渲染管线(`LivingEntityRenderer.submit`
四参签名、`AvatarRenderState`、`submitCustomGeometry`/`submitText`、`CameraRenderState.orientation`)、
Screen 输入事件化、`TicketType` 位图、`Window.handle()`、`Entity.getServer()` 已删、
gametest 数据驱动、`CompoundTag` Optional 化、`TagValueInput` 桥、`ClientInput.moveVector`、
`ServerLevel.updatePOIOnBlockStateChange`。47 条用例方法体与 8 个批次一字未改,
common 230 单测全绿。本档新增的只有下面三条。

**玩家权限等级换成权限集** ❗(api：`NumenScreen`、`SummonRequestPayload`)
——`Player.hasPermissions(int)` 整个删除,op 等级换成新包 `net.minecraft.server.permissions`
的权限对象:
```java
player.hasPermissions(2)
    → player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)
// PermissionLevel: MODERATORS(1)/GAMEMASTERS(2)/ADMINS(3)/OWNERS(4),常量在 Permissions 里一一对应;
// 原版 Player.canUseGameMasterBlocks() 本身就是 instabuild && COMMANDS_GAMEMASTER,等价关系由它坐实。
// 判据语义不变:创造档召唤仍是「有 gamemode 权限 或 主人本人在创造」才放行。
```

**游戏规则迁包 + 规则对象化** ❗(core：`NumenTestEnvironment`)
——批次前置里停随机刻的那一句:
```java
net.minecraft.world.level.GameRules → net.minecraft.world.level.gamerules.GameRules
GameRules.RULE_RANDOMTICKING(GameRules.Key<IntegerValue>) → GameRules.RANDOM_TICK_SPEED(GameRule<Integer>)
level.getGameRules().getRule(RULE).set(0, server) → level.getGameRules().set(RULE, 0, server)
// 取值侧 getInt(RULE) → get(RULE)。语义逐字等价,随机刻照停。
```
这一条不停摆就会重演 1.21.8 那个坑:图纸里的草方块被随机刻退化成泥土,
「所有格同时就位」的那一瞬永远不到来,用例跑满超时。

**大改名波及新搬下来的文件**(机械替换,共 10 个文件)——本档基线已把老文件改完,
新搬来的 0.1.1 文件还是旧写法:
```java
// ResourceLocation → Identifier:TakeItemsTool / BuildPalette / BuildTool /
//   CompanionGameTests / NumenGameTests(core),SpeechBubbleRenderer / ItemsView /
//   SpeechBubblePayload / SpeechBubbleSyncPayload(api)
// ResourceKey.location() → identifier():BlueprintStore / PathExecutor×2 / MovementAscend
//   (都是 block.builtInRegistryHolder().key().xxx().getPath() 这一串)
// RenderType.text(tex) → RenderTypes.text(tex):SpeechBubbleRenderer(移包+复数化)
```
`SkillRegistry` 的 `info.location()` 是自有 record 访问器(返回 `Path`),**不要**跟着替换。

**HUD 图层的取舍**:基线 1.21.11 注册的 `numen_toasts` 图层在 0.1.1 里已随
`NumenToasts` 一起去掉,`registerGuiLayers` 改注册 `talk_hint`(准星指着同伴时的对话提示)。
cherry-pick 会报 modify/delete 冲突,按 0.1.1 删掉即可。

**api 构件坐标**:本档基线三个 `build.gradle` 的六处 artifactId **本来就是**
`numen-api-{common,fabric,neoforge}-1.21.11`,只需把版本号提到 `0.0.8-SNAPSHOT`。
即便如此也要逐个看——错了不报错。

**技能包版本口径**:`skills/tier_progression/SKILL.md` 的
`## Where ores live (1.21+ worldgen)` 在本分支成立,无须改。

**结构模板 DataVersion 不硬盖**:`.snbt` 仍留 4189,`readStructure` 会走
`DataFixTypes.STRUCTURE.updateToCurrentVersion` 正向修到本代;硬盖反而跳过修数据器。

### 只在真机客户端才能目视验证的部分
与上一档同一份清单,且本档未再动渲染代码:头顶气泡的实际观感(层次先后、小方尾位置、
文字与底图的 z 关系、与名牌的相对位置)、转盘的物理按键接管与不断步、GUI 圆角 SDF
的渲染结果、快捷对话/语音三件套的手感,以及本档新改的**创造档下拉的可点/置灰**
(权限判据换实现后,面板上那个下拉是否按预期灰掉)——编译、mixin 目标(已逐条核对)、
datagen、gametest、单测都覆盖不到,只能开客户端看。

## 1.21.11 → 26.1.2 ✓（0.0.9 全量对齐;跨版本纪元的大跳:渲染第四震 + Java 25 + Fabric 原生 loom）

构建旋钮:MC `26.1.2` / range `[26.1.2, 26.2)` / NeoForm `26.1.2-1` / Fabric `0.148.2+26.1.2` /
**Fabric loader `0.19.2`** / NeoForge `26.1.2.50-beta`。

### ⚠ Java 25 ❗（环境级）
26.1 要求 **Gradle JVM 本身是 Java 25**(不是 toolchain)。`java_version=21 → 25`;构建时
`JAVA_HOME` 指 JDK 25(本机可用 gradle 自供给的 `~/.gradle/jdks/eclipse_adoptium-25-*`)。
CI 无需改:publish.yml 的 setup-java 动态读 gradle.properties 的 java_version。

### 渲染第四震:GuiGraphics → GuiGraphicsExtractor（api,render-state 提取模型)
```java
GuiGraphics → GuiGraphicsExtractor(类型+import,全量机械替换)
Screen.render → extractRenderState(+super);renderBackground → extractBackground
AbstractWidget: w.render(…) → w.extractRenderState(…);抽象 renderWidget → extractWidgetRenderState
AbstractButton.renderContents → extractContents;EditBox.renderWidget → extractWidgetRenderState
g.drawString → g.text;drawCenteredString → centeredText;renderItem → item;
renderItemDecorations → itemDecorations(fill/blitSprite 不变)
PlayerFaceRenderer → PlayerFaceExtractor(.draw → .extractRenderState,同参)
InventoryScreen.renderEntityInInventoryFollowsMouse → extractEntityInInventoryFollowsMouse
```
RoundRect(自定义 GuiElementRenderState):接口迁包 `client.gui.render.state → client.renderer.state.gui`
(方法面不变,addGuiElement 提交,原 submitGuiElement 改名);RenderPipeline 建造器
`withBlend(BlendFunction) → withColorTargetState(new ColorTargetState(BlendFunction))`、
`withDepthTestFunction(DepthTestFunction) → withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))`
(BlendFunction 迁 blaze3d.pipeline;DepthTestFunction 类删除);顶点格式 `NEW_ENTITY → ENTITY`。
**AW 命名空间 `named → official`**(原生 Mojang 映射下 official 即 Mojang 名,loom 会拒 named);
AW/AT 目标类 GuiGraphics→GuiGraphicsExtractor(guiRenderState/scissorStack 字段名不变,
ScissorStack 内部类随迁)。

### Fabric 原生 loom + Fabric API 大迁移
构建:`fabric-loom-remap → fabric-loom`,删 mappings 块,`modImplementation → implementation`
(api JiJ 的 include 不变;root/settings 不动)。API:
```java
KeyBindingHelper(keybinding.v1) → KeyMappingHelper(keymapping.v1).registerKeyMapping
HudRenderCallback.EVENT → hud.HudElementRegistry.addLast(Identifier, HudElement)
  (HudElement.extractRenderState(GuiGraphicsExtractor, DeltaTracker),lambda 兼容)
rendering.v1.world.WorldRenderEvents → rendering.v1.level.LevelRenderEvents
  (BEFORE_DEBUG_RENDER 没了 → BEFORE_GIZMOS,ctx.poseStack(),相机仍走 gameRenderer)
ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD → ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL
PayloadTypeRegistry.playC2S/playS2C → serverboundPlay/clientboundPlay
FabricDataOutput → FabricPackOutput;FabricTagProvider.BlockTagProvider → FabricTagsProvider.BlockTagsProvider
```
NeoForge 侧本档零改动(AfterTranslucentBlocks/FMLLoader.getCurrent() 均健在,loader 9 静态化预警未兑现)。

### core 侧代差
```java
ClickType → ContainerInput(常量同名)
ChunkPos.asLong(x,z) → pack(x,z);.x/.z 私有化 → x()/z()
Entity.interact / Player.interactOn 增加实体相对命中点 Vec3(中身高点为安全默认)
Recipe.assemble 去掉 registryAccess 参数(crafting/single/smithing 全family)
StateHolder.getValues() 返回 Stream<Property.Value> → 比对改走 getProperties()+getValue()
WaterlilyBlock → LilyPadBlock
SavedDataType 名参 String → Identifier(沿用旧分支 numen:companions 保存档兼容)
```

### ⚠ 测试环境:组件绑定迁数据包加载期 ❗
26.1 的 Item 不再自持 DataComponentMap(Item.components() 反委托 holder),绑定发生在
数据包加载(DataComponentInitializers)。headless 测试只跑 Bootstrap.bootStrap() 会在
ItemStack 构造时抛 "Components not bound yet" —— 引导后调
`McTestComponents.bindAll()`(官方 DATA_COMPONENT_INITIALIZERS.build(...).apply() 管线)。
另:@BeforeEach 的 assumeTrue 跳过**不会**拦住 @AfterEach,teardown 要兜底空对象,
否则 boot 失败会把 skip 变成 NPE 假失败。

(残留清点:本档 api 清 14 个旧文件/贴图、core 清 51 个旧 0.0.x 引擎类——与 1.21.10 档同集合;
两仓 README/CI 等主分支身份文件与 1.21.11 同路径,树替换直接覆盖为新版,无需单独保留。)

### 0.1.1 功能面移植追加(1.21.11 的十四个提交搬到 26.1.2 时新碰到的)

前五档的整套适配在本档**原样成立**——提交式渲染管线、Screen 输入事件化、`TicketType`
位图、`Window.handle()`、`Entity.getServer()` 已删、gametest 数据驱动、`CompoundTag`
Optional 化、`TagValueInput` 桥、`ClientInput.moveVector`、`ServerLevel.updatePOIOnBlockStateChange`、
以及上一档新摸出来的**玩家权限集**与**游戏规则迁包/规则对象化**(那句停随机刻的
`getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, server)` 一字未改就编过)。
47 条用例方法体与 8 个批次一字未改。本档新增的是下面这些。

**聊天 API 改名 + 加行入口换名**(api:`ChatLines`、`ChatComponentAccessor`)
```java
net.minecraft.client.GuiMessage → net.minecraft.client.multiplayer.chat.GuiMessage
ChatComponent.addMessage(Component) 整个删除 → addClientSystemMessage(Component)
// 私有的 allMessages 字段与 refreshTrimmedMessages() 都还在,摘行手术照做。
```
⚠ **流式打字机效果的前提要重新确认**:旧代靠"加行后取 `allMessages.get(0)`"拿到刚
插入的那一行。26.1 的加行链路是 `addClientSystemMessage → addMessage → addMessageToQueue`,
而 `addMessageToQueue` 第一句就是 `allMessages.addFirst(msg)` —— 新行仍落 0 位,
取句柄的写法继续成立(已逐条读字节码确认,不是想当然)。

**渲染状态提取波及新搬下来的文件**(机械替换,api 4 个文件)——本档基线已把老文件
改完,新搬来的 0.1.1 文件还是旧写法:
```java
// GuiGraphics → GuiGraphicsExtractor:CompanionChatScreen / CompanionWheelScreen /
//   TalkHint / ItemsView
Screen.render → extractRenderState;renderBackground → extractBackground
widget.render(…) → widget.extractRenderState(…)
g.drawString → g.text;g.renderItem → g.item;g.renderItemDecorations → g.itemDecorations
PlayerFaceRenderer.draw → PlayerFaceExtractor.extractRenderState
InventoryScreen.renderEntityInInventoryFollowsMouse → extractEntityInInventoryFollowsMouse
net.minecraft.client.renderer.state.CameraRenderState → …state.level.CameraRenderState
//   ↑ SpeechBubbleRenderer 的签名与 MixinLivingEntityRenderer 的 method 描述符都要跟
```

**Fabric 客户端入口三处**(api:`NumenFabricClient`)
```java
KeyBindingHelper.registerKeyBinding → KeyMappingHelper.registerKeyMapping   // 新增的三个键
HudRenderCallback.EVENT.register(...)                                        // 快捷对话提醒
  → rendering.v1.hud.HudElementRegistry.addLast(Identifier, HudElement)
rendering.v1.world.WorldRenderEvents.BEFORE_DEBUG_RENDER
  → rendering.v1.level.LevelRenderEvents.BEFORE_GIZMOS
```
另:0.1.1 删掉了 `NumenToasts`,基线注册的 `numen_toasts` 图层要一并撤掉,
换成注册 `talk_hint`(neoforge 侧同理,`RegisterGuiLayersEvent`)。

**`ChunkPos` 收口**(api `CompanionChunkLoader`、core `MineCompanionTask`/`CachedNavView`)
```java
pos.toLong() → pos.pack()         // 实例方法
ChunkPos.asLong(BlockPos) → pack(BlockPos)
pos.x / pos.z 私有化 → pos.x() / pos.z()
```

**方块类改名**(core:`BuildStates`)——`FarmBlock → FarmlandBlock`。

**基线的 `getValues()` 适配被 0.1.1 自己抹掉**(core:`BuildValidity`)
——基线为了绕开 `StateHolder.getValues()` 改返回 `Stream<Property.Value>`,把对账改成
遍历 `getProperties()`。而 0.1.1 把对账整个换成了**白名单**(只比 `AUTHORED_PROPERTIES`
里那十几个作者姿态位,逐个 `hasProperty` + `getValue`),既不碰 `getValues()` 也不碰
`getProperties()`,版本中立。冲突处直接取 0.1.1 那一侧,基线的适配连同注释一并作废。

### ⚠ gametest:测试环境泛型化 + 时刻迁世界时钟 ❗
```java
TestEnvironmentDefinition → TestEnvironmentDefinition<SavedDataType>
  setup(ServerLevel) 由 void 改为返回 SavedDataType;新增 teardown(ServerLevel, SavedDataType)
  // 注册面同步泛型化:DeferredRegister<MapCodec<? extends TestEnvironmentDefinition<?>>>、
  //   Holder<TestEnvironmentDefinition<?>>、TestData<Holder<TestEnvironmentDefinition<?>>>
ServerLevel.setDayTime(int) 整个删除 → 世界时钟(WorldClock / ServerClockManager):
  level.dimensionTypeRegistration().value().defaultClock()
       .ifPresent(clock -> level.clockManager().setTotalTicks(clock, 6000));
```
`teardown` **刻意留空**(存档类型取 `Unit`)。26.1 给的是"批次收尾还原现场"的能力,
但这三样前置的本意就是整轮压住环境随机性——尤其随机刻:批间还原成 3,上一批留在
世界里的草方块会在下一批的 setup 重新压住之前退化成泥土,正是这套前置要防的那个坑。

### ⚠ gametest:测试结构目录由平铺改成资源包布局 ❗
1.21.11 的 `StructureUtils.testStructuresDir` 走 `FileUtil.createPathToResource(dir, id.getPath(), ".snbt")`
——**平铺**,命名空间不参与,`gameteststructures/floor20.snbt` 即可。26.1 把它拆成
`testStructuresSourceDir`(读)/ `testStructuresTargetDir`(写),并且读侧走
`DirectoryTemplateSource(dir, PackType.SERVER_DATA, FileToIdConverter("structure", ".snbt"))`
——即**当成资源包根目录**解析:
```
gameteststructures/data/numen/structure/floor20.snbt      ← 26.1 布局
gameteststructures/floor20.snbt                            ← 1.21.11 布局
```
不改布局的后果是 47 条用例全数 `Failed to place test structure ... on tick 0`(在 tick 0
就全灭,不是超时)。图纸夹具 `japanese_cottage.litematic` 是按路径直接读的,不走模板
系统,仍留在 `gameteststructures/` 根下。

⚠ **跑失败之后必须清 `neoforge/runs/gametestserver/`**。上面那次"模板找不到"的失败留下了
一个世界存档,之后在它上面重跑会把区块系统拖进死亡螺旋:堆里堆到 286 万个 `ChunkHolder`
与 4890 万条排队的 `ChunkTaskDispatcher` 任务(jcmd 直方图实测),服务端主线程卡死在
`DistanceManager.runAllUpdates → LoadingChunkTracker.runDistanceUpdates`,最后 OOM
(8G 堆撑满;换 14G 也只是多撑几分钟)。**这不是代码问题**——票据一侧逐行比对过两代的
`TicketType`(record 形参与 5 个 flag 完全一致)、`TicketStorage.addTicketWithRadius`
(仍是 `ChunkLevel.byStatus(FULL) - radius`)、`purgeStaleTickets` / `canTicketExpire`
与 `ChunkHolder.isReadyForSaving`,**四处逐字相同**;`ChunkPos.pack(BlockPos)` 也确认仍
做 block→section 换算。清掉 run 目录后,默认堆、12 秒跑完 48 条全绿,与 1.21.11 的 19 秒同档。

**结构模板 DataVersion 4189 本档仍然可用**:`.snbt` 原样保留 4189 未硬盖,
`readStructure` 的 `DataFixTypes.STRUCTURE.updateToCurrentVersion` 正向修到 26.1.2,
47 条用例全绿即为实证。

### ⚠ 单测夹具:组件绑定的上下文必须带数据包注册表 ❗
基线给 `McTestComponents.bindAll()` 的上下文是
`RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)` ——**只有静态注册表**。
但防火物品的初始化器要查 `damage_type` 里的 `minecraft:is_fire` 标签,而 `damage_type`
是数据包注册表,静态注册表里根本没有,`HolderGetter.Provider.getOrThrow(TagKey)` 直接抛
`Missing tag`。抛出后被 `@BeforeAll` 的 catch 吞掉,`booted` 留在 false,**5 个测试类
共 35 条用例被 assumeTrue 静默跳过**(报告不红,只是没跑——其中就有本次移植专门对回
0.1.1 口径的那几条建造断言)。
```java
RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
  → net.minecraft.data.registries.VanillaRegistries.createLookup()
```
它把静态注册表与全部数据包注册表的引导内容合成一份 provider,且对**任何**标签一律
给空标签集(`RegistrySetBuilder` 的 datagen 语义:标签来自数据文件,引导期本就没有)。
标签查得到、内容为空,组件照常绑定,也不写死任何注册表清单。改完 230 条单测由
"195 跑 / 35 跳"变成 230 全跑全绿。

### 未发生的预警项
- **NeoForge loader 9 静态化没有兑现**:`FMLLoader.getCurrent()` 在 26.1.2.50-beta 仍在,
  neoforge 侧本次移植零改动;
- **Java 25 只是环境级**:`mixin` 的 `compatibilityLevel` 保持 `JAVA_21` 即可(两仓的
  mixins.json 都没动),gametest server 起得来即为实证——`required: true` +
  `defaultRequire: 1` 下,mixin 找不到目标会直接崩服;
- 权限集与游戏规则两条(上一档的新坑)在本档由基线与 0.1.1 代码天然对齐,未再动。

### 版本口径与不迁项
- `skills/tier_progression/SKILL.md` 的 `## Where ores live` 由 `1.21+` 改成
  `1.21+ / 26.x` —— 逐条对过 26.1.2 的 `OrePlacements`,分布与 1.21 完全一致
  (煤 triangle(0,192)、铁 triangle(-24,56) 与 triangle(80,384)、钻石
  triangle(aboveBottom ±80)),**数字不动,只动口径**;
- `gradle.properties` 的版本号故意不迁(主仓留 `0.1.0`,api 提到 `0.0.8-SNAPSHOT`)。

### 只在真机客户端才能目视验证的部分
与上一档同一份清单,且本档在渲染语义上只做了签名/包名的机械替换,没有改绘制逻辑:
头顶气泡的实际观感(层次先后、小方尾位置、文字与底图的 z 关系、与名牌的相对位置)、
转盘的物理按键接管与不断步、GUI 圆角 SDF 的渲染结果、快捷对话/语音三件套的手感、
创造档下拉的可点/置灰。本档另加两项**因为换了 API 才需要重看**的:
- **HUD 图层换成 `HudElementRegistry.addLast`** 之后,快捷对话提醒的绘制层级与位置
  (相对准星、相对其它 HUD 元素)是否与旧的 `HudRenderCallback` 一致;
- **调试线换挂 `LevelRenderEvents.BEFORE_GIZMOS`** 之后,寻路调试覆盖层的深度关系
  (是否仍被方块正确遮挡)是否与旧的 `BEFORE_DEBUG_RENDER` 一致。

编译、mixin 目标、datagen、gametest、单测都覆盖不到这些,只能开客户端看。
