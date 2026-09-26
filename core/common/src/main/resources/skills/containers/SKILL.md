---
name: containers
description: How to move items in/out of any container or machine GUI — chest, barrel, shulker, furnace, modded machine. The open (use block) → use gui → use shift / use transfer → use close loop, one move per line; crafting by laying a recipe into the grid, smelting by loading a furnace, and error recovery.
---

# Skill: containers

You move items through real GUIs, exactly like a player: open the block, look at the slots, move items, close. There is no per-item black box — you drive the menu yourself, one move per line, which works for **any** container or machine (vanilla or modded) and lets you **see and fix** what goes wrong.

## The loop

1. **Open** — `use block right 120 64 -35` on the container block (goto it first: it does not travel). This opens its GUI and leaves it open.
2. **Look** — `use gui`. Lists every slot: `index: item xN`, which side (container vs your inventory), and `[output]` for take-only slots (a furnace result, a machine product).
3. **Move** — one move per line (see below). To move several stacks, send several lines in the same turn; they run in order.
4. **Verify** — each result already says what happened; `use gui` again only if you need to re-check.
5. **Close** — `use close` when done. (It also auto-closes if you walk away.)

## Moving items: two moves

- **`use shift`** — shift-click a slot: its whole stack goes to the *other section*, routed by the menu (into a chest, out of one, a smeltable into a furnace's input, coal into its fuel slot). This is the easy bulk deposit/withdraw — you don't pick a slot.
- **`use transfer`** — put a slot's items into one EXACT slot: empty → moves there; same item → merges; **a different item → the two slots SWAP**. Add `--count` to move only part of the stack (into an empty slot or onto the same item).

Each result spells out what actually happened — how much moved, a merge, a swap, or *why nothing moved* (full / output-only slot) — so you rarely need to re-inspect.

**Deposit or take whole stacks** — one line per stack, all in the same turn:
```
use shift 5
use shift 6
```

**Exact count** — the stack's slot, a free slot, how many:
```
use transfer 3 40 --count 10
```

**Swap two slots** — a slot holding a different item (e.g. swap a tool into a slot):
```
use transfer 38 12
```

## Reading machine progress

`use gui` also shows a `data values: [...]` line — the menu's synced ints (the same numbers a real GUI uses to draw progress/fuel/energy bars), separate from the item slots. Meaning is machine-specific; for a **vanilla furnace** they are `[litTime, litDuration, cookProgress, cookTotal]`, so:
- cook % = `cookProgress / cookTotal` (the smelting arrow),
- still burning if `litTime > 0`.

So to check a furnace: `use block` it → `use gui` → read the input count (slots) + the data values. The output slot filling up is also a clear "an item finished" signal.

## Crafting

For an ordinary [crafting] recipe `inv craft` does all of this for you. Lay a grid by hand only for a modded grid or when you want to see each step:

1. **`inv recipe`** the item — the ingredients and, for shaped recipes, the grid layout.
2. **Open the grid:**
   - **≤2×2 recipe** (planks, sticks, torches, a crafting table): NO table needed — `use gui` with nothing open shows your own 2×2 grid.
   - **3×3 recipe** (most tools, etc.): `use block` a crafting table, then `use gui` — it draws the grid as a 2D map of slot numbers.
3. **Place the recipe** — lay its layout onto the grid **top-left**: one `use transfer` with `--count 1` per NON-EMPTY cell, all in the same turn. A 1-ingredient recipe = ONE cell; don't fill the rest.
4. **Take the result** — `use shift` the result slot (this performs the craft). Repeat steps 3–4 for each item you want.
5. For a table, `use close` when done.

**Watch the cells** — the grid is wider than a small recipe. A 2-wide recipe in a 3-wide table uses the top-left cells, **NOT** consecutive slot numbers (e.g. a 2×2 recipe in a 3×3 grid skips the right column). Read the 2D map from `use gui` and match the layout cell-for-cell; this is the easiest thing to get wrong.

**Make many at once** — put a *stack* in each cell, then ONE `use shift` of the result crafts over and over until a cell runs dry. E.g. 7 logs in one grid cell → a single take = 28 planks; 8 planks in each of the two stick cells → one take = 32 sticks. Far fewer calls than one set at a time.

*Example — sticks (2 oak_planks stacked vertically), your own grid with cells 1–4 and the result in slot 0, planks in slot 20:*
```
use transfer 20 1 --count 1
use transfer 20 3 --count 1
use shift 0
```

## Smelting

Smelting is NOT crafting — there's no auto-tool, you load the furnace yourself (it's just two slots):
  1. `use block` the furnace / blast furnace / smoker.
  2. Load the input: `use shift` the raw item's slot — the menu routes it to the top input slot.
  3. Add fuel: `use shift` the coal's slot — it routes to the bottom fuel slot. **Fuel rule**: 1 coal/charcoal smelts 8 items; a log/plank ~1.5, so add ~⌈N/8⌉ coal.
  4. `use close`, then set a timer for roughly when the batch should be done, like `task timer 90 collect the iron from the furnace` — a vanilla furnace takes ~10s per item, a blast furnace / smoker ~5s. The timer doesn't occupy your body, so walk away and do something else; don't stand there polling.
  5. When the `timer` event fires, come back and re-open the furnace. The timer is a reminder, not proof: `use gui` shows the real state (`data values` = `[litTime, litDuration, cookProgress, cookTotal]`). Not done? Set a shorter timer and leave again.
  6. `use shift` the output slot to collect (awards the smelting XP). `use close`.

## Modded machines (hand-load)

A custom modded machine has its own slots. For a single input, `use shift` it — the menu routes it. For a machine with several specific input slots, `use gui` then `use transfer` each input into its own slot. A modded *crafting* grid works the same as a vanilla one (see Crafting above): `use gui` draws it as a 2D map of slot numbers — lay the recipe onto it cell-for-cell (smaller recipe → top-left), one `use transfer` with `--count 1` per non-empty cell.

## Common patterns

**Store everything of one type into the nearest chest** — open it, find your cobblestone stacks under "your inventory" in `use gui`, shift each one, close:
```
use block right 120 64 -35
use gui
use shift 30
use shift 31
use close
```

**Take 10 iron from a chest (exact)** — the iron's slot, a free slot of yours:
```
use block right 120 64 -35
use gui
use transfer 4 45 --count 10
use close
```

**Empty a furnace's output:** `use gui` → the `[output]` slot → `use shift 2`.

## Error recovery (your advantage)

Every move's result tells you its outcome, and you can always `use gui` — you are never blind:

- **"nothing moved"** → the destination is full, or it's an `[output]` slot you can't put INTO. Try another slot or another container.
- **Chest is full** → `use gui` shows no empty container slots. Find another chest (scan / known_blocks) or take something out first.
- **Got a swap you didn't want** → `use transfer` put it onto a slot holding a different item. `use shift` instead to route it, or pick an empty slot.
- **"no GUI open"** → you didn't open one, or walked out of range and it closed. Open it again with `use block`.

Always `use close` (or walk away) when finished so you don't leave a menu hanging.
