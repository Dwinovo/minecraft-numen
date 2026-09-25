# Numen CLI:她只有一个能力——执行一行游戏指令

状态:
- **已落地**:第 1–3、5 步,以及第 6 步的执行管线(`command` 工具、`/numen` 注册进 MC 指令树、唯一的执行入口、`/numen drive`)。第 1、2、5、6 步的细节见附录 A、B、C、D。
- **进行中**:第 6 步的帮助与报错(第九节)。
- **之后**:第 4 步,核心工具迁移,直接按第 6 步的形态做。

## 一、为什么

- **工具只增不减。** 原来有 49 个工具,每轮全量发出,约 1.45 万 token,占固定前缀的 84%(基线统计,09-24)。每接一个模组就多几个工具。
- **模组本来就带着能力。** 模组和原版的指令本身就是现成的能力(FTB Teams 的入队、YSM 的换模型……),却没有入口能用。
- **模型最熟的是命令行。** Claude Code 的做法是一个 Bash 加上技能:技能讲"什么时候、怎么用",具体事情交给命令行。

## 二、心智模型

1. **一个能力**:她只会一件事——执行一行游戏指令,就像 Claude 的 Bash。走路、挖矿、看任务书、换模型、发私信,都是一行指令。
2. **一棵树**:指令就是 Minecraft 自己的指令树。
   - **Numen 自己的**挂在 `/numen` 下(`numen goto …`、`numen ftbquests list`),只注册给她,玩家看不见。
   - **原版和别的模组的**是它们自己的(`give …`、`ftbteams party join …`),我们不包、不加。
3. **两个执行侧,一个入口**:
   - 少数 Numen 命令只能在主人客户端算,例如写计划、记忆,以及按主人语言读任务书,它们住在客户端的一张小表里。
   - 其余一律送服务端,以她的身份执行。
   - 模型看到的是同一种写法。
4. **快捷工具是 alias**:高频的几条命令(goto、mine……)提升为独立工具,省去查帮助这一步。它们和命令用同一个处理函数,回执也一样。
5. **执行指令是一种动作,由权限层裁决**:
   - 出厂规则(数据,可在面板里改)放行 Numen 自己的指令和只读、无害的指令,其余没有规则的就问主人。
   - Numen 命令内部挖、放、打的每个动作,照旧按动作裁决。
6. **知情**:指令的回显原样作为回执返回;长活开始、结束都对得上这次调用;身体的变化照常进状态。
7. **学会用**:
   - Numen 的命令带说明,用 `--help` 查。
   - 原版和模组的用原版 `help`,它只列她能执行的。
   - 要教她某个模组的指令,就写技能,不写代码。
8. **扩展**:
   - 模组联动在 `/numen <模组id>` 下注册自己的命令组。
   - 动作已有、模组只是多了名词的,实现扩展点(比如穿戴的 `GearSource`)。
   - 原版注册表已经覆盖的(模组配方、结构)什么都不用做。

## 三、结构

```
模型
 ├─ 快捷工具 goto / mine / …          ← 常驻,一步到位;是某条 /numen 命令的 alias
 └─ command 工具 "<一行指令>"          ← 其余一切
          │
          ▼  主人客户端:这行解析到 Numen 的客户端动作?
          ├─ 是 → 客户端当场执行(写计划、记忆、读任务书……)
          └─ 否 → 原样送服务端
                    │
                    ▼  服务端:以她的 CommandSourceStack 解析(写不通当场失败,附用法)
                    ▼  权限层:command(根名) —— 放行 / 问主人 / 拒绝
                    ▼  Commands.performPrefixedCommand(和玩家在聊天栏敲的同一条路)
                    ├─ /numen …      → Numen 动作的处理函数(长活交任务槽)
                    └─ 原版/模组指令  → 它们自己
                    ▼  回显收集器 → 回执
```

- **工具名**:由 `numen` 改名为 `command`,参数是一整行,不带前导 `/` 也接受。`numen` 这个词回到它本来的含义:Numen 自己的指令根。
- **放在哪**:机制(执行入口、命令组声明、帮助、快捷工具生成)在 `api`,原版领域的命令在 `core`,模组命令在各自的 `plugins/*`。

## 四、指令长什么样

```
help                                  原版:她此刻能执行的全部指令(服务器按她的来源过滤)
numen --help                          Numen 的命令组,各一句话
numen ftbquests --help                一个组的动作与用法(带参数说明)
numen ftbquests list [--page 2]
numen ftbquests submit <任务>
numen goto <x> <z>                    第 4 步迁完后
give @s minecraft:diamond 2           原版,权限等级够才行
ftbteams party join Dwin_Party#1a2b   模组自己的
```

Numen 命令的形状沿用已落地的约定(附录 A):
- 组 → 动作两级;必填参数按位置写,可选参数写成 `--name value`。
- 帮助是树上的节点,写错时报错附带那一层的用法,列表分页。
- 长活的任务名叫"组 动作"。

## 五、在哪一侧执行

- **声明**:Numen 的每个动作登记时声明执行侧。命令组的声明在两侧都登记,是同一份公共代码。
- **客户端**:只有 Numen 的客户端动作住在客户端的一张小表里,由 Numen 自己的调度器执行。
  - 不注册成 MC 的客户端指令,原因有两个:一是 MC 客户端指令的来源是主人自己,表达不了"替哪一只同伴";二是会出现在主人的聊天补全里。
  - 这张表只收 `/numen` 下声明为客户端的动作。
- **服务端**:其余一切原样送服务端,由服务器的指令树执行。服务端的 Numen 动作真实注册进 MC 指令树的 `/numen` 下。
- **路由规则只有一条**:这行在客户端的声明里解析到客户端动作,就在客户端执行;否则一律送服务端。客户端不认识模组的指令,也不需要认识。
- **帮助**:`numen --help` 与组、动作的帮助都在客户端当场回答,因为声明两侧都有。原版 `help` 送服务端。

## 六、只给她:注册与可见性

- **只给她的节点**:Numen 给她的命令组节点带 `requires(来源实体是 NumenPlayer)`。
  - 原版给每个玩家发指令树时,会按 `requires` 过滤:玩家收不到这些节点,补全和 `help` 里也看不到。
  - 玩家硬敲,会得到原版的"未知或不完整的指令"。
- **只给玩家的节点**:`/numen` 下原有的玩家管理指令(召唤、设置、权限、征询……)反过来,`requires(来源不是 NumenPlayer)`。她看不见、也用不了,所以"她能不能通过指令召唤同伴"的问题从结构上就不存在了。
- **调试入口**:给 OP 用的是 `/numen drive <同伴> <一行指令>`,把这一行交给她的执行入口,和 `command` 工具是同一个入口。
  - `DebugCommands` 里另写的 `goto`/`mine`/`cancel` 删掉,不留第二份实现。
  - 不能用 `/execute as 她 run numen …` 代替:Brigadier 解析时按发指令的人查 `requires`。
- **参数类型登记**:Numen 自己的参数类型(可选标志 `FlagsArgument` 等)在 MC 的指令参数类型注册表(`COMMAND_ARGUMENT_TYPE`)里登记,两侧都要。
  - 原因:指令树的包在构造时就会把每个参数类型序列化,没登记的类型直接报错,她的假连接丢包也救不了。
  - 玩家收到的树里没有这些节点,所以装不装 Numen 客户端的玩家都不受影响。

## 七、权限与知情

- **执行就是动作**:执行一行指令就是动作 `command(根名)`,规则写法见 `docs/permission-layer.md`,别名一并认。
- **出厂规则**(数据,和"别人放的方块要问"是同一层,主人在面板里可以改):
  - `allow command(numen)`:Numen 自己的命令。它们内部的挖、放、打仍然按动作裁决,外层不重复问。
  - `allow command(help)`、`allow command(list)`、`allow command(me)`、`allow command(msg)`、`allow command(teammsg)`、`allow command(seed)`、`allow command(random)`:只读或只说话的指令,别名(tell、w、tm)随根名一起认。
  - 其余没有规则覆盖的,问主人。代码里不写任何白名单。
- **写不通先失败,不打扰主人**:没有这条指令、服务器不让她用、参数写错,这三种都当场失败,附上用法,不进任务槽。
- **回显**:用收集器收下成功和失败的回话,作为回执返回。"要不要知会别的管理员"照服务器原样处理:只有原版的管理类指令会广播,Numen 自己的命令不广播,所以不会刷屏。
- **调用上下文**:她的 `CommandSourceStack` 由执行入口构造,其中的输出收集器同时带着这次调用(调用 id、任务名、回信口)。Numen 动作的处理函数从这里取,长活交任务槽后,受理、结束的回执才对得上号。
- **身体的变化**:背包、位置照常进状态;命令自己的回执写做了什么。

## 八、快捷工具

- **同源**:提升自服务端动作的快捷工具,把 JSON 参数按同一组参数类型读取,再交给同一个处理函数,不拼字符串再解析。回执与从 `command` 调用一字不差(附录 A)。
- **哪些提升,按调用频率定**。基线建议的 11 个:get_self_status、scan_blocks、look_around、scan_nearby_entities、get_owner_status、inspect_block、goto、mine、build、load_skill,以及 task_status。task_status 主要是轮询,是否提升待定。
- **快捷工具和命令一样过权限层**:走的是同一个执行入口,裁决的是同一个动作。

## 九、帮助与报错:像真正的 CLI 一样把她教会

帮助和报错是模型读的界面。所有内容都有唯一的来源:要么是命令登记时写下的声明,要么是 Brigadier 本身。

### Numen 自己的命令:说明全由登记写

分层给出,只有最后一层是全量:
- **组的帮助**:每个动作一行。
- **动作的帮助**:给全,包含以下几块:
  - **用法**:参数形状。
  - **一句说明**。
  - **逐个参数**:类型全称、取值范围或可选值、默认值、说明。取值不固定的参数,写明去哪查,例如"模型名用 `numen ysm options` 查"。
  - **例子**:登记时每个动作**至少一个,缺了就在登记那一刻报错**,和名字不合规时报错是同一种把关。模型照着例子写,比读语法可靠。
  - **注意**:会不会问主人、是否长活(结果作为 `task_finished` 到达)、不会做什么、会动她的什么(比如扣她背包里的东西)。
  - **相关命令**:做完这件事,下一步通常用的命令。

```
numen ftbquests submit <quest>
  Hand in a quest's items, experience or checkmarks from your own inventory.
  <quest> (word) — The quest's id, as list and show print it.
  Examples:
    numen ftbquests submit 15CDF6A098B95FDA
  Notes:
    Takes the items from YOUR inventory; FTB decides what counts.
    Observation tasks are not supported. Rewards arrive as quest_reward_auto events.
  See also: numen ftbquests list, numen ftbquests show
```

### 原版与别的模组的指令:从 Brigadier 挖

它们没有说明文字,我们也不替它们写,要教她就写技能。但 Brigadier 带着的信息比原版 `help` 显示的多。统一挖出来,对所有指令一样有效,不必逐个适配:
- **用法**:服务器按她的来源给出的 `getSmartUsage`,也就是原版 `help` 那一行。
- **每个参数的类型**:参数节点上的 `ArgumentType`,比如物品、实体选择器、坐标。
- **类型自带的例子**:`ArgumentType#getExamples`,例如物品参数给出 `stick`、`minecraft:stick`。
- **此刻可选的值**:补全引擎(`getCompletionSuggestions`)按她的来源给出候选,比如 `ftbteams party join` 会补全她能进的队伍。候选太多时只列前几个,再加总数。

入口是 `help <指令>`。原版的 `help` 已经按她的来源过滤,我们在它后面接上面几项。
- `help` 不带参数时仍是原版的清单。
- `numen … --help` 走第一小节。

### 写错时,报错就是帮助

- **位置与原因**:Brigadier 的原话,加上出错位置(`…<--[HERE]`)。
- **那一层的用法**:Numen 命令附上那一层的帮助;别的指令附上 `getSmartUsage`。
- **你是不是要写**:用同一个补全引擎,取出错位置上合法的候选,按编辑距离挑最接近的几个。例如 `numen ysm swtich` → `switch`,`give @s minecraft:dimond` → `minecraft:diamond`。对任何指令都一样,不维护同义词表。

### 长度

帮助每次都进上下文,所以:
- 组的帮助只给一行一个动作;
- 候选值有上限;
- 长清单一律分页(`Listing`,附录 A);
- 例子和注意只写在动作的帮助里。

## 十、提示词与技能

- **系统提示**:`command` 工具的描述写清它就是"执行一行指令",Numen 的用 `numen --help` 查,其它的用 `help` 查。`<commands>` 索引照旧列出 Numen 已安装的命令组,各一句话。
- **技能**:讲"什么时候、怎么用",附一两个例子,不抄语法。某个模组的指令想让她会,就给那个模组写技能,不写代码。

## 十一、扩展点

判据不变:**动作已有、模组只是多了名词、意图不变**,就开扩展点;真正的新动作,进模组自己的命令组。同一件事只留一个入口。

- **注册表**:原版读的注册表,模组本来就在往里写,什么都不用做。
- **行为提供者**:同一个动作有多个来源,例如 `GearSource`(`docs/curios-gear-slots.md`)。
- **输出片段**:同一个查询由多家往里加,例如身体状态的片段。
- **名词解析器**:动作不变,能指的东西变多,例如地点解析,等地图联动时再开。
- **给模组补命令**:模组 A 功能丰富,但它自带的指令不够用时,联动插件(只在 A 在场时加载)经 A 的公开 API,在 `/numen <A 的 id>` 下补上她要的动作。`numen ftbquests list|submit|join` 就是这样来的。
  - A 自己的指令已经能做好的事,不再包一层:技能里教她直接用 A 的指令,同一件事只留一个入口。
  - 不往 A 自己的指令根(`/a …`)里挂节点。Brigadier 技术上允许同名合并,但那是嫁接:会和 A 以后的版本撞名,出了错也说不清是谁的,而且玩家的补全里会平白多出东西。
- **不嫁接**:插件只拿到自己那一组,够不着 `/numen` 的根,也够不着别人的组(附录 A)。

## 十二、外脑(MCP 模式)

外脑看到的是同一批快捷工具,加上 `command` 工具,走同一个执行入口。

## 十三、不变的东西

- 权限层按动作裁决;身体发生的事都要告诉她。
- 任务槽、回执、打断、叫停不变。
- GameTest 从入口调用:`command` 工具、快捷工具,以及 `/numen drive`。

## 十四、测试

- **单元测试**:
  - 快捷工具的 schema 生成;
  - 帮助快照;
  - 解析错误附用法;
  - 客户端、服务端的路由;
  - 出厂规则的解析和匹配。
- **GameTest**:
  - 同源:同一件事分别从快捷工具和 `command` 调用,结果一致。
  - 可见性:玩家的指令树里没有只给她的节点;她的树里没有玩家管理节点。
  - 权限:`help`、`numen …` 不问;`setblock` 没有规则就问;没有权限如实失败。
  - `/numen drive` 与 `command` 结果一致。
- **迁移期**:原来覆盖这些功能的测试改从新入口调用,断言不放宽。

## 十五、路线

| 步 | 内容 | 状态 |
|---|---|---|
| 0 | 基线统计(调用频率、工具定义的 token 开销) | 已完成 |
| 1 | 底座:命令组声明、帮助、快捷工具同源、两侧分发 | 已落地(附录 A) |
| 2 | 三个联动插件的 8 个工具改成命令 | 已落地(附录 B) |
| 3 | FTB Quests 命令组、Curios 走装备位扩展点 | 已落地 |
| 5 | `numen mc` 原版指令入口 + 权限层 COMMAND | 已落地(附录 C),第 6 步已把它并掉 |
| **6** | **并进 MC 指令树**:`command` 工具取代 `numen` 工具;Numen 服务端组真实注册到 `/numen`,只给她看见;客户端组留在客户端小表;`numen mc` 组与帮助目录删掉(原版 `help` 已按她的来源过滤);出厂规则;`/numen drive` 取代 DebugCommands 的重复实现;自定义参数类型在 MC 注册表登记;帮助与报错按第九节补齐(例子必填、注意、相关命令;别的指令从 Brigadier 挖类型、例子、候选;报错带"你是不是要写") | 执行管线已落地(附录 D);帮助与报错进行中 |
| 4 | 核心工具按领域分批迁移,直接注册到 `/numen`,高频的提升为快捷工具 | 第 6 步之后 |

## 十六、待核实

三条都已核实,结论见附录 D:
- **调用上下文怎么取**:那个字段是私有的,原版只给换不给读,用一个只读的访问器 mixin 读回。
- **参数类型的两侧登记**:经平台服务,NeoForge 用 `DeferredRegister`,Fabric 用 `ArgumentTypeRegistry`;她的指令树在服务端造得出包。
- **客户端动作的根**:两棵树共用 `numen` 这个根、同一个形状;客户端动作的参数与执行不进 MC 树,帮助两侧都有。

## 十七、不做的

- 不做 shell:没有管道、变量、重定向。
- 不包原版和模组的指令,不为它们另写命令。
- 不嫁接,插件不往别人的节点下挂东西。
- 技能里不抄语法。
- 不写死指令白名单:出厂规则是数据,主人能改。

## 附录 A:第 1 步落地时定下的细节

第 6 步之后,文中的 `numen` 工具改名为 `command`,整行不再以工具名打头,而是一行真实的指令;其余约定照旧,
以下几条已被附录 D 取代:
- `CommandGroup.serverDirect` 与帮助目录 `Action.catalog` 删掉(它们只为 `numen mc` 而开)。
- "客户端先解析、服务端动作送去服务端再解析同一棵树"改为两棵树、一条路由规则;服务端不再有 Numen 自己的调度器,
  服务端动作在 MC 指令树上。
- `numen` 工具由引擎登记,现在是 `command` 工具。

代码在 `api` 的 `com.dwinovo.numen.cli` 包。

- **登记写法不是裸 Brigadier。** 插件经 `NumenApi.registerCommands(组名, 一句话, 组 -> …)` 拿到自己的 `CommandGroup`,往里加 `Action`,参数用 `Param` 与 `ArgType` 声明;Brigadier 树由这一层生成。
  - 原因:帮助的说明、执行侧、可选标志、schema 都要挂在节点上,Brigadier 的节点没有这些位置;裸 builder 还能 `redirect` 或拿到别人的节点,"不许嫁接"就只能靠约定。
  - 插件手里只有自己那一组,够不着根和别的组;组名、动作名、参数名、快捷工具名撞了或不合规,都在登记的那一刻抛出。登记块返回后这一组封口。
  - 引擎自带的 `task` 组也经这扇门登记(`TaskCommands.install`),由 core 在原来三个工具的位置调用,工具表顺序不变。
- **一行命令以 `numen` 开头**:`numen` 工具的参数 `command` 是整行,如 `numen task status`。帮助里的每一行都能原样照抄。
- **层级只有两级**:组 → 动作。设计里没有更深的需要,没做嵌套组。
  - 一组也可以直接就是一个服务端动作(`CommandGroup.serverDirect`):参数紧跟组名,没有动作名,`numen mc <command...>` 就是这样。这样的组不能再有具名动作(具名动作会和它的参数抢同一个位置),登记时就查;它的 `--help` 与写错时附的用法都是这个动作的帮助。
- **帮助可以接一张服务端目录**(`Action.catalog(标题, 源 -> 条目)`):只有服务端按这具身体此刻的样子才答得出的条目(`numen mc --help` 列她能用的原版指令)。客户端解析到这种帮助时把调用原样送去服务端,那边算出来,和组的列表一样每页 20 行、认 `--page`。
- **参数**:必填的是位置参数,按声明顺序;可选的是标志 `--name value`,顺序随意。标志名就是参数名、也就是 JSON 的键。
  - 标志只有一种机制:位置参数之后挂一格 Brigadier 参数节点(`FlagsArgument`),它读到行尾,逐个认标志名,值交给那个参数自己的类型在同一个读头上读。所以出错位置是整行里的真实位置,用法照样附上。
  - 每个标志都带值(布尔也写 `--x true`),分隔都是一个空格,和 Brigadier 分隔位置参数的规矩一致;写重、缺值、没声明的标志各有一句报错。
  - 吃掉余下整行的 `text` 只能是最后一个必填参数,且这个动作不能再有标志。
- **参数类型**第 1 步开了三种:`integer(min, max)`、`word`、`text`;第 2 步补的见附录 B。要新的,就在 `ArgType` 加一种。
  - `integer` 的范围写进 schema 与帮助,读的时候不拦越界值:夹住还是拒绝、回执里怎么说,是动作自己的语义(`task timer` 夹住并说明你要的和实际定的)。
  - 快捷工具的 JSON 值取字面文字,交给同一个 Brigadier 类型整段读完。没声明的键拒掉;JSON `null` 等于没给。
- **帮助是树上的普通节点**:根下的 `help` 与 `--help`,每组、每个动作下的 `--help`,和别的命令同一次解析认出来。组与根的列表每页 20 行,`--page N` 翻页,超出时说还剩几条、怎么翻。
  - 分页只有一份,是公开的 `Listing`:动作自己列的清单(如 `ftbquests list`)把 `Listing.PAGE` 登记为参数,处理函数把读好的参数交给 `Listing.result`,每页行数、翻页提示、越界的说法都和帮助一样。
- **报错**:Brigadier 的报错原文加上出错那一层的帮助(根、组的第一页,或动作的完整帮助)。某一层连一个候选都对不上时(组名、动作名写错)报"未知命令"而不是"参数不对"。
- **回执**:命令与快捷工具都回 `TaskResult` 的 JSON,帮助是一条成功回执,解析错误是一条失败回执。
- **执行侧**:两个处理函数接口 `Action.OnServer` 与 `Action.OnClient`,登记时选哪个就是哪一侧。
  - 客户端先解析:帮助、解析错误当场回,客户端动作当场执行;服务端动作把这次调用原样经 `ServerToolTransport` 送去服务端,那边再解析、执行。
  - 服务端收到客户端动作如实拒绝。专用服务器上客户端动作照样登记(帮助要它的说明),处理函数永远不会在那里被调用。
  - 快捷工具提升自服务端动作时,调用照身体工具的路子直接送服务端,参数在那边读,读错的回执与所有身体工具同一种说法。
- **长活**:`ServerSource` 带着这次调用本身(`toolName`、`args`:快捷工具名和它的 JSON,或 `numen` 和 `{"command": …}`)。长活交 `TaskDispatch.setTask(source, record)`,重启后的重放记的就是这次调用,走同一个入口再来一遍。任务叫什么见附录 B。
- **一行索引**:`NumenCli.index()` 生成 `<commands>` 块,组按名字排序,挂在系统提示的技能表之后;只随组的增减变。
- **`numen` 工具由引擎在 `CommonClass` 登记**:插件的命令只依赖引擎,谁登记了命令都指望这个入口在。外脑(`NumenActuator` / MCP)读的就是同一张工具表,自然看到 `numen` 与各快捷工具。

## 附录 B:第 2 步落地时定下的细节

三个联动插件的工具全部改成命令,都不提升(插件工具是长尾);旧工具类删掉,描述拆成组说明、动作说明、参数说明写在各插件的 `*Commands` 类里,技能里只留命令的例子。

| 旧工具 | 命令 |
|---|---|
| `kc_recipes` | `numen kaleidoscope recipes <cookware> [--have_only] [--name]` |
| `kc_inspect` | `numen kaleidoscope inspect <x> <y> <z>` |
| `kc_cook` | `numen kaleidoscope cook <x> <y> <z> <recipe>`(长活) |
| `list_maid_models` | `numen tlm models [--search]` |
| `wear_maid_model`(给 model) | `numen tlm wear <model>` |
| `wear_maid_model`(model 留空) | `numen tlm remove` |
| `list_ysm_options` | `numen ysm options` |
| `switch_model` | `numen ysm switch <model> [--texture]` |
| `play_emote` | `numen ysm emote <animation>`(`stop` 停下,照 YSM 自己的写法) |

- **"留空表示另一件事"拆成两个动作。** 工具贵,才把穿和脱塞进一个参数;命令不花工具表的钱,一个动作一个意思。
- **`ysm switch` 是任务槽里的一次同步短任务**(`runSync`,和 `numen mc` 同一种):成败以回读她身上穿的为准,回读之前 YSM 的命令必须已经执行完。原版的指令在另一条指令的执行当中被调起时排到那条之后(控制台、`/numen debug`、`/test` 调进来的都是这样,生产专用服上实测回读早于 YSM 设上),任务在服务器刻里跑,不在任何指令的执行当中,命令当场执行完。没换成时把 YSM 对这条命令说的话原样带回。
- **`ysm emote` 只说"已发出"**:YSM 的 play 命令静默,动作补全又不看目标是谁(专用服上一律为空,单人游戏里是客户端兜底模型的动作),服务端拿不到她这身模型的动作清单,所以不校验、不说"做了";`ysm options` 也不再列动作。
- **新参数类型**:
  - `integer()`:不设范围的整数,方块坐标用。
  - `bool()`:`true` / `false`,当标志也要写值。
  - `id()`:资源 id,读成 `ResourceLocation`;字符集与合法性用原版 `ResourceLocation` 自己的规则,不写命名空间即 `minecraft:`。配方、女仆模型用它。
  - `string()`:一个值,到空格为止的任意字符(中文、`/`、大写都行),带空格就加引号。模组自己起的名字(YSM 的模型文件名、动作名,女仆包的角色名)用它,这些名字的字符集不归我们定。
  - 动作帮助里每个参数都写出类型的完整称呼,必填的 `<model> (string, quote it if it has spaces)`,可选标志的 `--texture <string> (string, quote it if it has spaces; optional)`:"带空格要加引号"跟着类型走,哪个参数用了 `string()` 都有。
  - JSON 进来的值写成它在命令行上的样子再读:多数类型就是字面文字,`string()` 一律加上引号——JSON 的字符串本来就有边界,否则带空格的名字命令行收、JSON 拒。
- **任务叫什么**:任务记录、受理回执、`task_finished`、`<current_task>` 写的名字是 `ServerSource.taskName()`:从快捷工具进来是快捷工具名,从 `numen` 进来是"组 动作"(如 `kaleidoscope cook`)。解析到动作、交给处理函数前,源对象先绑上那个动作。
  - 命令派的活用 `TaskRecord(ServerSource, deadline)` 起记录,名字与调用 id 都取自源;交 `TaskDispatch.setTask(source, record)`。
  - 记录的名字不是能重放的工具名,所以重放记的是那次调用本身(`numen` 与那一行命令)。工具派的活照旧 `setTask(companion, record, args, reply)`,记录以工具名命名,重放按这个名字找回工具。
  - 落盘时名字与重放的调用一起记下(`CompanionRegistry.Entry` 的 `taskName`,取自受理时的记录):重启后接不回来,`task_finished` 用的就是这个名字,和受理时她看到的一样。
- **客户端动作的插件也在两侧登记命令**:`tlm` 的三个动作都在主人客户端执行,命令组照样在 `NumenPlugins.register` 块里直接登记(专用服务器上只为帮助),插件原来放在 `onClient` 里的登记工具那两行随之删掉。
- **工具表的账**(字符数粗估,英文约 4 字符一个 token、中文一字一个):8 个旧工具定义约 4500 字符、约 1350 token;换成 `<commands>` 里三行,约 250 字符、约 60 token。三个插件都装时每轮少发约 1300 token。

## 附录 C:第 5 步(`numen mc`)落地时定下的细节

**第 6 步已把这一组并掉**(附录 D):它的执行入口(先解析、写不通当场失败、过权限层、`performPrefixedCommand`、收集回显)
泛化成服务端唯一的执行入口 `CommandRunner`,所有指令都走它;`McCommands`、`McCommandTaskRecord`、`McCommandCompanionTask`、
`serverDirect` 组形态与帮助目录(`Action.catalog`)都已删掉,原版 `help` 已按她的来源过滤。下面标"已取代"的几条以附录 D 为准,
其余(来源与回显的收法、回执的写法、征询卡)照旧。

代码:权限层的动作是 `Action.command`,规则写法见 `docs/permission-layer.md` 的"指令"。

- **执行**(已取代):~~任务槽里的一次同步短任务(`runSync`,期限 5 秒,等主人答复的刻不计)~~。现在不进任务槽,放行就当场执行;要问主人时这次调用悬着(附录 D)。动手前先把 `command(整行)` 交给权限层,要问就走现有的征询流程。
- **来源与回显**:
  - 来源是她的 `CommandSourceStack`,权限等级、位置、`@s` 都是她自己的,只是把回话的去处换成一个收集器。
  - 成功和失败的回话都收下。成败以执行完的结果回调为准:分叉的指令有一支成功就算成功;没有回调就是没跑成。
- **回执**:
  - 成功时是 `ran /<整行>: <回显>`,失败时是 `/<整行> failed: <回显>`。
  - `data` 里带 `command`、`output`、`result`。
- **征询卡**:动词写"执行 / run",名字是 `/整行`。"以后都允许"记下的是 `allow command(根名)`。

## 附录 D:第 6 步(执行管线)落地时定下的细节

代码在 `api` 的 `com.dwinovo.numen.cli`;`/numen` 下玩家那一半在 `entity.NumenCommands`,core 的调试开关在 `DebugCommands`。

- **一份声明,两棵树**(`CommandTree`)。主人客户端的小表(`NumenCli` 自己的调度器)与 MC 指令树 `/numen` 下她的节点,由同一个生成器从命令组声明长出来,形状相同:根下 `help`、`--help`;组下 `--help` 与每个动作;动作下 `--help`。动作的参数、标志尾巴、可执行的那一格只长在执行它的那一侧。登记时把例子按这一组的树解析一遍(第九节)用的也是这个生成器,只是那棵树上每个动作都长着参数。
- **路由**(`NumenCli.run`)。一行先在客户端小表上解析,看解析走到的最后一个字面节点:是帮助,或是一个客户端动作,就在客户端答,这个动作写错了也当场报;停在根上、组上、服务端动作上,或者不以 `numen` 开头,原样经 `ServerToolTransport` 送服务端。服务端动作写错由服务端报,两侧报错是同一个函数(`NumenCli.problem`),一字不差。
- **注册与可见性**(`NumenCommands`)。两个加载器的入口本来就在指令注册事件里调 `NumenCommands.register`,她的节点(`NumenCli.herNodes()`)在这里挂到 `/numen` 下,不另开平台服务。
  - 她的节点 `requires(FOR_HER)`:来源实体是 `NumenPlayer`。玩家的管理节点(`player`、`settings`、`reset`、`permission`、`consent`、`drive`,core 的 `debug`、`profile`、`pad`)`requires(FOR_PLAYERS)`。
  - 挂到 `/numen` 下的每一格都经 `NumenCommands.graft`:同名的一格已经在了就抛出。Brigadier 会把同名两格悄悄并成一格,留下先来那一格的观众。
  - 服务器建指令树的这一刻各模组的组都已登记完,相关命令(`seeAlso`)在这里一次查全,断掉的引用开服就报错;连着别人服务器的客户端不建指令树,仍在小表第一次被读时查。
  - 实测(GameTest):她在 `/numen` 下用得了的格与玩家用得了的格不相交;玩家的树里没有 Numen 自己的参数类型;她的 `help` 列出 `/numen`、不列召唤与权限。
- **执行入口**(`CommandRunner`)。
  - 一行:以她的来源在服务器指令树上解析。`numen` 开头的用 Numen 的报错(Brigadier 原话加那一层的帮助);别的指令用和服务器执行前同一道检查,写不通时说没有这条、服务器不让她用、还是参数写错(附 `getSmartUsage`)。写不通当场失败,不打扰主人。然后权限层 `command(根名)`,放行就 `Commands.performPrefixedCommand`,来源的回话去处换成 `Echo`。
  - 快捷工具:JSON 按同一张参数表读成值;权限层裁决它作为 alias 的那一行(`numen 组 动作 值… --标志 值`,值写成它在命令行上的样子);放行后交同一个处理函数,不拼行再解析。
  - 执行一行指令不是身体上的活,不进任务槽:原版与模组的指令当场执行、当场回执;`/numen` 的处理函数照旧当场回执或把长活交任务槽。附录 C 的同步短任务删掉,原因之一是它在任务槽里执行 `/numen` 的处理函数时,处理函数自己的 `runSync` 会把它顶掉。
- **要问主人时**(`PendingCommands`)。这次调用挂在身体上(和征询登记处一样),征询的作用域就是这一次挂着的调用;`ConsentDesk` 的作用域因此从任务记录放宽为任意对象,授权照旧按身份记、随发起的一方收场而清。每刻读一次结论:
  - 允许就接着执行,回执末尾交代主人允许了什么(`ServerSource.allowed`,和任务回执交代允许是同一种写法);
  - 拒绝、超时、被新的请求顶替,如实回执;
  - 主人按停止、身体离开世界:撤掉征询,回执说被谁叫停、没有执行;她死了:撤掉征询、不回执(那条调用已由死因结算);
  - 等主人的时候身体照常做手上的事。
- **调用上下文**(`Echo`)。`Echo` 既是她来源的回话去处(`CommandSource` 与 `CommandResultCallback`),也带着这次调用(`ServerSource`:调用 id、工具名、参数、回信口)。
  - `CommandSourceStack` 的回话去处是私有字段,原版只给 `withSource` 换、不给读;用一个只读的访问器 mixin(`CommandSourceStackAccessor`)读回,放在 api 的公共 mixin 配置里,两个加载器共用。
  - `execute` 这类改写来源的指令只换位置、朝向、实体,回话去处原样传下去,所以指令树上任何一格都取得到它。
  - `/numen` 的节点取出这次调用、绑上动作交给处理函数;处理函数正常返回,就记下"这次调用由 Numen 答了",入口不再拿回显作回执。取不到就抛出:有人绕开了执行入口。没有线程变量。
  - 长活:处理函数交 `TaskDispatch.setTask(source, record)`,记录的调用 id 与名字都取自这次调用,受理回执与 `task_finished` 对得上号(GameTest 实测)。
- **参数类型登记**。`FlagsArgument` 与 `ArgType` 的 `id()`、`string()`(从方法引用改成具名类 `IdArgument`、`ValueArgument`)经平台服务 `IPlatformHelper.registerArgumentType` 登记进 `COMMAND_ARGUMENT_TYPE`,注册名 `numen_api:flags|id|string`:NeoForge 用 `DeferredRegister` 加 `ArgumentTypeInfos.registerByClass`,Fabric 用 `ArgumentTypeRegistry`。
  - 它们的 `ArgumentTypeInfo`(`HerArgumentInfo`)什么都不写,读回就抛:这些类型只在她的节点上,从不发到客户端。
  - NeoForge 服务器本来就要求装 Numen 客户端(Numen 的网络载荷不是可选的),装了的客户端两侧都登记过;玩家收到的树里没有这些类型。
- **`/numen drive <同伴> <一行指令>`**(OP 2 级,只给玩家)。同伴用原版的玩家参数(名字或选择器);这一行交 `CommandRunner.run`,与 `command` 工具同一个入口,回执说给发指令的人听。
  - drive 自己正在执行,原版把执行当中调起的指令排到它之后,入口返回时她那一行还没跑。所以交给服务器的任务队列(`TickTask`),等 drive 执行完再以她的身份执行。
  - `DebugCommands` 的 `goto`、`mine`、`cancel` 删掉,只剩 `debug`、`profile`、`pad`。
- **重放**。长活记下的是 `command` 与 `{"command": 那一行}`,重启后走同一个入口再来一遍。改名前落盘的 `numen` 调用按"这个工具已经不在了"告诉她,不做转接。
- **名字与提示**。工具叫 `command`,参数 `command` 是一整行,前导 `/` 可有可无;描述写明 Numen 的用 `numen --help`、其它的用 `help`。`<commands>` 索引开头是"Numen's command groups, run with the command tool"。外脑(MCP)读的是同一张工具表。
- **与正文的出入**。
  - 客户端动作不进 MC 树,指的是参数与执行;它的名字与 `--help` 两侧都有,`/numen drive` 问得到帮助,写到它那儿报这个动作的帮助。
  - 客户端动作写错参数在客户端当场答:这一行解析到的就是客户端动作。
  - 帮助只经 `/numen drive` 才会在服务端答;在那里翻页越界,回执是指令失败的回显,不附那一层的帮助。
