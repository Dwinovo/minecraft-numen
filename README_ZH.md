<div align="center">

# Numen · 言出法随

### 一个住在 Minecraft 里的 AI 同伴——召唤它、跟它说话、看它把活干完。

*言出法随（yán chū fǎ suí）——你说出口，它便成真。*

[English](README.md) · [**简体中文**](README_ZH.md)

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20~%2026.1.2-62B47A?style=flat-square)
![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-DE7C36?style=flat-square)
![Java](https://img.shields.io/badge/Java-17%20%7C%2021%20%7C%2025-007396?style=flat-square&logo=openjdk&logoColor=white)
![Version](https://img.shields.io/badge/version-0.0.4-4B6BFB?style=flat-square)
![License](https://img.shields.io/badge/code-LGPL--3.0-A8731E?style=flat-square)

[**它是什么**](#它是什么) · [**功能**](#功能) · [**安装**](#安装) · [**快速开始**](#快速开始) · [**技能与记忆**](#技能与记忆) · [**生态**](#生态) · [**给开发者**](#给开发者) · [**授权**](#授权)

</div>

<!-- 提示：有了实机演示后，在这里放一张 GIF/截图，比任何文字都更能说明这个模组。 -->

---

## 它是什么

Numen 往你的世界里放进一个 **AI 同伴**。你召唤它，用大白话跟它说话，它自己规划步骤、动手干活——挖矿、建造、种田、战斗、合成。

你说"去挖一组铁回来"，它就下矿、寻路、挥镐，满载而归，回头还问你要不要顺手熔了。你说"在我站的地方盖个小屋"，地基就一块块垒起来。你说出口的每一句话，都会在世界里变成真实发生的事——用你的模型会的任意语言。

```
你：    挖一组铁矿回来
Numen： 这就去。下矿找铁。
        ▸ 4 步 · locate_biome · move_to · auto_mine · collect_items   ✔
Numen： 拿到 64 个粗铁——要我熔了吗？
```

**它是一个货真价实的 agent。** 你给一个目标，它自己拆解、规划、选对工具、判断距离、随机应变，还会从失败里纠错，全程不用你盯着。原版生存里一个玩家会干的活，它能替你干八成——从开局撸树，一路到进末地屠龙。

**它天生看得懂模组内容。** 同伴是一具真正的服务端玩家，所有交互都走原生玩家代码——和原版、红石、怪物 AI、以及别人的 mod 站在同一套规则里。它能挖模组的方块、右键模组的机器、从槽位里掏东西、打开模组的 GUI，**不必为谁单独写适配**；甚至隔着外壳就能读出一台机器里装了多少物品、流体和能量。这正是协议层机器人迈不过去的那道坎：走封包的机器人分不清机械压印机和外壳，Numen 分得清，因为它和模组玩的是同一套规则。机械动力（Create）、AE2、Mekanism——这些它都伸得进手。

## 功能

给它一个意图，它拆成几十步动作，一口气从头干到尾。

| | |
|---|---|
| ⛏️ **干真活** | 挖矿、伐木、采集、建造、精确放置与破坏、照配方手搓、用熔炉熔炼、把战利品分门别类塞进箱子。 |
| 🧭 **真走位** | 一套参考 Baritone 思路、为同伴重写的寻路器：会搭桥、垫脚、搭柱上升、挖隧道、下挖楼梯、游泳。"去那个坐标"就是字面意思，哪怕一路挖到钻石层。 |
| ⚔️ **真战斗** | 原生玩家近战与弓箭，真冷却、真暴击。受伤会自己吃东西，快淹死会自己游上岸。死了也能恢复：原版照常掉落，缓一会儿在你身边重生。 |
| 🔭 **真感知** | 扫方块、扫实体、查状态、查配方、定位任意结构与群系，还能不开 GUI 就透视一台机器的内部。 |
| 🧠 **真记性** | 对话跨存档持久化，太长会自动压缩（Claude-Code 式的记忆压缩）。它记得用过的工作台、熔炉、箱子，下次直接走回去，而不是重造一个。 |
| 🧩 **模组原生** | 一具真 `ServerPlayer` 身体，意味着模组的方块、物品、GUI 开箱即用，无需逐个适配。 |

近三十个工具，拼成它此刻的双手与双眼；这套能力还会靠**技能**和**扩展**继续往上长（见下文）。

## 安装

Numen 已上架 **[CurseForge](https://www.curseforge.com/) · [Modrinth](https://modrinth.com/) · [GitHub](https://github.com/Dwinovo/minecraft-numen)**。你在哪个版本玩，它就在哪个版本等你——**共 11 个 Minecraft 版本，每个版本一条 git 分支**。

| Minecraft | 加载器 |
|---|---|
| 1.20.1 · 1.20.2 · 1.20.4 | Fabric · Forge |
| 1.20.6 · 1.21.1 · 1.21.4 · 1.21.5 · 1.21.8 · 1.21.10 · 1.21.11 · 26.1.2 | Fabric · NeoForge |

1. **安装**模组（Fabric 端另需 [Fabric API](https://modrinth.com/mod/fabric-api)），启动一次。
2. **自备大模型 key。** Numen 自身不含 AI 服务，需要你提供一个 **OpenAI 兼容的 API key**。OpenAI、DeepSeek、Kimi、Qwen、豆包…… 任意 OpenAI 兼容后端都行。

大脑跑在你自己的机器上：agent loop 在你的客户端、用你的 key 调 LLM。每个玩家各付各的用量，服主全程不经手任何人的 key，你也不必上交自己的。Numen **零第三方运行时依赖**——LLM 传输只用 JDK 的 `HttpClient` 加 Gson。

## 快速开始

1. **填 key。** 按 **`G`** → **Settings**，选 provider，粘贴 key，选好模型。
2. **召唤一个同伴。** 点面板左栏的 **`+`**，给它起个名字，回车。
3. **点它的头像开聊**，把要做的事告诉它。剩下的，交给它。

> 按 `G` 的面板有三页：**Chat**（聊天 + 实时计划面板）、**Items**（一张仿原版背包的只读角色卡）、**Settings**（填 key 和模型）。左栏就是同伴名册——点头像切换、点 **`+`** 召唤、点 **`✕`** 注销。屏幕左缘还有个小头像 HUD，它说话时头像和气泡会一起滑出来。

## 技能与记忆

除了内置工具，Numen 的本事还能靠两条路往上长：

- 📖 **技能——调教它。** 技能是一篇纯文本 Markdown 工作流（零代码、人人能写），放在 `config/numen/skills/` 下，只在相关时才加载，让提示词始终精简。Numen 出厂自带一整套通往屠龙终局的攻略——下界、烈焰棒、末影珍珠、要塞、龙战。改一篇、或自己写一篇，就能把你基地的规矩、或一个新 mod 的玩法亲手教给它。config 目录里的同名技能永远优先。
- 🧠 **持久的记忆。** 对话跨存档保存，太长时自动压缩，让同伴在一整段长时间游玩里始终连贯，记得住自己刚干过什么。

它的能力边界还会随下文的**扩展生态**一起扩张——Numen 自己的每一分能力，都写在和扩展共用的那套公共 API 上。

## 生态

Numen 建在 **numen-api** 引擎之上，引擎随模组一起打包在内。引擎和它的配套项目都是独立、开放、可依赖的：

| 项目 | 是什么 |
|---|---|
| [**numen-api**](https://github.com/Dwinovo/numen-api) | Numen 底下的引擎——agent loop、工具调度、LLM provider，以及给扩展用的公共 API（`NumenGateway` + `NumenActuator`）。你可以注册自己的工具、把技能随 jar 打包进去。 |
| [**numen-maven**](https://github.com/Dwinovo/numen-maven) | 托管 numen-api 制品的 Maven 仓库，让你在自己的构建里依赖引擎。 |
| [**numen-qq-mcp**](https://github.com/Dwinovo/numen-qq-mcp) | **Numen QQ Bridge**——直接在 QQ 聊天里指挥你的同伴，人不在键盘前也能派活。 |
| [**numen-mcp**](https://github.com/Dwinovo/numen-mcp) | **Numen MCP**——通过 Model Context Protocol，让电脑上的外部 AI（比如 Claude）反向操作你的同伴。桥是双向的：整个世界成了外部 agent 的沙盘。 |

## 给开发者

Numen 出厂的每一个工具、每一篇技能，全部只用公共 API 写成，没有任何私有通道。引擎（[numen-api](https://github.com/Dwinovo/numen-api)）拆分出来之后，任何 mod 作者都拿得到同一份能力：

- 🔧 **通过 `NumenGateway` 注册一个工具**，你 mod 的能力就长在了 AI 的手上。工具契约里刻意**不含任何 Minecraft 概念**——怎么完成调用（同步、异步、自己发包、调外部网络服务）完全由工具自己做主。正因如此，同一套 API 伸向 Discord、QQ、MCP，和伸向一条矿脉一样顺手。
- 📖 **随 jar 附带技能**——一句调用，就把你 jar 里的 `/skills` 目录变成内置技能，玩家装上你的 mod，AI 自动学会怎么玩它。
- 🏗️ **或者，造一个完全不同的 AI**——同一块地基上，AI NPC、剧情角色、服务器管家，随你想象。

```gradle
repositories { maven { url = 'https://raw.githubusercontent.com/Dwinovo/numen-maven/main' } }
dependencies  { modImplementation "com.dwinovo.numen:numen-api-fabric-1.21.1:<version>" }
```

面向集成的公共对接 API 采用 **MIT** 授权——写工具、写技能、写兼容，不必被 LGPL 牵着走。上手指南、完整示例与版本矩阵，见 [numen-api 的 README](https://github.com/Dwinovo/numen-api)。

## 授权

授权参照 AE2 拆分——不同层，不同条款：

| 层 | 授权 |
|---|---|
| 源代码 | [**LGPL-3.0**](LICENSE)——你分发的修改版必须以同协议继续开源。 |
| 公共对接 API（扩展 / MCP 桥接所依赖的接口，随 [numen-api](https://github.com/Dwinovo/numen-api) 提供） | [**MIT**](LICENSE-API)——自由地写 mod 兼容，包括用在闭源项目里。 |
| 美术与资源 | [**保留所有权利**](LICENSE-ASSETS)——"Numen" / "言出法随" 名称与品牌标识均予保留。 |

基于 [MultiLoader Template](https://github.com/jaredlll08/MultiLoader-Template) 构建。寻路器在设计思路上借鉴了 [Baritone](https://github.com/cabaletta/baritone)，并针对服务端（假玩家）场景完全独立重写——未复制、移植或改写其任何源码。

---

<div align="center">

<sub>想自己构建、看完整工具清单或架构设计？都在源码里——从 <code>common/src/main/java/com/dwinovo/numen/</code> 看起。</sub>

<br><sub><b>言出法随</b>——你的意图直达世界，AI 的能力直达每一个模组。</sub>

</div>
