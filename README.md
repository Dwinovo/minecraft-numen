<div align="center">

# Numen · 言出法随

### An AI companion that lives inside Minecraft — summon it, talk to it, watch it work.

*言出法随 (yán chū fǎ suí) — speak, and it is done.*

[**English**](README.md) · [简体中文](README_ZH.md)

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20~%2026.1.2-62B47A?style=flat-square)
![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-DE7C36?style=flat-square)
![Java](https://img.shields.io/badge/Java-17%20%7C%2021%20%7C%2025-007396?style=flat-square&logo=openjdk&logoColor=white)
![Version](https://img.shields.io/badge/version-0.0.4-4B6BFB?style=flat-square)
![License](https://img.shields.io/badge/code-LGPL--3.0-A8731E?style=flat-square)

[**What it is**](#what-it-is) · [**Features**](#features) · [**Install**](#install) · [**Quick start**](#quick-start) · [**Skills--memory**](#skills--memory) · [**Ecosystem**](#ecosystem) · [**For developers**](#for-developers) · [**License**](#license)

</div>

<!-- Tip: drop a gameplay GIF/screenshot here once you have one — it sells the mod faster than any paragraph. -->

---

## What it is

Numen puts an **AI companion** inside your world. You summon it, you talk to it in plain language, and it plans its own steps and does the work — mining, building, farming, fighting, crafting.

Say *"go grab me a stack of iron,"* and it heads underground, paths through the dark, swings the pickaxe, and comes back loaded — then asks if you want it smelted. Say *"build a hut where I'm standing,"* and the foundation rises block by block. Every word you say turns into something that actually happens in the world — in any language your model speaks.

```
You:    go grab me a stack of iron
Numen:  On it. Heading underground to find iron.
        ▸ 4 steps · locate_biome · move_to · auto_mine · collect_items   ✔
Numen:  Got 64 raw iron — want me to smelt it?
```

**It's a real agent.** Give it a goal and it decomposes it, plans it, picks the right tool, judges distance, improvises, and corrects itself from failure — no hand-holding. It already handles about 80% of what a player does in vanilla survival, from punching the first tree all the way to the End and the dragon fight.

**It natively understands modded content.** The companion is a genuine server-side player, so it interacts with the world through native player code — the same paths vanilla, redstone, mob AI, and other people's mods run on. It mines modded blocks, right-clicks modded machines, pulls items from their slots, and opens modded GUIs with **no per-mod adapter**. It can even read what a machine *holds* — items, fluid, energy — without opening its interface. That's the line a protocol-level bot can't cross: packet-driven bots don't know a Mechanical Press from a Casing. Numen does, because it plays by the same rules the mod does. Create, AE2, Mekanism — it can reach into all of them.

## Features

Give it an intent; it breaks it into dozens of actions and runs them end to end.

| | |
|---|---|
| ⛏️ **Real work** | Mine, chop, gather, build, place and break with precision, craft by recipe, smelt in furnaces, sort loot into chests. |
| 🧭 **Real movement** | A pathfinder that takes design cues from Baritone, rewritten for the companion: it bridges gaps, pillars up, tunnels through, staircases down, and swims. *"Go to that coordinate"* is meant literally — even if it has to dig to diamond level. |
| ⚔️ **Real combat** | Native player melee and bow — real cooldowns, real crits. It eats when hurt and swims to shore before it drowns. Death is recoverable: vanilla drops as usual, then it respawns by your side. |
| 🔭 **Real perception** | Scan blocks and entities, check status, look up recipes, locate any structure or biome, and x-ray what's inside a machine without opening its GUI. |
| 🧠 **Real memory** | Conversations persist across saves and auto-compact when they grow long (Claude-Code-style compaction). It remembers the crafting tables, furnaces, and chests it has used, and walks back to them instead of building new ones. |
| 🧩 **Modded-native** | A real `ServerPlayer` body means modded blocks, items, and GUIs work out of the box — no per-mod adapter. |

Nearly thirty tools make up its hands and eyes today, and the set keeps growing through **skills** and **addons** (below).

## Install

Numen is published on **[CurseForge](https://www.curseforge.com/) · [Modrinth](https://modrinth.com/) · [GitHub](https://github.com/Dwinovo/minecraft-numen)**. Whatever version you play, it's there — **11 Minecraft versions, one git branch per version**.

| Minecraft | Loaders |
|---|---|
| 1.20.1 · 1.20.2 · 1.20.4 | Fabric · Forge |
| 1.20.6 · 1.21.1 · 1.21.4 · 1.21.5 · 1.21.8 · 1.21.10 · 1.21.11 · 26.1.2 | Fabric · NeoForge |

1. **Install** the mod (plus [Fabric API](https://modrinth.com/mod/fabric-api) if you're on Fabric) and launch once.
2. **Bring your own LLM key.** Numen ships no AI service of its own — you supply an **OpenAI-compatible API key**. OpenAI, DeepSeek, Kimi, Qwen, Doubao… any OpenAI-compatible backend works.

The brain runs on your own machine: the agent loop calls the LLM from your client, with your key. Each player pays their own way, the server owner never handles anyone's key, and you never hand yours over. Numen carries **zero third-party runtime dependencies** — LLM transport is just the JDK's `HttpClient` plus Gson.

## Quick start

1. **Add your key.** Press **`G`** → **Settings**, pick a provider, paste your key, choose a model.
2. **Summon a companion.** Click the **`+`** in the panel's left rail, give it a name, hit Enter.
3. **Click its avatar to chat**, and tell it what to do. The rest is on it.

> The panel (press `G`) has three tabs: **Chat** (conversation + a live plan board), **Items** (a read-only character sheet styled like the vanilla inventory), and **Settings** (key and model). The left rail is your companion roster — click an avatar to switch, **`+`** to summon, **`✕`** to dismiss. A small avatar HUD hugs the left screen edge; when a companion speaks, its avatar and a speech bubble slide out together.

## Skills & memory

Numen's abilities grow two ways beyond its built-in tools:

- 📖 **Skills — coach it.** A skill is a plain-text Markdown workflow (zero code, anyone can write one) under `config/numen/skills/`, loaded only when it's relevant so the prompt stays lean. Numen ships with a full set of built-in guides for the vanilla end-game — the Nether, blaze rods, ender pearls, the stronghold, the dragon fight. Edit one, or write your own, to teach it your base's rules or a whole new mod's playbook. A same-named skill in your config folder always wins.
- 🧠 **Memory that lasts.** Conversations persist across saves and auto-compact when they run long, so a companion stays coherent over a long play session instead of forgetting what it just did.

Its capabilities also extend through the **addon ecosystem** below — every ounce of capability Numen itself has is written against the same public API that addons use.

## Ecosystem

Numen is built on the **numen-api** engine, bundled inside the mod. The engine and its companions are separate open projects you can build on:

| Project | What it is |
|---|---|
| [**numen-api**](https://github.com/Dwinovo/numen-api) | The engine under Numen — agent loop, tool scheduling, LLM providers, and the public API for addons (`NumenGateway` + `NumenActuator`). Register your own tools and bundle skills right inside your jar. |
| [**numen-maven**](https://github.com/Dwinovo/numen-maven) | The Maven repository hosting numen-api artifacts, so you can depend on the engine from your own build. |
| [**numen-qq-mcp**](https://github.com/Dwinovo/numen-qq-mcp) | **Numen QQ Bridge** — command your companion straight from QQ chat, without being at the keyboard. |
| [**numen-mcp**](https://github.com/Dwinovo/numen-mcp) | **Numen MCP** — drive your companions from an external agent, like Claude on your desktop, over the Model Context Protocol. The bridge runs both ways: the world becomes the outside agent's sandbox. |

## For developers

Every tool and skill Numen ships is written entirely against the public API — there's no private backdoor. Because the engine ([numen-api](https://github.com/Dwinovo/numen-api)) is split out, any mod author gets the same power:

- 🔧 **Register a tool** through `NumenGateway` and your mod's capability grows onto the AI's hands. The tool contract deliberately contains **no Minecraft concepts** — how a call gets done (sync, async, your own packets, an external web service) is entirely the tool's own business, which is exactly why the same API reaches Discord, QQ, and MCP as easily as it reaches an ore vein.
- 📖 **Bundle skills in your jar** — one call turns your jar's `/skills` directory into built-in skills, so players install your mod and the AI already knows how to play it.
- 🏗️ **Or build a different AI entirely** on the same foundation — AI NPCs, story characters, server stewards.

```gradle
repositories { maven { url = 'https://raw.githubusercontent.com/Dwinovo/numen-maven/main' } }
dependencies  { modImplementation "com.dwinovo.numen:numen-api-fabric-1.21.1:<version>" }
```

The public integration API is **MIT**-licensed — write tools, skills, and compat without LGPL strings attached. The getting-started guide, full examples, and version matrix live in [numen-api's README](https://github.com/Dwinovo/numen-api).

## License

Licensing follows the AE2 model — different layers, different terms:

| Layer | License |
|---|---|
| Source code | [**LGPL-3.0**](LICENSE) — forks you distribute must stay open under the same license. |
| Public integration API (what addons / MCP bridges code against, shipped with [numen-api](https://github.com/Dwinovo/numen-api)) | [**MIT**](LICENSE-API) — build mod-compat freely, including in proprietary projects. |
| Art & assets | [**All Rights Reserved**](LICENSE-ASSETS) — the names "Numen" / "言出法随" and the branding are reserved. |

Built on the [MultiLoader Template](https://github.com/jaredlll08/MultiLoader-Template). The pathfinder draws on [Baritone](https://github.com/cabaletta/baritone) for design ideas only and is a fully independent rewrite for a server-side (fake-player) setting — no source was copied, ported, or adapted from it.

---

<div align="center">

<sub>Want to build it yourself, see the full tool list, or read the architecture? It's all in the source — start under <code>common/src/main/java/com/dwinovo/numen/</code>.</sub>

<br><sub><b>言出法随</b> — your intent reaches the world; the AI's ability reaches every mod.</sub>

</div>
