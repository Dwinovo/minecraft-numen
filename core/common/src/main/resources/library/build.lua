-- Building all of a design: walk the site, dig out what is in the way, place what is within reach.

---Build a design or blueprint file at a spot until all of it stands. Each round asks build.left what is still to do
---from where you stand, then places what is within reach (build.at), or digs out the blocks in the way (move.goto_
---with arrive "dig", then work.dig), or walks within reach of the lowest nearest cell left (move.goto_ with arrive
---"reach"). Drops of what it digs stay where they fall: work.collect() picks them up. A round that leaves everything
---as it was raises failed with what is left, and so do cells nothing holds once all else stands; a step that fails
---raises its own error.
---@param name string The design or blueprint file.
---@param opts? table at and rotation are build.at's (at defaults to where you stand); the rest are route flags for the walks (alter = "natural" lets it pillar up to high cells and dig its way).
---@return integer rounds How many rounds it took.
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
  local function at(p)
    return p and string.format("%d %d %d", p.x, p.y, p.z) or "-"
  end
  -- every round that gets anywhere changes what is left or where the next cell is; seeing a round again means it
  -- goes round in circles
  local seen = {}
  local rounds = 0
  while true do
    local left = build.left(name, spot)
    if left.left == 0 then
      return rounds
    end
    rounds = rounds + 1
    local now = left.left .. "/" .. left.reach .. "/" .. #left.dig .. "/" .. at(left.next)
    if seen[now] then
      raise("failed", "building " .. name .. " is stuck: " .. left.left .. " cell(s) left, " .. left.reach
          .. " within reach, " .. #left.dig .. " to dig out, " .. left.far .. " out of reach"
          .. (left.next and ", the lowest nearest at " .. at(left.next) or ""), "build.left(\"" .. name .. "\")")
    end
    seen[now] = true
    if left.reach > 0 then
      build.at(name, spot)
    elseif #left.dig > 0 then
      to(left.dig[1], "dig")
      work.dig(left.dig)
    elseif left.next then
      to(left.next, "reach")
    elseif left.short > 0 then
      raise("no_material", left.short .. " cell(s) of " .. name .. " hold another block and you carry nothing to put "
          .. "there", nil)
    else
      raise("failed", left.unheld .. " cell(s) of " .. name .. " would not stay where the design puts them: nothing "
          .. "holds them there", "build.left(\"" .. name .. "\")")
    end
  end
end
