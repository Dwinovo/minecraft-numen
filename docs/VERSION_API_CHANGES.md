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

> ⚠ 哑气泡 mixin：气泡挂载点必须是 LivingEntityRenderer.render(渲染状态四参)——PlayerRenderer 在 1.21.2+ 不再自己实现 render，注旧签名无头测试照不出、真机 require=1 必炸（官方 mappings 逐字核过签名）。
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

### 1.21.4 三次对齐(0.1.4.1 → 1.21.1@19c8710b0 + 对齐期通用修复)

整树换成 1.21.1(目录已是 `numen-api/`、`numen/`、`numen/pathing`,另有 `mcp`、`permission`、`script`、`store`、Paper 端),再逐处重放。上面两节已记的不再重复,只记这一轮新碰到的与做法变了的。

**构建**
- paperweight-userdev 1.7.2 认不得 1.21.4 的开发包(新格式,`paperweightDevelopmentBundle` 解析出多个文件就报错),换 `2.0.0-beta.22`(最后一个只要 Gradle 插件 API 9.0 的;beta.24 要 9.7)。`reobfArtifactConfiguration`、`paperDevBundle` 写法不变。
- slf4j 声明跟游戏走到 2.0.16;Gson 仍编译对着 2.10.1:游戏带的是 2.11.0(向后兼容),而 2.11.0 的 POM 依赖 `error_prone_annotations`,它进了开发期运行的类路径以后和游戏那份在模块层撞名(`Module org.reactivestreams reads more than one module named com.google.errorprone.annotations`),NeoForge 的 runData 与 GameTest 服务器起不来。
- `@EventBusSubscriber` 在 FML 6 不按事件类型挑总线,`GatherDataEvent.Client` 的订阅要写明 `bus = MOD`(否则 "IModBusEvent events are not allowed on the common NeoForge bus")。
- Litematica/MaLiLib 的 masa maven 坐标按 MC 版本分(`litematica-fabric-1.21.4`),build.gradle 里的坐标后缀改用 `${minecraft_version}`。

**注册表、高度、方向**:同上两节(`getValue`、`lookupOrThrow`、`getOrThrow`;`getMinY`/`getMaxY`,要"不含端"的写成 `getMaxY() + 1`,比较直接改 `<= getMaxY()`;`getApproximateNearest`、`getNearest(..., NORTH)`)。新增:`Registry.getTag(TagKey)` → `get(TagKey)`;`registry.get(id)` 返回 `Optional<Holder.Reference>`,要值用 `getValue`;`Structure.generate` 首参要 `Holder<Structure>` 与维度键;`RegistryDataLoader.load` 吃 `List<RegistryLookup<?>>`(`fromRegistryOfRegistries(...).listRegistries().toList()`);`DirectionProperty` 没了,朝向属性是取值为 `Direction` 的 `EnumProperty`。

**配方(配方书 RecipeBook、CraftOps、RecipeProbe 按 Recipe 接口泛读)**
- 产物没有 `getResultItem` 了,改读配方的展示:`recipe.display()` 里产出那格 `result().resolveForFirstStack(ContextMap{REGISTRIES})`,原版配方书也读它,模组配方同样走这条;不用空输入 `assemble`(那是出合同的问法)。
- 配料表一律 `placementInfo().ingredients()`;摆不进格(`isImpossibleToPlace`)的合成配方当产出依赖输入的那种跳过;锻造三个槽是 `Optional<Ingredient>`,认不认一样东西用 `Ingredient.testOptionalIngredient`;切石机界面列的是可选项(`getVisibleRecipes().entries()`),每项的产物读 `optionDisplay()`。
- 服务端按类型取配方没有公开口:`recipeAccess().getRecipes()` 全表走一遍,用 `RecipeBook.isCrafting` 筛。

**玩家与服务端**
- ❗ `hasClientLoaded`:假时服务端不收动作包与玩家指令(展开鞘翅、起床、开坐骑物品栏都走玩家指令),玩家也不受伤(`kill` 无效)。FakeClient 收到 `LEVEL_CHUNKS_LOAD_START` 记下;加入与重生两条原版流程一走完就报"加载好了"(`arrived`、`respawn`,重生时要等连接换上新身体再报,当场报会落在旧身体上),换维度这类中途发来的照传送编号晚一刻回。她一到就能动手、也能挨打,和 1.21.1 一样。寻路的 GameTest 夹具 `TestBody` 不经 `placeNewPlayer`,建连接后当场报。
- 中键取物没有"指名背包第几格"的包了(1.21.2 起中键包只说对着哪个方块或实体,服务端自己找格)。`ClientPackets.pickItem` 照原版找到格以后的三步写:选中合适的快捷栏格并对换、发 `ClientboundSetHeldSlotPacket`、`inventoryMenu.broadcastChanges()`;创造模式凭空取一叠是 `pickNew`(原版 `addAndPickItem` 那几行)。两处都不调 `Inventory.pickSlot`/`addAndPickItem`:Paper 给它们加了目标格参数,签名两边不同。
- `ServerPlayer.getPermissionLevel` 变 public;`shouldInformAdmins` 不再对外,她自己的指令来源问 `commandSource().shouldInformAdmins()`;`MinecraftServer.tell` → `schedule`;`Level.getGameRules` 只在 `ServerLevel` 上;`Item.getDescription` → `getName`;`ItemCooldowns.isOnCooldown(ItemStack)`;`LocalPlayer.sendSystemMessage` → `displayClientMessage(c, false)`。
- 鞘翅"没坏"是 `!stack.nextDamageWillBreak()`(`ElytraItem` 没了);展开、寻路的滑翔物判断、`numen.use.glide` 的拒绝原因三处同此。
- 右键后挥不挥手照原版服务端:`result instanceof Success s && s.swingSource() == SERVER`(Mouse 里一处小方法)。
- 用例里:`EntityType.X.create(level, EntitySpawnReason.STRUCTURE)`(照原版 `GameTestHelper.spawn`)、木船 `EntityType.OAK_BOAT` + `moveTo`、`kill(level)`、`doHurtTarget(level, target)`、`teleportTo(level, x, y, z, Set.of(), yaw, pitch, true)`、`TeleportTransition`/`teleport`、`ClientInformation` 多一个 `particleStatus`。

**NeoForge**:服务端重载监听 `AddReloadListenerEvent` → `AddServerReloadListenersEvent.addListener(键, 监听)`。

**客户端**:同上两节(`GuiAlpha`、`blitSprite(RenderType::guiTextured, …)`、`Sprites` 着色走顶点色、`ToastManager`、`MixinLivingEntityRenderer`、`ClientInput.keyPresses`、`ShapeRenderer.renderLineBox`)。新增:`PlayerFaceRenderer.draw` 的 ResourceLocation 版补 `(hat=true, upsideDown=false, tint=-1)`。

**Paper(本轮新带)**:`Entity.getBukkitSender` 没了,指令来源有实体时取 `getBukkitEntity()`(本身就是 CommandSender);`DiscardedPayload` 装 `byte[]`;`CraftScheduler.mainThreadHeartbeat()` 无参;CraftingMenu 重算结果的反射签名里关卡参数是 `ServerLevel`。

**JEI 20(1.21.4)**:类别的类型是 `IRecipeType`(`RecipeType` 标了待删);配方的工作台查 `createCraftingStationLookup`(`createRecipeCatalystLookup` 待删)。

**GameTest 坐标**:1.21.2 起 `GameTestHelper.absolutePos` 从内容原点起算(1.21.1 从内容下面那一格的结构方块起算),场地本身的摆法(内容从网格点起、结构方块在它下面一格、`clearSpaceForStructure` 在内容底下一格以下填石头)没变。上两节的做法是给模板垫一层(复刻原 y0 层),坐标对上了,场地却多了一层实心:地板变两层、地板下面的空格没了。这一轮实测两种垫法都和 1.21.1 不等价——复刻垫层让 `a_walk_refused_at_the_owners_wall_stops_there` 里她踩着多出来的实心从墙底下爬过去、又问了一遍地板;垫空层则让地板下面空出两格(1.21.1 是一格空、再往下是石头),`interact_own_vehicle_ends_immediately` 的水漏下去、`look_around_draws_walls_steps_and_water` 的图变了。所以模板一律不动,改由 GameTest 夹具的 `GameTestHelperOriginMixin` 把 `GameTestHelper` 换算坐标的四处(`absolutePos`、`relativePos`、`absoluteVec`、`relativeVec`)起点换回结构方块,场地与坐标都与 1.21.1 一模一样。Paper 上没有 Mixin,Paper 的 GameTest 在这一版上坐标差一格(见遗留)。

**插件**:Curios(NeoForge,`10.0.1+1.21.4`)、Litematica(Fabric `0.21.7`/MaLiLib `0.23.5`;NeoForge 上是 Forgematica)、JEI(`20.0.0.4`,只有 NeoForge 构建,所以只接 NeoForge,Fabric 的 Builtin 与依赖去掉)。车万女仆、YSM、森罗物语没有 1.21.4 版;FTB Quests 的 maven 没有 2104;KubeJS 的 maven 从 2101 直接到 26.1:都不带。

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
