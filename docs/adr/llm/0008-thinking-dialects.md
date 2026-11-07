# 0008 思考的方言:出向按开关的形态分族,入向锁定字段

- 状态:已接受;被 [0014](0014-thinking-plan.md) 修订(形态成了 Chat 兼容项的 `thinkingFormat`,档位由 [0012](0012-models-dev-upstream.md) 的档位表给)
- 日期:2026-08-02;08-03 界面收敛;10-12 `<think>` 归入思考通道
- 提交:`f4278064c`、`8d34c7acb`、`019672ba7`、`59a6de44c`

## 背景

"开不开思考、想多深"每家写法不同,原来只对 `reasoning_effort` 形态真正生效,国内厂家的思考开关一律空转。
返回的思考内容也散在不同字段里。

## 决定

- **出向按"开关长什么样"分族,不按厂家分:** `effort`(顶层 `reasoning_effort`)、`effort-nested`(`reasoning: {effort}`,聚合网关)、
  `thinking-type`(`thinking: {type: enabled|disabled}`)、`enable-bool`(`enable_thinking`)、`budget`(`thinking.budget_tokens`,
  档位映射成预算并自动抬 `max_tokens`)、`none`(常开或不可控,不发参数)。目录里每家选一种,新厂家只是选一个已有形态。
- **"自动"什么都不发:** 只有玩家选了具体档位才写参数,保护不支持思考的型号不被未知参数打 400。
- **入向:** `reasoning_content`/`reasoning`/`reasoning_text` 里第一个非空的在流内锁定,思考文本进 `AssistantTurn.reasoning`(展示),
  回传照旧走 `extras`;正文里夹的 `<think>…</think>` 也归入思考通道(10-12)。
- **界面只有一个下拉(08-03):** 档位型出"自动/关闭/低/中/高",开关型出"自动/开启/关闭",常开型隐藏。双控件(开关 × 强度)连设计者都绕晕了;
  主流客户端都是单控件。教训:后端参数结构不是界面结构。

## 后果

- 国内厂家的思考开关第一次真正生效。
- "这家用哪种形态"写在手工目录里,型号一多就跟不上:同一家不同型号形态不同(例如开关 + 档位并存)。0012 改由上游数据给出。
