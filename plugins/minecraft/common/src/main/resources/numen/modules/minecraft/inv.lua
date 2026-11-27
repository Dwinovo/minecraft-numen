-- Making, smelting and giving: the inv functions put together with walks, crafting tables and furnaces.
local M = {}

local function id(item)
  return item:find(":") and item or "minecraft:" .. item
end

-- a free cell beside you to put a block down on: air, with something solid under it
local function beside()
  local me = minecraft.status.self().pos
  local x, y, z = math.floor(me.x), math.floor(me.y), math.floor(me.z)
  for _, d in ipairs({{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}}) do
    local cell = {x = x + d[1], y = y, z = z + d[2]}
    if minecraft.scan.block(cell).is_air and minecraft.scan.block({x = cell.x, y = y - 1, z = cell.z}).is_solid then
      return cell
    end
  end
  raise("failed", "no free cell beside you to put a crafting table down on", "step onto open ground and try again")
end

-- the cell of a crafting table to craft at: the nearest within 16 blocks, or one you carry put down beside you
local function table_cell()
  local found = minecraft.scan.blocks("minecraft:crafting_table", {radius = 16})
  if #found > 0 then
    return found[1].nearest
  end
  if minecraft.inv.count("minecraft:crafting_table") == 0 then
    raise("not_found", "no crafting table within 16 blocks and none in your pack",
        "minecraft.inv.make(\"minecraft:crafting_table\") makes one from 4 planks")
  end
  local cell = beside()
  minecraft.build.place({{name = "minecraft:crafting_table", pos = cell}})
  return cell
end

---Make count of item (default 1): pick a crafting recipe you have the materials for (minecraft.inv.recipes), and craft it
---with minecraft.inv.craft until there are enough. A 2x2 recipe crafts in your own grid; a 3x3 one at the nearest crafting
---table within 16 blocks (minecraft.inv.craft walks to it), else at one you carry put down beside you. Short of materials
---for every recipe raises no_material with the shortfall of the nearest one (err.data.missing); no crafting recipe at
---all raises not_found. A craft that fails raises its error as it is.
---@param item string
---@param count? integer
---@return integer made How many it made.
function M.make(item, count)
  item = id(item)
  count = count or 1
  local recipe, closest = nil, nil
  for _, r in ipairs(minecraft.inv.recipes(item).recipes) do
    if r.category == "minecraft:crafting" and r.grid > 0 then
      if not r.missing then
        recipe = r
        break
      end
      if not closest or #r.missing < #closest.missing then
        closest = r
      end
    end
  end
  if not recipe then
    if closest then
      raise("no_material", "not enough materials for " .. item .. " — missing: " .. table.concat(closest.missing, ", "),
          nil, {missing = closest.missing})
    end
    raise("not_found", "no crafting recipe makes " .. item, "minecraft.inv.recipes(\"" .. item .. "\") shows how it is made")
  end
  local at = recipe.grid == 3 and table_cell() or nil
  local made = 0
  while made < count do
    made = made + minecraft.inv.craft(recipe.id, {count = count - made, at = at}).crafted
  end
  return made
end

---Smelt count of item (all you carry by default) in a furnace, blast furnace or smoker: put the item in
---(minecraft.furnace.input) and opts.fuel (minecraft.furnace.fuel, default minecraft:coal, one per 8 items), then stand by and
---take out what is cooked every 2 seconds (minecraft.furnace.take) until count items have come out. It stops waiting when
---nothing new has come out for opts.patience seconds (default 15): the fuel ran out or the item does not cook.
---@param furnace Pos|Block
---@param item string
---@param count? integer
---@param opts? {fuel?: string, patience?: number}
---@return integer took How many items it took out.
function M.smelt(furnace, item, count, opts)
  item = id(item)
  opts = opts or {}
  count = count or minecraft.inv.count(item)
  if count == 0 then
    raise("no_material", "you carry no " .. item .. " to smelt")
  end
  count = minecraft.furnace.input(furnace, item, count)
  minecraft.furnace.fuel(furnace, id(opts.fuel or "minecraft:coal"), math.ceil(count / 8))
  local took, idle = 0, 0
  while took < count and idle < (opts.patience or 15) do
    minecraft.time.wait(2)
    local got = 0
    for _, s in ipairs(minecraft.furnace.take(furnace)) do
      got = got + s.count
    end
    took = took + got
    idle = got > 0 and 0 or idle + 2
  end
  return took
end

---Give count of item (all you carry by default) to a player or another entity: walk within 2 blocks of them
---(minecraft.move.walk with a distance of 2) and drop it there for them to pick up.
---@param target Entity|Pos Who gets it (an Entity from minecraft.scan.entities, or where they stand).
---@param item string
---@param count? integer
---@return integer dropped How many it dropped.
function M.give(target, item, count)
  minecraft.move.walk(target.pos or target, 2)
  if count then
    return minecraft.inv.drop(id(item), {count = count}).dropped
  end
  return minecraft.inv.drop(id(item)).dropped
end

return M
