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

# 并行跑:几个服务器进程各跑一份场景,跑完并成一份结果(几份由 bench.parallel 给,不给按处理器数取,见 §九)
NUMEN_BENCH_API_KEY=sk-... ./gradlew --no-daemon :core:neoforge:runBenchParallel -Dbench.scenarios=all -Dbench.repeats=3 -Pbench.parallel=4

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
| `bench.parallel` | 处理器数 ÷ 4,1 到 4 | 只给 `runBenchParallel`:起几个服务器进程 |
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
  只有那一份;札记索引是 `MemoryPreamble`,整理记忆是 `Compactor`,派工具的顺序是 `SerialCalls`(程序整段在服务端跑)。
  和主人客户端不同的只有:人设用内置默认人设,主动性用默认档位,插件在客户端现算的状态片段没有(没有客户端)。
- **上行**:整段程序照产品的路走到 `ProgramUplink`,它的上行出口 `ProgramUplink.wire` 在评测里直接交给服务端真实入口
  (`RunProgramPayload.handle`,停止与客户端函数的答复同样),发送者是模拟主人,上行的包按网络的样子编解码一遍。
- **模拟主人**:一个在线的 `ServerPlayer`,连接是 `OwnerConnection`。发给主人的模组载荷截下来,按网络的样子
  编解码一遍,照主人客户端的做法交给大脑:程序的回执(与每次调用的结局)给上行部件,服务端要客户端执行的函数交给 `ClientEndpoint`,
  当前任务与身体状态给运行期状态,世界事件进收件箱,
  征询按剧本经 `ConsentDesk.reply` 答复,死亡切断循环。
  下行包过得了 NeoForge 的频道检查,是因为连接用 NeoForge 给 GameTest 的 `NetworkRegistry.configureMockConnection`
  写上了协商好的频道表(同伴的 `FakeConnection` 不需要:numen 的 mixin 在检查之前就把发给它的包丢了)。
- **技能**:主人客户端起来时把自带技能接进技能表;评测没有客户端,`numen_bench` 构造时经同一扇门
  (`NumenPlugins.bindSkills`)接上,玩家自己的技能目录不扫。
- **每次运行从白纸开始**:新的场地(彼此隔 512 格,任何扫描都看不见上一块)、新的她(新 UUID)、新的主人、
  临时目录里的札记、会话日志与她的 Lua 模块目录(只用内置原版,不读主人目录里的覆盖;收场后整个删掉,评测从不读它们)、清空的图纸库(`schematics/`,设计也在里面:全服共用、
  跨次保留,不清的话上一次画的设计会占着名字出现在下一次里)。

---

## 三、一次评测怎么跑

一组场景是一条 GameTest 用例,场景一次一次地跑、不并行:

1. 每个场景先跑**标准解**(把场景写好的一段 Lua 程序当作一次 `lua` 调用交出去,必须过)和**空操作**(她只答一句,必须挂)。两种基线
   不花 API,证明场景可解、断言不被什么都不做骗过。对不上的场景不跑真实模型,记为自检失败。
2. 基线可信的场景跑真实模型 `bench.repeats` 次。
3. 用例本身的成败只说评测靠不靠得住:自检全对、没有评测出错、没被余额不足打断就通过。模型成不成功只进报告。

一次运行:搭场地 → 主人上线 → 召出她 → 搭场景 → 等服务端把她的身体状态推过来(第一次请求里就有背包)→
主人开口 → 每刻推循环、看收不收场。搭场景时发生的事(如她的女仆死了、急件)会在主人开口前就叫醒她:她的那一轮先跑完、闲下来,
主人再开口(等不到闲下来,到预算的游戏刻就按超游戏刻收场)。

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
                suite -> suite.add(MaidFarmhand::new).add(ReviveMaid::new));
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
| `metrics(Scene)` | 可选:收场时记一组数(名字到数值)进结果的 `metrics`,只是指标、不决定成败 |
| `solution(Scene)` | 标准解:一段 Lua 程序,和她调 `lua` 工具写的一样 |
| `arena()`、`budget()`、`start()`、`ownerAt()`、`owner()` | 可选:场地大小、预算(默认 30 轮、10 分钟;长链条的场景自己给,如 `iron_pickaxe_chain` 是 60 轮、20 分钟)、她和主人站哪、模拟主人的剧本(默认允许一次、不回话) |

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
  `finalWords`(她最后说的话)、`transcript`(记录文件)、`error`、`metrics`(场景自己记的指标,如 `guard_owner` 收场时主人剩的血
  `owner_health`;更早的记录没有这一项)、`functions`(她的程序用到的每个 API 函数:`function`、
  `calls`、按种类的 `failures`、第一次调它之前查帮助的 `helpLookups`、`repeatedFailures`;更早的记录没有这一项)。
- `summary.md`:自检表、每个场景一行的汇总、每个函数一行(调用、失败率、参数错、其他失败、调用前查帮助、重复失败)、失败分布与
  每次失败的去处。`:bench:compare` 的对比也按函数并排前后两份。
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

bench **只测智能能力**:采集、合成链、深处挖掘、取物交付、战斗、建造、长链条、种植加工。权限、征询、打断这类机制由
GameTest 管,不在这里测。场景分三集:**回归集**每次改动都跑,**能力集**阶段性跑,**插件集**挂着目标模组单开一次。

| 集 | 场景 | 搭了什么 | 主人说 | 成功 | 负面 | 标准解 |
|---|---|---|---|---|---|---|
| 回归 | `mine_iron` | 七乘七、四层的石堆里埋 12 块铁矿,包里一把石镐 | 帮我挖 10 个铁回来。 | 粗铁 ≥ 10 | 没死 | `numen.scan.blocks("iron_ore", {radius = 12})`,`while #found > 0` 循环先 `numen.move.to(found[1], {arrive = "dig", costs = {dig = true, place = true, consent = false}})` 后 `numen.work.dig(found[1])`、再扫一次;再站进挖空的芯、`numen.work.collect()` |
| 回归 | `craft_stone_pickaxe` | 空地,包里 3 块橡木原木,旁边一堆三乘三、两层的露天石头 | 给我做把石镐。 | 包里有石镐(子目标按里程碑:工作台、木镐、圆石) | 没死 | `numen.inv.make` 木板、工作台、木棍,`numen.build.place` 放工作台,`numen.inv.make("wooden_pickaxe")`,`numen.move.to` 石堆边、`numen.work.dig` 上层三块、`numen.work.collect()`,`numen.inv.make("stone_pickaxe")` |
| 回归 | `dig_deep_diamond` | 十四层实心石头,钻石矿埋在她正下方 12 格(手够不着),包里一把铁镐 | 我们脚底下深处埋着钻石,去挖上来。 | 钻石在她包里、主人包里,或掉在主人碰撞箱外扩 5 格以内 | 没死 | `numen.move.to(<矿>, {arrive = "dig", costs = {dig = true, place = true, consent = false}})`、`numen.work.dig(<矿>)`、`numen.work.collect()` |
| 回归 | `chest_in_wall` | 三格厚的石墙,箱子嵌在墙根只露朝她的一面(头顶一格玻璃),里面 5 颗钻石 | 把箱子里的钻石拿给我。 | 5 颗钻石在她包里、主人包里,或掉在主人碰撞箱外扩 5 格以内 | 没死 | `numen.move.to(<箱子>, {arrive = "use"})`、`numen.use.block(<箱子>)`、`numen.gui.quick(0)`、`numen.gui.close()` |
| 回归 | `guard_owner` | 夜里、简单难度;主人生存模式、60 点血站在场地当中,三只僵尸在他身边五六格外;她在另一头(十几格,防御本能不替她出手),包里一把铁剑 | 保护我! | 三只僵尸全死 | 没死、主人活着 | 三行 `numen.fight.attack(<一只>)`。指标 `owner_health`:收场时主人剩的血,不作判据 |
| 回归 | `build_wall_between_posts` | 两根三格高的栅栏柱相距 5 格(之间 4 格空位),包里正好 12 块圆石 | 在那两根柱子之间砌一堵三格高的圆石墙。 | 目标墙格(两柱之间 4×3)的 F1 ≥ 0.9:目标格与场地里实际圆石格的交集,精确率 = 交集 ÷ 实际圆石格,召回率 = 交集 ÷ 目标格(照 IGLU) | 没死、两根柱子还在 | `numen.move.to` 墙南两格,`numen.build.raise(numen.shape.box(<墙左下>, <墙右上>, "cobblestone"))` |
| 能力 | `iron_pickaxe_chain` | 三棵树(各四格原木)、一堆四乘三两层的露天石头、两乘二的露天铁矿、两块露天煤矿,她空手;预算 60 轮、20 分钟 | 给我做一把铁镐。 | 包里有铁镐(子目标按里程碑:工作台、木镐、石镐、熔炉、铁锭、铁镐) | 没死 | `numen.work.mine` 一棵树,`numen.inv.make` 木板、工作台、木棍、木镐,`numen.work.mine` 石堆,`numen.inv.make("stone_pickaxe")`,`numen.work.mine` 煤矿与铁矿,`numen.inv.make("furnace")`、`numen.build.place` 放熔炉、`numen.inv.smelt(<熔炉>, "raw_iron", 3)`、`numen.inv.make("iron_pickaxe")` |
| 能力 | `harvest_and_bread` | 六乘四的麦田,中间一排水,一侧两排麦子熟了(12 株)、另一侧两排没熟(12 株),田和麦子都记成主人放的;场地里一张工作台,她空手 | 把田里熟了的麦子收了,做几个面包给我。 | 面包 ≥ 3(在她包里、主人包里,或掉在主人碰撞箱外扩 5 格以内) | 没死、没熟的麦子一株没拆 | `numen.work.dig` 熟的两排、`numen.work.collect()`、`numen.inv.make("bread", 3)`、拿着种子 `numen.use.block` 补种、`numen.inv.give` 面包给主人;子目标:收了熟的、补种 |
| 车万女仆 | `maid_farmhand` | 一只野生女仆,三乘三的湿耕地(旁边一格水),她包里一块蛋糕和 16 颗小麦种子 | 把那只女仆收了,让她帮我种这块地。 | 女仆归她、女仆当前工作是种地、女仆格子里有种子 | 没死、女仆活着 | `numen.move.to(<女仆>, {arrive = "near", range = 2})`、`numen.use.entity(<女仆>, {item = "minecraft:cake"})`、`tlm.maid.open`、`numen.gui.put("minecraft:wheat_seeds")`、`numen.gui.close()`、`tlm.maid.task("touhou_little_maid:farm")`、`tlm.maid.config({home = true})`;子目标:驯服、交种子、切模式、家模式开着且家在耕地附近 |
| 车万女仆 | `revive_maid` | 她的一只女仆搭场景时就已死(墓碑里有她的东西和胶片,maid_died 急件先到),车万女仆的祭坛用代码搭好,她包里有青金石、金锭、红石、铁锭、煤各一(`altar_recipe/reborn_maid`),P 点满 | 把女仆救回来,别用神社。 | 场地里有一只活着、归她的女仆,墓碑没了 | 没死 | 走到墓碑旁 `numen.use.entity(<墓碑>)` 取胶片,再逐根走到祭坛的六根柱子旁 `numen.gear.hold(<一样>)`、`numen.use.block(<柱顶>)`,放齐就复活 |

世界:和平、正午且不走时间、晴天、不刷怪,每次运行开场都拨回这个样子;场景要别的就在搭场景时改,只管这一次
(`guard_owner` 改成夜里、简单难度)。

模拟主人是一个不走动的玩家:他不捡地上的东西,所以"交给主人"算上他碰撞箱外扩 5 格以内的地上;挨打会掉血、会死。

各场景的取舍:

- 挖深处、墙里的箱子都把目标放在手够不着或看不见的地方,量的是先看、再开路、再干活这一串能不能接上。
- 砌墙判的是形状:目标格与实际圆石格的 F1,多放或少放都扣分,柱子拆了不算。
- 做石镐的原木刚好够:3 块原木 = 12 块板,工作台 4、木棍 4、木镐 3,余 3 块;工作台放在地上就行,不用挖回。
  铁镐链条的树只有三棵,原料有余量,但每一步做错了都补不回来。
- 护主的主人 60 点血(模拟主人不走自己的那一刻,穿甲不算护甲值,20 点血十几秒就没了):给她从开口到赶过去的时间;僵尸在主人身边、离她十几格,她的防御本能(只管四格以内)不会替模型出手。
- 麦田的田和麦子记成主人放的,但判据不涉及权限:只看面包数、没熟的有没有被拆、熟田有没有补种。面包要三乘三的格子,所以场地里立着一张工作台。
- 复活用的祭坛是车万女仆自己的多方块:模板按它的结构摆好、再经它的 `MultiBlockAltar.build` 变成祭坛方块,和玩家用博丽御币搭出来的是同一份。

---

## 九、并行跑

`runBenchParallel` 起几个服务器进程,每个就是 `runBench` 那一条命令行(同样的类路径、JVM 参数、系统属性与环境),只多两个属性:
`bench.shard=第几份/共几份`(一组里点到的场景按登记顺序轮流分给各份,一个场景连同它的两种基线与全部真实模型的次数只在一份里跑)
与 `bench.results`(这一份的结果目录)。各份的游戏目录在 `runs/bench-shards/<第几份>/`,日志是其中的 `bench.log`。跑完由
`:bench` 的 `Merge` 并成 `runs/bench/results/<时间戳>/`:`runs.jsonl` 按组、场景、变体、第几次排好,`transcripts/` 搬到一处,
`summary.md` 重写,和串行跑出来的一份同一个样子,`:bench:compare` 照常读。分到零个场景的那一份给一条当场跑完的用例。

为什么是几个进程,不是同一个服务器里几块场地:一次运行要拨的世界状态(时刻、天气、难度、规则)、图纸与设计库、大脑的几个静态
挂点(上行出口、札记与模块目录)都是整个服务器一份;同一个服务器里并行就得改产品去分开它们。分进程什么都不用改,每份有自己的
世界、目录与静态状态,彼此碰不到;一份里的场地照旧隔 512 格,远超任何扫描、寻路与实体搜索的半径。

几份由 `-Pbench.parallel`(或 `-D`)给;不给按处理器数取:每份一个服务器约占四个核与两三 GB 内存,最少 1 份,最多 4 份。
