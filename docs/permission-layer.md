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
2. **信号通用,不按种类枚举。** 玩家放置、带方块实体、有主人、有名字、是村民、领地裁决,
   六个信号覆盖原版和任何模组。高级工作台有方块实体,模组宠物继承原版驯服,都不用适配。
3. **主人定义环境,引擎不猜。** 同伴的沙盒是自然世界加她自己的背包;出了沙盒是主人的和别人
   的。哪些东西"随便动"由主人写规则,不由启发式判"像不像建筑"(Baritone 七年没做成的事)。
4. **模型永远不是权限的执行者。** 它没有任何写入口;它看到的只是普通的工具结果和回执。
5. **一个入口。** 全仓凡会改世界的地方只问一个裁决函数;判据只有一个出处。

## 四、概念

放在 api,它是机器;core 的任务与工具是往里送动作的内容。五个零件,与 #10 提案的
`ProtectedAction` / `ProtectionRule` 同形。

**动作(Action)。** 身体要对世界做的一件具体的事及其目标:`break(pos)`、`place(pos, block)`、
`attack(entity)`、`use_block(pos)`、`use_entity(entity)`、`take(container, item)`、`drop(item)`。
不带工具名、不带 JSON。

**信号(Signal)。** 给动作贴事实的函数,每个只回答一个通用问题:

| 信号 | 问题 | 来源 |
|---|---|---|
| placed | 这格是不是玩家放的 | `BlockItem.place` 返回处的 mixin,放的人不是同伴就按区块记进每维度一份 SavedData;查询时格子已是空气视为无记号;同伴自己垫路的不记,build 完工把成果格登记 |
| block_entity | 这格有没有方块实体 | 世界 |
| contents | 容器里有没有东西 | 世界 |
| owned | 这只实体有没有主人 | `OwnableEntity` |
| named | 有没有自定义名字 | 实体 |
| villager | 是不是村民 | 实体类型 |
| hostile | 是不是敌对 | 实体分类 |
| hazard_item | 放的是不是岩浆、火、TNT、水 | 物品 |
| near_placed | 放置点附近有没有玩家放的方块 | placed 的邻域查询 |
| claimed | 领地 mod 说不说不 | loader 模块各一个实现:Fabric 接 Common Protection API;NeoForge 由挖掘走 `ServerPlayerGameMode` 触发 BreakEvent 被领地 mod 拦,回执如实报 |

插件可登记新信号,出厂这几个已够。

**规则(Rule)。** deny、ask、allow 三张表。查的顺序 deny → 熔断 → allow → ask → 都不中即
放行,第一个命中即定;allow 排在 ask 前面,因为"允许并记住"(§七)存的是从某条 ask 行里抠出来
的一条更细的 allow 行,ask 若先查,记住的规则永远轮不到。一条规则一行字符串
`动作(信号 & 信号 & !信号)`,与 Claude Code 的 `Tool(specifier)` 同形:

```
break(placed)          攻击/挖掘/放置四个动词 × 信号
attack(owned)
place(hazard_item & near_placed)
break(#minecraft:beds) 也接受方块标签与 id,给主人写细规则用
```

出厂默认表:

| 表 | 规则 |
|---|---|
| deny | 空(领地裁决不是规则,是命中即 deny 的信号) |
| ask | `break(placed)`、`break(block_entity)`、`break(#minecraft:beds)`、`break(#minecraft:doors)`、`break(#minecraft:trapdoors)`、`break(#minecraft:fence_gates)`、`attack(owned)`、`attack(named)`、`attack(villager)`、`drop(*)`、`place(hazard_item & near_placed)` |
| allow | 其余:自然方块、野生动物、敌对生物、自己的背包、开关门、开容器、从容器拿东西 |

从主人的容器拿东西默认放行:她的设计就是用主人的工作台熔炉箱子,相当于 Claude Code 读项目
文件;主人想管就加一条 `take(placed)` 的 ask。

**熔断(Circuit breaker)。** 两条永远问,"允许并记住"也盖不住,对应 Claude Code 的关键路径:
`attack(owned)`、`break(block_entity & contents)`。它们撤不回,模型犯一次错的代价太大。

**裁决(Verdict)。** 唯一入口,收一个或一批动作,回答三种之一:放行;拒绝并附理由;需要主人
同意并附一份清单。

**模式(Mode)。** 每个同伴一个,主人在面板设:`ask`(默认,走规则表)、`bypass`(全放行,
单机不想被打扰的人用)、`observe`(只看不动,拒绝一切改世界的动作,等于 plan mode)。

**征询(Consent)。** 裁决说需要同意时发起,见 §六。

## 五、动作分类

| 档 | 动作 | 对应 Claude Code |
|---|---|---|
| 从不问 | scan、look_around、inspect、status、lookup_recipe、plan_route | Read、Grep、Glob |
| 默认放行 | 挖自然方块、砍野树、用自己的方块搭路盖房、合成烧炼、吃、装备、换工具、打敌对生物、宰野生动物、开关门与栅栏门、开容器、拿东西、睡自己放的床 | 工作目录内的编辑 |
| 问 | 挖玩家放的、挖带方块实体的、打有主人或有名字的、打村民、丢物品、在别人的东西旁放危险物 | `rm -rf`、`git push`、网络 |
| 硬拒 | 领地 mod 拒绝的、挖不动的、出生点保护 | deny 规则、受保护路径 |

走路默认不改地形是规划层的最小权限,不在本表里;它决定的是"要不要挖",本表决定的是
"这一格能不能挖"。

## 六、检查时机与征询协议

**三个时机,各管一事。**

| 时机 | 做什么 |
|---|---|
| 规划 | 只读规则表,把需要同意的格子算进账单(`TerrainBill.Break.consent`);默认搜索里这些格无穷大,有别的路就不走这条;规格 `alter=any` 时按有限代价算进路线、账单里单列 |
| 执行开始 | 整条路线或整个动作的账单过一次规则表,需要同意就发起一次征询;不是走到墙边才问 |
| 每次动作 | `BlockDigger`、攻击落点、放置落点强制,不发起征询;到这里还没授权就当动作失败,任务按既有机制重算或收尾 |

**各内容的接入点。**

| 内容 | 什么时候送动作 |
|---|---|
| mine | 最小权限式:还有不用问就能挖的候选时先挖那些,ask 的跳过并在回执里报;能挖的都挖完了或一开始就只剩 ask 的候选,才发起一次征询("附近只剩你放的 6 块橡木,要挖吗"),允许就挖、拒绝就以回执收尾。deny 的永远剔除 |
| goto、follow | 规划出路以后、开走以前,整条路的挖放格子打包送一次;重规划再送,任务期授权覆盖就不重复问 |
| build | 清场格送一遍(改走 `BlockDigger`,不再直接 `setBlock`);放置格送 `place` |
| attack | 开打前送目标;自卫本能换目标时再送 |
| interact_at、容器 | 左键走挖掘落点;右键 `use_block`;拿东西 `take` |
| drop_items | 每次送 `drop` |

**征询是一个请求响应子协议**,与 pi 的 RPC 扩展 UI 同形:

1. 服务端记一条请求:id、同伴、动作清单(每条带信号和坐标或实体)、发起者写的原因。
   同一同伴同时只挂一条,新的顶掉旧的。
2. 发主人客户端。聊天面板与头像 HUD 出卡:同伴名、清单、原因、三个按钮、一个可选输入框;
   世界里给涉及的方块和实体描轮廓。卡是非模态的,主人在玩游戏。
3. 三个按钮:**允许**只对这次规划或这次动作有效;**允许并记住**按动作的信号追加一条 allow
   规则(见 §七);**拒绝**可附一句话。
4. 答复带同 id 回服务端。允许:任务期授权写入,动作继续或任务重算。拒绝:动作失败,理由是
   主人那句原话或"主人拒绝";任务按既有失败路径收尾,回执进模型收件箱。
5. 超时由服务端算,主人离线或到点按拒绝,理由写"主人不在场,无法征得同意"。
6. 悬着期间:同步工具就是这次调用悬着(派发器的兜底超时对它豁免);后台任务停在原地。
   模型不参与,没有 LLM 开销。

模型看到的只有普通的工具结果和回执。系统提示词加一段:有些动作要主人点头,被拒就别绕路。

## 七、记住的规则与存储

"允许并记住"存的作用域从命中的信号推:

| 命中 | 存成 |
|---|---|
| `break(placed)` 挖了圆石 | `allow break(placed & minecraft:cobblestone)`:我放的圆石随便挖 |
| `attack(named)` 某只狼 | `allow attack(entity:<uuid>)`:这一只可以 |
| `break(block_entity)` 空箱子 | `allow break(block_entity & minecraft:chest & !contents)` |

熔断项不会被记住。规则按主人存在服务端存档(每主人一份 SavedData),面板一页看和改,
和 Claude Code 的 `/permissions` 对话框一样列出每条规则和它从哪来。任务期授权不落盘,
随任务结束消失。

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
| `MineCompanionTask` 只按种类选目标 | 选目标前过裁决 |

## 十、分步落地

1. 放置记录(mixin + SavedData)、动作与裁决的最小接口、`mine` 选目标剔除。这一步就收掉
   "砍家里柱子"。
2. 信号与三张规则表、默认规则、`observe`/`bypass` 模式;`BlockDigger`、攻击、放置落点强制;
   删 §九 的旧判据。此步之后"挖主人的墙会被拒"真机可见。
3. goto、build、interact、attack、drop 接入执行开始时的批量裁决;goto 的候选探针在 natural 无路时再用 any 探一次,候选带同意标注;mine 的两段式征询。
4. 征询协议:网络载荷、卡片、HUD 轮廓、超时;派发器豁免。
5. 记住的规则与面板页;熔断。
6. 领地 mod 信号(Fabric CPA);GameTest:不砍玩家放的原木、不拆有东西的箱子、允许后能拆、
   拒绝附言回到模型、observe 模式拒绝一切改动。

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
- Common Protection API(Patbox):`canBreakBlock/canPlaceBlock/canInteractBlock/
  canDamageEntity(Level, pos/entity, NameAndId, @Nullable Player)`。
- Baritone `isPossiblyProtected()` 返回 false 的桩与 issue #4643:按启发式识别玩家建筑这条路
  七年没走通。
