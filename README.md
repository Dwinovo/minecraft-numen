<div align="center">

# 言出法随

[English](README_EN.md) · [**简体中文**](README.md)

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20~%2026.2-62B47A?style=flat-square)
![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20NeoForge%20%7C%20Forge%20%E2%89%A41.20.4-DE7C36?style=flat-square)
![Java](https://img.shields.io/badge/Java-17%20%7C%2021%20%7C%2025-007396?style=flat-square&logo=openjdk&logoColor=white)
![License](https://img.shields.io/badge/code-LGPL--3.0-A8731E?style=flat-square)

[**网站**](https://numen.dwinovo.cn) · [**安装**](#安装) · [**快速开始**](#快速开始) · [**架构**](#架构) · [**插件**](#插件) · [**常见问题**](#常见问题)

<img src="assets/branding/numen-hero-2560.webp" alt="Numen 言出法随" width="100%">

</div>

言出法随是一个**运行在 Minecraft 内部的具身智能体**，*作为模组直接存在于游戏之中*，它能够不断探索 Minecraft 世界，**自主决策并采取行动完成任务**。

详情请见[网站](https://numen.dwinovo.cn)。

## 安装

言出法随已发布在 [CurseForge](https://www.curseforge.com/projects/1581109)，在你常用的 Minecraft 启动器中搜索 **"Numen"** 模组下载即可。目前支持如下版本和加载器：

| Minecraft 版本 | 加载器 | Java |
|---|---|---|
| 1.20.1、1.20.2、1.20.4 | Fabric、Forge | 17 |
| 1.20.6、1.21、1.21.1、1.21.4、1.21.5、1.21.8、1.21.10、1.21.11 | Fabric、NeoForge | 21 |
| 26.1.2、26.2 | Fabric、NeoForge | 25 |

## 快速开始

1. **配置模型。** 进入游戏后按下打开面板的快捷键（默认为 `G`，1.21.8 及以上版本为 `N`，可在游戏的按键设置中修改），在设置页中选择模型服务并填入**你自己的 API key**。
2. **召唤智能体。** 在面板中召唤一个智能体，为它起一个名字。
3. **开始交流。** 像与任何智能体交流一样，*用自然语言*告诉它你想做的事。

言出法随原生支持 **OpenAI** 与 **Anthropic** 两种接口协议，并内置了一些模型服务商，对于未列出的服务商，只要它兼容其中一种协议，*填入地址即可使用*。

### 外接大脑

言出法随可以整体作为一个 **MCP 服务器**，由外部的智能体直接驱动，例如 Claude Code、Codex、Cursor 等任何支持 MCP 协议的客户端。此时推理和 token 消耗都计在外部智能体上，你可以用这些客户端的**订阅套餐**来驱动它，*通常比按量调用 API 更划算*。

### 常用操作

| 按键 | 作用 |
|---|---|
| `G`（1.21.8 及以上为 `N`） | 打开面板 |
| `Y` | 不打开面板，快速输入一句话 |
| 按住 `R` 并滚动鼠标滚轮 | 有多个智能体时，选择当前对话的对象 |
| 按住 `V` | 语音输入，松开后发送，*不影响 WASD 移动* |

面板的设置页还提供以下功能。

- **人设**：内置了几种简单的人设，你也可以写入任何自己喜欢的人设。
- **皮肤**：为召唤出的智能体设置皮肤，上传的皮肤经由 [MineSkin](https://mineskin.org) 签名后生效。
- **语音**：配置语音输入与语音输出，让智能体能听会说。
- **群聊**：把不同智能体的头像拖到一起即可建立群聊。在群聊中可以 @ 某个成员让它回复，也可以不 @ 任何人向全体广播。

## 架构

<p align="center">
  <img src="assets/diagrams/numen-architecture.svg" alt="言出法随的分层架构" width="760">
</p>

言出法随的架构自下而上分为**三层**。最底层是 **Minecraft**，智能体在其中*操纵一个真实的玩家*。中间层是开放给大语言模型的接口，主体是由**原子动作**构成的 **Lua API**，涵盖感知、移动、挖掘、建造与交互，*其他模组也能经由插件接入*；另有少量 **Java API** 以工具调用的形式提供，例如加载技能。最上层是**大语言模型**，它编写在沙箱中运行的 Lua 程序，自由组合原子动作，**一次调用即可连续完成数十个步骤**。

更详细的架构见[网站](https://numen.dwinovo.cn)。

## 插件

基于上述架构，**任何开发者都可以通过插件**向言出法随注册新的 Lua API，供模型调用，从而*让智能体学会使用其他模组*。本仓库的 `plugins` 目录已经为[是，史蒂夫模型](https://github.com/YesSteveModel/YesSteveModel)、[车万女仆](https://github.com/TartaricAcid/TouhouLittleMaid)等模组提供了插件，例如模型可以通过"是，史蒂夫模型"的 Lua API 切换自己的皮肤，也可以通过"车万女仆"的 Lua API 驯服女仆。这些插件可以作为编写插件的*参考模板*，完整的开发者文档见[网站](https://numen.dwinovo.cn)。

## 常见问题

**我的 API key 安全吗？**
**安全。** 智能体的推理发生在你自己的客户端上，API key *只保存在本地*，直接发往你选择的模型服务，**不经过任何第三方服务器**，也不会上传到游戏服务器。

**联机服能用吗？**
**能。** 言出法随是双端模组，服务端与客户端都安装后即可在联机服中使用。每位玩家使用自己的 API key，驱动属于自己的智能体。

**要花钱吗？**
言出法随本身**开源免费**，调用大模型使用的是你自己的 API key，费用由你选择的模型服务决定。

**外接大脑的模式下，人设还生效吗？**
**不生效。** 外接大脑模式下由外部智能体接管言出法随，言出法随不会把人设交给它，对话上下文也不会在言出法随内部保存。*如需保持人设，可以把人设写进外部智能体自己的提示词中。*

## 许可

本项目源代码以 [LGPL-3.0](LICENSE) 协议发布，通过 API 使用本项目的插件不受此限。截图、演示动图与架构图以 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 协议发布，其余美术资源保留所有权利，详见 [licenses/ASSETS.txt](licenses/ASSETS.txt)。

## 致谢

感谢每一位关注、下载与使用言出法随的玩家，以及提交问题与代码的[贡献者](https://github.com/Dwinovo/minecraft-numen/graphs/contributors)。言出法随所借鉴的开源项目与研究工作，详情请见[网站](https://numen.dwinovo.cn)。
