# Numen CLI:她只有一个能力——执行一行命令

状态:
- **已落地**:第 1–7 步,细节见附录 A–G。本稿正文描述的就是第 7 步"分层"之后的样子;第 6 步里"把她的命令挂进 MC 指令树 `/numen`"的做法已撤回(附录 F);第 4 步核心工具迁移按分层后的形态做完(附录 G)。
- **10-03 起模型的入口只剩 `lua` 一个工具**(附录 K):登记处的每个动作是一个 Lua 函数,正文里"执行一行命令"的那一层现在是
  人在聊天框里的第二个前端;模型写的是程序,`command` 工具与快捷工具删了。
- **10-04 技能改由 `skill` 工具装**:`skill load` 与 `numen.skill.load` 删了,见 `docs/shell.md` §七;附录里的 `skill_load` 是当时的记录。
- **10-04 计划改由 `todo` 工具记**:`numen.todo.write` 删了,见 `docs/shell.md` §七;附录里的 `todowrite`、`todo.write` 是当时的记录。
- **10-04 API 第二版**(附录 N):全名 `numen.<组>.<函数>`、值带方法、没有区域与设计这类名词;附录里更早的命令名是当时的记录。
- **10-04 命令行前端删了**:登记处、参数类型、Brigadier 树与一行字的读法(`cli` 包)都没有了;API 函数是带 `@Fn` 的静态方法,签名就是
  契约,脚本是唯一的入口,`/numen drive` 跑一段 Lua。见 `docs/shell.md` §十三;本稿正文与附录是当时的记录。

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
   - 第 1 层的一级命令,是核心领域名(`task`、第 4 步迁来的 `move`……),或者模组 id(`ysm`、`ftbquests`……)。
   - 和第 0 层同名也不冲突:`ysm switch` 是第 1 层的,`/ysm model set` 是第 0 层的。
5. **第 1 层命令的三种来源**:
   - 包装第 0 层的指令,比如 `ysm switch`:限定只作用于她、按主人的授权、执行后回读确认;
   - 用模组的 API 直接补上模组没给的功能,比如 `ftbquests submit`;
   - 核心动作,比如 `task status`,以及第 4 步迁来的 `move goto`、`work dig` 等。
6. **快捷工具是 alias**:第 1 层里高频的几条提升为独立工具,名字就是 `组_动作`,用同一个处理函数,回执也一样。
7. **权限**:
   - 第 1 层每条命令自己声明以谁的权威执行,默认是她自己。包装类命令可以借服务器的权威,但作用范围写死在代码里。
   - 身体对世界的每个动作,仍然由权限层按动作裁决。
   - 第 0 层的每一行,都是一个 `command(根名)` 动作,出厂规则(数据)放行只读和只说话的那些,其余的问主人。
8. **知情**:回执原样返回;长活开始、结束对得上是哪次调用;身体的变化照常进状态。
9. **学会用**:第 1 层用 `--help`;第 0 层用 `/help <指令>`。想让她会用某个模组的原生指令,就写技能,不写代码。

## 三、结构

```
模型
 ├─ 快捷工具 move_goto / work_dig / … ← 第 1 层里高频命令的 alias
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
- 它操作的对象按位置写(一条命令只有一类,可以多个),其余写成 `--name value`,没有必填的标志(附录 J);
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
- **叫什么**:工具名由命令路径生成,`组_动作`(`move goto` 提升成 `move_goto`),登记时不另起名字(附录 G)。
- **提升哪些**:按调用频率定。现在提升的是 status_self、status_owner、scan_around、scan_blocks、scan_entities、scan_block、move_goto、work_dig、skill_load、task_stop 十个(附录 G)。
  - `task status` 与 `task timer` 只留命令:task_status 主要被拿来轮询,而收尾本来就会以 `task_finished` 送到。
  - 基线建议里的 build 不再是工具:建造改为 `numen.build.place` 放给它的格、`numen.build.raise` 盖完一整份(附录 G、附录 N)。
- **独立工具**:除了快捷工具与 `command`,工具表里只留一个 `todowrite`——输入本身是一份结构化清单的动作留作独立工具,这是这条规则下唯一的例外(附录 G)。

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
- 长清单分页(`Listing`,按下面的输出预算切页);
- 例子和注意只写在动作的帮助里。

### 输出预算

一条命令的输出至多 **2000 行或 50KB**(UTF-8),先到哪个算哪个。只在 `Listing` 一处定义(`MAX_LINES`、`MAX_BYTES`),
形状照 pi 的 `truncate.ts` 与 Claude Code 的读文件:

- **会随数据变长的输出一律是 `Listing`**:按预算从头切页,整条整条地放,保留开头;放不下时末尾写明一共几条、这一页是第几到
  第几条、下一段怎么取——她读不了文件,下一段就是同一条命令加 `--page`:

  ```
  [Showing 1-12 of 43. Use inv recipes minecraft:stick --page 2 to continue.]
  ```

  最后一页不写这一句。抬头与结尾算在预算里,这一句不算(和 pi 一样只算内容)。
- **一条条目自己就比一页大**(一步极长的设计、一份很长的技能里的一行):单占一页,只放得下的开头(按字节,不切断一个字),
  后面注明 `[This entry is N bytes; only its first M fit in one page.]`。
- **不随数据变长的输出**(一个对象的回执、一次改动的结果、本身有上限的清单)天然在预算内,不分页。哪些命令属于哪一种,
  逐条审查的结论在附录 H。
- **预算之外还有线上的上限**(`Wire`,附录 H):那是网络一个包的硬顶,不是分页。回执万一超过它,网络层换成一条如实说明的
  失败回执,连接不断。

## 十、提示词与技能

- **`command` 工具的描述**照 Claude Code 的工具描述写:动词起头("Runs one command line and returns its output"),只说它做什么、环境什么样,不说"谁给了你"。分两段写两层:不带 `/` 的执行 `<commands>` 里列出的命令组,用 `help` 和 `<组> --help` 查;带 `/` 的执行原版和模组指令,用 `/help <指令>` 查,按你自己的权限执行、可能要问主人。
- **`<commands>` 索引**照旧列出第 1 层已安装的命令组,各一句话。
- **技能**讲"什么时候、怎么用",附一两个例子,不抄语法。
  - 同一件事第 1 层已经有包装的,技能教她用包装版;
  - 模组原生指令就够用的,技能教她用 `/` 写法,我们不再包一层。
- **文字里怎么写命令**:技能、系统提示、工具与动作的说明里提到一条命令,一律写进反引号(或 ``` 代码块的一行),写成能照抄的样子——一条完整的命令(`use shift 5`),或只点名一组、一个动作(`inv recipe`)。占位符(`<x>`)与省略号不是命令的写法。这样写的每一行都由防漂移测试按命令树读一遍(附录 G)。

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
| 4 | 核心工具迁移:直接进第 1 层,高频的提升为快捷工具 | 已落地(附录 G) |

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

- ~~第 1 层的一级命令名统一规划~~:第 4 步已定,一级命令全是领域名(`move`、`work`、`fight`、`build`、`use`、`inv`、`gear`、`scan`、`status`、`locate`、`memory`、`skill`、`task`),动词只做动作名(`move goto`);快捷工具名由路径生成(`move_goto`),见附录 G。
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
- **帮助是树上的普通节点**:根下的 `help` 与 `--help`,每组、每个动作下的 `--help`,和别的命令同一次解析认出来。组与根的列表按输出预算切页(第九节),`--page N` 翻页,放不下时说一共几条、这页是哪一段、下一页怎么取。
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
- **客户端动作的插件也在两侧登记命令**:`tlm` 穿模型的三个动作(`models`、`wear`、`remove`)在主人客户端执行,命令组照样在 `NumenPlugins.register` 块里直接登记(专用服务器上只为帮助),插件原来放在 `onClient` 里的登记工具那两行随之删掉。同一组里管女仆的动作在服务端执行,见附录 I。
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

## 附录 G:第 4 步(核心工具迁移)落地时定下的细节

代码在 core 的 `tools/*/*Commands`(各组的登记)与 `core.build`(建造的原语、设计、建成的房子);机制的增补在 api 的
`com.dwinovo.numen.cli`(标志组、只读不执行的读法、写回命令行、文字里的命令)。

### 分三批迁完

一级命令全是领域名,动词只做动作名;高频的几条提升为快捷工具(第八节)。

| 批 | 原来的工具 | 现在的命令 | 提升成快捷工具 |
|---|---|---|---|
| A 感知与定位 | get_self_status、get_owner_status、get_world_info | `status self`、`status owner`、`status world` | status_self、status_owner |
| | look_around、scan_blocks、scan_nearby_entities、inspect_block、inspect_block_storage | `scan around`、`scan blocks`、`scan entities`、`scan block`、`scan storage` | scan_around、scan_blocks、scan_entities、scan_block |
| | locate_structure、locate_biome | `locate structure`、`locate biome` | |
| B 背包、交互、札记、技能 | craft、lookup_recipe、eat、drop_items、take_items | `inv craft`、`inv recipe`、`inv eat`、`inv drop`、`inv take` | |
| | equip_item | `gear wear`、`gear remove` | |
| | interact_at、interact_entity、inspect_gui、close_gui、sleep | `use block`、`use ahead`、`use entity`、`use gui`、`use close`、`use sleep` | |
| | transfer | `use transfer`、`use shift`(本批,见下) | |
| | remember、recall、forget、load_skill | `memory remember`、`memory recall`、`memory forget`、`skill load` | skill_load |
| | task_status、task_stop、set_timer | `task status`、`task stop`、`task timer` | task_stop |
| C 长活 | goto、follow、plan_route | `move goto`、`move follow`、`route plan`(09-30 起;原 `move route`) | move_goto |
| | mine、collect_items、fish | `work dig`(09-30 起;原 `work mine`)、`work collect`、`work fish` | work_dig |
| | attack | `fight attack` | |
| | blueprint、blueprint_read、build | `build` 组(本批,见下) | |
| | scaffold_materials | `throwaway` 组(见下;10-03 起是一趟路描述里的 `materials`) | |

- A 批给参数类型补了小数、几个固定值之一、id 或 `#标签`、一串值(附录 B 的几种之外);C 批让一串值读到下一个标志为止,
  所以它也能当标志(`--avoid_break a b --count 3`),并补了方块或坐标格类型(路线规格的禁令)。
- "留空表示另一件事"一律拆成两个动作(附录 B 的规矩):`use block` / `use ahead`、`gear wear` / `gear remove`、
  `use transfer` / `use shift`。
- 工具表剩下:十个快捷工具、`command`、`todowrite`。

### 快捷工具名:`组_动作`

快捷工具名由命令路径机械生成:组名、下划线、动作名(`Action.toolNameOf`),`promote` 只收工具描述,不收名字。

- **一个能力一个名字**:`move goto` 与 `move_goto` 是同一个动作的两种写法,可以机械地互推,不需要记一张对照表。
  手起的名字(`look_around` 对 `scan around`、`get_self_status` 对 `status self`)让她分不清哪个是命令、哪个是工具,
  真机里她把 `look_around` 写进了 `command`,只得到"未知命令"加整份组列表。
- **写反了直接指路**:`command` 的一行解析失败、第一个词又按同一个写法反推得到一个提升过的动作(`Action.promotedAs`,
  和生成写在同一处)时,报错只说这是工具名:直接调工具,或者写 `组 动作`;不倾倒整份组列表。
- **旧名不留兼容**:会话记录里的旧工具调用照原样回放(服务商不要求历史里的工具名还在工具表上);重启前落盘的活按旧
  工具名接不回来时,`TaskPersistence` 照既有的规矩发一条 task_finished 说清楚;外接大脑配置 `mcp_server.json` 的
  `hidden_tools` 出厂写着 `load_skill`,读档时认它一次、存档只写 `skill_load`(不认的话升级后它会对外接大脑露出来)。

### 建造:原语命令、设计与建成的房子

设计稿见 `docs/build-designs.md`。`build` 工具与它的 ops JSON 解析删掉,建造全部走命令。

**类与职责**(组合关系,自下而上):

- `Primitive`:七个原语(`set place line layer cylinder sphere copy`)。每个是一张参数表(命令行的写法,最后是 `--mask`)
  加一步画法:把读好的参数画到一张 `Canvas` 上。当场执行与记进设计读的是同一份参数、画的是同一些格子。
  - `set` 照写下的方块状态直写,`place` 像玩家右键那样放(朝向随视线):原来 `set` 按"写没写状态"分两种,拆成两个动作。
- `Canvas`:画到一半的图。后写覆盖先写;每一步画了哪些格另记一份(`build show` 逐步报)。没画过的格读**底子**
  (`Ground`):当场执行时底子是世界(坐标就是世界坐标,`copy` 抄已经立着的),设计里底子是空的(坐标相对原点,`copy`
  只抄这份设计前面画的)——设计因此和摆到哪儿无关。
- `Placement`:原点落在锚点、绕原点顺时针转几个 90°,位置与方块朝向都由原版算。设计与蓝图文件同一条变换。
- `Layout`:摆到世界里的一份施工图——目标格,外加蓝图文件才带的方块实体数据、摆设、逐格料单与掉格数。原来的
  `BlueprintStore.Loaded` 就是它。
- `Design`:一份设计 = 名字、所属主人、作者、创建时间与一串原语命令行。读与写都过 `NumenCli.read`(见下),每一步读成
  原语与参数、画一遍,读不通或画不出来整份不认,报出第几行(文件)或第几步(改设计时)。步骤按原语的参数表写回规范的
  一行(`Design.Step.line`)。
- `Designs`:设计库 `schematics/designs/<名>.numen`,每次从盘上读;存之前把要写的文本照读的规矩读一遍。设计与蓝图文件
  共用名字空间,`kindOf` 是"这个名字指什么"的唯一判据(两样都没有、两样都叫这个名都如实报)。
- `Built`:建成的房子(主世界的 SavedData,一份记全部维度)。一栋 = 施工图名 + 维度 + 落点(`Site` 认一栋),记着朝向、
  谁盖的、何时,以及她**实际放下的每一格**(格 → 方块)。执行器每放下一格、拆掉一格就记一笔,活怎么收场、服务器停在
  哪一刻都照实。
- `Changes`:把一处变成施工图的样子要动哪些格——施工图的每一格和世界比(补、换),加上这一栋记录里有、施工图里没有、
  世界里仍是记录里那个方块的格(拆)。没有要动的就不派活。交给执行器的是施工图的全部格加要拆的格:执行器本来就逐格对着
  世界跳过已经对的格。
- 执行器(`BuildCompanionTask`)不变,只多三处:拆除格(`Target.removes`)只在那一格仍是她放的方块时动手;放下与拆掉
  随手记进 `Built`;回执写明放了、换了、拆了多少格。整单的让路档位撤掉,每格自己的 `--mask`,不写是 `carve`。
- `BuildOps`(当场执行、`build at`、`build built`)、`DesignOps`(新建、改步、删除、列出、展示)、`BuildCommands`
  (登记)。

**命令的实际样子**:

```
build new shed
build layer 0 0 0 #### #### #### --block cobblestone --into shed
build layer 0 1 0 #### #..# #### --block oak_planks --up_to 1 --into shed
build set air 1 1 0 --into shed
build show shed
build at shed 120 64 -35 --rotation 90
build step shed 1 layer 0 0 0 #### #### --block cobblestone
build at shed 120 64 -35 --rotation 90
build built
build place crafting_table 120 64 -30
```

**设计文件的实际样子**(`schematics/designs/shed.numen`,改了就覆盖):

```
# Numen design shed
# author: Aria
# owner: d3e2c687-5ff0-4a20-9212-10cb825d5412 Dwin
# created: 2026-09-26T08:18:51Z
build layer 0 0 0 #### #### #### --block cobblestone
build layer 0 1 0 #### #..# #### --block oak_planks --up_to 1
build set air 1 1 0
```

**`build show` 的实际输出**(GameTest 里跑出来的):

```
shed: 3 step(s), 4x2x3 at x 0..3, y 0..1, z 0..2, 22 cells — cobblestone x12, oak_planks x9
1. build layer 0 0 0 #### #### #### --block cobblestone
   layer at x 0..3, y 0, z 0..2, 12 cells: cobblestone x12
2. build layer 0 1 0 #### #..# #### --block oak_planks --up_to 1
   layer at x 0..3, y 1, z 0..2, 10 cells: oak_planks x10
3. build set air 1 1 0
   set at x 1, y 1, z 0, 1 cells: no materials
It is your owner's design; you can change it. Written by Aria.
She carries enough for all of it.
```

`data` 里另有整份的料单(拿去采集的全量清单)与生存画像的 `short_of`,不随页变。步骤是一条条的条目,按输出预算
(第九节)分页:一份设计写得长,第一页末尾说一共几步、这页是哪几步、`build show shed --page 2` 取下一页。蓝图文件的
`build show` 就是原来 `build blueprint_read` 不带落点的那一份(尺寸、格数、全量料单、按组件全等收的料、按层分布),
它不随图纸变长,只有一页。

**`build show <名> --layer <y>`:一层的俯视图。** 她写一层、看一层、改一层,不必动笔前把整栋在脑子里算完(真机里为此在
三轮思考里烧了约 8 万 token)。画的是那一层建成之后的最终样子(`Slice`):设计按步骤画完的那张图(后写覆盖先写),蓝图文件
读出来的格;门的上半、床头不在施工图里、建成后在,照建成的样子画上,那一格后来被别的一步画过的留后画的。格子和
`build layer` 的字符网格同一个约定(一行一个 z,北在上,每行从西往东,`.` 是什么都没画),图例写成 `--legend` 的样子、方块
状态照 `/setblock` 写全,抄回 `build layer` 画出来是同一层(单测验);行首标 z、头上一行标 x 的末位数;所有层用整份的 x、z
范围做框,上下两层叠得上。行多了按输出预算分页。字从方块名的字母里挑(格数多的先挑),挑不到按数字、符号、希腊字母的顺序,
不用 `.`、空格、`-`(抄进 `build layer` 时以它开头的一行会被当成标志)、引号、反斜杠与 `=`。

```
build show cottage --layer 1
→ design cottage at y=1 (it spans y 0..4), seen from above: x -1..7 left to right (east), z -1..4 top to bottom (south); the x row gives each column's last digit; . = nothing here.
  x     901234567
  z -1  .........
  z  0  .ooooooo.
  z  1  .oc....o.
  z  2  .o.....o.
  z  3  .o.....o.
  z  4  .oooOooo.
  legend: o=oak_planks c=chest[facing=south,type=single,waterlogged=false] O=oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]
```

**`build at` 的回执**:受理即回执;`task_finished` 写成

```
shed#1: built 22/22 block(s); placed 21, cleared 0 (all requested cells match)
shed#1: built 18/22 block(s); placed 0, cleared 0 (left 4 cell(s) alone because the owner said no: …)
```

她放下的每一格记在她自己名下(`PlacedBlocks.placedBy`:物品车道由 `BlockItem.place` 的 mixin 调,照图直写由执行器调),
改设计后再 `build at` 拆、换她自己的格由出厂的 `break(self_placed & !contents)` 与 `place(!hazard_item)` 放行,不问。
第二行是那一格的方块是主人后来亲手放的:拆它是 `break(placed)`,主人不在场问不到就不拆,如实交代。数据里有 `placed`、
`replaced`、`cleared`、`removed` 与 `building`。

**与设计稿的出入**:

- 蓝图文件也照设计的摆法:图纸自己的原点(最小角)落在锚点、**绕原点**转。原来按"转完再把最小角对齐锚点";改动是为了
  两样东西一个摆法,也为了同一栋房子改了之后按同一个落点再摆,墙落回原来的格子。不转(0°)时和原来一样。
- 生存缺料:设计与当场执行的原语整份预检、缺料一格不放;蓝图文件保留原来的"能建多少建多少,同一行再发一次从断点接上"
  ——整张社区图纸一趟本来运不完。这是施工图来源自带的一条,不是开关。
- 原来 `build blueprint_read` 带落点报"那里已经立着多少、还差多少"的用法删掉:同一个问题现在由 `build at` 的差异回答,
  没有差异就当场说"已经是那个样子",有差异就去补;不另开一个只读的"差异预览",那就是被撤掉的 plan。
- `build step` / `build insert` 的新一步写成"`build` 之后的那一截"(`layer 0 1 0 …`),存进设计时写回完整的一行。这一截
  是一个吃整行的参数,它的类型是"本组的另一行命令"(`ArgType.command`):由正在读外面那一行的同一棵树读,里面那一步和
  单独执行时读得一样完整,写错了报它自己的错(附着它自己的用法、"你是不是要写");防漂移测试读到的也是里外两层。
- 方块参数(`--block`、`layer` 的图例)在命令树读的时候就认:参数类型是在"一个值"的写法上接一个解读(`ArgType.as`),
  解读就是 `BuildPalette.parse`(原版 `BlockStateParser`)。方块名、状态写错了是那一行写错了,当场执行的一行、`build step`
  写进设计的一步、设计文件的一行、技能里写着的一行都在读的时候报,不等到画或施工;画的时候拿到的是读好的调色板,不再解析。
- `copy` 在设计里只抄这份设计前面画的;要抄世界里已有的一片,当场执行 `build copy`。
- 实例记录里被别人换掉的格照实留着(记的是她当初放的),只是世界里对不上,不会拆;不在读的时候顺手改记录。

**持久化**:设计是文件,跨世界复用,每次从盘上读(GameTest 验:写下的几步读回来一字不差);房子在世界存档里,存下的那份
读回来还是同一栋、同样的格子。设计删掉,房子照样在 `build built` 里,写明它的设计已删。

### throwaway:她赶路时愿意消耗的方块

10-03 起这一组删了:垫路料不再是挂在她身上、跟着名册落盘的一份清单,而是每一趟路描述里的 `materials`(不写就是标签
`numen:throwaway` 的普通方块,`ThrowawayBlocks.factory`),见 `shell.md` §十。下面是当时的设计。

寻路往上垫柱、过沟搭桥时愿意消耗掉的那份方块清单,是她自己的一项设置,单独一组 `throwaway`(名字取 Baritone 的
acceptableThrowawayItems;不叫 scaffold,免得和原版的脚手架方块混在一起)。代码、命令、状态、存档用同一个词:
`ThrowawayBlocks`(清单与取料)、`ThrowawayOps`(改清单)、`ThrowawayCommands`(登记)、出厂标签 `numen:throwaway`、
名册存档键 `throwaway`。

```
throwaway add minecraft:cobblestone minecraft:cobbled_deepslate
throwaway remove minecraft:dirt
throwaway set minecraft:netherrack
throwaway clear
```

- 四个动作只改清单,当场回,改完报主人一句;回执是存进去之后读回来的那份,外加背包里还没进清单的方块。
- 没有"看清单"的动作:清单现状是身体状态的一段(`ThrowawayBlocks.bodyState`,和命令组在同一处装上),每轮挂进
  `<runtime_state>`,`status self` 照抄。样子是 `<throwaway>cobblestone, dirt, cobbled_deepslate, …</throwaway>`(原版 id
  省掉命名空间,模组的带着),清空了是 `<throwaway>empty: you place no blocks while moving</throwaway>`。
- 名册里旧存档的清单在 `scaffold` 键下(出厂标签那时叫 `#numen:scaffolds`)。读档时认它一次,存档只写 `throwaway`:
  不读的话她自己定过的清单(包括清空)会悄悄变回出厂默认。整合包往 `numen:scaffolds` 里加过方块的,要改加到
  `numen:throwaway`。
- `build` 组去掉原来的五个 `scaffold*` 动作后是十六个动作,组帮助一页放得下。

### work:挖矿与捡东西只在工作区里干(09-29)

`work mine`(`work_mine`)原来找矿扫 32 个区块、许改地形时先整条规划再出发,而一次规划只看身边一份快照(以起点所在区块为中心、
边长 13 个区块)与四万个节点;远处的矿于是被报成 "found N … could not reach any … Move me somewhere else",寻路给的准确原因
(预算用完不能证明无路、区块未加载……)被压成一句。现在改成工作区:

- **工作区**(`core/nav/WorkArea`,只写在这一处):受理那一刻她脚下那一格为中心的球,距离按两格整数坐标算,与找方块的查询同一把尺
  (09-30 起它就是一块区域,判定只问区域,见"看与挖收区域"一节)。
  半径上限 `WorkArea.RADIUS` 由寻路一次看得清的范围推出——快照从任何起点保证看得见水平 `SEARCH_RADIUS × 16` = 96 格,
  工作区的直径取这么大,区里任意两点在同一份快照里;竖直方向用同一个半径。今天是 48 格。
- **区里**:照旧——按"走过去加挖掉"一起定价挑最便宜的、就地挖通、垫高、捡掉落物,挪动用 `Trip`。首次找矿查询回来之前不出发。
  捡的掉落物是区里的,加上她自己敲出来、弹出区外一两格的。
- **区外**:不去,只报告。找矿查询以工作区中心为球心、半径 32 个区块(服务器视距的上限,查询只读已加载的区块,即"她身边加载着的
  全部地形"),区里的命中进名单,区外的记下来(09-30 起挖矿只收区域、自己不找,区外的就是点名区域落在工作区外的格,说法见"看与挖收区域"
  一节);回执说区外还有几个(查询凑够个数就停时说"至少")、最近一个在哪、离她多远,下一步照抄:

  ```
  found no ochre_froglight in my work area (within 48 blocks of -12553958,-58,11491503), so I stayed put; 2 more lie beyond it, the nearest at -12553911,-58,11491550, about 66 blocks from me: move_goto there first (x:-12553911 y:-58 z:11491550 arrive:near near:8), then work_mine again
  gathered 6/64 pearlescent_froglight (no more pearlescent_froglight in my work area, within 48 blocks of …; 7 more lie beyond it, the nearest at …, about … blocks from me: move_goto there first (…), then work_mine again)
  ```
- **受理回执**当场交代工作区(`TaskRecord.acceptNote`,由 `TaskDispatch` 接在受理那句话后面):
  `My work area is within 48 blocks of -12553812,-58,11491503, where I stand now: I mine only there, and the end reports what lies beyond it.`;
  groups 用法说点名的格有几格在区里、几格在区外留着不挖。
- **`--groups` 点名的团一格都不在区里**(09-30 起 `--groups` 由 `--area` 取代,同一条规矩,见"看与挖收区域"一节):和编号过期同一条规矩,
  派发当场拒收,不派活:
  `group g4 lies wholly beyond my work area (within 48 blocks of …), so I did not start; the nearest of its cells is at …, about 66 blocks away. move_goto there first (…), then work_mine again (group ids stay good until your next scan_blocks).`
- **到不了按类型说**:区里的一批搜不出路就收工,回执接上寻路结局的原话与下一步(`NavText.failure`,只此一处):真无路、预算用完、
  未加载、要改地形(说出要改几格、放开哪一档;列候选路线的探路 09-30 删掉,见下一节)、没有垫路料、被拒、起点待不住、执行受阻、看不见。例:
  `could not reach any of the 1 pumpkin in my work area (within 48 blocks of -12553556,-58,11490388); gathered 0: had to stop: changing -12553556,-57,11490389 is refused (denied by rule break(minecraft:white_wool) (is minecraft:white_wool)); that is not mine to get around, so pick another destination or ask your owner`;
  同样关在小屋里、模型给了 `--alter none` 时是另一句:`… gathered 0: found no path to target without altering terrain (from …, about 11 blocks away; a route that digs, bridges or pillars through natural terrain exists, changing 1 block(s): walk with alter:'natural' to take it)`。
- **`scan blocks`**:每一团标 `in_work_area`(`all` / `none` / `8 of 17 cells`),小结里有 `work_area`;扫描的中心就是她此刻
  脚下,和她从这里派 `work mine` 时的工作区是同一块(09-30 删掉:去不去得了归规划,见"看与挖收区域"一节)。
- **`work collect`**:半径参数就是工作区的半径(默认 16,上限 `WorkArea.RADIUS`),以受理时她脚下为中心、不跟着她走——
  原来每捡一件都以她新的位置重算半径,一件接一件能越捡越远;两处各写一份的上限 48 合成这一处(09-30 起这个球是一块区域,
  `--area` 再把它收窄到点名的区域里)。

### move goto:只收坐标,`--arrive` 说怎样算到了(09-29)

真机上近一半寻路结局是"到了看不见"(`NoLineOfSight`):`move_goto --block X` 与挖矿借的是同一个"够得着"的到达,只按几何距离算,
不看视线——隔着四格石头、在悬崖底下都算够得着。现在:

- **只收坐标**:`--x --z`(一处)、`--x --y --z`(一格)、`--y`(一个高度)。`--block`(自动找最近的一种方块)
  删去,连同只为它存在的 `NearestBlockFinder`:找东西是 `scan blocks` 的事,它给坐标。
- **`--arrive at|use|near`**(默认 `at`),`--near N` 只配 `near`。命令行只管参数的写法、帮助与写错时的提醒,参数名一一对应到
  寻路模块的目标只在 `core/task/move/Destination` 一处:`at` 是位置(`Goals.at`/`column`/`level`;站上一块方块
  就是给它上面那一格,写错时的提醒给出那一格),`use` 是用一格方块(`Goals.use`:站在它敞开的面前、看得见、点得到),`near` 是距离范围(`Goals.within`)。路线的终点与途经点(`route new --to`、`route via`)读同一份。
- **写错当场提醒,不去搜索、不替她改写**(`GotoReminders` 写字,判断一律问寻路模块,经适配层 `Terrain`)。受理回执是
  `invalid arguments: …`,不派活:
  ```
  120,64,-35 is furnace — no room to stand in it, and this walk changes nothing. To use it: move_goto x:120 y:64 z:-35 arrive:use; to stand on top of it: move_goto x:120 y:65 z:-35; to stop close by: move_goto x:120 y:64 z:-35 arrive:near near:<blocks>; to dig into it instead, add alter:natural.
  120,70,-35 is in mid-air — nothing to stand on there (the ground in that column is at y=64: move_goto x:120 y:64 z:-35). Omit y to go to that column; to pillar up to it, add alter:natural.
  arrive:use names one block — give its y too (x, y and z).
  120,64,-35 is air — nothing there to click. To get close: move_goto x:120 y:64 z:-35 arrive:near near:<blocks>.
  120,64,-35 (furnace) is walled in on every side — no face is open to see or click: west stone at 119,64,-35, … Dig one of them open — the west one is nearest me: `use block left 119 64 -35` once in reach — then move_goto x:120 y:64 z:-35 arrive:use again.
  near:3 only goes with arrive:near — write arrive:near near:3 to stop within 3 blocks; without it arrival is exact.
  ```
  `at` 指向站不进去、站不住的格只在这一趟不改地形(`alter none`)时提醒:许改地形时那是寻路去挖、去垫的事。
- **`use block` 不自己走路**:够不着、看不见时下一步是能照抄的 `move_goto x:… y:… z:… arrive:use`。右键看向目标看得见的那一面
  (`Aim.use`,与 `arrive:use` 同一个视线函数),两个键都是纯按键:准星落在谁就点谁,视线上挡着的(箱子前的高草)不清,回执照实说
  并写出下一步,例如 `right-clicked tall_grass at … — the crosshair landed there, not on …. To click …: `work dig …` clears it
  out of the way, or move_goto … arrive:use stands where another face of it is in sight`。清视线只归 `work dig`。
- 别处"先走过去"的下一步一并改成能照抄的写法:合成找工作台、睡觉找床、森罗厨房的锅(`arrive:use`),挖矿区外的矿
  (`arrive:near near:8`)。

### move 与 route:先规划、再照承诺走(09-30)

设计稿见 `docs/look-plan-act.md` §四。寻路原来把规划藏在执行里:`move goto` 许改地形时直接开走,`alter any` 时先规划、征询、再走,
没路时放宽一档探路、把候选路线记进身上的路线簿(`r1`、`r2`,不落盘),`move goto --route r2` 取走即删,`move route` 只列候选。现在
路线是存盘的名词,规划与执行分成两件事:

- **路线**(`core/route`):`Itinerary` = 名字、维度、一串路段(每段是去一个途经点的那一截;途经点是 `Destination.Stop`,坐标加
  到达方式,最后一个是终点)、整条与每段的路线标志(她写的那一截原样存成文字,`RouteFlags` 经 `route spec` 那一行命令的同一棵树
  读回、经 `RouteSpecFlags` 翻成规格)、最近一次计划(`Plan`)、最近 8 次走过的记录。按主人存在主世界的 SavedData(`Routes`,
  文件名带主人 UUID,与权限层同一个做法),同一个主人的同伴共用,重启不丢。名字规矩是 `Names`,与设计名、区域名同一条。
- **计划**(`Plan`):从哪一格、何时规划的;每段一份:走得通(几步、估几刻、停在哪)、只看清一截(停在哪、之后为什么未知:预算用完、
  伸进没加载的区块)、走不通(寻路结局的原话,下一步写成改这条路线的命令)、没规划(前面一段没走通或没看清);每段要挖的格(连同当时
  的方块)、要放的格(倒水接坠落的那一格记水)、要问主人的格(连同为什么问);与回执里说要改什么读的是同一份(`NavText.Changes`)。
- **规划**(`core/nav/Survey`):只搜不走,从她脚下逐段一次搜索,下一段接在上一段的终点与最后一步后面(门面 `PlanQuery.after`);
  没走到的那一段交出看清的那一截(门面 `PlanResult.partial`)。`RoutePlanning` 把途经点按此刻的世界编成目标、交给它、写成计划。
- **执行**(`core/nav/Trip`):照一段规划好的路走(`Trip.following`),路上边走边细算;反射层(跟随、战斗走位、捡东西、钓鱼、走到
  实体跟前、建造走外圈、挖矿)照旧 `Trip.to`,许动要主人同意的格时先规划一条过目、问过再走。失败后放宽规格探路(PROBING)删掉:
  放不放宽是她的决定,回执说要改几格、照抄哪一行。
- **承诺**:她看过的那份计划就是 `move go` 许改的全部格子。`Plan.bind` 把它写成这一趟规格里按位置的"只许"
  (`PositionCosts.confine`:只许挖计划里挖的格、只许放计划里放的格),执行层重搜时自然只在承诺里找,不另写检查。

```
route new home --to 120 64 -35 --alter natural
route via home 100 70 -20 [--at 1]          插途经点;route drop home via 1 删
route spec home --leg 2 --avoid water        整条或一段的标志:写了的换掉,没写的照旧;一段的叠在整条的上面
route plan home                              只搜不走,不占身体,结论出来时回复;计划记在路线上
route show home / route list / route delete home / route reverse home --as home_back
move go home                                 占身体,task_finished 收尾
```

- 改意图(`via`、`drop`、`spec`)的每一步丢掉旧计划。`route reverse` 的终点是这一条上次规划时的起点(没规划过就说要先规划)。
- **`move go`**:从她此刻的位置重新规划;路线有计划时拿新计划比那一份(`Plan.beyond`:要挖、要放、要问的格各自比,只看多出来的),
  超出就把多出来的格说出来、不走;没计划就把这一份记成承诺直接走。有走不通的段也不走。开走前要问主人的格一次问完(沿用征询机制),
  然后一段一段走,每段规格绑上承诺。路上停下时从这里再规划剩下几段:多出承诺外的格就说是哪几格,否则照寻路的结局说。只看清一截的
  那一段、以及因此没规划的后面几段,照这一段自己的目标开走(有看清的那一截就拿它当开头),执行层边走边分段细算;承诺照样绑着:
  `alter none` 的承诺是一格不改,远途 goto 一路走到底;要改地形的路线走进未知部分后要改承诺外的格,重搜找不到,停下说是哪几格。
  开走前"不走"只针对确定走不通(按规格无路)的段,预算用完、未加载的"未知"不算。每一趟收场都记进路线走过的记录。
- **`move goto` 是简写**:参数不变,把这一趟写成她自己的匿名路线 `goto-<她的名字>`(受理回执里写出来)再和 `move go` 一样走。
  `--route` 删掉。失败回执给出能照抄的下一步,连同寻路诊断出的那条路要改的格(GameTest 里的原话):

  ```
  blocked on route goto-gametest_lodger: got within 6.0 blocks of -958990,-58,-171598 (now on the ground at y=-58). found no route without touching what needs the owner's consent (from -958996, -58, -171598 toward -958990, -58, -171598, about 6 blocks away; one exists that changes 2 block(s), some of them someone's — break 2 oak_planks (-958994,-57,-171598; -958994,-58,-171598) needing consent (placed by a player):) `route spec goto-gametest_lodger --alter any` lets me take it; then `route plan goto-gametest_lodger` shows the plan, and walking it asks the owner first.
  ```
- 从别处出发、新计划超出承诺,不走;路上世界变了、要承诺外的格,停下(GameTest 里的原话):

  ```
  the way from here goes beyond the plan of route out (made from -959112,-58,-170135), so I did not set off: it would also break 2 oak_planks (-959105,-57,-170130; -959105,-58,-170130). route plan out plans it from here and shows it; then move go out keeps to that plan.
  stopped on route tunnel at -959070,-58,-170130: the way on from here needs cells outside the plan I keep to — it would break 2 stone (-959070,-57,-170130; -959070,-58,-170130). route plan tunnel plans it from here and shows it; then move go tunnel keeps to that plan.
  ```
- `route plan` 的回执是这个样子:抬头(从哪儿、多久以前、几段几步、估几刻),每段一行(要改的格与实际账同一种写法;只看清一截的
  写"known for N steps up to x,y,z …, unknown past that",走不通的写原因),末尾一句能照抄的下一步:

  ```
  plan of route out, made from 7,-58,7 just now: 1 leg, 9 steps, about 131 ticks as priced.
    leg 1 to 13,-58,7: 9 steps, about 131 ticks; break 2 oak_planks (9,-58,7; 9,-57,7)
  move go out walks it; it changes only the cells listed here.
  ```
- 删掉的:`RouteBook`、`r` 编号、`move route` 与它的 `--alternatives`、`move goto --route`、`Trip` 的 PROBING 与 `probing()`、`NavText` 的
  候选清单(`line`、`listing`、`noCleanRoute`、`unplanned`、`plannedRoutes`)。计划的说法只在 `RouteText`(要改的格与实际账同一种
  写法,`NavText.planned`)。
- 寻路结局"要改地形才有路"(`Outcome.NeedsAlter`)带着诊断出的那条路要做的改动,回执点名那几格(反射层的活也一样,例如
  `use entity` 隔着玻璃罩时说 `break 2 glass (…)`),代替原来的候选清单。诊断问"许改的够不够"时设想身上有料,所以列出的可能是
  身上没有的垫路料(`place 2 cobblestone`);照这份规格规划(`route plan`)时按身上真有的料算。

### 路线收区域:去处、禁区与路线标志(09-30)

设计稿 `docs/look-plan-act.md` §七第 3 步的路线一半。"一块地方"在路线里只有区域一种写法(api `com.dwinovo.numen.area`),坐标就是
只有一格的区域:

```
move goto --area farm                                   走进区域里任意一格(站得住的)
move goto --area storage --arrive use                   用区域里任意一个能点、用得上的方块
move goto --area ores/g3 --arrive near --near 3         离区域(这一部分)里任意一格不超过 3 格
route new ore --to ores/g3 --arrive near --near 3       --to:数是坐标(x y z / x z / y),一个名字是区域
route via home farm --arrive near --near 2              途经点同一种写法:route via <name> <place...>
move goto --x 120 --z -35 --avoid water area:farm       --avoid:格子种类之外,area:<名字> 是不进入的一块地
route spec home --avoid_break area:house                --avoid_break/_place/_step:方块 id、#标签、x,y,z 一格、area:<名字>
```

- **去处**:`Destination.Stop` 多一样 `area`(`AreaRef`,存名字),与坐标二选一;`move goto` 的 `--area`(快捷工具的 `area` 字段),
  `route new --to` 与 `route via` 的一处(`Stop.of(List<String>)`:一到三个整数是坐标,一个名字是区域;帮助原文
  `x y z (one cell), x z (a place, at whatever height stands there), y (a height), or an area of your owner's: its name, or name/part like ores/g3`)。
  参数到寻路目标仍只在 `Destination`:三种到达对整块区域成立,用模块现成的 `Goals.anyOf` 组合——`at` 是 `anyOf(at(格)…)`、`use`
  是 `anyOf(use(格)…)`、`near` 是 `anyOf(within(at(格), 0, N)…)`,寻路模块没有为区域加到达。
- **有界**:成员只在区域里离出发点最近的 4096 格里挑(api `Cells.nearest(from, limit)`,按小节由近到远翻,四百万格的区域也只翻附近
  几节),`at`/`near` 至多 64 个成员,`use` 至多 8 个(至多给 16 个能点的方块列候选站位)。`at` 在不改地形的一趟只收站得住的格
  (没加载的列此刻判不了,照收),许改地形时照收(挖进去、垫上去是寻路的事,与单格同一条)。离出发点最近的那一侧就是她走进去的那一侧;
  要去别处,点名那一部分。路线的第二段起,"出发点"是上一段去的那一格。
- **写错当场提醒**(`GotoReminders`,能照抄的写法是 `move_goto area:<名字> …`):
  ```
  there is no area named pen; your owner's areas are shed (area list shows them)
  area house has no part b7; its parts are b1, b2 (area show house lists them)
  area portal lies in minecraft:the_nether, and I am in minecraft:overworld; an area belongs to one dimension
  area ores has no cells yet, so there is nowhere in it to go; `area add ores --box <x1,y1,z1..x2,y2,z2>` or `scan blocks <radius> <block ids> --into ores` fills it.
  none of the 4096 cells of area rock nearest me (of 9261) is somewhere to stand, and this walk changes nothing. To stop close by: move_goto area:rock arrive:near near:<blocks>; to dig or pillar into it, add alter:natural.
  none of the 2 cell(s) of area air holds a block to click — they are air or fluid. To get close: move_goto area:air arrive:near near:<blocks>.
  none of the 3 block(s) of area vault nearest me can be used: each is walled in on every side or has nowhere within reach to stand and see it. move_goto x:… y:… z:… arrive:use says what is in the way of the nearest one; to get as close as I can: move_goto area:vault arrive:near near:<blocks>.
  ```
- **禁区与路线标志**:`--avoid area:<名字>` 落成位置代价的禁入与禁站(`PositionCosts.Use.PASS` 与 `STAND`),`--avoid_break` /
  `--avoid_place` / `--avoid_step` 的 `area:` 落成挖、放、踩那一栏的禁止。区域整块交给寻路:`NamedAreas.region` 给出"一格在不在"
  的判定(问区域的小节位图),寻路模块只收这个判定(`PositionCosts.Region`),不逐格展开。**盒子写法 `x1,y1,z1..x2,y2,z2` 删掉**
  (连同逐格展开的 `cellsOf`):写了会被参数类型当场拒,`a cell is one x,y,z; a box or any other stretch of cells is an area — frame it as one and write area:<name>`。
  单格坐标、方块 id、标签照旧。
- **活引用**:路线存区域的名字(去处的 `area` 字段、标志原文里的 `area:…`),每次规划按那一刻主人名下的区域解析(`NamedAreas`,
  与权限规则 `area:` 同一个口径):写的时候(`move goto`、`route new`、`route via`、`route spec`)点名不在的区域当场拒;之后区域或
  部分被删,规划如实说哪一段(`route plan` 的 `leg 1 to area pen: can't be walked: there is no area named pen; …`),`move go`
  不出发(`can't walk route topen as it stands: there is no area named pen; …`、`… --avoid area:farm: there is no area named farm; …`)。
- 回执:到了一块区域说 `reached area pen, standing at …`、`standing at …, with the chest at x,y,z of area chests in sight and in
  reach — use it from here`、`arrived within 3 blocks of area pen, standing at …`。

### 看与挖收区域:area 组、scan blocks --into/--in、work --area(09-30)

设计稿 `docs/look-plan-act.md` §七第 3 步的看与挖一半。"一块地方"在看与挖里也只剩区域一种写法:扫描写进区域,挖与捡点名区域,
工作区本身是一块区域;团编号簿、`g` 编号计数、`--groups`、`in_work_area`、`box` 都删掉。

**`area` 组**(`core/tools/area/AreaCommands`,实现 `AreaOps`,说法 `AreaText`):

```
area new ores                                    在她此刻的维度建一块空区域
area add house --box 10,60,5..20,70,15           框一个盒子,一部分 b1、b2……;一框至多 2^24 格
area add chest --at 12 64 7                      一格,p1……
area add home --built house#1                    一栋建成的房子放下的格(Built 记的,唯一出处),c1……
area add tunnel --route mine                     一条路线最近一次计划要挖、要放的格,c……
area drop ores g2 / area delete ores / area list [--page]
area show ores [--page] / area show ores/g3      一部分一行
area refresh ores                                按活世界复核附带方块的格,划掉变了的
area union all ores gold                         并;右边的部分按左边的计数续号
area minus safe house house/b2                   差
area intersect near ores base                    交
area filter logs house --blocks #minecraft:logs  按附带的方块筛(框出来的格没有方块,不留)
area grow buffer house 2                         外扩 N 格
area center mid ores                             中心附近的一格
```

- 命令层无状态,每行点名区域;名字规矩是 `Names`,点名一块或一部分的写法是 `AreaRef`,参数类型是 `ArgType.area()`,按名字找主人的
  区域只经 `NamedAreas`(与路线同一处,她此刻所在的维度)。运算的结果存成一块新区域(名字要是新的,已有的不覆盖),不存算式。
- `Area.Kind` 多一种 `CELLS`(`c`):一栋房子、一条路线计划这类别处记着的一组格。
- `area show` 的一行与 `scan blocks` 的一团是同一种 JSON(`AreaText.part`:编号、格数、附带的方块各几格、流体源头几格、最近一格的
  方向与距离、16 格以内逐格列坐标),再接上包围盒(写法就是 `--box` 收的,能原样抄回)与此刻挖它许不许——逐格用挖掘落点会提交的
  同一个 `break` 动作问 `Gate.judgeLive`,说法只有一种时写成 `permission` + `reason`,几种时是 `{说法: 格数}`;此刻是空气的数进
  `air_now`,没加载的数进 `not_loaded`。
- `area refresh` 的"还是当时那种方块"与挖矿动手前的复核同一条(`Cells.Seen.holds`:比方块种类,不比朝向这类状态);没加载的格不看、
  照实说;一格都没变就不改区域、不经权限层。
- **改区域过权限层**:新建、加部分、删部分、`refresh` 划掉、删除、运算存成新的一块、`scan blocks --into` 写进去,都是动作
  `edit_area(区域名)`(`docs/permission-layer.md` "改区域"),经 `ServerSource.authorize` 裁决——与第 0 层指令同一个口子
  (`PendingCommands` 从只挂指令改为挂任何一次调用的一件事):放行照做;不许回执理由、区域不变;要问就这次调用悬着、不占任务槽,
  主人答复后按那一刻的存档再算一遍才写。出厂 ask 行 `edit_area(ruled)`:主人层规则的 `area:` 项点名的区域(区域此刻在不在都算),
  改它先问主人;其余照出厂 allow 行 `edit_area(!ruled)`。命令里不写死哪块能改。

**`scan blocks`**:

```
scan blocks 32 iron_ore deepslate_iron_ore               只是看:团没有编号,什么也不存
scan blocks 32 iron_ore deepslate_iron_ore --into ores   每一团加成 ores 的一部分(g 在区域里续),回执里编号是 ores/g5
scan blocks 16 #minecraft:beds --in base                 只收落在 base 里的格;半径照旧是从她脚下看多远
```

- 找方块、逐格问权限、分团收成 `core/scan/BlockScan`(只有 `scan blocks` 用它,挖矿自己不找);看完的结果经 `Found.into` 写进
  区域,每格附带看到的方块状态与那一刻(主世界游戏刻)。`BlockGroups.Group` 只留格子与状态、说法、最近一格:各种几格、源头几格
  由区域的格子说,包围盒由 `area show` 说。
- `--in`:半径参数不变,范围是"从她脚下的半径"与"点名的区域"两者都要在——区域判定只问 `Area.contains`,搜索的球只是看多远。
- `--into` 的区域没有就新建(像 shell 的 `>`,在她看的那个维度;回执说 `added to the new area ores (made just now)`),有就得在她
  此刻的维度;要是整块(不收部分),看之前先过 `edit_area`(建区域也是它)。能不能写只在 `AreaOps.into` 判;`area new` 照旧建空区域。
- 回执删掉 `in_work_area`、`box` 与小结里的 `work_area`:去不去得了归规划(`route plan`、`move goto --area`),框一块用 `area add --box`。

**`work mine`**:

```
work mine ores/g3                  挖这一部分里还是当时那种方块的格,挖完为止
work mine ores/g3 ores/g4 --count 10
work mine logs --count 16 --avoid_break area:house
```

- 只收区域:先看、再规划、后执行——`area new ores`、`scan blocks 32 iron_ore deepslate_iron_ore --into ores`,范围不对用
  `area drop/minus/intersect/filter` 调,再 `work mine ores`。区域是必填的位置参数 `<area...>`(命令行里必填即位置参数),快捷工具
  `work_mine` 的字段仍叫 `area`、标成必填。`--count` 可选,是新增的物品数;不给就挖完区域里落在工作区里的格。

- 候选只有一个来处:区域里附带了方块的格(`MineBlockTaskRecord.scanned`),每格动手前按 `Cells.Seen.holds` 复核,变了的记成"别人动过"
  (顺路挖掉的记她的),不往外扩。框出来的格不挖。
- 区域在派发时解析:没有这块、没有这一部分、没有扫描过的格、整块落在工作区外,当场拒收不派活,回执写照抄就能做的下一步:
  `targets has no scanned cells, so I did not start: work_mine digs the cells a scan added, and framed cells carry no block. scan blocks 48 <block ids> --into targets adds what is there, then work_mine it`
  `all 2 scanned cells of targets lie wholly beyond my work area (within 48 blocks of …), so I did not start; the nearest is at …, about 67 blocks away. move_goto there first (x:… y:… z:… arrive:near near:8), then work_mine again.`
- 落在工作区外的只报告(`Beyond`),挖完区里的收场时接在回执后面:
  `gathered 6/64 pearlescent_froglight (nothing left to dig in my work area, within 48 blocks of …; 7 scanned cells of targets lie beyond it and were left, the nearest at …, about … blocks from me: move_goto there first (…), then work_mine again)`
- 区里的格到时都不在了(别人挖掉、换掉):区外还有就说在哪、怎么过去;没有就给再扫一遍的那一行(扫的就是这件活的方块,半径取工作区的):
  `all 3 scanned cells of ores in my work area (within 48 blocks of …) were gone or had changed since the scan; gathered 0. Nothing of ores is left to dig; scan blocks 48 minecraft:iron_ore --into ores adds what is there now`
- 删掉 `--block_ids` 简写(09-30):原来开工时先 `BlockScan`(半径 192)收成一块不存盘的匿名区域、看回来之前不出发,连同只为它存在的
  `MineBlockTaskRecord.SCAN_RADIUS`、任务里的 `mapped`/`absorbScan`/搜索句柄、`Beyond` 的"至少"与 `more` 说法、"工作区里和身边加载着的
  地形里都找不到"那句话一起删掉。找方块只有 `scan blocks` 一处。
- 受理回执:点名区域时说 `all N scanned cells of ores/g3 lie in it`,或 `n of the N … lie in it; the other k lie beyond it and will be left`。

**工作区与捡东西**:`WorkArea` 带着一块球形区域(`Cells.sphere`,`Area.Kind.SPHERE`),`contains` 问区域;挖矿把点名区域与它求交、
求差。`work collect` 的范围也是一块区域:受理时脚下为中心的球(`--radius`,默认 16;点名区域时默认取工作区的 48),`--area` 再与
点名的区域求交;找掉落物经 `NearbyEntities.in(level, Area, …)`(区域的包围盒向世界要候选,逐只问区域)。

**没改的**:
- `BuildSite`(建造走向外圈时的工地禁令)不改成区域:它的格子是这次施工的目标格(`BuildCompanionTask.targetByPos`),只在这一趟里交给
  寻路的位置代价(`PositionCosts`,寻路模块不依赖 api 的区域);收成区域再展开回去只多一层转换,判定仍是寻路模块那一处。
- `scan storage --in`:不做。`scan storage` 的必填位置参数是一格 `x y z`,命令行没有可省的位置参数,`--in` 要么让同一个动作有两种
  写法、要么另起一个动作;"区域里的容器装了什么"眼下没有消费方,等要用时按真实用例开。

### 挖掘统一:work dig、跟前的工作区、--arrive dig(09-30,`look-plan-act.md` §十一)

```
work dig ores/g3                 扫描来的格:只挖还是当时那种方块的
work dig pit                     框出来的格:里面是什么挖什么(空气、流体跳过)
work dig 120 12 -35              坐标:只有一格的区域;一串里名字与坐标可以混写
work dig ores --count 10         --count 是新增的物品数
move goto --x 120 --y 12 --z -35 --arrive dig --alter natural    走到手够得着它的地方,那一格留给 work dig
route new ore --to ores/g3 --arrive dig --alter natural          路线的去处同一种写法
```

- `work dig`(快捷工具 `work_dig`,字段 `place`)取代 `work mine`,不收路线标志;"别碰什么"由 `area minus` 与主人的规则表达。
  一串写法由 `Destination.Stop.each` 分成几处,每一处照 `Stop.of` 读(数是坐标、名字是区域)。
- 工作区 = 跟前:受理时脚下为中心、半径 10 的球(`WorkArea`,理由在类注释),走动用 `PositionCosts.confine` 关在里面;`work collect`
  的范围是同一个工作区,`--radius` 删掉。区外的只报告,下一步两种写法都给:
  `4 cell(s) of targets lie beyond it and are left, the nearest at …, about 12 blocks from me. To dig there, open the way first: `route new targets --to targets --arrive dig --alter natural`, `route plan targets` to see what the way changes, `move go targets` — or straight away move_goto area:targets arrive:dig alter:natural — then `work dig targets` again.`
- 派发时当场拒收的几种:区域是空的(`targets has no cells yet, so I did not start; `scan blocks <radius> <block ids> --into targets` or `area add targets --box <x1,y1,z1..x2,y2,z2>` fills it`)、整片在区外(`all 2 cell(s) of targets lie beyond my work area (within 10 blocks of …), so I did not start; …`)、
  区里一格都不用挖(扫描来的都变了,或框出来的都是空气、流体)。
- `--arrive dig`:模块现成的 `Goals.dig`;写错提醒:`arrive:dig names one block — give its y too (x, y and z).`、
  `120,64,-35 is air — nothing there to dig. To get close: move_goto x:120 y:64 z:-35 arrive:near near:<blocks>.`。到了的回执:
  `standing at …, with the ancient_debris at 30,3,30 within reach — `work dig 30 3 30` digs it from here, via route goto-….`
- 建造清场(生存)交给同一个挖掘执行,创造模式照原版一下就碎;`use block left` 退回纯按键(手上什么用什么、准星落在谁按谁,
  落在别的格照实说:`left-clicked dirt at 5,2,12 — the crosshair landed there, not on 6,2,12 — …`)。细节见 `look-plan-act.md` §十第 4 步。

### 受理 = 这件活此刻真能开始(09-30)

真机上 `move_goto` 受理后 0.12 秒就发 `task_finished failed`(不改地形没路):她那一轮已经对主人说了"往西边跑一趟",又被事件
叫醒再开一轮;挖矿受理后两秒才报搜索预算用光。现在派成后台活的调用受理之前先准备,开始不了的当场回错误,不受理。

- **机制只一份**(api):任务交出准备(`Task.prepare` → `Preparation`,`poll` 每刻问一次、`cancel` 作废在飞的搜索),`TaskDispatch`
  交给这具身体的准备位(`Preparing`,一具身体一件)。结论就绪才受理:换进槽里、顶掉她手上那件、落盘、回"已受理",准备查到的事实
  接在回执后面;不成就回错误结果——没有任务编号、没有 `task_finished`,她手上的活不动。不用搜索的当场回;要搜索的结论出来那一刻
  才回(调用的回信口晚一点回,和 `route plan` 一样),内脑派发器本来就等这条回执才派下一个。准备花掉的刻不算这件活的期限。
- **判的顺序**:参数写法(处理函数当场判,原样)→ 世界事实 → 规划(`Survey`/`RoutePlanning` 一次只搜不走,有展开预算)。
  `AbstractCompanionTask` 的前置条件挪到准备里判(同步动作、子活没有准备,开工时照旧判);其余由各任务的 `preparation()` 判,
  拒绝的说法与开工后收场时是同一句(同一个方法写成)。
- **旧活何时被顶掉**:新活受理的那一刻。准备期间后派的调用顶替先派的(先派的回 `not started: a newer body action came in before
  it was ready`),主人按停止回 `not started: the owner pressed Stop`,身体离开世界同理;她死了不回。
- **起点变了**:规划按调用那一刻她脚下。受理时她已不在那一格(上一件活还在挪她),`move goto`/`move go` 从这里重新规划、拿准备时
  那份当承诺比(与 `move go` 从别处出发同一口径);其余任务丢掉准备时的那条路、从脚下重搜(`Trip.prepared`)。
- **受理之后**才冒出来的照旧走 `task_finished`:路上世界变了、主人拒绝、中途卡住。计划里有要问主人的格照样受理,运行中问。
- **重启重放**走同一个入口,准备不过就是没接回来,`TaskPersistence` 报 `这件活没能接回来:…`。

各任务在准备里判的:

| 动作 | 准备里判什么 | 回执带的事实 |
|---|---|---|
| `move goto` / `move go` | 路线在、在这个维度;从脚下规划整条,走不通、超出承诺就拒(坐船先驾船的,规划留到靠岸) | 计划:几段几步多少刻、每段要改的格、开走前要问主人几格 |
| `work dig` | 工具收得到(前置条件);区里有挖得成、许挖的候选;站着够得着一格,或对整批目标在区里搜得到路 | 第一格几步远、路上要改的格 |
| `work collect` | 区里有要捡的掉落物;对它们搜得到路 | 几堆几件、最近在哪、第一件几步远 |
| `work fish` | 有鱼竿(前置条件);附近有干站位抛得进的水 | 从哪一格钓、抛向哪 |
| `build at` 与当场执行的原语 | 盘料(前置条件);要先走到外圈的,规划那条路(走不到外圈不算开始不了,落位不靠走位) | 走到外圈几步、要改的格,或者走不过去、就地盖 |
| `move follow` | 要跟的已经远到要起步时,规划过去的路(只走不改) | 那条路几步 |
| `fight attack --entity_ids` | 点名的还在的,权限层对每一只都说不许 | — |
| `inv eat` | 前置条件(创造无饥饿、身上没有、不能吃) | — |
| `kaleidoscope cook` | 这一格是锅、配方认得、有投料量、够得着、锅空着(半空里等站稳再判) | — |

没改的:`fight attack` 不点名(对手是开打那一刻谁在追她)、`use`/`gear`/`inv` 其余与 `locate`、`ysm switch`(同步动作,回合本来就
等它的结果,开始不了就是那个结果,没有 `task_finished`);`work fish` 走到站位走不到(区里换站位重试三次,是运行中的事);`tlm`、
`ftbquests` 没有占身体的后台活。

回执原文(GameTest):

```
move_goto 受理:Accepted as t27; your body is working on it in the background. … I keep this walk as my route goto-gametest_undeterred; route show goto-gametest_undeterred shows it again. The plan of route goto-gametest_undeterred, made from -2038064,-58,6638504 just now: 1 leg, 12 steps, about 43 ticks as priced.
  leg 1 to -2038064,-58,6638516: 12 steps, about 43 ticks; no terrain change
move_goto 拒绝:blocked on route goto-gametest_undeterred: got within 9.0 blocks of -2038056,-58,6638512 (now on the ground at y=-58). found no path to target without altering terrain (from -2038064, -58, 6638504 toward -2038056, -58, 6638512, about 11 blocks away; a route that digs, bridges or pillars through natural terrain exists, changing 2 block(s) — place 2 cobblestone (-2038059,-58,6638510; -2038059,-57,6638511):) `route spec goto-gametest_undeterred --alter natural` lets me take it; then `route plan goto-gametest_undeterred` shows the plan, and `move go goto-gametest_undeterred` walks it.
work dig 拒绝:found 1 cells of bedrock but none of them can be broken here (unbreakable, or fluid or loose falling blocks beside them); gathered 0
work collect 拒绝:nothing to pick up: no dropped items lie within 10 blocks of 2798277,-58,10086129, so I did not start
```

计划里有要问主人的格照样受理,回执末尾是 `Before setting off I ask your owner about 2 cell(s) of it.`,运行中挂征询等答复。

### transfer 改成一次一步

`transfer` 工具与它的 `moves` 数组删掉,换成 `use` 组的两个动作,一个动作一个意思:

```
use transfer 38 1 --count 1      放进指定的一格:空格就放,同样的东西就并,不同的就对调
use shift 5                      像按住 Shift 点它:整叠挪到另一边,菜单自己定落在哪
```

要搬好几样就同一轮发好几行(串行的派发器一行一行排开,每行的回执说这一步搬了什么)。放在 `use` 组:在打开的界面里点格子,
和点方块、点实体是同一种"像玩家那样点"。从容器里拿东西的那一步照旧先过权限层,可能挂着等主人。

### todowrite 是唯一的独立工具

规则:**输入本身是一份结构化清单的动作留作独立工具**,不进命令层——一行命令装不下一张带状态的清单,硬塞进去就是在命令行
里写 JSON。`todowrite` 是这条规则下唯一的例外(Claude Code 的 TodoWrite 同理),主人面板上的计划清单直接读它的参数。
它的行为不变。其余动作都是一行命令,或它的快捷工具。

### 帮助缩短:标志组

成批的可选标志(路线规格那十三个)在组帮助与动作的用法行里不再逐个列出。机制是通用的:参数可以声明归入一个标志组
(`Param.group("route flags")`),用法行里整组写成一格 `[route flags]`,排在组里第一个标志的位置;动作自己的 `--help`
把组里每个标志列全,列在组名那一小节下。命令行上怎么写、快捷工具的 schema 怎么摊都不变。move 与 work 没有特判,
它们只是把 `RouteSpecFlags` 的参数都声明进了这一组。

前(`move --help` 里 goto 那一行):

```
move goto [--x <integer>] [--y <integer>] [--z <integer>] [--block <id>] [--route <word>] [--alter <none|natural|any>] [--avoid <snow_layer|door|ladder|vine|water|flowing_water|lava|hazard...>] [--penalty_place <number>] [--penalty_break <number>] [--penalty_jump <number>] [--penalty_wade <number>] [--avoid_break <block|cell...>] [--avoid_place <block|cell...>] [--avoid_step <block|cell...>] [--parkour <boolean>] [--climb_vines <boolean>] [--max_fall <integer>] [--alter_budget <integer>] — Travel to one destination with full terrain pathfinding.
```

后:

```
move goto [--x <integer>] [--y <integer>] [--z <integer>] [--block <id>] [--route <word>] [--near <integer>] [route flags] — Travel to one destination with full terrain pathfinding.
```

`move goto --help` 里,不归组的参数之后接一小节 `Route flags:`,十三个标志各一行。

### 防漂移测试

`WrittenCommandsTest`(core 单测)把写着的每一行命令按命令树读一遍,读不通就失败并指出出处、那一行和报错的第一句:

- **读什么**:core 随身带的每一份技能文档(`SKILL.md` 与它们按需读的参考文件)、系统提示(`NumenPrompts` 的几段与本能
  名册)、每个命令组与动作的说明、参数说明、例子与注意、工具表里每个工具的描述与参数说明(快捷工具、`command`、
  `todowrite`),以及随模组发的设计文件(`.numen`,按设计的读法读,就是同一棵树)。
- **怎么认出一行命令**(`WrittenCommands`,约定只写在这一处):反引号里,或 ``` 代码块里的一行;以 `/` 打头,或第一个词是
  第 1 层的一级命令。别的反引号(方块 id、工具名、参数名、字符网格)不读。
- **读得通**:第 1 层交 `NumenCli.read`——和执行时同一个解析器,整行是一条能执行的命令,或整行只是一串名字(在文字里
  点名一组、一个动作);第 0 层按原版的指令树读(OP 4 级,看得见每一条)。判据只有命令树,不另记清单。
- **`NumenCli.read`** 是为它与设计文件开的:一行读成动作路径与参数、不执行,读不通抛出和执行时写错一样的话。只读的那一棵
  树和两侧的树同一个生成器、每个动作都长着参数。读好的参数能写回一行(`CommandArgs.write`,每种参数类型知道自己的值在
  命令行上的样子),再读一遍是同一份——设计文件的每一步就是这么写出来的。
- **先跑一遍抓到并修掉的**:技能里 `transfer moves=[…]` 的整套写法(containers 重写)、`build`/`build blueprint*` 的
  旧写法(building_design、nether_entry、stronghold_finding、tier_progression、end_game_overview、blaze_rods)、带
  占位符写不通的 `fight attack --entity_ids <id>`、`use block right <x> <y> <z> …`、不存在的 `wait`;动作注意与工具描述里
  没加反引号的命令提及(都补上了,否则读不到);系统提示的例子 `command(use block right <the furnace…>)`。
- **插件在自己的模块里读**:每个带命令组的联动有一个同样的测试(`plugins/*/src/test`,读法共用 core 测试里的
  `WrittenCommandsLint`),读它随身带的技能文档与它那一组的说明。
  - 跑在 core 单测的原版环境里:NeoForge 打过补丁的 MC 离了 FML 引导不起来,而读命令只要原版的指令树、core 的命令组与
    联动自己的登记代码。环境由 `numen-plugin` 约定接好,插件只写一个测试类。
  - 目标模组不需要在场:测试不经 `Builtin` 的闸门、不假装模组已装,只执行联动登记命令组的那一段(同一个 `install`,经同一扇
    `NumenPlugins` 的门),产品里"模组在场才登记"这条不动。登记时动作的处理函数要连上目标模组的类的(ftbquests 读任务书
    要 FTB 的类),那个联动把模组的 jar 加进测试运行时的类路径:只加载、不运行,不进任何产物。
  - 联动的组只进它自己那个测试进程,不混进 core 的单测(命令树是进程级的静态表,帮助的快照不受影响)。
  - 随发行 jar 的构建一起跑:`core:neoforge` 与 `core:fabric` 的 `check` 依赖它们带上的那几个联动的测试。
  - 先跑一遍抓到并修掉的:四份插件技能都写着 `<组> <动作> --help` 这种带占位符的写法,改成"在动作后面加 --help"并举一个
    能照抄的例子。

## 附录 H:包的上限与输出预算(09-26)

真机事故:主人让她建田园小屋,她写了一份 43 步的设计后执行 `build show tianyuan_cottage`,回执 19899 字符;
当时的 `TaskResultPayload` 用 `stringUtf8(16384)`,netty 编码时抛 `EncoderException: String too big (was 19899 characters, max
16384)`,连接断开,单人世界的房主掉线,服务器随之停止。两层根因:网络层的上限是随手定的数、发送前没人查、上游也不保证
不超;`build show` 把每一步完整列出,长度随设计增长,又不分页。

### 线上的上限:`Wire`

- **唯一来源** `network.Wire`,按方向的**单包上限**:下行 1048576 字节,上行 32767 字节;另有一条**整条消息**的上限
  `Wire.MESSAGE_BYTES`(8 MiB)与每个连接同时拼装的消息条数 `Wire.ASSEMBLING`(8)。
- **单包上限从哪来**(对着 1.21.1 的源码核过):下行的数是原版 `ClientboundCustomPayloadPacket` 的私有常量 `MAX_PAYLOAD_SIZE`,
  单测(`WireTest`)反射读它,改了版本先红。上行的数是 32767:1.20.1 的原版对客户端发来的**所有**自定义载荷硬卡它,1.20.2 起
  只卡认不出的载荷;但 NeoForge(`GenericPacketSplitter`)、Fabric(`registerLarge`,上行分片阈值同为 32767)与 CC: Tweaked
  (上传文件 30 KB 一片)都把它当成客户端到服务端一个包的安全线,超过就分片。真正的硬顶是帧:`Varint21FrameDecoder` 的三字节
  长度前缀,一帧至多 2097151 字节(`Wire.FRAME_BYTES`)。**判据:每个方向的单包上限加上包头仍在一帧以内**(`WireTest` 测它)。
- **超过单包上限:在自己的载荷层分片**(`network.Fragments` 与 `FragmentPayload`)。她的一整段程序(带上这个连接还没送过的模块原文)、
  反向请求与它的答复长度随数据长,实现 `Wire.Fragmentable`:超过本方向单包上限的,编码后的字节切成带编号的片(消息 id、第几片、
  共几片,第 0 片带原包的种类),除最后一片外片片等长,连续发出,不逐片等确认(连接本身有序可靠);对端每个连接一个收件箱,
  收齐拼回原包,交给原来的处理器,处理器感觉不到分过片。没超过单包上限的消息照旧一个包,没有额外字节。
- **为什么不用加载器的分片器、不碰 Netty 流水线**:NeoForge 与 Fabric 的分片行为不一(阈值、要不要对端协商、登记方式),Forge 1.20.1
  没有,而且要求对端同样有它;自己的一层在 13 个分支、3 个加载器上是同一份代码,收发两端永远配套。碰 Netty 流水线要 mixin
  `Connection`,各分支各加载器写法不同,又容易与加载器和别的模组冲突。先例是 CC: Tweaked 的 `UploadFileMessage`(消息层按 30 KB 切片)。
- **整条消息的上限与防滥用**:`Wire.MESSAGE_BYTES` = 8 MiB,大过正当消息的最大值——模块缓存每位主人至多 4 MB
  (`ProgramLimits.MODULE_CACHE_BYTES`),服务端报缺时要整批重送,再加程序、清单与包头。拼装中的消息超过它当场拒收并丢弃(片数声明
  多过装满它要的、已收字节超过它、编号不连续、除最后一片外不是满的——后者防一字节一片的放大);同一个连接同时拼装的消息多过
  `Wire.ASSEMBLING` = 8(一位主人至多同时跑 8 段程序,每段至多一条大消息在路上)拒收新的;断线清掉残片
  (服务端 `ServerPrograms.ownerLeft`,客户端 `AgentLoopRegistry.quiesceAll`)。拒收写一行警告日志,不答复:对端不是正当的 Numen。
- **送出只有一条路** `NumenNetwork.sendToPlayer` / `sendToServer`:交给 `Fragments.packets`,用包自己的编解码器编一遍量字节(下行带着那位玩家的
  注册表)。装得下一个包照发;可分片的包超过则分片;内容随数据长、一个包装不下就缩的包实现 `Wire.Oversized`,缩成它自己给的、装得下的
  样子,如实说明原来多大、上限多少;别的包内容本来有界,装不下是填它的代码错了,当场抛 `IllegalStateException`,不交给 netty 去断开连接。
  登记时每种包的编解码器与处理器按方向记下(`toClient` / `toServer`),量的就是真正上线的那些字节,拼回的包按它登记的编解码器解、交给
  它登记的处理器。上行的包都不带注册表里的东西,编解码器写在 `ByteBuf` 上。
- **字段**:长度不由包自己定的文字一律用 `Wire.X.text()`——编码不另拦(整包由上面那一步量,字段再拦就是第二个判据),
  解码以整条消息的上限为防线。保留的语义上限只有两个,都由发送方先守:召唤的名字 16(原版的玩家名规则)、征询的附言 512
  (答复框截断)。
- **上行的程序**连整条消息的上限都装不下才不送:`ProgramUplink` 先量(`Wire.carries`),装不下不送,就地给模型一条失败——服务端根本不知道这段程序,不会有结果回来。
  下行的回执(`ProgramResultPayload`)是内容有界的包:回执在生成处就按 `ScriptLimits` 有界(见 `docs/shell.md` §十四),装不下就是代码错,
  当场抛,不再缩。

逐个载荷的处理:

| 载荷 | 方向 | 内容 | 装不下时 |
|---|---|---|---|
| `ProgramResultPayload` | 下行 | 程序的回执与每次调用的结局 | 有界(`ScriptLimits`),装不下当场抛 |
| `NumenEventPayload` | 下行 | 一批事件(实时一条,离线补发至多 200 条) | 从正文最长的一条起换成一句说明,种类、时刻、急不急都留着 |
| `NumenStatePayload` | 下行 | 背包、效果、骑乘、身体状态片段(插件给) | 先把身体状态换成说明;还装不下,背包也不带,说明里交代"空格子不是你的背包" |
| `CurrentTaskPayload` | 下行 | 任务名与描述(插件的任务也在内) | 描述换成一句说明 |
| `NumenDeathPayload`、`NumenRespawnPayload` | 下行 | 死因(原版死亡消息,名字长短不定) | 死因换成一句说明 |
| `ConsentRequestPayload` | 下行 | 征询清单(按种类归堆)、记住的规则行、轮廓(至多 256 格、32 只) | 有界(按方块与实体种类) |
| `CompanionListPayload` | 下行 | 名册(至多 64 只) | 有界 |
| `NumenLocationsPayload` | 下行 | 定位(至多 16 只) | 有界 |
| `PathDebugPayload` | 下行 | 调试路径(寻路一段的格数) | 有界 |
| `ClientUiActionPayload` | 下行 | 一个枚举 | 有界 |
| `RunProgramPayload` | 上行 | 程序编号、程序、模块清单与没送过的正文 | 超过一个包就分片;连整条消息(8 MiB)都装不下才不送,客户端就地回失败 |
| `ClientCallPayload`、`ClientCallResultPayload` | 下行、上行 | 反向请求与它的答复(答复可带新模块清单) | 超过一个包就分片;连整条消息都装不下,请求当场回失败、答复换成失败 |
| `FragmentPayload` | 两个方向各一种 | 一条分片消息的一片 | 每片都是满的(最后一片除外),装得进一个包 |
| `StopProgramPayload` | 上行 | 程序编号、怎么停、原因 | 有界 |
| `SummonRequestPayload`、`ChangeSkinPayload` | 上行 | 名字(16)、Mojang 签名的皮肤(约 1KB + 700B) | 有界 |
| `ConsentReplyPayload` | 上行 | 答复与附言(512) | 有界 |
| 其余上行(`CancelTasks`、`SpeakingState`、`LocateNumen` 至多 16、`RequestState`、`DismissRequest`、`SetGameMode`) | 上行 | UUID 与几个标量 | 有界 |

模型看到的样子(`N` 是整包编码后的字节数):

```
The result of this call came to N bytes, more than the 8388608 bytes one message to the server can carry, so it was not sent. Ask for less of it at a time.

This call came to N bytes, more than the 8388608 bytes one message to your client can carry, so it was not sent. Give it less at a time.
```

### 输出预算的落地

- 规则在第九节,常量在 `Listing`:2000 行或 50KB。`Listing` 从"每页 20 条"改为按预算切页,帮助的根与组列表同一个预算;
  抬头与结尾可以省。翻页提示照 pi:`[Showing 1-12 of 43. Use build show house --page 2 to continue.]`。
- **改成分页的**(会随数据变长,原来一次全给):
  - `build show`:设计按步分页;`--layer` 的切片按行分页;
  - `skill load`(快捷工具 `skill_load`,多了 `page` 参数):技能正文与附属文件按行分页,和 pi 读文件一样;
  - `memory recall`:札记正文按行分页;
  - `use gui`:按槽分页(模组的大容器);
  - `tlm models`:包级摘要与搜索结果按行分页,原来的"搜索至多 40 条"(悄悄截断,还报成"找到 40 个")删掉;
  - `ysm options`:能换的模型一行一个分页,`data` 不再带整份模型清单;
  - `scan blocks`(快捷工具 `scan_blocks` 多了 `page`):原来"至多 16 团、其余只计数"删掉,一团一行(一个 JSON 对象)由近及远
    分页。没扫全时抬头只说"读到的那部分里"有几团,`groups_total` 照旧只在扫全时给。09-30 起不再有团编号簿:只是看的团没有编号,
    翻页就是再看一次(和 `scan entities` 一样);带 `--into` 的团存进区域,翻页提示指向 `area show <区域>`,`--into` 与 `--page`
    同写当场拒;
  - `scan entities`(`scan_entities` 多了 `page`):原来"至多 20 只、truncated"删掉,一只一行由近及远分页;翻页现读,
    实体会走动,编号在它还在世界里时不变;
  - `inv recipe`:原来"至多 4 条配方"(悄悄截断,不说一共几条)删掉,一条配方一个条目分页,各工位的做法在结尾;配料是标签
    又没有共同后缀时原来只列 3 个成员接省略号,现在全列;
  - `kaleidoscope recipes`:原来"至多 30 行"删掉,一道菜一行分页,`data` 只留不随页变的品质说明;
  - `scan storage`:原来"每个物品栏至多 64 行,其余只计数"删掉。加载器的读法(`IBlockCapabilityReader.describe`)交回一行
    一条,命令按预算分页。
- **原来就分页的**:`build designs`、`build built`、`ftbquests list`,换成按预算切页。
- **审查过、判为有界的**(不分页,理由):
  - `scan around` 半径夹在 4–16;`task status` / `task timer` 表至多 8 个;
  - `ftbquests show`:一个任务的正文(整段给出;原来截到 600 字,截掉的她无处可看,删掉)、依赖、任务项与奖励,随这一个
    任务的定义有界;它的参数吃掉余下整行,也挂不上 `--page`;`ftbquests submit`:每个任务项一行,同样随一个任务有界;
  - `throwaway` 四个动作:回执读回整份清单,清单只随她一次次写 id 变长(一次调用至多一个上行包),同一份清单每轮就在身体
    状态里。回执与寻路没料时的那句话里"背包里还没进清单的方块"原来各截到 12 种、6 种,现在全列:种数不会多过背包的格数;
  - `fight attack` 不点名时收尾的名单随十分钟时限有界;`use block` 等收尾里新出现
    实体的行在半径 6 格内;后台任务收尾里"路上动了什么"按方块种类归堆。
  - 其余动作都是一个对象的回执,字段固定。

## 附录 I:车万女仆——她养自己的女仆(09-30)

目标:人能对车万女仆做的她都能做,她可以有自己的女仆。全部在 `tlm` 组里(组名是模组 id);同组的 `models`、`wear`、
`remove` 是她自己的外观,在主人客户端执行,不变。

### 原子动词与插件动作

人养女仆时做的事,一半是世界里的一下点击,一半是女仆界面上的按钮。前一半本来就是原子动词,插件不另包:

| 人做的事 | 她的写法 |
|---|---|
| 拿蛋糕右键野生女仆驯服 | `use entity right 812 --item minecraft:cake` |
| 拿背包右键女仆给她换背包、剪刀右键卸背包 | `use entity right 812 --item touhou_little_maid:maid_backpack_small` |
| 在女仆界面里搬装备、放东西 | `use gui`、`use transfer`、`use shift`、`use close` |
| 右键墓碑取回东西、右键祭坛柱子放材料、神社放胶片与复活 | `use entity right <墓碑>`、`use block right x y z` |

只有界面上的按钮只能由客户端发包触发,这几个是插件动作:

| 命令 | 做什么 | 车万女仆里对应的 |
|---|---|---|
| `tlm maids` | 名下的女仆:加载着的(编号、名字、模型、工作、日程、家模式、血量、好感等级、坐没坐、距离),没加载区块里的(最后位置),墓碑 | 世界里的女仆实体、`MaidWorldData` |
| `tlm maid <maid>` | 一只的详情,和每个工作模式能不能切、缺什么、干活用什么 | 任务列表每个按钮的提示 |
| `tlm task <maid> <task>` | 切工作模式 | `MaidTaskPackage` |
| `tlm config <maid> [--home] [--pickup] [--ride] [--schedule day\|night\|all]` | 改设置页上的四样 | `MaidConfigPackage` |
| `tlm open <maid> backpack\|bauble\|curios` | 打开界面的一页,之后用 `use gui` 那一套 | `ToggleTabPackage` |

女仆按 `scan entities` / `tlm maids` 给的运行时编号点名,参数类型与 `move follow --entity_id`、`fight attack --entity_ids`
同一个(`ArgType.entity()`)。

### 调车万女仆自己的包,判据留在它那一处

- 按钮的判据(是不是主人、这个工作模式此刻开不开得了、家模式离日程点够不够近)全写在包的 `handle` 里。插件构造同一个
  包,以她为发送者直接调 `handle`,调完读回女仆的状态写进回执;没照做就照读回的说,并附上车万女仆自己的规矩此刻怎么说
  (不是主人、这个模式缺什么)。插件不另写一遍判据。
- 不能从她的连接把包注进去:她的连接没协商过车万女仆的频道,NeoForge 会断开它。
- 上下文用 `ServerPayloadContext(her.connection, TYPE.id())`,不自己实现 `IPayloadContext`:接口标着
  `@ApiStatus.NonExtendable`,自己实现等于押 NeoForge 不往里加方法;`ServerPayloadContext` 标着 `@ApiStatus.Internal`,
  但它就是真包到来时 NeoForge 给 `handle` 的那个对象,构造参数一变编译当场报错。
- 插件碰车万女仆服务端类的只有 `Maids` 一处;命令、事件的类只拿原版的 `Entity` 和它交出的名字、数字。联动的防漂移测试
  只执行登记命令那一段,那里没有车万女仆。

### 距离与权限

- 包本身不查距离,可人只有开着界面、离得够近才按得到。三个做事的动作照 `use block` 的规矩:先用女仆界面保持打开的同一
  判据(`canInteractWithEntity(maid, 4.0)`,约 7 格)量够不够得着,不够就失败,回执给照抄的
  `move goto --x … --y … --z … --arrive near --near 2`。
- 三个做事的动作都是对这只女仆的 `use_entity`,经 `ServerSource.authorize` 交权限层:放行就做,拒绝如实回执,要问就挂起这
  一次调用等主人答复——和第 0 层指令、改区域同一个口子。答复回来之后按同一个编号再认一次、再量一次。
- 出厂规则里 `use_entity(!owned)` 放行、`owned` 一行都没说到,所以对她自己的女仆现在会问主人;出厂放行
  `use_entity(self_owned)` 落地之后就不问了。

### 事件与身体状态

- `maid_tamed`(不急):她驯服了一只。`use entity` 的回执只说手里少了块蛋糕,归属变了是这条说的。
- `maid_died`(恒为急件):她的女仆死了,死在哪、墓碑编号与位置。认的是车万女仆的 `MaidTombstoneEvent`(主人名下的女仆
  死时把东西和胶片装进墓碑的那一刻),挂最低优先级、不收已取消的。
- `maid_fed_you`(不急):"喂食"模式的女仆喂了她一口,喂的是什么、吃完的饱食度与血量。吃东西走原版 `Player.eat`,
  不经任何事件,车万女仆也不发事件,所以插件带一个窄 mixin 包住 `TaskFeedOwner.feed`;配置单独一份
  (`numen_tlm.mixins.json`),只在车万女仆在场时挂(`neoforge.mods.toml` 的 `requiredMods`),方法没了或签名变了启动当场报错。
- 每轮身体状态 `<touhou_little_maid>`:她身上的 P 点(满 5)、车万女仆给她记的女仆数与上限。

### 测试

- 防漂移:`tlm` 组的说明与技能 `maid_keeping` 里写的每一行命令按命令树读一遍(`:plugins:tlm:test`)。
- GameTest 单开一次跑批:`./gradlew --no-daemon :plugins:tlm:runGameTestServer`。运行配置在插件的 build.gradle,照成品拼
  一个 numen 模组(本体 + 这个联动 + 用例),车万女仆 `maven.modrinth:touhou-little-maid` 在运行时类路径上当模组加载;
  用例挂自己的命名空间 `numen_tlm`,只跑这个命名空间。不挂进 `:core:neoforge:runGameTestServer`:车万女仆的 mixin 会改掉
  全部用例的环境。
- 用例(从入口调):野生女仆拿蛋糕驯服并出现在 `tlm maids`、有 `maid_tamed`;切到种地、改成夜班并读回;开背包页后
  `use transfer` 放进女仆的格子;离太远失败并给 `move goto`;别人的女仆被车万女仆的主人判据拒绝;女仆死亡的急件带墓碑;
  喂食的女仆喂了饿着的她、有 `maid_fed_you`(拿掉 mixin 这一条就红)。

## 附录 J:命令行十条落地(10-01,`shell.md` §二、§三)

### 规矩写在登记处

- 参数是 `Param(名字, 类型, 说明, required, positional, whenOmitted)`:必填的就是位置参数;可以不写的位置参数
  (`Param.optionalPositional`)只能是最后一个;标志一律可以不写。
- 登记时查(`CommandGroup.checkParams`,核心与插件同一处,违反就在登记那一刻抛出):名字不重复;**位置参数只有一类对象**
  (按 `ArgType.noun()` 比,`area minus <result> <from> <areas...>` 三个都是区域);**每个可以不写的参数都写明不写时会怎样**
  (`whenOmitted`,也就是没有必填的标志);开关不当位置参数;一串值与吃整行的只能在最后。
- 开关(`ArgType.bool()`)在命令行上写 `--sneak`、`--no-sneak`,写 `--sneak true` 报"是开关、不收值";快捷工具的 JSON 照旧给
  布尔值。标志名在帮助、回执、重放里一律写短横线,解析时 `_` 与 `-` 是同一个字符(`Param.nameOf` 一处)。
- 时长一律秒:`task timer --after`、`use … --hold`(收小数,换成刻在处理函数里一处)。
- 报错三段:`error:` 错在哪、`usage:` 那个动作的用法(带例子)、`hint:` 能照抄的下一步(`Problem.of` 一处拼;第 0 层的原版
  指令报错也走它)。

### 对象写法只在一处读

`ArgType` 里一份读法,命令只声明自己的对象是哪一类:

| 类 | 写法 | 读法 |
|---|---|---|
| `cell` | `120 64 -35` 或 `120,64,-35`;后面接 `..` 的报"一片格子是区域,先框成区域" | `ArgType.cell()` |
| `place` | 一格 `x y z`、一列 `x z`、一个高度 `y`,或区域 `ores`、`ores/g3` | `ArgType.place()` → `Place` |
| `area` | `ores`、`ores/g3` | `ArgType.area()` |
| `entity` | 运行时 id(重放时换成 UUID) | `ArgType.entity()` |
| `id` / `idOrTag` | `iron_ore`、`minecraft:iron_ore`、`#minecraft:logs` | `ArgType.id()`、`idOrTag()` |
| `block\|cell\|area` | 路线标志里混写的一串:数字打头是格,`area:` 打头是区域,其余是方块或标签 | `ArgType.blockCellOrArea()` |

快捷工具的 JSON 走同一个读法:`{"place": "120 64 -35"}`、`{"place": ["ores/g3", "120 12 -35"]}`。

### 对照表(普查过 core 与插件的全部命令,只列改了的;没列的本来就合规)

| 旧 | 新 | 为什么 |
|---|---|---|
| `fight attack --entity_ids 27 26` | `fight attack 27 26`(不写 = 打附近所有敌对的) | 实体是它操作的东西 |
| `use block right x y z`、`use block left x y z` | `use block x y z`、`use block x y z --left` | 见下 |
| `use entity right 812`、`use ahead right` | `use entity 812`、`use ahead`(`--left` 同上) | 同上 |
| `use … --hold_ticks 30`、`--sneak true` | `--hold 1.5`(秒)、`--sneak` | 秒;开关 |
| `use sleep --x 1 --y 2 --z 3` | `use sleep --at 1 2 3`(不写 = 手边那张床) | 格子一处读 |
| `inv drop cobblestone 32`、`inv take diamond 64` | `inv drop cobblestone [--count 32]`(不写 = 身上的全丢)、`inv take diamond [--count 64]`(不写 = 1) | 必填的数量给默认 |
| `gear remove --slot armor` | `gear remove`(不写 = 盔甲) | 默认 |
| `work dig ores --count 10`(走过去、挖到包里 10 个) | `work dig ores [120 12 -35 …] [--count 4]`(只挖手够得着的格,`--count` 是格数) | 原子化,见下 |
| `move goto --x 120 --y 64 --z -35`、`--area farm` | `move goto 120 64 -35`、`move goto 120 -35`、`move goto 16`、`move goto farm` | 去处是它操作的东西 |
| `move follow --entity_id 184` | `move follow [184]`(不写 = 跟主人) | 同上 |
| `scan blocks 32 iron_ore`、`scan entities 24 hostile` | `scan blocks iron_ore [--radius 16]`、`scan entities [hostile] [--radius 24]` | 方块、种类是对象;半径给默认 |
| `scan block x y z` | `scan block x y z`(也收 `x,y,z`) | 格子一处读 |
| `area add house --box 1,2,3..4,5,6`、`--at 1 2 3` | `area add house [--box 1 2 3 4 5 6 \| --at 1 2 3]`(不写 = 脚下那一格) | 盒子是两格,格子一处读 |
| `area drop ores g2` | `area drop ores/g2` | 部分就是区域的写法 |
| `area grow buffer house 2`、`area filter … --blocks`(必填) | `area grow buffer house [--by 1]`、`area filter logs house [--blocks …]`(不写 = 只留扫到的格) | 必填标志给默认 |
| — | `area parts <区域>`、`area has <区域或部分>` | 新增,给脚本逐个取、按成败判 |
| `route new r --to …`(必填)、`route via r x y z --at 2`、`route drop r via 2`、`route reverse r --as r_back` | `route new r [--to …]`(不写 = 脚下)、`route via r [--at x y z] [--stop 2]`、`route drop r [--stop 2]`(不写 = 最后一个)、`route reverse r [--as …]`(不写 = `r_back`) | 一类位置参数;必填标志给默认 |
| `task timer 300 collect the iron` | `task timer "collect the iron" [--after 300]`(不写 = 60 秒) | 秒;理由是对象 |
| `task stop --task_id tm3` | `task stop --task-id tm3` | 短横线 |
| `build set stone 1 2 3`、`build line air a b`、`build place chest 1 2 3` | `build set 1 2 3 [--block stone]`(不写 = 手上那块)、`build line a b --block air`、`build place 1 2 3 --block chest` | 格子是对象,方块是选项 |
| `build layer 0 1 0 ### --up_to 3` | `build layer ### [--at 0 1 0] --up-to 3`(不写 = 设计原点或脚下) | 网格是对象 |
| `build cylinder stone 5 0 5 3 6 --hollow true`、`build sphere glass 5 8 5 5` | `build cylinder 5 0 5 --radius 3 --height 6 --block stone --hollow`、`build sphere 5 8 5 --radius 5 --block glass` | 中心是对象;半径、高给默认 |
| `build step house 2 layer …`、`build insert house 3 set …` | `build layer … --into house --step 2`、`build set … --into house --before 3` | 两个动作删掉,改成原语的选项 |
| `build drop house 4`、`build at house 100 64 -20` | `build drop house/4`、`build at house [--at 100 64 -20]`(不写 = 脚下) | 一类位置参数 |
| `memory remember name world "…"` | `memory remember "…" [--name …] [--type world]`(不写名字 = `note-N`) | 内容是对象 |
| `kaleidoscope cook x y z <菜>`、`inspect` 三个数、`--have_only true` | `kaleidoscope cook <菜> [--at x y z]`(不写 = 够得着的最近一口锅)、`kaleidoscope inspect x y z`、`--have-only` | 一类位置参数;开关 |
| `tlm task 812 <工作>`、`tlm config 812 --home true`、`tlm open 812 backpack` | `tlm task <工作> [--maid 812]`(不写 = 够得着的最近一只自己的)、`tlm config 812 --home --no-pickup`、`tlm open 812 [--tab backpack]` | 同上 |

**`use block` 默认右键、`--left` 是左键**:照命令行的习惯,两个值里有一个是常用的那个时,不写一个必须给值的 `--button`,
而是让常用的当默认、另一个做开关。右键是"用"这一组的本义(用、放、开),点一格、点一只实体十回里九回是右键;左键(打、挖)
是例外,所以开关的名字是那个例外。

### 原子化

- `work dig <对象...> [--count N]`:只挖她**站在原地手够得着**的格(判据与 `Goals.dig` 同一个:够得着、身体不占着);挡在前面的
  天然地形一并挖开,要主人同意的、规则不许的不挖、不替她问,回执说是哪一格、为什么(`DigQuote.walledIn`);点名的目标本身要问
  就站着等主人。不走动、不捡。够不着的回执里说还剩几格、最近一格在哪、能照抄的 `move goto <对象> --arrive dig`。原来的
  走动与捡拾(数物品、区外报告、重扫、工作区)删掉;建造清场那一份走动搬到 `build at` 的 `ClearSiteTask`。
- `work collect` 只捡:工作区(脚下半径 10 格的球)归它。
- `move goto <对象> --arrive dig`:到了 = `work dig` 站在这儿办得成——挡着视线的按 `work dig` 清遮挡的规格判清不清得掉、
  目标按它定价的规格判挖不挖得成(`DigTaskRecord.SPEC`、`TARGET_SPEC` 各一处);一块区域里挖不成的格不去,一格都挖不成就
  当场说最近那一格为什么。同样划算的站位里优先一次够得着最多格的,挖起来贵的格(要问主人的)只在便宜的远出它那份价钱时才去,
  定价只在 `Goals.dig(List<DigTarget>, …)` 一处。
- 够不够得着(`Reach`)量到包围盒往里收 `Reach.EDGE` 的那一圈,与瞄准离棱留的边同一个数:原子的 `work dig` 不再走动去找别的
  站位,`Reach` 说够得着而瞄不着的那一点差距会让它站在原地挖不成。
- `area parts`、`area has`:见上表;`area has` 按 `DigTaskRecord.wants`(扫来的格 `Cells.Seen.holds`,框来的格立着方块)在活世界里问。
- `build at`、`fight attack` 在帮助与类说明里标明是工作流(现在的实现里还替她做着决定,按 `shell.md` §三之后拆成原子命令
  与内置脚本)。

回执样例(坐标是示意;GameTest 断言的就是这些句子):

```
work dig ores                    受理:3 cell(s) of ores are within my reach where I stand; 5 more cell(s) of ores are out of
                                 my reach from here, the nearest at 131,12,-30 about 7 blocks away (later: `move goto ores
                                 --arrive dig`, then `work dig ores`).
                                 收工:dug 5 cell(s) of spruce_log; 16 more cell(s) of ores are out of my reach from here,
                                 the nearest at 127,64,94 about 5 blocks away — to dig them: `move goto ores --arrive dig`,
                                 then `work dig ores`. What I dug dropped on the ground: `work collect` picks it up.
work dig ores --count 2          dug 2 cell(s) of oak_log (the --count 2 I was given). What I dug dropped on the ground:
                                 `work collect` picks it up.
work dig ores(手边一格都没有)   I did not start: none of the cells of ores still to dig is within my reach where I stand;
                                 2 more cell(s) of ores are out of my reach from here, the nearest at 50,2,50 about 66 blocks
                                 away — to dig them: `move goto ores --arrive dig`, then `work dig ores`.
work dig 8 2 5(六面贴着别人放的木板)
                                 I did not start: every face of pumpkin at 8,2,5 is covered by a block I may not break:
                                 oak_planks at 7,2,5; oak_planks at 9,2,5; … (changing it needs the owner's consent); no
                                 stance lets me see it, and those blocks are not mine to get around, so dig something else or
                                 ask your owner.
work collect                     collected 3 all items
move goto ores --arrive dig      standing at 129,-58,90, within reach of a block of area ores — `work dig ores` digs it from
                                 here, via route goto-aria.
move goto barn --arrive dig      none of the 2 cell(s) of area barn can be dug; the nearest: pumpkin at 98,-58,93 can't be
(整块不许挖)                    dug: denied by rule break(area:barn) (is in area barn).
area parts ores                  ores/g1
                                 ores/g2
area has ores/g2                 成功:ores/g2 has 1 cell(s) left to dig, the nearest at 11,2,11.
                                 失败:ores/g2 has nothing left to dig (2 cell(s), all gone or changed since they were added).
```

### 留下的

- 存档里旧写法的设计步骤与路线标志(如 `--parkour true`、旧的原语写法)、旧的重放行读不通,要重写一遍。
- 全是数字的区域名会读成坐标。
- 路线标志里方块、格子、区域混写一串时区域仍要带 `area:`。

## 附录 K:只有 lua 一个工具,API 原子化(10-03,`shell.md` §七)

### 一个入口

- 模型的工具表只剩 `lua`(`ScriptTool`)与外接 MCP 服务器的工具。`command` 工具、各组的快捷工具(`goto`、`work_dig`、
  `task_stop`……)、`todowrite` 工具删了;计划清单是函数 `todo.write`,参数原样回显在回执的 `data.echoed` 里,聊天里的清单
  从那里画(`PlanChecklist.of`)。
- 一次 Lua 调用读成动作只在 `NumenCli.invocation` 一处:对象与选项写成参数名到值的 JSON,经 `CommandArgs.fromJson`
  读一遍,执行侧读的也是它,中间不拼命令行。人敲的命令行经 `NumenCli.read`,两个前端共用同一张登记表与处理函数。
- 系统提示 `<api>`、`api.help`、工具说明、技能、提示词里的例子都是 Lua;防漂移测试(`WrittenCommandsLint`)把反引号与
  ```lua 代码块里的程序经脚本前端读一遍。
- 外接大脑(MCP)的 `lua` 调用交给她自己的派发器(`AgentLoop.runAside`):同样的等收尾、上限与回执。

### 原子化后的普查

| 组 | 动作(原子) | 删掉或搬走的 | 组合它的库函数 |
|---|---|---|---|
| move | `go`(照一份计划走)、`follow`(跟一个实体,总有结束)、`dismount`(下坐骑) | `goto` 动作(规划+走) | `move.to` = `route.plan` + `move.go`;`move.flee`、`move.explore` |
| route | `plan`(收一张描述,只规划不动,交回计划) | `new`、`via`、`drop`、`spec`、`show`、`list`、`delete`、`reverse`(10-03,路线不存) | — |
| work | `dig`(手够得着的)、`fish` | `collect` 动作 | `work.collect` = `scan.entities("item")` + 一件件 `move.to` |
| fight | `attack`(只收一只) | "打一片"(`--entity_ids` 一串) | `fight.clear` = `scan.entities("hostile")` + 一只只 `fight.attack` |
| build | 原语 `set/place/line/layer/cylinder/sphere/copy`、设计 `new/show/drop/designs/delete`、`at`(只放手够得着的格)、`left`(查还差什么)、`built` | `at` 的走动、绕外圈、清场(`ClearSiteTask`)、垫块记账(`DropTracker`) | `build.raise` = `build.left` + `build.at` / `move.to … arrive "dig"` + `work.dig` / `move.to … arrive "place"` |
| scan、area、locate、status、inv、use、gear、task、script、memory、skill、todo、mc、api | 本来就是原子的,不变 | `throwaway` 组(10-03,改成描述的 `materials`) | — |
| 插件 tlm、kaleidoscope、ysm、ftbquests | 本来就是一种意图对一个名词(喂、开界面、换装、交任务……),只改了说明与例子 | — | — |

- 到达方式新增 `reach`(10-03 改名 `place`):手够得着往那一格里放方块、不站进那一格(`Goals.place`)。
- `build.at` 一格都够不着时当场拒(`OUT_OF_REACH`),说够不着的几格、最低最近的那一格与照抄的 `move.to(…, {arrive =
  "place"})`,要先挖开的几格与 `work.dig`;放完够得着的、还剩别的,以成功收场并在回执里说还剩什么。设计格里立着别的方块
  (生存)由 `work.dig` 挖,`build.*` 只放。
- `scan.entities` 的掉落物带 `pickup_delay`(还要几刻才捡得起),位置是 Pos(附录 L)。
- 每个调用在脚本里返回它声明的数据(附录 L)。

## 附录 L:值、错误与帮助一个样子(10-03,`shell.md` §八)

### 前后对照

| | 之前 | 现在 |
|---|---|---|
| 一格 | `{120, 64, -35}`、`"120 64 -35"`、`"120,64,-35"` 都收 | 只收 Pos `{x = 120, y = 64, z = -35}`,或任何带 `pos` 的表;旧写法 `bad_argument`,`hint` 是改好的那一行 |
| 一列、一个高度 | `{120, -35}`、`16` | `{x = 120, z = -35}`、`{y = 16}` |
| 查询结果的位置 | `"120,64,-35"` 字符串 | Pos;方块 `{pos, name, …}`、实体 `{id, pos, name, category, …}`、物品继承实体 |
| 返回 | 有声明返回项的交那一项,其余是回执数据或回执那句话 | 每个动作必须声明返回类型;返回数据,声明不返回的是 nil;那句话只进回执 |
| 失败 | Lua 错误是一个字符串 `work.dig: <那句话>`,种类靠读文字 | 错误值 `{kind, message, hint, fn, data}`,`tostring`/`..` 写成 `work.dig: out_of_reach — …` |
| 参数错 | 三段 `error:`/`usage:`/`hint:` 的字符串 | `bad_argument`/`no_function` 的错误值,`message` 带用法,`hint` 是改好的那一行或怎么看帮助 |
| 帮助 | 用法行 `work.dig(place..., {count=…}) — 说明` | LuaLS 注释:组是 `---@class` 加每个函数一行 `---@field f fun(…): 返回`;一个函数是完整的 `---@param`/`---@return` |
| 库函数的说明 | 注释的第一行 | 注释的第一句;签名照它的 `---@param`/`---@return` |
| 评测的失败分类 | 正则读回执文字 | 读回执 `data.error.kind`(`Meter.errorKind`),五类由种类归(`Meter.errorClass`) |

### 删掉的

- 三个数的列表与数字串的位置读法(`ArgType` 里的旧分支)、用法行风格的帮助(`CommandHelp` 的 usage 清单)、`NavText.lua(Place)`
  (位置写进程序的另一处写法,改用 `Place.literal`/`Shapes.literal`)、回执消息里一行一个 JSON 的清单(`scan.blocks`、
  `scan.entities` 的行改成数据里的列表)、`Meter` 里读文字的几条正则。

## 附录 M:日式小屋盖不完的根因(10-03)

`build_japanese_cottage` 在底层来回打转、卡在一千来格。修好一处,盖得更远,下一处才露出来;按露出来的先后:

- **路线挖她刚放下的设计格。** `build.raise` 走去够下一格用 `alter = "natural"`;安山岩、花岗岩这类设计格在权限层眼里是
  她自己放的(`self_placed`,放行),路线为了站进地面那一层把它挖开,下一轮 `build.at` 补上,再下一趟又挖。修法在规划的
  禁区里:每段路的底子是 `RouteFlags.base(her)`,出厂规格加上 `Built` 记着、此刻还立着的格禁挖
  (`Built.standingIn`、`PositionCosts.forbid(DIG, …)`),哪个路线标志都放不开;`Built` 是"哪些格算一栋房子"的唯一出处,
  她垫的料不在里面,照旧可挖。站位与够得着照旧只由寻路的目标(`Goals`/`Reach`/`Feet`)判,禁区只让那些要挖房子的站位
  进不了路线。复现:`CellRouteGameTests.a_walk_never_digs_a_building_she_built`。
- **托着它的还没盖好的格被当成"够得着就放"。** 地毯、挂着的灯这类格放下去要有相邻的方块托着;托着它的那格够不着时,
  `build.at` 把它算进手边的活、放不下就以"原版立不住"失败,或者 `build.left` 让她去够它、到了又放不下,在两处之间来回走。
  `BuildSurvey` 多一种情形 `UNHELD`:此刻立不住、而相邻的设计格还有没盖好的;`build.at` 不放它,`build.left` 数进
  `unheld`、`next` 不指向它。只问走原生车道(像右键那样放)的格:物品落位时原版问的就是 `canSurvive`;照图直写的格立不立得住
  由建完之后的落定说。相邻的都盖好了还立不住的,照旧交给 `build.at` 放、照实报"立不住"。复现:
  `BuildGameTests.a_cell_whose_support_is_out_of_reach_is_not_within_reach`。
- **跳进炼药锅:头顶的空只够贴着锅沿。** 迈步推导(`Stepping`)判起跳时只看身体在"最高的脚高"上平移得过去,好像能停在恰好
  够高的那一点;真跳起来身体升到顶(约 1.25 格)再落,锅上方两格压着的上半活板门先撞头,挪不过锅沿,一步步卡死。改成按起跳
  真能到的顶(起点那一列头顶撞上的为止)、在越过的高度再高 `JUMP_CLEARANCE`(0.2 格,高出这么多的有三四刻能往前挪)上平移。
  复现:`SteppingTest.aJumpNeedsRoomAboveTheRimNotJustToTouchIt`。
- **规划只问了能贴的面,执行瞄不中。** 上一级要先在面前垫一块台阶:规划判"能放"只看邻格有没有可贴的面,执行时从站着的
  眼睛瞄那一面,准星那一点被栅栏北边的圆石挡着,放不下,一直空等。点得中的那一面挪到第 0 层 `Faces.inSight`,执行
  (`Aim.face`)与规划(`Draft.placeInSight`,上一级垫台阶用它)问同一个。复现:`PlacementTest.aFaceTheEyeCannotReachIsNotInSight`。
- **一圈圈落回去,期限永远从头算。** 走着一步落回了前面一步的起点,驱动层悄悄退回去重走;再加上每走成一步就清掉全部
  "走不下去"的次数,同一处绕上几百圈也等不到卡住,只能等到这件活的期限。落回前面一步的起点现在照"走不下去"记一次
  (`Hitch.FELL_BACK`),次数只勾掉走成了的那几步的,同一步第三次就收场。
- **走到半路要改计划外的格。** `move.go` 守着规划时的承诺,半路重搜要多放一块就停下(`no_path`)。`build.raise` 把这次
  `no_path` 当这一轮没走到:下一轮从站着的地方重新问还剩什么、重新规划;一轮什么都没变照旧以 `failed` 收场,并带上最后一次
  没走到的原因。别的错原样抛。
- **盖完、世界落定之后又去补。** 最后一格放下时世界落定一次,原版立不住或形状由邻居定的格会变;`build.left` 又数到它们,
  `build.raise` 补了又落、落了又补。`build.at` 放完整份时回执数据带 `settled_away`(落定后变了的格数),`build.raise` 见它
  放完(`left == 0`)就收工:落定之后的样子是原版的裁决,再放一遍还是这样。

## 附录 N:API 第二版(10-04,`shell.md` §十)

### 全名

脚本与文字里一律写全名:`numen.<组>.<函数>`,插件 `<模组 id>.<组>.<函数>`。名字空间由 `NumenPlugins.register(名字空间, …)` 定,
组的登记代码不写名字空间;帮助、`<api>` 索引、回执里的函数名、提示与技能的例子都按全名写,防漂移测试按全名读。

### 删掉的

| 删掉的 | 去了哪 |
|---|---|
| `area` 组、`scan.blocks` 的 `into`、`work.dig` 收区域、路线标志里的区域名 | 没有区域:`scan.blocks` 交回团(Cluster),`work.dig` 收 Block 或 Pos,路线的 `avoid` 收选项或格 |
| `build.new/drop/delete/show/designs/built`,原语 `build.set/place/line/layer/cylinder/sphere/copy` | 模块 `numen.shape` 画 Cells;`numen.build.place(cells)`、`numen.build.diff(cells)`;蓝图句柄 `numen.build.blueprint` |
| `build.at`、`build.left` | `build.place`(放一遍)、`build.diff` |
| `scan.around`、`scan.storage` | `scan.map`、`scan.container` |
| `use.ahead`、`use` 的 `left`、`use.gui/transfer/shift/close` | `use.item`、`use.hit`、`numen.gui.view/move/quick/close` 与 Window |
| `inv.recipe`、`inv.take`、`inv.craft` 找台走开合关 | `inv.recipes`、`numen.creative.give`、`inv.craft` 合一次 + 模块 `numen.inv.make` |
| `work.fish` 的 `count` 与常驻 | 一次一竿 |
| `fight.attack` 里的逃跑 | 逃跑本能 `FleeChain` |
| `kaleidoscope.pot.cook` 的 Java 版 | 锅上的六步 + 模块 `kaleidoscope.pot.cook` |
| 登记检查"位置参数只有一类" | 删了:`build.blueprint(name, origin)` 这样一个函数收两种对象是这一版要的签名 |

### 新增的

`scan.sight`、`use.hit`、`gui.put/take`、`inv.recipes/items/count/craftable`、`gear.hold`、`time.wait`、`creative.give`,
模块 `numen.shape`、`numen.gui`、`numen.inv`、`numen.time`;类 Recipe、Window、Blueprint、Cluster 与 Pos/Cells 的方法。

