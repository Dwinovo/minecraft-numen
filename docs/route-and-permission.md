# 路线规划:让模型真的在用规划器

状态:设计稿(2026-09-14),前两步已落地并真机验过。权限层另见 `permission-layer.md`;
本文与 `architecture-mind-model.md` 冲突处以本文为准,落地时同步修宪(见 §九)。

## 一、问题

今天的 `goto` 对模型是一个黑盒:给坐标,得到一条路,或者一个失败。模型看不到这条路
要经过什么、要挖什么、会不会动到主人的东西,也没有第二条路可选。`may_alter_terrain`
是模型给自己的许可,它把"能不能改地形"这个决定压成一个布尔,模型记不住它是什么,
主人也不知道它开过。

会改世界的判据散在四处,互不知情:`do_not_break` 标签硬禁挖;`NavSettings.
blocksToAvoidBreaking` 对工作台、熔炉、箱子只做成本乘十,没路照拆;寻路目标格的
`sacred` 只护这一次导航;建造任务另有一道"带方块实体不动"。没有任何一处知道"这块
是不是玩家放的"。#74 的拆家、开箱子拆箱、把基地木头当资源,全部来自这里。

## 二、原则

1. **规划器出带价签的选项,模型在选项之间选,主人对自己的东西点头。** 三层各管
   各的,没有一层替另一层做决定。
2. **同一判据只有一个出处。** 一格能不能挖、能不能放,全仓只问一个地方。
3. **路径不是可交接的东西。** Baritone 和我们的 `PathingCore` 都在执行中不停拼接、
   重算;能交接的是目标加规格,路径由它们随时推导。
4. **旋钮枚举得完,路线枚举不完。** 模型用自然语言表达偏好,自己翻译成旋钮;
   规划器只认旋钮。要新走法就加旋钮,不加分支。
5. **权限是动作的属性,不是工具的属性。** 哪个工具走到受门控的动作,哪个工具在
   那里停下来等;模型永远不是权限的执行者。

## 三、路线规格

一个按次传值的对象,寻路的每次搜索和每次执行都带一份,取代 `TerrainPermit` 枚举、
`may_alter_terrain` 参数,以及 `NavSettings` 全局单例里模型该碰的那部分。

主流方案(Recast/Detour 的查询过滤器、原版 `PathType` 打分、MineColonies 的
`PathingOptions`、Baritone 的 `CalculationContext`、mineflayer 的 `Movements`)的谓词
只有四种形状,规格按这四组组织,每组一个出处:

**1. 格子分类与每类代价。** 成本模型先把一格归到一个类型,再按类型查代价;
排除就是代价无穷大。类型表是引擎内置的固定词汇,规格只改代价:

| 类型 | 出厂代价 | 说明 |
|---|---|---|
| 地面 | 1 | 可站的实心面 |
| 水 | 涉水惩罚 | 原版给 8 |
| 流水 | 高 | 会被冲走 |
| 岩浆 | 排除 | |
| 危险相邻(火、岩浆、仙人掌、甜浆果旁) | 高 | 原版 DANGER_FIRE 给 8 |
| 门、栅栏门 | 低 | 能开的才算 |
| 可攀爬(梯子、藤) | 中 | |
| 落沙下方 | 排除 | 挖会砸 |
| 玩家放置 | 权限层决定 | 见 §六 |
| 带方块实体 | 权限层决定 | 见 §六 |

"平一点"就是抬高上下移动的代价,"避水"就是把水改成排除,不需要新分支。

**2. 能力开关与上限。** 这一格能不能做这种动作:挖、放、跑酷、冲刺、游泳、潜水、
开门、开栅栏门、攀爬、斜向上下、向下挖;最大落差、一条路最多改几格。`alter`
(`none` / `natural` / `any`)属于这一组:走路能不能改世界,`none` 是 goto 默认,
build 天然 `natural`,mine 默认 `any`(模型给 mine 的 `spec` 与 goto 同一个对象、同一个解析器,
叠在 mine 的默认上),`any` 表示连需要主人同意的格子也算进去、账单里单列。

**3. 按位置的谓词。** 不看类型看坐标:这些格不许踩、不许穿、不许挖、不许放,以及
反向的允许表(Altoclef 为末地传送门留的"强制可踩")。形状与 mineflayer 的
`exclusionAreas`、Altoclef 的 `breakAvoiders` 相同:给一格返回代价,累加。实体
避让也在这组:围绕实体的球形代价(Baritone 的 `Avoidance`)。同组还有按方块种类的
禁令(不挖这些、不往这些格里放、不踩这些),回答的是"这一种"而不是"这一格"。

**4. 动作代价。** 挖、放、跳、涉水四项惩罚。只留有实证的这四个:Altoclef 的
"偏好楼梯"因拖垮可靠性被掏空,全局启发式加项无人使用,以此为戒。可选一个默认关
的每格小噪声(MineColonies 的 `randomnessFactor=0.1`),让路线看起来像人走的;
它不用来出备选,备选走 §五 的惩罚法。

服主总开关(`allowBreak`、`allowPlace`、超时、节点上限)留在 `NavSettings`,它们是
上限,规格只能在上限之内收紧。规格里的每一项都要能在账单里看出它起了什么作用。

## 四、账单

预算账和实际账同一个格式,`TerrainBill` 扩成这一份:

- 长度(格数、估计刻数);
- 要挖的格子:坐标、方块、以及"为什么需要同意"(玩家放置 / 带方块实体 / 无);
- 要放的格子:坐标、用什么;
- 汇总:自然改动几格、需要同意几格。

规划时出预算账,执行完出实际账,任务回执两份都带。模型看账单选路线,主人看账单
点头,事后看账单知道动了什么。

## 五、两个工具

**只读的规划查询(`move route`)。** 收 goto 的坐标目标和规格,只搜不走,回执列候选
路线,每条带 id(r1、r2……)、长度、预算账;id 记进这个同伴的路线簿(`RouteBook`,挂在
身体上,身体没了簿子跟着没,上限是引擎参数)。id 的数字取自同伴存在自己 `.dat` 里的编号
(`NumenPlayer.nextIdNumber`,`scan_blocks` 的团编号共用),休眠、复活、重启之后接着往上数——模型的
对话历史跨过这些都在,旧 id 不能指到新路线上。要多条备选(最多三条)时用惩罚法:把已有
候选踩过的格子写进按位置代价表的踩价栏加价(倍率是引擎参数),同一目标再搜一次;与已有
候选重叠率高于阈值的丢弃,一次无路即收工。不引入随机种子,Baritone 也没有,路线可复现
是排障的前提。本体是 `RoutePlanner`——`PlayerNav` 的无路探针也是它的一次查询,全仓只有
这一个只查路线的派发口。查询不独占身体,不进任务槽,搜索完成那一刻回复。

**goto。** 收坐标或路线 id(`route`),外加可选的 `spec`。收坐标时在给定规格(默认
`alter=none`)下规划:路线干净就当场开走,一次调用结束;没有干净的路就不走,查可自然改动
的候选(最多三条)记进路线簿,以 TERRAIN_BLOCKED 回执把候选和预算账交回来,由模型选一条
再派;连改地形都没路仍是 NO_PATH。收 id 时目标与规格都是那条路的,给了 `route` 就不能
再给 `spec`;身体离起点不超过两格且首段仍可走就直接走缓存的路径,否则在同一规格下重算;
中途被堵也在同一规格下重算,不会偷偷换成另一种走法。一条路取走即划掉——走过一次的路径
带着执行状态,不能再走第二遍。回执文案(候选行、清单、拒绝与查询的措辞)只在 `TerrainBill`
一处。

`spec` 的形状按规格四组:`alter`(none/natural)、`avoid`(排除的格子类型)、
`penalties{place,break,jump,wade}`、`avoid_break/avoid_place/avoid_step`(方块 id、`#标签`、
坐标 `x,y,z` 或坐标盒 `x1,y1,z1..x2,y2,z2`)、`parkour`、`climb_vines`、`max_fall`、
`alter_budget`;JSON 到规格的翻译只在 `RouteSpecJson` 一处。按方块种类的禁令
(`RouteSpec.BlockBans`)与按位置的表互补:位置表回答"这一格",种类表回答"这一种"。

`move follow` 没有 `may_alter_terrain`,跟随不改地形;跟不上时回执同样列候选,模型先 goto 一条
开路再接着跟。

## 六、与权限层的接口

权限层不是路由的子功能,它是 api 里独立的一台机器,寻路只是它的一个调用方;设计见
`permission-layer.md`。路由这边只留两个预留位,由权限层来填:

- `TerrainBill.Break.consent`:每条挖掘条目为什么需要主人同意,规划器自己不判。
- `RouteSpec.Alter.ANY`:把需要同意的格子也算进路线、账单里单列;选了这种路线,执行开始
  前由权限层发起征询。

`sacred` 不是权限,它是"别挖自己要站的那格",规划器的正确性约束,留在原处。

## 七、收债清单

| 现在 | 去处 |
|---|---|
| `may_alter_terrain` | 删。意图由规格的 `alter` 表达(已落地) |
| `TerrainPermit` 枚举 | 删。成本模型只收规格(已落地) |
| `ExecHarness` 只存不用的 permit | 随 `TerrainPermit` 一起删(已落地) |
| `sacred` | 留,注释说明它不是权限(已落地) |
| `TerrainBill` | 留,扩成 §四 的账单(已落地) |

`do_not_break` 标签、`blocksToAvoidBreaking` 软惩罚、建造的方块实体门这些属于权限判据,
它们的去处在 `permission-layer.md` §九。

## 八、分步落地

1. 路线规格与账单:规格对象替换 `TerrainPermit` 穿过 `ContextFactory` 进成本模型;
   `TerrainBill` 扩成预算账。删 `may_alter_terrain`。此步之后行为与今天等价。(已落地)
2. 规划查询工具:探针一般化成 `RoutePlanner`,备选用惩罚法;`move route` 命令;goto 接收
   `spec` 与路线 id,路线簿挂在身体上;`alter_budget` 真判。(已落地,09-14 真机验过)
3. 权限层接入:见 `permission-layer.md` §十。

## 九、宪法修订

- §一"所有机制都是 `TaskChain`,每 tick 出价"已与代码不符(`TaskSelector` 是固定
  四层选择),随本文一起改。
- §六 加一条:寻路的规划是查询,执行是任务;查询当场返回,不进任务槽。
- 权限相关的修宪见 `permission-layer.md` §十一。

## 参考

- Baritone `api/Settings.java`:旋钮清单,无随机性(本机 `D:\01_Projects\baritone`)。
- Altoclef `BotBehaviour.java`:按任务 push/pop 的规划器配置沙盒,谓词式禁令,
  失败靠进度检测加退避加黑名单(本机 `D:\01_Projects\altoclef`)。
- mineflayer-pathfinder `lib/movements.js`:每次寻路一份 Movements,`exclusionAreas`
  是返回代价的谓词。
- pi-mono `coding-agent/examples/extensions/permission-gate.ts`、`docs/rpc.md`:
  工具调用前的可阻塞钩子,HITL 即 await 对话框,远程 UI 是带 id 与超时的请求响应。
- Claude Code 权限文档:deny/ask/allow 三表按序首个命中;是 / 是且不再问 / 否;
  否决附言发回模型;权限由 harness 执行而不是模型。
- Recast/Detour `dtQueryFilter`:区域类型加每类代价,include/exclude 掩码;Unreal、Unity
  的导航过滤器同形。
- 原版 `PathType` 与 `Mob.setPathfindingMalus`:格子分类加每类罚分,-1 为排除。
- MineColonies `PathingOptions`、`SurfaceType`:每个寻路任务一份能力开关与代价,
  `randomnessFactor=0.1` 每格加小噪声(本机 `D:\01_Projects\minecolonies`)。
- 路线多样性:Shortest-Path Diversification through Network Penalization
  (SIGSPATIAL IWCTS 2019);Diverse Shortest Paths in Game Maps(2022)。
