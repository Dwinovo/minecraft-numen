<div align="center">

# Numen API

### Numen mod 底层的引擎，也是插件对接的稳定 API

*[Numen · 言出法随](https://github.com/Dwinovo/minecraft-numen) 的心脏：AI 同伴只是第一盘卡带，这里是那台游戏机。*

[English](README_EN.md) · [**简体中文**](README.md)

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=flat-square)
![Loaders](https://img.shields.io/badge/Loaders-common%20%7C%20Fabric%20%7C%20Forge%20%7C%20NeoForge-DE7C36?style=flat-square)
![Java](https://img.shields.io/badge/Java-21-007396?style=flat-square&logo=openjdk&logoColor=white)
![License](https://img.shields.io/badge/code-LGPL--3.0%20·%20API%20MIT-4B6BFB?style=flat-square)
![Version](https://img.shields.io/badge/version-0.0.10-A8731E?style=flat-square)

[**这是什么**](#这是什么) · [**公共 API**](#公共-api) · [**如何依赖**](#如何依赖) · [**构建与发布**](#构建与发布) · [**生态**](#生态) · [**授权**](#授权)

</div>

---

## 这是什么

**numen-api** 是驱动 [Numen](https://github.com/Dwinovo/minecraft-numen) mod 的引擎，独立成一个项目，对外提供一套**稳定的公共 API**。Numen mod 把这台引擎打包在身上，插件则编译对接它。同伴会想、会说、会走、会挖、会打、会记事——这些能力全部住在引擎里；那个 mod 只是在引擎之上叠了一套工具和技能。

引擎提供这些东西：

- **客户端对话回路**（`EntityAgentLoop`）——听一句话 → 选一个工具 → 干活 → 看结果 → 决定下一步。这条回路跑在**玩家自己的游戏客户端**上，用**玩家自己的 API key**。
- **工具契约**——`NumenTool` / `ToolRegistry` / `ToolCall` / `TaskResult`。工具就是同伴能调用的一种能力；引擎负责调度它，并把结果送回对话。
- **兼容 OpenAI 接口的模型接入**——DeepSeek、DashScope（通义千问）、OpenAI、Moonshot（Kimi）、Zhipu（GLM）、Minimax、SiliconFlow、Volcengine（豆包）。传输层用 JDK 自带的 `HttpClient` + Gson 手搓，**不带任何第三方运行时依赖**。
- **对话记忆**——跨存档持久化，聊长了自动摘要压缩（Claude Code 式的压缩策略）。
- **同伴身体**——`NumenPlayer`，一个服务端的"真玩家"（`ServerPlayer`）。每个动作都走原版玩家的代码路径，所以红石、怪物、容器、别人的 mod 天生都拿它当真玩家对待。
- **技能系统**——纯文本 Markdown 工作流，教同伴怎么玩，按需加载以保持提示词精简。
- **多加载器**——同一套代码打通 `common` / `fabric` / `forge` / `neoforge`。

---

## 公共 API

插件通过三扇门接触引擎：两扇门给同伴喂输入，一扇门教它一项新能力。下面的一切都在稳定的、已发布的 API 面上。

### 门一 —— `NumenGateway`：喂给内置大脑

把一条消息原样交给同伴的**内置大脑**。引擎会在对话协议允许的下一个位置把它拼进去，效果和主人亲手打字一样；随后由内置 LLM 决定要做什么。入站桥接就是这样工作的——QQ 桥把一条 QQ 消息变成一次 `enqueue`。

```java
import com.dwinovo.numen.api.NumenGateway;

// 一条消息从 QQ / Discord / 直播弹幕进来，原样交给同伴的大脑。
boolean queued = NumenGateway.enqueue(companionUuid, "QQ 里有人说：去给我挖一组铁");
// queued 只有在消息为空、或该同伴本局从未召唤过时才为 false。
```

同伴的回复通过**调用工具**（门三）离开，不走回调。入站 = 消息队列，出站 = 工具调用。任意线程都可安全调用，`enqueue` 内部会切到客户端主线程。

### 门二 —— `NumenActuator`：用外部大脑驱动身体

完全绕过内置 LLM，直接驱动同伴的**身体**。契约是 **`acquire` → `invoke*` → `release`**：`acquire` 暂停内置大脑并腾出身体，让两个大脑不会抢同一具身体；`invoke` 无头地跑任意已注册工具，返回一个装着结果 JSON 的 `CompletableFuture`；`release` 把控制权交还。每次调用都指向一个同伴 UUID，各具身体独立跑任务，所以一个外部大脑可以 `acquire` 多个同伴、驱动一支**并行舰队**。MCP 服务器（numen-mcp）就是这样工作的。

```java
import com.dwinovo.numen.api.NumenActuator;
import java.util.UUID;

NumenActuator.companions().thenAccept(fleet -> {
    UUID body = fleet.get(0).uuid();
    NumenActuator.acquire(body)                                            // 暂停它的内置大脑
        .thenCompose(ok -> NumenActuator.invoke(body, "move_to", "{\"x\":100,\"y\":64,\"z\":-200}"))
        .thenAccept(resultJson -> System.out.println(resultJson))          // 一个 TaskResult JSON 字符串
        .whenComplete((r, e) -> NumenActuator.release(body));              // 始终把身体交还
});
```

无头的 `invoke` 从不触碰同伴的对话记录——上下文由外部大脑自己持有。各类失败（未知工具、参数错误、工具抛异常）都以 `TaskResult.fail` 的 JSON 返回，`CompletableFuture` 不会异常完成。任意线程可调。

### 门三 —— `NumenTool` + `ToolRegistry.register`：教它一项新能力

工具就是同伴能调用的一种能力。实现四个方法，在 mod 初始化时注册实例即可。契约上**刻意一个 Minecraft 概念都没有**——工具可以驱动身体、可以对接外部服务、可以调用某个 Web API；引擎只负责把它呈现给 LLM、把调用送达、把结果送回。

```java
import com.dwinovo.numen.agent.tool.*;
import com.dwinovo.numen.task.TaskResult;
import java.util.Map;

public final class SendQqMessageTool implements NumenTool {
    public String name()        { return "send_qq_message"; }
    public String description() { return "通过 QQ 回复主人。当你有话要对主人说时使用。"; }

    public Map<String, Object> parameterSchema() {
        return Map.of("type", "object",
                "properties", Map.of("text", Map.of("type", "string")),
                "required", java.util.List.of("text"));
    }

    public void invoke(ToolCall call) {
        String text = call.args().get("text").getAsString();
        // 想怎么干就怎么干——立即完成、切线程、POST 给外部服务——然后恰好 complete 一次：
        myQqClient.send(text);
        call.complete(TaskResult.ok("已通过 QQ 发给主人").toJson());
    }
}
```

```java
// 在 mod 初始化时：
ToolRegistry.register(new SendQqMessageTool());
```

`invoke` 通过唯一的动词 `ToolCall.complete(json)` 报告结果——同步报告，或把活儿交给别的线程/服务端身体之后再报告。`ToolRegistry.register` 遇到重名会抛异常，并保留注册顺序（稳定的工具顺序有利于提示词缓存）。

### 哪些是稳定的

公共 API 就是那些在 `package-info` 里明确声明为公共的包，沿用 Applied Energistics 2 的约定。这些包之外的一切、或包内标了 `@Internal` 的成员，都可能在任意版本变动。

| 包 | 公共类型 | 作用 |
|---|---|---|
| `com.dwinovo.numen.api` | `NumenGateway`、`NumenActuator` | 喂输入 / 驱动同伴的两扇门 |
| `com.dwinovo.numen.agent.tool` | `NumenTool`、`ToolRegistry`、`ToolCall` | 工具契约 + 注册 |
| `com.dwinovo.numen.agent.tool.api` | `ToolContext` | 服务端工具的单次调用上下文 |
| `com.dwinovo.numen.task` | `TaskResult` | 工具交回的结果信封 |
| `com.dwinovo.numen.entity` | `NumenPlayer` | 服务端的同伴身体 |

其余一切——各家模型接入、对话回路、记忆、技能系统、网络、UI——都是 `@Internal`。需要一份完整的参考实现？[numen-core](https://github.com/Dwinovo/minecraft-numen) 的全部工具与技能都构建在这套 API 之上，没有走任何后门。

---

## 如何依赖

artifact 发布在 [numen-maven](https://github.com/Dwinovo/numen-maven)。坐标里带着加载器和 Minecraft 版本：

```
com.dwinovo.numen:numen-api-<loader>-<mcversion>:<version>
```

依赖精简版的公共 API jar（classifier 为 `api`）。运行时引擎由 **Numen mod 提供**——它把引擎打包在身上，插件自己不携带任何引擎代码。

```gradle
repositories {
    maven { url = 'https://raw.githubusercontent.com/Dwinovo/numen-maven/main' }
}

dependencies {
    // Fabric：瘦 jar 与主 jar 一样是 intermediary 命名，用 modCompileOnly
    // 让 Loom 映射到你自己的命名——yarn 和 mojmap 都能用
    modCompileOnly "com.dwinovo.numen:numen-api-fabric-1.21.1:0.0.10:api"

    // NeoForge / Forge：运行期命名就是 Mojang 命名，直接 compileOnly
    // compileOnly "com.dwinovo.numen:numen-api-neoforge-1.21.1:0.0.10:api"
}
```

按你的目标替换加载器（`fabric` / `forge` / `neoforge`）和 Minecraft 版本。本分支基于 Java 21 构建 `1.21.1`。

`numen-ai`（模型接入与用量核算）和 `numen-ui`（控件）会随依赖自动带进来——`NumenTool` 继承的 `IToolSpec` 就住在 `numen-ai` 里，少了它编译不过。这两个坐标不带 MC 版本后缀，各分支发的是同一份字节。

**要改引擎本身的机制**，就依赖 core：

```gradle
dependencies {
    modImplementation "com.dwinovo.numen:numen-neoforge-1.21.1:0.1.2"
}
```

core 会把对应的 `numen-api-*` 一并带出来，不用另写一行。

> 两家的 `-common` 坐标（`numen-api-common-*` / `numen-common-*`）都别依赖。它们只有跨加载器那部分代码：没有加载器入口，`numen-api-common-*` 还没有语言文件，`numen-common-*` 也不内嵌引擎。能编译，装进游戏什么都不会发生。**带加载器名的那个坐标才是完整的。**

---

## 构建与发布

标准的 MultiLoader-Template 布局（`common` + 各加载器子项目）。

```bash
./gradlew build         # 构建每个加载器
./gradlew datagenAll    # 跑齐两家、两个 loader 的数据生成
./gradlew publishAll    # 发 api + core + ai + ui 的全部制品
```

发布目标由 `gradle.properties` 的 `local_maven_url` 决定，`-Plocal_maven_url=...` 可覆盖。`datagenAll` / `publishAll` 会自己按分支挑第二个 loader（Forge 还是 NeoForge），调用方不必知道。

发布物按坐标分三类：完整 jar（运行时用，由 Numen mod 打包携带）、classifier 为 `api` 的精简 jar（插件 `compileOnly` 用），以及 sources / javadoc。

**正式发布由 CI 做**——打 `v*` tag 或手动触发 `Publish Maven Artifacts` 工作流，它会 checkout `numen-maven` 并把制品推上去。制品版本是定死的正式版号，同一个坐标只写一次；要发新的就先把版本号往前推。

---

## 生态

**Numen**（[minecraft-numen](https://github.com/Dwinovo/minecraft-numen)）是那个 mod——AI 同伴本体,跑在 **[numen-api](https://github.com/Dwinovo/numen-api)** 引擎上(经 **[numen-maven](https://github.com/Dwinovo/numen-maven)** 发布),引擎对外开放一套小巧的公共 API。两类东西建在它之上： *(本仓库)*

**扩展一个同伴**——同伴自己的大脑仍然做主:
- **桥(Bridge)** 把一个外部渠道接进同伴:消息进来,同伴自己决定怎么做。基于 `NumenGateway`。→ **[numen-qq-bridge](https://github.com/Dwinovo/numen-qq-bridge)**(QQ),后续还有更多。
- **技能(Skill)** 教同伴怎么做事——markdown 注入它的上下文。随 Numen 内置,或社区编写。

**把 Numen 暴露出去**——把操控权交给外部大脑:
- **[numen-mcp](https://github.com/Dwinovo/numen-mcp)** 是一个 Model Context Protocol 服务器:任意外部智能体(比如 Claude)直接驱动同伴。基于 `NumenActuator`。

---

## 授权

- **源代码 —— [LGPL-3.0](LICENSE)。** 你分发的修改版必须以同协议继续开源。
- **公共对接 API 面 —— [MIT](LICENSE-API)。** 插件与 MCP 桥接对接的那层表面（`com.dwinovo.numen.api` 包下的类）是 MIT，写兼容不必被 LGPL 牵着走，商业闭源项目也可自由使用。
- **美术与资源 —— [保留所有权利](LICENSE-ASSETS)。** "Numen" / "言出法随" 名称亦予保留。

基于 [MultiLoader Template](https://github.com/jaredlll08/MultiLoader-Template) 构建。
