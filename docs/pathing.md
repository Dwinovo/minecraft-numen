# 寻路模块

状态:设计稿(2026-09-27),未动工。取代已删除的 `pathing-refactor-log.md` 与 `route-and-permission.md`;
权限层另见 `permission-layer.md`。

## 一、为什么重做

09-27 真机一局里暴露的几件事，病根都在结构上:

- 爬梯子上阁楼卡死：规划器认为头顶那格屋顶楼梯"可穿",执行器被碰撞箱顶住头，两边对不上，重规划算出同一条路。
- 上楼梯是一级一级跳上去的，价钱按跳算，路线倾向绕开楼梯。
- 手里有圆石，失败时却说"没带垫路料":"这次不许放块"和"没有料"被折成了一个布尔。
- goto 没到要求的高度却报 done:任务层另有一套"差不多到了"的判据。

结构原因有四条:

1. **地形几何有两个来源。** 规划器查手写的分类表 `CellClass`(楼梯可穿、半砖可站……),执行器按真实碰撞箱走。
   表写错了没有任何东西报错;楼梯那条的理由"原版视为可通行"是错的，原版 1.21.1 `StairBlock.isPathfindable`
   返回 `false`。两边对不上时就在各处打补丁，例如 `Movement.feet` 把落在楼梯、半砖里的脚位上抬一格。
2. **每个动作把规划和执行写在一个类里**(照搬 Baritone 的 `Movement`),两半各自判断地形，半砖、楼梯、灵魂沙的
   特例在每个动作里各写一遍。
3. **边界两头都漏。** 寻路 import 了 `NumenPlayer`、`InputDriver`、`CompanionRegistry`、权限层的 `Gate`/`Verdict`/
   `ConsentItem`/`Listing`、`BuildValidity`、`BlockDigger`、`InitTag`、`FailureType`;外面又直接伸进寻路内部:
   `BuildCalculationContext` 继承成本上下文，挖矿与建造直接调 `MovementHelper` 和具体动作类，外部引用的寻路类
   有四十多种。
4. **不能单独测。** 规划只能在 GameTest 服务器里连着整个 Numen 测，楼梯一条测试都没有。

寻路现在是 `core/pathing` 下 81 个文件、约 1.5 万行。

## 二、目标

1. 地形几何只有一个来源：从方块碰撞箱推导。规划、执行、感知(`look_around`)读同一份。
2. 寻路成为独立的 Gradle 模块，只依赖原版 Minecraft;Numen 经端口接入，边界由编译器保证。
3. 规划与执行分开;执行用同一份几何校验，失败交出结构化的结局，模块里不写给模型看的话。
4. 能单独测：几何与规划跑单测，执行跑 GameTest,用一个普通假玩家，不用 `NumenPlayer`。
5. 以后可以单独发布给别的模组用(LGPL)。

不做：分层粗图(`hier/` 已于 07-18 删除，不复活)、船导航重写、其他 12 条版本分支的移植(先在 1.21.1 跑稳)。

## 三、沿用的决定

这些在旧稿里定下、真机验过，新架构照旧:

- **路线规格 `RouteSpec`** 按次传值，每次搜索和执行各带一份，成本模型只认它。四组谓词，每组一个出处:
  格子类型与每类代价、能力开关与上限(含 `alter`:`none` / `natural` / `any`)、按位置的代价(`PositionCosts`)
  与按方块种类的禁令、动作代价(挖、放、跳、涉水)。服主总开关是上限，规格只能在其内收紧。
- **路径不是可交接的东西**,能交接的是目标加规格;路径由引擎随时推导、拼接、重算。
- **规划是查询，执行是任务。** 查询当场返回，不占身体;候选路线用惩罚法出(把已有候选踩过的格加价重搜，
  重叠过高的丢弃),不引入随机，路线可复现。
- **账单**:预算账和实际账同一格式(长度、要挖的格、要放的格、需要同意的格)。
- **权限不是寻路的子功能。** 寻路只问"这一格能不能动、要不要问",规划器自己不判;`sacred`(别挖自己要站的
  那一格)是规划正确性约束，不是权限。
- **目标是意图编出来的**(`GoalCompiler`:贴脸、站上、靠近……),到达判据只写在目标里。
- **搜索预算按展开节点数计**,结论不随机器负载和 tick 速率漂移;所有搜索经同一个派发口。
- **执行报告实际改动**:路上真放下、真挖掉的格记在执行器的实际账里，调用方从这本账取，不另记意图账。

## 四、分层

```
            ┌───────────────────────── Numen(core) ─────────────────────────┐
            │ 任务 / 命令 / 感知     适配层 core/nav:端口实现、路线簿、文案渲染 │
            └───────────────┬───────────────────────────────┬────────────────┘
                            │ 只经门面                        │ 实现端口
┌──────────────────────── pathing 模块(只依赖原版 MC)─────────────────────────┐
│ 4 门面 api/      Navigator:plan / drive;请求、结局、账单都是数据             │
│ 3 执行 drive/    段状态机、每种动作的控制器、疾跑、视角;驱动 Body 端口          │
│ 2 搜索 search/   A*、目标、预算、派发与快照、候选路线                           │
│ 1 规划 plan/     每种动作的前提与代价(纯函数)、成本模型组合                    │
│ 0 地形 world/    身体尺寸、从碰撞箱推导的落脚与净空、少量语义分类              │
└──────────────────────────────────────────────────────────────────────────────┘
```

每层只依赖它下面的层。第 0 层被规划、执行和 Numen 的感知共同使用。

### 第 0 层　地形模型(`world/`)

回答三件事，全部从碰撞箱推导，按 `BlockState` 缓存(状态是驻留对象，按状态 id 建表，线程安全、只读):

- **落脚**:身体站在这一列上时脚落在多高。下半砖 0.5、灵魂沙 0.875、楼梯 1.0(站在楼梯上等同整块)、地毯、雪层
  各按自己的碰撞箱。"脚的高度归到哪一格"的规则只写一处，规划的节点和执行时"人现在在哪个节点"都用它，
  `Movement.feet` 那种补丁随之消失。
- **净空**:以某个落脚高度站着，身体的碰撞盒(宽 0.6、高 1.8,潜行 1.5)和周围方块的碰撞箱有没有交叠。
  头顶那格是楼梯、活板门、半砖时按真实形状判断。
- **迈步**:从某个方向走进相邻一列，要越过的高度是否在身体的迈步高度(原版 0.6,取实体属性)以内。
  楼梯从矮的那面进是走上去，从背面、侧面进要跳;下半砖是走上去;栅栏、墙 1.5 高，迈不过也跳不上。

碰撞箱表达不了的，留一张**语义分类**(现在的 `CellClass` 缩到只剩这些):流体(静水、流水、岩浆)、可攀爬
(`BlockTags.CLIMBABLE`)、能打开的门与栅栏门、危险(火、仙人掌、甜浆果、细雪、蜘蛛网)、落沙。几何判断一律不进这张表。

少数碰撞箱随世界或实体变化的方块(脚手架、细雪等)在语义表里明确列出、单独处理，不混进缓存。

### 第 1 层　规划(`plan/`)

- 每种动作(平走、斜走、上一级、下一级、下落、跑酷、垫柱、向下挖……)只有**前提与代价**两个纯函数，读的是只读世界
  视图、第 0 层和成本模型，不接触 `ServerPlayer`。
- **成本模型由几部分组合而成**:物理代价(`ActionCosts`)、路线规格、改地形许可(端口 `TerrainPolicy`)、
  垫路料(端口 `Materials`)、身体快照(迈步高度、游戏模式、背包里的工具、附魔、够得着的距离)。
  任务要改价，只能通过路线规格或按位置的代价表，不能继承成本上下文。
- 放一块的价钱在规格要求"事后拆回"时连拆的那一下一起算，只在放置定价这一处算。
- "许不许改地形"和"有没有料"是两个独立事实，分别来自规格与 `Materials`,不折成一个布尔。

### 第 2 层　搜索(`search/`)

A*、目标族、按节点数计的预算、异步派发与区块快照、候选路线规划(`RoutePlanner`)。
没到目标的结论带着停下的原因：搜完了没有路，还是预算用完(不能证明没路)。现有代码基本就是这个样子，主要是搬家。

### 第 3 层　执行(`drive/`)

- 每种动作有一个**控制器**,把规划好的一步变成按键与视角，经 `Body` 端口落到身体上。
- 动作开始前和执行中，用**同一个**第 0 层和同一份前提函数在活世界上复核;对不上就停下，交出结构化原因
  (哪一格、什么方块、哪种动作、哪一条前提不成立，比如"头顶净空不足"),不再报笼统的"找不到路"。
- 段状态机(首段、提前规划接续段、拼接)、疾跑、视角步进沿用现有实现。
- **到达只看目标自己的成员判定。** 模块里没有"差不多到了";调用方要"靠近就行",就编一个靠近的目标。
- 实际改动记在账里(真放下的、真挖掉的),随结局交出。

### 第 4 层　门面(`api/`)

```java
Navigator nav = Navigator.of(body, ports);
PlanResult plan = nav.plan(query);          // 只搜不走:候选路线与预算账
Navigation run = nav.drive(request);        // 目标 + 规格;返回句柄
NavStatus s = run.tick();                   // RUNNING / ARRIVED / FAILED(Outcome)
EditLedger edits = run.stop();              // 叫停也交出实际账
```

请求、结局、账单都是数据。结局是枚举加事实，例如 `NO_ROUTE`(搜完无路)、`OUT_OF_BUDGET`、
`BLOCKED(位置, 方块, 动作, 原因)`、`NEEDS_ALTER`(规格不许改地形)、`NO_MATERIALS`、`DENIED(位置, 许可给的理由)`。
模块里没有一句给模型或玩家看的话。

### 端口：宿主实现的接口

| 端口 | 模块向宿主要什么 | Numen 的实现 |
|---|---|---|
| `Body` | 身体实体(服务端玩家)、按键与视角输入、迈步高度、游戏模式 | `NumenPlayer` + `InputDriver` |
| `TerrainPolicy` | 这一格能不能挖或放:放行 / 要问(带一个模块不解读的凭据)/ 拒绝 | 权限层 `Gate`;凭据就是 `ConsentItem` |
| `Materials` | 有没有可垫的料、下一块用哪个、拿到手 | `ThrowawayBlocks` |
| `Effector` | 真的挖掉或放下一格，并如实返回成没成、为什么没成 | `BlockDigger` 与放置通道 |
| `PlacementAdvice` | 这一格放下去应该是什么状态(给建造) | 建造任务 |
| `Limits` | 服主总开关与上限(能不能挖、能不能放、超时) | Numen 配置 |
| `NavLog` | 日志出口 | Numen 的日志 |

### Numen 适配层(`core/.../nav/`)

端口的实现、路线簿 `RouteBook`(挂在同伴身上、编号跨重启)、账单与结局渲染成给模型看的英文、
结局到 `FailureType` 的映射、建造工地的位置代价、`look_around` 读第 0 层。任务、命令、感知只经门面使用寻路。

## 五、模块与构建

- 新增 Gradle 模块 `pathing`,和 `api/common` 一样对着原版编译(neoForm),不含加载器代码，按 `api/common`
  的方式打进两个加载器的发行包。包名 `com.dwinovo.numen.pathing`。
- 依赖只有 Minecraft、fastutil、slf4j;不依赖 `ai`、`agent`、`api`、`core`。模块的 classpath 上没有 Numen,
  往外的依赖在编译期就过不去。
- `core` 依赖 `pathing`;`api` 不需要它。
- `core` 只能 import `pathing.api`、`pathing.spec`、`pathing.goal` 三个包，由 core 里的一条单测扫 import 钉住。
- 以后单独发布时，门面与端口就是对外接口。

## 六、现有代码的去向

| 现在 | 去处 |
|---|---|
| `spec/CellClass` | 第 0 层：几何部分由碰撞箱推导取代，只留语义分类 |
| `moves/MovementHelper`、`BlockReach`、`util/BlockHelper` 里的可站、可穿、落脚判断 | 第 0 层 |
| `moves/movements/*` | 拆成第 1 层的前提与代价、第 3 层的控制器 |
| `moves/CalculationContext`、`ActionCosts`、`ToolSet`、`Moves` | 第 1 层;权限、垫料、身体改走端口与快照 |
| `astar/*`、`goals/*`、`goal/GoalCompiler`、`calc/NavGoal`、`bridge/*`、`cache/*`、`calc/PathPlannerPool`、`plan/RoutePlanner` | 第 2 层 |
| `execute/PathingCore`、`PathExecutor`、`ExecHarness`、`SprintPolicy`、`AimProcessor`、`moves/AimGeometry`、`BoatNav` | 第 3 层 |
| `execute/PlayerNav` | 第 4 层门面 `Navigator` 的实现 |
| `execute/TerrainBill` | 数据留在模块;渲染成文字的部分进 Numen 适配层 |
| `spec/RouteSpec`、`PositionCosts` | 模块的 `spec` 包 |
| `settings/NavSettings` | 引擎参数留模块;服主开关经 `Limits` 端口 |
| `settings/ThrowawayBlocks`、`plan/RouteBook` | Numen 适配层 |
| `moves/movements/BuildPlacementRegistry` | 删除，由 `PlacementAdvice` 端口取代 |
| `task/build/BuildCalculationContext` | 删除，改为路线规格加位置代价加 `PlacementAdvice` |
| `util/NavProfiler` | 模块 |

## 七、测试

先把架构搭好，再按功能写测试。每条测试回答"这个功能玩家看来正不正常",不测内部计数。

**单测(模块内，跑在原版环境里，拿真实的 `BlockState`)**
- 第 0 层对照原版碰撞箱逐项核:楼梯(四个朝向、上下半、五种形状)、半砖、栅栏与墙、地毯、雪层 1–8、灵魂沙、耕地、
  土径、门与活板门的开关、玻璃板、灯笼、床、箱子、脚手架、梯子。
- 小场景规划：在内存里摆几列方块，看规划出的路线。例如楼梯从正面走上去、从背面要跳或绕到正面、头顶压着楼梯的
  梯子口不规划钻过去、`alter=none` 的路线一格不改。

**GameTest(执行，生成一个普通假玩家去真走)**
- 每条用例搭一个场景，生成一个不是 `NumenPlayer` 的假玩家，交给 `Navigator.drive`,断言:
  在时限内到达;`alter=none` 时世界一格没变;实际账和世界的变化一致;失败时结局给的原因对。
- 场景清单:直楼梯上下、转角楼梯、从楼梯背面上、半砖台阶、栅栏挡路、地毯与雪层、开门穿过、
  爬梯子进头顶压着楼梯的阁楼、过水、`alter=natural` 下垫柱与搭桥、许可时挖穿、限高下落、跑酷越沟。
- 用这个假玩家通过，就证明模块不依赖 Numen。

**Numen 侧 GameTest** 继续在 core 里从工具入口测:goto、follow、建造绕圈、挖矿等。

**现有测试**:寻路单测 24 类、`MovementGameTests` 32 条，每批落地时迁到新接口。只有当同一个功能已经由新测试覆盖时
才删旧的，并在报告里逐条列出。

## 八、分批落地

每批结束时两个加载器都能构建，单测和 GameTest 全绿，不做到一半的中间态。只在 1.21.1 做。

1. **模块与端口。** 建 `pathing` 模块并搬代码;切掉所有往外的依赖，改为端口，由 Numen 适配层实现;门面立起来，
   任务、命令、感知只经门面;删 `BuildPlacementRegistry`、`BuildCalculationContext`。这一批行为不变。
2. **地形模型。** 第 0 层从碰撞箱推导;`CellClass` 缩成语义表;脚位归格只留一处，删 `Movement.feet` 的补丁;
   楼梯从正面走上去;`look_around` 改读第 0 层。
3. **规划与执行分开，结局结构化。** 每种动作拆成前提与代价、控制器;执行用同一份前提复核;到达只看目标;
   "不许改地形"和"没有料"分开报;Numen 侧按结局渲染回执。真机上 goto 没到高度报 done、说没带料、梯子口卡死
   这三件在这一批消失。
4. **功能测试。** 第七节的单测与 GameTest 场景清单全部落地;现有测试迁完;文档收尾。

第 4 批通过后再部署到测试实例真机验。

## 九、验收

- `pathing` 模块的 classpath 上没有 Numen 的任何模块。
- core 对 `pathing` 的 import 只落在 `api`、`spec`、`goal` 三个包里(单测钉住)。
- `world/` 之外没有 `instanceof StairBlock`、`SlabBlock` 这类几何特判。
- 模块里没有给模型或玩家看的句子。
- 第七节的单测与 GameTest 全部通过。

## 十、要同步修改的文档

- `architecture-mind-model.md`:加一条"寻路是独立模块，Numen 经端口接入;规划与执行共用一份地形几何"。
- `spatial-perception.md`:第 2 批落地后，把 `MovementHelper.canWalkOn` 等引用改成第 0 层。
- `permission-layer.md`:开头对旧稿的引用已改指本文。

## 参考

- Baritone:对外接口单独一个源码集 `api`,实现依赖它而不是反过来;`Settings` 无随机性(本机 `D:\01_Projects\baritone`)。
- 原版 1.21.1:`StairBlock.isPathfindable` 返回 `false`;`PathType` 加 `Mob.setPathfindingMalus` 的"分类加每类罚分"。
- MineColonies `PathingOptions`(本机 `D:\01_Projects\minecolonies`)、mineflayer-pathfinder `Movements`、
  Recast/Detour `dtQueryFilter`:每次寻路一份能力开关与代价，与 `RouteSpec` 同形。
