-- Positions, selections and shapes, modelled on WorldEdit: Regions (cuboid, cyl, sphere, poly, convex) edited by their shape, shapes drawn as Cells, Cells you can turn, stack, move and paste.
local M = {}

---@class Pos
M.Pos = {}
M.Pos.__index = M.Pos

---@class Cells
M.Cells = {}
M.Cells.__index = M.Cells

---@class Region
M.Region = {}
M.Region.__index = M.Region

local floor, ceil, abs = math.floor, math.ceil, math.abs

-- the position of a cell: a Block's pos, or the Pos itself
local function where(c)
  return c.pos or c
end

local function key(p)
  return p.x .. "," .. p.y .. "," .. p.z
end

-- the cell a position falls in: whole coordinates as they are, decimals (a body's position) rounded down
local function cell_at(p)
  local q = where(p)
  return {x = floor(q.x), y = floor(q.y), z = floor(q.z)}
end

local function bad(message, hint, data)
  raise("bad_argument", message, hint, data)
end

---A Pos at x, y, z.
---@param x number
---@param y number
---@param z number
---@return Pos
function M.pos(x, y, z)
  return setmetatable({x = x, y = y, z = z}, M.Pos)
end

---This position moved by dx, dy, dz.
---@param dx number
---@param dy? number
---@param dz? number
---@return Pos
function M.Pos:offset(dx, dy, dz)
  return M.pos(self.x + dx, self.y + (dy or 0), self.z + (dz or 0))
end

---Straight-line distance to another position (or anything with a pos).
---@param other Pos|Block|Entity
---@return number
function M.Pos:dist(other)
  local o = where(other)
  return math.sqrt((self.x - o.x) ^ 2 + (self.y - o.y) ^ 2 + (self.z - o.z) ^ 2)
end

function M.Pos.__add(a, b)
  local p, q = where(a), where(b)
  return M.pos(p.x + q.x, p.y + q.y, p.z + q.z)
end

function M.Pos.__sub(a, b)
  local p, q = where(a), where(b)
  return M.pos(p.x - q.x, p.y - q.y, p.z - q.z)
end

function M.Pos.__eq(a, b)
  return a.x == b.x and a.y == b.y and a.z == b.z
end

-- a cell at p: a Block named name, or the Pos when there is no name
local function cell(p, name)
  local at = M.pos(p.x, p.y, p.z)
  if name then
    return {name = name, pos = at}
  end
  return at
end

-- the same cell moved to p
local function moved(c, p)
  return cell(p, c.pos and c.name or nil)
end

-- ---- how many cells one Cells holds ----

local limit

-- the most cells one Cells holds (the host says; asked once per run)
local function max_cells()
  limit = limit or numen.shape.max_cells()
  return limit
end

local function check_size(n)
  if n > max_cells() then
    bad("that comes to more than " .. limit .. " cells, the most one Cells holds; draw it in pieces (a smaller "
        .. "radius or fewer copies, parts joined with Cells:union) and numen.build.place each piece", nil,
        {limit = limit})
  end
end

-- ---- directions and offsets ----

local DIRECTIONS = {
  north = {x = 0, y = 0, z = -1}, south = {x = 0, y = 0, z = 1},
  east = {x = 1, y = 0, z = 0}, west = {x = -1, y = 0, z = 0},
  up = {x = 0, y = 1, z = 0}, down = {x = 0, y = -1, z = 0},
}
local SHORT = {n = "north", s = "south", e = "east", w = "west", u = "up", d = "down"}

-- a direction written as a name (north, up, n, u …) or a vector {x, y, z}, as a vector
local function direction(d)
  if type(d) == "table" and d.x then
    return {x = d.x, y = d.y, z = d.z}
  end
  local named = type(d) == "string" and DIRECTIONS[SHORT[d] or d]
  if not named then
    bad("a direction is north, south, east, west, up or down (or a vector {x = 1, y = 0, z = 0}); got "
        .. tostring(d), "region:expand(2, \"north\")")
  end
  return {x = named.x, y = named.y, z = named.z}
end

-- one direction or a list of them (a name, a vector, or {"north", "south"}), as vectors
local function directions(d)
  if type(d) == "table" and not d.x then
    local out = {}
    for i, one in ipairs(d) do
      out[i] = direction(one)
    end
    return out
  end
  return {direction(d)}
end

local function scaled(v, n)
  return {x = v.x * n, y = v.y * n, z = v.z * n}
end

-- an offset written dx, dy, dz, as a Pos or vector, or as an amount and a direction (5, "up")
local function offset_of(a, b, c)
  if type(a) == "table" then
    return {x = a.x, y = a.y, z = a.z}
  end
  if type(b) == "string" or type(b) == "table" then
    return scaled(direction(b), a)
  end
  return {x = a, y = b or 0, z = c or 0}
end

-- ---- Cells ----

-- the smallest and the largest corner of a list of cells; nothing for an empty list
local function bounds_of(list)
  local lo, hi
  for _, c in ipairs(list) do
    local p = where(c)
    if not lo then
      lo = {x = p.x, y = p.y, z = p.z}
      hi = {x = p.x, y = p.y, z = p.z}
    else
      if p.x < lo.x then lo.x = p.x elseif p.x > hi.x then hi.x = p.x end
      if p.y < lo.y then lo.y = p.y elseif p.y > hi.y then hi.y = p.y end
      if p.z < lo.z then lo.z = p.z elseif p.z > hi.z then hi.z = p.z end
    end
  end
  return lo, hi
end

-- the origin a Cells was given (a shape drawn at a point, a paste, a rotation); the cells' own metatable carries it, so
-- the cells stay a plain list for everything that reads them
local function given_origin(list)
  local meta = getmetatable(list)
  return meta and meta.__origin
end

local function cells_of(list, origin)
  check_size(#list)
  return setmetatable(list, origin and {__index = M.Cells, __origin = origin} or M.Cells)
end

-- the origin of cells: the one they were given, else the smallest corner of their box
local function origin_of(list)
  local given = given_origin(list)
  if given then
    return given
  end
  local lo = bounds_of(list)
  return lo and M.pos(lo.x, lo.y, lo.z)
end

---Cells from a list of Blocks (a block to put in each cell) or Pos (just the cells): a scan's blocks, numen.build.diff's
---result, your own list. origin is the point turns and pastes are about (default: the smallest corner of the cells).
---@param list (Block|Pos)[]
---@param origin? Pos
---@return Cells
function M.cells(list, origin)
  local out = {}
  for i, c in ipairs(list) do
    out[i] = c
  end
  local o = origin and cell_at(origin)
  return cells_of(out, o and M.pos(o.x, o.y, o.z))
end

---The point these cells turn, flip and paste about: where they were drawn (or given), else their smallest corner.
---Nothing for no cells.
---@return Pos?
function M.Cells:origin()
  return origin_of(self)
end

---These cells moved by dx, dy, dz, a Pos, or an amount and a direction (5, "up"). Their origin moves with them.
---@param dx number|Pos
---@param dy? number|string|table
---@param dz? number
---@return Cells
function M.Cells:shift(dx, dy, dz)
  local o = offset_of(dx, dy, dz)
  local out = {}
  for i, c in ipairs(self) do
    local p = where(c)
    out[i] = moved(c, {x = p.x + o.x, y = p.y + o.y, z = p.z + o.z})
  end
  local given = given_origin(self)
  return cells_of(out, given and M.pos(given.x + o.x, given.y + o.y, given.z + o.z))
end

-- the cells of other by position: Cells, a list, or one Pos or Block
local function positions(other)
  local set = {}
  if other.x or other.pos then
    set[key(where(other))] = true
  else
    for _, c in ipairs(other) do
      set[key(where(c))] = true
    end
  end
  return set
end

---These cells and other's together; where both have a cell, other's is kept.
---@param other Cells|(Block|Pos)[]
---@return Cells
function M.Cells:union(other)
  local theirs = positions(other)
  local out = {}
  for _, c in ipairs(self) do
    if not theirs[key(where(c))] then
      out[#out + 1] = c
    end
  end
  for _, c in ipairs(other) do
    out[#out + 1] = c
  end
  return cells_of(out, given_origin(self))
end

---These cells without the ones other has (Cells, a list, or one Pos or Block).
---@param other Cells|(Block|Pos)[]|Pos|Block
---@return Cells
function M.Cells:minus(other)
  local theirs = positions(other)
  local out = {}
  for _, c in ipairs(self) do
    if not theirs[key(where(c))] then
      out[#out + 1] = c
    end
  end
  return cells_of(out, given_origin(self))
end

-- cells in this order, the last one at a position wins
local function last_wins(list)
  local last = {}
  for i, c in ipairs(list) do
    last[key(where(c))] = i
  end
  local out = {}
  for i, c in ipairs(list) do
    if last[key(where(c))] == i then
      out[#out + 1] = c
    end
  end
  return out
end

local AIR = {air = true, cave_air = true, void_air = true}

local function is_air(c)
  local base = c.name and c.name:match("^%s*([^%[{%s]+)")
  return base ~= nil and AIR[(base:gsub("^minecraft:", ""))] == true
end

-- ---- patterns ----

local parsed = {}

-- the block of a weighted pattern for a cell, rolled for that cell alone
local function chooser(choices)
  local total = 0
  for _, part in ipairs(choices) do
    total = total + part.weight
  end
  if #choices == 1 and #choices[1].blocks == 1 then
    local only = choices[1].blocks[1]
    return function()
      return only
    end
  end
  return function()
    local roll = math.random() * total
    local upto = 0
    local pick = choices[#choices]
    for _, part in ipairs(choices) do
      upto = upto + part.weight
      if roll <= upto then
        pick = part
        break
      end
    end
    local blocks = pick.blocks
    return blocks[#blocks == 1 and 1 or math.random(#blocks)]
  end
end

-- Cells as a pattern: the box of the cells repeats through the world, the block at a cell being the cells' block at
-- the same place in the box (nothing where the cells have none)
local function tiler(cells)
  local lo, hi = bounds_of(cells)
  local sx, sy, sz = hi.x - lo.x + 1, hi.y - lo.y + 1, hi.z - lo.z + 1
  local at = {}
  for _, c in ipairs(cells) do
    if not c.name then
      bad("Cells used as a pattern need a block in each cell; this one has a cell with only a position",
          "numen.shape.cuboid(a, b):set(\"stone\")")
    end
    local p = where(c)
    at[(p.x - lo.x) .. "," .. (p.y - lo.y) .. "," .. (p.z - lo.z)] = c.name
  end
  return function(x, y, z)
    return at[(x % sx) .. "," .. (y % sy) .. "," .. (z % sz)]
  end
end

-- a pattern as fn(x, y, z) → the block for that cell (nil: none): text, what numen.shape.pattern read, or Cells.
-- positions() gives the list of cells it will be asked about; only a ^ pattern, which changes what is in the world there,
-- needs it (the world is asked once, for all of them)
local function block_source(pattern, positions)
  if type(pattern) == "string" and pattern:sub(1, 1) == "^" then
    local at = {}
    for _, b in ipairs(numen.shape.restated(positions(), pattern)) do
      at[key(b.pos)] = b.name
    end
    return function(x, y, z)
      return at[x .. "," .. y .. "," .. z]
    end
  end
  if type(pattern) == "string" then
    parsed[pattern] = parsed[pattern] or numen.shape.pattern(pattern)
    return chooser(parsed[pattern])
  end
  if type(pattern) == "table" and pattern[1] ~= nil then
    return pattern[1].blocks and chooser(pattern) or tiler(pattern)
  end
  bad("a pattern is text such as \"stone\" or \"50%stone,50%cobblestone\", what numen.shape.pattern returned, or Cells "
      .. "to repeat; got " .. type(pattern), "numen.shape.cuboid(a, b):set(\"50%stone,50%cobblestone\")")
end

-- ---- kinds of region ----
-- each: bounds(r) → lo, hi, the box of its cells; rows(r, emit) calls emit(y, z, from, to) for every maximal run of cells
-- along x; estimate(r) the rough number of cells; expand(r, changes), contract(r, changes), shift(r, v) the changed
-- region; center(r) → x, y, z.

local KINDS = {}

local function region(fields)
  return setmetatable(fields, M.Region)
end

local function changed(r, fields)
  local out = {}
  for k, v in pairs(r) do
    out[k] = v
  end
  for k, v in pairs(fields) do
    out[k] = v
  end
  return setmetatable(out, M.Region)
end

local function cannot(what, change)
  bad("a " .. what .. " cannot be " .. change, "numen.shape.cuboid(a, b):expand(2, \"up\")")
end

-- the points a distance can reach from a centre along one axis, as WorldEdit's own spheres and cylinders measure it:
-- a radius r covers every cell whose distance in radius + 0.5 units is at most 1
local function inverse(r)
  return 1 / (r + 0.5)
end

-- cuboid

local function cuboid_corner(r, axis, v, toward_max)
  local a, b = r.p1, r.p2
  local first_is_max = a[axis] >= b[axis]
  local move_first = (toward_max == first_is_max) or a[axis] == b[axis]
  local p1 = {x = a.x, y = a.y, z = a.z}
  local p2 = {x = b.x, y = b.y, z = b.z}
  local target = move_first and p1 or p2
  target[axis] = target[axis] + v
  return changed(r, {p1 = p1, p2 = p2})
end

KINDS.cuboid = {
  bounds = function(r)
    return {x = math.min(r.p1.x, r.p2.x), y = math.min(r.p1.y, r.p2.y), z = math.min(r.p1.z, r.p2.z)},
        {x = math.max(r.p1.x, r.p2.x), y = math.max(r.p1.y, r.p2.y), z = math.max(r.p1.z, r.p2.z)}
  end,
  rows = function(r, emit)
    local lo, hi = KINDS.cuboid.bounds(r)
    for y = lo.y, hi.y do
      for z = lo.z, hi.z do
        emit(y, z, lo.x, hi.x)
      end
    end
  end,
  estimate = function(r)
    local lo, hi = KINDS.cuboid.bounds(r)
    return (hi.x - lo.x + 1) * (hi.y - lo.y + 1) * (hi.z - lo.z + 1)
  end,
  expand = function(r, changes)
    for _, c in ipairs(changes) do
      for _, axis in ipairs({"x", "y", "z"}) do
        r = cuboid_corner(r, axis, c[axis], c[axis] > 0)
      end
    end
    return r
  end,
  contract = function(r, changes)
    for _, c in ipairs(changes) do
      for _, axis in ipairs({"x", "y", "z"}) do
        r = cuboid_corner(r, axis, c[axis], c[axis] < 0)
      end
    end
    return r
  end,
  shift = function(r, v)
    return changed(r, {p1 = {x = r.p1.x + v.x, y = r.p1.y + v.y, z = r.p1.z + v.z},
                       p2 = {x = r.p2.x + v.x, y = r.p2.y + v.y, z = r.p2.z + v.z}})
  end,
  center = function(r)
    local lo, hi = KINDS.cuboid.bounds(r)
    return (lo.x + hi.x) / 2, (lo.y + hi.y) / 2, (lo.z + hi.z) / 2
  end,
}

-- the farthest whole step along x at which a shape still holds on one row (-1 when even the step 0 is outside):
-- inside(x) is true up to some x and false after, guess a first estimate that is corrected against inside itself
local function row_reach(inside, guess)
  if not inside(0) then
    return -1
  end
  local x = math.max(0, guess)
  while not inside(x) do
    x = x - 1
  end
  while inside(x + 1) do
    x = x + 1
  end
  return x
end

-- a cyl's or a sphere's changes along each axis: their sum (the centre moves by half of it) and the sum of their sizes
-- (the radius changes by half of that); the centre stays on a block, so on every axis it scales the sum must be even
local function scaling(what, changes, axes)
  local sum, size = {x = 0, y = 0, z = 0}, {x = 0, y = 0, z = 0}
  for _, c in ipairs(changes) do
    for _, axis in ipairs({"x", "y", "z"}) do
      sum[axis] = sum[axis] + c[axis]
      size[axis] = size[axis] + abs(c[axis])
    end
  end
  for _, axis in ipairs(axes) do
    if sum[axis] % 2 ~= 0 then
      bad("a " .. what .. " changes by steps that add up to an even number along each axis it scales (its centre "
          .. "stays on a block); these add up to " .. sum.x .. ", " .. sum.y .. ", " .. sum.z,
          "region:expand(2, \"north\")")
    end
  end
  return sum, {x = floor(size.x / 2), y = floor(size.y / 2), z = floor(size.z / 2)}
end

-- cyl: centre column cx, cz, radii rx, rz and the layers min_y to max_y

local function cyl_inside(rx, rz)
  local ix, iz = inverse(rx), inverse(rz)
  return function(x, z)
    local xn, zn = x * ix, z * iz
    return xn * xn + zn * zn <= 1
  end
end

-- how far a cylinder's floor plan reaches along x on the row z steps from the centre: widths[z] for z from 0 to last
local function cyl_widths(rx, rz)
  local inside = cyl_inside(rx, rz)
  local ix, iz = inverse(rx), inverse(rz)
  local widths = {}
  local last = -1
  for z = 0, ceil(rz + 0.5) do
    if not inside(0, z) then
      break
    end
    local zn = z * iz
    widths[z] = row_reach(function(x) return inside(x, z) end, floor(math.sqrt(math.max(0, 1 - zn * zn)) / ix))
    last = z
  end
  return widths, last
end

KINDS.cyl = {
  bounds = function(r)
    local widths, last = cyl_widths(r.rx, r.rz)
    return {x = r.cx - widths[0], y = r.min_y, z = r.cz - last}, {x = r.cx + widths[0], y = r.max_y, z = r.cz + last}
  end,
  rows = function(r, emit)
    local widths, last = cyl_widths(r.rx, r.rz)
    for y = r.min_y, r.max_y do
      for dz = -last, last do
        local w = widths[abs(dz)]
        emit(y, r.cz + dz, r.cx - w, r.cx + w)
      end
    end
  end,
  estimate = function(r)
    return math.pi * (r.rx + 0.5) * (r.rz + 0.5) * math.max(0, r.max_y - r.min_y + 1)
  end,
  -- sideways about the centre column, up and down by its layers, one change at a time
  expand = function(r, changes)
    local sum, half = scaling("cyl", changes, {"x", "z"})
    local min_y, max_y = r.min_y, r.max_y
    for _, c in ipairs(changes) do
      if c.y > 0 then max_y = max_y + c.y else min_y = min_y + c.y end
    end
    return changed(r, {cx = r.cx + sum.x / 2, cz = r.cz + sum.z / 2, rx = r.rx + half.x, rz = r.rz + half.z,
                       min_y = min_y, max_y = max_y})
  end,
  -- the layers never shrink past each other: a change is cut to the height there is
  contract = function(r, changes)
    local sum, half = scaling("cyl", changes, {"x", "z"})
    local min_y, max_y = r.min_y, r.max_y
    for _, c in ipairs(changes) do
      local height = max_y - min_y
      if c.y > 0 then
        min_y = min_y + math.min(height, c.y)
      else
        max_y = max_y + math.max(-height, c.y)
      end
    end
    return changed(r, {cx = r.cx - sum.x / 2, cz = r.cz - sum.z / 2, rx = math.max(1, r.rx - half.x),
                       rz = math.max(1, r.rz - half.z), min_y = min_y, max_y = max_y})
  end,
  shift = function(r, v)
    return changed(r, {cx = r.cx + v.x, cz = r.cz + v.z, min_y = r.min_y + v.y, max_y = r.max_y + v.y})
  end,
  center = function(r)
    return r.cx, (r.min_y + r.max_y) / 2, r.cz
  end,
}

-- ellipsoid: centre cx, cy, cz and radii rx, ry, rz

local function ellipsoid_inside(rx, ry, rz)
  local ix, iy, iz = inverse(rx), inverse(ry), inverse(rz)
  return function(x, y, z)
    local xn, yn, zn = x * ix, y * iy, z * iz
    return xn * xn + yn * yn + zn * zn <= 1
  end
end

-- how far an ellipsoid reaches along x on the row y, z steps from the centre: widths[y][z], for y from 0 to last_y and
-- z from 0 to last_z[y]
local function ellipsoid_widths(rx, ry, rz)
  local inside = ellipsoid_inside(rx, ry, rz)
  local ix, iy, iz = inverse(rx), inverse(ry), inverse(rz)
  local widths, last_z = {}, {}
  local last_y = -1
  for y = 0, ceil(ry + 0.5) do
    if not inside(0, y, 0) then
      break
    end
    local yn = y * iy
    widths[y] = {}
    for z = 0, ceil(rz + 0.5) do
      if not inside(0, y, z) then
        break
      end
      local zn = z * iz
      widths[y][z] = row_reach(function(x) return inside(x, y, z) end,
          floor(math.sqrt(math.max(0, 1 - yn * yn - zn * zn)) / ix))
      last_z[y] = z
    end
    last_y = y
  end
  return widths, last_y, last_z
end

KINDS.ellipsoid = {
  bounds = function(r)
    local widths, last_y, last_z = ellipsoid_widths(r.rx, r.ry, r.rz)
    local wx, wz = widths[0][0], last_z[0]
    return {x = r.cx - wx, y = r.cy - last_y, z = r.cz - wz}, {x = r.cx + wx, y = r.cy + last_y, z = r.cz + wz}
  end,
  rows = function(r, emit)
    local widths, last_y, last_z = ellipsoid_widths(r.rx, r.ry, r.rz)
    for dy = -last_y, last_y do
      local ay = abs(dy)
      for dz = -last_z[ay], last_z[ay] do
        local w = widths[ay][abs(dz)]
        emit(r.cy + dy, r.cz + dz, r.cx - w, r.cx + w)
      end
    end
  end,
  estimate = function(r)
    return 4 / 3 * math.pi * (r.rx + 0.5) * (r.ry + 0.5) * (r.rz + 0.5)
  end,
  expand = function(r, changes)
    local sum, half = scaling("sphere", changes, {"x", "y", "z"})
    return changed(r, {cx = r.cx + sum.x / 2, cy = r.cy + sum.y / 2, cz = r.cz + sum.z / 2,
                       rx = r.rx + half.x, ry = r.ry + half.y, rz = r.rz + half.z})
  end,
  contract = function(r, changes)
    local sum, half = scaling("sphere", changes, {"x", "y", "z"})
    return changed(r, {cx = r.cx - sum.x / 2, cy = r.cy - sum.y / 2, cz = r.cz - sum.z / 2,
                       rx = math.max(1, r.rx - half.x), ry = math.max(1, r.ry - half.y),
                       rz = math.max(1, r.rz - half.z)})
  end,
  shift = function(r, v)
    return changed(r, {cx = r.cx + v.x, cy = r.cy + v.y, cz = r.cz + v.z})
  end,
  center = function(r)
    return r.cx, r.cy, r.cz
  end,
}

-- poly: the corner points pts ({x, z}) and the layers min_y to max_y

-- whether the cell column x, z is inside a polygon, its edges and corners included
local function poly_contains(pts, x, z)
  local inside = false
  local prev = pts[#pts]
  for _, p in ipairs(pts) do
    local dx, dz = p.x - prev.x, p.z - prev.z
    -- on the edge: on its line and between its ends
    if dx * (z - prev.z) == dz * (x - prev.x) and x >= math.min(p.x, prev.x) and x <= math.max(p.x, prev.x)
        and z >= math.min(p.z, prev.z) and z <= math.max(p.z, prev.z) then
      return true
    end
    -- a ray towards +x crosses the edge when the edge spans this z (one end counted, the other not)
    if (prev.z > z) ~= (p.z > z) and x < prev.x + (z - prev.z) * dx / dz then
      inside = not inside
    end
    prev = p
  end
  return inside
end

-- the box of points (a poly's corners have no y: 0)
local function points_box(points)
  local lo, hi
  for _, p in ipairs(points) do
    local y = p.y or 0
    if not lo then
      lo = {x = p.x, y = y, z = p.z}
      hi = {x = p.x, y = y, z = p.z}
    else
      lo = {x = math.min(lo.x, p.x), y = math.min(lo.y, y), z = math.min(lo.z, p.z)}
      hi = {x = math.max(hi.x, p.x), y = math.max(hi.y, y), z = math.max(hi.z, p.z)}
    end
  end
  return lo, hi
end

local function ordered_y(r, min_y, max_y)
  return changed(r, {min_y = math.min(min_y, max_y), max_y = math.max(min_y, max_y)})
end

KINDS.poly = {
  bounds = function(r)
    local lo, hi = points_box(r.pts)
    return {x = lo.x, y = r.min_y, z = lo.z}, {x = hi.x, y = r.max_y, z = hi.z}
  end,
  rows = function(r, emit)
    local lo, hi = points_box(r.pts)
    for z = lo.z, hi.z do
      local runs, from = {}, nil
      for x = lo.x, hi.x + 1 do
        local inside = x <= hi.x and poly_contains(r.pts, x, z)
        if inside and not from then
          from = x
        elseif not inside and from then
          runs[#runs + 1] = {from, x - 1}
          from = nil
        end
      end
      for y = r.min_y, r.max_y do
        for _, run in ipairs(runs) do
          emit(y, z, run[1], run[2])
        end
      end
    end
  end,
  estimate = function()
    return 0
  end,
  expand = function(r, changes)
    local min_y, max_y = r.min_y, r.max_y
    for _, c in ipairs(changes) do
      if c.x ~= 0 or c.z ~= 0 then
        cannot("poly", "grown sideways: it only changes up and down")
      end
      if c.y > 0 then max_y = max_y + c.y else min_y = min_y + c.y end
    end
    return ordered_y(r, min_y, max_y)
  end,
  contract = function(r, changes)
    local min_y, max_y = r.min_y, r.max_y
    for _, c in ipairs(changes) do
      if c.x ~= 0 or c.z ~= 0 then
        cannot("poly", "shrunk sideways: it only changes up and down")
      end
      if c.y > 0 then min_y = min_y + c.y else max_y = max_y + c.y end
    end
    return ordered_y(r, min_y, max_y)
  end,
  shift = function(r, v)
    local pts = {}
    for i, p in ipairs(r.pts) do
      pts[i] = {x = p.x + v.x, z = p.z + v.z}
    end
    return changed(r, {pts = pts, min_y = r.min_y + v.y, max_y = r.max_y + v.y})
  end,
  center = function(r)
    local lo, hi = KINDS.poly.bounds(r)
    return (lo.x + hi.x) / 2, (lo.y + hi.y) / 2, (lo.z + hi.z) / 2
  end,
}

-- convex: the points pts and the planes of the hull, each {nx, ny, nz, d} meaning nx*x + ny*y + nz*z <= d

local function gcd(a, b)
  while b ~= 0 do
    a, b = b, a % b
  end
  return a
end

-- the planes that have every point on one side (or on them): the faces of the hull of the points
local function hull_planes(pts)
  local planes, seen = {}, {}
  local o = pts[1]
  local rel = {}
  for i, p in ipairs(pts) do
    rel[i] = {x = p.x - o.x, y = p.y - o.y, z = p.z - o.z}
  end
  for i = 1, #rel - 2 do
    for j = i + 1, #rel - 1 do
      for k = j + 1, #rel do
        local a, b, c = rel[i], rel[j], rel[k]
        local ux, uy, uz, vx, vy, vz = b.x - a.x, b.y - a.y, b.z - a.z, c.x - a.x, c.y - a.y, c.z - a.z
        local nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
        if nx ~= 0 or ny ~= 0 or nz ~= 0 then
          local d = nx * a.x + ny * a.y + nz * a.z
          local above, below = false, false
          for _, p in ipairs(rel) do
            local side = nx * p.x + ny * p.y + nz * p.z - d
            if side > 0 then above = true elseif side < 0 then below = true end
            if above and below then
              break
            end
          end
          if above ~= below then
            if above then
              nx, ny, nz, d = -nx, -ny, -nz, -d
            end
            local g = gcd(gcd(abs(nx), abs(ny)), abs(nz))
            nx, ny, nz, d = nx / g, ny / g, nz / g, d / g
            local id = nx .. "," .. ny .. "," .. nz .. "," .. d
            if not seen[id] then
              seen[id] = true
              -- back to the world's coordinates: n.(p - o) <= d  becomes  n.p <= d + n.o
              planes[#planes + 1] = {nx, ny, nz, d + nx * o.x + ny * o.y + nz * o.z}
            end
          end
        end
      end
    end
  end
  return planes
end

KINDS.convex = {
  bounds = function(r)
    return points_box(r.pts)
  end,
  rows = function(r, emit)
    local lo, hi = points_box(r.pts)
    for y = lo.y, hi.y do
      for z = lo.z, hi.z do
        local from, to = lo.x, hi.x
        for _, plane in ipairs(r.planes) do
          local nx, rest = plane[1], plane[4] - plane[2] * y - plane[3] * z
          if nx == 0 then
            if rest < 0 then
              to = from - 1
            end
          elseif nx > 0 then
            to = math.min(to, floor(rest / nx))
          else
            from = math.max(from, ceil(rest / nx))
          end
        end
        if from <= to then
          emit(y, z, from, to)
        end
      end
    end
  end,
  estimate = function()
    return 0
  end,
  expand = function()
    cannot("convex", "expanded")
  end,
  contract = function()
    cannot("convex", "contracted")
  end,
  shift = function(r, v)
    local pts = {}
    for i, p in ipairs(r.pts) do
      pts[i] = {x = p.x + v.x, y = p.y + v.y, z = p.z + v.z}
    end
    return changed(r, {pts = pts, planes = hull_planes(pts)})
  end,
  center = function(r)
    local x, y, z = 0, 0, 0
    for _, p in ipairs(r.pts) do
      x, y, z = x + p.x, y + p.y, z + p.z
    end
    return x / #r.pts, y / #r.pts, z / #r.pts
  end,
}

-- cells: any shape, as the list of its cells

KINDS.cells = {
  bounds = function(r)
    return bounds_of(r.list)
  end,
  rows = function(r, emit)
    local lo, hi = bounds_of(r.list)
    if not lo then
      return
    end
    local span = hi.z - lo.z + 1
    local row_of, rows = {}, {}
    for _, p in ipairs(r.list) do
      local k = (p.y - lo.y) * span + (p.z - lo.z)
      local row = row_of[k]
      if not row then
        row = {y = p.y, z = p.z}
        row_of[k] = row
        rows[#rows + 1] = row
      end
      row[#row + 1] = p.x
    end
    for _, row in ipairs(rows) do
      table.sort(row)
      local from, to
      for _, x in ipairs(row) do
        if from and x <= to + 1 then
          to = math.max(to, x)
        else
          if from then
            emit(row.y, row.z, from, to)
          end
          from, to = x, x
        end
      end
      emit(row.y, row.z, from, to)
    end
  end,
  estimate = function(r)
    return #r.list
  end,
  expand = function()
    cannot("selection of arbitrary cells", "expanded")
  end,
  contract = function()
    cannot("selection of arbitrary cells", "contracted")
  end,
  shift = function(r, v)
    local list = {}
    for i, p in ipairs(r.list) do
      list[i] = {x = p.x + v.x, y = p.y + v.y, z = p.z + v.z}
    end
    return changed(r, {list = list})
  end,
  center = function(r)
    local lo, hi = bounds_of(r.list)
    return (lo.x + hi.x) / 2, (lo.y + hi.y) / 2, (lo.z + hi.z) / 2
  end,
}

-- a region of any kind as it is; Cells (or a list of cells) as the region of arbitrary shape that has exactly those cells
local function as_region(x)
  if getmetatable(x) == M.Region then
    return x
  end
  local list = {}
  for i, c in ipairs(x) do
    local p = where(c)
    list[i] = {x = p.x, y = p.y, z = p.z}
  end
  return region({kind = "cells", list = list, origin = given_origin(x)})
end

-- a region of arbitrary shape from a list of cells drawn at origin
local function region_of_cells(list, origin)
  return region({kind = "cells", list = list, origin = origin})
end

-- visits every cell of the region; a region that is plainly more cells than one Cells holds fails before the loop
local function visit(r, fn)
  local kind = KINDS[r.kind]
  local estimate = kind.estimate(r)
  if estimate > max_cells() * 1.25 then
    check_size(floor(estimate))
  end
  kind.rows(r, function(y, z, from, to)
    for x = from, to do
      fn(x, y, z)
    end
  end)
end

-- the cells of the region as a list of {x, y, z}
local function cell_list(r)
  local out = {}
  visit(r, function(x, y, z)
    out[#out + 1] = {x = x, y = y, z = z}
  end)
  return out
end

-- ---- selections ----

---A selection of the box between two corners (cuboid, WorldEdit's default). Draw it with :set, :walls or :faces, change
---it with :expand, :contract, :shift, :outset and :inset, take its cells with :cells.
---@param a Pos
---@param b Pos
---@return Region
function M.cuboid(a, b)
  return region({kind = "cuboid", p1 = cell_at(a), p2 = cell_at(b)})
end

---A selection of a polygon seen from above, between layers min_y and max_y. points are at least three Pos (their x and
---z count); the edges and corners are inside.
---@param points Pos[]
---@param min_y integer
---@param max_y integer
---@return Region
function M.poly(points, min_y, max_y)
  if #points < 3 then
    bad("poly needs at least three points; got " .. #points,
        "numen.shape.poly({{x = 0, y = 64, z = 0}, {x = 6, y = 64, z = 0}, {x = 0, y = 64, z = 6}}, 64, 70)")
  end
  local pts = {}
  for i, p in ipairs(points) do
    local q = cell_at(p)
    pts[i] = {x = q.x, z = q.z}
  end
  return ordered_y(region({kind = "poly", pts = pts}), floor(min_y), floor(max_y))
end

local function radii_of(radii, count, lowest, hint)
  local out = {}
  if type(radii) == "number" then
    for i = 1, count do
      out[i] = math.max(lowest, radii)
    end
    return out
  end
  if type(radii) == "table" and (#radii == count or #radii == 1) then
    for i = 1, count do
      out[i] = math.max(lowest, radii[#radii == 1 and 1 or i])
    end
    return out
  end
  bad("radii is one number" .. (count == 2 and " (x and z the same) or {x, z}" or " (all three the same) or {x, y, z}")
      .. "; got " .. (type(radii) == "table" and #radii .. " values" or type(radii)), hint)
end

---A selection of the ellipsoid around center with radii (one number for a sphere, or {x, y, z}; decimals work, the
---surface is half a block beyond the radius as in WorldEdit).
---@param center Pos
---@param radii number|number[]
---@return Region
function M.ellipsoid(center, radii)
  local c = cell_at(center)
  local r = radii_of(radii, 3, 0, "numen.shape.ellipsoid(pos, {4, 6, 4})")
  return region({kind = "ellipsoid", cx = c.x, cy = c.y, cz = c.z, rx = r[1], ry = r[2], rz = r[3],
                 origin = M.pos(c.x, c.y, c.z)})
end

---A selection of the convex hull of at least four points that do not lie in one plane (a flat shape is a poly).
---@param points Pos[]
---@return Region
function M.convex(points)
  local pts, seen = {}, {}
  for _, p in ipairs(points) do
    local q = cell_at(p)
    if not seen[key(q)] then
      seen[key(q)] = true
      pts[#pts + 1] = q
    end
  end
  local planes = hull_planes(pts)
  if #planes == 0 then
    bad("convex needs at least four different points that do not lie in one plane; got " .. #pts .. " different "
        .. "point(s)", "numen.shape.convex({{x = 0, y = 64, z = 0}, {x = 4, y = 64, z = 0}, {x = 0, y = 64, z = 4}, "
        .. "{x = 0, y = 68, z = 0}})")
  end
  return region({kind = "convex", pts = pts, planes = planes})
end

-- the Cells of a region drawn at its origin
local function located(r, out)
  return cells_of(out, r.origin)
end

local function air_block()
  return "air"
end

-- the cells of a list (Pos or Blocks) with a block from pattern each, nothing meaning air
local function painted(list, pattern, origin)
  local source = pattern == nil and air_block or block_source(pattern, function() return list end)
  local out = {}
  for _, c in ipairs(list) do
    local p = c.pos or c
    local name = source(p.x, p.y, p.z)
    if name then
      out[#out + 1] = {name = name, pos = M.pos(p.x, p.y, p.z)}
    end
  end
  return cells_of(out, origin)
end

---The cells of this selection, each a Pos.
---@return Cells
function M.Region:cells()
  local out = {}
  visit(self, function(x, y, z)
    out[#out + 1] = M.pos(x, y, z)
  end)
  return located(self, out)
end

---Every cell of this selection with a block from pattern (WorldEdit's //set). A pattern is text: "stone",
---"oak_stairs[facing=north]", "50%stone,50%cobblestone" (relative weights, a part without % weighs 1), "##logs" (any
---block of that tag), "##*logs" (any state of any of them), "*oak_log" (any value of the properties left out); or Cells
---to repeat through the world. "^[waterlogged=true]" (a pattern of its own, not one part of a list) keeps each cell's
---current block and changes only those properties; it reads loaded chunks. Each cell rolls its own block once, so
---numen.build.diff and numen.build.place see the same.
---@param pattern string|Cells|table
---@return Cells
function M.Region:set(pattern)
  local source = block_source(pattern, function() return cell_list(self) end)
  local out = {}
  visit(self, function(x, y, z)
    local name = source(x, y, z)
    if name then
      out[#out + 1] = {name = name, pos = M.pos(x, y, z)}
    end
  end)
  return located(self, out)
end

-- the common part of two sorted lists of runs ({from, to, from, to …})
local function intersect(p, q)
  local out, i, j = {}, 1, 1
  while i <= #p and j <= #q do
    local from, to = math.max(p[i], q[j]), math.min(p[i + 1], q[j + 1])
    if from <= to then
      out[#out + 1] = from
      out[#out + 1] = to
    end
    if p[i + 1] < q[j + 1] then
      i = i + 2
    else
      j = j + 2
    end
  end
  return out
end

local NONE = {}

-- runs cut down to what row covers; a single run against a single run (the usual) is cut in place
local function narrow(runs, row)
  if #runs == 0 or #row == 0 then
    return NONE
  end
  if #runs == 2 and #row == 2 then
    local from, to = math.max(runs[1], row[1]), math.min(runs[2], row[2])
    if from > to then
      return NONE
    end
    runs[1], runs[2] = from, to
    return runs
  end
  return intersect(runs, row)
end

-- the cells of a selection that touch the outside: a cell is kept when one of its six neighbours is not in the
-- selection; with walls the layers above the top and below the bottom count as inside, leaving the top and the bottom
-- open. A row at a time: what a row keeps is the ends of its runs and the cells the rows beside it do not cover.
local function shell(r, walls, fn)
  local lo, hi = KINDS[r.kind].bounds(r)
  if lo.y > hi.y then
    return
  end
  local rows = (hi.y - lo.y + 1) * (hi.z - lo.z + 1)
  if rows > max_cells() then
    check_size(rows)
  end
  local span = hi.z - lo.z + 3
  local grid, keys = {}, {}
  KINDS[r.kind].rows(r, function(y, z, from, to)
    local k = (y - lo.y + 1) * span + (z - lo.z + 1)
    local runs = grid[k]
    if not runs then
      runs = {y = y, z = z}
      grid[k] = runs
      keys[#keys + 1] = k
    end
    runs[#runs + 1] = from
    runs[#runs + 1] = to
  end)
  for _, k in ipairs(keys) do
    local runs = grid[k]
    local y, z = runs.y, runs.z
    local north, south = grid[k - 1] or NONE, grid[k + 1] or NONE
    local up = (not walls or y < hi.y) and (grid[k + span] or NONE) or nil
    local down = (not walls or y > lo.y) and (grid[k - span] or NONE) or nil
    for i = 1, #runs, 2 do
      local from, to = runs[i], runs[i + 1]
      local inner = narrow(narrow(from < to - 1 and {from + 1, to - 1} or NONE, north), south)
      if up then
        inner = narrow(inner, up)
      end
      if down then
        inner = narrow(inner, down)
      end
      local cursor = from
      for j = 1, #inner, 2 do
        for x = cursor, inner[j] - 1 do
          fn(x, y, z)
        end
        cursor = inner[j + 1] + 1
      end
      for x = cursor, to do
        fn(x, y, z)
      end
    end
  end
end

local function sides(r, pattern, faces)
  local source = block_source(pattern, function()
    local list = {}
    shell(r, not faces, function(x, y, z)
      list[#list + 1] = {x = x, y = y, z = z}
    end)
    return list
  end)
  local out = {}
  shell(r, not faces, function(x, y, z)
    local name = source(x, y, z)
    if name then
      out[#out + 1] = {name = name, pos = M.pos(x, y, z)}
    end
  end)
  return located(r, out)
end

---The four sides of this selection with a block from pattern (see Region:set), the top and bottom open (WorldEdit's
---//walls). A cuboid's four faces; any other shape's shell, its top and bottom layers left open.
---@param pattern string|Cells|table
---@return Cells
function M.Region:walls(pattern)
  return sides(self, pattern, false)
end

---The whole surface of this selection with a block from pattern (see Region:set; WorldEdit's //faces): a cuboid's
---six faces, any other shape's cells that touch the outside.
---@param pattern string|Cells|table
---@return Cells
function M.Region:faces(pattern)
  return sides(self, pattern, true)
end

---WorldEdit's second name for faces.
---@param pattern string|Cells|table
---@return Cells
function M.Region:outline(pattern)
  return self:faces(pattern)
end

---The middle of this selection with a block from pattern (see Region:set; WorldEdit's //center): one cell, or two,
---four or eight where the middle falls between cells (a side with an even number of cells).
---@param pattern string|Cells|table
---@return Cells
function M.Region:center(pattern)
  local middle = {KINDS[self.kind].center(self)}
  -- each axis: from the middle cut towards zero to the middle rounded away from zero (the same cell when it is whole)
  local ranges = {}
  for i, c in ipairs(middle) do
    local cut = c < 0 and ceil(c) or floor(c)
    local rounded = (c < 0 and -1 or 1) * floor(abs(c) + 0.5)
    ranges[i] = {math.min(cut, rounded), math.max(cut, rounded)}
  end
  local list = {}
  for x = ranges[1][1], ranges[1][2] do
    for y = ranges[2][1], ranges[2][2] do
      for z = ranges[3][1], ranges[3][2] do
        list[#list + 1] = M.pos(x, y, z)
      end
    end
  end
  return painted(list, pattern, self.origin)
end

-- ---- changing a selection ----

-- changes of one direction, or each of a list: an amount, and the amount the other way (WorldEdit's reverse amount)
local function steps(amount, dir, reverse)
  local out = {}
  for i, d in ipairs(directions(dir)) do
    out[i] = {scaled(d, amount)}
    if reverse and reverse ~= 0 then
      out[i][2] = scaled(d, -reverse)
    end
  end
  return out
end

---This selection grown by amount towards a direction ("north", "south", "east", "west", "up", "down", a vector or a
---list of them), and by reverse the other way (WorldEdit's //expand). A cuboid grows by a side, a cyl and a sphere
---by their radius (an even amount, about their centre), a poly only up and down; a convex or arbitrary cells cannot.
---The selection itself stays as it was; the grown one is returned.
---@param amount integer
---@param dir string|table
---@param reverse? integer
---@return Region
function M.Region:expand(amount, dir, reverse)
  local r = self
  for _, changes in ipairs(steps(amount, dir, reverse)) do
    r = KINDS[r.kind].expand(r, changes)
  end
  return r
end

---This selection shrunk by amount towards a direction, and by reverse the other way (WorldEdit's //contract; see
---Region:expand for the directions and what each shape allows). A cyl and a sphere keep a radius of at least 1.
---@param amount integer
---@param dir string|table
---@param reverse? integer
---@return Region
function M.Region:contract(amount, dir, reverse)
  local r = self
  for _, changes in ipairs(steps(amount, dir, reverse)) do
    r = KINDS[r.kind].contract(r, changes)
  end
  return r
end

---This selection grown to the whole height of the world, from numen.shape.y_range().min to .max (//expand vert).
---@return Region
function M.Region:expand_vert()
  local range = numen.shape.y_range()
  local height = range.max - range.min
  local r = KINDS[self.kind].expand(self, {{x = 0, y = height, z = 0}, {x = 0, y = -height, z = 0}})
  if r.kind == "cuboid" then
    return changed(r, {p1 = {x = r.p1.x, y = math.max(range.min, math.min(range.max, r.p1.y)), z = r.p1.z},
                       p2 = {x = r.p2.x, y = math.max(range.min, math.min(range.max, r.p2.y)), z = r.p2.z}})
  elseif r.kind == "poly" then
    return ordered_y(r, math.max(range.min, r.min_y), math.min(range.max, r.max_y))
  end
  return r
end

---This selection moved by dx, dy, dz, a Pos, or an amount and a direction (5, "up"); WorldEdit's //shift.
---@param dx number|Pos
---@param dy? number|string|table
---@param dz? number
---@return Region
function M.Region:shift(dx, dy, dz)
  local v = offset_of(dx, dy, dz)
  local r = KINDS[self.kind].shift(self, v)
  local o = self.origin
  return o and changed(r, {origin = M.pos(o.x + v.x, o.y + v.y, o.z + v.z)}) or r
end

-- the changes outset and inset make: the six directions, fewer with horizontal or vertical
local function all_around(amount, opts)
  opts = opts or {}
  local out = {}
  if not opts.horizontal then
    out[#out + 1] = {x = 0, y = amount, z = 0}
    out[#out + 1] = {x = 0, y = -amount, z = 0}
  end
  if not opts.vertical then
    out[#out + 1] = {x = amount, y = 0, z = 0}
    out[#out + 1] = {x = -amount, y = 0, z = 0}
    out[#out + 1] = {x = 0, y = 0, z = amount}
    out[#out + 1] = {x = 0, y = 0, z = -amount}
  end
  return out
end

---This selection grown by amount in every direction (WorldEdit's //outset). opts.horizontal grows only sideways,
---opts.vertical only up and down. See Region:expand for what each shape allows.
---@param amount integer
---@param opts? {horizontal?: boolean, vertical?: boolean}
---@return Region
function M.Region:outset(amount, opts)
  return KINDS[self.kind].expand(self, all_around(amount, opts))
end

---This selection shrunk by amount in every direction (WorldEdit's //inset). opts.horizontal shrinks only sideways,
---opts.vertical only up and down. See Region:expand for what each shape allows.
---@param amount integer
---@param opts? {horizontal?: boolean, vertical?: boolean}
---@return Region
function M.Region:inset(amount, opts)
  return KINDS[self.kind].contract(self, all_around(amount, opts))
end

-- ---- Cells as a selection of arbitrary shape ----

-- Cells take every Region method (set, walls, faces, center, expand …) on the selection of arbitrary shape that has
-- exactly their cells: what such a selection cannot do (expand, contract) it says itself
setmetatable(M.Cells, {__index = function(_, name)
  local method = M.Region[name]
  if method then
    return function(self, ...)
      return method(as_region(self), ...)
    end
  end
end})

-- ---- turning, stacking, moving, pasting ----

-- cos and sin of an angle in degrees, exact at the multiples of 90 (so a quarter turn moves cells exactly)
local function dcos(degrees)
  if degrees % 90 == 0 then
    return ({[0] = 1, [90] = 0, [180] = -1, [270] = 0})[degrees % 360]
  end
  return math.cos(math.rad(degrees))
end

local function dsin(degrees)
  if degrees % 90 == 0 then
    return ({[0] = 0, [90] = 1, [180] = 0, [270] = -1})[degrees % 360]
  end
  return math.sin(math.rad(degrees))
end

local function multiply(a, b)
  local out = {{}, {}, {}}
  for i = 1, 3 do
    for j = 1, 3 do
      out[i][j] = a[i][1] * b[1][j] + a[i][2] * b[2][j] + a[i][3] * b[3][j]
    end
  end
  return out
end

-- the turn of Cells:rotate as WorldEdit builds it: about y, then x, then z, each by minus the angle given (a positive
-- angle is clockwise seen from above); the z turn is applied first
local function rotation_matrix(y, x, z)
  local function about_y(t)
    local c, s = dcos(t), dsin(t)
    return {{c, 0, s}, {0, 1, 0}, {-s, 0, c}}
  end
  local function about_x(t)
    local c, s = dcos(t), dsin(t)
    return {{1, 0, 0}, {0, c, -s}, {0, s, c}}
  end
  local function about_z(t)
    local c, s = dcos(t), dsin(t)
    return {{c, -s, 0}, {s, c, 0}, {0, 0, 1}}
  end
  return multiply(multiply(about_y(-y), about_x(-x)), about_z(-z))
end

-- the cells moved about their origin by the linear part m (and t added before rounding down, as WorldEdit's revolve
-- does), blocks turned by the same m; cells whose block is air can be left out
local function turned(self, m, t, without_air)
  local o = origin_of(self)
  if not o then
    return cells_of({}, nil)
  end
  local names, slot = {}, {}
  for _, c in ipairs(self) do
    if c.name and not slot[c.name] then
      names[#names + 1] = c.name
      slot[c.name] = #names
    end
  end
  local blocks = #names > 0 and numen.shape.transform_blocks(names, m) or names
  local m11, m12, m13, m21, m22, m23, m31, m32, m33 = m[1][1], m[1][2], m[1][3], m[2][1], m[2][2], m[2][3], m[3][1],
      m[3][2], m[3][3]
  local ox, oy, oz = o.x, o.y, o.z
  local out = {}
  for _, c in ipairs(self) do
    if not (without_air and is_air(c)) then
      local p = c.pos or c
      local dx, dy, dz = p.x - ox, p.y - oy, p.z - oz
      local at = setmetatable({x = ox + floor(dx * m11 + dy * m12 + dz * m13 + t),
                               y = oy + floor(dx * m21 + dy * m22 + dz * m23 + t),
                               z = oz + floor(dx * m31 + dy * m32 + dz * m33 + t)}, M.Pos)
      local name = c.pos and c.name
      out[#out + 1] = name and {name = blocks[slot[name]], pos = at} or at
    end
  end
  return cells_of(out, o)
end

---Turned about the origin by y degrees (clockwise seen from above), x and z about those axes: WorldEdit's //rotate.
---The blocks turn along: facing, axis, shape and rotation follow (vanilla's own rotate for quarter turns about the
---vertical axis, WorldEdit's rules for the rest). Angles that are not multiples of 90 move each cell to the cell
---its centre turns into, which leaves gaps.
---@param y number
---@param x? number
---@param z? number
---@return Cells
function M.Cells:rotate(y, x, z)
  return turned(self, rotation_matrix(y or 0, x or 0, z or 0), 0, false)
end

---Mirrored across the origin along a direction ("east" swaps east and west, "up" turns upside down; a name or a
---vector): WorldEdit's //flip. The blocks turn along (vanilla's mirror for sideways flips, WorldEdit's rules for upside
---down: half and slab type swap, rails and facing follow).
---@param dir string|table
---@return Cells
function M.Cells:flip(dir)
  local d = direction(dir)
  local m = {{1 - 2 * abs(d.x), 0, 0}, {0, 1 - 2 * abs(d.y), 0}, {0, 0, 1 - 2 * abs(d.z)}}
  return turned(self, m, 0, false)
end

---These cells and count copies, each one box further along dir (a name or a vector): WorldEdit's //stack. A box is
---the size of the box of the cells; opts.cells steps by dir in cells instead. opts.skip_air leaves air cells out of the
---copies. The copies may not overlap the original.
---@param count? integer
---@param dir string|table
---@param opts? {cells?: boolean, skip_air?: boolean}
---@return Cells
function M.Cells:stack(count, dir, opts)
  opts = opts or {}
  count = count or 1
  if count < 1 then
    bad("count is how many copies to add, at least 1; got " .. count, "cells:stack(3, \"up\")")
  end
  local lo, hi = bounds_of(self)
  if not lo then
    return self
  end
  local size = {x = hi.x - lo.x + 1, y = hi.y - lo.y + 1, z = hi.z - lo.z + 1}
  local d = direction(dir)
  local step = opts.cells and d or {x = d.x * size.x, y = d.y * size.y, z = d.z * size.z}
  if abs(step.x) < size.x and abs(step.y) < size.y and abs(step.z) < size.z then
    bad("the copies would overlap the original: they are " .. abs(step.x) .. ", " .. abs(step.y) .. ", " .. abs(step.z)
        .. " cells apart and the cells are " .. size.x .. " x " .. size.y .. " x " .. size.z,
        "cells:stack(3, \"up\")")
  end
  if not opts.skip_air then
    check_size(#self * (count + 1))
  end
  local out = {}
  for i, c in ipairs(self) do
    out[i] = c
  end
  for k = 1, count do
    for _, c in ipairs(self) do
      if not (opts.skip_air and is_air(c)) then
        local p = where(c)
        out[#out + 1] = moved(c, {x = p.x + k * step.x, y = p.y + k * step.y, z = p.z + k * step.z})
      end
    end
    check_size(#out)
  end
  return cells_of(out, given_origin(self))
end

---Moved count cells along dir (a name or a vector), the cells left behind filled from pattern leave (default air):
---WorldEdit's //move. The result is what to write to make the move; the origin moves with the cells.
---@param count? integer
---@param dir string|table
---@param leave? string|Cells|table
---@return Cells
function M.Cells:move(count, dir, leave)
  count = count or 1
  if count < 1 then
    bad("count is how many times to move by dir, at least 1; got " .. count, "cells:move(1, \"north\")")
  end
  local v = scaled(direction(dir), count)
  local there = self:shift(v.x, v.y, v.z)
  local out = {}
  for _, c in ipairs(painted(self:minus(there), leave)) do
    out[#out + 1] = c
  end
  for _, c in ipairs(there) do
    out[#out + 1] = c
  end
  -- the cells they leave would change the smallest corner, so the moved origin is given outright
  local o = origin_of(self)
  return cells_of(out, o and M.pos(o.x + v.x, o.y + v.y, o.z + v.z))
end

---Moved so that the origin lands on at (WorldEdit's //paste); the origin is at afterwards.
---@param at Pos
---@return Cells
function M.Cells:paste(at)
  local o = origin_of(self)
  local to = cell_at(at)
  if not o then
    return self
  end
  local out = self:shift(to.x - o.x, to.y - o.y, to.z - o.z)
  return cells_of(out, M.pos(to.x, to.y, to.z))
end

---These cells and count - 1 copies turned evenly round a full turn about the vertical axis through the origin:
---WorldEdit's //revolve (count at least 2). opts.reverse turns counter-clockwise. Air cells are not copied.
---@param count integer
---@param opts? {reverse?: boolean}
---@return Cells
function M.Cells:revolve(count, opts)
  if count < 2 then
    bad("count is the number of copies round the full turn, including the original: at least 2; got " .. count,
        "cells:revolve(4)")
  end
  local reverse = opts and opts.reverse
  local o = origin_of(self)
  if not o then
    return self
  end
  local all = {}
  for i, c in ipairs(self) do
    all[i] = c
  end
  for i = 1, count - 1 do
    local theta = (reverse and 1 or -1) * (360 * i) / count
    local c, s = dcos(theta), dsin(theta)
    local part = turned(self, {{c, 0, s}, {0, 1, 0}, {-s, 0, c}}, 0.5, true)
    for _, copy in ipairs(part) do
      all[#all + 1] = copy
    end
    check_size(#all)
  end
  return cells_of(last_wins(all), given_origin(self))
end

-- ---- generating shapes ----

-- the result of a shape function: the selection of the shape, or its cells with the blocks of pattern when one is given
local function finish(r, pattern)
  if pattern == nil then
    return r
  end
  return r:set(pattern)
end

local function put_cell(list)
  return function(x, y, z)
    list[#list + 1] = {x = x, y = y, z = z}
  end
end

-- the shell of a region (its faces; its walls with walls true): the cells with a block from pattern, or without one the
-- selection of exactly those cells
local function hollow(r, walls, pattern)
  if pattern ~= nil then
    return sides(r, pattern, not walls)
  end
  local list = {}
  shell(r, walls, put_cell(list))
  return region_of_cells(list, r.origin)
end

---A cylinder standing on at, which is the middle of its bottom layer, height layers up (a negative height goes down);
---radii is one number, or {x, z}, at least 1; the edge is half a block beyond the radius as in WorldEdit's //cyl. With a
---pattern (see Region:set) it is the Cells; without, the selection.
---@param at Pos
---@param pattern? string|Cells|table
---@param radii number|number[]
---@param height? integer
---@return Cells|Region
function M.cyl(at, pattern, radii, height)
  local c = cell_at(at)
  local r = radii_of(radii, 2, 1, "numen.shape.cyl(pos, \"stone\", {4, 6}, 5)")
  height = floor(height or 1)
  local min_y = height < 0 and c.y + height or c.y
  local max_y = height < 0 and c.y - 1 or c.y + height - 1
  return finish(region({kind = "cyl", cx = c.x, cz = c.z, rx = r[1], rz = r[2], min_y = min_y, max_y = max_y,
                        origin = M.pos(c.x, c.y, c.z)}), pattern)
end

---Only the wall of a cylinder (see numen.shape.cyl): the cells with an open cell beside them, no top and no bottom.
---@param at Pos
---@param pattern? string|Cells|table
---@param radii number|number[]
---@param height? integer
---@return Cells|Region
function M.hcyl(at, pattern, radii, height)
  return hollow(M.cyl(at, nil, radii, height), true, pattern)
end

---A sphere (or ellipsoid) around at; radii is one number, or {x, y, z}; the surface is half a block beyond the radius as
---in WorldEdit's //sphere. raised moves it up by its vertical radius so it rests on at. With a pattern (see
---Region:set) it is the Cells; without, the selection.
---@param at Pos
---@param pattern? string|Cells|table
---@param radii number|number[]
---@param raised? boolean
---@return Cells|Region
function M.sphere(at, pattern, radii, raised)
  local c = cell_at(at)
  local r = radii_of(radii, 3, 0, "numen.shape.sphere(pos, \"stone\", {4, 6, 4})")
  local up = raised and floor(r[2]) or 0
  return finish(region({kind = "ellipsoid", cx = c.x, cy = c.y + up, cz = c.z, rx = r[1], ry = r[2], rz = r[3],
                        origin = M.pos(c.x, c.y, c.z)}), pattern)
end

---Only the shell of a sphere (see numen.shape.sphere): the cells with an open cell beside them, the thinnest closed
---layer.
---@param at Pos
---@param pattern? string|Cells|table
---@param radii number|number[]
---@param raised? boolean
---@return Cells|Region
function M.hsphere(at, pattern, radii, raised)
  return hollow(M.sphere(at, nil, radii, raised), false, pattern)
end

-- the cells of a cone: fn(x, y, z) for each; hollow keeps a wall of the given thickness
local function cone_cells(c, r, height, hollow, thickness, fn)
  local rx, rz = r[1], r[2]
  local layers = abs(height)
  local hh, rxx, rzz = height * height, rx * rx, rz * rz
  local wx, wz = ceil(rx), ceil(rz)
  local t = thickness
  for k = 0, layers - 1 do
    local y = height < 0 and c.y - k or c.y + k
    -- the layer's share of the base: 1 at the bottom, shrinking to the tip
    local q = (k - layers) * (k - layers) / hh
    local q_inner = (k + t - layers) * (k + t - layers) / hh
    for i = -wx, wx do
      local ai = abs(i)
      for j = -wz, wz do
        local aj = abs(j)
        local fx, fz = i * i / rxx, j * j / rzz
        if fx + fz - q <= 0 then
          -- the wall: a cell stays unless a step outward on x and on z, and the layer t above, are all still inside
          local inner = hollow
              and (ai + t) * (ai + t) / rxx + fz - q <= 0
              and fx + fz - q_inner <= 0
              and fx + (aj + t) * (aj + t) / rzz - q <= 0
              and k + t ~= layers
          if not inner then
            fn(c.x + i, y, c.z + j)
          end
        end
      end
    end
  end
end

---A cone standing on at, which is the middle of its base, height layers up (a negative height goes down) to a point;
---radii is the base's one number, or {x, z}, at least 1. With a pattern (see Region:set) it is the Cells; without,
---the selection of its cells.
---@param at Pos
---@param pattern? string|Cells|table
---@param radii number|number[]
---@param height? integer
---@return Cells|Region
function M.cone(at, pattern, radii, height)
  local c = cell_at(at)
  local list = {}
  cone_cells(c, radii_of(radii, 2, 1, "numen.shape.cone(pos, \"stone\", {4, 6}, 5)"), floor(height or 1), false, 1,
      put_cell(list))
  return finish(region_of_cells(list, M.pos(c.x, c.y, c.z)), pattern)
end

---Only the wall of a cone (see numen.shape.cone), thickness cells thick (default 1).
---@param at Pos
---@param pattern? string|Cells|table
---@param radii number|number[]
---@param height? integer
---@param thickness? number
---@return Cells|Region
function M.hcone(at, pattern, radii, height, thickness)
  local c = cell_at(at)
  local list = {}
  cone_cells(c, radii_of(radii, 2, 1, "numen.shape.hcone(pos, \"stone\", {4, 6}, 5)"), floor(height or 1), true,
      thickness or 1, put_cell(list))
  return finish(region_of_cells(list, M.pos(c.x, c.y, c.z)), pattern)
end

-- the cells of a pyramid of size layers standing on c; hollow keeps only each layer's ring and no floor
local function pyramid_cells(c, size, hollow, fn)
  for k = 0, size - 1 do
    local s = size - 1 - k
    for i = -s, s do
      for j = -s, s do
        if not hollow or math.max(abs(i), abs(j)) == s then
          fn(c.x + i, c.y + k, c.z + j)
        end
      end
    end
  end
end

---A stepped pyramid standing on at, which is the middle of its base: size layers, the base 2 * size - 1 cells across.
---With a pattern (see Region:set) it is the Cells; without, the selection of its cells.
---@param at Pos
---@param pattern? string|Cells|table
---@param size integer
---@return Cells|Region
function M.pyramid(at, pattern, size)
  local c = cell_at(at)
  local list = {}
  pyramid_cells(c, floor(size), false, put_cell(list))
  return finish(region_of_cells(list, M.pos(c.x, c.y, c.z)), pattern)
end

---Only the outside of a pyramid (see numen.shape.pyramid): each layer's ring, open at the bottom.
---@param at Pos
---@param pattern? string|Cells|table
---@param size integer
---@return Cells|Region
function M.hpyramid(at, pattern, size)
  local c = cell_at(at)
  local list = {}
  pyramid_cells(c, floor(size), true, put_cell(list))
  return finish(region_of_cells(list, M.pos(c.x, c.y, c.z)), pattern)
end

-- cells (a set keyed by position) → every cell whose six neighbours are not all in the set
local function hollowed(set)
  local out = {}
  for _, p in pairs(set) do
    if not (set[key({x = p.x + 1, y = p.y, z = p.z})] and set[key({x = p.x - 1, y = p.y, z = p.z})]
        and set[key({x = p.x, y = p.y + 1, z = p.z})] and set[key({x = p.x, y = p.y - 1, z = p.z})]
        and set[key({x = p.x, y = p.y, z = p.z + 1})] and set[key({x = p.x, y = p.y, z = p.z - 1})]) then
      out[#out + 1] = p
    end
  end
  return out
end

-- every cell within thickness of a cell of the set (a ball round each), as a set
local function ballooned(set, thickness)
  local reach_out = ceil(thickness)
  local ball = {}
  for dy = -reach_out, reach_out do
    for dz = -reach_out, reach_out do
      for dx = -reach_out, reach_out do
        if dx * dx + dy * dy + dz * dz <= thickness * thickness then
          ball[#ball + 1] = {dx, dy, dz}
        end
      end
    end
  end
  local out = {}
  for _, p in pairs(set) do
    for _, d in ipairs(ball) do
      local q = {x = p.x + d[1], y = p.y + d[2], z = p.z + d[3]}
      out[key(q)] = q
    end
  end
  return out
end

-- the cells of a thick path whose centre line is the set of cells given
local function thick(centre, thickness, hollow, origin, pattern)
  local set = ballooned(centre, thickness)
  local list = {}
  for _, p in pairs(hollow and hollowed(set) or set) do
    list[#list + 1] = p
  end
  return finish(region_of_cells(list, origin), pattern)
end

-- what a line or a curve needs: two or more points, a thickness of 0 or more
local function check_path(points, thickness)
  if #points < 2 then
    bad("a path needs at least two points; got " .. #points, "numen.shape.line({a, b}, \"stone\")")
  end
  if thickness < 0 then
    bad("thickness is at least 0; got " .. thickness, "numen.shape.line({a, b}, \"stone\", 1)")
  end
end

local function sign(n)
  return n > 0 and 1 or -1
end

local function round(n)
  return floor(n + 0.5)
end

-- the cells of the straight lines between consecutive points, as WorldEdit's //line draws them: the longest axis
-- steps one cell at a time, the others follow, rounded
local function centre_line(points)
  local out = {}
  local axes = {"x", "y", "z"}
  for n = 1, #points - 1 do
    local a, b = cell_at(points[n]), cell_at(points[n + 1])
    local d = {x = abs(b.x - a.x), y = abs(b.y - a.y), z = abs(b.z - a.z)}
    -- the longest axis steps; a tie goes to x, then y
    local main = "x"
    if d.y > d[main] then main = "y" end
    if d.z > d[main] then main = "z" end
    if d[main] == 0 then
      out[key(a)] = a
    else
      for step = 0, d[main] do
        local p = {}
        for _, axis in ipairs(axes) do
          local s = sign(b[axis] - a[axis])
          if axis == main then
            p[axis] = a[axis] + step * s
          else
            p[axis] = round(a[axis] + step * d[axis] / d[main] * s)
          end
        end
        out[key(p)] = p
      end
    end
  end
  return out
end

---A line through points (two or more Pos; diagonals included), thickness the radius of the ball drawn round each cell
---of it (default 0: one cell wide), as WorldEdit's //line. With a pattern (see Region:set) it is the Cells; without,
---the selection of its cells.
---@param points Pos[]
---@param pattern? string|Cells|table
---@param thickness? number
---@return Cells|Region
function M.line(points, pattern, thickness)
  thickness = thickness or 0
  check_path(points, thickness)
  local first = cell_at(points[1])
  return thick(centre_line(points), thickness, false, M.pos(first.x, first.y, first.z), pattern)
end

---Only the outside of a thick line (see numen.shape.line): the cells with an open neighbour.
---@param points Pos[]
---@param pattern? string|Cells|table
---@param thickness? number
---@return Cells|Region
function M.hline(points, pattern, thickness)
  thickness = thickness or 0
  check_path(points, thickness)
  local first = cell_at(points[1])
  return thick(centre_line(points), thickness, true, M.pos(first.x, first.y, first.z), pattern)
end

-- the length of the Catmull-Rom spline through nodes, summed over its segments; eight steps each
-- (coeff[i] is segment i's cubic {a, b, c, d}, one {x, y, z} each); the speed |3a t² + 2b t + c| by the trapezoid rule
-- over the ends and the inner eighths 1 to 6
local function spline_length(coeff)
  local function speed(s, t)
    local v = {}
    for _, axis in ipairs({"x", "y", "z"}) do
      v[axis] = (3 * s.a[axis] * t + 2 * s.b[axis]) * t + s.c[axis]
    end
    return math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
  end
  local length = 0
  for i = 1, #coeff - 1 do
    local s = coeff[i]
    local sum = 0.5 * speed(s, 0)
    for k = 1, 6 do
      sum = sum + speed(s, k / 8)
    end
    sum = sum + 0.5 * speed(s, 1)
    length = length + sum / 8
  end
  return length
end

-- the cells a smooth curve through the points passes: the spline is sampled ten times a cell of its length
local function curve_line(points)
  local nodes = {}
  for i, p in ipairs(points) do
    local q = cell_at(p)
    nodes[i] = {x = q.x + 0.5, y = q.y + 0.5, z = q.z + 0.5}
  end
  local n = #nodes
  local function node(i)
    return nodes[math.max(1, math.min(n, i))]
  end
  -- segment i runs from node i to node i + 1 (Catmull-Rom, tension 0); segment n is there for the parameter 1 itself
  local coeff = {}
  for i = 1, n do
    local p0, p1, p2, p3 = node(i - 1), node(i), node(i + 1), node(i + 2)
    local s = {a = {}, b = {}, c = {}, d = {}}
    for _, axis in ipairs({"x", "y", "z"}) do
      local v0, v1, v2, v3 = p0[axis], p1[axis], p2[axis], p3[axis]
      s.a[axis] = -0.5 * v0 + 1.5 * v1 - 1.5 * v2 + 0.5 * v3
      s.b[axis] = v0 - 2.5 * v1 + 2 * v2 - 0.5 * v3
      s.c[axis] = -0.5 * v0 + 0.5 * v2
      s.d[axis] = v1
    end
    coeff[i] = s
  end
  local out = {}
  local function sample(u)
    local at = u * (n - 1)
    local m = floor(at)
    local tau = at - m
    local s = coeff[m + 1]
    local p = {}
    for _, axis in ipairs({"x", "y", "z"}) do
      p[axis] = floor(((s.a[axis] * tau + s.b[axis]) * tau + s.c[axis]) * tau + s.d[axis])
    end
    out[key(p)] = p
  end
  local length = spline_length(coeff)
  if length == 0 then
    sample(0)
    return out
  end
  -- the parameter grows by adding the step each time, so the samples are the ones that sum reaches
  local step = 1 / length / 10
  local u = 0
  while u <= 1 do
    sample(u)
    u = u + step
  end
  return out
end

---A smooth curve through points (Kochanek-Bartels with tension, bias and continuity 0: it passes through every point), thickness
---the radius of the ball drawn round each cell of it (default 0), as WorldEdit's //curve. With a pattern (see
---Region:set) it is the Cells; without, the selection of its cells.
---@param points Pos[]
---@param pattern? string|Cells|table
---@param thickness? number
---@return Cells|Region
function M.curve(points, pattern, thickness)
  thickness = thickness or 0
  check_path(points, thickness)
  local first = cell_at(points[1])
  return thick(curve_line(points), thickness, false, M.pos(first.x, first.y, first.z), pattern)
end

---Only the outside of a thick curve (see numen.shape.curve).
---@param points Pos[]
---@param pattern? string|Cells|table
---@param thickness? number
---@return Cells|Region
function M.hcurve(points, pattern, thickness)
  thickness = thickness or 0
  check_path(points, thickness)
  local first = cell_at(points[1])
  return thick(curve_line(points), thickness, true, M.pos(first.x, first.y, first.z), pattern)
end

-- the space a function sees a selection in, as WorldEdit's //generate and //deform give it: the cell it is for is
-- (cell - zero) / unit; "unit" is -1 to 1 across the selection's box
local function space_of(lo, hi, mode)
  if mode == nil or mode == "unit" then
    local zero = {x = (lo.x + hi.x) / 2, y = (lo.y + hi.y) / 2, z = (lo.z + hi.z) / 2}
    local unit = {x = hi.x - zero.x, y = hi.y - zero.y, z = hi.z - zero.z}
    for _, axis in ipairs({"x", "y", "z"}) do
      if unit[axis] == 0 then
        unit[axis] = 1
      end
    end
    return zero, unit
  elseif mode == "world" then
    return {x = 0, y = 0, z = 0}, {x = 1, y = 1, z = 1}
  elseif mode == "center" then
    return {x = (lo.x + hi.x) / 2, y = (lo.y + hi.y) / 2, z = (lo.z + hi.z) / 2}, {x = 1, y = 1, z = 1}
  elseif type(mode) == "table" and mode.x then
    return {x = mode.x, y = mode.y, z = mode.z}, {x = 1, y = 1, z = 1}
  end
  bad("mode is \"unit\" (-1 to 1 across the selection), \"world\" (the cell's own coordinates), \"center\" (distance "
      .. "from the middle of the selection) or a Pos (distance from that cell); got " .. tostring(mode),
      "numen.shape.generate(region, \"stone\", function(x, y, z) return x * x + y * y + z * z <= 1 end, \"unit\")")
end

-- the cells of region where fn(x, y, z) says true, as WorldEdit's //generate: fn gets each cell's coordinates in the
-- space mode names; hollow keeps the cells that have a neighbour where fn says false (asked for the neighbours too)
local function generated(r, fn, mode, hollow)
  local lo, hi = KINDS[r.kind].bounds(r)
  local zero, unit = space_of(lo, hi, mode)
  -- fn is asked once per cell, its answer kept: hollow asks about neighbours that other cells share
  local answers = {}
  local function inside(x, y, z)
    local k = x .. "," .. y .. "," .. z
    local answer = answers[k]
    if answer == nil then
      answer = fn((x - zero.x) / unit.x, (y - zero.y) / unit.y, (z - zero.z) / unit.z)
      if type(answer) ~= "boolean" then
        bad("the function returns true for a cell that is part of the shape and false for one that is not; it gave "
            .. type(answer), "function(x, y, z) return x * x + y * y + z * z <= 1 end")
      end
      answers[k] = answer
    end
    return answer
  end
  local out = {}
  visit(r, function(x, y, z)
    if inside(x, y, z) and (not hollow
        or not inside(x + 1, y, z) or not inside(x - 1, y, z)
        or not inside(x, y + 1, z) or not inside(x, y - 1, z)
        or not inside(x, y, z + 1) or not inside(x, y, z - 1)) then
      out[#out + 1] = {x = x, y = y, z = z}
    end
  end)
  return out
end

---The cells of region (a selection, or Cells) where fn(x, y, z) returns true, as WorldEdit's //generate with a function
---instead of an expression. mode says what x, y, z are: "unit" (default; -1 to 1 across the selection, 0 in the middle),
---"world" (the cell's own coordinates), "center" (blocks from the middle of the selection) or a Pos (blocks from
---that cell). With a pattern (see Region:set) it is the Cells; without, the selection of the cells.
---@param region Region|Cells
---@param pattern? string|Cells|table
---@param fn fun(x: number, y: number, z: number): boolean
---@param mode? string|Pos
---@return Cells|Region
function M.generate(region, pattern, fn, mode)
  local r = as_region(region)
  return finish(region_of_cells(generated(r, fn, mode, false), r.origin), pattern)
end

---Only the outside of the shape numen.shape.generate makes: the cells with a neighbour where fn says false.
---@param region Region|Cells
---@param pattern? string|Cells|table
---@param fn fun(x: number, y: number, z: number): boolean
---@param mode? string|Pos
---@return Cells|Region
function M.hgenerate(region, pattern, fn, mode)
  local r = as_region(region)
  return finish(region_of_cells(generated(r, fn, mode, true), r.origin), pattern)
end

---One level drawn as a character grid: a floor, a wall ring, a roof course, a window pattern. Rows run +x from at, the
---first row at its z and each next row one further south, so the grid reads like a map; legend says which block each
---character is ({["#"] = "cobblestone", ["<"] = "oak_stairs[facing=south]"}). ' ' and '.' leave a cell out; "air" digs one.
---@param at Pos
---@param rows string[]
---@param legend table<string, string>
---@return Cells
function M.layer(at, rows, legend)
  local o = where(at)
  local out = {}
  for r, row in ipairs(rows) do
    for col = 1, #row do
      local ch = row:sub(col, col)
      if ch ~= " " and ch ~= "." then
        local block = legend[ch]
        if not block then
          bad("layer: row " .. r .. " has '" .. ch .. "', which the legend does not name",
              "add [\"" .. ch .. "\"] = \"<block>\" to the legend")
        end
        out[#out + 1] = cell({x = o.x + col - 1, y = o.y, z = o.z + r - 1}, block)
      end
    end
  end
  return cells_of(out, nil)
end

-- ---- reading the world ----
-- What these ask of the world is answered by the Java side, a whole selection per call (numen.shape.read, matching,
-- interior, tops …); nothing here writes, the cells they give are for numen.build.place.

-- the floor plan WorldEdit works down: a flat selection's (cyl, poly) own columns, any other's box; and the lowest and
-- highest y of the selection
local function columns_of(r)
  local lo, hi = KINDS[r.kind].bounds(r)
  local out = {}
  if r.kind == "cyl" or r.kind == "poly" then
    KINDS[r.kind].rows(r, function(y, z, from, to)
      if y == lo.y then
        for x = from, to do
          out[#out + 1] = {x = x, y = 0, z = z}
        end
      end
    end)
  else
    for z = lo.z, hi.z do
      for x = lo.x, hi.x do
        out[#out + 1] = {x = x, y = 0, z = z}
      end
    end
  end
  check_size(#out)
  return out, lo.y, hi.y
end

---The cells of this selection whose block in the world passes mask, each with a block from pattern (WorldEdit's
---//replace). A mask is text: "stone,dirt" (one of them; a property left out matches any value), "##logs" (a block of
---that tag), "^[facing=north]" (blocks with those property values), "#existing" (not air, the default), "#air", "#solid",
---"#fullcube", "#surface", "!m" (not m), ">m" (m under it), "<m" (m over it), "~m" (m beside it), "%25" (a quarter of the
---cells, by chance); words separated by spaces must all hold. Reads loaded chunks only; the whole call fails, saying
---where, if the selection reaches an unloaded one.
---@param mask? string
---@param pattern string|Cells|table
---@return Cells
function M.Region:replace(mask, pattern)
  if pattern == nil then
    bad("replace needs the pattern to put where the mask matches", "region:replace(\"dirt\", \"stone\")")
  end
  return painted(numen.shape.matching(self:cells(), mask or "#existing"), pattern, self.origin)
end

---On top of the highest block in each column of this selection, a block from pattern (WorldEdit's //overlay): the
---cell above the highest block that is not air, between the selection's lowest and highest y (one cell above its top
---at most). A column with something already over it is left alone. Reads loaded chunks only.
---@param pattern string|Cells|table
---@return Cells
function M.Region:overlay(pattern)
  local columns, min_y, max_y = columns_of(self)
  return painted(numen.shape.tops(columns, min_y, max_y), pattern, self.origin)
end

---The inside of this selection emptied (WorldEdit's //hollow): the cells no open air reaches, found by flowing in from
---the selection's box through every block that does not stop movement. thickness is how much shell to leave, as a
---walker steps (default 0, the one layer touching the outside); pattern fills what is emptied (default air). A selection
---solid all round has no open air and is emptied whole. Reads loaded chunks only.
---@param thickness? integer
---@param pattern? string|Cells|table
---@return Cells
function M.Region:hollow(thickness, pattern)
  return painted(numen.shape.interior(self:cells(), thickness), pattern, self.origin)
end

---The blocks that make this selection's ground natural (WorldEdit's //naturalize): in each column the grass, dirt and
---stone from the highest of them down become grass on top, three layers of dirt, stone below. Reads loaded chunks only.
---@return Cells
function M.Region:naturalize()
  local columns, min_y, max_y = columns_of(self)
  return numen.shape.naturalized(columns, min_y, max_y)
end

---The blocks that smooth the ground in this selection's box (WorldEdit's //smooth): each column's height blurred with a
---Gaussian kernel, iterations times (default 1), the columns raised or lowered to it. mask says which blocks are
---ground (default: blocks that stop movement). Reads loaded chunks only.
---@param iterations? integer
---@param mask? string
---@return Cells
function M.Region:smooth(iterations, mask)
  local lo, hi = KINDS[self.kind].bounds(self)
  return numen.shape.smoothed(lo, hi, {iterations = iterations, mask = mask})
end

---The smallest box round the cells of this selection whose block passes mask (WorldEdit's //trim), as a cuboid selection.
---mask defaults to "#existing" (not air); with no cell passing, it fails. Reads loaded chunks only.
---@param mask? string
---@return Region
function M.Region:trim(mask)
  local hits = numen.shape.matching(self:cells(), mask or "#existing")
  local lo, hi = bounds_of(hits)
  if not lo then
    bad("no cell of this selection has a block that passes the mask " .. (mask or "#existing"),
        "region:trim(\"#existing\")")
  end
  return M.cuboid(lo, hi)
end

---This selection with each cell taking the block from another place (WorldEdit's //deform): fn(x, y, z) gets the cell's
---coordinates and returns the coordinates of the cell to take the block from, in the space mode names (see
---numen.shape.generate: "unit" by default, "world", "center" or a Pos). Reads loaded chunks only.
---@param fn fun(x: number, y: number, z: number): number, number, number
---@param mode? string|Pos
---@return Cells
function M.Region:deform(fn, mode)
  local lo, hi = KINDS[self.kind].bounds(self)
  local zero, unit = space_of(lo, hi, mode)
  local targets, sources = {}, {}
  visit(self, function(x, y, z)
    local ox, oy, oz = fn((x - zero.x) / unit.x, (y - zero.y) / unit.y, (z - zero.z) / unit.z)
    if type(ox) ~= "number" or type(oy) ~= "number" or type(oz) ~= "number" then
      bad("the function returns the three coordinates to take the block from, x, y, z; it gave " .. type(ox) .. ", "
          .. type(oy) .. ", " .. type(oz), "function(x, y, z) return x, y + 1, z end")
    end
    targets[#targets + 1] = {x = x, y = y, z = z}
    sources[#sources + 1] = {x = floor(ox * unit.x + zero.x + 0.5), y = floor(oy * unit.y + zero.y + 0.5),
                             z = floor(oz * unit.z + zero.z + 0.5)}
  end)
  local blocks = numen.shape.read(sources)
  local out = {}
  for i, b in ipairs(blocks) do
    local t = targets[i]
    out[i] = {name = b.name, pos = M.pos(t.x, t.y, t.z)}
  end
  return cells_of(out, self.origin)
end

---A copy of the blocks in a selection, as Cells to paste elsewhere (WorldEdit's //copy): every cell with the block the
---world has there now, air included; the origin is the cell you stand in, so numen.shape.copy(r):paste(at) puts
---it down with that cell on at. mask (see Region:replace) makes the cells that fail it read as air. A block's items and
---other data are not copied, only its state. Reads loaded chunks only.
---@param region Region|Cells
---@param mask? string
---@return Cells
function M.copy(region, mask)
  local cells = as_region(region):cells()
  return M.cells(numen.shape.read(cells, mask), cell_at(numen.status.self().pos))
end

---Copy a selection and clear it (WorldEdit's //cut): a table with clipboard, the same Cells numen.shape.copy gives, and
---cleared, the selection's cells (those passing mask, when given) with a block from the pattern leave (default air).
---Writing cleared removes the original; writing clipboard:paste(at) puts it down elsewhere.
---@param region Region|Cells
---@param leave? string|Cells|table
---@param mask? string
---@return {clipboard: Cells, cleared: Cells}
function M.cut(region, leave, mask)
  local r = as_region(region)
  local cells = r:cells()
  return {clipboard = M.copy(r, mask), cleared = painted(mask and numen.shape.matching(cells, mask) or cells, leave, r.origin)}
end

-- fill and fillr: the air the cell reaches, with the pattern
local function filled(at, pattern, radius, depth, everywhere)
  if pattern == nil then
    bad("fill needs the pattern to fill with", "numen.shape.fill(pos, \"water\", 5)")
  end
  local c = cell_at(at)
  return painted(numen.shape.holes(c, radius, depth, {everywhere = everywhere}), pattern, M.pos(c.x, c.y, c.z))
end

---Fill a hole with a pattern (WorldEdit's //fill): the air in a ball of radius round at, at at's height or lower, no
---deeper than depth (default 1), that at reaches going sideways at its own level and then down. Reads loaded chunks only.
---@param at Pos
---@param pattern string|Cells|table
---@param radius number
---@param depth? integer
---@return Cells
function M.fill(at, pattern, radius, depth)
  return filled(at, pattern, radius, depth, false)
end

---Fill a hole recursively (WorldEdit's //fillr): like numen.shape.fill, but the air spreads in all six directions and
---depth has no limit unless given.
---@param at Pos
---@param pattern string|Cells|table
---@param radius number
---@param depth? integer
---@return Cells
function M.fillr(at, pattern, radius, depth)
  return filled(at, pattern, radius, depth, true)
end

-- the box of cells size - 1 each way from the cell at, from at's layer up (or down) height layers, defaulting to the
-- world's own limit
local function column_box(at, size, height, up)
  local c = cell_at(at)
  local reach = math.max(1, floor(size or 1)) - 1
  local limit
  if height then
    limit = up and c.y + height - 1 or c.y - height + 1
  else
    local range = numen.shape.y_range()
    limit = up and range.max or range.min
  end
  return M.cuboid({x = c.x - reach, y = c.y, z = c.z - reach}, {x = c.x + reach, y = limit, z = c.z + reach})
end

---Air from the cell at upward (WorldEdit's removeabove): the square of columns size - 1 cells either side of it, height
---layers tall (default to the top of the world).
---@param at Pos
---@param size? integer
---@param height? integer
---@return Cells
function M.removeabove(at, size, height)
  return column_box(at, size, height, true):set("air")
end

---Air from the cell at downward (WorldEdit's removebelow): the square of columns size - 1 cells either side of it,
---height layers deep (default to the bottom of the world).
---@param at Pos
---@param size? integer
---@param height? integer
---@return Cells
function M.removebelow(at, size, height)
  return column_box(at, size, height, false):set("air")
end

---Air where blocks passing mask stand (WorldEdit's removenear), in the cube of radius - 1 cells either side of at.
---radius defaults to 50, a cube too big for one Cells: give the radius you need. Reads loaded chunks only.
---@param at Pos
---@param mask string
---@param radius? integer
---@return Cells
function M.removenear(at, mask, radius)
  local c, reach = cell_at(at), math.max(1, floor(radius or 50)) - 1
  return M.cuboid({x = c.x - reach, y = c.y - reach, z = c.z - reach}, {x = c.x + reach, y = c.y + reach, z = c.z + reach})
      :replace(mask, "air")
end

---A pattern where blocks passing mask (default "#existing") stand (WorldEdit's replacenear), in the cube of radius cells
---either side of at. Reads loaded chunks only.
---@param at Pos
---@param radius integer
---@param mask? string
---@param pattern string|Cells|table
---@return Cells
function M.replacenear(at, radius, mask, pattern)
  local c, reach = cell_at(at), math.max(1, floor(radius))
  return M.cuboid({x = c.x - reach, y = c.y - reach, z = c.z - reach}, {x = c.x + reach, y = c.y + reach, z = c.z + reach})
      :replace(mask, pattern)
end

---Scatter a pattern round a point (WorldEdit's splatter brush): the cells of a ball of radius round at get it, each by
---chance, fewer towards the edge as decay (0 to 10, default 1) is bigger. pattern defaults to cobblestone.
---@param at Pos
---@param pattern? string|Cells|table
---@param radius? number
---@param decay? integer
---@return Cells
function M.splatter(at, pattern, radius, decay)
  radius, decay = radius or 2, decay or 1
  if decay < 0 or decay > 10 then
    bad("decay is from 0 to 10; got " .. decay, "numen.shape.splatter(pos, \"gravel\", 3, 2)")
  end
  local c = cell_at(at)
  local ball = M.sphere(c, nil, radius)
  local source = block_source(pattern or "cobblestone", function() return cell_list(ball) end)
  local size2, out = radius * radius, {}
  visit(ball, function(x, y, z)
    local d2 = (x - c.x) ^ 2 + (y - c.y) ^ 2 + (z - c.z) ^ 2
    if math.random() > d2 / size2 * (decay / 10) * 2 then
      local name = source(x, y, z)
      if name then
        out[#out + 1] = {name = name, pos = M.pos(x, y, z)}
      end
    end
  end)
  return cells_of(out, M.pos(c.x, c.y, c.z))
end

---Snow layers laid over the ground of this selection's box, smoothed (WorldEdit's //snowsmooth): the same height blur as
---Region:smooth, with each snow layer an eighth of a block, laid as snow with layers snow blocks under it (default 1).
---mask says which blocks are ground (default: blocks that stop movement). Reads loaded chunks only.
---@param iterations? integer
---@param layers? integer
---@param mask? string
---@return Cells
function M.Region:snowsmooth(iterations, layers, mask)
  local lo, hi = KINDS[self.kind].bounds(self)
  return numen.shape.snowsmoothed(lo, hi, {iterations = iterations, layers = layers, mask = mask})
end

---How many cells of this selection hold a block that passes mask (WorldEdit's //count; see Region:replace for the mask).
---Reads loaded chunks only.
---@param mask string
---@return integer
function M.Region:count(mask)
  return numen.shape.count(self:cells(), mask)
end

---How many cells of this selection hold each kind of block, the most common first (WorldEdit's //distr): a list of
---{name, count}. opts.mask counts only the blocks that pass it, opts.separate counts every block state on its own.
---Reads loaded chunks only.
---@param opts? {mask: string, separate: boolean}
---@return {name: string, count: integer}[]
function M.Region:distr(opts)
  opts = opts or {}
  return numen.shape.distr(self:cells(), {mask = opts.mask, separate = opts.separate})
end

---The size of this selection (WorldEdit's //size): kind, min and max (the corners of its box), size (the box's width,
---height and length), distance (between those corners) and blocks (how many cells it holds). Reads nothing.
---@return {kind: string, min: Pos, max: Pos, size: Pos, distance: number, blocks: integer}
function M.Region:size()
  local lo, hi = KINDS[self.kind].bounds(self)
  local blocks = 0
  KINDS[self.kind].rows(self, function(_, _, from, to)
    blocks = blocks + to - from + 1
  end)
  local dx, dy, dz = hi.x - lo.x, hi.y - lo.y, hi.z - lo.z
  return {kind = self.kind, min = M.pos(lo.x, lo.y, lo.z), max = M.pos(hi.x, hi.y, hi.z),
          size = M.pos(dx + 1, dy + 1, dz + 1), distance = math.sqrt(dx * dx + dy * dy + dz * dz), blocks = blocks}
end

---A forest over this selection (WorldEdit's //forest): each column, in the share density says (percent, default 5), gets
---a tree on its highest non-air block. tree is the id of a placed feature of the game that grows a tree (default
---"minecraft:oak_checked"; mods' trees work too), "worldedit:pine" or "worldedit:random". Returns {cells, lost}: lost is
---what the game's generator wanted that Cells cannot hold (see numen.shape.feature). Reads loaded chunks only.
---@param tree? string
---@param density? number
---@return {cells: Cells, lost: table[]}
function M.Region:forest(tree, density)
  local columns, min_y, max_y = columns_of(self)
  return numen.shape.forest(columns, min_y, max_y, {tree = tree, density = density})
end

---Flora over this selection (WorldEdit's //flora): grass and small flowers on grass blocks, dead bushes and cacti on sand,
---in the share density says (percent, default 5). Reads loaded chunks only.
---@param density? number
---@return Cells
function M.Region:flora(density)
  local columns, min_y, max_y = columns_of(self)
  return numen.shape.flora(columns, min_y, max_y, {density = density})
end

---A forest in the square of size cells (default 10) round at, from size layers below to size above (WorldEdit's
---forestgen): the same as numen.shape.cuboid(at - size, at + size):forest(tree, density).
---@param at Pos
---@param size? integer
---@param tree? string
---@param density? number
---@return {cells: Cells, lost: table[]}
function M.forestgen(at, size, tree, density)
  local c, n = cell_at(at), floor(size or 10)
  return M.cuboid({x = c.x - n, y = c.y - n, z = c.z - n}, {x = c.x + n, y = c.y + n, z = c.z + n}):forest(tree, density)
end

---Put out the fire near at (WorldEdit's //extinguish): air where fire burns, in the cube of radius - 1 cells either side
---of at (default 40). Reads loaded chunks only.
---@param at Pos
---@param radius? integer
---@return Cells
function M.extinguish(at, radius)
  return M.removenear(at, "fire", radius or 40)
end

-- the region WorldEdit's brushes work in: a ball by default, or "cuboid", or "cyl" (as tall as it is wide)
local function brush_region(at, radius, shape)
  local c, r = cell_at(at), radius or 5
  if shape == nil or shape == "sphere" then
    return M.sphere(c, nil, r)
  elseif shape == "cuboid" then
    return M.cuboid({x = c.x - floor(r), y = c.y - floor(r), z = c.z - floor(r)},
                    {x = c.x + floor(r), y = c.y + floor(r), z = c.z + floor(r)})
  elseif shape == "cyl" then
    return M.cyl(c, nil, r, floor(r) * 2 + 1)
  end
  bad("shape is \"sphere\", \"cuboid\" or \"cyl\"; got " .. tostring(shape), "numen.shape.raise(pos, 5, \"cuboid\")")
end

---Raise the ground in a ball (or cuboid, or cyl) round at by one block (WorldEdit's raise brush): every cell takes the
---block of the cell under it. Reads loaded chunks only.
---@param at Pos
---@param radius? number
---@param shape? string
---@return Cells
function M.raise(at, radius, shape)
  return brush_region(at, radius, shape):deform(function(x, y, z) return x, y - 1, z end, "world")
end

---Lower the ground in a ball (or cuboid, or cyl) round at by one block (WorldEdit's lower brush): every cell takes the
---block of the cell above it. Reads loaded chunks only.
---@param at Pos
---@param radius? number
---@param shape? string
---@return Cells
function M.lower(at, radius, shape)
  return brush_region(at, radius, shape):deform(function(x, y, z) return x, y + 1, z end, "world")
end

---Erode the ground in a ball round at (WorldEdit's erode brush, a preset of numen.shape.morph): solid blocks with two or
---more open faces wear down, then empty cells with five or more solid faces fill. Reads loaded chunks only.
---@param at Pos
---@param radius? number
---@return Cells
function M.erode(at, radius)
  return numen.shape.morph(at, radius or 5, {erode_faces = 2, erode_passes = 1, dilate_faces = 5, dilate_passes = 1})
end

---Dilate the ground in a ball round at (WorldEdit's dilate brush, a preset of numen.shape.morph): solid blocks with five
---or more open faces wear down, then empty cells with two or more solid faces fill. Reads loaded chunks only.
---@param at Pos
---@param radius? number
---@return Cells
function M.dilate(at, radius)
  return numen.shape.morph(at, radius or 5, {erode_faces = 5, erode_passes = 1, dilate_faces = 2, dilate_passes = 1})
end


return M
