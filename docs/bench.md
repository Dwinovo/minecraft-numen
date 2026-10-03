# 真模型评测(bench)

在无头 GameTest 服务器里用代码搭场景,让**产品里真实的大脑循环与提示词**接**真实的模型**去完成任务,量成功率、
pass^k、轮数、token、每次成功的成本与失败类型。每次改命令、提示词、回执之后跑一遍,和上一份结果对比。

它是独立的模块,不进发行 jar、不改产品行为:插件能给自己的联动加场景,别人也能换一个模型来比。

---

## 一、怎么跑

```bash
# 只跑两种基线(标准解、空操作),不花 API:验证场景与断言
./gradlew --no-daemon :core:neoforge:runBench -Dbench.scenarios=all -Dbench.repeats=0

# 真实模型,每个场景 3 次(key 只从环境变量读)
NUMEN_BENCH_API_KEY=sk-... ./gradlew --no-daemon :core:neoforge:runBench -Dbench.scenarios=all -Dbench.repeats=3

# 车万女仆的场景:挂着车万女仆单开一次(原版那次不挂)
NUMEN_BENCH_API_KEY=sk-... ./gradlew --no-daemon :plugins:tlm:runBench -Dbench.scenarios=tlm -Dbench.repeats=3

# 对比两份结果(路径相对仓库根,报告打到标准输出)
./gradlew --no-daemon -q :bench:compare -Pbefore=core/neoforge/runs/bench/results/<时间戳> -Pafter=core/neoforge/runs/bench/results/<时间戳>
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `bench.scenarios` | 空 = 什么都不跑 | 逗号隔开:`all`、组名(`vanilla`、`tlm`)、场景名(`mine_iron`)或 `组名/场景名` |
| `bench.repeats` | 3 | 真实模型每个场景跑几次;0 = 只跑基线 |
| `bench.provider` | `deepseek` | 服务商,同产品的服务商表 |
| `bench.model` | `deepseek-v4-flash` | 模型 |
| `bench.baseUrl` | `https://api.deepseek.com/beta` | 端点 |
| `bench.reasoning` | 空(= 产品的 auto,不发) | 思考档位,同产品 |
| 环境变量 `NUMEN_BENCH_API_KEY` | — | API key。**只从环境变量读**,不进任何属性、文件、日志、报告 |

参数用 `-D` 或 `-P` 给 Gradle 都行,构建脚本转成游戏进程的系统属性;单价表 `bench/pricing.json` 与提交号由构建脚本
自动带上。没选中任何场景时一条用例都不生成;平时的 `runGameTestServer` 不加载评测。

温度等生成参数用产品的设置(服务商表里给这个模型配的),不为降方差另改。

---

## 二、它是怎么接起来的

```
GameTest 服务器(runs/bench)
├─ 模组 numen            产品本体,原样
└─ 模组 numen_bench      评测:只在 runBench 里加载
     ├─ :bench           纯 JVM:记录、统计、报告、对比
     ├─ :bench:game      场景接口、运行器、评测大脑、模拟主人
     └─ 场景源码集        core/neoforge/src/bench(原版)、plugins/<联动>/src/bench
```

- **大脑**:循环内核 `AgentLoop` 原样,四个端口在服务端进程里接上(`Brain`)。请求由产品的
  `AgentRequestContext.turn` 组装——系统提示(`SystemPromptComposer`)、运行期状态(`RuntimeState`)、工具表
  只有那一份;札记索引是 `MemoryPreamble`,整理记忆是 `Compactor`,派工具的顺序与等待是 `SerialCalls`。
  和主人客户端不同的只有:人设用内置默认人设,主动性用默认档位,插件在客户端现算的状态片段没有(没有客户端)。
- **上行**:工具照产品的路走到 `ServerToolTransport`,它的上行出口 `uplink` 在评测里直接交给服务端真实入口
  `ExecuteActionPayload.handle`,发送者是模拟主人。
- **模拟主人**:一个在线的 `ServerPlayer`,连接是 `OwnerConnection`。发给主人的模组载荷截下来,按网络的样子
  编解码一遍,照主人客户端的做法交给大脑:回执给传输层,当前任务与身体状态给运行期状态,世界事件进收件箱,
  征询按剧本经 `ConsentDesk.reply` 答复,死亡切断循环。
  下行包过得了 NeoForge 的频道检查,是因为连接用 NeoForge 给 GameTest 的 `NetworkRegistry.configureMockConnection`
  写上了协商好的频道表(同伴的 `FakeConnection` 不需要:numen 的 mixin 在检查之前就把发给它的包丢了)。
- **技能**:主人客户端起来时把自带技能接进技能表;评测没有客户端,`numen_bench` 构造时经同一扇门
  (`NumenPlugins.bindSkills`)接上,玩家自己的技能目录不扫。
- **每次运行从白纸开始**:新的场地(彼此隔 512 格,任何扫描都看不见上一块)、新的她(新 UUID)、新的主人、
  临时目录里的札记与会话日志(收场后整个删掉,评测从不读它们)、清空的图纸库(`schematics/`,设计也在里面:全服共用、
  跨次保留,不清的话上一次画的设计会占着名字出现在下一次里)。

---

## 三、一次评测怎么跑

一组场景是一条 GameTest 用例,场景一次一次地跑、不并行:

1. 每个场景先跑**标准解**(把场景写好的一段 Lua 程序当作一次 `lua` 调用交出去,必须过)和**空操作**(她只答一句,必须挂)。两种基线
   不花 API,证明场景可解、断言不被什么都不做骗过。对不上的场景不跑真实模型,记为自检失败。
2. 基线可信的场景跑真实模型 `bench.repeats` 次。
3. 用例本身的成败只说评测靠不靠得住:自检全对、没有评测出错、没被余额不足打断就通过。模型成不成功只进报告。

一次运行:搭场地 → 主人上线 → 召出她 → 搭场景 → 等服务端把她的身体状态推过来(第一次请求里就有背包)→
主人开口 → 每刻推循环、看收不收场。

评测按真实游戏**每秒 20 刻**走:GameTest 服务器本来不等下一刻、有多快跑多快(每秒上千刻),那样她等模型回话的
几秒里世界已经过了好几分钟,活干多久、事件什么时候到、游戏刻预算都不对。评测每刻补足 50 毫秒。

收场,按先后看:她死了;调模型的次数超预算;调模型失败且不再重试(或端点不可用);游戏刻或墙钟超预算;她闲下来
保持 3 秒——没在跑的对话、没停牌、身体没有后台活、队里没有会叫醒她的条目。然后对终态判断言、记一行、清场。

API 返回 402(余额不足)时不再调模型,余下的真实模型运行全部取消,用例失败并说明。

---

## 四、加一个场景

场景实现 `Scenario`,组用 `Bench.suite` 登记,写法同登记命令组:

```java
@GameTestHolder(Bench.NAMESPACE)
public final class TlmBench {
    @GameTestGenerator
    public static Collection<TestFunction> scenarios() {
        return Bench.suite("tlm", "Touhou Little Maid: taming and keeping maids.",
                suite -> suite.add(TameWildMaid::new));
    }
}
```

`Scenario` 要写的:

| 方法 | 说明 |
|---|---|
| `id()` | 场景名,`bench.scenarios` 按它选 |
| `setup(Scene)` | 搭场景:她与主人已在场地里。放方块、给物品、生成实体 |
| `opening()` | 主人开场说的话 |
| `checks()` | `Check.success`(成功断言)、`Check.guard`(负面断言)、`Check.subgoal`(子目标);写法同 GameTest,不成立就 `scene.assertTrue(false, "看到了什么")`。"她没死"每个场景都有 |
| `solution(Scene)` | 标准解:一段 Lua 程序,和她调 `lua` 工具写的一样 |
| `arena()`、`budget()`、`start()`、`ownerAt()`、`owner()` | 可选:场地大小、预算(默认 30 轮、10 分钟)、她和主人站哪、模拟主人的剧本(默认允许一次、不回话) |

每次运行造一个新实例(`suite.add` 收构造器),这一次生成的东西(一只女仆)放在场景自己的字段里。坐标相对场地:
地板在 y=0,站在地板上是 y=1。

**插件的场景**放在插件自己的 `src/bench` 源码集里,由插件自己的 `runBench` 挂上目标模组跑(照 `plugins/tlm/build.gradle`):
场景源码集编译对着 `:bench:game`,运行时和 `:bench`、`:bench:game` 一起组成模组 `numen_bench`。

---

## 五、指标

| 指标 | 定义 |
|---|---|
| 成功 | 成功断言全过、负面断言全过 |
| c/n、成功率 | n 次里成功 c 次;区间是 Wilson 95% |
| pass^k | τ-bench 的定义:k 次全成功的概率的无偏估计 `C(c,k)/C(n,k)`。汇总表给 pass^3(n≥3) |
| 子目标 | 达成的比例,不决定成败 |
| 轮数 | 调模型的次数(一次 run 里的对话调用;整理记忆不算) |
| 命令数、命令出错 | 工具调用数(一段程序算一次);其中结果 `success:false` 的。每个失败的程序在记录里记下回执错误值的种类(`error_kind`,如 `bad_argument`、`out_of_reach`、`no_path`)并按种类归一类(`error_class`):`syntax` 读不成、`api_args` 一次 API 调用写错了(`bad_argument`、`no_function`)、`api_failed` 一次 API 调用(或库函数 `raise` 的)做了但失败了、`runtime` 程序自己的运行错、`stopped` 被停在调用之间或到了上限 |
| 重复失败 | 和之前某个失败的调用一字不差、又失败了的次数 |
| 征询 | 身体向主人征询的次数 |
| token | 未命中(含缓存写)/ 命中 / 输出,DeepSeek 的 `prompt_cache_miss_tokens` / `prompt_cache_hit_tokens` / `completion_tokens` |
| 成本 | 按 `bench/pricing.json` 的单价折算;表里没有的模型不折。DeepSeek 记的是闲时价,峰时三项都翻倍 |
| 每次成功成本 | 这些次的总花费 ÷ 成功次数 |
| 游戏刻、墙钟 | 一次运行从开始到收场 |
| 说完成没过 | 她自己收了工,断言却没过 |

两份结果的对比(`:bench:compare`)只看真实模型:按场景配对算成功率差值,场景层 bootstrap(一万轮、固定种子)
给均值的 95% 区间;一个场景"过"指过半数次数成功,列出由过变挂、由挂变过。

---

## 六、报告

写到 `<游戏目录>/results/<时间戳>/`(原版是 `core/neoforge/runs/bench/results/`,车万女仆那次是
`plugins/tlm/runs/bench/results/`):

- `runs.jsonl`:一次一行。字段:`suite`、`scenario`、`variant`(solution / noop / live)、`attempt`、`commit`、
  `promptHash`(系统提示 SHA-256 前 12 位)、`model`、`passed`、`checks` 与 `subgoals`(每条的名字、种类、过没过、
  说明)、`end`(结束原因)、`turns`、`toolCalls`、`toolErrors`、`repeatedFailures`、`consents`、`tokensMiss`、
  `tokensHit`、`tokensOut`、`cost`、`currency`、`wallMs`、`gameTicks`、`claimedDone`、`tag`(失败分类)、
  `finalWords`(她最后说的话)、`transcript`(记录文件)、`error`。
- `summary.md`:自检表、每个场景一行的汇总、失败分布与每次失败的去处。
- `transcripts/<组>-<场景>-<变体>-<第几次>.jsonl`:一行一件事——主人的话、她的话、每个工具调用(她写的整段程序)与它的整张回执
  (失败的带 `error_class` 与 `error_kind`,都带这段程序做了几次 API 调用 `calls`)、
  进收件箱的世界事件、征询与答复、收场。

**不落任何思考流**:评测不订阅流式增量,记录与报告里没有模型的思考;会话日志只在运行期间落在临时目录里供整理记忆用,
收场即删,评测不读。

结束原因:自己收工、权限被拒(自己收工,而最后一个失败的结果是主人拒绝或规则不许)、超轮数、超游戏刻、超墙钟、
API 错、超上下文、死亡、评测出错。

---

## 七、失败分类

| 标签 | 含义 | 自动打 |
|---|---|---|
| A | 理解:没听懂要什么 | |
| B | 感知:没看见、看错了世界 | |
| C | 规划:步骤、顺序不对 | |
| D | 执行参数:调用写错、参数不合 | 有一段程序停在一次 API 调用写错上(没有这个函数、参数读不成) |
| E | 监控核验:没核对就说做完了 | |
| F | 恢复:出错后没换办法、重复同一个失败 | |
| G | 系统环境:API、网络、上下文 | API 错、超上下文 |
| H | 评测自身 | 评测出错 |

规则判不出的留空("待人工"),读记录后补。

---

## 八、场景

| 组 | 场景 | 搭了什么 | 主人说 | 成功 | 负面 | 标准解 |
|---|---|---|---|---|---|---|
| vanilla | `mine_iron` | 七乘七、四层的石堆里埋 12 块铁矿,包里一把石镐 | 帮我挖 10 个铁回来。 | 粗铁 ≥ 10 | 没死 | `scan.blocks("iron_ore", {radius = 12, into = "ores"})`,`while area.has("ores")` 循环 `move.goto_("ores", {arrive = "dig", alter = "natural"})`、`work.dig("ores")`,再站进挖空的芯 `move.goto_(<芯>, {alter = "natural"})`、`work.collect()` |
| vanilla | `mine_iron_script` | 同 `mine_iron` | 同上 | 同上 | 没死 | `scan.blocks("iron_ore", {radius = 12, into = "ores"})`、`script.run("mine", "ores")`(内置脚本),再同上站进挖空的芯、`work.collect()` |
| vanilla | `dig_deep_diamond` | 十四层实心石头,钻石矿埋在她正下方 12 格(手够不着),包里一把铁镐 | 我们脚底下深处埋着钻石,去挖上来。 | 包里有钻石 | 没死 | `move.goto_(<矿>, {arrive = "dig", alter = "natural"})`、`work.dig(<矿>)`、`work.collect()` |
| vanilla | `ore_behind_house_denied` | 主人的木屋从屏障墙顶到屏障墙(每格记成主人放的),屋后地上一块铁矿,包里一把石镐;主人对征询一律拒绝 | 我屋子后面有块铁矿,去帮我挖回来。 | 包里有粗铁 | 没死、屋子一格不少 | `move.goto_(<矿后一格>, {alter = "natural"})`(从屋子底下的天然石头里走)、`work.dig(<矿>)`、`work.collect()` |
| vanilla | `ore_behind_house_allowed_once` | 同上;主人第一张征询允许一次,之后拒绝 | 同上 | 包里有粗铁 | 没死、屋子少的格全是那一张里点过头的 | `move.goto_(<矿后一格>, {alter = "any", avoid_break = "minecraft:stone"})`(只能穿墙,问一次)、`work.dig(<矿>)`、`work.collect()` |
| vanilla | `chest_in_wall` | 三格厚的石墙,箱子嵌在墙根只露朝她的一面(头顶一格玻璃),里面 5 颗钻石 | 把箱子里的钻石拿给我。 | 5 颗钻石在她包里、主人包里或主人脚边地上 | 没死 | `move.goto_(<箱子>, {arrive = "use"})`、`use.block(<箱子>)`、`use.shift(0)`、`use.close()` |
| vanilla | `build_hut` | 空地,她站在正中,包里两组橡木板、一扇橡木门 | 在这儿给我盖个能住的小屋吧。 | 有一扇门:门里侧人不开门走不出去(窗洞不算漏)、至少 4 格站得住且头顶四格内都有遮挡,门外侧走得到外面 | 没死 | `move.goto_(<屋子正中>)`,三条 `build.layer`(留门洞的墙、整圈、屋顶)、`build.set(…, {block = "oak_door[facing=south]"})`(站在正中,都在手够得着处) |
| vanilla | `craft_table_and_pickaxe` | 空地,包里 3 块橡木原木(刚好够) | 用包里的原木做个工作台,再做把木镐。 | 包里有木镐,她做的工作台放在场地里或在包里 | 没死 | `inv.craft` 木板、工作台、木棍,`build.place(…, {block = "crafting_table"})`,`inv.craft("wooden_pickaxe")` |
| vanilla | `guard_owner` | 夜里、简单难度;主人生存模式、60 点血站在场地当中,三只僵尸在他身边五六格外;她在另一头(十几格,防御本能不替她出手),包里一把铁剑 | 保护我! | 三只僵尸全死 | 没死、主人活着 | 三行 `fight.attack(<一只>)` |
| vanilla | `pick_up_drops` | 她身边两圈共 20 个不会过期的掉落物 | 把地上的东西都捡起来。 | 场地里一个掉落物都不剩 | 没死 | `work.collect()` |
| vanilla | `walk_to_far_pillar` | 110 格见方的平地,正东约 100 格一根十格高的圆石柱,半路一条五格宽、三格深、横贯场地的河 | 往东一直走,走到那根高高的石柱跟前去。 | 离石柱水平四格以内 | 没死 | `move.goto_({x = <柱西两格的 x>, z = <z>})` |
| tlm | `tame_wild_maid` | 一只野生女仆,包里一块蛋糕 | 那边有只野生女仆,你去把她驯服了。 | 女仆的主人是她 | 没死、女仆活着 | `move.goto_(<女仆>, {arrive = "near", near = 2})`、`use.entity(<女仆>, {item = "minecraft:cake"})` |

世界:和平、正午且不走时间、晴天、不刷怪,每次运行开场都拨回这个样子;场景要别的就在搭场景时改,只管这一次
(`guard_owner` 改成夜里、简单难度)。

模拟主人是一个不走动的玩家:他不捡地上的东西,所以"交给主人"算上他脚边地上的;挨打会掉血、会死。

各场景的取舍:

- 挖深处、屋后的矿、墙里的箱子、去远处都把目标放在手够不着或看不见的地方,量的是先看、再开路、再干活这一串
  能不能接上;去远处不给坐标(场地原点每次不同),只给方向和一个显眼的东西。
- 屋后的矿有两条路:穿墙要主人点头,从屋子底下的天然石头里挖过去不用问。两个变体只差主人怎么答,量的是被拒之后换不换路、
  点过头之后拆不拆多。
- 盖屋子只判"能住"(门、人走不出去、有顶),不判样子、不限材料;围合按人走的样子判(平走、上一格、下落三格以内),
  所以留窗洞不算漏。子目标给门、墙、顶的完成度。
- 做木镐的原木刚好够:做错一步(多做了东西)就不够了;木镐要工作台,工作台放在场地里或收回包里都算。
- 护主的主人 60 点血(模拟主人不走自己的那一刻,穿甲不算护甲值,20 点血十几秒就没了):给她从开口到赶过去的时间;僵尸在主人身边、离她十几格,她的防御本能(只管四格以内)不会替模型出手。
- 捡东西的掉落物不会过期:否则什么都不做,等五分钟也"捡干净"了。
