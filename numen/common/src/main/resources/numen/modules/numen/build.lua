-- Building all of a building: dig out what is in the way, place the rest (numen.build.place walks up to the cells out of reach).
local M = {}

---Build Cells or a Blueprint (from numen.build.blueprint) until all of it went in. Each round asks numen.build.diff what
---is still to do, then digs out the blocks in the way (numen.work.dig, which walks up to them), or places what is left
---(numen.build.place, which walks up to the cells out of reach). Drops of what it digs stay where they fall:
---numen.work.collect() picks them up. A round that leaves everything as it was raises failed with what is left, and so do
---cells nothing holds once all else stands; any other step that fails raises its own error.
---@param building Cells|Blueprint What to build.
---@return integer rounds How many rounds it took.
function M.raise(building)
  local name = building.blueprint or (#building .. " cells")
  local function at(p)
    return p and string.format("%d %d %d", p.x, p.y, p.z) or "-"
  end
  -- every round that gets anywhere changes what is left, where the next cell is or how much was put down so far (a
  -- cell going in can knock a neighbour out again, leaving the counts alone); seeing a round again means it goes round
  -- in circles
  local seen = {}
  local put_down = 0
  local rounds = 0
  while true do
    local left = numen.build.diff(building)
    if left.left == 0 then
      return rounds
    end
    rounds = rounds + 1
    local now = left.left .. "/" .. left.reach .. "/" .. #left.dig .. "/" .. at(left.next) .. "/" .. put_down
    if seen[now] then
      raise("failed", "building " .. name .. " is stuck: " .. left.left .. " cell(s) left, " .. left.reach
          .. " within reach, " .. #left.dig .. " to dig out, " .. left.far .. " out of reach"
          .. (left.next and ", the lowest nearest at " .. at(left.next) or ""), "numen.build.diff(building)")
    end
    seen[now] = true
    if #left.dig > 0 and left.reach == 0 then
      numen.work.dig(left.dig)
    elseif left.reach > 0 or left.next then
      -- the last cell going in lets the world settle once; what it changes after that is vanilla's say, and placing it
      -- again comes out the same (numen.build.place's settled_away counts them)
      local did = numen.build.place(building)
      if did.left == 0 then
        return rounds
      end
      put_down = put_down + did.placed + did.cleared
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
