# 命令行与脚本:原子命令 + Lua 脚本

状态:10-01 设计,10-03 改成只有 `lua` 一个工具(§七),10-04 模块统一成库、存在主人客户端(§九),同日 API 第二版:全名、
值带方法、无名词增删改查(§十)。总纲与五层见
`docs/architecture-mind-model.md` §零。

## 一、为什么

真模型评测的基线(`docs/bench.md`,`20261001-baseline`)里,命令写错是最常见的问题,连成功的局里也有:
`work_dig` 的位置写成一整串 `"x y z"`(挖掘类 12 局里 8 局)、`fight attack 27 26` 漏了 `--entity_ids`(3/3)、
`use block x y z` 漏了左右键(3/3)、`inv drop` 漏了数量。她是照 bash 的习惯写的,是我们的写法不像 bash。另一面,
`work dig`、`build at` 这类胖命令在执行里替她做决定(下一格挖哪个、为够到它多挖哪些),违背总纲。

做法:**命令照业界公认的命令行规矩来,一条命令只做一件事;组合交给 Lua 脚本**。不发明新语言——Lua 是游戏里
跑脚本的通行做法(ComputerCraft/CC: Tweaked),模型熟,能在任何地方让出、等身体收尾。

## 二、命令行十条

依据:POSIX 工具语法规范、GNU 长选项约定、clig.dev(Command Line Interface Guidelines)。

1. **结构**:`组 动词 [对象...] [--选项 值]...`。组是领域名词(move、work、route、scan、use、gui、inv、gear、build、
   fight、time、creative、task、module 等);脚本里一律写全名 `numen.<组>.<函数>`,插件是 `<模组 id>.<组>.<函数>`(§十)。
2. **一条命令只有一种位置参数**——它要操作的东西,可以多个;其余一律是 `--选项`。
3. **对象写法全局统一、只在一处解析**:坐标是三个数(`120 64 -35`,也收 `120,64,-35`);路线是名字;实体是运行时 id;
   方块与物品是 id(`minecraft:` 可省);标签 `#minecraft:logs`。命令只声明"我的对象是哪一类"。
4. **不要必填的 `--选项`**:必填的做成位置参数,否则给默认值,默认就是对的。
5. **开关写 `--sneak`**,不写 `true`;默认开的用 `--no-xxx` 关。
6. **选项名用短横线**(`--block-ids`),解析时 `_` 与 `-` 视为同一个字符(同一条解析规则,不是两份写法)。
7. **常用选项用标准名**:`--help`、`--count`、`--radius`、`--page`。
8. **输出**:第一行一句话说结果,细节其后,列表一行一个 JSON;出错依次 `error:`(错在哪)、`usage:`(正确写法)、
   `hint:`(能照抄的下一步)。
9. **帮助**:`组 --help` 列动词,`组 动词 --help` 给用法、选项、例子,都由登记自动生成。
10. **单位统一**:时长一律秒。

**规矩写进命令登记处,登记时检查,违反就启动报错**(必填选项、要写 `true` 的开关、例子读不通……)。插件登记的
命令同样受约束。这是机制里的一处判定,不是另写守卫测试。

## 三、原子命令

判据(总纲):**一条原子命令 = 对一个名词做一种意图**;可以含完成这个意图必需的动作层控制,不在几种会改世界的
方案之间替她选,不碰它那个名词以外的东西。

| 函数 | 只管 | 不管 |
|---|---|---|
| `scan.*` | 读世界:`scan.blocks` 交回相连的团(Cluster)、`scan.map`、`scan.container`、`scan.sight` | 不存任何东西 |
| `route.new` / `route.plan` | 寻路:写路线、算路线,写明要改的格、要问谁 | 不动 |
| `move.go` | 移动:照规划好的路线走,只改承诺里的格 | 不挖目标、不捡 |
| `move.follow` | 跟着一个实体走,它走远了、没了、超时收尾 | 不打、不捡 |
| `work.dig` | 挖**站在原地手够得着**的格:挡在前面的格一并挖开(要问/被禁的不挖,如实说),换工具 | 不走动、不捡 |
| `build.place` | 把给的格(Cells 或蓝图句柄)里**站在原地手够得着**的放一遍 | 不走动、不挖、不重来 |
| `build.diff` | 查:还差什么,够得着的、要先挖的、够不着的与最低最近的那一格 | 不动 |
| `fight.attack` | 打**一只**:追、转头、等冷却、出手,死了/丢了/超时收尾 | 不挑下一只、不跑(跑是逃跑本能) |
| `use.*` | 按一下键:右键一格/一只实体/前方,左键一下 `use.hit` | 不挖(挖是 `work.dig`) |
| `gui.*` | 开着的界面(Window):看、按种类放进拿出、挪一格、整叠挪、关 | 不开界面(开是 `use.block`) |
| `inv.craft` | 在开着的合成格里照一条配方合一次 | 不挑配方、不找工作台 |
| `work.fish` | 抛一竿,钓上来或如实失败 | 不再抛 |
| `time.wait` | 站着等几秒,主人一喊停就停 | — |

模块函数(Lua 写的,随模组发,`numen.module.show` 看得到全文;和组同名的模块给那一组加函数)在同一张目录里、和动作一样调用:

| 模块函数 | 由哪几个原子函数组成 |
|---|---|
| `move.goto_(place, opts)` | `route.new` + `route.plan` + `move.go`,走的是她自己那条 `goto-<名字>` |
| `work.collect(opts)` | `scan.entities("item")` + 一件件 `move.goto_` 走上去(原版玩家走过去就捡起) |
| `work.mine(blocks)` | `work.dig(blocks)` 挖够得着的 + `work.collect` 捡 / 够不着时 `move.goto_(blocks, {arrive = "dig", alter = "natural"})`,挖完为止,返回挖了几格 |
| `fight.clear(radius)` | `scan.entities("hostile")` + 一只一只 `fight.attack` |
| `build.raise(building, opts)` | `build.diff` 问还差什么 → `build.place` 放够得着的 / `move.goto_ … arrive "dig"` + `work.dig` 挖开挡路的 / `move.goto_ … arrive "reach"` 走到够得着最低最近那格的地方 |
| `inv.make(item, count)` | `inv.recipes` 挑一条料够的合成配方 → 2x2 在自己的格里、3x3 开工作台(开着的、16 格内走过去的、或把带着的放在身边)→ `inv.craft` 到够数 → 关上它开的台 |
| `inv.store` / `inv.fetch` / `inv.smelt` / `inv.give` | 走过去 `use.block` 开箱子(熔炉)、`Window:put`/`take`、关上;烧炼中间 `time.wait_until` 等烧完;给人是走到身边 `inv.drop` |
| `time.wait_until(ready, opts)` | 看一眼,不成就 `time.wait` 一会儿再看,超时抛 `timeout` |
| `shape.*` | 画格子:`box`、`line`、`cylinder`、`sphere`、`layer`,交回 Cells |

- **"挖"的到达**(`arrive = "dig"`)= 手够得着目标区域里任意一格,不要求看得见。同样划算的站位里,优先一次能够到
  最多目标格的(定价只在寻路模块 `Goals.dig` 一处)。**"放"的到达**(`arrive = "reach"`)= 手够得着往那一格里放方块,
  不站进那一格(`Goals.place`,和 `Goals.dig` 同一套够得着的格,只多禁站进目标)。
- `work.dig` 挖完回执说:挖了几格、还剩几格够不着、下一步能照抄的 `move.goto_(…, {arrive = "dig"})`。给的是扫描交回的
  Block 时,那一格还是那种方块才挖,挖掉的下一次自动不算。
- **没有不可拆的行为,只有循环快慢之分**:秒级的决策循环(打哪只、按什么顺序、何时撤、打完捡东西)进脚本;每刻都要转
  的控制(盯着转头、追着保持在够得着处、等冷却出手、举盾)进原子函数内部。例如 `fight.attack(27)` 只管"打这一只"
  (追、转头、等冷却、出手,目标死了/丢了/超时收尾),"打哪几只"由程序决定(`fight.clear` 就是这样一段);安全兜底
  (打不过就跑的逃跑本能、岩浆自救)由反射打断程序。

## 四、脚本:Lua

组合语言是 Lua 5.2(10-01 定),只有这一套组合语言。语言只经 `agent.script.ScriptEngine` 一处认:工具名、扩展名、注释写法、
"命令怎么调"那段说明、函数名的改写、读与跑;派发、等待、上限、打断、回执、脚本名词、命令函数的目录与参数换算都与语言
无关(`ScriptRun` 的调用请求、结局、命令结果不带任何虚拟机类型)。实现在 `agent.script.lua.LuaEngine`,虚拟机的类型只在
这个类里;换语言只改 `ScriptEngine.IN_USE` 与它的实现。

虚拟机是本仓自己的纯 JVM 模块 `lua`(不碰 Minecraft 与 Numen 的类型):LuaJ 主干最后一次提交 `daf3da9`(2020-04-01)
的源码,MIT,包名改成 `com.dwinovo.lua.vm`,只留解析、编译、解释与安全的标准库;从 FiguraMC/luaj 逐个挑了几处修复。
上游是哪一次、挑了哪几个提交、我们改了什么,见 `lua/README.md`。对外只有 `com.dwinovo.lua.LuaSandbox`。

### 工具面

- 模型只有一个工具 `ScriptTool`(名字随语言,眼下是 `lua`):一段程序(参数 `code`),一次调用跑完,回一张回执。一次调用
  就是一行程序:`status.self()`。外接 MCP 服务器的工具照旧挂着(`RemoteMcpTool`),不在这一套里。
- 计划清单也是一个函数 `todo.write({"[x] …", "[>] …", "[ ] …"})`(主人客户端执行),它的参数原样记进回执的
  `data.echoed`,聊天里的计划清单从那里画。
- 系统提示的 `<api>` 索引、工具说明、`api.help` 都从登记处生成;提示词与技能里的例子全是 Lua,防漂移测试经脚本的前端读一遍。
- 人在聊天框里敲的命令行(`/numen drive`)是第二个前端,读的是同一张登记表、同一个处理函数。

### 命令函数:由登记处生成

每个登记了的动作是一个函数 `组.动作(对象..., {选项=值})`:同一份登记、同一个处理函数、同样过权限、照常记实际账、
受理即能跑。

- 按顺序的对象依次给位置参数,最后一个位置参数收下余下的全部对象;最后一个参数是一张表、而且它的键都是这个函数的选项名,
  它就是选项表(键里的 `_` 与 `-` 同一),否则它是一个对象(一个 Pos 也是一张表)。一处位置写成 Pos `{x = 120, y = 64,
  z = -35}`,一串值写成一张列表。值的样子、返回与错误见 §八。
- 换算只在 `NumenCli.invocation` 一处:对象与选项写成参数名到值的 JSON,当场经参数类型 `ArgType` 读一遍
  (`CommandArgs.fromJson`,执行的那一侧读的也是它),**不拼命令行**。读不成(没有这个函数、对象多了、缺必填、选项名不对、
  值读不成)在调用处抛错误值,种类 `bad_argument` 或 `no_function`,`hint` 是改好的那一行调用(看得出想写什么时)或怎么看
  帮助。读一组里没有的函数当场报错,附最像的那个名字。
- **成功返回数据,失败抛错误值。** 占身体的命令等它的 task_finished 再返回,task_finished 带着那件活的结果数据。返回什么
  由登记时声明的类型说(`Action.returns(类型)`,或 `returns("count", 整数)` 只交数据里的一项):`inv.count` 是一个整数,
  `scan.blocks` 是团的列表,`work.dig` 是 `{dug, left, out_of_reach, nearest}`,声明了不返回的是 nil。那句话只进回执,不进
  程序。失败抛的错误值 `pcall` 接得住,接住了就按 `err.kind` 分支。
- `print(...)` 写进回执(至多 6000 字;表按 Lua 的写法印出来)。
  `raise(kind, message, hint)` 以一个错误值失败,`error("why", 0)` 是程序自己的运行错(`runtime`)。
- **名字的改写只有一条**:组名或动作名撞上 Lua 的保留字(`goto`、`end`……)或沙箱自带的全局名(`string`、`table`、
  `print`……)的,后面加 `_`:`move.goto_`。命令名本身不变,只是脚本里的写法;帮助里这样的动作多一行
  `In a script: move.goto_(...).`,`lua` 工具的说明里也写了这一条。规则只在 `ScriptEngine.functionName` 一处,登记处的
  目录、回执里的函数名、帮助都从它来;沙箱不收撞名的宿主函数,登记时就抛出。

### 放在哪:`agent` 模块,和 `SerialCalls` 同一层

- 语言(`agent.script.ScriptEngine` 与实现)、一次运行(`ScriptRun`)、一次调用里的脚本(`ScriptCall`)、上限(`ScriptLimits`)
  是纯 JVM,和派发器 `SerialCalls` 在同一个模块。理由:等身体收尾、急件打断等待的那处机制就在 `SerialCalls`,脚本每调
  一个命令要的正是它;放进 api 要么另造一份等待,要么让 api 的工具反过来伸进派发器。
- `lua` 模块由 `agent` 依赖;发行 jar 里和 `ai`、`agent` 一样平铺进引擎(`api-loader` 约定插件),许可随 jar 带
  `LICENSE_numen-lua`。
- 派发器经端口 `SerialCalls.Port`(继承 `ScriptCall.Host`)要这几样:执行一个调用、认出组合命令的调用里的程序、把一行命令写成
  一次 `command` 调用、受理回执与 task_finished 的认法、命令目录与换算、记战绩、墙钟。主人客户端与评测大脑用
  `CompanionToolPort`,GameTest 的一轮用服务端的 `ServerPort`(直接 `serve`,战绩直接记)。

### 运行:一个脚本一个虚拟线程

- 每次运行一个新的虚拟机(`Globals`),跑在它自己的虚拟线程上;模块用到时才从磁盘或 jar 读此刻的正文(§九),所以改了文件下一段
  程序就用新的。命令函数是宿主函数:收参数、把调用请求
  (`ScriptRun.Call`:行号、组、动作、对象、选项)交给驱动方,然后在原地阻塞等结局,拿到后从调用处接着跑。驱动方只在
  两次调命令之间等它算完(指令预算管着);等身体干活时谁都不等它,脚本线程停着不占平台线程。
- 不装 `coroutine` 库,命令只有脚本本身调得到。

### 沙箱与上限

- 只装基本函数、`string`、`table`、`math`;没有 `load`、`loadstring`、`dofile`、`loadfile`、`collectgarbage`、
  `string.dump`、`coroutine`,也没有 io、os、debug、package、luajava。虚拟机只收源码文本(不收二进制块)。
- 字符串库表与字符串元表全 JVM 只有一份,锁成只读:一个脚本改不了 `string.rep` 或 `getmetatable("").__index` 去影响
  别的脚本。
- 上限只在 `ScriptLimits` 一处,到了停在当前那一行,回执说停在哪、因为哪一条:
  - 命令数 200(嵌套的算在一起):挖一块区域每圈"还剩没有、走过去、挖"三条,两百条够七十来圈;再多是循环条件写错了在空转。
  - 两次调命令之间 100 万条指令(约几十毫秒),一次运行共 1000 万条。
  - 一次运行为字符串分配的字节共 64 MB:每次开新字节数组之前记账,`s = s .. s` 翻倍几十次就停。
  - 墙钟 20 分钟(原版一整天):沙箱在指令之间查,派发器在命令之间查;等身体收尾时不打断那件活,活有自己的期限。
  - 循环圈数不另设:不调命令的循环由指令预算管,调命令的由命令数管。
- 预算与打断都在每条指令前的钩子里查,到了抛的是 Java `Error`,`pcall` 只接 Lua 错误,接不住:`pcall` 里的死循环一样停。
  外面也能随时打断一次运行(`Running.interrupt`),阻塞在命令函数里的脚本线程同样放手。

### 打断:停在命令之间

复用"同一轮里等 task_finished"的那处(`SerialCalls`)与它的口径:

- 等某件身体活收尾时来了急件(主人说话、急事):不再等,脚本停,回执写 `your owner spoke; t8 keeps running`
  (那件活照常跑);这一批余下的调用照旧各回"没执行"。
- 一行命令在跑时来了急件:这一行的回执到了就停,停在命令之间。
- 主人按停止(以及死亡、登出、外接接管、遣散):内核 `halt` 先收工具口再作废这次 run,在跑的脚本这时交出回执
  `this turn was cut off; t8 was stopped too`(停止键叫停身体)或 `keeps running`,作为它那个调用的结果进历史,
  切断点随后记下原因;所以模型看到的是真实的停处,而不是笼统的"被打断"。
- 程序被停下,它用到的每个模块也记一次战绩(没跑完、停在哪一行、为什么)。

### 回执

第一行一句话说结局(跑完用了几次调用几秒;出错停在哪一行、那个错误值写出来的样子;被停下停在哪、为什么);之后每次 API
调用一行(在哪一段的哪一行、哪个函数、`ok` 或错误的种类、它那句话的第一行),然后是返回值(照 Lua 的写法)与 `print`
写的字。回执里的话由各次调用自己的那句话拼成,数据由同一份结果给程序,两样出自同一处。每件身体活的实际账照旧只在它自己的
task_finished 里说一次(`NavText` 一处),回执点它的编号与结局,不另写一份。数据里有 `status`(ok / error / stopped)、
`calls`、`returned`(返回值本身,是表就是 JSON 对象)、出错时的 `error`(`kind`、`message`、`hint`、`fn`),按名字跑的有
`script`,回显的调用(`todo.write`)在 `echoed`。例:

```
The script stopped at line 3 after 2 calls: work.dig: bad_argument — argument 'place': a position is one table with named fields; got {120, 12, -35}
usage: work.dig(place..., {count=…})
hint: work.dig({x = 120, y = 12, z = -35})
line 1 scan.blocks: ok — 1 group(s) within 32 blocks of …
line 2 route.new: ok — made route goto-…
line 3 work.dig: bad_argument — argument 'place': a position is one table with named fields; …
```

```
The script stopped at line 1 (move.go) after 3 calls: your owner spoke; t12 keeps running. Nothing after that ran.
```

(停在第 1 行时那件活还没收尾,那一行没有结局可记,回执只有第一句。)

### 模块:`module` 组

见 §九。模块是唯一一种存下来的 Lua:一个文件返回一张函数表,程序按名字直接用;唯一从头跑的程序是 `lua` 工具这一轮的 `code`。

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
  `<scripts>` 与 `<saved_scripts>` 索引)、内置 `mine`。
- 虚拟机:自己的模块 `lua`(LuaJ 主干 `daf3da9` 加 FiguraMC 的三处修复,见 `lua/README.md`),`LuaEngine` 接它;成功直接
  返回值、失败抛错;撞名加 `_`。
- 单测:`LuaSandboxTest`(语义、沙箱、元表锁、字符串预算、`pcall` 里的死循环、墙钟、外部打断、虚拟线程阻塞、值互转、撞名)、
  `LuaEngineTest`(交出与接着跑、直接返回与抛错、声明的返回项、撞名)、`SerialCallsTest`(顺序、等收尾、分支、开口与急件、
  切断时的回执、按名字跑与嵌套、两种上限)、`ScriptLineTest`、`ScriptStoreTest`、`GateTest`;GameTest:`ScriptGameTests`
  (顺序、按失败分支、主人停止与开口、`for` 走 `area.parts`、`script run mine` 挖空埋在石头里的矿、存读跑删)。
- 评测:`mine_iron_script` 和 `mine_iron` 同一个场景,标准解的挖矿交给 `script run mine ores`;一格高的矿洞里的掉落物
  `work collect` 走不进去(它不改地形),两份标准解最后都站进挖空的芯再捡。
- 外接大脑(MCP)当时直接调工具、没有派发器;10-03 起它的 `lua` 调用经她自己的派发器跑(§七)。

## 七、只有 lua 一个工具,API 原子化(10-03)

- **一个入口**:模型的工具只剩 `lua`;`command` 与各组的快捷工具删了,`todowrite` 成了 `todo.write`。Lua 的对象与选项
  直接按参数类型读成值交给处理函数,不经命令行字符串。外接大脑(MCP)的 `lua` 调用交给她自己的派发器跑
  (`AgentLoop.runAside`):同一套等收尾、上限、回执;程序跑着的时候到达的事件转给它,不另起一轮对话。
- **原子化**:`build.at` 只放站在原地够得着的格,一格都够不着就当场拒并说怎么走过去;施工绕外圈、清场(`ClearSiteTask`)、
  垫块记账(`DropTracker`)都删了。走与挖由 `build.raise` 这段库函数组合。新增 `build.left`(查询)与到达方式 `reach`。
  `work.collect` 变成库函数(扫掉落物、走上去),新增掉落物的 `pickup_delay`;`fight.attack` 只收一只,"打一片"是
  `fight.clear`。
- **回执**:每次 API 调用一行;出错带行号与那次调用的 `error:`/`usage:`/`hint:`;返回值与 `print` 在后面。
- **测试**:GameTest 全部从 Lua 入口调(`GameTestKit.lua`);库函数各有端到端的 GameTest(`mine`、`work.collect`、
  `fight.clear`、`build.raise`),到达方式 `reach`、`build.left`、够不着时 `build.at` 的拒绝各有一条。

## 八、值、错误与帮助一个样子(10-03)

照主流的写法统一:位置是一种值,查询结果原样交给动作,API 返回数据,失败是带种类的错误值,帮助是从登记处生成的类型签名。

| 借的 | 出处 | 我们的落点 |
|---|---|---|
| 位置是一种带名字字段的值,方块带着它的 `position`,`bot.dig(block)` 收方块本身 | mineflayer 的 `Vec3`、`Block.position`、`bot.dig` | `Pos {x, y, z}`(`Shapes.POS`),Block/Entity/Item 都带 `pos`;收位置的参数收任何带 `pos` 的表(`ArgType.cellFromJson`、`placeFromJson`) |
| 查到的东西直接交给下一步,在代码里筛,不让模型抄数 | Anthropic《Code execution with MCP》、Cloudflare Code Mode | `scan.blocks(...).groups[1].nearest` → `work.dig`;`scan.entities()[1]` → `fight.attack`、`move.to`;`route.plan(...)` 的计划 → `move.go` |
| 函数签名写成类型,模型照签名写代码;只给一个写代码的工具 | Cloudflare Code Mode(TS 接口)、smolagents `CodeAgent`(带类型的函数签名) | `api.help` 给 LuaLS 注释:`---@class 组` 加每个函数一行 `---@field f fun(…): 返回`(`LuaEngine.groupText`、`functionText`) |
| 先看索引,要用哪组再展开 | Anthropic 的渐进披露(`search_tools`) | 系统提示只放 `<api>` 索引(组名、一句话、函数名与共用的类);`api.help("组")` 展开一组,`api.help("组.函数")` 展开一个 |
| 失败是一个值,带能拿来改正的信息,程序自己接住再改 | CodeAct(报错回给代码自纠)、Voyager 技能库抛 `Error` 再由技能接住 | 错误值 `{kind, message, hint, fn, data}`,`pcall` 接住按 `err.kind` 分支;库函数 `raise(kind, …)` |
| 类型注解的写法 | LuaLS(`---@class`、`---@field`、`---@param`、`---@return`) | 登记处的 `ScriptType` 与库函数自己的注释,同一种写法 |

### 值

- **位置只有一种写法**:一格是 Pos `{x = 120, y = 64, z = -35}`,一列是 `{x = …, z = …}`,一个高度是 `{y = …}`。小数按所在的
  那一格算(`BlockPos.containing`),所以 `status.self().pos` 原样能交。任何带 `pos` 字段的表(Block、Entity、Item、一团的
  `nearest`)放在位置那一格就是它的 `pos`;收实体的参数收编号或带 `id` 的表。读法只在 `ArgType` 一处。
- **旧写法删了**:`{120, 64, -35}`、`"120 64 -35"`、`"120,64,-35"` 都是 `bad_argument`,`hint` 是改好的那一整行调用;
  只给了一列却要一格,说缺 y。删的理由:同一处位置三种写法,模型在三种之间来回猜,查询结果也交不进动作。
- 共用的类在 `Shapes` 与各组登记时声明(`CommandGroup.declare`):`Pos`、`Block`、`Entity`、`Item`(继承 Entity)、`Error`,
  以及 `AreaPart`、`Area`、`Plan`、`Leg`、`Step`、`Ask`、`Stop`、`Costs`、`Placed`、`Design`、`Maid` 这些组自己的。

### 返回

- 每个动作登记时必须声明返回的类型(`Action.returns`,`CommandGroup.close` 查;不返回值是 `ScriptType.NOTHING`)。处理函数交回
  `TaskResult`:`data` 给程序,`message` 只给回执与 task_finished,两样在同一处从同一份事实写出。
- 只读不跑一段正文时(帮助里的例子、技能里的写法),调用按声明的类型造一个样子返回(`ScriptType.sample`),取字段、取第一项、
  循环的写法都读得通;所以例子写错一个字段名,登记时就查出来。

### 错误

- 错误值是一张表:`kind`(`bad_argument`、`no_function`、`not_found`、`out_of_reach`、`no_path`、`no_material`、
  `needs_consent`、`denied`、`interrupted`、`timeout`、`failed`;只在回执里的 `syntax`、`runtime`、`limit`)、`message`、
  `hint`(能照抄的下一行程序,如 `move.to({x = …}, {arrive = "dig"})`)、`fn`、`data`。种类只在 `ErrorKind` 一处。
- `tostring(err)` 与 `"…" .. err` 都是 `work.dig: out_of_reach — 原因` 加一行 `hint: …`(元表的 `__tostring`、`__concat`)。
- 参数错说是哪个参数、要什么样子、给了什么(`argument 'at': expected a Pos {x = …, y = …, z = …} …; got {x = 1, z = 3},
  which has no y`)。
- 库函数按种类处理,不吞错:`work.collect` 走不到的那件跳过(`no_path`),最后有剩的以 `no_path` 失败并在 `data.left` 里列出;
  `fight.clear` 跳过已经没了的(`not_found`);别的错原样抛出。

### 帮助

- `<api>` 索引:一句怎么用帮助,共用的类,然后一组一行(说明与函数名)。`api.help("组")`:`---@class 组`、每个函数一行
  `---@field 名字 fun(参数: 类型, opts?: {…}): 返回 说明`、`组 = {}`,再接这一组用到的类。`api.help("组.函数")`:完整的
  `---@param`/`---@return`、选项表与结果写成 `组.函数.opts`、`组.函数.result` 两个类、例子与说明。都由登记处与库的注释生成,
  没有手写的第二份。

## 九、模块统一成库,存在主人客户端(10-04)

### 五层

| 层 | 是什么 | 在哪 |
|---|---|---|
| ⓪ 身体控制 | 寻路执行、瞄准、换工具、追着转头、等冷却、憋气;模型看不见 | Java,任务与身体 |
| ① 原子 API | 对一个名词做一种意图;每刻控制在里面;过权限层;做的事报告给模型 | Java,登记处生成 Lua 函数 |
| ② 出厂模块 | 常见的组合(`numen.move.to`、`numen.move.flee`、`numen.move.explore`、`numen.work.mine`、`numen.build.raise`、`numen.inv.make`、`numen.shape` …) | Lua,随模组或插件发布,装进主人客户端的模块目录 |
| ③ 她的模块 | 她存下来或改过的函数表 | Lua,主人客户端 `config/numen/lua/<主人>/<名字空间>/<组>.lua` |
| ④ 这一轮的程序 | `lua` 工具的 `code` | Lua |

权限层只守在 ①;反射独立于各层,能打断程序。判据:**秒级的决策进 Lua;每刻的控制、重计算(寻路搜索、大范围扫描)、权限、
反射、名词的存取与校验留 Java。**

### 一种文件:模块

- 模块返回一张函数表(`local M = {} … function M.chop(t) … end … return M`),正文开头一行注释说它做什么,每个函数上面几行
  LuaLS 注释说它做什么、收什么、返回什么——`<api>` 索引与 `api.help("模块")` 都从这些注释生成,和第 ① 层的签名同一种写法。
- 程序里按名字直接用,**没有 `require`**:写了是 `no_function`,hint 是按名字用的写法;`<api>` 索引里写明"不需要也不能
  require"。第一次用到才装(沙箱全局表的 `__index`),每段程序一个新环境,所以热重载不用重启。模块名写错报"没有叫 X 的模块"
  并列出有哪些;一个模块读不通、跑出错、没返回表,只坏用到它的那一行。
- 和第 ① 层的组同名的模块(`move`、`work`……)给那一组加函数。
- 原来的内置脚本 `mine` 成了 `numen.work` 模块里的 `numen.work.mine(blocks)`(返回挖了几格),和 `numen.work.collect` 同组:
  "挖出这些方块"是 work 这个领域的组合,名字照组里动词的写法。"整段当程序跑、`...` 取参"删了。

### 存在哪、怎么升级、怎么还原

规矩只在 `api` 的 `Modules` 一处:

- **名字就是路径。** 主人客户端上一个目录 `config/numen/lua/<主人 UUID>/`,同一主人的同伴共用,主人能拿编辑器改。模块名两段
  `名字空间.组`,文件在 `<名字空间>/<组>.lua`:`numen/work.lua` 是 `numen.work`,`tlm/skin.lua` 是 `tlm.skin`,`my/lumber.lua`
  是 `my.lumber`——`my` 只是她习惯放自己模块的名字空间,没有别的特殊规则。程序运行时只读这个目录这一个来源。
- **出厂的只是安装包,升级照 dpkg 的 conffile。** core 与插件经 `NumenApi.bundleModules` 交来的目录(core 是 jar 里的 `modules/`,
  插件挨着它的技能放在 `plugins/<插件>/modules/`)第一次用到目录时装进对应的文件,账本 `modules.json` 记下每个文件上次装进去的
  出厂指纹:没改过的换成这一版出厂的;改过的留着她的、出厂变了就标"出厂有新版";删了的尊重删除、不再装回;她新建的撞上新出厂的
  同名一份当作改过;出厂不再发的,没改过的删掉、改过的留给她。`numen.module.reset(name)` 还原成这一版出厂的。
- `numen.module.save(code, {name = …})` 存(不写名字存成下一个空着的 `my.module_N`),`numen.module.delete(name)` 删,
  `numen.module.list()` 标出每一份是出厂的、改过的还是她的,`numen.module.show(name, {factory = true})` 看出厂原文。
- 存前和运行时同一个解释器装一次(`ScriptEngine.checkModule`):读不通、不返回表、给第 ① 层的名字赋值都拒,回执说哪一行、
  给 hint。主人拿编辑器绕过存直接改的文件,运行时同一条规则照样拦着。
- 改模块不问主人(权限层只管世界与身体);存、改、删都写进回执与客户端日志。
- 战绩(用到它的程序跑了几段、跑完几段、最近一次没跑完停在哪一行为什么)记在同一本账里;`numen.module.*` 都是客户端动作,
  外接大脑(MCP)同在客户端,走同一处。

### 第 ① 层不可覆盖

沙箱把宿主登记的全局函数、函数表与表里的每个宿主函数定死(`FixedKeysTable`):`function move.go() end`、`move = {}`、
`rawset(move, "go", f)` 都在那一行报错,种类 `runtime`,hint 是换个名字;往组里加别的名字照常。规则只在沙箱这一处,存前的检查
就是装它一次。

### 评测与 GameTest

评测每次运行、GameTest 每次启动,模块目录指向这一次专用的空目录,装进去的是没改过的出厂一套,不读主人目录里的改动。

## 十、API 第二版(10-04)

### 全名与名字空间

- 脚本里一律写全名:引擎与 core 的是 `numen.<组>.<函数>`(`numen.work.dig`),插件的是 `<模组 id>.<组>.<函数>`
  (`tlm.maid.task`、`kaleidoscope.pot.fill`)。名字空间由登记者定(`NumenPlugins.register(名字空间, …)`),路线、移动等组
  不改登记代码就落在 `numen.*` 下。模块按名字空间与组放(§九),和同名的组合在一起。
- 没有名词的增删改查。Lua 不留状态,世界就是状态:区域、设计、存下来的扫描结果都删了;要记住的东西只走 `numen.memory`。

### 值带方法(照 mineflayer)

- **Pos**(`numen.shape.pos(x, y, z)`):`p:offset(dx, dy, dz)`、`p:dist(q)`、`p + q`、`p - q`、`p == q`。
- **Block、Entity、Item**:带 `pos` 的数据;身体动作永远是 `numen.*` 的函数,收这些对象(`numen.work.dig(b)`、
  `numen.fight.attack(e)`)。
- **Cells**(`numen.shape.*` 画出来的):`rotate(quarters, origin)`、`shift(dx, dy, dz)`、`union(other)`、`minus(other)`。
- **Cluster**(`numen.scan.blocks` 交回的一团):`filter(keep)`、`minus(other)`。
- **Window**(`numen.use.block` 打开界面时交回、`numen.gui.view()` 读到的):`put(item, count?)`、`take(item, count?)`、
  `move(from, to, opts?)`、`quick(slot)`、`close()`,就是 `numen.gui` 的函数。
- **Recipe**(`numen.inv.recipes`、`numen.inv.craftable`):编号、工位、合出几件、每格的料;合成配方带最小的格和还缺什么。
- **蓝图句柄** `numen.build.blueprint(name, origin, {rotation})`:数据里有尺寸、格子、材料与缺什么;`numen.build.diff`、
  `numen.build.place` 与模块都收句柄或一串格。
- 查不到交 nil 或空表;失败抛 `{kind, message, hint, data}`。数打印成十进制原样,不出科学计数法。
- 方法写在类所在的模块里(`Pos`、`Cells` 在 `numen.shape`,`Cluster` 在 `numen.scan`,`Window` 在 `numen.gui`),登记的类用
  `methodsIn` 指过去;帮助里类的方法从模块的注释读出来。

### 原子与组合各归其位

| 删掉或改原子的 | 组合去了哪 |
|---|---|
| `build.new/drop/delete/show/designs/built`、原语 `set/place/line/layer/cylinder/sphere/copy` | 几何是模块 `numen.shape`;放格子是 `numen.build.place(cells)` 一遍 |
| `build.at` 放到放完 | `build.place` 一遍即止,反复是 `numen.build.raise` |
| `scan.blocks` 的 `into` 与区域 | `scan.blocks` 交回团,`work.mine(cluster.blocks)` |
| `use.block` 的左键、`use.gui/transfer/shift/close` | 左键一下 `use.hit`,挖是 `work.dig`;界面是 `numen.gui` 与 Window |
| `inv.craft` 找配方、找台、走开合关 | `inv.craft` 在开着的格里合一次,`numen.inv.make` 挑配方与工作台 |
| `inv.take`(创造取物) | `numen.creative.give` |
| `work.fish` 钓几条、常驻 | 一次调用抛一竿,钓几条是程序里调几次 |
| `fight.attack` 里的逃跑(DISENGAGE) | 逃跑本能 `FleeChain`(反射层):打不过且有东西在追就跑,跑不掉让出身体接着打 |
| 森罗 `kaleidoscope.pot.cook` 一口气做完 | 锅上的一步一个函数(`oil/base/fill/lid/stir/plate`),`kaleidoscope.pot.cook` 是模块;翻炒那段时间窗留在 Java |

新增的原子:`scan.sight`、`use.hit`、`gui.put/take`、`inv.recipes/items/count/craftable`、`gear.hold`、`time.wait`。

### 保护只有"玩家放的"

权限层不再认区域:出厂规则里要问的是"玩家放的"(`break(placed)`),主人要护一片地方,护的是他放下的方块。测试与评测原先靠区域
造的场景,改成由玩家放下方块。

## 十一、路线是一张描述,权限走到那一格才问(10-03)

照导航软件的样子:一个引擎,一趟路一张描述(去处与途经点、移动方式、偏好旋钮、避开),交出一份计划;没有备选路线。Lua 不存状态,
`route` 组只剩 `numen.route.plan`,`throwaway` 组整组删了。

### 描述:`numen.route.plan` 收的那张表

| 项 | 写法 | 不写时 |
|---|---|---|
| `to` | 终点:Pos、Block、Entity(规划时它在哪)、一团(`numen.scan.blocks` 交回的 Cluster)或一串格(Cells):其中任意一格、一列 `{x, z}`、一个高度 `{y}` | 用 `stops` 的最后一站作终点 |
| `arrive` | `at` 站进去;`near` 在 `range` 格内;`use` 看得见、够得着、能用;`dig` 手够得着(一团时一次够得着最多的),挡着的可以挖开;`place` 手够得着那一格、不站进去;`away` 离它至少 `range` 格 | `at` |
| `range` | `near` 多近算到、`away` 多远算到,1–64 | `near` 3,`away` 8 |
| `stops` | 终点之前的途经点 `{{to = …, type = "through" \| "stop", arrive = …, range = …}, …}`;`through` 路过不停,`stop` 先停稳 | 直接去终点 |
| `mode` | `walk` 走路;`boat` 坐在船上驾到离每一站最近的水格(每一站是 Pos 或一列,`at`/`near`) | `walk` |
| `costs` | 偏好旋钮 `{dig, place, consent, jump, swim, fall, parkour, max_changes}`,见下 | 不挖不放;许挖或许放时要问的格贵十倍 |
| `avoid` | 整个不进:格子种类名(`"water"`、`"door"`、`"climbable"`……)、一格(Pos 或 Block)、一个盒子(两个 Pos 写成一项)、一团(Cluster)或一串格(Cells) | 只避开出厂避开的(岩浆、危险、流水、机关、易碎) |
| `allow` | 放开出厂避开的几种:`flowing_water`、`trigger`、`fragile` | 照出厂避开 |
| `avoid_break` / `avoid_place` / `avoid_step` | 不挖 / 不往里放 / 不站上:方块 id 或 `#标签`,或一格、盒子、一团、一串格 | 不按名字禁 |
| `materials` | 这一趟可以垫掉的方块,好的在前(id 或 `#标签`) | 标签 `#numen:throwaway` 里的普通方块 |

`costs` 的每一项:`dig`、`place` 是 `false`(不许,默认)、`true`(许,原价)或一个数(许,每挖/放一格另加这么多);`consent` 是
`true`(许走要问主人的格,价钱乘 10,默认)、`false`(要问的格当墙,绕开)或一个不小于 1 的倍数;`jump`、`swim` 是每跳一下、每过一格
水另加的价;`fall` 是脚下没水时最多跳多高;`parkour` 许不许疾跑跳过 2–4 格的空隙;`max_changes` 是整趟最多改几格。读法与翻成寻路
规格(`RouteSpec`)只在 `core/route/Description` 一处。

权限不是描述的一项。规划时每一格自动问权限层(`GateTerrain`,与执行同一个 `Gate` 判定):拒绝的当墙,要问的照 `costs.consent`
算贵、列进计划的 `asks`,允许的照常。

### 计划:`numen.route.plan` 返回的那份

只规划不动,走不通也不抛(`ok = false` 加 `why`);只有参数写错才抛 `bad_argument`。计划只在这一段程序里有效(按程序记在她身上,
下一段程序 `numen.move.go` 它是 `not_found`)。

| 字段 | 是什么 |
|---|---|
| `ok` | 没有走不到的段,`numen.move.go` 走得了 |
| `why` | 走不通(或只看清一截)的那一段为什么 |
| `id` | 这份计划,`numen.move.go` 认它 |
| `spec` | 交进来的描述原样带回,改一项再 `numen.route.plan` 一次 |
| `from` | 从哪一格规划的 |
| `steps`、`seconds` | 看清的那部分一共几步、大约几秒 |
| `legs` | 一站一段,终点最后:`to`(这一站)、`reach`(`reached` / `partial` 只看清开头一截 / `unreachable` / `unplanned` 前一段没走到)、`finish`(停在哪一格)、`steps`、`seconds`、`path`、`breaks`、`places`、`asks`、`dives`(憋气潜过的水下)、`why` |
| `path` | 一步一项 `{pos, move}`,`move` 是 `walk`、`jump`、`fall`、`swim`、`climb`、`dig`、`place`、`sail`;取得到,不进回执、不进 `print` |
| `breaks`、`places` | 要挖、要放的 Block |
| `asks` | 其中要问主人的格:Block 加 `why` |

例子:

```lua
local plan = numen.route.plan({to = {x = 120, y = 64, z = -35}, costs = {dig = true, place = true}})
if not plan.ok then return plan.why end
print(plan.steps, #plan.legs[1].asks)
numen.move.go(plan)
```

```lua
-- 最近那块铁矿,先路过桥头,站到手够得着它的地方;不问主人,要问的格绕开
local ore = numen.scan.blocks("iron_ore", {radius = 16})[1].nearest
local plan = numen.route.plan({stops = {{to = {x = 10, y = 64, z = 5}, type = "through"}}, to = ore, arrive = "dig",
  costs = {dig = true, place = true, consent = false}, materials = {"minecraft:cobblestone"}})
```

### 走:`numen.move.go`、`numen.move.follow`、`numen.move.dismount`

- `numen.move.go(plan)`:照这份计划走,只改计划里列的格(承诺,`Plan.bind`);从当前位置重新规划,超出计划就不走、说多出哪几格。
  路上走到一格要问主人的,停在它跟前问(卡片上连同剩下的路里这一声答应同样放行的同种格):答应了接着走;拒绝了在那里失败,`kind = "denied"`,说为什么,`hint` 是把那一格加进
  `avoid` 再规划的那一行。走不通是 `no_path`。不出发前整条再裁决一次——什么时候问只看走到了哪儿。
- `numen.move.follow(entity?, {distance, seconds})`:跟着(不给就是主人),总有结束:`seconds` 不写是 60 秒。
- `numen.move.dismount()`:从坐着的东西上下来,当场返回 `{vehicle, pos}`。
- 坐船是描述里的 `mode = "boat"`,不另设动作。

### 库:`numen.move.lua`

- `numen.move.to(target, spec)`:`numen.route.plan` 加 `numen.move.go`,`spec` 是描述的其余几项;规划走不通时以 `no_path` 抛出,带 `why`。
- `numen.move.flee(from, {distance})`:离开一处(Pos、Block、Entity),就是 `arrive = "away"`,默认 8 格。
- `numen.move.explore(dir, {until_, seconds})`:朝一个方向(`"north"` 这样的方位或一个 Pos)一跳一跳地走,`until_` 是每跳之后问的
  函数(`until` 是 Lua 关键字),返回真就停;`seconds` 封顶。
- 模块不吞错:`numen.move.go` 的 `denied`、`no_path` 原样抛给程序。

### 删掉的

`route.new/via/drop/spec/show/list/delete/reverse`、`move.goto_`、`throwaway` 组(垫路料清单不再挂在她身上、不落盘,写在每一趟的
`materials` 里)、`alter`(拆成 `costs` 的 `dig`/`place`/`consent`)、路线存档(`Itinerary`、`Routes`)与路线标志的翻译
(`RouteFlags`、`RouteSpecFlags`、`RouteOps`)、`area.add` 的 `route` 选项、`Trip` 开走前整条规划并一次问完要问的格。

