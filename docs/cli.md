# Numen CLI:她只有一个能力——执行一行命令

状态:
- **已落地**:第 1–3、5–7 步,细节见附录 A–F。本稿正文描述的就是第 7 步"分层"之后的样子;第 6 步里"把她的命令挂进 MC 指令树 `/numen`"的做法已撤回(附录 F)。
- **下一步**:第 4 步,核心工具迁移,直接按分层后的形态做。

## 一、为什么

- **工具只增不减。** 原来有 49 个工具,每轮全量发出,约 1.45 万 token,占固定前缀的 84%(基线统计,09-24)。每接一个模组就多几个工具。
- **模组本来就带着能力。** 模组和原版的指令本身就是现成的能力,却没有入口可用。
- **模型最熟的是命令行。** Claude 只靠一个 Bash 就能做完所有事,其它功能都可以看作 Bash 的封装。
- **MC 指令树不适合直接交给 AI。** 这是第 6 步之后才看清的:
  - 写法和规矩各不相同;
  - 有用的指令多是管理员指令(比如 `ysm model set` 能给任何人换模型),她用不了,给她 OP 又什么都能做;
  - 一棵树里大多是给玩家和管理员准备的东西。
  
  所以她需要一层专门给她的命令。

## 二、心智模型

1. **一个能力**:她只会执行一行命令,像 Claude 的 Bash。其余功能都是这一行的封装。
2. **两层**:
   - **第 1 层**:Numen 命令层,只给她。这是我们自己的一棵树,命名空间由我们定,规矩统一:有帮助、有例子、写错了提示你是不是要写别的、以谁的权威执行写得清清楚楚。
   - **第 0 层**:MC 指令树,原版和各模组的指令,是给玩家的。我们不往里挂任何东西。
3. **一个标记**:行首带 `/` 的,原样交给第 0 层,以她自己的权限执行,和在聊天栏敲的一样;不带 `/` 的,交给第 1 层。
4. **命名空间**:
   - 第 1 层的一级命令,是核心领域名(`task`、第 4 步迁来的 `goto`……),或者模组 id(`ysm`、`ftbquests`……)。
   - 和第 0 层同名也不冲突:`ysm switch` 是第 1 层的,`/ysm model set` 是第 0 层的。
5. **第 1 层命令的三种来源**:
   - 包装第 0 层的指令,比如 `ysm switch`:限定只作用于她、按主人的授权、执行后回读确认;
   - 用模组的 API 直接补上模组没给的功能,比如 `ftbquests submit`;
   - 核心动作,比如 `task status`,以及第 4 步迁来的 goto、mine 等。
6. **快捷工具是 alias**:第 1 层里高频的几条提升为独立工具,用同一个处理函数,回执也一样。
7. **权限**:
   - 第 1 层每条命令自己声明以谁的权威执行,默认是她自己。包装类命令可以借服务器的权威,但作用范围写死在代码里。
   - 身体对世界的每个动作,仍然由权限层按动作裁决。
   - 第 0 层的每一行,都是一个 `command(根名)` 动作,出厂规则(数据)放行只读和只说话的那些,其余的问主人。
8. **知情**:回执原样返回;长活开始、结束对得上是哪次调用;身体的变化照常进状态。
9. **学会用**:第 1 层用 `--help`;第 0 层用 `/help <指令>`。想让她会用某个模组的原生指令,就写技能,不写代码。

## 三、结构

```
模型
 ├─ 快捷工具 task_status / goto / …    ← 第 1 层里高频命令的 alias
 └─ command 工具 "<一行>"
          │
          ├─ 行首是 "/" → 第 0 层:服务端以她的 CommandSourceStack 解析(写不通当场失败,附用法)
          │                 → 权限层 command(根名) → performPrefixedCommand → 回显 → 回执
          │
          └─ 否则 → 第 1 层:主人客户端用 Numen 自己的调度器解析
                     ├─ 客户端动作、帮助、写错 → 当场回
                     └─ 服务端动作 → 原样送服务端 → Numen 服务端调度器解析 → 处理函数
                                      (以动作声明的权威执行;身体对世界的动作照常过权限层)
```

- **执行入口**:服务端只有一个,即 `CommandRunner`,管两件事:第 1 层的服务端动作,以及第 0 层的 `/` 行。两者共用解析失败的说法、回执、长活对号和挂起征询。
- **放在哪**:机制(两层的分派、调度器、帮助、快捷工具生成)在 `api`;原版领域的命令在 `core`;模组的命令在各自的 `plugins/*`。

## 四、命令长什么样

```
help                                  第 1 层:所有命令组,各一句话
ysm --help                            一个组的动作与用法
ysm switch wine_fox/07_jk             第 1 层,包装模组指令
ftbquests submit 15CDF6A098B95FDA     第 1 层,用 API 补的功能
task status                           第 1 层,核心动作
/help give                            第 0 层:原版用法 + 从 Brigadier 挖的类型、例子、候选
/give @s minecraft:diamond 2          第 0 层,权限等级够才行
/ftbteams party join Dwin_Party#1a2b  第 0 层,模组自己的指令
```

第 1 层命令的形状沿用已落地的约定(附录 A),只是去掉了 `numen` 前缀:
- 组 → 动作两级;
- 必填参数按位置写,可选参数写成 `--name value`;
- 帮助是树上的节点,写错时附上那一层的用法,列表分页;
- 长活的任务名叫"组 动作"。

## 五、在哪一侧执行

- **声明是同一份**:第 1 层每个动作登记时声明执行侧;命令组的声明在两侧都登记,用的是同一份公共代码。
- **主人客户端**:只有这里能算的动作在这里执行,比如写计划、记忆、按主人的语言读任务书。
- **服务端**:其余的在服务端执行。服务端也用 Numen 自己的调度器,不挂到 MC 的指令树上。
- **路由规则只有一条**:
  - 行首是 `/`,送服务端,走第 0 层;
  - 否则在客户端按第 1 层解析:解析到客户端动作、帮助,或者写错了,当场回答;解析到服务端动作,原样送服务端。

## 六、玩家与调试

- 玩家在 MC 里看不到任何她的命令:第 1 层不在 MC 的指令树上,也就不需要按"是不是她"过滤可见性。
- `/numen` 下的玩家管理指令(召唤、设置、权限、征询……)照旧只给玩家用。
- OP 调试用 `/numen drive <同伴> <一行>`:把这一行交给她的执行入口,和 `command` 工具是同一个入口。所以 `drive` 既能跑第 1 层,也能跑 `/` 开头的第 0 层。

## 七、权限与知情

- **以谁的权威**:
  - 第 1 层每个动作的处理函数,拿到的执行来源由动作声明决定,默认是她自己;
  - 包装类动作要借服务器的权威时,必须在声明里写明,而且作用对象写死为她自己;
  - 权威只在这一处声明,处理函数自己不另开后门。
- **身体对世界的动作**:照旧由权限层按动作裁决,和从哪一层进来无关。
- **第 0 层**:每一行都是动作 `command(根名)`,规则写法见 `docs/permission-layer.md`,别名一并认。出厂规则放行 `help`、`list`、`me`、`msg`、`teammsg`、`seed`、`random`;其余没有规则覆盖的,问主人。
- **写不通先失败**:两层都一样,当场失败并附上用法,不进任务槽,也不打扰主人。
- **回显与回执**:第 0 层的回显照旧由收集器收下;第 1 层的回执由处理函数给出。长活交任务槽后,受理、结束都对得上是哪次调用。

## 八、快捷工具

- **同源**:把 JSON 参数按同一组参数类型读成值,交给同一个处理函数,回执与从 `command` 调用一字不差(附录 A)。
- **提升哪些**:按调用频率定。基线建议的 11 个是 get_self_status、scan_blocks、look_around、scan_nearby_entities、get_owner_status、inspect_block、goto、mine、build、load_skill,以及 task_status;task_status 主要被拿来轮询,要不要提升待定。

## 九、帮助与报错:像真正的 CLI 一样把她教会

帮助和报错是模型读的界面。所有内容都只有一个来源:要么是命令登记时写下的声明,要么是 Brigadier 本身。

### 第 1 层:说明全由登记写

分层给出,只有最后一层是全量:
- **组的帮助**:每个动作一行。
- **动作的帮助**:给全,包括:
  - 用法;
  - 一句说明;
  - 逐个参数:类型全称、取值提示,例如"模型名用 `ysm options` 查";
  - **例子**:每个动作至少一个,缺了就在登记那一刻报错;例子必须能按本组的树解析通过;
  - 注意;
  - 相关命令。

```
ftbquests submit <quest>
  Hand in a quest's items, experience or checkmarks from your own inventory.
  <quest> (word) — The quest's id, as list and show print it.
  Examples:
    ftbquests submit 15CDF6A098B95FDA
  Notes:
    Takes the items from YOUR inventory; FTB decides what counts.
    Observation tasks are not supported. Rewards arrive as quest_reward_auto events.
  See also: ftbquests list, ftbquests show
```

### 第 0 层:从 Brigadier 挖

原版和模组的指令没有说明文字,我们也不替它们写,要教她就写技能。`/help <指令>` 由原版 help 执行,我们在它的用法之后接上从 Brigadier 挖出的三样:
- 每个参数的类型;
- 类型自带的例子;
- 她此刻能填的候选值,有上限并给总数。

`/help` 不带参数时,仍是原版那份按她的来源过滤过的清单。

### 写错时,报错就是帮助

两层都一样:
- Brigadier 的原话,加上出错位置;
- 那一层的用法;
- "你是不是要写":两层共用同一个最近候选函数(附录 E)。

### 长度

- 组的帮助一行一个动作;
- 候选值有上限;
- 长清单分页(`Listing`);
- 例子和注意只写在动作的帮助里。

## 十、提示词与技能

- **`command` 工具的描述**照 Claude Code 的工具描述写:动词起头("Runs one command line and returns its output"),只说它做什么、环境什么样,不说"谁给了你"。分两段写两层:不带 `/` 的执行 `<commands>` 里列出的命令组,用 `help` 和 `<组> --help` 查;带 `/` 的执行原版和模组指令,用 `/help <指令>` 查,按你自己的权限执行、可能要问主人。
- **`<commands>` 索引**照旧列出第 1 层已安装的命令组,各一句话。
- **技能**讲"什么时候、怎么用",附一两个例子,不抄语法。
  - 同一件事第 1 层已经有包装的,技能教她用包装版;
  - 模组原生指令就够用的,技能教她用 `/` 写法,我们不再包一层。

## 十一、扩展点与模组联动

判据不变:**动作已有、模组只是多了名词、意图不变**,就开扩展点;真正的新动作,放进模组自己的命令组(组名就是模组 id)。同一件事只留一个入口。

- **注册表**:原版读的注册表,模组本来就在往里写,什么都不用做。
- **行为提供者**:同一个动作有多个来源,例如 `GearSource`(`docs/curios-gear-slots.md`)。
- **输出片段**:同一个查询由多家往里加,例如身体状态的片段。
- **名词解析器**:动作不变,能指的东西变多,例如地点解析,等真做地图联动时再开。
- **给模组补命令**:模组 A 的原生指令不够用、权限对不上、执行完不报结果,就在第 1 层的 `A` 组里包装或补齐,插件只在 A 在场时加载。
  - A 的原生指令已经能做好的事,不再包一层,写技能教她用 `/` 写法。
- **不嫁接**:插件只拿到自己那一组,够不着别的组,也不往第 0 层挂任何东西。

## 十二、外脑(MCP 模式)

外脑看到的是同一批快捷工具,加上 `command` 工具,走同一个执行入口。

## 十三、不变的东西

- 权限层按动作裁决;身体发生的事都要告诉她。
- 任务槽、回执、打断、叫停不变。
- GameTest 从入口调用:`command` 工具、快捷工具,以及 `/numen drive`。

## 十四、测试

- **单元测试**:
  - 两层路由:`/` 开头走第 0 层,其余走第 1 层,两侧执行的分派都要覆盖;
  - 第 1 层没有 `numen` 前缀的解析、帮助快照、写错时附用法;
  - 快捷工具的 schema;
  - 权威声明:包装类动作的作用对象写死为她。
- **GameTest**:
  - 同源:同一件事分别从快捷工具和 `command` 调用,结果一致;
  - 同名:`ysm …`(第 1 层)与 `/ysm …`(第 0 层)互不干扰;
  - 第 0 层:`/help` 不问主人,`/setblock` 没有规则时问主人,没有权限如实失败,写错附用法和"你是不是要写";
  - 玩家看不到她的任何命令;
  - `/numen drive` 与 `command` 结果一致,两层都要测。
- **迁移期**:原来的测试改从新入口调用,断言不放宽。

## 十五、路线

| 步 | 内容 | 状态 |
|---|---|---|
| 0 | 基线统计 | 已完成 |
| 1 | 底座:命令组声明、帮助、快捷工具同源、两侧分发 | 已落地(附录 A) |
| 2 | 三个联动插件的 8 个工具改成命令 | 已落地(附录 B) |
| 3 | FTB Quests 命令组;Curios 走装备位扩展点 | 已落地 |
| 5 | 原版指令入口与权限层 COMMAND | 已落地(附录 C),第 6 步并入唯一执行入口 |
| 6 | `command` 工具、唯一执行入口、出厂规则、帮助与报错、`/numen drive` | 已落地(附录 D、E) |
| 7 | 分层:见下 | 已落地(附录 F) |
| **4** | **核心工具迁移**:直接进第 1 层,高频的提升为快捷工具 | 下一步 |

**第 7 步的内容**(落地细节见附录 F):
- **撤回**第 6 步里把她的命令挂进 MC `/numen` 的做法:
  - `NumenCommands` 里给她的节点、按 NumenPlayer 过滤可见性的 `requires`;
  - 自定义参数类型在 MC 注册表的登记(`HerArgumentInfo`、平台服务的 `registerArgumentType`);
  - 从 MC 指令来源里取调用上下文的访问器(`CommandSourceStackAccessor`),第 1 层的处理函数直接拿 Numen 自己的来源对象。
- **第 1 层去掉 `numen` 前缀**:组名直接就是一级命令;根下的 `help`、`--help` 属于第 1 层。
- **加上 `/` 标记**:行首的 `/` 从"可有可无"改为分层标记。第 0 层走 `CommandRunner` 现有的原版分支,也就是原来 `numen mc` 的那条路。
- **权威声明**:加进动作的声明层。YSM 的包装改用声明,不再自己拼 `withPermission(4)`。
- **同步更新**:提示词、`command` 工具的描述、各插件技能文档与帮助里的例子,改成新写法。
- **留用**:`CommandRunner`、回显收集器、`PendingCommands`、权限层的 COMMAND 动作与出厂规则、帮助与报错(附录 E)、快捷工具同源、`/numen drive`。

## 十六、待核实

- 第 1 层的一级命令名要和核心领域名、将来迁来的动作名统一规划,避免 `goto` 这类动词和组名混用得不一致(第 4 步定)。
- ~~权威声明的形状~~:已定,`Authority` 的两种,见附录 F。

## 十七、不做的

- 不做 shell:没有管道、变量、重定向。
- 不往第 0 层挂东西,不嫁接。
- 不做"第 1 层认不出就转给第 0 层":那是兜底,会让同一个词两层都能答。
- 技能里不抄语法。
- 不写死指令白名单:出厂规则是数据,主人能改。

## 附录 A:第 1 步落地时定下的细节

> 第 7 步之后(附录 F),第 1 层的一行不再以 `numen` 打头,组名直接是一级命令,下文的 `numen …` 写法都去掉这个前缀读;
> 服务端重新有 Numen 自己的调度器(两棵树由同一个生成器长出,见附录 F),下面第二条的"服务端不再有 Numen 自己的调度器"
> 随之作废。

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

> 第 7 步去掉了 `numen` 前缀(附录 F):下表的命令读作 `kaleidoscope recipes …`、`ysm switch …` 等;YSM 的三个动作
> 改为声明借服务器的权威(附录 F)。

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

> 第 7 步已撤回其中"把她的命令挂进 MC 指令树 `/numen`"的部分(下文"一份声明,两棵树"里的 MC 那一棵、"路由"、"注册与可见性"
> 里她的节点、"调用上下文"、"参数类型登记",以及快捷工具经权限层裁决 alias 那一行),执行入口、征询挂起、重放、drive 留用;
> 见附录 F。

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

## 附录 E:第 6 步(帮助与报错)落地时定下的细节

> 第 7 步之后(附录 F),下文的原版 `help <指令>` 在她的一行里写作 `/help <指令>`(第 0 层),`numen gt_long …` 写作
> `gt_long …`(第 1 层);挖法与"你是不是要写"不变。

代码在 `api` 的 `com.dwinovo.numen.cli`:`BrigadierHelp` 挖别的指令的帮助,`Completions` 是补全引擎的候选与"你是不是要写"。Numen 自己命令的帮助(第九节第一小节)在第 1、6 步已经落地(附录 A,例子必填见 `Action`)。

- **`help <指令>` 接在哪**(`CommandRunner.perform`)。`help <指令>` 照常交给原版执行,原版说的用法由 `Echo` 收下;跑成了,回执在原版原话之后接上 `BrigadierHelp.mine` 挖出的几项。用法只有原版 `help` 这一个来源,这里不再写一遍。
  - 不在执行前截下来自己答:那样就要把原版 help 的那几步(解析、取最后一格、`getSmartUsage`)再写一遍,成了两份;也绕开了 `performPrefixedCommand`(别的模组在指令事件里拦或记指令)。
  - 不在 core 另注册一个只给她的节点:`help` 是原版的根,往它下面挂节点就是嫁接;另起一个名字,又多一个入口、和 `numen help` 撞义。
  - 认的是根名 `help` 且带了参数的一行;`help` 不带参数照旧是原版的清单。原版 help 自己失败了(没有这条指令),回执照旧是失败的回显,不接。
- **挖什么**(`BrigadierHelp`)。
  - 参数:从解析到的最后一格往下,和 `getSmartUsage` 走同一条路(一个子节点就接着往下,几个就各列一格不深入,可执行的一格之后只列下一格,redirect 不跟),所以列的正是原版那一行用法里出现的参数;她用不了的格不列。想看更深,就像原版那样多写一截(`help give @s`)。
  - 类型的称呼:`ArgumentType` 在 `COMMAND_ARGUMENT_TYPE` 注册表里的名字,括号里接这一格的设定——设定由类型自己的 `ArgumentTypeInfo.serializeToJson` 写出,和原版导出指令树(`ArgumentUtils`)是同一份。
  - 例子:`ArgumentType#getExamples`,没有就不写。
  - 接下来能写什么:整行读通了,是下一格的候选(补一个空格再补全);最后一截读不通(物品 id 的开头这类),是以它开头的候选,和按 Tab 一样。最多列 10 个,超出时写"10 of N"。那一截对不上任何候选时接上"你是不是要写"。
  - 长清单不翻页,靠多写一截缩小:`help` 这一行归原版解析,加不进 `--page`。只有读不通的那一截会缩小——`word`、玩家名这类怎么写都读得通的参数,写半截会被当成写完,候选跳到下一格。
- **补全引擎按她的来源**(`Completions.at`)。Brigadier 的 `getCompletionSuggestions` 不看 `requires`:原版客户端手里的树是服务器按它的来源滤过的,用不着看;服务端手里是整棵树,原样调会列出她用不了的(没有 OP 时的 `give`、`/numen` 下玩家的管理指令)。所以照引擎的同几步走(找光标所在那一层 → 每个子节点按同一个上下文给候选 → 合并),只多一条 `canUse`。一个节点给候选时抛 `CommandSyntaxException` 的,照引擎的口径算它没有候选。服务端的候选提供者当场算完,这里 `join`。
- **你是不是要写**(`Completions.didYouMean`,Numen 命令与别的指令同一个函数)。
  - 位置:解析停下的地方(`ParseResults` 的读头),她写的那个词到下一个空格为止;候选是那个位置上的全部(不按她写的前缀滤)。
  - 远近:编辑距离,相邻两个对调算一步。五个字符以内一步、更长的两步;只列最近的那一档,至多 3 个;和她写的一字不差的不算。依据:绝大多数错字只差一步,长词偶尔两处;短词放到两步就会指向不相干的词。
  - 资源 id 照原版补全的规矩(`SharedSuggestionProvider#filterResources`):不带命名空间的,按 `minecraft:` 下的路径比。
  - 一行读完才发现缺东西(`numen gt_parse`、`give @s`)不给——那不是写错。服务器不让她用的根不指给她(不在候选里)。
  - 接在哪:Numen 命令在那一层的帮助之后(`NumenCli.problem`);别的指令在 `Usage:` 之后,或"没有这条指令"那一句之后(`CommandRunner.problem`)。
- **实测**(GameTest,她有 OP 2 级;真服务器上 Brigadier 的内置报错是 MC 的说法):

```
help give
→ ran /help give: /give <targets> <item> [<count>]
  Arguments:
    <targets> minecraft:entity (amount multiple, type players) — e.g. Player, 0123, @e, @e[type=foo], dd12be42-52a9-4a91-a8a1-11c01849e498
    <item> minecraft:item_stack — e.g. stick, minecraft:stick, stick{foo=bar}
    <count> brigadier:integer (min 1) — e.g. 0, 123, -123
  Can go next (10 of 11): @a, @e, @n, @p, @r, @s, gametest_mc_builder, gametest_mc_held, gametest_mc_holder, gametest_mc_landlord

help give @s minecraft:diamond_
→ …Can go next (10 of 12): minecraft:diamond_axe, minecraft:diamond_block, minecraft:diamond_boots, …

give @s minecraft:dimond
→ Unknown item 'minecraft:dimond' at position 8: give @s <--[HERE]
  Usage: /give <targets> <item> [<count>]
  Did you mean: minecraft:diamond?

numen gt_long lingre 40
→ Unknown or incomplete command, see below for error at position 14: ...n gt_long <--[HERE]
  numen gt_long: Test fixture: long work dispatched by a command. Actions:
    numen gt_long linger <ticks> — Stand still for a while, as background work.
  numen gt_long <action> --help explains one action.
  Did you mean: linger?
```

- **执行管线留下的两处**。
  - 同步短活的结果只有一个去处:派它的那次调用的回信口。原先 `TaskDispatch.runSync` 收下回信口却不用,结算后 `CompanionBrain` 另按调用 id 给主人发 `TaskResultPayload`——drive 的发令人收不到,`ServerSource.allowed` 包上的"主人允许了什么"也丢了。现在回信口绑在记录上(`TaskRecord.replyTo`),结算后只从那里回:网络入口来的调用照旧回给发来它的主人客户端,drive 回给发令人,主人点过头的带上交代(GameTest 实测:drive 发令人恰好收到一条最终回执,末尾是"the owner allowed")。没有第二条回执通道。
  - 征询撤回的原因由收尾的一方给真实的那一个(`ConsentDesk.Withdrawal`):任务收场、主人按了停止、她离开了世界、她死了,以及原有的主人不在、被新的顶替、不用再问。`TaskRecord.StopCause` 带着它对应的那一个,叫停一件活和叫停一条等着的指令说同一句。载荷只带是哪一种,主人的客户端按语言文件显示(中英文案都在 `ModLanguageData`)。模型读的拒绝理由(`ConsentDesk.OWNER_ABSENT` 等)不变;撤回的请求没有发起者再读它的结论,理由留空。
- **与正文的出入**。
  - 第九节说长清单一律分页;`help <指令>` 的候选不分页,靠多写一截缩小(见上)。

## 附录 F:第 7 步(分层)落地时定下的细节

代码在 `api` 的 `com.dwinovo.numen.cli`;`/numen` 下玩家那一半在 `entity.NumenCommands`,YSM 的包装在 `plugins/ysm`。

- **撤回的**(附录 D 里挂进 MC `/numen` 的那一半)。
  - 删掉:`HerArgumentInfo`,平台服务的 `registerArgumentType` 与两个加载器的实现(NeoForge 的 `ARGUMENT_TYPES` 延迟注册一并删),
    `CommandSourceStackAccessor` 与它在 mixin 配置里的一行,`NumenCli.herNodes`,`Echo.of` 与"这次调用由 Numen 答了"。
  - `ArgType` 的 `id()`、`string()` 从具名类收回成方法引用:具名只为按类登记进注册表。
  - `Echo` 只剩第 0 层的回显收集(`receipt` 收成回执,`lines` 交回借权的处理函数)。
  - 快捷工具在服务端把读好的参数直接交处理函数,不再拼出它作为 alias 的那一行去过权限层。
- **类与职责**(组合关系,自上而下)。
  - `CommandTool`:她唯一的能力。`invoke`(主人客户端)交 `NumenCli.run`;`onServerCall`(服务端)交 `CommandRunner.line`。
    `/numen drive` 交 `CommandRunner.run`,同一个入口。
  - `Line`:一行落在哪一层,路由的唯一规则,两侧都经它分。
  - `NumenCli`:第 1 层。登记处,持有两棵 `CommandTree`(主人客户端一棵、服务端一棵),两侧共用的报错(`problem`)与出错那一层的
    帮助(`helpAt`);`run` 是客户端这一侧,`serve` 是服务端这一侧。
  - `CommandTree<S>`:一侧的第 1 层树,就是一个 Numen 自己的 Brigadier 调度器,由声明长出来;构造时给一条"这个动作在这一侧执行吗",
    决定哪些动作在这一侧长参数、可执行。源对象就是 Numen 自己的来源(`ClientSource` / `ServerSource`),处理函数直接拿到它。
    登记时查例子也是现长一棵(每个动作都长参数,只解析)。
  - `CommandRunner`:服务端唯一的执行入口。`line` 按 `Line` 分:第 1 层交 `NumenCli.serve`;第 0 层走原来的原版分支
    (解析 → 权限层 `command(根名)` → `performPrefixedCommand` → `Echo` 收回显 → 回执;要问就挂在 `PendingCommands`)。
  - `Authority` / `Action.authority` / `ServerSource.onHer()` / `OnHer`:以谁的权威执行,与借服务器权威的唯一途径(见下)。
  - `NumenCommands`:`/numen` 只剩玩家的管理指令。
- **路由**。行首 `/` 是第 0 层,否则是第 1 层,两侧同一条规则。
  - 主人客户端:第 0 层的一行原样送服务端;第 1 层在客户端的树上解析,解析到服务端动作(走到了它的名字,不是它的 `--help`)
    原样送服务端,客户端动作、帮助、写错的当场答。
  - 服务端:第 0 层走原版分支;第 1 层在服务端的树上解析、执行。
  - 第 1 层认不出的一行在第 1 层报错,不转第 0 层(`give @s …` 不带 `/` 就是"未知命令"加第 1 层的根帮助)。
  - 调用记下的是她写的原样(带着 `/`),重放落在同一层。改名前落盘的 `numen …` 调用重放时是第 1 层里一个不存在的组,如实报错,
    不做转接。
- **去掉前缀**。
  - 组名直接是一级命令,根下 `help`、`--help`;组名与 `help` 撞、两组同名在登记时报错(沿用原有检查);例子、相关命令写
    `<组> <动作>`,多写 `numen` 的例子登记时报错。
  - `<commands>` 索引和系统提示里的技能清单同一个形状:"The following command groups are available for use with the command
    tool:",每组一行 `- 名字: 描述`;根帮助的最后一句提示带 `/` 的是原生指令;第 0 层写不通时附的是 "/help lists the commands you can run."。
  - `command` 工具的描述分两段写两层(第十节)。
- **`/numen` 只给玩家**(第六节的判断)。她的命令不在 MC 树上,第 1 层不需要"是不是她"的过滤;但她作为玩家,经第 0 层照样敲得到
  MC 树上的每一条。召唤、设置、权限、征询、drive 是给人的,她不该用,所以 `/numen` 这个根只给不是她的来源,在
  `NumenCommands.graft` 建根时一处定下,下面每一格(含 core 的 `debug`、`profile`、`pad`)随之。她敲 `/numen …` 当场失败:
  "the server does not let you use /numen",不问主人;她的 `/help` 里没有 `/numen`。
- **权威声明**。
  - 形状只有两种:`Authority.HERS`(默认)与 `Authority.SERVER_ON_HER`。声明组合在动作上:`.authority(Authority.SERVER_ON_HER)`,
    只有服务端动作能声明,封口后不能改。帮助里写明 "Runs with the server's authority, and only on you.";她自己的是默认,不写。
  - 借权的唯一途径是 `OnHer`:只有声明了的动作,处理函数才从 `ServerSource.onHer()` 拿得到,没声明的来拿就抛出。
    `run(前段, 值…)` 执行 `<前段> <她> <值…>`,她的名字由它写进去(名字与值按 Brigadier 的 `escapeIfRequired` 加引号),
    调用方够不着别人;来源是服务器自己的(`createCommandSourceStack`,等级 4),回话由 `Echo` 收回。`next(前段, 值…)` 是那一格
    之后的补全候选(同一个 `Completions.at`),还原成值本身。它不经权限层:权威就在声明里给。
  - 她自己权威的动作没有调第 0 层的途径:她要执行原生指令就自己写 `/`,走第 0 层的权限层。
- **YSM**。`ysm options`、`switch`、`emote` 都声明 `SERVER_ON_HER`;模型清单、贴图清单、`ysm model set`、`ysm play` 都经 `OnHer`。
  `Ysm` 里自拼的 `withPermission(4)` 来源、`Heard` 回显收集、补全去引号删掉(换成 `OnHer`、`Echo`、`Completions`)。
  授权镜像(`OwnerSync` 的 `ysm auth <她> clear|add`)不是她的动作,是服务器按主人的授权做的对账,以服务器自己的来源执行
  (`createCommandSourceStack`,不另抬等级),回显照原版进服务器日志。
- **第 1 层与权限层**。第 1 层的一行不是 `command(…)` 动作,整行不送权限层;出厂 allow 表去掉 `command(numen)`。其中身体对世界的
  动作照旧逐个裁决。所以"主人点过头的调用,回执末尾交代允许了什么"只出现在第 0 层;GameTest 相应拆成两条:drive 派的同步短活
  只回一条最终结果,drive 执行、主人点过头的 `/setblock` 回执末尾交代主人允许了什么。
- **相关命令什么时候查**。任一侧的树第一次被读(执行一行、系统提示要索引)时查全。服务器不再建"她的指令树",所以断掉的引用
  在第一次被读时报出,不再是开服那一刻。
- **实测**(`/help give`、写错的两条、`/numen …` 出自 GameTest(`/help give`、`/give …` 两条她有 OP 2 级);`help` 列的是只装了 YSM 时的样子,`ysm --help`、`ysm switch --help` 按 YSM 登记的声明逐字写出,YSM 不在 GameTest 里;"…" 是这里省略的):

```
help
→ <group> <action> [arguments]. Command groups:
    task — The background task and your pending timers.
    ysm — Yes Steve Model looks: what you wear and can switch to, switching, emotes.
  <group> --help lists a group's actions. A line starting with / is a native command instead (/help lists those).

ysm --help
→ ysm: Yes Steve Model looks: what you wear and can switch to, switching, emotes. Actions:
    ysm options — Your model and texture now, the models you can switch to, and this model's textures.
    ysm switch <model> [--texture <string>] — Switch to another model.
    ysm emote <animation> — Play one of this model's emotes, or stop the one playing.
  ysm <action> --help explains one action.

ysm switch --help
→ ysm switch <model> [--texture <string>]
    Switch to another model.
    Runs with the server's authority, and only on you.
    <model> (string, quote it if it has spaces) — The model to switch to. Values: a model id exactly as ysm options lists it.
    --texture <string> (string, quote it if it has spaces; optional) — Which of the model's textures to wear. Values: …
    Examples:
      ysm switch misc/1_alex
      ysm switch "抽象鸣潮 菲比.ysm"
    Notes: …
    See also: ysm options

/help give
→ ran /help give: /give <targets> <item> [<count>]
  Arguments:
    <targets> minecraft:entity (amount multiple, type players) — e.g. Player, 0123, @e, @e[type=foo], dd12be42-…
    <item> minecraft:item_stack — e.g. stick, minecraft:stick, stick{foo=bar}
    <count> brigadier:integer (min 1) — e.g. 0, 123, -123
  Can go next (10 of 12): @a, @e, @n, @p, @r, @s, gametest_mc_builder, gametest_mc_held, gametest_mc_landlord, …

/give @s minecraft:dimond
→ Unknown item 'minecraft:dimond' at position 8: give @s <--[HERE]
  Usage: /give <targets> <item> [<count>]
  Did you mean: minecraft:diamond?

gt_long lingre 40
→ Unknown or incomplete command, see below for error at position 8: gt_long <--[HERE]
  gt_long: Test fixture: long work dispatched by a command. Actions:
    gt_long linger <ticks> — Stand still for a while, as background work.
  gt_long <action> --help explains one action.
  Did you mean: linger?

/numen player summon gametest_mc_twin        (她敲玩家的管理指令)
→ the server does not let you use /numen. /help lists the commands you can run.
```

- **与正文的出入**。
  - 第五节"写错了当场回答":主人客户端的树上服务端动作只有名字与帮助,服务端动作的参数写错由服务端报(同一个
    `NumenCli.problem`,两侧一字不差);根、组、动作名写错与客户端动作写错在客户端答。
  - 第六节"不需要按是不是她过滤可见性":对第 1 层成立;`/numen` 根仍排除她,那是第 0 层的可见性(见上)。
