# Numen · 言出法随

### An AI harness that lets large models truly live inside Minecraft

*言出法随 (yán chū fǎ suí) — speak it, and it comes true.*

[English](README.md) · [简体中文](README_CN.md)

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20~%2026.1.2-62B47A?style=flat-square)
![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-DE7C36?style=flat-square)
![Java](https://img.shields.io/badge/Java-17%20%7C%2021%20%7C%2025-007396?style=flat-square&logo=openjdk&logoColor=white)
![License](https://img.shields.io/badge/code-LGPL--3.0-4B6BFB?style=flat-square)
![Status](https://img.shields.io/badge/status-Beta-A8731E?style=flat-square)

---

We already have AI that can chat, write code, and reason. But it all lives in a text box — no body, no world; it finishes a task and forgets it ever happened.

**Numen wants to let large models out of the chat box, and into a world they actually live in.** And Minecraft is about as close to "a whole world" as you can get.

Give it a body that mines, fights, and wires redstone; give it eyes that trace an ore vein and see straight through a machine's shell; and give it one promise — **言出法随**.

Say *"go grab me a stack of iron,"* and it really heads underground, paths through the dark, swings the pickaxe, and comes back loaded — then asks if you want it smelted. Say *"build a hut where I'm standing,"* and the foundation rises block by block. **Every word you say turns into something that actually happens in the world.** In any language your model speaks.

```
You:    go grab me a stack of iron
Numen:  On it. Heading underground to find iron.
        ▸ 4 steps · locate_biome · move_to · auto_mine · collect_items   ✔
Numen:  Got 64 raw iron — want me to smelt it?
```

## Vision

Numen is already a real **agent**: give it a goal, and it decomposes it, plans it, does the work, and corrects itself from failure. **It can already do about 80% of what a player does in vanilla survival** — from punching the first tree, through mining, building, fighting, smelting, and stockpiling, all the way into the End and through the dragon fight.

And that's the starting point.

Minecraft's real universe is in the mods: Create's gear trains, Applied Energistics' storage networks, Mekanism's factory lines — players pour countless hours into these systems. **Numen's next goal is to let AI play this entire universe with its own hands**: say *"build me an automated iron factory,"* and it stands one up for real, across Create and AE2.

Beyond the modded universe lies the **real world**. To the AI, "mine a block of iron" and "post a tweet" are the same kind of thing — each is just a hand (the "Reach" section below explains why). A creeper blows a hole in your base at 3 a.m., and it pings you on **Discord**: *"Trouble at home — I've already patched the wall."* It finishes a castle, screenshots it, writes a caption, and **posts it** to show off. Put it on stream and pipe the chat in — viewers command it one message at a time. And through MCP, the AI outside the game — say, Claude on your desktop — reaches back *into* Minecraft and treats the world as its sandbox. The bridge runs both ways. **Minecraft is the closest thing we have to a whole world, and Numen is wiring that world to reality.**

The engine holding all of this up now lives as its own project: [numen-api](https://github.com/Dwinovo/numen-api) — the agent loop, LLM transport, tool scheduling, skill system, and fake-player body, packaged as a foundation any mod can stand on. The mod you're installing is the **first agent** running on that engine; the same foundation can grow haggling NPCs, villagers with daily routines, server stewards that read player chat, embodied-AI benchmarks that use the whole world as an exam hall… **What we're building is Minecraft's AI layer**: every mod, every playstyle, able to grow intelligence of its own.

It's a long road, and we've only just set out. But the direction couldn't be clearer.

> **言出法随 / Reach** — your intent reaches the world; the AI's ability reaches every mod.

## How it works

That companion you talk to is just a body this system puts on. What makes *"言出法随"* real is the engineering underneath — we call it the **Harness**.

In AI engineering, a harness is the scaffolding around a model: it wires the model into a world it can perceive, act in, and learn from. **Numen is that scaffolding, built for Minecraft.** Four parts:

- 🧍 **A body — a real player.** The companion is a server-side *fake player* (`ServerPlayer`); every action runs through native player code paths. Which means it plays by the same rules as redstone, mob AI, containers, and other people's mods — by birth.
- 👁️ **Eyes — a perception API.** Its own and the world's status, ranged block and entity scans, recipe lookups, single-block inspection — down to an **x-ray** that reads what a machine **holds** (items, fluid, energy) **without opening its GUI**.
- ✋ **Hands — an action API.** Move, mine, place, fight (native melee + bow), drive any container/machine GUI, manage inventory, locate structures and biomes.
- 🔁 **A teaching feedback loop.** Every tool result — success or failure — is written as a line that **teaches the model how Minecraft works**. *"Can't mine iron ore bare-handed — equip at least a stone pickaxe"* is this loop doing its job. This is the essence of an agent: use tools to pull **ground truth** from the environment, then decide the next move. That's how the model learns to play.

Above all this, **the brain runs on your own machine**: the agent loop calls the LLM from the owner's client, with the owner's API key — each player pays their own way, the server owner doesn't foot everyone's bill, and you never hand over your key. And it ships with **zero third-party runtime dependencies** — LLM transport is just the JDK's `HttpClient` + Gson (Java's AI ecosystem being what it is — you know how it goes — I had to hand-roll it).

The scaffolding now comes in two genuine layers: the engine ([numen-api](https://github.com/Dwinovo/numen-api)) does scheduling only — it shows the tools to the model, delivers the calls, and writes results back into the conversation, staying fully blind to what a tool actually does — while the mod you install (**numen-core**) is the content pack running on top: nearly thirty tools and ten bundled skills, assembled into a complete agent. The layering means one thing: **every ounce of capability Numen has, third-party developers get too.**

## Reach

Beating vanilla is just the appetizer. What we really want to chew through is the mods — that's the deep end of Minecraft.

Lucky for us, Numen's body is a real player, so *physically* it gets along with mods out of the box: a mod's machines, chests, and contraptions — it can mine them, place them, right-click them, pull items from their slots, **no per-mod adapter required**. It can even see through the shell and read how many items, how much fluid, and how much energy most machines, tanks, and batteries are holding.

But "able to reach out" isn't "able to understand." Does the AI know what a Mechanical Press is for? How an AE2 network should be wired? Delivering that understanding to the model takes two instruments — the very same two Claude itself uses to reach the real world:

- 🔌 **MCP — connect.** A compatibility module that wires a mod's inner world, structured, into the AI's senses and hands. In Anthropic's own words: this is **handing it a hammer**.
- 📖 **Skill — coach.** A plain-text workflow — zero code, anyone can write one — that teaches the AI how to put a capability to good use. This is **showing it how to swing that hammer to drive a nail**.

One grants the capability, the other the craft — and better yet, **they stack**:

> Some mods need only a Skill: the vanilla hands already reach far enough; all it's missing is the manual.
> For the self-contained universes — Create, AE2, Mekanism — wire it in with an MCP first, then teach it to play with a Skill. **Connect + coach: together, that's 通达 (reach).**

Better still, these two instruments are **in everyone's hands now**. With the engine split out, any mod author can register tools and bundle skills right inside their own jar — the way you'd write a JEI recipe page today, you write the AI a *"here's how my mod plays."* Compat waits on nobody's schedule; the ecosystem grows itself (see "For developers" below).

And that hand reaches further than mods. Every one of the AI's "hands" (tools) follows one contract, and the contract deliberately contains **not a single Minecraft concept**: a hand can reach into an ore vein, or out onto the internet — Discord, Twitter/X, live-stream chat, smart-home gear. Every scene in the "Vision" above starts as one ordinary tool.

Every mod you can name, every service with a network cable — the AI can play it. It's a big promise, but every brick is going up.

## Quick start

Whatever version you play, Numen is already there: **Minecraft 1.20.1 through 26.1.2 — 11 versions, across Fabric / Forge / NeoForge** — from the servers still holding the line on 1.20.1 to the freshly baked 26.1.2.

1. **Install** the mod (plus [Fabric API](https://modrinth.com/mod/fabric-api) if you're on Fabric) and launch once.
2. **Add your API key.** Press **`G`** → **Settings**, pick a provider, and paste your own key (OpenAI, DeepSeek, Kimi, Qwen, Doubao… any OpenAI-compatible backend works).
3. **Summon a companion.** Click the **`+`** in the panel's left rail, give it a name, hit Enter.
4. **Click its avatar to chat**, and tell it what to do. The rest is on it.

> The panel (press `G`) has three tabs: **Chat** (conversation + a live plan board), **Items** (a read-only character sheet styled like the vanilla inventory), and **Settings** (key and model). The left rail *is* your companion roster — click an avatar to switch, **`+`** to summon, **`✕`** to dismiss; you barely need commands at all. A small avatar HUD hugs the left screen edge, too — when a companion speaks, its avatar and a speech bubble slide out together.

## What it can do

Give it an intent and it breaks it into dozens of actions and runs them end to end — planning the route, picking the right tool, judging distance, improvising as it goes — all without you watching over its shoulder. **That's what an agent is supposed to look like: you name the goal, it owns the process.**

- ⛏️ **Real work** — mine, chop, gather, build, place and break with precision, hand-craft by recipe, smelt in furnaces, sort loot into chests.
- 🧭 **Real movement** — a server-side navigation engine: it bridges gaps, pillars up, tunnels through, staircases down, and swims. *"Go to that coordinate"* is meant literally — even if that means digging all the way to diamond level, or carving a way back up from the bottom of a shaft.
- ⚔️ **Real combat** — native player melee and bow: real cooldowns, real crits; it eats when hurt and swims to shore before it drowns.
- 🔭 **Real perception** — scan blocks, scan entities, check status, look up recipes, locate any structure or biome, even x-ray what's inside a machine without opening its GUI.
- 🧠 **Real memory** — conversations persist across saves and auto-compact when they grow long; it remembers the crafting tables, furnaces, and chests it has used, and walks back to them instead of building new ones. Death is recoverable: vanilla death drops as usual, then it respawns by your side after a moment.

Nearly thirty tools like these make up its hands and eyes *right now*. And its abilities keep growing — through the very two instruments of "Reach" above:

- 📖 **Write a Skill to coach it.** Markdown workflows under `config/numen/skills/`, loaded only when relevant to keep the prompt lean. It ships with a full set of guides for the whole vanilla end-game (the Nether, blaze rods, ender pearls, the stronghold, the dragon fight…). Edit one, or write your own, to teach it your base's rules — or a whole new mod's playbook.
- 🔌 **Plug in an MCP to extend it.** A compatibility module wires a mod's inner world, structured, into its senses and hands — so the boundary of what it *can do* grows together with the entire modded ecosystem.

Stack the two, and it goes from mastering vanilla all the way to mastering the entire modded universe.

## For developers

Numen's engine is a standalone project you can depend on: [numen-api](https://github.com/Dwinovo/numen-api). What it opens to you is the very foundation numen-core stands on — our 28 tools and 10 skills are written entirely against the public API, **with no private backdoor**:

- 🔧 **Register a tool.** Implement `NumenTool`'s four methods (`name` / `description` / `parameterSchema` / `invoke`), drop it into the `ToolRegistry`, and your mod's capability grows onto the AI's hands. The contract deliberately contains **no Minecraft concepts** — how a call gets done (sync, async, your own packets, an external service) is entirely the tool's own business.
- 📖 **Bundle skills in your jar.** One call — `SkillRegistry.instance().declareBundled(root)` — turns your jar's `/skills` directory into built-in skills: players install your mod and the AI already knows how to play it. A same-named skill under the player's `config/numen/skills/` always wins, so the final say stays with the player.
- 🏗️ **Or build a different AI entirely.** The fake-player body, agent loop, LLM transport, and full UI are all public foundation — AI NPCs, story characters, server stewards, whatever you can imagine.

```gradle
repositories { maven { url = 'https://raw.githubusercontent.com/Dwinovo/numen-maven/main' } }
dependencies  { modImplementation "com.dwinovo.numen:numen-api-fabric-1.21.1:0.0.1-SNAPSHOT" }
```

The public integration API is **MIT**-licensed — write tools, skills, and compat without LGPL strings attached. Getting-started guide, full examples, and the version matrix live in [numen-api's README](https://github.com/Dwinovo/numen-api).

## Roadmap

Numen is young, but two big slabs of the foundation are already poured:

- ✅ **Every version, every loader.** Minecraft 1.20.1 through 26.1.2 — 11 versions across Fabric / Forge / NeoForge. Done.
- ✅ **The engine, split out (numen-api).** The tool contract and skill registration are open to every developer.

Where we're headed next:

- **Connect the big mods (MCP).** For the self-contained tech universes — Create, AE2, Mekanism — dedicated MCP compatibility modules will wire their inner structure into the AI's senses and hands. The capability-based `inspect_block_storage` x-ray is the first brick.
- **Grow a library of Skills.** Make "teach the AI a new mod" as simple as writing one Markdown file, built and shared by the community — and stacked with MCP, it carries the AI from *connected* all the way to *fluent*.
- **Wire in the real world.** Discord reports, its own social account, live-chat command, the two-way MCP bridge — its hands, reaching outside the game.
- **Take the public API to 1.0.** As the first third-party integrations land, harden the tool and skill contracts to where we can promise stability.
- **Play more like a veteran.** Deeper memory of the world, and longer-horizon planning.

Contributions, skill submissions, and compat experiments are all welcome. This is an open blueprint — **and an invitation being cashed in, one commit at a time.**

---

Want to build it yourself, see the full tool list, or read the architecture? It's all in the source — start under `common/src/main/java/com/dwinovo/numen/`.

**Licensing** (modeled on AE2): the source code is [LGPL-3.0](https://github.com/Dwinovo/minecraft-numen/blob/HEAD/LICENSE) — forks you distribute must stay open under the same license. The public integration API (what compatibility modules / MCP bridges code against, shipped with [numen-api](https://github.com/Dwinovo/numen-api)) is [MIT](https://github.com/Dwinovo/minecraft-numen/blob/HEAD/LICENSE-API), so anyone can build mod-compat freely. The art & assets are [All Rights Reserved](https://github.com/Dwinovo/minecraft-numen/blob/HEAD/LICENSE-ASSETS), and the names "Numen" / "言出法随" are reserved. Built on the [MultiLoader Template](https://github.com/jaredlll08/MultiLoader-Template).

The **planning layer** implements techniques from the heuristic-search literature: weighted A*, budgeted partial-path commitment, and RTAA*-style cross-segment heuristic learning (Koenig & Likhachev, *Real-Time Adaptive A\**, AAMAS 2006), with game-independent unit tests. The **execution layer** differs from [Baritone](https://github.com/cabaletta/baritone) fundamentally in where it runs: Baritone is a client-side mod driving the local player, while Numen drives a **server-side fake player** — movement, digging and placement all go through server APIs. It draws on Baritone's publicly documented mechanics for design ideas only; **no source was copied, ported, or adapted from it**. Numen's code is licensed LGPL-3.0 of its own accord; that choice is not a consequence of Baritone (which is also LGPL-3.0).
