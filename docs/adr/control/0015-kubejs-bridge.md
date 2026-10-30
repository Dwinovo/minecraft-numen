# 0015 KubeJS 联动:脚本里写的插件,和 Java 插件走同一扇门

- 状态:已接受,已落地(实现说明见文末)
- 日期:2026-10-31
- 另见:control 0014(登记归属与撤回)、control 0009(Lua API 登记)、issue #108

## 背景

整合包里的模组太多,Numen 不可能一个个内置联动(#108 举的 TaCZ 开枪、Beyond Dimensions 的存储终端都只是缺一层按对方公开接口的
"翻译",用不着 Mixin)。写 Java 插件门槛高,改一次要编译、发 jar、两端更新。#108 的设想是像 KubeJS 那样写脚本、热重载。
09-24 我们答复过:不在 Numen 里自建脚本引擎,做 KubeJS 联动,排在 Curios 之后(Curios 已落地)。

## 决定

- **不建自己的脚本引擎**:KubeJS 是 Minecraft 整合包脚本的事实标准,作者们已经会用,我们接它的插件接口(事件组与绑定),
  服务端脚本随 `/reload` 重载。
- **脚本插件就是插件**:脚本里每声明一个插件(给一个名字空间),桥就经 `NumenPlugins.register` 登记一个插件,拿到的是同一扇门;
  花名册里有它的一行(来源记为"脚本"),开关、出错隔离、体检和 Java 插件一样。脚本重载时,桥对上一次登记的脚本插件逐个撤回、
  再按新脚本重登(control 0014),没有专为 KubeJS 开的路。
- **脚本能做的就是门能做的**:登记工具组与函数(用 0014 的函数描述,说明照样必填)、监听同伴事件、登记事件类型与发事件、
  身体状态片段、穿戴来源等扩展点。动世界或伤实体的动作只能经 0014 的受权动作组件,身体做了什么照常报告给模型——脚本不是后门。
- **先接已有的动词**:模组带来的是新名词(一种枪、一个存储终端)时,接到已有动作的扩展点上(远程武器、容器视图),
  按真实用例逐个开扩展点;找不到对应的已有动作时才登记新的工具组。第一个真实用例是 TaCZ 开枪。
- **模块**:`plugins/kubejs` 是独立模块,只依赖 numen-api 与 KubeJS 的接口,不认识 `numen` 的类型;KubeJS 在场才装
  (照内嵌联动的闸门)。KubeJS 在 1.21.1 只有 NeoForge 版,先做 1.21.1 NeoForge,1.20.1 Forge 随后移植。

## 后果

- 整合包作者、服主写脚本就能给她接第三方模组,`/reload` 即生效,不重启、不重编译、客户端不用更新;脚本可以分享。
- Numen 本体只管原版与接口,第三方兼容交给社区。
- 要 Mixin 才做得到的仍得写 Java 插件。
- 脚本写的函数质量参差;体检报告(`ApiTester.lint`)与 Java 插件一样跑在脚本插件上。

## 实现说明

- 模块 `plugins/kubejs`(只 NeoForge,编译只引用 KubeJS 与 Rhino 的接口,`kubejs-neoforge:2101.7.2-build.379`):类与资源和别的内嵌联动一样平铺进成品 jar。
  发现不经 Numen 的闸门——KubeJS 读各模组 jar 根上的 `kubejs.plugins.txt` 找到 `NumenKubeJSPlugin`,KubeJS 在场才会读,Numen 的 `Builtin` 里没有它的一行。
- 脚本接口:事件组 `NumenEvents`,服务端事件 `register`(`event.plugin(名字空间, plugin => …)`)。声明只是记录;每次服务端脚本加载完
  (`KubeJSPlugin.afterScriptsLoaded`,启动与 `/reload` 同一条路)先把上一批脚本插件逐个 `PluginRoster.withdraw`,再发事件、对声明的每个插件
  `NumenPlugins.admit(SCRIPT, …)` 加 `NumenPlugins.register`——只经 0014 的两个操作,没有专为 KubeJS 开的路。
- 函数用 0014 的描述写(`ApiFunction.builder`),类型名 `string`/`integer`/`number`/`boolean`/`block`/`item`/`id`/`pos` 加 `[]` 对应 `LuaCodecs` 里的值转换;
  `summary` 与每个参数的说明在脚本这一层必填(缺了登记那个函数时就抛,点名缺什么),Java 一侧的生产者仍只拦硬错误、写法交给 lint。
- 失败隔离:一个插件的声明回调抛了,只它记为失败(`NumenPlugins.failed`),原因写进 KubeJS 控制台;登记抛了同样;别的脚本插件照常。
  脚本插件与 Java 插件同名的,`admit` 拒绝(一个来源的 id 不让另一种来源往里加登记)。
- 动世界:`ScriptCall` 只有四个受权的动作(`useEntity`、`attack`、`useBlock`、`breakBlock`),都先 `ServerCall.authorize` 过权限层,放行才跑脚本给的那一段,
  `call.report` 说的话随结果写进回执。0014 的 `GatedAct` 是按刻推进的活用的积木;脚本函数是一次调用(`Pending`),用的是它的调用级对应物
  `ServerCall.authorize`,两者都只是权限层的判与问,没有第二套裁决。这条没有照 ADR 原文"脚本也用 GatedAct"写,理由是脚本不持有按刻推进的活。
- 第一个真实用例 TaCZ 开枪还没做:枪是"远程攻击"这个已有动作上的新名词,要先在 numen-api 开远程武器的扩展点(见提案),再用脚本接枪。
