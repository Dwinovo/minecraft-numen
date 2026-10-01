-- Dig out an area: walk within reach of what is left of it, dig what is in reach, until nothing is left; then pick up the drops.
-- usage: script run mine <area>   (an area from area list, or one part of it: ores/g3)
local where = ...
if where == nil then
  error("usage: script run mine <area>", 0)
end

local stuck
while area.has(where) do
  local walk = move.goto(where, {arrive = "dig", alter = "natural"})
  if not walk.ok then
    stuck = "could not get within reach of " .. where .. ": " .. walk.text
    break
  end
  local dig = work.dig(where)
  if not dig.ok then
    stuck = "could not dig " .. where .. ": " .. dig.text
    break
  end
end

work.collect()
if stuck then
  error(stuck, 0)
end
