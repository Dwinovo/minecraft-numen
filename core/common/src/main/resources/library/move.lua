-- Going somewhere in one call: write your own route to the place, plan it, walk it.

-- Walk to a place: a cell {x, y, z}, a column {x, z}, a height y, or an area of your owner's ("ores", "ores/g3").
-- opts are what route.new takes besides the name: arrive, near and the route flags (alter, avoid, ...).
-- It is route.new, route.plan and move.go on your own route goto-<your name>; when a step fails, its error stops here.
function move.goto_(place, opts)
  local spec = {}
  for k, v in pairs(opts or {}) do
    spec[k] = v
  end
  spec.to = place
  route.new(spec)
  route.plan()
  move.go()
end
