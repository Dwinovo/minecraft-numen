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

### 2026-10 对齐 1.21.1 @ 7a3d3e251(0.1.3 之后约 900 个提交)追加 ❗

方法:不逐提交搬,**最终态整树替换 + 平台适配重放**。树级三方合并(`merge-tree --merge-base=<上次对齐的 1.21.1 提交> <新 1.21.1 尖>
<本分支尖>`)把上一次对齐留下的平台适配自动重放到新树上,冲突的手工收;但"新代码里新踩到的 API 差异"合并是不知道的,
得靠编译 + datagen(mixin 的运行期校验)+ GameTest 逐个找。下面只记**新**碰到的。

**批量替换的陷阱**(这次踩过两次):
- `\binv\.items\b` 这类正则会咬到 Lua 函数名 `numen.inv.items`(模型能看见的 API 名、@Example、测试里的 Lua 串)。
  替换完必须 grep 一遍字符串里有没有被改坏的 API 名。
- 排除构建目录别按名字 `build` 过滤——`core/task/build/`、`core/build/` 是源码包。

**客户端 GUI(新代码的绘制面比旧树大得多)**
- `GuiGraphics.setColor` / `RenderSystem.getShaderColor` 没了,而 1.21.1 的界面把它当成"整块淡入淡出"的全局透明度
  (卡片、菜单、通知、对话流新消息飞入都靠它,字、填充、脸一起淡)。1.21.6+ 绘制攒成渲染状态,没有全局着色。
  补法:`GuiAlpha`(客户端渲染线程上的一个透明度)+ `MixinGuiGraphics`,在 `submitColoredRectangle`(填充)、
  `drawString(Font, FormattedCharSequence, …, boolean)`(文字)、`submitBlit`(所有贴图与精灵,玩家脸也走它)三个提交口把
  颜色的 alpha 乘上它。调用点只把 `g.setColor(1,1,1,a)` 换成 `GuiAlpha.set(a)`,`getShaderColor()[3]` 换成 `GuiAlpha.get()`。
  `Sprites.draw` 的着色改成 `blitSprite(RenderPipelines.GUI_TEXTURED, …, argb)`。
- `drawString` 的颜色 alpha 为 0 就不画(1.21.1 会补满 alpha):字色一律要带 `0xFF……`。
- `pose()` 是 `Matrix3x2fStack`:`pushPose/popPose` → `pushMatrix/popMatrix`,`translate(x,y,0)/scale(a,b,1)` 去掉 z。
  原来靠 z 抬高的层(通知卡 z=300,让给 400 的悬停提示)改 `g.nextStratum()`:提示是帧末另起一层画的。
- `renderTooltip/renderComponentTooltip` → `setTooltipForNextFrame/setComponentTooltipForNextFrame`;
  `blitSprite(ResourceLocation,…)` → `blitSprite(RenderPipelines.GUI_TEXTURED, …)`。
- `ClickEvent` 拆成按动作的记录:`new ClickEvent(OPEN_URL, String)` → `new ClickEvent.OpenUrl(URI)`,`getAction()` → `action()`。
  URI 解析会抛(`|`、`^` 这类字符),所以 `ChatLinks.find` 只认 `URI.create` 过得去的网址——画与点的地方不再担心抛错。
- `ToastComponent` → `ToastManager`(`mc.getToasts()` → `mc.getToastManager()`,`occupiedSlots` 还在)。
- 世界渲染:`LevelRenderer.renderLineBox` → `ShapeRenderer.renderLineBox`,`camera.getPosition()` → `camera.position()`。
- 新 1.21.1 树里没有 `RoundRect`,所以 AT/AW(`guiRenderState`/`scissorStack`)与自定义 shader 一并不需要:上面"AW 放 api/fabric"那条在这棵树上不适用。
- 头顶气泡 mixin 挂在 `LivingEntityRenderer.render(S,PoseStack,MultiBufferSource,I)` 的 **HEAD**(与 1.21.1 的 `PlayerRenderer.render` HEAD 同理:
  插件取消渲染事件时 TAIL 不会跑)。

**存档**
- 新增的三个 SavedData(`PlacedBlocks`、`PermissionStore`、`Built`)按同一套转成 `SavedDataType`:`CODEC` 包内可见(单测直接对它往返)。
  `PermissionStore` 每主人一个文件,`SavedDataType` 的相等只看 id,所以每次 `of()` 现造一个带 id 的类型即可,存储层按 id 缓存。
  `Built.CODEC` 是 public:GameTest 用它做"重启读回"的往返(`save/load` 重写没了)。
- `CompoundTag/ListTag` 读取 Optional 化:`Cells.load`、`BlueprintStore` 的实体坐标(`getDoubleOr`)。

**同伴身体 / 引擎**
- **客户端加载门**:`Player.hasClientLoaded()` 为假时服务端不收挖/放/用的动作包(`ServerGamePacketListenerImpl.handle*` 都挂着它),
  受击也免疫(`isInvulnerableTo`);60 刻后才自己放宽。真客户端在收到 `LEVEL_CHUNKS_LOAD_START` 之后加载完才发
  `ServerboundPlayerLoadedPacket`。同伴没有客户端:`FakeClient` 看见 `LEVEL_CHUNKS_LOAD_START`(进服、换维度、重生都发)就在下一刻
  `handleAcceptPlayerLoad` 回执。不补这一条,新召唤出来 3 秒内的挖/放/用都被静默拒掉(GameTest 里是"服务器没让挖掉这一格")。
- mixin 靶点变化(都是运行期才炸,datagen 一跑就现形;**客户端 mixin 的靶点 datagen 不加载,要对 javap**):
  `ServerCommonPacketListenerImpl.send(Packet, PacketSendListener)` → `send(Packet, ChannelFutureListener)`;
  `ItemEntity.hurt` → `hurtServer(ServerLevel, DamageSource, float)`;
  `CraftingMenu.slotChangedCraftingGrid` 的 `Level` 参数 → `ServerLevel`。
- `Boat` → `AbstractBoat`;木船按 `EntityType.OAK_BOAT` 等各自成型,测试里 `new Boat(...)` → `create(level, STRUCTURE)` + `setInitialPos`。
- `RecipeHolder.id()` 现在是 `ResourceKey<Recipe<?>>`:要字符串用 `id().location().toString()`(**能编译但串变成 `ResourceKey[…]`**);
  `byKey(ResourceLocation)` → `byKey(ResourceKey.create(Registries.RECIPE, id))`;按类型取配方 → `recipeAccess().getRecipes()` 全量 + instanceof。
- 指令回显:`ServerPlayer` 不再是 `CommandSource`,`shouldInformAdmins()` 没处问(它的指令源恒为 true),`Echo` 直接给 true。
- `MinecraftServer.tell(TickTask)` → `schedule`;`Item.getDescription()` → `getName()`;`OwnableEntity.getOwnerUUID()` → `getOwnerReference().getUUID()`;
  `BuiltInRegistries.X.get(ResourceLocation)` → `getValue`;`registry.asLookup()` 删掉(Registry 本身就是 RegistryLookup);
  `Level.isDay/isNight` → `isBrightOutside/isDarkOutside`(语义同);`Direction.getNearest(float…)` → `getApproximateNearest`。

**寻路模块(只依赖原版)**
- `getMinBuildHeight/getMaxBuildHeight` → `getMinY/getMaxY`(**getMaxY 含端**,`y < max` 改 `y <= getMaxY()`,"开区间上界"要 `getMaxY()+1`);
  实现 `LevelHeightAccessor` 的自家视图覆写 `getMinY`。
- `Hotbar`:按格取物的包(`ServerboundPickItemPacket`)没了,服务端处理就是 `Inventory.pickSlot(slot)` + 回报选中格 + 广播背包;
  创造凭空取一叠 `setPickedItem` → `addAndPickItem`。`InteractionResult.shouldSwing` → `Success.swingSource() == SERVER`。
- `EntityCollisionContext` 构造器多了 `placement` 布尔。
- 玩家 `serverLevel()` → `level()`;`MobEffects.DIG_SLOWDOWN` → `MINING_FATIGUE`;`registryOrThrow` → `lookupOrThrow`。

**GameTest(整套重做,见 `:gametest` 模块)**
- 1.21.5 删了注解入口。新增不发布的模块 `:gametest`:`@NumenGameTestHolder/@NumenTest/@NumenBeforeBatch/@NumenAfterBatch/@NumenGameTestGenerator`
  与登记处 `NumenGameTests`(`@EventBusSubscriber` 自动挂在模组 numen 上,发行 jar 里没有它——用例类上的注解在那里是读不到的名字,不起作用)。
  登记处从 `ModList.get().getAllScanData()` 里按 `@NumenGameTestHolder` 找类(core、寻路模块、评测三处的源码集都在同一个 numen 模组里),
  按 `neoforge.enabledGameTestNamespaces` 过滤命名空间(NeoForge 1.21.5 起不再认它,运行配置照旧给,这里按原口径过滤),先初始化类(静态块里登记夹具)再读方法。
  用例编号 `<命名空间>:<类名>.<方法名>`(小写);批次 = 测试环境 `NumenTestEnvironment(batch)`,`setup/teardown` 回到名册里的 `@NumenBeforeBatch/@NumenAfterBatch`。
- 不能用原版 `FunctionGameTestInstance`(`TEST_FUNCTION` 注册表在 BuiltInRegistries 引导时就冻结);`TEST_INSTANCE`、`TEST_ENVIRONMENT` 会同步给客户端,
  所以自带的实例类型/环境类型要经 `RegisterEvent` 登记进 `TEST_INSTANCE_TYPE`/`TEST_ENVIRONMENT_DEFINITION_TYPE`。
- `GameTestHelper` 断言/失败改 `Component`:`GameTestKit` 里三个薄包装(`assertTrue/assertFalse/fail(helper, …, String)`);
  `GameTestAssertException` 构造要 `(Component, tick)`;`StructureUtils.testStructuresDir` 是 `Path`。
- 结构模板:内容原点基准变了(1.21.4 那条),三块地板模板(floor16/20/52)按"底部垫一层"重做(size y+1、data 整体 y+1、原 y0 层复刻成新底层);
  寻路模块的三块模板是空的,不用垫。
- NeoForge 21.8 的 `ConfigSync.syncPendingConfigs` 每刻遍历玩家列表,**精确是 `ServerPlayer` 类**、有配置频道、又不是内存连接、又没走过配置阶段的会抛
  `configsToSync should contain an entry…`:扮演主人的夹具连接(GameTestKit.OwnerLine、评测的 OwnerConnection)覆写 `isMemoryConnection()` 为真。
- 玩家继承了 `WaypointTransmitter.Connection` 这个成员接口,会盖过 `import net.minecraft.network.Connection`:`TestBody` 里嵌套的 `extends Connection` 要写全名。
- 实体迁包:`Sheep` → `animal.sheep`,`Wolf` → `animal.wolf`(1.21.5 就搬了,不是 1.21.11);猪的鞍 `equipSaddle` → `setItemSlot(EquipmentSlot.SADDLE, …)`;
  `changeDimension(DimensionTransition)` → `teleport(TeleportTransition)`;`isInvulnerableTo(source)` → `isInvulnerableTo(level, source)`;
  狼的主人 `setOwnerUUID` → `setOwnerReference(new EntityReference<>(uuid))`;`ServerPlayer.teleportTo(level, x, y, z, yaw, pitch)` → 带 `Set<Relative>` 与 `boolean` 的八参版。
- 单测夹具:冻结后的注册表拒绝直写标签,标签一律 `prepareTagReload(new TagLoader.LoadResult<>(key, tags)).apply()`(五处夹具);
  `Inventory(player, equipment)` 要把同一个 `EntityEquipment` 种进 `LivingEntity.equipment`。
- 环境规则:见下文 1.21.10 档的"GameTest 环境规则"——随机刻停摆、火不 tick 由 `NumenTestEnvironment.setup` 统一定,不在批次方法里各写。

**联动(plugins/)**
- 本代只移植 Curios(NeoForge,`curios-neoforge:12.0.0+1.21.8`,maven.theillusivec4.top,公开 API 与 1.21.1 的 9.5.1 同形,代码零改动)。
  不移植的:TLM(Modrinth 上只有 1.21/1.21.1 的 NeoForge 版)、YSM(Modrinth 只有 1.21/1.21.1 与 26.1.2)、
  森罗物语厨房(CurseForge 上只有 1.21.1 的 NeoForge 版)、FTB Quests(maven.ftb.dev 只有 2101.x、2111.x 与 26.1.2.x,没有 1.21.8 的构建)。

### 2026-11 对齐 1.21.1 @ 352c6fec8(0.1.5:19c8710b0 加对齐期间的十二个通用修复)❗

方法同上一轮:1.21.1 整树替换,目标分支独有文件逐个清点删除(1396 个旧路径文件 + 101 个不带的联动文件),再把上一轮的平台适配
按文件三方合并进新树(`7a3d3e251 → bd3b7036c` 的 diff,107 个文件自动合入、其余逐个手工),然后编译驱动补齐。目录与包名在两轮之间整体改过
(`api/` → `numen-api/`、`core/` → `numen/`、包移到 `com.dwinovo.numen.api.*`),三方合并按文件名找新家。下面只记这一轮**新**碰到的。

**批量替换的陷阱(又踩了一次)**:按目录名排除 `build` 会漏掉 `numen/build/`、`task/build/` 两个源码包;Git Bash 里以 `/` 开头的参数会被改写成
Windows 路径,拿来当路径过滤条件时一个文件都匹配不上。

**GameTest:套件机制在数据驱动框架里等价落地**
- 1.21.1 的用例只挂原版注解,经 `GameTestSuites` 一个 `@GameTestGenerator` 交给原版。1.21.5 起没有注解入口,也没有生成器:
  Numen API 的夹具里照原样立了同名注解 `GameTest`/`BeforeBatch`/`AfterBatch`/`GameTestGenerator` 与记录 `TestFunction`(包
  `com.dwinovo.numen.api.gametest`),用例类只换 import。断言的 String 口径经 `Checks`(`assertTrue/assertFalse/fail(helper, …)`、
  `failure(String)`),全树一处。
- 登记:`GameTestRegistrationMixin` 挂在 `RegistryDataLoader.loadContentsFromManager` 末尾,原版读完测试环境、测试实例两个数据注册表
  (还没冻结)时交给 `GameTestRegistration`:每个批次一个环境(`numen_api_gametest:batch/<批次>`)、每条用例一个实例(`<套件>:<类>.<方法>`,
  铺份写成 `/份号`)。两个加载器同一条路,加载器只登记两个类型(NeoForge 经 `RegisterEvent`,Fabric 在 `ModInitializer` 里直接 `Registry.register`)。
  Fabric 的 `fabric-gametest` 入口不再用(它要 Fabric 自己的注解)。
- 批次:原版按测试环境分组用的是以环境 Holder 为键的散列表(身份散列,每次开服先后不同),`GameTestBatchOrderMixin` 改成按登记先后分组。
  测试服只跑我们的用例(`GameTestSelectionMixin`):原版数据包自带的 `minecraft:always_pass` 不跑、不计数。
- 世界规则:`NumenTestEnvironment.setup` 在批前方法之前定下原 `GameTestServer` 的五样(不刷怪、不换天气、随机刻 0、火不 tick、晴天),
  布尔与数值规则都经 `GameTestWorld`(Paper 的签名不同,`setRule(level, IntegerValue, int)` 是这一轮加的)。
- 可复现那一套的落点:起点 `startTests` 的局部变量照旧;开批对齐照旧挂 `GameTestRunner.runBatch`(环境的 setup 在里面,一并推迟到整拍);
  同刻回调顺序照旧挂 `GameTestInfo.runAtTickTimeMap/tickInternal`;区块刻洗牌在 `ServerChunkCache.tickChunks(ProfilerFiller, long)`;
  场地强制加载搬到了 `TestInstanceBlockEntity.forceLoadChunks`(`StructureChunksMixin` 跟过去);`GameTestServer.create` 的参数变成
  `(Thread, LevelStorageAccess, PackRepository, Optional<String>, boolean)`。收场汇报仍是 `GlobalTestReporter`,用例全名经 `NumenTestInstance.name(info)`。
- 加载器自己的用例(NeoForge 联网协商那一条)经 `GameTestSuites.loaderTests` 交进来,和 1.21.1 一样不属于任何套件、每次都跑。
- 场地坐标:1.21.1 的 `GameTestHelper.absolutePos` 以结构方块那一格(内容原点正下方)为基准,1.21.4 起以内容原点为基准。上一轮把三块地板模板
  底部垫了一层复刻的地板,坐标对上了,世界却不一样:地板下面原本是一层空气再往下才是石头,垫了之后是两层地板(权限用例里"挖地板绕过墙"
  会多问一张卡)。现在模板与 1.21.1 一字不差,`GameTestHelperOriginMixin` 把 `absolutePos/relativePos/absoluteVec/relativeVec` 的基准点
  放回内容原点的正下方,摆出来的场地与 1.21.1 逐格相同(空模板的寻路场地同理,不再多出一层空气)。
- 场地四周:1.21.5 起原版铺场地不再清地(`clearSpaceForStructure` 只在下一批开始时清上一批),围栏还在内容底下垫一整层屏障。
  `StructureSiteClearMixin` 在放测试方块之前照 1.21.1 清一遍地(内容下一层空气、再往下石头、往上全空,清地范围盖住测试方块那一格);
  `StructureBoundaryMixin` 罩着 `TestInstanceBlockEntity.processStructureBoundary`,只围四面墙(从内容最低一层起)和没有天空时的顶,
  不垫底,围上与拆掉走同一圈。没有这两处,"挖地板钻到墙下"的权限用例与"飞过峡谷"的寻路用例都会在屏障板上走过去。
- 用例计时:原版用例计时器从不启动(`getRunTime` 总是 0),`TestTicks` 在函数体前自记墙钟,`[numen-gametest-ticks]` 的毫秒才是实数。
- 区块落定(1.21.1 `352c6fec8`):`Util.ioPool()/backgroundExecutor()` 交出的是 `TracingExecutor`,池在 `service()` 里,先取再强转。
- Paper 的测试插件没有 Mixin:插件起来时读出用例,开跑前把两个已冻结的注册表解冻、经同一个 `GameTestRegistration.register` 登记、再冻上
  (原版放场地按测试实例的注册名回表取结构,用例必须在表里)。

**依赖撞包**:MC 1.21.6+ 客户端依赖把 jackson-annotations、Nimbus 一族钉成 strictly 的旧版;mcp 模块的官方 MCP SDK 要新版。由 mcp 产物重定位解决
(1.21.1 的 `2ca1f9d5f`、`972587017` 两个提交,cherry-pick 进本分支)。对齐期间落到 1.21.1 的十二个通用修复按原顺序逐个 cherry-pick,各自一个提交。

**其余新碰到的 API 差异**
- `ElytraItem` 没了:滑翔物看 `LivingEntity.canGlideUsing(stack, EquipmentSlot.CHEST)`(带滑翔组件、没坏),"没穿"看 `DataComponents.GLIDER`。
- 中键取物:1.21.2 起客户端的中键包不再指名背包的格。`ClientPackets.pickItem/pickNew` 照原版找到格以后做的那几步写(选合适的快捷栏格、
  对换或放一叠、回报选中格、广播背包),不调 `Inventory.pickSlot/addAndPickItem`:Paper 给它们加了参数,两边签名不同。与 1.21.4/1.21.5 同一写法。
- 客户端加载回执(1.21.2 起 `hasClientLoaded` 为假时服务端不收动作包、玩家不受伤):`FakeClient` 收到 `LEVEL_CHUNKS_LOAD_START` 记下,
  加入与重生流程一走完当场报(重生等连接换上新身体),换维度这类流程中途的晚一刻回;寻路夹具 `TestBody` 建好连接当场报。
- 载具与自身移动的权威:服务端只看 `isClientAuthoritative()`,`NumenPlayer` 只覆写它(返回 false,同原版 GameTest 的模拟玩家)。
- 寻路的运动学 `Kinematics.settle`:1.21.6 起玩家的水平速度按整体归零(`horizontalDistanceSqr() < 9.0E-6`),竖直单独比。
- 弓:松手与箭速按原版自己的拉弓计数 `getTicksUsingItem()`,不另数一份(1.21.8 上自数的比原版多一刻,射出的箭速度对不上)。
- `ServerPlayer.server` 私有:一律 `level().getServer()`;`getRespawnPosition()` → `getRespawnConfig()`;`getPermissionLevel()` 是 public。
- `DirectionProperty` → `EnumProperty<Direction>`;`registry.getTag` → `get(TagKey)`;`Registry.get(id)` 返回 `Optional<Reference>`,取值用 `getValue(id)`;
  `Structure.generate` 首参要 `Holder<Structure>` 与维度键。
- 配方:输入清单在 `placementInfo().ingredients()`;熔炼/切石 `input()`、`cookingTime()`;锻造的模板与附加料是 `Optional<Ingredient>`;
  任意类型(含模组机器)配方给配方书的产出取 `display()` 里的产出格(`RecipeProbe.displayedResult`);切石机菜单 `getVisibleRecipes().entries()`。
- `ClickEvent`/`HoverEvent` 是按动作的记录(`RunCommand`、`CopyToClipboard`、`OpenUrl(URI)`、`ShowText`);`Component.Serializer` 没了,
  语言文件里写成组件的译文用 `ComponentSerialization.CODEC` 解(`ServerLang`)。
- 存档:`PermissionStore` 读档前的旧行改写搬进存档编解码 `STORED`(读之前改写、改写过就标脏),`NavClock` 用 `Codec.unit`(不写盘)。
- NeoForge:`AddReloadListenerEvent` → `AddServerReloadListenersEvent`(登记要带键);`RenderLevelStageEvent` 按阶段分子事件;
  数据生成的 run 类型是 `clientData`,订阅 `GatherDataEvent.Client`。
- Paper 1.21.8:`setDifficulty` 多一个指令源参数;`CraftScheduler.mainThreadHeartbeat()` 无参;实体不再是 `CommandSource`(Bukkit 发令者取
  `getBukkitEntity()`);`DiscardedPayload` 带 `byte[]`。paperweight-userdev 升到 2.0.0-beta.22(1.7.x 读不了 1.21.8 的开发包),链接检查与 javadoc 认开发包里的
  `mappedServerJar.jar`(1.21.5 的 `2f0ca3da6`)。
- 面板快捷键默认 N(1.21.6 起 G 被原版 Quick Actions 占了)。

**联动**:带 Curios(`12.0.0+1.21.8`)、JEI(`24.2.0.6`,接口改名 `IRecipeType`)、Litematica(`0.23.7`/MaLiLib `0.25.7`,坐标按 MC 版本,
文件接口从 `File` 换成 `Path`)。不带 YSM、车万女仆、森罗物语厨房(都没有 1.21.8 构建)、FTB Quests(只有 2101/2111/26.x)、KubeJS(只有 2101 与 26.x)。

## 1.21.8 → 1.21.10 ✓（已验证:对齐 1.21.1@7a3d3e251 后的并仓树双 loader 编译 + 出包 + datagen + 1799 单测 + 567 条游戏内用例 ×3 + 评测自检;跳过 1.21.9）

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


### 2026-10 对齐 1.21.1 @ 7a3d3e251(0.1.3 之后约 900 个提交)追加 ❗

方法同 1.21.8 那一档:树级三方合并(基 = `origin/1.21.8`,ours = `align-1.21.8`,theirs = `origin/1.21.10`),把旧 1.21.10 分支
相对 1.21.8 的平台适配(输入事件化、authlib 9、提交式管线、Transfer 重写、召唤 mixin 退役)重放到对齐后的树上,冲突手工收,
新代码里新踩到的 API 差异靠编译 + datagen + GameTest 找。下面只记**新**碰到的。

**合并陷阱**
- 对齐把旧单体 `CompanionGameTests` 拆成按领域的十几份。合并算法把它当成 `BuildGameTests` 的改名对象,吐出一份夹着两边内容的怪文件。
  处理:整份丢掉,取 `align-1.21.8` 的原样,再把 origin 1.21.8→1.21.10 对旧单体的四处改动(`startRiding` 三参、`GameProfile.properties()`)
  重放到拆分后的文件上。合并完用 `git diff <基> <theirs> --stat` 的清单逐文件对账,别只信没冲突的那部分。

**客户端输入(新代码里的界面)**
- `NumenScreen` 的 `keyPressed/charTyped/mouseClicked/mouseDragged/mouseReleased` 全套换成 `KeyEvent/CharacterEvent/MouseButtonEvent`(`mouseScrolled` 不变);
  `mouseClickedInner` 等内部分发跟着收事件。`CompanionChatScreen` 的共识卡点击同理。
- Fabric `ScreenMouseEvents.allowMouseClick` 的回调从 `(screen, x, y, button)` 变成 `(screen, MouseButtonEvent)`。
- 世界渲染事件不再带相机:`PathDebugRenderer` 与 `ConsentOutlines` 一律走 `gameRenderer.getMainCamera()`;`ShapeRenderer.renderLineBox` 收 `PoseStack.Pose`。
- 头顶气泡挂 `LivingEntityRenderer.submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)` 的 **HEAD**(原因同 1.21.8:插件取消渲染事件时尾部不跑);
  玩家状态类是 `AvatarRenderState`;几何经 `submitCustomGeometry` 交出,回调延后执行,所以参与计算的局部量全是 final,动画状态的推进放在提交之前。
- `MixinGuiGraphics` 的三个提交口(`submitColoredRectangle`/`drawString(…FormattedCharSequence…boolean)`/`submitBlit`)签名在 1.21.8→1.21.10 逐字不变,
  ordinal 照旧;客户端 mixin 的靶点(`KeyboardInput.tick`/`ClientInput.moveVector`、`ChatComponent.allMessages/refreshTrimmedMessages`、
  `ToastManager.occupiedSlots`)对 1.21.10 反编译源核过,都在。

**同伴身体 / 引擎**
- authlib 9:`GameProfile` 是 record,`getName()/getId()/getProperties()` → `name()/id()/properties()`;带皮肤属性的档案只能在构造时给全。
- `Entity.getServer()` 没了:实体一律 `level().getServer()`(`CommandSourceStack`/`ServerLevel`/事件上的 `getServer()` 还在,别误改)。
- `MinecraftServer.getProfileCache()` → `services().nameToIdCache()`(`UserNameToIdResolver.get(UUID)` 给 `NameAndId`);
  `ServerOpListEntry`、`ops.remove` 都要 `NameAndId`(`new NameAndId(gameProfile)`)。
- `EntityReference` 构造私有:`new EntityReference<>(uuid)` → `EntityReference.of(uuid)`。
- 召唤 join:`placeNewPlayer` 不再阻塞等区块、不挪出生点、不碰 `.dat`(整段搬去配置期的 `PrepareSpawnTask`,只有真实登录走)。
  所以 1.21.8 的 `intendedSpawnPos` 与 `MixinPlayerListCompanionSpawn` 整个退役;`CompanionFactory` 里那次显式读档是唯一一次;
  读档 `PlayerList.load(player, reporter)` → `loadPlayerData(NameAndId)` 拿 `CompoundTag` 自己包 `TagValueInput`。
- `NumenPlayer.startRiding` 多了 `emitEvents` 第三参(覆写与全部调用点);`TicketType(timeout, flags)`(`FLAG_LOADING | FLAG_SIMULATION`)。
- `EntityCollisionContext` 构造器第五参从 `Predicate<FluidState>` 变成 `boolean alwaysCollideWithFluid`(寻路的 `Boxes.bodyAt`)。
- 单测夹具:`LevelChunkSection` 的方块容器 `new PalettedContainer<>(默认态, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY))`。

**NeoForge**
- Transfer 重写:`IItemHandler/IFluidHandler/IEnergyStorage` → `ResourceHandler<ItemResource/FluidResource>` + `EnergyHandler`;
  能力键 `Capabilities.Item/Fluid/Energy.BLOCK`;能量没有 `canReceive/canExtract`,在永不提交的 `Transaction` 里模拟灌入/抽出探方向。
- FML 10 的 `IModFile` 没有 `findResource`:`ModJar.find` 改走 `getContents().getContentRoots()`,逐个根找这条路径(开发运行时类与资源分在几个根)。
- 键位分类:`KeyMapping.Category.register(id)`,语言键 `key.category.numen_api.companions` 由 datagen 语言源与注册两头同改。

**联动(plugins/)**
- 本代只移植 Curios(NeoForge,`curios-neoforge:13.0.0+1.21.10`,maven.theillusivec4.top,代码零改动)。
  TLM、YSM、森罗物语厨房、FTB Quests 在 1.21.10 上都没有可取的构建(查法与 1.21.8 档相同)。

**GameTest 环境规则**
- 1.21.5 之前 `GameTestServer` 自带一套规则(不刷怪、不换天气、随机刻 0、火不 tick);数据驱动之后原版的默认环境是空的。
  `NumenTestEnvironment.setup` 在批次方法之前补两条会在判据背后改世界的:随机刻停摆(耕地湿度、屋顶下的草退化)、`doFireTick` 关
  (`fluidgametests.passes_cactus_fire_berries_and_magma_unhurt` 里下界岩上的火每三十来刻老化一次,红在"实际账与世界不符")。
  规则只在这一处定,`settleWorld`/`Worlds.settle` 里不再写随机刻。
- 没把"不换天气"补进环境:各批次的 `settleWorld` 已按用例需要重定天气与刷怪。


### 2026-11 对齐 1.21.1 @ 352c6fec8(0.1.5)❗

方法:以 `align-1.21.8`(已对齐 352c6fec8、带 1.21.8 平台适配)整棵树为底,旧路径的残留文件逐个删,再把 origin 1.21.8→1.21.10 的平台适配
按文件三方合并进来(基 = `origin/1.21.8`,theirs = `origin/1.21.10`;16 个文件自动合入,18 个冲突的取本树原样后照旧差异手工重放),
其余靠编译、单测、GameTest 找。1.21.8 那一节的 GameTest 夹具、可复现机制、统一写法在本档原样成立,下面只记 1.21.10 新碰到的。

**客户端**
- 输入事件化:`NumenScreen`、`CompanionChatScreen` 的 `keyPressed/charTyped/mouseClicked/mouseDragged/mouseReleased` 收 `KeyEvent/CharacterEvent/MouseButtonEvent`,
  方法开头取局部量,方法体与内部分发(`mouseClickedInner`)不动,`super` 透传事件;`CompanionWheelScreen` 的物理按键采样收 `Window`。
  Fabric `ScreenMouseEvents.allowMouseClick` 的回调是 `(screen, click)`。
- 键位分类 `KeyMapping.Category.register(numen_api:companions)`,语言键 `key.category.numen_api.companions`(datagen 语言源同改)。`PlayerSkin` 迁到 `world.entity.player`。
- 世界渲染事件不带相机:同意卡描边与寻路调试线的四个挂点(两个加载器各两处)传 `gameRenderer.getMainCamera()`;Fabric 的事件搬到
  `rendering.v1.world.WorldRenderEvents`,挂 `BEFORE_DEBUG_RENDER`(原版调试线的绘制点),`matrices()`;`ShapeRenderer.renderLineBox` 收 `PoseStack.Pose`。
- 头顶气泡走提交式管线:`MixinLivingEntityRenderer` 挂 `submit(状态, PoseStack, SubmitNodeCollector, CameraRenderState)` 的 HEAD,
  玩家状态类 `AvatarRenderState`;`SpeechBubbleRenderer` 经 `submitCustomGeometry`/`submitText` 交出,朝向取 `CameraRenderState.orientation`。

**同伴身体 / 引擎**
- authlib 9:`GameProfile` 是 record(`name()/id()/properties()`),属性在构造时给全(`PropertyMap`)。原地换皮肤
  (`Companions.refreshSkin`)照原档案抄一份、换掉 textures,整份换到身体上——新服务 `IPlayerProfiles`:模组经 `PlayerProfileAccessor`
  (`@Mutable @Accessor` 写 `Player.gameProfile`),Paper 按 Mojang 名反射同一个字段。
- 身份 `NameAndId`:op 名单、`deop`、`isSingleplayerOwner`、`getProfilePermissions` 都收它;`NumenPlayer.profileOf` 返回 `NameAndId`
  (在线的取档案,离线的取 `services().nameToIdCache()`)。`Entity.getServer()` 没了,实体一律 `level().getServer()`。
- 召唤:`placeNewPlayer` 不再阻塞等区块、不挪出生点、不碰 `.dat`(都在只有真实登录走的 `PrepareSpawnTask` 里),
  1.21.8 的 `MixinPlayerListCompanionSpawn` 与 `NumenPlayer.intendedSpawnPos` 退役;读档 `loadPlayerData(NameAndId)` 拿 `CompoundTag` 自己包 `TagValueInput`。
  给新玩家定朝向的那一步也在 `PrepareSpawnTask` 里,原版构造生物时掷的是随机朝向(`Math.random`):没有存档的新身体朝向取世界出生点的朝向
  (`getRespawnData()` 的 yaw/pitch),有存档的照存档。不定的话她起飞前转头的刻数每次开服不同,两次开服比对刻数时鞘翅用例差一刻。
- `startRiding(载具, force, emitEvents)`;`EntityReference.of(uuid)`;击退包 `ClientboundSetEntityMotionPacket.getMovement()`;
  `EntityCollisionContext` 第五参是 `boolean`(寻路 `Boxes.bodyAt`);重生点 `RespawnConfig(RespawnData(GlobalPos, yaw, pitch), forced)`、
  世界出生点 `getRespawnData()/setRespawnData`;PvP 是游戏规则 `pvp`;`Blocks.CHAIN` 改名 `IRON_CHAIN`;寻路评测的方块段用 `Strategy.createForBlockStates/createForBiomes`。

**NeoForge**
- Transfer 重写:`NeoForgeBlockCapabilityReader` 读 `ResourceHandler<ItemResource/FluidResource>`、`EnergyHandler`(`Capabilities.Item/Fluid/Energy.BLOCK`),
  能量方向在永不提交的 `Transaction` 里模拟灌入、抽出来探。`FMLLoader.getCurrent().isProduction()`。
- FML 10 的 `IModFile` 没有 `findResource`:内容根取 `getContents().getContentRoots()`,成品里内容根是 jar 文件本身,挂成 zip 文件系统再 resolve。
  这件事只在 Numen API 的平台口 `NeoForgePlatformHelper.modResource` 做一次,Numen 的 NeoForge `ModJar.find` 直接问它。成品放进真专用服核实过。

**GameTest**
- 原版 1.21.10 的 `clearSpaceForStructure` 只清给它的盒子、不再外扩,放结构前也自己清一遍内容盒。`StructureSiteClearMixin` 照 1.21.1 自己清外扩的那一圈
  (左右 2 到 3 格、下 3 格、上 20 格,内容下一层空气、再往下石头),每一格经原版私有的 `clearBlock`(`StructureUtilsInvoker`)。
  没有这一圈,地板底下是平坦世界的地层:看周围(`look_around`)的图把墙画成坑,船上的同伴沉到地板下面。

**依赖**:Gson 2.11.0、slf4j 2.0.16(本版游戏自带的版本,编译与单测都对着它)。端到端的协议客户端用 MCProtocolLib 1.21.9 的快照(固定到时间戳,
与 1.21.10 同一个协议号),它带的 Adventure 4.25 认得 1.21.5 起的组件形状,不再另提版本。

**联动**:带 Curios(`13.0.0+1.21.10`)、JEI(`26.3.0.31`)、Litematica(`0.24.9`/MaLiLib `0.26.7`),代码不用改。不带 YSM、车万女仆、森罗物语厨房、
FTB Quests、KubeJS(1.21.10 都没有可用构建)。Paper 开发包 `1.21.10-R0.1-SNAPSHOT`。

## 1.21.10 → 1.21.11 ✓（已验证:对齐 1.21.1@7a3d3e251 后的并仓树双 loader 编译 + 出包 + datagen + 1799 单测 + 567 条游戏内用例 ×3 + 评测自检）

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

### 游戏规则迁包 + 规则对象化 ❗（gametest:NumenTestEnvironment、core/pathing/bench 的 GameTest 夹具）
```java
net.minecraft.world.level.GameRules → net.minecraft.world.level.gamerules.GameRules
level.getGameRules().getRule(RULE_RANDOMTICKING).set(0, server)
    → level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, server)   // 取值侧 getInt(RULE) → get(RULE)
RULE_DOMOBSPAWNING → SPAWN_MOBS      RULE_DAYLIGHT → ADVANCE_TIME      RULE_WEATHER_CYCLE → ADVANCE_WEATHER
RULE_NATURAL_REGENERATION → NATURAL_HEALTH_REGENERATION
RULE_DOFIRETICK(布尔) → FIRE_SPREAD_RADIUS_AROUND_PLAYER(整数,-1 处处蔓延,0 谁身边都不蔓延)
```
随机刻不停摆就重演 1.21.8 的坑(草方块被随机刻退化);火不关,`passes_cactus_fire_berries_and_magma_unhurt` 里的火会老化而红。
两条都由 `NumenTestEnvironment.setup` 统一定(见上文 1.21.10 档的"GameTest 环境规则")。

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
  故只剩**每次出生 3 秒无敌窗口**;本树由 `FakeClient` 在看见 `LEVEL_CHUNKS_LOAD_START` 时替客户端补
  `handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket())` 上报(进服、换维度、重生都走它,见 1.21.8 档),窗口归零。
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
- 本树没有 AT/AW;mixin 全家靶点在(气泡 submit 四参、载具 isLocalInstanceAuthoritative 双分支、`MixinGuiGraphics` 三个提交口——
  `submitBlit` 多了一个 `GpuSampler` 形参但 int 的序号不变——与 1.21.10 一致)。
- FMLLoader.getCurrent() 中转在 21.11.42 仍在。
- 夹具 .snbt 仍 DataVersion 3955,DFU 正向升到本代,不硬盖。
- HitResult.getLocation()(Vec3)与大改名无关,别误替换。

### 2026-10 对齐 1.21.1 @ 7a3d3e251(0.1.3 之后约 900 个提交)追加 ❗

方法同 1.21.8、1.21.10 两档:树落底在上一档对齐完的 `align-1.21.10`,把旧 1.21.11 分支相对 1.21.10 的适配重放上去。
这一档 83 个文件里绝大多数是机械改名,**不走三方合并**(被对齐删掉、搬走的文件会在合并里复活,冲突解到"取我方"再逐处补改名也一样麻烦),
改成"取上一档整树 + 全局机械替换 + 编译驱动补漏"。机械替换的陷阱:
- 全局把 `.location()` 换成 `.identifier()` 会咬到别的类的同名访问器(`SkillInfo.location()`、`TagKey.location()` 都还叫 location,编译才发现);
  替换完逐个看 diff,别信"能编译就对"。
- 实体类包重组按 1.21.11 反编译源逐个类名找新位置,不手写:`animal.Pig/Cow/Chicken` → `animal.pig/cow/chicken.*`、
  `npc.AbstractVillager` → `npc.villager.*`、`projectile.Snowball` → `projectile.throwableitemprojectile.*`、`vehicle.Boat/AbstractBoat` → `vehicle.boat.*`、
  `monster.Zombie/ZombifiedPiglin` → `monster.zombie.*`、`monster.Spider` → `monster.spider.*`。

**新 1.21.1 功能面新踩到的**
- 世界线框:`ShapeRenderer.renderLineBox` 没了。新增 `client/debug/LineBox`(12 条棱各走一条线段,法线取线段方向),寻路调试覆盖层与征询轮廓共用;
  `RenderType` → `rendertype.RenderTypes`(`lines()`/`text(...)`)。
- 聊天里的链接:`Screen.handleComponentClicked(Style)` 与 `StringSplitter.componentStyleAtWidth` 都没了。找指针下的样式改交给原版的
  `ActiveTextCollector.ClickableStyleFinder`(把这一行文字按它画的位置喂进去);点开按原版 `Screen.clickUrlAction` 的规则自己做(它是 protected static):
  看 `chatLinks` 选项,开了 `chatLinksPrompt` 就走 `ConfirmLinkScreen.confirmLinkNow(原屏, uri)`(确认完回到这个面板),否则直接 `openUri`。
- 命令源权限:`.requires(source -> source.hasPermission(2))` → `.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))`;
  `new CommandSourceStack(..., int level, ...)` → 权限集 `LevelBasedPermissionSet.OWNER/GAMEMASTER/forLevel(PermissionLevel.byId(n))`;
  `ServerOpListEntry(…, int, bool)`、`withPermission(int)` 同理。
- 方块倒水蒸发:除 MLGChain 外,**寻路模块也用**——`WorldView.ultraWarm()` 改名 `waterEvaporates(BlockPos)`。`WATER_EVAPORATES` 是**按位置**的环境属性,
  `getDimensionValue` 会抛 `Position must always be provided for positional attribute`(第一次跑 GameTest 时 `numen.route.plan` 整条红才发现)。
  活世界逐点问;搜索用的快照在**主线程**捕获时按快照中心采一次、整片共用(工作线程取不了环境属性;原版只有维度类型会把它设成真)。
- `Player.canInteractWithBlock/Entity` → `isWithinBlockInteractionRange/isWithinEntityInteractionRange`(寻路的 `PlayerHands`)。
- `ServerOpListEntry`/`MinecraftServer.getProfileCache` 在 1.21.10 档已处理;本档 `Curios` 14.0.0+1.21.11 照编(API 有几处标了 `[removal]`:
  `IItemHandler.isItemValid/extractItem`、`CuriosApi.MODID/getSlots/getItemStackSlots`,下一档要换新接口)。
- 引擎里 `FakeClient` 已有加载回执,**不要**再采纳旧 1.21.11 分支在 `CompanionFactory` 里加的那一句(单一出处)。

**联动(plugins/)**
- 本代只移植 Curios(`curios-neoforge:14.0.0+1.21.11`)。TLM、YSM、森罗物语厨房、FTB Quests 在 1.21.11 上没有可取的构建
  (Modrinth/maven.ftb.dev 查法同前两档;FTB Quests 的 maven 只有 2101.x、2111.x 与 26.1.2.x)。

**MixinExtras**:NeoForge 日志 `MixinExtrasServiceImpl(version=0.5.3)`;Fabric 的 datagen 运行日志 `MixinExtras|Service … version=0.5.0`
(加载器 0.18.1 自带 `mixinextras-fabric-0.5.0`),均 ≥0.4,不需要自带。

### 2026-11 对齐 1.21.1 @ 352c6fec8(0.1.5)❗

方法:以 `align-1.21.10`(已对齐 352c6fec8、带 1.21.10 平台适配)整棵树为底,旧路径的残留文件逐个删,再把 1.21.10→1.21.11 的适配重放上去:
机械改名用脚本(大小写敏感、只改认得的写法),其余靠编译、单测、GameTest 找。1.21.8、1.21.10 两档的 GameTest 夹具、可复现机制、统一写法
在本档原样成立,下面只记 1.21.11 新碰到的。

**改名与迁包**(机械替换,上一节的陷阱照旧)
- `ResourceLocation` → `Identifier`、`ResourceKey.location()` → `identifier()`、`net.minecraft.Util` → `net.minecraft.util.Util`、
  `RenderType` → `rendertype.RenderTypes`、`GameRules` → `gamerules.GameRules`、`ResourceLocationArgument` → `IdentifierArgument`、
  `Codec.unit` → `MapCodec.unitCodec`(寻路的 `NavClock`)。
- 实体类包:`animal.pig/cow/chicken`、`npc.villager`(`AbstractVillager`、`VillagerProfession`、`VillagerDataHolder`)、
  `projectile.throwableitemprojectile.Snowball`、`vehicle.boat`、`vehicle.minecart`、`monster.zombie`、`monster.spider`。

**世界线框**:`ShapeRenderer` 只剩 `renderShape(PoseStack, VertexConsumer, VoxelShape, x, y, z, argb, lineWidth)`,lines 管线的顶点格式
逐顶点带线宽。征询轮廓(Numen API)与寻路调试覆盖层(Numen)的方框都交给它(方块是一个小方块形状加偏移,实体是 `Shapes.create(包围盒)`),
寻路调试的折线与竖线是自己的线段、逐顶点写 `setLineWidth`;线宽都取 `Window.getAppropriateLineWidth()`,与原版方块选中框相同。
两处分属两个模块(Numen 编译期只看得见瘦 api jar),所以不另做共用的画线助手。

**权限集**:`NumenPlayer.permissions()` 返回 `Authority.commandPermissions`(`PermissionSet`);`IPermissions.commandPermissions` 没主人时是
`NO_PERMISSIONS`,有主人取 `getProfilePermissions`;`ModPermissions.levels` 问 `permissions().hasPermission(new Permission.HasCommandLevel(...))`;
客户端面板判 `Permissions.COMMANDS_GAMEMASTER`。命令源、op 名单条目、`withPermission`、`.requires` 的写法见上一节。

**游戏规则**:规则是 `GameRule<T>`,`get(rule)` / `set(rule, value, server)`。用例夹具改规则只经 `GameTestWorld.setRule(level, rule, value)`:
原版实现传 `MinecraftServer`,Paper 的 `GameRules.set` 第三参是 `ServerLevel`(规则按世界存),只在 Paper 的实现里不同。

**倒水蒸发**:上一节的 `WorldView.waterEvaporates(BlockPos)` 照做;`CostModel.mayCatch(view, pos)` 按落点问,`Drop` 把落点传进去;
地形烘焙的 `ultra_warm` 键名不变(数据集的键),值取 `WATER_EVAPORATES`。

**其它**:`BedSleepingProblem` 是 record,`SleepOps.explain` 取 `message()`,只有 `OTHER_PROBLEM` 没文案;聊天面板的链接点开走自己的
`openLink`(看 `chatLinks`/`chatLinksPrompt`,`ConfirmLinkScreen.confirmLinkNow` 或 `openUri`)。

**依赖**:Gson 2.13.2、slf4j 2.0.17(本版游戏自带的版本,编译与单测都对着它)。端到端的协议客户端用 MCProtocolLib `1.21.11-1`,
它带的 Adventure 4.25 不再另提版本。

**联动**:带 Curios(`14.0.0+1.21.11`)、JEI(`27.54.0.116`)、FTB Quests(`2111.1.5`,与它构建时声明的 FTB Library `2111.1.1`、
FTB Teams `2111.1.1`、Architectury `19.0.1` 一组;2111 这一线的 `neoforge.mods.toml` 声明 `minecraft [1.21.11,)`)。FTB Quests 这一版的改动:
任务文件单例改走 `ServerQuestFile.getInstance()`/`ClientQuestFile.getInstance()`,`FTBQuestsClient.getClientPlayer()` 没了(取原版的
`Minecraft.getInstance().player`),`Tristate` 迁到 `ftblibrary.client.config`,删章节用 `deleteObject(id)`,`Component.Serializer` 没了
(JSON 标题走 `ComponentSerialization.CODEC` 加任务书的注册表上下文,和 FTB 自己的 `TextUtils.parseRawText` 同一条路);
它的 GameTest 换成 Numen API 夹具的 `@GameTest`/`@BeforeBatch` 与 `Checks.assertTrue`。
不带 YSM、车万女仆、森罗物语厨房、KubeJS(1.21.11 都没有可用构建;KubeJS 的 maven 只有 2004–2101 与 26.x)。
不带 Litematica:1.21.11 的 Litematica/MaLiLib 构建全出自 Loom 1.16.3 与 1.17.20(清单的 `Fabric-Loom-Version`),本仓库的 Loom 1.14.10
拒收更新的 Loom 打的模组(`Mod was built with a newer version of Loom`),Loom 1.17 又要更新的 Gradle。
Paper 开发包 `1.21.11-R0.1-SNAPSHOT`。
## 1.21.11 → 26.1.2 ✓（已验证：并仓树双 loader 编译 + 出包 + datagen 四路 + 867 单测 + 65 条游戏内用例 ×5；跨版本纪元:渲染第四震 + Java 25 + Fabric 原生 loom）

构建旋钮:MC `26.1.2` / range `[26.1.2, 26.2)` / NeoForm `26.1.2-1` / Fabric `0.148.2+26.1.2` /
**Fabric loader `0.19.2`** / NeoForge `26.1.2.50-beta`。运行时库 gson `2.13.2` / slf4j-api
`2.0.17` 与 1.21.11 完全同版(manifest 逐条核过),ai/build.gradle 数字不动只改口径。

### ⚠ Java 25 ❗（环境级）
26.1 要求 **Gradle JVM 本身是 Java 25**(不是 toolchain)。`java_version=21 → 25`;构建时
`JAVA_HOME` 指 JDK 25(本机可用 gradle 自供给的 `~/.gradle/jdks/eclipse_adoptium-25-*`)。
CI 无需改:workflow 的 setup-java 动态读 gradle.properties 的 java_version。
mixin 的 `compatibilityLevel` 保持 `JAVA_21` 即可(required:true 下 gametest 起得来即为实证)。

### 渲染第四震:GuiGraphics → GuiGraphicsExtractor（api,render-state 提取模型)
```java
GuiGraphics → GuiGraphicsExtractor(类型+import,全量机械替换;并仓树 21 文件)
Screen.render → extractRenderState(+super);renderBackground → extractBackground
AbstractWidget: w.render(…) → w.extractRenderState(…);抽象 renderWidget → extractWidgetRenderState
AbstractButton.renderContents → extractContents
g.drawString → g.text;drawCenteredString → centeredText;renderItem → item;
renderItemDecorations → itemDecorations(fill/blitSprite/setTooltipForNextFrame 不变)
PlayerFaceRenderer → PlayerFaceExtractor(.draw → .extractRenderState,同参,三种重载都在)
InventoryScreen.renderEntityInInventoryFollowsMouse → extractEntityInInventoryFollowsMouse
net.minecraft.client.GuiMessage → net.minecraft.client.multiplayer.chat.GuiMessage
ChatComponent.addMessage(Component) 删除 → addClientSystemMessage(Component)
  // 加行链路 addClientSystemMessage → addMessage → addMessageToQueue,第一句仍是
  // allMessages.addFirst(msg)——新行落 0 位,流式打字机"取 0 位句柄"的前提继续成立。
net.minecraft.client.renderer.state.CameraRenderState → …state.level.CameraRenderState
  // SpeechBubbleRenderer 签名与 MixinLivingEntityRenderer 的 method 描述符都要跟
```
自定义 render 方法(NumenUI 面板/视图层)**保名**,只有原版覆写与原版控件调用点改名。
并仓树的覆写点收敛在三个 Screen(CompanionChatScreen/CompanionWheelScreen/NumenScreen)
+ NumenScreen 的 w.render 循环——IDrawSurface 架构把其余 UI 隔在版本中立层。
McDrawSurface(画布适配器)机械替换即可,enableScissor/disableScissor/pose() 均健在。

RoundRect(自定义 GuiElementRenderState):接口迁包 `client.gui.render.state →
client.renderer.state.gui`(buildVertices/pipeline/textureSetup/scissorArea 方法面不变,
extends ScreenArea 供 bounds;addGuiElement 提交,原 submitGuiElement 改名);RenderPipeline 建造器
`withBlend(BlendFunction) → withColorTargetState(new ColorTargetState(BlendFunction))`、
`withDepthTestFunction(DepthTestFunction) → withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))`
(BlendFunction 迁 blaze3d.pipeline;DepthTestFunction 类删除);顶点格式 `NEW_ENTITY → ENTITY`。
顶点属性面(addVertexWith2DPose/setUv1/setUv2/setNormal)一字未动。

**AW 命名空间 `named → official`**(原生 Mojang 映射下 official 即 Mojang 名,loom 会拒 named);
AW/AT 目标类 GuiGraphics→GuiGraphicsExtractor,AW 里 guiRenderState 字段的描述符跟着
GuiRenderState 迁包(`client/renderer/state/gui/GuiRenderState`);guiRenderState/scissorStack
字段名不变,ScissorStack 内部类随迁。并仓树 AW 仍住 api/fabric 资源根(下游按声明就地找)。

### Fabric 原生 loom + Fabric API 大迁移
构建:根 build.gradle 插件声明 `fabric-loom-remap → fabric-loom`(同一 1.14.x 线),两个
fabric/build.gradle 同步换插件、删 mappings 块、`modImplementation → implementation`;
core 对 api 的 project 依赖不再走 `namedElements`(原生 loom 无 remap,普通 project 依赖
即 Mojang 名),`include project(':api:fabric')` JiJ 不变;root/settings 其余不动。API:
```java
KeyBindingHelper(keybinding.v1) → KeyMappingHelper(keymapping.v1).registerKeyMapping
HudRenderCallback.EVENT → hud.HudElementRegistry.addLast(Identifier, HudElement)
  (HudElement.extractRenderState(GuiGraphicsExtractor, DeltaTracker),lambda 兼容;
   talk_hint 一层带 TalkHint + NumenHudToasts 两个绘制体,与 neoforge 侧图层同名)
rendering.v1.world.WorldRenderEvents → rendering.v1.level.LevelRenderEvents
  (BEFORE_DEBUG_RENDER 没了 → BEFORE_GIZMOS,ctx.matrices() → ctx.poseStack(),相机仍走 gameRenderer)
ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD → ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL
PayloadTypeRegistry.playC2S/playS2C → serverboundPlay/clientboundPlay
FabricDataOutput → FabricPackOutput;FabricTagProvider.BlockTagProvider → FabricTagsProvider.BlockTagsProvider
  (ItemTagProvider → ItemTagsProvider 同理;FabricDataGenerators 的 createPack/addProvider 不变)
```
NeoForge 侧本档零改动(FMLLoader.getCurrent() 健在,loader 9 静态化预警未兑现;
moddev 2.0.141 原版本直吃 26.1.2-1)。fabric 侧 MixinSoundEngine 的 @Redirect 目标
`SoundBufferLibrary.getStream(Identifier,Z)` 在 26.1.2 逐字节确认仍在,SoundInstance
仍无可覆写的 getStream 钩子——双保险原样成立。

### core/api 侧代差
```java
ClickType → ContainerInput(常量同名;ContainerOps/CraftOps/MenuOps)
ChunkPos.asLong(x,z) → pack(x,z);.toLong() → .pack();.x/.z 私有化 → x()/z()
Entity.interact / Player.interactOn 增加实体相对命中点 Vec3(中身高点为安全默认)
Recipe.assemble 去掉 registryAccess 参数(crafting/single/smithing 全 family;RecipeProbe 跟着断参)
Recipe.group()/showNotification() 由默认实现变抽象(单测的匿名假配方要补;按原版默认 ""/true)
WaterlilyBlock → LilyPadBlock;FarmBlock → FarmlandBlock
SavedDataType 名参 String → Identifier——并仓树三处:CompanionRegistry 沿用旧分支
  numen:companions(保存档兼容),TimerRegistry → numen:timers、EventOutbox →
  numen:event_outbox(两者 26.1.2 均首发,无兼容包袱,取同一命名法)
ServerLevel.getDayTime() 整个删除 → 世界时钟读侧:Level.getOverworldClockTime()
  (读 WorldClocks.OVERWORLD 时钟总刻,即旧 dayTime 的共享日历语义;NumenEvents 的
   事件时间戳与 EntityAgentLoop 的时辰提示都走它;写侧见 gametest 环境一节)
```
BuildValidity 的对账仍是 AUTHORED_PROPERTIES 白名单(版本中立),getValues()/getProperties()
两不碰,本档零触点。authlib 7.0.63:GameProfile 三参构造/name() 访问器与 1.21.11 同形,
MojangSkinLookup/CompanionFactory/NumenPlayer 零改动。载具 mixin 的
isLocalInstanceAuthoritative 在 26.1 变 final——@Inject 不受 final 影响,原样成立;
#56 的 MixinServerCommonPacketListener(send 单参 + connection 字段)逐字健在。

### ⚠ 测试环境:组件绑定迁数据包加载期 ❗
26.1 的 Item 不再自持 DataComponentMap(Item.components() 反委托 holder),绑定发生在
数据包加载(DataComponentInitializers)。headless 测试只跑 Bootstrap.bootStrap() 会在
ItemStack 构造时抛 "Components not bound yet" —— 引导后调
`McTestComponents.bindAll()`(官方 DATA_COMPONENT_INITIALIZERS.build(...).apply() 管线)。
**上下文必须带数据包注册表**:`RegistryAccess.fromRegistryOfRegistries` 只有静态注册表,
防火物品的初始化器查 damage_type 的 `minecraft:is_fire` 标签直接抛 Missing tag,被
@BeforeAll 的 catch 吞掉后整类被 assumeTrue 静默跳过(报告不红,只是没跑)。用
`VanillaRegistries.createLookup()`:静态+全部数据包注册表的引导内容合成一份 provider,
任何标签一律空集(datagen 语义),组件照常绑定。并仓树 core/api 两个测试源码集各持一份
夹具(跨模块不可见),全部 10 个引导点在 bootStrap() 后接 bindAll(),867 条全跑零跳过。

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
26.1 把 `StructureUtils.testStructuresDir` 拆成 `testStructuresSourceDir`(读)/
`testStructuresTargetDir`(写),读侧走 `DirectoryTemplateSource(dir, PackType.SERVER_DATA,
FileToIdConverter("structure", ".snbt"))`——即**当成资源包根目录**解析:
```
gameteststructures/data/numen/structure/floor20.snbt      ← 26.1 布局
gameteststructures/floor20.snbt                            ← 1.21.11 平铺布局
```
不改布局的后果是全部用例 tick 0 即 `Failed to place test structure`。图纸夹具
`japanese_cottage.litematic` 按路径直接读、不走模板系统,仍留在根下;
copyCottageFixture 的读侧跟着走 `testStructuresSourceDir`。结构模板 DataVersion 4189
原样保留,`readStructure` 的 DFU 正向修到 26.1.2,65 条全绿即为实证。

### ⚠ gametest:26.1 的同步加载票据"未载先过期"——常驻 tick 身体不许站在未加载区块里 ❗
本档最大的一坑,现象是**随机批次卡死 → 8G 堆 OOM**(堆里 345 万 ChunkHolder、5794 万条
排队的 ChunkTaskDispatcher 任务,主线程整刻困在 DistanceManager.runAllUpdates 的等级传播)。
逐层取证(临时 mixin 钉 TicketStorage.addTicket 计数+抽样调用栈)后的完整链条:

1. **泄漏源**:四条用例(quote_and_gate / blueprint_second_run / blueprint_restock /
   running_out_of_materials)只 spawn 不 despawn,通过后同伴永久留场;
2. **暴露面**:gametest 同伴无主人,pad 按设计不发票(主人离线不占块);批次结束
   runner 把 forceLoad 全撤,孤儿同伴站进未加载区块;
3. **26.1 独有的引爆器**:玩家类身体不受区块门控、每刻必 tick,原版 tick 的方块读取
   (Entity.isInWall 等)对未加载区块做**同步加载**(UNKNOWN 票,timeout 1 且
   `FLAG_CAN_EXPIRE_IF_UNLOADED`——**区块没载完票也过期**,这个 flag 是 26.1 新引入);
   于是"同步加载→票过期→卸载→再加载"每刻一轮,单个孤儿实测把同一格 chunk 抽了 36 万次,
   任务队列只增不减直至 OOM;后续批次的结构区块永远到不了 entity-ticking,测试全部
   卡在 GameTestInfo.tick 的区块门上(不超时、不失败、只冻结)。
   1.21.11 同一泄漏是**良性**的:那代票据须等区块载毕才可过期,稳态收敛,故全绿。

修法(两层,都是根治):**NumenPlayer.tick() 开头加休眠门**——所在区块未加载即整刻
返回,把设计注释里"pad 失效她就闲置"落到实处(生产面同险:服务器上主人离线的同伴
就是同一颗雷);四条用例补 despawn(全 65 条 spawn/despawn 配对审计过)。修后 66 条
连过 5 轮(远/近坐标都覆盖),单轮 8–9 秒。

**同构负结果**:pad 票据(关掉照炸)、skipPlayer 门(26.1 仍在且 mixin 命中)、
玩家票 PlayerTicketTracker(同伴根本不进 playersPerChunk)、TicketType 位图
(record 形参与 5 个 flag 与 1.21.11 逐字一致)、落位远近(26.1 的 GameTestServer
随机落在 ±1500 万,1.21.11 也一样,远坐标只是批次顺序运气的错觉)——都排查过,均非因。
**上一档手册"失败残留世界导致死亡螺旋、清目录即愈"的结论由此作废**:同一签名的雪崩
在全新世界照样随机出现,病根是本节的泄漏+引爆器,清目录只是重掷了批次顺序的骰子。

### 未发生的预警项
- NeoForge loader 9 静态化没有兑现,neoforge 侧零改动;
- Java 25 只是环境级,mixins.json 不动;
- 权限集/游戏规则/TicketType/ClientInput.moveVector/TagValueInput 桥/CompoundTag
  Optional 化/数据驱动 gametest 框架的注册面——前五档整套适配原样成立,一处未动;
- BlockPos.asLong() 未删(过时的是别的 Stream 方法),WorkBlockMemory 零改动;
- StreamCodec.composite 上限仍 12 字段,NumenLocationsPayload 只改注释口径。

### 版本口径与不迁项
- `skills/tier_progression/SKILL.md` 的 `## Where ores live` 口径 `1.21+ → 1.21+ / 26.x`
  (26.1.2 的 OrePlacements 与 1.21 分布一致,旧分支逐条对过,同一 MC 版本结论直接沿用);
- 注释里的 1.21.11 字样逐条按仍真/不真处理:ShapeRenderer 无 renderLineBox(仍真,改
  本代口径)、vanilla 无 SoundInstance.getStream 钩子(仍真)、McDrawSurface 实现口径
  改 26.1.2、MixinSoundEngine 的优先级实证改本代措辞。

### 只在真机客户端才能目视验证的部分
与上一档同一份清单,且本档在渲染语义上只做了签名/包名的机械替换,没有改绘制逻辑:
头顶气泡的实际观感、转盘的物理按键接管、GUI 圆角 SDF 的渲染结果、快捷对话/语音三件套、
编辑卡/绑定点/召唤卡/Y 输入行在提取模型下的形态。本档另加两项因为换了 API 才需要重看:
HUD 图层换 `HudElementRegistry.addLast` 后快捷对话提醒的层级与位置;调试线换挂
`LevelRenderEvents.BEFORE_GIZMOS` 后寻路调试覆盖层的深度关系。

### 26.1.2 落地实录（并仓迁移）
树落底 1.21.11 尖(d5364657)后单跳本档。提交序列:树落底 → build(旋钮/原生 loom/
AW official/Java 25/CI 门)→ api(渲染第四震/Fabric API 迁移/世界时钟/SavedDataType)
→ core(ContainerInput/ChunkPos/assemble/gametest 泛型化与资源包布局/单测补绑)→
fix(未加载区块身体休眠 + 四条用例补 despawn)。双 loader 出包 + datagen 四路(生成物
零漂移)+ 867 单测零跳过 + gametest "All 66 required tests passed" ×5。旧独立仓
26.1.2 分支(api@c4a3c78 / core@59fd3fd8)的 GuiGraphicsExtractor 形态、loom 构建
文件、AW/AT 全部照抄成立;本档并仓树**新**碰到的是:世界时钟的**读侧**
(getOverworldClockTime,旧分支只趟过写侧)、SavedDataType 多出的两处(计时器/事件
暂存箱,0.1.1 后新增)、Recipe.group()/showNotification() 抽象化咬到单测假配方、
RecipeProbe 断参,以及上面整节的同步加载票据雪崩——前几样五分钟的事,最后一个吃掉
了本档大半排障时间,也推翻了上一档的一条旧结论。

## 26.1.2 对齐 1.21.1 @7a3d3e251（0.1.3 功能面：程序执行、网络包、FakeClient、外观、背包装备、bench）
方法:整树落成 1.21.1 的树,再把本分支的平台差异按 B26(`ddc705e63`)补回去,编译器与 GameTest 点名。
本档**新**碰到的(旧并仓树里没有这些代码):

### 代码侧
- **SavedData 的新代码全走 `SavedDataType`**:`PlacedBlocks`(`numen:placed`)、`PermissionStore`(`numen:permissions_<uuid>`)、
  `Built`(`numen:built`)各带一份 `CODEC`;单测与 GameTest 的"往返"走它,不再 `save`/`load(CompoundTag)`。
- **`Inventory` 主背包私有化、副手/盔甲迁进 `EntityEquipment`**:`items` → `getNonEquipmentItems()`、`offhand` →
  `setItem(Inventory.SLOT_OFFHAND, …)`、`selected` → `getSelectedSlot()/setSelectedSlot()`。**机械替换会误伤 Lua 文本**:
  `numen.inv.items()` 这类字符串里的 `.items` 也被换成 `getNonEquipmentItems()`,GameTest 才抓得到。
  单测里自造 `Inventory` 要和实体共用同一个 `EntityEquipment`(反射写 `equipment` 字段)。
- **权限换成权限集**(`CommandSourceStack` 构造、`hasPermission`):`LevelBasedPermissionSet.OWNER/ALL/forLevel(…)`、
  `permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)`。`OpList` 条目是 `NameAndId` + 权限集。
- **环境属性**:`DimensionType.ultraWarm()` 没了,`WATER_EVAPORATES` 要位置才能查;"这个维度水会不会蒸发"读
  `dimensionType().attributes().applyModifier(attribute, attribute.defaultValue())`(`pathing` 的 `Semantics.waterEvaporates`)。
- **`ServerPlayer` 继承成员类型 `WaypointTransmitter.Connection`**:在 `ServerPlayer` 子类里写 `Connection` 解析到它,
  不是 `net.minecraft.network.Connection`,要写全名。
- **mixin 目标全部对着 26.1.2 反编译源核对过**:`ServerCommonPacketListenerImpl.send(Packet, ChannelFutureListener)`、
  `ItemEntity.hurtServer`、`CraftingMenu` 的 `slotChangedCraftingGrid`(`ServerLevel` 参数)、`ToastManager` 访问器改名。
- **`ConfigSync.syncPendingConfigs` 要求非内存连接有待同步登记**:没有客户端的模拟主人连接(GameTest 的 `OwnerLine`、bench 的
  `OwnerConnection`)必须报 `isMemoryConnection() == true`。
- **客户端没有全局着色器颜色**:界面淡入淡出走 `Fade`(环境 alpha),在 `McDrawSurface`/`Nb`/`Sprites` 与直接 `g.fill/text/blitSprite`
  的几处收口;`Sprites.draw` 用完把 `Fade` 复位。聊天链接点击走 `clickUrlAction`,`ChatView` 用 `ClickableStyleFinder` 找链接。
- **NeoForge FML 11**:内嵌资源根经 `IModFile.getContents().getContentRoots()` 找(`findFile` 不返回目录,`ModJar`);GameTest 登记
  经 `RegisterGameTestsEvent`,只在 `GameTestHooks.isGametestEnabled()` 时挂。
- **`Hotbar`**:`handlePickItem` 包没了,换成 `pickSlot` 一类的服务端入口。

### 测试侧
- **GameTest 数据化**(1.21.5 起):`@GameTest`/`@BeforeBatch`/`@GameTestHolder` 全删。用例类上自带三枚注解
  (`NumenTest`/`NumenBeforeBatch`/`NumenAfterBatch`,住在寻路的 GameTest 源码集),`NumenGameTests` 经 FML 注解扫描登记成
  `NumenTestInstance`,批次的开场收尾包成 `NumenTestEnvironment`;同批次仍同批跑。**自带 test_instance 类型,不用原版
  `FunctionGameTestInstance`**(`TEST_FUNCTION` 注册表在引导时冻结)。用例名 `numen:<类名小写>.<方法名>`。
- **结构模板**放 `data/<ns>/structure/` 资源包布局,而且 **helper 的原点在模板第 1 层**(模板在原点下一层):1.21.1 的地板模板要在底下
  再垫一层(`size.y+1`、全部方块 y+1、原 y0 层再复制成新 y0),否则整个场地矮一格。
- **批次开场钉住场地**:`RANDOM_TICK_SPEED=0`、`FIRE_SPREAD_RADIUS_AROUND_PLAYER=0`(26.1 的火在有玩家的区域里按刻变老,身体是玩家;
  随机刻让草皮枯成泥土、耕地失水——用例对账看"除了她动过的一格都没变")。
- **GameTest 服务器不限速**:26.1 的一刻只要几十微秒(实测每秒两万多刻,1.21.1 约 600),`setTickRate` 压不住(`GameTestServer.waitUntilNextTick` 直接跑任务,不等)。按刻写的时限与异步搜索(按墙钟)的快慢比不靠补刻节奏:寻路用例的夹具在导航等搜索时这一刻等它回来(`Trial.Run.awaitSearch`)。
- **评测(bench)**同样数据化:`Bench.suite` 返回 `Optional<Bench.Run>`,场景类的方法标 `@BenchSuite`,`NumenBench` 在
  `RegisterGameTestsEvent` 里扫出来、每组登记一条 `BenchTestInstance`(模板借核心的 `numen:floor16`);世界时间走世界时钟、
  `GameRules.ADVANCE_TIME/ADVANCE_WEATHER/SPAWN_MOBS`。

### 联动
- **车万女仆、森罗物语:厨房**:NeoForge 版封顶 1.21.1,这条分支不建这两个联动模块。**Curios**:`15.0.0+26.1.2`(Curios 自家 maven),
  只有 `ItemStack.getTags()` → `typeHolder().tags()` 一处要改。**FTB Quests**:`26.1.2.8` 存在(maven.ftb.dev),但 26.1 的事件 API
  整个换了(`api/event/*Event` + `Data`,`events.ObjectCompletedEvent`/`TeamEvent` 没了,`ServerQuestFile.INSTANCE` 私有),联动要重写,
  这一档没做。

### MixinExtras
1.21.1 有四处 mixin 用 `@WrapMethod`(要 MixinExtras ≥ 0.4,版本过旧时静默不生效)。26.1.2 上:NeoForge 的 GameTest 日志
`MixinExtrasServiceImpl(version=0.5.4)`;Fabric loader 0.19.2 的 jar 内嵌 `mixinextras-fabric-0.5.4.jar`。两边都够,不用内嵌。


## 26.1.2 对齐 1.21.1 @352c6fec8（0.1.5）
方法:不再从 1.21.1 直接移,而是以 1.21.11 的对齐结果(`align-1.21.11`@76a05df2c,已含 352c6fec8 及之前的通用修复、1.21.2+ 的各项适配)为底,
把 26.1.2 相对 1.21.x 的那一段差异三方合并进去(差异取自上一轮 26.1.2 的落地,基准是 1.21.1@19c8710b0),冲突一律先取 1.21.11 那边的写法,
再由编译器与 GameTest 点名补 26.1 的差异。同一处 API 差异在 1.21.x 线上已经适配过的,这里不再另写一份。下面只记 1.21.11 → 26.1.2 这一段。

**构建旋钮**:NeoForge 换成正式版 `26.1.2.126`(`26.1.2.50-beta` 太旧,KubeJS `26.1.2-8.0.8` 要求 `26.1.2.110` 以上);其余同上一节。
Fabric 是原生 Loom(`net.fabricmc.fabric-loom`,不重映射,没有 `mappings`、`mod*` 配置);paperweight-userdev `2.0.0-beta.22` 认得
`26.1.2.build.74-stable` 开发包。e2e 的 MCProtocolLib 用 `26.1-1`(与 26.1.2 同一个协议号)。

**GameTest 夹具(1.21.11 的数据驱动框架之上)**
- 测试环境带存档类型:`TestEnvironmentDefinition<SaveData>`,`setup` 返回存档、`teardown(level, 存档)`;`NumenTestEnvironment` 是 `<Unit>`。
  测试实例的数据是 `TestData<Holder<TestEnvironmentDefinition<?>>>`,构造多一个 `padding`(给 0)。
- 登记点:26.1 每个数据注册表一个装载任务,`GameTestRegistrationMixin` 挂 `RegistryLoadTask.freezeRegistry` 开头(读完、冻结前)。
- `GameTestServer.create` 多一个 `int repeatCount`;`StructureUtils.testStructuresDir` 拆成 `testStructuresSourceDir`/`testStructuresTargetDir`。
- 结构模板目录是资源包的布局:`syncGameTestStructures` 把各模块的模板放进 `data/<套件名>/structure/`,命名空间参与查找。借 Numen 地板的
  联动用例与评测写全名 `numen:floor16`(YSM、KubeJS 的用例,`Bench.ANCHOR`)。Fabric 不再另做一份 `gametest/structure` 资源,两边读同一个目录。
- 天气归服务器:原版是 `MinecraftServer.setWeatherParameters(4 参)`,Paper 是 `(ServerLevel, 4 参)`,经 `GameTestWorld.setWeather` 一处分开;
  时刻是世界时钟(`GameTestWorld.setDayTime`:维度类型的默认时钟拨总刻数)。
- 区块刻洗牌的 `tickChunks` 有两个重载,`ChunkTickShuffleMixin` 写全描述符 `(ProfilerFiller, long)`;`Util` 在 `net.minecraft.util`。
- `ChunkPos` 是记录(`x()`/`z()`)。

**单测**
- 物品的数据组件在数据包加载期才绑:单测会话开头经 JUnit 的 `LauncherSessionListener` 引导一次(Numen API 的 `McTestComponents`、寻路夹具的
  `VanillaSession`,后者也绑原版方块/流体标签)。因为原版标签在会话开头就绑好了,改绑标签的测试(`GateTerrainTest`)收场时原样绑回,不清空。

**代码侧**
- `BlockTags.DIRT` 拆了:现在只剩三种泥土,原来的整族(草方块、灰化土、菌丝、泥巴、苔藓块)是 `#minecraft:substrate_overworld`。
  滑翔落点的"天然方块"(`Landings.natural`)与 `numen:natural` 标签都改认它。
- `SavedDataType` 的名字是 `Identifier`:存档文件落在 `data/numen/<名>.dat`(`numen:companions`、`numen:placed`、`numen:permissions_<uuid>`、
  `numen:nav_clock`、计时器),与本分支 0.1.4.x 的名字一样。从 1.21.x 的世界跨大版本升上来时,旧名字(`numen_xxx.dat`)的文件不会被读到(遗留)。
  旧版服务端出箱的一次性迁移(`ServerBrains.legacyOutbox`)照同一条规则找 `numen:event_outbox` → `data/numen/event_outbox.dat`。
- 界面:`GuiGraphics` → `GuiGraphicsExtractor`(`drawString` → `text`、`renderFakeItem` → `fakeItem`、`PlayerFaceRenderer.draw` →
  `PlayerFaceExtractor.extractRenderState`);整块界面淡入淡出仍是 `GuiAlpha` + `MixinGuiGraphics`,三个提交口换成 `innerFill`、
  `text(Font, FormattedCharSequence, …)`、`innerBlit(RenderPipeline, GpuTextureView, GpuSampler, …)`。`CameraRenderState` 在 `renderer.state.level`。
- 世界里的线(寻路调试线、征询轮廓)走原版 gizmo:Fabric `LevelRenderEvents.BEFORE_GIZMOS`,NeoForge `RegisterDebugRenderersEvent`。
- `Item.getName()` 要 `ItemStack`(取默认那一叠的 `getItemName()`);`Recipe.assemble` 不再要注册表;`Recipe.group()`/`showNotification()` 变抽象。
- 本地玩家 `displayClientMessage(…, false)` → `sendSystemMessage`。

**联动**:Curios `15.0.0+26.1.2`、YSM `2.6.5`(26.1)、KubeJS `26.1.2-8.0.8`(Rhino `2101.2.8-build.91`;运行期还要 Better Advanced Tooltips
`2601.1.0-build.11`,`2601.1.1-build.12` 的 mods.toml 写的是 NeoForge 26.3 以上)、Litematica `0.27.14`/MaLiLib `0.28.12`(26.1 的发布物就是
Mojang 名,普通 `compileOnly`)、JEI `29.53.0.115`。KubeJS 用例脚本里杀目标是 `target.kill(target.level)`(`Entity.kill` 要 `ServerLevel`)。
不带:车万女仆、森罗物语厨房(没有 26.1 构建)。

**FTB Quests 联动(只 NeoForge)**:FTB Quests `26.1.2.10`、FTB Library `26.1.2.12`、FTB Teams `26.1.2.5`(Quests 构建时声明的一组)。
这一代 FTB 不再用 Architectury,联动照新接口重写,设计不变(她是真玩家、同队一起推进、奖励进她背包要报告):
- "是不是假玩家"挪进 FTB Library 的 `NeoMiscImpl.isFakePlayer`(凡是 `ServerPlayer` 子类都判假,判据同 Architectury)。联动自带一份
  窄 mixin(`numen_ftbquests.mixins.json`,`neoforge.mods.toml` 里 `requiredMods = ["ftblibrary"]`),对同伴答"不是"。
- 完成事件:`ObjectCompletedEvent` 没了,换成进度事件 `QuestProgressEvent`/`ChapterProgressEvent`(类型 STARTED/COMPLETED,数据是
  `ProgressEventData`:队伍、对象、在线成员);NeoForge 上由 FTB Library 转成 `FTBQuestsEvent.QuestProgress/ChapterProgress` 发到
  NeoForge 总线。换队是 `FTBTeamsEvent.PlayerChangedTeam`(数据里 `player()` 不在线为 null)。
- `ServerQuestFile.INSTANCE`/`ClientQuestFile.INSTANCE` 私有化 → `getInstance()`;客户端玩家取 FTB Library 的 `ClientUtils.getClientPlayer()`。
- 任务书数据从 NBT 换成 JSON5(`de.marhali.json5.Json5Object`,FTB Library 内嵌的 json5-java `3.0.0`):`readData` 对必有的字段
  `orElseThrow`。用例照编辑器的做法搭任务书:新对象先填好(物品任务的物品),写出自己的数据再读回。
- 标题原文的解析照 FTB 的 `TextUtils.parseRawText`:先把 Unicode 转义还原(commons-lang3 的 `UnicodeUnescaper`),JSON 走
  `ComponentSerialization.CODEC`。
- 26.x 的 GameTest 登记不再看 `neoforge.enabledGameTestNamespaces`,NeoForge 自己的联网协商用例因此进了每一次跑批;装着 FTB 时
  原版客户端被 FTB 的频道拒之门外,和这条用例要查的"我们的频道"无关。照 1.21.1 的口径,运行配置只开联动命名空间时不带这条用例。
- 26.2、26.3 没有 FTB 的构建,这两档不带。
