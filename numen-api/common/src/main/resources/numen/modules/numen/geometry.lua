-- Positions: the arithmetic every Pos has, wherever it comes from (a scan, a Block, an Entity, the program itself).
local M = {}

---@class Pos
M.Pos = {}
M.Pos.__index = M.Pos

-- the position of a cell: a Block's pos, or the Pos itself
local function where(c)
  return c.pos or c
end

---A Pos at x, y, z. Every Pos can be added (p + q), subtracted (p - q) and compared (p == q).
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

return M
