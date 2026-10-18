-- Going places: head out in a direction until something turns up. (numen.move.to, away and follow are API functions.)
local M = {}

local COMPASS = {north = {0, -1}, south = {0, 1}, east = {1, 0}, west = {-1, 0}}

-- seconds of walking are counted as the distance covered at about this many blocks per second
local SPEED = 5

---Head out a hop at a time, calling until_ after each hop, until it returns something or about seconds of walking are
---used up (counted as the distance covered at about 5 blocks per second). Each hop is a numen.move.to to within 4 blocks
---of the column hop blocks further on; a hop that can't be made raises its error as it is, and so does a hop that gets
---nowhere (no_path).
---@param dir string|Pos "north", "south", "east", "west", or a Pos to head toward.
---@param opts table until_ = a function called after each hop (whatever it returns other than nil or false stops the walk and is returned); seconds = about how long to walk (default 60); hop = blocks per hop (default 16).
---@return any found What until_ returned, or nil when the time ran out first.
function M.explore(dir, opts)
  local o = opts or {}
  local check, seconds, hop = o.until_, o.seconds or 60, o.hop or 16
  if type(check) ~= "function" then
    raise("bad_argument", "numen.move.explore needs until_ = a function to call after each hop",
        "numen.move.explore(\"north\", {until_ = function() return numen.scan.blocks(\"iron_ore\")[1] end})")
  end
  local dx, dz
  if type(dir) == "string" then
    local d = COMPASS[dir]
    if d == nil then
      raise("bad_argument", "dir is north, south, east, west or a Pos; got \"" .. dir .. "\"",
          "numen.move.explore(\"north\", opts)")
    end
    dx, dz = d[1], d[2]
  else
    local here = numen.status.self().pos
    dx, dz = dir.x - here.x, dir.z - here.z
    local len = math.sqrt(dx * dx + dz * dz)
    if len < 1 then
      raise("bad_argument", "that Pos is where you stand: there is no direction to head", nil)
    end
    dx, dz = dx / len, dz / len
  end
  local walked = 0
  while walked < seconds do
    local here = numen.status.self().pos
    numen.move.to({x = math.floor(here.x + dx * hop), z = math.floor(here.z + dz * hop)}, 4)
    local there = numen.status.self().pos
    local moved = math.sqrt((there.x - here.x) ^ 2 + (there.z - here.z) ^ 2)
    if moved < 1 then
      raise("no_path", "can't get any further that way from " .. math.floor(here.x) .. " " .. math.floor(here.y)
          .. " " .. math.floor(here.z), nil)
    end
    walked = walked + moved / SPEED
    local found = check()
    if found then
      return found
    end
  end
  return nil
end

return M
