-- Building all of a design: walk the site, dig out what is in the way, place what is within reach.

-- Build a design or blueprint file at a spot until all of it stands. opts.at and opts.rotation are build.at's; the
-- other opts are route flags for the walks (alter = "natural" lets it pillar up to high cells and dig its way).
-- Each round asks build.left what is still to do from where you stand, then places what is within reach (build.at),
-- or digs out the blocks in the way (move.goto_ with arrive "dig", then work.dig), or walks within reach of the lowest
-- nearest cell left (move.goto_ with arrive "reach"). Drops of what it digs stay where they fall: work.collect() picks
-- them up. An error when a round leaves everything as it was, saying what is left.
function build.raise(name, opts)
  local spot = {}
  local walk = {}
  for k, v in pairs(opts or {}) do
    if k == "at" or k == "rotation" then
      spot[k] = v
    else
      walk[k] = v
    end
  end
  local function to(place, arrive)
    local go = {arrive = arrive}
    for k, v in pairs(walk) do
      go[k] = v
    end
    move.goto_(place, go)
  end
  -- every round that gets anywhere changes what is left or where the next cell is; seeing a round again means it
  -- goes round in circles
  local seen = {}
  while true do
    local left = build.left(name, spot)
    if left.left == 0 then
      return
    end
    local now = left.left .. "/" .. left.reach .. "/" .. #left.dig .. "/"
        .. (left.next and table.concat(left.next, " ") or "-")
    if seen[now] then
      error("building " .. name .. " is stuck: " .. left.left .. " cell(s) left, " .. left.reach
          .. " within reach, " .. #left.dig .. " to dig out, " .. left.far .. " out of reach"
          .. (left.next and ", the lowest nearest at " .. table.concat(left.next, " ") or ""), 0)
    end
    seen[now] = true
    if left.reach > 0 then
      build.at(name, spot)
    elseif #left.dig > 0 then
      to(left.dig[1], "dig")
      work.dig(table.unpack(left.dig))
    elseif left.next then
      to(left.next, "reach")
    else
      error(left.short .. " cell(s) of " .. name .. " hold another block and you carry nothing to put there", 0)
    end
  end
end
