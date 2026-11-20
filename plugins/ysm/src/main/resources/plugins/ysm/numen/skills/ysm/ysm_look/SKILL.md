---
name: ysm_look
description: How changing models and playing emotes works in YSM (Yes Steve Model) — which models can be switched to, how model ids and textures are written, the emotes each model brings, which ones play by themselves, emote sounds, and what the server can and cannot confirm.
---

# YSM models and emotes

With YSM installed, the functions in the `ysm` group change your look (`ysm.model.switch`) and play emotes (`ysm.model.emote`); look up how to call them with
`numen.api.help("ysm.model.switch")`.

## Models

- `ysm.model.options()` gives three things at once: what you wear now, the list you can switch to, and the textures the current model brings (`textures`).
- Write a model id exactly as it is in the list: something like `misc/1_alex`; a packed model is a file name with its extension (such as `抽象鸣潮 菲比.ysm`). Neither is the display name.
- For another texture (a white variant, say) pick one from `textures` and name it when switching.
- What you can switch to **is the same as for your owner**: you have the models your owner is authorized for; one outside that set fails to switch and says so, and it means your owner does not have that look either.

## Emotes

- **Each model brings its own emotes**; after a model switch the old emote names may be gone. YSM does not tell the server which emotes a model has, so there is no emote list to look up.
- Most models have the `extra1` to `extra8` set, usually expressions or special moves made by the author. `idle`, `walk`, `run` and `sit` are for walking and standing, and the body plays them by itself.
- Some emotes **carry a sound** (attached to the animation by the model's author) that plays with the emote; which ones do cannot be seen from the server.
- `ysm.model.emote` has no receipt: when the model has no such emote the body does nothing, and the server cannot check.
- An emote plays until you **move** (forward, back, left, right, jump or sneak), **switch models**, **play another emote** or call `ysm.model.emote("stop")`. It is the same as for a human: the moment a player moves, their emote ends. Stopping is a consequence of this rule, with no separate notice.
- A looping emote does not stop by itself, and neither does one that holds its last frame; after a one-shot emote the look returns to idle, but the server does not know it finished and still counts it as playing.
- Known limit: after a one-shot emote, if you keep standing still, someone who walks away and comes back sees it play once more, until you move or stop. The server cannot get an emote's length, so it cannot end it for you.
