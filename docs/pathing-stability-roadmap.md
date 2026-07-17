# 寻路长期稳定路线（v3 设计）

> 2026-07-17 定稿。依据：柱子森林事故复盘、引擎范式鉴定（加权A*+RTAA*表）、
> Baritone/mineflayer 源码研读、游戏工业界寻路调研（navmesh/HPA*/D* Lite/
> 实时搜索 scrubbing 文献）。本文件是寻路层的设计契约：任何行为改动先对照
> 五原则，再动测试，最后动代码。

## 一、五条原则（设计宪法）

1. **确定性优先。** 同一世界+起点+目标 → 同一计划。搜索内部零自适应记忆，
   行为可复现、故障可复盘。反例即教训：RTAA* 学习表在执行失败场景把搜索
   推向邻列，垫柱成林（scrubbing，文献明载的实时搜索固有震荡）。
2. **记忆只住两个地方。** 世界本身（挖了/垫了的方块就是账本）和任务层
   （LLM 拿 typed failure 换策略）。引擎不记上次发生了什么。
3. **一份到达语义。** `NavGoal` 是唯一词汇表：搜索终止条件与任务到达断言
   同一个 `isAt`。到达语义只写一遍。
4. **结构胜过预算。** 鲁棒性来自分层（粗图管方向与可达性，细图管脚步），
   不来自调大预算（机器相关）或记忆补偿（震荡病根）。
5. **失败必须上抛。** 寻路永不无限重试。停滞止损后带着"什么动作在哪里
   反复失败"上抛（BOXED_IN + 具体 maneuver），换策略是任务层/LLM 的事。

## 二、目标形态

```
L3  任务层(LLM)     换策略/换目标/放弃 ← 消费 typed failure
L2  PlayerNav       计划生命周期: 三型重启(GOAL_MOVED/SEGMENT_DONE/EXEC_FAILURE)、
                    favoring、计划惯性、停滞止损
L1.5 RegionGraph    [规划中] chunk 段(16³)连通摘要图: 方向先验 + 可达性秒判
L1  engine          确定性预算加权A* + h提交 partial（学习表按三期审判退役）
L0  执行层          每tick代价复核、premise快失败、超时看门狗;
                    动作原语在 core/act（Interaction/BlockDigger/Placement/Ballistics）
```

RegionGraph 一次性治三个病：
- **洼地**：粗层距离场里深井不是洼地，细搜索直奔出口——学习表存在的理由消失；
- **假 NO-PATH**："被封死"在粗层一眼判出，不烧细层预算；
- **长途震荡**：partial 提交约束在粗层走廊内，贪婪 h 提交的病理失去土壤。

## 三、分期

### 第 0 期（2026-07-17 已完成）
- EXEC_FAILURE 重算换新表（学习表的悲观值对自建地形必然超估——写回只升不降，
  而自己垫的方块降了真实代价）；SEGMENT_DONE 健康分段保表；GOAL_MOVED 换表清账。
- 重算起点站边缘兜底（包围盒真实压着的可站邻格）。
- PillarDriver 掉落快判（脚低于柱基当 tick 判 premiseBroken）。
- BOXED_IN 报告带具体失败动作（kind+坐标）。
- 回归：PillarForestTest（写回超估 + 新表复用已建柱）。

### 第 1 期：稳定化收尾（2026-07-17 已完成）
1. **学习表审判 → 已退役**：ProductionBudgetAuditTest 证明生产预算把洼地类
   地形连吞带余两个数量级（深井/双口袋/3×深井全部单次 memoryless COMPLETE），
   HLearningTable 及 swap/refresh 全链路删除，引擎退回纯确定性。超出预算保险丝
   （50 万展开）的洼地按原则 5 走 BOXED_IN 上抛——上抛优于爬行。
2. **预算已改墙钟制**：`SearchBudget.timed`（primary 500ms / failure 2s +
   50 万展开保险丝），同一时间预算在任何机器上买到它能探索的量，无逐机调参。
   测试仍用确定性的展开数预算（`SearchBudget.of`）。
3. **计划惯性 → 审计后确认已结构性满足**：重算只由执行失败/目标移动/分段
   边界触发，无周期性重算；favoring（g×0.5）在位。Detour 的替换阈值防的是
   "每帧重算抖动"，我们的架构没有它的发生土壤，不另加机制。
4. 回归测试矩阵同步更新：新增 ProductionBudgetAuditTest；DeepWellTest 保留
   （钉 h 提交规则，饥饿预算隔离）；PillarForestTest 改钉"确定性重搜必复用
   已建柱"；OscillationTest/LearningLifecycleTest 随表退役删除。

### 第 2 期：RegionGraph 粗层（1–2 周，先出设计稿再动工）
- 节点 = chunk 段（16³），边 = 面连通性摘要 + 粗代价（走/挖档位）；
  随 PathCaches/NavSnapshot 增量失效。
- 用法：粗层距离场作细层启发式（洼地消融）；可达性预判（NO-PATH 秒答）；
  长途沿走廊分段（partial 永在走廊内）。
- 验收：深井/双口袋在小预算、无学习表下通过；柱子森林结构性不可能。

### 第 3 期：差异化（随需立项）
- 生存代价织入：怪物威胁/光照入边代价（reflex 链数据现成）——Baritone 没有。
- 多同伴共享 RegionGraph（服务端优势——Baritone 做不到）。
- 走廊内方块事件驱动失效（补 Baritone 的执行视界盲区）。

## 四、治理

- **测试矩阵即契约**：PillarForestTest（失败记忆病理）、OscillationTest（双口袋）、
  DeepWellTest（深井）、二期新增走廊测试。任何寻路行为改动先过测试审判。
- **公共契约只有两个名字**：`PlayerNav`（怎么去）+ `NavGoal`（什么算到）。
  包外出现其它 pathing 内部类 import 即为回归。
- **partial path 是承重墙**：runAway/分支挖矿依赖"永不到达+最优前缀"，
  任何"只收完整路径"的提议直接否决。
- **保护是硬闸门**：功能方块 do_not_break 硬 INF、grind 由 forceBreak
  （modify_terrain）唯一开关——不引入 Baritone 式"软避免"档，替玩家拆箱子
  不是代价问题，是权限问题。
