# 命令行与脚本:原子命令 + Lua 脚本

状态:10-01 设计,10-03 改成只有 `lua` 一个工具(§七),10-04 模块统一成库、存在主人客户端(§九),同日 API 第二版:全名、
值带方法、无名词增删改查(§十);路线是一张描述、越界才问(§十一),两版合在一起(§十二);10-05 程序整段在服务端跑、客户端想服务端做(§十四)。总纲与五层见
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
| `route.plan` | 寻路:照一张描述(去处、途经点、旋钮、避开)只搜不走,交回计划(Plan),写明要改的格、要问谁(§十一) | 不动、不存 |
| `move.go` | 移动:照这一段程序里的计划走,只改承诺里的格,走到要问的格跟前才问 | 不挖目标、不捡 |
| `move.dismount` | 从坐着的东西上下来 | — |
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
| `move.to(target, spec)` | `route.plan`(描述的其余几项加 `to = target`)+ `move.go`;走不通抛 `no_path` |
| `move.flee(from, opts)` | `move.to` 的 `arrive = "away"`,离它至少 `distance` 格 |
| `move.explore(dir, opts)` | 一跳一跳 `route.plan` + `move.go`,每跳之后问 `until_`,返回真就停 |
| `work.collect(opts)` | `scan.entities("item")` + 一件件 `move.to` 走上去(原版玩家走过去就捡起) |
| `work.mine(cluster)` | 先走后挖:`move.to(cluster, {arrive = "dig", costs = …})` 走到够得着最多格的地方 → `work.dig(cluster)` 挖够得着的 → `work.collect` 捡,那一团挖完为止,返回挖了几格 |
| `fight.clear(radius)` | `scan.entities("hostile")` + 一只一只 `fight.attack` |
| `build.raise(building, opts)` | `build.diff` 问还差什么 → `build.place` 放够得着的 / `move.to … arrive "dig"` + `work.dig` 挖开挡路的 / `move.to … arrive "place"` 走到够得着最低最近那格的地方 |
| `inv.make(item, count)` | `inv.recipes` 挑一条料够的合成配方 → 2x2 在自己的格里、3x3 开工作台(开着的、16 格内走过去的、或把带着的放在身边)→ `inv.craft` 到够数 → 关上它开的台 |
| `inv.store` / `inv.fetch` / `inv.smelt` / `inv.give` | 走过去 `use.block` 开箱子(熔炉)、`Window:put`/`take`、关上;烧炼中间 `time.wait_until` 等烧完;给人是走到身边 `inv.drop` |
| `time.wait_until(ready, opts)` | 看一眼,不成就 `time.wait` 一会儿再看,超时抛 `timeout` |
| `shape.*` | 画格子:`box`、`line`、`cylinder`、`sphere`、`layer`,交回 Cells |

- **"挖"的到达**(`arrive = "dig"`)= 手够得着目标区域里任意一格,不要求看得见。同样划算的站位里,优先一次能够到
  最多目标格的(定价只在寻路模块 `Goals.dig` 一处)。**"放"的到达**(`arrive = "place"`)= 手够得着往那一格里放方块,
  不站进那一格(`Goals.place`,和 `Goals.dig` 同一套够得着的格,只多禁站进目标)。
- `work.dig` 挖完回执说:挖了几格、还剩几格够不着、下一步能照抄的 `move.to(…, {arrive = "dig"})`。给的是扫描交回的
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

- 模型在世界里做事的工具只有一个 `ScriptTool`(名字随语言,眼下是 `lua`):一段程序(参数 `code`),一次调用跑完,回一张回执。一次调用
  就是一行程序:`status.self()`。外接 MCP 服务器的工具照旧挂着(`RemoteMcpTool`),不在这一套里。
- 计划清单是另一个工具 `todo`(见 §七),不是函数;聊天里的计划清单从这次调用的参数画。
- 系统提示的 `<api>` 索引、工具说明、`api.help` 都从登记处生成;提示词与技能里的例子全是 Lua,防漂移测试经脚本的前端读一遍。
- 管理员的 `/numen drive <同伴> <程序>` 跑一段 Lua,和她的程序同一个入口(§十三);没有第二个前端。

### 命令函数:由登记处生成

每个登记了的动作是一个函数 `组.动作(对象..., {选项=值})`:同一份登记、同一个处理函数、同样过权限、照常记实际账、
受理即能跑。

- 按顺序的对象依次给位置参数,最后一个位置参数收下余下的全部对象;最后一个参数是一张表、而且它的键都是这个函数的选项名,
  它就是选项表(键里的 `_` 与 `-` 同一),否则它是一个对象(一个 Pos 也是一张表)。一处位置写成 Pos `{x = 120, y = 64,
  z = -35}`,一串值写成一张列表。值的样子、返回与错误见 §八。
- 换算只在 `Dispatcher.invocation` 一处:对象与选项按函数的参数表经各自的值转换读一遍(§十三),执行的一侧拿到的是读好的
  参数 record,**不拼命令行**。读不成(没有这个函数、对象多了、缺必填、选项名不对、值读不成)在调用处抛错误值,种类
  `bad_argument` 或 `no_function`,`hint` 是改好的那一行调用(看得出想写什么时)或怎么看帮助。读一组里没有的函数当场报错,
  附最像的那个名字。
- **成功返回值,失败抛错误值。** 占身体的函数等它的 task_finished 再返回,task_finished 带着那件活的值。返回什么由函数的
  返回类型说(§十三):`inv.count` 是一个整数,`scan.blocks` 是团的列表,`work.dig` 是 `{dug, left, out_of_reach, nearest,
  drops}`,`void` 的是 nil。身体做了什么的账只进回执,不进程序。失败抛的错误值 `pcall` 接得住,接住了就按 `err.kind` 分支。
- `print(...)` 写进回执的 stdout(至多 6000 字,超出的写明少了多少;表按 Lua 的写法印出来,大表缩略,见"回执")。
  `raise(kind, message, hint)` 以一个错误值失败,`error("why", 0)` 是程序自己的运行错(`runtime`)。
- **名字的改写只有一条**:组名或动作名撞上 Lua 的保留字(`goto`、`end`……)或沙箱自带的全局名(`string`、`table`、
  `print`……)的,后面加 `_`:`until_`。命令名本身不变,只是脚本里的写法;帮助里这样的动作多一行
  `In a script: 组.名字_(...).`,`lua` 工具的说明里也写了这一条。规则只在 `ScriptEngine.functionName` 一处,登记处的
  目录、回执里的函数名、帮助都从它来;沙箱不收撞名的宿主函数,登记时就抛出。

### 放在哪:`agent` 模块,程序在服务端跑

- 语言(`agent.script.ScriptEngine` 与实现)、一次运行(`ScriptRun`)、一次调用里的脚本(`ScriptCall`)、一段程序从头到回执
  (`Program`)、上限(`ScriptLimits`)是纯 JVM,放在 `agent` 模块;`lua` 模块由 `agent` 依赖;发行 jar 里和 `ai`、`agent` 一样平铺进引擎
  (`api-loader` 约定插件),许可随 jar 带 `LICENSE_numen-lua`。
- **程序整段在服务端跑**(§十四):客户端只送程序文本、她的模块清单与服务端还没有的模块正文,服务端在身体与数据旁边跑完,回一张
  回执。客户端的 `SerialCalls` 只管一批调用的顺序,不认识程序里的 API 调用。

### 运行:一个程序两条虚拟线程,服务端主线程不等脚本

- 每次运行一个新的虚拟机(`Globals`),跑在它自己的虚拟线程上;驱动它的是这段程序自己的执行体(`SerialExecutor`,同一时刻只跑
  一个任务、也是虚拟线程),它在两次调用之间等脚本算完(指令预算管着),**服务端主线程永远不等脚本**。模块用到时才从这段程序握着的
  清单里取正文(§九、§十四)。命令函数是宿主函数:收参数、把调用请求(`ScriptRun.Call`:行号、组、动作、对象、选项)交给驱动方,
  然后在原地阻塞等结局,拿到后从调用处接着跑;等结果、等身体干活时脚本线程停着不占平台线程。
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

打断由服务端负责,急不急只有一条规则(`EventQueue.isUrgent`,收件箱与程序共用同一份代码):

- 等某件身体活收尾时服务端发出了急件(她饿了、背包满了、主人挨打……):不再等,程序停,回执写 `an urgent hungry event arrived; t8 keeps running`
  (那件活照常跑)。一次调用在跑时来了急件:这一次的结果到了就停,停在调用之间。
- 主人说话、按停止、外接大脑接管、断线这类客户端那边的事,客户端上行一个"停止程序"(`StopProgramPayload`):说话与急件是"停在调用之间"
  (原因是 `your owner spoke`),切断(停止键、死亡、登出、接管)是"当场停下"(`this turn was cut off; t8 was stopped too` 或 `keeps running`,
  身体叫停与否照实写)。停止键叫停身体另发 `CancelTasksPayload`。
- 程序是被叫停的,服务端交回的结局里带**结构化的原因**(`Outcome.stoppedFor`):客户端据此给这一批里还没派的调用各写一条"没执行",
  不去读回执的文字。切断时客户端不等回执,这一批的调用作废、切断点由内核记下;服务端照常为被切断的程序生成回执并下发,客户端的工具口
（`CompanionToolPort`）认出它属于已作废的那一批,把它作为一条 `program_stopped` 事件放进她的收件箱——正文就是服务端写的那份有界回执
本身，写明切断前做了什么、停在哪。投递档是捎带（`AMBIENT`）：她没要求这份回执，但身体做了的事必须让她知道，随下一次调模型交出；不叫醒她——
切断本来是主人的决定，停止键之后停牌要等主人再开口，为这份回执多开一轮没有意义；也不进聊天流。主人断线导致的切断，回执无处可送，不下发。
- 程序被停下,它用到的每个模块也记一次战绩(没跑完、停在哪一行、为什么)。

### 回执:标准管道加结局

回执像一个进程的输出:一个结局,三条管道。逐次调用的流水没有了——此前每次 API 调用记一行("line 5 numen.inv.count: ok — 0"),循环里查
一百遍背包就是一百行,真机上一次 52 轮的会话里工具结果占去约 9 万词元,大半是这些行。标准环境里没有这种流水。

- **结局**(第一行,像退出状态):跑完是 `ok · 3 calls · 2 s`;出错写停在哪一行、错误值写出来的样子(种类、消息、`usage:`、`hint:`);被停下写停在
  哪一行、为什么。
- **stderr**:API 与运行时**主动报告**的事,写不写、写什么由各 API 自己定,像命令行工具自己往 stderr 写:身体做了什么(一件占身体的活的整段
  实际账——`NavText` 一处写成,`BodyDelta` 写出的身体变化与装备用坏、主人点头允许了什么、权限拦下了什么都在里面;一件短活的账)、一次
  查询没看全哪里,以及**每一次 API 调用的失败**——她用 `pcall` 接住了也写,同 Unix:失败的程序照样往 stderr 写,处不处理是调用方的事。
  每条带行号与函数名:`line 5 numen.fight.attack: killed minecraft:cow …`;失败的是 `line 5 numen.work.dig: out_of_reach — 原因`。**只返回值、
  没什么可报告的调用什么也不写**。连续相同的条合并成一条加 `(×N)`。程序等着收尾的身体活,它的账就是一条 stderr,这件活的收尾只在这里说,
  不另发事件;程序停下时还在跑的活,收尾才是一条 task_finished 事件。
- **returned**:`return` 的值。
- **stdout**:她 `print` 的字。

框架不判断哪个函数是只读的、哪个是动作:一个函数有没有话要说,是它自己的事(§十三)。

**工具结果只有文字**:回执是 `{success, message}`,`message` 就是交给模型的全部(成败在第一行),同 Bash 的输出。规则只在 `ToolOutcome.modelText` 一处,所有工具(`lua`、`skill`、`todo`、`memory`、远端 MCP 工具)和所有出口(对话历史变成请求的 `ProtocolView`、压缩估算、外接智能体经 `McpServer` 拿到的结果)都问它;不是信封的(技能正文)原样。对话历史里存的就是这个信封,界面据 `success` 画成败、从成功的 `todo` 画清单。**没有 `data`**:此前回执带一个 `data`(`status`、`calls`、`returned` 原值、`error`),模型会把它连同文字一起读进去(`return` 一个大值时原值要读两遍),产品里却没有任何代码读它。现在结构化的结局只在程序结果那一层(`Program.Outcome`):`ending`(`status` ok / error / stopped,出错时错误值的种类)、每次调用的记录(`ScriptCall.Called`,评测按函数统计和调试日志读)、用到的模块、`stopped_for`,这些上网线(`RunResult`);程序 `return` 的值原样与完整的错误值(`returned`、`failure`)**不上网线**,只在服务端进程里的结局对象上,同进程的 GameTest 与 `ApiTester` 经 `CallObserver.ended` 读。评测的 `Meter` 经循环事件 `ProgramEnded` 读 `ending` 与调用数。历史文件格式不变:新写的工具结果就是 `{success, message}`;旧档里带 `data` 的程序结果照旧读得进,送给模型时同样只剩 `message`。

**有界,缩略从不静默**(数值都在 `ScriptLimits`):stderr 一条至多 `STDERR_RECORD_CHARS`,整栏至多 `STDERR_CHARS`,超出的写明还有多少字、
多少条没显示;stdout 至多 `PRINTED_CHARS`,超出的写明少了多少字。`print`、`return`、错误值里出现的表都经同一个渲染器
(`ScriptEngine.display`,`LuaDisplay`),判据只有这一处,照 NumPy 的 printoptions(`threshold`、`edgeitems`)与 pandas 的 `max_rows`:
列表或表超过 `DISPLAY_THRESHOLD` 项只显示首尾各 `DISPLAY_EDGE_ITEMS` 项,中间写 `…(950 items in all; index one with t[i], or filter in the
program before you print)`;嵌套超过 `DISPLAY_DEPTH` 层的写 `{...}`(末尾补一句怎么看里面);表里一段超过 `DISPLAY_STRING_CHARS` 字的文字留开头并写明
总长。小的值原样,和 `ScriptEngine.value`(提示里"改好的那一行"用的精确写法)一样。例:

```
The script stopped at line 3 after 2 calls: work.dig: bad_argument — argument 'place': a position is one table with named fields; got {120, 12, -35}
usage: work.dig(place..., {count=…})
hint: work.dig({x = 120, y = 12, z = -35})
stderr:
line 3 work.dig: bad_argument — argument 'place': a position is one table with named fields; got {120, 12, -35}
```

```
ok · 3 calls · 12 s
stderr:
line 2 numen.move.go: walked to {x = 120, y = 64, z = -35} … broke 2 minecraft:stone on the way
line 3 numen.work.dig: dug 4 minecraft:iron_ore …
returned: 4
```

```
The script stopped at line 1 (move.go) after 2 calls: your owner spoke; t12 keeps running. Nothing after that ran.
```

(停在第 1 行时那件活还没收尾,回执里没有它的 stderr 条目。)
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
- 外接大脑(MCP)当时直接调工具、没有派发器;10-03 起它的 `lua` 调用经她自己的派发器跑(§七)。

## 七、只有 lua 一个工具,API 原子化(10-03)

- **一个入口**:模型在世界里做事的工具只剩 `lua`;`command` 与各组的快捷工具删了。Lua 的对象与选项
  直接按参数类型读成值交给处理函数,不经命令行字符串。外接大脑(MCP)的 `lua` 调用交给她自己的派发器跑
  (`AgentLoop.runAside`):同一套等收尾、上限、回执;程序跑着的时候到达的事件转给它,不另起一轮对话。
- **原子化**:`build.at` 只放站在原地够得着的格,一格都够不着就当场拒并说怎么走过去;施工绕外圈、清场(`ClearSiteTask`)、
  垫块记账(`DropTracker`)都删了。走与挖由 `build.raise` 这段库函数组合。新增 `build.left`(查询)与到达方式 `reach`。
  `work.collect` 变成库函数(扫掉落物、走上去),新增掉落物的 `pickup_delay`;`fight.attack` 只收一只,"打一片"是
  `fight.clear`。
- **回执**:结局一行,再是 stderr、返回值与 stdout 三栏(§四"回执");出错带行号与那次调用的 `error:`/`usage:`/`hint:`。
- **技能是另一个工具,不是 API**(10-04):照 Claude Code 的 Skill 工具,`skill` 工具收技能名(附属文件 `file`、页码 `page`
  可选),技能正文就是这次调用的结果;系统提示的 `<available_skills>` 是索引。`numen.skill.load` 删了:程序回执里每次调用只露
  结果的第一行,正文没 `return` 出来她就读不到,真机上她连着三轮在程序里装同一份技能。外接大脑调的是同一个工具。
- **计划也是另一个工具,不是 API**(10-04):判据是工具只放管大脑自己的事、不碰世界的(技能、计划),作用于世界的一律是
  Lua API。照 Claude Code 的 TodoWrite,`todo` 工具收整份计划(`items`,一项一个带记号的字符串),每次替换上一份,主人客户端
  当场答;聊天里的清单从成功的这次调用的参数读(`PlanChecklist`,一项的写法只在 `TodoTool.Item.parse`)。`numen.todo.write`
  与只为它存在的回执回显(`data.echoed`)删了。模型的工具表是 `[lua, skill, todo, memory]`,外接大脑经 `ToolRegistry` 拿到同一套。
- **札记也是另一个工具,不是 API**(10-04):同一判据,札记本落在主人客户端、管她自己的事。照 Anthropic 的 memory 工具,一个
  `memory` 工具带 `command`(`remember`、`recall`、`forget`),三件事作用于同一本札记、参数大半共用,不拆成三个工具。札记索引照旧
  每轮作为 `<memory>` 注入。`numen.memory.*` 删了。
- **测试**:GameTest 全部从 Lua 入口调(`GameTestKit.lua`);库函数各有端到端的 GameTest(`mine`、`work.collect`、
  `fight.clear`、`build.raise`),到达方式 `reach`、`build.left`、够不着时 `build.at` 的拒绝各有一条。

## 八、值、错误与帮助一个样子(10-03)

照主流的写法统一:位置是一种值,查询结果原样交给动作,API 返回数据,失败是带种类的错误值,帮助是从登记处生成的类型签名。

| 借的 | 出处 | 我们的落点 |
|---|---|---|
| 位置是一种带名字字段的值,方块带着它的 `position`,`bot.dig(block)` 收方块本身 | mineflayer 的 `Vec3`、`Block.position`、`bot.dig` | `Pos {x, y, z}`(`LuaCodecs.POS`),Block/Entity/Item 都带 `pos`;收位置的参数收任何带 `pos` 的表(`Positions.cell`、`Place` 的读法) |
| 查到的东西直接交给下一步,在代码里筛,不让模型抄数 | Anthropic《Code execution with MCP》、Cloudflare Code Mode | `scan.blocks(...).groups[1].nearest` → `work.dig`;`scan.entities()[1]` → `fight.attack`、`move.to`;`route.plan(...)` 的计划 → `move.go` |
| 函数签名写成类型,模型照签名写代码;只给一个写代码的工具 | Cloudflare Code Mode(TS 接口)、smolagents `CodeAgent`(带类型的函数签名) | `api.help` 给 LuaLS 注释:`---@class 组` 加每个函数一行 `---@field f fun(…): 返回`(`LuaEngine.groupText`、`functionText`) |
| 先看索引,要用哪组再展开 | Anthropic 的渐进披露(`search_tools`) | 系统提示只放 `<api>` 索引(组名、一句话、函数名与共用的类);`api.help("组")` 展开一组,`api.help("组.函数")` 展开一个 |
| 失败是一个值,带能拿来改正的信息,程序自己接住再改 | CodeAct(报错回给代码自纠)、Voyager 技能库抛 `Error` 再由技能接住 | 错误值 `{kind, message, hint, fn, data}`,`pcall` 接住按 `err.kind` 分支;库函数 `raise(kind, …)` |
| 类型注解的写法 | LuaLS(`---@class`、`---@field`、`---@param`、`---@return`) | 登记处的 `ScriptType` 与库函数自己的注释,同一种写法 |

### 值

- **位置只有一种写法**:一格是 Pos `{x = 120, y = 64, z = -35}`,一列是 `{x = …, z = …}`,一个高度是 `{y = …}`。小数按所在的
  那一格算(`BlockPos.containing`),所以 `status.self().pos` 原样能交。任何带 `pos` 字段的表(Block、Entity、Item、一团的
  `nearest`)放在位置那一格就是它的 `pos`;收实体的参数收编号或带 `id` 的表。读法只在 `Positions` 一处。
- **旧写法删了**:`{120, 64, -35}`、`"120 64 -35"`、`"120,64,-35"` 都是 `bad_argument`,`hint` 是改好的那一整行调用;
  只给了一列却要一格,说缺 y。删的理由:同一处位置三种写法,模型在三种之间来回猜,查询结果也交不进动作。
- 共用的类在 `LuaCodecs`:`Pos`、`Block`、`Entity`、`Item`(继承 Entity)、`Cells`、`Error`;各组自己的类(`Plan`、`Leg`、
  `Step`、`Ask`、`Stop`、`Costs`、`Placed`、`Window`、`tlm.Maid`……)从函数签名里的 record 来(§十三)。

### 返回

- 返回的类型就是函数的返回类型(§十三)。值给程序;身体做了什么的账(活的收尾、短活与主人点头)只进回执与 task_finished,两样从
  同一份事实写出。
- 只读不跑一段正文时(帮助里的例子、技能里的写法),调用按返回类型造一个样子返回(`ScriptType.sample`),取字段、取第一项、
  循环的写法都读得通;所以例子写错一个字段名,lint 报告(`ApiTester.lint`)就指出来。

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
- 没有名词的增删改查。Lua 不留状态,世界就是状态:区域、设计、存下来的扫描结果都删了;要记住的东西只走 memory 工具。

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
| `scan.blocks` 的 `into` 与区域 | `scan.blocks` 交回团,`work.mine(cluster)` 先走后挖(§十二) |
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
规格(`RouteSpec`)只在 `core/route/Description` 一处;一格、一团(Cluster)、一串格(Cells)的读法借 `Positions.cells`,
和动作收一串格的参数(`@Rest`,经 `Positions.items`:一项是一团就展开成它的每一格)同一处。

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

## 十二、两版合在一起(10-04)

§十(全名、值带方法、删名词)与 §十一(路线描述、计划、越界才问)合到同一条线上:命名空间、各组的形状、模块目录、删区域与设计
按 §十;路线、移动、垫路料、寻路、征询卡片、计划与描述按 §十一。两边都碰到的几处:

- **一团与一串格进描述**:`to`、`avoid`、`avoid_break/place/step` 收扫描交回的 Cluster 与 `numen.shape` 画的 Cells,读法见上;
  §十一里"带 `cells` 的表"那种写法没有了。两个 Pos 写成一项仍是一个盒子(整片交给寻路、不逐格展开),两个 Block 是两格。
- **`numen.work.mine(cluster)` 先走后挖**:`numen.move.to(cluster, {arrive = "dig", costs = {dig, place, consent = false}})`
  走到够得着那一团最多格的地方,`numen.work.dig(cluster)` 挖手边的,`numen.work.collect({items = r.drops})` 捡这一挖还落在地上
  的,一轮一轮直到那一团不剩(`left == 0`);一轮一格也没挖到抛 `failed`。`numen.work.dig` 收一团就是收它的每一格。GameTest 的挖矿
  辅助(`GameTestKit.mine`)与评测 `mine_iron` 的标准解是同一个流程拆成原子调用。
- **挖的结果写明掉落物去了哪**(10-04):真机上她挖了岩浆上方的黑曜石,掉落物掉进岩浆烧掉,没有一句话告诉她。`numen.work.dig`
  收工前等这一挖的掉落物落定,最多 40 刻(2 秒:从被挖那一格弹起再落到底下约 10 刻、在地上滑停约 7 刻,岩浆两刻、火与仙人掌五刻
  毁掉一件,四十刻自由落体能掉二十来格;漂在水里的永远不着地,到上限就照此刻在哪说)。认领在生成那一刻:她的手挖掉一格
  (`ServerPlayerGameMode.destroyBlock`)那一段里进世界的掉落物记进这一挖的账,连带碎掉的(火把、箱子里的东西)也算;去向只在
  `core/act/Drops` 一处判——落地(原版判"不在动"的那条线)、被谁捡起(`LivingEntity.take`)、被什么毁掉(`ItemEntity.hurt`)、
  掉进虚空、被别的收走、到上限还在动;并堆跟着件数走。落定之后的事不归它。回执那一句(`Drops: 1 obsidian fell into lava and
  burned up at {x = …}`)与数据 `drops`(一笔一项 `{item, count, fate, pos, by?, cause?, id?}`)出自同一份记录。
  `numen.work.collect({items = …})` 只追给它的那几件(按 `id`),`numen.work.mine` 把挖的 `drops` 交给它,不再挖完另扫一遍。
- `numen.inv.*`、`numen.build.raise`、`numen.work.collect` 等模块里的走路一律是 `numen.move.to`,`alter` 换成
  `costs = {dig = true, place = true, consent = false}`(只改自然地形,要问的格绕开),`near` 换成 `range`、`arrive = "reach"`
  换成 `"place"`;`throwaway` 清单删了,垫路料用每一趟的 `materials`(不写是标签 `#numen:throwaway`)。
- **登记不合规矩当场抛出**:`NumenPlugins.register` 不再接住插件登记块里的异常记一行日志——那样一组会悄悄缺席(例子读不通、
  没声明返回……),模型看到的 API 少了一块却没人知道。现在异常原样抛出、启动失败,那句话写着名字空间、组、动作与哪条规矩。

## 十三、API 登记:签名就是契约(10-04)

照主流的写法(Python 的类型注解生成工具 schema、Cloudflare Code Mode 的 TS 接口、Spring 的注解方法):**一个函数就是一个带 `@Fn`
的静态方法,它的签名就是契约**——名字、在哪执行、怎么交回、参数(位置与选项)、返回的类型、帮助,全从签名读出来,不另写一份。命令行
前端(Brigadier 树、把一行字读成调用)删了,脚本是唯一的入口;Numen 自己的各组与每个插件走同一扇门
(`NumenPlugins.register(名字空间, numen -> numen.api(组, 一句话, XxxApi.class))`)。包在 `api` 的 `com.dwinovo.numen.sdk`。

### 写法

```java
public final class LocateApi {                         // 一组:一个公开类,每个 @Fn 静态方法一个函数

    public static void install(NumenApi numen) {
        numen.api("locate", "Find the nearest structure or biome in the dimension you are in.", LocateApi.class);
    }

    /** 参数是一个 record:组件就是参数。 */
    public record Biome(@Doc("Biome id or #tag ...") String biome) {}

    @Fn("Find the nearest biome of a type: its column, compass direction and distance.")
    @Example("numen.locate.biome(\"minecraft:warped_forest\")")
    @Note("Searches YOUR CURRENT dimension only ...")
    @SeeAlso({"numen.locate.structure", "numen.scan.entities"})
    public static Pending<Located> biome(ServerCall call, Biome args) { ... }
}
```

- **第一个参数说在哪执行**:`ServerCall`(服务端:身体、世界)或 `ClientCall`(主人客户端:只有那里才有的数据,比如模型包、任务书)。
- **第二个参数是一个 record**,组件就是参数:名字从驼峰换成下划线(`maxCount` 是 `max_count`);不是 `Optional` 的按顺序是位置参数,
  `Optional` 的进最后的选项表;`@Positional` 标一个可以不写的位置参数(只能在位置参数最后),`@Rest` 标收下余下全部对象的那一个
  (`f(a, b)` 与 `f({a, b})` 一样,一项是扫描交回的一团时展开成它的每一格)。说明写 `@Doc`,省略时的意思写 `@Omitted("…")`。
  不收参数的函数只有调用这一个参数。
- **返回类型说怎么交回**:`R` 当场;`Pending<R>` 等一会儿(主人点头、按刻分片的搜索、一件有界短身体活 `call.sync(record)`),
  不占任务槽;`Job<R>` 占身体、进任务槽,受理回活的编号,程序等它的 task_finished;要的样子此刻已经是了(`build.place` 的格都对了)
  是 `Job.done(值)`,不派活。`R` 是 record、枚举、`List`、`Map<String, T>`、Minecraft 的值(`BlockPos`、`Item`、`Block`……)或
  `void`;只有一个字段的结果直接交那个值(`inv.count` 是整数、`work.fish` 是一串字)。
- **客户端函数的约定**(指导,登记时不检查;好写法由评测的分数说话):① 只读查询、不产生副作用——主人的客户端在
  `ProgramLimits.CLIENT_ANSWER_TICKS` 内没答复,这次调用以 `timeout` 失败,失败或超时后原样再调一次必须安全;② 客户端的答复不可信,
  权限层与任何裁决不读客户端函数的返回值;③ 只是通知主人的事走单向事件(`NumenApi.emit`,服务端发 `NumenEventPayload`),不走反向请求;
  ④ 一次收一批键、在客户端就地过滤只回小结果,别在循环里逐条问(N+1)。`ClientCall` 的 Javadoc 是这条的全文。
- **失败只抛 `ApiError(kind, message, hint, data)`**;`hint` 是能照抄的下一行程序,用 `Call.of("numen.move.to", pos,
  Map.of("arrive", "dig"))` 写,不手拼。查不到不是失败:交回空表或 nil。函数说参数此刻不成立抛 `IllegalArgumentException`,和读不成
  同一种 `bad_argument`(附用法与帮助的写法)。
- **值转换**(`LuaCodecs`):record 是一张表(字段说明来自 `@Doc`,`@Folded` 的字段收起来,`@Flatten` 的第一个组件摊进这张表并成为
  父类,`@Methods("numen.scan")` 说值带的方法写在哪个模块),枚举是小写的名字,内置 `Pos`、`Block`、`Entity`、`Item`、`EntityRef`、
  `Place`、`Target`……;一种自己的类型登记一次 `numen.codec(X.class, codec)`(读、写与签名里写成什么在同一个对象上)。类名:引擎自己的
  不带前缀(`Plan`、`Window`),插件的带名字空间(`tlm.Maid`)。
- **够不着、用一只实体、过权限是原语**:`call.entity(ref)` 认出点名的实体(不在了是 `not_found`),`call.reach(entity, Reach.hand())`
  量够不够得着(够不着是 `out_of_reach`,`hint` 是走过去的那一行),`call.use(entity, reach, deed)` 是这三样加上权限层的
  `use_entity`,放行之后按同一个编号再认一次、再量一次才做 `deed`;`call.authorize(action)` 直接问权限层。许不许只在原语执行的那一刻
  由权限层裁决,`@Fn` 上没有任何许可字段。模组的管理指令经 `call.onHer()` 以服务器的权威、只对她执行。
- **身体做了什么都要说,往 stderr 说**:API 作者"报告一句"只有一个入口——`Pending.report(words)`(一件短活的实际账、主人点头允许了什么、一次
  扫描没看全哪里),随等到的值交回,写进程序回执的 stderr 一栏;占身体的活(`Job`)的账是任务收尾的那段话,同样写进 stderr。约定:**返回值
  是给程序用的;stderr 是告诉她身体做了什么、出了什么事**。只读的查询通常什么都不报告(查到的东西在值里),没看全才说;动了身体、改了
  世界的函数说它做了什么。框架不替函数判断自己是只读还是动作,要说什么由函数自己定。失败不用报告:抛 `ApiError`,运行时替它写进 stderr。

### 只拦会破坏系统的

登记时(`Binder`、`ApiRegistry`、`LuaCodecs`)硬错误只有这几条,抛 `IllegalArgumentException`,说是哪个函数、哪一条,启动失败:

- 名字写不出来:不是 `[a-z][a-z0-9_]*`,或撞上 Lua 的关键字(`end`)与沙箱自带的全局(名字空间 `string`);
- 组已经有主,或往别人的名字空间里加;
- 签名绑定不了:不是 `public static`、第一个参数不是 `ServerCall`/`ClientCall`、第二个参数不是 record、主人客户端的函数不当场返回;
- 一个参数或返回值的类型没有值转换;
- 可以不写的位置参数不在最后,`@Rest` 不在最后或不是 `List`;
- 两个类型争同一个类名。

写法上的问题不拦,交给 `ApiTester.lint()` 的报告:没写说明、参数没写 `@Doc`、没写例子、例子读不通或没调到自己或参数读不成、
`@SeeAlso` 指的函数不存在、随模组发的模块开头没写说明或函数上面没写注释;`ApiTester.lint(texts)` 读技能与提示词里写着的调用。core 与
各插件的单测各写一份报告到 `build/reports/api-lint.txt`(不让构建失败),core 的那份旁边还有整份 API 的 LuaLS 存根 `numen-api.lua`
与机器可读的元数据 `numen-api.json`。好写法好不好用,由评测的分数说话(见下)。

### 组成

| 部件 | 做什么 |
|---|---|
| `Binder` | 从签名推出 `ApiFunction`(参数、种类、返回的值转换、例子、注意、相关),只抛上面那几条硬错误 |
| `ApiRegistry` | 登记表:组、函数、名字空间;给脚本的目录(`ScriptCatalog`) |
| `LuaCodecs` / `Codec` | 值转换,连同签名里写成什么;record、枚举、列表、表现造,别的登记 |
| `Dispatcher` | 一次调用:脚本里的调用读成参数(读不成当场 `bad_argument`)、服务端或客户端执行、按种类交回(`ApiReply`)、受理活并记下重启后再跑的那一行 |
| `ApiDocs` | `<api>` 索引、`numen.api.help`、用法、LuaLS 存根、元数据,全从登记表生成 |
| `ApiTester` | 进程里跑一段程序看回执(`run`)、lint 报告 |
| `program.ServerPrograms` | 服务端跑程序的唯一入口(§十四):客户端送来的程序、`/numen drive`、重启后再跑、GameTest、评测与单测都经它 |

线上的样子只在 `agent` 的 `ApiReply` 一处:`{"ok": true, "value": …}`、`{"ok": true, "job": "t12"}`、
`{"ok": false, "error": {kind, message, hint, data}}`,等到的值另带 `stderr`(API 报告的话)。

### 三个例子

**查询**(当场交回,不碰世界):

```java
public record Located(boolean found, Optional<Place> pos, Optional<String> direction,
                      Optional<Integer> horizontalDistance, Optional<Integer> searched, String dimension) {}

@Fn("Find the nearest biome of a type: its column, compass direction and distance.")
@Example("numen.locate.biome(\"#minecraft:is_forest\")")
public static Pending<Located> biome(ServerCall call, Biome args) {
    return call.sync(new LocateBiomeTaskRecord(call, deadline(call), args.biome()));   // 按刻分片的搜索
}
```

**占身体的活**(进任务槽,程序等它收尾;重启后再跑的那一行写目标的 UUID,不写只在这一次开服有效的运行期编号):

```java
public record Attack(@Doc("The entity to fight: an Entity from numen.scan.entities, or its id.") EntityRef entity) {}

@Fn("Attack one entity until it is dead, lost or out of reach.")
@Example("numen.fight.attack(184)")
public static Job<Fought> attack(ServerCall call, Attack args) {
    Entity target = call.entity(args.entity());
    AttackTaskRecord record = new AttackTaskRecord(call.fn(), call.callId(), ..., List.of(target.getId()), false);
    return Job.<Fought>of(record).replayedAs(new Attack(EntityRef.of(target)));
}
```

**插件的函数**(车万女仆:用一只女仆是 `call.use`,判据留在车万女仆自己的包里,调完读回):

```java
NumenPlugins.register("tlm", numen -> numen.api("maid", "Touhou Little Maid: the maids you keep.", MaidApi.class));

@Fn("Switch one of your maids to another work mode, like a click in her task list.")
@Example("tlm.maid.task(\"touhou_little_maid:farm\", {maid = 812})")
public static Pending<Switched> task(ServerCall call, Task args) {
    Entity maid = args.maid().isPresent() ? maid(call, args.maid().get()) : yoursWithinReach(call);
    return call.use(maid, GUI, still -> {
        Maids.switchTask(call.her(), still, args.task());
        String now = Maids.task(still);
        if (!now.equals(args.task().toString())) {
            throw refused(call.her(), still, args.task(), "...", new Switched(still.getId(), now));
        }
        return new Switched(still.getId(), now);
    });
}
```

脚本里:`tlm.maid.task("touhou_little_maid:farm", {maid = m})` 交回 `{maid = 812, task = "touhou_little_maid:farm"}`;离得远是
`out_of_reach`,`err.hint` 是 `numen.move.to({x = …}, {arrive = "near", range = 2})`。

### 重启后接着做、`/numen drive`

- 受理一件活时,把这次调用写成一行 Lua 记下(`Call.of`,活换过参数的用 `Job.replayedAs` 那一份);重启后经 `ServerPrograms` 再跑这一行,
  和她写的程序同一个入口。没受理(读不通、当场失败、准备没过)就是没接回来,她收到一条 task_finished 说为什么。**旧存档不兼容**:旧版本
  按一行命令记下的活读进来只为说清它没接回来(日志与 task_finished 都说这一版不再跑它),不做转接。
- `/numen drive <同伴> <程序>`(OP)是一段 Lua 程序,经同一个入口跑,整张回执说给发令人。`/numen permission …`、`/numen consent …`
  照旧。
- `/help` 那一套挖 Brigadier 的帮助只留给 `numen.mc.run("help give")`:原版与模组的指令在原版的指令树上解析、执行,写不通附用法与最接近
  的候选。

### 评测按函数统计

程序里的每次 API 调用有了结局(成了、哪种失败,参数读不成的也算一次)都随回执从服务端送回(`Program.Outcome.calls`,每次调用只带写成的
文字——长过 `ScriptLimits.CALL_TEXT_CHARS` 的留头部加摘要——第一个文字对象与失败的种类),经 `ScriptCall.Called` → `ToolPort.Sink.called` →
`LoopEvent.ApiCalled` 报给循环;评测的 `Meter` 按函数记:调了几次、失败的各是哪一种(尤其 `bad_argument`)、第一次调它之前 `numen.api.help`
查过几次它(或它的组、名字空间)、和之前一字不差又失败了几次。每次运行写进 `runs.jsonl` 的 `functions`;`summary.md` 有"每个函数"
一张表,`Compare` 把前后两份按函数并排(调用、失败率、参数错率、查帮助/调用)。插件作者给自己的函数打分:用 `Bench.suite` 登记自己的
场景,看这张表。

## 十四、程序整段在服务端跑,客户端想、服务端做(10-05)

此前模型写的 Lua 在主人客户端跑,程序里每调一次服务端函数就经网络往返一次(一轮评测 36 次运行共 4469 次调用,98% 是服务端函数)。
现在倒过来:**程序整段在服务端、身体与数据旁边执行**,客户端只把"程序文本 + 她的模块清单 + 这个连接还没送过的模块正文"发上去,
服务端跑完回一份回执。函数仍然两端都有(`ServerCall` 在服务端、`ClientCall` 在主人客户端,按第一个参数定端):程序在服务端遇到
服务端函数就进程内执行,遇到客户端函数就向主人客户端发**反向请求**,Lua 线程挂着等客户端用 `Dispatcher.client` 执行完答回来
(同 MCP 的 sampling、LSP 的 workspace/configuration);对 API 作者完全透明。

### 部件(`api/common/.../program` 与 `agent.script`)

| 部件 | 做什么 |
|---|---|
| `Program`(agent) | 一段程序从头到回执,状态只在自己的执行体(`SerialExecutor`)上;派调用、等结果、等活收尾、急件与叫停 |
| `ServerPrograms` | 唯一入口:登记在跑的程序(每只同伴一段,每位主人 8 段、全服 64 段,`ProgramLimits`)、准入、把服务端事件交给程序、停止、主人断线与关服清理 |
| `ProgramCalls`:`ServerCalls` / `ClientCalls` / `RoutedCalls` / `ObservedCalls` | 一次调用在哪一端执行:进程内排进主线程车道、向客户端发反向请求、按函数的端分流、套一层给 GameTest 看每次调用;分流的逻辑只在 `RoutedCalls` |
| `MainQueue` | 程序的服务端调用排队等主线程。每段程序一条车道,每刻开头 `ServerPrograms.tick()` 在预算里取:全部 10 ms、每段 5 ms(取 CC: Tweaked 的 `max_main_global_time` / `max_main_computer_time`),起点每刻轮一段;主线程只执行,从不等程序 |
| `ClientTransport`:`NetworkTransport` / `LoopbackTransport` | 反向请求的传输端口。产品是网络;没有真客户端的地方(GameTest、单测)是同样过一遍包编解码的回环 |
| `ModuleSet` / `ModuleSync` / `ModuleCache` / `RunModules` | 她的模块:清单(名字 → `Modules.fingerprint`)加没送过的正文。客户端 `ModuleSync` 记这个连接送过哪些;服务端 `ModuleCache` 按主人按指纹缓存(每位主人 4 MB,最久没用的先丢,主人断线清掉,不落盘);`RunModules` 是一段程序握着的那份 |
| `ProgramUplink` / `ClientEndpoint` | 客户端:送程序、停止、收回执(服务端说缺正文就带上重发)、记模块战绩;替服务端执行客户端函数并答回 |
| `LoopbackClient` | 在本进程里扮主人客户端(GameTest、评测之外的单测),用的就是上面那些部件 |

### 包

`RunProgramPayload`(上行:程序编号、程序、`ModuleSet`)→ `ProgramResultPayload`(下行:一段 JSON,要么 `{missing:[指纹]}`,要么 `{receipt, status, error_kind, calls, used, stopped_for}`,回执只有成败与文字,`return` 的值原样不在里面);
`StopProgramPayload`(上行:停在调用之间或当场停下);`ClientCallPayload` / `ClientCallResultPayload`(反向请求与它的答复,答复里在函数改了
她的模块时带上新清单与新正文)。单包上限上行 32767 字节、下行 1 MB,超过的程序、反向请求与答复在载荷层分片,对端拼回(整条消息至多 8 MiB,`Wire` 与 `Fragments`,见 `docs/cli.md` 附录 H)。

### 反向请求有时限,程序结束即撤销

主人的客户端连着却一直不答(客户端函数卡住、客户端被改过),程序不能因此挂到被切断:每个反向请求从发出起有 `ProgramLimits.CLIENT_ANSWER_TICKS`
(600 刻,三十秒)的期限,在服务端主线程上按刻查(`ServerPrograms.tick` → `ClientCalls.expire`),不新开线程、不阻塞定时等待。
到期这次调用以 `timeout` 失败交回程序(`pcall` 接得住,程序往下走),`hint` 是同一行调用,同时传输撤掉它那头记的这笔
(`ClientTransport.cancel`),之后才到的答复没有人认。三十秒的取法:客户端函数是就地只读查询,正常毫秒到几百毫秒内答复,三十秒是百倍以上,容得下
卡顿、加载与垃圾回收;又远小于程序墙钟上限(二十分钟)与可能长时间运行的工具的量级(Anthropic 程序化工具调用约四分钟)。按刻数而不按墙钟,服务器自己
卡住的那些刻不算客户端迟。主流的要求一致:Roblox `RemoteFunction:InvokeClient` 官方警告客户端不答服务端就永远挂着;MCP 规范要求为所有发出的请求设
超时并设最大值。

一段程序以任何方式结束(跑完、出错、打断、切断、主人断线)都经 `ServerPrograms.run` 里 `Program` 的结局回调这一处,在那里 `ClientCalls.close`:
它在等的反向请求全部撤掉、不再交回,排着还没发的也不发。

### 她的模块:真源只有一个

真源仍是主人客户端 `config/numen/lua/<主人>/` 里的文件。服务端缓存只是内容的副本(指纹就是内容的名字,存入时核对),一段程序开跑时
就把清单里每个指纹的正文握在手里。程序里 `numen.module.save/delete/reset` 是客户端函数:`Modules.revision()` 变了,说明这次调用改了文件,
答复里带上客户端此刻的全清单和新正文,服务端先换进这段程序的 `RunModules` 再让程序往下走,所以同一段程序往后用到的就是新的;
已经装进虚拟机的那份不变。服务端缺某些正文(缓存丢了)时回 `Missing`,客户端清掉"已送过"、带上重发一次。

### 回执:给模型读的,按构造有界

回执在生成它的地方就有界(`ScriptLimits`):stderr 一条至多 `STDERR_RECORD_CHARS`(超出的整行省略并写明还有多少字)、整栏至多 `STDERR_CHARS`(超出的写明
另外几条、几个字),`return` 的值在回执文字里至多 `RETURNED_CHARS`(写明原来多长),stdout 至多 `PRINTED_CHARS`(写明少了
多少字);大表在进这些栏之前先经渲染器缩略(§四"回执");每次调用的结局只带写成的文字(超长的头部加摘要)。
所以一张回执远小于下行 1 MB,`ProgramResultPayload` 是内容有界的包:装不下就是代码错,当场抛,不再有"装不下缩成失败"。
身体活的账本身在生成处也已归堆计数(`NavText`:同类方块合并成"N 个 + 几处例子")。

### 线程

服务端函数的调用在程序线程上排进车道,主线程每刻在预算里取;一次调用至少等一个服务器刻(和 CC: Tweaked 的一次外设调用一样)。
程序等她派的活收尾:这件活的收尾事件在发出的那一刻、主线程上就判归谁(`NumenEvents.Watcher`)——归程序的写进回执、不再另送主人的大脑
(一件活的收尾只说一次);程序停下之后才到的收尾由程序转交出去,不会丢。管理员的 `/numen drive` 与重启后再跑的那一行没有模型读回执,它们派的活的
收尾照常发给她。
