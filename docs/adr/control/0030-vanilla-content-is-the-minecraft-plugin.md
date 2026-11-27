# 0030 原版内容是内置的 Minecraft 插件:模组 numen_minecraft,Lua 命名空间 minecraft;引擎的模组 id 改成 numen

- 状态:已接受,未落地
- 日期:2026-11-27
- 另见:[0014](0014-plugin-registrations-scoped.md)(插件登记有作用域)、[0029](0029-claude-style-prompt-and-api-search.md)(引擎不点名插件、
  上下文里只列插件命名空间)

## 背景

- 现在玩家装两个模组:引擎 `numen_api`(目录 `numen-api/`)和内容 `numen`(目录 `numen/`,含 `numen:pathing`)。内容模组打通的是原版:
  移动、挖掘、背包、战斗、寻路。它本来就只是引擎的一个插件,和 `ysm`、`kaleidoscope` 一样通过门登记。
- 可它和引擎共用 Lua 命名空间 `numen`:引擎自己的组(`numen.mc`、`numen.permission`、`numen.task`)和原版内容的组(`numen.move`、`numen.work`……)
  混在一起,内容插件登记时直接拿引擎的常量 `NumenPlugins.NUMEN` 当自己的 id。模型看不出哪些是引擎的、哪些是某个插件的;0029 定了上下文里只列插件
  命名空间,混着就列不清。
- 用户:"我开发的核心还是 numen-api,第一个插件是 Minecraft 插件";"玩家安装 numen-api 自带 Minecraft 插件";走"代码模块和模组也一起改",
  "本来就是 beta 版"。
- 硬限制:模组 id `minecraft` 被原版占着,NeoForge 与 Fabric 都不许重名。

## 决定

心智模型:**原版也只是一个插件,和其他插件完全对等;它恰好随引擎一起发,就像 VS Code 自带的内置扩展。**

| 层 | 现在 | 改成 |
|---|---|---|
| 引擎的模组 id | `numen_api` | **`numen`**(玩家装的就是它;它的配置 `config/numen/`、世界里的 `numen/` 本来就叫 numen) |
| 原版内容的模组 id | `numen` | **`numen_minecraft`** |
| 原版内容的 Lua 命名空间、插件 id | `numen`(借用引擎的) | **`minecraft`**:`minecraft.move.walk`、`minecraft.work.dig` |
| 引擎自己的 Lua 组 | `numen.mc`、`numen.permission`、`numen.task`、`numen.api` | 不变,仍在 `numen` 下:这是引擎自己的命名空间 |
| 原版内容的代码目录 | `numen/`、`numen:pathing` | **`plugins/minecraft/`**、`plugins:minecraft:pathing`,和其他插件并排 |
| 原版内容的 Java 包 | `com.dwinovo.numen` | **`com.dwinovo.numen.plugins.minecraft`** |
| 原版内容的资源命名空间与翻译键 | `assets/numen/`、它自己的键 | `assets/numen_minecraft/`、键以 `numen_minecraft.` 起头;引擎的键照旧 `numen.` |
| 引擎的代码目录、Java 包 | `numen-api/`、`com.dwinovo.numen.api` | 不变:写给开发者看,"API"是准确的 |
| 世界存档里的 `numen/` | 引擎的家 | 不变 |

1. **内置,但不焊死**:Minecraft 插件作为内嵌模组打进引擎的 jar(NeoForge 与 Fabric 的模组嵌模组),玩家只装一个 Numen。三条守住:
   - 引擎不认识它:`numen-api` 不引用它的任何类型、函数名或 id;`NumenPlugins.NUMEN` 只当引擎自己的命名空间用,插件不得借用。
   - 它不走后门:和 `ysm` 一样经门登记函数、说明(0029)、本能、技能;没有"内置插件专用"的路。
   - 插件分页里照常有它一行,标"内置";它在门外动过东西(工具、Mixin、网络包),照旧声明"卸下要重启"。
2. **一次改完,不留兼容**:网络通道与数据包 id 随引擎模组 id 从 `numen_api:` 变成 `numen:`,协议版本号升一级;所有插件声明的依赖改成 `numen`
   (以及要原版内容的插件依赖 `numen_minecraft`);各平台的模组描述文件、构建脚本、运行配置、CI 与发版工作流、AGENTS.md 与 CONTRIBUTING.md 里的命令
   (`:numen:neoforge:runGameTestServer` → `:plugins:minecraft:neoforge:runGameTestServer` 等)一起改。旧存档、旧客户端不迁移(beta)。
3. 函数、模块、帮助文字、插件说明、技能、提示词、测试里写到 `numen.move` 这类原版内容函数名的地方,全部改成 `minecraft.*`;
   写到引擎自己的组(`numen.mc` 等)的地方不动。
4. 只动 1.21.1;其他版本分支以后移植时再说。

## 后果

- 上下文里那一行变成 `Lua API: numen, minecraft, ysm, …`:`numen` 是引擎的,其余每个是一个插件,模型分得清。
- 仓库结构直接说明架构:`numen-api/` 是引擎,`plugins/` 下全是插件,其中 `minecraft` 随引擎发。
- 玩家只看到一个 Numen;开发者看到一个引擎加一组插件。
- 一次大改名:所有原版内容的函数全名、构建与发版路径都变;移植到其他版本分支时要整体重做一遍。
