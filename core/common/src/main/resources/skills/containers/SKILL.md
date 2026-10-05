---
name: containers
description: How to move items in/out of any container or machine GUI — chest, barrel, shulker, furnace, modded machine. numen.use.block opens it and hands back its Window; w:put / w:take move items of a kind, w:quick / w:move one slot, w:close; crafting by laying a recipe into the grid, smelting by loading a furnace, and error recovery.
---

# Skill: containers

You move items through real GUIs, exactly like a player: open the block, look at the slots, move items, close. There is no per-item black box — you drive the menu yourself, which works for **any** container or machine (vanilla or modded) and lets you **see and fix** what goes wrong.

## The loop

1. **Open** — `numen.use.block({x = 120, y = 64, z = -35})` on the container block (it does not travel: first `numen.move.to({x = 120, y = 64, z = -35}, {arrive = "use"})`, which stands you where the container is in sight and in reach). It opens the GUI, leaves it open and hands back its **Window**: every slot (`index`, `side`, `item`, `count`, `output` for take-only slots), the cursor and the machine's numbers.
2. **Move** — the Window's methods (below). Several moves in one program run in order.
3. **Verify** — each result already says what happened; `numen.gui.view()` reads the window again when you need to re-check.
4. **Close** — `w:close()` when done. (It also auto-closes if you walk away.)

The methods are the `numen.gui` functions (`w:put` calls `numen.gui.put`), and both act on the window open now.

## Moving items

- **`w:put(item, count?)`** — put items of one kind from your inventory into the window: all of them, or `count`. The window routes each stack like a shift-click: raw iron into a furnace's input, coal into its fuel slot, anything into a chest's free slots. Returns how many went in.
- **`w:take(item, count?)`** — take items of one kind out of the window into your inventory. Returns how many came out.
- **`w:quick(slot)`** — shift-click one slot: its whole stack goes to the other side, routed by the menu.
- **`w:move(from, to, {count = N}?)`** — put a slot's items into one EXACT slot: empty → moves there; same item → merges; **a different item → the two slots SWAP**. Add `count` to move only part of the stack.

**Store all your cobblestone, take 10 iron:**
```lua
local w = numen.use.block({x = 120, y = 64, z = -35})
w:put("minecraft:cobblestone")
w:take("minecraft:iron_ingot", 10)
w:close()
```

**How much is in there** — `numen.gui.count(item)` counts the window's own side (not your inventory):
```lua
local w = numen.use.block({x = 120, y = 64, z = -35})
print(numen.gui.count("minecraft:wheat"))
w:close()
```

**Swap two slots** — a slot holding a different item (e.g. swap a tool into a slot):
```lua
numen.gui.view():move(38, 12)
```

## Reading machine progress

The Window's `data` is the menu's synced ints (the same numbers a real GUI uses to draw progress/fuel/energy bars), separate from the item slots. Meaning is machine-specific; for a **vanilla furnace** they are `{litTime, litDuration, cookProgress, cookTotal}`, so:
- cook % = `cookProgress / cookTotal` (the smelting arrow),
- still burning if `litTime > 0`.

So to check a furnace: `numen.use.block` it and read the input count (slots) and `data`. The output slot filling up is also a clear "an item finished" signal.

## Crafting

For an ordinary [crafting] recipe `numen.inv.make` does all of this for you (it picks the recipe, finds and opens a crafting table, and crafts), and `numen.inv.craft` crafts one recipe in the grid you have open. Lay a grid by hand only for a modded grid or when you want to see each step:

1. **`numen.inv.recipes`** the item — the ingredients and, for shaped recipes, the grid layout.
2. **Open the grid:**
   - **≤2×2 recipe** (planks, sticks, torches, a crafting table): NO table needed — `numen.gui.view()` with nothing open is your own 2×2 grid.
   - **3×3 recipe** (most tools, etc.): `numen.use.block` a crafting table; `numen.gui.view` draws its grid as a 2D map of slot numbers.
3. **Place the recipe** — lay its layout onto the grid **top-left**: one `w:move(from, cell, {count = 1})` per NON-EMPTY cell. A 1-ingredient recipe = ONE cell; don't fill the rest.
4. **Take the result** — `w:quick` the result slot (this performs the craft). Repeat steps 3–4 for each item you want.
5. For a table, `w:close()` when done.

**Watch the cells** — the grid is wider than a small recipe. A 2-wide recipe in a 3-wide table uses the top-left cells, **NOT** consecutive slot numbers (e.g. a 2×2 recipe in a 3×3 grid skips the right column). Read the 2D map from `numen.gui.view` and match the layout cell-for-cell; this is the easiest thing to get wrong.

**Make many at once** — put a *stack* in each cell, then ONE `w:quick` of the result crafts over and over until a cell runs dry. E.g. 7 logs in one grid cell → a single take = 28 planks; 8 planks in each of the two stick cells → one take = 32 sticks.

*Example — sticks (2 oak_planks stacked vertically), your own grid with cells 1–4 and the result in slot 0, planks in slot 20:*
```lua
local w = numen.gui.view()
w:move(20, 1, {count = 1})
w:move(20, 3, {count = 1})
w:quick(0)
```

## Smelting

Smelting is NOT crafting. `numen.inv.smelt` loads a furnace, waits and takes the output; by hand it is just two slots:
  1. `local w = numen.use.block({x = 120, y = 64, z = -35})` on the furnace / blast furnace / smoker.
  2. Load the input: `w:put("minecraft:raw_iron")` — the menu routes it to the top input slot.
  3. Add fuel: `w:put("minecraft:coal", 2)` — it routes to the bottom fuel slot. **Fuel rule**: 1 coal/charcoal smelts 8 items; a log/plank ~1.5, so add ~⌈N/8⌉ coal.
  4. `w:close()`, then set a timer for roughly when the batch should be done, like `numen.task.timer("collect the iron from the furnace", {after = 90})` — a vanilla furnace takes ~10s per item, a blast furnace / smoker ~5s. The timer doesn't occupy your body, so walk away and do something else; don't stand there polling.
  5. When the `timer` event fires, come back and re-open the furnace. The timer is a reminder, not proof: the Window's slots and `data` show the real state. Not done? Set a shorter timer and leave again.
  6. `w:take("minecraft:iron_ingot")` collects the output (awards the smelting XP). `w:close()`.

## Modded machines (hand-load)

A custom modded machine has its own slots. For a single input, `w:put` it — the menu routes it. For a machine with several specific input slots, read the slots and `w:move` each input into its own slot. A modded *crafting* grid works the same as a vanilla one (see Crafting above).

## Error recovery (your advantage)

Every move's result tells you its outcome, and `numen.gui.view()` reads the window any time — you are never blind:

- **put fails, no room** → the window has no free slot that takes it (an `output` slot takes nothing). Take something out first, or use another container.
- **take fails** → the window holds none of it (not_found), or your inventory is full.
- **Got a swap you didn't want** → `w:move` put it onto a slot holding a different item. `w:quick` instead to route it, or pick an empty slot.
- **"no GUI open"** → you didn't open one, or walked out of range and it closed. Open it again with `numen.use.block`.
- **"right-clicked tall_grass … the crosshair landed there"** → something stood between you and the container, and the click went to it. The result names the next step: `numen.work.dig` that cell, or `numen.move.to({x = 120, y = 64, z = -35}, {arrive = "use"})` to stand where another side is in sight; then `numen.use.block` again.

Always `w:close()` (or walk away) when finished so you don't leave a menu hanging.
