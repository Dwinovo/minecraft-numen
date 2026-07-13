---
name: dragon_combat
description: Final boss phase. Covers End entry, obsidian spawn platform safety, bridging to the central island, destroying all end crystals with shoot, handling caged crystals with pillar/climb + iron bar mining, dragon flying/perched attack patterns, HP discipline, void safety, enderman interference, and post-kill exit.
---

# Skill: dragon_combat

This is Phase 6 of the dragon route: the final boss.

The Ender Dragon has:

```text
200 HP
high knockback attacks
flight movement
perch phases
healing from end crystals
void-based arena danger
```

The main danger is not just damage. The main danger is:

```text
falling into the void
getting knocked off the island
standing in dragon breath
exploding crystals at close range
running out of food/arrows
fighting before crystals are destroyed
```

Load this skill when:

```text
You are about to enter the End portal.
You are in the End dimension.
You are fighting the Ender Dragon.
You are destroying end crystals.
```

Also load:

```text
combat_basics
```

because this fight uses the general HP, food, retreat, hunt, shoot, and positioning rules.

---

# 0. Completion condition

This phase is complete only when:

```text
The Ender Dragon reaches 0 HP.
The death animation plays.
The central bedrock exit portal opens.
The dragon egg appears on top of the portal fountain.
The player/entity is alive.
The fight is over and the arena is safe.
```

After completion:

```text
Congratulate the owner.
Mark the endgame route completed.
Do not punch the dragon egg unless specifically asked.
Wait for owner instruction before entering the exit portal if appropriate.
```

Do not mark complete if:

```text
Some crystals are still alive.
The dragon is still flying.
The dragon is only low HP.
You died.
You are lost on the End island.
You escaped but dragon is alive.
```

---

# 1. Absolute priority rules

## 1.1 Crystals first, always

End crystals heal the dragon.

If any crystal remains alive:

```text
damaging the dragon is inefficient
dragon HP can regenerate
arrows and melee time are wasted
```

Therefore:

```text
Destroy all crystals before focusing dragon damage.
```

Correct priority:

```text
1. Get safely onto central island.
2. Destroy all open crystals.
3. Open and destroy caged crystals.
4. Verify no crystals remain.
5. Kill dragon.
```

Wrong priority:

```text
shoot dragon while crystals are still healing it
hunt dragon before crystals are gone
ignore caged crystals
```

## 1.2 Never fight near the island edge

The void is the true final boss.

Avoid:

```text
island rim
obsidian platform edge
pillar tops after crystal is exposed
bridges over void
cliffs
knockback-prone positions
```

Fight near:

```text
central island
bedrock fountain area
solid end stone
wide terrain
```

If the dragon knocks you near the edge:

```text
move_to back toward center immediately
```

## 1.3 Never detonate a crystal at close range

End crystals explode.

Do not:

```text
punch crystals
stand next to crystals
shoot a crystal from point-blank range
open a cage and instantly break the crystal while beside it
```

Correct:

```text
open cage
retreat at least 8-12 blocks or descend to ground
shoot crystal from range
```

## 1.4 HP discipline is stricter than normal combat

This is a long boss fight.

Between every major tool call:

```text
get_self_status
```

Boss fight thresholds:

```text
HP >= 18: good
HP 14-17: eat before risky action
HP 11-13: disengage and eat if safe
HP <= 10: mandatory retreat/eat
HP <= 6: emergency; use golden_apple if available
```

Do not continue attacking at low HP.

## 1.5 Do not trust auto-eat alone

`hunt` and `shoot` may auto-eat when HP drops low, but this fight can kill through knockback, crystal explosion, and breath clouds.

Use:

```text
eat_item
get_self_status
```

between phases.

## 1.6 Re-equip correct tools deliberately

Use:

```text
bow for crystals and flying dragon
sword for perched dragon
pickaxe/auto_mine for iron bars
blocks for navigation/bridging/pillaring
```

After mining or moving, re-equip the correct combat item.

---

# 2. Required tools

| Tool | Use |
|---|---|
| `get_self_status` | Check HP, inventory, arrows, food, armor, position, dimension. |
| `equip_item` | Equip sword, bow, pickaxe, food, or blocks. |
| `shoot` | Destroy end crystals and damage flying dragon. |
| `hunt` | Melee the dragon during perch phase. |
| `move_to` | Travel from spawn platform to island, reposition, retreat, climb/descend pillars. |
| `auto_mine` | Mine iron bars around caged crystals if available. |
| `break_block` | Fallback for individual iron bars if auto_mine is unavailable. |
| `place_block` | Emergency block placement, bridging, cover, or path correction. |
| `scan_nearby_entities` | Find dragon, end crystals, or aggroed endermen if available. |
| `scan_blocks` | Locate iron bars/cages or terrain features if available. |
| `eat_item` | Heal directly using food. |
| `collect_items` | Usually not important during fight, but can recover drops if needed. |

---

# 3. Packlist before entering the End

Verify with:

```text
get_self_status
```

before entering the End portal.

Do not enter if the packlist is incomplete unless the user explicitly accepts the risk.

## 3.1 Required gear

Minimum:

```text
diamond_sword or better
bow
32+ arrows
128+ solid blocks, preferably cobblestone
32+ cooked food
armor equipped
pickaxe or auto_mine capability for iron bars
```

Preferred:

```text
diamond_sword
bow
64+ arrows
128-192 cobblestone
32+ cooked_beef or cooked_porkchop
golden_apple x1 or more
full iron/diamond armor
pickaxe
extra blocks
```

## 3.2 Why each item matters

Sword:

```text
Main damage during perch phase.
Dragon takes good melee damage while perched.
```

Bow:

```text
Required for crystals.
Required for flying dragon damage.
Required for safe crystal explosions.
```

Arrows:

```text
10 crystals
misses
flying dragon shots
emergency ranged attacks
```

Blocks:

```text
The End spawn platform can be separated from the island.
Navigation may need to bridge across void.
Caged crystal pillars may require pillaring/climbing.
Emergency path repair may be needed.
```

Food:

```text
Numen heals directly by eating.
This is a long fight.
Dragon breath and knockback can stack damage.
```

Golden apple:

```text
Emergency heal for boss danger.
Use at HP <= 6, or when trapped/knocked/burning-style damage pressure occurs.
```

Armor:

```text
Reduces dragon damage and enderman hits.
Do not fight dragon unarmored unless in creative mode or explicitly requested.
```

Pickaxe/auto_mine:

```text
Needed to open iron-bar cages around caged crystals.
```

## 3.3 Minimum go/no-go checklist

Before portal entry, confirm:

```text
dimension is Overworld/stronghold portal room
HP >= 18
sword exists and can be equipped
bow exists
arrows >= 32
food >= 32 cooked food preferred
blocks >= 128
armor equipped
pickaxe available
```

If any critical item is missing:

```text
stop
restock
do not enter End yet
```

---

# 4. Creative mode exception

If:

```text
get_self_status reports game_mode=creative
```

then load:

```text
creative_mode
```

Creative mode changes the fight:

```text
void and damage are less relevant
items can be given directly
flight and commands can solve movement
commands can kill or summon if user asks
```

However:

```text
Do not use destructive commands unless requested.
If the user wants an actual fight, still follow this skill.
```

---

# 5. Pre-portal procedure

Before jumping into the End portal:

```text
load_skill(name="combat_basics")
get_self_status
```

Then:

```text
equip_item diamond_sword
```

Why sword first:

```text
If you arrive and immediately face endermen or dragon danger, sword is ready.
Bow will be equipped before shooting crystals.
```

Check:

```text
HP
food count
arrow count
block count
armor
weapon
current position
```

If HP is below 18:

```text
eat_item
get_self_status
```

Only enter when stable.

---

# 6. End arena layout

## 6.1 Spawn platform

When entering the End, you appear on a small obsidian platform.

Important:

```text
The platform may be away from the main island.
It may be suspended over the void.
The main island is roughly centered near x=0, z=0.
The island surface is usually around y=55-70.
```

A common safe target:

```text
move_to x=0 y=62 z=0
```

Navigation may bridge automatically using your blocks.

## 6.2 Central island

The central island contains:

```text
end stone terrain
central bedrock fountain
obsidian pillars
end crystals
endermen
Ender Dragon
```

The safest general combat zone is:

```text
near the center, around the bedrock fountain
not directly inside breath clouds
not near the rim
```

## 6.3 Obsidian pillars

There are usually:

```text
10 obsidian pillars
```

Each has:

```text
one end crystal at the top
```

Most crystals are open.

Usually:

```text
2 crystals are protected by iron-bar cages
```

The caged crystals are commonly on the taller pillars.

## 6.4 Central bedrock fountain

Before dragon death:

```text
central bedrock fountain is the dragon perch area
```

After dragon death:

```text
exit portal opens here
dragon egg appears on top
```

Do not enter the exit portal until ready.

---

# 7. Arrival procedure in the End

Immediately after entering:

```text
get_self_status
```

Confirm:

```text
dimension == The End
HP safe
standing on obsidian platform or island
not falling
```

## 7.1 If on separated platform

If you are on the obsidian platform away from the island:

```text
do not fight from the platform
do not stand on the edge
move_to x=0 y=62 z=0
```

Navigation may bridge.

You need blocks for this. That is why 128+ cobblestone is required.

If `move_to` fails due to no path or insufficient blocks:

```text
check block count
use place_block/bridge strategy if available
or return/recover if possible
```

## 7.2 If inside/near island

If the platform is close to or embedded in the island:

```text
move_to central safe area near x=0 y=62 z=0
```

Avoid:

```text
digging randomly
breaking platform beneath yourself
moving toward island rim
```

## 7.3 First safety check on island

Once on the island:

```text
get_self_status
```

If HP is not high:

```text
eat_item
```

Then begin crystals.

---

# 8. Fight priority overview

The fight order is:

```text
1. Enter End safely.
2. Reach central island.
3. Destroy 8 open crystals with bow.
4. Open and destroy 2 caged crystals.
5. Verify crystals are gone.
6. Damage dragon while flying with bow.
7. Damage dragon while perched with sword/hunt.
8. Heal and reposition between tool calls.
9. Repeat until dragon dies.
10. Stop, verify death, congratulate owner.
```

Never skip crystal cleanup.

---

# 9. Phase 1: destroy open crystals

## 9.1 Equip bow

Before shooting crystals:

```text
get_self_status
equip_item bow
get_self_status
```

Confirm:

```text
bow equipped
arrows > 0
HP safe
standing on solid ground
not near void edge
```

## 9.2 Use shoot for open crystals

Use:

```text
shoot("minecraft:end_crystal", 8)
```

or equivalent:

```text
shoot(end_crystal, 8)
```

The intent:

```text
destroy the 8 non-caged crystals
from range
without approaching explosions
```

Crystals die to one accurate arrow, but misses can happen.

## 9.3 Why not melee crystals

Never use:

```text
hunt end_crystal
punch crystal
stand beside crystal
```

Reason:

```text
end crystals explode
close explosion can deal heavy damage
explosion can knock you off a pillar or island
```

## 9.4 After open crystals

After the `shoot` call:

```text
get_self_status
```

Check:

```text
HP
arrows remaining
food remaining
position
whether dragon is healing
whether crystals remain
```

If HP <= 10:

```text
move_to center
eat_item
get_self_status
```

If the dragon is still healing, assume crystals remain.

---

# 10. Phase 2: caged crystals

Caged crystals are protected by iron bars.

Bow shots usually cannot hit them until the cage is opened.

## 10.1 Find caged crystals

Useful methods:

```text
scan_blocks("minecraft:iron_bars", radius=128)
scan_nearby_entities("minecraft:end_crystal")
visual/arena logic: tallest pillars often have cages
```

If `shoot(end_crystal, 8)` destroyed open crystals but some crystals remain, they are likely caged.

## 10.2 Caged crystal procedure

For each caged crystal:

```text
1. Move/climb to the top area of the pillar.
2. Mine/open enough iron bars to expose the crystal.
3. Retreat away from the crystal.
4. Shoot the crystal from range.
5. Descend/reposition safely.
6. Check HP.
```

## 10.3 Move to pillar top

Use:

```text
move_to x=<pillar_top_x> y=<pillar_top_y + 1> z=<pillar_top_z>
```

Notes:

```text
Navigation may pillar up using your blocks.
This consumes cobblestone/blocks.
Do not linger on temporary cobblestone pillars.
The dragon can destroy many non-End blocks if it flies through them.
```

If climbing path becomes unsafe:

```text
move_to back down
eat if needed
retry from a safer side
```

## 10.4 Mine iron bars

Use:

```text
auto_mine("minecraft:iron_bars")
```

or, if auto_mine is unavailable:

```text
break_block individual iron bars blocking line of sight
```

Goal:

```text
open the cage enough to shoot the crystal
```

You do not need to remove every iron bar.

Do not mine:

```text
obsidian pillar unnecessarily
bedrock
the crystal itself by hand
blocks under your feet if it creates fall risk
```

## 10.5 Retreat before destroying crystal

After opening cage:

```text
move_to away from crystal at least 8-12 blocks
```

Best:

```text
descend to ground or a wide safe ledge
stand on solid terrain
avoid pillar edge
avoid void-facing direction
```

Then:

```text
equip_item bow
shoot("minecraft:end_crystal", 1)
```

Do not shoot while standing next to the crystal.

## 10.6 After each caged crystal

After each caged crystal:

```text
get_self_status
```

If HP <= 10:

```text
move_to center
eat_item
get_self_status
```

If arrows are low:

```text
continue only if enough arrows remain for final dragon fight
```

If all crystals are destroyed:

```text
begin dragon damage phase
```

## 10.7 If dragon attacks while on pillar

If dragon strafes or knocks you while climbing/mining:

```text
finish opening only if HP is safe
otherwise descend immediately
move_to center
eat_item
retry later
```

Do not fight the dragon from the pillar top.

The pillar top is dangerous because:

```text
fall damage
knockback
crystal explosion
void exposure
limited movement
```

---

# 11. Verify crystals are gone

Before focusing the dragon, verify no crystals remain.

Signs crystals remain:

```text
dragon receives healing beam
dragon HP increases/regenerates
shoot(end_crystal, ...) still finds targets
visible crystals remain on pillars
scan_nearby_entities finds end_crystal
```

If any crystal remains:

```text
destroy it before attacking dragon
```

Do not waste arrows or melee time on the dragon until crystals are gone.

---

# 12. Phase 3: killing the dragon

Once crystals are gone, the fight alternates between:

```text
flying phase
perched phase
```

Use different tools for each.

---

# 13. Flying dragon phase

## 13.1 Use bow

When dragon is flying:

```text
equip_item bow
shoot("minecraft:ender_dragon")
```

or:

```text
shoot(ender_dragon)
```

Use bow because:

```text
dragon is airborne
melee cannot reliably reach
arrows can chip HP during flight
```

## 13.2 Aim expectations

Dragon hit behavior:

```text
head shots are best
body shots may deal reduced damage
misses are normal
```

Do not panic if damage is slow.

The real damage often happens during perch.

## 13.3 Do not chase to the edge

If the dragon flies near the island rim:

```text
do not follow to edge
stay near center
shoot only from safe terrain
```

The dragon will return.

## 13.4 Between ranged calls

After each `shoot` call or small shooting sequence:

```text
get_self_status
```

If HP <= 10:

```text
move_to center
eat_item
get_self_status
```

If arrows are low:

```text
save remaining arrows
wait for perch
use melee during perch
```

---

# 14. Perched dragon phase

The dragon periodically lands at the central bedrock fountain.

This is the best melee window.

## 14.1 Recognize perch

Perch signs:

```text
dragon is at central fountain
dragon is low/near ground
dragon is not flying around pillars
dragon stays in place briefly
```

When perched:

```text
switch to sword
hunt dragon
```

## 14.2 Use melee during perch

Do:

```text
equip_item diamond_sword
hunt("minecraft:ender_dragon")
```

or:

```text
hunt(ender_dragon)
```

Why:

```text
melee deals strong damage during perch
arrows during perch are often wasteful or unreliable
```

## 14.3 Perch positioning

Fight from:

```text
central area
side of fountain
solid ground
not inside dragon breath
not at island rim
```

Do not stand:

```text
in purple breath
inside the portal structure after it opens
near void edge
under unstable blocks
```

## 14.4 Back off when dragon takes off

When dragon leaves perch:

```text
stop melee chase
move_to 10+ blocks sideways or toward center
get_self_status
eat if needed
equip bow for flying phase
```

Do not chase the dragon away from the center.

---

# 15. Dragon attacks and responses

| Attack | Danger | Correct response |
|---|---|---|
| Dive/charge | Heavy damage and knockback | Stay near center; if hit, move_to center and eat. |
| Dragon breath | Lingering purple cloud, damage over time | Move sideways immediately; never stand or hunt in purple. |
| Wing buffet while perched | Damage and knockback | Expected during melee; eat between perches. |
| Contact/body collision | Damage and displacement | Keep HP high, avoid edges, reposition after hit. |
| Knockback near rim | Void death risk | Avoid rim; move_to center immediately. |

## 15.1 Dragon breath rule

Purple breath clouds are dangerous.

If in breath:

```text
move_to sideways immediately
```

Do not:

```text
continue hunting
stand still to shoot
eat inside the cloud if you can move first
```

Correct order:

```text
move out of breath
then eat
then re-engage
```

## 15.2 Dive/charge rule

The dragon may launch you.

Prevention:

```text
fight near center
keep HP high
avoid rim
```

If knocked:

```text
move_to center
get_self_status
eat_item if damaged
```

## 15.3 Perch damage rule

During perch melee, taking some damage is normal.

But:

```text
do not stay if HP <= 10
do not fight in breath
do not chase after takeoff
```

---

# 16. HP and food management

## 16.1 Boss HP thresholds

Use these boss thresholds:

| HP | Action |
|---:|---|
| 18-20 | Continue safely. |
| 14-17 | Eat before climbing pillars or meleeing perch. |
| 11-13 | Disengage soon; eat before next action. |
| <= 10 | Mandatory retreat/eat. |
| <= 6 | Emergency; use golden apple if available. |
| <= 4 | Do not attack; escape and heal immediately. |

## 16.2 Retreat location

If HP is low:

```text
move_to central safe area
avoid breath clouds
avoid endermen
avoid island edge
eat_item
get_self_status
```

A good retreat point is:

```text
near but not inside the central fountain area
on solid end stone
away from purple breath
away from rim
```

## 16.3 Golden apple usage

Use golden apple if:

```text
HP <= 6
dragon fight is ongoing
you are knocked into danger
you are surrounded by endermen
you need to survive while retreating
food healing is too slow
```

Do not waste golden apple at high HP.

## 16.4 Food after every phase

After:

```text
open crystal batch
each caged crystal
each perch phase
any heavy hit
any breath escape
```

call:

```text
get_self_status
```

Then eat if needed.

---

# 17. Arrow management

## 17.1 Minimum arrows

Minimum:

```text
32 arrows
```

Preferred:

```text
64+ arrows
```

Why:

```text
10 crystals
missed crystal shots
flying dragon shots
possible missed dragon shots
```

## 17.2 Arrow priority

Highest priority:

```text
end crystals
```

Then:

```text
flying dragon
```

Do not waste arrows on:

```text
endermen
perched dragon if melee is possible
random mobs
```

## 17.3 If arrows run low

If arrows are low after crystals:

```text
wait for perch
use sword/hunt during perch
shoot only when safe and worthwhile
```

If arrows run out before crystals are gone:

```text
problem is serious
do not approach crystals point-blank
look for safe way to open/shoot remaining crystal if possible
or recover arrows/supplies if possible
```

Do not punch crystals unless absolutely unavoidable and safe distance can be created, which is rarely true.

---

# 18. Endermen management

Endermen fill the End island.

For Numen:

```text
Endermen do not aggro from looking.
They aggro when hit.
```

## 18.1 Avoid accidental hits

During dragon fight:

```text
do not intentionally hit endermen
do not use unnecessary melee near groups
avoid dragging hunt path through endermen
```

## 18.2 If one enderman aggroes

If a single enderman attacks:

```text
equip_item sword
hunt("minecraft:enderman")
get_self_status
eat if needed
return to dragon fight
```

Fight on safe ground.

Do not fight endermen at the rim.

## 18.3 If multiple endermen aggro

If multiple endermen attack:

```text
move_to center or open safe area
hunt one at a time
eat between kills
do not continue dragon damage until stable
```

If HP <= 10:

```text
retreat/eat
```

The dragon can still attack while endermen are distracting you.

---

# 19. Positioning rules

## 19.1 Safe zones

Prefer:

```text
central island
near bedrock fountain
wide end stone terrain
behind pillars only if not near rim
```

## 19.2 Dangerous zones

Avoid:

```text
island edge
void bridges
spawn platform edge
top of pillars after cage is opened
inside dragon breath cloud
between dragon and void
narrow temporary block paths
```

## 19.3 Movement after every hit

If hit by dragon:

```text
move_to center or stable ground
get_self_status
eat if needed
```

Do not immediately retaliate if the hit moved you toward the rim.

## 19.4 Pillar top rule

Only go up pillars to handle caged crystals.

Do not:

```text
fight dragon from pillar top
linger after opening cage
shoot crystal while standing next to it
stand near edge after crystal explosion
```

---

# 20. Detailed fight algorithm

Use this exact high-level algorithm.

## 20.1 Before entering End

```text
load_skill(name="combat_basics")
get_self_status

if HP < 18:
    eat_item
    get_self_status

verify:
    diamond_sword or better
    bow
    arrows >= 32
    blocks >= 128
    cooked_food >= 32 preferred
    armor equipped
    pickaxe/auto_mine available

equip_item diamond_sword
enter End portal
```

## 20.2 Arrival

```text
get_self_status

if not on central island:
    move_to x=0 y=62 z=0

get_self_status

if HP < 18:
    eat_item
```

## 20.3 Open crystals

```text
equip_item bow
shoot("minecraft:end_crystal", 8)
get_self_status

if HP <= 10:
    move_to center
    eat_item
```

## 20.4 Caged crystals

For each caged crystal:

```text
find pillar/cage
move_to x=<pillar_top_x> y=<pillar_top_y + 1> z=<pillar_top_z>
auto_mine("minecraft:iron_bars")
move_to away/down at least 8-12 blocks
equip_item bow
shoot("minecraft:end_crystal", 1)
get_self_status

if HP <= 10:
    move_to center
    eat_item
```

Repeat until no crystals remain.

## 20.5 Dragon damage loop

Loop until dragon dies:

```text
get_self_status

if HP <= 10:
    move_to center safe area
    eat_item
    get_self_status

if dragon is flying:
    equip_item bow
    shoot("minecraft:ender_dragon")
    get_self_status

if dragon is perched:
    equip_item diamond_sword
    hunt("minecraft:ender_dragon")
    get_self_status
    move_to 10+ blocks sideways/center after takeoff

if in dragon breath:
    move_to sideways out of cloud
    eat_item if needed

if enderman attacks:
    equip_item sword
    hunt("minecraft:enderman")
    get_self_status
```

Stop when:

```text
dragon HP = 0
death animation begins
```

---

# 21. Handling common problems

## 21.1 Spawn platform is far from island

Action:

```text
move_to x=0 y=62 z=0
```

If navigation bridges:

```text
let it use cobblestone
do not fight on bridge
do not stop on bridge
```

If blocks are insufficient:

```text
do not attempt risky jumps
return/recover if possible
or use any available blocks
```

## 21.2 Dragon knocks you toward edge

Action:

```text
move_to center immediately
get_self_status
eat_item if damaged
```

Do not shoot/hunt until safe.

## 21.3 Falling toward void

Emergency:

```text
move_to nearest solid ground or center if tool can recover
use ender_pearl if available and supported
```

But do not rely on recovery.

Prevention is the real solution:

```text
never fight near rim
never stand on narrow bridges during attacks
```

## 21.4 Crystal explosion hurts you

Action:

```text
move_to center/safe ground
eat_item
get_self_status
```

Then continue only if HP is safe.

Cause likely:

```text
you were too close
you shot from pillar top
you failed to retreat
```

Fix:

```text
retreat farther before shooting next crystal
```

## 21.5 Still seeing dragon healing

Cause:

```text
one or more crystals remain
```

Action:

```text
stop attacking dragon
scan/shoot/find remaining end_crystal
destroy it
```

## 21.6 Cannot hit caged crystal

Cause:

```text
iron bars block line of sight
```

Action:

```text
move_to pillar top
auto_mine iron_bars
retreat
shoot crystal
```

## 21.7 Arrows low before dragon dies

If crystals are gone:

```text
save arrows
wait for perch
use sword/hunt during perch
```

If crystals are not gone:

```text
prioritize remaining crystals
do not waste arrows on dragon
```

## 21.8 Food low

If food is low mid-fight:

```text
play conservatively
avoid risky pillar climbing
avoid melee unless dragon is safely perched
eat only when needed but before critical
```

If food is gone:

```text
this is dangerous
avoid further damage
finish only if dragon is nearly dead and safe
otherwise retreat/recover if possible
```

## 21.9 Aggroed endermen interrupt fight

Action:

```text
move_to safe central ground
hunt endermen one at a time
eat after
resume dragon fight
```

Do not fight endermen near void.

## 21.10 Dragon breath covers fountain

If dragon is perched but breath is covering melee area:

```text
do not hunt inside breath
move_to side
wait for cloud to clear
shoot if dragon takes off
melee only when safe
```

## 21.11 Bow not equipped or shoot fails

Action:

```text
get_self_status
equip_item bow
verify arrows
retry shoot
```

If no arrows:

```text
do not call shoot again
switch to melee only if target is dragon perched or enderman
```

## 21.12 Wrong item in hand after mining cage

After mining iron bars:

```text
equip_item bow
```

After shooting crystal and returning to dragon:

```text
equip_item bow for flying phase
equip_item sword for perched phase
```

---

# 22. Death handling

If you die:

```text
the run is at severe risk
items may be lost
void deaths usually destroy everything
```

If death was not void and owner can recover gear:

```text
recover items
get_self_status
re-verify full packlist
reload dragon_combat
return through stronghold End portal
```

Important:

```text
The dragon usually keeps damage already dealt.
Crystals destroyed remain destroyed.
But do not re-enter without gear/food/arrows/blocks.
```

If death was in void:

```text
assume carried items are gone
rebuild gear and supplies before retrying
```

Do not continue the route as if inventory is intact.

---

# 23. After dragon death

When dragon HP reaches 0:

```text
stop attacking
move_to safe central area
get_self_status
```

Wait for:

```text
death animation
XP explosion if applicable
exit portal opening
dragon egg appearing
```

## 23.1 Congratulate owner

Say:

```text
Congratulations — the Ender Dragon is defeated.
```

## 23.2 Exit portal

The central bedrock fountain becomes the exit portal.

When owner is ready:

```text
move_to into the exit portal
```

This returns to the Overworld spawn/credits sequence depending on game behavior.

## 23.3 Dragon egg

Dragon egg appears on top of the portal.

Do not punch it unless asked.

Reason:

```text
dragon egg teleports when punched
egg collection is a separate trophy task
owner may want to handle it
```

## 23.4 Mark plan complete

After dragon death:

```text
mark endgame plan completed in todowrite
```

If using todo phases:

```text
phase 6 dragon_combat = completed
overall dragon route = completed
```

---

# 24. Final response after victory

After completion, report briefly:

```text
Done — the Ender Dragon is defeated.
The exit portal is open and the dragon egg appeared.
Congratulations!
```

If relevant, add:

```text
I am at the central fountain and ready to exit when you are.
```

---

# 25. Quick reference

## 25.1 Full phase summary

```text
get_self_status
verify sword/bow/arrows/blocks/food/armor
enter End
move_to x=0 y=62 z=0
equip bow
shoot end_crystal 8
handle each caged crystal:
    move_to pillar top
    auto_mine iron_bars
    retreat
    shoot crystal 1
verify no crystals remain
loop:
    if flying -> shoot dragon
    if perched -> equip sword -> hunt dragon
    if HP <= 10 -> retreat/eat
    if breath -> move sideways
    if endermen aggro -> hunt them safely
finish when dragon HP = 0
congratulate owner
```

## 25.2 Highest-priority reminders

```text
1. Crystals first.
2. Never fight near void.
3. Never pop crystals close range.
4. Check HP between every tool call.
5. HP <= 10 means retreat and eat.
6. Bow for crystals and flying dragon.
7. Sword/hunt for perched dragon.
8. Move out of purple breath immediately.
9. Do not chase dragon to island rim.
10. Do not punch dragon egg unless asked.
```
