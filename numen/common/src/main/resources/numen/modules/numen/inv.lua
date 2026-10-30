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

-- right-click a block open (numen.use.block walks up to it first); a block that opens no window raises failed
local function open(at)
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
  -- the game's own recipe list: it alone knows the grid a recipe fits and what you lack for it, and asking it does not
  -- wait for a source on your owner's client
  for _, r in ipairs(numen.recipes_source.read(item).recipes) do
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
---(numen.move.to with a distance of 2) and drop it there for them to pick up.
---@param target Entity|Pos Who gets it (an Entity from numen.scan.entities, or where they stand).
---@param item string
---@param count? integer
---@return integer dropped How many it dropped.
function M.give(target, item, count)
  numen.move.to(target.pos or target, 2)
  if count then
    return numen.inv.drop(id(item), {count = count}).dropped
  end
  return numen.inv.drop(id(item)).dropped
end

-- ---- what the game, the mods and your owner's client know about items ----
-- numen.inv.recipes, numen.inv.uses and numen.inv.info each ask every source registered for them (numen.api.sources),
-- put the answers together and say which source gave what and which one did not answer. The sources themselves are
-- hidden functions; a plugin registers one with NumenApi.knowledgeSource.

---@class SourceReport
---@field source string The source: the plugin that registered it (numen is the game's own recipe list).
---@field status string "answered", or "missing" when it did not answer.
---@field why? string When missing: why, as the call failed (your owner's client is offline, the mod is not installed on it, it timed out).
---@field missing? string When missing: the kind of information that is therefore not in the result.
---@field not_provided string[] What this source cannot say at all, so no value for these is ever given: never guessed.
M.SourceReport = {}

---@class RecipeResult
---@field recipes Recipe[] The recipes, each saying which sources listed it.
---@field omitted? integer How many more matched but are not listed.
---@field sources SourceReport[] Every source asked and whether it answered.
M.RecipeResult = {}

---@class InfoNote
---@field source string Who wrote it.
---@field text string
M.InfoNote = {}

---@class InfoResult
---@field facts? ItemFacts The game's own facts about the item.
---@field notes InfoNote[] Descriptions of what it is for, from modpack and mod sources.
---@field sources SourceReport[] Every source asked and whether it answered.
M.InfoResult = {}

-- the function a source's call names: ns.group.fn walked from the globals
local function resolve(call)
  local fn = _ENV
  for part in call:gmatch("[^.]+") do
    fn = fn[part]
  end
  return fn
end

-- asks every source of one verb about the item; a source that does not answer is reported, never skipped silently
local function gather(kind, item)
  local pages, report = {}, {}
  for _, s in ipairs(numen.api.sources(kind)) do
    local ok, page = pcall(resolve(s.call), item)
    if ok then
      pages[#pages + 1] = {source = s.id, page = page}
      report[#report + 1] = {source = s.id, status = "answered", not_provided = s.not_provided}
    else
      if type(page) == "table" and page.kind == "bad_argument" then
        -- the item is wrong for every source alike: that is the caller's mistake, not a source that is absent
        raise("bad_argument", page.message, "numen.inv." .. kind .. "(\"minecraft:stone\")")
      end
      local why = type(page) == "table" and (page.kind .. ": " .. page.message) or tostring(page)
      report[#report + 1] = {source = s.id, status = "missing", why = why, missing = s.provides,
          not_provided = s.not_provided}
    end
  end
  return pages, report
end

-- fills what a recipe lacks from the same recipe another source listed: a value, or a list that is empty
local function fill(into, from)
  for k, v in pairs(from) do
    if k ~= "sources" and (into[k] == nil or (type(into[k]) == "table" and next(into[k]) == nil)) then
      into[k] = v
    end
  end
end

local function recipes_of(kind, item)
  local pages, report = gather(kind, id(item))
  local out, by_id, omitted = {}, {}, 0
  for _, p in ipairs(pages) do
    omitted = omitted + (p.page.omitted or 0)
    for _, r in ipairs(p.page.recipes) do
      local seen = r.id and by_id[r.id]
      if seen then
        fill(seen, r)
        seen.sources[#seen.sources + 1] = p.source
      else
        r.sources = {p.source}
        out[#out + 1] = r
        if r.id then
          by_id[r.id] = r
        end
      end
    end
  end
  return {recipes = out, omitted = omitted > 0 and omitted or nil, sources = report}
end

---How an item is made, like JEI: every recipe that outputs it, from every source (numen.api.sources("recipes") lists them).
---The game's recipe list (source numen) holds the recipes of every mod: crafting, cooking, stonecutting and smithing in
---full, a mod's machine types with their category, inputs and product only. A source on your owner's client, such as
---JEI, adds which machine does it, the category's name in their language and fluids; a recipe two sources list is one
---entry naming both. Chance, time, heat and energy are given by no source: they are not in the result, never guessed.
---Instant. At most 40 recipes per source come back, omitted says how many more matched. A source that did not answer
---is in sources with the reason and the kind of information that is missing: ask your owner to join, or go on with
---what the other sources gave. Empty recipes with every source answered means the item is mined or traded, not made.
---To make it: crafting is numen.inv.make(item, N) (picks the recipe and the grid) or numen.inv.craft(id) in the open
---grid; smelting, blasting, smoking numen.inv.smelt(furnace, input, N); stonecutter: numen.use.block it,
---numen.gui.put the input, numen.gui.choose the recipe, take the output; smithing: numen.use.block it, numen.gui.move
---template + base + addition; a mod's machine: the catalysts (or the machine block of the category), numen.use.block
---it, then the numen.gui functions.
---@param item string
---@return RecipeResult
function M.recipes(item)
  return recipes_of("recipes", item)
end

---What an item can be used for, like JEI's uses page: every recipe that takes it as an input, from every source. Same
---sources, shape and limits as numen.inv.recipes; each entry is a recipe with the item among its inputs (a tag input
---such as planks(any) counts when the item is in the tag) and says what it makes. Empty with every source answered
---means no recipe takes it; it may still be a fuel, food or a tool: numen.inv.info says.
---@param item string
---@return RecipeResult
function M.uses(item)
  return recipes_of("uses", item)
end

---What is known about an item: the game's own facts (stack size, durability, food values, the block it places, its
---item tags) and the descriptions modpack and mod sources wrote (JEI's information pages); numen.api.sources("info")
---lists the sources. It does not say how the item is made or used: that is numen.inv.recipes and numen.inv.uses.
---Instant. A source that did not answer is in sources with the reason and the kind of information that is missing.
---@param item string
---@return InfoResult
function M.info(item)
  local pages, report = gather("info", id(item))
  local result = {notes = {}, sources = report}
  for _, p in ipairs(pages) do
    result.facts = result.facts or p.page.facts
    for _, text in ipairs(p.page.notes) do
      result.notes[#result.notes + 1] = {source = p.source, text = text}
    end
  end
  return result
end

return M
