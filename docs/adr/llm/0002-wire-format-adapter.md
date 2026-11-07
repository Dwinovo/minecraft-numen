# 0002 一层线格式适配器,不认识的字段默认原样保留

- 状态:已接受
- 日期:2026-05-15;08-11 补工具参数的合法性
- 提交:`b3cbfd833`、`c1899b352`、`13f02eb1f`

## 背景

"OpenAI 兼容"的厂家大多 95% 兼容、5% 私有扩展:DeepSeek 多一个 `reasoning_content` 且要求下一轮原样带回,聚合网关多一个
`reasoning_details` 数组。智能体循环不该知道这些。

## 决定

- 接口 `LlmProvider` 只做翻译:内部类型(`AssistantTurn`、`LlmToolCall`)↔ 一家的请求与响应 JSON。
  不做网络(`HttpLlmTransport`)、不管会话(`ConvoState`)、不管循环。
- **不认识的字段默认保留**:照 LiteLLM 的做法(它在 OpenAI 基类上统一保留 `provider_specific_fields`),
  响应里非标准的消息字段、流里非标准的增量字段都收进 `AssistantTurn.extras`,下一轮原样回传。
  DeepSeek 因此不需要专门的代码。
- 工具参数的合法性收进类型:`LlmToolCall` 构造时保证参数是合法 JSON 对象,解析不了按无参处理并留日志。
  这段字符串会进历史、每轮回传、还落盘,漏一次就是每轮 400、重启照旧。

## 后果

- 循环与会话对厂家无感;新增一种协议就是新写一个 `LlmProvider` 实现(llm 0009)。
- `extras` 回传要防"换了模型后把上一家的私货塞给下一家"(llm 0010)。
