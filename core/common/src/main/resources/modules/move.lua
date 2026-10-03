-- Going somewhere in one call: write your own route to the place, plan it, walk it.
local M = {}

---Walk to a place: numen.route.new, numen.route.plan and numen.move.go on your own route goto-<your name>. A step that fails raises
---its error as it is (no_path when there is no way).
---@param place Pos|Block|Entity|table A Pos (or anything with a pos), a column {x = …, z = …}, a height {y = …}, or several cells (a cluster's blocks) to reach any one of.
---@param opts? table What numen.route.new takes besides the name: arrive, near and the route flags (alter, avoid, ...).
---@return {pos: Pos, route: string, distance_left: number} moved What numen.move.go returns: where you stand now.
function M.goto_(place, opts)
  local spec = {}
  for k, v in pairs(opts or {}) do
    spec[k] = v
  end
  spec.to = place
  numen.route.new(spec)
  numen.route.plan()
  return numen.move.go()
end

return M
