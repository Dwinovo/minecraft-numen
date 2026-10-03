-- Clearing hostiles: fight the hostile mobs around you one at a time.

-- Attack every hostile mob within radius blocks of you (default 16), nearest first, one fight.attack each, until none
-- is left. Returns how many fights it started. When a fight fails (it got away, you could not reach it), that error
-- stops here.
function fight.clear(radius)
  radius = radius or 16
  local fights = 0
  while true do
    local hostiles = scan.entities("hostile", {radius = radius})
    if #hostiles == 0 then
      return fights
    end
    fight.attack(hostiles[1].id)
    fights = fights + 1
  end
end
