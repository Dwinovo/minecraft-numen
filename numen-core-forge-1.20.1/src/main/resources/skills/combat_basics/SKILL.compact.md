# Combat basics: compact execution rules

1. Check self status, target, terrain, food/healing and weapon/ammo before fighting.
2. Survival comes first: avoid lava, cliffs, void, deep water and uncontrolled groups. Retreat and heal before critical HP.
3. Use `hunt` for melee/mobile targets and `shoot` for dangerous ranged targets or when distance matters. Endermen are poor arrow targets.
4. Equip the intended weapon; verify arrows before shooting. Food heals this companion, so eat early enough for the action to finish.
5. Fight from stable ground with an escape path. Do not chase into unknown terrain.
6. Re-scan after each engagement; verify target death and collect drops. Do not count expected drops until inventory confirms them.
7. Re-equip the correct work tool afterward. If repeated fights reduce supplies below the task’s safe budget, stop and report/gather.

For special enemies load their phase skill as well. Never claim combat success from damage alone.

## Compact reference: early rules and setup

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

- Carry several usable healing foods before a planned fight; one item is not a recovery plan.
- Start at healthy HP and reserve enough food for the return trip.
- If healing items are absent or cannot be eaten, disengage and obtain help instead of entering combat.

## Final verification and recovery

After combat, confirm the intended target is gone, collect and count required drops, heal, scan for remaining hostiles and restore the work tool. If the target escaped or the tool timed out, re-scan world state before retrying so the AI does not attack the wrong entity or duplicate a completed objective.
