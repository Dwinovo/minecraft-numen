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

### 1.21.4 二次对齐(0.1.3 → 1.21.1@7a3d3e251)

整树对齐新增 agent/lua/pathing/bench/插件框架之后,新代码碰到的 1.21.2+ 差异(上面没有的),逐条:

**注册表与配方**
- `Registry.asLookup()` 没了:注册表自己就是 `HolderLookup`,直接传 `BuiltInRegistries.BLOCK`。`registryAccess().registryOrThrow` → `lookupOrThrow`,`getHolderOrThrow` → `getOrThrow`。
- 配方:`level.getRecipeManager()` → `level.recipeAccess()`;`byKey` 吃 `ResourceKey<Recipe<?>>`(`ResourceKey.create(Registries.RECIPE, id)`);**`RecipeHolder.id()` 现在是 ResourceKey,取原来的字符串要 `.id().location()`**(直接 `toString()` 编得过、值错);静态配料清单走 `placementInfo()`,`Ingredient.items()` 是 `Stream<Holder<Item>>`,熔炼/切石 `input()`/`cookingTime()`。
- `CraftingMenu.slotChangedCraftingGrid` 的 level 参数是 `ServerLevel`(CraftingMenuAccessor 的 @Invoker 描述符要跟)。

**玩家与服务端**
- ❗ **`ServerPlayer.hasClientLoaded()`**:为假时服务端不收任何动作包(挖/放/用)且玩家不受伤;原版靠客户端发 `ServerboundPlayerLoadedPacket`,60 刻后兜底。假玩家走真包路径的操作(寻路模块的 `PlayerHands`)头 60 刻会被静默吞掉(症状是挖掘全报"服务端没让挖")。FakeClient 在收到下行 `ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START` 时晚一刻替她回 `handleAcceptPlayerLoad`,与回传送编号同一套。
- `ClientboundPlayerPositionPacket.getId()` → `id()`;`MinecraftServer.tell` → `schedule`;`Item.getDescription()` → `getName()`;`ServerPlayer.shouldInformAdmins()` 不再对外(玩家的指令来源恒为 true,McApi 直接传 true);`isInvulnerableTo(ServerLevel, DamageSource)`;`teleportTo(level, x,y,z, yaw, pitch)` → `teleportTo(level, x,y,z, Set<Relative>, yaw, pitch, setCamera)`;`changeDimension(DimensionTransition)` → `teleport(TeleportTransition)`。
- 背包:`ServerboundPickItemPacket` 拆成按方块/按实体两个包,背包深处换进快捷栏改直接 `inventory.pickSlot(slot)` + `ClientboundSetHeldSlotPacket` + `inventoryMenu.broadcastChanges()`(与原版 `tryPickItem` 收尾同三步);`setPickedItem` → `addAndPickItem`。
- `InteractionResult` 成密封接口:`shouldSwing()` → `result instanceof Success s && s.swingSource() == SwingSource.SERVER`。
- 高度:`LevelHeightAccessor.getMinBuildHeight()` 抽象方法换成 `getMinY()`(自家 `WorldView`/`SearchView` 的实现与单测夹具一并改);`getMaxBuildHeight()`(不含端)→ `getMaxY()`(含端),要保持"上界不含端"的写成 `getMaxY() + 1`;`getMinSection` → `getMinSectionY`。
- `Direction`:`getNormal` → `getUnitVec3i`;`getNearest(double×3)` → `getApproximateNearest`(同一套点积算法);`getNearest(int×3)` 补兜底参 `Direction.NORTH`;`fromDelta` 没了,同样走 `getNearest(..., NORTH)`。

**mixin(无头测不到,逐个对源码核过签名)**
- `ItemEntity.hurt` → `hurtServer(ServerLevel, DamageSource, float)`(ItemEntityDropsMixin)。
- 船族上移 `AbstractBoat`;`EntityType.BOAT` → `OAK_BOAT`,`NavText` 报"下了 oak_boat"(单测断言跟随)。`MoveToCompanionTask`/`Planning` 里的 `instanceof Boat` 必须改 `AbstractBoat`,否则木筏/箱船静默漏判。
- 气泡 mixin 沿用 `MixinLivingEntityRenderer`,挂载点改 HEAD(与 1.21.1 的理由一致:接管渲染的插件取消渲染前置事件时 TAIL 不会跑)。
- `ToastComponent` → `ToastManager`,`getToasts()` → `getToastManager()`(ToastComponentAccessor 的目标随之换,类名未动)。
- MixinExtras `@WrapMethod` 要 ≥ 0.4:NeoForge 21.4.123 自带 0.4.1(GameTest 日志 `MixinExtrasServiceImpl(version=0.4.1` 实证),Fabric loader 0.18.1 自带 0.5.0(jar 内 `META-INF/jars/mixinextras-fabric-0.5.0.jar` 实证,Fabric 侧无头测不到)。够用,不内嵌。

**客户端渲染**
- 1.21.2+ `GuiGraphics` 没有 `setColor`,画下去的东西先攒进缓冲、`flush()` 才真画,画的那一刻用 `RenderSystem` 着色器颜色。整块画面的不透明度走 `GuiAlpha.set(g, alpha)`(先 flush 再设着色器颜色);`Sprites.draw` 的着色走 `blitSprite(RenderType::guiTextured, …, argb)` 的顶点色并在贴完 flush,与 1.21.1 "tint 覆盖着色器颜色、贴完复位"同语义。
- `LevelRenderer.renderLineBox` → `ShapeRenderer.renderLineBox`;`blitSprite` 补 `RenderType::guiTextured`。NumenUI 的圆角 shader 已随 1.21.1 删除,本分支不再有 shader 注册。

**GameTest / 单测夹具**
- `EntityType.create(level)` 补 `EntitySpawnReason.STRUCTURE`;`new Boat(...)`/`new Minecart(...)` → `create` + `setInitialPos`。
- 冻结注册表拒绝直写标签:`MappedRegistry.bindTags` 夹具(RuleTest、GateTerrainTest、ThrowawayTagTestSupport、pathing 的 Vanilla)改走 `Registry#prepareTagReload(new TagLoader.LoadResult<>(key, tags)).apply()`,它会整体换掉标签集,所以"清空"也写成空 map。
- 模板垫底(`floor16/20/52`):size y+1、data 整体 y+1、原 y0 层复刻为新底层。pathing 的三个空模板只有尺寸,不用垫。
- 寻路 StrideControl:脚已经不比落点低就不再起跳。1.21.4 上日式小屋用例稳定复现:开着的活板门(整高薄板)后面是半砖,她跳过薄板落在板顶,落点比板顶低,原逻辑落地后又 `onGround && 前沿贴近` 地再跳,一路蹦过落点,再回头走又被"落回前一步起点"判为 FELL_BACK,三次后整段收场。1.21.1 上同一用例过,是因为两版施工的先后在第 1491 条规划起出现平局分歧、没走到这一处;这是共用代码里的缺口,不是 1.21.4 的物理差异(逐刻轨迹的起跳、落地与 1.21.1 的原版公式一致)。

**插件**:1.21.4 NeoForge 上只有 Curios(Maven `10.0.x+1.21.4`)。车万女仆、YSM、森罗物语在 Modrinth 上只有 1.21/1.21.1 版;FTB Quests 的 maven 里只有 2004/2100/2101/2111/26.1.2 五档(没有 1.21.4)。故 `plugins/` 只留 curios,Builtin 只列它,Fabric 侧没有联动(Curios 只有 NeoForge 版),不建 `core/fabric` 的 Builtin。

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
移植办法（登记层是独立模块 `:gametest`，核心、寻路模块、评测的用例共用，不发布、不进发行 jar）：
```
@NumenTestHolder(命名空间) / @NumenTest(template/timeoutTicks/batch) / @BeforeBatch / @AfterBatch
                                                   ← 与旧注解同形状，用例方法体不动
@NumenTestGenerator + TestCase                     ← 评测按选中的场景现算用例（旧 @GameTestGenerator）
NumenTestInstance extends GameTestInstance         ← 按用例名到名册取回方法体
NumenTestEnvironment implements TestEnvironmentDefinition
                                                   ← 一个批次一个环境：setup 跑 @BeforeBatch，teardown 跑 @AfterBatch
GameTests（@EventBusSubscriber）：ModList 扫 @NumenTestHolder → RegisterGameTestsEvent 登记实例与环境
```
坑一：**不能用原版 `FunctionGameTestInstance`**——它按 `Registries.TEST_FUNCTION` 取用例体，
而该注册表在 `BuiltInRegistries` 引导时（`BuiltinTestFunctions::bootstrap` 一次跑完所有
loader）就冻结了，模组加载轮不上。
坑二：`TEST_INSTANCE` / `TEST_ENVIRONMENT` 在 `SYNCHRONIZED_REGISTRIES` 里，自定义实例
类型与环境类型的 `MapCodec` 必须经 `RegisterEvent` 注册进 `TEST_INSTANCE_TYPE` /
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

**随机刻必须停摆** ❗（`build_japanese_cottage`、`does_not_jump_onto_farmland`）——批次环境把
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

**批次环境口径**：每个批次一个 `NumenTestEnvironment`，`setup` 先停随机刻再跑这一批的 `@BeforeBatch`，
`teardown` 跑 `@AfterBatch`；全部批次一视同仁（原版默认环境是空的，不管随机刻）。

**同构负结果**(查过、确认不用动):`GameTestHelper` 除 assertTrue/fail 外
succeedWhen/runAfterDelay/startSequence/succeed 签名全部未变;圆角 GLSL 与 api 仓
1.21.5 分支逐字节一致,原样复用;SNBT 模板 DataVersion 4189 不动,DFU 正常升到 4325;
NeoForge 21.5 仍无公开 `common.data.ItemTagsProvider`(方块侧照旧走
BlockTagsProvider、物品侧裸 TagsProvider);vanilla 1.21.5 的 `SoundInstance` 仍无
`getStream` 钩子(语音的 NeoForge 补丁/Fabric mixin 双轨照旧);MC 1.21.5 运行时
gson 仍为 2.11.0;其余七只 mixin(skipPlayer/applyChunkTrackingView/send/play/
controlBoat/dataSlots/allMessages+refreshTrimmedMessages/nibble)目标逐一对过
1.21.5 字节码,全部还在。


### 1.21.5 二次对齐(align-1.21.4 → 1.21.5)

底是 `align-1.21.4`(它又是 1.21.1@7a3d3e251 的整树对齐),上面各节的 1.21.5 差异按新树重放一遍;新树新碰到的、上面没有的:

**存档与数据**
- `SavedData` 全部改成 codec 化的 `SavedDataType`:`CompanionRegistry`、`EventOutbox`、`PermissionStore`、`PlacedBlocks`、`TimerRegistry`、`Built`。`save()/load()` 重写没了,单测对包内可见的 `CODEC` 往返(测的就是生产在用的那条路),`Built.CODEC` 公开给 GameTest 按存档的形状往返。读档失败的兜底在原版存储层,codec 只许返回 error、不许抛。
- `CompoundTag`/`ListTag` 读取全部 Optional 化(`getIntOr`、`getCompoundOrEmpty`、`getListOrEmpty`、`keySet`、`asString()` 返 Optional;`contains(k, TAG_X)` 没了,改判 `getX(k).isPresent()`)。图纸格式、`BlueprintStore`、`Built`、单测夹具与 GameTest 里读 NBT 的地方一并改。**坑**:`getListOrEmpty` 不筛元素类型,旧写法直接强转会 CCE。

**玩家、背包与实体**
- `Inventory` 的 `items`/`selected`/`offhand` 私有化:`getNonEquipmentItems()`、`getSelectedSlot()/setSelectedSlot()`、副手 `setItem(Inventory.SLOT_OFFHAND, …)`(寻路的 `Hotbar`/`Snapshots`、`PlayerInv`、`Wardrobe`、`InvApi`)。装备在 `EntityEquipment` 里:**`LivingEntity.equipment` 与 `Inventory` 共用同一份**,单测里空壳玩家(`Unsafe.allocateInstance`)要把同一个 `EntityEquipment` 同时塞给两处,否则 `getOffhandItem()` 空指针。
- `Entity.moveTo` → `snapTo`;`ServerLevel.onBlockStateChange` → `updatePOIOnBlockStateChange`(mixin 只在运行期炸,datagen 能抓到);`Level.isDay/isNight` → `isBrightOutside/isDarkOutside`。
- `Wolf`/`Sheep` 挪进 `animal.wolf`/`animal.sheep` 子包;宠物主人 `setOwnerUUID` → `setOwnerReference(new EntityReference<>(uuid))`;猪鞍不再是 `equipSaddle`,是装备槽 `EquipmentSlot.SADDLE`。`EntityCollisionContext` 构造多一个布尔参(寻路单测夹具)。

**客户端**
- `ClickEvent` 成接口(`new ClickEvent.OpenUrl(URI)`),点击事件的 JSON 形状也换了:`clickEvent` → `click_event`,`run_command` 的 `value` → `command`(旧形状不会解码失败,只是事件当未知字段被丢掉,"带点击事件的牌子必须拒收"的用例以"没检测到威胁"的形式变红)。
- `RenderSystem.enableBlend` 没了(渲染管线代码定义化)。**GuiAlpha 靠 `RenderSystem.setShaderColor` 给整块画面调不透明度,这一条无头 GameTest 照不出,要真机看淡入淡出。**

**GameTest 登记层(本代最大的一刀)**
- 原版不再有 `@GameTest`/`@BeforeBatch`/`@GameTestHolder`,用例成了 `minecraft:test_instance` 注册表里的数据条目。新树的用例分在核心(27 个类)、寻路模块(约 15 个类)、评测(`Bench.suite`)三处,所以登记层是独立模块 `:gametest`(`GameTests`:`@EventBusSubscriber` + `ModList` 扫 `@NumenTestHolder` → `RegisterGameTestsEvent`),不发布、不进发行 jar;核心经 `compileOnly` 看见它,运行时靠 `mods.numen` 挂上。注解与旧代同形状:`@NumenTestHolder(命名空间)`、`@NumenTest`、`@BeforeBatch`/`@AfterBatch`(批次环境的 `setup`/`teardown`)、`@NumenTestGenerator` + `TestCase`(评测按选中的场景现算用例)。结构模板目录由登记层统一指到 `numen.gametest.structures`(`StructureUtils.testStructuresDir` 成了 `Path`)。
- `GameTestHelper` 的断言/失败消息要 `Component`:`assertTrue(boolean, Component)`、`fail(Component)`,`GameTestAssertException(Component, int tick)`。用例里一律 `text("...")` 包一层;没有 `helper` 的地方(步骤、轮询辅助类)抛 `TestFailure`(只带一句话,不带"第几刻")。
- `:gametest` 对着 NeoForge 编译(同 `:bench:game`),不能对着 NeoForm:NeoForm 的依赖把 asm 严格钉在 9.6,FML 的 `loader` 要 9.7,解析不过。
- **原版的 GameTest 世界换了**:1.21.4 以前 `GameTestServer` 开服就关了随机刻和火刻(并关了刷怪、天气循环);1.21.5 的默认环境是空的,只剩后两样。于是耕地失水(`does_not_jump_onto_farmland`)、草皮退化成泥土(日式小屋 5857 格同时就位永远等不到)、火蔓延与变老(`passes_cactus_fire_berries_and_magma_unhurt`)全冒出来。批次环境 `setup` 把这两条补回,一视同仁加给所有批次。
- **NeoForge 21.5 的 `ConfigSync.syncPendingConfigs` 对"非内存连接上的真玩家"要求配置阶段登记过一张待同步表,没有就抛**(`GameTestPlayer` 子类放行)。GameTest 里的在场主人 `OwnerLine` 与评测的模拟主人 `OwnerConnection` 都是没走配置阶段的 mock 连接,会在下一刻服务端刻崩服;两处都覆写 `isMemoryConnection()` 为真(没有网线,正是"和自己同步"那一支)。
- 总数 564 = 本仓 563 + 原版自注册的 `minecraft:always_pass`。

**MixinExtras**:`@WrapMethod` 要 ≥ 0.4。NeoForge 21.5.97 自带 0.5.3(datagen 日志 `MixinExtrasServiceImpl(version=0.5.3` 实证),Fabric loader 0.18.1 自带 0.5.0(同 1.21.4)。不内嵌。

**插件**:1.21.5 NeoForge 上只有 Curios(`11.0.1+1.21.5`,`gradle.properties` 的 `curios_version`)。车万女仆、YSM、森罗物语只有 1.21/1.21.1 版,FTB Quests 的 maven 没有 1.21.5 档(同 1.21.4 一节查的那几份清单),故 `plugins/` 只留 curios,Fabric 侧没有联动。

**真机要看的**(无头测不到):`GuiAlpha` 淡入淡出与 `Sprites` 着色、`MixinLivingEntityRenderer` 的气泡、`ToastComponentAccessor`、聊天里的链接点击(`ClickEvent.OpenUrl`)、`FakeClient` 的 `hasClientLoaded` 握手(真连接上)。

---

## 1.21.5 → 1.21.8
<!-- 约 24 文件 -->
_待移植时填写_

## 1.21.8 → 1.21.10
_待移植时填写_

## 1.21.10 → 1.21.11
_待移植时填写_

## 1.21.11 → 26.1.2
_待移植时填写_
