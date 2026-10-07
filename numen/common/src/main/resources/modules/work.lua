-- Picking up and digging out: walk onto the dropped items lying around, dig out a cluster.
local M = {}

---Pick up the dropped items around you, nearest first, walking onto each with numen.move.to. Given items (the drops
---numen.work.dig returned), it goes only after those of them still lying around. A fresh drop cannot be picked up for
---a few ticks (its pickup_delay): standing on it, it walks onto it again until it is taken. An item with no way to it
---(numen.move.to fails with no_path) is passed over and the rest are picked up; at the end the ones passed over raise
---no_path, with them in err.data.left. An item still there after walking onto it with no delay left (a full pack, or
---a spot you cannot stand in) raises failed; any other error of a walk stops here as it is.
---@param opts? table items = only these (anything with an id: the drops of numen.work.dig, Items of numen.scan.entities); radius = how far to look (default 8, or 64 with items); the rest is the description for the walks (costs = {dig = true, place = true} lets it dig and pillar to drops in a pit).
---@return integer picked How many items it walked onto that are gone now.
function M.collect(opts)
  local walk = {}
  for k, v in pairs(opts or {}) do
    walk[k] = v
  end
  local wanted = nil
  if walk.items then
    wanted = {}
    for _, item in ipairs(walk.items) do
      if item.id then
        wanted[item.id] = true
      end
    end
  end
  local radius = walk.radius or (wanted and 64 or 8)
  walk.radius = nil
  walk.items = nil
  local walked = {}
  local unreachable = {}
  while true do
    local items = {}
    for _, item in ipairs(numen.scan.entities("item", {radius = radius})) do
      if not unreachable[item.id] and (wanted == nil or wanted[item.id]) then
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
            .. left[1].item .. " x" .. left[1].count, string.format("numen.move.to({x = %d, y = %d, z = %d}, {costs = "
            .. "{dig = true, place = true}})", math.floor(p.x), math.floor(p.y), math.floor(p.z)),
            {picked = picked, left = left})
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
    local ok, err = pcall(numen.move.to, item.pos, walk)
    if ok then
      walked[item.id] = item.count
    elseif err.kind == "no_path" then
      unreachable[item.id] = item
    else
      error(err, 0)
    end
  end
end

---Dig out one cluster, walking first: walk within reach of it (numen.move.to with arrive "dig": where your hand reaches
---the most of what is left of it, digging and pillaring on the way but keeping away from cells needing your owner's
---consent), dig what is in reach (numen.work.dig), pick up what that dig dropped and still lies around
---(numen.work.collect with its drops), and again until none of it is left. A round that digs nothing raises failed with what is left; any other failing step (a walk with no way there,
---a dig refused) raises its error as it is.
---@param cluster Cluster|Cells What to dig: a cluster numen.scan.blocks found, as it is (or Cells, or a list of Blocks).
---@return integer dug How many cells it dug.
function M.mine(cluster)
  local costs = {dig = true, place = true, consent = false}
  local dug = 0
  while true do
    numen.move.to(cluster, {arrive = "dig", costs = costs})
    local r = numen.work.dig(cluster)
    dug = dug + r.dug
    M.collect({items = r.drops, costs = costs})
    if r.left == 0 then
      return dug
    end
    if r.dug == 0 then
      raise("failed", "dug nothing this round and " .. r.left .. " cell(s) of it are left", nil,
          {left = r.left, nearest = r.nearest})
    end
  end
end

return M
