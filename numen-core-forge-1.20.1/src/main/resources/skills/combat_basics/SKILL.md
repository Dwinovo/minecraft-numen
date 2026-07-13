---
name: combat_basics
description: Generic combat tactics for the Numen entity. Covers HP management through eating, hunt vs shoot selection, arrow budgeting, retreat logic, positioning, aggro pitfalls, post-fight cleanup, and safe reusable combat loops for blazes, endermen, dragon, and common mobs.
---

# Skill: combat_basics

This is a **support skill**, not a route phase.

Load this skill before engaging hostile mobs in any phase.

It applies to:

```text
blazes
endermen
dragon
skeletons
wither skeletons
zombies
spiders
creepers
ghasts
hoglins
magma cubes
general hostile mobs
```

Enemy-specific skills still override or extend this skill:

```text
blaze_rods
ender_pearls
dragon_combat
tier_progression
```

This skill gives the reusable fundamentals:

```text
how to manage HP
when to eat
when to use melee
when to use bow
when to retreat
how to avoid bad terrain
how to avoid unnecessary aggro
how to recover after fights
```

---

# 0. Most important combat rules

Always follow these rules.

## 0.1 Do not die

Dying is the worst outcome.

Dying can lose:

```text
weapons
armor
food
arrows
blaze rods
ender pearls
eyes of ender
dragon-route progress
time
position
```

Retreating five times is cheaper than dying once.

## 0.2 Check status before combat

Before any serious fight:

```text
get_self_status
```

Check:

```text
HP
current dimension
coordinates
main hand item
weapon
bow
arrows
food
armor if visible
dangerous effects if visible
nearby enemies if visible
```

Never start a dangerous fight blindly.

## 0.3 Eat before you are almost dead

The Numen entity has no hunger bar. Food heals directly.

Do not wait for natural regeneration.

If HP is not high enough before a fight:

```text
eat_item
get_self_status
```

## 0.4 Use the right tool

Use:

```text
hunt = melee
shoot = bow/ranged
```

`shoot` requires:

```text
bow equipped
arrows in inventory
line of sight to target
```

`hunt` requires:

```text
melee weapon equipped
safe enough terrain to close distance
```

## 0.5 Re-equip pickaxe after combat

After combat, if the next task involves travel, digging, mining, or navigation:

```text
equip_item pickaxe
```

Important:

```text
Navigation digs with the held tool.
A sword in hand can make stone or terrain effectively impassable.
After fighting, do not keep sword/bow equipped while trying to dig or navigate.
```

## 0.6 Do not fight near lethal terrain

Avoid fighting near:

```text
lava
void
cliff edges
fortress bridge edges
ravines
deep holes
End island edges
Nether lava sea ledges
```

Knockback often kills harder than raw damage.

---

# 1. You are Numen, not a normal player

The Numen entity does not follow all player mechanics.

## 1.1 No hunger bar

There is no normal player hunger/saturation logic.

Do not use player rules like:

```text
stay above 18 hunger for regeneration
wait for natural regen
save food until hunger drops
```

Instead:

```text
food directly heals through eat_item
eat when HP is low or before dangerous fights
```

## 1.2 Food is healing

`eat_item` heals directly.

Healing scales with the food's nutrition value.

Better food gives better healing.

## 1.3 Eating takes time

Eating is not instant.

Assume eating takes about:

```text
~1.6 seconds
```

During that time, enemies may still attack.

Therefore:

```text
eat before HP is critical
eat behind cover if possible
eat after retreating if possible
```

## 1.4 Auto-eat exists during hunt/shoot, but do not rely on it blindly

During `hunt` and `shoot`, the system may auto-eat when HP drops below roughly 40%.

For a 20 HP body:

```text
40% HP ~= 8 HP
```

The combat result may report:

```text
what food was eaten
post-fight HP
whether food ran out
```

Your job is logistics:

```text
carry enough dense food
top up before dangerous fights
restock immediately if combat result says no food
retreat if food is gone
```

Auto-eat is a safety net, not a plan.

## 1.5 No XP/enchanting/shields/brewed potions as baseline

Do not plan around:

```text
XP grinding
enchanting
shield blocking
brewing potions
player-only mechanics
```

Your force multipliers are:

```text
better gear
dense food
enough arrows
good positioning
correct use of hunt vs shoot
retreat discipline
```

If special items already exist, enemy-specific skills may use them, but this baseline skill does not require them.

## 1.6 Endermen do not aggro from looking

For Numen:

```text
Endermen do not care where you look.
Gaze-aggro is player-only.
They become hostile when you hit them.
```

Therefore:

```text
Do not avoid eye contact as a core tactic.
Do avoid accidentally hitting extra endermen.
```

---

# 2. Combat tools

## 2.1 Tool table

| Tool | Use |
|---|---|
| `get_self_status` | Check HP, location, dimension, inventory, weapon, food, arrows. |
| `equip_item` | Equip sword, bow, pickaxe, food, or other item. |
| `eat_item` | Heal directly using food. |
| `hunt` | Melee attack a target. Best for endermen and common mobs. |
| `shoot` | Bow attack a target. Best for blazes, crystals, ghasts, flying dragon. |
| `move_to` | Retreat, reposition, escape bad terrain, or return to safety. |
| `scan_nearby_entities` | Check for nearby hostile mobs or pursuers if available. |
| `collect_items` | Pick up drops after combat. |
| `inspect_block` | Check terrain hazards when positioning. |
| `place_block` | Emergency cover, bridge repair, edge guard, or line-of-sight block if available. |

## 2.2 Tool assumptions

`hunt`:

```text
moves into melee range
keeps attacking until target is dead or action ends
may auto-eat if HP gets low and food exists
```

`shoot`:

```text
uses bow and arrows
needs bow equipped
needs arrows in inventory
works best with line of sight
may auto-eat if HP gets low and food exists
```

Both tools can consume resources:

```text
hunt consumes weapon durability
shoot consumes arrows
both may consume food through auto-eat
```

---

# 3. Pre-fight checklist

Before any non-trivial fight, do this.

## 3.1 Check status

```text
get_self_status
```

Confirm:

```text
HP is safe
food exists
correct weapon exists
correct weapon can be equipped
bow and arrows exist if planning to shoot
dimension is understood
terrain is not obviously lethal
```

## 3.2 Equip correct combat item

For melee:

```text
equip_item sword
```

Preferred:

```text
diamond_sword
iron_sword
stone_sword only for weak mobs or emergencies
```

For ranged:

```text
equip_item bow
```

Then confirm arrows exist.

Important:

```text
shoot fails or performs badly without bow in hand and arrows in inventory.
```

## 3.3 Check food before starting

Minimum food recommendations:

| Fight type | Minimum food | Preferred food |
|---|---:|---:|
| 1 weak mob | 1-2 cooked food | 4+ |
| Small hostile group | 4+ cooked food | 8+ |
| Nether fortress | 8+ cooked food | 16+ |
| Blaze farming | 12+ cooked food | 16-32 |
| Enderman farming | 12+ cooked food | 16+ |
| Dragon fight | 24+ cooked food | 32+ and golden apple if available |

If food is low:

```text
restock before fighting
```

Do not begin a major fight with no food.

## 3.4 Check arrows before shooting

Arrow recommendations:

| Situation | Minimum arrows | Preferred arrows |
|---|---:|---:|
| Emergency ranged kill | 4 | 8+ |
| Blazes | 24 | 48-64+ |
| Blaze rod farming mostly by bow | 48 | 64-96+ |
| End crystals | 16 | 24+ |
| Dragon fight | 32 | 64+ |
| General travel safety | 8 | 16+ |

If arrows are low:

```text
save arrows for enemies that require range
use hunt for common mobs
restock before boss fights
```

## 3.5 Check terrain

Before fighting, ask:

```text
Am I near lava?
Am I near void?
Am I on a narrow bridge?
Am I at a cliff edge?
Can knockback kill me?
Do I have room to move?
Is there a safe retreat path?
```

If terrain is bad:

```text
move_to safer ground before fighting
```

## 3.6 Check aggro risk

Before attacking, ask:

```text
Will this mob call nearby allies?
Will this anger neutral mobs?
Will this trigger a group?
Will this pull too many enemies?
```

If yes, avoid or isolate one target.

---

# 4. HP management

Assume normal max HP is:

```text
20
```

If `get_self_status` reports a different max HP, scale thresholds proportionally.

## 4.1 HP thresholds

Use these default thresholds:

| HP | Meaning | Action |
|---:|---|---|
| 18-20 | Full or nearly full | Safe to start most fights. |
| 14-17 | Slightly damaged | Eat before dangerous fights. |
| 9-13 | Unsafe for dangerous fights | Eat or retreat before continuing. |
| <= 8 | Retreat threshold | Stop fighting, move away, eat. |
| <= 6 | Critical | Emergency retreat; use golden apple if available. |
| <= 4 | Death danger | Do not attack; escape and eat immediately. |

## 4.2 Pre-fight HP rule

Before dangerous fights, prefer:

```text
HP >= 16
```

Before boss or Nether fortress fights, prefer:

```text
HP >= 18
```

If below:

```text
eat_item
get_self_status
```

## 4.3 In-fight HP rule

Between combat calls:

```text
get_self_status
```

If:

```text
HP <= 8
```

Then:

```text
stop fighting
move_to safe point 20+ blocks away if possible
eat_item until HP is safe
re-engage only after stable
```

## 4.4 Burning, poison, and wither

If burning:

```text
retreat earlier
eat sooner
avoid lava/fire source
do not continue long fights at low HP
```

If withered:

```text
retreat immediately if HP is not high
eat aggressively
do not fight more mobs until effect ends or HP is safe
```

Wither skeletons are especially dangerous because damage continues after the hit.

## 4.5 Golden apple rule

Use golden apple only for serious danger.

Good uses:

```text
HP critical during boss fight
HP critical while surrounded
wither/burn damage is ongoing
retreat path is dangerous
dragon fight emergency
```

Bad uses:

```text
minor damage after a zombie
before a trivial animal hunt
when cooked food is enough
```

---

# 5. Food rules

## 5.1 Food ranking

Best normal foods:

```text
minecraft:cooked_beef
minecraft:cooked_porkchop
```

These have high nutrition and are the preferred combat food.

Good foods:

```text
minecraft:cooked_mutton
minecraft:cooked_chicken
minecraft:cooked_salmon
minecraft:cooked_cod
minecraft:bread
```

Emergency/special:

```text
minecraft:golden_apple
```

Bad combat foods:

```text
raw_beef
raw_porkchop
raw_chicken
raw_mutton
raw_cod
raw_salmon
rotten_flesh
spider_eye
```

Avoid raw or harmful foods unless there is no alternative.

## 5.2 Carry dense food

For any serious route phase:

```text
carry cooked_beef or cooked_porkchop if possible
```

Recommended counts:

```text
16+ cooked food for dangerous Nether/End work
32+ cooked food for dragon fight
```

## 5.3 Eat before and after fights

Before fight:

```text
if HP < target, eat_item
```

After fight:

```text
get_self_status
if HP is below safe level, eat_item
```

Do not walk into the next fight already damaged.

## 5.4 If combat result says no food

If `hunt` or `shoot` result says something like:

```text
NO food in inventory
could not auto-eat
food missing
```

Then treat it as urgent.

Action:

```text
stop combat
retreat
restock food
do not engage dangerous mobs until food exists
```

---

# 6. Weapon and tool rules

## 6.1 Melee weapon priority

Use the best available sword.

Preferred order:

```text
netherite_sword
diamond_sword
iron_sword
stone_sword
wooden_sword
```

If no sword exists, use the best weapon-like item available, but do not start dangerous fights with bad gear.

## 6.2 Bow rule

Use bow only if:

```text
bow exists
arrows exist
target is worth arrows
line of sight is available
```

Before `shoot`:

```text
equip_item bow
get_self_status if unsure arrows exist
```

## 6.3 Pickaxe after combat

After combat, if the next action is not another immediate fight:

```text
equip_item pickaxe
```

Reason:

```text
navigation and digging depend on held tool
sword in hand may prevent digging through stone
bow in hand may also be bad for mining/navigation
```

## 6.4 Weapon durability

If status or combat result indicates weapon is nearly broken:

```text
stop combat
replace weapon
repair if possible through other skills
avoid boss fights until fixed
```

Do not let the only good weapon break during a boss or fortress fight.

---

# 7. hunt vs shoot decision table

Use this table by default.

| Target/situation | Use | Reason |
|---|---|---|
| Blaze | `shoot` by default | Fireballs punish walking in; range is safer. |
| Blaze with low arrows | `hunt` fallback | Melee works if food, armor, and terrain are safe. |
| End crystal | `shoot` | Crystals explode; destroy from range. |
| Dragon flying | `shoot` | Cannot melee reliably while airborne. |
| Dragon perched | `hunt` | Best melee window; arrows are usually wasteful. |
| Enderman | `hunt` | Endermen teleport away from arrows. |
| Zombie | `hunt` | Save arrows. |
| Spider | `hunt` | Save arrows. |
| Skeleton close | `hunt` or `shoot` | Use cover; melee if safe. |
| Skeleton far/open | `shoot` | Avoid taking many arrows while closing. |
| Creeper | `shoot` or careful `hunt` | Avoid explosion. |
| Wither skeleton | `shoot` if possible, otherwise careful `hunt` | Wither effect is dangerous. |
| Ghast | `shoot` | Flying target; avoid fireball knockback. |
| Magma cube | `hunt` if necessary | Avoid edges/lava. |
| Food animals | `hunt` | Never spend arrows. |
| Zombified piglin | Avoid | Group aggro is dangerous. |
| Piglin | Avoid unless necessary | Aggro risk; use gold helmet in Nether. |
| Warden | Avoid | Not worth fighting in normal route. |

---

# 8. Melee combat with `hunt`

## 8.1 When to use hunt

Use `hunt` when:

```text
target is on reachable ground
terrain is flat enough
you have a sword equipped
you want to save arrows
target dodges arrows
target is weak/common
```

Good targets:

```text
enderman
zombie
spider
food animals
perched dragon
single skeleton if close
single blaze if arrows low and terrain is safe
```

## 8.2 When not to use hunt

Avoid `hunt` when:

```text
target is above lava
target is across a void/cliff
target is on a narrow bridge
target explodes
multiple ranged enemies are firing
you have low HP
you have no food
you have bad weapon
```

## 8.3 Basic melee loop

Use this pattern:

```text
get_self_status
if HP < 16 for dangerous fight: eat_item
equip_item sword
hunt target
get_self_status
collect_items if safe
if HP <= 8: retreat and eat
equip_item pickaxe if next action is navigation
```

## 8.4 Dangerous melee batch rule

Do not chain too many melee fights without checking status.

After each dangerous target:

```text
get_self_status
```

For weak mobs, you can fight more, but still check regularly.

## 8.5 Enderman melee rule

For Numen:

```text
Endermen aggro when hit, not when looked at.
Use hunt.
Do not use shoot.
Expect teleporting.
Keep swinging.
Avoid fighting at cliff/void/lava edges.
```

---

# 9. Ranged combat with `shoot`

## 9.1 When to use shoot

Use `shoot` when:

```text
target is flying
target is dangerous at melee range
target explodes
target is across a hazard
target is on a tower/pillar
target is a crystal
closing distance is unsafe
```

Good targets:

```text
blaze
ghast
end crystal
flying dragon
skeleton at range
wither skeleton at range
creeper at unsafe melee distance
```

## 9.2 Requirements

Before shooting:

```text
equip_item bow
get_self_status
confirm arrows > 0
```

If no arrows:

```text
do not call shoot
switch to hunt if safe
or retreat/restock
```

## 9.3 Basic ranged loop

Use this pattern:

```text
get_self_status
if HP < 16 for dangerous fight: eat_item
equip_item bow
shoot target in small batch
collect_items if safe
get_self_status
if HP <= 8: retreat and eat
equip_item pickaxe if next action is navigation
```

## 9.4 Small batch rule

For dangerous ranged enemies, do not try to clear everything in one huge call.

Use small batches:

```text
shoot 1 blaze
shoot 2 blazes
shoot 3 blazes
```

Then:

```text
collect_items
get_self_status
```

Small batches prevent:

```text
dying while action runs
wasting arrows
leaving drops behind
getting surrounded
```

## 9.5 Line-of-sight rule

Use cover when shooting.

Good pattern:

```text
stand behind wall/corner
peek
shoot
hide
eat if needed
repeat
```

Especially useful against:

```text
blazes
skeletons
ghasts
dragon projectiles
```

---

# 10. Arrow budgeting

Arrows are consumed.

Do not waste them on easy melee targets.

## 10.1 Arrow priority

Use arrows for:

```text
end crystals
blazes
ghasts
flying dragon
dangerous creepers
ranged skeletons across bad terrain
wither skeletons if melee is unsafe
```

Do not usually use arrows for:

```text
zombies
spiders
food animals
endermen
perched dragon
weak mobs on safe flat ground
```

## 10.2 Budget estimates

Rough budgets:

```text
blaze: up to ~6 arrows per blaze
end crystal: 1 arrow if accurate, more if missed
ghast: a few arrows
dragon fight: keep at least 32, preferably 64+
general travel safety: keep at least 8-16
```

Blaze farming can consume many arrows.

If collecting blaze rods by bow only:

```text
7 rods may need ~14 blaze kills on average
14 blazes * ~6 arrows = ~84 arrows
```

Therefore:

```text
use bow for safety
but use melee fallback if arrows get low and terrain is safe
```

## 10.3 Arrow count states

| Arrow count | State | Action |
|---:|---|---|
| 0 | No ranged option | Do not call shoot. |
| 1-7 | Emergency only | Save for crystals/ghasts/critical shots. |
| 8-23 | Low | Avoid wasting on common mobs. |
| 24-63 | Usable | Good for short fights. |
| 64+ | Comfortable | Good for Nether/End operations. |
| 96+ | Excellent | Good for extended blaze/dragon work. |

## 10.4 Crafting arrows

If survival route needs arrow restock:

```text
1 flint + 1 stick + 1 feather = 4 arrows
```

If in creative mode, load `creative_mode` and use creative item access instead.

---

# 11. Retreat rules

Retreating is a core combat tactic.

## 11.1 Mandatory retreat triggers

Retreat immediately if:

```text
HP <= 8
HP is dropping fast
burning and HP <= 12
withered and HP is unsafe
food is gone
weapon is about to break
arrows are gone and range is required
3+ dangerous enemies are attacking at once
standing near lava/void/cliff while under attack
knocked close to an edge
lost line of sight control
```

## 11.2 How far to retreat

Move at least:

```text
20+ blocks away
```

Prefer:

```text
behind terrain
around a corner
inside a corridor
up to a safe platform
away from lava/void
away from bridge edges
```

Example:

```text
move_to safe point 20-40 blocks away
eat_item
get_self_status
return only when stable
```

## 11.3 Do not long-travel with enemies shooting you

Before a long `move_to`, check for pursuers if possible:

```text
scan_nearby_entities hostile
```

If a blaze, skeleton, ghast, or other ranged mob is actively attacking:

```text
break line of sight
kill or outrun immediate threat
then travel
```

Traveling with a hostile mob on your tail can cause:

```text
constant damage
food waste
death during pathfinding
falling into hazards
```

## 11.4 Retreat is not failure

Correct thinking:

```text
I retreated, healed, and returned alive.
```

Wrong thinking:

```text
I must finish this fight now even at low HP.
```

---

# 12. Positioning

## 12.1 General melee positioning

For `hunt`, prefer:

```text
flat open ground
solid floor
room to pathfind
no lava nearby
no void nearby
no cliff nearby
few obstacles
```

Bad melee terrain:

```text
narrow bridges
stairs over lava
fortress edges
ravines
End island edge
one-block paths
water/lava flows
```

## 12.2 General ranged positioning

For `shoot`, prefer:

```text
solid cover
corners
doorways
pillars
high ground if safe
clear line of sight
safe retreat path
```

Bad ranged positioning:

```text
standing still in open space
shooting from bridge edge
fighting multiple angles at once
letting blazes/skeletons surround you
```

## 12.3 Nether positioning

In the Nether, avoid:

```text
lava-adjacent corridors
fortress bridges without railings
thin netherrack ledges
open lava sea edges
magma cube knockback areas
```

If attacked in the Nether:

```text
move to a wider platform or corridor
fight from cover
do not chase drops into lava
```

## 12.4 End positioning

In the End, avoid:

```text
void edge
obsidian pillar edges
dragon knockback near island edge
endermen swarms near void
```

For dragon fight:

```text
stay on main island
avoid void-side combat
shoot crystals from safe positions
hunt dragon only when perched and safe
```

## 12.5 Fortress positioning

Fortresses are dangerous because of:

```text
blazes
wither skeletons
open bridges
lava below
tight corridors
knockback
```

Good fortress fighting spots:

```text
corridor corner
wide nether brick platform
inside room
behind wall/fence/cover
away from bridge edges
```

Bad fortress fighting spots:

```text
thin bridge
edge over lava
open spawner balcony with many blazes
intersection with multiple wither skeletons
```

---

# 13. Aggro pitfalls

## 13.1 Zombified piglins

Do not attack zombified piglins.

Reason:

```text
They group-aggro.
Hitting one can make many nearby swarm you.
```

Rule:

```text
Never use hunt on zombified piglins unless there is no alternative.
Avoid accidental hits.
```

If accidentally aggroed:

```text
retreat far away
avoid more hits
do not fight the entire group unless extremely well prepared
```

## 13.2 Piglins

Piglins attack if you are not wearing gold armor.

Rule:

```text
Wear a gold helmet or other gold armor in the Nether if piglins are nearby.
```

Also remind the owner/player:

```text
Owner should wear gold too if applicable.
```

Avoid:

```text
opening chests near piglins
mining gold near piglins
attacking piglins
entering bastions casually
```

## 13.3 Piglin brutes

Piglin brutes are always hostile.

Avoid bastions unless another skill requires them.

If seen:

```text
do not treat as normal piglin
retreat or use ranged attacks from safety
```

## 13.4 Endermen

For Numen:

```text
looking does not aggro
hitting does aggro
arrows are unreliable
hunt is correct
```

Endermen teleport behind or around you. This is expected.

Do:

```text
fight on safe flat ground
keep swinging
eat if low
avoid edges/lava/void
```

Do not:

```text
shoot endermen
fight at void edge
hit many at once accidentally
```

## 13.5 Wither skeletons

Wither skeletons inflict Wither.

Wither means:

```text
damage over time
HP may keep dropping after the hit
eating may not outpace damage if you keep fighting
```

Rules:

```text
fight one at a time
use bow if possible
avoid being cornered
retreat after wither hit if HP is not high
```

## 13.6 Creepers

Creepers can explode.

Use:

```text
shoot if possible
or careful hunt with enough room
```

Avoid fighting creepers near:

```text
valuable builds
lava edges
drops
other mobs
your portal
```

## 13.7 Skeletons

Skeletons punish straight-line approaches.

Good tactics:

```text
use cover
close distance around corners
shoot back if far away
hunt if close and terrain is safe
```

## 13.8 Blazes

Blazes shoot volleys and set you on fire.

Default:

```text
shoot
```

Fallback:

```text
hunt if arrows are low, food is stocked, and terrain is safe
```

Never fight many blazes in the open if you can use cover.

## 13.9 Ghasts

Ghasts are ranged flying enemies.

Use:

```text
shoot
cover
avoid bridge edges
```

Do not fight ghasts from a narrow bridge over lava.

---

# 14. Target priority

When multiple enemies are present, prioritize by danger.

## 14.1 Highest priority

Deal with these first if they are actively threatening you:

```text
creeper about to explode
wither skeleton in melee range
blaze group with line of sight
skeleton/ghast trying to knock you off edge
enemy blocking retreat path
```

## 14.2 Medium priority

```text
skeletons at range
blazes without direct line of sight
magma cubes near edge
endermen already aggroed
spiders/zombies in groups
```

## 14.3 Low priority

```text
passive animals
neutral mobs not aggroed
far mobs not affecting route
zombified piglins unless blocking path
```

Do not create extra fights unnecessarily.

---

# 15. Combat batch sizing

`hunt` and `shoot` can run until their target is handled. Use sensible batch sizes.

## 15.1 Dangerous enemies

For dangerous enemies, fight in small batches:

```text
1-3 blazes
1 wither skeleton
1-2 endermen
1 creeper
1 ghast
```

Then:

```text
get_self_status
eat if needed
collect drops if safe
continue
```

## 15.2 Weak enemies

For weak mobs on safe terrain, larger batches are acceptable.

Examples:

```text
zombies on flat ground
food animals
single spiders
```

Still check HP regularly.

## 15.3 Bosses

For boss phases:

```text
act between windows
check HP often
eat before next phase
do not tunnel vision
```

Dragon example:

```text
shoot crystals
check HP
shoot dragon while flying
check HP
hunt perched dragon
check HP
eat if needed
repeat
```

---

# 16. Basic combat algorithms

## 16.1 Generic pre-combat algorithm

Use this before any fight:

```text
get_self_status

if game_mode=creative:
    load_skill(name="creative_mode")
    combat risk is reduced, but still avoid destructive commands unless requested

if HP < 16 and fight is dangerous:
    eat_item
    get_self_status

check food count
check weapon
check arrows if using bow
check terrain
choose hunt or shoot
```

## 16.2 Generic melee algorithm

```text
get_self_status

if HP < 16:
    eat_item
    get_self_status

equip_item best_sword

hunt target

get_self_status

if HP <= 8:
    move_to safe retreat point
    eat_item
    get_self_status

collect_items if safe

equip_item pickaxe if next action is navigation/digging
```

## 16.3 Generic ranged algorithm

```text
get_self_status

if arrows <= 0:
    do not shoot
    choose hunt if safe or retreat/restock

if HP < 16:
    eat_item
    get_self_status

equip_item bow

shoot target in small batch

collect_items if safe

get_self_status

if HP <= 8:
    move_to safe retreat point
    eat_item
    get_self_status

equip_item pickaxe if next action is navigation/digging
```

## 16.4 Generic retreat algorithm

```text
get_self_status

if HP <= 8 or danger is high:
    choose safe point 20+ blocks away
    prefer cover and solid ground
    move_to safe point
    eat_item
    get_self_status
    do not re-engage until HP and food are safe
```

If enemies are actively shooting:

```text
break line of sight first
then move_to farther safe point
```

---

# 17. Post-fight checklist

After every meaningful fight:

```text
get_self_status
```

Then check:

```text
HP
food remaining
arrows remaining
weapon still usable
important drops collected
dangerous effects gone or manageable
nearby hostiles cleared
current held item
```

Then:

```text
collect_items if safe
eat_item if HP below safe level
equip_item pickaxe before navigation
continue route only when stable
```

## 17.1 Do not leave drops behind if safe

Use:

```text
collect_items
```

Especially important for:

```text
blaze_rod
ender_pearl
dragon drops if any
food
arrows
gear
```

But do not chase drops into:

```text
lava
void
cliff edge
enemy swarm
```

## 17.2 Recover before moving on

Do not chain dangerous phases with low HP.

Bad:

```text
finish blaze fight at 7 HP
immediately navigate fortress bridge
```

Good:

```text
finish fight
retreat to safe corner
eat
check status
continue
```

---

# 18. Resource restocking

## 18.1 Restock food

If food is low:

```text
stop dangerous combat
hunt food animals if safe
cook food through appropriate skill
or use creative_give if in creative mode
```

Do not enter Nether/End boss phases with low food.

## 18.2 Restock arrows

If arrows are low and ranged combat is needed:

```text
craft more arrows
collect arrows if possible
use melee fallback only when safe
or use creative_give if in creative mode
```

Arrow recipe:

```text
1 flint + 1 stick + 1 feather = 4 arrows
```

## 18.3 Replace weapons

If weapon is weak or nearly broken:

```text
upgrade through tier_progression
craft/obtain replacement
do not boss fight with bad weapon
```

---

# 19. Enemy-specific quick tactics

This section is a quick reminder. Full tactics live in enemy-specific skills.

## 19.1 Blaze

Default:

```text
shoot
```

Rules:

```text
use cover
avoid open spawner balcony
collect rods after small batches
retreat if HP <= 8
melee only if arrows low and terrain safe
```

## 19.2 Enderman

Default:

```text
hunt
```

Rules:

```text
looking does not matter for Numen
hit to aggro
expect teleport
fight on flat safe ground
do not shoot
avoid void/lava edges
```

## 19.3 Dragon

Use:

```text
shoot crystals
shoot dragon while flying
hunt dragon while perched
```

Rules:

```text
do not fight near void edge
save arrows for crystals/flying dragon
use golden apple for critical boss danger
eat between phases
```

## 19.4 Wither skeleton

Default:

```text
shoot if possible
hunt only if isolated and safe
```

Rules:

```text
avoid groups
watch HP after wither effect
retreat early
```

## 19.5 Creeper

Default:

```text
shoot if distance exists
careful hunt if safe
```

Rules:

```text
do not let it explode near valuable structures
do not fight near cliffs/lava
```

## 19.6 Skeleton

Default:

```text
shoot if far
hunt if close and safe
```

Rules:

```text
use cover
avoid bridge edges
do not take arrows while slowly pathing across open ground
```

## 19.7 Common mobs

For:

```text
zombie
spider
slime
food animals
```

Use:

```text
hunt
```

Save arrows.

---

# 20. Terrain-specific rules

## 20.1 Nether

Highest risks:

```text
lava
fortress bridges
blazes
wither skeletons
ghasts
piglin aggro
```

Rules:

```text
wear gold if piglins are nearby
do not attack zombified piglins
fight away from lava
use bow for blazes/ghasts
retreat behind fortress walls
```

## 20.2 End

Highest risks:

```text
void
dragon knockback
endermen swarms
crystal explosions
```

Rules:

```text
do not fight at island edge
shoot crystals from range
hunt endermen only on safe ground
hunt dragon only when perched
```

## 20.3 Caves

Highest risks:

```text
mobs from multiple directions
lava pockets
skeleton knockback
creeper explosions
falling into holes
```

Rules:

```text
control tunnels
fight one direction at a time
avoid backing into lava
use cover against skeletons
```

## 20.4 Open overworld

Highest risks:

```text
creepers
skeletons
large groups at night
falling into ravines
```

Rules:

```text
use flat ground
avoid cliffs
shoot creepers if unsafe
hunt common mobs
```

---

# 21. If something goes wrong

## 21.1 HP low

If:

```text
HP <= 8
```

Action:

```text
stop attacking
move_to safe spot
eat_item
get_self_status
```

## 21.2 No food

If no food remains:

```text
retreat
avoid combat
restock
do not continue dangerous phase
```

## 21.3 No arrows

If no arrows remain:

```text
do not call shoot
use hunt only if safe
restock before ranged-required fights
```

## 21.4 Wrong item equipped

If holding sword/bow while trying to navigate:

```text
equip_item pickaxe
```

If holding pickaxe while combat starts:

```text
equip_item sword or bow
```

## 21.5 Surrounded

If surrounded:

```text
retreat toward open safe ground
avoid edge/lava
fight one target at a time
eat as soon as possible
```

Do not stand still trading damage.

## 21.6 Aggroed piglins or zombified piglins

If neutral mob group aggro occurs:

```text
do not keep fighting the group unless unavoidable
run far away
break line of sight
avoid hitting more
recover HP
```

## 21.7 Death occurred

If death happens:

```text
stop current combat plan
recover items if possible
do not continue route as if gear still exists
get_self_status
rebuild gear/food/arrows
```

---

# 22. Creative mode exception

If:

```text
get_self_status reports game_mode=creative
```

Then:

```text
load_skill(name="creative_mode")
```

In creative mode:

```text
normal HP/food/resource rules may not matter
items can be obtained directly
flight/commands can solve many combat risks
```

However:

```text
still avoid destructive global commands
still avoid accidentally damaging user builds
still use correct tool if user asked for actual combat
```

---

# 23. What this skill is not

This skill does not replace:

```text
blaze_rods
ender_pearls
dragon_combat
tier_progression
building
creative_mode
```

Use enemy-specific skills for detailed tactics.

Use `tier_progression` for gear upgrades.

Good tactics with bad gear can still die.

---

# 24. Final reminders

Before combat:

```text
get_self_status
equip correct weapon
check food
check arrows
check terrain
```

During combat:

```text
use hunt or shoot correctly
use small batches for dangerous mobs
retreat at HP <= 8
eat before critical danger
avoid lava/void/edges
```

After combat:

```text
get_self_status
collect_items
eat if damaged
check resources
equip pickaxe before navigation
```

Highest priority order:

```text
1. Stay alive.
2. Avoid lava/void/cliff knockback.
3. Keep enough food.
4. Use correct weapon.
5. Save arrows for ranged-required targets.
6. Retreat early.
7. Collect important drops.
8. Re-equip pickaxe after fighting.
```
