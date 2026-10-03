-- Going somewhere in one call: write your own route to the place, plan it, walk it.
local M = {}

---Walk to a place: route.new, route.plan and move.go on your own route goto-<your name>. A step that fails raises
---its error as it is (no_path when there is no way, not_found for an area that is gone).
---@param place Pos|Block|Entity|string A Pos (or anything with a pos), a column {x = …, z = …}, a height {y = …}, or an area of your owner's ("ores", "ores/g3").
---@param opts? table What route.new takes besides the name: arrive, near and the route flags (alter, avoid, ...).
---@return {pos: Pos, route: string, distance_left: number} moved What move.go returns: where you stand now.
function M.goto_(place, opts)
  local spec = {}
  for k, v in pairs(opts or {}) do
    spec[k] = v
  end
  spec.to = place
  route.new(spec)
  route.plan()
  return move.go()
end

return M
