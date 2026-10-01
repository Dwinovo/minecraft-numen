# 命令行与脚本:原子命令 + Lua 脚本

状态:设计稿(10-01),施工中。总纲见 `docs/architecture-mind-model.md` §零。

## 一、为什么

真模型评测的基线(`docs/bench.md`,`20261001-baseline`)里,命令写错是最常见的问题,连成功的局里也有:
`work_dig` 的位置写成一整串 `"x y z"`(挖掘类 12 局里 8 局)、`fight attack 27 26` 漏了 `--entity_ids`(3/3)、
`use block x y z` 漏了左右键(3/3)、`inv drop` 漏了数量。她是照 bash 的习惯写的,是我们的写法不像 bash。另一面,
`work dig`、`build at` 这类胖命令在执行里替她做决定(下一格挖哪个、为够到它多挖哪些),违背总纲。

做法:**命令照业界公认的命令行规矩来,一条命令只做一件事;组合交给 Lua 脚本**。不发明新语言——Lua 是游戏里
跑脚本的通行做法(ComputerCraft/CC: Tweaked),模型熟,能在任何地方让出、等身体收尾。

## 二、命令行十条

依据:POSIX 工具语法规范、GNU 长选项约定、clig.dev(Command Line Interface Guidelines)。

1. **结构**:`组 动词 [对象...] [--选项 值]...`。组是领域名词(move、work、area、route、scan、use、inv、build、fight、
   task、插件的模组 id)。
2. **一条命令只有一种位置参数**——它要操作的东西,可以多个;其余一律是 `--选项`。
3. **对象写法全局统一、只在一处解析**:坐标是三个数(`120 64 -35`,也收 `120,64,-35`);区域、路线是名字
   (`ores`、`ores/g3`);实体是运行时 id;方块与物品是 id(`minecraft:` 可省);标签 `#minecraft:logs`。命令只声明
   "我的对象是哪一类"。
4. **不要必填的 `--选项`**:必填的做成位置参数,否则给默认值,默认就是对的。
5. **开关写 `--sneak`**,不写 `true`;默认开的用 `--no-xxx` 关。
6. **选项名用短横线**(`--block-ids`),解析时 `_` 与 `-` 视为同一个字符(同一条解析规则,不是两份写法)。
7. **常用选项用标准名**:`--help`、`--count`、`--radius`、`--page`、`--into`。
8. **输出**:第一行一句话说结果,细节其后,列表一行一个 JSON;出错依次 `error:`(错在哪)、`usage:`(正确写法)、
   `hint:`(能照抄的下一步)。
9. **帮助**:`组 --help` 列动词,`组 动词 --help` 给用法、选项、例子,都由登记自动生成。
10. **单位统一**:时长一律秒。

**规矩写进命令登记处,登记时检查,违反就启动报错**(两种位置参数、必填选项、要写 `true` 的开关……)。插件登记的
命令同样受约束。这是机制里的一处判定,不是另写守卫测试。

## 三、原子命令

判据(总纲):**一条原子命令 = 对一个名词做一种意图**;可以含完成这个意图必需的动作层控制,不在几种会改世界的
方案之间替她选,不碰它那个名词以外的东西。

| 命令 | 只管 | 不管 |
|---|---|---|
| `scan …` | 读世界,`--into` 存进区域 | — |
| `area …` | 编辑地方;查询 `area parts`、`area has`(给脚本用) | — |
| `route plan` | 寻路:算路线,写明要改的格、要问谁 | 不动 |
| `move go` / `move goto` | 移动:照路线走,只改承诺里的格 | 不挖目标、不捡 |
| `work dig` | 挖**站在原地手够得着**的格:挡在前面的格一并挖开(要问/被禁的不挖,如实说),换工具 | 不走动、不捡 |
| `work collect` | 捡:走过去捡掉落物 | 不挖 |
| `use …` | 按一下键 | — |

- **"挖"的到达**(`--arrive dig`)= 手够得着目标区域里任意一格,不要求看得见。同样划算的站位里,优先一次能够到
  最多目标格的(定价只在寻路模块 `Goals.dig` 一处)。
- `work dig` 挖完回执说:挖了几格、还剩几格够不着、下一步能照抄的 `move goto … --arrive dig`。区域记着每格扫描时是
  什么,挖掉的下一次自动不算。
- **没有不可拆的行为,只有循环快慢之分**:秒级的决策循环(打哪只、按什么顺序、何时撤、打完捡东西)进脚本;每刻都要转
  的控制(盯着转头、追着保持在够得着处、等冷却出手、举盾)进原子命令内部。例如 `fight attack 27` 只管"打这一只"
  (追、转头、等冷却、出手,目标死了/丢了/超时收尾),"打哪几只"由脚本 `for _, m in ipairs(scan.entities(…)) do if not
  fight.attack(m).ok then break end end` 决定;安全兜底(血少逃跑、岩浆自救)由反射打断脚本。`build at`、`fight attack` 现在的胖实现在
  命令层与脚本层落地、评测追平后,按这条拆成原子命令 + 内置脚本。

## 四、脚本:Lua

组合语言是 Lua(10-01 定):照 ComputerCraft/CC: Tweaked 的路子,调命令时协程让出、等身体收尾再接着跑。只有这一套组合
语言。语言只经 `agent.script.ScriptEngine` 一处认:工具名、扩展名、注释写法、"命令怎么调"那段说明、读与跑;派发、等待、
上限、打断、回执、脚本名词、命令函数的目录与参数换算都与语言无关(`ScriptRun` 的调用请求、结局、命令结果不带任何
虚拟机类型)。实现在 `agent.script.lua.CobaltLua`,虚拟机的类型只在这个类里;眼下接的是
Cobalt 0.9.9,选型未定(Cobalt 的 README 声明版本间 API 不保证稳定、建议别处改用 LuaJ、JNLua 或 Rembulan),换虚拟机
只改 `ScriptEngine.IN_USE` 与它的实现、构建里的依赖。

### 工具面

- `command`:一行命令,入口不变。
- 组合命令的工具(`ScriptTool`,名字随语言,眼下是 `lua`):一段程序(参数 `code`),一次调用跑完,回一张回执。两种写法是两种语言,一个工具名说清它收哪一种。
- 快捷工具的 JSON 写法先保留,是否收掉由评测 A/B 定。
- 提示词(`NumenPrompts` 的 operating principles)告诉她:下一步要看上一步的结果时(一块区域的每一部分、挖到没有为止、
  头一个失败就停),写成一段交给组合命令的工具;已有的脚本先看 `<scripts>`/`<saved_scripts>`;写好用得顺的 `script save` 存下。

### 命令函数:由登记处生成

每个登记了的动作是一个函数 `组.动作(对象..., {选项=值})`,背后就是那一行命令:同一份登记、同一次解析、同样过权限、
照常记实际账、受理即能跑。

| Lua | 写成的命令行 |
|---|---|
| `work.dig("ores/g3")` | `work dig ores/g3` |
| `move.goto("ores/g3", {arrive = "dig", alter = "natural"})` | `move goto ores/g3 --arrive dig --alter natural` |
| `scan.blocks(12, {"iron_ore"}, {into = "ores"})` | `scan blocks 12 iron_ore --into ores` |
| `script.run("mine", "ores")` | `script run mine ores` |

- 按顺序的对象依次是必填参数;最后一个必填参数吃掉余下整行(`text`)时,多出来的对象空格隔开写进它。最后一个参数是
  带名字键的表就是选项。命令行上占几个词的一个值(一串 id、三个坐标)在这里是列表 `{a, b, c}`。
- 换算只在命令层一处(`NumenCli.scriptLine`):值经参数类型读成参数(`CommandArgs.fromJson`,快捷工具同一个读法),再按
  同一张参数表写回命令行(`CommandArgs.write`)。写不成(没有这个动作、对象多了、缺必填、选项名不对)在调用处抛 Lua 错误,
  附这个动作的用法。
- 函数返回 `{ok = 成没成, text = 回执那句话, data = 回执数据转成的表}`。占身体的命令等它的 task_finished 再返回,
  `done` 算成功,`text` 是收尾那句话,`data` 是 `{task, status}`。
- 登记时声明了返回项的查询(`Action.returns("has")`,如 `area has`、`area parts`)直接返回那一项,拿来就能循环:
  `for _, p in ipairs(area.parts("ores")) do … end`、`while area.has("ores") do … end`;失败时没有值可给,在调用处抛
  Lua 错误(照常往下走只会在错的东西上空转),`pcall` 接得住。帮助里这几个动作多一行 `In Lua: … returns data.<项>`。
- `print(...)` 写进回执(至多 2000 字)。按名字跑的脚本用 `...` 与 `arg` 取参数。
- 撞了 Lua 自己的全局名(`string`、`table`……)的命令组不进脚本,标准库不让给命令。动作名是 Lua 关键字的写成
  `组["关键字"](...)`;`goto` 在 Cobalt 里只在语句开头算关键字,`move.goto(...)` 照常写。

### 放在哪:`agent` 模块,和 `SerialCalls` 同一层

- 语言(`agent.script.ScriptEngine` 与实现)、一次运行(`ScriptRun`)、一次调用里的脚本(`ScriptCall`)、上限(`ScriptLimits`)
  是纯 JVM,和派发器 `SerialCalls` 在同一个模块。理由:等身体收尾、急件打断等待的那处机制就在 `SerialCalls`,脚本每调
  一个命令要的正是它;放进 api 要么另造一份等待,要么让 api 的工具反过来伸进派发器。
- 眼下的 Cobalt 只在 `agent` 引入一处(`implementation 'cc.tweaked:cobalt:0.9.9'`,SquidDev 的仓库 `maven.squiddev.cc`,
  group `cc.tweaked` 独占)。许可:Cobalt 本体 MIT(LuaJ 的可重入分支),内含的浮点格式化取自 V8,BSD-3-Clause,都与本仓
  的 LGPL-3.0 兼容;0.9.9 是 CC: Tweaked 1.21.1 用的那一版,编译目标 Java 17,在 Java 21 上跑。发行 jar 不平铺它,
  NeoForge 经 `jarJar`、Fabric 经 `include` 嵌进引擎的 jar:装了 CC: Tweaked 时两份同坐标的库由加载器按版本挑一份,
  平铺就成了两个模组里的同名包。开发期的 run 从 `agent` 的依赖拿到它(再加 `additionalRuntimeClasspath` 会重复成两个
  同名模块,起不来)。
- 派发器经端口 `SerialCalls.Port`(继承 `ScriptCall.Host`)要这几样:执行一个调用、认出组合命令的调用里的程序、把一行命令写成
  一次 `command` 调用、受理回执与 task_finished 的认法、命令目录与换算、记战绩、墙钟。主人客户端与评测大脑用
  `CompanionToolPort`,GameTest 的一轮用服务端的 `ServerPort`(直接 `serve`,战绩直接记)。

### 运行:协程在调用处让出

- 每次运行一个新的虚拟机。脚本在自己的协程里跑;命令函数是可恢复的 Java 函数,收参数、在调用处让出一个调用请求
  (`ScriptRun.Call`:行号、组、动作、对象、选项),拿到结局后从调用处接着跑。等身体干活时脚本不占线程、不阻塞谁。
- 命令只能由脚本本身调;在脚本自己开的协程里调会报错说明(那次让出会落到她自己的 `resume` 手里)。
- 一段调用里可以再跑一份有名字的脚本:`script run`(命令行上或 `script.run`)是一条普通命令,命令层按名字找到脚本、
  把正文与参数放进回执 `data.run`;派发器见到这样的回执就在原地开一层跑它,跑完它的结局就是那次调用的结局。上限算
  整段调用的总数,打断时每一层都停。外接大脑直接调工具、没有这个派发器:组合命令的工具回一条说明,`script run` 只交回正文。

### 沙箱与上限

- 只装 Lua 自己的安全标准库:基本函数、string、table、math、coroutine、utf8;拿掉 `load`、`loadstring`、`setfenv`、
  `getfenv`、`string.dump`;Cobalt 本来就不装 io、os、debug、require、dofile。
- 上限只在 `ScriptLimits` 一处,到了停在当前那一行,回执说停在哪、因为哪一条:
  - 命令数 200(嵌套的算在一起):挖一块区域每圈"还剩没有、走过去、挖"三条,两百条够七十来圈;再多是循环条件写错了在空转。
  - 墙钟 20 分钟(原版一整天):只在命令之间查,等身体收尾时不打断那件活,活有自己的期限。
  - 两次调命令之间 100 万条指令:脚本在大脑的线程上跑(主人客户端主线程、评测时服务端主线程),正常脚本两条命令之间只做
    几百条以内;一百万条约几十毫秒,再多是死循环。数指令的钩子挂在虚拟机主线程上、协程都继承它;超了把整个虚拟机挂起,
    不是抛 Lua 错误,`pcall` 接不住。
  - 循环圈数不另设:不调命令的循环由指令预算管,调命令的由命令数管。
- 内存不设上限(和 CC: Tweaked 一样):`string.rep` 有长度上限,反复拼接的字符串没有;记作遗留。

### 打断:停在命令之间

复用"同一轮里等 task_finished"的那处(`SerialCalls`)与它的口径:

- 等某件身体活收尾时来了急件(主人说话、急事):不再等,脚本停,回执写 `your owner spoke; t8 keeps running`
  (那件活照常跑);这一批余下的调用照旧各回"没执行"。
- 一行命令在跑时来了急件:这一行的回执到了就停,停在命令之间。
- 主人按停止(以及死亡、登出、外接接管、遣散):内核 `halt` 先收工具口再作废这次 run,在跑的脚本这时交出回执
  `this turn was cut off; t8 was stopped too`(停止键叫停身体)或 `keeps running`,作为它那个调用的结果进历史,
  切断点随后记下原因;所以模型看到的是真实的停处,而不是笼统的"被打断"。
- 有名字的脚本被停下也记一次战绩(没跑完、停在哪一行、为什么)。

### 回执

第一行一句话说结局;之后按脚本的行各一句(调了几次、成败、最后一次那句话的第一行),不重复命令全文;最后是 `print`
写的字。每件身体活的实际账照旧只在它自己的 task_finished 里说一次(`NavText` 一处),回执点它的编号与结局,不另写一份。
数据里有 `status`(ok / error / stopped)、`commands`,按名字跑的有 `script`。例:

```
Script mine stopped at line 24 after 7 commands: could not dig ores: 2 blocks are out of reach.
line 9 area.has: 2 calls, none failed; last ok — ores has 2 blocks left
line 10 move.goto: 2 calls, none failed; last ok — t41 done: Arrived within reach of ores/g2.
line 15 work.dig: 2 calls, 1 failed; last failed — t44 failed: 2 blocks are out of reach.
line 22 work.collect: ok — t45 done: Picked up 9 raw_iron.
```

```
The script stopped at line 1 (move.goto) after 1 command: your owner spoke; t12 keeps running. Nothing after that ran.
```

(停在第 1 行时那件活还没收尾,那一行没有结局可记,回执只有第一句。)

### 脚本这个名词:`script` 组

- 内置的随模组发布、只读(`BuiltinScripts`);同伴存下的归主人(`ScriptStore`,主世界存档数据 `numen_scripts_<主人>`,
  和 `AreaStore` 同一个做法),同一主人的同伴都看得见、都跑得了。名字规矩用 `Names`(小写字母、数字、`_`、`-`)。
- `script list`(每份一行:说明、谁的、战绩)、`script show <名字>`、`script run <名字> [参数...]`、
  `script save <名字> <正文>`、`script delete <名字>`。都不占身体。
- 存之前用同一个编译器读一遍,读不通不收,报错带行号(`gt-broken:2: …`);正文开头一行注释(`-- …`)就是它的一句话
  说明,没写不收——内置脚本也是这一条,说明只有正文里这一处。存同名的替换旧的、清掉旧战绩(旧战绩说的是旧正文);
  内置的同名存不进,删不掉,想改就另存一份。
- 正文怎么传:`script save` 的最后一个参数吃掉余下整行;在 Lua 里是 `script.save("sweep", [[ … ]])`。不另开工具字段,
  也不做 here-document:存脚本就是一条普通命令,同一次解析、同样过权限。
- 战绩:跑了几次、跑完几次、最近一次的时刻、最近一次没跑完停在哪一行与原因,只给事实。大脑跑完(跑完、出错、被停下)
  一份有名字的脚本时经 `ScriptTallyPayload` 送到服务端(只认主人),和工具调用同一个上行出口。内置脚本的战绩也记在
  主人名下。
- 权限:改、删别的同伴存的脚本问主人。动作 `edit_script`,信号 `saved`(这份是别的同伴存的);出厂 allow
  `edit_script(!saved)`(新存、改她自己的不问)、ask `edit_script(saved)`。理由:那是别人摸索出来的东西,删了撤不回,
  该不该动只有主人说得清;不写死能或不能,主人改一行规则就能放开或收紧。主人看与删用同一组命令(`/numen drive`)。
- 索引:内置脚本进系统提示 `<scripts>`(只随登记变,稳定前缀);同伴们存的随存删变,挂在她身上的状态
  `<saved_scripts>`(身体状态片段,服务端算、随状态包推)。两处都只列名字与一句说明,和技能表同一种写法;全文按需
  `script show`。

### 内置脚本的登记

照技能与命令的登记:`NumenApi.bundleScripts(Path)` 收一个目录,每个 `<名字>.lua` 一份;两侧都登记(大脑跑它,服务端
存取与列它),在 `NumenPlugins.register` 的块里直接调。登记那一刻把关:名字合规矩、读得通、开头有说明、不重名,任何一条
不过当场抛出。core 原地读 jar 里的 `scripts/`。

第一批 `mine`(`core/common/src/main/resources/scripts/mine.lua`):

```lua
-- Dig out an area: walk within reach of what is left of it, dig what is in reach, until nothing is left; then pick up the drops.
-- usage: script run mine <area>   (an area from area list, or one part of it: ores/g3)
local where = ...
if where == nil then
  error("usage: script run mine <area>", 0)
end

local stuck
while area.has(where) do
  local walk = move.goto(where, {arrive = "dig", alter = "natural"})
  if not walk.ok then
    stuck = "could not get within reach of " .. where .. ": " .. walk.text
    break
  end
  local dig = work.dig(where)
  if not dig.ok then
    stuck = "could not dig " .. where .. ": " .. dig.text
    break
  end
end

work.collect()
if stuck then
  error(stuck, 0)
end
```

## 五、落地

在集成分支上两路并行:**命令层**(十条规矩与登记检查、对象统一解析、原子化 `work dig`/`collect`、`area parts/has`、
`--arrive dig` 按覆盖定价、提示词与技能)与**脚本层**(脚本运行、组合命令的工具、上限与打断、脚本名词与内置脚本登记、
`mine` 脚本)。接口:脚本的每个命令函数就是一条命令行,交给命令层原样执行;查询要直接返回值的,登记时声明
`Action.returns`。

**合回 1.21.1 的门槛:评测在同一批场景上追平或超过基线**(成功率、pass^3、命令出错率、轮数、墙钟),配对比较。

## 六、落地记录

### 命令层(10-01)

细节与新旧对照表在 `docs/cli.md` 附录 J。

- 十条里的 2、4、5、6、10 写进登记处(`CommandGroup.checkParams`、`Param`、`FlagsArgument`):位置参数只有一类对象,可以不写的
  参数都写明默认,开关不收值,标志名 `_`/`-` 同一,时长是秒;违反的在登记时抛出,插件同样。
- 对象写法只在 `ArgType` 一处读(格子、去处、区域、实体、方块与标签);快捷工具的 JSON 走同一个读法。
- 报错三段 `error:`/`usage:`/`hint:` 由 `Problem.of` 一处拼。
- `work dig` 只挖手够得着的格、不走不捡;`work collect` 只捡;`--arrive dig` 到了 = `work dig` 站在那儿办得成,同样划算的站位
  优先够得着最多格的(`Goals.dig(List, …)`);新增 `area parts`、`area has`。`build at`、`fight attack` 标明是工作流。
- 评测的标准解改成 `move goto … --arrive dig`、`work dig`、`work collect` 组合;挖一块区域的 GameTest 也用同样的组合
  (`GameTestKit.mine`),待脚本层的 `mine` 内置脚本到位后可换成它。

### 脚本层(10-01)

- **脚本层(10-01)**:组合命令的工具 `ScriptTool`、语言只经 `ScriptEngine`、命令函数由登记处生成
  (`NumenCli.scriptCatalog`/`scriptLine`、`Action.returns`)、派发器逐条派与
  等身体收尾(`SerialCalls` + `ScriptCall`)、上限(`ScriptLimits`)、在命令之间停下(急件、主人开口、`halt` 先收工具口)、
  按行的回执、脚本名词(`script` 组、`ScriptStore`、`BuiltinScripts`、`edit_script`/`saved`、战绩经 `ScriptTallyPayload`、
  `<scripts>` 与 `<saved_scripts>` 索引)、内置 `mine`。单测:`CobaltLuaTest`(让出与恢复、返回表与直接返回值、沙箱、指令预算、
  报错行号)、`SerialCallsTest`(顺序、等收尾、分支、开口与急件、切断时的回执、按名字跑与嵌套、两种上限)、`ScriptLineTest`、
  `ScriptStoreTest`、`GateTest`;GameTest:`ScriptGameTests`。
- 外接大脑(MCP)直接调工具,没有派发器:组合命令的工具回一条说明,`script run` 只交回正文。要让外接大脑也跑脚本,得把它的调用
  也经派发器、并把收件箱的到达转给它,另做。
