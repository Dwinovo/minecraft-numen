---
name: ysm_look
description: How models and emotes work in YSM (Yes Steve Model) — which models a player may wear, how model ids and textures are written, the emotes each model brings, which ones play by themselves, emote sounds, when an emote ends, and what the server cannot see.
---

# YSM models and emotes

## Models

- A model id looks like `misc/1_alex`; a packed model's id is its file name with the extension (such as `抽象鸣潮 菲比.ysm`). Neither is the display name.
- A model can bring several textures (a white variant, say), chosen together with the model.
- The models you may wear **are the same as your owner's**: you have the models your owner is authorized for, and a model outside that set is one your owner does not have either.

## Emotes

- **Each model brings its own emotes**; after a model switch the old emote names may be gone. YSM does not tell the server which emotes a model has.
- Most models have the `extra1` to `extra8` set, usually expressions or special moves made by the author. `idle`, `walk`, `run` and `sit` are for walking and standing, and the body plays them by itself.
- Some emotes **carry a sound** (attached to the animation by the model's author) that plays with the emote; which ones do cannot be seen from the server.
- An emote plays until the player **moves** (forward, back, left, right, jump or sneak), **switches models**, **plays another emote** or stops it. It is the same for a human: the moment a player moves, their emote ends, with no separate notice.
- A looping emote does not stop by itself, and neither does one that holds its last frame; after a one-shot emote the look returns to idle, but the server does not know it finished and still counts it as playing.
- After a one-shot emote, if the player keeps standing still, someone who walks away and comes back sees it play once more, until the player moves or stops it. The server cannot get an emote's length.
