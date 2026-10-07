# 她的输入层

状态:已落地(2026-10-07),0.1.5 寻路重构的第一步。

## 一、要解决什么

同伴是一个没有客户端的服务端玩家。真人玩家手里的东西——键盘、视角、鼠标、快捷栏——她都要有,而且**只此一份**、谁驱动她都用同一份。
落地之前这份东西被拆在三处,落地之后全在 Numen API 的 `com.dwinovo.numen.api.entity`:

| 能力 | 在哪 | 第三方插件拿得到吗 |
|---|---|---|
| 键盘(前后左右、跳、潜行、疾跑) | `Controls`,`NumenPlayer.controls()` | 能 |
| 每刻物理结算 | `Physics`(`NumenPlayer.tick` 里自动跑) | 不需要拿 |
| 视角:看向一点、转到一个朝向、看一格方块上看得见的那一点 | `Look`,`NumenPlayer.look()` | 能 |
| 鼠标:准星拾取、左键按住挖(带进度)、右键点一下/按住 | `Mouse`,`NumenPlayer.mouse()` | 能 |
| 快捷栏、换手 | `Hotbar`,`NumenPlayer.hotbar()` | 能 |
| 挖一格要几刻、挖完缓几刻 | `DigTime` | 能 |
| 够得着、看得见、点得中哪一面、放下去落在哪一格 | `Reach`、`Sight`、`Faces`、`Replaceable` | 能 |
| 动手前过权限层 | 鼠标自己:身体是同伴就过,**绕不过去** | 能(也绕不开) |

这样解决的四件事:

1. **第三方不只能让她走,也能让她动手。** 一套第三方寻路要挖路、搭桥,用的就是她的鼠标,不必把原版的挖掘循环再抄一遍——"机制只此一份"。
2. **Numen 的通用工具不再依赖寻路模块。** 挖矿、交互、建造、MLG 落水这些与寻路无关的东西用的是 `her.mouse()`、`her.look()`;`numen/common` 对
   `pathing.body` 只剩端口 `Body`。鼠标是身体的,不是寻路的。
3. **权限的关卡长在身体上。** "动手必须先过权限"由鼠标保证,别人自己 new 一个同伴的鼠标也绕不过去。
4. **换人驱动时松键不靠自觉。** `CompanionBrain` 换驱动者时统一松开键盘与鼠标。

## 二、主流怎么做

| 项目 | 身体这一层 | 寻路怎么用它 |
|---|---|---|
| **Mineflayer** | 机器人本体提供 `setControlState` / `clearControlStates`(键盘)、`look` / `lookAt`(视角)、`dig` / `stopDigging` / `digTime`(左键)、`placeBlock` / `activateBlock` / `activateItem`(右键)、`equip` / `setQuickBarSlot`(快捷栏)、`blockAtCursor`(准星) | `mineflayer-pathfinder` 是独立插件,只调本体这些接口 |
| **Carpet 假玩家** | `EntityPlayerActionPack`:use / attack / jump / sneak / sprint / 视角 / 快捷栏,每个动作可以按一次、按住、按间隔 | 没有寻路,但这层就是"真人能按的东西" |
| **Baritone** | `InputOverrideHandler`(键盘与左右键统一成一组输入)、`LookBehavior`(视角)、`PlayerController`(挖与用) | 寻路只往输入层写输入 |

共同点:**有一层统一的"玩家输入",粒度就是真人能按的东西;寻路是建在它上面的一个使用者。** Mineflayer 的结构和我们"Numen 是 Numen API 的插件"一一对应。

## 三、设计

### 1. 一层,四件,都挂在她身上

全部在 Numen API 的公开包 `com.dwinovo.numen.api.entity`,和 `NumenPlayer`、`FakeClient` 同处,都进瘦 jar。它们补的都是"她没有客户端"的那一半。

```java
NumenPlayer her;
her.controls()  // 键盘:Controls
her.look()      // 视角:at(点)、turn(朝向)、faceToward(x, z);point/digPoints/use/face:看向一格方块上她看得见的那一点或那一面
her.mouse()     // 鼠标:pick/on/itemRay 准星;dig/release 左键;use/useItem/stopUse 右键
her.hotbar()    // 快捷栏:hold(格)、grip(东西),每次换了都交回 BodyAction
```

四件都是"一具服务端玩家 + 它的状态"的小类,`new Look(player)`、`new Mouse(player)`、`new Hotbar(player)` 就能给一具普通的假玩家用(寻路模块自己的
`TestBody` 就是这样)。

- **粒度就是真人的输入**,不多不少。"挖掉那一格""放一块到那里"这类组合动作不进这一层——那是使用者(寻路、Numen 的工具、第三方)用这四件组合出来的。
- **鼠标只对准星起作用。** 和真人一样:先 `look()` 转过去,再按。`mouse()` 自己做准星拾取,不收调用方给的命中结果——"手点到了哪一格"只有一个出处。
  调用方要的是某一格时,先问 `on(pos)`(准星此刻落在那一格上才交出那一下),再按。
- **每一下都如实交回结果**:左键交回 `Strike`(还在挖 / 挖掉了(原来是什么)/ 被拒(理由)/ 准星没落在方块上),右键交回 `Use`(缓手没按 /
  按了(准星落着什么、世界变了哪几格、有没有打开一个界面)/ 被拒(理由))。
- **右键点格子、点空气、点实体是同一个右键**,结果是同一种 `Use.Pressed`。`numen.use.block` / `item` / `entity` / `hit` 都从这一个结果写回执:
  打开了界面就交回界面(`Clicks.Pressed` = 界面 | 点击),几个函数不会再各自声明一套、彼此对不上(#118 就是 `use.item` 声明里漏了"打开界面")。
- **右键按住进鼠标**:点一下与按住(拉弓、吃喝、举盾、`use.block --hold`)是同一个右键的两种按法,和左键按住挖一样由鼠标管;两次按下的
  间隔(原版 4 刻)是鼠标的事,驱动者每刻按就行,松开是 `stopUse()`。要的只是"用手里的东西"、不管准星落着什么(吃、喝、桶找水)用 `useItem()`
  (对照 Mineflayer 的 `activateItem`)。

### 2. 权限长在鼠标上

- 左键换到新的一格时问"能不能拆这一格";右键手上的东西会往世界里放东西(`Mouse.placing`)时问"能不能放",否则问"能不能用这一格"。
- 要问主人的时候,鼠标停下、交回 `Refusal.Asks`(裁决连同要问的那一条),由驱动者决定怎么问(寻路是走到那一格停下问)。不许是 `Refusal.Denied`
  (裁决本身当理由);服务端把挖掘退回来是 `Refusal.Server`(身体汇报,不是权限裁决)。
- **判据只认身体是谁**:身体是同伴就过权限层;不是同伴的普通假玩家(寻路自己的 `TestBody`)照原版。一个类,不分两份。
- 原来包着手的 `CompanionHands` 删掉了。它另一个职责"把挖这种方块最快的工具拿到手上"是挑工具的策略,归 `ToolChoice.take(state, hotbar)`,
  寻路的执行与 Numen 的挖掘用同一处。

### 3. 原版公式归身体,只有一个出处

- **挖掘时间**:`DigTime` 是原版的挖掘进度公式(工具、效率、急迫、在水里、离地)与挖完的冷却,身体上的事实是 `DigTime.Mining`(`Mining.of(玩家)` 从
  真身上读)加游戏模式,所以不碰真实的身体也能算。鼠标按它缓手,寻路的代价模型与 `ToolChoice` 也调它,第三方估一格的价钱同样用它。
- **够得着、看得见**:`Reach`(眼睛到一格或一个碰撞箱的距离)、`Sight`(从眼睛到一点的视线,按轮廓、不看流体)、`Faces`(放方块时点哪个邻格的哪一面)、
  `Replaceable`(放下去落在哪一格),身体与寻路规划共用一份。`look()` 要"看向一格上看得见的那一点"用的是它们;寻路规划"站在这格能不能挖到那格"也是。
- 规划用的身体快照(`BodySnapshot`)与从身体抄快照的 `Snapshots`(`pathing.api`)是寻路自己的,留在 pathing。

### 4. 换人驱动时由大脑统一松手

`CompanionBrain` 换驱动者的那一刻,在调旧驱动者的 `stop(PREEMPTED)` 之后,**统一把键盘和鼠标松开**(键全松、挖掘进度清零、右键松开)。新驱动者从一具
干净的身体开始。

不另做"这一刻输入归谁"的仲裁:`CompanionBrain` 已经每刻只选一个驱动者(反射 > 同步 > 当前任务 > 空闲姿态,层内固定先后),仲裁本来就在那里,输入层不重复一份。
被顶掉的任务与反射的 `stop` 不再各自松键;被换掉(`REPLACED`)的任务不经这里,仍由自己的 `stop` 或收尾的 `cleanup` 松。

### 5. pathing 怎么接

- 身体端口 `Body` 留在 pathing,不再有另一个端口 `Effector`:`Body` 交出那具玩家与它的 `controls()`、`look()`、`mouse()`、`hotbar()`,寻路直接用
  Numen API 的这四件。`Ports` 里只剩寻路自己的策略(地形许可、垫路料、危险)。
- `TestBody` 照旧是普通假玩家,用同一套输入层(不过权限层);要观察或在动手那一刻动世界的用例派生一个 `Mouse` 换上去。
- 原来 `Aim` 里"挑哪一点、哪一面"并进了 `look()`:它们都是"她此刻的眼睛能看见、够得着的那一点",照 `Sight`/`Reach`/`Faces` 算;寻路规划站位时直接用这几份
  几何,不经过身体。

### 6. 对外的效果

- 第三方寻路能挖、能放:CI 里那个"第三方寻路"样例(`tools/third-party-nav`)带着"挡路就挖开"一步,只用瘦 jar 的公开 API,守住这一层确实够用。
- Numen 的挖矿、交互、建造、MLG 这些工具用 `her.mouse()` / `her.look()`,`numen/common` 不再 import pathing 的身体类(除端口 `Body`)。

## 四、分步落地(已完成)

每一步单独能构建、单测 + GameTest 全绿;都不改行为。

1. **原版公式与几何进 Numen API**:挖掘时间与冷却、交互距离、视线、露面、落点。pathing 与 Numen 改为引用,删掉原来的。
2. **`look()`**:合并 `InputDriver` 的看/转身与 `Aim` 的转头与瞄点;`hotbar()` 挂上身体。
3. **`mouse()`**:准星 + 两只手搬进 Numen API,权限规则从 `CompanionHands` 并进来;删 `CompanionHands`、pathing 的 `Effector` 端口;寻路、Numen 工具、`TestBody` 改用它;
   右键点一下是同一个右键、同一种结果。
4. **换驱动者统一松手**:`CompanionBrain` 在换人时清空输入;GameTest 核过没有任务依赖"被顶掉期间键还按着",删掉被顶掉时才松键的那几处。
5. **第三方样例补挖路**,文档改成现状。

验收:单测与 GameTest 条数不减;原版与车万女仆评测自检通过;瘦 jar 含这四件且第三方样例只靠瘦 jar 编过;Numen 的 `numen/common` 不再 import
`com.dwinovo.numen.pathing.body` 下除 `Body` 之外的任何类。

## 五、已定

1. **右键按住进 `mouse()`**:点一下与按住(拉弓、吃喝、举盾、`use.block --hold`)是同一个右键的两种按法,和左键按住挖一样由鼠标管;Carpet 的"按一次 / 按住"也是这个粒度。
2. **几何只搬身体与规划共用的那一组**:`Reach`、`Sight`、`Faces`、`Replaceable` 进 Numen API;`Footing`、`Stepping`、`Clearance` 等纯规划的留在 pathing。
3. **换驱动者统一松手**:先用 GameTest 核一遍有没有任务依赖"被顶掉期间键还按着",有就改那个任务,不给统一松手开例外。核过了:一条也没有。
