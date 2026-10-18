-- Waiting for something to come true: look, and if it is not there yet, numen.time.wait a little and look again.
local M = {}

---Wait until ready() returns true, checking every opts.every seconds (default 1). Raises timeout after opts.timeout
---seconds (default 60) without it; a stopped wait raises interrupted, as numen.time.wait does. Returns the seconds it
---took.
---@param ready fun(): boolean
---@param opts? {every?: number, timeout?: number}
---@return number
function M.wait_until(ready, opts)
  opts = opts or {}
  local every, timeout, waited = opts.every or 1, opts.timeout or 60, 0
  while not ready() do
    if waited >= timeout then
      raise("timeout", "still not ready after " .. waited .. " seconds")
    end
    waited = waited + numen.time.wait(every)
  end
  return waited
end

return M
