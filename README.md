<div align="center">

# Numen · 言出法随

### An AI harness that lets large models truly live inside Minecraft

*言出法随 (yán chū fǎ suí) — speak it, and it comes true.*

[**English**](README.md) · [简体中文](README_CN.md)

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-62B47A?style=flat-square)
![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20NeoForge-DE7C36?style=flat-square)
![Java](https://img.shields.io/badge/Java-21-007396?style=flat-square&logo=openjdk&logoColor=white)
![License](https://img.shields.io/badge/code-LGPL--3.0-4B6BFB?style=flat-square)
![Status](https://img.shields.io/badge/status-early%20%2F%20vision-A8731E?style=flat-square)

[**Vision**](#vision) · [**How it works**](#how-it-works) · [**Reach**](#reach) · [**Quick start**](#quick-start) · [**What it can do**](#what-it-can-do) · [**Roadmap**](#roadmap)

</div>

<!-- Tip: drop a gameplay GIF/screenshot here once you have one — it sells the project faster than any paragraph. -->

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

Getting AI to beat vanilla is only the start.

Minecraft's real universe is in the mods: Create's gear trains, Applied Energistics' storage networks, Mekanism's factory lines — players pour countless hours into these systems. **Numen's ultimate goal is to let AI reach through this entire universe** — not just understand it, but actually play it: say *"build me an automated iron factory,"* and it can stand one up for real, across Create and AE2.

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

## Reach

Beating vanilla is just the appetizer. What we really want to chew through is the mods — that's the deep end of Minecraft.

Lucky for us, Numen's body is a real player, so *physically* it gets along with mods out of the box: a mod's machines, chests, and contraptions — it can mine them, place them, right-click them, pull items from their slots, **no per-mod adapter required**. It can even see through the shell and read how many items, how much fluid, and how much energy most machines, tanks, and batteries are holding.

But "able to reach out" isn't "able to understand." Does the AI know what a Mechanical Press is for? How an AE2 network should be wired? Delivering that understanding to the model takes two instruments — the very same two Claude itself uses to reach the real world:

- 🔌 **MCP — connect.** A compatibility module that wires a mod's inner world, structured, into the AI's senses and hands. In Anthropic's own words: this is **handing it a hammer**.
- 📖 **Skill — coach.** A plain-text [workflow](#what-it-can-do) — zero code, anyone can write one — that teaches the AI how to put a capability to good use. This is **showing it how to swing that hammer to drive a nail**.

One grants the capability, the other the craft — and better yet, **they stack**:

> Some mods need only a Skill: the vanilla hands already reach far enough; all it's missing is the manual.
> For the self-contained universes — Create, AE2, Mekanism — wire it in with an MCP first, then teach it to play with a Skill. **Connect + coach: together, that's 通达 (reach).**

Every mod you can name, the AI can play. It's a big promise — but every brick is going up.

## Quick start

1. **Install** the mod (plus [Fabric API](https://modrinth.com/mod/fabric-api) if you're on Fabric) and launch once.
2. **Add your API key.** Press **`G`** → **Settings**, pick a provider, and paste your own key (OpenAI, DeepSeek, Kimi, Qwen, Doubao… any OpenAI-compatible backend works).
3. **Summon a companion.** Click the **`+`** in the panel's left rail, give it a name, hit Enter.
4. **Click its avatar to chat**, and tell it what to do. The rest is on it.

> The panel (press `G`) has three tabs: **Chat** (conversation + a live plan board), **Items** (a read-only character sheet styled like the vanilla inventory), and **Settings** (key and model). The left rail *is* your companion roster — click an avatar to switch, **`+`** to summon, **`✕`** to dismiss; you barely need commands at all. A small avatar HUD hugs the left screen edge, too — when a companion speaks, its avatar and a speech bubble slide out together.

## What it can do

Give it an intent and it breaks it into dozens of actions and runs them end to end — planning the route, picking the right tool, judging distance, improvising as it goes — all without you watching over its shoulder.

- ⛏️ **Real work** — mine, chop, gather, build, place and break with precision, hand-craft by recipe, smelt in furnaces, sort loot into chests.
- 🧭 **Real movement** — a pathfinder that takes its cues from Baritone, rewritten for the companion: it bridges gaps, pillars up, tunnels through, staircases down, and swims. *"Go to that coordinate"* is meant literally — even if that means digging all the way to diamond level.
- ⚔️ **Real combat** — native player melee and bow: real cooldowns, real crits; it eats when hurt and swims to shore before it drowns.
- 🔭 **Real perception** — scan blocks, scan entities, check status, look up recipes, locate any structure or biome, even x-ray what's inside a machine without opening its GUI.
- 🧠 **Real memory** — conversations persist across saves and auto-compact when they grow long; it remembers the crafting tables, furnaces, and chests it has used, and walks back to them instead of building new ones. Death is recoverable: vanilla death drops as usual, then it respawns by your side after a moment.

Nearly thirty tools like these make up its hands and eyes *right now*. And its abilities keep growing — through the very two instruments of [Reach](#reach):

- 📖 **Write a Skill to coach it.** Markdown workflows under `config/numen/skills/`, loaded only when relevant to keep the prompt lean. It ships with a full set of guides for the whole vanilla end-game (the Nether, blaze rods, ender pearls, the stronghold, the dragon fight…). Edit one, or write your own, to teach it your base's rules — or a whole new mod's playbook.
- 🔌 **Plug in an MCP to extend it.** A compatibility module wires a mod's inner world, structured, into its senses and hands — so the boundary of what it *can do* grows together with the entire modded ecosystem.

Stack the two, and it goes from mastering vanilla all the way to mastering the entire modded universe.

## Roadmap

Numen is young. Vanilla play already runs smoothly; the bigger story — letting AI **reach through the entire modded universe** — is being written one line at a time. Where we're headed:

- **Connect the big mods (MCP).** For the self-contained tech universes — Create, AE2, Mekanism — dedicated MCP compatibility modules will wire their inner structure into the AI's senses and hands. The capability-based `inspect_block_storage` x-ray is the first brick.
- **Grow a library of Skills.** Make "teach the AI a new mod" as simple as writing one Markdown file, built and shared by the community — and stacked with MCP, it carries the AI from *connected* all the way to *fluent*.
- **Play more like a veteran.** Deeper memory of the world, and longer-horizon planning.

Contributions, skill submissions, and compat experiments are all welcome. This is an open blueprint — **and an invitation being cashed in, one commit at a time.**

---

<div align="center">

<sub>Want to build it yourself, see the full tool list, or read the architecture? It's all in the source — start under <code>common/src/main/java/com/dwinovo/numen/</code>.</sub>

<sub><b>Licensing</b> (modeled on AE2): the source code is <a href="LICENSE">LGPL-3.0</a> — forks you distribute must stay open under the same license. The forthcoming public integration API (what compatibility modules / MCP bridges code against) is <a href="LICENSE-API">MIT</a>, so anyone can build mod-compat freely. The art &amp; assets are <a href="LICENSE-ASSETS">All Rights Reserved</a>, and the names "Numen" / "言出法随" are reserved. Built on the <a href="https://github.com/jaredlll08/MultiLoader-Template">MultiLoader Template</a>.</sub>

<sub>The pathfinder draws on <a href="https://github.com/cabaletta/baritone">Baritone</a> for design ideas only, and is a fully independent rewrite for a <b>server-side (fake-player)</b> setting — <b>no source was copied, ported, or adapted from it</b>. Numen's code is licensed LGPL-3.0 of its own accord; that choice is not a consequence of Baritone (which is also LGPL-3.0).</sub>

</div>
