-- Building all of a building: walk the site, dig out what is in the way, place what is within reach.
local M = {}

---Build Cells or a Blueprint (from numen.build.blueprint) until all of it went in. Each round asks numen.build.diff what
---is still to do from where you stand, then places what is within reach (numen.build.place), or digs out the blocks in
---the way (numen.move.to with arrive "dig", then numen.work.dig), or walks within reach of the lowest nearest cell left
---(numen.move.to with arrive "place"). Drops of what it digs stay where they fall: numen.work.collect() picks them up.
---A walk that stops short (no_path) ends its round and the next round plans again from where you stand. A round that
---leaves everything as it was raises failed with what is left (and why the last walk stopped), and so do cells nothing
---holds once all else stands; any other step that fails raises its own error.
---@param building Cells|Blueprint What to build.
---@param opts? table The description for the walks (costs = {dig = true, place = true} lets it pillar up to high cells and dig its way).
---@return integer rounds How many rounds it took.
function M.raise(building, opts)
  local name = building.blueprint or (#building .. " cells")
  -- the last walk that stopped short: no_path is a round that got nowhere, the next round plans again from where
  -- she stands; any other error is raised as it is
  local stopped = nil
  local function to(place, arrive)
    local go = {arrive = arrive}
    for k, v in pairs(opts or {}) do
      go[k] = v
    end
    local ok, err = pcall(numen.move.to, place, go)
    if not ok then
      if type(err) ~= "table" or err.kind ~= "no_path" then
        error(err, 0)
      end
      stopped = err
    end
  end
  local function at(p)
    return p and string.format("%d %d %d", p.x, p.y, p.z) or "-"
  end
  -- every round that gets anywhere changes what is left or where the next cell is; seeing a round again means it
  -- goes round in circles
  local seen = {}
  local rounds = 0
  while true do
    local left = numen.build.diff(building)
    if left.left == 0 then
      return rounds
    end
    rounds = rounds + 1
    local now = left.left .. "/" .. left.reach .. "/" .. #left.dig .. "/" .. at(left.next)
    if seen[now] then
      raise("failed", "building " .. name .. " is stuck: " .. left.left .. " cell(s) left, " .. left.reach
          .. " within reach, " .. #left.dig .. " to dig out, " .. left.far .. " out of reach"
          .. (left.next and ", the lowest nearest at " .. at(left.next) or "")
          .. (stopped and "; the last walk stopped: " .. stopped.message or ""), "numen.build.diff(building)")
    end
    seen[now] = true
    if left.reach > 0 then
      -- the last cell going in lets the world settle once; what it changes after that is vanilla's say, and placing it
      -- again comes out the same (numen.build.place's settled_away counts them)
      if numen.build.place(building).left == 0 then
        return rounds
      end
    elseif #left.dig > 0 then
      to(left.dig[1], "dig")
      numen.work.dig(left.dig)
    elseif left.next then
      to(left.next, "place")
    elseif left.no_stock > 0 then
      raise("no_material", left.no_stock .. " cell(s) of " .. name .. " hold another block and you carry nothing to put "
          .. "there", nil)
    else
      raise("failed", left.unheld .. " cell(s) of " .. name .. " would not stay where they go: nothing holds them "
          .. "there", "numen.build.diff(building)")
    end
  end
end

return M
