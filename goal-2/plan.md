# goal-2 实施计划

## 1. 原始目标

修复 `goal-1/tasks.md` 中“2026-08-04 goal-1 功能漏洞审查”列出的 6 个遗留问题，并修复切换人格后状态 Tab 错误显示“积压”信息的问题。

## 2. 当前证据与问题定义

当前工作树仍包含 goal-1 的未提交实现，不能回退或覆盖。已读取现有实现并把 7 个问题具体化：

1. 服务端 `/goal <同伴名>` 不带后续命令时走 `sendDirect`，即使首词是已拥有同伴名，也会把名字当成 goal 内容广播给所有同伴。
2. 聊天侧所有未知首词都会作为直接创建内容；`/goal comlete` 之类的近似拼写不会报错，会误建 goal。
3. `GoalStatus.FAILED` 与 `GoalState.markFailed` 只有测试使用，最终 API 失败没有同步到 goal 状态，状态模型与生产行为不闭环。
4. 外接大脑占用或全局 MCP 模式开启时，`add/resume` 会被 `startGoalExecution()` 静默跳过；释放控制后没有自动续跑。
5. `/goal add`、`/goal update` 等需要参数的精确候选按 Enter 会直接发送，输入框被清空，用户无法自然继续输入参数。
6. `GoalCommands.execute(null, ...)` 会修改临时 `GoalState.none("")`，返回成功但调用方无法拿到新状态，属于静默丢失。
7. `EntityAgentLoop.hasQueuedPrompts()` 文档声称只表示主人 prompt，实际却用 `!inbox.isEmpty()` 把 persona-change 等 ambient event 也算进去；`ItemsView` 再读取 prompt 数量，导致切换人格后状态 Tab 显示错误的“积压”状态（通常是 0 条）。

## 3. 实现原则

- 保留已交付的 `/goal <内容>` 直接创建语义，不把所有未知词一刀切为错误。
- 服务端单词参数若精确匹配已拥有同伴，应只向该同伴查询状态；不匹配时仍可作为直接 goal 内容广播。
- 拼写保护只拦截与已知子命令高度接近的疑似 typo，并返回候选提示；普通中文/自然语言目标继续直接创建。
- `FAILED` 用于 API 重试耗尽后的真实失败状态，保留持久化错误；用户可通过 `/goal resume` 恢复。死亡、主人停止和外接大脑接管不应误标失败。
- 外接大脑释放时自动检查 active goal 并补排执行；全局 MCP 模式关闭也要有等价恢复入口，且保持幂等，不能重复排队。
- 需要参数的命令候选补全后保留输入焦点并添加空格；只有可直接执行的完整命令才允许 Enter 立即发送。
- `GoalCommands.execute` 对 null 采用显式失败（fail-fast），避免产生不可观测的新状态。
- 状态 Tab 的“积压”只统计真实 owner prompt；ambient event 仍保留在 inbox，随下一次合法回合进入上下文。

## 4. 验证方案

- 为服务端路由拆出可测决策或补充 Brigadier 路由测试，覆盖：精确同伴、直接内容、带同伴命令、无同伴。
- 扩充 `GoalCommandsTest`，覆盖近似拼写、自然语言直接创建、null 参数、FAILED → resume。
- 扩充 `EntityAgentLoop`/纯逻辑测试，覆盖 API 最终失败、外接大脑释放自动续跑、重复释放不重复排队、ambient event 不计入 prompt backlog。
- 扩充 `CommandCompletionPolicyTest`，覆盖需要参数候选的 Enter/Tab 行为和普通命令回归。
- 运行 `:api:common:test`、`:api:common:checkServerSafe`、`:api:common:compileClientJava`，以及 Fabric/NeoForge 相关编译或构建。
- 按前端视觉验证规范启动实际客户端，保存状态 Tab 切换人格前后截图和命令补全截图；截图先裁剪到相关区域，再由独立检查流程读图确认没有信息重叠或错误“积压”。

## 5. 风险与默认假设

- “挤压信息”结合用户补充“积压”和当前代码，默认解释为切换人格后状态 Tab 错误出现 backlog/积压状态，而不是几何宽度压缩；实际截图阶段仍检查几何布局。
- API 失败只在现有重试预算耗尽后标记 FAILED；瞬时失败重试成功不改变 goal 状态。
- 当前主工作树存在大量 goal-1 未提交改动，所有编辑在其上增量完成，不清理、不还原用户改动。
- goal-1 记录还提到 1.21.10 临时工作区，但当前可写权威工作区是本仓库；先完成并验证主仓库，若后续确认临时工作区仍存在且属于交付范围，再同步同一修复。

## 6. 回滚方案

- 每个 task 单独提交；行为变化配套测试，可按单个提交回滚。
- 不改 goal JSON 的现有字段名；FAILED 闭环使用当前 schema，避免迁移用户数据。
- UI 修复只调整 backlog 判定和补全策略，不改状态页整体布局或用户已有主题资源。

