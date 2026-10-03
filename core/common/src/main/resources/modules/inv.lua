-- Making, storing, fetching, smelting and giving: the inv functions put together with walks and windows.
local M = {}

local function id(item)
  return item:find(":") and item or "minecraft:" .. item
end

-- how many crafting-grid cells a window has: 4 for your own inventory, 9 for a crafting table, 0 for a chest
local function grid_cells(w)
  local n = 0
  for _, s in ipairs(w.slots) do
    if s.side == "grid" then
      n = n + 1
    end
  end
  return n
end

-- runs work(), closes the window you opened whatever happened, and raises work's error as it is
local function closing(work)
  local ok, out = pcall(work)
  numen.gui.close()
  if not ok then
    error(out, 0)
  end
  return out
end

-- walk to a block and right-click it open; a block that opens no window raises failed
local function open(at)
  numen.move.to(at, {arrive = "use"})
  local w = numen.use.block(at)
  if not w.slots then
    local p = at.pos or at
    raise("failed", string.format("the block at %d %d %d opened no window", p.x, p.y, p.z))
  end
  return w
end

-- a free cell beside you to put a block down on: air, with something solid under it
local function beside()
  local me = numen.status.self().pos
  local x, y, z = math.floor(me.x), math.floor(me.y), math.floor(me.z)
  for _, d in ipairs({{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}}) do
    local cell = {x = x + d[1], y = y, z = z + d[2]}
    if numen.scan.block(cell).is_air and numen.scan.block({x = cell.x, y = y - 1, z = cell.z}).is_solid then
      return cell
    end
  end
  raise("failed", "no free cell beside you to put a crafting table down on", "step onto open ground and try again")
end

-- a crafting table window: the one open, the nearest table within 16 blocks walked to, or one you carry put down
-- beside you
local function table_window()
  local w = numen.gui.view()
  if grid_cells(w) >= 9 then
    return w
  end
  local found = numen.scan.blocks("minecraft:crafting_table", {radius = 16})
  if #found > 0 then
    return open(found[1].nearest)
  end
  if numen.inv.count("minecraft:crafting_table") == 0 then
    raise("not_found", "no crafting table within 16 blocks and none in your pack",
        "numen.inv.make(\"minecraft:crafting_table\") makes one from 4 planks")
  end
  local cell = beside()
  numen.build.place({{name = "minecraft:crafting_table", pos = cell}})
  return open(cell)
end

---Make count of item (default 1): pick a crafting recipe you have the materials for (numen.inv.recipes), and craft it
---with numen.inv.craft until there are enough. A 2x2 recipe crafts in your own grid; a 3x3 one in the crafting table
---open, else the nearest within 16 blocks (walked to), else one you carry put down beside you; a table it opened is
---closed again. Short of materials for every recipe raises no_material with the shortfall of the nearest one
---(err.data.missing); no crafting recipe at all raises not_found. A craft that fails raises its error as it is.
---@param item string
---@param count? integer
---@return integer made How many it made.
function M.make(item, count)
  item = id(item)
  count = count or 1
  local recipe, closest = nil, nil
  for _, r in ipairs(numen.inv.recipes(item)) do
    if r.station == "crafting" and r.grid > 0 then
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
    raise("not_found", "no crafting recipe makes " .. item, "numen.inv.recipes(\"" .. item .. "\") shows how it is made")
  end
  local function craft()
    local made = 0
    while made < count do
      made = made + numen.inv.craft(recipe.id, {count = count - made}).crafted
    end
    return made
  end
  if recipe.grid == 3 then
    local open_before = grid_cells(numen.gui.view()) >= 9
    table_window()
    if open_before then
      return craft()
    end
    return closing(craft)
  end
  if grid_cells(numen.gui.view()) == 0 then
    numen.gui.close()
  end
  return craft()
end

---Walk to a container (a chest, a barrel …), open it, put count of item in (all you carry by default) and close it.
---@param at Pos|Block The container.
---@param item string
---@param count? integer
---@return integer moved How many went in.
function M.store(at, item, count)
  local w = open(at)
  return closing(function()
    return w:put(id(item), count)
  end)
end

---Walk to a container, open it, take count of item out (all it holds by default) and close it.
---@param at Pos|Block The container.
---@param item string
---@param count? integer
---@return integer moved How many came out.
function M.fetch(at, item, count)
  local w = open(at)
  return closing(function()
    return w:take(id(item), count)
  end)
end

---Smelt count of item (all you carry by default) in a furnace, blast furnace or smoker: walk to it, put the item in
---and opts.fuel (default minecraft:coal, one per 8 items), stand by until the input is used up and nothing is cooking
---(numen.time.wait_until, opts.timeout seconds, default 12 per item), take every output out and close it.
---@param furnace Pos|Block
---@param item string
---@param count? integer
---@param opts? {fuel?: string, timeout?: number}
---@return integer took How many items it took out.
function M.smelt(furnace, item, count, opts)
  item = id(item)
  opts = opts or {}
  count = count or numen.inv.count(item)
  if count == 0 then
    raise("no_material", "you carry no " .. item .. " to smelt")
  end
  local w = open(furnace)
  return closing(function()
    w:put(item, count)
    w:put(id(opts.fuel or "minecraft:coal"), math.ceil(count / 8))
    numen.time.wait_until(function()
      local now = numen.gui.view()
      return numen.gui.count(item) == 0 and (now.data[3] or 0) == 0
    end, {every = 2, timeout = opts.timeout or count * 12})
    local took = 0
    for _, s in ipairs(numen.gui.view().slots) do
      if s.output and s.item then
        took = took + s.count
        numen.gui.quick(s.index)
      end
    end
    return took
  end)
end

---Give count of item (all you carry by default) to a player or another entity: walk within 2 blocks of them
---(numen.move.to with arrive "near") and drop it there for them to pick up.
---@param target Entity|Pos Who gets it (an Entity from numen.scan.entities, or where they stand).
---@param item string
---@param count? integer
---@return integer dropped How many it dropped.
function M.give(target, item, count)
  numen.move.to(target.pos or target, {arrive = "near", range = 2})
  if count then
    return numen.inv.drop(id(item), {count = count}).dropped
  end
  return numen.inv.drop(id(item)).dropped
end

return M
