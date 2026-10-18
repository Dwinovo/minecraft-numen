-- Positions and shapes: Pos values that add and measure, shapes drawn as Cells, and Cells you can turn, move and combine.
local M = {}

---@class Pos
M.Pos = {}
M.Pos.__index = M.Pos

---@class Cells
M.Cells = {}
M.Cells.__index = M.Cells

-- the position of a cell: a Block's pos, or the Pos itself
local function where(c)
  return c.pos or c
end

local function key(p)
  return p.x .. "," .. p.y .. "," .. p.z
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

---Cells from a list of Blocks (a block to put in each cell) or Pos (just the cells): a scan's blocks, numen.build.diff's
---result, your own list.
---@param list (Block|Pos)[]
---@return Cells
function M.cells(list)
  local out = {}
  for i, c in ipairs(list) do
    out[i] = c
  end
  return setmetatable(out, M.Cells)
end

---These cells moved by dx, dy, dz (or by a Pos).
---@param dx number|Pos
---@param dy? number
---@param dz? number
---@return Cells
function M.Cells:shift(dx, dy, dz)
  if type(dx) == "table" then
    dx, dy, dz = dx.x, dx.y, dx.z
  end
  local out = {}
  for i, c in ipairs(self) do
    local p = where(c)
    out[i] = moved(c, {x = p.x + dx, y = p.y + (dy or 0), z = p.z + (dz or 0)})
  end
  return M.cells(out)
end

local TURN = {north = "east", east = "south", south = "west", west = "north"}

-- a block state turned clockwise by quarters: facing, axis, rotation (signs, banners, heads) and the north/east/south/west
-- properties (fences, walls, glass panes) turn with it
local function turned(name, quarters)
  local base, props = name:match("^([^%[]+)%[(.*)%]$")
  if not base or quarters == 0 then
    return name
  end
  local out = {}
  for prop in props:gmatch("[^,]+") do
    local k, v = prop:match("^%s*([%w_]+)%s*=%s*([%w_]+)%s*$")
    if k then
      if k == "facing" and TURN[v] then
        for _ = 1, quarters do
          v = TURN[v]
        end
      elseif k == "axis" and quarters % 2 == 1 then
        v = v == "x" and "z" or v == "z" and "x" or v
      elseif k == "rotation" and tonumber(v) then
        v = tostring((tonumber(v) + 4 * quarters) % 16)
      elseif TURN[k] then
        for _ = 1, quarters do
          k = TURN[k]
        end
      end
      out[#out + 1] = k .. "=" .. v
    end
  end
  return base .. "[" .. table.concat(out, ",") .. "]"
end

---These cells turned clockwise seen from above, about origin (default {x = 0, y = 0, z = 0}): quarters is 1 for 90
---degrees, 2 for 180, 3 for 270. Facing, axis and the like of each block turn with it.
---@param quarters integer
---@param origin? Pos
---@return Cells
function M.Cells:rotate(quarters, origin)
  local q = quarters % 4
  local o = origin and where(origin) or {x = 0, y = 0, z = 0}
  local out = {}
  for i, c in ipairs(self) do
    local p = where(c)
    local dx, dz = p.x - o.x, p.z - o.z
    for _ = 1, q do
      dx, dz = -dz, dx
    end
    local at = {x = o.x + dx, y = p.y, z = o.z + dz}
    out[i] = c.pos and cell(at, c.name and turned(c.name, q)) or cell(at)
  end
  return M.cells(out)
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
  return M.cells(out)
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
  return M.cells(out)
end

---Every cell of the box between two corners, or only its faces when hollow. With block each cell is that Block (written
---as /setblock takes it), without it a Pos.
---@param from Pos
---@param to Pos
---@param block? string
---@param hollow? boolean
---@return Cells
function M.box(from, to, block, hollow)
  local a, b = where(from), where(to)
  local x1, x2 = math.min(a.x, b.x), math.max(a.x, b.x)
  local y1, y2 = math.min(a.y, b.y), math.max(a.y, b.y)
  local z1, z2 = math.min(a.z, b.z), math.max(a.z, b.z)
  local out = {}
  for y = y1, y2 do
    for z = z1, z2 do
      for x = x1, x2 do
        if not hollow or x == x1 or x == x2 or y == y1 or y == y2 or z == z1 or z == z2 then
          out[#out + 1] = cell({x = x, y = y, z = z}, block)
        end
      end
    end
  end
  return M.cells(out)
end

---A line of cells from one point to another, diagonals included: beams, posts, ridges.
---@param from Pos
---@param to Pos
---@param block? string
---@return Cells
function M.line(from, to, block)
  local a, b = where(from), where(to)
  local steps = math.max(1, math.abs(b.x - a.x), math.abs(b.y - a.y), math.abs(b.z - a.z))
  local seen, out = {}, {}
  for i = 0, steps do
    local p = {
      x = math.floor(a.x + (b.x - a.x) * i / steps + 0.5),
      y = math.floor(a.y + (b.y - a.y) * i / steps + 0.5),
      z = math.floor(a.z + (b.z - a.z) * i / steps + 0.5),
    }
    if not seen[key(p)] then
      seen[key(p)] = true
      out[#out + 1] = cell(p, block)
    end
  end
  return M.cells(out)
end

---A cylinder standing on its bottom centre: towers, wells, round rooms; only its wall when hollow.
---@param base Pos
---@param radius integer
---@param height integer
---@param block? string
---@param hollow? boolean
---@return Cells
function M.cylinder(base, radius, height, block, hollow)
  local c = where(base)
  local outer, inner = (radius + 0.5) ^ 2, (radius - 0.5) ^ 2
  local out = {}
  for y = c.y, c.y + math.max(1, height) - 1 do
    for dz = -radius, radius do
      for dx = -radius, radius do
        local d = dx * dx + dz * dz
        if d <= outer and not (hollow and d < inner) then
          out[#out + 1] = cell({x = c.x + dx, y = y, z = c.z + dz}, block)
        end
      end
    end
  end
  return M.cells(out)
end

---A sphere around its centre: domes, globes; only its shell when hollow.
---@param center Pos
---@param radius integer
---@param block? string
---@param hollow? boolean
---@return Cells
function M.sphere(center, radius, block, hollow)
  local c = where(center)
  local outer, inner = (radius + 0.5) ^ 2, (radius - 0.5) ^ 2
  local out = {}
  for dy = -radius, radius do
    for dz = -radius, radius do
      for dx = -radius, radius do
        local d = dx * dx + dy * dy + dz * dz
        if d <= outer and not (hollow and d < inner) then
          out[#out + 1] = cell({x = c.x + dx, y = c.y + dy, z = c.z + dz}, block)
        end
      end
    end
  end
  return M.cells(out)
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
          raise("bad_argument", "layer: row " .. r .. " has '" .. ch .. "', which the legend does not name",
              "add [\"" .. ch .. "\"] = \"<block>\" to the legend")
        end
        out[#out + 1] = cell({x = o.x + col - 1, y = o.y, z = o.z + r - 1}, block)
      end
    end
  end
  return M.cells(out)
end

return M
