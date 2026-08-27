<div align="center">

# Numen · 言出法随

### An AI companion that lives in your world

*言出法随 (yán chū fǎ suí) — you say it, and it becomes real.*

[**English**](README_EN.md) · [简体中文](README.md)

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20~%2026.1.2-62B47A?style=flat-square)
![Loaders](https://img.shields.io/badge/Loaders-Fabric%20%7C%20Forge%20%7C%20NeoForge-DE7C36?style=flat-square)
![Java](https://img.shields.io/badge/Java-17%20%7C%2021%20%7C%2025-007396?style=flat-square&logo=openjdk&logoColor=white)
![License](https://img.shields.io/badge/code-LGPL--3.0-A8731E?style=flat-square)

[**Quick start**](#quick-start) · [**What it can do**](#what-it-can-do) · [**Extending it**](#extending-it) · [**External brain**](#external-brain) · [**Design**](#design) · [**FAQ**](#faq) · [**For developers**](#for-developers) · [**Roadmap**](#roadmap)

</div>

<p align="center">
  <img src="docs/numen-demo.gif" alt="Numen in action: chopping, mining, crafting, fighting, driving Mekanism machines" width="640">
</p>

---

Numen puts an AI companion in your world. Tell it what you want in plain language — type it, or hold `V` and just say it, in any language your model speaks — and it breaks the goal into dozens of steps, plans a route, picks the right tool, adapts when things go wrong, and gets it done.

It isn't a chatbot NPC. It's a real player on the server: it mines, walks, swings, opens chests, and every action goes through the vanilla player code path — which means it plays by the same rules as redstone, mob AI, and everyone else's mods.

```
You:    Go get me a stack of iron
Numen:  On it. Heading underground.
        ▸ 4 steps · locate_biome · move_to · auto_mine · collect_items   ✔
Numen:  Got 64 raw iron — want me to smelt it?
```

## Quick start

1. **Install** the mod (Fabric also needs [Fabric API](https://modrinth.com/mod/fabric-api)) and launch once.
2. **Add an API key.** Press **`N`** → **Settings** → **Model**, pick a provider, paste your own key.
3. **Summon a companion.** Click **`+`** in the panel's left rail, name it, hit enter.
4. **Click its portrait and start talking.**

> **Models**: ten presets built in — OpenAI, Anthropic, DeepSeek, Kimi, Zhipu GLM, Doubao, Qwen, MiniMax, SiliconFlow, OpenRouter — plus any OpenAI-compatible backend you point it at. Anthropic runs on its own native protocol, not a compatibility shim.

> **The panel**: `N` opens three tabs — **Chat** (conversation plus a live plan view), **Items** (a read-only character sheet styled after the vanilla inventory), and **Settings**. The left rail is your companion roster: click a portrait to switch, **`+`** to summon, **`✕`** to dismiss. You rarely need to type a command. There's also a small portrait HUD on the left edge that slides out when a companion speaks. Settings has ten pages: model, speech-to-text, text-to-speech, persona, profile, skin, theme, skill library, external brain, and MCP.

> **Talking without the panel**: hold **`R`** for the companion wheel to pick who you're talking to (or just put your crosshair on them), **`Y`** for a minimal text box, and hold **`V`** to talk walkie-talkie style — release and the transcript is sent. Rebind anything under Options → Controls → Numen.

> **Speech in and out**: seven speech-to-text presets ship with the mod, two of which (Alibaba Bailian and Doubao) are streaming — transcription happens as you speak. Companions can also talk back: text-to-speech supports Alibaba Bailian, Fish Audio, GPT-SoVITS, MiniMax, and any OpenAI-compatible TTS. Once a voice is picked, speech is synthesized and played sentence by sentence rather than after the whole reply is generated.

> **macOS voice input**: the microphone permission has to be declared in the launcher's `.app` `Info.plist`, and the mod runs in a Java subprocess that can't add that declaration itself — so use a launcher that declares microphone access. [Prism Launcher](https://prismlauncher.org/) is the one we'd recommend. Allow microphone access the first time you use it; you can check it later under System Settings → Privacy & Security → Microphone. The launcher only provides that permission gate — recording and transcription are still done by Numen and whichever service you configured.

## What it can do

<table>
  <tr>
    <td width="50%"><img src="docs/showcase/plan.png" width="100%"><br><b>🧠 Planning</b> · decomposes a goal step by step</td>
    <td width="50%"><img src="docs/showcase/pathfinding.png" width="100%"><br><b>🔭 Perception & pathfinding</b></td>
  </tr>
  <tr>
    <td><img src="docs/showcase/combat.png" width="100%"><br><b>⚔️ Native combat</b></td>
    <td><img src="docs/showcase/interact.png" width="100%"><br><b>🧩 Mod compatibility</b> · Mekanism shown</td>
  </tr>
</table>

Close to thirty tools make up its hands and eyes right now:

- ⛏️ **Work** — mining, logging, gathering, building, precise placement and breaking, crafting from recipes, smelting in furnaces, and sorting loot into chests.
- 🧭 **Movement** — a server-side pathfinding engine that jumps, swims, climbs, opens doors, parkours and pilots boats, and can bridge gaps, pillar up, tunnel through and staircase down. Walking never alters the world by default — walls, floors, other people's builds and the landscape stay as they were; when the only route would need digging or bridging it lists exactly which blocks, and only proceeds once the model consents (`may_alter_terrain`). Every result reports what was actually broken or placed en route.
- ⚔️ **Combat** — vanilla player melee and archery, with real cooldowns and real crits; it eats when hurt and swims up when it's about to drown.
- 🔭 **Perception** — scan blocks, scan entities, query state, look up recipes, locate structures and biomes, and read what's inside a machine without opening its GUI.
- 🗣️ **Voice** — speech in, speech out, plus a persona, a skin, and a voice you pick yourself.
- 🧠 **Memory** — conversations persist across saves and get compacted when they grow too long. It remembers the crafting table, furnace, and chests it has used and walks back to them instead of building new ones. Death works the vanilla way — items drop, and it respawns beside you after a moment.

## Extending it

How deep a companion can go comes down to three things, and they are nowhere near equally hard.

**Whether it can touch it — already universal.** The companion is a real player, so it can break, place, and right-click a mod's blocks, open and move items through a mod's containers, and read the items, fluids, and energy inside any machine that exposes the standard capabilities. This layer needs no per-mod adaptation at all; it works the moment you install the mod.

**Whether it knows what a thing is — mostly free.** Recipes, tags, and item names are data that gets synced to the client, and mods live in that same system. "What does this machine consume and produce" is, for a good share of mods, already readable.

**Whether it knows how to play — this one is on us.** AE2 channels have to be budgeted, Create's stress will stall a whole line if you exceed it, some things only make sense after a tier upgrade. None of that lives in a data structure — it can't be read out, it has to be written down. That's what a **Skill** is: a Markdown workflow under `config/numen/skills/`, loaded only when relevant so the prompt stays lean. No code, anyone can write one. A set of examples ships with the mod (Nether, blaze rods, ender pearls, strongholds, the dragon fight); edit one or write your own to teach it your base's conventions or a new mod's gameplay.

When words aren't enough, you can hand it tools directly: a mod author registers one through `NumenGateway`, or you attach any Model Context Protocol server from the **MCP** settings page (stdio or HTTP, OAuth supported). Tools arriving either way are treated exactly like built-in ones. A tool hands it a hammer; a Skill teaches it how to swing.

The first two layers are one-time engineering. The third keeps growing, and that takes a community. We're not going to pretend it will ever be finished.

## External brain

It works in reverse too. Numen can run an MCP **server** on your machine, letting an external AI client (Claude Desktop, Cursor, anything that speaks MCP) drive the companion in your world directly — mining, building, fighting, talking to you, all through that AI instead of the built-in brain.

While it's on, the built-in brain stands down completely; one body can't have two brains. Flip the switch in settings and the endpoint and access token are right there on that page, with the token generated randomly by default. See the [external brain docs](docs/mcp-server.md).

## Design

The companion you chat with is just the body this system wears. Underneath it are four parts:

- 🧍 **The body — a real player.** The companion is a server-side fake player (`ServerPlayer`), and every action goes through the vanilla player code path. That's why it plays by the same rules as redstone, mob AI, containers, and other people's mods without being told to.
- 👁️ **The eyes — the perception API.** Self and world state, ranged block and entity scans, recipe lookup, single-block inspection, and reading what a machine holds (items, fluids, energy) without opening its GUI.
- ✋ **The hands — the action API.** Movement, mining, placement, combat, driving any container or machine GUI, inventory management, locating structures and biomes.
- 🔁 **A feedback loop that teaches.** Every tool return — success or failure — is written as a sentence that teaches the model how Minecraft works. "You can't mine iron ore by hand — equip a stone pickaxe at least" is that loop doing its job. The model decides its next step from ground truth it collected in the environment.

On top of all that, **the brain runs on your own machine**: the agent loop lives in the owner's client and calls the LLM with the owner's API key. Everyone pays for their own usage, server owners don't foot the bill for the whole server, and you never hand your key to anyone. LLM transport has zero third-party runtime dependencies — just the JDK's `HttpClient` and Gson.

## FAQ

**Does it cost money? Which model should I use?** Numen itself is free and open source; the LLM calls use your own key. For cheap, DeepSeek, Qwen, Kimi, or GLM will usually run a typical task for a fraction of a cent. For the smartest results, Claude or GPT. Faster and smarter models make for a better companion. Same story for the two voice services — your key, your bill.

**Is my API key safe?** The brain runs in your own client. The key is stored locally and used only to connect directly to the backend you chose. It never passes through a third-party server and is never uploaded to the author.

**Does it work on multiplayer servers?** Yes. The companion is a real player on the server, its actions are validated one by one server-side, and you can only drive your own. The server just needs Numen installed; each client brings their own key.

**Will it tear down my base or go rogue?** It only does what a real player could do in survival, and every action is ownership-checked against its owner. It can't conjure items, and it won't touch things that aren't yours.

**Responses feel slow?** Every step costs one LLM round trip, so faster models feel noticeably better. We're still working on this.

## For developers

Every tool and every skill Numen ships with is written against the public API — there are no private back channels. Any mod author gets the same capabilities:

- 🔧 **Register a tool through `NumenGateway`** and your mod's capabilities become part of the AI's hands. The tool contract deliberately contains no Minecraft concepts — how a call completes (synchronously, asynchronously, sending its own packets, calling an external web service) is entirely up to the tool. That's why the same API reaches a chat platform as comfortably as it reaches an ore vein.
- 📖 **Ship skills inside your jar** — one call turns your jar's `/skills` directory into built-in skills, so players who install your mod get an AI that already knows how to play it.
- 🏗️ **Or build a completely different AI** — same foundation, whether you want AI NPCs, story characters, or a server butler.

The engine (`api/`) lives in this repository under `api/`, and is still published under its own coordinates for third parties:

```gradle
repositories { maven { url = 'https://raw.githubusercontent.com/Dwinovo/numen-maven/main' } }
dependencies  { modImplementation "com.dwinovo.numen:numen-api-fabric-1.21.8:0.1.2" }
```

The public integration API is **MIT** licensed — write tools, skills, and compat without being dragged into LGPL.

Building it yourself: clone the repo and run `./gradlew :core:fabric:build` (or `:core:neoforge:build`). Bugs, ideas, and compat experiments are all welcome — [open an issue](https://github.com/Dwinovo/minecraft-numen/issues), or write a skill and send a PR.

## Roadmap

- **Adapting the big mods.** Create, AE2, Mekanism and other tech mods that are universes unto themselves have to be adapted one at a time — registering tools, writing skills, wiring up MCP, whichever fits. The mod ecosystem is far too large for any single mechanism to cover it. `inspect_block_storage` is the first brick.
- **Growing a skill library.** Make "teach the AI a new mod" as simple as writing one Markdown file, built and shared by the community.
- **Playing more like a veteran.** Deeper world memory and longer-horizon planning.

---

<div align="center">

<sub>Want to build it yourself, see the full tool list, or read the architecture? It's all in the source — start under <code>core/common/src/main/java/com/dwinovo/numen/</code>.</sub>

<sub><b>Licensing</b>: the source code is <a href="LICENSE">LGPL-3.0</a> — forks you distribute must stay open under the same license. The public integration API (what compatibility modules / MCP bridges code against) is <a href="LICENSE-API">MIT</a>, so anyone can build mod-compat freely. The art &amp; assets are <a href="LICENSE-ASSETS">All Rights Reserved</a>, and the names "Numen" / "言出法随" are reserved. Built on the <a href="https://github.com/jaredlll08/MultiLoader-Template">MultiLoader Template</a>.</sub>

<sub>The <b>planning layer</b> implements techniques from the heuristic-search literature: weighted A* with budgeted partial-path commitment (on search timeout the best partial path across several heuristic-coefficient tiers is committed), with game-independent unit tests. The <b>path-following layer</b> advances movement primitive by movement primitive along the computed path: windowed backward/forward relocation, seamless segment splicing and over-length cutoff, in-flight cost re-verification with an off-path watchdog, plus a set of sprint-decision heuristics. The <b>execution layer</b> differs from <a href="https://github.com/cabaletta/baritone">Baritone</a> fundamentally in where it runs: Baritone is a client-side mod driving the local player, while Numen drives a <b>server-side fake player</b> — movement, digging and placement all go through server APIs. It draws on Baritone's publicly documented mechanics for design ideas only; <b>no source was copied, ported, or adapted from it</b>. Numen's code is licensed LGPL-3.0 of its own accord; that choice is not a consequence of Baritone (which is also LGPL-3.0).</sub>

<sub>The <b>spatial representation</b> fed to the model is an egocentric semantic character grid rather than a list of raw coordinates: the voxels around the player are discretized and semantically pooled into a character matrix centred on the companion itself. The effectiveness of this format follows Gao et al., <i>Exploring Spatial Representation to Enhance LLM Reasoning in Aerial Vision-Language Navigation</i> (arXiv:2410.08500, 2024), whose ablations show that, for the same textual input budget, a semantic-topological-metric grid matrix substantially outperforms both topological graphs and bearing-distance descriptions, and does far better than feeding images directly. Numen adopts its "egocentric + discretized + semantically pooled" formatting principle and adapts it to three dimensions for the verticality of a block world (layered slices / height information).</sub>

</div>
