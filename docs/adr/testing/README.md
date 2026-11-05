# 测试的决策记录

单元测试、GameTest、端到端与评测各测什么,GameTest 怎么写、不稳定怎么处理。命令与跑法看 `CONTRIBUTING.md`。

| 编号 | 日期 | 决定 | 状态 |
|---|---|---|---|
| [0001](0001-gametest-short-deterministic-fixed.md) | 11-04 | 测试分层照主流:GameTest 只测短、确定、固定场地的集成,不用重试;算法与长趟、真实地形进纯 JVM;可靠性矩阵进慢批 | 已接受,未落地 |
| [0002](0002-gametest-reproducible-and-admission.md) | 11-05 | GameTest 必须可复现(固定原点、随机定种子或不依赖、每刻等齐全部后台、每条从已知状态开始并收场);只有离不开真世界的才进 GameTest,判据与提示进单测 | 已接受,未落地 |
