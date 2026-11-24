# 权限层:她能动什么,谁说了算

本文讲身体的任何动作在动手前怎么过权限。与 `pathing.md` 的分工:那份讲寻路怎么走;寻路只是权限层的调用方之一,攻击、开容器、
丢物品、执行指令是另外几个。决定的来历在 `adr/control/`(0020 问题与登记的门、0025 权限层只判不认、0026 "这是谁的"去问)。

心智模型一句话:**权限层只判,不认,也不记账。** 一样东西是哪一类、是谁的,它去问,谁知道谁回答;没人回答就照实当成没人认得、
没主人的。

## 一、模型:防火墙

规则照防火墙的五个要素写:

| 要素 | 这里是 | 说明 |
|---|---|---|
| 主体 | 同伴 | 缺省是主人名下的每一只;可点名某一只 |
| 动作 | `break`、`place`、`attack`、`use_block`、`use_entity`、`drop`、`command` | 七个动词。从容器里拿东西不是单独的一问,归右键打开它的那一下 `use_block`(实体是 `use_entity`):界面开了之后搬东西不再过权限层 |
| 对象 | 方块、实体、物品、指令根名,用对象组与属性组合 | 见 §二 |
| 判决 | `allow`、`ask`、`deny` | |
| 默认策略 | 哪条都不中:`ask` | |

再加防火墙的两样:

- **对象组。** 一个名字代表一类对象:`nature`、`owner`、`structure(#minecraft:village)`……规则只写名字,这个名字在一个对象上成不成立
  由登记它的一方答(§二.3)。
- **命中计数。** 每条规则记命中次数与最近一次命中的时间,规则列表与面板上显示,主人一眼看出哪条在起作用、哪条从没用过。改了规则计数清零。

**不照搬防火墙的一处:顺序。** 防火墙按书写顺序首条命中,一条宽的放行写在前面就让后面的禁止全失效。这里照 IAM:
**明确的禁止永远赢**,判定顺序与书写顺序无关(§四)。

一行规则:

```
<判决> <动作>(<对象条件>) [@<同伴名>]

deny  break(other)
ask   break(structure(#minecraft:village))
allow break(owner & minecraft:cobblestone)
deny  attack(owner | self) @小蓝
allow command(msg)
```

对象条件是对象组、属性、方块或物品 id、标签(`#minecraft:beds`)、某一只实体(`entity:<uuid>`)、`*` 的组合,
`&`、`|`、`!`、括号。全仓只在规则核心的解析器里解析(§八),写错了回教学式的错误,列出认得的动词、组与属性(自带的加此刻登记着的)。

## 二、对象

### 1. 归属:是谁的,去问

每个对象恰好属于四类之一:

| 组 | 是谁的 |
|---|---|
| `self` | 她自己的 |
| `owner` | 主人这一边的:主人本人的,与主人名下别的同伴的 |
| `other` | 别的玩家的,与别人名下同伴的 |
| `nature` | 没主人的:没有谁说它是谁的 |

**是谁的不由权限层认,也不由它记。** 它问一圈,回答者说事实:是某个人的(交回那个人的 UUID);不知道就不答。两个问题,
回答者经 `numen.answer` 登记(机制见 `adr/control/0020`):

```java
// 这只实体是谁的。服务端主线程上被叫。
numen.answer(Questions.ENTITY_OWNER, "my_golems", entity ->
        entity instanceof MyGolem golem ? golem.masterId() : null);

// 这一格方块是谁的。主线程上交回她身边一片的查表,之后任何线程逐格读这一份。
numen.answer(Questions.BLOCK_OWNER, "my_claims", (level, center, chunkRadius) -> {
    Map<ChunkPos, UUID> owners = MyClaims.copyAround(level, center, chunkRadius);
    return owners.isEmpty() ? null : pos -> owners.get(new ChunkPos(pos));
});
```

- **谁来答。** 实体由 Numen 答原版的驯服记录(回答者 `tame`:狼、猫、鹦鹉,以及继承原版驯服的模组宠物;记着的主人是一只活着的非玩家时
  不算有主)。**方块在原版里没有主人,Numen 不答**;知道哪些格子有主人的一方(领地模组的插件)登记回答者。还没有哪个插件登记。
- **没人回答,就是没主人的**(`nature`)。
- **换算归 Numen API。** 回答者只说"某个人";这个人对要动手的同伴算她自己、主人这一边还是别人,由权限层换算(`Whose`)。
  身体本身就是一个人的实体(玩家、同伴)而没人说它另有主人,按那个人算。
- **几个回答者说的不是同一个人,取最严的:** 别人 > 主人 > 自己。不排登记的先后。
- **一个回答者抛了异常,只丢它那一条**,记进日志,别的回答照常。问一圈并合起来只在一处(`Owners`):权限层、每轮请求里的"附近有谁"、
  `numen.scan` 里的 `owner` 问的都是它。
- **线程。** 权限层在两处问归属:真要动手前在服务端主线程;寻路给一条路定价时在搜索线程,那里碰不得活世界。所以方块的回答分两步:
  取裁决快照的那一刻(`Permission.gateFor`,与结构件范围同一处、同一个半径)在主线程上把她身边一片的归属抄成一份不可变的查表,
  之后哪条线程来问都只读这一份。主人名下此刻在场的别的同伴也在这一刻认好。没有任何方块回答者时那份查表是空的,逐格定价与
  `near(…)` 都不扫。

后果:纯原版、没装回答方块归属的插件时,每一格方块都是没主人的,写着 `owner`、`self`、`other` 的方块规矩碰不上;装了这样的插件,
同样的规矩自己生效。Numen 只出一套规矩,保护有多严看装了谁来回答。服务器上别的玩家放方块时 Numen 不做任何事,存档里也没有
"谁放的哪一格"这样的账。

### 2. 属性

归属之外的事实。按一条判据分两处:**不用认识任何具体东西的,Numen API 自带;说得出"这是哪一类东西"的,谁认得谁登记**(§二.3)。

| 属性 | 问题 | 谁的 |
|---|---|---|
| `structure`、`structure(<结构类型标签>)` | 这一格在不在某个生成结构件的范围里;带标签只认那一类,`structure(#minecraft:village)` | Numen API 自带 |
| `block_entity` | 这格有没有方块实体 | Numen API 自带 |
| `named` | 实体有没有自定义名字 | Numen API 自带 |
| `near(<组>)` | 放置点周围 3 格内有没有属于那一组的格,`near(owner)`、`near(other)`、`near(self)`;问的是方块归属那一份查表 | Numen API 自带 |
| `contents` | 容器里有没有东西(撤不回)。只有主线程读得到方块实体,搜索线程按"有"回答 | Numen 登记(原版) |
| `villager` | 是不是村民 | Numen 登记(原版) |
| `hostile` | 是不是敌对生物 | Numen 登记(原版) |
| `hazard_item` | 放的是不是岩浆、火、TNT、水 | Numen 登记(原版) |

`structure` 是自带的词,不交给谁登记:结构件范围随区块存档,读它是引擎的通用机制(和标签、方块实体同类,模组的结构也走同一张注册表),
只有这一个出处,没有第二个人答得了。它说的是格子在哪,和这一格是谁的各说各的:结构里的一格可以同时是某个人的。

种类 id、标签、`entity:<uuid>`、`*` 是规则的语法,不是词。

撤不回由词标:`contents`(任何动词下)、实体的 `owner`/`self`/`other` 命中 `attack` 时,征询卡把那一项标成"撤不回"。

### 3. 规则里的词是登记的

Numen API 留着动词、规则怎么写怎么比怎么排、征询协议;一个词在一个对象上成不成立,由认得那类东西的一方登记来答。
登记口是一个问题常量(`Questions.RULE_WORD`):

```java
numen.answer(Questions.RULE_WORD, "villager", new RuleWord(
        "is a villager",                                    // 命中时的英文自述,给模型与日志
        Component.translatable("numen.rule_word.villager"), // 同一句给主人看的,征询卡上显示,键在登记者自己的语言文件里
        Set.of(),                                           // 哪些动词下成立就算撤不回
        view -> view.action().entity() instanceof AbstractVillager));
```

回答者的名字就是规则里写的那个词;它拿到的和自带的词拿到的是同一个东西(`ActView`:`action()` 说做什么、对哪一格哪只实体哪件物品,
`facts()` 是读世界的口)。

| 谁 | 词 |
|---|---|
| Numen API 自带 | `self`、`owner`、`other`、`nature`、`structure`、`block_entity`、`named`、`near(<组>)` |
| Numen 登记(原版) | `contents`、`villager`、`hostile`、`hazard_item` |
| 模组的插件 | 各登记各认得的那一类;还没有哪个插件登记 |

规矩:

- 登记的词不带括号参数;带参数的只有自带的 `structure(...)` 与 `near(...)`。
- 名字照回答者名字的规矩(小写字母开头,小写字母、数字、下划线);重名、用了自带的名字,登记时就是错。
- **没人登记的词写进规则是解析错误**,和写错任何一个名字一样,错误里列出此刻认得的全部名字(自带的加登记着的)。出厂规则与硬规则的
  原文也照此刻认得的词解析:写着没人登记的词的那一行不成规则(记一条错误日志、不进表),登记的名字变了就重新解析。
- 规则解析之后登记撤回了(插件卸下):那个词在任何对象上都不成立,再解析写着它的规则是解析错误。
- **线程**:哪条线程在裁决就在哪条线程上被问。真要动手前的那一问在服务端主线程,读得到活世界;寻路给每一格定价时在搜索线程,只许读动作本身与
  冻结的方块快照,要活读才答得出的词这时按保守的一头回答(`contents` 按"有")。只陈述事实,不裁决,不改世界,不做慢事。
- 插件只加名词,不加动词,不改判定顺序。

规则认得的词只在一处合起来(`Groups.vocabulary`:自带的加登记着的):解析、教学式的错误、撤不回的判断、她读规则时拿到的词表
(`numen.permission.rules().words`)读的都是它。

**同一个道理也用在"这一下右键会做什么"上**(`Questions.CLICK`,回答者 `ClickActions`):鼠标右键按在一格方块上之前,两只手里的东西各问一圈
"拿着这一叠按在这一面上会对世界做哪几件事",回答者交回动作(今天都是一次 `place`),Numen API 把各人说的合起来、再加上它自己的那一问
`use_block`,逐件交给裁决。原版的四种由 Numen 登记:方块物品贴着命中面放进可替换的格、桶、打火石、火焰弹;模组的放置类物品由它的插件登记;
没人认得的物品,这一下只问 `use_block`。回答者在服务端主线程上被叫,世界是这具身体所在的活世界;寻路规划不问这一圈(搜索线程给垫路定价时
直接提出放置动作)。

## 三、谁能改规则

| 谁 | 能做什么 |
|---|---|
| 服主 | 硬规则的开关(§五),服务器设置 |
| 主人 | 自己那一层随便改:加、删、放宽、收紧;选模式 |
| 她(模型) | **只能收紧**:往主人层加 `deny`、`ask` 行,加完照实告诉主人。**放宽一律经主人**:她要加 `allow` 行、删任何一行,发一张征询卡,主人答应才写进去 |

收紧不会出事:禁止永远赢、`ask` 行排在同层 `allow` 之后(§四),她加的行只可能让更多动作被拦、被问。放宽要过主人,
否则就是她自己给自己发许可。她加 `allow`、删行的征询卡是命令项:卡上写成她没法自己跑的那条命令
(`/numen permission rules add allow …`、`/numen permission rules remove <表> <序号>`),主人看得懂也能自己敲;不提供"允许并记住"。

她加的行记着来源("她加的,因为主人说别动箱子"),规则列表里一眼看得出,主人删起来和删自己的一样。

主人说"别动我的箱子",她就加 `deny break(owner & #c:chests)`;主人说"农场随便收",她不能自己加 `allow`,要发卡。

## 四、判定顺序

一个动作进来,依次:

1. **模式**:`observe` 拒一切改世界的动作;`bypass` 放行一切——硬规则除外。
2. **硬规则**:命中即拒,模式、主人层都解不开。
3. **任何一层的 `deny`**:命中即拒。
4. **主人层 `allow`**,再 **主人层 `ask`**。
5. **出厂层 `allow`**,再 **出厂层 `ask`**。
6. 都不中:`ask`。

同层 `allow` 先于 `ask`,是为了"允许并记住"抠出来的细 `allow` 能盖过它出自的那条宽 `ask`。书写顺序不影响结果。

任务期授权(主人在卡上点了"允许")只把这一次任务里同一行规则问出来的同一种东西变成放行,解不开任何拒绝。

## 五、出厂规则

**硬规则**:主人答应不了的事。服主可在服务器设置里关(单机就是你自己)。

```
deny break(other)
deny place(other)
deny use_block(other & contents)
deny attack(other)
deny use_entity(other)
deny place(hazard_item & near(other))
```

**出厂层:**

| 判决 | 规则 |
|---|---|
| allow | `break(nature & !block_entity & !structure(#minecraft:village))`、`break(self & !contents)`、`place(!hazard_item)`、`place(hazard_item & !near(owner) & !near(other))`、`attack(hostile)`、`attack(nature & !named & !villager)`、`use_block(!contents)`、`use_block(contents & !other & !structure(#minecraft:village))`、`use_entity(nature)`、`use_entity(self)`、`command(help)`、`command(list)`、`command(me)`、`command(msg)`、`command(teammsg)`、`command(seed)`、`command(random)` |
| ask | `break(owner)`、`break(structure(#minecraft:village))`、`break(block_entity)`、`break(self & contents)`、`attack(owner)`、`attack(self)`、`attack(named)`、`attack(villager)`、`use_entity(owner)`、`drop(*)`、`place(hazard_item & near(owner))` |
| deny | 空 |

读法:

- 没主人的随便挖、随便打;她自己的随便拆(装着东西的除外);主人的、村子里的、带方块实体的要问;别人的碰不得。
- 纯原版里没人回答方块归属:普通方块直接挖、直接放;村庄结构件范围里的要问;带方块实体的(箱子、熔炉、地牢的刷怪笼与战利品箱、
  蜂巢、可疑的沙子)要问;装着东西的容器除了村子里的都打得开。写着 `owner`、`self`、`other` 的方块规矩等回答者。
- 同层 `allow` 先于 `ask`,所以 `allow` 行写得比 `ask` 行窄:带方块实体的与村子里的不进拆没主人的方块那一行,落到
  `break(block_entity)`、`break(structure(#minecraft:village))` 去问。
- 右键一格里面没装东西的方块放行(`use_block(!contents)`):开门、按按钮、睡床、开空容器不动别人的东西。
- 右键一格装着东西的容器,问的就是"能不能动里面的东西"——打开之后往里放、往外拿都不再另问。不是别人的、不在村子里的放行:她的设计
  就是用主人的工作台熔炉箱子,主人想管就加 `ask use_block(contents & owner)`。村子里的没有一行说到,落到默认去问。别人的是硬规则。
- `contents` 认的是"方块实体是个不空的容器,或是放着书的讲台"(讲台不是容器,书靠它界面上的钮取下)。营火上的肉这类不是这样存的,不算。

## 六、动作分类

| 档 | 动作 |
|---|---|
| 从不问 | 看(`scan`、`status`、`inv` 的查询、`build.diff`)、合成烧炼、吃、装备、换手——不改世界,不是权限层的动作 |
| 出厂放行 | 挖没主人的、拆自己的、放普通方块、打怪与野生动物、右键没装东西的方块、右键装着东西的容器(别人的与村子里的除外,连同往外拿)、只读只说话的指令 |
| 问 | 挖主人的、挖村子里的、挖带方块实体的、打有主或有名字的、打村民、丢东西、在主人的东西旁放危险物,以及没有任何一行规则说到的(右键村子里装着东西的容器就在这里) |
| 拒 | 别人的方块、实体与装着东西的容器(硬规则)、任何一层的 `deny`、`observe` 模式 |

挖不动(基岩)、岩浆与危险格、摔落上限回答的是"做不做得到、会不会伤到自己",是物理,不经裁决、不弹卡,留在寻路与身体。

## 七、检查时机与征询

**三个时机。**

| 时机 | 做什么 |
|---|---|
| 规划 | 寻路的代价只读裁决快照(端口 `TerrainPolicy`,Numen 以 `Gate` 实现):放行按原价,要问的格加价(默认 10 倍),拒绝是墙。只裁决挖与放,不裁决右键 |
| 执行开始 | 一件成批的活(建造施工前、攻击开打前)把动作整批裁决,要问的合成一张卡 |
| 每次动手 | 她的鼠标 `Mouse` 在每一下之前问;走路时走到要问的那一格跟前停下发卡。答应了接着走;拒了那一格在这一趟里当墙、重新规划绕开,绕不开才以 `denied` 收场 |

走路不分墙和地形:挡路的照价钱挖,能不能动哪一格只归上面这三个时机里的裁决。

**各内容的接入点。** 内容只提出动作,判与问都归权限层。

| 内容 | 什么时候送动作 |
|---|---|
| `move.walk`、`move.fly`、`move.glide`、`move.away`、`move.follow` | 规划时只读裁决定价;走到要改的格子那一刻由鼠标问 |
| `work.dig` | 挖给它的每一格,动手前问;够不着先走过去,路上要改的格同上 |
| `build.place` | 施工前把要清的与要放的整批裁决,一张卡;拒绝的格按"主人不让动"跳过并写进回执 |
| `fight` | 开打前送目标,一张卡,等答复期间不打它;自卫换目标时新冒出来的再送 |
| `use.block`、`use.entity`、`use.hit`、`use.item` | 按下去之前送准星落到的动作:右键落在方块上,先是手里的东西会做的事(问一圈 `ClickActions`),再是 `use_block` |
| 往容器里放、从容器里取(`container.put`、`container.take`、`furnace.take`、`lectern.take` 等) | 右键打开它的那一下送 `use_block`(或 `use_entity`);界面开了之后搬东西不再送任何动作 |
| `inv.drop` | 每次送 `drop` |
| `numen.mc.run` | 解析通了才送 `command(根名)`;服务器不让她用的当场失败,不送 |
| 她改规则 | 加 `deny`、`ask` 直接写入并回执;加 `allow` 或删行发卡 |

**征询协议:**

1. 服务端登记处 `ConsentDesk` 挂在同伴身上,一只同伴同时挂一条请求:id、清单(动词、坐标或实体、命中的规则与理由、名字)、到期游戏刻。
   清单真的变了才由新的顶掉旧的,旧的按拒绝收尾。
2. 清单按"动作 + 对象 + 理由"归堆发主人客户端。理由是命中的那一行规则里成立的词各自给主人看的那一句:"你的""生成结构里的""功能方块";
   撤不回的整行警示。世界里给涉及的方块和实体描轮廓,请求结束就撤。
3. 答复框取代这只同伴的输入行:**允许**(本任务)、**以后都允许**(另写一行 `allow` 进主人层)、**拒绝**、拒绝并附一句话。
   有撤不回的项时不给默认选中。右上角 toast 提醒按键答复。
4. 卡片与 `/numen consent` 命令落到同一个入口 `ConsentDesk.reply`,只认主人。拒绝的附言原话交给模型。
5. 两分钟没答、主人离线,按拒绝,理由"主人不在场,无法征得同意"。
6. 悬着期间不占模型,没有 LLM 开销;同步动作这次调用悬着,后台任务停在原地,期限不走表。

**允许的作用范围。** 本任务内,同一行规则问出来的同一种方块(或同一只实体)都算已授权,不再重复问——穿过主人的一堵墙只弹一张卡。
任务收尾即清。

## 八、模块

拆成两层,按组合接起来:

| 层 | 放什么 | 在哪 |
|---|---|---|
| **规则核心**,纯 JVM,不碰游戏类 | 规则语法的解析与打印;对象条件的语法树(`&`、`|`、`!`、括号);判决与判定顺序(§四);层与硬规则;主体匹配;命中计数;谁能改什么(§三);"允许并记住"从一次命中推出一行 `allow`;对象组的登记口——核心只知道组名与"问提供者属不属于" | 顶层模块 `permission/`,与 `ai/`、`ui/` 同类,单测几毫秒跑完 |
| **游戏适配** | 把游戏里的一下动作翻成核心的"主体 + 动作 + 对象";问归属的那一圈与换算(`Owners`、`Whose`);自带的词(`Groups`)与结构件范围(`Structures`);名字校验(id、标签是否存在);裁决快照 `Gate`;征询登记处与网络载荷;存档;命令 | `numen-api` 的 `com.dwinovo.numen.api.permission` |
| **原版的回答** | 规则里原版的词(`VanillaWords`)、驯服记录的回答者(`VanillaOwners`)、右键会做什么(`VanillaClicks`) | `numen`,经 `numen.answer` 登记,和插件同一个写法 |

- 寻路不依赖权限层,经 `TerrainPolicy` 端口问。
- 插件经 `numen-api` 登记回答者(§二),不碰核心。
- 走服务端插件(Paper 一类没有加载器的平台)时,核心原样带走,只重写适配层。

## 九、存储、命令与面板

**存储。** `PermissionStore`,每主人一份存档:主人层规则(每行原文、来源是主人还是她、命中计数、最近命中时间)与名下每只同伴的模式。
任务期授权不落盘。

- **命中账在主人的存档里。** 硬规则、出厂层、主人层三处的账都记在这位主人的 `PermissionStore`,按"层名 | 表 | 规则原文"认。
  规则本身随模组发、不进存档,账只能跟着主人走。只有真动手前的那一问(`Gate.judgeLive`)记账,规划时逐格定价的 `judge` 不记;
  模式、任务期授权、没有规则覆盖定下的答复不是哪一行规则定的,也不记。
- **读不通的行不进表。** 读档经规则核心解析;写错的行、写着此刻没人认得的词或动词的行(更早的存档里的 `unknown`、`take(…)` 属于这一种)
  记一条错误日志(`the permission save has unreadable content`)、不生效,别的行照读;文件不因此重写,那几行到这份存档下一次落盘时才没有。
- **更早的名字读档时改写。** `placed`、`self_placed`、`owned`、`self_owned`、`near_placed` 改写成现在的归属组并记日志
  (`RuleMigration`);改写后的行经规则核心规范打印,所以 `!placed` 成了 `!(owner | other)`。
- **服主的开关在服务器设置 `config/numen/server/server.json`。** `hardRules`(默认开)。文件不存在时写出一份默认的;读不懂的文件记日志、取默认。

**"允许并记住"推出的规则**:同一个动词;对象认那一只实体(`entity:<uuid>`)或那一种方块、物品、指令根名,
并留着问出它的那一行的条件;撤不回的属性这一次不成立的,取反钉上——卡上没标撤不回,记下的规则就盖不到撤不回的情形。

| 命中 | 存成 |
|---|---|
| `break(owner)` 挖了圆石 | `allow break(owner & minecraft:cobblestone)` |
| `break(block_entity)` 挖了空箱子 | `allow break(block_entity & minecraft:chest & !contents)` |
| `attack(named)` 某只狼 | `allow attack(entity:<uuid>)` |
| 没有规则说到的 `setblock` | `allow command(setblock)` |

**命令**(主人专用,只调公开接口,不复制判断):

```
/numen permission mode <同伴名> [ask|bypass|observe]
/numen permission rules list                    硬规则、主人层(带序号、来源、命中次数)、出厂层
/numen permission rules add <deny|ask|allow> <规则>
/numen permission rules remove <序号>
/numen permission rules reset                   清空主人层
/numen consent <allow|remember> <请求 id>
/numen consent deny <请求 id> [附言]
```

她读规则用 `numen.permission.rules()`:三层规则,连同此刻规则里能写的词。

**面板的"权限"页。**

- 读写走库协议的一种 `permission`,不另开包。整表由 `RuleBook`(命令 `rules list` 与 `numen.permission.rules()` 读的同一份)编成 JSON:
  服主的硬规则开没开、主人名下每只同伴的模式、三层规则(每层 deny、ask、allow 次序,带来源、理由、命中账)。
  写有四种:加一行(`put`)、按规则原文删一行(`delete`,不按序号:她可能在两次读之间加过行)、清空主人层(`reset`)、设一只同伴的模式(`mode`),
  调的都是 `PermissionStore` 的公开方法,规则对不对仍只由规则核心的解析判,写错的答复带着错误码,界面用命令那一套 `numen.command.rule_mistake.<码>` 翻。
- 只有主人读写自己那一份:协议入口只认发请求的玩家,不看是不是管理员;模式只能设在他名下的同伴身上。硬规则的开关是服主的服务器设置,
  页面只报告它开没开,不提供开关。
- 规则有变就推:存档任何一处变(她加了行、命令改了、命中账涨了)记下是谁的,每 10 个服务端刻合并成一包推给在线、装着客户端的主人
  (`PermissionFeed`)。挖矿时每一格都命中一次,半秒的窗口既让计数看着是活的,也不把整张表每刻发一遍。
- 主人层的行只有删,没有改——改一条规则就是另一条规则(命中账从零数起),删了再加。删在右键菜单里,和别的库页面一致。

## 还没有的

- **回答方块归属的插件**(领地模组)还没有写。
- **标记**:给实体、区域起名字,主人标的区域同时是方块归属的一个回答者——在讨论,见 `adr/control/0026`。

## 参考

- Claude Code 文档 `permissions`、`permission-modes`、`auto-mode-config`:三张表、规则语法、是/以后都允许/否与附言、`hard_deny`。
- AWS IAM 策略求值:明确拒绝优先,与书写顺序无关。
- 防火墙规则模型:主体、动作、对象、判决、默认策略、对象组、命中计数。
- pi-mono `coding-agent/docs/security.md`、`examples/extensions/permission-gate.ts`:权限门独立一层、只有一个入口。
- Common Protection API:保护类模组登记成提供者,内容模组动手前问;问题是固定的几个,没有"从容器里拿"。
- #10 GlariaLuminous 的 Protection API 提案:`ProtectedAction`、`ProtectionRule`、服务端权威、在真实动作前检查。
- 原版 `StructureManager`:结构件范围随区块存档。
