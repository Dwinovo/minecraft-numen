# 模型接入的决策记录

`ai` 模块(传输、厂家适配、会话)与客户端的模型档案。现状与细节看代码与 `docs/agent-loop.md`。

| 编号 | 日期 | 决定 | 状态 |
|---|---|---|---|
| [0001](0001-zero-dependency-stack.md) | 05-15 | 不用官方 SDK,自己写一层零依赖的接入 | 已接受 |
| [0002](0002-wire-format-adapter.md) | 05-15 | 一层线格式适配器,不认识的字段默认原样保留 | 已接受 |
| [0003](0003-provider-catalog-as-data.md) | 06-19 | 厂家目录是数据,只有行为不同才写代码 | 已被 0012 取代 |
| [0004](0004-network-resilience.md) | 07-14 | 网络韧性照抄官方 SDK 的共识 | 已接受 |
| [0005](0005-per-companion-profiles.md) | 07-14 | 每只同伴一份模型档案,空库起步、不兜底 | 已接受 |
| [0006](0006-prompt-cache-first.md) | 07-16 | 前缀缓存是一等公民,用量按各家口径记 | 被 0016 修订 |
| [0007](0007-pure-jvm-ai-module.md) | 08-02 | 模型接入独立成纯 JVM 模块 `ai` | 已接受 |
| [0008](0008-thinking-dialects.md) | 08-02 | 思考的方言:出向按开关的形态分族,入向锁定字段 | 被 0014 修订 |
| [0009](0009-three-protocols.md) | 08-02 / 09-20 | 三种线协议:Chat Completions、Anthropic Messages、OpenAI Responses | 被 0017 修订 |
| [0010](0010-turn-origin.md) | 09-20 | 回合记住出处,换模型后不回塞上一家的私货 | 已接受 |
| [0011](0011-protocol-view.md) | 09 | 消息配对只在 ProtocolView 一处修 | 已接受 |
| [0012](0012-models-dev-upstream.md) | 10-10 | 厂家与型号目录改用 models.dev 做上游 | 已接受;厂家 `/models` 一级待做 |
| [0013](0013-pi-as-reference.md) | 09-20 / 10-15 | 拿不准的协议细节照 pi-mono 做 | 已接受 |
| [0014](0014-thinking-plan.md) | 10-14 | 每一档思考实际发什么,只由端点一处算出 | 已接受 |
| [0015](0015-stream-success-criterion.md) | 10-15 | 流算不算成功,只在一处判 | 已接受 |
| [0016](0016-prompt-cache-all-protocols.md) | 10-15 | 三种协议都把前缀缓存做对 | 已接受 |
| [0017](0017-openai-on-responses.md) | 10-15 | OpenAI 官方缺省走 Responses,协议一律按目录推 | 已接受;未用真 key 验证 |
| [0018](0018-hide-deprecated-models.md) | 10-14 | 已弃用的型号读目录时就丢掉,不提示 | 已接受 |

## 沿着一件事看

- **依赖:** 0001 官方 SDK → 撤掉,零依赖自写。
- **厂家怎么加:** 0003 每家一个类 → 样板类数据化、协议进数据 → 0012 整个目录交给 models.dev。
- **思考:** 0008 按形态分族、界面一个下拉 → 0012 形态按型号由上游给 → 0014 每档发什么由端点一处算出。
- **协议:** 0009 Chat → Anthropic → Responses;差异都在适配器里,序列合法由 0011 一处保证,流成功与否由 0015 一处判;0017 OpenAI 改走 Responses。
- **缓存:** 0006 Anthropic 显式断点 → 0016 三种协议都做对。
- **参照:** 0013 拿不准照 pi。
