# 先看、再规划、后执行:area 与 route

状态:已落地(09-30,集成分支 look-plan-act),落地细节与和本稿的出入见 §十。10-03 起路线不再是存盘的名词:一趟路是交给
`route.plan` 的一张描述,计划只在一段程序里有效,权限走到那一格才问(§十 第 5 步,`shell.md` §十);§四 是当时的设计。

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
area new ores                                            建一块空区域
scan blocks 32 iron_ore deepslate_iron_ore --into ores   扫描,每一团加成一部分(没有 ores 就新建)
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
| 路线计划要改的格 | `work mine <区域>`:挖区域里还是当时那种方块的格 |
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
- 执行:`work mine ores/g3`,只挖区域里、工作区里、还是当时那种方块的格;区外的只报告。
- `work mine` 只收区域(必填,`work mine <区域[/部分] …> [--count N]`),不留按方块种类挖的简写:找方块是看的事,挖矿自己
  不找。范围不对先用 `area` 的增删与运算调,再挖。`--count N` 可选,是新增的物品数;不给就挖完区域里落在工作区里的格。

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
  重启后 `move go home`、`work mine ores/g3` 的重放照常。

## 九、以后

- DSL:脚本(可沙箱、可限步的嵌入式语言)只调用这些命令做组合;每个动作仍是一条命令,过权限、记账。`--into` 这类写法届时由变量取代。
- `look`(`vision-look` 那份调研)消费 area。
- `route save-last`:把刚走通的轨迹简化成途经点存成路线;常走的路可以让她修出来(build 消费 route)。

## 十、落地记录

### 第 1 步:area 地基与权限规则项(09-30)

- **api `com.dwinovo.numen.area`**:`Cells`(一堆格子,按 16³ 小节位图,键是原版 `SectionPos.asLong`;附带的方块按节调色板,
  每格 `Seen(state, tick)`;构造:盒子、单点、一组格、带方块的一组格、球;运算:并、差、交、按方块筛、外扩,逐节位运算)、`Area`
  (一个维度 + 编号的部分,整块是部分的并;跨维度运算报错)、`AreaRef`(`名字`/`名字/部分`,全仓只在这里读)、`AreaStore`(按主人的
  主世界 SavedData,`numen_areas_<主人>`)。
- **名字规矩只一处**:api `cli/Names`,设计名、区域名、路线名都引用它。
- **权限**:`Rule` 认 `area:名字[/部分]`,只写在落在一格上的动词(挖、放、右键方块、拿,或 `*`)上;`Gate` 快照在主线程把主人的
  区域一并取成不可变值,寻路线程判 `area:` 不回头读存档。一行规则点名的区域不在了就**整行不作数**(连 `!area:` 也是,免得
  `allow break(!area:house)` 在区域删掉后变成哪儿都放行);加规则时点名不存在的区域当场拒收,已有规则的区域被删由 `rules list` 标出。
- 定下的细节:按方块筛时没附带方块的格一律不留(区域这一层不读世界);外扩按立方体(连对角);运算保留左边的部分编号;球的部分
  前缀 `s`;中心取离所有格平均位置最近、且在区域里的一格。

### 第 2 步:route 与 move(09-30)

- **规划与执行分开**:`core/nav/Survey` 只搜不走,多段逐段规划(下一段接在上一段后面,`PlanQuery.after`),没走到的段交出看清的
  那一截(`PlanResult.partial`);`Trip` 只管照路走、边走边细算。PROBING 与失败后放宽规格探路删掉。
- **路线对象** `core/route`:`Itinerary`(名字、维度、路段 = 去处 + 这一段的规格标志、整条的规格标志、最近一次计划、最近 8 次走过的
  记录;标志存原文,读回走同一棵命令树,翻译只在 `RouteSpecFlags`)、`Plan`(从哪一格、何时;每段状态、步数、刻数、要挖/放/问的格)、
  `Routes`(按主人的 SavedData)。
- **承诺**:`Plan.bind` 把"只许改计划里那几格"写成这一趟的位置代价(pathing 加了 `PositionCosts.confine`,合并取交集),执行层重搜时
  只在承诺里找,不另写检查。`move go` 从当前位置重新规划、与路线上的计划比(`Plan.beyond` 按挖、放、问分开比),超出就说多出哪几格、
  不走;路上停下时从当前位置再规划剩下几段,说是哪几格在承诺之外。
- **看不清的段不另停**:只看清一截或没规划的段,照这一段自己的目标走、规格照样绑承诺;`alter none` 的远路照旧一路走到底
  (GameTest 208 格),要改地形的路线走进未知部分后需要承诺外的格时停下说明。开走前"不走"只针对确定走不通的段。
- **与设计稿的出入**:`NeedsAlter` 结局带着诊断出的那条路的改动,回执点名要动哪几格(删掉候选清单后这条事实原本会丢);已知诊断时
  假设身上有料,可能报出身上没有的垫料,`route plan` 按真实库存算。`route reverse` 的终点是这条路线上次规划时的起点,没规划过就拒绝。
- `move goto` = 她自己的匿名路线 `goto-<名字>` → 规划 → 执行;失败回执给出 `route spec … --alter …`、`route plan …` 这样能照抄的下一步。

### 第 3 步:area 接进 core(09-30)

**路线这一半**

- 去处只在 `Destination` 一处编成寻路目标:`move goto --area 名字[/部分]`、`route new --to` 与 `route via` 的位置参数(数是坐标,
  一个名字是区域)。三种到达对整块区域成立,都用模块现成的 `Goals.anyOf` 拼:`at` = 各格的 `at`,`use` = 各方块的 `use`,
  `near` = 各格的 `within`;寻路模块没有为区域加新的到达。大区域有界:只在离出发点最近的 4096 格里挑(api `Cells.nearest`,
  按小节由近到远翻),`at`/`near` 至多 64 个成员,`use` 至多 8 个。
- 禁区与标志:`--avoid area:名字`(禁入 + 禁站)、`--avoid_break/place/step area:名字`。盒子写法删掉,写了当场说"框成区域再写
  `area:`"。pathing 只加一样数据 `PositionCosts.Region`(`contains(long cell)`),core 的 `NamedAreas` 用区域的小节位图实现它,
  不逐格展开(四百万格翻译一次不到一秒)。
- 区域按名字活引用,规划时按主人当时的区域解析(`NamedAreas`,与权限规则同一口径);被删了如实说是哪一段。

**看与挖这一半**

- `area` 命令组:`new`、`add`(`--box`、`--at`、`--built`、`--route`)、`drop`、`show`、`list`、`delete`、`refresh`,运算
  `union/minus/intersect/filter/grow/center`(结果用新名字,不覆盖)。一个盒子至多 2^24 格。"还是当时那种方块"只在
  `Cells.Seen.holds` 一处判,挖矿复核与 `refresh` 共用。
- **改区域过权限层**:动作 `edit_area(区域名)`、信号 `ruled`(主人层规则的 `area:` 项点名的区域,区域此刻不在也算);出厂
  `ask edit_area(ruled)`、`allow edit_area(!ruled)`。命令里不写死哪块能改;主人可写 `allow edit_area(area:ores)` 放开、
  `deny edit_area(*)` 收紧。挂起等答复与第 0 层指令同一个口子(`ServerSource.authorize`),不占任务槽。
- 扫描:`scan blocks --into` 每一团加成区域的一部分;不带 `--into` 不编号不存。`--in 区域` 与半径球求交。删 `GroupBook`、
  `staleMessage`、`NumenPlayer.nextIdNumber`、回执的 `in_work_area`/`box`。
- 挖与捡:`work mine` 点名区域取代 `--groups`,任务里原来的补查、慢心跳重查删掉,候选只有一个来处。落地时还留过一个
  `--block_ids` 简写(开工时现扫一块匿名区域再挖),09-30 删掉:`work mine` 只收区域,区域是必填的位置参数
  (`work mine ores/g3 [--count N]`,命令行里必填即位置参数;快捷工具的字段仍叫 `area`),先 `scan blocks … --into` 再挖。`work collect --area` 与半径球求交。`WorkArea` 带着一块球形区域,判定只问区域。
- **与设计稿的出入**:`work mine` 的区域是位置参数,不写 `--area`;`BuildSite` 没改成区域(它是交给寻路的位置代价,收成区域
  再展开回去只多一层转换);`scan storage --in` 没做(`x y z` 是必填位置参数,加 `--in` 就是一个动作两种写法,眼下也没有使用方);
  `area show` 只看她所在维度的区域;`--built`、`--route` 加进来的格是 `Area.Kind.CELLS`(编号 `c`)。

### 第 4 步:挖掘统一(§十一 第 1–5 条,09-30)

- **`work dig <区域或坐标...> [--count N]`**(快捷工具 `work_dig`,字段 `place`)取代 `work mine`,不留别名,不收路线标志。一串写法
  由 `Destination.Stop.each` 分成几处(名字是区域,三个数一组是一格,每一处照 `Stop.of` 读),坐标是只有一格的区域。挖哪一格只在
  `DigTaskRecord.wants` 判:附带方块的格(扫描来的)按 `Cells.Seen.holds`,不附带的(框的、点的、坐标)有方块就挖,空气与流体跳过。
  派发时按活世界数工作区里要挖的格,空区域、整片在区外、区里都不用挖,当场拒收。
- **工作区 = 跟前**:`WorkArea` 改成受理时脚下为中心、半径 10 的球(理由写在类注释:常见一团矿横竖四五格,`--arrive dig` 站到
  交互距离 4.5 格内再开工,另一头在 9 格内,掉落物再留一格);`WorkArea.confine` 把站、过、挖、放都用 `PositionCosts.confine` 关在区里,
  搜索展开的节点不多过区里的格数,不另设预算。走动的规格是 `alter natural`,要问主人的格不进路线、在动手那一刻逐格 `permit`,不走
  `Trip` 的先整条规划。区外的只报告(`Beyond`,存 `Cells` 不展开成清单),下一步两种写法都给:`route new … --arrive dig --alter natural`、
  `route plan`、`move go`,或 `move_goto … arrive:dig alter:natural`,到了再 `work dig`。
- **任务**:`MineCompanionTask` 收成 `DigCompanionTask`(包 `core/task/dig`)。删掉的:卡死尺里"挪出两格就算进展"(只为走远路);
  路线规格与 `DEFAULT_SPEC`(alter any)。卡死尺改成"二十秒里没挥一下、没挖掉、没捡到"。掉落物的目标与 `work collect` 同一个
  (`DropTracker.pickUp`:离那一格一格以内),弹到区边外一格的也捡得到。
- **`work collect`**:删掉 `--radius`,范围就是同一个工作区(与 `--area` 求交),走动同样关在里面。
- **`--arrive dig`**:`Destination` 加一种到达,编成模块现成的 `Goals.dig`;写错提醒照 `GotoReminders`(没给 y、空气或流体、区域里
  离她最近的那些格都没有方块)。到了的回执给出 `work dig` 那一行。
- **建造清场**:生存模式下待办里立着东西、要成空气或要换成别的方块的格,走到外圈之后交给 `DigCompanionTask` 作为建造的子活
  (`AbstractCompanionTask.runChild`:子活问主人记在父活名下;它跑时父活期限冻住、它按自己的期限走;收场时它的实际账与主人点过的头
  并进父活,回执只说一次)。清场的工作区是工地外扩两格(外圈也离工地两格),图纸里不清的格禁挖禁放(`BuildSite.clearing`)。每格只交
  一次,挖不掉的收工时归为 `BuildOutstanding.Cause.NOT_CLEARED`,连同挖掘执行的那句话。要放方块的格身上有料才清。创造模式照原版
  一下就碎:写成空气(通知邻居)再落位,不走挖掘。`BlockDigger.destroyNow` 删掉,破坏方块只有她的手这一条路。
- **`use block left`**:纯按键——朝那一格中心看,准星落在谁就按谁,手上是什么就用什么,按住直到碎或 `--hold_ticks` 到,不挪步;
  落在别的格或实体上回执照实说。`Interaction` 的左键挖方块直接按 `CompanionHands`,不再经 `BlockDigger`(那里的换工具、清视线
  归挖掘执行)。
- **`use block right`**(09-30 跟进):同样退回纯按键——看向目标看得见的那一面,准星落在谁就点谁,手上是什么就用什么,不挪步、
  不换工具、不再先清视线上的软遮挡;点到别的格或实体照实说,右键落在别的格上时写出 `work dig` 挖掉它或 `arrive:use` 从另一面点。
  清视线只剩 `work dig` 一处。
- **与设计稿的出入**:生存清场的格要身上有收得下它的工具才挖(与 `work dig` 同一条工具规矩),没有就如实报,不再像原来那样空手
  毁掉;创造清场是"写成空气再落位",与原版创造一下就碎、再放一块同一个结果。

### 第 5 步:路线成了一张描述(10-03)

- **路线不存**:`Itinerary`、`Routes`(按主人的 SavedData)、`route new/via/drop/spec/show/list/delete/reverse`、路线标志的翻译
  (`RouteFlags`、`RouteSpecFlags`、`RouteOps`、`RoutePlanning`)、`move goto` 的匿名路线 `goto-<名字>`、`area add --route` 都删了。
  Lua 程序自己就有变量,一趟路是一张表(`core/route/Description`):去处与途经点(`to`、`stops`,途经点 `type = "through"` 路过不停、
  `"stop"` 先停稳)、移动方式(`mode`:`walk` 或 `boat`)、偏好旋钮(`costs`)、避开(`avoid`、`avoid_break/place/step`)、
  放开(`allow`)、垫路料(`materials`)。没有备选路线。去处与避开不收名字,收一格、一个盒子(两个 Pos)、带 `cells` 的表。
- **计划**:`route.plan(描述)` 只规划不动,交回 `Plan`(`ok`、`why`、`spec`、`from`、`steps`、`seconds`、每站一段的 `legs`,段里有
  `path`、`breaks`、`places`、`asks`)。走不通不抛,`ok = false` 加 `why`。计划按这段程序记在她身上(`Plans`,键是工具调用的程序号),
  下一段程序里就没了。`move.go(plan)` 照它走,承诺照旧(`Plan.bind`、`Plan.beyond`)。
- **`alter` 拆成三个开关**:`costs = {dig, place, consent}`,寻路规格是 `RouteSpec.dig/place/consent/consentMultiplier`
  (`pathing.md`"规格的三个开关与越界才问")。
- **权限的时机**:开走前把要问的格一次问完那一步删了(它只为权限而在)。规划时要问的格照 `consent` 计价并列进 `asks`;走到那一格
  跟前才问,答应接着走,拒绝在那里以 `denied` 收场,`hint` 是加上 `avoid` 再规划的那一行。
- **`move goto` → `move.to`**:库函数(`modules/move.lua`),等于 `route.plan` 加 `move.go`;同一处还有 `move.flee`(`arrive = "away"`)、
  `move.explore`。到达方式 `reach` 改名 `place`,`near` 的选项 `near` 改名 `range`,新增 `away`。新增 `move.dismount()`;
  `move.follow` 总有结束(`seconds` 默认 60)。
- **垫路料**:`throwaway` 组与名册里那份清单删了,每趟在描述的 `materials` 里写,不写就是标签 `#numen:throwaway` 的普通方块。
- **测试**:`RouteGameTests`、`PermissionGameTests` 的走路几条、`AreaRouteGameTests` 改成描述的写法;区域被删时路线怎么说的那条随
  名字一起删了。

## 十一、挖掘统一(09-30 定)

真机:挖远一点、深一点的矿连续 `OutOfBudget`(正下方 26 格、12 格外往下 8 格都一样)。病根是 `work mine` 在半径 48 的工作区里
自己规划隧道——规划藏在执行里;`alter any` 还要先整条规划、不交半程;挖掘时估价远低于实价,搜索在三维里铺开(4 步的路展开
9305 个节点)。工作区半径由快照几何推出,真正卡住的是节点预算。

**定下的:**

1. **`work dig <区域或坐标> [--count N]` 取代 `work mine`。** 挖一格、挖一团矿、挖坑、砍树、清场都是"把这块区域里的方块挖掉"。
   挖哪些格由区域的数据定,不另设开关:扫描来的部分只挖还是当时那种方块的格(即挖矿);框出来的部分与点里面是什么挖什么
   (空气、液体跳过)。坐标就是只有一格的区域(与 `move goto` 同一条规矩)。`--count` 是新增物品数,不给就挖完够得着的。
   不再收路线规格:"别碰什么"由区域运算(`area minus`)与权限层(`deny break(area:…)`)表达。
2. **只在跟前干(方案 B)。** 活动范围是受理时她脚下周围几格的一块区域,移动用 `PositionCosts.confine` 关在里面——挪几步、走进刚挖开的
   洞、捡弹开的掉落物;开不出远路是结构上做不到,不另写检查。区外的只报告,下一步是开路:`route new … --arrive dig`、`route plan`、
   `move go`,到了再 `work dig`。挖分散的几团由她串起来(以后是 DSL 的循环)。跟前的格要问主人时,动手那一刻逐格问,不先整条规划。
3. **`--arrive dig`**:`move goto` 与 `route` 的到达方式加上"站到手够得着它、挡着的可以挖开"(模块已有的 `Goals.dig`,只开到命令行)。
4. **建造清场:** 创造模式直接替换(原版创造本来一下就碎);生存模式设计里要成空气的格交给与 `work dig` 同一个挖掘执行,真挖、
   用工具、有掉落。建造不写两套:挖掘按原版规则自己分模式。
5. **`use block left` 退回纯按键:** 手上是什么就用什么点、准星落在谁就点谁(点到高草如实说),对准、按住直到碎或松手,不移动。
   自动换工具、清视线上的软遮挡、捡掉落物都归 `work dig`。挖东西用 `work dig`,按左键用 `use block left`;底下同一双手、同一个权限层。

**同时做:** 搜索效率(加权估价、按时间取当前最好的一段;先调研、GameTest 对照再改)、规划不许走封顶的水下段超过憋气时长且回执说
明路线经过水下。
