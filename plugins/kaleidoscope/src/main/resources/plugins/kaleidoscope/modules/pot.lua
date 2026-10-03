-- Cooking a dish start to finish: walk to the pot or stockpot and do its steps in the order the cookware wants.
local M = {}

---Cook one dish on the pot or stockpot at `at`: walk within reach (numen.move.to with arrive "use"), check it is
---free, then for a pot oil, fill, stir and plate it; for a stockpot take the lid off, pour the soup base, fill, put the
---lid on, wait until it is done (numen.time.wait_until on kaleidoscope.pot.inspect) and ladle it out. A cookware that
---is busy with something else raises failed; a step that fails raises its error as it is (a dish that came out as
---something else fails at plate, saying what it is).
---@param recipe string A recipe id exactly as kaleidoscope.pot.recipes prints it.
---@param at Pos|Block The pot or stockpot.
---@return string plated What came out.
function M.cook(recipe, at)
  numen.move.to(at, {arrive = "use"})
  local now = kaleidoscope.pot.inspect(at)
  local p = at.pos or at
  local where = string.format("%d %d %d", p.x, p.y, p.z)
  if #now.in_the_pot > 0 or (now.stage ~= "put_ingredient" and now.stage ~= "put_soup_base") then
    raise("failed", "the " .. now.cookware .. " at " .. where .. " is busy (" .. now.stage .. ", holding "
        .. #now.in_the_pot .. " item(s)) — wait for it or use another one")
  end
  if now.cookware == "pot" then
    kaleidoscope.pot.oil(at)
    kaleidoscope.pot.fill(at, recipe)
    kaleidoscope.pot.stir(at)
  else
    if now.has_lid then
      kaleidoscope.pot.lid(at)
    end
    kaleidoscope.pot.base(at, recipe)
    kaleidoscope.pot.fill(at, recipe)
    kaleidoscope.pot.lid(at)
    local simmer = kaleidoscope.pot.inspect(at).done_in_ticks or 0
    numen.time.wait_until(function()
      return kaleidoscope.pot.inspect(at).stage == "finished"
    end, {every = 2, timeout = simmer / 20 + 30})
    kaleidoscope.pot.lid(at)
  end
  return kaleidoscope.pot.plate(at, recipe).plated
end

return M
