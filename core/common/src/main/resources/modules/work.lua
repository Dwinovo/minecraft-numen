-- Picking up and digging out: walk onto the dropped items lying around, dig out a whole area.
local M = {}

---Pick up the dropped items around you, nearest first, walking onto each with move.goto_. A fresh drop cannot be
---picked up for a few ticks (its pickup_delay): standing on it, it walks onto it again until it is taken. An item
---with no way to it (move.goto_ fails with no_path) is passed over and the rest are picked up; at the end the ones
---passed over raise no_path, with them in err.data.left. An item still there after walking onto it with no delay
---left (a full pack, or a spot you cannot stand in) raises failed; any other error of a walk stops here as it is.
---@param opts? table radius = how far to look (default 8); the rest are route flags for the walks (alter = "natural" lets it dig and pillar to drops in a pit).
---@return integer picked How many items it walked onto that are gone now.
function M.collect(opts)
  local walk = {}
  for k, v in pairs(opts or {}) do
    walk[k] = v
  end
  local radius = walk.radius or 8
  walk.radius = nil
  local walked = {}
  local unreachable = {}
  while true do
    local items = {}
    for _, item in ipairs(scan.entities("item", {radius = radius})) do
      if not unreachable[item.id] then
        items[#items + 1] = item
      end
    end
    if #items == 0 then
      local picked = 0
      for _, n in pairs(walked) do
        picked = picked + n
      end
      local left = {}
      for _, item in pairs(unreachable) do
        left[#left + 1] = item
      end
      if #left > 0 then
        local p = left[1].pos
        raise("no_path", "picked up " .. picked .. " item(s); no way to the " .. #left .. " left, the nearest "
            .. left[1].item .. " x" .. left[1].count, string.format("move.goto_({x = %d, y = %d, z = %d}, {alter = "
            .. "\"natural\"})", math.floor(p.x), math.floor(p.y), math.floor(p.z)), {picked = picked, left = left})
      end
      return picked
    end
    local item = items[1]
    if walked[item.id] and item.pickup_delay == 0 then
      raise("failed", item.item .. " x" .. item.count .. " is still there after walking onto it: a full pack, or a "
          .. "spot you cannot stand in", nil, {item = item})
    end
    if item.pickup_delay > 100 then
      raise("failed", item.item .. " x" .. item.count .. " cannot be picked up for another " .. item.pickup_delay
          .. " ticks", nil, {item = item})
    end
    local ok, err = pcall(move.goto_, item.pos, walk)
    if ok then
      walked[item.id] = item.count
    elseif err.kind == "no_path" then
      unreachable[item.id] = item
    else
      error(err, 0)
    end
  end
end

---Dig out an area: walk within reach of what is left of it (move.goto_ with arrive "dig" and alter "natural"), dig
---what is in reach (work.dig), pick up the drops (work.collect with alter "natural"), until nothing of it is left. A
---step that fails raises its error as it is.
---@param where string An area of your owner's from area.list(), or one part of it ("ores/g3").
---@return integer dug How many cells it dug.
function M.mine(where)
  local dug = 0
  while area.has(where) do
    move.goto_(where, {arrive = "dig", alter = "natural"})
    dug = dug + work.dig(where).dug
    M.collect({alter = "natural"})
  end
  return dug
end

return M
