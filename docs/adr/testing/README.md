# 测试的决策记录

单元测试、GameTest、端到端与评测各测什么,GameTest 怎么写、不稳定怎么处理。命令与跑法看 `CONTRIBUTING.md`。

| 编号 | 日期 | 决定 | 状态 |
|---|---|---|---|
| [0001](0001-gametest-short-deterministic-fixed.md) | 11-04 | 测试分层照主流:GameTest 只测短、确定、固定场地的集成,不用重试;算法与长趟、真实地形进纯 JVM;可靠性矩阵进慢批 | 已接受,未落地 |
