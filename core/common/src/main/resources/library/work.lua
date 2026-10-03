-- Picking up: walk onto the dropped items lying around, the way a player picks things up by walking over them.

-- Pick up the dropped items around you, nearest first, walking onto each with move.goto_. opts.radius is how far to
-- look (default 8); the other opts are route flags for the walks (alter = "natural" lets it dig and pillar to drops
-- in a pit). Returns how many items it walked onto that are gone now. A fresh drop cannot be picked up for a few ticks
-- (its pickup_delay): standing on it, it walks onto it again until it is taken. An error when an item is still there
-- after walking onto it with no delay left (a full pack, or it lies where you cannot stand), or when its delay is long.
function work.collect(opts)
  local walk = {}
  for k, v in pairs(opts or {}) do
    walk[k] = v
  end
  local radius = walk.radius or 8
  walk.radius = nil
  local walked = {}
  while true do
    local items = scan.entities("item", {radius = radius})
    if #items == 0 then
      local count = 0
      for _, n in pairs(walked) do
        count = count + n
      end
      return count
    end
    local item = items[1]
    local where = item.item .. " x" .. item.count .. " at " .. table.concat(item.cell, " ")
    if walked[item.id] and item.pickup_delay == 0 then
      error(where .. " is still there after walking onto it: a full pack, or a spot you cannot stand in", 0)
    end
    if item.pickup_delay > 100 then
      error(where .. " cannot be picked up for another " .. item.pickup_delay .. " ticks", 0)
    end
    walked[item.id] = item.count
    move.goto_(item.cell, walk)
  end
end
