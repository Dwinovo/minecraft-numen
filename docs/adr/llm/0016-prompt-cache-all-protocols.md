# 0016 三种协议都把前缀缓存做对

- 状态:已接受
- 日期:2026-10-15
- 修订:[0006](0006-prompt-cache-first.md)
- 提交:`ed8d4954c`

## 背景

0006 只给 Anthropic 直连打了缓存断点。对照 pi 后发现:OpenRouter 上的 `anthropic/*` 型号走 Chat,从没缓存过;
Responses 没传缓存键;Anthropic 的用量先归一成 OpenAI 字段再拆回来,某些返回会把缓存计数清零,Kimi 的用量整轮丢失。

## 决定

- **Anthropic:** 断点打在最后一个工具、system、消息里最后两个可放的块。比 pi 多一个,因为每轮末尾都追加一段现算的运行状态,
  pi 的"最后一块"下一轮就变,倒数第二块才是历史末尾。只用 5 分钟缓存,不提供 1 小时(写入两倍价,没有需求)。
- **Chat:** OpenRouter 且型号以 `anthropic/` 开头时显式打断点,位置同上;由 `Compats.resolve` 一处认出,档案可覆盖。
  DeepSeek、Kimi 等自动缓存的厂家不做事。
- **Responses:** 传 `prompt_cache_key`,取同伴 UUID。
- **用量:** 各协议按自己的原始字段记进四元,不再先归一。
- `CachePrefixTest` 守着:同一段对话多走一步,工具表、系统提示、已有消息逐字不变。

## 后果

- 三种协议、包括经 OpenRouter 用 Claude,重复上下文都能命中缓存。
- 往前缀里塞每轮会变的东西会直接让测试失败。
