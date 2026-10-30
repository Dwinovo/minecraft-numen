# 0017 远程武器:一个动词,多个提供者;弓和弩是 Numen 自己的两个提供者

- 状态:已接受,已落地(实现说明见文末)
- 日期:2026-10-31
- 另见:control 0014(登记归属)、control 0015(KubeJS 联动)、control 0016(同一类的扩展点:一个动词多个来源)

## 背景

她打远处的东西只会用弓和弩:`Loadout` 按 `BowItem`、`CrossbowItem` 挑武器,`RangedShot` 是弓弩专用的"拉弓/上弦 → 对准 → 松手 → 确认射出"
状态机,`Ballistics` 只会算箭的抛物线。#108 里整合包玩家要的是让她用 TaCZ 的枪——枪是"远程攻击"这个已有动作上的一个新名词,
不是一个新动作:该不该打由权限层按 `attack` 裁决,走位、选目标、连射、没弹药怎么收场都还是攻击任务那一套,变的只是"怎么起手、
怎么射、弹道什么样"。

用户的要求:照扩展点原则(已有动词加名词、意图不变、原版也是提供者),不为某一个模组特化,不留多源与胶水。

## 决定

1. **远程武器是 Numen API 的一张登记表**:提供者实现 `RangedWeapon`——认不认得这件物品、此刻能不能射(现成/要准备/没弹)、弹道什么样
   (抛物线的初速重力阻力,或命中扫描)、站位的环与射程、起一发(`Shot`:按刻推进的状态机,交回射没射出去)。
   登记经插件的门(`rangedWeapon`),句柄和作用域同 control 0014。
2. **弓和弩是 Numen 自己的两个提供者**,走同一张表、同一扇门,原版没有任何捷径。
3. **攻击任务只认提供者**:`Loadout` 向表里的提供者问每件背包里的物品(现成的 > 要准备的,同档按登记先后),`AttackCompanionTask`
   驱动提供者给的 `Shot`,`Ballistics` 按提供者说的弹道算瞄准(多一个命中扫描的分支)。弓弩的行为不变。
4. **权限不变**:任务照旧提出 `Action.attack`,权限层裁决;提供者不判断能不能打,只管怎么射。
5. **脚本也是提供者**:KubeJS 桥把 `rangedWeapon` 开给脚本插件(同一扇门),TaCZ 这类模组带来的枪用脚本接,Numen 不内置。
   不想要攻击任务自己选枪、瞄准、连发,只要模型一次开一枪的,用 0015 的受权动作写一个函数就够。

## 后果

- 枪、魔杖、弩炮这类"远程攻击的新名词"不改 Numen,写一个提供者(Java 或脚本)。
- 提供者的 `Shot` 在服务端主线程按刻跑;提供者抛了错按这件活失败收场,不带倒服务端。
- 站位的环与射程来自提供者:弓是 8 到 12 格,枪可以更远;命中扫描没有"到达时间"。
- 寻路里"走不到但射得到"的判断也改用手上这件武器自己的弹道(以前一律按弓的初速算)。

## 实现说明

- 表与接口在 numen-api 的 `combat` 包:`RangedWeapon`(`handles`、`readiness`、`flight`、`handling`、`begin`、`misfireText`,`Shot` 是按刻的状态机)、
  `RangedWeapons`(登记表,句柄 `Handle.once` 只撤自己那条)、`Flight`(`Arc` 抛物线或 `Hitscan` 命中扫描)、`Handling`(站位的环与射程)、`Aim`
  (瞄准结果;夹角的算法只有这一份,`Ballistics.angleDegrees` 委托给它)。门多一个 `rangedWeapon`,花名册多一种记数 `RANGED_WEAPON`。
- numen 里弓与弩是 `VanillaRanged` 的两个实例,在 `NumenContent` 的登记里经门登记(弓先);`Loadout` 按"现成的 > 要准备的、同档先登记的先"挑,
  `AttackCompanionTask` 驱动提供者的 `Shot`、站位的环取自提供者,`Ballistics.findShot` 按 `Flight` 分派到抛物线或命中扫描。
  `AttackPlan.Action.BOW` 改名 `RANGED`。站位日志里的武器名取提供者的 `name()`,哑火的回执取 `misfireText()`,哑火按未分类失败收场(不是拿错了工具)。
- 唯一有意的行为变化:"走不到但射得到"的判断按手上这件武器自己的弹道算,以前一律按弓的初速算——对弓一样,对弩更准。
  弓弩原有的单测(`RangedShotRulesTest`、`BallisticsTest`、`AttackPlanTest` 除改名外)断言未动;战斗 GameTest 新增一条弓的用例(原来没有)。
- 脚本侧:`plugin.rangedWeapon(名字, weapon => …)`(`ScriptWeapon`),`fire` 返回 `true`/`'wait'`/其他,多一个 `facing` 参数给模组接口要的
  `Supplier<Float>`(脚本的箭头函数交回的数到 Java 一侧不是 `Float`);`call.facingTo(目标)` 给函数版用。权限不变:任务提出 `attack`,提供者不判断。
- 真模组验证(一次性,不随仓库):TaCZ 的非官方 1.21.1 NeoForge 移植(Modrinth `tacz-1.21.1:1.1.8-hotfix-r7`)挂在 kubejs GameTest 的运行类路径上,
  `tacz:ak47` 无头可用:没有提供者时她背着枪一发不射(弹药不变);脚本登记提供者并 `/reload` 后,攻击任务挑枪、站进 6 到 24 格的环、开枪,
  子弹实体打在僵尸身上;一次开一枪的函数版同样打中。验完撤了依赖与这两条用例,脚本留在 docs/kubejs.md。
  TaCZ 的切枪与射击冷却按真实时间算,GameTest 的刻不限速,所以那两条用例先切好枪、等真实的两秒;这是 TaCZ 的性质,不进产品。
  留下的 kubejs 用例用一根烈焰棒验同一条桥(声明、`/reload` 后被攻击任务挑中并开火、删脚本后撤回)。
