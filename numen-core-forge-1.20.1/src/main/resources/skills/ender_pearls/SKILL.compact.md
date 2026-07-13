# Ender pearls: compact route

Goal: at least 12 ender pearls, with blaze materials preserved.

- Best route is hunting endermen in a Nether `minecraft:warped_forest`; use `locate_biome`, travel safely, then scan entities.
- Use `hunt`, not arrows. The companion’s gaze does not trigger normal enderman aggro, but attacks do.
- Fight on stable dry ground away from lava, water, cliffs and crowds. Heal between fights.
- Overworld night hunting is fallback; rain/water makes it unreliable. Piglin bartering is optional only with a safe setup and spare gold.
- Collect each drop and verify inventory count; do not infer pearls from kill count.
- Keep rods/powder/eyes and route-critical supplies reserved. Stop when count is sufficient and move to a safe location.

## Compact reference: early rules and setup

# Skill: ender_pearls

This is Phase 4 of the dragon route.

Goal:

```text
Acquire at least 12 minecraft:ender_pearl.
Stay alive.
Keep blaze rods/powder safe.
End in a safe spot, preferably ready to start stronghold_finding.
```

Why 12 pearls:

```text
1 ender pearl + 1 blaze powder = 1 eye of ender
An End portal can require up to 12 eyes of ender
Stronghold portal frames may spawn partly filled, but never rely on that
12 pearls is the safe target
```

If you have:

```text
>= 12 ender_pearl
```

this phase is complete.

Do not waste pearls before the End portal is activated.

---

# 0. Completion condition

This skill is complete only when:

```text
get_self_status shows minecraft:ender_pearl count >= 12
player/entity is alive
player/entity is not in immediate danger
blaze rods/blaze powder are still safe
```

Preferred completion state:

```text
At Nether portal, Overworld base, or another safe staging point
Ready to load stronghold_finding
```

Do not mark complete if:

```text
you only killed many endermen but did not verify pearl count
pearls are on the ground and not collected
HP is low and enemies are attacking
you died and inventory is not verified
you have fewer than 12 pearls
```

---

# 1. Absolute priority rules

## 1.1 Use `hunt`, not `shoot`

Endermen teleport away from arrows.

Correct:

```text
equip_item diamond_sword
hunt("minecraft:enderman")
```

Incorrect:

```text
shoot("minecraft:enderman")
```

Do not waste arrows on endermen.

## 1.2 Your gaze does not aggro endermen

For the Numen entity:

```text
Looking at endermen does not anger them.
Endermen become hostile when you hit them.
```

This differs from a normal player.

However, if the owner/player is nearby:

```text
Warn the owner not to stare at endermen.
The owner may still trigger normal player gaze aggro.
```

No pumpkin tricks are needed for Numen.

## 1.3 Warped forest is the best hunting location

Best default location:

```text
minecraft:warped_forest in the Nether
```

Why:

```text
high enderman density
no rain
often already in Nether after blaze_rods
more reliable than Overworld night
```

Do not wander randomly if `locate_biome` exists.

Use:

```text
locate_biome biome="minecraft:warped_forest"
```

## 1.4 Do not fight near lava, water, cliffs, or void-like drops

Endermen teleport and reposition unpredictably.

Avoid hunting near:

```text
lava
water
Nether cliffs
thin bridges
soul sand valleys with ghasts nearby
Overworld ravines
large drops
```

Kill endermen on safe, dry, solid ground.

## 1.5 Verify count with `get_self_status`

Ender pearl drops are random.

Do not estimate from kills.

After hunting batches:

```text
collect_items
get_self_status
```

Stop only when:

```text
ender_pearl >= 12
```

---

# 2. Required support skills

Load before combat:

```text
load_skill(name="combat_basics")
```

Use combat_basics for:

```text
HP thresholds
food healing
hunt vs shoot rules
retreat logic
positioning
post-fight recovery
```

If in creative mode:

```text
load_skill(name="creative_mode")
```

In creative mode, if the owner allows shortcuts:

```text
creative_give item_id="minecraft:ender_pearl" count=12
```

Do not shortcut if the owner asked for legit survival gameplay.

---

# 3. Useful tools

| Tool | Use |
|---|---|
| `get_self_status` | Check HP, inventory, dimension, pearl count, weapon, food. |
| `get_world_info` | Check time, rain, and environment for Overworld hunting. |
| `locate_biome` | Find warped forest directly. |
| `move_to` | Travel to warped forest, reposition, retreat, return to portal. |
| `scan_nearby_entities` | Confirm endermen nearby. |
| `scan_blocks` | Confirm warped forest blocks such as warped_nylium. |
| `equip_item` | Equip sword, pickaxe, food, gold armor if needed. |
| `hunt` | Melee endermen. Correct combat method. |
| `eat_item` | Heal directly. |
| `collect_items` | Pick up dropped pearls after kills. |
| `drop_items` | Drop gold ingots for piglin bartering fallback. |
| `wait` | Wait for night, wait during bartering, or let danger pass. |
| `interact_entity` | Optional piglin interaction if supported. |

---

# 4. Enderman facts

## 4.1 Stats and behavior

Endermen:

```text
have 40 HP
deal strong melee damage, around 7 damage per hit
teleport when hit
often reappear behind or near you
drop 0-1 ender pearl without Looting
average about 0.5 pearls per kill
```

Expected kills:

```text
12 pearls usually requires about 24 enderman kills
bad luck can require more
```

If you already have some pearls:

```text
pearlsNeeded = 12 - currentPearls
expectedKills = pearlsNeeded * 2
```

Example:

```text
currentPearls = 5
pearlsNeeded = 7
expectedKills ~= 14
```

## 4.2 Looting

If you already have a Looting sword:

```text
use it
```

Looting improves pearl yield.

Do not plan the route around enchanting unless another skill or owner explicitly provides it.

## 4.3 Endermen and water/rain

Endermen avoid and teleport away from water and rain.

Therefore:

```text
Do not hunt next to water.
Do not hunt in rain.
Do not use water as a normal farming method for Numen.
```

Water can be emergency safety, but it makes kills slower because endermen teleport away.

## 4.4 Endermen and arrows

Endermen usually teleport away from arrows.

Rule:

```text
Never use shoot for normal enderman farming.
```

Save arrows for:

```text
blazes
ghasts
End crystals
dragon fight
```

---

# 5. Location choice

## 5.1 Best location: Nether warped forest

Use this by default after blaze rods.

Advantages:

```text
high enderman spawn density
no rain
usually reachable while already in Nether
good for repeated hunting batches
```

Signs of warped forest:

```text
warped_nylium
warped_stem
warped_wart_block
nether_sprouts
twisting_vines
teal/blue-green forest appearance
many endermen
```

Confirm with:

```text
scan_blocks("minecraft:warped_nylium", radius=64)
scan_nearby_entities("minecraft:enderman", radius=64)
```

## 5.2 Overworld night fallback

Use only if warped forest is unavailable or Nether travel is not practical.

Good Overworld hunting areas:

```text
plains
desert
savanna
flat open terrain
dry areas away from rivers/oceans
```

Requirements:

```text
night or dark enough for endermen
not raining
not near water
safe flat terrain
```

Check:

```text
get_world_info
```

If raining:

```text
wait
or switch to Nether hunting
```

If daytime:

```text
wait until night
or use Nether hunting
```

## 5.3 Soul sand valley fallback

Soul sand valley can have some endermen.

Pros:

- It is a usable fallback when a warped forest is unavailable.

Cons:

- Terrain is slower and riskier, and enderman density is usually lower.
- Ghasts, skeletons, fire and cliffs can make repeated hunting inefficient.

Prefer warped forest unless the current safe route strongly favors this fallback.

## Final verification and recovery

Count pearls in inventory, not kills. Confirm at least 12 remain after any crafting or accidental use, and preserve blaze materials. On task resume, re-scan biome and nearby entities before continuing; never assume the previous target still exists.
