# Curios 联动:装备位扩展点

状态:设计已定,未落地(09-24 暂停,等更大的统一设计)。Curios 源码:`D:\01_Projects\Curios`(1.21.1 分支 9.5.1)。

## 一、要解决的问题

1. **核心 `gear wear` 假成功**。`EquipCompanionTask.onStart` 的流程是:
   - 先 `player.gameMode.useItem(...)`(右键)。Curios 饰品默认不响应右键;只挂了 `curios:*` 标签、没有 `ICurio` 能力的饰品,右键永远戴不上。
   - 右键没穿上,就退回 `resolveSlot`,等于 `getEquipmentSlotForItem`。非盔甲一律得到 `MAINHAND`。
   - 最后回报 "holding … in main hand",算成功。
2. **同一个根子上的问题**:
   - 成功判据是"背包少一个"。右键雪球会被扔出去,右键水桶或岩浆桶会倒进世界,都报装备成功。这里的 `useItem` 是裸调,**绕过了权限层去改世界**。
   - `findItem` 遍历 41 格,包括身上穿着的。
   - `UnequipCompanionTask` 脱甲时不检查绑定诅咒。
3. **Curios 联动本身**:整合包里的饰品要能戴上、真生效,模型也要知道自己戴着什么。

用户的约束:工具表每轮全量发,联动不能靠加工具;要解耦、用组合、只有一条路径。

## 二、判据:为什么是扩展点而不是新工具

开扩展点要同时满足三条:
- 动词已有(装备 / 卸下);
- 模组只是加名词(新的穿戴位置);
- 意图不变("把这件穿上")。

原版的实现也作为提供者之一,走同一扇门,不写"原版先决定、模组兜底"。

## 三、接口(api/common,`com.dwinovo.numen.api.gear`,在瘦 api jar 的 `api/**` 内)

```java
/** 一处能把东西穿戴在身上的来源:原版四件甲是一处,Curios 饰品栏是一处。
 *  gear wear / gear remove 的穿、脱、自动选位,<worn> 状态,都只经这里。
 *  来源只陈述游戏规则,不做许可裁决(穿戴不改世界、不伤实体)。只在服务端主线程调用。 */
public interface GearSource {
    /** 这具身体此刻有的位置,顺序固定(自动选位按它)。句柄只在本次调用内有效。 */
    List<GearSlot> slots(NumenPlayer body);
    /** 按这处来源的规矩,这件该戴在哪类位置(与 GearSlot.name 同名),不管身上有没有。
     *  空集 = 不归这处管。用来区分"该戴却戴不上"(如实失败)和"不是穿戴物"(交给手)。 */
    Set<String> kindsOf(NumenPlayer body, ItemStack stack);
}

public interface GearSlot {
    /** 模型写在 slot 参数里的名字;同类多格同名。原版用裸名(head…),其它来源带 mod id 前缀(curios:ring)。 */
    String name();
    ItemStack worn();                          // 空栈 = 空位
    Optional<String> refuseWear(ItemStack one); // 空 = 能放;否则是给模型看的真实原因
    Optional<String> refuseRemove();            // 空位返回空
    ItemStack swap(ItemStack in);               // 放入 in(EMPTY = 摘空),返回原来那件;不碰背包
}
```

- `NumenApi.registerGear(GearSource)` 由 `NumenPlugins` 实现,内部用 `CopyOnWriteArrayList`。登记顺序就是自动选位的优先级。
- 引擎内部入口是 `gearSlots(body)` 和 `gearKinds(body, stack)`。
- 服务端要用,所以插件要在 `register` 块里直接调,不能放进 `onClient`。
- `kindsOf` 是必需的:没有它,自动模式只能二选一——要么"没位置就拿手上"(就是现在的 bug),要么"没位置就失败"(`gear wear stone_pickaxe` 就不能用了)。

## 四、原版提供者(core)

- `VanillaArmor` 用 `body.inventoryMenu` 里真实的四个 `ArmorSlot`,顺序是 `ARMOR_SLOT_START + i` 对应 HEAD、CHEST、LEGS、FEET。
  - 放入用 `mayPlace`(NeoForge 改写成 `stack.canEquip`,Fabric 用原版判据),取出用 `mayPickup`(绑定诅咒),写入用 `setByPlayer`(触发 `onEquipItem`,有音效)。
  - 各加载器用各自的原版规则,通用代码不按加载器分支。
- `NumenCore.init` 先于 `Builtin.registerAll`,所以原版总是排在最前。
- **主手、副手不是装备位。** 主手是在 36 格里选中一格,不是把东西搬出背包;手里拿的已经在 `<inventory>` 的 holding 行里报了,再算进 `<worn>` 就成了同一事实的两个来源。

## 五、工具与任务

**穿(重写 `EquipCompanionTask.onStart`)**
- 删掉 `useItem`、`resolveSlot` 以及跟它们配套的辅助方法。
- 只在 36 格里找货源。
- `mainhand`、`offhand` 保持现状。
- 显式给了 `slot`:在这个名字的格子里挑;没有这个名字就失败,并列出她实际拥有的槽。
- 省略 `slot`:
  - `gearKinds` 为空 → 交给手(盾进副手,其余进主手);
  - 不为空但身上没有这类格 → 如实失败并说明原因。
- 先找收它的空位;没有空位,再找收它、而且原物摘得下的格子去换。
- 满包要在动手之前拒绝(`NO_SPACE`),绝不把东西掉在地上。
- 失败文字来自 `refuse*`。`FailureType` 用 `UNKNOWN` / `NO_SPACE`,不用 `REFUSED`,那一档专指权限层拒绝。
- 回执里的 `slot` 字段取 `target.name()`。

**脱(`UnequipCompanionTask`)**
- 按 `slot` 名,`armor` 别名在 core 里展开成四个原版名;或者按 `item_id`,从戴着它的第一格摘。
- 每一格依次:先问 `refuseRemove` → 查背包有没有空位 → `swap(EMPTY)` → `inv.add`。
- 顺带修掉绑定诅咒的问题。

**参数与描述**
- `slot` 从枚举改为 `optionalString`:可写 `mainhand`、`offhand`、`<worn>` 里列出的名字,卸下时还可写 `armor`。
- 描述是固定文字,不随环境变,不打碎 prompt 缓存。槽名清单每轮随 `<worn>` 下发,不做成动态枚举。

## 六、状态:`<worn>`

```
<worn>head: minecraft:iron_helmet; chest: empty; legs: empty; feet: empty; curios:ring: minecraft:gold_ring, empty; curios:necklace: empty</worn>
```

- 由 api 渲染,作为 `BODY_STATE` 的第一个片段,复用 `joinFragments` 的出错隔离。插件不需要各自再调 `contributeBodyState`。
- **空位也列出**(用户已定):这是模型认识自己有哪些槽名的唯一来源,每轮大约多 20–60 token。
- 只写物品 id,不写耐久和组件。`CompanionStateWatch` 按整段字符串做差分,所以耐久变化不会推包。
- `get_self_status` 的 `equipment` 只保留两只手。

**第一版不加饰品变化事件。** "戴着什么"是状态;自己穿脱有回执;外部变化下一轮 `<worn>` 会如实反映。以后真要加事件:
- 在 api 层对"槽名 → 物品 id"的快照做差分,不监听 `CurioChangeEvent`;
- 出生第一份快照只记录、不发;
- 自己装备任务那一刻产生的差分不发;
- 死亡交给 death 事件。

## 七、Curios 插件(`plugins/curios/`,只做 NeoForge,三个类,不注册工具也不注册事件)

- **`NumenCurios`**:`install(skills)` 里调 `NumenPlugins.register(n -> n.registerGear(new CuriosGear()))`。
- **`CuriosGear`**:
  - `slots`:遍历 `CuriosApi.getCuriosInventory(body).getCurios()`,跳过 `!isVisible()` 的,按 `ISlotType.getOrder()` 排序后逐格展开。
  - `kindsOf`:物品本身得是饰品(有 `getCurio(stack)` 能力,或带 `curios:*` 标签)才认领,返回 `getItemStackSlots(stack, level)` 的键加 `curios:` 前缀。这样整合包用 `curios:all` 定义的"什么都收"的槽,就不会把镐子塞进去。
- **`CurioGearSlot`**:
  - `refuseWear`:槽被停用时拒绝(inactive),否则看 `IDynamicStackHandler.isItemValid`;
  - `refuseRemove`:`extractItem(i, n, true)` 为空就拒;
  - `swap`:`setStackInSlot`;放入非空且槽启用时,补调 `ICurio.onEquipFromUse`,和 `CurioSlot.set` 一致;只放一件。
  - 这三个方法和玩家在界面里拖动是同一条校验路径(`CurioSlot` 继承 `SlotItemHandler`,`DynamicStackHandler` 里依次走标签断言、`ICurio.canEquip`、`CurioCanEquipEvent`,以及 `CurioCanUnequipEvent`、绑定诅咒、`canUnequip`)。
- **只用 `top.theillusivec4.curios.api` 包。** `CuriosApi` 的静态方法是空桩,实现由 Mixin 注入,模组不在场时不能调,靠 Gate 的延迟加载保证。
- **构建**:
  - 仓库 `maven.theillusivec4.top`,用 `exclusiveContent` 只放 `top.theillusivec4.curios`;
  - `compileOnly "top.theillusivec4.curios:curios-neoforge:${curios_version}:api"`,加上 `:api:neoforge` 的 `apiJar`;
  - `curios_version=9.5.1+1.21.1`;
  - `settings.gradle` 里 include,`core/neoforge` 里 `compileOnly` 并加进 jar;
  - `Builtin.java` 里 `gate.open("curios", "curios", …)`。
- **第一版不带技能文档。**

**Curios 已核实的事实**
- 假玩家按 `EntityType.PLAYER` 拿到同一套槽;整合包没给玩家分槽时,饰品栏能力不会挂上。
- 属性和效果挂在 `EntityTickEvent.Post` 上,同伴的 `tick()` 会调 `doTick()`。
- attachment 随存档保存(`copyOnDeath`)。
- `CurioChangeEvent` 的差分连组件一起比,所以耐久变化也会触发;读档后第一刻会整批发一遍;缩槽时漏报;槽被停用时会发 `to=EMPTY`,但东西其实还在。

**Fabric 以后接**:Trinkets(`TrinketsApi` / `Trinket.canEquip`)和 Accessories(`AccessoriesAPI.canInsertIntoSlot`)都能对上这组接口,槽名写成 `trinkets:hand/ring`、`accessories:ring`。缺的是 Loom 联动构建的约定。

## 八、行为变化(用户知情)

- 带绑定诅咒的甲从此脱不下来。
- 靠右键才能装上的模组物品(既不是 `Equipable`,也不走 Curios)不再碰巧装上,改为拿在手上或如实失败。
- 删掉 `useItem` 后,倒水、扔雪球这条绕过权限层的路径消失。

## 九、另起一个提交:遣散时饰品丢失

`DismissRequestPayload` 只 `getInventory().dropAll()`,饰品随身体一起消失。改为遣散时对所有 `gearSlots` 执行 `swap(EMPTY)` 并掉落,并按现有方式报告。

## 十、测试

**常驻 GameTest**:照 `PluginGameTests` 的写法,在测试里经那扇门登记一个假的 `GearSource`,`slots` 和 `kindsOf` 只对本测试同伴的 UUID 生效。覆盖:
- 自动选位,回执写出槽名;
- 认领了却被拒:失败、主手不变、背包没少;
- 认领了但身上没有这类槽;
- 写了不存在的槽名,失败并列出她实际拥有的槽;
- 按槽名卸下、按 `item_id` 卸下;
- 满包时拒绝卸下;
- `refuseRemove` 拒绝时如实失败;
- `body_state` 含 `<worn>`。

**原版回归**:
- 现有的头盔、南瓜、换旧头盔、卸甲、满包几条保持绿色;
- 新增水桶(不倒水)、雪球(不扔出)、绑定诅咒头盔(卸下被拒)、盾(自动进副手);
- 水桶、雪球两条先在旧代码上跑红,作对照。

**真 Curios 对照**(验完撤掉依赖):
1. 临时 `runtimeOnly curios-neoforge:9.5.1+1.21.1`,日志的 Mod List 里确认看到 curios。
2. 临时数据包:给 player 分 ring、necklace 两个槽;金粒标成 ring;绿宝石标成 charm(这个槽不分给玩家)。放进 GameTest 世界的 datapacks,不进正式资源。
3. 旧代码跑一遍,复现假成功。
4. 新代码跑一遍,检查:
   - 进了 `curios:ring`;
   - `<worn>` 里有这一项;
   - 卸下后回到背包;
   - 绿宝石失败;
   - 休眠唤醒后仍然戴着;
   - 死亡掉落,不复制。

## 十一、顺带发现的无关问题(未改)

- `ReflexRegistry` 的提示词说 `item_id` 传 "auto" 可以解除钉住,但这并没有实现。
- `NumenPrompts` 说 `get_self_status` 返回完整背包,但实际不列背包。
- `InventoryGameTests` 的注释还写着"走原版右键换装"。
