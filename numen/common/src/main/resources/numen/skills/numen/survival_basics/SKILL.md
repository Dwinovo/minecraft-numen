---
name: survival_basics
description: Minecraft 1.21.1 survival mechanics - health and hunger, natural regeneration, the food value table, environmental damage (falls, lava, fire, drowning, suffocation, freezing, the void), flying and elytra gliding with firework rockets, when and where hostile mobs spawn, day and night, beds, and what happens to items on death.
---

# Survival basics (1.21.1)

## Health and hunger

- **Health** is 20 HP (10 hearts). **Hunger** is 20 points (10 drumsticks), backed by a hidden **saturation** value that drains first.
- **Natural regeneration**: with hunger at 18 or more, 1 HP every 4 s; with a full hunger bar and saturation left, much faster (1 HP every 0.5 s). Each healed HP costs hunger.
- **Sprinting** needs hunger above 6.
- **Starving** (hunger 0): 1 HP every 4 s, down to 10 HP on Easy, 1 HP on Normal, death on Hard.
- Exhaustion that drains hunger: sprinting (the biggest), jumping, sprint-jumping, attacking, taking damage, swimming, mining; standing still costs nothing.

## Food

| Food | Hunger | Saturation | Notes |
|---|---|---|---|
| Cooked beef (steak), cooked porkchop | 8 | 12.8 | Best common food |
| Golden carrot | 6 | 14.4 | Highest saturation |
| Cooked mutton, cooked salmon | 6 | 9.6 | |
| Cooked chicken | 6 | 7.2 | |
| Rabbit stew | 10 | 12 | Not stackable |
| Mushroom stew, beetroot soup, suspicious stew | 6 | 7.2 | Not stackable |
| Bread, baked potato, cooked cod, cooked rabbit | 5 | 6 | |
| Pumpkin pie | 8 | 4.8 | |
| Apple | 4 | 2.4 | Oak and dark oak leaves drop them |
| Carrot | 3 | 3.6 | |
| Raw beef, raw porkchop | 3 | 1.8 | Raw meat heals far less than cooked |
| Raw chicken | 2 | 1.2 | 30% chance of Hunger |
| Melon slice, sweet berries, glow berries, cookie | 2 | ≤1.2 | Snacks |
| Rotten flesh | 4 | 0.8 | 80% chance of Hunger (drains hunger fast) |
| Spider eye | 2 | 3.2 | Poison |
| Pufferfish | 1 | 0.2 | Poison, Hunger and Nausea |
| Golden apple | 4 | 9.6 | Regeneration II (5 s) + Absorption (2 min) |
| Enchanted golden apple | 4 | 9.6 | Regeneration II, Absorption IV, Resistance, Fire Resistance; loot only |
| Honey bottle | 6 | 1.2 | Cures Poison |
| Milk bucket | - | - | Clears all status effects |

Eating takes 1.6 s (dried kelp 0.8 s). Food cannot be eaten with a full hunger bar, except golden apples and chorus fruit.

## Damage from the world

| Source | Damage |
|---|---|
| Falling | 1 HP per block fallen beyond 3; landing in water, on a slime block, in powder snow, or on a hay bale (80% less) cancels or cuts it |
| Lava | 4 HP every 0.5 s while in it, then burning |
| Burning (fire, after lava) | 1 HP per second; water or rain puts it out |
| Drowning | Air lasts 15 s under water; then 2 HP per second |
| Suffocation (head inside a block) | 1 HP every 0.5 s |
| Cactus, sweet berry bush | Small damage on contact |
| Magma block | 1 HP per second standing on it, unless sneaking |
| Freezing (powder snow) | After 7 s inside, 1 HP every 2 s; leather armor prevents it |
| The void | 4 HP every 0.5 s below the world (64 blocks under the bottom); nothing survives it |
| Lightning | 5 HP and sets on fire |

Armor reduces mob, arrow and explosion damage but not falls (Feather Falling does), drowning, suffocation, starvation or the void.

## Flying and gliding

- **Creative flight**: in creative mode a double tap of jump starts flying; touching the ground ends it.
- **Elytra**: worn in the chest slot; found on end ships beside End cities. It opens on a second jump while falling, never in water, while riding or under Levitation, and closes on touching the ground.
- **Wear**: 432 durability, 1 point per second of gliding (Unbreaking slows it). At 1 point left it stops opening but does not break. Phantom membranes repair it on an anvil, and so does Mending.
- **Gliding** follows where the glider looks: looking down trades height for speed, looking up trades speed for height. Without a boost a glide slowly sinks.
- **Firework rockets**: used while gliding, a rocket pulls the glider toward 1.5 blocks per tick (30 blocks per second) along the look direction while it burns. Used on the ground it only launches into the sky.

| Rocket | Crafted from | Burns for |
|---|---|---|
| Flight duration 1 | 1 paper + 1 gunpowder → 3 rockets | 1.0-1.5 s |
| Flight duration 2 | 1 paper + 2 gunpowder → 3 rockets | 1.5-2.0 s |
| Flight duration 3 | 1 paper + 3 gunpowder → 3 rockets | 2.0-2.5 s |

- A rocket crafted with **firework stars** explodes when it burns out and hurts the glider it pulls: 5 HP plus 2 per star.
- **Flying into a wall** while gliding hurts by the horizontal speed lost: (blocks per tick lost × 10) − 3 HP, about 12 HP for a dead stop at rocket speed.
- **Landing**: a shallow glide lands without fall damage; a dive sinking faster than 0.5 blocks per tick (10 blocks per second) builds up fall distance like falling.

## Mob spawning and light

- Hostile mobs spawn on solid blocks in **block light 0**: any light from a torch or lamp on that block stops spawning there completely. Outdoors they also need darkness, so they spawn at night and in deep shade.
- They spawn 24 to 128 blocks from players and despawn when far away. A torch every few blocks, keeping every walkable block above light 0, makes an area spawn-proof.
- Zombies, skeletons, strays, drowned and phantoms **burn in sunlight** unless they are in water, in shade or wearing a helmet. Creepers, spiders, endermen and witches do not.
- **Spiders** are neutral in daylight (unless hit) and hostile in the dark. **Endermen** stay neutral unless looked at directly (head) or hit.
- **Phantoms** spawn at night above players who have not slept for 3 or more in-game days.
- **Slimes** spawn in slime chunks below Y 40 and in swamps at night.
- Every overworld biome spawns hostiles except mushroom fields and the deep dark.

## Day, night and sleeping

- A full day is 20 minutes: about 10 min of day, 1.5 min dusk, 7 min of night, 1.5 min dawn. Monsters start spawning outdoors at dusk.
- **Sleeping** in a bed is possible at night or during thunderstorms and skips to morning when every player in the world is asleep; it also resets the phantom timer and sets the respawn point. Monsters within 8 blocks prevent sleeping.
- Beds **explode** in the Nether and the End.

## Death

- Everything carried is dropped where the body died, together with most of the experience. Dropped items **disappear after 5 minutes** in a loaded area, and are destroyed at once by lava, fire, cactus or the void.
- Respawning happens at the last bed slept in (or a charged respawn anchor in the Nether), else at the world spawn.
