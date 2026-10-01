-- Dig out an area: walk within reach of what is left of it, dig what is in reach, pick up the drops, until nothing is left.
-- usage: script run mine <area>   (an area from area list, or one part of it: ores/g3)
local where = ...
if where == nil then
  error("usage: script run mine <area>", 0)
end

while area.has(where) do
  local walked, why = pcall(move.goto_, where, {arrive = "dig", alter = "natural"})
  if not walked then
    error("could not get within reach of " .. where .. ": " .. why, 0)
  end
  local dug, err = pcall(work.dig, where)
  if not dug then
    error("could not dig " .. where .. ": " .. err, 0)
  end
  -- what was dug lies at your feet: pick up what you can walk to before walking on. work.collect fails when
  -- nothing lies there or every drop is in a pit it cannot walk into; that is not a mining failure, and the
  -- receipt's line for it says where they lie
  pcall(work.collect)
end
