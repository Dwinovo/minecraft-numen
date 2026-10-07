-- Clearing hostiles: fight the hostile mobs around you one at a time.
local M = {}

---Attack every hostile mob within radius blocks of you, nearest first, one numen.fight.attack each, until none is left.
---One that is gone before the fight starts (not_found: it died or despawned since the scan) is skipped; any other
---failure of a fight (it got away, you could not reach it) raises that error as it is.
---@param radius? integer How far to look, default 16.
---@return integer fights How many fights it started.
function M.clear(radius)
  radius = radius or 16
  local fights = 0
  local gone = {}
  while true do
    local foe = nil
    for _, e in ipairs(numen.scan.entities("hostile", {radius = radius})) do
      if not gone[e.id] then
        foe = e
        break
      end
    end
    if foe == nil then
      return fights
    end
    local ok, err = pcall(numen.fight.attack, foe)
    if ok then
      fights = fights + 1
    elseif err.kind == "not_found" then
      gone[foe.id] = true
    else
      error(err, 0)
    end
  end
end

return M
