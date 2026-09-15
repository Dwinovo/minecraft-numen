# 权限层:哪些动作要问主人,怎么问

状态:设计稿(2026-09-14)。与 `route-and-permission.md` 的分工:那份讲规划器怎么出带价签的
路线,本文讲身体的任何动作在动手前怎么过权限。权限层不是路由的子功能,路由只是它的一个
调用方;攻击、开容器、丢物品是另外几个。

## 一、问题

四类反复被玩家诟病的行为,根因是同一个:全仓没有"这东西是谁的"这个概念,所有保护都按
方块种类枚举,而种类枚举不完。

- `mine oak_log` 把家里的橡木柱子当野树砍了(#74 及之前多次反馈)。
- 开箱子、开熔炉时误拆箱子熔炉,里面的东西洒一地。
- 寻路时挖穿主人的墙、地板。
- 攻击时误伤主人的宠物、命名的动物、村民。

现有判据散在四处:`do_not_break` 标签硬禁挖几种设施;`NavSettings.blocksToAvoidBreaking`
对工作台熔炉箱子只做成本乘十,没路照拆;建造任务另有一道"带方块实体不动";寻路目标格的
`sacred` 只护这一次导航。

## 二、参照:主流 harness 怎么做

**Claude Code**(文档 `permissions`、`permission-modes`、`auto-mode-config`):

- 判断顺序固定:deny、ask、allow 三张规则表先裁,第一个命中即定;只读动作和工作目录内的
  编辑自动放行;其余交给分类器;拒了模型收到理由去找别的办法。
- 分类器的三个判据:不可逆、破坏性、指向环境之外。"环境"由用户告诉它——信任的仓库、
  域名、桶,没列的都算外面。
- 规则三档:`hard_deny` 无条件,用户意图也解不开;`soft_deny` 是破坏性动作,用户明确点名
  的意图能解开("清理一下仓库"不算授权强推,"把这个分支强推了"才算);`allow` 是例外。
- 关键路径是熔断器:删 `.git` 这类路径,连 allow 规则和 hook 说 allow 都批不了,"guards
  against model error"。
- 提示三个按钮:是 / 是且不再问(存成一条 allow 规则)/ 否;否可以附一句话,原话作为拒绝
  理由发给模型。
- 问不问看的是动作内容不是工具名:`Bash(git push *)` 是按命令内容写的规则。
- 权限由 harness 强制执行,不由模型执行;提示词只影响模型尝试什么。

**pi**(`docs/security.md`、`examples/extensions/permission-gate.ts`):核心零权限,工具只分
只读集合与会改的两组;权限门是扩展里挂在 `tool_call` 上的一个可阻塞钩子,HITL 就是在钩子里
`await` 一个对话框,没 UI 就默认拒绝;远程 UI 是带 id 与超时的请求响应子协议。教训是把门做成
独立一层、只有一个入口,核心保持干净。

## 三、原则

1. **权限是动作的属性,不是工具的属性。** 哪个工具走到受门控的动作,哪个工具在那里停下来等。
2. **信号通用,不按种类枚举。** 玩家放置、带方块实体、有主人、有名字、是村民,这几个信号
   覆盖原版和任何模组。高级工作台有方块实体,模组宠物继承原版驯服,都不用适配。
3. **主人定义环境,引擎不猜。** 同伴的沙盒是自然世界加她自己的背包;出了沙盒是主人的和别人
   的。哪些东西"随便动"由主人写规则,不由启发式判"像不像建筑"(Baritone 七年没做成的事)。
4. **模型永远不是权限的执行者。** 它没有任何写入口;它看到的只是普通的工具结果和回执。
5. **一个入口。** 全仓凡会改世界的地方只问一个裁决函数;判据只有一个出处。
6. **代码里不写死能,也不写死不能。** 不是说不能,而是要问,不替主人做决定。放行只来自主人
   看得见、改得了的 allow 行与他选的 bypass;拒绝只有两个来源——主人写的 deny 行、主人选的
   observe;没有一行规则说过的事就问。内容(mine、build……)
   不判、不跳、不问,只提出动作。身体的物理与安全判断(岩浆与危险格、摔落上限、挖不动)
   回答的是"做不做得到、会不会死",不属权限,不经裁决、不弹卡。

## 四、概念

放在 api,它是机器;core 的任务与工具是往里送动作的内容。五个零件,与 #10 提案的
`ProtectedAction` / `ProtectionRule` 同形。

**动作(Action)。** 身体要对世界做的一件具体的事及其目标:`break(pos)`、`place(pos, block)`、
`attack(entity)`、`use_block(pos)`、`use_entity(entity)`、`take(container, item)`、`drop(item)`。
不带工具名、不带 JSON。

**信号(Signal)。** 给动作贴事实的函数,每个只回答一个通用问题:

| 信号 | 问题 | 来源 |
|---|---|---|
| placed | 这格是不是别人放的 | `BlockItem.place` 返回处的 mixin,谁放的就连同放的人按区块记进每维度一份 SavedData(真玩家、同伴都记);查询时格子已是空气视为无记号;放的人就是要动手的同伴自己时不算——她垫的路、搭的桥是她的,别人家同伴搭的要问;build 完工把成果格改记到主人名下 |
| block_entity | 这格有没有方块实体 | 世界 |
| contents | 容器里有没有东西 | 世界 |
| owned | 这只实体有没有主人 | `OwnableEntity` |
| named | 有没有自定义名字 | 实体 |
| villager | 是不是村民 | 实体类型 |
| hostile | 是不是敌对 | 实体分类 |
| hazard_item | 放的是不是岩浆、火、TNT、水 | 物品 |
| near_placed | 放置点附近有没有别人放的方块 | placed 的邻域查询,同样不算她自己放的 |
权限层只做原版:领地模组与服务器保护不接进裁决,领地之后以联动插件做。

原生通道照旧生效:挖掘照真客户端发 START/STOP,原版的出生点保护、冒险模式,以及取消左键或破坏事件的模组
都在那里拦。`BlockDigger` 在 START 与收尾那一下之后读服务端的挖掘状态对账,被退回来的按 `REFUSED`
收场,理由"服务器没让挖掉这一格",不空挥到超时,也不把没挖掉的报成挖掉了。这是身体如实汇报,不是权限。

**规则(Rule)。** 两层,每层 deny、allow、ask 三张表:主人层是主人自己写的(命令、"允许并记住",
存档见 §七),出厂层是下面这张默认表。查的顺序第一个命中即定:

模式 → 主人层(deny → allow → ask)→ 出厂层(allow → ask;出厂 deny 表是空的)→ 都不中也问。

- 主人层整体先于出厂层:主人手写的 `ask break(!placed & !block_entity)` 压过出厂的自然方块 allow 行,
  挖自然方块也问;主人写的 allow 行也压过出厂的 ask 行。
- 层内 allow 先于 ask:"允许并记住"(§七)存的是从某条 ask 行里抠出来的一条更细的 allow 行,ask 若先查,
  记住的规则永远轮不到。记住 `allow break(placed & minecraft:cobblestone)` 之后挖玩家放的圆石不问,挖玩家
  放的橡木仍问。于是出厂 allow 行也必须写得比出厂 ask 行窄。
- 任务期授权(§六)只覆盖问出来的动作,解不开拒绝。

裁决快照(`Gate`)由 `Permission.gateFor` 在主线程取:模式、主人层与出厂层、放置记录、任务期
授权,不可变,任何线程可读。一条规则一行字符串 `动作(信号 & 信号 & !信号)`,与 Claude Code 的
`Tool(specifier)` 同形,全仓只在 `Rule.parse` 解析,写错了回教学式的错误(列出认得的动词与信号):

```
break(placed)          攻击/挖掘/放置四个动词 × 信号
attack(owned)
place(hazard_item & near_placed)
break(#minecraft:beds) 也接受方块标签与 id,给主人写细规则用
```

出厂默认表:

| 表 | 规则 |
|---|---|
| deny | 空 |
| allow | `break(!placed & !block_entity & !#minecraft:beds & !#minecraft:doors & !#minecraft:trapdoors & !#minecraft:fence_gates)`、`place(!hazard_item)`、`place(hazard_item & !near_placed)`、`attack(!owned & !named & !villager)`、`use_block(*)`、`use_entity(!owned)`、`take(*)` |
| ask | `break(block_entity & contents)`、`break(placed)`、`break(block_entity)`、`break(#minecraft:beds)`、`break(#minecraft:doors)`、`break(#minecraft:trapdoors)`、`break(#minecraft:fence_gates)`、`attack(owned)`、`attack(named)`、`attack(villager)`、`drop(*)`、`place(hazard_item & near_placed)` |

allow 行把日常动作一行一行写明:自然方块、不危险的放置、敌对生物与野生动物、开关门开容器、
对没主人的实体右键、从容器拿东西。ask 表里同一个动作命中几行时第一行作数,所以更具体的在前
(装着东西的容器先于玩家放的)。从主人的容器拿东西默认放行:她的设计就是用主人的工作台熔炉
箱子,相当于 Claude Code 读项目文件;主人想管就把 `take(*)` 改窄、加一条 `take(placed)` 的 ask。

**不可逆提示。** `attack(owned)`、`break(block_entity & contents)` 是普通 ask 行,主人可以允许,
"允许并记住"也盖得住——代码不替他决定。只是它们撤不回(宠物死了、箱子里的东西洒一地会消失),
征询卡片把命中这两类的清单项标一枚醒目的"撤不回"。标不标由信号决定(`owned`、`contents` 是
撤不回的信号),文案只在语言文件一处。

**裁决(Verdict)。** 唯一入口,收一个或一批动作,回答三种之一:放行;拒绝并附理由;需要主人
同意并附理由(问的是哪一行规则,清单按它归堆)。

**模式(Mode)。** 每个同伴一个,主人设(`/numen permission mode`,面板页待交互重做):`ask`(默认,走规则表)、
`bypass`(全放行,单机不想被打扰的人用)、`observe`(只看不动,拒绝一切改世界的动作,等于 plan mode)。

**征询(Consent)。** 裁决说需要同意时发起,见 §六。

## 五、动作分类

| 档 | 动作 | 对应 Claude Code |
|---|---|---|
| 从不问 | scan、look_around、inspect、status、lookup_recipe、plan_route | Read、Grep、Glob |
| 出厂 allow 行 | 挖自然方块、砍野树、用自己的方块搭路盖房、打敌对生物、宰野生动物、开关门与栅栏门、开容器、拿东西 | 工作目录内的编辑 |
| 问 | 挖玩家放的、挖带方块实体的、打有主人或有名字的、打村民、丢物品、在别人的东西旁放危险物,以及没有任何一行规则说到的动作 | `rm -rf`、`git push`、网络 |
| 拒 | 主人写的 deny 行、observe 模式 | deny 规则 |

合成烧炼、吃、装备、换工具不改世界,不是权限层的动作;挖不动(基岩)是物理,不是拒绝。

走路默认不改地形是规划层的最小权限,不在本表里;它决定的是"要不要挖",本表决定的是
"这一格能不能挖"。

## 六、检查时机与征询协议

**三个时机,各管一事。**

| 时机 | 做什么 |
|---|---|
| 规划 | 成本模型只读裁决:放行按原价,要问的格在 `alter=any` 下乘 `CONSENT_COST_MULTIPLIER`、其余规格下无穷大,拒绝无穷大;账单每条挖掘条目带着那一条征询(`TerrainBill.Break.consent`) |
| 执行开始 | 整条路线或整个动作过一次裁决,需要同意就发起一次征询;不是走到墙边才问 |
| 每次动作 | `BlockDigger`、攻击落点、放置落点强制,不发起征询;到这里还没授权就当动作失败,任务按既有机制重算或收尾 |

**各内容的接入点。** 内容只提出动作,判与问都归权限层(`AbstractCompanionTask.permit`/`permitAll`、
导航的开走前放行口)。

| 内容 | 什么时候送动作 |
|---|---|
| mine | 两种用法:`block_ids` 由 `BlockSearch` 找候选;`groups` 只挖最新一次 `scan_blocks` 点名的团里、仍是扫描时那种方块的格子。选目标不看权限:主人放的方块和野树一样是候选。规格默认 `alter=any`,模型的 `spec` 叠在上面;挑目标按"走过去 + 挖它"的同一套定价(需要同意的格贵十倍,A* 按到达价挑终点),附近有野树时自然先挖野树;轮到需要同意的目标、或为了够到目标要穿过需要同意的格,动手前或开走前征询;允许就挖,拒绝(主人拒绝、主人写的 deny、observe)与服务器退回的挖掘,两种用法都按 REFUSED 附理由收场,不略过继续 |
| scan_blocks | 不改世界、不征询。命中的每一格用挖掘落点会提交的同一个 `break` 动作在主线程问一次裁决(`Gate.judgeLive`),相连且说法相同的格子成一团,团带着说法与理由报给模型;玩家放的原木柱贴着野树是两团 |
| goto、follow | 规划出路以后、开走以前(导航采纳每一段路之前,含路线簿里的路与预算内整路),`alter=any` 的路把账单里要问的格打包送一次;重规划再查,授权覆盖的不重复问。无路时探针放宽一档(只走不改的先查自然改动,自然改动的查 `any`),候选行标 needing consent |
| build | 施工前把要清的格与要放的格整批裁决,要问的一张卡;允许就建,拒绝的格按"主人不让动"跳过并写进回执 |
| attack | 开打前送目标,要问的合成一张卡,等答复期间不打它;自卫换目标时新冒出来的再送 |
| interact_at、interact_entity | 按下去之前送准星落到的动作:左键是挖、打,右键是 `use_block`、`use_entity` |
| transfer | 逐步执行,把东西从容器里拿进背包的那一步动手前送 `take`(容器是右键打开界面的那一格);她自己背包的合成格与没有方块实体的工作台类界面不算 |
| drop_items | 每次送 `drop`,出厂是问 |

**允许的作用范围。** 对所有工具通用:本任务内,同一行规则问出来的同一种方块(或同一只实体)
都算已授权,不再重复问——挖一堆主人放的原木只弹一次卡;换一种方块、另一只实体另问。授权只把
"问"变成放行,解不开任何拒绝。任务收尾(成功、失败、被顶、叫停、身体离开)即清。

**征询是一个请求响应子协议**,与 pi 的 RPC 扩展 UI 同形:

1. 服务端登记处(`ConsentDesk`,挂在同伴身体上)记一条请求:id、同伴、清单(每条带动词、
   方块的坐标或实体 id、命中的规则与自述、方块或实体名)、到期游戏刻。清单本身说清要做什么,不另附原因。
   实体认那一只,不记它脚下的格——它走一步不算新的请求。同一同伴同时只挂一条,清单真的变了才由新的
   顶掉旧的,旧的按拒绝收尾("被新的请求顶替")。
2. `ConsentRequestPayload` 发主人客户端:清单在服务端按"动作 + 对象 + 理由"归堆,每堆拆成给主人看的几样——
   动作、物品图标(实体和没有物品形态的方块给名字)、数量、可翻译的理由(信号有各自的短译文,玩家放的说出是谁放的);格子不写,世界里描
   轮廓——给模型的回执是同一堆的英文说法(坐标最多点名 6 个,与路线账单同一种说法)。另带"允许并记住"会写进主人层的规则行、
   要描轮廓的格子与实体。
   世界里给涉及的方块和实体描轮廓,请求结束就撤。撤回带着原因(超时、任务结束、发起者撤回),主人没答就撤掉的
   用一条 toast 说清。
3. **问答挡住对话框**(与 pi 把编辑器整个换成选择框、Claude Code 的权限提示同形):这只同伴的整条输入行
   (输入框连同旁边的键、快捷对话的名字牌)换成答复框,答完才回来。答复框和输入框同级:占整行的宽度,
   往上长多高就占多高,上面的对话流让位,不是叠在对话流上的弹层。G 面板的聊天页与 Y 快捷对话是同一张框
   (都由输入行开)。版式照 pi 的选择框收,让界面替文字说话:顶上一道线(撤不回时是红的),抬头是她的脸、名字、
   别的同伴还有几条("+1")、一条缩短的时间条;清单一堆一行"挖 [原木图标]×6 · 玩家放的",撤不回的整行警示色;
   四项前面是序号,选中的那项多一个箭头:**允许**(只在发起它的任务里有效)、**以后都允许**(本任务内同样放行,
   并把 allow 行写进主人层,见 §七;要记下的规则只在选中这项时出现)、**拒绝**,第四项就是一个只画下划线的
   输入框(占位写着"不行,告诉她该怎么做"):选中直接打字,回车按拒绝连同这句送出,↑ 回到选项。这个输入框就是
   输入行自己那一个,屏幕上始终只有一个真输入框。↑↓ 选、回车确定或直接按 1-4。清单里有撤不回的事时不给默认选中。
   Esc 只关界面,请求留着。答完框就收起,不另报。
   界面外的提醒交互照原版、样式用 Numen 自己的 toast:她开始等主人时右上角弹一条"小蓝 请求你的同意,按 [Y] 答复"
   (键名取自按键绑定;同一只同伴换一条请求不再弹;原版 toast 占着右上角时排到它们下面);主人没答就撤了再弹一条为什么。
   有征询挂着时对话键先打开最早在等的那位的答复框,答完还有别的同伴在等就换到她。面板侧栏给在等的同伴头像标"!"。
4. `ConsentReplyPayload` 带同 id 回服务端,只认主人;`/numen consent <allow|remember> <id>`、
   `/numen consent deny <id> [附言]` 与它落到同一个入口 `ConsentDesk.reply`。附言只随拒绝(主人要她换个做法才会说)。
   允许:清单记成任务期授权,动作继续。拒绝:动作失败,`REFUSED` 的理由是主人那句原话或"主人拒绝";回执走既有的
   工具结果与 task_finished,不新增事件种类。
5. 超时由服务端按游戏刻算(两分钟),主人离线或到点按拒绝,理由写"主人不在场,无法征得同意"。
6. 悬着期间:同步工具(interact_at、drop_items)就是这次调用悬着,派发器的兜底超时在这具身体
   挂着征询时不走表;后台任务停在原地,任务期限不走表。模型不参与,没有 LLM 开销。

模型看到的只有普通的工具结果和回执。系统提示词加一段:有些动作要主人点头,身体自己会问,
被拒就别绕路。

## 七、记住的规则与存储

"允许并记住"存的作用域只在一处推(`Rule.remembering`,建征询清单时按裁决用的同一份事实推好,放在
`ConsentItem.remember`),卡片与命令答复走同一个入口写进主人层:

- 同一个动词;
- 对象:实体认那一只(`entity:<uuid>`);方块与物品认种类 id,并留着问出它的那一行的条件;
- 撤不回的信号这一次不成立、不读活世界时却按成立算的,取反钉上:卡上这一条没标撤不回,记下的规则就
  盖不到撤不回的情形。记下的这一行一定盖得住这次问的动作。

| 命中 | 存成 |
|---|---|
| `break(placed)` 挖了圆石 | `allow break(placed & minecraft:cobblestone)`:我放的圆石随便挖 |
| `attack(named)` 某只狼 | `allow attack(entity:<uuid>)`:这一只可以 |
| `break(block_entity)` 空箱子 | `allow break(block_entity & minecraft:chest & !contents)` |
| 主人写的 `ask break(!placed & !block_entity)` 挖了石头 | `allow break(!placed & !block_entity & minecraft:stone)` |

撤不回的也记得住——记不记由主人决定,卡片只负责把"撤不回"标出来。

**存储。** `PermissionStore`,每主人一份 SavedData,与他手下每只同伴的模式放在一起:deny、ask、allow
三张表,每行是规则原文,读档经 `Rule.parse`,写错的行记一条错误日志、不进表。规则层是不可变快照,改一次
换一份。任务期授权(§六:同一行规则问出来的同一种东西)不落盘,随任务结束消失。

**命令入口。** 主人专用,是卡片、面板与以后可点击按钮的底层接口;命令只调 `Permission`、`PermissionStore`、
`ConsentDesk` 的公开接口,不复制判断:

```
/numen permission mode <同伴名> [ask|bypass|observe]    不带模式参数时显示当前模式
/numen permission rules list                          主人层(带序号)与出厂层
/numen permission rules add <deny|ask|allow> <规则>    例: add ask take(*)
/numen permission rules remove <deny|ask|allow> <序号>
/numen permission rules reset                         清空主人层
/numen consent <allow|remember> <请求 id>
/numen consent deny <请求 id> [附言]
```

面板里看和改规则的那一页,和 Claude Code 的 `/permissions` 对话框一样列出每条规则和它从哪来,随交互统一
按主流 HITL 设计重做。

## 八、与路由的接口

路由这边只有两个预留位,现在都是空的:

- `TerrainBill.Break.consent`:每条挖掘条目为什么需要同意,由权限层填,规划器自己不判。
- `RouteSpec.Alter.ANY`:把需要同意的格子也算进路线,账单里单列;`goto route:rN` 选了这种
  路线,执行开始前发起征询。

`sacred` 不是权限,它是"别挖自己要站的那格",留在规划器。

## 九、收债清单

| 现在 | 去处 |
|---|---|
| `do_not_break` 标签及 `BlockHelper.shouldAvoidBreaking` | 删。变成默认规则表里几条可编辑的 ask 或 deny(`break(#minecraft:beds)` 等) |
| `NavSettings.blocksToAvoidBreaking` 与 `ToolSet.avoidanceMultiplier` | 删。被 `break(block_entity)` 覆盖 |
| `BuildCellRules` 的方块实体门 | 删。同一条规则覆盖 |
| `BuildCompanionTask.clear` 直接 `setBlock(AIR)` | 改走 `BlockDigger` |
| `InteractAtCompanionTask` 遮挡时"去 break 挡着的方块"的文案 | 删。改为报告挡着的方块要不要同意 |
| `MineCompanionTask` 只按种类选目标 | 选目标不看权限,按同一套定价挑,动手前把挖掘交给权限层(已落地) |
| 都不中即放行、熔断常量 | 删。都不中也问;日常动作写成出厂 allow 行;熔断改为卡片上的不可逆提示(已落地) |
| `FIND`/`mine` 的目标剪枝读带权限的挖掘成本 | 剪枝只问挖不挖得动(`plausibleToBreak` 读 `getUnpricedMiningDurationTicks`:FIND 按可改地形算,mine 按它这件活的规格,`avoid_break` 这类规格限制在这里作用到目标上),许不许挖是执行开始时的事;mine 挖不成的格在回执里交代(已落地) |

## 十、分步落地

1. 放置记录(mixin + SavedData,连同是谁放的;建造成果记在主人名下)、动作与裁决的最小接口。(已落地)
2. 信号与三张规则表、默认规则、`observe`/`bypass` 模式;`BlockDigger`、攻击、放置落点强制;
   删 §九 的旧判据。(已落地)
3. goto、build、interact、attack、drop、mine 接入执行开始时的裁决;goto 的候选探针无路时放宽
   一档,候选带同意标注;mine 按统一定价挑目标。(已落地)
4. 征询协议:登记处、网络载荷、答复框、toast 提醒、轮廓、超时;派发器豁免。(已落地)
5. 记住的规则与面板页。后端已落地:主人层三张表与分层查询、"允许并记住"推规则写进主人层、
   `/numen permission`、`/numen consent` 命令入口;右键方块、右键实体、从容器拿东西在动手前过裁决。
   答复交互已按 §六 第 3 条重做(答复框取代输入行);规则的面板页待做。
6. GameTest:不砍玩家放的原木、不拆有东西的箱子、允许后能拆、拒绝附言回到模型、observe 模式拒绝一切
   改动。已落地:挖掘落点被原生通道退回按 REFUSED"服务器没让挖掉这一格"收场;GameTest 覆盖记住、分层、
   命令答复、observe 拦开箱与拿东西、主人写 `ask take(*)`、破坏事件被取消。领地模组不在本层做,之后以
   联动插件做。

## 十一、宪法修订

- §七 加第五问:这个动作会不会改世界或伤到实体?会,就必须经过权限层的裁决,没有第二个入口。
- 新增一节"权限层",内容为本文 §四与 §六。
- §四"核心代码永远不直接调 emitEvent"不变:征询的答复走任务回执,不新增事件种类。

## 参考

- Claude Code 文档:`permissions`(三张表按序首个命中、规则语法、是/不再问/否与附言)、
  `permission-modes`(模式表、任何模式都不自动批的动作、受保护路径与关键路径熔断、分类器
  的四步顺序)、`auto-mode-config`(hard_deny / soft_deny / allow 三档,明确意图才解 soft
  block,环境由用户定义)。
- pi-mono `coding-agent/docs/security.md`、`examples/extensions/permission-gate.ts`、
  `docs/rpc.md` Extension UI Protocol(本机 `D:\01_Projects\pi-mono`)。
- #10 GlariaLuminous 的 Protection API 提案:`ProtectedAction`、`ProtectionRule`、服务端
  权威、在真实动作前检查。
- Baritone `isPossiblyProtected()` 返回 false 的桩与 issue #4643:按启发式识别玩家建筑这条路
  七年没走通。
