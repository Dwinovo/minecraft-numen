-- The window you have open: a Window's methods are the numen.gui functions, so w:put("minecraft:coal", 8) is
-- numen.gui.put("minecraft:coal", 8). They act on the window open now, the one numen.gui.view() reads.
local M = {}

---@class Window
M.Window = {}
M.Window.__index = M.Window

local function id(item)
  return item:find(":") and item or "minecraft:" .. item
end

---How many of item the window's own side holds (its container slots, not your inventory), counted in the open
---window now.
---@param item string
---@return integer
function M.count(item)
  local want, n = id(item), 0
  for _, s in ipairs(numen.gui.view().slots) do
    if s.side == "container" and s.item == want then
      n = n + s.count
    end
  end
  return n
end

---Put items of one kind from your inventory into this window: all of them, or count. Returns how many went in.
---@param item string
---@param count? integer
---@return integer
function M.Window:put(item, count)
  if count then
    return numen.gui.put(item, count)
  end
  return numen.gui.put(item)
end

---Take items of one kind out of this window into your inventory: all of them, or count. Returns how many came out.
---@param item string
---@param count? integer
---@return integer
function M.Window:take(item, count)
  if count then
    return numen.gui.take(item, count)
  end
  return numen.gui.take(item)
end

---Move items from slot from to slot to of this window; opts.count moves part of the stack.
---@param from integer
---@param to integer
---@param opts? table
function M.Window:move(from, to, opts)
  if opts then
    return numen.gui.move(from, to, opts)
  end
  return numen.gui.move(from, to)
end

---Shift-click a slot of this window: its whole stack goes to the other side.
---@param slot integer
function M.Window:quick(slot)
  return numen.gui.quick(slot)
end

---Close this window.
function M.Window:close()
  return numen.gui.close()
end

return M
