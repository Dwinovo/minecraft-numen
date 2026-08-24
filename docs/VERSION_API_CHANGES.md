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
