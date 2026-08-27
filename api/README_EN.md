<div align="center">

# Numen API

### The engine under the Numen mod — and the stable API addons build against

*The heart of [Numen · 言出法随](https://github.com/Dwinovo/minecraft-numen): the AI companion is one cartridge; this is the console.*

[**English**](README_EN.md) · [简体中文](README.md)

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-62B47A?style=flat-square)
![Loaders](https://img.shields.io/badge/Loaders-common%20%7C%20Fabric%20%7C%20Forge%20%7C%20NeoForge-DE7C36?style=flat-square)
![Java](https://img.shields.io/badge/Java-21-007396?style=flat-square&logo=openjdk&logoColor=white)
![License](https://img.shields.io/badge/code-LGPL--3.0%20·%20API%20MIT-4B6BFB?style=flat-square)
![Version](https://img.shields.io/badge/version-0.1.2-A8731E?style=flat-square)

[**What it is**](#what-it-is) · [**Public API**](#public-api) · [**Depend on it**](#depend-on-it) · [**Build & publish**](#build--publish) · [**Ecosystem**](#ecosystem) · [**License**](#license)

</div>

---

## What it is

**numen-api** is the engine that powers the [Numen](https://github.com/Dwinovo/minecraft-numen) mod, packaged as a standalone project with a **stable public API**. The Numen mod bundles this engine; addons compile against it. Everything the companion can do — think, talk, move, mine, fight, remember — lives here; the mod is just one set of tools and skills stacked on top.

What the engine provides:

- **A client-side agent loop** (`EntityAgentLoop`) — hears a message, picks a tool, runs it, reads the result, decides the next move. The brain runs on the owner's own game client with the owner's own API key.
- **A tool contract** — `NumenTool` / `ToolRegistry` / `ToolCall` / `TaskResult`. A tool is any capability the companion can call; the engine schedules it and routes the result back into the conversation.
- **OpenAI-compatible LLM providers** — DeepSeek, DashScope (Qwen), OpenAI, Moonshot (Kimi), Zhipu (GLM), Minimax, SiliconFlow, Volcengine (Doubao). Transport is hand-rolled on the JDK's `HttpClient` + Gson, so there are **zero third-party runtime dependencies**.
- **Conversation memory** — persists across saves and auto-compacts (Claude-Code-style) when it grows long.
- **A companion body** — `NumenPlayer`, a server-side fake player (`ServerPlayer`). Every action runs through native player code paths, so redstone, mob AI, containers, and other mods treat it as a real player.
- **A skill system** — plain-text Markdown workflows that teach the companion how to play, loaded only when relevant.
- **Multi-loader** — one codebase across `common` / `fabric` / `forge` / `neoforge`.

---

## Public API

Addons touch the engine through three doors. Two feed a companion; one teaches it a new capability. Everything below is on the stable, published API surface.

### Door 1 — `NumenGateway`: feed the built-in brain

Hand a companion's **built-in brain** a message, verbatim. The engine splices it into the conversation at the next protocol-valid point, exactly as if the owner had typed it; the built-in LLM then decides what to do. This is how inbound bridges work — the QQ bridge turns a QQ message into an `enqueue`.

```java
import com.dwinovo.numen.api.NumenGateway;

// A message arrives from QQ / Discord / stream chat. Hand it to the companion's brain as-is.
boolean queued = NumenGateway.enqueue(companionUuid, "someone in QQ says: go mine me a stack of iron");
// queued == false only when the message is blank or that companion was never summoned this session.
```

Replies leave the companion by **calling a tool** (Door 3), not through a callback. Inbound = message queue; outbound = tool call. Safe to call from any thread; the enqueue is marshalled onto the client main thread.

### Door 2 — `NumenActuator`: drive the body from an external brain

Skip the built-in LLM entirely and drive a companion's **body** directly. The contract is **`acquire` → `invoke*` → `release`**: `acquire` pauses the built-in brain and frees the body so the two brains never fight over it; `invoke` runs any registered tool headlessly and returns a `CompletableFuture` of the result JSON; `release` hands control back. Every call is addressed to a companion UUID and bodies run tasks independently, so an external brain can acquire several companions and drive a **parallel fleet**. This is how the MCP server (numen-mcp) works.

```java
import com.dwinovo.numen.api.NumenActuator;
import java.util.UUID;

NumenActuator.companions().thenAccept(fleet -> {
    UUID body = fleet.get(0).uuid();
    NumenActuator.acquire(body)                                            // pause its built-in brain
        .thenCompose(ok -> NumenActuator.invoke(body, "move_to", "{\"x\":100,\"y\":64,\"z\":-200}"))
        .thenAccept(resultJson -> System.out.println(resultJson))          // a TaskResult JSON string
        .whenComplete((r, e) -> NumenActuator.release(body));              // always hand the body back
});
```

A headless `invoke` never touches the companion's conversation log — the external brain owns the context. Failures (unknown tool, bad args, a thrown tool) come back as a `TaskResult.fail` JSON, never an exceptional future. Any thread.

### Door 3 — `NumenTool` + `ToolRegistry.register`: teach a new capability

A tool is any capability the companion can call. Implement four methods and register the instance during mod init. There is deliberately **nothing about Minecraft on the contract** — a tool can drive the body, hook an external service, or call a web API; the engine only presents it to the LLM, delivers the call, and routes the result back.

```java
import com.dwinovo.numen.agent.tool.*;
import com.dwinovo.numen.task.TaskResult;
import java.util.Map;

public final class SendQqMessageTool implements NumenTool {
    public String name()        { return "send_qq_message"; }
    public String description() { return "Send a reply to the owner over QQ. Use when you have something to say to them."; }

    public Map<String, Object> parameterSchema() {
        return Map.of("type", "object",
                "properties", Map.of("text", Map.of("type", "string")),
                "required", java.util.List.of("text"));
    }

    public void invoke(ToolCall call) {
        String text = call.args().get("text").getAsString();
        // Do anything — run now, hop a thread, POST to an external service — then complete exactly once:
        myQqClient.send(text);
        call.complete(TaskResult.ok("sent to owner over QQ").toJson());
    }
}
```

```java
// during mod init:
ToolRegistry.register(new SendQqMessageTool());
```

`invoke` reports its result through the one verb, `ToolCall.complete(json)` — synchronously, or later after handing work off to another thread or the server body. `ToolRegistry.register` throws on a duplicate name and preserves registration order (stable tool order helps prompt caching).

### What is stable

The public API is the set of packages whose `package-info` declares them so, mirroring Applied Energistics 2's convention. Anything outside these packages — or annotated `@Internal` inside them — may change in any release.

| Package | Public types | Role |
|---|---|---|
| `com.dwinovo.numen.api` | `NumenGateway`, `NumenActuator` | the two doors that feed / drive a companion |
| `com.dwinovo.numen.agent.tool` | `NumenTool`, `ToolRegistry`, `ToolCall` | the tool contract + registration |
| `com.dwinovo.numen.agent.tool.api` | `ToolContext` | per-call context for a server-side tool |
| `com.dwinovo.numen.task` | `TaskResult` | the result envelope a tool hands back |
| `com.dwinovo.numen.entity` | `NumenPlayer` | the server-side companion body |

Everything else — providers, agent loop, memory, skill system, networking, UI — is `@Internal`. For a full worked reference, [numen-core](https://github.com/Dwinovo/minecraft-numen) builds its entire tool and skill set on exactly this surface, with no back doors.

---

## Depend on it

Artifacts are published to [numen-maven](https://github.com/Dwinovo/numen-maven). The coordinate carries the loader and Minecraft version:

```
com.dwinovo.numen:numen-api-<loader>-<mcversion>:<version>
```

Depend on the slim public-API jar (classifier `api`). At runtime the engine is **provided by the Numen mod**, which bundles it — an addon ships no engine code of its own.

```gradle
repositories {
    maven { url = 'https://raw.githubusercontent.com/Dwinovo/numen-maven/main' }
}

dependencies {
    // Fabric: the slim jar carries intermediary names, same as the full jar. Use
    // modCompileOnly so Loom maps it into your own namespace — yarn or mojmap.
    modCompileOnly "com.dwinovo.numen:numen-api-fabric-1.21.8:0.1.2:api"

    // NeoForge / Forge: runtime names are Mojang names, so plain compileOnly works.
    // compileOnly "com.dwinovo.numen:numen-api-neoforge-1.21.8:0.1.2:api"
}
```

Swap the loader (`fabric` / `forge` / `neoforge`) and Minecraft version to match your target. This branch builds `1.21.1` on Java 21.

`numen-ai` (model access and usage accounting) and `numen-ui` (widgets) come along transitively — `IToolSpec`, which `NumenTool` extends, lives in `numen-ai`, so without it your tool will not compile. Neither coordinate carries an MC-version suffix; every branch publishes the same bytes.

**To change engine mechanics themselves**, depend on core:

```gradle
dependencies {
    modImplementation "com.dwinovo.numen:numen-neoforge-1.21.8:0.1.2"
}
```

core pulls the matching `numen-api-*` in with it — no second line needed.

> Do not depend on either family's `-common` coordinate (`numen-api-common-*` / `numen-common-*`). They hold only the cross-loader code: no loader entrypoint; `numen-api-common-*` also has no language files, and `numen-common-*` does not nest the engine. They compile, and then do nothing in game. **The loader-named coordinate is the complete one.**

---

## Build & publish

Standard MultiLoader-Template layout (`common` + per-loader subprojects).

```bash
./gradlew build         # build every loader
./gradlew datagenAll    # run data generation for both families, both loaders
./gradlew publishAll    # publish api + core + ai + ui
```

The target repo comes from `local_maven_url` in `gradle.properties`; override with `-Plocal_maven_url=...`. `datagenAll` / `publishAll` pick the branch's second loader (Forge or NeoForge) themselves — callers never need to know which.

Artifacts fall into three kinds: the full jar (runtime, bundled by the Numen mod), the slim `api`-classifier jar (what addons `compileOnly`), and sources / javadoc.

**Real publishing is done by CI** — push a `v*` tag or dispatch the `Publish Maven Artifacts` workflow; it checks out `numen-maven` and pushes the artifacts there. Versions are fixed releases, written once per coordinate: to ship something new, bump the version.

---

## Ecosystem

**Numen** ([minecraft-numen](https://github.com/Dwinovo/minecraft-numen)) is the mod — the AI companion. It runs on the **[numen-api](https://github.com/Dwinovo/numen-api)** engine (published through **[numen-maven](https://github.com/Dwinovo/numen-maven)**), which exposes a small public API. Two things build on it: *(this repo)*

**Extend a companion** — its own brain stays in charge:
- **Bridges** carry an outside channel into a companion: a message arrives, and the companion decides what to do. Built on `NumenGateway`. → **[numen-qq-bridge](https://github.com/Dwinovo/numen-qq-bridge)** (QQ), with more to come.
- **Skills** teach a companion how to behave — markdown loaded into its context. Bundled with Numen, or community-written.

**Expose Numen** — hand the controls to an outside brain:
- **[numen-mcp](https://github.com/Dwinovo/numen-mcp)** is a Model Context Protocol server: any external agent (like Claude) drives companions directly. Built on `NumenActuator`.

---

## License

- **Source code — [LGPL-3.0](LICENSE).** Forks you distribute must stay open under the same license.
- **Public integration API — [MIT](LICENSE-API).** The surface addons and MCP bridges code against (classes under `com.dwinovo.numen.api`) is MIT, so mod-compat can be built freely, including in proprietary projects.
- **Art & assets — [All Rights Reserved](LICENSE-ASSETS).** The names "Numen" / "言出法随" are reserved.

Built on the [MultiLoader Template](https://github.com/jaredlll08/MultiLoader-Template).
