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

### Client / UI 渲染（并仓迁移时逐条验证）
1. **blitSprite 家族带 RenderType 函数首参**：
   ```java
   g.blitSprite(SPRITE, x, y, w, h)
     → g.blitSprite(RenderType::guiTextured, SPRITE, x, y, w, h)
   ```
   波及所有 GUI sprite 绘制（`NumenScreen`、`ChatView`、`ChatInputBar`、`ItemsView`、
   `SimpleButton`、`FlatEditBox` 等）。`GuiGraphics.setColor` 没了——染色改走
   blitSprite 的末位 ARGB tint 重载。
2. **自定义 core shader 管线重写**（RoundRect 圆角 SDF）：`ShaderInstance` 没了，
   程序是一个 `ShaderProgram` **键**（configId 带 `core/` 前缀，POSITION_COLOR +
   `ShaderDefines.EMPTY`），编译由 ShaderManager 随资源加载完成——它扫描资源树里
   全部 `shaders/` 配置，**无需加载器注册**：
   - fabric 的 `CoreShaderRegistrationCallback` 已删,直接删注册块；
   - NeoForge `RegisterShadersEvent.registerShader(PROGRAM)` 只登记键做预热；
   - 每次绘制 `CompiledShaderProgram sh = RenderSystem.setShader(PROGRAM)` 键查表取
     编译实例（null → 降级方角）；shader json 去掉 `blend`/`attributes` 段,
     vertex/fragment 路径带 `core/` 前缀,vsh/fsh 本体不动。
3. **输入容器拆分**：`net.minecraft.client.player.Input` → `ClientInput`
   （可变冲量 + 不可变 `net.minecraft.world.entity.player.Input` 按键记录 `keyPresses`,
   整条重建而非逐字段赋值）；`KeyboardInput.tick()` 不再收潜行参数——潜行减速由
   `LocalPlayer.aiStep` 在 `input.tick()` 之后自己乘。波及 `MixinKeyboardInput`、
   `CompanionWheelScreen.feedMovement`。
4. **PlayerFaceRenderer** 的 ResourceLocation 版签名补 `(hat, upsideDown, tint)`：
   `draw(g, face, x, y, size)` → `draw(g, face, x, y, size, true, false, -1)`（原版默认）。
5. **世界渲染**：`LevelRenderer.renderLineBox` → `ShapeRenderer.renderLineBox`
   （`net.minecraft.client.renderer.ShapeRenderer`）；`getMinBuildHeight/getMaxBuildHeight`
   → `getMinY/getMaxY`（PathDebugRenderer）。
6. **客户端 reload listener（NeoForge）**：`RegisterClientReloadListenersEvent.registerReloadListener(l)`
   → `AddClientReloadListenersEvent.addListener(ResourceLocation, l)`（带键）。
7. **同构负结果**：SpeechBubble/顶点链（`addVertex().setColor().setUv().setLight()`）、
   `GuiGraphics.fill/drawString`、文本测量、`Tesselator.begin/BufferUploader.drawWithShader`
   均不动。

### 其它
- `getMinBuildHeight/getMaxBuildHeight` → `getMinY/getMaxY` 全面改名
  （**语义变了：getMaxY 含端 = 旧 getMaxBuildHeight − 1**;含自家 NavView 接口与测试
  FakeView 一并跟随改名）；`getMinSection` → `getMinSectionY`（语义不变）。
- `Direction.getNormal()` → `getUnitVec3i()`；`Direction.getNearest(x,y,z)` 需补
  fallback 参：`getNearest(dx, 0, dz, Direction.NORTH)`。
- `Entity.spawnAtLocation(stack)` → `spawnAtLocation(serverLevel, stack)`。
- `ItemCooldowns.isOnCooldown(Item)` → `isOnCooldown(ItemStack)`（冷却按组件分组）。
- `EntityType.create(level)` / `create(nbt, level)` 补 `EntitySpawnReason`
  （结构/蓝图放实体用 `STRUCTURE`,与原版 StructureTemplate 同路）。
- `BlockEntity.onlyOpCanSetNbt()` 挪到 **BlockEntityType** 上：`be.getType().onlyOpCanSetNbt()`。
- `Block.getCloneItemStack(level, pos, state)`（public）变 protected 四参；公开入口在
  `BlockStateBase`：`state.getCloneItemStack(level, pos, includeData)`。
- `Ingredient`：`getItems()`(ItemStack[]) → `items()`(Stream<Holder<Item>>)；
  `Ingredient.EMPTY` 已删（空格位在 shaped 里是 `Optional.empty()`）。
- 服务端**没有按类型取配方表的公开口**（`RecipeManager.recipeMap()` 非 public）,
  按类型枚举只能 `recipeAccess().getRecipes()` 全量遍历 + instanceof 过滤。
- `Recipe.getResultItem(registries)` 已删；枚举产出改空输入 `assemble`
  （shaped/shapeless 用 `CraftingInput.EMPTY`,熔炼/切石用 `new SingleRecipeInput(EMPTY)`,
  null/异常折 EMPTY——RecipeProbe 封装）。`AbstractCookingRecipe` 改继承
  `SingleItemRecipe`（`input()`/`cookingTime()`）；`SmithingRecipe` 有了
  `template/base/additionIngredient()` 的 Optional getter（本分支不需要,仅记录）。

### 1.21.4 落地实录（并仓迁移）
2026-08 从 1.21.1 @ b550494c 整树落底后的增量,只记上面没有的新坑：

- **GameTest 相对坐标基准变了**❗：1.21.1 的 `helper.absolutePos(rel)` 以**结构方块位**
  起算（内容首层 = rel y1）；1.21.2+ 改以**内容原点**起算（内容首层 = rel y0）。
  按旧约定写的用例整体"高一格"——门悬空（脚下空、头顶门）、沉地水凸出地面、生成点
  悬空坠落,红的全是依赖地板关系的用例（门/攀爬/水桶/放船 5 条）,其余用例内部
  自洽照样绿,极具迷惑性。修法：所有 SNBT 模板底部垫一层（size y+1、data 整体 y+1、
  原 y0 层复刻为新底层）,恢复 rel y1=地板约定,用例源码一行不动。
- **冻结注册表拒绝直写标签**：单测夹具的 `MappedRegistry.bindTags(map)` 在 1.21.4 是
  `bindTag`（逐条）,且冻结后走 `validateWrite` 直接抛。原版数据包加载对冻结注册表
  走 `prepareTagReload(new TagLoader.LoadResult<>(registryKey, map)).apply()`——夹具
  改走同一条路。症状同样迷惑：`@BeforeAll` 吞异常置 booted=false,多数类静默跳过
  （核对 skipped 数!）,个别类的 `@AfterEach` 没有 assume 护栏才炸出 NPE。
- **船族拆层级**：`Boat` 拆成 `AbstractBoat`（共用桨物理/输入字段/`controlBoat`/`setInput`）
  ← `Boat`/`Raft`/`AbstractChestBoat`。1.21.1 `instanceof Boat`（涵盖木筏、箱船）的语义
  对应本代 `AbstractBoat`——BoatAccessor mixin 目标、驾驶/上下船的类型判断全部上移;
  每种木船各自成 EntityType（`EntityType.OAK_BOAT` 等）,`new Boat(level,x,y,z)` 位置
  构造器没了,生成走 `create(level, reason)` + `setInitialPos`（Minecart 同）。
- **NeoForge datagen**：run type `data()` → `clientData()`（MDG 拆分）；
  `GatherDataEvent` → `GatherDataEvent.Client`；`BlockTagsProvider` 构造器去掉
  ExistingFileHelper 参（`neoforge.common.data.ItemTagsProvider` 本代仍没有,继续用
  vanilla 的）。
- **运行时库版本**：gson 2.10.1 → 2.11.0,slf4j-api 2.0.9 → 2.0.16（ai 模块声明跟随）。
- **同构负结果**（核实过不动的面）：FakeConnection/CommonListenerCookie/`placeNewPlayer`
  假玩家生成链、SavedData、ChunkMap mixin 注入点、`Entity.isControlledByLocalInstance`
  载具权威开关（travel 的门与 1.21.1 同構,vehicle 三用例实证）、GameTest 仍是注解制、
  附魔/数据组件/StreamCodec/ResourceLocation 工厂、SpeechBubble 顶点链、Fabric
  `MixinSoundEngine` 的 `SoundBufferLibrary.getStream` INVOKE 注入点（vanilla 1.21.4
  仍无 SoundInstance 官方钩子）。SNBT 夹具 DataVersion 3955 由 DFU 升到 4189 无恙,
  方块/物品名无需改。
- 验收数字：单测 867 全绿（0 跳过）,gametest 65/65 连续 3 轮全绿。

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

### 1.21.5 落地实录（并仓迁移，2026-08）

树落底 1.21.4 完成树（ab8212b1，源出 1.21.1 b550494c）后一步走到 1.21.5。上面各节
全部按预告命中；并仓树新踩到的如下。

**载具权威判定拆分** ❗❗（`MixinEntityVehicleControl`，上面各节没有——载具功能面
当年未铺到旧 1.21.5 分支）：`Entity.isControlledByLocalInstance` 改名
`isLocalInstanceAuthoritative`，且判定拆成三个方法——服务端侧变成
`!isClientAuthoritative()`，而 `isClientAuthoritative` 见"控制乘客是玩家"即真。
同伴是 Player 子类照样中招，冻船 bug 在新判定下原样存在;注入点跟着改名即可,
覆写语义一字不变。**只在运行期炸**(datagen 会拉起 mod 加载,一跑就现形)。

**气泡 mixin 在 1.21.2+ 一直是哑的** ❗❗:`MixinPlayerRenderer` 注的
`render(AbstractClientPlayer,FF,PoseStack,MultiBufferSource,I)` 在渲染状态化
(1.21.2)之后的 `PlayerRenderer` 上根本不存在——1.21.4 完成树带着同一只哑 mixin
(无头 gametest 不加载客户端渲染类,照不出;require=1,真机开客户端渲染玩家时必炸)。
本档换成 `LivingEntityRenderer.render(LivingEntityRenderState,…)` TAIL 挂载:
`instanceof PlayerRenderState` 筛玩家,经状态里的实体网络 id(`PlayerRenderState.id`,
1.21.5 仍在)取回本体。**1.21.4 分支同病,待回铺。**

**SavedDataType 全家桶**:并仓树共 **三个** SavedData 类(`CompanionRegistry`、
`EventOutbox`、`TimerRegistry`),全部按上文方式转 codec 化 `SavedDataType`;
save()/load() 重写删除后,单测的往返改为直接对包内可见的 CODEC(测的就是生产在用的
那条路)。"垃圾降级为空"的兜底责任移进 vanilla 存储层(`readSavedData` 解析失败
`resultOrPartial(log).orElse(null)`,`computeIfAbsent` 落回构造器)——我们自己那半边
只需保证 codec 解析失败返回 error 而不抛异常,单测判据相应改写。

**MobEffects 字段改名补一条**(测试夹具):`DAMAGE_RESISTANCE → RESISTANCE`
(与 DIG_SPEED→HASTE 同一轮改名)。

**9 字段 payload 改回 composite**:`NumenLocationsPayload.Snapshot`(9 字段)从
1.21.4 的手写 StreamCodec 改回 `StreamCodec.composite`(上限回到 9);线格式与手写
逐字段一致(同序同码,`writeUtf(256)` ≡ `stringUtf8(256)`),不破协议。

**gametest 总数会多 1**:vanilla 在 `TEST_INSTANCE` 注册表引导时自注册
`minecraft:always_pass`,GameTestServer 报 `66 GAME TESTS`= 本仓 65 + 它 1,不是账错。

**批次环境口径**(并仓树 14 个批次):旧代带 `@BeforeBatch` 的七个批次
(mine/build/build_cottage/build_heavy/blueprint/mode/cottage_jp)挂"和平+正午+
随机刻停摆"环境;其余七个(combat/interact/inventory/smoke/survival/terrain/vehicle)
旧代就没有前置,挂空环境,语义逐字保持。

**同构负结果**(查过、确认不用动):`GameTestHelper` 除 assertTrue/fail 外
succeedWhen/runAfterDelay/startSequence/succeed 签名全部未变;圆角 GLSL 与 api 仓
1.21.5 分支逐字节一致,原样复用;SNBT 模板 DataVersion 4189 不动,DFU 正常升到 4325;
NeoForge 21.5 仍无公开 `common.data.ItemTagsProvider`(方块侧照旧走
BlockTagsProvider、物品侧裸 TagsProvider);vanilla 1.21.5 的 `SoundInstance` 仍无
`getStream` 钩子(语音的 NeoForge 补丁/Fabric mixin 双轨照旧);MC 1.21.5 运行时
gson 仍为 2.11.0;其余七只 mixin(skipPlayer/applyChunkTrackingView/send/play/
controlBoat/dataSlots/allMessages+refreshTrimmedMessages/nibble)目标逐一对过
1.21.5 字节码,全部还在。


## 1.21.5 → 1.21.8 ✓（已验证：并仓树双 loader 编译 + 出包 + datagen 四路 + 867 单测 + 65 条游戏内用例 ×3）


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


### 1.21.8 落地实录（并仓迁移，0.1.1 → 0.1.2 功能面 = 1.21.1 b550494c）

树落底 1.21.5 尖（78c0ec54）后单跳本档。上面整节（0.1.1 时代趟平的路）在并仓树上
**全部原样成立**；本实录只记并仓/0.1.2 新碰到的。

**同伴召唤冻死 gametest 服务器** ❗——1.21.8 的 `PlayerList.placeNewPlayer` 在建网络
会话前新增 `ServerLevel.waitForChunkAndEntities(player.chunkPosition(), 1)`：阻塞等身体
所在区块**连实体存储一起**就绪。两个事实叠出死锁：无 .dat 的新召唤在等待前被 vanilla
先 snap 去世界出生点（等的是出生点区块，不是召唤点）；`managedBlock` 只在
`haveTime()` 时轮询区块任务，gametest 的繁忙 tick 预算耗尽后区块加载永不推进——
服务器线程从此停摆（线程转储：`MinecraftServer.waitForTasks` ← `waitForChunkAndEntities`
← `placeNewPlayer`），且**间歇复现**（首轮 66 条全过、次轮冻死）。治法照 Carpet 假玩家
的 `fixStartingPosition`：`NumenPlayer` 挂 `intendedSpawnPos`，新 mixin
`MixinPlayerListCompanionSpawn` 注在 `waitForChunkAndEntities` 调用前把身体先站到召唤
落点（主人身边/测试结构，区块必然已加载且实体就绪），等待条件当场满足。休眠原位
苏醒（pos=null）不动，与真玩家登入未加载区块同一风险面，vanilla 口径。

**AW 落位（并仓 loom 专属）** ❗——`core:fabric` 以 `namedElements` 直连消费
`api:fabric` 时，loom 按 api 的 fabric.mod.json 声明**就地**找 `numen_api.accesswidener`；
放 api/common 资源根（旧独立仓的位置）会让 core:fabric 配置期直接炸
"Could not find"。AW 必须放 `api/fabric/src/main/resources/`（与 fabric.mod.json 同资源
根），`accessWidenerPath` 也指这里；AT 照旧住 api/common 的 META-INF（common/neoforge
的接线都是"文件存在即生效"，放对位置零改构建）。

**0.1.2 功能面复核**（上面 0.1.1 追加节之外的新代码）：
- 编辑卡/G 面板/绑定点这批新 UI 全程走 GuiGraphics 正路（fill/drawString/blitSprite/
  enableScissor），无一处 flush/Tesselator/RenderPass 直绘残留,深绘制面机械替换即净；
  `MultiLineEditBox` 在 0.1.2 **零调用**（人设编辑重构进编辑卡），builder 迁移整条免掉。
- `player.serverLevel()` 在 0.1.2 树零调用点，core 通用条免掉。
- 载具面：`isLocalInstanceAuthoritative` 的 final + isLocalClientAuthoritative/
  isClientAuthoritative 双分支形态 1.21.5 与 1.21.8 一字不差，MixinEntityVehicleControl
  原样成立；AbstractBoat 族、BoatAccessor(controlBoat) 同样未动。
- 头顶气泡：`LivingEntityRenderer.render(LivingEntityRenderState,PoseStack,
  MultiBufferSource,I)V` 签名未变，`PlayerRenderState.id` 仍在，PlayerRenderer 仍无
  render override——挂载点不用挪。
- 九只 mixin（skipPlayer/applyChunkTrackingView/send/play/controlBoat/dataSlots/
  allMessages+refreshTrimmedMessages/nibble/updatePOIOnBlockStateChange）目标逐一对过
  1.21.8 反编译源，全部还在。
- `StreamCodec.composite` 上限扩到 11 字段（1.21.5 是 9）；`hasChunkAt` 两代同为
  @Deprecated,非本档新增。datagen 四路产物与 1.21.5 树 committed 生成物零 diff。
  gametest 日志的 3 条 `BlockAttachedEntity … invalid position: null` ERROR 与 1.21.5
  分支逐条相同，非回归。运行时 gson 2.11.0 / slf4j-api 2.0.16 与 1.21.5 同。

## 1.21.8 → 1.21.10 ✓（已验证：并仓树双 loader 编译 + 出包 + datagen 四路 + 867 单测 + 65 条游戏内用例 ×3；跳过 1.21.9）

含 1.21.9 的**输入 API 重构 + NeoForge Transfer 重写 + authlib 9**。构建旋钮：MC `1.21.10` /
range `[1.21.10, 1.21.11)` / NeoForm `1.21.10-20251010.172816` / Fabric `0.138.4+1.21.10` / NeoForge `21.10.64`。

### 客户端输入 ❗（api：三个 Screen 子类全套事件化）
```java
keyPressed(int, int, int)             → keyPressed(KeyEvent)                    // event.key()/scancode()/modifiers()
keyReleased(int, int, int)            → keyReleased(KeyEvent)
charTyped(char, int)                  → charTyped(CharacterEvent)               // (char) event.codepoint()
mouseClicked(double, double, int)     → mouseClicked(MouseButtonEvent, boolean) // event.x()/y()/button()
mouseReleased(double, double, int)    → mouseReleased(MouseButtonEvent)
mouseDragged(double,double,int,double,double) → mouseDragged(MouseButtonEvent, double, double)
mouseScrolled(double,double,double,double)    → 不变
KeyMapping.matches(int, int)          → KeyMapping.matches(KeyEvent)
// 方法开头取局部变量保持方法体不变;super 调用透传 event;import net.minecraft.client.input.*
// 只有 Screen 子类受影响(NumenScreen/CompanionChatScreen/CompanionWheelScreen);
// 自有 (mx,my) 辅助类(ChatView/Dropdown/SettingsView/各 Panel)都不是 GuiEventListener,不动。
// SafeUi 护栏拆出的 mouseClickedInner 同步换 (MouseButtonEvent, boolean) 并在开头取局部量。
```

### 其它客户端（api）
```java
// KeyMapping 分类:字符串翻译键 → KeyMapping.Category.register(RL("numen_api","companions"))(NumenKeys;默认键保持 N);
//   语言键随 Category.label() 推导键走:key.categories.numen → key.category.numen_api.companions(datagen 语言源同改)
// PlayerSkin 迁包:net.minecraft.client.resources → net.minecraft.world.entity.player(KnownSkins/ChatView/NumenScreen)
// GuiElementRenderState.buildVertices(VertexConsumer, float z) → buildVertices(VertexConsumer)
//   addVertexWith2DPose(pose, x, y, z) → addVertexWith2DPose(pose, x, y)(RoundRect$State,z 由体系管理)
// ShapeRenderer.renderLineBox 首参 PoseStack → PoseStack.Pose(传 poseStack.last(),PathDebugRenderer)
// 窗口句柄不再裸 long:mc.getWindow().getWindow() → mc.getWindow().handle();
//   InputConstants.isKeyDown(long,int) → isKeyDown(Window,int);GLFW.glfwGetMouseButton 仍收 long,
//   传 window.handle()(CompanionWheelScreen 物理按键采样)
// Fabric 世界渲染事件:...rendering.v1.WorldRenderEvents → ...rendering.v1.world.WorldRenderEvents
//   AFTER_TRANSLUCENT 与 ctx.camera() 均被撤:挂 BEFORE_DEBUG_RENDER(原版调试线绘制点),
//   ctx.matrixStack()→ctx.matrices(),相机走 Minecraft.getInstance().gameRenderer.getMainCamera()
// NeoForge RenderLevelStageEvent 子事件也撤了 getCamera() → 同样走 getMainCamera()
// SoundEngine.play 返回类型 void → PlayResult(MixinSoundEngine 按名匹配,不受影响)
```

### 渲染换成提交式管线 ❗（api：MixinLivingEntityRenderer、SpeechBubbleRenderer）
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
// 0.1.1 的正文/状态双行、statusFrom 分色逐行 submitText,功能面一字不动。
```

### authlib 9 ❗（api：CompanionFactory）
```java
// GameProfile 变不可变 record:getProperties()/getId()/getName() → properties()/id()/name();
// 要带 textures 构造,只能建 Multimap → new PropertyMap(map) → new GameProfile(uuid, name, propMap)
// 换肤流程不另受影响:皮肤真源在注册表,换肤本就整只重建身体(dormant→respawn),构造时注入即可。
```

### 存档 load（api：CompanionFactory）❗
```java
getPlayerList().load(player, reporter).ifPresent(player::load)   // 1.21.8 形态
  → getPlayerList().loadPlayerData(new NameAndId(player.getGameProfile()))
        .map(tag -> TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), tag))
        .ifPresent(player::load)
```

### 召唤 join 流程重构 ❗（api：MixinPlayerListCompanionSpawn 退役）
```java
// 1.21.9+ 把「等区块(waitForChunkAndEntities→改名 waitForEntities)+ 挪出生点 + 内部读档」
// 整段从 placeNewPlayer 搬去配置期的 PrepareSpawnTask(只有真实登录走):
// placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie) 签名未变,但不再阻塞、
// 不再挪位、不再碰 .dat。
// → 1.21.8 治 gametest 饿死的 MixinPlayerListCompanionSpawn 注入靶点消失,饿死路径同时消失:
//   整只退役,NumenPlayer.intendedSpawnPos 脚手架一并删除;工厂直接 join,显式落点仍在
//   join 后 snapTo(语义与旧代一字不变)。gametest 65 条 ×3 验证无饿死。
```

### 实体（api/core）
```java
startRiding(Entity, boolean)  → startRiding(Entity, boolean, boolean emitEvents)
// 第三参 = 是否发 ENTITY_MOUNT 游戏事件与骑乘进度触发;原版单参便捷重载默认 true。
// NumenPlayer 覆写透传;gametest 三处直调点补第三参 true。
Entity.getServer() 删除       → companion.level().getServer()   // BlueprintTool
```

### NeoForge 平台（api）❗
```java
// Transfer API 重写(NeoForgeBlockCapabilityReader 整体重写,整搬旧 1.21.10 分支已迁版,零改动成立):
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

### TicketType 二度变形 ❗（api：CompanionChunkLoader）
——1.21.5 刚把它改成 record，1.21.9 又把「是否入档 + 用途」合并成一个位图：
```java
new TicketType(timeout, /*persist*/ false, TicketType.TicketUse.LOADING_AND_SIMULATION)
    → new TicketType(timeout, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION)
// 位:PERSIST=1 LOADING=2 SIMULATION=4 KEEP_DIMENSION_ACTIVE=8 CAN_EXPIRE_IF_UNLOADED=16
// 不置 PERSIST 即不入档,与旧代 persist=false 逐字等价;addTicketWithRadius 未变。
```

### 同构负结果（0.1.1 并仓树里不存在的旧触点，一句话记录）
- EditBox.setFormatter → addFormatter:0.1.1 无 EditBox 格式化器触点,整条免掉。
- server.getProfileCache()/getSessionService() 收进 services():皮肤查询 0.1.1 已整体搬到
  客户端 HTTP(MojangSkinLookup),服务端会话服务栈零触点,整条免掉。
- api 构件坐标:并仓树 archivesName = "${mod_id}-${project.name}-${minecraft_version}"
  模板自动带版本,旧独立仓要手改的三处 artifactId 不存在。
- 旧分支的 MixinTitleScreen 在 0.1.1 已不存在。

### 陷阱与免动项
- **反编译源是 NeoForge 补丁后的** ❗:补丁把后续 MC 的 `SoundInstance.getStream` 官方钩子
  提前引入,光看补丁源会误判 vanilla 已有钩子而错删 fabric 的 MixinSoundEngine;官方
  mappings 证实 vanilla 1.21.10 仍无该钩子、`SoundBufferLibrary.getStream(RL,boolean)` 原样,
  redirect 靶点成立,mixin 保留(IVoiceSoundFactory 双轨照旧)。
- `isLocalInstanceAuthoritative` 的 final + isClientAuthoritative 双分支形态与 1.21.8
  一字不差,MixinEntityVehicleControl 原样成立(@Inject 不受 final 影响)。
- 其余 mixin 靶点(skipPlayer/applyChunkTrackingView/send(Packet)/controlBoat/dataSlots/
  allMessages+refreshTrimmedMessages/nibble/updatePOIOnBlockStateChange)对过 1.21.10
  反编译源逐一全在;AW/AT 三条(guiRenderState/scissorStack/ScissorStack)原样。
- `StreamCodec.composite` 上限仍 11 字段;运行时 gson 2.11.0 / slf4j-api 2.0.16 与 1.21.8 同。
- 夹具 .snbt 全为 DataVersion 3955(1.21.1 源出),`readStructure` 走 DFU 正向升到本代,
  三轮 gametest 无恙——不硬盖,硬盖反而跳过修数据器。

### 只在真机客户端才能目视验证的部分
头顶气泡换到提交式管线后的**实际观感**(层次先后、小方尾位置、文字与底图的 z 关系、
与名牌的相对位置)、转盘的物理按键接管与不断步、GUI 圆角 SDF 的渲染结果、
快捷对话/语音三件套的手感——编译、mixin 靶点(已逐条核对 1.21.10 反编译源/官方
mappings)、datagen、gametest、单测都覆盖不到,只能开客户端看。

### 1.21.10 落地实录（并仓迁移，0.1.1 功能面 = 1.21.1 b550494c）

树落底 1.21.8 尖(510e77c5)后单跳本档,跨 1.21.9。提交序列:树落底 → build 旋钮 →
api → core,双 loader 出包 + datagen 四路 + 867 单测零跳过 + gametest
"All 66 required tests passed" ×3(首轮即过,无回归)。前三档整套适配在本档原样成立,
一处未动;旧独立仓 1.21.10 分支(api@87dd5e7 / core@1fa056b3)的输入事件、authlib9、
Transfer 重写、提交式管线形态整搬/照抄成立。本档并仓树**新**碰到的只有两条:
「召唤 join 流程重构」(placeNewPlayer 不再阻塞,1.21.8 的召唤站位 mixin 整只退役)与
「startRiding 三参」(载具功能 0.1.1 新有,旧分支没趟过)——都已并入上文;其余即
负结果与陷阱清单。语言键 key.category.numen_api.companions 由 datagen 语言源与
Category 注册两头同改,生成物核对含新键。

## 1.21.10 → 1.21.11 ✓（已验证：并仓树双 loader 编译 + 出包 + datagen 四路 + 867 单测 + 65 条游戏内用例 ×3）

构建旋钮:MC `1.21.11` / range `[1.21.11, 1.22)` / NeoForm `1.21.11-20251209.172050` /
Fabric `0.139.5+1.21.11` / NeoForge `21.11.42`。运行时库跟着跳了一档:
gson `2.13.2` / slf4j-api `2.0.17`(ai/build.gradle 的"与 MC 运行时同版"声明要跟表,
1.21.10 还是 2.11.0/2.0.16——错了不报错,launcher manifest 是唯一真源)。

### Mojang 大改名（机械替换但要逐点确认）❗
```java
ResourceLocation → Identifier                      // 类改名,包仍 net.minecraft.resources,工厂方法全保留;并仓树 165 处/53 文件
ResourceKey.location() → identifier()              // 19 处:dimension().location()/ref.key().location()/unwrapKey k.location()/builtInRegistryHolder().key().location()
net.minecraft.Util → net.minecraft.util.Util       // 迁包,28 处(import 与全限定名都要改)
RenderType → net.minecraft.client.renderer.rendertype.RenderTypes   // 移包+复数化;lines()/text() 同名保留,2 文件
```
- `SkillRegistry` 的 `info.location()` 是自有 record 访问器(返回 `Path`),3 处**不要**跟着替换——
  逐调用点看接收者是不是 ResourceKey。
- **脚本替换必须大小写敏感** ❗:PowerShell `-replace` 默认不敏感,`net\.minecraft\.Util`
  会咬到 `net.minecraft.util.*`(炸出 `util.Util.FormattedCharSequence`),`monster\.Zombie`
  会咬到刚替换出的 `monster.zombie.` 新包名。用 `[regex]::Replace`,替换完全树复核。
- **排除 gradle build 目录的过滤器别误伤源码包** ❗:`core/build/`、`core/task/build/`
  两个蓝图建造包就叫 build,`-notmatch '\\build\\'` 会把它们整包漏掉(BuildPalette/
  BuildShapes 的全限定 ResourceLocation 因此漏网,编译才现形)。

### GUI（api）
```java
AbstractButton.renderWidget → renderContents       // 本树零触点:NumenUI 不走 AbstractButton,EditBox 系仍 renderWidget
KeyMapping 构造第 4 参 Category                     // 上档已就位,确认即可
ShapeRenderer.renderLineBox 整个删除                // → PathDebugRenderer 12 棱 seg() 自绘(seg 本来就有,drawBox 改内联 12 条)
```

### 实体类包重组（api/core,共 8 文件）
```java
net.minecraft.world.entity.monster.Spider          → monster.spider.Spider          // CaveSpider 同包(本树未用)
net.minecraft.world.entity.monster.Zombie          → monster.zombie.Zombie          // ❗路书外:本体也迁了(gametest 在用)
net.minecraft.world.entity.monster.ZombifiedPiglin → monster.zombie.ZombifiedPiglin
net.minecraft.world.entity.vehicle.AbstractBoat    → vehicle.boat.AbstractBoat      // ❗路书外:旧树只用具体类 Boat 没趟过;instanceof 语义不漂移
// Creeper/EnderMan/Enemy/Monster 未动
```

### 玩家权限等级换成权限集 ❗（api:Companions.applyGameMode、NumenScreen ×2）
```java
player.hasPermissions(2)
    → player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)
// 客户端 LocalPlayer 同一写法(原版已同步);判据语义不变:创造档仍是「有 gamemode 权限 或 主人在创造」放行。
```

### 游戏规则迁包 + 规则对象化 ❗（core:NumenTestEnvironment）
```java
net.minecraft.world.level.GameRules → net.minecraft.world.level.gamerules.GameRules
level.getGameRules().getRule(RULE_RANDOMTICKING).set(0, server)
    → level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, server)   // 取值侧 getInt(RULE) → get(RULE)
```
不停摆就重演 1.21.8 的坑:图纸草方块被随机刻退化,「所有格同时就位」永远不到来。

### 1.21.1 功能面首次趟到的两处（路书没列——旧 0.1.1 树无此代码,以反编译源为准）
- **`DimensionType.ultraWarm()` 删除**(core:MLGChain)→ 环境属性表:
  `level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos)`,
  与原版 `BucketItem` 蒸发分支同源同判。
- **`Player.BedSleepingProblem` 枚举 → record**(core:SleepOps/SleepOpsTest)——
  `record BedSleepingProblem(@Nullable Component message)`:`getMessage()` → `message()`,
  `name()`/`values()` 没了;常量剩 TOO_FAR_AWAY/OBSTRUCTED/OTHER_PROBLEM/NOT_SAFE,
  "白天不能睡"类拒绝改由 BED_RULE 环境属性 `asProblem()` 供文案(仍带原话);
  null 文案只剩 OTHER_PROBLEM。测试改点名常量。

### #56 两条症状在本树的判定 ❗
- **假玩家伤害免疫**:根因是 1.21.x 的客户端加载门——`ServerPlayer.isInvulnerableTo`
  含 `!connection.hasClientLoaded()`,新建连接自带 60 tick 宽限倒计时(在
  `ServerPlayer.tick()` 里递减)。本树 NumenPlayer.tick 走 super.tick,计时会走完,
  故只剩**每次出生 3 秒无敌窗口**;已在 CompanionFactory 出生时替客户端补
  `handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket())` 上报,窗口归零。
  `fall_damage_reaches_the_body` ×3 过。注意 vanilla 的 `markClientLoaded()` 是
  **private**——NeoForge 补丁源里 public,又一例"反编译源是补丁后的"陷阱
  (common 对 vanilla NeoForm 编译,得走 handleAcceptPlayerLoad 公开门)。
- **传送门寻路**:本树判定链(canWalkThroughBlockState 无点名 →
  `isPathfindable(LAND)` 默认 = 非整格碰撞 → 可通行)对 nether_portal 与 1.21.1
  同判;NetherPortalBlock 两版都无 isPathfindable 覆写,blocksToAvoid 默认空。
  旧树症状出自已退役引擎。测试套无传送门用例,穿门全程真机未验。

### 同构负结果与免动项
- `StreamCodec.composite` 上限 11 → 12 字段(本 record 9 个两版都装下,仅注释口径)。
- vanilla `SoundInstance` 仍无 `getStream` 钩子(官方 mappings 证实),fabric
  MixinSoundEngine 靶点原样;`Sound.getLocation()/getPath()` 未改名(SoundInstance
  的 `getLocation()` 改成了 `getIdentifier()`,本树零触点)。
- AT/AW 三条(guiRenderState/scissorStack/ScissorStack)原样;mixin 全家靶点在
  (气泡 submit 四参、载具 isLocalInstanceAuthoritative 双分支与 1.21.10 一字不差)。
- FMLLoader.getCurrent() 中转在 21.11.42 仍在;GuiRenderState/RoundRect 接口面零变化。
- 夹具 .snbt 仍 DataVersion 3955,DFU 正向升到本代,不硬盖。
- HitResult.getLocation()(Vec3)与大改名无关,别误替换。

### 1.21.11 落地实录（并仓迁移,0.1.1 功能面 = 1.21.1 b550494c）

树落底 1.21.10 尖(20afd821)后单跳本档。提交序列:树落底 → build 旋钮 → api →
core → test(SleepOpsTest record 化) → fix(#56 加载上报) → build(ai 库对表)。
双 loader 出包 + datagen 四路(生成物零漂移)+ 867 单测零跳过 + gametest
"All 66 required tests passed" ×3 再 +1(修复后复验)。上一档整套适配原样成立,
一处未动;api 构件坐标模板自动带版本。本档新趟出的都在上文:实体迁包比路书多
两条(AbstractBoat/Zombie)、1.21.1 功能面首碰两处(ultraWarm/BedSleepingProblem)、
#56 根因(hasClientLoaded 门)顺手修掉。

## 1.21.11 → 26.1.2
_待移植时填写_
