# 先看、再规划、后执行:area 与 route

状态:定稿(09-30),施工中。落地细节与和本稿的出入,落地时补进 §十。

## 一、为什么

Minecraft 里她做的每件事都是三步:**先看**(东西在哪、许不许动),**再规划**(怎么过去、要改哪几格、多久),**后执行**(照着做,
交回实际账)。建造已经拆开了:设计是存盘的对象,`build at` 只管执行(`build-designs.md`)。寻路和挖矿还揉在一起:

- **规划藏在执行里。** `scan_blocks` 交出团编号,`work_mine --groups g3` 直接开工;去哪一团、走哪条路、挖几格由任务里的一次 A*
  自己挑,她看不到。`Trip` 有三套流程(直接开走;`alter any` 先规划、征询、再走;失败后放宽规格探路),候选路线只是失败的副产品。
- **看里面混进了执行的概念。** `scan_blocks` 的 `in_work_area` 为 `work_mine` 而加,`box` 为能抄进 `--avoid_break` 而加。
- **看过的不留。** 团编号簿、路线簿只挂在身体上,不落盘;重启后按原调用重放 `work_mine --groups g3`、`move_goto --route r2` 必然失败。
- **主人的话记不住。** 权限规则只认动作的种类与信号,没有位置:"这栋房子不许挖"今天没有持久的写法。
- **"一块地方"有五六种写法。** `WorkArea` 的球、`collect --radius` 的圆、`BuildSite` 的施工禁区、路线标志里的盒子、`scan` 的半径球,
  各自解析、各自判定。

行业里这几件各有成熟做法,本稿把它们按 Minecraft 的特点(环境可改、改了不可逆、有的改动要主人同意)组合起来:

| 本稿 | 借鉴 |
|---|---|
| area 命名、持久、被保护规则引用 | WorldGuard 的区域与标志 |
| area 集合运算、外扩 | GIS 几何运算(Shapely/JTS 的 union/difference/intersection/buffer) |
| area 当禁区、代价 | Nav2 的禁区过滤层、Unity/Unreal 的导航区域 |
| 规划与执行分开,粗细两层 | Nav2 的 ComputePathToPose / FollowPath、Route Server |
| 计划是承诺,执行不越出它 | Terraform 的 plan / apply |

## 二、心智模型

**三步:看 → 规划 → 执行。三个名词,三类动词。**

名词都持久、都能增删改查、都用名字点到、都跟着存档与主人走(同一个主人的同伴都认得,重启不丢):

| 名词 | 是什么 |
|---|---|
| **area** | 一堆坐标,每个坐标可附带当时看到的方块。能做集合运算。**一个坐标就是只有一格的 area** |
| **route** | 意图(途经点、约束)+ 最近一次计划 + 走过的记录 |
| **design** | 要建成什么样(已有,`build-designs.md`) |

动词:

| 动词 | 占身体 | 例子 |
|---|---|---|
| **看**(传感器,即时) | 否,当场回 | `scan around/blocks/entities/block/storage`、以后的 `look`;`--into <area>` 把结果存进区域 |
| **规划** | 否 | `route plan`:交计划(每段多长、要改哪几格、要问主人哪几格) |
| **执行** | 是,收尾发 `task_finished` | `move go`、`work mine`、`build at`:交实际账 |

**名词是公用的。** 不是一条 area → route → move 的流水线,而是谁都可以产出、谁都可以消费:加一个功能时只问"它产出哪个名词、
消费哪个名词",不发明新的"范围"写法。

**框架只做三件事:增删改查、把名字解析成东西、如实报告。** 判断交给模型。只守两条底线,它们守的是主人,不是限制模型:
- 改世界、伤实体只由**权限层**裁决(主人的规矩,比如 `deny break(area:house)`);
- 身体做过的每件事都**报告**(实际账)。

实体不进 area:实体会动、靠运行时 id 指,由 `scan entities` 即时看。

## 三、area

### 是什么

一堆格子,每格可附带"当时看到的方块与时刻"。框出来的只有格子("这些格子,不管里面是什么");扫出来的带方块("这些格子,当时是
铁矿")。两者同构,可以互相运算。附带的方块是**当时的**:消费方动手时按活世界复核,`refresh` 把复核显式做一遍并写回。

区域由若干**部分**组成(扫描出的每一团、每次框的盒子、每个点各是一部分,编号 `g1`、`b1`、`p1`,区域内递增、删了不重排),整个
区域是各部分的并。`ores` 指整个区域,`ores/g3` 指其中一部分;凡是收 area 的地方两种写法都收。

### 存法

按 16×16×16 小节存位图(每节 4096 位),和原版存区块同一个思路:四百万格的基地也只有几百节。集合运算是逐节位运算;"一格在不在
区域里"是一次查表。附带的方块只给扫描出来的格子存(按节的调色板)。

### 运算

结果当场算出、存成一个**新的区域**,不存算式(存算式就有依赖网,改一处牵动别处):

```
area union  all  ores gold               并
area minus  safe house house/b2          差:house 里去掉门那一块
area intersect near  ores base           交
area filter logs house --blocks #minecraft:logs   按方块筛
area grow   buffer house 2               外扩 N 格
area center mid ores                     中心附近的一格(单格区域)
```

### 命令

```
area new ores
scan blocks 32 iron_ore deepslate_iron_ore --into ores   扫描,每一团加成一部分
area add house --box 10,60,5..20,70,15                    框一块
area add chest --at 12 64 7                                一个点
area add home --built house#1                              一栋建成的房子(Built 记着每一格)
area add tunnel --route mine                               一条路线的计划要改的格
area show ores [--page]      每一部分:格数、各方块数、最近一格、包围盒、许不许挖(按现在问)
area drop ores g2 / area refresh ores / area list / area delete ores
```

名字由模型给,规矩同设计名;命令层无状态,每行点名对象。

### 公用:产出方与消费方

| 产出 | 消费 |
|---|---|
| `scan blocks --into` | 权限层:规则项 `area:名字`,如 `deny break(area:house)`,一直有效、管所有动作 |
| 框盒子、点 | route:终点、途经点、禁区、代价 |
| 建成的房子 | 路线标志:`--avoid_break area:farm`、`--avoid area:…`,只管这一趟 |
| 路线计划要改的格 | `work mine --area`:挖区域里还是当时那种方块的格 |
| 集合运算 | `work collect --area`:只捡区域里的掉落物 |
| (以后)主人在客户端框选、模组插件的领地 | `scan blocks --in`:只在区域里找;`scan storage --in`:区域里的箱子装了什么 |
| | (以后)`look --at`、`build fill`、事件"有怪进 base 就叫醒我"、本能"空闲时待在 home"、客户端画出轮廓 |

隐式的"范围"一律改成 area 表示:工作区是"以受理时她脚下为中心、半径 48 的球"这个区域;`BuildSite` 的施工禁区、`collect` 的半径同理。
判定只在 area 一处。

### 分层

存储、判定、集合运算放在 `api`(权限层要用,`api` 不能引用 `core`);命令组、`--into`、说法放在 `core`。

## 四、route

### 存什么

| 部分 | 内容 |
|---|---|
| 名字 | `home` |
| 路段 | 一串途经点:每个是一个 area(或坐标)加到达方式(`at` / `use` / `near N`);最后一个是终点 |
| 约束 | 整条或某一段的规格:`alter`、禁区、`avoid_*`、代价偏好、改动预算(与现在的路线标志同一套) |
| 计划 | 最近一次规划:从哪儿规划的、何时、每段多长、多少刻、要挖哪几格、要放哪几格、要问主人哪几格、到不了的段及原因类型 |
| 走过的记录 | 谁、何时走的,实际多久,走没走通 |

意图不因世界变化失效,存得住;一步步的路是推导出来的,不存(寻路模块"路线不可交接,能交接的是目标加规格"的原则保留)。
不强制起点:从哪儿出发都行。

### 命令

```
route new mine --to ores/g3 --arrive near --near 3
route via mine 100 70 -20 [--at 2]          插途经点;route drop mine via 2 删
route spec mine --alter natural [--leg 2]   整条或某一段的规格(标志与 move goto 同一套)
route plan mine                              规划,不动身体;计划存在路线上
route show mine / route list / route delete mine
route reverse mine --as back                 反着的一条
```

### 规划:粗细两层

计划是粗的:按途经点分段,每段一次搜索,范围不超过一次快照看得清的地方;超出或未加载的部分照实写"这段后面还是未知"。执行时每段
再细算,与现在的执行层相同。

### 执行:`move go`,照承诺走

- `move go mine` 从她**当前位置**重新规划,拿结果和路线上那份她看过的计划比:要改的格、要问的格没超出那份,就走;超出了,把
  差别报给她,不走。路线还没规划过,就规划了直接走(等于 `move goto`)。
- 开走前把要问主人的格一次问完(沿用"开走前整条过一次裁决"的时机)。
- 路上边走边细算。**承诺用现成机制实现**:"只许改计划里的那几格"写成这一趟的位置代价交给执行层,世界变了重搜时自然只在承诺里找;
  找不到就停,说哪一段、要哪几格超出了。不另写检查。`alter none` 的路线承诺就是一格不改,怎么重算都不越界。
- 走到计划里"未知"那段的边上就停,让她接着规划下一段。

### `move goto` 是简写

`move goto x y z …` = 建一条她自己的匿名路线(每个同伴一条,名字固定,回执里写出来)→ 规划 → 执行,同一份代码。失败后不必重打整条:
回执给出下一步,比如 `route spec <那条> --alter natural`、`route plan <那条>`。失败后自动放宽规格探路(PROBING)删掉:放不放宽、看不看
别的走法,是她的决定。

### 什么不做成 route

任务内部每刻都在变的移动是反射:跟随、战斗走位、捡掉落物、钓鱼、走到实体跟前、建造走外圈、在工作区里挖一格换一格。它们照旧直接用
执行层,只随 `Trip` 收成"规划 + 执行"两件事一起换实现。

## 五、挖矿套三步

- 看:`scan blocks … --into ores`。
- 规划:走到矿边是一条路线;在工作区里挖哪一格、先挖哪一格是每刻闭环的反射,留在 `work mine` 里。
- 执行:`work mine --area ores/g3`,只挖区域里、工作区里、还是当时那种方块的格;区外的只报告。
- `work mine --block_ids … --count N` 保留为简写:等于扫描进一块匿名区域再挖,与 `move goto` 同理。

## 六、取代

| 删 | 由什么取代 |
|---|---|
| `GroupBook`、`g` 编号、`work mine --groups`、`staleMessage` 那套话 | area 与它的部分 |
| `scan_blocks` 回执里的 `in_work_area`、`box` | 去不去得了归规划;盒子在区域里 |
| 路线标志里的盒子写法 `x1,y1,z1..x2,y2,z2`(逐格展开、无上限) | `area:名字`;单格坐标、方块 id、标签保留 |
| `RouteBook`、`r` 编号、`move route`、`move goto --route` | `route` 组、`move go` |
| `Trip` 的三套流程与 PROBING | 规划(`route plan`)+ 执行(`move go`) |
| `NumenPlayer.nextIdNumber`(g 与 r 共用计数) | 名字与区域内的部分编号 |
| `WorkArea`、`BuildSite`、`collect --radius` 各自的范围判定 | area |

## 七、落地顺序

1. **area 地基(`api`)与权限规则项 `area:`**:类型、小节位图、集合运算、存储(按主人的 SavedData)、判定;`Rule`/`Gate` 认 `area:`。
2. **route 与 move(`core`)**:路线对象与存储、`route` 组、计划与承诺、`move go`、`move goto` 改简写;删 `RouteBook`、`move route`、
   `--route`、PROBING;`Trip` 收成规划 + 执行。先只收坐标终点。
3. **area 接进 core**:`area` 命令组、`scan blocks --into/--in`、`work mine --area`、`work collect --area`、路线的终点/途经点/禁区收
   area、路线标志 `area:`;删 `GroupBook`、`--groups`、`in_work_area`、`box`、盒子写法;`WorkArea`、`BuildSite` 改用 area。
4. 文档:`cli.md` 附录、`pathing.md` 适配层、`permission-layer.md`、技能里的写法。

1 与 2 并行,3 在两者合入后做。每一步都有 GameTest 从工具入口测。

## 八、测试要点

- 区域重启后还在;集合运算结果正确(单测覆盖跨节、空区域、自己减自己);`refresh` 划掉被人换过的格。
- `deny break(area:house)` 挡住挖矿、寻路、`use block` 左键;`area:` 规则经 `/numen permission rules add` 可加可删。
- 计划超出承诺时 `move go` 不走并说出差别;路上世界变了、需要承诺外的格时停下并说明;`move goto` 简写与三步分开做结果相同;
  重启后 `move go home`、`work mine --area ores/g3` 的重放照常。

## 九、以后

- DSL:脚本(可沙箱、可限步的嵌入式语言)只调用这些命令做组合;每个动作仍是一条命令,过权限、记账。`--into` 这类写法届时由变量取代。
- `look`(`vision-look` 那份调研)消费 area。
- `route save-last`:把刚走通的轨迹简化成途经点存成路线;常走的路可以让她修出来(build 消费 route)。

## 十、落地记录

(施工中)
