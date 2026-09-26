# 智能体循环:ReAct + 事件队列(设计稿)

同伴的大脑在客户端:一只同伴一个循环,调模型、派工具、收结果,主人的话和世界事件进同一个队列。
这份设计把现在的 `EntityAgentLoop` 重做成**一个 ReAct 循环加一个事件队列**,参照 pi-mono 的
`packages/agent`(`agent-loop.ts`、`agent.ts`、`ai/src/api/transform-messages.ts`)。

模型看到的内容、工具串行、主动性档位、失败重试一次、自动压缩、长期目标,这些行为不变;
变的是结构,以及顺带修掉的一批已经会出错的问题(§十四)。

---

## 零、为什么重做

`EntityAgentLoop` 约 2000 行,状态和判断长期打补丁:

- **"在不在跑"散在 7 处**:`awaitingLlmResponse`、`compacting`、`goalJudging`、`dispatcher.busy()`、
  `turnPause`(4 种值)、`dead`、外接模型驾驶。
- **"能不能开轮"有三套不同的条件**:`maybeDrain` 只看死亡和外接,`clientTick` 另看在飞与工具,
  `tryStartTurn` 再加暂停与压缩。停牌时来一条急件,每 tick 刷一条"主动开轮"(实测 10 秒 191 条)。
- **开轮入口 12 处**:`tryStartTurn` 7 处直接调用,`maybeDrain` 5 处。
- **发请求的代码 4 份**:正常一轮、失败重跑(逐行复制,但没过停牌/端点/压缩三道闸)、压缩、目标评估。
- **协议修补 4 处**:打断、复活、读盘恢复、失败收尾,各自往历史里补"(已中断)"或合成工具结果,
  三种文案、两种构造方式,条件各不相同。
- **UI 副作用写在循环逻辑里**:气泡、聊天行、提示条、语音、MCP 记录、token 记账。
- **没有测试**:整个类依赖 `Minecraft.getInstance()`;GameTest 只跑服务端,客户端循环零覆盖。

普查出的已经会出错的问题见 §十四。

---

## 一、参照:pi-mono 怎么做

1. **"在不在跑"只看一个对象** `activeRun`(一次 run 的 promise + AbortController)。所有入口只问它:
   闲就开 run,忙就入队。检查和占位是原子的。
2. **一次 run 是两层循环**(`runLoop`):
   ```
   pending = 取 steering
   while (true):
     while (还有工具调用 || pending 非空):
       注入 pending → 调模型 → 出错/中断就结束
       执行这批工具 → 结果进上下文 → turn_end
       pending = 取 steering
     followUps = 取 follow-up
     有 → pending = followUps; continue
     没有 → break
   agent_end
   ```
3. **两种插话,取出点固定**:steering 在这批工具执行完、下次调模型前插入,不打断正在执行的工具;
   follow-up 只在本来要停时插入。队列从不自己唤醒循环;run 收尾时再查一次队列,
   不会出现"有消息没人开轮",也没有"反复尝试开轮"。
4. **中断**:每个 run 一个取消令牌,传给模型流和工具。流到一半的回复标成 aborted,这批工具不再执行。
5. **协议配对只在发请求前保证一次**(`transformMessages`):跳过 aborted/error 的回复,
   给没有结果的工具调用补一条错误结果。中断、崩溃、读盘都不在当时修补历史。
6. **失败不抛异常**:模型出错写成带原因的消息,run 结束;要不要重试由外层按错误类型决定。
7. **状态由事件归约**:循环只发事件(agent/turn 开始结束、消息流、工具开始结束),
   UI 和持久化订阅事件,循环不碰 UI。
8. **自定义消息**经唯一的 `convertToLlm` 转成模型能读的消息——对应我们的世界事件怎么进上下文。

pi 默认"插话一次取一条"、工具并行;这两点我们不照搬(§七、§六)。

---

## 二、分层

```
┌─ 同伴门面 EntityAgentLoop(api client,依赖 Minecraft)─────────────────────┐
│  拼请求:系统提示、运行期状态;模型与人设绑定;外接模型取件口;把端口接给内核  │
│  订阅者:TurnPresenter(打字机/语音/气泡/聊天行/提示)、TokenLedger、           │
│          McpTranscript、显示记录                                            │
└──────────────────────────────┬──────────────────────────────────────────────┘
                               │ 端口
┌─ 循环内核 AgentLoop(agent 模块,com.dwinovo.numen.agent.loop,纯 Java)──────┐
│  Run(阶段 + 取消令牌)、Hold(停牌)、pump()、halt()、两层循环、重试决定、       │
│  LoopEvent 事件、LoopStatus 只读快照                                         │
└───────┬──────────────┬───────────────┬───────────────┬──────────────────────┘
        │ ModelPort    │ ToolPort      │ Transcript    │ Inbox
   NumenLlmClient   ToolDispatcher   ConvoState       EventQueue + EventTypes
   (ai,请求前过      (api client,     + ConvoLog       (agent,熟度、类型表)
    ProtocolView)     串行、兜底超时)   (ai)
```

照 pi-mono 的 `ai → agent → coding-agent` 分三层,模块依赖单向:

| 模块 | 内容 |
|---|---|
| `ai`(现有,纯 Java) | 传输、服务商、`NumenLlmClient`、`ConvoState`/`ConvoLog`/`CompactSplit`;新增 `ProtocolView` |
| `agent`(新建,纯 Java,依赖 ai) | 循环内核(`Run`、`Hold`、`pump`、`halt`、`LoopEvent`、`LoopStatus`、端口接口);队列 `EventQueue`/`EventTypes`/`JsonlJournal`(从 api 的 `event` 包搬来,包名 `com.dwinovo.numen.agent.inbox`);长期目标 `GoalState`/`GoalPrompts`(从 api 搬来)与 `GoalSteward`;`Compactor` |
| `api`(依赖 agent) | 同伴门面 `EntityAgentLoop`、`ToolDispatcher`、`TurnPresenter`、`SystemPromptComposer`、`RuntimeState`;`NumenEvents` 与服务端 `EventOutbox`(用到 Minecraft,留下) |

- **内核单独成模块**:不放 api——api 依赖 Minecraft,"不引用 Minecraft"只能靠自觉,编译器拦不住;
  不放 ai——ai 是"大脑与服务商之间的连接层",循环是大脑本身。`agent` 模块零 Minecraft 依赖,
  由构建保证;测试在 `agent/src/test`,不带 Minecraft 类路径。
- **搬家不破坏插件**:`EventQueue`/`EventTypes`/`JsonlJournal`/`GoalState`/`GoalPrompts` 都不引用 Minecraft;
  队列与类型表只在 api 内部使用(`core`、`plugins` 与仓外桥接均未引用),改包名没有外部调用方。
- **接入构建照 `ai` 的样子**:`settings.gradle` include;`agent/build.gradle`(`java-library` + 发布坐标
  `numen-agent-<minecraft_version>`);`api:common` 以 `api project(':agent')` 依赖;加载器发行 jar 平铺它的
  类与源码;NeoForge 开发运行带上它的源码集;插件编译路径 `compileOnly`;根构建发布依赖它。
- **ProtocolView 放 ai**:`NumenLlmClient.chatStreaming` 把消息转成服务商格式是全仓唯一的出口,
  正常一轮、压缩、目标评估都经过它。
- **线程**:内核的一切状态只在客户端主线程读写。异步回调(模型流、工具结果)由门面注入的
  主线程执行器切回主线程再进内核。

---

## 三、状态:一个 Run + 一个 Hold

### Run

```java
final class Run {
    final long id;              // 递增;异步回调带着它回来,不是当前 run 就丢
    Phase phase;                // MODEL | TOOLS | COMPACT
    final CancelToken cancel;   // 传给模型调用;halt 时取消
    boolean ownerSpoke;         // 这次 run 注入过主人的话(语音硬停还是句界衔接)
    boolean retried;            // 这条链已经重试过一次
}
```

`run == null` 就是闲。取代全局 `turnGeneration`、`awaitingLlmResponse`、`compacting`:
异步回调核对 `run != null && run.id == 回调带的 id`。

目标评估不是 run(见 §十一),它有自己的"在飞"记录,但不参与开轮判断。

### Hold(停牌)

一个值,谁解开写死在表里:

| Hold | 什么时候进入 | 什么解开 |
|---|---|---|
| `DEAD` | 身体死亡 | 复活 |
| `EXTERNAL` | 外接模型驾驶(**现算**自 `McpMode.driving()`,不存) | 交还 |
| `OWNER_STOP` | 主人按停止 | 主人再说话 |
| `BLOCKED` | 模型端点不可用(未绑定、没 key) | 主人再说话;或绑定变更 |
| `FAILED` | 调用失败且不再重试 | 主人说话;或来了急件 |

`DEAD` 与 `EXTERNAL` 优先于其余三种(身体不在/驾驶席不在内脑手里时,别的停牌无意义)。
存储上是 `dead` 布尔(身体事实)+ `stopped` 枚举(`NONE/OWNER_STOP/BLOCKED/FAILED`)+ 外接现算,
对外只暴露合成后的一个 `Hold`。

### LoopStatus(给 UI 的唯一读口)

```java
record LoopStatus(Phase phase /* null = 闲 */, Hold hold, boolean bodyTaskRunning,
                  List<String> queuedPreview, double compactProgress, String activity) {
    boolean busy()         { return phase != null || bodyTaskRunning; }
    boolean canInterrupt() { return busy() || !queuedPreview.isEmpty(); }
}
```

`isBusy`、`canInterrupt`、`hasQueuedPrompts`、`isCompacting`、presenter 的两个 supplier、
`NumenGateway` 的 `Delivery` 判断、ItemsView/NumenScreen/SpeechBubbles 各自拼的"忙不忙",
全部改读它。

---

## 四、唯一的推进点 `pump()`

任何可能改变"能不能开 run"的事发生后都只调它:入队、run 结束、停牌解开、每个客户端 tick。

```java
void pump() {                          // 一步一步走,直到没有能同步做完的事;不递归
    if (pumping) { pumpAgain = true; return; }   // 推进那一圈里又有人要求推进:只记一笔
    pumping = true;
    do { pumpAgain = false; } while (step() || pumpAgain);
    pumping = false;
}

boolean step() {                       // "下一步做什么"只有这一个判断
    if (run != null) return false;                          // run 里的边界自己取队列
    if (hold() == DEAD || hold() == EXTERNAL) return false;
    if (hold() == null && inbox.ripeness(now, level).ripe()) { startRun(); return true; }
    if (inbox.hasControl()) return runControl();            // 开不了 run 时控制条目自己执行,见 §七
    return false;
}
```

- **熟度与取件看同一段队列**:`ripeness` 只数第一条控制条目("墙")之前、声明会叫醒她的条目;run 取件
  (`takeForCall`)看的也是墙之前这一段、同一份声明。熟了就一定有叫醒她的条目,开出来的 run 一定取得到它,
  空轮在结构上不存在。
- **不递归**:run 在开它的那一步里就结束(端口当场回话)、清空执行完、订阅者推了新输入,再要求推进时只记
  "再看一眼",由同一圈接着走。调用栈不随步数变深;哪天某一步又没推进任何东西,循环停下,不会越陷越深。
- **只在真的开 run 或执行控制条目时打日志**;每 tick 调一次也不会有输出,结构上不可能刷屏。
- `tick` 调 pump 只是为了"攒够时长"这一条;其余情况都由事件当场触发。
- 端点检查在 `startRun` 里:不可用就进 `BLOCKED` 并通过事件提示主人,不开 run。

---

## 五、一次 run:两层循环

内核是异步的(模型和工具都回调),把 pi 的 `while` 展开成三个步进函数,语义逐条对应:

```java
void startRun() {
    run = new Run(nextId++);
    emit(RunStarted);
    turn();
}

// 内层循环的一次:注入 → (压缩)→ 调模型
void turn() {
    if (needsCompaction()) { compact(then = this::turn); return; }   // §十一,先压缩再注入
    Batch steer = inbox.takeSteering();          // 取光所有"插话"条目,合成一条 user
    if (!steer.isEmpty()) { transcript.addUser(steer.text()); run.ownerSpoke |= steer.fromOwner(); }
    if (!transcript.awaitsAnswer()) { end(DONE); return; }  // 没有新输入也没有待答的工具结果
    run.phase = MODEL;
    model.call(request(), run.cancel, run.id, this::onModel);
}

void onModel(long id, ModelOutcome out) {
    if (!current(id)) return;
    switch (out) {
        case Failed f    -> end(ERROR(f.reason()));
        case Answered a  -> {
            transcript.addAssistant(a.turn());
            emit(AssistantMessage(a.turn()));
            if (a.turn().hasToolCalls()) { run.phase = TOOLS; tools.run(a.calls(), run.id, this::onToolsSettled); }
            else afterFinal();
        }
    }
}

void onToolsSettled(long id) { if (current(id)) turn(); }   // 工具结果已逐条进历史

// 内层要停了:有插话继续内层,有接续条目转成插话继续,否则收尾
void afterFinal() {
    if (inbox.hasSteering())  { turn(); return; }
    if (inbox.hasFollowUp())  { inbox.promoteFollowUp(); turn(); return; }
    end(DONE);
}

void end(RunEnd reason) {
    Run finished = run; run = null;
    emit(RunEnded(finished, reason));
    afterRun(finished, reason);   // 重试(§十)、目标(§十一)
    pump();
}
```

- **工具串行**(不照搬 pi 的并行):身体只有一个动作槽。`ToolDispatcher` 保持串行、兜底超时、
  展开闸;结果逐条经 `Transcript` 写入,一批全部结算后回调 `onToolsSettled`。
- **异步身体任务**:世界动作工具的"结果"是立刻回来的受理回执,真正完成是之后的 `task_finished`
  事件。所以 run 往往在身体还在干活时就结束了;`task_finished` 作为插话进队,下次 pump 开新 run。
  这正是 pi 的"闲时来消息就开 run"。
- **没有轮数上限、没有循环检测**:保持现状(模型合理地连着派很多任务;失控由主人停止)。

---

## 六、ModelPort 与 ToolPort

```java
interface ModelPort {
    /** 回调在主线程;取消后不再回调。 */
    void call(ModelRequest req, CancelToken cancel, long runId, BiConsumer<Long, ModelOutcome> done);
}
sealed interface ModelOutcome { record Answered(AssistantTurn turn, Usage usage) ... ; record Failed(FailureKind kind, String reason, Usage partial) ... }

interface ToolPort {
    void run(List<ToolInvocation> calls, long runId, LongConsumer settled);
    /** 放弃这批里还没结果的调用;返回它们的 id。stopBody 决定是否叫停身体。 */
    List<String> cancel(boolean stopBody);
}
```

- **真取消**:`NumenLlmClient` 现在没有取消口,中断只能等回应落地再丢。改为传输层接
  `CancelToken`:取消即关闭 SSE 订阅,不再计费、不再回调(pi 的 AbortController)。
- **一份发请求代码**:正常一轮、重试、压缩、目标评估都走 `ModelPort`;回主线程、用量上报
  (`ModelUsed` 事件,§十二)只在门面的实现里写一次。评估器的用量从此也进 TokenLedger。
- **ModelRequest** 由门面组装:历史快照 + 运行期状态 + 系统提示 + 工具表(全份)。
  "可调工具集"(展开闸)用**同一份**发出去的快照计算,不再另算一份。

---

## 七、队列:投递方式写进类型表

`EventTypes.Type` 增加两列,四个 push 点硬写的 `urgent=true`、`isControlEntry` 等按类型字符串的判断全部删除:

```java
record Type(String id,
            Function<String,String> toModel,      // null = 不是给模型的文本
            Function<String,String> chatPreview,
            boolean clearedByInterrupt,
            boolean fromOwner,
            Delivery delivery,                    // STEER | FOLLOW_UP | CONTROL | AMBIENT
            boolean alwaysUrgent)                 // true = 这类恒为急件;false = 发送方定。只有叫醒她的档能恒急
```

| 类型 | delivery | alwaysUrgent | clearedByInterrupt | fromOwner |
|---|---|---|---|---|
| `query` 主人的话 | STEER | true | true | true |
| 世界的事(`task_finished` 等) | STEER | 各行自定 | false | false |
| `goal` 目标续跑 | FOLLOW_UP | true | true | true |
| `compact` 压缩 | CONTROL | false | true | true |
| `clear` 清空 | CONTROL | false | true | true |
| `talk`/`left` 旁听、离场 | AMBIENT | false | false | false |

### 每一档自己声明怎么投递

投递档不是一个让各处去 `switch` 的名字。每一档声明两件事,队列与循环只读这两件:

| 档 | `wakes` 本身是不是叫醒她的理由 | `joins` 随哪一次调模型交出去 |
|---|---|---|
| STEER | 是 | `ANY_CALL`:下一次调模型就带上,不管这次是谁引起的 |
| FOLLOW_UP | 是 | `OWN_CALL`:只随自己引起的那次——别的已经要调模型时它等着,等本来要停的时候 |
| CONTROL | 否 | `NONE`:不是给模型的文本,循环闲时自己执行;它是队列里的墙 |
| AMBIENT | 否 | `ANY_CALL`:随下一次调模型捎带,自己不引起调用 |

由这两件推出全部行为,没有第二处写:

- **急件**:急件的意思是"立刻叫醒她",只有 `wakes` 的档可能急;入队与读盘都过 `EventQueue` 里同一条规则,
  发送方怎么标都一样。不叫醒她的档登记成恒急,登记时就拒绝。
- **闲时熟度**:只数墙之前 `wakes` 的条目——有急件即熟,否则按档位攒够条数或等够时长。
- **回合里的边界**:工具结果等着回应时(这次调用无论如何都会发生),带上墙之前所有 `ANY_CALL` 的条目;
  模型本来要停时,墙之前有 `wakes` 的条目才再调一次,带上 `ANY_CALL` 的,若这次只是 `OWN_CALL` 引起的就连它们一起。
  run 开头同"本来要停":闲时开的 run 是队里的条目引起的;失败重试同"等着回应":它接着答失败那次的输入。
- **主人开口**(解开停止/配置停牌、语音硬停):来自主人、`wakes`、`ANY_CALL` 的条目,`Type.ownerWords()` 一处。
- **控制条目**:见下。
- **外接模型取件**:`takeText` 取走一切不是控制条目的文本,不管排在哪;控制条目留着等内脑。

加一档新投递只改 `Delivery` 一处;`DeliveryDeclarationsTest` 逐档验证队列的行为都能从声明推出来,并钉住
实现代码里没有一处按档名判断。

### 控制条目:开不了 run 的时候就执行

- **墙**:第一条控制条目之前那一段是模型调用能取的全部,它和它后面的等它执行完。排在它前面、此刻能开 run
  的话先走(主人先说一句再按 `/clear`:先答,再清);排在它后面的话进下一次 run。
- **它的"急"不是开 run 的理由**:控制条目不叫醒她。闲时 `step` 先问墙之前能不能开 run,开不了(没熟、停着)
  才轮到它——排在它前面、此刻开不起 run 的条目(旁听、还没攒熟的事、停牌压着的回执)不挡它,也不为它们调模型。
  于是 `OWNER_STOP`/`BLOCKED`/`FAILED` 都挡不住它(按了停止再清空,立即清;停止后叫停身体的回执排在前面也一样)。
  `DEAD`、`EXTERNAL` 时不执行;这两种状态下 `/compact`、`/clear` 直接拒绝并说明原因(`compactProblem`/`clearProblem` 读 `hold()`)。
- **墙前面的条目怎么处置,由控制自己的语义定**(见 §十九)。
- 连着按的几次算一次;批里混着清空就清空说了算。

其余照旧:

- **一次取光**:注入时把该带的条目合成一条 user 消息(保持现状,不照搬 pi 的一次一条)。
  顺序保持现状:世界的事按时间排进 `<events>`,主人的话垫底。
- **离线补发一次送达**:服务端 `Companions.replayOutbox` 把攒的条目**打成一个包**发给客户端,
  客户端一次 push 完再 pump。现在逐条发包,第一条急件就开 run,只带走了已到的几条。

`EventQueue` 仍然只是台账:不认识 run 和停牌,只答熟度、按声明取件。

### 事件的种类就是类型表里的一行

所有输入都走这一个队列,主人的话只是其中一种类型。现在却有**两套类型**:队列的 `EventTypes`
(`query`/`event`/`goal`/`compact`/`clear`),和服务端写死的 `NumenEvents.Kind` 枚举(`task_finished`、
`death`、`hungry`、`owner_hurt`、`timer`、`woke`、`dimension_change`、`body_log`)——后者全挤在 `event`
这一行里,"是哪种事"只写在文本的 XML 属性上。`body_log` 更是个兜底桶:本能自救、防御收场都往里塞。

收成一张表:

- **每种事件是 `EventTypes` 的一行**:`task_finished`、`death`、`hungry`、`owner_hurt`、`timer`、`woke`、
  `dimension_change` 各自登记投递方式、是否恒为急件、打断时清不清、进不进聊天流;急件属性照现在各发送点的
  实际取值登记,由发送方决定的保留发送方决定。条目的 `type` 就是事件种类,拼给模型的
  `<event kind="…">` 里的 kind 取自同一个 id。`NumenEvents.Kind` 删掉,`event` 这个笼统类型也不再需要。
- **`body_log` 退役**:本能做的事登记成 `reflex` 一种,带上是哪个本能(溺水自救、下落放水、防御……)。
  不再有"身体日志"这个桶。
- **一个发出口**:服务端发事件只有一个方法(现在的 `NumenEvents.emit` 收成它),按类型查表,主人在线直送
  客户端、离线进出箱——内置事件和插件事件同一条路。
- **插件同一扇门**:`NumenApi` 提供"登记事件类型"与"发出一条事件"。`enqueue`(现在只在客户端、语义是
  "主人说了一句话")收进同一扇门——发的就是 `query` 类型;在客户端调时仍如实返回 `Delivery`。
  插件不需要任何旁路。第一个用户是 Curios 插件的 `accessory_changed`(戴上、摘下、坏了、死亡掉落)。

### 状态不是事件:身体状态片段

"她这一轮身上是什么样"是**状态**:每轮都要在、不能进历史(压缩之后就丢了),和背包、状态效果同一类。
它不走队列,走运行期状态。现在插件只有 `contributeState`:在**客户端**发请求时现算,只读得到客户端手里的
数据——同伴走远、换了维度,客户端里没有她的实体,读不到;而核心的背包块由服务端推送,没有这个问题。

- **新增服务端身体状态片段**:插件在服务端给一个"身体 → 一段描述"的函数;引擎在 `CompanionStateWatch`
  检测变化时一并算、有变化随状态包推给主人的客户端;这段描述出现在运行期状态里,也出现在
  `get_self_status` 里("你的全部"不再漏掉插件管的部位)。
- **两个来源按事实住在哪里分工**:身体上的事实(饰品栏、模组给的装备位)用服务端片段;只有主人客户端知道
  的事(东方小女仆的外观是客户端渲染的)仍用 `contributeState`。一个事实只有一个来源。

---

## 八、中断、死亡、登出、外接接管:一个 `halt(reason)`

四条路径现在各写一份清理、互有遗漏(死亡漏了清气泡和流式行,登出误用了"主人停止"的全套)。
收成一个入口,差别只在表里:

```java
void halt(HaltReason reason) {
    if (run != null) {
        run.cancel.cancel();
        List<String> orphans = tools.cancel(reason.stopsBody());
        if (run.phase == TOOLS || run.phase == MODEL) transcript.addHalt(reason);   // §九
        Run cut = run; run = null;
        emit(RunEnded(cut, HALTED(reason)));
    }
    inbox.clear(reason.clearsQueue());      // 只清类型表 clearedByInterrupt 的
    if (reason.endsGoal()) goals.clear(reason);
    stopped = reason.hold(stopped);
    emit(HoldChanged(hold()));
}
```

| reason | 触发 | 叫停身体 | 清被取代的指令 | 长期目标 | 之后的停牌 |
|---|---|---|---|---|---|
| `OWNER_STOP` | 停止键 | 是 | 是(忙时闲时都清,含排着的 `goal`) | 收工 | `OWNER_STOP` |
| `DEATH` | 死亡包 | 否(已死) | 否 | 保留 | `DEAD` |
| `DISCONNECT` | 登出、断线 | 否(身体还在服务器跑) | 否 | **保留** | 不变 |
| `EXTERNAL` | tick 发现驾驶翻转为外接 | 否 | 否 | 保留 | (现算 `EXTERNAL`) |
| `DISPOSE` | 遣散、`RESET_LOOPS` | 否 | —(队列随同伴删除) | — | — |

- **登出不再删目标、不再置停牌**:现在 `quiesce` 复用 `abort`,会删掉目标文件,并在后台任务
  进行中时置 `OWNER_STOP`,离线补发的 `task_finished` 唤不醒她。
- **接管时作废在飞的回合**:门面每 tick 比对驾驶状态,翻转为外接即 `halt(EXTERNAL)`。
  现在接管后在飞的回复照样落地派工具、在飞的压缩照样替换历史。
- **交还时不自动续跑**:halt 已记录中断标记,链条算结束;外接期间攒的事件由 pump 按熟度处理。
- **`RESET_LOOPS` 先 halt 再清表**:现在直接清表,旧循环的在飞回合继续写同一个会话文件。
- **只取消这个循环的调用**:`ToolPort.cancel` 按调用 id 取消,`ServerToolTransport.forget(entity)`
  按同伴整批清空的做法删掉——现在内脑的打断会把外接模型挂着的调用一起丢掉。
- **复活**:清 `dead`,推一条死亡事件(急件)进队,pump。补悬空结果交给 §九,不再在这里写。

---

## 九、协议配对:只在 ProtocolView 一处

`ConvoState.Msg` 增加一种记录:

```java
record Halt(String reason) implements Msg {}   // 这一轮在这里被切断:原因给模型看
```

历史**如实记录**发生了什么:模型的回复、工具结果、某一轮在哪里被切断。发请求前,
`ProtocolView.forWire(List<Msg>)`(ai 模块,`chatStreaming` 转服务商格式前调用)统一变成合法序列:

1. **悬空工具调用补结果**:assistant 的 tool_calls 在下一条 assistant/user/Halt/结尾之前没有结果的,
   补 `{"success":false,"message":"<原因>"}`。原因取紧随其后的 Halt;没有 Halt(游戏被直接关掉)
   用"游戏关闭前没有返回结果"。
2. **Halt 本身不发**;若它切断的是一次模型回复(没有悬空调用),在下一条 user 消息开头加一行
   "(上一轮被打断:原因)"。
3. **相邻 user 合并**:失败后又来一句话、运行期状态块、切断说明,都可能造出连续 user,统一合并。

随之删除:
- 循环里补结果与封口的四处(打断、复活、读盘恢复、失败收尾),以及 `ConvoLog.unansweredToolCallIds`;
- `AgentRequestContext` "tool_call 悬空时不挂状态"的特判(配对由 ProtocolView 保证,运行期状态恒作为
  一条 user 追加,由第 3 条合并);
- `AgentRequestContext.withoutLegacyCurrentTask` 请求期剥旧块的兼容代码,改为 `ConvoLog.migrateIfNeeded`
  一次性迁移。

`ConvoLog` 编码 `Halt` 为事件记录;`load` 回放它,`loadDisplay` 把它显示成一条中断分隔。
面板里不再出现"(已中断)"这类补丁消息。

---

## 十、失败与重试

照 pi:失败不改历史,run 以 `ERROR` 结束,外层决定。

```java
void afterRun(Run r, RunEnd end) {
    if (end instanceof Error e) {
        if (e.retryable() && !r.retried) { startRun(retriedFrom = r); return; }   // 同一条 startRun,过同样的闸
        emit(TurnFailed(e.words()));          // 聊天行 + 提示条,由 presenter 画
        stopped = FAILED;
        if (inbox.hasUrgent()) stopped = NONE; // 已经排着急件/主人的话:它们就是解开的理由
        return;
    }
    if (end == DONE) goals.onRunDone(r);      // §十一
}
```

- **可重试**:流式中途断开、看门狗超时(传输层自己的退避不算在内)。一条链只重试一次。
- **重试走同一个 `startRun`**,不再复制发请求的代码,也就自然过了停牌、端点、压缩检查。
- 失败回应不写进历史;之后来的话与已有的 user 相邻,由 ProtocolView 合并。
- 回应落地时连接已断:按 `halt(DISCONNECT)` 处理,不单独写分支。

---

## 十一、压缩与长期目标

### 压缩
- **自动**:`turn()` 里、**注入插话之前**检查。现在先注入主人的话再判断压缩,切分退化分支会把
  刚说的话总结掉。压缩是 run 的一个阶段(`phase = COMPACT`),完成后回到同一个 `turn()`。
- **手动 `/compact`**:CONTROL 条目,闲时由 pump 执行,不开 run;执行期间 `phase = COMPACT`,
  挡住 pump。
- 压缩提示、摘要提取、切分收进 `Compactor`;切分仍用 `CompactSplit` 这把唯一的尺。
- 连续失败的熔断保留(自动路径三次失败后不再自动压缩,手动不受限)。

### 长期目标
- `GoalSteward` 订阅 `RunEnded(DONE)`:有目标、队列里没有会叫醒她的条目、身体没有进行中的非常驻任务时,
  发起一次评估(`ModelPort`,不是 run,不带历史与工具)。
- 评估回来:达成/打转/额度用尽 → 收工;否则推一条 `goal` 条目(FOLLOW_UP)→ pump。
- 评估在飞期间开了新 run、或目标被换掉,结果作废。
- 目标起点不再靠在历史里找 `"<goal>"` 子串:设定目标时记下当时的历史位置;收到压缩或清空的
  `TranscriptBoundary` 时起点归零(新历史开头的摘要已经涵盖了之前的一切)。

---

## 十二、事件:循环不碰 UI

```java
sealed interface LoopEvent {
    record RunStarted(long runId, boolean ownerSpoke)                  implements LoopEvent {}
    record ModelDelta(long runId, JsonObject chunk)                    implements LoopEvent {}
    record AssistantMessage(long runId, AssistantTurn turn)            implements LoopEvent {}
    record ToolStarted(long runId, ToolInvocation call)                implements LoopEvent {}
    record ToolFinished(long runId, ToolInvocation call, String json)  implements LoopEvent {}
    record RunEnded(long runId, RunEnd reason)                         implements LoopEvent {}
    record TurnFailed(String words)                                    implements LoopEvent {}
    record HoldChanged(Hold hold)                                      implements LoopEvent {}
    record ModelUsed(Usage usage, Purpose purpose)                     implements LoopEvent {}  // TURN | COMPACT | GOAL
    record TranscriptBoundary(Boundary kind)                           implements LoopEvent {}  // COMPACT | CLEAR | PERSONA | HALT
}
```

订阅方:
- **TurnPresenter**:打字机、思考流、语音(`RunStarted.ownerSpoke` 决定硬停/句界衔接)、说话状态包、
  头顶气泡、聊天行、提示条、"想了想什么也没说"。`SpeechBubbles`/`ChatLines`/`NumenHudToasts`/`TalkHint`
  的调用全部从循环搬到这里。
- **TokenLedger**:`ModelUsed`,三种用途都记。
- **显示记录**:`TranscriptBoundary` 驱动分隔线;现在"写日志事件 + 手动加分隔"的三处双写删除,
  显示记录与 `ConvoLog.loadDisplay` 共用一份映射。
- **McpTranscript**:外接模型的现场记录。
- **WorkBlockMemory**:`ToolFinished` 里收工作站坐标(现在的 `harvestWorkBlocks`)。

---

## 十三、同伴门面与组件

`EntityAgentLoop` 退成门面,只剩同伴侧的事:

| 组件 | 职责 | 来源 |
|---|---|---|
| `EntityAgentLoop` | 构造内核并接端口;人设/模型绑定;外接模型取件与 `externalSay`;注册表生命周期 | 现类 |
| `SystemPromptComposer` | 人设 + 操作核心 + 技能表 + 本能名册 | `composeSystemPrompt` |
| `RuntimeState` | `<runtime_state>`:当前任务、背包、效果、骑乘、插件片段 | `runtimeStateXml` 等 |
| `Compactor` | 压缩提示、切分、摘要提取、熔断 | 压缩相关方法 |
| `GoalSteward` | 目标设定、评估、续跑、收工 | 目标相关方法 |
| `TurnPresenter` | 订阅事件的全部表现层 | 现类 + 循环里散落的 UI 调用 |

其他收口:
- **主人的话一个入口**:面板也走 `NumenGateway`(现在面板直调循环并自己先查端点);端点检查只在
  `startRun` 一处,UI 读 `LoopStatus.hold == BLOCKED` 与事件给出的原因。
- **端点口径一致**:未绑定档案就是不可用;删除 `client()`/`modelWindow()` 回落全局配置的分支
  (`endpointProblem` 已判为不可用,那条回落走不到)。
- **外接判断一个入口**:`McpMode.driving()` 的直调(面板、聊天视图、循环)统一经 `LoopStatus.hold`。
- **收窄 public**:`convo()`、`entityUuid()` 无外部调用;`clientTick()`、`quiesce()` 只给注册表。
  死代码删除:`AgentLoopRegistry.activeEntityUuids`、`TurnPresenter` 两参 `tapForUi`、
  `ConvoState.clear()`、`ConvoLog.delete()`、`handleResponse` 里的空 else。

---

## 十四、收口的旧债(对照普查)

### 已经会出错的

| # | 现在 | 新设计里 |
|---|---|---|
| 1 | 登出复用"主人停止":删目标文件;后台任务中置停牌,离线补发唤不醒;空闲时清掉死亡期间主人的话和排着的命令 | `halt(DISCONNECT)`:不删目标、不置停牌、不清队列(§八) |
| 2 | 外接驾驶时 `/compact` 入队,外接取件停在队首,后面的话永远取不到 | 驾驶时控制命令直接拒绝;外接取件跳过控制条目(§七) |
| 3 | 接管不作废在飞回合:回复照样派工具,压缩照样换历史 | `halt(EXTERNAL)`(§八) |
| 4 | 内脑打断按同伴清传输表,连带丢掉外接模型的调用 | 按调用 id 取消(§八) |
| 5 | 失败回合队列为空时不封口,之后出现连续 user;断线落地同样 | ProtocolView 合并相邻 user(§九) |
| 6 | `RESET_LOOPS` 直接清表,旧循环继续写会话文件 | 先 `halt(DISPOSE)`(§八) |
| 7 | 忙时打断不清排着的 `goal` 块,跟下一句话一起倒给模型 | `OWNER_STOP` 忙闲都按类型表清(§八) |
| 8 | 自动压缩判断在注入主人的话之后,退化分支把刚说的话总结掉 | 注入之前压缩(§十一) |
| 9 | 停牌时每 tick 刷"主动开轮" | `pump()` 只在真正开 run 时打日志(§四) |
| 10 | 停止后清空要等下一句话 | 控制条目不受 `OWNER_STOP` 影响(§七) |
| 11 | 离线补发逐条送达,第一条急件就开轮,只带走部分 | 一个包送达(§七) |

### 多源与重复

| 现在 | 新设计里 |
|---|---|
| 协议修补 4 处、3 种文案 | ProtocolView 一处(§九) |
| 发请求 4 份,重跑是复制且没过闸 | `ModelPort` 一份;重试走 `startRun`(§六、§十) |
| "忙不忙"7 处以上 | `LoopStatus`(§三) |
| "能不能开轮"散在 12 个入口 | `pump()`(§四) |
| 停牌 `turnPause` 6 处写入 + `dead` + 外接直调 | `Hold` 一张表(§三) |
| 死亡/复活/登出/打断四份清理 | `halt(reason)`(§八) |
| 类型表外硬写 `urgent=true`、按类型字符串判断 | 类型表 `delivery`/`alwaysUrgent`(§七) |
| 主人的话两条入口、端点检查三处、外接判断两套入口 | 一个入口、一处检查、`LoopStatus.hold`(§十三) |
| 显示记录三处双写 | `TranscriptBoundary` + 共用映射(§十二) |
| 评估器用量不进账 | `ModelUsed` 三种用途都记(§十二) |
| 展开闸用重算的快照 | 用发出去的同一份(§六) |
| 请求期剥旧 `current_task` 块 | 迁移一次(§九) |
| UI 调用散在循环 20 余处 | 订阅事件(§十二) |
| 事件种类两套:队列 `EventTypes` 与服务端 `NumenEvents.Kind` | 种类就是类型表的一行(§七) |
| `body_log` 兜底桶收本能叙事 | `reflex` 类型带本能名(§七) |
| 插件报身体上的事没有正门,只有冒充主人的 `enqueue` | 插件登记类型、发出事件,与内置同一条路(§七) |
| 插件状态只能在客户端现算,远处/跨维度的同伴读不到 | 服务端身体状态片段随状态包推送,也进 `get_self_status`(§七) |

---

## 十五、文档同步

`docs/architecture-mind-model.md` 第二至四节与实现早已对不上,随这次重写:

- §二"只在三种时刻运转":改为 pump 的触发条件(入队、run 结束、停牌解开、时长熟度)。
- §三输入全景:补上请求期运行期状态、目标续跑、技能表/本能名册(已实现);
  删去不存在的 `emitEvent`;"核心永远传 false"改为类型表 `alwaysUrgent` + 发送方决定。
- §四"三态路由":改为 §七 的三种投递方式与熟度规则;删去"队列锁"、`BodyLog` 类名等已经不存在的概念;
  死亡事件恒为急件;事件种类即类型表的行,本能叙事是 `reflex` 类型。
- 顺带清掉普查列出的陈旧与错位注释("队列锁"残留、`WorkBlockMemory` 头注释、`EventTypes` 引用
  不存在的 `EventQueue#drain` 等)。

---

## 十六、测试

**内核单元测试**(`agent/src/test`,假的 ModelPort/ToolPort/Transcript,同步执行器):
- 闲时急件开 run;非急件攒够条数/时长才开;每 tick pump 不产生任何输出。
- 停牌(停止/失败/配置)时来急件不开 run、不打日志;主人说话解开。
- 停止后清空立即执行;死亡与外接驾驶时控制命令被拒。
- run 中主人说话:不打断工具,这批结算后在下次调模型前注入;连说三句合成一条。
- 目标续跑只在 run 要结束时接上;打断清掉排着的续跑。
- 流式中停止:回调不再进入;历史记 Halt;下一次请求经 ProtocolView 合法。
- 工具进行中停止:未结算的调用拿到带原因的结果;身体收到叫停;外接模型的调用不受影响。
- 失败:可重试的重试一次且同样过闸;第二次失败进 `FAILED`;排着急件时不停牌。
- 死亡冻结、复活推事件;登出不删目标不置停牌;接管作废在飞回合。
- 自动压缩发生在注入之前,主人刚说的话逐字保留。

**ProtocolView 测试**(`ai/src/test`):悬空调用补结果(有/无 Halt)、Halt 切断回复的说明、
相邻 user 合并、正常序列原样通过、压缩请求与评估请求同样合法。

**类型表测试**(`agent/src/test`,现有 `EventQueueTest`/`JsonlJournalTest`/`GoalStateTest` 随类搬过去):五种内置类型的投递方式与急件属性;外接取件跳过控制条目。

**GameTest** 照跑(服务端不受影响;离线补发打包的服务端改动加一条)。

**真机清单**(表现层无法自动化):流式打字机与思考流、语音硬停与衔接、头顶气泡、停止键、
死亡复活、登出重登后后台任务完成的通知、外接模型接管与交还、`/compact` 进度条、`/goal` 续跑。

---

## 十七、分步落地

每一步单独提交、单独跑通 `:ai:test :agent:test :api:common:test :core:common:test :ui:test`(第 0 步之前
没有 `:agent:test`)、两个加载器构建、GameTest;客户端行为变化的步骤部署后真机过一遍。
`CONTRIBUTING.md` 的测试命令同步补上 `:ai:test :agent:test`。

0. **建 `agent` 模块并搬家**:接入构建;`EventQueue`/`EventTypes`/`JsonlJournal`/`GoalState`/`GoalPrompts`
   与它们的测试搬进去,改包名,调用方跟着改 import。只搬不改行为。
1. **ProtocolView + `Msg.Halt` + ConvoLog 编解码**(ai):删掉四处修补、`unansweredToolCallIds`、
   `AgentRequestContext` 的两处特判(旧块迁移进 `migrateIfNeeded`)。旧循环在打断、死亡、失败处改为写
   `Halt`,其余照旧。
2. **类型表加 `delivery`/`alwaysUrgent`**,删掉硬写急件与按类型字符串的判断;外接取件跳过控制条目;
   离线补发打包。
3. **循环内核**:`AgentLoop`、`Run`、`Hold`、`pump`、`halt`、两层步进、重试;`ModelPort`(含传输层取消)
   与 `ToolPort`(按 id 取消);`EntityAgentLoop` 改为委托内核,删除 `tryStartTurn`/`maybeDrain`/
   `handleResponse`/`abort`/`onEntityDied` 中的旧逻辑。内核单元测试在这一步落地。
4. **事件种类统一与插件的门**(§七):种类登记进类型表,删 `NumenEvents.Kind`,`body_log` 退役为 `reflex`;
   服务端一个发出口;`NumenApi` 加登记类型、发出事件,`enqueue` 收进去;服务端身体状态片段(随状态包推送、
   进运行期状态与 `get_self_status`)。
5. **事件与 LoopStatus**:表现层、记账、显示记录、MCP 记录改为订阅;UI 的"忙不忙"改读 `LoopStatus`;
   主人的话统一走 `NumenGateway`;端点口径统一。
6. **拆组件**:`SystemPromptComposer`、`RuntimeState`、`Compactor`、`GoalSteward`;收窄 public、删死代码。
7. **文档与注释**:§十五。

第 4 步之后,Curios 联动插件(另有设计稿)直接用第 4 步的事件门与身体状态片段落地,不开旁路。

第 0–7 步都已落地;落地时与本稿的出入记在 §十八,之后一次事故带来的收件箱推进改动记在 §十九。

---

## 十八、落地时与本稿的出入

- **事件**:`RunStarted` 之外另有 `TurnStarted(ownerSpoke)`(每次调模型一条,语音据此选硬停还是衔接)和
  `Halted`(闲时按停止也要让语音闭嘴);`ModelDelta` 带的是已按服务商方言解开的文本。
- **用量只有一条路**:`Purpose` 有 `TURN`/`COMPACT`/`GOAL` 三种。目标评估经 `AgentLoop.consult` 发出——
  不是 run、不占内核、不看停牌,但用量照样发 `ModelUsed`。台账、整理、目标各自订阅。
- **显示记录不靠 `TranscriptBoundary`**:`ConvoLog.onDisplay` 把日志写下的每一条经读盘同一个换法
  (`displayOf`)交给显示记录,分隔(整理、清空、换人设)与切断点都从日志来,不需要 `PERSONA` 边界。
  思考文本随日志落盘——面板重启后照样画得出思考块,Anthropic 回传思考块也要它原文。
- **停牌带原因**:`LoopStatus.holdReason` 是进入 `BLOCKED`/`FAILED` 时那句话,界面从快照读,不另存。
- **端点只在内核判**:开 run、闲时执行整理记忆、旁路调用三处问 `ModelPort.unavailable()`。整理记忆遇到
  端点不可用不丢条目,留在队首进 `BLOCKED`,改好绑定自己接着走。面板、技能命令、`/compact` 不再各自查端点。
- **"谁在驾驶"与"为什么不动"分开**:`LoopStatus.hold` 里 `DEAD` 压过 `EXTERNAL`,回答的是内脑为什么不开 run;
  界面要问"驾驶席在谁手里"(画外接现场、报 `TO_EXTERNAL_BRAIN`)就直接问 `McpMode.driving()` 这一处真源,
  门面不再转述。
- **目标的评估窗口按消息认起点**:往回扫到设定目标的那条(`GoalPrompts.isDirective`),不记"设定时的历史位置"——
  目标跨重进游戏活着,重进后历史按条数上限读回,位置早就对不上了。
- **组件**:`Compactor` 在 `agent.memory`,`GoalSteward` 在 `agent.goal`(都有单测);`SystemPromptComposer`、
  `RuntimeState`(含当前任务的镜像)在 api 客户端。工作站坐标与任务镜像也各自订阅内核,门面自己不订阅任何事件。
- **模型端口的失败只有一个出口**:请求还没组装出来就出的错(服务商配置对不上、历史转不成线格式)同样作为
  失败交回,不会同步抛出去让内核永远等在 `MODEL`。

---

## 十九、收件箱推进:一次栈溢出之后(09-26)

**现场。** 主人按 `/clear` 后客户端 `StackOverflowError`:`pump → startRun → turn → end → pump → …` 同步递归,
"主动开轮:有急件(攒了 24 条…)"一毫秒一条刷了两千多遍。她的 `inbox.jsonl`:23 条 `talk`(别的同伴在群里说的话,
最老躺了约 4.5 天)+ 末尾 1 条 `clear`。`pump` 只看队首是不是控制条目,队首是 `talk`,不执行清空;熟度见到急件
(`clear` 当时恒为急件)要开 run;run 取件只取 STEER、run 开头再加 FOLLOW_UP,碰到控制条目就停,一条没取到;
没有待答就 `end(DONE)`,而 `end` 同步调 `pump`——同样的判断再来一遍。

**三处根因,各自的结构:**

| 为什么会发生 | 同类问题怎么不再出现 |
|---|---|
| 捎带档加进类型表时,文档写着"回合进行中与 STEER 一样在边界注入",循环里取件的手写谓词却没跟着改——捎带条目只进不出。群聊里同伴从那天起一直听不见彼此,没有任何报错。队列测试验"捎带跟着走"用的是转发用的 `takeEntries`,没走循环真正的取件口 | 投递档自己声明 `wakes`/`joins`(§七),队列与循环只读声明,加一档只改 `Delivery` 一处。`DeliveryDeclarationsTest` 逐档验证队列行为可从声明推出,并钉住实现代码里不出现档名;循环层测试直接走 `AgentLoop` |
| "要不要开一轮"(熟度,数全队列的急件)与"这一轮能取到什么"(取件,碰控制就停)是两个各自为政的判断,不一致时开出空轮 | 闲时只有 `step` 一个判断(§四);熟度与取件看同一段(墙之前)、同一份声明,熟了就一定取得到。控制条目不叫醒她,它的"急"不再是开 run 的理由:开不了 run 时它自己执行 |
| `end → pump` 同步递归,一次空转就是无限递归 | `pump` 一圈一圈往下走,嵌套的推进请求只记"再看一眼";即使将来又出现别的不一致,也只会停下 |

**墙前面的旁听:清空时一并清掉,整理时留着。** 斜杠命令只在私聊里用,但她只有一条上下文(`group-chat.md` §七):
私聊里的、群里的、旁听到的都在同一条流上。躺在队里的旁听,是她已经听见、还没交给模型的那部分上下文——

- `/clear` 的意思是"之前的都忘掉"。主人在私聊里按下清空,她之前在群里听见的那些话属于被清掉的那段;清完再交给她,
  新上下文一开头就是主人刚让她忘掉的旧闲聊。所以一并清掉。
- 要她回应的条目(还没攒熟的世界的事、停牌压着的回执或主人的话)不丢:它们要一个回应,也是她该知道的事实,清完进新的上下文。
- `/compact` 只把历史换成摘要;排着的条目还没进历史,不属于被整理的那段,照旧留着,跟下一次调模型走。

**读回那份现场。** 读盘与入队过同一条急件规则,`clear` 读回来就不急。第一次推进:墙之前只有旁听,开不了 run;
执行清空,连带清掉 23 条旁听;不调模型,不开空轮,之后每 tick 什么也不做。

**顺带收口的同类判断:**

- 目标评估"队里还排着东西就先不判"改为"还排着会叫醒她的条目才不判":旁听开不起 run,控制条目执行完也不开 run,
  等它们就是让目标停在那儿。
- 外接取件跳过控制条目、主人开口、急件规则,都改读声明。
- 一次 run 调不调模型由边界自己知道的原因定(工具结果等着回应、失败重试接着答、否则看队里有没有要她回应的),
  不再从历史末尾去猜——整理留下的摘要、被切断的那句话停在末尾,看上去"待回应",却不是这次调用的原因。
- 开 run 的日志用熟度本身算出的条数与理由,不再另拿全队列去数。

---

## 附:已定的取舍

- **插话一次取光**,合成一条 user(pi 默认一次一条)。
- **按停止之后**:清空/压缩立即执行;世界事件照收但不开 run,等主人下一句话。
- **工具串行**(pi 默认并行):身体只有一个动作槽。
- **不自动续跑**:游戏重开、外接交还后,链条以 Halt 结束,不替主人再开口;新输入到来才开 run。
