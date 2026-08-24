# 寻路釜底抽薪重构 — 实施日志与 B 期设计稿

> 2026-07-17。计划全文见 `~/.claude/plans/ok-sota-curried-catmull.md`（用户已批准）。
> 本文件是执行侧的真实记录：改了什么、为什么、怎么验证的。

## Phase A：语义契约层（已完成，全测试绿）

### 改动清单

**新包 `core/pathing/goal/`**（意图边界，pathing 正门扩为 PlayerNav+GoalCompiler）
- `GoalCompiler`：意图工厂 `interact / standOn / standAdjacent / near / block / mineField`，
  产出 `Compiled{ goal, sacred(LongSet, BlockPos.asLong 域), arrival }` 三元组。
  `block()` 取代旧 `resolveBlockGoal` 的 `near(2.0)` 欧氏球兜底（垫柱事故的几何根源）。
  `Stance{ore, stanceBase, maxBelow}` 分离"矿格（进 sacred）"与"站姿基准（进目标）"。
- `ArrivalSpec`：身体级到达要素（focus/reachSqr/lineOfSight/grounded/membership），
  LOS 唯一实现收编自 MineCompanionTask；interact 默认 LOS 关（与既有任务一致）。

**`calc/NavContext`**：`sacred` 字段 + 4 参 forSearch/forExecution（旧重载委托空集）；
`costOfBreaking` 首查 sacred→INF（forceBreak 不可穿透）；`costOfPlacing` 新增
①sacred 本格/上格→INF ②**全局修复**：`shouldAvoidBreaking(pos.below())`→INF
（不许把方块放在箱子/工作台顶上——放置侧此前完全无保护）；`explainBreakVeto` 加
sacred 诊断分支；`forView` 测试专用 seam（包私有）。

**`calc/NavGoal`**：新 `nearGround(center, r)`（水平半径 + dy∈[-1,+1]），实体追逐
的球体病免疫版；原 3D `near` 保留给显式重试梯。

**`exec/PlayerNav`**：目标供应升级为 `Supplier<Compiled>`（`pullGoal()` 同步刷新
sacred → search/execution 双上下文穿透）；新入口 `PlayerNav.to(compiled…)`；
BlockPos 构造器经 `GoalCompiler.block` 编译（8 个旧调用方一次获得贴脸语义+目标神圣）；
`searchSatisfied` 持续 ARRIVED（修复：空 COMPLETE 次 tick 谎报 TARGET_LOST 且无恢复）。

**STANCE_DUD 协议**：`FailureType.STANCE_DUD` 新增；`GoToThenDoTask.onTick` 的
ARRIVED-但-未 reached 经 10-tick 缓冲后路由 `handleNavFailure(STANCE_DUD)`——
复用各任务现有恢复梯（InteractAt/BreakBlock/InteractEntity 的 repositionable 已收编
该类型）。Mine 的死档拉黑（工作区原有未提交改动）即本协议在 composite 目标上的特例。

**任务迁移**：MoveTo BLOCK kind → `PlayerNav.to(blockCompiled)`（AUTO 推断保留
collision-shape 判定语义）；Mine → `mineField`（全部矿格 sacred——路径不再能顺路
吃掉目标矿，挖矿归任务簿记）；其余 8 调用方经构造器 reroute 自动迁移。
**未做（有意）**：Hunt/Shoot/MobDefense/InteractEntity 的追逐目标形状未改
（现状=实体脚格 exact，已足够紧；换 nearGround 触碰战斗手感，收益残余小——列为
D 期候选）；PlaceBlock 的 standAdjacent 站位改动按计划 R4 门控，待游戏内验证
PlaceManeuver 边潜行兼容后再做。

**move_to `arrival` 参数**：schema `optionalNullableEnum("arrival", "interact"|
"stand_on"|"near")`（numen-api Schema.java:180 已支持）；
Args→MovementTools→MoveToTaskRecord(`Arrival` 枚举, 非 BLOCK kind 传参→教学错误)
→MoveToCompanionTask.blockCompiled() 按覆盖选编译。

**治理**：`docs/pathing-stability-roadmap.md` 已删除（用户判定废案）。

### 测试（全部通过，`./gradlew :common:test`）

| 测试 | 钉住什么 |
|---|---|
| NavContextSacredTest (5) | sacred 挖/放双否决、forceBreak 不穿透、旧重载空集默认 |
| NavGoalMembershipTest (6) | getToBlock/nearGround 拒绝一切悬空格（垫柱地基钉死）；near 球体对照 |
| GoalCompilerTest (6) | 各意图的 goal/sacred/arrival 映射；错位站姿 sacred=矿格 |
| MovesProtectionTest (2, @Tag("mc")) | **真实 Moves.generate 端到端**：箱子破坏必 INF；同地形 dirt 无保护有限价、标 sacred 后必 INF。MC Bootstrap 在本环境可用（非跳过） |
| 既有 12 类（引擎矩阵+forceBreak） | 回归护栏，零改动零失败 |

已知测试缺口（诚实记录）：放置侧"盖住保护方块"的端到端（需 SCAFFOLDS tag 绑定，
无数据包加载的单测做不到）——由 NavContextSacredTest 纯层覆盖规则本身；
`searchSatisfied` 持续 ARRIVED 依赖 NumenPlayer——游戏内验收覆盖。

---

## Phase B：分层粗图（设计稿 v1 → **已按此实现，全测试绿**）

### 实现状态（2026-07-17）

`core/pathing/hier/` 落地：`CellSampler`（纯接口+均质探针语义）、`SectionSummary`
（6 面三态）、`SectionSummarizer`（均质 fast path + 精确全脸扫描）、`CoarseField`
（目标侧多源 Dijkstra：场+判定一次产出）、`SummaryCache`（TTL 20 ticks，tick 线程）、
`McSampler`（BlockHelper 语义 + 27 点均质探针）、`CoarsePlanner`（每维度缓存门面）。
接线：`GoalCompiler.Compiled` 增 `coarseEligible`（bare/runAway=false——场朝它们的
center 是反方向）；`EngineSearch.create` 新重载包装 h（`max(gh, min(field, gh×CAP))`，
`gh<=0` 不膨胀保护 runAway 负 h）；`PlayerNav.startFreshSearch` 距离≥48 且 eligible
时建场，SEALED 判定直接短路出结构化 NO_PATH（省掉 2s 失败预算），continuation 段
复用本导航冻结场。`PathSettings` 新增 6 个 COARSE_* 常量（含成本上限说明）。
测试：`CoarseFieldTest` 7 项——走廊优先于凿穿、截断不判 sealed、软墙永不 sealed、
硬墙耗尽才 sealed、未知地形回 0 不说谎、TTL、段打包往返。
fabric/neoforge 双 loader 编译通过。

### 现实约束（读码结论）

- 快照体系（PathCaches/LoadedChunks）是**每 tick 整体重建、无失效追踪**：
  "Castle Story 式事件失效"在现架构无挂点（加块变更监听=跨 loader 侵入面）。
  → B v1 用 **懒构建 + 短 TTL 缓存**（20 ticks）：摘要按需构建、过期重建；
  自有编辑（执行层挖/垫）天然被 TTL 吞掉，第三方编辑最多陈旧 1 秒。
- 成本账：全脸扫描 ≈ 256 格×2 读×6 面/段；纯空气/纯实心段走 `hasOnlyAir`/
  均质 fast path O(1)。混合段每次构建**封顶**（`COARSE_MIXED_SCAN_CAP`），超限
  → 放弃粗层（无场退化为原启发式），绝不拖垮 tick。

### 语义决策（关键，动之前必读）

1. **摘要是地形性质，能力在查询时施加。** 每段 6 面三态：
   `OPEN`（面层存在 2 高可穿行格）/ `SOFT`(存在可破坏、非保护材质) / `HARD`
   （整面 bedrock/保护/不可破坏）。边(a,d)=两侧面态合成。
2. **距离场 = 有界不可采纳的引导，不是正确性来源。** OPEN 边权=16×COST_HEURISTIC；
   SOFT 边权=16×COST_HEURISTIC+`COARSE_SOFT_CROSS_PENALTY`（挖穿一面的名义代价）。
   SOFT 罚使场沿走行走廊流动（深井导向出口=治疗效果），代价是对"挖穿更优"场景
   高估——与 `near()` 同一教义：有界故意不可采纳，正确性由细搜索+执行复核兜底。
   护栏：`h' = max(goal.h, min(field, goal.h × COARSE_FIELD_CAP))`，误导上界受控。
3. **SealedIn 判定必须保守。** 仅当：起点段经 OPEN+SOFT 边 BFS 前沿**耗尽**
   （非预算截断）且沿途全部段**全脸扫描过**且全在快照内，才产出 SealedIn；
   任何采样/超限/快照外 → Unknown（照常细搜索）。错误 SealedIn=假 NO_PATH
   （会引发错误拉黑），宁可漏报不可误报。
4. **每搜索冻结。** 场在派发时构建（tick 线程，读该次搜索同一快照视图），
   搜索期间不变——引擎 h 按节点创建时缓存，契约不破。
5. **仅长途启用。** goal.center 距离 > `COARSE_MIN_DISTANCE`(48) 才建场/问判定；
   短途维持现状零开销。

### 形态

```
hier/CellSampler      纯接口: passable(x,y,z)/breakable(x,y,z)/uniform(sx,sy,sz)
                      （MC 无关 → GridWorld 风格测试直接给 lambda）
hier/SectionSummary   6 面三态 + anyPassable + fullyScanned
hier/SectionSummarizer 全脸扫描 + 均质 fast path；产出 Summary
hier/CoarseField      从目标段 Dijkstra（OPEN+SOFT 权重如上，段数封顶
                      COARSE_SECTION_CAP）→ {段→下界代价, 判定素材}
hier/CoarseVerdict    REACHABLE / SEALED_IN(frontier 全硬且可信) / UNKNOWN
hier/McSampler        BlockGetter+BlockHelper 适配（含 LevelChunk hasOnlyAir 均质钩子）
```

接线（两处，皆可单行退开）：
- `EngineSearch.create` 新重载带 `LongToDoubleFunction coarseBound`（引擎包装 h）；
- `PlayerNav.startFreshSearch`：距离过阈→建场+问判定；SEALED_IN→直接结构化
  NO_PATH 尸检（"整片区域连挖带垫也封死"）；否则场入搜索。

### 验收与校准

- 纯测试：人造段网格（lambda sampler）——走廊场单调性、SOFT 罚生效、
  封顶退化、SealedIn 保守性（采样不全必 Unknown）。
- ⚠ h 提交点会因场移位：`ProductionBudgetAuditTest`/`CommitmentTest`/
  `DeepWellTest` 预期需复跑校准（引擎测试不接场，不受影响；受影响的是
  未来把场喂进这些场景的新测试）。
- 游戏内：长途 move_to 走廊直奔、深井不再烧预算乱探、封死洞穴秒报。

## 测后修复（2026-07-17 首测日志驱动）

**病根：计划端"能站"≠执行端"能贴"**（水面/树叶/悬空三分歧）+ 确定性重搜复现同一计划 = 死循环。
- 桥 backplace 三分法（`Moves.bridgeSupport`）：脚下流体→INF、实心→过执行端同一 `canPlaceAgainst`、
  空气→放行（链桥承重墙）；`mustBeSolidToWalkOn` 已删。pillar 加 `pillarBaseIsFluid` 否决（水面浮身垫柱）。
  回归钉 5 条（BridgeSupportTest，MC 引导真跑，含"树叶判定=执行谓词判定"一把尺契约）。
- 粗层止血：场每导航建一次（GOAL_MOVED 才重建）；COARSE_EXACT_SCAN_CAP 192→512。

**边缘放置五修（P1-P5，Baritone/LiquidBounce 对照后定案，LOS 纪律保留）**：
- P1 拒放反馈环：执行端 NO_SUPPORT（姿态无关的确定性拒绝）→ 格子进 `NavContext.deniedPlace`
  （导航生命周期，favoring 同类），重搜不再端出同一座桥（rim-standing 循环破解）。日志 `DENY-PLACE`。
- P2 过冲回修：`edgeToward` 身位越过瞄点（max-axis <0.29，Baritone #208 同款）→ 倒退重开视线。
- P3 打掉软遮挡：解析器带出首个遮挡格（`PlaceResolution.occluder`，OUTLINE clip 下草/雪/叶都算），
  硬度≤0.2 且非保护 → `Interaction.attackBlock` 揍掉（Baritone "something in the way" 同款）。
- P4 条件潜行：sneak 仅在距目标格 <1.1（max-axis）或即将按键时——搭桥速度税解除。
- P5 朝向严格档：`Hints.strict`（build_structure 预留）超时拒绝妥协并点名朝向；默认档妥协时打日志。

### 明示推迟（B v2 候选，不在本次）

- 走廊约束（continuation 段丢弃离粗走廊 >k 段的边）；
- 事件驱动段失效（需跨 loader 方块变更钩子）；
- 自有编辑即时 dirty（executor 挖/垫回写脏标）；
- 多同伴共享场缓存的并发精细化。
