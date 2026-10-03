-- Going places: plan a walk and walk it, get away from something, head out in a direction until something turns up.
local M = {}

---Walk to a place in one call: route.plan with the description and to = target, then move.go. A walk that can't be
---made raises no_path (with the plan's why); any other error is raised as it is.
---@param target Pos|Block|Entity|table A Pos (or anything with a pos), an Entity (where it is now), anything with cells (a Cluster), a column {x = …, z = …} or a height {y = …}.
---@param spec? table The rest of the description route.plan takes: arrive, range, stops, mode, costs, avoid, materials …
---@return {pos: Pos, distance_left: number} moved What move.go returns: where you stand now.
function M.to(target, spec)
  local s = {}
  for k, v in pairs(spec or {}) do
    s[k] = v
  end
  s.to = target
  return move.go(route.plan(s))
end

---Get away from something: walk until you are at least distance blocks from it (route.plan with arrive "away").
---@param from Pos|Block|Entity|table What to get away from: a place, an entity (where it is now), or anything with cells.
---@param opts? table distance = how far to get (default 8); the rest is the description (costs, avoid …).
---@return {pos: Pos, distance_left: number} moved What move.go returns.
function M.flee(from, opts)
  local s = {}
  for k, v in pairs(opts or {}) do
    s[k] = v
  end
  s.range = s.distance or 8
  s.distance = nil
  s.arrive = "away"
  return M.to(from, s)
end

local COMPASS = {north = {0, -1}, south = {0, 1}, east = {1, 0}, west = {-1, 0}}

---Head out a hop at a time, calling until_ after each hop, until it returns something or about seconds of walking are
---used up. Each hop is a route.plan + move.go to the column hop blocks further on; a hop that can't be made raises its
---error as it is, and so does a hop that gets nowhere (no_path).
---@param dir string|Pos "north", "south", "east", "west", or a Pos to head toward.
---@param opts table until_ = a function called after each hop (whatever it returns other than nil or false stops the walk and is returned); seconds = about how long to walk (default 60, added up from each hop's plan); hop = blocks per hop (default 16); the rest is the description for each hop (costs, avoid …).
---@return any found What until_ returned, or nil when the time ran out first.
function M.explore(dir, opts)
  local o = {}
  for k, v in pairs(opts or {}) do
    o[k] = v
  end
  local check, seconds, hop = o.until_, o.seconds or 60, o.hop or 16
  o.until_, o.seconds, o.hop = nil, nil, nil
  if type(check) ~= "function" then
    raise("bad_argument", "move.explore needs until_ = a function to call after each hop",
        "move.explore(\"north\", {until_ = function() return scan.blocks(\"iron_ore\").groups[1] end})")
  end
  local dx, dz
  if type(dir) == "string" then
    local d = COMPASS[dir]
    if d == nil then
      raise("bad_argument", "dir is north, south, east, west or a Pos; got \"" .. dir .. "\"",
          "move.explore(\"north\", opts)")
    end
    dx, dz = d[1], d[2]
  else
    local here = status.self().pos
    dx, dz = dir.x - here.x, dir.z - here.z
    local len = math.sqrt(dx * dx + dz * dz)
    if len < 1 then
      raise("bad_argument", "that Pos is where you stand: there is no direction to head", nil)
    end
    dx, dz = dx / len, dz / len
  end
  local walked = 0
  while walked < seconds do
    local here = status.self().pos
    o.to = {x = math.floor(here.x + dx * hop), z = math.floor(here.z + dz * hop)}
    o.arrive, o.range = "near", 4
    local plan = route.plan(o)
    if plan.ok and plan.steps == 0 then
      raise("no_path", "can't get any further that way from " .. math.floor(here.x) .. " " .. math.floor(here.y)
          .. " " .. math.floor(here.z), nil, {plan = plan})
    end
    move.go(plan)
    walked = walked + plan.seconds
    local found = check()
    if found then
      return found
    end
  end
  return nil
end

return M
