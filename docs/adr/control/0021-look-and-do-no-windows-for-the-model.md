# 0021 她面前只有看和做:界面只当内部引擎,"这是什么"由回答者说

- 状态:已接受,在落地(看、做已落地;拿掉界面未落地,见文末)
- 日期:2026-11-23
- 另见:[0020](0020-questions-answered-by-whoever-knows.md)(问问题的机制;本条取代它的决定 5)、0005(一条命令只做一件事)、
  0008(组合交给出厂 Lua 库)、body 0003(她缺的那半个客户端)、issue #167

## 背景

0020 定了"Numen 不认东西,改成问问题",并落地了第一个问题:一次动作前后变了什么。它的决定 5 打算照样再开一个登记口:
`numen.gui.view` 问一圈"谁认得开着的这个界面"。动手前卡在类型上(读出来的 `Screen` 是封死的)。往下聊,发现卡的不是类型,
是"界面"这个概念本身该不该给模型。用户的原话:

> 界面这个概念肯定会在一定程度上让模型的能力下降,因为这很不符合直觉,他又看不到界面,理论上应该直接操纵,我们这边弄API给他操纵,
> 这样子给他一个看不见的界面说实话真的很抽象吧感觉?

> 我不是很想留界面这种扣子给模型说实话。

> 我们这个界面当初也是想的是想任何客户端一样,打开,操作,就这个逻辑通吃,但显然没办法通吃,还是需要适配,
> 那么我们的思想必须从继承改变为组合啊?

当时代码里的样子:

1. **给模型的"界面"是一组十二个函数**(`numen.gui.view`、`put`、`take`、`move`、`quick`、`choose`、`rename`、`page`、`toggle`、
   `open`、`close`、`count`)和一个 `Window` 对象。附一把剑的魔要这样写:

   ```lua
   numen.use.block(table)              -- 打开一个她看不见的东西
   numen.gui.put("diamond_sword")
   numen.gui.put("lapis_lazuli", 3)
   local s = numen.gui.view().screen   -- 读它
   numen.gui.choose(2)                 -- 按编号选
   numen.gui.take("diamond_sword")
   numen.gui.close()
   ```

2. **"打开、操作"并不通吃。** `choose`、`rename`、`page`、`toggle` 只认原版八种界面;模组界面上的按钮,没有任何函数按得到。
   通吃的只有"按格子搬东西"。所以这套做法还是在认类:认得的八种有读数、有选项,认不得的只剩格子。
3. **看一个方块分成了两个函数,里面还有一处认类。** `numen.scan.block` 给方块本身(id、状态、硬度),其中专门为告示牌留了一个字段
   (`instanceof SignBlockEntity`);`numen.scan.container` 给"里面装着什么",它的名字本身就在问"这是不是容器"。再来一种就得再加一个。
4. **按目标做事的函数早就有,而且是她常用的。** `numen.inv.store(箱子, 物品, 数量)`、`fetch`、`smelt`、`make`:一行说清对谁做什么,
   开界面、关界面在函数里面,她看不见。

## 主流怎么做

- **mineflayer**(外挂端的事实标准):每种特殊界面是自己的一组动词,不是一套通用的"读界面、按编号选"。熔炉是 `putInput`、`putFuel`、
  `takeOutput` 加 `fuel`、`progress` 两个数;附魔台是 `enchant(choice)` 加 `enchantments` 三项;铁砧是 `combine(item[, name])`;
  村民是 `villager.trades` 与 `bot.trade(villager, index, times)`;普通容器是 `deposit`、`withdraw`。任意界面按钮没有专门的函数,
  只有按格子的 `clickWindow`。
- **Voyager、Mindcraft、VoxelMind**(给大模型用的三个框架):模型面前都没有界面,拿到的是 `craftItem`、`smeltItem`、
  `putInChest`、`takeFromChest`、`tradeWithVillager` 这一类。三家的 smelt 都是一个会等的函数(放料、等、取);VoxelMind 另有
  `collect_furnace` 给提前走开的情况。
- **主流没有的**:酿造台、锻造台、砂轮、切石机、织布机、信标、讲台、制图台、合成器。mineflayer 里只能按格子点,三个框架不支持。
  这几种在本条里照熔炉、附魔台的样子往外推,没有现成的可照。
- **Jade、The One Probe**(0020 已写):看一个方块,各模组登记的提供者各往同一处添几行。

## 决定

心智模型一句话:**她面前只有两件事,看和做。两处都是问大家:看的时候问"这是什么",做完问"变了什么"。**

用户的复述:

> 我们这边不再让他去别扭的操控界面了,之前的gui.view会被这种问答机制代替,就是回答这个东西是干嘛的,然后这个问答就是直接提出来,
> 然后谁能回答都回答一下就行,这属于看,那么做的话变化不太大,就是相当于给那些操作注册API就好。

| 她调用 | Numen 做什么 | 谁答 |
|---|---|---|
| `numen.scan.block(目标)` | 固定的自己算,再问一圈"这是什么" | 原版、各插件,各添几行 |
| `numen.use.block(目标)`、`numen.inv.store(…)` 等 | 干活,干完问一圈"变了什么"(0020) | 原版、各插件,各添几行 |

1. **看:只有一个 `numen.scan.block`。** 方块本身的事(id、全部状态、硬度、挖多久、够不够得着)每个方块都有,Numen 自己算,不问人。
   "这是什么、里面有什么、能对它做什么"是一个新的问题常量,登记还是 `numen.answer`,问的那个循环在 `numen-api`。
   没人答,她拿到的就只有固定的那些。

   ```java
   // 原版登记:告示牌上的字
   numen.answer(Questions.DESCRIBE, (her, target) -> 是告示牌 ? List.of("front: " + 字) : List.of());
   // 模组插件登记:它自己懂的
   numen.answer(Questions.DESCRIBE, (her, target) -> !Mek.isSmelter(target) ? List.of()
           : List.of("Mekanism energized smelter, running", "mek.side(pos, face, mode) sets a side"));
   ```
2. **`numen.scan.container` 并进 `numen.scan.block`,告示牌那个字段也搬走。** 存着的物品、流体、能量和告示牌的字,都成了原版登记的回答者。
3. **做:一个个函数,一行说清对谁做什么;界面只在函数里面开和关。** 这部分不要新机制:原版的由 `numen` 提供,模组独有的事由插件经门
   登记自己的函数(`numen.api`,已有)。返回值里"变了什么"照 0020。
4. **一种特殊方块一组自己的函数,不是一个通用函数靠参数分辨。** 用户的原话:

   > 我其实倾向于一个特殊方块一个操作集……总是用Inv然后又得靠输入参数来分辨有点奇怪。

   熔炉、附魔台、铁砧、村民各有自己的一组(mineflayer 也是这样分的),目标写在参数里,函数之间不留"开着哪个界面"的状态:

   ```lua
   numen.furnace.input(pos, "minecraft:raw_iron", 8)
   numen.furnace.fuel(pos, "minecraft:oak_log", 2)    -- 说得清"当燃料";按 Shift 点的规矩,原木会进原料格
   numen.furnace.take(pos)
   ```

   (名字只是示意。)普通的格子容器(箱子、木桶、没人适配的模组机器)留一组通用的放与取。原版和模组长得一样:模组插件登记的也是
   自己的一组;她该用哪一组,由"看"的回答说。
5. **原子函数不替她等,等的那一行留在出厂的 Lua 库里。** 放料、放燃料、取产物各是一步,做完就返回;等多久、站着等还是走开,
   是她的事(`numen.time.wait`、`numen.time.wait_until`、`numen.task.timer`)。`numen.inv.smelt` 照旧是一行烧完的 Lua 库(主流给模型的
   也是一个会等的 smelt),只是改成拼这三步:反复取产物,一阵子没有新的就收工(Mindcraft 的等法),不用读界面上的数。
6. **界面不再是给模型的东西。** `numen.gui.*` 与 `Window` 从她面前拿掉;开界面、点格子留作实现这些函数的内部引擎。
7. **取代 0020 的决定 5:不开"界面种类"登记口。** 原版认得的八种界面(附魔台、切石机、织布机、交易、信标、铁砧、讲台、合成器)和现在只按
   格子用的那几种(熔炉、酿造台等),都照第 4 条各成一组。
8. **分工不变**(0020 决定 2):问题常量、问的循环、登记的门在 `numen-api`;她调的函数和原版的回答在 `numen`,原版从同一扇门登记。

## 后果

- 给一台模组机器加"她看得懂"= 插件登记一个回答者;加"她会用"= 插件登记自己的函数。两样都不改 `numen` 与 `numen-api`。
- 原版少两处认类(告示牌字段、`scan.container`),少一张固定的界面表。
- 没人适配、又非按按钮不可的模组机器,她用不了。今天也用不了:没有按任意按钮的函数。能存、能取、能看存量的那一半照旧通吃。
- 她能自己拼的最小一块,从"点一格"变成"对一个目标做一件事"。出厂的 Lua 库(0008)照旧是她看得见、改得了的,只是拼的东西换了。
- 函数的组变多了(一种特殊方块一组)。她不用全记着:帮助本来就是按组查的,该用哪一组由"看"的回答告诉她。
- 给模型看的函数与说明会大改,改完的好坏要评测说话(评测由用户安排)。

## 落地的次序

1. 看:开问题常量,`scan.block` 接上,`scan.container` 与告示牌字段搬成回答者。
2. 做:原版的特殊方块逐种做成自己的一组函数。动手前先把每一组的名字与参数列给用户。
3. 她再没有非用 `numen.gui.*` 不可的事之后,把它和 `Window` 拿掉。

## 没有定的

定了方向之后才需要定,按碰到的先后:

- **原始 NBT 给不给。** 状态是全的,带数据的方块(箱子、机器)还有 NBT;它是"没人适配"时的兜底,但可能很大、键名是模组的内部写法。
  倾向:默认不带,她要的时候才带(`numen.scan.block(pos, {nbt = true})`),太大就截断并写明。
- **`numen.use.block` 点开了界面怎么办。** 倾向:在里面关掉,把"看"的那几行交回她。
- **物品打开的容器怎么指**(背包、无线终端):它不是一格方块。
- **她自己的背包格、骑着的马的背包**:现在分别是没开界面时的 `numen.gui.view` 与 `numen.gui.open`;`numen.inv.craft` 认的是"开着的那个合成格"。
- **通用的动词被某个目标认领**(AE2 终端的"放入"是放进网络):等真实用例再开,那是"只能一个人干"的问题,和"大家都答"不是一种。
- **插件写自己的函数时要不要用开界面、点格子的引擎**:用得到,它就得在 `numen-api`(0020 决定 2)。
- **实体也问"这是什么"**(村民的职业、马驯没驯):问题常量的目标留好方块与实体两种,接 `numen.scan.entities` 放后面。
- **`scan` 这个词**:看一个方块用 `inspect` 更准,但这一组"用眼睛的事"都叫 `scan`,`look` 留给了以后的真看画面;暂不改。

## 实现说明

第 1 步(看)已落地:

- 问题与问的那一处:`Questions.DESCRIBE`,`numen-api` 的 `describe` 包里 `Describer`(认得交回几行,不认得交回空表)与
  `Descriptions.of(她, 目标)`(把登记着的挨个问一遍、按登记先后接起来,逐个隔离,出错记日志点名回答者的类)。目标用 `Focus`
  (一格方块或一只实体);它原在 `witness` 包,两个问题都用,挪到了 `numen-api` 的根包。瘦 api jar 放行 `Focus` 与 `describe` 包。
- `numen.scan.block` 交回的值多了 `details`(回答者们说的,一句一条,没人认得是空表),少了 `sign`;`numen.scan.container` 删了。
- 原版的回答者在 `numen` 的 `describe.VanillaDescribers`,经门登记:告示牌(两面各四行都写出来,涂了蜡多一句)、方块里存着什么
  (照旧经加载器的标准存储读,字的写法仍在 `BlockStorageReading.lines()` 一处)。
- 只接了方块。实体的"这是什么"没接。
- 测试:`DescriptionsTest`、`VanillaDescribersTest`;GameTest `a_plugins_describer_adds_its_line_to_what_a_block_scan_says`
  (告示牌的字和插件的一句都在,旁边抛异常的回答者不碍事),读箱子、读石头与空气的两条改问 `numen.scan.block`。

第 2 步(做)落地了通用容器与熔炉:

- 一件活 `StationTaskRecord`(`numen` 的 `task.station`):右键目标是一件子活(走过去、过权限层、按下去,和 `numen.use.block` 同一件),
  界面开了按步骤搬东西(每步是 `GuiItemsCompanionTask` 这件子活,它多了"只动界面那一侧的这几格"和"不点名拿哪一种"),收场时合上界面,
  做成、失败、被叫停都合。回执说哪一步搬了几件什么,做到一半收场已经搬了的照样说。
- 开工之前就拒绝的两样:目标那一格打不开界面(没有方块实体、也没有菜单提供者;不拦的话右键下去手里的方块会被放上去),要放的东西她没带。
- `numen.container.put`、`take`(目标是一格或一只右键能打开的实体;放进哪一格由界面自己分),`numen.furnace.input`、`fuel`、`take`
  (界面得是 `AbstractFurnaceMenu`;取产物时还没烧出来交回空表)。`numen.inv.store`、`fetch` 删了。
- `numen.inv.smelt` 仍是出厂 Lua 库,改成拼熔炉那三个函数:放料、放燃料,每两秒取一次,够数或 15 秒没有新产物就收。
- 测试:GameTest `container_put_and_take_go_through_the_chest`、`furnace_fuel_and_input_land_in_their_own_slots`(原木当燃料进的是
  燃料格)、`container_put_on_a_block_with_no_window_is_refused_before_any_click`;慢批里的 `smelt_loads_waits_and_takes_the_output` 照旧过。

第 2 步(做)落地了其余的原版特殊方块,和在工作台上合成:

- 十二组函数,都在 `numen` 的 `tools.station`,一组一个类,建在同一件活上:`numen.enchant`(`options`、`apply`)、`numen.anvil`
  (`combine`、`rename`)、`numen.stonecutter.cut`、`numen.loom.weave`、`numen.trade`(`offers`、`buy`,目标是实体)、
  `numen.beacon.set`、`numen.lectern`(`read`、`page`、`take`)、`numen.crafter`(`put`、`toggle`)、`numen.brewing`(`bottles`、
  `ingredient`、`fuel`、`take`)、`numen.smithing.forge`、`numen.grindstone`(`disenchant`、`repair`)、`numen.cartography`
  (`extend`、`copy`、`lock`)。
- 这件活的步骤多了一种:当场做完的一件事(`Deed`:发原版客户端会发的那个包、读回来,没让做就带着读到的事实收场),和搬东西的步骤
  排在一起。不看界面就判得了的参数(选项编号、合成器的格号、信标效果的名字、页码、次数)在派活之前退回;切石机切不切得出看配方表,
  也在走过去之前说。
- 引擎跟着改了三处。搬了几件看她背包里那一种变了多少,不看搬出来的那一格少了多少(切石机的成品格取一件又出一件)。合上界面改在
  回执拼好之前,合上时原版退回她背包的东西量出来写进回执:附魔台、铁砧这类不存东西的界面,没做成时放进去的就是这样回来的。界面
  自己合上时正在搬的那一步让它收完(讲台的书取下来,原版就合上讲台)。
- 交易一下一笔:`SelectTrade` 之后普通地点一下结果格,再把光标上的东西点进她的背包;按住 Shift 点结果格是能做几笔做几笔,所以
  不用它。信标先看层数许不许、再放付款物品:信标的界面合上时会把没用掉的付款物品丢在地上。
- `numen.inv.craft` 不再认"开着的那个合成格":不给 `at` 就在她自己的 2x2 里合,给了(一张工作台的格)就是这件活里的一步,配方
  在不在、料够不够在走过去之前判。参数叫 `at` 不叫 `table`:`table` 是 Lua 自带的全局名,登记时不许用,附魔台、锻造台、制图台那
  几个函数的第一个参数因此也写全名。`numen.inv.make` 找到或放下工作台后把那一格交给它,库里不再有 `numen.gui`。
- `numen.gui.*` 与 `tools.menu` 没有动,留给第 3 步。信标按层数能选哪些效果只有一处(`tools.station.BeaconEffects`),新旧两边同用。
  一处要在第 3 步收口:`numen.lectern.take` 是搬东西的一步(取别人讲台上的书先过权限层),书靠一个钮取下这件事仍由 `ContainerOps`
  问 `MenuKinds.takeOut`,删 `tools.menu` 时要把它搬到搬东西的那一处。
- 测试:GameTest `StationGameTests`(批 `numen_station`),每组一条从函数入口走完整一趟,附魔另有一条等级不够时如实说、东西都回来;
  `RefusedArgumentsTest` 加了派活之前退回的三条。
