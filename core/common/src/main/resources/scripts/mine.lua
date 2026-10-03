-- Dig out an area: walk within reach of what is left of it, dig what is in reach, pick up the drops, until nothing is left.
-- usage: script.run("mine", "ores")   (an area from area.list(), or one part of it: "ores/g3")
local where = ...
if where == nil then
  error("usage: script.run(\"mine\", \"<area>\")", 0)
end

while area.has(where) do
  move.goto_(where, {arrive = "dig", alter = "natural"})
  work.dig(where)
  work.collect({alter = "natural"})
end
