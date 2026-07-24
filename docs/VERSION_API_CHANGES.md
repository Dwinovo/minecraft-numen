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

## 1.21.5 → 1.21.8 ✓（已验证，双 loader 编译 + 出包 + 222 测试全绿）

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

## 1.21.10 → 1.21.11
_待移植时填写_

## 1.21.11 → 26.1.2
_待移植时填写_
