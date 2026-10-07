# 她的输入层(设计稿)

状态:定稿(2026-10-07),0.1.5 寻路重构的第一步。

## 一、要解决什么

同伴是一个没有客户端的服务端玩家。真人玩家手里的东西——键盘、视角、鼠标、快捷栏——她都要有,而且**只此一份**、谁驱动她都用同一份。
现在这份东西被拆在三处:

| 能力 | 现在在哪 | 第三方插件拿得到吗 |
|---|---|---|
| 键盘(前后左右、跳、潜行、疾跑) | Numen API `entity/Controls` | 能 |
| 每刻物理结算 | Numen API `entity/Physics`(`NumenPlayer.tick` 里自动跑) | 不需要拿 |
| 快捷栏、换手 | Numen API `entity/Hotbar` | 能 |
| 视角:看向一点、转向一个方向 | 一半在 Numen API `entity/InputDriver`,一半在 pathing `body/Aim` | 只有一半 |
| 鼠标:左键按住挖(带进度)、右键用 | pathing `body/PlayerHands`(端口 `body/Effector`) | 不能 |
| 准星落在哪 | pathing `body/Crosshair` | 不能 |
| 挖一格要几刻、挖完缓几刻 | pathing `plan/DigTime` | 不能 |
| 动手前过权限层 | Numen `nav/CompanionHands`(包着 `PlayerHands`) | 不能,也**绕得过去** |

后果:

1. **第三方只能让她走,不能让她动手。** 一套第三方寻路要挖路、搭桥,只能把原版的挖掘循环再抄一遍——"机制只此一份"破了。
2. **Numen 的通用工具反过来依赖寻路模块。** 挖矿、交互、建造、MLG 落水这些与寻路无关的东西都 import 了 pathing 的 `Aim`、`Crosshair` 和那双手。鼠标是身体的,不是寻路的。
3. **权限的关卡放错了层。** 权限层在 Numen API,可"动手必须先过权限"由 Numen 的 `CompanionHands` 保证。别人自己 new 一双手就能绕过去。关卡应当长在身体上,谁驱动她都绕不开。
4. **换人驱动时松键靠自觉。** `CompanionBrain` 每刻选出一个驱动者,换人时只调旧驱动者的 `stop(PREEMPTED)`,松不松键由各个任务自己记得做。

## 二、主流怎么做

| 项目 | 身体这一层 | 寻路怎么用它 |
|---|---|---|
| **Mineflayer** | 机器人本体提供 `setControlState` / `clearControlStates`(键盘)、`look` / `lookAt`(视角)、`dig` / `stopDigging` / `digTime`(左键)、`placeBlock` / `activateBlock` / `activateItem`(右键)、`equip` / `setQuickBarSlot`(快捷栏)、`blockAtCursor`(准星) | `mineflayer-pathfinder` 是独立插件,只调本体这些接口 |
| **Carpet 假玩家** | `EntityPlayerActionPack`:use / attack / jump / sneak / sprint / 视角 / 快捷栏,每个动作可以按一次、按住、按间隔 | 没有寻路,但这层就是"真人能按的东西" |
| **Baritone** | `InputOverrideHandler`(键盘与左右键统一成一组输入)、`LookBehavior`(视角)、`PlayerController`(挖与用) | 寻路只往输入层写输入 |

共同点:**有一层统一的"玩家输入",粒度就是真人能按的东西;寻路是建在它上面的一个使用者。** Mineflayer 的结构和我们"Numen 是 Numen API 的插件"一一对应。

## 三、设计

### 1. 一层,四件,都挂在她身上

全部放进 Numen API 的公开包 `com.dwinovo.numen.api.entity`,和 `NumenPlayer`、`FakeClient` 同处。它们补的都是"她没有客户端"的那一半。

```java
NumenPlayer her;
her.controls()  // 键盘:现有的 Controls,不变
her.look()      // 视角:看向一点、转到一个朝向、水平转身;看向一格方块上她看得见的那一点
her.mouse()     // 鼠标:准星拾取;左键按住挖(带进度,松开清零);右键点一下/按住
her.hotbar()    // 快捷栏:现有的 Hotbar(拿起一格、换副手、创造模式取物),每次换了都交回 BodyAction
```

- **粒度就是真人的输入**,不多不少。"挖掉那一格""放一块到那里"这类组合动作不进这一层——那是使用者(寻路、Numen 的工具、第三方)用这四件组合出来的。
- **鼠标只对准星起作用。** 和真人一样:先 `look()` 转过去,再按。`mouse()` 自己做准星拾取,不收调用方给的命中结果——"手点到了哪一格"只有一个出处。
- **每一下都如实交回结果**:左键交回"还在挖 / 挖掉了(原来是什么)/ 被拒(理由)",右键交回"世界变了哪些格 / 打开了一个界面 / 什么都没发生 / 被拒(理由)"。即现在 `Effector.Strike` / `Effector.Use` 那一套,右键补上"打开了界面"这一种。
- **右键点格子、点空气、点实体是同一个右键**,结果是同一种类型。`numen.use.block` / `item` / `entity` 都从这一个结果写回执:打开了界面就交回界面,三个函数不会再各自声明一套、彼此对不上(#118 就是 `use.item` 声明里漏了"打开界面"这一种)。

### 2. 权限长在鼠标上

- 左键换到新的一格时问"能不能拆这一格";右键手上的东西会往世界里放东西时问"能不能放",否则问"能不能用这一格"。即现在 `CompanionHands` 的规则,原样搬进 `mouse()`。
- 要问主人的时候,鼠标停下、交回"待问"(裁决连同要问的那一条),由驱动者决定怎么问(寻路是走到那一格停下问)。
- **判据只认身体是谁**:身体是同伴就过权限层;不是同伴的普通假玩家(pathing 自己的 `TestBody`)照原版。一个类,不分两份。
- `CompanionHands` 删掉。它另一个职责"把挖这种方块最快的工具拿到手上"(`takeToolFor`)是挑工具的策略,留在 Numen(或 pathing 的 `ToolChoice` 旁边),用 `hotbar()` 去换。

### 3. 原版公式归身体,只有一个出处

- **挖掘时间**:原版的挖掘进度公式(工具、效率、急迫、在水里、离地)与挖完的冷却搬进 Numen API,公开成一个查询(对照 Mineflayer 的 `digTime`)。鼠标按它推进度,寻路的代价模型与 `ToolChoice` 也调它,不再各算一份。
- **够得着、看得见**:玩家的交互距离、从眼睛到一点的视线检查、一格方块哪几面露在外面,是身体事实。`look()` 要"看向一格上看得见的那一点",就得用它们;寻路规划"站在这格能不能挖到那格"也用它们。搬进 Numen API 作为唯一出处,pathing 改为引用(见下面待定 2)。
- 规划用的身体快照(`Snapshots` / `BodySnapshot`)是寻路自己的,留在 pathing。

### 4. 换人驱动时由大脑统一松手

`CompanionBrain` 换驱动者的那一刻,在调旧驱动者的 `stop(PREEMPTED)` 之后,**统一把键盘和鼠标松开**(键全松、挖掘进度清零、右键松开)。新驱动者从一具干净的身体开始。

不另做"这一刻输入归谁"的仲裁:`CompanionBrain` 已经每刻只选一个驱动者(反射 > 同步 > 当前任务 > 空闲姿态,层内固定先后),仲裁本来就在那里,输入层不重复一份。

### 5. pathing 怎么接

- 身体端口 `Body` 留在 pathing,但不再要另一个端口 `Effector`:`Body` 交出那具玩家,寻路直接用 Numen API 的 `controls()`、`look()`、`mouse()`、`hotbar()`。少一个端口,`Ports` 里只剩寻路自己的策略(地形许可、垫路料、危险)。
- `TestBody` 照旧是普通假玩家,用同一套输入层(不过权限层)。
- `Aim` 里"挑哪一点、哪一面"的规划部分(比如为放一块挑贴着的面)留在 pathing;"把头转过去"的部分并进 `look()`。

### 6. 对外的效果

- 第三方寻路能挖、能放:CI 里那个"第三方寻路"样例补上"挡路就挖开"一步,只用公开 API,守住这一层确实够用。
- Numen 的挖矿、交互、建造、MLG 这些工具改用 `her.mouse()` / `her.look()`,不再 import pathing 的身体类。

## 四、分步落地

每一步单独能构建、单测 + GameTest 全绿;都不改行为。

1. **原版公式与几何进 Numen API**:挖掘时间与冷却、交互距离、视线、露面。pathing 与 Numen 改为引用,删掉原来的。
2. **`look()`**:合并 `InputDriver` 的看/转身与 `Aim` 的转头部分;删掉两边的重复。
3. **`mouse()`**:`Crosshair` + `PlayerHands` 搬进 Numen API,权限规则从 `CompanionHands` 并进来;删 `CompanionHands`、pathing 的 `Effector` 端口;寻路、Numen 工具、`TestBody` 改用它。
4. **换驱动者统一松手**:`CompanionBrain` 在换人时清空输入;删掉各任务里只为"被顶掉时松键"而写的代码。
5. **第三方样例补挖路**,文档(`architecture-mind-model.md`、`pathing.md`)改成现状。

验收:单测与 GameTest 条数不减;原版与车万女仆评测自检通过;瘦 jar 含这四件且第三方样例只靠瘦 jar 编过;Numen 的 `numen/common` 不再 import `com.dwinovo.numen.pathing.body` 下除 `Body` 之外的任何类。

## 五、已定

1. **右键按住进 `mouse()`**:点一下与按住(拉弓、吃喝、举盾、`use.block --hold`)是同一个右键的两种按法,和左键按住挖一样由鼠标管;Carpet 的"按一次 / 按住"也是这个粒度。
2. **几何只搬身体与规划共用的那一组**:`Reach`、`Sight`、`Faces`、`Replaceable` 进 Numen API;`Footing`、`Stepping`、`Clearance` 等纯规划的留在 pathing。
3. **换驱动者统一松手**:先用 GameTest 核一遍有没有任务依赖"被顶掉期间键还按着",有就改那个任务,不给统一松手开例外。
