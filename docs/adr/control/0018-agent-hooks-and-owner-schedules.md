# 0018 插件听得到大脑,主人能定时对她说话

- 状态:已接受,已落地(实现说明见文末)
- 日期:2026-11-01
- 另见:control 0014(登记归属与撤回)、control 0015(KubeJS 联动)、issue #137

## 背景

issue #137 要插件可订阅的 Agent 生命周期钩子,外加用户可配的定时触发与一个"高级"页。现状(1.21.1,1e5920e6e):

- 插件经门只能订阅身体上的事:`CompanionEvent` 只有 SPAWN、REMOVE、DEATH、ABORT、HURT。大脑开轮、收轮、活的收尾,插件收不到。
- 想听大脑只能绕过门直接 `CompanionLoops.get(uuid).subscribe(...)`:`AgentLoop.subscribe` 没有退订、不进 0014 的作用域;`AgentLoop.emit` 不隔离,一个监听器抛了会把内核卡在一步中间,排在它后面的订阅者也收不到;主人重进时 `install` 换了循环,订阅悄悄丢了。
- `TaskSessionHooks` 有 4 个触发点,但唯一的登记方早已删掉(0f52572cf),是没有订阅方的死代码。
- 主人没有可持久的周期提醒:她自己的表(`TimerRegistry`)一次只响一次、最长 1200 秒,是给"世界不会通知你"的事准备的。

主流:pi 的扩展事件分只通知、可改写、可拦截三类,通知类逐个隔离、出错继续;pi 没有权限层,拦截就是它的权限机制。Claude Code 的 hooks 执行后的不能拦,文档明说硬性允许/禁止用权限系统而不是 hook。定时(Claude Code 的 `/loop` 与定时任务、ChatGPT Tasks)到点都是往会话里送一段话,错过的不补,有条数上限。

## 决定

1. **插件经门订阅大脑事件,只读**:`CompanionEvent` 加 `RUN_STARTED`、`RUN_ENDED`(带结局:完成、失败及原因、被切断及原因)、`TASK_FINISHED`(活的编号、名称、状态、结果文字)。不开拦截、不开改写:动世界的决定只归权限层(宪法 §八"没有第二个入口"),模型的动作是整段程序、真正的动作在 `GatedAct` 里逐个判,工具层的闸看不到;改写参数会让模型拿到不是它发出的那次请求的回执,违背知情权。插件要限制什么,登记权限的对象组;要给她加信息,已有身体状态片段、事件类型、内容目录三扇门。
2. **一个来源**:事件的源头仍是内核的 `LoopEvent` 与 `NumenEvents.taskFinished` 那一处;门仍是 `CompanionEvent`/`CompanionEvents`(登记顺序、逐个隔离、句柄随插件撤回)。不把 `LoopEvent` 直接交给插件(它是内核的封闭类型,字段带内部类型),门上的载荷是只追加字段的 record。桥在 numen-api 的大脑一侧:订阅每个循环的事件、攒下来,在 `CompanionLoops.tick` 里于服务端主线程、内核两步之间统一派发,所以插件回调里调门是安全的;循环换新时桥随之换,订阅不丢。`TASK_FINISHED` 不论那件活的收尾归了程序还是进了收件箱都发一次。外部大脑驾驶时没有 run,`RUN_*` 不发,`TASK_FINISHED` 照发。
3. **内核的事件派发不让一个订阅者拖垮别人**:`AgentLoop.emit` 逐个隔离订阅者,出错记日志、接着派发给下一个;连续出错不刷屏。
4. **删掉 `TaskSessionHooks`** 与只为它服务的代码。
5. **主人定时对她说一句话**:一条定时 = 一句话 + 间隔(游戏刻)。到点等于主人说了这句:经主人说话的同一入口进她的收件箱,走权限、停止、外部大脑等全部现成机制,不另开执行通道;聊天流里标明"定时"。时间按游戏刻记绝对的下次到期刻(与 `TimerRegistry` 同一口径:世界不跑表不走,重启不重新计时);先落盘下次到期再送话,最多触发一次;错过的不补;主人不在线时到期的合并成一次,等他回来送。每只同伴条数有上限、间隔有下限(每触发一次就是一次模型调用)。只有主人能增删改;不做现实时间的 cron,不做插件登记的定时,不做"到点直接调用操作"。
6. **界面不单开"高级"页**:定时是她和主人之间的东西,放在资料页里作为一节,与"记忆"并列(界面规则:一个分区一页说完,不藏子页)。插件订阅了哪些事件对主人没有用,不做界面。

## 后果

- 插件作者有了正门:撤得回、出错隔离、换循环不丢;不再需要绕过门订阅内核。
- 主人能让她周期性地做事,而她始终知道是主人让她做的;代价是每次触发的模型调用,由条数与间隔的约束兜住。
- 先做 1.21.1;其他版本统一移植时一起带上。

## 实现说明

- **删 `TaskSessionHooks`**:连同只为它触发的 `HandPinRelease`(手部空闲边沿的计数器)、构建里对它们的排除一起删;两处调用点的取消路径照旧,只是不再通报。
- **大脑事件**:`CompanionEvent` 加 `RUN_STARTED`(`RunStarted`)、`RUN_ENDED`(`RunEnded`,`Outcome` ∈ DONE / FAILED / HALTED,`reason` 是原因码:失败是 `llm.*`/`endpoint.*` 码,切断是 `halt.*` 码)、
  `TASK_FINISHED`(`TaskFinished`:身体、编号、任务名、状态、她读到的结果文字)。载荷是 record,只追加字段;给的是 UUID(run 的开收不依赖身体在不在世界里),
  `TASK_FINISHED` 因为收尾那刻身体就在,给身体。派发的端、线程、顺序、出错规则写在 `RunStarted` 的 javadoc 上。
  - `RUN_*`:`BrainEventBridge`(`api.brain`)一座桥一个循环,挂在 `CompanionLoops` 的登记项上和 `FeedEncoder` 并排;只攒 `LoopEvent.RunStarted`/`RunEnded`,在 `CompanionLoops.tick` 里
    `loop.tick()` 之后派发(服务端主线程、内核两步之间),循环换新、遣散、放手时先 `flush` 再收,所以每个开了的 run 都有收;关服不派。外接大脑驾驶时没有 run,所以没有 `RUN_*`(驾驶翻转时被切断的那一次照发,原因 `halt.external`)。
  - `TASK_FINISHED`:`NumenEvents.taskFinished` 那一处在 `emit` 之后发一次,收尾归了在跑的程序还是进了收件箱都经过这里;外接大脑驾驶时照发。同步在收尾那一刻的服务端主线程上派,回调里不要去推进她的任务槽。
  - 门上订阅的隔离、顺序、撤回仍是 `CompanionEvents` 那一套(登记顺序、逐个 catch、句柄随插件撤回);`ScriptPlugin` 的事件名表加了 `run_started`、`run_ended`、`task_finished`。
- **内核隔离**:`AgentLoop.subscribe` 登记的订阅者各带一个"上一次是不是出错了";`emit` 逐个 try,运行时异常记一条错误日志后接着送给后面的订阅者,同一个订阅者连续出错只记第一次,它再成功一次后重新计。
- **定时**:`OwnerSchedules`(`api.brain`)是一只同伴的账,存她家目录的 `schedules.json`(`JsonDoc`,与收件箱同一条写车道)。`CompanionLoop.tick` 在主人在场(`ownerOnline`)时问一次
  `due(游戏刻)`:到期的每条先把下次到期刻改成 `此刻 + 间隔` 并落盘,再各自送一次话;不在场时不问,到期刻留在过去,他回来后的第一次问到就送一次,错过几个间隔都是一次;错过的不补。重载存档读回的就是落盘的绝对刻,所以不重发。
  送话经 `CompanionLoop.submitScheduled`:和主人的话同一个 `enqueueOwnerWords`,私聊、回话回面板,`<query>` 开标签上多一个 `source="schedule"`(`ConvoLog.SCHEDULED_ATTR`,认它只在 `ConvoLog.scheduled` 一处),聊天流据此在这一句前标一行"定时"。
  模型看到的就是带这个属性的 `<query>`——聊天流的标记和日志是同一份内容,要让模型看不见得另盖一份章,而"模型是否知道是定时"本来没定,先让它知道(属性可去:`EventQueue.scheduledQuery` 一处)。
  - 增删改走库协议 `Libraries.SCHEDULES`(`list`/`put`/`delete`):判"是不是她的主人"复用札记那一处(`ownedCompanion`),没有服务端大脑拒绝;答复是这位玩家看得到的整表。客户端每 5 秒与札记一起重问一次,状态以服务端为准。
  - 资料页"定时"一节排在"记忆"之后、"共同群聊"之前:一行一条(话、"每 N 个游戏小时/日"),点开 `ScheduleEditPanel`(话、一个数、单位:游戏小时或游戏日),删除走确认卡;新增是最后一行。
- **限制与取值**:每只最多 `MAX = 8` 条(与她自己的表 `TimerRegistry.MAX_PER_COMPANION` 同一量级,每条触发一次就是一次模型调用);间隔 `MIN_EVERY_TICKS = 1000` 刻(一个游戏小时,现实 50 秒,
  和 Claude Code 定时任务、`/loop` 的"一分钟起"同一个量级);最长 100 个游戏日(够"每天""每周",也挡住乘法溢出);一句话最长 500 字。最坏情形是 8 条都是一小时一次,即现实每 50 秒最多 8 次模型调用,
  再多的由主人自己拉长间隔。服务端判,界面不替它判。
- **测试**:`OwnerSchedulesTest`(到期只交一次、错过不补、重载不重发、不在场合成一次、限制);`NumenPluginScopeTest`(大脑事件顺序、隔离、撤回);`AgentLoopTest`(一个订阅者抛了其余照收);
  `EventQueueTest`(定时的话带来源、`ConvoLog.scheduled`);GameTest:`BrainGameTests` 里插件听 run 开收与结局、外接大脑驾驶时不发 `RUN_*`、`PluginGameTests` 里听 `TASK_FINISHED`、
  定时到点只送一次且重载不重发、主人不在时到期的回来只收一句、只有主人能增删改(批 `numen_schedule` 要等满一个最短间隔,时限放宽)。
