-- What a scan found: keep the blocks of a cluster you want, take out the ones you don't.
local M = {}

---@class Cluster
M.Cluster = {}
M.Cluster.__index = M.Cluster

local function key(p)
  return p.x .. "," .. p.y .. "," .. p.z
end

---A Cluster of these blocks, nearest first as you give them: its nearest is the first, its count how many.
---@param blocks Block[]
---@return Cluster
function M.cluster(blocks)
  return setmetatable({blocks = blocks, nearest = blocks[1], count = #blocks}, M.Cluster)
end

---The blocks of this cluster that keep(block) says yes to, as a cluster (nearest first, as before).
---@param keep fun(block: Block): boolean
---@return Cluster
function M.Cluster:filter(keep)
  local out = {}
  for _, b in ipairs(self.blocks) do
    if keep(b) then
      out[#out + 1] = b
    end
  end
  return M.cluster(out)
end

---This cluster without the cells other has: Cells, a list of Blocks or Pos, or one Pos or Block.
---@param other Cells|(Block|Pos)[]|Pos|Block
---@return Cluster
function M.Cluster:minus(other)
  local theirs = {}
  if other.x or other.pos then
    theirs[key(other.pos or other)] = true
  else
    for _, c in ipairs(other) do
      theirs[key(c.pos or c)] = true
    end
  end
  local out = {}
  for _, b in ipairs(self.blocks) do
    if not theirs[key(b.pos)] then
      out[#out + 1] = b
    end
  end
  return M.cluster(out)
end

return M
